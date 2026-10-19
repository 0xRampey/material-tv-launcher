# Material TV Launcher

**A buttery smooth launcher for Android TV and Google TV.**

Your most-used apps, in the order you use them, and nothing else. The whole screen takes on the colors of
whatever app you're on, in Material 3 Expressive. No ads, no recommendation rows, no internet permission, and
about 35 MB of memory.

![Material TV Launcher: Spotify in focus with the whole screen tinted Spotify green, an equalizer under its tile, Now Playing with a wavy progress line, and a dock of the six most-used apps](docs/home-playing.png)

I made it for my own cheap Hisense TV. I wanted the lightest home screen I could get, and I wanted it to look
like it belonged on a Pixel.

---

## Table of contents

- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [What you get](#what-you-get)
- [Supported TVs](#supported-tvs)
- [Privacy](#privacy)
- [Build it yourself](#build-it-yourself)
- [Troubleshooting](#troubleshooting)
- [Questions people ask](#questions-people-ask)
- [Contributing](#contributing)
- [Licence](#licence)

---

## How it works

Google TV ignores any home screen that isn't its own, so a launcher needs a little help:

1. **Install** one APK from your computer over Wi-Fi, using the TV's debug bridge (ADB).
2. **Switch on** three things with one script: a tiny accessibility service that sends the remote's Home button
   here, usage access to order your apps, and media access for the Now Playing card.
3. **Press Home.** That's it. Nothing on the TV is removed, and one command puts everything back.

## Quick start

You need a computer on the same Wi-Fi as the TV, with
[ADB](https://developer.android.com/tools/releases/platform-tools) installed.

**1. On the TV**
Settings → System → About → press OK on **Android TV OS build** seven times → go back → Developer options →
turn on **USB debugging** (and **Network debugging** if you see it). Note the TV's IP address under Settings →
Network.

**2. On your computer**
Download `material-tv-launcher-<version>.apk` and `install.sh` from the [latest release](../../releases/latest)
into one folder, then run:

```bash
bash install.sh 192.168.1.50 material-tv-launcher-<version>.apk
```

**3. On the TV again**
Accept **Allow USB debugging?**, then press Home on the remote.

Changed your mind? `bash uninstall.sh 192.168.1.50` (also in the release) brings Google TV's home back.

## What you get

![The PlayStation 5 in focus: a console tile named over HDMI-CEC with a green dot because it is on, and the screen re-tinted to PlayStation blue](docs/home.png)

- **Your apps, by use.** The dock shows the six apps you use most, most used on the left. It reorders itself
  as your habits change. Nothing is hardcoded, so it works for anyone's TV.
- **Colors from the app you're on.** As you move, the background, the shape, the buttons and the Now Playing
  card re-tint to the focused app, using Google's own
  [material-color-utilities](https://github.com/material-foundation/material-color-utilities) (the same math
  behind Android's wallpaper colors).
- **Your consoles, by name.** Instead of a generic "Live TV" tile, devices that announce themselves over HDMI
  (a PlayStation 5, an Xbox) get their own tile, with a green dot while they're on. Select it and the TV switches
  straight to that input.
- **Now Playing.** When music or video plays in any app (Spotify, YouTube Music, ...), a card shows the art,
  the song, a wavy progress line and a play/pause button, and a little equalizer marks the playing app in the dock.
- **Material 3 Expressive.** Spring motion, a colored focus glow, shapes from the Material shape library that
  morph as you move, a connected button group, neighbours that make room, a morphing loader when an app is slow,
  and a Google Sans Flex clock whose weight follows the time of day.
- **Buttery smooth.** Animations run at your TV's full refresh rate (61 fps with zero janky frames on the 60 Hz
  Hisense below), it uses about 35 MB of memory, and nothing runs while you're in another app.

**And a screensaver that follows the sun.** *Material TV Clock* draws a fresh Material You-style wallpaper
every hour: indigo at night, peach at sunrise, sky blue at midday, orange at sunset.

![The screensaver: a large clock, the date and the song that is playing over soft Material shapes in early-evening greens](docs/screensaver.png)

<details>
<summary><strong>Turn on the screensaver</strong></summary>

```bash
adb shell settings put secure screensaver_components com.rampeys.home/com.rampeys.home.Screensaver
```

Back to the stock one: `adb shell settings put secure screensaver_components com.android.dreams.basic/com.android.dreams.basic.Colors`.
It starts after the TV's idle time (`adb shell settings get system screen_off_timeout`).
</details>

## Supported TVs

Anything running **Google TV** or **Android TV 12 or newer** should work: Hisense, TCL, Sony, Xiaomi, Philips,
Nvidia Shield and others. Not supported: Samsung (Tizen), LG (webOS) and Amazon Fire TV.

| Tested on | OS | Notes |
|---|---|---|
| Hisense 85" 4K Google TV (A53H, MediaTek) | Google TV 12 | Home takeover via the accessibility service |

Tried it on another TV? Please add a row, see [Contributing](#contributing).

## Privacy

The app has **no internet permission**, so nothing it reads can leave the TV. Check it yourself:
`aapt2 dump permissions material-tv-launcher-<version>.apk`.

| `install.sh` switches on | Why |
|---|---|
| Accessibility service (*Home button*) | To catch the remote's Home button. It reads no screen content ([code](src/com/rampeys/home/HomeKeyService.java)). |
| Usage access | To order the dock by how much you use each app. |
| Notification access (*Now Playing*) | To see which app is playing. It reads no notifications ([code](src/com/rampeys/home/MediaListener.java)). |

## Build it yourself

<details>
<summary><strong>No Gradle, just the Android SDK</strong></summary>

You need the Android SDK (platform 34, build-tools 34.0.0) and JDK 17.

```bash
./build.sh                    # → build/home.apk, signed with your Android debug key
./install.sh 192.168.1.50
```

Set `KEYSTORE`, `KEYSTORE_PASS` and `KEY_ALIAS` to sign with your own key, and `ANDROID_HOME` if your SDK lives
elsewhere. The code is plain Java:

| File | What it is |
|---|---|
| [`HomeActivity.java`](src/com/rampeys/home/HomeActivity.java) | The home screen: clock, buttons, dock, Apps grid, Now Playing |
| [`Palette.java`](src/com/rampeys/home/Palette.java) | Colors from each app's banner |
| [`Motion.java`](src/com/rampeys/home/Motion.java) | Material 3 Expressive spring curves |
| [`ButtonGroup.java`](src/com/rampeys/home/ButtonGroup.java) | The connected button group |
| [`CookieDrawable.java`](src/com/rampeys/home/CookieDrawable.java) | The morphing background shape |
| [`Screensaver.java`](src/com/rampeys/home/Screensaver.java), [`ShapeScene.java`](src/com/rampeys/home/ShapeScene.java) | The time-of-day screensaver |
| [`HomeKeyService.java`](src/com/rampeys/home/HomeKeyService.java) | The Home-button redirect |
</details>

## Troubleshooting

- **Home still opens Google TV.** The accessibility service is off. Run `install.sh` again, or turn on **Home
  button** under Settings → System → Accessibility.
- **Google TV flashes for a second after the TV boots.** Expected: Android starts the service a moment after
  boot, then this launcher takes over.
- **`adb` says unauthorized.** Accept the **Allow USB debugging?** prompt on the TV and run the script again.
- **No Now Playing card.** The app has to publish what it's playing. Some, like the YouTube TV app, also stop
  playing as soon as you leave them.
- **No console tile.** Your console has to announce itself over HDMI-CEC. Turn on HDMI-CEC (often called
  "Anynet+", "BRAVIA Sync" or "HDMI Device Control") on both the TV and the console.

## Questions people ask

**Is it safe? Can it break my TV?**
No. It's one ordinary app. Nothing is rooted, flashed or deleted, and `uninstall.sh` undoes everything.

**Why not [tv-vibehack](https://github.com/Gathin23/tv-vibehack)?**
It's a full kit for cleaning up a TV, with its own Apple TV-style launcher. This is only a launcher, aimed at
being as light as possible on weak TVs, in Material 3 Expressive. You can use it on its own or alongside it.

**How much memory does it use?**
35 MB (`adb shell dumpsys meminfo com.rampeys.home`, one reading on the Hisense above). On the same TV, Google
TV's own home used 240–380 MB. Your numbers will differ.

**Does it switch my PS5 on?**
Only when you select its tile. Switching to the input wakes the console over HDMI-CEC, same as the TV's input
menu.

**Can I pin my own apps?**
Not yet. The dock follows what you use. A pin option is a welcome contribution.

## Contributing

The most useful thing you can send is a tested-TV report with a screenshot. Launcher ideas that keep it light
are welcome too: see [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

Apache 2.0, see [`LICENSE`](LICENSE). Bundled:
[material-color-utilities](https://github.com/material-foundation/material-color-utilities) (Apache 2.0) and
[Google Sans Flex](https://github.com/googlefonts/googlesans-flex) (SIL Open Font License 1.1). "Google",
"Google Sans" and "Material Design" belong to Google; this project is not affiliated with or endorsed by Google.
