RELEASE_DEX_PATH := `realpath -m dist/dex/release/classes.dex`
DEBUG_DEX_PATH := `realpath -m dist/dex/debug/classes.dex`

RESOURCES_DIR := "resources"
RESOURCES_ZIP := `realpath -m dist/resources.zip`

PLUGIN_PY := `grep -ls '^__id__ = ' -- *.py | head -n1`
DIST_PY := "dist/" + file_name(PLUGIN_PY)
DIST_PLUGIN := "dist/" + file_stem(PLUGIN_PY) + ".plugin"

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
