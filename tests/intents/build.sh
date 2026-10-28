#!/usr/bin/env bash
# Build a developer-only instrumentation APK after build.sh. Sign with the launcher's keystore.
set -euo pipefail
cd "$(dirname "$0")/../.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/${BUILD_TOOLS:-34.0.0}"
JAR="$SDK/platforms/android-34/android.jar"
OUT=build/intent-tests
mkdir -p "$OUT/classes" "$OUT/dex"
"$BT/aapt2" link -I "$JAR" --manifest tests/intents/device/AndroidManifest.xml -o "$OUT/base.apk"
javac -nowarn --release 17 -cp "$JAR:build/classes" -d "$OUT/classes" tests/intents/device/VoiceIntentTest.java
"$BT/d8" --min-api 31 --lib "$JAR" --classpath build/classes --output "$OUT/dex" "$OUT/classes/com/rampey/materialtv/VoiceIntentTest.class"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "${KEYSTORE:-$HOME/.android/debug.keystore}" --ks-key-alias "${KEY_ALIAS:-androiddebugkey}" --ks-pass "pass:${KEYSTORE_PASS:-android}" --key-pass "pass:${KEYSTORE_PASS:-android}" --out "$OUT/intent-tests.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/intent-tests.apk"
