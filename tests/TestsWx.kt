package com.libexil.radarforge.tests

import com.libexil.radarforge.core.*

private fun ok(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

private fun utc(s: String) = Time.parseIso(s)

fun registerWx() {
    Extra.all.add("winter: precipitation type field" to ::ptype)
    Extra.all.add("winter: freezing line and contours" to ::contours)
    Extra.all.add("winter: snowfall listing, rainfall keys" to ::listings)
    Extra.all.add("winter: county-shaded alerts" to ::areaAlerts)
    Extra.all.add("winter: snow reports" to ::snowReports)
    Extra.all.add("tropical: current storms" to ::currentStorms)
    Extra.all.add("tropical: forecast points and arrival" to ::arrival)
    Extra.all.add("tropical: model tracks (a-deck)" to ::adeck)
    Extra.all.add("tropical: HURDAT2 and tracks near" to ::hurdat)
    Extra.all.add("tropical: buoys" to ::buoys)
    Extra.all.add("map raster: resample and colour" to ::raster)
}

/** A one-sweep REF field: two rays (north, south), three gates. */
private fun refField(): Field {
    val raw = floatArrayOf(30f, 30f, 2f, 30f, 30f, 2f)
    val codes = ByteArray(6) { Math.round(raw[it] * 2 + 66).coerceIn(2, 255).toByte() }
    return Field(Product.PTYPE, "KTLX", 0L, 0L, 0.5f, 2, 3, 10f, 20f, 2f, 66f, floatArrayOf(0f, 180f), floatArrayOf(-0.5f, 179.5f),
        floatArrayOf(0.5f, 180.5f), codes, null, 0f, 0f, 0f, true)
}

private fun ptype() {
    val n = Winter.N
    val codes = ByteArray(n * n) { if (it / n < n / 2) Winter.SNOW.toByte() else Winter.RAIN.toByte() }   // the northern half snows
    val grid = Winter.SurfaceGrid(codes, FloatArray(n * n), 35.0, -97.0, 0L, "test")
    val f = Winter.ptypeField(refField(), grid)
    val v = f.values()
    ok(f.product == Product.PTYPE && f.codes16 != null, "16-bit PTYPE field")
    ok(Math.abs(v[0] - 130f) < 0.6f && Math.abs(v[3] - 30f) < 0.6f, "north snows, south rains: ${v[0]} ${v[3]}")
    ok(v[2].isNaN(), "below 5 dBZ: nothing")
    ok(Math.abs(Winter.ptypeField(refField(), null).values()[0] - 30f) < 0.6f, "no grid: plain reflectivity")
    // the HRRR fields sampled onto the square: a 3 x 3 lat/lon grid around the radar, cold in the north
    val t2 = floatArrayOf(270f, 270f, 270f, 280f, 280f, 280f, 285f, 285f, 285f)
    val g = Grib2.Grid(t2, 3, 3, 40.0, -102.0, 5.0, 5.0, 0, null, 0L)
    val cs = Grib2.Grid(FloatArray(9), 3, 3, 40.0, -102.0, 5.0, 5.0, 0, null, 0L)
    val fz = Grib2.Grid(FloatArray(9) { if (it == 4) 1f else 0f }, 3, 3, 40.0, -102.0, 5.0, 5.0, 0, null, 0L)
    val sg = Winter.build(mapOf("t2" to g, "csnow" to cs, "cfrzr" to fz), 35.0, -97.0, 0L, "test")
    ok(sg.code(0f, 0f) == Winter.FRZR, "freezing rain where the model says so: ${sg.code(0f, 0f)}")
    ok(Math.abs(sg.t2(0f, 0f) - ((280f - 273.15f) * 1.8f + 32f)) < 0.01f, "2 m temperature in °F")
    ok(sg.code(0f, 470f) == Winter.SNOW || sg.code(0f, 470f) == -1, "cold air with no precip counts as snow")
}

private fun contours() {
    // a cone: values = distance from the middle; the 10 contour is a circle of radius 10
    val w = 41; val h = 41
    val v = FloatArray(w * h) { k -> Math.hypot((k % w - 20).toDouble(), (k / w - 20).toDouble()).toFloat() }
    val lines = MapRaster.march(v, w, h, 10f)
    ok(lines.size == 1, "one closed line, got ${lines.size}")
    var worst = 0.0
    val p = lines[0]
    var k = 0
    while (k < p.size) { worst = maxOf(worst, Math.abs(Math.hypot(p[k] - 20.0, p[k + 1] - 20.0) - 10.0)); k += 2 }
    ok(worst < 0.3, "on the circle (off by $worst)")
    ok(Math.abs(p[0] - p[p.size - 2]) < 1e-3 && Math.abs(p[1] - p[p.size - 1]) < 1e-3, "closed")
    // the freezing line of a grid warm in the south, cold in the north
    val n = Winter.N
    val t2f = FloatArray(n * n) { 20f + 30f * (it / n) / n }            // 20 °F at the top, 50 °F at the bottom
    val g = Winter.SurfaceGrid(ByteArray(n * n), t2f, 35.0, -97.0, 0L, "t")
    val fl = Winter.freezingLine(g)
    ok(fl.isNotEmpty(), "a freezing line")
    val ys = fl[0].filterIndexed { i, _ -> i % 2 == 1 }
    val want = Winter.HALF_KM - (12f / 30f) * 2 * Winter.HALF_KM
    ok(ys.all { Math.abs(it - want) < 8f }, "the line is where it's 32 °F: ${ys.take(3)} vs $want")
    val layer = MapRaster.layerOf(fl)
    ok(layer.chunks.isNotEmpty() && layer.pts.size == (fl.sumOf { it.size / 2 + 1 }) * 2, "drawable layer")
    val proj = Geo.Aeqd(35.0, -97.0)
    val frame = RasterFrame.around(proj, 400f, 400f, 4f)
    val vals = FloatArray(frame.w * frame.h) { k -> frame.y((k / frame.w).toFloat()) }      // = northing
    val cl = MapRaster.contours(vals, frame, 100f)
    ok(cl.map { it.level }.toSet() == setOf(-100f, 0f, 100f), "levels ${cl.map { it.level }.toSet()}")
    ok(cl.first { it.level == 100f }.pts.filterIndexed { i, _ -> i % 2 == 1 }.all { Math.abs(it - 100f) < 6f }, "100 km line")
    ok(MapRaster.barbParts(65f).toList() == listOf(1, 1, 1), "65 kt: flag, barb, half barb")
}

private fun listings() {
    val html = """<a href="sfav2_CONUS_24h_2026011512.grb2">x</a> <a href="sfav2_CONUS_24h_2026011512_grid184.grb2">x</a>
<a href="sfav2_CONUS_48h_2026011600_grid184.grb2">x</a> <a href="sfav2_CONUS_24h_2026011600_grid184.grb2">"""
    val files = Winter.snowfallFiles(html, 24)
    ok(files.map { it.first } == listOf(utc("2026-01-15T12:00:00Z"), utc("2026-01-16T00:00:00Z")), "times ${files.map { Time.iso(it.first) }}")
    ok(files[0].second.endsWith("_grid184.grb2"), "prefers the 2.5 km grid")
    ok(Winter.snowfallFiles(html, 48).size == 1, "48 h")
    ok(Winter.qpeProduct(72) == "MultiSensor_QPE_72H_Pass2_00.00", "QPE product")
    val keys = listOf("CONUS/x/20261015/MRMS_x_20261015-120000.grib2.gz", "CONUS/x/20261015/MRMS_x_20261015-130000.grib2.gz",
        "CONUS/x/20261015/MRMS_x_20261015-140000.grib2.gz")
    ok(Winter.pickKey(keys, utc("2026-10-15T13:30:00Z"), 180)?.first == keys[1], "newest before the time")
    ok(Winter.pickKey(keys, utc("2026-10-15T20:00:00Z"), 180) == null, "too old")
}

private fun areaAlerts() {
    val json = """{"features": [{"geometry": null, "properties": {"event": "Winter Storm Warning", "sent": "2026-01-05T10:00:00Z",
      "expires": "2026-01-06T10:00:00Z", "senderName": "NWS Norman OK", "geocode": {"SAME": ["040109", "040017"]},
      "parameters": {}, "id": "x"}}]}"""
    val ring = floatArrayOf(-97.7f, 35.1f, -97.3f, 35.1f, -97.3f, 35.4f, -97.7f, 35.1f)
    val a = Alerts.parse(json) { fips -> if (fips == 40109 || fips == 40017) listOf(ring) else emptyList() }
    ok(a.size == 1 && a[0].isArea && a[0].rings.size == 2, "county outlines for a zone alert")
    ok(a[0].variant == "WSW" && Alerts.group("Winter Storm Warning") == "winter" && Alerts.group("Hurricane Watch") == "tropical", "groups")
    ok(Alerts.group("Tornado Watch") == "watch", "storm watches stay watches")
    ok(Alerts.VARIANTS.getValue("SVR").priority > Alerts.VARIANTS.getValue("HUW").priority &&
        Alerts.VARIANTS.getValue("HUW").priority > Alerts.VARIANTS.getValue("WWY").priority, "storm warnings draw on top")
    ok(Alerts.URL.contains("Blizzard%20Warning") && Alerts.URL.contains("Hurricane%20Warning"), "asked for in the feed")
}

private fun snowReports() {
    val json = """{"features": [{"geometry": {"type": "Point", "coordinates": [-97.5, 35.5]}, "properties": {"typetext": "HEAVY SNOW",
      "magf": 8.5, "unit": "INCH", "city": "Norman", "st": "OK", "valid": "2026-01-05T12:00:00Z"}}]}"""
    val r = Reports.parseLsr(json)[0]
    ok(r.kind == ReportKind.SNOW && r.snowInches == 8.5, "snow total ${r.magnitude} ${r.snowInches}")
    ok(Reports.kindOf("SNOWMELT FLOODING") == ReportKind.FLOOD, "snowmelt flooding is flooding")
}

private const val CURRENT = """{"activeStorms": [{"id": "al142026", "binNumber": "AT1", "name": "Harper", "classification": "HU",
  "intensity": "105", "pressure": "958", "latitudeNumeric": 27.0, "longitudeNumeric": -89.0, "movementDir": 335,
  "movementSpeed": 10, "lastUpdate": "2026-09-10T21:00:00.000Z", "publicAdvisory": {"advNum": "12", "url": "https://example/adv"}}]}"""

private fun currentStorms() {
    val s = Tropical.parseCurrent(CURRENT)[0]
    ok(s.title == "Major Hurricane Harper" && s.bin == "AT1" && s.links.any { it.first == "Public advisory" }, "storm ${s.title}")
    ok(s.advisory == "12" && s.basin == "AL" && s.wind == 105.0, "advisory, basin, wind")
    ok(Tropical.category(105.0) == "3" && Tropical.category(33.0) == "TD" && Tropical.category(140.0) == "5", "categories")
}

private fun arrival() {
    val adv = utc("2026-09-10T21:00:00Z")
    val f = Models.fmt("dd/HHmm")
    val feats = listOf(Triple(0, 27.0 to -89.0, 105), Triple(12, 28.0 to -89.6, 110), Triple(24, 29.5 to -90.0, 100), Triple(36, 31.0 to -90.2, 70))
        .joinToString(",") { (tau, ll, w) ->
            """{"geometry": {"type": "Point", "coordinates": [${ll.second}, ${ll.first}]}, "properties": {"tau": $tau, "maxwind": $w,
              "validtime": "${f.format(java.util.Date(adv + tau * Models.HOUR))}"}}"""
        }
    val pts = Tropical.parseForecastPoints("""{"features": [$feats]}""", adv)
    ok(pts.map { it.tau } == listOf(0, 12, 24, 36) && pts[2].timeMs == adv + 24 * Models.HOUR, "forecast points")
    val st = Tropical.parseCurrent(CURRENT)[0]
    st.forecast = pts
    st.radii = listOf(0, 12, 24, 36).map { Tropical.Radii(it, 34, 150.0, 150.0, 120.0, 120.0, adv + it * Models.HOUR) }
    val a = Tropical.arrival(st, 29.95, -90.07)!!                   // New Orleans
    ok(a.closestKm < 60 && a.closestMs > adv + 20 * Models.HOUR && a.closestMs < adv + 32 * Models.HOUR, "closest ${a.closestKm} km")
    ok(a.tsArrive != null && a.tsArrive < a.closestMs, "tropical-storm winds arrive first")
    ok(Tropical.validFrom(utc("2026-09-30T21:00:00Z"), "02/0600") == utc("2026-10-02T06:00:00Z"), "into the next month")
}

private fun adeck() {
    val lines = ArrayList<String>()
    for ((cyc, off) in listOf("2026091012" to 0, "2026091018" to 5)) for (tau in listOf(0, 12, 24))
        lines.add("AL, 14, $cyc, 03, AVNO, ${tau.toString().padStart(3)}, ${270 + tau + off}N, 0890W, 90, 960, HU")
    lines.add("AL, 14, 2026090100, 03, NVGM,   0, 200N, 0700W, 30, 1005, TD")
    val aids = Tropical.parseAdeck(lines.joinToString("\n"))
    val avno = aids.first { it.tech == "AVNO" }
    ok(avno.cycleMs == utc("2026-09-10T18:00:00Z") && Math.abs(avno.points[1].lat - 28.7) < 1e-9 && avno.points[0].lon == -89.0, "AVNO newest cycle")
    ok(aids.none { it.tech == "NVGM" }, "old runs dropped")
    ok(Tropical.ll("0885W") == -88.5 && Tropical.ll("254N") == 25.4 && Tropical.ll("x") == null, "ATCF positions")
}

private const val HURDAT = """AL092022,                IAN,     4,
20220927, 0000,  , HU, 23.4N,  83.2W, 100,  960,
20220928, 0000,  , HU, 25.6N,  82.9W, 135,  937,
20220928, 1905, L, HU, 26.7N,  82.2W, 130,  941,
20220929, 1200,  , TS, 28.6N,  80.4W,  55,  990,
AL012022,               ALEX,     1,
20220605, 1800,  , TS, 31.0N,  77.0W,  45, 1000,
"""

private fun hurdat() {
    val storms = Tropical.parseHurdat2(HURDAT)
    val ian = storms[0]
    ok(storms.size == 2 && ian.name == "IAN" && ian.year == 2022 && ian.peak == 135.0 && ian.landfalls.size == 1, "IAN")
    ok(ian.landfalls[0].timeMs == utc("2022-09-28T19:05:00Z"), "landfall time")
    ok(Tropical.tracksNear(storms, 26.6, -82.0, 100.0).map { it.first.name } == listOf("IAN"), "tracks near Fort Myers")
    val track = Tropical.interpolateTrack(ian.points, 60)
    ok(track.first().timeMs == ian.points.first().timeMs && track.last().timeMs == ian.points.last().timeMs, "interpolated track")
    ok(ian.title == "Ian (2022) – category 4 hurricane, 135 kt", "title ${ian.title}")
    ok(Tropical.hurdatName("""<a href="hurdat2-1851-2023-051124.txt"> <a href="hurdat2-1851-2024-040425.txt">""", "AL") == "hurdat2-1851-2024-040425.txt", "newest HURDAT2")
    ok(Tropical.bdeckNames("""bal012026.dat bal022026.dat bep012026.dat bal012026.dat""", "al", 2026) == listOf("al012026", "al022026"), "b-decks")
}

private fun buoys() {
    val text = "#STN       LAT      LON  YYYY MM DD hh mm WDIR WSPD   GST WVHT  DPD APD MWD   PRES  PTDY  ATMP  WTMP  DEWP  VIS   TIDE\n" +
        "#text\n42040  29.207  -88.237 2026 09 10 20 50 110  10.0  14.0  2.0  MM  MM  MM 1004.5  MM  27.0  28.0  MM  MM  MM\n"
    val b = Tropical.parseNdbc(text)[0]
    ok(b.id == "42040" && Math.abs(b.wspdKt!! - 19.44) < 0.01 && Math.abs(b.waveFt!! - 6.56) < 0.01, "buoy wind and waves")
    ok(b.pres == 1004.5 && Math.abs(b.waterF!! - 82.4) < 1e-6, "pressure, water temperature")
}

private fun raster() {
    // a 1° lat/lon grid whose value is the latitude: resampled around Oklahoma it reads back the latitude
    val ni = 60; val nj = 40
    val g = Grib2.Grid(FloatArray(ni * nj) { k -> (50 - k / ni).toFloat() }, ni, nj, 50.0, -125.0, 1.0, 1.0, 0, null, 0L)
    val proj = Geo.Aeqd(35.0, -97.0)
    val frame = RasterFrame.around(proj, 600f, 600f, 10f)
    val v = MapRaster.resample(g, g.values, MapRaster.sampleIndex(g, frame), true)
    val mid = v[(frame.h / 2) * frame.w + frame.w / 2]
    ok(Math.abs(mid - 35f) < 0.1f, "middle reads 35°: $mid")
    val c = MapRaster.colorize(floatArrayOf(Float.NaN, 0.5f, 3000f, 99999f), Models.CAPE)
    ok(c[0] == 0 && c[1] == 0 && c[3] == Models.CAPE.last().second && (c[2] ushr 24) > 0, "colours")
}
