# Contributing

Thanks for helping. The launcher stays deliberately small: plain Java and no Gradle. The stable release has no
network. This experimental branch adds Gemini voice with a pinned capture runtime and the user's OpenRouter key.
See [preview scope and build instructions](AGENTS.md#gemini-voice-preview).

## 1. Report a tested TV

The most useful contribution. Open an issue with:

- The exact model, Android version and firmware (`adb shell getprop ro.product.model`,
  `ro.build.version.release`, `ro.build.display.id`).
- Whether Home takeover worked, and anything odd.
- A screenshot of Home: `adb exec-out screencap -p > home.png`.

## 2. Improve the launcher

Fits the direction: pinning apps in the dock, icon fallbacks for apps without a TV banner, localization, better D-pad behavior at the edges.

Doesn't fit: recommendation rows, content feeds, ads, telemetry, or network access outside a requested voice turn.

Rules of thumb:

- Home stops animating while another app is on screen; no background polling or inference. The assistant is active
  only during an explicit request and releases capture and focus when dismissed.
- Nothing specific to one person's TV in code: read it from the device (installed apps, usage).
- Use the shape and focus system in `Focus.java` (cards 32 dp, media 20 dp, pill buttons, the focus ring).
- Test on a real TV, not only an emulator, and attach a before/after screenshot to the pull request.

Build with `./build.sh` (see the README).

## Pull requests

- One topic per pull request. Small is fine.
- Say which TV you tested on.
- By contributing you agree your work is released under the Apache License 2.0.
