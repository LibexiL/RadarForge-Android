# RadarForge for Android

A fast, GR2Analyst-style **NEXRAD weather radar viewer for Android phones and tablets**.
It is a separate app with its own code, built in the spirit of the
[RadarForge desktop app](https://github.com/LibexiL/RadarForge).

Live Level II data from any of the 160 NEXRAD radars, drawn tilt by tilt while the radar is still
scanning, with 1, 2 or 4 linked panels, loops that are ready the moment you pick a radar, NWS warnings,
GR-style colour tables, dealiasing, a Σ max-value trail, and the same six themes as RadarForge for PC.

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
- **Previous scans load straight away**: pick a radar and the 10 scans before the newest load in the
  background (0–20, your choice), so the loop plays instantly. ◀ ▶ step one scan at a time. Only the start
  of each older file is downloaded (just enough for the tilt on screen – about 2–3 MB per scan at the lowest
  tilt). On mobile data they load for the lowest tilts only, or not at all if you prefer (Settings → Loop).
- **Quick switches**: every layer and radar option as one-tap buttons at the top of Map layers.
- **Dealias velocity**: unfolds aliased velocity (BV and SRV) with the same region-based method as the
  desktop app, so strong winds and tight couplets don't flip colour.
- **Σ max-value trail**: each panel shows the strongest value seen at every spot over the loaded scans –
  hail swaths from reflectivity, rotation tracks from velocity, debris trails from CC (its lowest value).
- **Learn mode and radar guide**: press and hold the map and plain-language notes explain the values
  there (hail? debris? how strong is that wind?); the guide covers every product and the classic signatures.
- **NWS warnings** (tornado, severe thunderstorm, flash flood, marine, snow squall, special weather
  statements) and **watches**, with tornado and flash flood emergencies highlighted. Tap one to read it.
  Every warning type and threat level (TOR, TORR, TORP, TORE, SVR, SVRC, SVRD, FFW, FFWC, FFWE…) has
  its own line – colour, width and style – with NWS colours by default. "Show on map" switches to the
  radar nearest the warning.
- **Inspector:** press and hold anywhere to read the value, the distance and bearing from the radar,
  the beam height and the latitude/longitude.
- **Measuring tools:** distance and bearing between any points, and a storm track that shows when
  a storm reaches the towns in its path (and you). The track can set the storm motion for SRV.
- **Storm reports:** NWS local storm reports and Spotter Network reports (tornado, funnel, wall
  cloud, hail, wind, flooding) for the last 1–24 hours, fading with age. Tap one to read it.
- **Storm chasers:** live Spotter Network positions with the direction they're driving.
- **SPC:** the day 1, 2 or 3 convective outlook (tap for tornado / wind / hail chances) and mesoscale
  discussions (tap to read).
- **Your location:** follow mode keeps the map on you and switches radars as you travel. The app
  can also alert you (vibrate and open the warning) when a new warning covers where you are.
- **Share** a picture of the map, and keep **favourite radars** at the top of the radar list.
- **Colour tables:** GR-style tables built in, and you can import your own GRLevelX / GR2Analyst
  `.pal` files.
- **Map:** states, counties, highways, lakes, cities, range rings and every radar site (tap a site to
  switch to it).
- **Themes:** RadarForge Dark, Midnight Blue, GR Classic, Nord, High Contrast and Daylight – the desktop
  app's six – for the app and the map, an accent colour, map text size, and an option to follow the
  phone's dark mode.
- **Always know how fresh the data is:** a coloured dot by the radar name – green while the radar is
  scanning or the data is recent, then amber, then red.
- Portrait and landscape layouts, two-finger tap to zoom out, the map opens where you left it, and a
  crash report you can copy and send if something goes wrong.

---

## Using it

| To | Do this |
|---|---|
| Change radar | Tap the radar name at the top, or tap a green square on the map. ☆ keeps a radar at the top of the list |
| Move / zoom | Drag; pinch; double-tap to zoom in; two-finger tap to zoom out |
| Read a value | Press and hold the map (drag to move the cross-hair, tap to hide it) |
| Change product | Tap a product along the bottom (BR, BV, SRV, CC, ZDR, SW, PHI) |
| Change tilt | The arrows at the bottom left, or tap the tilt to pick from the list |
| 2 or 4 panels | The panel button; tap a panel to choose its product |
| Loop | ▶ at the bottom plays the previous scans (already loaded); ◀ ▶ step one scan, drag the slider to scrub, ✕ to stop |
| Warnings | The ⚠ button at the top (the number counts warnings near the radar) |
| Map layers | The layers button at the top: quick switches for everything, then map, warnings, storm reports, storm chasers, SPC outlook (day 1–3) |
| Dealias / Σ trail / learn mode | Map layers → Quick switches |
| Theme | Settings → Theme (or press and hold the settings button) |
| Reload | Radar list → **Reload**, or Settings → Reload radar data |
| Measure | The ruler button: **Distance** (tap points, drag to move them) or **Storm track** (tap a storm, drag the arrowhead) |
| Follow me | The location button centres the map on you and follows you; tap it again or drag the map to stop |
| Share | The share button at the top sends a picture of the map |
| Settings | The sliders button: theme, units, storm motion for SRV, previous scans and loop speed, learn mode and the radar guide, my location, colour tables, warning lines |

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
│   │   ├── Sheets.kt         radar picker, layers, settings, warnings, reports, chasers, SPC, about
│   │   ├── ShotProvider.kt   hands the shared map picture to other apps
│   │   ├── core/             radar decoding & products – plain Kotlin, tested on the JVM
│   │   │   ├── Bzip2.kt          bzip2 decoder
│   │   │   ├── Level2.kt         NEXRAD Level II (message 31 / 1, archive files, live chunks)
│   │   │   ├── Products.kt       products, tilts, storm-relative velocity
│   │   │   ├── ColorTable.kt     GRLevelX .pal colour tables
│   │   │   ├── Alerts.kt         NWS warnings
│   │   │   ├── Reports.kt        storm reports (IEM local storm reports, Spotter Network)
│   │   │   ├── Chasers.kt        Spotter Network positions (via Placefile.kt)
│   │   │   ├── Spc.kt            SPC day 1–3 outlooks and mesoscale discussions
│   │   │   ├── Dealias.kt        region-based velocity dealiasing (same method as the desktop app)
│   │   │   ├── Trail.kt          Σ max-value trail: resampling and combining scans
│   │   │   ├── Learn.kt          learn-mode notes and the radar guide
│   │   │   ├── LoopSupport.kt    previous scans: partial reads, trimming, picking the frames
│   │   │   ├── Measure.kt        distance and storm-track maths
│   │   │   ├── S3.kt, Net.kt     AWS bucket listings and downloads
│   │   │   └── Basemap.kt, Geo.kt, RenderData.kt, ...
│   │   ├── gl/               OpenGL ES 3.0 renderer
│   │   ├── ui/               overlay (labels, legend, inspector, gestures), widgets, sheets, themes
│   │   └── data/             live tracking, previous scans, dealiased / trail fields, settings, colour tables
│   ├── assets/               map outlines, radar sites, colour tables, shaders
│   └── res/                  icon, theme
├── tests/                    JVM tests for the core, and DataManager against a simulated S3 (stubs/: the few Android classes it needs)
├── tools/
│   ├── build_assets.py       rebuilds assets/basemap.bin and sites.json from RadarForge desktop's map data
│   └── glcheck/              renders a radar scene with the app's own shaders in WebGL2 (GLSL ES 3.00)
└── build.sh
```

---

## Credits

Radar data: NOAA NEXRAD Level II on AWS (Unidata real-time chunks and archive). Warnings: National
Weather Service API. Storm reports, SPC outlooks and mesoscale discussions: NWS and the Storm
Prediction Center via the [Iowa Environmental Mesonet](https://mesonet.agron.iastate.edu/). Storm
chaser positions and spotter reports: [Spotter Network](https://www.spotternetwork.org/) (non-commercial
use). Map data: US Census Bureau, Natural Earth, GeoNames. Radar site list derived from Supercell Wx.

## License

RadarForge for Android is released under the [MIT License](LICENSE). Data sources keep their own terms.
