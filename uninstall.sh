#!/usr/bin/env bash
# Remove the launcher and everything install.sh granted. Google TV's own home screen comes back.
#   ./uninstall.sh <tv-ip>
set -euo pipefail
IP="${1:?usage: ./uninstall.sh <tv-ip>}"
PKG=com.rampey.materialtv
T="$IP:5555"
S() { adb -s "$T" shell "$@" | tr -d '\r'; }

adb connect "$T" >/dev/null
SVC="$PKG/$PKG.HomeKeyService"
CUR="$(S settings get secure enabled_accessibility_services)"
NEW="$(printf '%s' "$CUR" | tr ':' '\n' | grep -v -x "$SVC" | paste -sd: -)"
if [[ -z "$NEW" ]]; then
  S settings put secure enabled_accessibility_services '""'
  S settings put secure accessibility_enabled 0
else
  S settings put secure enabled_accessibility_services "$NEW"
fi
S cmd notification disallow_listener "$PKG/$PKG.MediaListener" || true
adb -s "$T" uninstall "$PKG"
echo "Removed. Press Home to get Google TV back."
