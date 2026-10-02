package com.libexil.radarforge.core

import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Radar beam geometry (4/3 earth) and the radar-centred azimuthal equidistant projection (km). */
object Geo {
    const val EARTH_R = 6371.0
    const val AE = EARTH_R * 4.0 / 3.0
    const val KT = 0.514444            // m/s per knot

    fun slantRange(groundKm: Double, elevDeg: Double): Double {
        val phi = groundKm / AE
        return AE * sin(phi) / cos(Math.toRadians(elevDeg) + phi)
    }

    fun beamHeight(slantKm: Double, elevDeg: Double): Double {
        val th = Math.toRadians(elevDeg)
        return sqrt(slantKm * slantKm + AE * AE + 2 * slantKm * AE * sin(th)) - AE
    }

    fun groundRange(slantKm: Double, elevDeg: Double): Double {
        val th = Math.toRadians(elevDeg)
        val h = beamHeight(slantKm, elevDeg)
        return AE * asin((slantKm * cos(th) / (AE + h)).coerceIn(-1.0, 1.0))
    }

    /** Projection centred at (lat0, lon0); results in km, x east, y north. */
    class Aeqd(val lat0: Double, val lon0: Double) {
        private val p0 = Math.toRadians(lat0)
        private val sp0 = sin(p0)
        private val cp0 = cos(p0)
        private val l0 = Math.toRadians(lon0)

        /** Writes x,y into out[o], out[o+1]. */
        fun forward(lat: Double, lon: Double, out: FloatArray, o: Int) {
            val p = Math.toRadians(lat)
            val dl = Math.toRadians(lon) - l0
            val sp = sin(p); val cp = cos(p)
            val cdl = cos(dl)
            val a = cp * sin(dl)
            val b = cp0 * sp - sp0 * cp * cdl
            val cosc = sp0 * sp + cp0 * cp * cdl
            val sinc = hypot(a, b)
            val c = atan2(sinc, cosc)
            val k = if (sinc > 1e-12) c / sinc else 1.0
            out[o] = (EARTH_R * k * a).toFloat()
            out[o + 1] = (EARTH_R * k * b).toFloat()
        }

        fun forward(lat: Double, lon: Double): DoubleArray {
            val f = FloatArray(2)
            forward(lat, lon, f, 0)
            return doubleArrayOf(f[0].toDouble(), f[1].toDouble())
        }

        /** (lat, lon) of a projected point. */
        fun inverse(x: Double, y: Double): DoubleArray {
            val rho = hypot(x, y)
            if (rho < 1e-9) return doubleArrayOf(lat0, lon0)
            val c = rho / EARTH_R
            val sc = sin(c); val cc = cos(c)
            val lat = asin((cc * sp0 + y * sc * cp0 / rho).coerceIn(-1.0, 1.0))
            val lon = l0 + atan2(x * sc, rho * cp0 * cc - y * sp0 * sc)
            var lonDeg = Math.toDegrees(lon)
            lonDeg = (lonDeg + 540.0) % 360.0 - 180.0
            return doubleArrayOf(Math.toDegrees(lat), lonDeg)
        }
    }

    /** Great-circle distance in km. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_R * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun azimuthDeg(x: Double, y: Double): Double = (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0

    /** Initial great-circle bearing from point 1 to point 2, degrees clockwise from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** The point [distKm] away from (lat, lon) along [bearingDeg]: (lat, lon). */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distKm: Double): DoubleArray {
        val d = distKm / EARTH_R
        val th = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lon)
        val p2 = asin((sin(p1) * cos(d) + cos(p1) * sin(d) * cos(th)).coerceIn(-1.0, 1.0))
        val l2 = l1 + atan2(sin(th) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return doubleArrayOf(Math.toDegrees(p2), (Math.toDegrees(l2) + 540.0) % 360.0 - 180.0)
    }

    /** A distance in the chosen units: "mi", "km" or "nm". Finer steps when short. */
    fun distText(km: Double, units: String): String {
        val (v, u) = when (units) { "km" -> km to "km"; "nm" -> km * 0.539957 to "nm"; else -> km * 0.621371 to "mi" }
        return when {
            v < 10 -> String.format(Locale.US, "%.2f %s", v, u)
            v < 100 -> String.format(Locale.US, "%.1f %s", v, u)
            else -> String.format(Locale.US, "%.0f %s", v, u)
        }
    }

    /** A speed given in km/h in the chosen velocity units: "kts", "mph" or "m/s". */
    fun speedText(kmh: Double, units: String): String = when (units) {
        "mph" -> String.format(Locale.US, "%.0f mph", kmh * 0.621371)
        "m/s" -> String.format(Locale.US, "%.0f m/s", kmh / 3.6)
        else -> String.format(Locale.US, "%.0f kts", kmh / 1.852)
    }

    /** Even-odd point-in-polygon over lon,lat rings (holes and multi-part shapes both work). */
    fun ringsContain(rings: List<FloatArray>, lat: Double, lon: Double): Boolean {
        var inside = false
        for (r in rings) {
            val n = r.size / 2
            if (n < 3) continue
            var j = n - 1
            for (i in 0 until n) {
                val xi = r[2 * i]; val yi = r[2 * i + 1]
                val xj = r[2 * j]; val yj = r[2 * j + 1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    /** Bounding box of lon,lat rings: [minLat, maxLat, minLon, maxLon]. */
    fun ringsBox(rings: List<FloatArray>): FloatArray {
        var a = 999f; var b = -999f; var c = 999f; var d = -999f
        for (r in rings) {
            var i = 0
            while (i + 1 < r.size) {
                c = minOf(c, r[i]); d = maxOf(d, r[i]); a = minOf(a, r[i + 1]); b = maxOf(b, r[i + 1]); i += 2
            }
        }
        return floatArrayOf(a, b, c, d)
    }

    fun latLonText(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.4f°%s %.4f°%s", abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W")

    private val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    fun compass(deg: Double): String = dirs[(((deg % 360 + 360) % 360) / 22.5 + 0.5).toInt() % 16]
}

object Time {
    private fun fmt(p: String, tz: TimeZone = TimeZone.getTimeZone("UTC")) =
        java.text.SimpleDateFormat(p, Locale.US).apply { timeZone = tz }

    fun iso(ms: Long): String = fmt("yyyy-MM-dd HH:mm:ss'Z'").format(java.util.Date(ms))
    fun hmsZ(ms: Long): String = fmt("HH:mm:ss'Z'").format(java.util.Date(ms))
    fun hmZ(ms: Long): String = fmt("HH:mm'Z'").format(java.util.Date(ms))
    fun local(ms: Long, pattern: String = "h:mm a"): String =
        fmt(pattern, TimeZone.getDefault()).format(java.util.Date(ms))

    fun ymd(ms: Long): String = fmt("yyyy/MM/dd").format(java.util.Date(ms))

    /** "3 min ago" style age. */
    fun age(ms: Long, now: Long = System.currentTimeMillis()): String {
        val s = (now - ms) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ${(s % 3600) / 60} min ago"
            else -> "${s / 86400} d ago"
        }
    }

    /** Parses ISO-8601 like 2026-09-30T19:41:00-05:00 or ...Z; 0 on failure. */
    fun parseIso(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val formats = arrayOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mmXXX")
        for (f in formats) {
            try {
                return java.text.SimpleDateFormat(f, Locale.US).parse(s)?.time ?: continue
            } catch (_: Exception) {
            }
        }
        return 0
    }
}
