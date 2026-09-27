#!/usr/bin/env bash
# Runs tools/parity/corpus.json on the connected emulator or phone, with the debug APK's own classes
# (the shared modules as the app ships them) on Android's regex engine and Unicode stack.
# Needs ANDROID_HOME and one device visible to adb; builds the debug APK first unless APK is set.
# Usage: tools/parity/run-device.sh      Exit status 1 when any case differs.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
adb="$ANDROID_HOME/platform-tools/adb"
apk="${APK:-}"
if [ -z "$apk" ]; then
  (cd "$root" && ./gradlew -q :android-app:assembleDebug)
  apk="$root/android-app/build/outputs/apk/debug/android-app-debug.apk"
fi
echo "device: $("$adb" shell getprop ro.product.model | tr -d '\r'), API $("$adb" shell getprop ro.build.version.sdk | tr -d '\r')"
"$adb" push "$apk" /data/local/tmp/loupe-parity.apk > /dev/null
"$adb" push "$root/tools/parity/corpus.json" /data/local/tmp/loupe-parity-corpus.json > /dev/null
set +e
out="$("$adb" shell CLASSPATH=/data/local/tmp/loupe-parity.apk app_process /system/bin dev.loupe.parity.ParityCorpusMain /data/local/tmp/loupe-parity-corpus.json)"
status=$?
set -e
printf '%s\n' "$out"
if [ "$status" -ne 0 ]; then exit "$status"; fi
# An older adb reports 0 whatever happened: the summary must also say "0 differ".
printf '%s\n' "$out" | tr -d '\r' | grep -Eq '^parity corpus: [0-9]+ cases, 0 differ$' || { echo "parity corpus: no clean summary from the device" >&2; exit 1; }
