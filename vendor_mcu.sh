#!/bin/bash
# Vendor Google's material-color-utilities (Java, Apache-2.0) into third_party/, dropping the androidx/errorprone
# annotations (the build has no AndroidX). Usage: vendor_mcu.sh <path-to-material-color-utilities-checkout>
set -euo pipefail
SRC="${1:?checkout path}/java"
cd "$(dirname "$0")"
DST=third_party/material-color-utilities
rm -rf "$DST"; mkdir -p "$DST"
cp -R "$SRC"/* "$DST"/
cp "$SRC/../LICENSE" "$DST/LICENSE"
git -C "$SRC/.." log -1 --format='Vendored from https://github.com/material-foundation/material-color-utilities at %H (%cs), java/ only. Apache-2.0 (see LICENSE). Changes: androidx/errorprone annotations removed.' > "$DST/README.md"
find "$DST" -name '*.java' -print0 | xargs -0 sed -i '' \
  -e '/^import androidx\.annotation\./d' \
  -e '/^import com\.google\.errorprone\.annotations\./d' \
  -e 's/@NonNull //g' -e 's/@Nullable //g' -e 's/@CanIgnoreReturnValue//g' \
  -e 's/@NonNull$//' -e 's/@Nullable$//' -e '/^@CheckReturnValue$/d' -e 's/@Var //g'
grep -rn -E '@(NonNull|Nullable|CanIgnoreReturnValue)|androidx|errorprone' "$DST" --include='*.java' | head -5 || true
echo "vendored $(find "$DST" -name '*.java' | wc -l | tr -d ' ') files"
