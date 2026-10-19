#!/usr/bin/env bash
# Install the launcher on an Android TV over ADB and grant what it needs.
#   ./install.sh <tv-ip> [path/to/home.apk]
# Before: on the TV, turn on Developer options > USB debugging (and Network debugging if listed), then accept the
# "Allow debugging?" prompt the first time this connects.
set -euo pipefail
IP="${1:?usage: ./install.sh <tv-ip> [apk]}"
APK="${2:-$(dirname "$0")/build/home.apk}"
PKG=com.rampey.materialtv
T="$IP:5555"
S() { adb -s "$T" shell "$@" | tr -d '\r'; }

adb connect "$T" >/dev/null
adb -s "$T" wait-for-device
echo "Installing $APK ..."
adb -s "$T" install -r "$APK"

# Now Playing card: see which app is playing (reads no notifications).
S cmd notification allow_listener "$PKG/$PKG.MediaListener"
# Dock order: per-app screen time, read on the TV only.
S cmd appops set "$PKG" GET_USAGE_STATS allow

# Home button: Google TV ignores other home apps, so a small accessibility service sends the Home button here.
# Added to (not replacing) any accessibility services already on.
SVC="$PKG/$PKG.HomeKeyService"
CUR="$(S settings get secure enabled_accessibility_services)"
if [[ "$CUR" != *"$SVC"* ]]; then
  if [[ -z "$CUR" || "$CUR" == "null" ]]; then NEW="$SVC"; else NEW="$CUR:$SVC"; fi
  S settings put secure enabled_accessibility_services "$NEW"
fi
S settings put secure accessibility_enabled 1

S am start -n "$PKG/.HomeActivity" >/dev/null
echo "Done. Press Home on the remote; undo everything with ./uninstall.sh $IP"
