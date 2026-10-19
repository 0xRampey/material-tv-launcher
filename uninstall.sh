#!/usr/bin/env bash
# Remove the launcher and everything install.sh granted. The TV's own home screen comes back.
#   ./uninstall.sh <tv-ip>
set -euo pipefail
IP="${1:?usage: ./uninstall.sh <tv-ip>}"
PKG=com.rampey.materialtv
T="$IP:5555"
S() { adb -s "$T" shell "$@" | tr -d '\r'; }

if ! adb connect "$T" | grep -q "connected"; then
  echo "Can't reach the TV at $IP. Check the IP and that USB debugging (or Network debugging) is on." >&2
  exit 1
fi

# Home button: take only our service out of the list, keep any others.
SVC="$PKG/$PKG.HomeKeyService"
CUR="$(S settings get secure enabled_accessibility_services)"
[[ "$CUR" == "null" ]] && CUR=""
NEW="$(printf '%s' "$CUR" | tr ':' '\n' | { grep -v -x "$SVC" || true; } | paste -sd: -)"
if [[ -z "$NEW" ]]; then
  S settings delete secure enabled_accessibility_services >/dev/null
  S settings put secure accessibility_enabled 0
else
  S settings put secure enabled_accessibility_services "$NEW"
fi

# Now Playing access.
S cmd notification disallow_listener "$PKG/$PKG.MediaListener" || true

# Screensaver: if ours was chosen, go back to the TV's default.
if [[ "$(S settings get secure screensaver_components)" == *"$PKG"* ]]; then
  S settings delete secure screensaver_components >/dev/null
fi

adb -s "$T" uninstall "$PKG"
echo "Removed. Press Home to get the TV's own home screen back."
