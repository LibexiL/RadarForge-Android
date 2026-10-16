package com.libexil.radarforge.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Weather models (HRRR, NAM 3 km, RAP, GFS) from NOAA's buckets on AWS, like the desktop app's Models view:
 * the same field ids, inputs and maths (radarforge/features/models.py). Only the fields a map needs are
 * downloaded, with byte ranges taken from each file's .idx inventory.
 */
object Models {
    const val HOUR = 3_600_000L
    const val MS2KT = 1.943844f

    class ModelDef(val id: String, val name: String, val long: String, val bucket: String, val cycleEvery: Int,
                   val resKm: Float, val delayH: Float) {
        private fun ymd(ms: Long) = fmt("yyyyMMdd").format(java.util.Date(ms))
        private fun hh(ms: Long) = fmt("HH").format(java.util.Date(ms))

        fun key(run: Long, fh: Int): String {
            val d = ymd(run); val h = hh(run)
            return when (id) {
                "hrrr" -> "hrrr.$d/conus/hrrr.t${h}z.wrfsfcf%02d.grib2".format(fh)
                "nam" -> "nam.$d/nam.t${h}z.conusnest.hiresf%02d.tm00.grib2".format(fh)
                "rap" -> "rap.$d/rap.t${h}z.awp130pgrbf%02d.grib2".format(fh)
                else -> "gfs.$d/$h/atmos/gfs.t${h}z.pgrb2.0p25.f%03d".format(fh)
            }
        }

        fun prefix(run: Long): String { val k = key(run, 0); return k.substring(0, k.lastIndexOf('f')) }

        fun fhOf(key: String): Int? = Regex("""f(\d{2,3})(?:\.tm00)?(?:\.grib2)?\.idx$""").find(key)?.groupValues?.get(1)?.toInt()

        fun maxFh(run: Long): Int {
            val h = ((run / HOUR) % 24).toInt()
            return when (id) {
                "hrrr" -> if (h % 6 == 0) 48 else 18
                "rap" -> if (h % 6 == 3) 51 else 21
                "nam" -> 60
                else -> 384
            }
        }

        fun hours(run: Long): List<Int> =
            if (id == "gfs") (0..120).toList() + (123..384 step 3) else (0..maxFh(run)).toList()
    }

    val MODELS = linkedMapOf(
        "hrrr" to ModelDef("hrrr", "HRRR", "High-Resolution Rapid Refresh · 3 km · hourly", "noaa-hrrr-bdp-pds", 1, 3f, 0.8f),
        "nam" to ModelDef("nam", "NAM 3 km", "North American Mesoscale nest · 3 km · every 6 h", "noaa-nam-pds", 6, 3f, 1.5f),
        "rap" to ModelDef("rap", "RAP", "Rapid Refresh · 13 km · hourly", "noaa-rap-pds", 1, 13f, 0.8f),
        "gfs" to ModelDef("gfs", "GFS", "Global Forecast System · 0.25° · every 6 h", "noaa-gfs-bdp-pds", 6, 25f, 3.5f),
    )

    fun fmt(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    // ---------------------------------------------------------------- index files
    class IdxEntry(val num: Int, val start: Long, var end: Long, val variable: String, val level: String, val fcst: String) {
        val accSpan: IntArray?
            get() = Regex("""(\d+)-(\d+) hour (acc|ave|max|min)""").find(fcst)?.let {
                intArrayOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
            }
        val stat: String get() = Regex("""\d+-\d+ hour (acc|ave|max|min)""").find(fcst)?.groupValues?.get(1) ?: "fcst"
    }

    fun parseIdx(text: String): List<IdxEntry> {
        val rows = ArrayList<IdxEntry>()
        for (line in text.lines()) {
            val p = line.trim().split(":")
            if (p.size < 6) continue
            val num = p[0].split(".")[0].toIntOrNull() ?: continue
            val off = p[1].toLongOrNull() ?: continue
            rows.add(IdxEntry(num, off, -1, p[3].trim(), p[4].trim(), p[5].trim()))
        }
        rows.sortBy { it.start }
        for (i in 0 until rows.size - 1) rows[i].end = if (rows[i + 1].start > rows[i].start) rows[i + 1].start - 1 else -1
        return rows
    }

    /** stat: "fcst" (instantaneous), "acc0" (from the start), "acc1", "max1" (last hour), "ave", "accspan", "any". */
    fun findEntry(rows: List<IdxEntry>, v: String, level: String, stat: String = "fcst", fh: Int? = null,
                  span: IntArray? = null): IdxEntry? {
        val cands = rows.filter { it.variable.equals(v, true) && it.level.equals(level, true) }
        if (cands.isEmpty()) return null
        if (stat == "any") return cands[0]
        if (stat == "fcst") return cands.firstOrNull { it.fcst == "anl" || it.fcst.endsWith("hour fcst") || it.fcst.endsWith("min fcst") }
        for (r in cands) {
            val sp = r.accSpan ?: continue
            if (span != null && !(sp[0] == span[0] && sp[1] == span[1])) continue
            when (stat) {
                "acc0" -> if (r.stat == "acc" && sp[0] == 0 && (fh == null || sp[1] == fh)) return r
                "acc1" -> if (r.stat == "acc" && sp[1] - sp[0] == 1 && (fh == null || sp[1] == fh)) return r
                "accspan" -> if (r.stat == "acc") return r
                "max1" -> if (r.stat == "max" && (fh == null || sp[1] == fh)) return r
                "ave" -> if (r.stat == "ave" && (fh == null || sp[1] == fh)) return r
            }
        }
        return null
    }

    // ---------------------------------------------------------------- downloads and caches
    /** Set by the app: where downloaded fields are kept (pruned to [cacheBytes]). */
    var cacheDir: File? = null
    var cacheBytes = 300L shl 20
    /** Swapped in by the tests: (url, start, end) -> bytes; start < 0 means the whole file. */
    var fetcher: (String, Long, Long) -> ByteArray = { url, a, b -> if (a < 0) Net.getBytes(url, 60_000) else Net.getRange(url, a, b) }

    private fun cached(name: String, maxAgeMs: Long?, get: () -> ByteArray): ByteArray {
        val dir = cacheDir
        val f = if (dir != null) File(dir, name) else null
        if (f != null && f.length() > 0 && (maxAgeMs == null || System.currentTimeMillis() - f.lastModified() < maxAgeMs)) {
            return try { f.readBytes() } catch (_: Exception) { get() }
        }
        val data = get()
        if (f != null) try {
            dir!!.mkdirs()
            val tmp = File(dir, "$name.part")
            tmp.writeBytes(data)
            tmp.renameTo(f)
        } catch (_: Exception) {}
        return data
    }

    fun prune() {
        val files = cacheDir?.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= cacheBytes) break; total -= f.length(); f.delete() }
    }

    private fun h(s: String) = Integer.toHexString(s.hashCode()) + "_" + s.length

    fun url(m: ModelDef, key: String) = "https://${m.bucket}.s3.amazonaws.com/$key"

    fun idx(m: ModelDef, run: Long, fh: Int): List<IdxEntry> {
        val key = m.key(run, fh) + ".idx"
        return parseIdx(String(cached("idx_" + h(m.bucket + key), null) { fetcher(url(m, key), -1, -1) }, Charsets.UTF_8))
    }

    fun message(m: ModelDef, run: Long, fh: Int, e: IdxEntry): ByteArray {
        val key = m.key(run, fh)
        return cached("msg_" + h("${m.bucket}/$key/${e.start}/${e.end}"), null) { fetcher(url(m, key), e.start, e.end) }
    }

    /** Decoded fields kept in memory, up to [gridCells] values in all (an HRRR field is 1.9 million). */
    var gridCells = 14_000_000L
    private val grids = LinkedHashMap<String, Grib2.Grid>(16, 0.75f, true)

    fun decoded(m: ModelDef, run: Long, fh: Int, e: IdxEntry): Grib2.Grid {
        val k = "${m.id}/$run/$fh/${e.start}"
        synchronized(grids) { grids[k]?.let { return it } }
        val g = Grib2.decode(message(m, run, fh, e))
        synchronized(grids) {
            grids[k] = g
            var total = grids.values.sumOf { it.values.size.toLong() }
            val it = grids.entries.iterator()
            while (total > gridCells && grids.size > 1 && it.hasNext()) {
                val en = it.next()
                if (en.key == k) continue
                total -= en.value.values.size
                it.remove()
            }
        }
        return g
    }

    fun clearMemory() = synchronized(grids) { grids.clear() }

    class Run(val timeMs: Long, val hours: List<Int>)

    fun listRun(m: ModelDef, run: Long): List<Int> {
        val l = S3.listAll(m.bucket, m.prefix(run), maxPages = 4)
        val allowed = m.hours(run).toSet()
        return l.objects.filter { it.key.endsWith(".idx") }.mapNotNull { m.fhOf(it.key) }.filter { it in allowed }.distinct().sorted()
    }

    /** The newest runs that have at least their first hours, newest first. */
    fun latestRuns(m: ModelDef, count: Int = 3, now: Long = System.currentTimeMillis()): List<Run> {
        var t = now / HOUR * HOUR
        t -= ((t / HOUR) % m.cycleEvery) * HOUR
        val out = ArrayList<Run>()
        var tries = 0
        while (out.size < count && tries < count + 6) {
            tries++
            if (t <= now - (m.delayH * 0.25f * HOUR).toLong()) {
                val hrs = try { listRun(m, t) } catch (_: Exception) { emptyList() }
                if (hrs.isNotEmpty() && 0 in hrs) out.add(Run(t, hrs))
            }
            t -= m.cycleEvery * HOUR
        }
        return out
    }

    // ---------------------------------------------------------------- the field catalog
    class Spec(val v: String, val level: String, val stat: String = "fcst")
    class Res(val value: FloatArray, val extra: Map<String, FloatArray> = emptyMap())
    class Recipe(val inputs: LinkedHashMap<String, List<Spec>>, val note: String = "", val compute: (Map<String, FloatArray>, Grib2.Grid) -> Res)
    class Contour(val input: String, val interval: Float, val color: Int)

    class FieldDef(
        val id: String, val name: String, val group: String, val units: String,
        val stops: List<Pair<Float, Int>>?, val palette: String?, val recipes: List<Recipe>, val desc: String,
        val decimals: Int = 0, val categorical: Boolean = false, val contour: Contour? = null,
        val barbs: Boolean = false, val accum: String? = null, val zeroLine: Float? = null,
    )

    private fun argb(r: Int, g: Int, b: Int, a: Int = 235) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun ramp(vararg p: Pair<Number, Int>) = p.map { it.first.toFloat() to it.second }

    val CAPE = ramp(100 to argb(150, 190, 150, 130), 250 to argb(120, 180, 100, 190), 500 to argb(60, 160, 60), 1000 to argb(230, 225, 60),
        1500 to argb(240, 180, 40), 2000 to argb(240, 120, 30), 3000 to argb(215, 30, 30), 4000 to argb(200, 30, 150),
        5000 to argb(140, 40, 200), 6000 to argb(235, 215, 255))
    val CIN = ramp(-500 to argb(20, 30, 110), -300 to argb(40, 70, 170), -200 to argb(60, 110, 210), -100 to argb(110, 160, 235),
        -50 to argb(160, 200, 245), -25 to argb(200, 225, 250, 180), -10 to argb(220, 235, 255, 0))
    val SRH = ramp(50 to argb(170, 190, 210, 150), 100 to argb(110, 170, 220), 150 to argb(60, 180, 120), 200 to argb(230, 220, 60),
        300 to argb(240, 140, 40), 400 to argb(220, 40, 40), 500 to argb(200, 40, 160), 700 to argb(150, 60, 220), 1000 to argb(245, 220, 255))
    val SHEAR = ramp(20 to argb(180, 200, 220, 140), 30 to argb(100, 170, 220), 40 to argb(60, 180, 110), 50 to argb(230, 220, 60),
        60 to argb(240, 140, 40), 70 to argb(220, 40, 40), 80 to argb(190, 40, 170), 100 to argb(240, 200, 255))
    val STP = ramp(0.5 to argb(170, 200, 230, 150), 1 to argb(90, 160, 230), 2 to argb(60, 180, 90), 3 to argb(230, 220, 60),
        4 to argb(240, 150, 40), 6 to argb(220, 40, 40), 8 to argb(200, 40, 170), 10 to argb(140, 60, 220), 15 to argb(240, 210, 255))
    val SCP = ramp(1 to argb(170, 200, 230, 150), 2 to argb(90, 160, 230), 4 to argb(60, 180, 90), 8 to argb(230, 220, 60),
        12 to argb(240, 150, 40), 16 to argb(220, 40, 40), 20 to argb(200, 40, 170), 30 to argb(240, 210, 255))
    val UH = ramp(25 to argb(140, 170, 230, 200), 50 to argb(60, 180, 90), 75 to argb(230, 220, 60), 100 to argb(240, 140, 40),
        150 to argb(220, 40, 40), 200 to argb(200, 40, 170), 300 to argb(255, 255, 255))
    val TEMP = ramp(-40 to argb(230, 210, 255), -20 to argb(150, 80, 200), 0 to argb(70, 60, 190), 20 to argb(40, 120, 230),
        32 to argb(110, 200, 240), 40 to argb(60, 170, 110), 50 to argb(130, 200, 80), 60 to argb(230, 225, 80), 70 to argb(245, 175, 50),
        80 to argb(235, 110, 40), 90 to argb(210, 40, 40), 100 to argb(150, 20, 40), 115 to argb(240, 200, 220))
    val DEW = ramp(-10 to argb(120, 90, 60), 20 to argb(150, 120, 80), 40 to argb(200, 180, 140), 50 to argb(170, 210, 140),
        55 to argb(110, 190, 100), 60 to argb(40, 160, 60), 65 to argb(20, 120, 50), 70 to argb(30, 150, 170), 75 to argb(60, 90, 210),
        80 to argb(150, 70, 200))
    val RH = ramp(0 to argb(140, 90, 40), 20 to argb(190, 150, 90), 40 to argb(230, 220, 180), 60 to argb(170, 215, 160),
        80 to argb(70, 170, 90), 90 to argb(30, 130, 70), 100 to argb(20, 90, 60))
    val WIND = ramp(10 to argb(180, 200, 220, 120), 20 to argb(110, 170, 220), 30 to argb(60, 180, 110), 40 to argb(230, 220, 60),
        50 to argb(240, 140, 40), 60 to argb(220, 40, 40), 70 to argb(190, 40, 170), 90 to argb(240, 200, 255))
    val H500 = ramp(30 to argb(180, 200, 220, 120), 40 to argb(110, 170, 220), 50 to argb(60, 180, 110), 60 to argb(230, 220, 60),
        70 to argb(240, 150, 40), 85 to argb(220, 40, 40), 100 to argb(190, 40, 170), 130 to argb(240, 200, 255))
    val JET = ramp(50 to argb(180, 200, 220, 130), 70 to argb(110, 170, 220), 90 to argb(60, 180, 110), 110 to argb(230, 220, 60),
        130 to argb(240, 140, 40), 150 to argb(220, 40, 40), 170 to argb(190, 40, 170), 200 to argb(240, 200, 255))
    val PWAT = ramp(0.25 to argb(150, 110, 70, 160), 0.5 to argb(200, 170, 110), 0.75 to argb(180, 210, 150), 1.0 to argb(90, 180, 90),
        1.25 to argb(40, 150, 80), 1.5 to argb(40, 150, 190), 1.75 to argb(50, 90, 210), 2.0 to argb(130, 60, 200),
        2.5 to argb(220, 60, 160), 3.0 to argb(250, 200, 240))
    val LAPSE = ramp(5.5 to argb(200, 200, 200, 0), 6.0 to argb(200, 210, 160, 150), 6.5 to argb(230, 220, 80), 7.0 to argb(240, 170, 50),
        7.5 to argb(230, 100, 40), 8.0 to argb(210, 40, 40), 8.5 to argb(190, 40, 160), 9.5 to argb(240, 200, 255))
    val LI = ramp(-14 to argb(240, 200, 255), -10 to argb(170, 50, 200), -8 to argb(210, 40, 40), -6 to argb(240, 130, 40),
        -4 to argb(230, 220, 60), -2 to argb(120, 190, 100), 0 to argb(170, 200, 170, 120), 0.01 to argb(0, 0, 0, 0))
    val QPF = ramp(0.01 to argb(190, 230, 190, 200), 0.1 to argb(120, 200, 120), 0.25 to argb(40, 160, 60), 0.5 to argb(30, 110, 180),
        0.75 to argb(60, 60, 200), 1.0 to argb(150, 60, 200), 1.5 to argb(220, 50, 160), 2.0 to argb(200, 30, 40), 3.0 to argb(240, 130, 40),
        4.0 to argb(240, 220, 60), 6.0 to argb(255, 255, 200), 10.0 to argb(255, 255, 255))
    val SNOW = ramp(0.1 to argb(200, 225, 245, 180), 1 to argb(150, 200, 240), 2 to argb(90, 160, 230), 4 to argb(40, 100, 210),
        6 to argb(40, 50, 170), 8 to argb(120, 60, 200), 12 to argb(190, 70, 210), 18 to argb(230, 110, 200), 24 to argb(250, 170, 220),
        36 to argb(230, 230, 230), 48 to argb(180, 180, 180))
    val ICE = ramp(0.01 to argb(240, 200, 230, 200), 0.05 to argb(230, 150, 210), 0.1 to argb(210, 90, 190), 0.25 to argb(170, 40, 170),
        0.5 to argb(120, 20, 150), 0.75 to argb(80, 10, 110), 1.0 to argb(40, 0, 60))
    val VORT = ramp(8 to argb(240, 230, 160, 120), 12 to argb(240, 210, 80), 16 to argb(240, 150, 40), 20 to argb(220, 70, 40),
        28 to argb(190, 30, 90), 36 to argb(150, 30, 160), 50 to argb(240, 200, 255))
    val T850 = ramp(-30 to argb(230, 210, 255), -20 to argb(150, 80, 200), -10 to argb(60, 70, 200), -5 to argb(60, 130, 230),
        0 to argb(120, 200, 240), 5 to argb(70, 180, 110), 10 to argb(150, 210, 80), 15 to argb(235, 220, 70), 20 to argb(240, 150, 40),
        25 to argb(215, 50, 40), 30 to argb(150, 20, 50))
    val PTYPE_NAMES = listOf("Rain", "Snow", "Sleet", "Freezing rain")
    val PTYPE = ramp(10 to argb(130, 220, 120), 20 to argb(40, 170, 50), 30 to argb(20, 110, 30), 35 to argb(240, 230, 60),
        45 to argb(240, 140, 30), 55 to argb(210, 30, 30), 65 to argb(130, 10, 20), 99 to argb(130, 10, 20),
        110 to argb(180, 215, 245), 120 to argb(110, 170, 235), 130 to argb(40, 100, 220), 140 to argb(20, 40, 160), 199 to argb(20, 30, 120),
        210 to argb(250, 200, 160), 225 to argb(245, 150, 90), 235 to argb(225, 100, 40), 299 to argb(170, 60, 20),
        310 to argb(245, 180, 235), 325 to argb(220, 110, 210), 335 to argb(170, 40, 170), 399 to argb(100, 10, 110))
    val REF_STOPS = ramp(5 to argb(40, 80, 90, 120), 15 to argb(0, 155, 100), 30 to argb(0, 60, 0), 31 to argb(255, 213, 0),
        45 to argb(250, 0, 0), 58 to argb(60, 0, 10), 62 to argb(255, 255, 255), 75 to argb(0, 0, 0))

    private fun k2f(k: Float) = (k - 273.15f) * 1.8f + 32f
    private fun map(a: FloatArray, f: (Float) -> Float) = FloatArray(a.size) { f(a[it]) }
    private fun speed(u: FloatArray, v: FloatArray) = FloatArray(u.size) { hypot(u[it], v[it]) * MS2KT }
    private fun kt(a: FloatArray) = map(a) { it * MS2KT }
    private fun minus(a: FloatArray, b: FloatArray) = FloatArray(a.size) { a[it] - b[it] }

    private fun stp(d: Map<String, FloatArray>, su: FloatArray, sv: FloatArray): FloatArray {
        val cape = d.getValue("cape"); val srh = d.getValue("srh1"); val t2 = d.getValue("t2"); val td = d.getValue("td2")
        return FloatArray(cape.size) { i ->
            val lcl = maxOf(125f * (t2[i] - td[i]), 0f)
            val lclT = ((2000f - lcl) / 1000f).coerceIn(0f, 1f)
            val sh = hypot(su[i], sv[i])
            val shT = if (sh < 12.5f) 0f else minOf(sh, 30f) / 20f
            (maxOf(cape[i], 0f) / 1500f) * lclT * (srh[i] / 150f) * shT
        }
    }

    private fun scp(d: Map<String, FloatArray>, su: FloatArray, sv: FloatArray): FloatArray {
        val cape = d.getValue("cape"); val srh = d.getValue("srh3")
        return FloatArray(cape.size) { i ->
            val sh = hypot(su[i], sv[i])
            val shT = if (sh < 10f) 0f else minOf(sh / 20f, 1f)
            (maxOf(cape[i], 0f) / 1000f) * (srh[i] / 50f) * shT
        }
    }

    private fun vort(d: Map<String, FloatArray>, g: Grib2.Grid): FloatArray {
        val u = d.getValue("u"); val v = d.getValue("v")
        val out = FloatArray(u.size) { Float.NaN }
        val ll = DoubleArray(2)
        val l = g.lcc
        for (j in 1 until g.nj - 1) {
            g.latLon(j.toDouble(), g.ni / 2.0, ll)
            val lat = ll[0]
            val f = 2 * 7.292e-5 * sin(Math.toRadians(lat))
            val dx = l?.dx ?: (6371229.0 * cos(Math.toRadians(lat)) * Math.toRadians(g.di))
            val dy = (l?.dy ?: (6371229.0 * Math.toRadians(g.dj))) * (if (g.scan and 0x40 != 0) 1.0 else -1.0)
            for (i in 1 until g.ni - 1) {
                val k = j * g.ni + i
                val dvdx = (v[k + 1] - v[k - 1]) / (2 * dx)
                val dudy = (u[k + g.ni] - u[k - g.ni]) / (2 * dy)
                out[k] = ((dvdx - dudy + f) * 1e5).toFloat()
            }
        }
        return out
    }

    private fun ptype(d: Map<String, FloatArray>): FloatArray {
        val dbz = d.getValue("refc")
        val cr = d["crain"]; val cs = d["csnow"]; val ci = d["cicep"]; val cf = d["cfrzr"]; val t2 = d["t2"]
        return FloatArray(dbz.size) { i ->
            val z = if (dbz[i].isNaN()) -99f else dbz[i]
            if (z < 10f) return@FloatArray Float.NaN
            var t = 0f
            var none = true
            if (cr != null && cr[i] > 0.5f) { t = 0f; none = false }
            if (cs != null && cs[i] > 0.5f) { t = 1f; none = false }
            if (ci != null && ci[i] > 0.5f) { t = 2f; none = false }
            if (cf != null && cf[i] > 0.5f) { t = 3f; none = false }
            if (none && t2 != null && t2[i] <= 273.15f) t = 1f
            100f * t + z.coerceIn(10f, 99f)
        }
    }

    private fun ins(vararg p: Pair<String, List<Spec>>) = linkedMapOf(*p)
    private val REFC = listOf(Spec("REFC", "entire atmosphere"), Spec("REFC", "entire atmosphere (considered as a single layer)"))
    private val U10 = listOf(Spec("UGRD", "10 m above ground")); private val V10 = listOf(Spec("VGRD", "10 m above ground"))
    private val U5 = listOf(Spec("UGRD", "500 mb")); private val V5 = listOf(Spec("VGRD", "500 mb"))
    private val CAPE_MU = listOf(Spec("CAPE", "255-0 mb above ground"), Spec("CAPE", "180-0 mb above ground"), Spec("CAPE", "surface"))

    val FIELDS: LinkedHashMap<String, FieldDef> = linkedMapOf()
    val GROUPS = listOf("Severe", "Surface", "Upper air", "Precip & winter")

    private fun f(fd: FieldDef) { FIELDS[fd.id] = fd }

    init {
        f(FieldDef("refc", "Simulated radar", "Severe", "dBZ", null, "REF",
            listOf(Recipe(ins("refc" to REFC)) { d, _ -> Res(map(d.getValue("refc")) { if (it >= 5f) it else Float.NaN }) }),
            "What the radar would show if the model is right: the strongest echo in each column."))
        f(FieldDef("cape_sb", "Surface-based CAPE", "Severe", "J/kg", CAPE, null,
            listOf(Recipe(ins("cape" to listOf(Spec("CAPE", "surface")))) { d, _ -> Res(d.getValue("cape")) }),
            "Energy for storms, for a parcel lifted from the ground. 1000+ is enough for strong storms, 3000+ is a lot."))
        f(FieldDef("cape_ml", "Mixed-layer CAPE", "Severe", "J/kg", CAPE, null,
            listOf(Recipe(ins("cape" to listOf(Spec("CAPE", "90-0 mb above ground")))) { d, _ -> Res(d.getValue("cape")) }),
            "CAPE for a parcel mixed through the lowest 90 hPa – the usual measure of how unstable the air is."))
        f(FieldDef("cape_mu", "Most-unstable CAPE", "Severe", "J/kg", CAPE, null,
            listOf(Recipe(ins("cape" to CAPE_MU.take(2))) { d, _ -> Res(d.getValue("cape")) }),
            "CAPE of the most unstable parcel in the lowest 255 hPa – includes storms rooted above the ground."))
        f(FieldDef("cin_sb", "Surface-based CIN", "Severe", "J/kg", CIN, null,
            listOf(Recipe(ins("cin" to listOf(Spec("CIN", "surface")))) { d, _ -> Res(map(d.getValue("cin")) { if (it < -10f) it else Float.NaN }) }),
            "The cap holding surface parcels down. Below -50, storms struggle to form from the ground; below -200 the cap is strong."))
        f(FieldDef("srh01", "0–1 km helicity", "Severe", "m²/s²", SRH, null,
            listOf(Recipe(ins("h" to listOf(Spec("HLCY", "1000-0 m above ground")))) { d, _ -> Res(d.getValue("h")) }),
            "Low-level spin a storm can tilt up. 100+ supports tornadoes, 200+ strongly."))
        f(FieldDef("srh03", "0–3 km helicity", "Severe", "m²/s²", SRH, null,
            listOf(Recipe(ins("h" to listOf(Spec("HLCY", "3000-0 m above ground")))) { d, _ -> Res(d.getValue("h")) }),
            "Rotation potential for supercells. 150+ favours supercells, 300+ is high."))
        f(FieldDef("shear06", "0–6 km bulk shear", "Severe", "kt", SHEAR, null, listOf(
            Recipe(ins("su" to listOf(Spec("VUCSH", "0-6000 m above ground")), "sv" to listOf(Spec("VVCSH", "0-6000 m above ground")))) { d, _ ->
                Res(speed(d.getValue("su"), d.getValue("sv")), mapOf("u" to kt(d.getValue("su")), "v" to kt(d.getValue("sv"))))
            },
            Recipe(ins("u5" to U5, "v5" to V5, "u10" to U10, "v10" to V10), "10 m to 500 hPa") { d, _ ->
                val su = minus(d.getValue("u5"), d.getValue("u10")); val sv = minus(d.getValue("v5"), d.getValue("v10"))
                Res(speed(su, sv), mapOf("u" to kt(su), "v" to kt(sv)))
            }),
            "Difference between the wind at the ground and at 6 km. 35+ kt organises storms into supercells.", barbs = true))
        f(FieldDef("stp", "Significant tornado parameter", "Severe", "", STP, null, listOf(
            Recipe(ins("cape" to listOf(Spec("CAPE", "surface")), "srh1" to listOf(Spec("HLCY", "1000-0 m above ground")),
                "su" to listOf(Spec("VUCSH", "0-6000 m above ground")), "sv" to listOf(Spec("VVCSH", "0-6000 m above ground")),
                "t2" to listOf(Spec("TMP", "2 m above ground")), "td2" to listOf(Spec("DPT", "2 m above ground")))) { d, _ ->
                Res(stp(d, d.getValue("su"), d.getValue("sv")))
            },
            Recipe(ins("cape" to listOf(Spec("CAPE", "surface")), "srh1" to listOf(Spec("HLCY", "1000-0 m above ground"), Spec("HLCY", "3000-0 m above ground")),
                "u5" to U5, "v5" to V5, "u10" to U10, "v10" to V10, "t2" to listOf(Spec("TMP", "2 m above ground")),
                "td2" to listOf(Spec("DPT", "2 m above ground"))), "approximate: 10 m–500 hPa shear") { d, _ ->
                Res(stp(d, minus(d.getValue("u5"), d.getValue("u10")), minus(d.getValue("v5"), d.getValue("v10"))))
            }),
            "CAPE, cloud base, 0–1 km helicity and deep shear together. Above 1, the ingredients for significant tornadoes are there.",
            decimals = 1))
        f(FieldDef("scp", "Supercell composite", "Severe", "", SCP, null, listOf(
            Recipe(ins("cape" to CAPE_MU, "srh3" to listOf(Spec("HLCY", "3000-0 m above ground")),
                "su" to listOf(Spec("VUCSH", "0-6000 m above ground")), "sv" to listOf(Spec("VVCSH", "0-6000 m above ground")))) { d, _ ->
                Res(scp(d, d.getValue("su"), d.getValue("sv")))
            },
            Recipe(ins("cape" to CAPE_MU, "srh3" to listOf(Spec("HLCY", "3000-0 m above ground")), "u5" to U5, "v5" to V5, "u10" to U10,
                "v10" to V10), "approximate: 10 m–500 hPa shear") { d, _ ->
                Res(scp(d, minus(d.getValue("u5"), d.getValue("u10")), minus(d.getValue("v5"), d.getValue("v10"))))
            }),
            "Most-unstable CAPE, 0–3 km helicity and deep shear. Above 1 favours supercells.", decimals = 1))
        f(FieldDef("uh25", "Updraft helicity (2–5 km)", "Severe", "m²/s²", UH, null,
            listOf(Recipe(ins("uh" to listOf(Spec("MXUPHL", "5000-2000 m above ground", "max1"), Spec("MXUPHL", "5000-2000 m above ground", "any")))) { d, _ ->
                Res(map(d.getValue("uh")) { if (it >= 25f) it else Float.NaN })
            }),
            "The strongest rotating updraft in the past hour – the model's own supercells."))
        f(FieldDef("lapse75", "700–500 mb lapse rate", "Severe", "°C/km", LAPSE, null,
            listOf(Recipe(ins("t7" to listOf(Spec("TMP", "700 mb")), "t5" to listOf(Spec("TMP", "500 mb")), "z7" to listOf(Spec("HGT", "700 mb")),
                "z5" to listOf(Spec("HGT", "500 mb")))) { d, _ ->
                val t7 = d.getValue("t7"); val t5 = d.getValue("t5"); val z7 = d.getValue("z7"); val z5 = d.getValue("z5")
                Res(FloatArray(t7.size) { (t7[it] - t5[it]) / maxOf(z5[it] - z7[it], 1f) * 1000f })
            }),
            "How fast it cools with height between 3 and 5.5 km. 7+ °C/km is steep: big CAPE and hail.", decimals = 1))
        f(FieldDef("li", "Lifted index", "Severe", "°C", LI, null,
            listOf(Recipe(ins("li" to listOf(Spec("LFTX", "500-1000 mb"), Spec("LFTX", "surface")))) { d, _ -> Res(d.getValue("li")) }),
            "Negative is unstable; -6 or lower is very unstable.", decimals = 1))
        f(FieldDef("t2m", "2 m temperature", "Surface", "°F", TEMP, null,
            listOf(Recipe(ins("t" to listOf(Spec("TMP", "2 m above ground")))) { d, _ -> Res(map(d.getValue("t"), ::k2f)) }),
            "Air temperature at 2 m.", zeroLine = 32f))
        f(FieldDef("td2m", "2 m dewpoint", "Surface", "°F", DEW, null,
            listOf(Recipe(ins("t" to listOf(Spec("DPT", "2 m above ground")))) { d, _ -> Res(map(d.getValue("t"), ::k2f)) }),
            "Moisture. 60 °F+ feeds severe storms; 70 °F+ is tropical."))
        f(FieldDef("rh2m", "2 m relative humidity", "Surface", "%", RH, null,
            listOf(Recipe(ins("rh" to listOf(Spec("RH", "2 m above ground")))) { d, _ -> Res(d.getValue("rh")) }),
            "How close the air is to saturation."))
        f(FieldDef("wind10", "10 m wind", "Surface", "kt", WIND, null,
            listOf(Recipe(ins("u" to U10, "v" to V10)) { d, _ -> Res(speed(d.getValue("u"), d.getValue("v")), mapOf("u" to kt(d.getValue("u")), "v" to kt(d.getValue("v")))) }),
            "Sustained wind at 10 m, with barbs.", barbs = true))
        f(FieldDef("gust", "Wind gusts", "Surface", "kt", WIND, null,
            listOf(Recipe(ins("g" to listOf(Spec("GUST", "surface")))) { d, _ -> Res(kt(d.getValue("g"))) }), "Expected gusts at the surface."))
        f(FieldDef("mslp", "Sim. radar & pressure", "Surface", "dBZ", null, "REF",
            listOf(Recipe(ins("refc" to REFC, "p" to listOf(Spec("MSLMA", "mean sea level"), Spec("PRMSL", "mean sea level"), Spec("MSLET", "mean sea level")))) { d, _ ->
                Res(map(d.getValue("refc")) { if (it >= 5f) it else Float.NaN }, mapOf("p" to map(d.getValue("p")) { it / 100f }))
            }),
            "Simulated radar with isobars every 4 hPa: lows, highs and fronts.", contour = Contour("p", 4f, argb(235, 235, 235, 255))))
        f(FieldDef("pwat", "Precipitable water", "Surface", "in", PWAT, null,
            listOf(Recipe(ins("pw" to listOf(Spec("PWAT", "entire atmosphere (considered as a single layer)")))) { d, _ -> Res(map(d.getValue("pw")) { it / 25.4f }) }),
            "All the water vapour above a point. 1.5 in+ means heavy rain and flooding are possible.", decimals = 2))
        f(FieldDef("h500", "500 mb heights & wind", "Upper air", "kt", H500, null,
            listOf(Recipe(ins("z" to listOf(Spec("HGT", "500 mb")), "u" to U5, "v" to V5)) { d, _ ->
                Res(speed(d.getValue("u"), d.getValue("v")), mapOf("z" to map(d.getValue("z")) { it / 10f }, "u" to kt(d.getValue("u")), "v" to kt(d.getValue("v"))))
            }),
            "The mid-level steering pattern: troughs, ridges and the jet. Heights every 6 decametres.",
            contour = Contour("z", 6f, argb(15, 15, 15, 255)), barbs = true))
        f(FieldDef("vort500", "500 mb vorticity", "Upper air", "10⁻⁵/s", VORT, null, listOf(
            Recipe(ins("a" to listOf(Spec("ABSV", "500 mb")), "z" to listOf(Spec("HGT", "500 mb")))) { d, _ ->
                Res(map(d.getValue("a")) { it * 1e5f }, mapOf("z" to map(d.getValue("z")) { it / 10f }))
            },
            Recipe(ins("u" to U5, "v" to V5, "z" to listOf(Spec("HGT", "500 mb"))), "from the model winds") { d, g ->
                Res(vort(d, g), mapOf("z" to map(d.getValue("z")) { it / 10f }))
            }),
            "Spin in the mid-level flow; shortwaves show up as maxima moving through the pattern.",
            contour = Contour("z", 6f, argb(15, 15, 15, 255))))
        f(FieldDef("t850", "850 mb temperature & wind", "Upper air", "°C", T850, null,
            listOf(Recipe(ins("t" to listOf(Spec("TMP", "850 mb")), "z" to listOf(Spec("HGT", "850 mb")), "u" to listOf(Spec("UGRD", "850 mb")),
                "v" to listOf(Spec("VGRD", "850 mb")))) { d, _ ->
                Res(map(d.getValue("t")) { it - 273.15f }, mapOf("z" to map(d.getValue("z")) { it / 10f }, "u" to kt(d.getValue("u")), "v" to kt(d.getValue("v"))))
            }),
            "Temperature about 1.5 km up, with the low-level jet. The 0 °C line is the rain / snow line aloft.",
            contour = Contour("z", 3f, argb(15, 15, 15, 255)), barbs = true, zeroLine = 0f))
        f(FieldDef("w250", "250 mb jet stream", "Upper air", "kt", JET, null,
            listOf(Recipe(ins("z" to listOf(Spec("HGT", "250 mb")), "u" to listOf(Spec("UGRD", "250 mb")), "v" to listOf(Spec("VGRD", "250 mb")))) { d, _ ->
                Res(speed(d.getValue("u"), d.getValue("v")), mapOf("z" to map(d.getValue("z")) { it / 10f }, "u" to kt(d.getValue("u")), "v" to kt(d.getValue("v"))))
            }),
            "The jet stream near 10 km.", contour = Contour("z", 12f, argb(15, 15, 15, 255)), barbs = true))
        f(FieldDef("qpf", "Total precipitation", "Precip & winter", "in", QPF, null,
            listOf(Recipe(ins("p" to listOf(Spec("APCP", "surface", "acc0")))) { d, _ -> Res(map(d.getValue("p")) { it / 25.4f }) }),
            "All rain and melted snow from the start of the run.", decimals = 2, accum = "APCP"))
        f(FieldDef("ptype", "Precipitation type", "Precip & winter", "dBZ", PTYPE, null,
            listOf(Recipe(ins("refc" to REFC, "?crain" to listOf(Spec("CRAIN", "surface")), "?csnow" to listOf(Spec("CSNOW", "surface")),
                "?cicep" to listOf(Spec("CICEP", "surface")), "?cfrzr" to listOf(Spec("CFRZR", "surface")),
                "?t2" to listOf(Spec("TMP", "2 m above ground")))) { d, _ -> Res(ptype(d)) }),
            "Simulated radar coloured by what's falling: rain, snow, sleet or freezing rain.", categorical = true))
        f(FieldDef("snow10", "Total snowfall (10:1)", "Precip & winter", "in", SNOW, null,
            listOf(Recipe(ins("w" to listOf(Spec("WEASD", "surface", "acc0")))) { d, _ -> Res(map(d.getValue("w")) { it * 10f / 25.4f }) }),
            "Snow from the start of the run, at 10 inches of snow per inch of water.", decimals = 1, accum = "CSNOW"))
        f(FieldDef("asnow", "Total snowfall (model)", "Precip & winter", "in", SNOW, null,
            listOf(Recipe(ins("s" to listOf(Spec("ASNOW", "surface", "acc0")))) { d, _ -> Res(map(d.getValue("s")) { it * 39.3701f }) }),
            "The model's own snowfall, with its own snow-to-liquid ratio.", decimals = 1))
        f(FieldDef("frzr", "Freezing rain (ice)", "Precip & winter", "in", ICE, null,
            listOf(Recipe(ins("f" to listOf(Spec("FRZR", "surface", "acc0")))) { d, _ -> Res(map(d.getValue("f")) { it / 25.4f }) }),
            "Freezing rain from the start of the run. A quarter inch of ice brings down trees and power lines.", decimals = 2, accum = "CFRZR"))
        f(FieldDef("snod", "Snow depth", "Precip & winter", "in", SNOW, null,
            listOf(Recipe(ins("s" to listOf(Spec("SNOD", "surface")))) { d, _ -> Res(map(d.getValue("s")) { it * 39.3701f }) }),
            "Snow on the ground.", decimals = 1))
    }

    // ---------------------------------------------------------------- computing a field
    class Unavailable(msg: String) : Exception(msg)

    class ModelField(val fid: String, val model: String, val runMs: Long, val fh: Int, val grid: Grib2.Grid,
                     val value: FloatArray, val extra: Map<String, FloatArray>, val note: String) {
        val validMs: Long get() = runMs + fh * HOUR
        val def: FieldDef get() = FIELDS.getValue(fid)
    }

    private val WIND_PAIRS = listOf("u" to "v", "u5" to "v5", "u10" to "v10", "su" to "sv")

    /** Grid-relative Lambert winds -> east / north. */
    fun earthWinds(g: Grib2.Grid, u: FloatArray, v: FloatArray): Pair<FloatArray, FloatArray> {
        val l = g.lcc ?: return u to v
        if (!l.uvGrid) return u to v
        val eu = FloatArray(u.size); val ev = FloatArray(v.size)
        val ll = DoubleArray(2)
        for (j in 0 until g.nj) for (i in 0 until g.ni) {
            if (i % 8 == 0) g.latLon(j.toDouble(), (i + 4).toDouble(), ll)
            val a = l.n * Math.toRadians(((ll[1] - l.lov + 540.0) % 360.0) - 180.0)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val k = j * g.ni + i
            eu[k] = ca * u[k] + sa * v[k]
            ev[k] = -sa * u[k] + ca * v[k]
        }
        return eu to ev
    }

    fun compute(modelId: String, run: Long, fh: Int, fid: String): ModelField {
        val m = MODELS.getValue(modelId)
        val fd = FIELDS.getValue(fid)
        val rows = idx(m, run, fh)
        var lastErr: String? = null
        for (rec in fd.recipes) {
            val picks = LinkedHashMap<String, IdxEntry>()
            var ok = true
            for ((name, specs) in rec.inputs) {
                val opt = name.startsWith("?")
                val key = name.trimStart('?')
                var hit: IdxEntry? = null
                for (sp in specs) {
                    hit = findEntry(rows, sp.v, sp.level, sp.stat, fh)
                    if (hit == null && sp.stat == "fcst" && fh == 0) hit = findEntry(rows, sp.v, sp.level, "any")
                    if (hit != null) break
                }
                if (hit == null) {
                    if (opt) continue
                    ok = false
                    lastErr = "${m.name} has no ${specs[0].v} at ${specs[0].level}"
                    break
                }
                picks[key] = hit
            }
            if (!ok) continue
            val grids = picks.mapValues { decoded(m, run, fh, it.value) }
            val g0 = grids.values.first()
            if (grids.values.any { it.values.size != g0.values.size }) throw Unavailable("the inputs are on different grids")
            val d = HashMap<String, FloatArray>()
            for ((k, g) in grids) d[k] = g.values
            for ((a, b) in WIND_PAIRS) {
                val u = d[a]; val v = d[b]
                if (u != null && v != null) { val (eu, ev) = earthWinds(g0, u, v); d[a] = eu; d[b] = ev }
            }
            val r = rec.compute(d, g0)
            return ModelField(fid, modelId, run, fh, g0, r.value, r.extra, rec.note)
        }
        if (fd.accum != null) return accumulate(modelId, run, fh, fid)
        throw Unavailable(lastErr ?: "${fd.name} isn't available from ${m.name}")
    }

    /** Amount of an accumulated variable over (prev, cur], from the buckets the files carry. */
    fun increment(m: ModelDef, run: Long, prev: Int, cur: Int, v: String = "APCP"): FloatArray {
        val accs = idx(m, run, cur).filter { it.variable == v && it.level == "surface" && it.stat == "acc" && it.accSpan != null }
        accs.firstOrNull { val s = it.accSpan!!; s[0] == prev && s[1] == cur }?.let { return decoded(m, run, cur, it).values }
        for (r in accs.sortedBy { it.accSpan!![0] }) {
            val (a, b) = r.accSpan!!.let { it[0] to it[1] }
            if (b != cur || a > prev) continue
            val total = decoded(m, run, cur, r).values
            if (a == prev) return total
            val p = findEntry(idx(m, run, prev), v, "surface", "accspan", span = intArrayOf(a, prev)) ?: continue
            val before = decoded(m, run, prev, p).values
            return FloatArray(total.size) { total[it] - before[it] }
        }
        throw Unavailable("no $v amount for hours $prev-$cur")
    }

    /** Sums each step's precipitation (times the share that's snow / freezing rain) up to fh. */
    fun accumulate(modelId: String, run: Long, fh: Int, fid: String, hours: List<Int>? = null): ModelField {
        val m = MODELS.getValue(modelId)
        val fd = FIELDS.getValue(fid)
        if (fh == 0) throw Unavailable("nothing has fallen yet at hour 0")
        val avail = (hours ?: listRun(m, run)).filter { it in 1..fh }
        if (fh !in avail) throw Unavailable("hour $fh isn't out yet")
        var total: FloatArray? = null
        var prev = 0
        var g0: Grib2.Grid? = null
        for (hh in avail) {
            var inc = increment(m, run, prev, hh)
            if (fd.accum == "CSNOW" || fd.accum == "CFRZR") {
                val rows = idx(m, run, hh)
                val e = findEntry(rows, fd.accum, "surface", "ave", hh) ?: findEntry(rows, fd.accum, "surface") ?: throw Unavailable("${m.name} has no ${fd.accum}")
                val share = decoded(m, run, hh, e).values
                inc = FloatArray(inc.size) { inc[it] * share[it].coerceIn(0f, 1f) }
            }
            total = if (total == null) inc.copyOf() else FloatArray(inc.size) { total!![it] + inc[it] }
            if (g0 == null) g0 = findEntry(idx(m, run, hh), "APCP", "surface", "accspan")?.let { decoded(m, run, hh, it) }
            prev = hh
        }
        val k = if (fd.accum == "CSNOW") 10f / 25.4f else 1f / 25.4f
        return ModelField(fid, modelId, run, fh, g0!!, FloatArray(total!!.size) { maxOf(total[it], 0f) * k }, emptyMap(), "summed from each step")
    }

    // ---------------------------------------------------------------- colours and text
    fun colorOf(stops: List<Pair<Float, Int>>, v: Float): Int {
        if (v.isNaN() || v < stops[0].first) return 0
        if (v >= stops.last().first) return stops.last().second
        var i = 0
        while (i < stops.size - 1 && v >= stops[i + 1].first) i++
        val (v0, c0) = stops[i]; val (v1, c1) = stops[i + 1]
        val t = ((v - v0) / (v1 - v0)).coerceIn(0f, 1f)
        fun ch(c: Int, s: Int) = (c ushr s) and 0xff
        fun mix(s: Int) = (ch(c0, s) + (ch(c1, s) - ch(c0, s)) * t).toInt().coerceIn(0, 255)
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    fun fmtValue(fd: FieldDef, v: Float): String {
        if (v.isNaN()) return "—"
        if (fd.id == "ptype") return "${PTYPE_NAMES[(v / 100).toInt().coerceIn(0, 3)]}, ${(v % 100).toInt()} dBZ"
        return String.format(Locale.US, "%.${fd.decimals}f %s", v, fd.units).trim()
    }

    /** Regions for the area picker: name -> (lat, lon, width km). */
    val REGIONS = linkedMapOf(
        "CONUS" to Triple(38.5, -97.0, 4900.0), "Northeast" to Triple(42.5, -74.5, 1300.0),
        "Mid-Atlantic" to Triple(38.8, -77.5, 1100.0), "Southeast" to Triple(32.5, -84.5, 1500.0),
        "Great Lakes" to Triple(43.5, -85.5, 1400.0), "Midwest" to Triple(40.5, -90.5, 1400.0),
        "Northern Plains" to Triple(45.5, -99.5, 1500.0), "Central Plains" to Triple(39.0, -98.0, 1300.0),
        "Southern Plains" to Triple(33.5, -98.5, 1300.0), "Gulf Coast" to Triple(30.5, -89.0, 1500.0),
        "Southwest" to Triple(34.5, -111.5, 1500.0), "Northwest" to Triple(45.0, -119.5, 1500.0),
        "California" to Triple(37.0, -120.0, 1300.0), "Atlantic tropics" to Triple(24.0, -70.0, 5500.0),
    )
}
