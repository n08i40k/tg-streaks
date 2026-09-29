RELEASE_DEX_PATH := `realpath -m dist/dex/release/classes.dex`
DEBUG_DEX_PATH := `realpath -m dist/dex/debug/classes.dex`

RESOURCES_DIR := "resources"
RESOURCES_ZIP := `realpath -m dist/resources.zip`

PLUGIN_PY := `grep -ls '^__id__ = ' -- *.py | head -n1`
DIST_PY := "dist/" + file_name(PLUGIN_PY)
DIST_PLUGIN := "dist/" + file_stem(PLUGIN_PY) + ".plugin"

PROFILE_PACKAGE := "org.telegram.messenger"
PROFILE_DATA := `realpath -m perf/simpleperf.data`
SIMPLEPERF_DIR := `ls -d "$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)"/ndk/*/simpleperf 2>/dev/null | sort -V | tail -n1`

# fail early if the tools a recipe needs are not installed
[private]
_require +COMMANDS:
    #!/usr/bin/env bash
    set -euo pipefail

    missing=()
    for cmd in {{ COMMANDS }}; do
        command -v "$cmd" >/dev/null 2>&1 || missing+=("$cmd")
    done

    if [ ${#missing[@]} -ne 0 ]; then
        echo "missing required commands: ${missing[*]}" >&2
        exit 1
    fi

# build dex in debug mode
dex: (_require "java")
    ./gradlew buildDexDebug

# generate i18n files (use added lines without full dex rebuild)
loc: (_require "java")
    ./gradlew generateI18n4kFiles

# pack the resources tree into a reproducible resources.zip
resources OUTPUT=RESOURCES_ZIP: (_require "uv")
    uv run python tools/pack_resources.py '{{ RESOURCES_DIR }}' '{{ OUTPUT }}'

# re-encode the media tree in place
optimize-media INPUT=RESOURCES_DIR *FLAGS: (_require "uv" "ffmpeg" "ffprobe")
    uv run python tools/optimize_media.py '{{ INPUT }}' {{ FLAGS }}

# embed all embedable files
embed DEX_PATH=RELEASE_DEX_PATH OUTPUT=DIST_PY SOURCE=PLUGIN_PY: (_require "uv") resources
    #!/usr/bin/env bash
    set -euo pipefail

    mkdir -p "$(dirname '{{ OUTPUT }}')"

    uv run python tools/embed_assets.py \
        --dex '{{ DEX_PATH }}' \
        --resources '{{ RESOURCES_ZIP }}' \
        '{{ SOURCE }}' \
        '{{ OUTPUT }}'

# optionally fetch prepare release, build dex, embed all embedable resources
ci-release BADGES_SDK_DIR VERSION OUTPUT=DIST_PLUGIN *FLAGS: (_require "java" "uv")
    #!/usr/bin/env bash
    set -euo pipefail

    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT

    cp '{{ PLUGIN_PY }}' "$tmp/{{ file_name(PLUGIN_PY) }}"
    cp pyproject.toml "$tmp/pyproject.toml"

    uv run python tools/prepare_release.py \
        --version '{{ VERSION }}' \
        --plugin-file "$tmp/{{ file_name(PLUGIN_PY) }}" \
        --pyproject-file "$tmp/pyproject.toml"

    ./gradlew buildDexRelease -PbadgesSdk.dir={{ BADGES_SDK_DIR }}
    just embed '{{ RELEASE_DEX_PATH }}' '{{ OUTPUT }}' "$tmp/{{ file_name(PLUGIN_PY) }}"

# watch the plugin source, debug DEX and resources, and live-reload on device via extera dev-sync
watch *ARGS: (_require "uv" "adb")
    uv run python tools/dev_watch.py \
        '{{ PLUGIN_PY }}' \
        '{{ DEBUG_DEX_PATH }}' \
        '{{ RESOURCES_DIR }}' \
        {{ ARGS }}

# generate new Telegram.jar from updated extera/Ayu-Gram apk
update-apk PATH_TO_APK: (_require "dex2jar" "git")
    #!/usr/bin/env bash
    set -veuo pipefail

    # create task temp dir
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT

    # copy provided apk into temp dir
    cp {{ PATH_TO_APK }} "$tmp/Telegram.apk"

    # convert apk to jar
    dex2jar -f -o "$tmp/Telegram.jar" "$tmp/Telegram.apk"

    # copy generated jar
    mkdir -p ./libs/
    cp "$tmp/Telegram.jar" ./libs/Telegram.jar

    # and commit it
    git add -N -- ./libs/Telegram.jar
    git commit -m "chore: bump telegram version" -- ./libs/Telegram.jar

# generate stubs for python
gen-stubs PATH_TO_RT_JAR PATH_TO_ANDROID_JAR: (_require "java2pyi")
    java2pyi {{ PATH_TO_RT_JAR }} {{ PATH_TO_ANDROID_JAR }} ./libs/Telegram.jar -o stubs/

[private]
_require-simpleperf:
    #!/usr/bin/env bash
    set -euo pipefail

    if [ -z '{{ SIMPLEPERF_DIR }}' ]; then
        echo "simpleperf not found: install NDK via Android SDK (sdk.dir in local.properties)" >&2
        exit 1
    fi

# sample CPU call stacks of the running client with simpleperf (needs root on device)
profile SECONDS="10" PACKAGE=PROFILE_PACKAGE: (_require "adb")
    #!/usr/bin/env bash
    set -euo pipefail

    pid=$(adb shell pidof '{{ PACKAGE }}') || { echo "{{ PACKAGE }} is not running" >&2; exit 1; }
    remote=/data/local/tmp/tg-streaks-simpleperf.data

    adb shell su -c "simpleperf record -p $pid -g -f 2000 --duration {{ SECONDS }} -o $remote"
    adb shell su -c "chmod 644 $remote"

    mkdir -p "$(dirname '{{ PROFILE_DATA }}')"
    adb pull "$remote" '{{ PROFILE_DATA }}'
    adb shell su -c "rm $remote"

# main-thread plugin frames of the last profile, inclusive time (children) first
profile-report PACKAGE=PROFILE_PACKAGE: _require-simpleperf
    #!/usr/bin/env bash
    set -euo pipefail

    # simpleperf names the main thread by the process cmdline, i.e. the package
    '{{ SIMPLEPERF_DIR }}/bin/linux/x86_64/simpleperf' report \
        -i '{{ PROFILE_DATA }}' \
        --comms '{{ PACKAGE }}' \
        --sort symbol \
        --children \
        2>/dev/null \
        | grep -E '^(Samples|Event count)|Choreographer\.doFrame|n08i40k' \
        | sed 's/ru\.n08i40k\.streaks_shaded\.ru\.n08i40k\.badges/badges/'

# interactive HTML report (flame graphs per thread) of the last profile
profile-html: _require-simpleperf
    python3 '{{ SIMPLEPERF_DIR }}/report_html.py' \
        -i '{{ PROFILE_DATA }}' \
        -o '{{ without_extension(PROFILE_DATA) }}.html'

# convert the last profile for https://profiler.firefox.com (timeline + flame graph)
profile-gecko: _require-simpleperf
    python3 '{{ SIMPLEPERF_DIR }}/gecko_profile_generator.py' \
        -i '{{ PROFILE_DATA }}' \
        | gzip > '{{ without_extension(PROFILE_DATA) }}.json.gz'

# plugin-only slice of the last profile: per-method table, then speedscope over plugin stacks
profile-focus OPEN="true": _require-simpleperf (_require "python3")
    #!/usr/bin/env bash
    set -euo pipefail

    folded='{{ without_extension(PROFILE_DATA) }}.focus.folded'

    python3 tools/profile_focus.py '{{ PROFILE_DATA }}' \
        --simpleperf-dir '{{ SIMPLEPERF_DIR }}' \
        --folded "$folded"

    if [ '{{ OPEN }}' = "true" ]; then
        npx --yes speedscope "$folded"
    fi
