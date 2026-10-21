#!/usr/bin/env python3
"""Fetch the pinned speech-detector runtime. No ASR or language-model weights."""
import argparse
import hashlib
from pathlib import Path
import shutil
import sys
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
ARCHIVE = "sherpa-onnx-v1.13.8-android.tar.bz2"
URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/" + ARCHIVE
ARCHIVE_SHA = "2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44"
LIBRARIES = {
    "libonnxruntime.so": "7a8f17a707e52e0ac3a026be319272876d9eae61f4bb19585292772d785f7744",
    "libsherpa-onnx-jni.so": "3d6b6daf6d23162dceb1df91ac075a42a09b60fafb5f9400cba22562f10a1dc0",
}
DETECTOR_SHA = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"


def matches(path, digest):
    return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify only; never download")
    args = parser.parse_args()
    detector = ROOT / "assets/voice/silero_vad.onnx"
    if not matches(detector, DETECTOR_SHA):
        sys.exit("Missing or modified Silero detector: restore assets/voice/silero_vad.onnx.")
    destination = ROOT / "jni/lib/armeabi-v7a"
    if all(matches(destination / name, digest) for name, digest in LIBRARIES.items()):
        print("Verified ARMv7 capture runtime and Silero detector.")
        return
    if args.check:
        sys.exit("Missing or modified capture runtime. Run: python3 scripts/prepare-voice.py")

    cache = ROOT / "voice-deps" / ARCHIVE
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not matches(cache, ARCHIVE_SHA):
        temporary = cache.with_suffix(cache.suffix + ".tmp")
        try:
            print("Downloading pinned sherpa-onnx capture runtime (about 46 MB)...")
            with urllib.request.urlopen(URL, timeout=60) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
            if not matches(temporary, ARCHIVE_SHA):
                sys.exit("Runtime download checksum mismatch; nothing installed.")
            temporary.replace(cache)
        finally:
            temporary.unlink(missing_ok=True)
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(cache, "r:bz2") as archive:
        # Extract only the two known files, never arbitrary archive paths or links.
        for name, digest in LIBRARIES.items():
            member = archive.getmember("./jniLibs/armeabi-v7a/" + name)
            if not member.isfile():
                sys.exit("Unexpected runtime archive entry: " + name)
            with archive.extractfile(member) as source:
                data = source.read()
            if hashlib.sha256(data).hexdigest() != digest:
                sys.exit("Runtime library checksum mismatch: " + name)
            temporary = destination / (name + ".tmp")
            temporary.write_bytes(data)
            temporary.replace(destination / name)
    print("Prepared ARMv7 capture runtime. No local speech recognizer or LLM downloaded.")


if __name__ == "__main__":
    main()
