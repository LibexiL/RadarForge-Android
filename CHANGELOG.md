# Changelog

## 1.7.1 – 2026-10-15

* **Severe, Winter and Tropical each have their own page** under Layers, so the list isn't one long scroll.
  * Each page starts with **Set up the map for …**, then has just that weather's switches.
  * Each page ends with buttons that open its model maps:
    * **Severe set:** CAPE, helicity, shear and STP in four panels.
    * **Winter set:** precipitation type, snow, ice and temperature.
    * **Tropical set:** the GFS over the Atlantic.
    * Single maps one tap away.
* **Layers** itself is now the three weather pages, **Clean map**, then warnings on / off, the radar display and
  the map.
* Getting started and What's new point to the new pages.

## 1.7.0 – 2026-10-15

* **Models.** Tap **Models** at the top for forecast maps from the HRRR, NAM 3 km, RAP and GFS, like
  Pivotal Weather's. They come straight from NOAA, downloading only the fields you look at.
  * **30 fields in four groups:**
    * **Severe:** simulated radar, CAPE (surface, mixed-layer, most-unstable), CIN, 0–1 and 0–3 km
      helicity, 0–6 km shear, STP, the supercell composite, updraft helicity, lapse rates and the
      lifted index.
    * **Surface:** temperature, dewpoint, humidity, wind, gusts, radar with isobars, precipitable water.
    * **Upper air:** 500 mb heights and vorticity, 850 mb temperature, the 250 mb jet.
    * **Precipitation & winter:** total precipitation, precipitation type, snowfall (10:1 and the
      model's own), freezing rain, snow depth.
  * **Contours, barbs and labels:** height and pressure lines, wind barbs, the 32 °F / 0 °C line, and
    a stepped colour bar.
  * **Panels:** 1, 2 or 4, each with its own field.
  * **Moving through the forecast:** ◀ ▶ step the forecast hours, the slider scrubs, and ▶ plays the
    forecast.
  * **The model chip:** changes the model, the run and the area (the whole US, 12 regions, or the
    Atlantic tropics with the GFS).
  * **Reading values:** press and hold to read a value.
* **Winter:**
  * **P-Type:** reflectivity coloured by precipitation type – rain, snow, sleet or freezing rain –
    using the HRRR's analysis for the hour of the scan.
  * **Freezing line:** the 32 °F line for the hour of the scan.
  * **Snowfall totals:** NOHRSC's 24, 48 or 72-hour analysis.
  * **Winter colours:** a reflectivity colour table for light snow.
  * **Winter warnings shaded by county:** winter storm, blizzard, ice storm, lake-effect snow,
    advisories, extreme cold, freeze and frost.
  * **Snow, sleet, ice and blizzard reports**, with snow totals beside them.
  * **Learn mode** says what's falling, and the temperature.
* **Hurricanes:**
  * **Active storms** from the National Hurricane Center: forecast cone and track, the wind field now,
    and the coastal hurricane / tropical storm watches and warnings.
  * **Tap a storm** for its details, the forecast, the NHC's links, and **when it could reach you**:
    closest approach, and when tropical-storm and hurricane-force winds arrive and leave.
  * **Model tracks** (GFS, European, HAFS, Canadian, UK, consensus), the **tropical outlook**,
    **buoys**, and **rainfall totals** (MRMS, 24 / 48 / 72 h).
  * **Hurricane, tropical storm and storm surge warnings**, shaded by county.
  * **Past storms:** the best track of any Atlantic or east Pacific storm since 1950, this season
    included.
  * **History:** every storm that passed within 100 miles of you, or of the map centre.
* **Layers, reorganised:**
  * **Presets:** one tap sets up the map for **Severe**, **Winter**, **Tropical** or a **Clean** map.
  * **Groups:** the switches are grouped as warnings & watches, storm reports & chasers, SPC, winter,
    tropical, radar display and map, and each group folds away.
* **Easier to learn:** Getting started and What's new are rewritten, and short tips appear the first
  time you open the Models view or P-Type.
* **Share a picture of the map** has moved to Settings, to make room for the Radar | Models switch.

## 1.6.0 – 2026-10-14

* **Updates from inside the app.** RadarForge looks for a new version on GitHub about once a day and
  offers it with its release notes. **Update now** downloads it and hands it to Android's installer.
  Your settings, colour tables and favourites are kept.
  * The first time, Android asks you to allow RadarForge to install apps.
  * **Skip this version** stops the offer for that version.
  * **Settings → Updates** has **Check for updates** and the daily check switch. About has a
    **Check for updates** too.
* **Friendlier menus:**
  * Quick switches are in three groups: Warnings & storms, Radar display and Map.
  * They have clearer names: Storm reports, Storm chasers, Severe outlook, Discussions, Colour bars,
    Dealiasing and Σ Max trail.
  * Map layers lists warnings and storm data first, then radar display (with Colour tables), and the
    map last.
  * Settings puts theme, colour tables and warning lines together under **Look & colours**.
  * Settings adds **Learning & help** (learn mode, radar guide, What's new) and **Updates**.
* **Getting started.** The first-launch guide is now six short steps with **Choose a radar** and
  **Start** buttons. Reopen it any time from Settings → **Getting started**.

## 1.5.0 – 2026-10-14

* **New default colour tables:** WxVortex for reflectivity, Enhanced for velocity (in mph) and Heatseaker
  for correlation coefficient. Colour tables you imported yourself are kept.
* **Classic warning colours by default:**
  * Green flash flood, yellow severe thunderstorm and red tornado.
  * Magenta for a confirmed, PDS or emergency tornado.
  * Warning lines → **NWS colours** switches back. Lines you changed yourself are kept.
* **Previous scans show the warnings of their own time.** Stepping back through the loop shows the
  warnings that were in force at each scan, with the polygons as they were then; an update can shrink a
  warning. The newest scan shows what's in force now. The earlier warnings come from Iowa State's warning
  archive.
* **New warnings flash.** A warning that has just been issued, or upgraded (e.g. a tornado warning
  becoming "observed"), pulses on the map for 8 seconds. To turn it off: Warning lines → Flash new warnings.

## 1.4.1 – 2026-10-05

* **Fixed: the previous scans downloaded but never showed up**, so the loop stayed empty. Each file was
  downloaded, then thrown away when saving it to the phone's cache failed (Android refused the temporary
  file's name). This had also broken loops in 1.3.0, and the newest complete scan from the archive, so
  switching radars was slower than it should have been: the screen waited for the live feed instead.
* If the newest scan can't be downloaded, it's tried again after 20 seconds.
* A test now runs the data loading against a simulated radar server, so this can't slip through again.

## 1.4.0 – 2026-10-05

* **The 10 previous scans load as soon as you pick a radar**, so the loop plays straight away. They load
  right after the newest scan (which still shows first), and each finished scan joins the loop
  automatically.
  * ◀ ▶ in the loop bar step one scan at a time.
  * Settings → Loop: how many (0–20), and whether to load them on mobile data. On mobile data they load
    for the lowest tilts (about 2–3 MB a scan); higher tilts load on Wi-Fi, or when you press play.
  * Changing tilt or product reloads them for what's on screen. Only the part of each file that's
    missing is downloaded – going up a tilt doesn't fetch the lower ones again.
* **Themes** (Settings → Theme, or press and hold the settings button): RadarForge Dark, Midnight Blue,
  GR Classic, Nord, High Contrast and Daylight – the same six as RadarForge for PC – for the app and the map.
  * An accent colour, and map text size (small to larger).
  * Follow the phone's dark mode: Daylight in light mode, your dark theme in dark mode.
  * Changing theme keeps the radar loaded and the map where it was.
* **Quick switches** at the top of Map layers: warnings, watches, storm reports, chasers, SPC outlook and
  discussions, counties, highways, cities, range rings, radar sites, colour bar, smoothing, dealiasing,
  Σ trail and learn mode, one tap each.
* **Dealias velocity**: unfolds aliased velocity on BV and SRV, with the desktop app's region-based method
  (worked out in the background; the panel title says "dealiasing…" until it's ready).
* **Σ max-value trail**: each panel shows the strongest value seen at every spot over the loaded scans,
  up to the one shown – hail swaths, rotation tracks, debris trails (CC shows its lowest value).
* **Learn mode** and a **radar guide**: press and hold the map and a card explains the values there in
  plain words (hail? debris? how strong is that wind? how high is the beam?). The guide covers every
  product and the classic signatures (Settings → Radar guide).
* **SPC day 2 and day 3 outlooks** (Map layers); day 3 shows its "any severe" chance.
* A **coloured dot** by the radar name shows how fresh the data is: green while the radar is scanning or
  the data is recent, amber after 12 minutes, red after 25. The age stays up to date.
* **Reload** the radar data from the radar list or Settings.
* Two-finger tap zooms out, and the map opens where you left it.
* Warning names, SPC risk names and links stay readable in light and dark themes.

## 1.3.0 – 2026-10-01

* **Measuring tools** (ruler button at the bottom):
  * **Distance**: tap points to measure. You get each leg's length and direction and the total,
    and you can drag any point.
  * **Storm track**: tap a storm, then drag the yellow arrowhead to where it's going. Tick marks
    show the clock time along the way. Towns in its path are listed with arrival times, and you
    get your own arrival time if you're ahead of it.
  * Track options: 30–120 minute tracks, and **Use for SRV** sets the storm-relative velocity
    motion from the track.
* **Storm reports** (Map layers): NWS local storm reports plus Spotter Network reports, shown as
  lettered markers (T, FC, WC, H, W, G, F) that fade with age.
  * Choose 1–24 hours and which types to show.
  * Tap a marker for the details, or open the list from Map layers or Warnings.
* **Storm chasers** (Map layers): live Spotter Network positions, updated every minute.
  * Arrows show which way each chaser is driving; the colour shows how fresh the position is.
  * Show everyone or only active reporters, with or without names. Tap a chaser for details.
* **SPC day 1 outlook and mesoscale discussions** (Map layers): risk areas with labels.
  * Tap inside an outlook area for the tornado, wind and hail chances at that spot.
  * Tap a discussion to read its full text.
* **Follow my location**: the location button now keeps the map centred on you as you move.
  * Tap it again, or drag the map, to stop following.
  * While following, RadarForge switches to the nearest radar as you travel (can be turned off).
* **Alert me in a new warning** (Settings → My location): when a new tornado, severe
  thunderstorm or flash flood warning covers where you are, the app vibrates and opens the
  warning (while it's open).
* **Share**: the share button sends a picture of the map (radar, warnings, labels and the
  time) to any app.
* **Favourite radars**: star radars in the radar list to keep them at the top.

## 1.2.0 – 2026-10-01

* **Warning lines**: every warning type and threat level has its own line – colour, width and
  style (solid, black centre line or double). Codes: TOR, TORR (reported), TORP (PDS), TORE
  (emergency); SVR, SVRC (considerable), SVRD (destructive); FFW, FFWC, FFWE; SMW, SQW, EWW,
  DSW, SPS and the watches (TOA, SVA). Read from the NWS impact tags of each warning.
* Defaults use the NWS colours with the threat levels told apart by line style; a **Classic
  colours** preset gives green flash flood, yellow severe and magenta reported / PDS / emergency
  tornado.
* **Show on map** for a warning switches to the radar nearest it first (can be turned off under
  Warning lines).
* Warning lists and details show the threat level and its code.

## 1.1.0 – 2026-10-01

* Warning outlines use the **National Weather Service hazard colours** by default.
* **Warning colours** (Settings, or Map layers): pick your own colour for each warning and watch
  type, with NWS presets, a hex code or red/green/blue sliders, and reset to the NWS colours.
* Fixed: switching Watches off didn't hide tornado and severe thunderstorm watches, so they
  were still drawn and still opened when tapped.
* Extreme wind warnings are now under "Other", the same as the desktop app.

## 1.0.0 – 2026-09-30

* First release: live NEXRAD Level II, 7 products at every tilt, 1/2/4 linked panels, loops,
  NWS warnings and watches, inspector, GR-style colour tables with .pal import.
