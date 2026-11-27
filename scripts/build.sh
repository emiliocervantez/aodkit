#!/usr/bin/env bash
# Builds aodkit: debug APK, release APK and release AAB (for Google Play).
#
# Usage: scripts/build.sh [all|debug|release|aab]   (default: all)
#
# Release builds are signed only when keystore.properties exists at the repo root; otherwise they are
# built unsigned (an unsigned APK cannot be installed, and Play rejects an unsigned AAB).
#
# One-time signing setup (keep the .jks and the passwords safe: losing them means you can never
# update the app under the same signature):
#
#   keytool -genkeypair -v -keystore aodkit-release.jks -alias aodkit -keyalg RSA -keysize 4096 -validity 10000
#
# then create keystore.properties next to it (both files are git-ignored):
#
#   storeFile=aodkit-release.jks
#   storePassword=...
#   keyAlias=aodkit
#   keyPassword=...
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="${1:-all}"

case "$target" in
    all) selected=(debug release aab) ;;
    debug | release | aab) selected=("$target") ;;
    -h | --help) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Usage: $0 [all|debug|release|aab]" >&2; exit 2 ;;
esac

# Gradle task and output folder (under app/build/outputs) per target.
task_for() {
    case "$1" in
        debug) echo assembleDebug ;;
        release) echo assembleRelease ;;
        aab) echo bundleRelease ;;
    esac
}
dir_for() {
    case "$1" in
        debug) echo apk/debug ;;
        release) echo apk/release ;;
        aab) echo bundle/release ;;
    esac
}

tasks=()
for name in "${selected[@]}"; do tasks+=("$(task_for "$name")"); done

(cd "$root" && ./gradlew --console=plain "${tasks[@]}")

echo
echo "Built:"
for name in "${selected[@]}"; do
    for file in "$root/app/build/outputs/$(dir_for "$name")"/*.apk "$root/app/build/outputs/$(dir_for "$name")"/*.aab; do
        [ -f "$file" ] && echo "  $file"
    done
done

if [ "$target" != debug ] && [ ! -f "$root/keystore.properties" ]; then
    echo "WARNING: No keystore.properties: the release APK/AAB are unsigned. See the header of scripts/build.sh for the signing setup." >&2
fi
