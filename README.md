# RadarForge for Android

A fast, GR2Analyst-style **NEXRAD weather radar viewer for Android phones and tablets**.
It is a separate app with its own code, built in the spirit of the
[RadarForge desktop app](https://github.com/LibexiL/RadarForge).

Live Level II data from any of the 160 NEXRAD radars, drawn tilt by tilt while the radar is still
scanning, with 1, 2 or 4 linked panels, loops, NWS warnings, GR-style colour tables and a dark
RadarForge look.

**Contents:** [Install](#install) · [Features](#features) · [Using it](#using-it) ·
[Building from source](#building-from-source) · [Project layout](#project-layout) · [Changelog](CHANGELOG.md) · [License](#license)

---

## Install

You need an Android phone or tablet with **Android 8.0 or newer** (almost any phone from 2017 on).

1. On your phone, open the [**Releases**](https://github.com/LibexiL/RadarForge-Android/releases) page
   and download **`RadarForge-Android-….apk`** from the newest release.
2. Open the downloaded file (from the notification or your Downloads folder).
3. Android asks to allow installs from your browser or file manager: tap **Settings**, turn on
   **Allow from this source**, go back and tap **Install**.
   - If **Google Play Protect** says it doesn't recognise the app, tap **More details → Install anyway**.
     That message appears for every app installed outside the Play Store.
4. Open **RadarForge** from your app drawer. On first start it asks for your location so it can pick
   the nearest radar (you can say no and choose one yourself).

**Updating:** download the newer `.apk` and install it over the old one. Your settings are kept.
**Uninstalling:** long-press the RadarForge icon → **Uninstall**.

---

## Features

- **Live data** from NOAA's NEXRAD feed on AWS. The newest scan draws in tilt by tilt; tilts the new
  scan hasn't reached yet show the previous volume.
- **Products:** base reflectivity, velocity, storm-relative velocity, spectrum width, ZDR,
  correlation coefficient and differential phase, at every tilt (SAILS rescans included).
- **1, 2 or 4 linked panels:** pan and zoom move together; each panel shows its own product.
- **Loop** of the last 4–15 scans. Only the start of each older radar file is downloaded (just enough
  for the tilt on screen), which keeps mobile-data use down.
- **NWS warnings** (tornado, severe thunderstorm, flash flood, marine, snow squall, special weather
  statements) and **watches**, with tornado and flash flood emergencies highlighted. Tap one to read it.
  Outlines use the NWS's own hazard colours, and you can pick your own colour for each type.
- **Inspector:** press and hold anywhere to read the value, the distance and bearing from the radar,
  the beam height and the latitude/longitude.
- **Colour tables:** GR-style tables built in, and you can import your own GRLevelX / GR2Analyst
  `.pal` files.
- **Map:** states, counties, highways, lakes, cities, range rings and every radar site (tap a site to
  switch to it).
- **Dark theme** matching RadarForge desktop, portrait and landscape layouts, and a crash report you
  can copy and send if something goes wrong.

---

## Using it

| To | Do this |
|---|---|
| Change radar | Tap the radar name at the top, or tap a green square on the map |
| Move / zoom | Drag; pinch; double-tap to zoom in |
| Read a value | Press and hold the map (drag to move the cross-hair, tap to hide it) |
| Change product | Tap a product along the bottom (BR, BV, SRV, CC, ZDR, SW, PHI) |
| Change tilt | The arrows at the bottom left, or tap the tilt to pick from the list |
| 2 or 4 panels | The panel button; tap a panel to choose its product |
| Loop | ▶ at the bottom; drag the slider to step through frames, ✕ to stop |
| Warnings | The ⚠ button at the top (the number counts warnings near the radar) |
| Map layers | The layers button at the top |
| Settings | The sliders button: units, storm motion for SRV, loop length and speed, colour tables, warning colours |

**Storm-relative velocity** uses the storm motion set under Settings (default: from 240° at 30 kt).

RadarForge is not an official warning source. Always follow the National Weather Service and your
local officials.

---

## Building from source

Builds on **Linux** (or macOS, or Windows through WSL) with only **Java 17 or newer**, plus `curl`,
`unzip` and `zip`. You don't need Android Studio or the Android SDK. The build script downloads the
tools it needs (Kotlin compiler, Android 14 platform library, aapt2, D8, APK signer) from GitHub
once, checks each against a fixed SHA-256 fingerprint, and keeps them in `.tools/`.

```bash
git clone https://github.com/LibexiL/RadarForge-Android.git
cd RadarForge-Android
bash build.sh            # → build/RadarForge-Android-<version>.apk
bash build.sh test       # radar decoder tests on the JVM
```

The decoder tests compare against real radar files, which aren't in the repo. Put some Level II
files in a folder and point `RF_TESTDATA` at it; tests without their files are skipped.

**Signing:** builds are signed with the signer's standard debug key, so a build from any machine can
update an install from any other. To sign with your own key, create `keystore.properties` (it's
ignored by git):

```properties
KEYSTORE=/path/to/release.jks
ALIAS=radarforge
STORE_PASS=...
KEY_PASS=...
```

GitHub builds the APK and runs the tests on every push (Actions tab → latest run → *Artifacts*).

---

## Project layout

```
RadarForge-Android/
├── app/
│   ├── AndroidManifest.xml
│   ├── src/com/libexil/radarforge/
│   │   ├── MainActivity.kt   main screen, panels, loop, location, colour table import
│   │   ├── Sheets.kt         radar picker, layers, settings, warnings, tilts, about
│   │   ├── core/             radar decoding & products – plain Kotlin, tested on the JVM
│   │   │   ├── Bzip2.kt          bzip2 decoder
│   │   │   ├── Level2.kt         NEXRAD Level II (message 31 / 1, archive files, live chunks)
│   │   │   ├── Products.kt       products, tilts, storm-relative velocity
│   │   │   ├── ColorTable.kt     GRLevelX .pal colour tables
│   │   │   ├── Alerts.kt         NWS warnings
│   │   │   ├── S3.kt, Net.kt     AWS bucket listings and downloads
│   │   │   └── Basemap.kt, Geo.kt, RenderData.kt, ...
│   │   ├── gl/               OpenGL ES 3.0 renderer
│   │   ├── ui/               overlay (labels, legend, inspector, gestures), widgets, sheets
│   │   └── data/             live tracking, loop frames, settings, colour tables
│   ├── assets/               map outlines, radar sites, colour tables, shaders
│   └── res/                  icon, theme
├── tests/                    JVM tests for the core
├── tools/
│   ├── build_assets.py       rebuilds assets/basemap.bin and sites.json from RadarForge desktop's map data
│   └── glcheck/              renders a radar scene with the app's own shaders in WebGL2 (GLSL ES 3.00)
└── build.sh
```

---

## Credits

Radar data: NOAA NEXRAD Level II on AWS (Unidata real-time chunks and archive). Warnings: National
Weather Service API. Map data: US Census Bureau, Natural Earth, GeoNames. Radar site list derived from
Supercell Wx.

## License

RadarForge for Android is released under the [MIT License](LICENSE). Data sources keep their own terms.
