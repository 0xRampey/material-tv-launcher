# AGENTS.md

Guidance for coding agents (and people) working on Material TV Launcher. Read this before changing the UI.

## What this is

A buttery smooth launcher for Android TV / Google TV: the user's most-used apps, colors that follow the focused app,
Material 3 Expressive design. It is **only a launcher**: no debloat tooling, no AI, no network.

## Hard rules

- **No `INTERNET` permission.** Nothing the app reads may leave the TV.
- **Nothing user-specific in code.** The dock comes from on-device usage stats and installed apps; HDMI devices come
  from HDMI-CEC names. No hardcoded app lists, TV models, IPs or names.
- **No brand logos we don't own.** Console tiles use Material icons on a tone of the brand hue, never a logo.
- **Nothing animates while another app is on screen.** Stop every loop in `onPause`; resume in `onResume`.
- **Smooth on weak TVs** (4 × Cortex-A73 at 1.39 GHz, 2.4 GB RAM): animations run on display frames
  (`postOnAnimation`, time-based), cost only while Home is visible, and must keep 0 janky frames in `dumpsys gfxinfo`.
- **Plain Java Views, no Gradle, no dependencies** beyond the vendored `material-color-utilities`. Build with
  `./build.sh`. No Compose (too heavy for the target TVs).
- **Test on a real TV** with full-resolution screenshots (`adb exec-out screencap -p`) of every focus state you touch.

## Material 3 Expressive, as used here

**Color** (`Palette.java`): `SchemeFidelity` from the focused app banner's seed (`QuantizerCelebi` + `Score`, the
same as Android wallpaper color). Use roles in their intended pairs: `primary/onPrimary`, `surface*` for backgrounds,
`inverseSurface/inverseOnSurface` for focused fills and ink, `primaryContainer` for the background shape. Never
hard-code white/black for focus. Everything tinted cross-fades with the default effects curve on retint.

**Shape** (`Focus.java`): cards 32 dp, media (tiles, pictures, banners, album art) 20 dp, buttons fully round
(pill). Focused tiles tighten their corners (×0.62) and the ring stays concentric (radius + ring offset).
The background shape (`CookieDrawable`) morphs only between library shapes: Cookie 4/6/7/9/12, Clover 4- and
8-leaf, Sunny, Soft burst (MaterialShapes names); one per app, morphing on focus.

**Motion** (`Motion.java`): Expressive springs via the spec's fallback curves.
Spatial (movement, scale, shape) may overshoot: fast 350 ms for small components, default 500 ms for larger ones.
Effects (color, opacity) never overshoot: fast 150 ms, default 200 ms. Do not add other curves or durations.

**Focus** (TV): scale 1.10 with the fast spatial curve, a 3 dp ring 6 dp outside the tile that fades in, a glow in
the app's own color (`setOutlineSpotShadowColor`), neighbours shift 8 dp aside (standard button group). Buttons and
nav fill with `inverseSurface` on focus.

**Type**: Google Sans Flex with `'ROND' 0` (never rounded), `opsz` 24 for UI and 48 for display. The focused app
name settles into its weight; the clock's weight follows the time of day and swells briefly on each new minute.

**Components**: connected button group (`ButtonGroup`: outer corners full, inner 8 dp, selected becomes a pill,
3 dp gaps); wavy progress indicator in Now Playing (`WavyProgress`: moving wave while playing, flat when paused,
grows in from a flat line); a morphing shape loader when an app takes over 300 ms to open.

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
