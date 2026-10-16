package com.libexil.radarforge.tests

import com.libexil.radarforge.core.*
import java.io.DataInputStream
import java.io.File

private fun ok(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

private val DATA = File("tests/data")

private fun doubles(f: File): DoubleArray {
    val n = (f.length() / 8).toInt()
    DataInputStream(f.inputStream().buffered()).use { s -> return DoubleArray(n) { s.readDouble() } }
}

fun registerModels() {
    Extra.all.add("GRIB2: model packings vs eccodes" to ::gribPackings)
    Extra.all.add("GRIB2: MRMS png / simple / bitmap" to ::gribMrms)
    Extra.all.add("GRIB2: Lambert round trip" to ::lccRoundTrip)
    Extra.all.add("models: index files and keys" to ::idxParse)
    Extra.all.add("models: computed fields (fake run)" to ::computeFields)
    Extra.all.add("models: accumulation buckets" to ::buckets)
    Extra.all.add("models: colours and values" to ::colours)
    registerWx()
}

private fun gribPackings() {
    val raw = File(DATA, "model_packings.grib2").readBytes()
    val t = doubles(File(DATA, "model_packings_truth.bin"))
    val truth = t.copyOfRange(0, 2400); val lats = t.copyOfRange(2400, 2442); val lons = t.copyOfRange(2442, 2484)
    val gfsTruth = t.copyOfRange(2484, 3484); val gfsShape = intArrayOf(t[3484].toInt(), t[3485].toInt())
    val grids = Grib2.decodeAll(raw)
    ok(grids.size == 5, "5 messages, got ${grids.size}")
    val tmp = DoubleArray(2)
    for ((k, g) in grids.take(4).withIndex()) {
        ok(g.lcc != null && g.ni == 60 && g.nj == 40, "message $k is a 60x40 Lambert grid (${g.ni}x${g.nj})")
        var worst = 0.0
        for (i in 0 until 2400) {
            val a = g.values[i]; val b = truth[i]
            ok(a.isNaN() == b.isNaN(), "message $k missing value at $i")
            if (!b.isNaN()) worst = maxOf(worst, Math.abs(a - b))
        }
        ok(worst < 0.07, "message $k values off by $worst")
        var n = 0
        for (j in 0 until 40 step 7) for (i in 0 until 60 step 9) {
            g.index(lats[n], lons[n], tmp); n++
            ok(Math.abs(tmp[0] - j) < 1e-3 && Math.abs(tmp[1] - i) < 1e-3, "message $k: point $j,$i lands at ${tmp[0]},${tmp[1]}")
        }
    }
    val gfs = grids[4]
    ok(gfs.ni == gfsShape[1] && gfs.nj == gfsShape[0], "GFS shape ${gfs.ni}x${gfs.nj}")
    var worst = 0.0
    for (i in 0 until 1000) worst = maxOf(worst, Math.abs(gfs.values[i] - gfsTruth[i]))
    ok(worst < 1e-3, "GFS values off by $worst")
    ok(!gfs.sample(10.0, 179.9, true).isNaN() && !gfs.sample(10.0, -179.9, true).isNaN(), "global grid wraps at the dateline")
}

private fun gribMrms() {
    val e = doubles(File(DATA, "mrms_expected.bin"))
    for (name in listOf("png", "simple", "bitmap")) {
        val g = Grib2.decode(File(DATA, "mrms_$name.grib2.gz").readBytes())
        ok(g.ni == 40 && g.nj == 25 && Math.abs(g.la1 - 40.0) < 1e-6 && Math.abs(g.lo1 + 100.0) < 1e-6 && Math.abs(g.di - 0.01) < 1e-9,
            "$name grid ${g.ni}x${g.nj} ${g.la1} ${g.lo1} ${g.di}")
        ok(Time.iso(g.refTimeMs) == "2026-10-01 21:30:00Z", "$name time ${Time.iso(g.refTimeMs)}")
        for (i in e.indices) {
            if (name == "bitmap" && i == 10 * 40 + 10) { ok(g.values[i].isNaN(), "bitmap hole"); continue }
            ok(Math.abs(g.values[i] - e[i]) < 0.01, "$name value $i: ${g.values[i]} vs ${e[i]}")
        }
        ok(Math.abs(g.sample(39.9, -99.95, false) - e[10 * 40 + 5]) < 0.01, "$name sample")
        ok(g.sample(45.0, -99.95, false).isNaN(), "$name outside is NaN")
    }
    try { Grib2.decode("hello world".toByteArray()); ok(false, "garbage accepted") } catch (_: Grib2.GribException) {}
}

private fun lccRoundTrip() {
    val l = Grib2.Lcc.of(38.5, 38.5, -97.5, 6371229.0)
    val o = DoubleArray(2); val b = DoubleArray(2)
    for ((lat, lon) in listOf(25.0 to -120.0, 38.5 to -97.5, 49.0 to -70.0)) {
        l.forward(lat, lon, o); l.inverse(o[0], o[1], b)
        ok(Math.abs(b[0] - lat) < 1e-6 && Math.abs(b[1] - lon) < 1e-6, "round trip $lat,$lon -> ${b[0]},${b[1]}")
    }
}

private const val IDX = """1:0:d=2026101512:REFC:entire atmosphere:2 hour fcst:
2:100:d=2026101512:APCP:surface:0-2 hour acc fcst:
3:200:d=2026101512:APCP:surface:1-2 hour acc fcst:
4:300:d=2026101512:MXUPHL:5000-2000 m above ground:1-2 hour max fcst:
5:400:d=2026101512:TMP:2 m above ground:2 hour fcst:
"""

private val RUN = Time.parseIso("2026-10-15T12:00:00Z")

private fun idxParse() {
    val rows = Models.parseIdx(IDX)
    ok(rows[0].end == 99L && rows.last().end == -1L, "byte ranges")
    ok(Models.findEntry(rows, "APCP", "surface", "acc0", 2)?.start == 100L, "acc0")
    ok(Models.findEntry(rows, "APCP", "surface", "acc1", 2)?.start == 200L, "acc1")
    ok(Models.findEntry(rows, "MXUPHL", "5000-2000 m above ground", "max1", 2)?.start == 300L, "max1")
    ok(Models.findEntry(rows, "TMP", "2 m above ground")?.start == 400L, "fcst")
    ok(Models.findEntry(rows, "CAPE", "surface") == null, "missing field")
    val hrrr = Models.MODELS.getValue("hrrr")
    ok(hrrr.key(RUN, 3) == "hrrr.20261015/conus/hrrr.t12z.wrfsfcf03.grib2", "hrrr key ${hrrr.key(RUN, 3)}")
    ok(hrrr.fhOf(hrrr.key(RUN, 3) + ".idx") == 3 && hrrr.maxFh(RUN) == 48, "hrrr hours")
    val gfs = Models.MODELS.getValue("gfs")
    ok(gfs.key(RUN, 6) == "gfs.20261015/12/atmos/gfs.t12z.pgrb2.0p25.f006" && gfs.fhOf("x.f123.idx") == 123, "gfs key")
    ok(Models.MODELS.getValue("nam").fhOf("nam.t12z.conusnest.hiresf07.tm00.grib2.idx") == 7, "nam fh")
    ok(Models.MODELS.getValue("rap").key(RUN, 1) == "rap.20261015/rap.t12z.awp130pgrbf01.grib2", "rap key")
    ok(hrrr.prefix(RUN) == "hrrr.20261015/conus/hrrr.t12z.wrfsfc", "hrrr prefix ${hrrr.prefix(RUN)}")
}

/** Serves a small Lambert "model run": each (var, level, fcst) is the same message. */
private fun fakeRun(fields: List<Triple<String, String, String>>, msgIndex: Int = 2) {
    val msg = Grib2.messages(File(DATA, "model_packings.grib2").readBytes())[msgIndex]
    val data = java.io.ByteArrayOutputStream()
    val idx = StringBuilder()
    for ((i, f) in fields.withIndex()) {
        idx.append("${i + 1}:${data.size()}:d=2026101512:${f.first}:${f.second}:${f.third}:\n")
        data.write(msg)
    }
    val bytes = data.toByteArray()
    Models.cacheDir = null
    Models.fetcher = { url, a, b ->
        if (url.endsWith(".idx")) idx.toString().toByteArray()
        else bytes.copyOfRange(a.toInt(), if (b < 0) bytes.size else b.toInt() + 1)
    }
}

private fun computeFields() {
    fakeRun(listOf(Triple("CAPE", "surface", "2 hour fcst"), Triple("HLCY", "1000-0 m above ground", "2 hour fcst"),
        Triple("HLCY", "3000-0 m above ground", "2 hour fcst"), Triple("VUCSH", "0-6000 m above ground", "2 hour fcst"),
        Triple("VVCSH", "0-6000 m above ground", "2 hour fcst"), Triple("TMP", "2 m above ground", "2 hour fcst"),
        Triple("DPT", "2 m above ground", "2 hour fcst"), Triple("HGT", "500 mb", "2 hour fcst"),
        Triple("UGRD", "500 mb", "2 hour fcst"), Triple("VGRD", "500 mb", "2 hour fcst")))
    val truth = doubles(File(DATA, "model_packings_truth.bin")).copyOfRange(0, 2400)
    val sh = Models.compute("hrrr", RUN, 2, "shear06")
    ok("u" in sh.extra && "v" in sh.extra, "shear barbs")
    var worst = 0.0
    for (i in truth.indices) if (!truth[i].isNaN()) worst = maxOf(worst, Math.abs(sh.value[i] - Math.hypot(truth[i], truth[i]) * Models.MS2KT))
    ok(worst < 0.5, "shear (rotated to earth winds) off by $worst")
    val stp = Models.compute("hrrr", RUN, 2, "stp")
    ok(stp.validMs == RUN + 2 * Models.HOUR, "valid time")
    val want = truth.filter { !it.isNaN() }.maxOf { (it / 1500) * (it / 150) * 1.5 }
    val got = stp.value.filter { !it.isNaN() }.max()
    ok(Math.abs(got - want) <= 0.01 * want, "STP max $got vs $want")
    val h5 = Models.compute("hrrr", RUN, 2, "h500")
    ok(setOf("z", "u", "v").all { it in h5.extra }, "500 mb extras")
    val v = Models.compute("hrrr", RUN, 2, "vort500")
    ok(v.note.startsWith("from the model winds"), "vorticity from winds: ${v.note}")
    try { Models.compute("hrrr", RUN, 2, "pwat"); ok(false, "pwat should be unavailable") } catch (_: Models.Unavailable) {}
    // precipitation type: reflectivity plus categorical flags
    fakeRun(listOf(Triple("REFC", "entire atmosphere", "2 hour fcst"), Triple("CSNOW", "surface", "2 hour fcst")))
    val pt = Models.compute("hrrr", RUN, 2, "ptype")
    val vals = pt.value.filter { !it.isNaN() }
    ok(vals.isNotEmpty() && vals.all { it in 110f..199f }, "all snow where CSNOW is set: ${vals.take(5)}")
}

private fun buckets() {
    // GFS-style: 0-3 at hour 3, 0-6 at hour 6 -> 3-6 is the difference; each file holds the same message here
    fakeRun(listOf(Triple("APCP", "surface", "0-3 hour acc fcst"), Triple("APCP", "surface", "0-6 hour acc fcst")))
    val gfs = Models.MODELS.getValue("gfs")
    // the fake serves both rows in every file, so hour 6 has 0-6 and hour 3 has 0-3
    val a = Models.increment(gfs, RUN, 0, 3)
    val b = Models.increment(gfs, RUN, 3, 6)
    val g = Grib2.decode(Grib2.messages(File(DATA, "model_packings.grib2").readBytes())[2])
    for (i in a.indices) {
        if (g.values[i].isNaN()) continue
        ok(Math.abs(a[i] - g.values[i]) < 1e-4, "0-3 amount")
        ok(Math.abs(b[i]) < 1e-4, "3-6 amount is the difference")
    }
    val acc = Models.accumulate("gfs", RUN, 6, "qpf", hours = listOf(0, 3, 6))
    val i = g.values.indexOfFirst { !it.isNaN() }
    ok(Math.abs(acc.value[i] - maxOf(g.values[i], 0f) / 25.4f) < 1e-4, "sum over steps ${acc.value[i]}")
}

private fun colours() {
    val fd = Models.FIELDS.getValue("cape_sb")
    ok(Models.colorOf(fd.stops!!, 50f) == 0, "below the first stop is clear")
    ok(Models.colorOf(fd.stops, 1000f) == fd.stops.first { it.first == 1000f }.second, "exact stop")
    ok(Models.colorOf(fd.stops, 99999f) == fd.stops.last().second, "above the top")
    ok(Models.fmtValue(Models.FIELDS.getValue("ptype"), 135f) == "Snow, 35 dBZ", "ptype text")
    ok(Models.fmtValue(Models.FIELDS.getValue("pwat"), 1.234f) == "1.23 in", "pwat text")
    ok(Models.FIELDS.size == 30 && Models.FIELDS.values.all { it.group in Models.GROUPS }, "30 fields in 4 groups")
    for (fd2 in Models.FIELDS.values) ok(fd2.stops != null || fd2.palette != null, "${fd2.id} has colours")
}
