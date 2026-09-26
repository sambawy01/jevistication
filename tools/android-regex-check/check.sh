#!/usr/bin/env bash
# Compiles every regex literal of the shared modules and :android-app with Android's own regex
# engine (ICU), on a connected emulator or phone. The host JDK accepts patterns ICU rejects, so unit
# tests cannot catch this; see extract.py. Needs JAVA_HOME (JDK 17+), ANDROID_HOME (build-tools
# 35.0.0, or else the newest installed) and one device visible to adb.
# Usage: tools/android-regex-check/check.sh     Exit status 1 when the device rejects a pattern.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
adb="$ANDROID_HOME/platform-tools/adb"
tools="$ANDROID_HOME/build-tools/35.0.0"
[ -x "$tools/d8" ] || tools="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)"
python3 "$here/extract.py" > "$work/patterns.txt"
"$JAVA_HOME/bin/javac" --release 11 -d "$work/classes" "$here/RegexCheck.java"
"$tools/d8" --min-api 29 --output "$work" "$work/classes/RegexCheck.class"
echo "device: $("$adb" shell getprop ro.product.model | tr -d '\r'), API $("$adb" shell getprop ro.build.version.sdk | tr -d '\r')"
"$adb" push "$work/classes.dex" /data/local/tmp/regexcheck.dex > /dev/null
"$adb" push "$work/patterns.txt" /data/local/tmp/regexcheck-patterns.txt > /dev/null
"$adb" shell CLASSPATH=/data/local/tmp/regexcheck.dex app_process /system/bin RegexCheck /data/local/tmp/regexcheck-patterns.txt
