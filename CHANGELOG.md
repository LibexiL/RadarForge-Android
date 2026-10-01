# Changelog

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
