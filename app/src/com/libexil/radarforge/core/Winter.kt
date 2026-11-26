package com.libexil.radarforge.core

import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Winter weather, like the desktop app (radarforge/features/winter.py):
 *  - precipitation type: the HRRR's newest forecast valid at the hour of the scan (rain / snow / sleet / freezing
 *    rain; its analysis hour has no precipitation, so no types) colours the radar's own reflectivity: the PTYPE
 *    product. Where the radar has precipitation the model doesn't, the model's nearest type (within 60 km) is used,
 *    and beyond that the 2 m wet-bulb temperature decides between snow and rain;
 *  - the freezing line: the HRRR's 32 °F line at 2 m;
 *  - snowfall totals: NOHRSC's gridded snowfall analysis (24 / 48 / 72 h, https://www.nohrsc.noaa.gov/snowfall_v2/);
 *  - rainfall totals: MRMS multi-sensor estimates (24 / 48 / 72 h, for tropical rain).
 */
object Winter {
    const val HALF_KM = 480f
    const val N = 320                         // 3 km cells around the radar
    const val RAIN = 0; const val SNOW = 1; const val SLEET = 2; const val FRZR = 3
    val TYPE_NAMES = listOf("Rain", "Snow", "Sleet", "Freezing rain")

    /** HRRR precipitation type and 2 m temperature on a square around a radar. */
    class SurfaceGrid(val codes: ByteArray, val t2f: FloatArray, val lat0: Double, val lon0: Double, val validMs: Long, val source: String) {
        private fun index(x: Float, y: Float): Int {
            val c = ((x + HALF_KM) / (2 * HALF_KM) * N).toInt()
            val r = ((HALF_KM - y) / (2 * HALF_KM) * N).toInt()
            return if (r in 0 until N && c in 0 until N) r * N + c else -1
        }
        fun code(x: Float, y: Float): Int { val i = index(x, y); return if (i < 0) -1 else codes[i].toInt() }
        fun t2(x: Float, y: Float): Float { val i = index(x, y); return if (i < 0) Float.NaN else t2f[i] }
    }

    private val grids = object : LinkedHashMap<String, SurfaceGrid>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SurfaceGrid>?) = size > 6
    }
    private val failed = HashMap<String, Long>()

    private fun hourOf(t: Long) = (t + 30 * 60_000L) / Models.HOUR * Models.HOUR

    private fun key(lat0: Double, lon0: Double, t: Long) = String.format(Locale.US, "%.3f,%.3f,%d", lat0, lon0, hourOf(t))

    /** Whether getting the grid for this hour failed in the last few minutes. */
    fun failedRecently(lat0: Double, lon0: Double, t: Long): Boolean =
        synchronized(grids) { System.currentTimeMillis() - (failed[key(lat0, lon0, t)] ?: 0L) < 5 * 60_000L }

    /** The grid if it's already here (no download). */
    fun peek(lat0: Double, lon0: Double, t: Long): SurfaceGrid? = synchronized(grids) { grids[key(lat0, lon0, t)] }

    /** The HRRR surface grid for the hour of [t] around (lat0, lon0): the newest forecast valid then, the analysis last. Blocking. */
    fun surfaceGrid(lat0: Double, lon0: Double, t: Long): SurfaceGrid? {
        val k = key(lat0, lon0, t)
        synchronized(grids) {
            grids[k]?.let { return it }
            if (System.currentTimeMillis() - (failed[k] ?: 0L) < 5 * 60_000L) return null
        }
        val m = Models.MODELS.getValue("hrrr")
        val hour = hourOf(t)
        // the analysis (0) has no precipitation types, only temperatures: forecasts 1, 2, 3 hours ahead first
        for (back in intArrayOf(1, 2, 3, 0)) {
            val run = hour - back * Models.HOUR
            val rows = try { Models.idx(m, run, back) } catch (_: Exception) { continue }
            val picks = HashMap<String, Models.IdxEntry>()
            for ((name, v) in listOf("crain" to "CRAIN", "csnow" to "CSNOW", "cicep" to "CICEP", "cfrzr" to "CFRZR",
                                     "t2" to "TMP", "td2" to "DPT")) {
                val lev = if (v == "TMP" || v == "DPT") "2 m above ground" else "surface"
                (Models.findEntry(rows, v, lev, "fcst") ?: Models.findEntry(rows, v, lev, "any"))?.let { picks[name] = it }
            }
            if ("t2" !in picks) continue
            val g = try {
                Models.fetchEntries(m, run, back, picks.values.toList())          // neighbours in the file: one request
                picks.mapValues { Models.decoded(m, run, back, it.value) }
            } catch (_: Exception) { continue }
            val grid = build(g, lat0, lon0, run + back * Models.HOUR, "HRRR ${Time.hmZ(run).take(2)}Z" + if (back == 0) " analysis" else " +$back h")
            synchronized(grids) { grids[k] = grid }
            return grid
        }
        synchronized(grids) { failed[k] = System.currentTimeMillis() }
        return null
    }

    const val REACH_KM = 60f            // how far the model's nearest precipitation type is carried to the radar's echoes
    const val SNOW_TW_C = 1f            // wet-bulb temperature (°C) at or below which precipitation is taken as snow

    /** 2 m wet-bulb temperature (°C) from temperature and dewpoint (Stull 2011); the temperature without a dewpoint. */
    fun wetBulbC(tC: Float, tdC: Float = Float.NaN): Float {
        if (tdC.isNaN()) return tC
        val t = tC.toDouble(); val td = minOf(tdC, tC).toDouble()
        fun es(x: Double) = 6.112 * Math.exp(17.62 * x / (243.12 + x))
        val rh = (100.0 * es(td) / es(t)).coerceIn(5.0, 100.0)
        val tw = t * Math.atan(0.151977 * Math.sqrt(rh + 8.313659)) + Math.atan(t + rh) - Math.atan(rh - 1.676331) +
            0.00391838 * Math.pow(rh, 1.5) * Math.atan(0.023101 * rh) - 4.686035
        return minOf(tw, t).toFloat()
    }

    /**
     * Type codes on an n x n grid from 2 m temperature / dewpoint (K, NaN unknown) and the model's flags (0 / 1).
     * The model's own type where it has precipitation (rising order: rain, snow, sleet, freezing rain; a rain / snow
     * mix shows as snow); elsewhere its nearest type within [REACH_KM] unless the air plainly disagrees; beyond
     * that the wet bulb.
     */
    fun classify(n: Int, t2: FloatArray, td2: FloatArray?, flags: Map<String, FloatArray>, cellKm: Float): ByteArray {
        val size = n * n
        val tw = FloatArray(size) { if (t2[it].isNaN()) 99f else wetBulbC(t2[it] - 273.15f, td2?.get(it)?.minus(273.15f) ?: Float.NaN) }
        val model = ByteArray(size) { -1 }
        for ((code, name) in listOf(RAIN to "crain", SNOW to "csnow", SLEET to "cicep", FRZR to "cfrzr")) {
            val f = flags[name] ?: continue
            for (i in 0 until size) if (f[i] > 0.5f) model[i] = code.toByte()
        }
        // carry the model's types outwards, one cell a step (8 neighbours), up to the reach
        val near = model.copyOf()
        var frontier = IntArray(size).let { q -> var m = 0; for (i in 0 until size) if (model[i] >= 0) q[m++] = i; q.copyOf(m) }
        val steps = (REACH_KM / cellKm).toInt()
        var step = 0
        while (frontier.isNotEmpty() && step < steps) {
            val next = IntArray(minOf(size, frontier.size * 8)); var m = 0
            for (i in frontier) {
                val r = i / n; val c = i % n
                for (dr in -1..1) for (dc in -1..1) {
                    val rr = r + dr; val cc = c + dc
                    if (rr < 0 || rr >= n || cc < 0 || cc >= n) continue
                    val j = rr * n + cc
                    if (near[j] < 0) { near[j] = near[i]; if (m < next.size) next[m++] = j }
                }
            }
            frontier = next.copyOf(m); step++
        }
        val out = ByteArray(size)
        for (i in 0 until size) {
            if (t2[i].isNaN()) { out[i] = -1; continue }
            var code = when {
                model[i] >= 0 -> model[i].toInt()
                near[i] >= 0 -> near[i].toInt().let { v ->
                    when {
                        v != RAIN && tw[i] > 3f -> RAIN           // frozen types into warm air: rain
                        v == RAIN && tw[i] < -2f -> SNOW          // rain into cold air: snow
                        else -> v
                    }
                }
                else -> if (tw[i] <= SNOW_TW_C) SNOW else RAIN
            }
            out[i] = code.toByte()
        }
        return out
    }

    /** Samples the HRRR fields onto the radar-centred square (also used by the tests). */
    fun build(g: Map<String, Grib2.Grid>, lat0: Double, lon0: Double, validMs: Long, source: String): SurfaceGrid {
        val g0 = g.getValue("t2")
        val proj = Geo.Aeqd(lat0, lon0)
        val size = N * N
        val idx = IntArray(size)
        val tmp = DoubleArray(2)
        val step = 2 * HALF_KM / N
        for (r in 0 until N) for (c in 0 until N) {
            val ll = proj.inverse((-HALF_KM + (c + 0.5f) * step).toDouble(), (HALF_KM - (r + 0.5f) * step).toDouble())
            g0.index(ll[0], ll[1], tmp)
            val row = Math.round(tmp[0]).toInt(); val col = Math.round(tmp[1]).toInt()
            idx[r * N + c] = if (row < 0 || row >= g0.nj || col < 0 || col >= g0.ni) -1 else row * g0.ni + col
        }
        fun samp(name: String): FloatArray? {
            val v = g[name]?.values ?: return null
            return FloatArray(size) { val i = idx[it]; if (i < 0 || i >= v.size) Float.NaN else v[i] }
        }
        val t2 = samp("t2")!!
        val flags = HashMap<String, FloatArray>()
        for (n in listOf("crain", "csnow", "cicep", "cfrzr")) samp(n)?.let { flags[n] = it }
        val codes = classify(N, t2, samp("td2"), flags, step)
        val t2f = FloatArray(size) { if (t2[it].isNaN()) Float.NaN else (t2[it] - 273.15f) * 1.8f + 32f }
        return SurfaceGrid(codes, t2f, lat0, lon0, validMs, source)
    }

    /** Reflectivity coded by type for the PTYPE colour table: 100 x type + dBZ (from 5 dBZ). */
    fun ptypeField(ref: Field, grid: SurfaceGrid?): Field {
        val vals = ref.values()
        val out = CharArray(vals.size)
        val ng = ref.nGates
        val cosE = cos(Math.toRadians(ref.elevation.toDouble())).toFloat()
        for (r in 0 until ref.nRays) {
            val a = Math.toRadians(ref.azCenter[r].toDouble())
            val sa = sin(a).toFloat(); val ca = cos(a).toFloat()
            for (gi in 0 until ng) {
                val v = vals[r * ng + gi]
                val k = r * ng + gi
                if (v.isNaN() || v.isInfinite() || v < 5f) { out[k] = 0.toChar(); continue }
                val s = (ref.firstGate + gi * ref.gateSpacing) * cosE
                val t = grid?.code(s * sa, s * ca)?.coerceAtLeast(0) ?: 0
                val pv = 100f * t + v.coerceIn(5f, 99f)
                out[k] = Math.round(pv * ref.scale + ref.offset).coerceIn(2, 65535).toChar()
            }
        }
        return ref.withCodes(null, out, "ptype@" + (grid?.validMs ?: 0L), product = Product.PTYPE)
    }

    /** The 32 °F line (km lines around the radar). */
    fun freezingLine(g: SurfaceGrid): List<FloatArray> {
        val v = FloatArray(N * N) { if (g.t2f[it].isNaN()) Float.NaN else g.t2f[it] }
        val step = 2 * HALF_KM / N
        return MapRaster.march(v, N, N, 32f).filter { it.size >= 12 }.map { pl ->
            FloatArray(pl.size) { k -> if (k % 2 == 0) -HALF_KM + (pl[k] + 0.5f) * step else HALF_KM - (pl[k] + 0.5f) * step }
        }
    }

    // ---------------------------------------------------------------- snowfall (NOHRSC)
    const val NOHRSC = "https://www.nohrsc.noaa.gov/snowfall_v2/data/"
    private val SNOW_FILE = Regex("""sfav2_CONUS_(\d+)h_(\d{10})(?:_grid184)?\.grb2""")

    /** (valid time, file name) of an NOHRSC month listing for one period, oldest first (the 2.5 km grid preferred). */
    fun snowfallFiles(html: String, hours: Int): List<Pair<Long, String>> {
        val out = HashMap<Long, String>()
        val f = Models.fmt("yyyyMMddHH")
        for (m in SNOW_FILE.findAll(html)) {
            if (m.groupValues[1].toInt() != hours) continue
            val t = try { f.parse(m.groupValues[2])!!.time } catch (_: Exception) { continue }
            val name = m.value
            if (t !in out || "grid184" in name) out[t] = name
        }
        return out.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    val SNOW_STOPS get() = Models.SNOW

    /** Values on a raster frame and their picture. */
    class Total(val raster: Raster, val values: FloatArray, val frame: RasterFrame, val validMs: Long, val label: String) {
        fun valueAt(x: Float, y: Float): Float {
            val c = ((x - frame.x0) / frame.cellKm).toInt(); val r = ((frame.y1 - y) / frame.cellKm).toInt()
            return if (r in 0 until frame.h && c in 0 until frame.w) values[r * frame.w + c] else Float.NaN
        }
    }

    /** NOHRSC snowfall over the last [hours] around a point (blocking). [t]: the time shown (0 = now). */
    fun snowfall(lat0: Double, lon0: Double, hours: Int, t: Long = 0L, get: (String) -> ByteArray = { Net.getBytes(it, 60_000) }): Total {
        val now = if (t > 0) t else System.currentTimeMillis()
        val months = listOf(now, now - 4 * 86_400_000L).map { Models.fmt("yyyyMM").format(java.util.Date(it)) }.distinct().sorted()
        val files = ArrayList<Pair<Long, String>>()
        for (mo in months) try { files += snowfallFiles(String(get("$NOHRSC$mo/"), Charsets.UTF_8), hours) } catch (_: Exception) {}
        val pick = files.sortedBy { it.first }.lastOrNull { it.first <= now + 30 * 60_000L }
            ?: throw IllegalStateException("no $hours-hour snowfall analysis found")
        val mo = Models.fmt("yyyyMM").format(java.util.Date(pick.first))
        val g = Grib2.decode(get("$NOHRSC$mo/${pick.second}"))
        val frame = RasterFrame.around(Geo.Aeqd(lat0, lon0), 1400f, 1400f, 2f, 700)
        val v = MapRaster.resample(g, g.values, MapRaster.sampleIndex(g, frame), true)
        var mx = 0f
        for (x in v) if (!x.isNaN() && x > mx) mx = x
        // metres (the analysis' unit) -> inches; a file already in cm or inches is left alone
        val k = if (mx < 5f) 39.3701f else if (mx > 200f) 1 / 2.54f else 1f
        for (i in v.indices) v[i] = if (v[i].isNaN() || v[i] * k < 0.1f) Float.NaN else v[i] * k
        val img = MapRaster.colorize(v, SNOW_STOPS)
        val label = "$hours-hour snowfall"
        return Total(Raster("snow/$hours/${pick.second}/${frame.key}", frame.w, frame.h, img, frame.x0, frame.y0, frame.x1, frame.y1, true, 0.8f),
            v, frame, pick.first, label)
    }

    // ---------------------------------------------------------------- rainfall (MRMS)
    const val MRMS_BUCKET = "noaa-mrms-pds"
    val QPE_STOPS = listOf(0.1f to 0xbe50c850.toInt(), 0.25f to 0xd21e8c1e.toInt(), 0.5f to 0xe1ffff00.toInt(),
        1f to 0xebff9600.toInt(), 2f to 0xf5ff0000.toInt(), 3f to 0xfac800c8.toInt(), 5f to 0xffffffff.toInt(),
        8f to 0xffbebeff.toInt(), 12f to 0xff825ad2.toInt(), 20f to 0xff460a5a.toInt())

    fun qpeProduct(hours: Int) = "MultiSensor_QPE_%02dH_Pass2_00.00".format(hours)

    private val MRMS_TIME = Regex("""_(\d{8}-\d{6})\.grib2""")

    /** The newest MRMS key at or before [t] (a minute's grace), within [maxAgeMin]. */
    fun pickKey(keys: List<String>, t: Long, maxAgeMin: Int): Pair<String, Long>? {
        val f = Models.fmt("yyyyMMdd-HHmmss")
        var best: Pair<String, Long>? = null
        for (k in keys) {
            val kt = MRMS_TIME.find(k)?.groupValues?.get(1)?.let { try { f.parse(it)!!.time } catch (_: Exception) { null } } ?: continue
            if (kt > t + 60_000L || kt < t - maxAgeMin * 60_000L) continue
            if (best == null || kt > best.second) best = k to kt
        }
        return best
    }

    /** MRMS rainfall over the last [hours] around a point (blocking). */
    fun rainfall(lat0: Double, lon0: Double, hours: Int, t: Long = 0L): Total {
        val now = if (t > 0) t else System.currentTimeMillis()
        val product = qpeProduct(hours)
        val keys = ArrayList<String>()
        for (day in listOf(now - 3 * Models.HOUR, now).map { Time.ymd(it).replace("/", "") }.distinct()) {
            val l = S3.listAll(MRMS_BUCKET, "CONUS/$product/$day/", maxPages = 3)
            keys += l.objects.map { it.key }
        }
        val (key, kt) = pickKey(keys, now, 180) ?: throw IllegalStateException("no ${hours}-hour rainfall estimate found")
        val raw = Net.getBytes(S3.objectUrl(MRMS_BUCKET, key), 90_000)
        val half = 700f
        val frame = RasterFrame.around(Geo.Aeqd(lat0, lon0), 2 * half, 2 * half, 2f, 700)
        // only the latitudes around the radar, every second row and column (the CONUS grid is 7000 x 3500)
        val dLat = half / 111f + 1f
        val g = Grib2.decode(raw, lat0 + dLat, lat0 - dLat, 2)
        val v = MapRaster.resample(g, g.values, MapRaster.sampleIndex(g, frame), false)
        for (i in v.indices) v[i] = if (v[i].isNaN() || v[i] < 0f) Float.NaN else (v[i] / 25.4f).let { if (it < 0.1f) Float.NaN else it }
        val img = MapRaster.colorize(v, QPE_STOPS)
        return Total(Raster("qpe/$hours/$key/${frame.key}", frame.w, frame.h, img, frame.x0, frame.y0, frame.x1, frame.y1, true, 0.8f),
            v, frame, kt, "$hours-hour rainfall")
    }
}
