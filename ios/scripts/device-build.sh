#!/bin/zsh
# Builds Loupe for a physical iPhone and installs it (epic #7 child 9). Needs the owner's Apple
# Developer Team ID in ios/Config/Signing.local.xcconfig (or DEVELOPMENT_TEAM in the environment),
# the device slice of LoupeKit, and an unlocked iPhone connected by cable or on the same network
# with Developer Mode on. Fails with a clear message when any of that is missing.
#
#   ios/scripts/device-build.sh                      # Debug build (Diagnostics included), install, done
#   ios/scripts/device-build.sh --device <UDID>      # pick one of several devices
#   ios/scripts/device-build.sh --no-install         # build only
#   ios/scripts/device-build.sh --diagnostics-archive   # Release archive with Me → Diagnostics, for TestFlight
set -euo pipefail

here=${0:A:h}
ios=${here:h}
repo=${ios:h}
cd "$ios"

die() { print -u2 "device-build: $*"; exit 1; }

device=""; install=1; archive=0
while (( $# )); do
  case $1 in
    --device) device=${2:-}; shift 2 ;;
    --no-install) install=0; shift ;;
    --diagnostics-archive) archive=1; install=0; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) die "unknown argument $1 (see --help)" ;;
  esac
done

# 1. The team: environment first, then the git-ignored xcconfig. Never guessed.
team=${DEVELOPMENT_TEAM:-}
if [[ -z $team && -f Config/Signing.local.xcconfig ]]; then
  team=$(sed -nE 's/^[[:space:]]*DEVELOPMENT_TEAM[[:space:]]*=[[:space:]]*([A-Z0-9]+).*/\1/p' Config/Signing.local.xcconfig | tail -1)
fi
[[ -n $team ]] || die "no Apple Developer Team ID. Copy ios/Config/Signing.local.xcconfig.example to ios/Config/Signing.local.xcconfig and set DEVELOPMENT_TEAM (ios/README.md, 'On your iPhone')."
[[ $team == ABCDE12345 ]] && die "DEVELOPMENT_TEAM is still the example value ABCDE12345; set your own Team ID."
[[ $team =~ '^[A-Z0-9]{10}$' ]] || die "DEVELOPMENT_TEAM '$team' is not a 10-character Team ID."

# 2. LoupeKit must have the device slice.
xcf=$repo/loupe-kit/build/XCFrameworks/debug/LoupeKit.xcframework
[[ -d $xcf/ios-arm64 ]] || die "LoupeKit has no device slice at $xcf/ios-arm64. Run: ios-native/build.sh && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :loupe-kit:assembleLoupeKitDebugXCFramework"

command -v xcodegen >/dev/null || die "xcodegen is not installed (brew install xcodegen)."
xcodegen generate -q

derived=$ios/build/device
if (( archive )); then
  # Release with the Diagnostics screen compiled in (LOUPE_DIAGNOSTICS) and its fixture bundled;
  # the screen shows only under TestFlight. Upload the archive with Xcode's Organizer.
  xcodebuild archive -project Loupe.xcodeproj -scheme Loupe -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$ios/build/Loupe-diagnostics.xcarchive" \
    -allowProvisioningUpdates DEVELOPMENT_TEAM="$team" \
    SWIFT_ACTIVE_COMPILATION_CONDITIONS='$(inherited) LOUPE_DIAGNOSTICS' \
    EXCLUDED_SOURCE_FILE_NAMES=flights-lis-lhr.json
  print "device-build: archive at $ios/build/Loupe-diagnostics.xcarchive (Xcode → Organizer → Distribute → TestFlight)"
  exit 0
fi

# 3. A device to install on (only when installing).
if (( install )) && [[ -z $device ]]; then
  json=$(mktemp)
  xcrun devicectl list devices --json-output "$json" >/dev/null 2>&1 || die "xcrun devicectl could not list devices (Xcode 15+ needed)."
  device=$(/usr/bin/python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))["result"]["devices"]
ok = [x for x in d if x.get("hardwareProperties", {}).get("platform") == "iOS"
      and x.get("connectionProperties", {}).get("tunnelState") != "unavailable"]
print(ok[0]["hardwareProperties"]["udid"] if ok else "")' "$json")
  rm -f "$json"
  [[ -n $device ]] || die "no connected iPhone found. Connect it, unlock it, trust this Mac, and turn on Developer Mode (Settings → Privacy & Security)."
fi

xcodebuild build -project Loupe.xcodeproj -scheme Loupe -configuration Debug \
  -destination 'generic/platform=iOS' -derivedDataPath "$derived" \
  -allowProvisioningUpdates DEVELOPMENT_TEAM="$team"
app=$derived/Build/Products/Debug-iphoneos/Loupe.app
[[ -d $app ]] || die "build finished but $app is missing."
print "device-build: built $app"

if (( install )); then
  xcrun devicectl device install app --device "$device" "$app"
  print "device-build: installed on $device. Open Loupe → Me → Decision model, then Me → Diagnostics."
fi
