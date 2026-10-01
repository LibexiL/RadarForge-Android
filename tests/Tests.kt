package com.libexil.radarforge.tests

import com.libexil.radarforge.core.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/**
 * JVM tests for the radar core (run with:  bash build.sh test).
 * Radar sample files are not in the repo; set RF_TESTDATA to a folder holding them.
 */
private val failures = ArrayList<String>()
private var checks = 0

private fun check(cond: Boolean, msg: () -> String) {
    checks++
    if (!cond) failures.add(msg())
}

private fun near(a: Double, b: Double, tol: Double) = Math.abs(a - b) <= tol

internal val dataDir = File(System.getenv("RF_TESTDATA") ?: "testdata")
private val refFile = File("tests/ref_level2.json")

private fun timed(label: String, block: () -> Unit) {
    val t0 = System.nanoTime()
    try {
        block()
        println(String.format("  ok   %-44s %6.0f ms", label, (System.nanoTime() - t0) / 1e6))
    } catch (e: Throwable) {
        failures.add("$label threw $e")
        println("  FAIL $label: $e")
        e.printStackTrace()
    }
}

fun main(args: Array<String>) {
    println("RadarForge core tests")
    timed("json") { testJson() }
    timed("colour tables") { testColorTables() }
    timed("geometry") { testGeo() }
    if (dataDir.isDirectory && refFile.exists()) {
        val ref = Json.parse(refFile.readText()).obj()
        timed("bzip2 whole file vs Python") { testBzip2(ref["_bz2"].obj()) }
        for ((name, r) in ref) {
            if (name.startsWith("_")) continue
            val f = File(dataDir, name)
            if (!f.exists()) { println("  skip $name (not found)"); continue }
            timed("level2 $name") { testLevel2(f, r.obj()) }
        }
        timed("streaming read stops early") { testStreaming() }
        timed("chunked read matches whole file") { testChunked() }
    } else {
        println("  (radar sample files not found in $dataDir - decoder tests skipped)")
    }
    registerMore()
    for (extra in Extra.all) timed(extra.first) { extra.second() }
    println()
    if (failures.isEmpty()) {
        println("ALL PASSED ($checks checks)")
    } else {
        println("${failures.size} FAILURE(S) of $checks checks:")
        failures.take(40).forEach { println("  - $it") }
        System.exit(1)
    }
}

/** Other test files register here. */
object Extra {
    val all = ArrayList<Pair<String, () -> Unit>>()
}

private fun testJson() {
    val v = Json.parse("""{"a": [1, 2.5, -3e2, true, null], "b": {"c": "x\"yé"}, "d": []}""").obj()
    check(v["a"].arr().size == 5) { "json array size" }
    check(v["a"].arr()[2].num() == -300.0) { "json exponent" }
    check(v["b"].obj()["c"].str() == "x\"yé") { "json escapes: ${v["b"]}" }
    check(v["d"].arr().isEmpty()) { "json empty array" }
}

private fun testColorTables() {
    val ct = ColorTable.parse("Units: KTS\nScale: 1.9426\nColor: -10 0 255 0\nSolidColor: 0 100 100 100\nColor: 10 255 0 0 255 255 0\n", "t")
    check(near(ct.dataScale("m/s").toDouble(), 1.9426, 1e-4)) { "pal scale" }
    check(ct.colorAt(-20f)[3] == 0) { "below first entry is transparent" }
    check(ct.colorAt(5f).take(3) == listOf(100, 100, 100)) { "solid colour" }
    check(ct.colorAt(15f)[0] == 255) { "last gradient" }
    val mph = ColorTable.parse("Units: MPH\nColor: 0 0 0 0\nColor: 50 255 255 255\n", "m")
    check(near(mph.dataScale("m/s").toDouble(), 2.236936, 1e-5)) { "units conversion" }
    for (name in listOf("REF", "VEL", "SW", "ZDR", "CC", "PHI", "KDP")) {
        val f = File("app/assets/palettes/$name.pal")
        val t = ColorTable.parse(f.readText(), name)
        check(t.entries.isNotEmpty()) { "$name.pal has entries" }
        check(t.lut(256).size == 1024) { "$name lut size" }
        check(ColorTable.family(t) == name || (name == "VEL" && ColorTable.family(t) == "VEL")) { "$name family = ${ColorTable.family(t)}" }
    }
}

private fun testGeo() {
    val p = Geo.Aeqd(35.333, -97.278)
    val xy = p.forward(36.0, -96.0)
    val back = p.inverse(xy[0], xy[1])
    check(near(back[0], 36.0, 1e-4) && near(back[1], -96.0, 1e-4)) { "aeqd round trip ${back.toList()}" }
    for (el in doubleArrayOf(0.5, 5.0, 19.5)) {
        val s = Geo.groundRange(200.0, el)
        check(near(Geo.slantRange(s, el), 200.0, 1e-6)) { "slant/ground inverse at $el" }
    }
    check(near(Geo.distanceKm(35.0, -97.0, 36.0, -97.0), 111.19, 0.1)) { "great circle" }
}

private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

private fun testBzip2(r: Map<String, Any?>) {
    val raw = File(dataDir, r["file"].str()).readBytes()
    val t0 = System.nanoTime()
    val out = Bzip2.decompress(raw)
    val ms = (System.nanoTime() - t0) / 1e6
    println(String.format("       %.1f MB -> %.1f MB in %.0f ms", raw.size / 1e6, out.size / 1e6, ms))
    check(out.size == r["len"].num().toInt()) { "bz2 length ${out.size} vs ${r["len"]}" }
    check(sha(out) == r["sha"].str()) { "bz2 content differs" }
    // truncated input keeps complete blocks and doesn't throw
    val part = Bzip2.decompress(raw, 0, raw.size / 3)
    check(part.size in 1 until out.size) { "truncated stream salvage ${part.size}" }
    check(part.contentEquals(out.copyOf(part.size))) { "salvaged bytes match" }
}

private fun testLevel2(f: File, r: Map<String, Any?>) {
    val t0 = System.nanoTime()
    val v = Level2.read(f.readBytes())
    println(String.format("       decoded %d sweeps in %.0f ms", v.sweeps.size, (System.nanoTime() - t0) / 1e6))
    val name = f.name
    check(v.site == r["site"].str() || r["site"].str().isEmpty()) { "$name site ${v.site}" }
    check(v.vcp == r["vcp"].num().toInt()) { "$name vcp ${v.vcp} vs ${r["vcp"]}" }
    val rs = r["sweeps"].arr()
    check(v.sweeps.size == rs.size) { "$name sweep count ${v.sweeps.size} vs ${rs.size}" }
    for ((i, rsw0) in rs.withIndex()) {
        val s = v.sweeps.getOrNull(i) ?: break
        val rsw = rsw0.obj()
        check(s.elevNum == rsw["elev_num"].num().toInt()) { "$name #$i elevNum" }
        check(near(s.elevation.toDouble(), rsw["elev"].num(), 0.01)) { "$name #$i elevation ${s.elevation} vs ${rsw["elev"]}" }
        check(s.nRays == rsw["nrays"].num().toInt()) { "$name #$i rays ${s.nRays} vs ${rsw["nrays"]}" }
        check(s.startMs == rsw["t"].num().toLong()) { "$name #$i time ${s.startMs} vs ${rsw["t"]}" }
        val rnyq = rsw["nyq"]
        if (rnyq != null) check(near(s.nyquist.toDouble(), rnyq.num(), 0.01)) { "$name #$i nyquist ${s.nyquist} vs $rnyq" }
        val rm = rsw["moments"].obj()
        check(s.moments.keys == rm.keys) { "$name #$i moments ${s.moments.keys} vs ${rm.keys}" }
        for ((mn, mr0) in rm) {
            val m = s.moments[mn] ?: continue
            val mr = mr0.obj()
            val shape = mr["shape"].arr().map { it.num().toInt() }
            check(m.nRays == shape[0] && m.nGates == shape[1]) { "$name #$i $mn shape ${m.nRays}x${m.nGates} vs $shape" }
            var sum = 0L
            if (m.data8 != null) for (b in m.data8!!) sum += b.toInt() and 0xff else for (c in m.data16!!) sum += c.code
            check(sum == mr["sum"].num().toLong()) { "$name #$i $mn checksum $sum vs ${mr["sum"]}" }
            check(near(m.firstGate.toDouble(), mr["first"].num(), 1e-3) && near(m.gateSpacing.toDouble(), mr["spacing"].num(), 1e-3)) { "$name #$i $mn gates" }
            check(m.scale == mr["scale"].num().toFloat() && m.offset == mr["offset"].num().toFloat()) { "$name #$i $mn scale/offset" }
            check(m.wordBits == mr["bits"].num().toInt()) { "$name #$i $mn word size" }
        }
    }
    val tilts = Tilts.build(v)
    val rt = r["tilts"].arr()
    check(tilts.size == rt.size) { "$name tilt count ${tilts.size} vs ${rt.size}" }
    for ((i, t0r) in rt.withIndex()) {
        val t = tilts.getOrNull(i) ?: break
        val tr = t0r.obj()
        check(near(t.elevation.toDouble(), tr["elev"].num(), 0.006)) { "$name tilt $i elevation ${t.elevation} vs ${tr["elev"]}" }
        check(t.scans.size == tr["n"].num().toInt()) { "$name tilt $i scans" }
        check(t.moments().sorted() == tr["moments"].arr().map { it.str() }) { "$name tilt $i moments" }
    }
    // a field can be built and sampled for every product present
    val t = tilts.first()
    for (p in Product.entries) {
        val sw = t.sweep(p.moment) ?: continue
        val fld = Field.from(p, v.site, v.startMs, sw) ?: continue
        check(fld.azCenter.toList().zipWithNext().all { (a, b) -> a <= b }) { "$name ${p.id} azimuths sorted" }
        var hits = 0
        for (az in 0 until 360 step 5) for (km in 10 until 200 step 10) if (fld.sample(az.toDouble(), km.toDouble()) != null) hits++
        check(hits > 0 || p != Product.REF) { "$name ${p.id} sample found nothing" }
    }
}

private fun testStreaming() {
    // bucket-style file: AR2V header + bzip2 records
    val f = File(dataDir, "Level2_KDDC_20200823_204121.ar2v")
    if (f.exists()) {
        val bytes = f.readBytes()
        val copy = java.io.ByteArrayOutputStream()
        val v = Level2.readStreaming(ByteArrayInputStream(bytes), "", copy) { b -> b.lastElevNum > 2 }
        check(v.sweeps.size >= 2) { "streaming got ${v.sweeps.size} sweeps" }
        check(copy.size() < bytes.size / 3) { "streaming read ${copy.size()} of ${bytes.size} bytes" }
        val tilts = Tilts.build(v)
        check(tilts.isNotEmpty() && tilts.first().sweep("VEL") != null && tilts.first().sweep("REF") != null) { "lowest tilt complete when streaming" }
        val v2 = Level2.read(copy.toByteArray())
        check(v2.sweeps.size == v.sweeps.size) { "cached prefix decodes (${v2.sweeps.size} vs ${v.sweeps.size})" }
        println(String.format("       read %.1f of %.1f MB for the lowest tilt", copy.size() / 1e6, bytes.size / 1e6))
        // reading to the end gives the whole volume
        val all = Level2.readStreaming(ByteArrayInputStream(bytes))
        check(all.sweeps.size == Level2.read(bytes).sweeps.size && all.complete) { "streaming full read" }
    }
    // uncompressed archive (gunzipped NCDC file) falls back to reading everything
    val g = File(dataDir, "KTLX20130520_201643_V06.gz")
    if (g.exists()) {
        val bytes = java.util.zip.GZIPInputStream(g.inputStream()).readBytes()
        val v = Level2.readStreaming(ByteArrayInputStream(bytes)) { b -> b.lastElevNum > 2 }
        check(v.sweeps.size == 17) { "uncompressed streaming ${v.sweeps.size}" }
    }
}

private fun testChunked() {
    // split an AR2V file into header+records "chunks" and feed them one by one, as live mode does
    val f = File(dataDir, "Level2_KDDC_20200823_204121.ar2v")
    if (!f.exists()) return
    val bytes = f.readBytes()
    val whole = Level2.read(bytes)
    val b = SweepBuilder()
    var p = 24
    var first = true
    var updates = 0
    while (p + 4 <= bytes.size) {
        val size = Math.abs(((bytes[p].toInt() and 0xff) shl 24) or ((bytes[p + 1].toInt() and 0xff) shl 16) or
            ((bytes[p + 2].toInt() and 0xff) shl 8) or (bytes[p + 3].toInt() and 0xff))
        val end = minOf(bytes.size, p + 4 + size)
        val chunk = bytes.copyOfRange(p, end)
        b.addBytes(if (first) bytes.copyOfRange(0, 24) + chunk else chunk)
        first = false
        p = end
        if (updates++ % 5 == 0) b.build()            // live mode rebuilds as chunks arrive
    }
    val v = b.build()
    check(v.sweeps.size == whole.sweeps.size) { "chunked sweeps ${v.sweeps.size} vs ${whole.sweeps.size}" }
    check(v.sweeps.zip(whole.sweeps).all { (a, c) -> a.nRays == c.nRays && a.moments.keys == c.moments.keys }) { "chunked sweep content" }
}
