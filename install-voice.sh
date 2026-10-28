#!/usr/bin/env bash
# Agent setup: ./install-voice.sh <tv-ip> --key-file /path/to/key --enable-gemini
# Installing/provisioning a key leaves the current assistant unchanged. Enabling requires explicit opt-in.
set -euo pipefail
usage() {
  cat <<'USAGE'
Usage: bash install-voice.sh <tv-ip> [--key-file FILE] [--apk FILE] [--enable-gemini]

Install only:     bash install-voice.sh <tv-ip>
Install + Gemini: bash install-voice.sh <tv-ip> --key-file /path/to/key --enable-gemini
Update Gemini:   bash install-voice.sh <tv-ip> --enable-gemini

Without --enable-gemini, keep the current assistant (including Google Assistant).
--key-file provisions a key without enabling Gemini by itself. Omit it to reuse a stored key.
--apk selects a specific APK; otherwise use material-tv-launcher.apk beside this script or in build/.
The key file must contain only the key. Never pass the key itself on the command line.
Legacy positional arguments [key-file] [apk] remain supported.
USAGE
}
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then usage; exit 0; fi
[[ $# -gt 0 && "$1" != -* ]] || { usage >&2; exit 1; }
IP="$1"
shift
ENABLE=false
KEY_FILE=""
APK=""
NAMED=false
KEY_SET=false
APK_SET=false
ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --enable-gemini) ENABLE=true; shift ;;
    --key-file|--apk)
      [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || { echo "$1 needs a file path." >&2; exit 1; }
      NAMED=true
      if [[ "$1" == --key-file ]]; then
        [[ "$KEY_SET" == false ]] || { echo "Use --key-file only once." >&2; exit 1; }
        KEY_FILE="$2"; KEY_SET=true
      else
        [[ "$APK_SET" == false ]] || { echo "Use --apk only once." >&2; exit 1; }
        APK="$2"; APK_SET=true
      fi
      shift 2 ;;
    --help|-h) usage; exit 0 ;;
    -*) echo "Unknown option. Run install-voice.sh --help." >&2; exit 1 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
[[ ${#ARGS[@]} -le 2 ]] || { echo "Too many arguments" >&2; exit 1; }
if [[ ${#ARGS[@]} -gt 0 ]]; then
  [[ "$NAMED" == false ]] || { echo "Use either named or positional file arguments, not both." >&2; exit 1; }
  KEY_FILE="${ARGS[0]:-}"
  APK="${ARGS[1]:-}"
fi
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=com.rampey.materialtv
TARGET="$IP:5555"
KEY_PATH="/sdcard/Android/data/$PKG/files/openrouter_key"
S() { adb -s "$TARGET" shell "$@" | tr -d '\r'; }

command -v adb >/dev/null || { echo "ADB is required. Install Android platform-tools first." >&2; exit 1; }
if [[ "$ENABLE" == true || -n "$KEY_FILE" ]]; then
  command -v python3 >/dev/null || { echo "Python 3 is required to provision or enable Gemini." >&2; exit 1; }
fi
if [[ "$ENABLE" == true && (! -f "$HERE/assistant.sh" || ! -f "$HERE/scripts/assistant-settings.py") ]]; then
  echo "Missing assistant setup files. Keep assistant.sh and scripts/assistant-settings.py beside the installer." >&2
  exit 1
fi
if [[ -n "$KEY_FILE" ]]; then
  python3 - "$KEY_FILE" <<'PY'
import sys
from pathlib import Path
try:
    key = Path(sys.argv[1]).read_text(encoding="utf-8").strip()
    valid = 10 < len(key) < 4096 and key.isascii() and not any(c.isspace() for c in key)
except (OSError, UnicodeError):
    valid = False
if not valid:
    sys.exit("Key file must contain one nonempty OpenRouter key. Nothing was installed or changed.")
PY
fi
# Pass an empty APK path through to install.sh so release-folder and build/ discovery both work.
bash "$HERE/install.sh" "$IP" "$APK"
if [[ -n "$KEY_FILE" ]]; then
  S mkdir -p "/sdcard/Android/data/$PKG/files"
  # Do not replace a working key if transfer fails; no key contents appear in output.
  adb -s "$TARGET" push "$KEY_FILE" "$KEY_PATH.pending" >/dev/null 2>&1
  S mv "$KEY_PATH.pending" "$KEY_PATH"
fi
if [[ "$ENABLE" == true ]]; then
  bash "$HERE/assistant.sh" "$IP" enable
else
  echo "Assistant selection unchanged. Add a key, then use Home → Settings → Voice assistant to enable Gemini."
  echo "Or run: bash assistant.sh $IP enable"
fi
S am start -n "$PKG/.HomeActivity" >/dev/null
echo "Check setup: bash assistant.sh $IP status"
