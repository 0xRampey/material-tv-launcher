#!/usr/bin/env bash
# Install the launcher on an Android TV over ADB and grant what it needs.
#   ./install.sh <tv-ip> [path/to/material-tv-launcher.apk]
# Uses material-tv-launcher.apk next to this script (the release download), else build/material-tv-launcher.apk.
# Before: on the TV, turn on Developer options > USB debugging (and Network debugging if listed), then accept the
# "Allow USB debugging?" prompt the first time this connects.
set -euo pipefail
IP="${1:?usage: ./install.sh <tv-ip> [apk]}"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="${2:-}"
if [[ -z "$APK" ]]; then
  for c in "$HERE/material-tv-launcher.apk" "$HERE/build/material-tv-launcher.apk"; do
    [[ -f "$c" ]] && { APK="$c"; break; }
  done
fi
[[ -n "$APK" && -f "$APK" ]] || { echo "No APK found. Put material-tv-launcher.apk next to install.sh, or pass its path." >&2; exit 1; }
PKG=com.rampey.materialtv
T="$IP:5555"
S() { adb -s "$T" shell "$@" | tr -d '\r'; }

if ! adb connect "$T" | grep -q "connected"; then
  echo "Can't reach the TV at $IP. Check the IP and that USB debugging (or Network debugging) is on." >&2
  exit 1
fi
echo "If the TV asks \"Allow USB debugging?\", accept it. Waiting for the TV ..."
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
for _ in 1 2 3 4 5 6 7 8 9 10; do
  # Read the whole dump first: grep -q/-m1 closing a pipe early makes it fail under pipefail.
  ACT="$(S dumpsys activity activities)"
  if grep ResumedActivity <<<"$ACT" | grep "$PKG" >/dev/null; then
    echo "Done: Material TV Launcher is on screen. Press Home on the remote anytime. Undo with ./uninstall.sh $IP"
    exit 0
  fi
  sleep 1
done
echo "Installed, but the launcher isn't on screen yet. Press Home on the remote. Undo with ./uninstall.sh $IP"
