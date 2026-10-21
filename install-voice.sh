#!/usr/bin/env bash
# Gemini preview: ./install-voice.sh <tv-ip> [key-file] [apk]
# An omitted key-file keeps an existing key on the TV. Key contents are never printed.
set -euo pipefail
IP="${1:?usage: ./install-voice.sh <tv-ip> [key-file] [apk]}"
KEY_FILE="${2:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="${3:-$HERE/build/material-tv-launcher.apk}"
PKG=com.rampey.materialtv
TARGET="$IP:5555"
KEY_PATH="/sdcard/Android/data/$PKG/files/openrouter_key"
S() { adb -s "$TARGET" shell "$@" | tr -d '\r'; }

if [[ -n "$KEY_FILE" && ! -s "$KEY_FILE" ]]; then
  echo "Key file is missing or empty. Pass a file containing your OpenRouter key." >&2
  exit 1
fi
bash "$HERE/install.sh" "$IP" "$APK"
if [[ -n "$KEY_FILE" ]]; then
  S mkdir -p "/sdcard/Android/data/$PKG/files"
  adb -s "$TARGET" push "$KEY_FILE" "$KEY_PATH" >/dev/null 2>&1
fi
if ! S test -s "$KEY_PATH"; then
  echo "Home is installed. Voice needs a key: rerun with your OpenRouter key file." >&2
  exit 1
fi
S pm grant "$PKG" android.permission.RECORD_AUDIO
S cmd role add-role-holder --user 0 android.app.role.ASSISTANT "$PKG" 0
S settings put secure assistant "$PKG/.AssistService"
S settings put secure voice_interaction_service "$PKG/.AssistService"
S settings put secure voice_recognition_service "$PKG/.AssistRecognitionService"
S am start -n "$PKG/.HomeActivity" >/dev/null
if [[ "$(S settings get secure voice_interaction_service)" != "$PKG/.AssistService" ]]; then
  echo "Home is installed, but assistant selection did not stick. Check the TV's assistant settings." >&2
  exit 1
fi
echo "Gemini voice is ready. Press the remote mic or select Ask. Audio and TV context go through OpenRouter using your key."
