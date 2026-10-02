package com.libexil.radarforge.tests

import com.libexil.radarforge.core.*

private fun ok(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

private fun near(a: Double, b: Double, tol: Double) = Math.abs(a - b) <= tol

fun registerFeeds() {
    Extra.all.add("placefile parse" to ::placefile)
    Extra.all.add("Spotter Network chasers" to ::chasers)
    Extra.all.add("storm reports (IEM LSR)" to ::lsr)
    Extra.all.add("storm reports (Spotter Network)" to ::snReports)
    Extra.all.add("SPC outlook + MCD" to ::spc)
    Extra.all.add("measure: bearing, destination, ETAs" to ::measure)
    Extra.all.add("warning tracking key (VTEC)" to ::vtec)
}

// a trimmed copy of www.spotternetwork.org/feeds/gr.txt (phone / e-mail replaced)
private val SN_POSITIONS = """Refresh: 1
Threshold: 999
Title: Spotter Network Positions (96dpi) - All
Font: 1, 11, 0, "Courier New"
IconFile: 1, 30, 30, 15, 15, "https://www.spotternetwork.org/iconsheets/Spotternet_096.png"
IconFile: 2, 21, 35, 10, 17, "https://www.spotternetwork.org/iconsheets/Arrows_096.png"
IconFile: 6, 30, 30, 15, 15, "https://www.spotternetwork.org/iconsheets/Spotternet_New_096.png"

Object: 40.0495262,-111.6625595
Icon: 0,0,000,6,6,"Ryatt Brown\n2026-10-01 21:45:06 UTC\nSTATIONARY"
Text: 15, 10, 1, "Ryatt Brown"
End:
Object: 32.7556152,-97.6989136
Icon: 0,0,3,2,15,
Icon: 0,0,000,6,2,"Victor Florez\n2026-10-01 21:59:38 UTC\nHeading: N (3)\nPhone: 555-0100\nEmail: someone@example.com\nNote: FF/EMR"
Text: 15, 10, 1, "Victor Florez"
End:
Object: 39.0543365,-96.7667007
Icon: 0,0,265,2,15,
Icon: 0,0,000,6,6,"Richard Gantt (K8RCG)\n2026-10-01 21:43:07 UTC\nHeading: W (265)\nHam: 147.52000\nWeb: https://example.com/chase, live"
Text: 15, 10, 1, "K8RCG"
End:
"""

private fun placefile() {
    val p = Placefile.parse(SN_POSITIONS)
    ok(p.refreshMin == 1, "refresh ${p.refreshMin}")
    ok(p.iconFiles[2]?.endsWith("Arrows_096.png") == true, "icon files ${p.iconFiles}")
    ok(p.objects.size == 3, "objects ${p.objects.size}")
    val o = p.objects[1]
    ok(near(o.lat, 32.7556152, 1e-9) && near(o.lon, -97.6989136, 1e-9), "lat/lon")
    ok(o.icons.size == 2 && o.icons[0].angle == 3f && o.icons[0].file == 2 && o.icons[0].text.isEmpty(), "arrow icon")
    ok(o.icons[1].text.startsWith("Victor Florez\n2026-10-01"), "hover text line breaks: ${o.icons[1].text}")
    ok(o.texts == listOf("Victor Florez"), "text ${o.texts}")
    ok(Placefile.fields("""0,0,000,6,6,"a, b, c"""") == listOf("0", "0", "000", "6", "6", "a, b, c"), "quoted commas")
    ok(Placefile.fields("0,0,3,2,15,") == listOf("0", "0", "3", "2", "15", ""), "trailing empty field")
    // loose icons (outside Object blocks) carry their own position
    val loose = Placefile.parse("Icon: 35.5, -97.5, 0, 1, 2, \"Hail 1.00\"\nText: 36, -98, 1, \"Label\"")
    ok(loose.objects.size == 2 && loose.objects[0].lat == 35.5 && loose.objects[0].icons[0].text == "Hail 1.00", "loose icon")
}

private fun chasers() {
    val c = Chasers.parse(SN_POSITIONS)
    ok(c.size == 3, "count ${c.size}")
    ok(c[0].name == "Ryatt Brown" && !c[0].moving && c[0].heading == null, "stationary")
    ok(Time.iso(c[0].timeMs) == "2026-10-01 21:45:06Z", "time ${Time.iso(c[0].timeMs)}")
    ok(c[1].heading == 3f && c[1].moving, "heading ${c[1].heading}")
    ok(c[1].info.none { it.first.equals("Phone", true) || it.first.equals("Email", true) }, "phone / e-mail not kept: ${c[1].info}")
    ok(c[1].info == listOf("Note" to "FF/EMR"), "info ${c[1].info}")
    ok(c[2].label == "K8RCG" && c[2].name == "Richard Gantt (K8RCG)" && c[2].heading == 265f, "label / heading")
    ok(c[2].info.any { it.first == "Web" && it.second == "https://example.com/chase, live" }, "web link with comma")
    ok(Chasers.parseTime("STATIONARY") == 0L, "not a time")
}

private fun lsr() {
    val json = """{"type": "FeatureCollection", "features": [
 {"type": "Feature", "id": 0, "properties": {"wfo": "GLD", "type": "R", "magf": 4.06, "county": "Wallace", "typetext": "RAIN", "state": "KS", "remark": "C6099 9.9 SSE Sharon Springs, KS Total near end of event.", "city": "9 SSE Sharon Springs", "source": "Mesonet", "unit": "Inch", "valid": "2026-10-01T20:03:00Z", "lon": -101.68, "lat": 38.77, "qualifier": "M", "st": "KS", "magnitude": "4.06"}, "geometry": {"type": "Point", "coordinates": [-101.68, 38.77]}},
 {"type": "Feature", "id": 1, "properties": {"wfo": "OUN", "type": "H", "magf": 1.75, "county": "Cleveland", "typetext": "HAIL", "remark": null, "city": "3 N Norman", "source": "Trained Spotter", "unit": "Inch", "valid": "2026-10-01T21:10:00Z", "st": "OK", "magnitude": "1.75"}, "geometry": {"type": "Point", "coordinates": [-97.44, 35.26]}},
 {"type": "Feature", "id": 2, "properties": {"wfo": "OUN", "typetext": "TSTM WND GST", "magf": 70.0, "unit": "MPH", "city": "Moore", "st": "OK", "county": "Cleveland", "valid": "2026-10-01T21:20:00Z", "source": "ASOS"}, "geometry": {"type": "Point", "coordinates": [-97.48, 35.33]}},
 {"type": "Feature", "id": 3, "properties": {"wfo": "OUN", "typetext": "TORNADO", "magf": null, "unit": "", "city": "Newcastle", "st": "OK", "valid": "2026-10-01T21:25:00Z", "source": "Public"}, "geometry": {"type": "Point", "coordinates": [-97.6, 35.24]}},
 {"type": "Feature", "id": 4, "properties": {"typetext": "TSTM WND DMG"}, "geometry": null}
]}"""
    val r = Reports.parseLsr(json)
    ok(r.size == 4, "count ${r.size} (one without a position)")
    ok(r[0].kind == ReportKind.OTHER && r[0].title == "Rain 4.06 in" && r[0].magnitude == "4.06 in", "rain: ${r[0].title} ${r[0].magnitude}")
    ok(r[1].kind == ReportKind.HAIL && r[1].title == "Hail 1.75 in", "hail: ${r[1].title}")
    ok(r[1].place == "3 N Norman, OK (Cleveland Co.)" && r[1].origin == "NWS OUN" && r[1].remark == "", "place ${r[1].place}")
    ok(Time.iso(r[1].timeMs) == "2026-10-01 21:10:00Z", "time")
    ok(r[2].kind == ReportKind.WIND_GUST && r[2].title == "Wind gust 70 mph", "gust: ${r[2].title}")
    ok(r[3].kind == ReportKind.TORNADO && r[3].title == "Tornado" && r[3].magnitude == "", "tornado: ${r[3].title}")
    for ((t, k) in listOf("FUNNEL CLOUD" to ReportKind.FUNNEL, "WALL CLOUD" to ReportKind.WALL_CLOUD, "WATERSPOUT" to ReportKind.TORNADO,
        "MARINE TSTM WIND" to ReportKind.WIND_GUST, "NON-TSTM WND DMG" to ReportKind.WIND_DAMAGE, "FLASH FLOOD" to ReportKind.FLOOD,
        "EXTR WIND CHILL" to ReportKind.OTHER, "SNOW" to ReportKind.OTHER, "MARINE HAIL" to ReportKind.HAIL)) {
        ok(Reports.kindOf(t) == k, "$t -> ${Reports.kindOf(t)}")
    }
    ok(Reports.lsrUrl(6).endsWith("lsr.geojson?hours=6") && Reports.lsrUrl(500).endsWith("hours=72"), "url")
}

private fun snReports() {
    val text = """Refresh: 1
Threshold: 999
Title: Spotter Network (96dpi) - Reports Only
Font: 1, 11, 0, "Courier New"
IconFile: 3, 30, 30, 15, 15, "https://www.spotternetwork.org/iconsheets/SN_Reports_096.png"
Object: 35.20,-97.60
Icon: 0,0,000,3,1,"Reported By: Hailey Smith\nTornado\nTime: 2026-10-01 21:30:00 UTC\nNotes: Rope tornado west of town"
End:
Object: 35.30,-97.40
Icon: 0,0,000,3,4,"Reported By: Sam Jones\nHail\nSize: 1.25 in\nTime: 2026-10-01 21:35:00 UTC"
End:
Object: 35.40,-97.30
Icon: 0,0,000,3,4,"Reported By: Pat Lee\nHail\nTime: 2026-10-01 21:40:00 UTC\nNotes: no tornado seen, small funnel earlier"
End:
"""
    val r = Reports.parseSpotterNetwork(text)
    ok(r.size == 3, "count ${r.size}")
    ok(r[2].kind == ReportKind.HAIL, "the type line wins over the notes: ${r[2].kind}")
    ok(r[0].kind == ReportKind.TORNADO, "reporter's name doesn't make it hail: ${r[0].kind}")
    ok(r[0].origin == "Spotter Network" && r[0].remark.contains("Rope tornado"), "remark")
    ok(Time.iso(r[0].timeMs) == "2026-10-01 21:30:00Z", "time ${Time.iso(r[0].timeMs)}")
    ok(r[1].kind == ReportKind.HAIL, "hail")
}

private fun spc() {
    val json = """{"type": "FeatureCollection", "name": "x", "crs": {}, "features": [
 {"type": "Feature", "properties": {"issue": "2026-10-01T20:00:00Z", "product_issue": "2026-10-01T19:49:00Z", "expire": "2026-10-02T12:00:00Z", "threshold": "TSTM", "category": "CATEGORICAL", "product_id": "p"},
  "geometry": {"type": "MultiPolygon", "coordinates": [[[[-100, 30], [-90, 30], [-90, 40], [-100, 40], [-100, 30]], [[-97, 34], [-96, 34], [-96, 35], [-97, 35], [-97, 34]]], [[[-80, 30], [-79, 30], [-79, 31], [-80, 30]]]]}},
 {"type": "Feature", "properties": {"issue": "2026-10-01T20:00:00Z", "expire": "2026-10-02T12:00:00Z", "threshold": "0.05", "category": "TORNADO"},
  "geometry": {"type": "Polygon", "coordinates": [[[-98, 33], [-95, 33], [-95, 36], [-98, 36], [-98, 33]]]}}
]}"""
    val o = Spc.parseOutlook(json)
    ok(o.size == 2 && o[0].category == "CATEGORICAL" && o[0].threshold == "TSTM" && o[0].rings.size == 3, "outlook parse")
    ok(o[0].contains(32.0, -95.0), "inside TSTM")
    ok(!o[0].contains(34.5, -96.5), "inside the hole is outside")
    ok(o[0].contains(30.2, -79.3) && !o[0].contains(30.3, -79.8) && !o[0].contains(45.0, -95.0), "second part / outside")
    ok(o[1].contains(34.5, -96.5) && Spc.probText(o[1].threshold) == "5%" && Spc.probText("SIGN") == "significant", "probability")
    ok(Time.iso(o[0].expireMs) == "2026-10-02 12:00:00Z", "expire")
    ok(Spc.catIndex("ENH") == 3 && Spc.catIndex("0.05") == -1, "category order")

    fun at(s: String) = Time.parseIso(s)
    val u1 = Spc.outlookUrls(at("2026-10-01T21:00:00Z"))
    ok(u1.first().endsWith("valid=2026-10-01&cycle=20"), "21Z -> 20Z: ${u1.first()}")
    ok(u1[1].endsWith("valid=2026-10-01&cycle=16") && u1.size == 4, "then 1630Z: $u1")
    val u2 = Spc.outlookUrls(at("2026-10-02T02:00:00Z"))
    ok(u2.first().endsWith("valid=2026-10-01&cycle=1"), "02Z -> previous date's 01Z: ${u2.first()}")
    val u3 = Spc.outlookUrls(at("2026-10-01T05:20:00Z"))
    ok(u3.first().endsWith("valid=2026-10-01&cycle=6"), "05:20Z tries the 06Z one early: ${u3.first()}")
    val u4 = Spc.outlookUrls(at("2026-10-01T16:00:00Z"))
    ok(u4.first().endsWith("valid=2026-10-01&cycle=16") && u4[1].endsWith("cycle=13"), "16:00Z: $u4")

    val mcd = """{"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {"product_id": "202609292309-KWNS-ACUS11-SWOMCD", "year": 2026, "num": 2335, "issue": "2026-09-29T23:09:00Z", "expire": "2026-09-30T01:45:00Z", "watch_confidence": 20.0, "concerning": "SEVERE POTENTIAL...WATCH UNLIKELY"},
     "geometry": {"type": "Polygon", "coordinates": [[[-104, 31], [-101, 31], [-101, 34], [-104, 34], [-104, 31]]]}},
     {"type": "Feature", "properties": {"product_id": "x", "num": 2336, "issue": "2026-09-29T23:26:00Z", "expire": "2026-09-30T01:30:00Z", "watch_confidence": null, "concerning": ""}, "geometry": {"type": "Polygon", "coordinates": [[[-90, 31], [-89, 31], [-89, 32], [-90, 31]]]}}]}"""
    val m = Spc.parseMcd(mcd)
    ok(m.size == 2 && m[0].number == 2335 && m[0].watchChance == 20 && m[1].watchChance == null, "mcd parse")
    ok(m[0].contains(32.0, -102.0) && !m[0].contains(35.0, -102.0), "mcd contains")
    ok(Spc.mcdTextUrl(m[0].productId).endsWith("/nwstext/202609292309-KWNS-ACUS11-SWOMCD"), "mcd text url")
    ok(Spc.mcdPage(2335, 2026) == "https://www.spc.noaa.gov/products/md/2026/md2335.html", "mcd page")
}

private fun measure() {
    // Norman, OK -> Oklahoma City: about 30 km, a little west of north
    val b = Geo.bearingDeg(35.2226, -97.4395, 35.4676, -97.5164)
    ok(b > 340 && b < 352, "bearing $b")
    ok(near(Geo.bearingDeg(35.0, -97.0, 35.0, -96.0), 89.7, 0.5), "east")
    val d = Geo.destination(35.0, -97.0, 45.0, 100.0)
    ok(near(Geo.distanceKm(35.0, -97.0, d[0], d[1]), 100.0, 0.01), "destination distance")
    ok(near(Geo.bearingDeg(35.0, -97.0, d[0], d[1]), 45.0, 0.05), "destination bearing")
    ok(Geo.distText(1.0, "km") == "1.00 km" && Geo.distText(16.2, "mi") == "10.1 mi" && Geo.distText(16.0, "mi") == "9.94 mi" && Geo.distText(500.0, "nm") == "270 nm", "dist text")
    ok(Geo.speedText(55.56, "kts") == "30 kts" && Geo.speedText(48.28, "mph") == "30 mph", "speed text")
    ok(near(Measure.pathKm(listOf(doubleArrayOf(35.0, -97.0), doubleArrayOf(35.0, -96.0), doubleArrayOf(36.0, -96.0))),
        Geo.distanceKm(35.0, -97.0, 35.0, -96.0) + Geo.distanceKm(35.0, -96.0, 36.0, -96.0), 1e-9), "path length")

    // a storm moving east 60 km in 60 minutes past three towns
    val cities = ProjectedCities(floatArrayOf(30f, 15f, 45f, 70f, -5f), floatArrayOf(2f, -4f, 20f, 0f, 0f),
        intArrayOf(9000, 8000, 7000, 6000, 5000), arrayOf("Mid", "Early", "OffTrack", "Beyond", "Behind"))
    val e = Measure.etas(0f, 0f, 60f, 0f, 60.0, cities, 8.0)
    ok(e.map { it.name } == listOf("Early", "Mid"), "towns on the track, soonest first: ${e.map { it.name }}")
    ok(near(e[0].minutes, 15.0, 1e-6) && near(e[1].minutes, 30.0, 1e-6) && near(e[1].offKm, 2.0, 1e-6), "minutes / offset")
    ok(Measure.etaAt(0f, 0f, 60f, 0f, 60.0, 90f, 3f, 8.0)?.let { near(it, 90.0, 1e-6) } == true, "beyond the arrow keeps going")
    ok(Measure.etaAt(0f, 0f, 60f, 0f, 60.0, -10f, 0f, 8.0) == null, "behind the storm")
    ok(Measure.tickMinutes(60) == 15 && Measure.tickMinutes(120) == 30, "ticks")
}

private fun vtec() {
    fun alert(id: String, vtec: String, threat: String) = """{"id":"$id","type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-97.5,35.2],[-97.2,35.2],[-97.2,35.5],[-97.5,35.5],[-97.5,35.2]]]},
 "properties":{"id":"$id","event":"Tornado Warning","sent":"2026-10-01T21:30:00-05:00","expires":"2026-10-01T22:15:00-05:00","headline":"h","description":"d","instruction":"","areaDesc":"Cleveland, OK","senderName":"NWS Norman OK",
 "parameters":{"VTEC":["$vtec"],"tornadoDetection":["RADAR INDICATED"],"tornadoDamageThreat":["$threat"]}}}"""
    val json = """{"type":"FeatureCollection","features":[
 ${alert("urn:1", "/O.NEW.KOUN.TO.W.0042.261001T0230Z-261001T0315Z/", "")},
 ${alert("urn:2", "/O.CON.KOUN.TO.W.0042.000000T0000Z-261001T0315Z/", "CONSIDERABLE")}
]}"""
    val a = Alerts.parse(json)
    ok(a.size == 2, "parsed ${a.size}")
    ok(a.all { it.trackKey == "KOUN.TO.W.0042" }, "same key for both updates: ${a.map { it.trackKey }}")
    ok(a.map { it.action }.toSet() == setOf("NEW", "CON"), "actions ${a.map { it.action }}")
    ok(a.any { it.variant == "TORP" }, "the update is the PDS line")
}
