#!/usr/bin/env bash
# Device-free regressions for the preserved capture and audio-focus behavior.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac --release 17 -d "$OUT" \
  src/com/rampey/materialtv/VoiceFocusLease.java \
  tests/voice/focus/VoiceFocusLeaseTest.java \
  src/com/rampey/voice/CompletedSpeech.java tests/voice/CompletedSpeechTest.java \
  src/com/rampey/voice/StreamingDecoder.java tests/voice/StreamingDecoderTest.java
java -cp "$OUT" com.rampey.materialtv.VoiceFocusLeaseTest
java -cp "$OUT" com.rampey.voice.CompletedSpeechTest
java -cp "$OUT" com.rampey.voice.StreamingDecoderTest
