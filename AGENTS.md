# AGENTS.md

Guidance for coding agents (and people) working on Material TV Launcher. Read this before changing the UI.
Installation, build and troubleshooting details also live here; keep the README focused on the launcher.

## What this is

A buttery smooth launcher for Android TV / Google TV: the user's most-used apps, colors that follow the focused app,
Material 3 Expressive design. The stable launcher has no network or AI. This experimental branch adds
Gemini voice through OpenRouter, using the user's own key. It contains no debloat tooling.

## Hard rules

- **Network is limited to an explicit voice request.** This preview sends audio and the documented TV context
  through OpenRouter. During an explicitly requested Android-use task, it also sends the selected app's
  visible accessibility text and action results. Screen images require the separate, default-off voice setting;
  they stay in memory and are sent only during that task. No telemetry uploads, background inference, or bundled API keys.
  Preserve the stable launcher's offline behavior outside the assistant.
- **Nothing user-specific in code.** The dock comes from on-device usage stats and installed apps; HDMI devices come
  from HDMI-CEC names. No hardcoded app lists, TV models, IPs or names.
- **No brand logos we don't own.** Console tiles use Material icons on a tone of the brand hue, never a logo.
- **Home stops animating while another app is on screen.** Stop its loops in `onPause`; resume in `onResume`.
  The assistant may animate only while its window is visible; hiding it cancels capture and pending actions.
- **Smooth on weak TVs** (4 × Cortex-A73 at 1.39 GHz, 2.4 GB RAM): animations run on display frames
  (`postOnAnimation`, time-based), cost only while Home is visible, and must keep 0 janky frames in `dumpsys gfxinfo`.
- **Plain Java Views, no Gradle.** Vendored color utilities plus the pinned capture runtime described below.
  No local ASR or LLM weights, workers, or model downloads in this preview. No Compose.
- **Test on a real TV** with full-resolution screenshots (`adb exec-out screencap -p`) of every focus state you touch.

## Material 3 Expressive, as used here

**Color** (`Palette.java`): `SchemeFidelity` from the focused app banner's seed (`QuantizerCelebi` + `Score`, the
same as Android wallpaper color). Use roles in their intended pairs: `primary/onPrimary`, `surface*` for backgrounds,
`inverseSurface/inverseOnSurface` for focused fills and ink, `primaryContainer` for the background shape. Never
hard-code white/black for focus. Everything tinted cross-fades with the default effects curve on retint.

**Shape** (`Focus.java`): cards 32 dp, media (tiles, pictures, banners, album art) 20 dp, buttons fully round
(pill). The Now Playing card is a pill with a radius of half its measured height. Focused tiles tighten their
corners (×0.62) and the ring stays concentric (radius + ring offset).
The background shape (`CookieDrawable`) morphs only between library shapes: Cookie 4/6/7/9/12, Clover 4- and
8-leaf, Sunny, Soft burst (MaterialShapes names); one per app, morphing on focus.

**Motion** (`Motion.java`): Expressive springs via the spec's fallback curves.
Spatial (movement, scale, shape) may overshoot: fast 350 ms for small components, default 500 ms for larger ones.
Effects (color, opacity) never overshoot: fast 150 ms, default 200 ms. Do not add other curves or durations.

**Focus** (TV): scale 1.10 with the fast spatial curve, a 3 dp ring 6 dp outside the tile that fades in, a glow in
the app's own color (`setOutlineSpotShadowColor`), neighbors shift 8 dp aside (standard button group). Buttons and
nav fill with `inverseSurface` on focus.

**Type**: Google Sans Flex with `'ROND' 0` (never rounded), `opsz` 24 for UI and 48 for display. The focused app
name settles into its weight; the clock's weight follows the time of day and swells briefly on each new minute.

**Components**: top-bar strip (`ButtonGroup`: 30 dp outer radius, 5 dp padding, 4 dp tab gaps, 24 dp tab radii).
The selected page has a `primary/onPrimary` pill; focused tabs use `inverseSurface/inverseOnSurface` and scale 1.05;
inactive tabs are transparent. Fills fade as focus and selection change. Now Playing uses a wavy progress indicator
(`WavyProgress`: moving wave while playing, flat when paused, grows in from a flat line); a morphing shape loader
appears when an app takes over 300 ms to open.

**Status marks go under the tile, never on the artwork**: the playing app gets an animated equalizer
(`EqualizerBadge`), an HDMI device that's on gets a green dot (Material green, tone 80). When the tile is focused,
the name appears beside the mark. The label row has a fixed height so the dock never jumps.

**Screensaver** (`Screensaver`, `ShapeScene`): generated Material You-style wallpapers (rounded library shapes in one
tonal palette, big anchors plus small accents, a left scrim for the clock), hue and brightness from the time of day,
a new arrangement every hour, drawn on display frames.

## Layout

960 × 540 dp canvas (TV baseline), 48 dp side margins (5% overscan safe area). Dock of six 16:9 tiles that exactly
fill the width; Now Playing card 380 dp on the right.

## Commits

Small, one topic each, plain messages. **No `Co-Authored-By` or other AI attribution trailers.**

## Install with a coding agent

The following quick start installs the stable release. For this branch, use [Gemini voice preview](#gemini-voice-preview).

**Install it with your coding agent.** Turn on USB debugging on the TV ([how](#quick-start)), then paste this
into Claude Code, Codex or any agent that can run commands:

```text
Install Material TV Launcher (https://github.com/0xRampey/material-tv-launcher) on my Android TV
by following its Quick start. My TV's IP address is 192.168.1.50.
```

Use your TV's own IP address (Settings → Network & Internet). Rather do it yourself? See [Quick start](#quick-start).

## Quick start

You need a computer on the same Wi-Fi as the TV, with
[ADB](https://developer.android.com/tools/releases/platform-tools) installed (`adb version` works in a terminal).

**1. On the TV**
Settings → System → About (on Android TV: Settings → Device Preferences → About) → press OK on **Android TV OS
build** seven times → go back → Developer options → turn on **USB debugging** (and **Network debugging** if you
see it). Note the TV's IP address under Settings → Network & Internet.

**2. On your computer**
Download the app and the scripts from the
[latest release](https://github.com/0xRampey/material-tv-launcher/releases/latest) into one folder, then run
`install.sh` with your TV's IP address:

```bash
curl -fLO https://github.com/0xRampey/material-tv-launcher/releases/latest/download/material-tv-launcher.apk
curl -fLO https://github.com/0xRampey/material-tv-launcher/releases/latest/download/install.sh
curl -fLO https://github.com/0xRampey/material-tv-launcher/releases/latest/download/uninstall.sh
bash install.sh 192.168.1.50
```

**3. On the TV again**
When the TV shows **Allow USB debugging?**, accept it; the script waits for you. It finishes with
`Done: Material TV Launcher is on screen.` (if it says it isn't on screen yet, press Home). From then on, Home
on the remote brings it up.

Changed your mind? `bash uninstall.sh 192.168.1.50` brings the TV's own home screen back.

## Screensaver setup

<details>
<summary><strong>Turn on the screensaver</strong></summary>

```bash
adb shell settings put secure screensaver_components com.rampey.materialtv/com.rampey.materialtv.Screensaver
```

Before switching, save the current one with `adb shell settings get secure screensaver_components`; to go back,
`put` that value again.
It starts after the TV's idle time (`adb shell settings get system screen_off_timeout`).
</details>

## Privacy

The stable release has no internet permission. This branch has `INTERNET` and `RECORD_AUDIO` for Gemini voice.
Pressing the mic or Ask records a turn and sends the WAV plus TV context through OpenRouter. Context includes
installed apps, most-used app ranking, foreground app, media title/artist and playback state, HDMI inputs,
volume, local time, and the last four voice exchanges held in memory. Android assist-data requests are disabled.
Android-use requests additionally read the selected installed app's accessibility tree,
compact it using the MIT Android Use components, and send visible text plus action results through OpenRouter.
Reads are on demand; events are not retained. Password fields and their descendants are omitted from targets.
The default-off **Share screen images for Android use** voice setting allows per-step screen images when
text controls are insufficient. Images include what is visible on the display, including private app content;
they are resized to at most 1280 pixels wide, sent to Gemini in memory, and never written to disk. Protected
screens or unsupported TV touch gestures may prevent visual control. Text redaction cannot redact image pixels.
The assistant log contains timing, token/cost, action metadata and errors, not audio or
transcripts. The key lives in the app's external files directory; keep USB debugging access trusted.

Inspect permissions with `aapt2 dump permissions build/material-tv-launcher.apk`.

| `install.sh` switches on | Why |
|---|---|
| Accessibility service (*Home button*) | To catch the remote's Home button. It reads no screen content ([code](src/com/rampey/materialtv/HomeKeyService.java)). |
| Usage access | To order the dock by how much you use each app. |
| Notification access (*Now Playing*) | To see which app is playing. It reads no notifications ([code](src/com/rampey/materialtv/MediaListener.java)). |
| Microphone and assistant role (`install-voice.sh`) | To receive the remote mic and capture one request over the current app. |
| Accessibility service (*Android use*, opt-in) | To read screen text and navigate the selected app during an explicit assistant task. |

## Gemini voice preview

This is an unreleased branch based on v0.1.4. It retains the existing remote/TV-mic handoff, endpoint detection,
focus handling and assistant window. `com.rampey.voice` stays an internal source package; there is no separate
library release. The launcher calls it in capture-only mode, then sends audio to `google/gemini-3.5-flash-lite`
through OpenRouter. Gemini does both speech recognition and action planning. The action executor validates
installed app packages, known HDMI inputs, media commands and volume bounds before dispatching Android intents.
Back, Home and dismissal invalidate the turn so a late reply cannot run actions.

Requirements: Android 12+, a TV supporting ARMv7 apps, ADB, an OpenRouter key with credit/access to that model,
and internet on the TV. There is no local speech recognizer, LLM, or automatic offline fallback.

```bash
python3 scripts/prepare-voice.py  # verifies/downloads the pinned native capture runtime
./build.sh
bash install-voice.sh 192.168.1.50 /path/outside/repo/openrouter_key
```

The key file contains only your key. Do not commit it or paste it into logs. On later updates, omit the key-file
argument to retain the key already on the TV. The script installs the APK, grants the existing Home/Now Playing
permissions, grants microphone access and selects this app as the assistant. Press Home, then the remote mic
or Ask. From another app the assistant stays over that app; a command can then open a different app.
Android use is enabled separately in Accessibility → Android use. A navigation turn is bounded to 12 model steps
and 75 seconds; it observes the screen again before each accessibility action, rejects stale or ambiguous text
targets, and checks the result after acting. Optional image actions check the app window and image age before
dispatching a bounded touch gesture; they do not prove the pixels stayed unchanged during inference.
Back/Home or dismissal cancels remaining actions. There is no always-listening wake word. Spotify deep links open search; actual playback
depends on Spotify, so do not claim a successful search guarantees that a song started.

`uninstall.sh` removes the app; if the TV does not restore its previous assistant automatically, select it again
in the TV's assistant settings. Record that setting before trying the preview on a new TV.

### Preview checks

```bash
python3 scripts/prepare-voice.py --check
bash tests/voice/run.sh
bash tests/android-use/run.sh  # JUnit 4.13.2 + Hamcrest 1.3, from JUNIT_CP or a local Gradle cache
./build.sh
```

The Java regressions cover audio-focus cancellation, capture trimming and capture/decoder queue behavior.
The Android Use regressions exercise the vendored semantic compiler and label resolver. The optional
`tests/android-use/device/` instrumentation runner checks cancellation, stale-screen rejection and one-shot
timeout completion on the TV. Build/sign it separately with the same key as the launcher and remove it after
testing; instrumentation exits stop the target process, so rebind the existing accessibility services afterward.
Image-based navigation and physical mic behavior still require a real user trial; compilation and text-only
guard checks do not establish that a particular TV accepts touch gestures or that playback succeeded.
They do not prove physical remote-mic pairing or cloud accuracy. On a TV, check Home, a mic command from Home,
a command from inside an app, and cancellation with Back/Home. Audio tests send data through OpenRouter;
use only recordings the user authorized for that purpose. Keep APK/signature and TV install evidence outside
the repository, and distinguish scripted checks from physical remote tests.

## Build it yourself

<details>
<summary><strong>No Gradle, just the Android SDK</strong></summary>

You need the Android SDK (platform 34, build-tools 34.0.0), JDK 17, and Python 3.9+.

```bash
python3 scripts/prepare-voice.py # pinned capture runtime; checksum checked
./build.sh                    # → build/material-tv-launcher.apk, signed with your Android debug key
bash install-voice.sh 192.168.1.50 /path/outside/repo/openrouter_key
```

Set `KEYSTORE`, `KEYSTORE_PASS` and `KEY_ALIAS` to sign with your own key, and `ANDROID_HOME` if your SDK lives
elsewhere. The code is plain Java:

| File | What it is |
|---|---|
| [`HomeActivity.java`](src/com/rampey/materialtv/HomeActivity.java) | The home screen: clock, buttons, dock, Apps grid, Now Playing |
| [`Palette.java`](src/com/rampey/materialtv/Palette.java) | Colors from each app's banner |
| [`Motion.java`](src/com/rampey/materialtv/Motion.java) | Material 3 Expressive spring curves |
| [`ButtonGroup.java`](src/com/rampey/materialtv/ButtonGroup.java) | The top-bar strip |
| [`CookieDrawable.java`](src/com/rampey/materialtv/CookieDrawable.java) | The morphing background shape |
| [`Screensaver.java`](src/com/rampey/materialtv/Screensaver.java), [`ShapeScene.java`](src/com/rampey/materialtv/ShapeScene.java) | The time-of-day screensaver |
| [`HomeKeyService.java`](src/com/rampey/materialtv/HomeKeyService.java) | The Home-button redirect |
</details>

## Troubleshooting

- **Home still opens Google TV.** The accessibility service is off. Run `install.sh` again, or turn on **Home
  button** under Settings → System → Accessibility.
- **Google TV flashes for a second after the TV boots.** Expected: Android starts the service a moment after
  boot, then this launcher takes over.
- **`adb` says unauthorized.** Accept the **Allow USB debugging?** prompt on the TV and run the script again.
- **No Now Playing card.** The app has to publish what it's playing. Some apps also stop playing as soon as you
  leave them.
- **No console tile.** Your console has to announce itself over HDMI-CEC. Turn on HDMI-CEC (your TV may call it
  "HDMI Device Control" or a brand name ending in Sync or Link) on both the TV and the console.

## Questions people ask

**Is it safe? Can it break my TV?**
No. It's one ordinary app. Nothing is rooted, flashed or deleted, and `uninstall.sh` undoes everything.

**Why not [tv-vibehack](https://github.com/Gathin23/tv-vibehack)?**
It's a full kit for cleaning up a TV, with its own Apple TV-style launcher. This is only a launcher: pretty light, made
for weak TVs, in Material 3 Expressive. You can use this launcher on its own or alongside tv-vibehack.

**How much memory does it use?**
The Gemini preview measured about 67 MB idle on Home (PSS in `adb shell dumpsys meminfo com.rampey.materialtv`)
with an APK of about 13 MB. This is an idle measurement, not peak memory during recording or a request.
The stable launcher previously measured about 40 MB. Your numbers will differ.

**Does it switch my PS5 on?**
Only when you select its tile. Switching to the input wakes the console over HDMI-CEC, same as the TV's input
menu.

**Can I pin my own apps?**
Not yet. The dock follows what you use. A pin option is a welcome contribution.

## License

Apache 2.0, see [`LICENSE`](LICENSE). Bundled:
[material-color-utilities](https://github.com/material-foundation/material-color-utilities) (Apache 2.0) and
[Google Sans Flex](https://github.com/googlefonts/googlesans-flex) (SIL Open Font License 1.1). "Google",
"Google Sans" and "Material Design" belong to Google; this project is not affiliated with or endorsed by Google.
The voice capture runtime and detector notices are documented in `third_party/sherpa-onnx/README.md` and bundled
under `assets/licenses/`.
The MIT-licensed Android Use semantic compiler and target resolver are pinned and documented in
`third_party/android-use/README.md`; its host/ADB bridge and companion app are not shipped. The optional test APK
under `tests/android-use/device/` is developer-only and is not needed for normal use.

## Pull request reports

- Every pull request created or pushed by Codex must have exactly one detailed
  GitHub PR report comment. Do not commit the report as a repository file unless
  the user explicitly requests a file artifact.
- Start the comment with the stable marker `<!-- codex-pr-report -->`. Before
  posting, search existing PR comments for that marker. Create the comment when
  absent; edit the existing comment after subsequent pushes instead of adding a
  duplicate.
- Use GitHub-rendered HTML and Markdown in the comment. Include, as applicable:
  executive summary, end-to-end user flow, architecture and trust boundaries,
  feature change set, database migrations, security considerations, test and
  build evidence, deployment status, rollout notes, deferred scope, and a
  reviewer guide with primary files.
- The report must describe the tested PR head and distinguish deployed code from
  local or undeployed work. Never claim a test, migration, deployment, or check
  passed without verifying it.
- After creating or updating the report, read the PR back, verify that the marked
  comment exists, and give the user direct links to both the PR and report
  comment. Do not call PR work complete until this verification succeeds.
