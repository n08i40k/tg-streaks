RELEASE_DEX_PATH := `realpath -m build/outputs/dex/release/classes.dex`
DEBUG_DEX_PATH := `realpath -m build/outputs/dex/debug/classes.dex`

RESOURCES_DIR := "resources"
RESOURCES_ZIP := `realpath -m build/resources.zip`

# badges-sdk release pinned for this plugin: the compat AAR is a build input and
# the .plugin is embedded into the release build so it can be installed on device
BADGES_SDK_REPO := "n08i40k/badges-sdk"
BADGES_SDK_VERSION := "1.0.1"
BADGES_SDK_PLUGIN := `realpath -m build/badges-sdk/badges-sdk.plugin`
BADGES_SDK_COMPAT_AAR := `realpath -m libs/badges-sdk-compat.aar`

PLUGIN_PY := `grep -ls '^__id__ = ' -- *.py | head -n1`
DIST_PY := "dist/" + file_name(PLUGIN_PY)

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

# download the pinned badges-sdk release assets (both are gitignored)
badges-sdk: (_require "curl")
    #!/usr/bin/env bash
    set -euo pipefail

    base='https://github.com/{{ BADGES_SDK_REPO }}/releases/download/{{ BADGES_SDK_VERSION }}'

    mkdir -p "$(dirname '{{ BADGES_SDK_COMPAT_AAR }}')" "$(dirname '{{ BADGES_SDK_PLUGIN }}')"
    curl -fsSL "$base/badges-sdk-compat.aar" -o '{{ BADGES_SDK_COMPAT_AAR }}'
    curl -fsSL "$base/badges-sdk.plugin" -o '{{ BADGES_SDK_PLUGIN }}'

    echo "fetched badges-sdk {{ BADGES_SDK_VERSION }}"

# put a locally built compat AAR into libs/ instead of the released one (dev; see 'just compat' in the SDK repo)
badges-sdk-local PATH_TO_AAR:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p "$(dirname '{{ BADGES_SDK_COMPAT_AAR }}')"
    cp '{{ PATH_TO_AAR }}' '{{ BADGES_SDK_COMPAT_AAR }}'

# build the release DEX and pack the resources
ci: (_require "java") badges-sdk resources
    ./gradlew buildDexRelease
    cp {{ RELEASE_DEX_PATH }} ./

# embed a DEX (default: release), the resources and the pinned badges-sdk into a
# distributable copy of the plugin .py; without a fetched SDK (dev) it is left out
embed DEX_PATH=RELEASE_DEX_PATH OUTPUT=DIST_PY: (_require "uv") resources
    #!/usr/bin/env bash
    set -euo pipefail

    mkdir -p "$(dirname '{{ OUTPUT }}')"

    badges_sdk_args=()
    if [ -s '{{ BADGES_SDK_PLUGIN }}' ]; then
        badges_sdk_args=(--badges-sdk '{{ BADGES_SDK_PLUGIN }}')
    else
        echo "badges-sdk is not fetched, embedding without it (run 'just badges-sdk' for a release build)" >&2
    fi

    uv run python tools/embed_assets.py \
        --dex '{{ DEX_PATH }}' \
        --resources '{{ RESOURCES_ZIP }}' \
        "${badges_sdk_args[@]}" \
        '{{ PLUGIN_PY }}' '{{ OUTPUT }}'

# watch the plugin source, debug DEX and resources, and live-reload on device via extera dev-sync
watch *ARGS: (_require "uv" "adb")
    uv run python tools/dev_watch.py '{{ PLUGIN_PY }}' '{{ DEBUG_DEX_PATH }}' '{{ RESOURCES_DIR }}' {{ ARGS }}

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
