RELEASE_DEX_PATH := `realpath -m dist/dex/release/classes.dex`
DEBUG_DEX_PATH := `realpath -m dist/dex/debug/classes.dex`

RESOURCES_DIR := "resources"
RESOURCES_ZIP := `realpath -m dist/resources.zip`

BADGES_SDK_REPO := "n08i40k/badges-sdk"
BADGES_SDK_VERSION := "1.1.0"
BADGES_SDK_LOADER := `realpath -m build/badges-sdk/badges-sdk-loader.py`
BADGES_SDK_COMPAT_AAR := `realpath -m libs/badges-sdk-compat.aar`

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

# download the pinned badges-sdk release assets (both are gitignored)
badges-sdk: (_require "curl")
    #!/usr/bin/env bash
    set -euo pipefail

    base='https://github.com/{{ BADGES_SDK_REPO }}/releases/download/{{ BADGES_SDK_VERSION }}'

    mkdir -p "$(dirname '{{ BADGES_SDK_COMPAT_AAR }}')" "$(dirname '{{ BADGES_SDK_LOADER }}')"
    curl -fsSL "$base/badges-sdk-compat.aar" -o '{{ BADGES_SDK_COMPAT_AAR }}'
    curl -fsSL "$base/badges-sdk-loader.py" -o '{{ BADGES_SDK_LOADER }}'

    echo "fetched badges-sdk {{ BADGES_SDK_VERSION }}"

# put a locally built compat AAR into libs/ instead of the released one (dev; see 'just compat' in the SDK repo)
badges-sdk-local PATH_TO_AAR:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p "$(dirname '{{ BADGES_SDK_COMPAT_AAR }}')"
    cp '{{ PATH_TO_AAR }}' '{{ BADGES_SDK_COMPAT_AAR }}'

# embed all embedable files
embed DEX_PATH=RELEASE_DEX_PATH OUTPUT=DIST_PY SOURCE=PLUGIN_PY: (_require "uv") resources
    #!/usr/bin/env bash
    set -euo pipefail

    mkdir -p "$(dirname '{{ OUTPUT }}')"

    badges_sdk_args=()
    if [ -s '{{ BADGES_SDK_LOADER }}' ]; then
        badges_sdk_args=(--badges-sdk '{{ BADGES_SDK_LOADER }}')
    else
        echo "badges-sdk is not fetched, embedding without it (run 'just badges-sdk' for a release build)" >&2
    fi

    uv run python tools/embed_assets.py \
        --dex '{{ DEX_PATH }}' \
        --resources '{{ RESOURCES_ZIP }}' \
        "${badges_sdk_args[@]}" \
        '{{ SOURCE }}' '{{ OUTPUT }}'

# optionally fetch badges-sdk, prepare release, build dex, embed all embedable resources
ci-release VERSION OUTPUT=DIST_PLUGIN *FLAGS: (_require "java" "uv")
    #!/usr/bin/env bash
    set -euo pipefail

    offline=0
    for flag in {{ FLAGS }}; do
        case "$flag" in
            --offline) offline=1 ;;
            *) echo "unknown flag: $flag" >&2; exit 1 ;;
        esac
    done

    if [ "$offline" -eq 0 ]; then
        just badges-sdk
    else
        for asset in '{{ BADGES_SDK_COMPAT_AAR }}' '{{ BADGES_SDK_LOADER }}'; do
            if [ ! -s "$asset" ]; then
                echo "--offline needs badges-sdk {{ BADGES_SDK_VERSION }} fetched already: $asset is missing (run 'just badges-sdk' once)" >&2
                exit 1
            fi
        done
    fi

    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT

    cp '{{ PLUGIN_PY }}' "$tmp/{{ file_name(PLUGIN_PY) }}"
    cp pyproject.toml "$tmp/pyproject.toml"

    uv run python scripts/prepare_release.py \
        --version '{{ VERSION }}' \
        --plugin-file "$tmp/{{ file_name(PLUGIN_PY) }}" \
        --pyproject-file "$tmp/pyproject.toml"

    ./gradlew buildDexRelease
    just embed '{{ RELEASE_DEX_PATH }}' '{{ OUTPUT }}' "$tmp/{{ file_name(PLUGIN_PY) }}"

# watch the plugin source, debug DEX and resources, and live-reload on device via extera dev-sync
watch *ARGS: (_require "uv" "adb")
    uv run python tools/dev_watch.py '{{ PLUGIN_PY }}' '{{ DEBUG_DEX_PATH }}' '{{ RESOURCES_DIR }}' \
        --badges-sdk '{{ BADGES_SDK_LOADER }}' {{ ARGS }}

# generate new Telegram[-compile].jar from updated extera/Ayu-Gram apk
update-apk PATH_TO_APK: (_require "dex2jar" "jbang" "git")
    #!/usr/bin/env bash
    set -veuo pipefail

    # create task temp dir
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT

    # copy provided apk into temp dir
    cp {{ PATH_TO_APK }} "$tmp/Telegram.apk"

    # convert apk to jar
    dex2jar -f -o "$tmp/Telegram.jar" "$tmp/Telegram.apk"

    # fix class inheritance and exclude unneded packages
    jbang ./tools/FixTelegramJar.java "$tmp/Telegram.jar" "$tmp/Telegram-compile.jar"

    # copy generated jars
    mkdir -p ./libs/
    cp "$tmp/Telegram.jar" ./libs/Telegram.jar
    cp "$tmp/Telegram-compile.jar" ./libs/Telegram-compile.jar

    # and commit them
    git add -N -- ./libs/Telegram.jar ./libs/Telegram-compile.jar
    git commit -m "chore: bump telegram version" -- ./libs/Telegram.jar ./libs/Telegram-compile.jar

# generate stubs for python
gen-stubs PATH_TO_RT_JAR PATH_TO_ANDROID_JAR: (_require "java2pyi")
    java2pyi {{ PATH_TO_RT_JAR }} {{ PATH_TO_ANDROID_JAR }} ./libs/Telegram.jar -o stubs/
