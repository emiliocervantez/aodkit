#!/usr/bin/env bash
# Installs the aodkit APK on the connected phone, keeping its data and permissions.
#
# Usage: scripts/install.sh [debug|release] [--build]
#   debug     (default) the debug APK
#   release   the release APK; it must be signed (see scripts/build.sh)
#   --build   build the variant first
#
# Debug and release builds are signed with different keys. Android refuses to replace one with the
# other (INSTALL_FAILED_UPDATE_INCOMPATIBLE); switching needs `adb uninstall app.aodkit` first, which
# wipes aodkit's settings and granted permissions.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
variant=debug
build=0

for arg in "$@"; do
    case "$arg" in
        debug | release) variant="$arg" ;;
        -b | --build) build=1 ;;
        -h | --help) sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Usage: $0 [debug|release] [--build]" >&2; exit 2 ;;
    esac
done

find_adb() {
    local dirs=() dir exe
    [ -n "${ANDROID_HOME:-}" ] && dirs+=("$ANDROID_HOME")
    [ -n "${ANDROID_SDK_ROOT:-}" ] && dirs+=("$ANDROID_SDK_ROOT")
    if [ -f "$root/local.properties" ]; then
        # local.properties escapes ':' and '\' (sdk.dir=C\:\\android\\sdk).
        dir="$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" | head -n 1 | tr -d '\r' | sed -e 's/\\:/:/g' -e 's/\\\\/\\/g')"
        [ -n "$dir" ] && dirs+=("$dir")
    fi
    for dir in ${dirs[@]+"${dirs[@]}"}; do
        command -v cygpath >/dev/null 2>&1 && dir="$(cygpath -u "$dir")"
        for exe in adb adb.exe; do
            if [ -x "$dir/platform-tools/$exe" ]; then
                echo "$dir/platform-tools/$exe"
                return 0
            fi
        done
    done
    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return 0
    fi
    echo "adb not found: set ANDROID_HOME, sdk.dir in local.properties, or put adb on PATH." >&2
    return 1
}

if [ "$build" -eq 1 ]; then
    bash "$root/scripts/build.sh" "$variant"
fi

apk="$root/app/build/outputs/apk/$variant/app-$variant.apk"
if [ ! -f "$apk" ]; then
    if [ "$variant" = release ] && [ -f "$root/app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
        echo "The release APK is unsigned and cannot be installed. Set up keystore.properties (see scripts/build.sh) and rebuild." >&2
    else
        echo "No $variant APK at $apk. Run scripts/build.sh $variant, or pass --build." >&2
    fi
    exit 1
fi

adb="$(find_adb)"
# adb on Windows ends lines with CRLF.
devices="$("$adb" devices | tr -d '\r' | tail -n +2 | grep -c $'\tdevice$' || true)"
if [ "$devices" -eq 0 ]; then
    echo "No phone connected (adb devices lists none, or it is unauthorised)." >&2
    exit 1
fi
if [ "$devices" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
    echo "Several devices are connected; set ANDROID_SERIAL to the one to use." >&2
    exit 1
fi

echo "Installing $apk"
status=0
output="$("$adb" install -r "$apk" 2>&1)" || status=$?
echo "$output"
if [ "$status" -ne 0 ]; then
    if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$output"; then
        echo "The installed aodkit is signed with a different key (debug vs release). Run \"adb uninstall app.aodkit\" first; this wipes its settings and permissions." >&2
    else
        echo "adb install failed with exit code $status." >&2
    fi
    exit 1
fi
