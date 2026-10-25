# KlikDash for developers

[English](DEVELOPMENT.md) | [Bahasa Indonesia](DEVELOPMENT.id.md) · back to the [README](../README.md)

## Structure

```
klikdash/
├── AndroidManifest.xml
├── build.sh                  build + install on the headunit
├── klikdash.keystore         APK signing key (do not lose it, see Notes)
├── src/com/ashafizullah/klikdash/
│   ├── HomeActivity.java     launcher: tiles, clock, prayer times, temperature, drag & drop
│   ├── PrayerTimes.java      prayer-time calculation (praytimes.org algorithm)
│   ├── QuranActivity.java    mushaf view + player
│   ├── QuranService.java     background recitation player
│   ├── QuranData.java        loader for assets/quran.txt
│   ├── DzikirActivity.java   morning & evening dhikr with a guided counter
│   ├── SettingsActivity.java settings with tabs: launcher, power saver, AI assistant
│   ├── AiPanel.java          AI assistant tab: profiles, model, ElevenLabs voice & model pickers
│   ├── Updater.java          update check from GitHub Releases, signature check, install
│   ├── HomeSwitch.java       make KlikDash the home screen / back to the stock launcher
│   ├── PowerPanel.java       power saver tab: block apps from running in the background
│   ├── AskActivity.java      TANYA AI tile: voice assistant for car commands
│   ├── AiClient.java         AI tool-use loop (Claude Messages API / OpenAI-compatible)
│   ├── AiTools.java          car commands the AI may run (open app, navigate, murattal, volume…)
│   ├── AiStore.java          AI profiles; API keys encrypted with Android Keystore
│   ├── AiVoice.java          speech: Android or ElevenLabs TTS and STT (picks installed Android engines)
│   ├── BeamActivity.java     KlikBeam (beta) receiver, opened from Settings › Launcher › KLIKBEAM (iOS app not released yet)
│   ├── BeamServer.java       KlikBeam v1 TCP server + Bonjour/NSD registration
│   ├── BeamDecoder.java      hardware H.264 decoder (MediaCodec) rendering to a Surface
│   ├── Tweaks.java           system commands through local ADB (ksmd, hide Zlink, launcher, appops)
│   └── Compat.java           Android 7–8 differences (fonts, audio focus, notification channel, services)
├── res/                      theme, vector icons, ISRG root certificates for Android 7.0 (network_security.xml)
├── assets/
│   ├── dzikir.json           morning & evening dhikr, built from KlikBand (tools/build_dzikir.mjs)
│   ├── quran.txt             6,236 verses (Arabic, Latin, translation), 3.1 MB — NOT in the repo, build it yourself
│   ├── display.ttf           Big Shoulders (clock, labels)
│   ├── mono.ttf              Martian Mono (small numbers)
│   ├── arabic.ttf            Amiri Quran
│   ├── serif.ttf             Literata (translation; instanced & subset)
│   ├── static/               static instances of display/mono for Android 7–8.1 (tools/build_static_fonts.py)
│   └── licenses/             SIL OFL text for each font (shipped inside the APK)
├── tools/build_quran.py      builds quran.txt from equran.id JSON
├── tools/build_static_fonts.py  builds assets/static/ (needs fonttools)
├── design/                   README header (header.html → header.png / header.id.png) + real screenshots
└── LICENSE                   MIT (code); fonts stay under the OFL

../quran-data/                not part of the APK
├── text/                     per-surah JSON from equran.id
├── zips/  audio/             Alafasy 128 kbps recitation (everyayah.com), 6,236 mp3 files
└── intro/                    SSS000.mp3 files (unused)
```

## Requirements

- macOS with JDK 17 and the Android SDK (`build-tools/36.0.0`, `platforms/android-35`) in
  `~/Library/Android/sdk` or `$ANDROID_HOME`.
- `adb`. The headunit is reached over **ADB WiFi** (`192.168.1.17:5555`), because its USB port works in
  host mode.

## Build & install

The first time, build `assets/quran.txt` (see [Quran text](#quran-text)); `build.sh` stops with
instructions if that file is missing.

```bash
adb connect 192.168.1.17:5555
./build.sh              # build only → build/klikdash.apk
./build.sh release      # public release APK without the Quran text → build/klikdash-<version>.apk
./build.sh install      # build, install, grant location, make it the home app
DEVICE=1.2.3.4:5555 ./build.sh install   # if the headunit's IP changes
DEBUGGABLE=1 ./build.sh install          # debuggable build, needed for the adb TANYA AI tests (see AskActivity)
```

Always install with `./build.sh install`. With a plain `adb install`, the home preference can be lost until
`set-home-activity` is run again.

## Recitation audio

The app downloads the audio by itself (see Features). To fill all 114 surahs at once (1.6 GB) without
using the headunit's connection, download them on a computer and push them over ADB. Files live at
`/sdcard/Android/data/com.ashafizullah.klikdash/files/quran/audio/SSSAAA.mp3`.

```bash
# download (once)
cd ../quran-data
seq -f "%03g" 1 114 | xargs -P 4 -I{} curl -fsS -C - -o zips/{}.zip \
  https://everyayah.com/data/Alafasy_128kbps/zips/{}.zip
for z in zips/*.zip; do unzip -qq -o -j "$z" '*.mp3' -d audio; done
mkdir -p intro && mv audio/*000.mp3 intro/

# push to the headunit (~20 minutes over WiFi)
adb push audio /sdcard/Android/data/com.ashafizullah.klikdash/files/quran/
```

The basmala before verse 1 (except Al-Fatihah and At-Taubah) uses `001001.mp3`.

> ⚠️ That folder belongs to the app. **Uninstalling KlikDash deletes the audio.** Updating with
> `./build.sh install` is safe.

## Quran text

The Kemenag mushaf text and translation are **not included in this repo or in the release APK**. The
distribution of mushaf in Indonesia is regulated by PMA 44/2016 (verification by LPMQ). The app downloads the
text from equran.id on first use and cleans it the same way as `tools/build_quran.py` (byte-identical output):
it adjusts a few marks so the Amiri font can display them. For your own developer build you can embed it
instead (`./build.sh` includes `assets/quran.txt` when present; `./build.sh release` never does):

```bash
mkdir -p ../quran-data/text
for i in $(seq 1 114); do curl -fsS -o ../quran-data/text/$i.json https://equran.id/api/v2/surat/$i; done
python3 -I tools/build_quran.py ../quran-data/text assets/quran.txt
```

The script also cleans the text:
- It removes Kemenag-specific mushaf markers that the Amiri font lacks and that are not recited:
  U+08D6 (ruku' sign), U+06D4 (juz separator), and U+06D5 (waqf carrier).
- It attaches waqf marks to the preceding word.
- It strips control characters.

The English translation (Saheeh International, from alquran.cloud) is downloaded separately into
`quran-en.txt` when the interface is in English. To embed it in a developer build (never in a release):

```bash
python3 -I tools/build_quran.py --en assets/quran-en.txt
```

## Translations

The interface is in Indonesian (`res/values/strings_*.xml`, the default) and English
(`res/values-en/strings_*.xml`), chosen in **⚙ › Launcher › BAHASA** and not from the system language (`Lang.java`).
- Every new piece of UI text goes into **both** folders under the same name. Use positional arguments
  (`%1$s`) and `<plurals>` for counts. Never compare or save displayed text: keep a separate key.
- Activities and `QuranService` wrap their context in `attachBaseContext`. Receivers and the application context
  call `Lang.wrap(c)` before `getString`.
- The voice assistant has its own language (`AiStore`, **⚙ › Asisten AI**). Its prompts and spoken replies are in
  `strings_assistant.xml` and are read with `Lang.wrap(c, AiStore.lang(c))`.

## Firmware-specific behaviour

- **Default launcher.** The NWD firmware ignores Android's home choice and uses
  `persist.nwd.launcher.default` (`PackageManagerService.chooseBestActivity`).
- **Direct boot.** During boot, the encrypted storage (FBE) stays locked for a few seconds, and only
  `directBootAware` launchers can be chosen. That is why `HomeActivity` is directBootAware and keeps its
  settings in DE storage. The app list is reloaded after `ACTION_USER_UNLOCKED`.
- **System commands.** Turning off ksmd and returning to home need system privileges. `Tweaks.java` runs
  them through the device's own ADB (`127.0.0.1`), without modifying the system partition, so ADB must be
  enabled on the headunit. If ADB is off, these two tweaks are skipped and everything else still works.
- **The headunit sleeps rather than shuts down.** On ACC off the headunit only goes to sleep, so the
  "just started" window for Zlink also begins on `SCREEN_ON`, `com.nwd.ACTION_AUTO_SLEEP_WAKE_STATE`, and
  `com.nwd.android.ACTION_SYSTEM_AUTO_WAKEUP`.
- **Zlink and WiFi.** On startup, the Zlink service turns on a hotspot for wireless CarPlay, and the WiFi
  chip cannot run a hotspot and client WiFi at the same time. While wireless CarPlay is active, **WiFi is
  off** and the headunit cannot be reached from a computer. To use ADB, turn WiFi on manually.

## Notes

- **The city name is not detected automatically**, because the geocoder on this device returns no results.
  It can be set manually and is kept while the position stays within 15 km:
  ```bash
  adb shell am start -n com.ashafizullah.klikdash/.HomeActivity --es lat -6.1754 --es lon 106.8272 --es city "Jakarta"
  ```
- **`klikdash.keystore`** (alias `klikdash`) must be backed up somewhere safe, for example as a password-manager
  attachment. It is not in the repo. Its password is in the **macOS Keychain** (service `klikdash-keystore`):
  `security find-generic-password -a klikdash -s klikdash-keystore -w`. For CI, set the `KLIKDASH_KS_PASS`
  env variable. An APK signed with a different key cannot update KlikDash; you would have to uninstall
  first, and uninstalling also deletes the recitation audio. A fresh clone without a keystore gets a new
  key generated by `build.sh`.

## README header

`design/header.png` (EN) and `design/header.id.png` (ID) are rendered from `design/header.html` using real
headunit screenshots:

```bash
cd design
for l in en id; do
  out=header.png; [ $l = id ] && out=header.id.png
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars \
    --window-size=1280,640 --force-device-scale-factor=2 --virtual-time-budget=5000 \
    --screenshot="$PWD/$out" "file://$PWD/header.html?lang=$l"
done
```
