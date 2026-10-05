package com.libexil.radarforge.tests

import com.libexil.radarforge.core.*
import java.io.File

private fun ok(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

fun registerMore() {
    Extra.all.add("S3 listing parse" to ::s3Listing)
    Extra.all.add("latest volume search" to ::latestVolume)
    Extra.all.add("NWS alerts parse" to ::alerts)
    Extra.all.add("sites" to ::sites)
    Extra.all.add("basemap read + project" to ::basemap)
    Extra.all.add("loop: read just enough" to ::loopEnough)
    registerFeeds()
    registerLoop()
    registerData()
}

private fun s3Listing() {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Name>unidata-nexrad-level2</Name><Prefix>2026/09/30/KTLX/</Prefix>
<KeyCount>3</KeyCount><MaxKeys>1000</MaxKeys><IsTruncated>true</IsTruncated><NextContinuationToken>abc&amp;def</NextContinuationToken>
<Contents><Key>2026/09/30/KTLX/KTLX20260930_000338_V06</Key><LastModified>2026-09-30T00:09:00.000Z</LastModified><Size>9876543</Size></Contents>
<Contents><Key>2026/09/30/KTLX/KTLX20260930_000338_V06_MDM</Key><Size>12</Size></Contents>
<Contents><Key>2026/09/30/KTLX/KTLX20260930_001012_V06</Key><Size>8765432</Size></Contents>
<CommonPrefixes><Prefix>KTLX/12/</Prefix></CommonPrefixes></ListBucketResult>"""
    val l = S3.parseListing(xml)
    ok(l.objects.size == 3, "3 objects, got ${l.objects.size}")
    ok(l.truncated && l.nextToken == "abc&def", "continuation token ${l.nextToken}")
    ok(l.prefixes == listOf("KTLX/12/"), "prefixes ${l.prefixes}")
    val files = l.objects.mapNotNull(S3::parseL2)
    ok(files.size == 2, "MDM files skipped")
    ok(Time.iso(files[0].timeMs) == "2026-09-30 00:03:38Z", "L2 time ${Time.iso(files[0].timeMs)}")
    val c = S3.parseChunk("KTLX/417/20260930-193522-012-I")!!
    ok(c.volume == 417 && c.number == 12 && c.kind == 'I' && Time.iso(c.timeMs) == "2026-09-30 19:35:22Z", "chunk parse")
    val chunks = S3.newestChunks(listOf(S3.Obj("KTLX/5/20250101-000000-001-S", 1), S3.Obj("KTLX/5/20260930-100000-002-I", 1),
        S3.Obj("KTLX/5/20260930-100000-001-S", 1)))
    ok(chunks.map { it.number } == listOf(1, 2) && chunks.all { it.stamp.startsWith("20260930") }, "newest chunks only")
    ok(S3.listUrl(S3.CHUNKS, "KTLX/", "/").contains("prefix=KTLX%2F&delimiter=%2F"), "list url encoding")
}

private fun latestVolume() {
    // volume numbers wrap at 999; stamps increase with time
    fun stamps(newest: Int, present: List<Int>): (Int) -> String = { n ->
        val age = (newest - n + 999) % 999          // volumes ago
        String.format("20260930-%06d", 999999 - age * 7)
    }
    val all = (1..999).toList()
    for (newest in listOf(1, 2, 500, 998, 999)) {
        val got = S3.findLatestVolume(all, stamps(newest, all))
        ok(got == newest, "newest $newest found $got")
    }
    val few = listOf(3, 4, 5, 6, 997, 998, 999)          // only some volumes kept
    ok(S3.findLatestVolume(few, stamps(6, few)) == 6, "sparse wrap")
    ok(S3.nextVolume(999) == 1 && S3.nextVolume(5) == 6, "next volume")
}

private fun alerts() {
    val json = """{"type":"FeatureCollection","features":[
 {"id":"https://api.weather.gov/alerts/x1","type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-97.5,35.2],[-97.2,35.2],[-97.2,35.5],[-97.5,35.5],[-97.5,35.2]]]},
  "properties":{"id":"x1","event":"Tornado Warning","sent":"2026-09-30T19:41:00-05:00","expires":"2026-09-30T20:15:00-05:00",
   "headline":"Tornado Warning issued","description":"...TORNADO EMERGENCY FOR MOORE...","instruction":"TAKE COVER NOW!","areaDesc":"Cleveland, OK",
   "senderName":"NWS Norman OK","parameters":{"tornadoDetection":["OBSERVED"],"maxHailSize":["1.75"]}}},
 {"id":"x2","type":"Feature","geometry":null,"properties":{"id":"x2","event":"Tornado Watch","geocode":{"SAME":["040027","040109"]},"description":""}},
 {"id":"x3","type":"Feature","geometry":null,"properties":{"id":"x3","event":"Air Quality Alert"}}
]}"""
    val rings = mapOf(40027 to listOf(floatArrayOf(-97.6f, 35.0f, -97.1f, 35.0f, -97.1f, 35.3f, -97.6f, 35.0f)))
    val a = Alerts.parse(json) { rings[it] ?: emptyList() }
    ok(a.size == 2, "2 alerts kept, got ${a.size}")
    val tor = a.first { it.event.startsWith("Tornado E") }
    ok(tor.contains(35.35, -97.35) && !tor.contains(35.6, -97.35), "point in polygon")
    ok(tor.tags.contains("OBSERVED") && tor.tags.contains("hail 1.75 in"), "tags ${tor.tags}")
    ok(Time.iso(tor.expiresMs) == "2026-10-01 01:15:00Z", "expires ${Time.iso(tor.expiresMs)}")
    ok(tor.office == "Norman OK", "office")
    val watch = a.first { it.isWatch }
    ok(watch.rings.size == 1, "watch filled from county outlines")
    ok(a.last().style.priority >= a.first().style.priority, "sorted by priority")
    ok(Alerts.NWS_COLORS.getValue("Flash Flood Warning") == 0xff8b0000.toInt() && Alerts.NWS_COLORS.getValue("Tornado Watch") == 0xffffff00.toInt() &&
        Alerts.STYLES.getValue("Extreme Wind Warning").color == 0xffff8c00.toInt(), "NWS colours")
    ok(Alerts.STYLES.keys == Alerts.NWS_COLORS.keys, "every style has an NWS colour")
    ok(tor.variant == "TORE", "tornado emergency variant ${tor.variant}")
    ok(watch.variant == "TOA", "watch variant")
    fun params(vararg kv: Pair<String, String>) = kv.associate { (k, v) -> k to listOf(v) }
    val cases = listOf(
        Triple("Tornado Warning", params("tornadoDetection" to "RADAR INDICATED"), "TOR"),
        Triple("Tornado Warning", params("tornadoDetection" to "OBSERVED"), "TORR"),
        Triple("Tornado Warning", params("tornadoDetection" to "OBSERVED", "tornadoDamageThreat" to "CONSIDERABLE"), "TORP"),
        Triple("Tornado Warning", params("tornadoDamageThreat" to "CATASTROPHIC"), "TORE"),
        Triple("Severe Thunderstorm Warning", params("thunderstormDamageThreat" to "CONSIDERABLE"), "SVRC"),
        Triple("Severe Thunderstorm Warning", params("thunderstormDamageThreat" to "DESTRUCTIVE"), "SVRD"),
        Triple("Severe Thunderstorm Warning", params("tornadoDetection" to "POSSIBLE"), "SVR"),
        Triple("Flash Flood Warning", params("flashFloodDamageThreat" to "CONSIDERABLE"), "FFWC"),
        Triple("Flash Flood Emergency", params(), "FFWE"),
        Triple("Snow Squall Warning", params(), "SQW"),
        Triple("Severe Thunderstorm Watch", params(), "SVA"))
    for ((ev, p, want) in cases) ok(Alerts.variantOf(ev, p) == want, "variant of $ev $p = ${Alerts.variantOf(ev, p)}, expected $want")
    ok(Alerts.BASE_CODE.values.all { it in Alerts.VARIANTS } && Alerts.CLASSIC_PRESET.keys.all { it in Alerts.VARIANTS }, "codes known")
    ok(Alerts.VARIANTS.getValue("TORE").line.kind == "double" && Alerts.VARIANTS.getValue("SVRD").line.innerShare > 0f, "line kinds")
    for ((ev, g) in mapOf("Tornado Warning" to "tornado", "Tornado Emergency" to "tornado", "Tornado Watch" to "watch",
            "Severe Thunderstorm Warning" to "severe", "Severe Thunderstorm Watch" to "watch", "Flash Flood Warning" to "flood",
            "Flash Flood Emergency" to "flood", "Extreme Wind Warning" to "other", "Special Weather Statement" to "other"))
        ok(Alerts.group(ev) == g, "group of $ev is ${Alerts.group(ev)}, expected $g")
    ok(Alerts.URL.contains("event=Tornado%20Warning,") && !Alerts.URL.contains("Emergency") && Alerts.URL.contains("Severe%20Thunderstorm%20Watch"), "alerts url ${Alerts.URL}")
}

private fun sites() {
    val s = Sites.parse(File("app/assets/sites.json").readText())
    ok(s.size == 160, "160 radars, got ${s.size}")
    val tlx = s.first { it.id == "KTLX" }
    ok(Sites.nearest(s, 35.47, -97.52)?.id == "KTLX", "nearest to OKC")
    ok(tlx.title.contains("OK"), "title ${tlx.title}")
}

private fun basemap() {
    val t0 = System.nanoTime()
    val m = Basemap.read(File("app/assets/basemap.bin").readBytes())
    val t1 = System.nanoTime()
    ok(m.layers.keys.containsAll(listOf("states", "counties", "countries", "lakes", "roads", "roads2")), "layers ${m.layers.keys}")
    ok(m.cities.names.size > 20000, "cities")
    val p = Geo.Aeqd(35.3331, -97.2778)
    val states = ProjectedLayer.project(m.layers.getValue("states"), p, 1600.0)
    val counties = ProjectedLayer.project(m.layers.getValue("counties"), p, 1600.0)
    val t2 = System.nanoTime()
    ok(states.chunks.isNotEmpty() && counties.chunks.size > 5, "chunks")
    val maxR = counties.pts.toList().chunked(2).filter { it[0] < 1e8f }.maxOf { Math.hypot(it[0].toDouble(), it[1].toDouble()) }
    ok(maxR < 2600, "projected within range ($maxR km)")
    ok(m.countyRings(40027).isNotEmpty(), "Cleveland County OK outline")
    val cities = ProjectedCities.project(m.cities, p, 600.0)
    ok(cities.names.take(12).contains("Oklahoma City") && cities.names[0] == "Dallas", "biggest nearby cities ${cities.names.take(12)}")
    println(String.format("       read %.0f ms, project states+counties %.0f ms (%d pts)", (t1 - t0) / 1e6, (t2 - t1) / 1e6, counties.pts.size / 2))
}

private fun loopEnough() {
    val f = File(dataDir, "Level2_KDDC_20200823_204121.ar2v")
    if (!f.exists()) return
    val bytes = f.readBytes()
    val whole = Level2.read(bytes)
    val wholeTilts = Tilts.build(whole)
    for ((tilt, moments) in listOf(0.5f to setOf("REF", "VEL"), 0.5f to setOf("RHO"), 1.5f to setOf("REF", "VEL", "ZDR"), 3.1f to setOf("VEL"))) {
        val copy = java.io.ByteArrayOutputStream()
        val v = Level2.readStreaming(java.io.ByteArrayInputStream(bytes), "", copy, setOf("CFP"), moments, LoopSupport.enoughFor(tilt, moments))
        val frame = LoopSupport.trimTo(v, tilt)
        val ts = Tilts.build(frame)
        ok(ts.size == 1, "tilt $tilt: one tilt kept, got ${ts.map { it.label }}")
        val t = ts[0]
        ok(Math.abs(t.elevation - tilt) < 0.3f, "tilt $tilt: kept ${t.elevation}")
        for (m in moments) {
            val sw = t.sweep(m)
            ok(sw != null && sw.complete, "tilt $tilt: $m complete")
            // same data as reading the whole file (first scan at that angle)
            val ref = wholeTilts[Tilts.closest(wholeTilts, tilt)].scans.first().sweeps[m]!!
            ok(sw!!.nRays == ref.nRays && sw.moments.getValue(m).nGates == ref.moments.getValue(m).nGates, "tilt $tilt $m matches whole-file decode")
        }
        ok(frame.sweeps.all { s -> s.moments.keys.all { it in moments } }, "only requested moments kept")
        ok(copy.size() < bytes.size * (if (tilt < 1f) 0.4 else 0.97), "tilt $tilt read ${copy.size()} of ${bytes.size}")
        println(String.format("       tilt %.1f %s: read %.1f of %.1f MB", tilt, moments, copy.size() / 1e6, bytes.size / 1e6))
    }
}
