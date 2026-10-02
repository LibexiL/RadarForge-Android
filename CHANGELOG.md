# Changelog

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
