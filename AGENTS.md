# AGENTS.md

Guidance for coding agents (and people) working on Material TV Launcher. Read this before changing the UI.
Installation, build and troubleshooting details also live here; keep the README focused on the launcher.

## What this is

A fluid launcher for Android TV / Google TV: the user's most-used apps, colors that follow the focused app,
Material 3 Expressive design. Version 0.1.5 adds optional
Gemini voice through OpenRouter, using the user's own key. Home works offline. It contains no debloat tooling.

## Hard rules

- **Network is limited to an explicit voice request.** This preview sends recorded audio and the documented
  TV context through OpenRouter using the person's key. Commands use Android intents and media/volume APIs.
  No screen reading, screen images, UI navigation, loopback ADB client, telemetry, background inference, or bundled keys.
  Keep the stable launcher's offline behavior outside explicit voice requests.
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

For this Gemini preview branch, an agent can do the entire setup with one command once the person has
authorized ADB and supplied a local key file:

```bash
bash install-voice.sh <tv-ip> --key-file /absolute/path/to/openrouter_key --enable-gemini
bash assistant.sh <tv-ip> status
```

Example request to an agent:

```text
Install this Gemini preview of Material TV Launcher on my TV at <tv-ip>.
Use my OpenRouter key file at <absolute-path> and enable Gemini.
Save my previous assistant so I can restore it. Verify setup without displaying my key.
```

Use the APK beside `install-voice.sh`, the local `build/material-tv-launcher.apk`, or pass `--apk /path/to/file.apk`.
Version 0.1.5 includes optional Gemini voice; earlier stable releases do not. The setup folder
must include `install.sh`, `install-voice.sh`, `assistant.sh`, and `scripts/assistant-settings.py`.
Have Android platform-tools (`adb`) and Python 3 available. The person must accept the TV's debugging prompt
if the computer is not already authorized. A supplied local key file is checked before installation; a failed
key transfer leaves the old key intact. Key contents must never appear in chat, command-line arguments, or logs.

Without a key or permission to enable Gemini, run `bash install-voice.sh <tv-ip>`: install Home and keep the
existing assistant, including Google Assistant. `--key-file` alone only adds the key; it does not select Gemini.
Use `--enable-gemini` only when the person has requested Gemini setup. Updates can reuse the TV's stored key
with `bash install-voice.sh <tv-ip> --enable-gemini`; repeated enable does not overwrite the original backup.
This preserves an already-enabled Gemini installation too; it does not silently revert it to Google.

`assistant.sh <tv-ip> status` returns JSON with `gemini_selected`, `key_present`, `restore_available`, and the
current/previous assistant selections. It makes no cloud request and never exposes the key. `key_present` is
not proof of credit or model access: `api_access_verified` remains false. Do not claim a successful spoken turn
without a separate voice test. To undo Gemini selection, use `bash assistant.sh <tv-ip> restore`; Home stays installed.
No screen-reading service, screenshot sharing, or on-device remote-control authorization is needed for commands.
The Home-button accessibility service remains; it reads no screen contents.

The following quick start installs the launcher and keeps the existing assistant. Enable Gemini separately.

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
curl -fLO https://github.com/0xRampey/material-tv-launcher/releases/latest/download/material-tv-setup.zip
unzip material-tv-setup.zip
cd material-tv-setup
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

Version 0.1.5 has `INTERNET` and `RECORD_AUDIO` for optional Gemini voice; earlier releases had no internet permission.
Pressing the mic or Ask records a turn and sends the WAV plus TV context through OpenRouter. Context includes
installed apps, most-used app ranking, foreground app, media title/artist and playback state, HDMI inputs,
volume, local time, and the last four voice exchanges held in memory. Android assist-data requests are disabled.
The assistant cannot read another app's screen or personal watch/listening history. It does not upload images,
click controls, or run an app-navigation loop. The Home-button accessibility service does not read screen contents.
The assistant log contains timing, token/cost, action metadata and errors, not audio or
transcripts. The key lives in the app's external files directory; keep USB debugging access trusted.

Inspect permissions with `aapt2 dump permissions build/material-tv-launcher.apk`.

| `install.sh` switches on | Why |
|---|---|
| Accessibility service (*Home button*) | To catch the remote's Home button. It reads no screen content ([code](src/com/rampey/materialtv/HomeKeyService.java)). |
| Usage access | To order the dock by how much you use each app. |
| Notification access (*Now Playing*) | To see which app is playing. It reads no notifications ([code](src/com/rampey/materialtv/MediaListener.java)). |
| Microphone and assistant role (explicit Gemini enable) | To receive the remote mic and capture one request over the current app. Installing or adding a key does not change the assistant selection. |

## Gemini voice preview

Version 0.1.5 builds on v0.1.4. It retains the existing remote/TV-mic handoff, endpoint detection,
focus handling and assistant window. `com.rampey.voice` stays an internal source package; there is no separate
library release. The launcher calls it in capture-only mode, then sends audio to `google/gemini-3.8-flash`
through OpenRouter. Gemini does speech understanding and returns a bounded list of supported TV commands.
The launcher dispatches Android intents for app/content/HDMI/settings/Home requests and uses Android media and
volume APIs for existing playback. It does not observe or navigate another app's UI. A cancelled turn cannot
execute a returned action.
The action executor validates
installed app packages, known HDMI inputs, media commands and volume bounds before dispatching Android intents.
Back, Home and dismissal invalidate the turn so a late reply cannot run actions.

Requirements: Android 12+, a TV supporting ARMv7 apps, ADB, an OpenRouter key with credit/access to that model,
and internet on the TV. There is no local speech recognizer, LLM, or automatic offline fallback.

```bash
python3 scripts/prepare-voice.py  # verifies/downloads the pinned native capture runtime
./build.sh
bash install-voice.sh 192.168.1.50 --key-file /path/outside/repo/openrouter_key --enable-gemini
```

The key file contains only your key. Do not commit it or paste it into logs. On later updates, omit the key-file
argument to retain the key already on the TV. The script installs the APK and grants the existing Home/Now Playing
permissions. It leaves the current assistant (for example Google Assistant) selected, even when adding a key.
You can also enter or change a key on the TV through Home → Settings → Voice assistant → Add/Change OpenRouter key.
The password field never pre-fills an existing key, excludes autofill/state saving, and blocks screen capture while open.
Keys are validated as a single ASCII token and atomically replaced; saving does not change assistant selection.
Enable Gemini separately through Home → Settings → Voice assistant → Enable Gemini voice, then accept Android's
microphone and assistant prompts. Ask opens this setup when Gemini is not selected. No key means no assistant
takeover and no recording by the launcher. A nonempty key is required before enabling; API rejection or an
exhausted balance still produces an error, not an automatic switch to another assistant.

For explicit ADB opt-in, run `bash assistant.sh <tv-ip> enable`, or add `--enable-gemini` to `install-voice.sh`.
Distribute `assistant.sh` with `scripts/assistant-settings.py`; Python 3 and authorized ADB are required.
Before changing roles, setup saves the previous assistant/service/recognizer selection in
`files/previous_assistant.json`, without recording the API key. Repeated enable/update while Gemini is selected
does not overwrite that backup. The ADB helper records the exact role holder; the in-app setup derives the
previous assistant package from its component because public apps cannot enumerate another app's role holders.
This preview supports setup on Android's primary TV user only; other users are left unchanged.

Home → Settings → Voice assistant → Restore previous assistant names the saved selection and opens Android's
assistant/default-app chooser. Android requires the person to select the previous app there; an ordinary launcher
cannot silently assign another app the assistant role. The ADB equivalent, `bash assistant.sh <tv-ip> restore`,
restores the saved selection directly and verifies it, including a distinct original speech recognizer. It leaves
another assistant selected later untouched. Failed writes attempt rollback; missing/invalid backups do not guess
a Google component. Older builds that already replaced the assistant have no recoverable previous selection
unless it was saved separately. Restoring keeps the launcher, key, and opt-in settings installed.

After enabling, press Home, then the remote mic or Ask. From another app the assistant stays over that app;
a command can then open a different app.
Content requests use {"type":"content","package":"<installed package>","query":"<full title/artist>","mode":"play|search"}.
The executor checks what the installed app actually resolves: Android play-from-search first for play requests,
then a supported search intent or app search link. YouTube TV also declares play-from-search with its URL filter;
Spotify search links use its spotify: scheme. Query text is encoded by the launcher, not generated as a URL by
Gemini. Direct content links can open specific items when the person supplies a real link; never guess content ids.
Search results are not playback: if only search is supported, report "Search opened. Select a result to play".
An accepted play-from-search is reported as "Playback requested in the app", not proof the requested song started.
History, personal playlists, sign-in flows and arbitrary UI steps are unsupported. Explain the limit instead of
substituting a public search. Reject removed/unknown action types before any dispatch and reject mixed app-open
plus current-session media actions to avoid playing stale content in the wrong app. The microphone, Silero endpoint
detection, audio-focus handoff, Gemini model, and assistant overlay are unchanged. No local ASR/LLM is added.
Back/Home or dismissal invalidates pending commands; there is no always-listening wake word.

The removed Android-use experiment is not bundled: no accessibility tree, images, clicks, TV remote keys,
on-device debugging client, or iterative navigation prompts. On upgrade, remove only this package's obsolete
AndroidUseService entry from enabled accessibility services; preserve HomeKeyService and every other service.

`uninstall.sh` restores the saved assistant before removal when Gemini is selected; keep the complete setup folder.
If restoration fails or the backup is absent, choose an assistant in Android settings before uninstalling. Removing
the app also removes its saved assistant selection. Older installations without a backup need Android's default-app settings.

### Preview checks

```bash
python3 scripts/prepare-voice.py --check
python3 -B -m unittest discover -s tests/assistant -p 'test_*.py'
bash tests/voice/run.sh
./build.sh
```

The Java regressions cover audio-focus cancellation, capture trimming and capture/decoder queue behavior.
The optional developer-only intent instrumentation under `tests/intents/device/` checks the production media
intent adapter and action executor, including complete query preservation and rejection of old Android-use plans.
It can test synthetic text through the real Gemini planner and execute the resulting intent. It does not prove
physical remote-mic pairing or audio recognition quality. Tests that accept an intent must distinguish that from
observed playback; use current media-session metadata to verify content where exposed. Do not capture new screenshots.
Build/sign the test APK separately with the launcher's key; remove it afterward, and rebind the assistant/Home
service if instrumentation stops the target process. Keep device evidence outside the repository.

## Now Playing resume

Play first tries the existing session, then a real media ID/URI or session activity when exposed. Otherwise it
restores the app task without resetting it, waits up to eight seconds for matching package/title metadata, and
requests Play and the saved position only when supported. Returning Home or destroying Home cancels the request.
There is no global Play key, title search, screen reading, or invented content ID. A killed app that no longer
exposes its remembered item cannot be reconstructed without a real link. Never claim universal resume support.

## Build it yourself

<details>
<summary><strong>No Gradle, just the Android SDK</strong></summary>

You need the Android SDK (platform 34, build-tools 34.0.0), JDK 17, and Python 3.9+.

```bash
python3 scripts/prepare-voice.py # pinned capture runtime; checksum checked
./build.sh                    # → build/material-tv-launcher.apk, signed with your Android debug key
bash install-voice.sh 192.168.1.50 --key-file /path/outside/repo/openrouter_key --enable-gemini
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
under `assets/licenses/`. The Android-use and on-device ADB libraries are no longer bundled.

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
