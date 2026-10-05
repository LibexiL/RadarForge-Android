package com.libexil.radarforge.core

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

enum class Product(
    val id: String, val title: String, val short: String, val category: String,
    val moment: String, val palette: String, val units: String, val decimals: Int,
) {
    REF("REF", "Base Reflectivity", "BR", "Base", "REF", "REF", "dBZ", 1),
    VEL("VEL", "Base Velocity", "BV", "Base", "VEL", "VEL", "m/s", 1),
    SRV("SRV", "Storm Relative Velocity", "SRV", "Derived", "VEL", "VEL", "m/s", 1),
    SW("SW", "Spectrum Width", "SW", "Base", "SW", "SW", "m/s", 1),
    ZDR("ZDR", "Differential Reflectivity", "ZDR", "Dual-Pol", "ZDR", "ZDR", "dB", 2),
    CC("CC", "Correlation Coefficient", "CC", "Dual-Pol", "RHO", "CC", "", 3),
    PHI("PHI", "Differential Phase", "PHI", "Dual-Pol", "PHI", "PHI", "deg", 1);

    val isVelocity: Boolean get() = units == "m/s"

    /** Doppler velocity (aliased, so smoothing unfolds neighbours first). */
    val isDoppler: Boolean get() = this == VEL || this == SRV

    companion object {
        fun byId(id: String?): Product = entries.firstOrNull { it.id == id } ?: REF
    }
}

/** One antenna pass at an angle; a split cut (surveillance + Doppler) is merged into one scan. */
class Scan(val elevation: Float, val timeMs: Long, val sweeps: MutableMap<String, Sweep>) {
    internal var parts = 1
}

/** All scans at (about) one elevation angle, oldest first (SAILS repeats the lowest tilt). */
class Tilt(val elevation: Float, val scans: MutableList<Scan>) {
    val label: String
        get() = String.format(java.util.Locale.US, "%.1f°", elevation) + if (scans.size > 1) " ×${scans.size}" else ""

    /** Newest scan holding this moment (e.g. the last SAILS cut). */
    fun sweep(moment: String): Sweep? {
        for (i in scans.indices.reversed()) scans[i].sweeps[moment]?.let { return it }
        return null
    }

    fun moments(): Set<String> = scans.flatMap { it.sweeps.keys }.toSet()
}

object Tilts {
    fun build(vol: Volume): List<Tilt> {
        val scans = ArrayList<Scan>()
        for (sw in vol.sweeps) {
            val last = scans.lastOrNull()
            if (last != null && Math.abs(last.elevation - sw.elevation) < 0.3f && last.parts == 1 &&
                "VEL" !in last.sweeps && "VEL" in sw.moments) {
                for (m in sw.moments.keys) if (m !in last.sweeps || m == "VEL" || m == "SW") last.sweeps[m] = sw
                last.parts = 2
                continue
            }
            scans.add(Scan(sw.elevation, sw.startMs, sw.moments.keys.associateWith { sw }.toMutableMap()))
        }
        val sorted = scans.sortedWith(compareBy<Scan>({ Math.round(it.elevation * 10f) }, { it.timeMs }))
        val tilts = ArrayList<Tilt>()
        for (sc in sorted) {
            val t = tilts.lastOrNull()
            if (t != null && Math.abs(t.elevation - sc.elevation) < 0.25f) t.scans.add(sc)
            else tilts.add(Tilt(Math.round(sc.elevation * 100f) / 100f, mutableListOf(sc)))
        }
        return tilts
    }

    /** Index of the tilt closest to [elevation] (keeps the user's angle when volumes change). */
    fun closest(tilts: List<Tilt>, elevation: Float): Int {
        if (tilts.isEmpty()) return -1
        var best = 0
        for (i in tilts.indices) if (Math.abs(tilts[i].elevation - elevation) < Math.abs(tilts[best].elevation - elevation)) best = i
        return best
    }
}

/**
 * One product at one tilt, ready to draw: rays sorted by azimuth, with the
 * angular bounds of each ray. Gate codes are kept raw; the GPU decodes them.
 */
class Field(
    val product: Product,
    val site: String,
    val volumeMs: Long,
    val sweepMs: Long,
    val elevation: Float,
    val nRays: Int,
    val nGates: Int,
    val firstGate: Float,
    val gateSpacing: Float,
    val scale: Float,
    val offset: Float,
    val azCenter: FloatArray,
    val azLo: FloatArray,
    val azHi: FloatArray,
    val codes8: ByteArray?,
    val codes16: CharArray?,
    val nyquist: Float,
    val stormU: Float,        // m/s, storm motion for SRV (0 otherwise)
    val stormV: Float,
    val complete: Boolean,
    /** "" for the radar's own data; otherwise what was done to it (dealiased, Σ trail...), so GPU copies don't mix. */
    val variant: String = "",
) {
    val maxRange: Float get() = firstGate + gateSpacing * (nGates - 0.5f)
    val key: String = "$site/$volumeMs/$sweepMs/${product.id}/$elevation/$nRays/$stormU/$stormV" + if (variant.isEmpty()) "" else "/$variant"

    /** Largest code the gate words can hold. */
    val maxCode: Int get() = if (codes8 != null) 255 else 65535

    /** The same rays and gates with new codes (and product / storm motion / Nyquist as given). */
    fun withCodes(c8: ByteArray?, c16: CharArray?, variant: String, product: Product = this.product, nyquist: Float = this.nyquist,
                  stormU: Float = this.stormU, stormV: Float = this.stormV) =
        Field(product, site, volumeMs, sweepMs, elevation, nRays, nGates, firstGate, gateSpacing, scale, offset,
            azCenter, azLo, azHi, c8, c16, nyquist, stormU, stormV, complete, variant)

    /** Every gate's value (storm motion removed for SRV); NaN for no echo, +Infinity for range folded. */
    fun values(): FloatArray {
        val out = FloatArray(nRays * nGates)
        val srv = product == Product.SRV && (stormU != 0f || stormV != 0f)
        for (r in 0 until nRays) {
            val sub = if (srv) { val a = Math.toRadians(azCenter[r].toDouble()); (stormU * sin(a) + stormV * cos(a)).toFloat() } else 0f
            val o = r * nGates
            for (g in 0 until nGates) {
                val c = if (codes8 != null) codes8[o + g].toInt() and 0xff else codes16!![o + g].code
                out[o + g] = when (c) { 0 -> Float.NaN; 1 -> Float.POSITIVE_INFINITY; else -> (c - offset) / scale - sub }
            }
        }
        return out
    }

    /** Codes for [values] in this field's scale (NaN -> 0, +Infinity -> 1, the rest clamped to 2..maxCode). */
    fun encode(values: FloatArray): Pair<ByteArray?, CharArray?> {
        val hi = maxCode
        fun code(v: Float): Int = when {
            v.isNaN() -> 0
            v == Float.POSITIVE_INFINITY -> 1
            else -> Math.round(v * scale + offset).coerceIn(2, hi)
        }
        return if (codes8 != null) Pair(ByteArray(values.size) { code(values[it]).toByte() }, null)
        else Pair(null, CharArray(values.size) { code(values[it]).toChar() })
    }

    fun code(ray: Int, gate: Int): Int {
        val i = ray * nGates + gate
        return if (codes8 != null) codes8[i].toInt() and 0xff else codes16!![i].code
    }

    class Sample(val value: Float, val rangeFolded: Boolean, val slantKm: Double, val heightKm: Double, val azimuth: Double)

    /** Value at azimuth (deg) / ground distance (km) from the radar, or null outside the data. */
    fun sample(azDeg: Double, groundKm: Double): Sample? {
        if (nRays == 0) return null
        var lo = 0; var hi = nRays
        while (lo < hi) { val m = (lo + hi) ushr 1; if (azCenter[m] < azDeg) lo = m + 1 else hi = m }
        for (j in intArrayOf(lo % nRays, (lo - 1 + nRays) % nRays)) {
            val span = ((azHi[j] - azLo[j]) % 360f + 360f) % 360f
            val d = ((azDeg - azLo[j]) % 360.0 + 360.0) % 360.0
            if (d <= span + 1e-6) {
                val r = Geo.slantRange(groundKm, elevation.toDouble())
                val g = floor((r - (firstGate - gateSpacing / 2)) / gateSpacing).toInt()
                if (g < 0 || g >= nGates) return null
                val c = code(j, g)
                if (c == 0) return null
                val h = Geo.beamHeight(r, elevation.toDouble())
                if (c == 1) return Sample(Float.NaN, true, r, h, azDeg)
                var v = (c - offset) / scale
                if (product == Product.SRV) {
                    val a = Math.toRadians(azCenter[j].toDouble())
                    v -= (stormU * sin(a) + stormV * cos(a)).toFloat()
                }
                return Sample(v, false, r, h, azDeg)
            }
        }
        return null
    }

    companion object {
        /** Builds the field for [product] from [sweep]; null when the sweep lacks the moment. */
        fun from(product: Product, site: String, volumeMs: Long, sweep: Sweep,
                 stormDirFrom: Float = 240f, stormKts: Float = 30f): Field? {
            val m = sweep.moments[product.moment] ?: return null
            val n = sweep.nRays
            if (n < 10) return null
            val order = (0 until n).sortedBy { sweep.azimuths[it] }
            val az = FloatArray(n) { sweep.azimuths[order[it]] }
            val lo = FloatArray(n)
            val hi = FloatArray(n)
            val cap = sweep.azRes * 0.75f
            for (i in 0 until n) {
                val gn = ((az[(i + 1) % n] - az[i]) % 360f + 360f) % 360f
                val gp = ((az[i] - az[(i - 1 + n) % n]) % 360f + 360f) % 360f
                hi[i] = az[i] + minOf(gn / 2f, cap)
                lo[i] = az[i] - minOf(gp / 2f, cap)
            }
            val g = m.nGates
            var c8: ByteArray? = null
            var c16: CharArray? = null
            if (m.data8 != null) {
                val src = m.data8
                c8 = ByteArray(n * g)
                for (i in 0 until n) System.arraycopy(src, order[i] * g, c8, i * g, g)
            } else {
                val src = m.data16!!
                c16 = CharArray(n * g)
                for (i in 0 until n) System.arraycopy(src, order[i] * g, c16, i * g, g)
            }
            var su = 0f; var sv = 0f
            if (product == Product.SRV) {
                val toward = Math.toRadians(stormDirFrom + 180.0)
                su = (stormKts * Geo.KT * sin(toward)).toFloat()
                sv = (stormKts * Geo.KT * cos(toward)).toFloat()
            }
            return Field(product, site, volumeMs, sweep.startMs, sweep.elevation, n, g, m.firstGate, m.gateSpacing,
                m.scale, m.offset, az, lo, hi, c8, c16, sweep.nyquist, su, sv, sweep.complete)
        }
    }
}
