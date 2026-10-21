#!/usr/bin/env bash
# Pure-Java regression cases shipped with the pinned Android Use source.
set -euo pipefail
cd "$(dirname "$0")/../.."
if [[ -z "${JUNIT_CP:-}" ]]; then
  JUNIT=("$HOME"/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/*/*.jar)
  HAMCREST=("$HOME"/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/*/*.jar)
  if [[ ! -f "${JUNIT[0]}" || ! -f "${HAMCREST[0]}" ]]; then
    echo "Set JUNIT_CP to junit-4.13.2.jar:hamcrest-core-1.3.jar" >&2
    exit 1
  fi
  JUNIT_CP="${JUNIT[0]}:${HAMCREST[0]}"
fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac --release 17 -cp "$JUNIT_CP" -d "$OUT" \
  third_party/android-use/com/rampey/materialtv/*.java tests/android-use/upstream/*.java
java -cp "$OUT:$JUNIT_CP" org.junit.runner.JUnitCore \
  com.rampey.materialtv.SemanticCompilerTest com.rampey.materialtv.TargetsTest
