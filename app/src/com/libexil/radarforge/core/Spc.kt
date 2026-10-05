package com.libexil.radarforge.core

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One area of an SPC outlook: categorical (TSTM...HIGH) or a probability (TORNADO 0.05, HAIL SIGN...). */
class OutlookArea(
    val category: String,          // CATEGORICAL, TORNADO, WIND, HAIL
    val threshold: String,         // TSTM, MRGL, SLGT, ENH, MDT, HIGH  or  0.02, 0.05 ... SIGN
    val rings: List<FloatArray>,   // lon,lat
    val issueMs: Long,
    val expireMs: Long,
) {
    private val box = Geo.ringsBox(rings)
    fun contains(lat: Double, lon: Double): Boolean =
        lat >= box[0] && lat <= box[1] && lon >= box[2] && lon <= box[3] && Geo.ringsContain(rings, lat, lon)
}

/** An SPC mesoscale discussion. */
class MesoDiscussion(
    val number: Int,
    val productId: String,
    val issueMs: Long,
    val expireMs: Long,
    val concerning: String,
    val watchChance: Int?,         // probability of a watch, percent
    val rings: List<FloatArray>,   // lon,lat
) {
    private val box = Geo.ringsBox(rings)
    val minLat: Float get() = box[0]
    val maxLat: Float get() = box[1]
    val minLon: Float get() = box[2]
    val maxLon: Float get() = box[3]
    fun contains(lat: Double, lon: Double): Boolean =
        lat >= box[0] && lat <= box[1] && lon >= box[2] && lon <= box[3] && Geo.ringsContain(rings, lat, lon)
}

/** Storm Prediction Center day 1 outlook and mesoscale discussions, from the Iowa Environmental Mesonet's API. */
object Spc {
    val CATEGORIES = listOf("TSTM", "MRGL", "SLGT", "ENH", "MDT", "HIGH")
    /** Line colours (SPC's fill colours: they read well on the dark map). */
    val CAT_COLOR = mapOf(
        "TSTM" to 0xffc1e9c1.toInt(), "MRGL" to 0xff66a366.toInt(), "SLGT" to 0xffffe066.toInt(),
        "ENH" to 0xffffa366.toInt(), "MDT" to 0xffe06666.toInt(), "HIGH" to 0xffee99ee.toInt(),
    )
    val CAT_NAME = mapOf(
        "TSTM" to "General thunderstorms", "MRGL" to "Marginal risk (1 of 5)", "SLGT" to "Slight risk (2 of 5)",
        "ENH" to "Enhanced risk (3 of 5)", "MDT" to "Moderate risk (4 of 5)", "HIGH" to "High risk (5 of 5)",
    )
    const val MCD_COLOR = 0xff4f8fff.toInt()
    const val MCD_URL = "https://mesonet.agron.iastate.edu/api/1/nws/spc_mcd.geojson"
    fun mcdTextUrl(productId: String) = "https://mesonet.agron.iastate.edu/api/1/nwstext/$productId"
    fun mcdPage(number: Int, year: Int) = "https://www.spc.noaa.gov/products/md/$year/md${String.format(Locale.US, "%04d", number)}.html"

    /**
     * The day 1 outlooks that may be the newest at [nowMs], newest first, as request URLs.
     * Day 1 is issued at 06, 13, 16:30, 20 and 01 UTC; the 01 UTC one belongs to the previous
     * day's outlook date. Each is tried ~45 minutes before its nominal time (SPC often issues early).
     */
    fun outlookUrls(nowMs: Long, max: Int = 4): List<String> {
        val utc = TimeZone.getTimeZone("UTC")
        val cands = ArrayList<Pair<Long, String>>()
        for (back in 0..1) {
            val day = Calendar.getInstance(utc).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, -back)
            }
            val d0 = day.timeInMillis
            val date = String.format(Locale.US, "%04d-%02d-%02d", day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH))
            for ((cycle, minutes) in listOf(6 to 6 * 60, 13 to 13 * 60, 16 to 16 * 60 + 30, 20 to 20 * 60, 1 to 25 * 60)) {
                val t = d0 + minutes * 60_000L
                if (t - 45 * 60_000L <= nowMs) cands.add(t to "https://mesonet.agron.iastate.edu/api/1/nws/spc_outlook.geojson?day=1&valid=$date&cycle=$cycle")
            }
        }
        return cands.sortedByDescending { it.first }.take(max).map { it.second }
    }

    /**
     * Request URLs to try for the day 2 or day 3 outlook, newest first. IEM files them under the date
     * they cover; that date moves on with the 06 UTC day 1 issuance.
     */
    fun outlookUrlsAhead(nowMs: Long, day: Int): List<String> {
        val utc = TimeZone.getTimeZone("UTC")
        val cycles = if (day == 3) listOf(20, 19, 8, 7) else listOf(17, 6, 7, 1)
        val base = Calendar.getInstance(utc).apply {
            timeInMillis = nowMs
            if (get(Calendar.HOUR_OF_DAY) < 6) add(Calendar.DAY_OF_MONTH, -1)
        }
        val out = ArrayList<String>()
        for (back in 0..1) {
            val d = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, day - 1 - back) }
            val date = String.format(Locale.US, "%04d-%02d-%02d", d.get(Calendar.YEAR), d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH))
            for (c in cycles) out.add("https://mesonet.agron.iastate.edu/api/1/nws/spc_outlook.geojson?day=$day&valid=$date&cycle=$c")
        }
        return out
    }

    /** The URLs to try for day [day] (1, 2 or 3), newest first. */
    fun outlookUrlsFor(nowMs: Long, day: Int): List<String> = if (day <= 1) outlookUrls(nowMs) else outlookUrlsAhead(nowMs, day)

    private fun rings(g: Map<String, Any?>): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        fun ring(r: Any?) {
            val pts = r.arr()
            val a = FloatArray(pts.size * 2)
            var n = 0
            for (pt in pts) {
                val c = pt.arr()
                val lon = c.getOrNull(0).num(); val lat = c.getOrNull(1).num()
                if (lon.isNaN() || lat.isNaN()) continue
                a[n++] = lon.toFloat(); a[n++] = lat.toFloat()
            }
            if (n >= 6) out.add(if (n == a.size) a else a.copyOf(n))
        }
        val coords = g["coordinates"].arr()
        when (g["type"].str()) {
            "Polygon" -> coords.forEach { ring(it) }
            "MultiPolygon" -> coords.forEach { poly -> poly.arr().forEach { ring(it) } }
        }
        return out
    }

    fun parseOutlook(json: String): List<OutlookArea> {
        val out = ArrayList<OutlookArea>()
        for (f in Json.parse(json).obj()["features"].arr()) {
            val fo = f.obj()
            val p = fo["properties"].obj()
            val r = rings(fo["geometry"].obj())
            if (r.isEmpty()) continue
            out.add(OutlookArea(p["category"].str().uppercase(Locale.US), p["threshold"].str().uppercase(Locale.US), r,
                Time.parseIso(p["issue"].str()), Time.parseIso(p["expire"].str())))
        }
        return out
    }

    fun parseMcd(json: String): List<MesoDiscussion> {
        val out = ArrayList<MesoDiscussion>()
        for (f in Json.parse(json).obj()["features"].arr()) {
            val fo = f.obj()
            val p = fo["properties"].obj()
            val r = rings(fo["geometry"].obj())
            if (r.isEmpty()) continue
            val wc = p["watch_confidence"].num()
            out.add(MesoDiscussion(p["num"].num(0.0).toInt(), p["product_id"].str(), Time.parseIso(p["issue"].str()),
                Time.parseIso(p["expire"].str()), p["concerning"].str().trim(), if (wc.isNaN()) null else wc.toInt(), r))
        }
        return out
    }

    /** Categorical risk index (0 = TSTM ... 5 = HIGH), -1 when none. */
    fun catIndex(threshold: String) = CATEGORIES.indexOf(threshold)

    /** "5%", "15%", "Significant" for a probabilistic threshold. */
    fun probText(threshold: String): String {
        if (threshold == "SIGN") return "significant"
        val v = threshold.toDoubleOrNull() ?: return threshold
        return "${Math.round(v * 100)}%"
    }
}
