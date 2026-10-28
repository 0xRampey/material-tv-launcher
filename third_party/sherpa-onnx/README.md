sherpa-onnx v1.13.8 Java API sources (https://github.com/k2-fsa/sherpa-onnx, Apache-2.0), unchanged except
LibraryLoader.java (loads the APK's native libraries) and removal of trailing whitespace. The native libraries (jni/lib/armeabi-v7a) come from the
release asset sherpa-onnx-v1.13.8-android.tar.bz2 and are not committed.

Run `python3 scripts/prepare-voice.py` from the repo root. The script downloads that exact release, checks its
SHA-256 (`2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44`), and extracts only
`libonnxruntime.so` and `libsherpa-onnx-jni.so` for ARMv7. Individual library hashes are pinned in the script too.
The bundled ONNX Runtime identifies itself as version 1.28.2.

The Gemini preview uses this runtime only for the existing speech endpoint detector. ASR support code remains
inside the preserved capture package, but it is not activated and no ASR or language-model weights are included.
No runtime libraries are downloaded by the TV app.

`assets/voice/silero_vad.onnx` is the 643,854-byte Silero detector from
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx,
SHA-256 `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`.

Bundled notices:

- `assets/licenses/sherpa-onnx-LICENSE.txt`: Apache 2.0, from k2-fsa/sherpa-onnx v1.13.8.
- `assets/licenses/onnxruntime-LICENSE.txt`: MIT, from microsoft/onnxruntime v1.28.2.
- `assets/licenses/onnxruntime-ThirdPartyNotices.txt`: dependencies listed by ONNX Runtime v1.28.2.
- `assets/licenses/silero-vad-LICENSE.txt`: MIT, from snakers4/silero-vad (license blob
  `0bf5e90cac691b999d4a35044f97167d7bbbf0b9`).
