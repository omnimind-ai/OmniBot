#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

: "${LLMTHU_API_KEY:?LLMTHU_API_KEY must be set for the LLMTHU device-install flow}"

device_serial="${ANDROID_SERIAL:-}"
if [[ -z "$device_serial" ]]; then
    connected_devices=()
    while IFS= read -r connected_device; do
        [[ -n "$connected_device" ]] && connected_devices+=("$connected_device")
    done < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    if [[ "${#connected_devices[@]}" -ne 1 ]]; then
        echo "Connect exactly one authorized Android device or set ANDROID_SERIAL." >&2
        exit 1
    fi
    device_serial="${connected_devices[0]}"
fi

apk_path="app/build/outputs/apk/productionStandard/release/app-production-standard-release.apk"

flutter_root="${FLUTTER_ROOT:-}"
if [[ -z "$flutter_root" ]]; then
    flutter_bin="$(command -v flutter || true)"
    if [[ -z "$flutter_bin" ]]; then
        echo "Set FLUTTER_ROOT or put flutter on PATH." >&2
        exit 1
    fi
    flutter_root="$(cd "$(dirname "$flutter_bin")/.." && pwd)"
fi

FLUTTER_ROOT="$flutter_root" \
    ./gradlew --no-daemon :app:assembleProductionStandardRelease \
    -Ptarget=lib/main_standard.dart \
    -POOB_BUNDLE_LLMTHU_PROVIDER=1 \
    -POMNI_RELEASE_STORE_FILE="${OMNI_RELEASE_STORE_FILE:-$HOME/.android/debug.keystore}" \
    -POMNI_RELEASE_STORE_PWD="${OMNI_RELEASE_STORE_PWD:-android}" \
    -POMNI_RELEASE_KEY_ALIAS="${OMNI_RELEASE_KEY_ALIAS:-androiddebugkey}" \
    -POMNI_RELEASE_KEY_PWD="${OMNI_RELEASE_KEY_PWD:-android}"

adb -s "$device_serial" install -r "$apk_path"
adb -s "$device_serial" shell am force-stop cn.com.omnimind.bot
adb -s "$device_serial" shell monkey -p cn.com.omnimind.bot 1 >/dev/null

echo "Installed the LLMTHU-enabled release APK on $device_serial."
