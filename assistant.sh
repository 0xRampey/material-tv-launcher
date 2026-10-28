#!/usr/bin/env bash
# Explicit opt-in, restore, or read-only JSON status after installing the launcher.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
exec python3 "$HERE/scripts/assistant-settings.py" "${1:?usage: ./assistant.sh <tv-ip> enable|restore|status}" "${2:?choose enable, restore, or status}"
