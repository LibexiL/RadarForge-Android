package com.libexil.radarforge.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * GRLevelX-compatible colour tables (.pal): Product, Units, Scale, Offset, Step,
 * RF, Color, Color4, SolidColor, SolidColor4 (same semantics as GR2Analyst):
 *  - values below the first entry are transparent
 *  - Color blends to its optional second colour, else to the next entry
 *  - SolidColor holds until the next entry; the last entry holds to the end
 *  - data are converted with  display = data * Scale + Offset
 */
class ColorTable(val name: String) {
    class Entry(val value: Float, val c1: IntArray, val c2: IntArray?, val solid: Boolean)

    var product = ""
    var units = ""
    var scale: Float? = null
    var offset = 0f
    var step: Float? = null
    var rf = intArrayOf(119, 0, 125, 255)
    val entries = ArrayList<Entry>()
    var source = ""          // asset or file path

    val vmin: Float get() = entries.firstOrNull()?.value ?: 0f
    val vmax: Float
        get() {
            if (entries.isEmpty()) return 1f
            if (entries.size == 1) return entries[0].value + 1f
            val last = entries.last().value
            return last + maxOf(last - entries[entries.size - 2].value, 1e-6f)
        }

    /** Factor applied to stored values (e.g. m/s) before the lookup. */
    fun dataScale(storageUnits: String): Float {
        scale?.let { return it }
        val u = units.uppercase().replace(" ", "")
        return UNIT_FACTORS[storageUnits]?.get(u) ?: 1f
    }

    /** RGBA (0-255 each) at a display value; alpha 0 = transparent. */
    fun colorAt(v: Float, out: IntArray = IntArray(4)): IntArray {
        out.fill(0)
        if (entries.isEmpty() || v.isNaN()) return out
        var idx = -1
        for (i in entries.indices) if (entries[i].value <= v) idx = i else break
        if (idx < 0) return out
        val e = entries[idx]
        if (e.solid || (idx == entries.size - 1 && e.c2 == null)) {
            for (k in 0..3) out[k] = e.c1[k]
            return out
        }
        val c2 = e.c2 ?: entries.getOrNull(idx + 1)?.c1 ?: e.c1
        val v1 = e.value
        val v2 = if (idx + 1 < entries.size) entries[idx + 1].value else vmax
        val t = ((v - v1) / maxOf(v2 - v1, 1e-12f)).coerceIn(0f, 1f)
        for (k in 0..3) out[k] = (e.c1[k] + (c2[k] - e.c1[k]) * t).roundToInt().coerceIn(0, 255)
        return out
    }

    /** n RGBA entries covering [vmin, vmax) for a GPU lookup texture. */
    fun lut(n: Int = 1024): ByteArray {
        val lo = vmin; val hi = vmax
        val out = ByteArray(n * 4)
        val c = IntArray(4)
        for (i in 0 until n) {
            colorAt(lo + (i + 0.5f) / n * (hi - lo), c)
            for (k in 0..3) out[i * 4 + k] = c[k].toByte()
        }
        return out
    }

    /** Values to label on a legend. */
    fun legendStops(count: Int = 10): List<Float> {
        val lo = vmin; val hi = vmax
        var st = step ?: 0f
        if (st <= 0f || (hi - lo) / st > 24) st = niceStep((hi - lo) / count)
        while ((hi - lo) / st > 16) st *= 2
        val out = ArrayList<Float>()
        var v = ceil(lo / st) * st
        while (v <= hi + 1e-4f) { out.add(v); v += st }
        return out
    }

    companion object {
        private val UNIT_FACTORS = mapOf(
            "m/s" to mapOf("KTS" to 1.943844f, "KT" to 1.943844f, "KNOTS" to 1.943844f, "MPH" to 2.236936f,
                "KMH" to 3.6f, "KM/H" to 3.6f, "KPH" to 3.6f, "M/S" to 1f, "MPS" to 1f),
        )

        private fun niceStep(x: Float): Float {
            if (x <= 0f) return 1f
            val p = 10.0.pow(floor(log10(x.toDouble()))).toFloat()
            for (m in floatArrayOf(1f, 2f, 2.5f, 5f, 10f)) if (m * p >= x) return m * p
            return 10 * p
        }

        private val NUM = Regex("[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?")

        fun parse(text: String, name: String): ColorTable {
            val ct = ColorTable(name)
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore(';').trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || ':' !in line) continue
                val key = line.substringBefore(':').trim().lowercase()
                val rest = line.substringAfter(':').trim()
                val nums = NUM.findAll(rest).map { it.value.toFloat() }.toList()
                when (key) {
                    "product" -> ct.product = rest
                    "units" -> ct.units = rest
                    "scale" -> if (nums.isNotEmpty()) ct.scale = nums[0]
                    "offset" -> if (nums.isNotEmpty()) ct.offset = nums[0]
                    "step" -> if (nums.isNotEmpty()) ct.step = nums[0]
                    "rf" -> if (nums.size >= 3) ct.rf = intArrayOf(nums[0].toInt(), nums[1].toInt(), nums[2].toInt(),
                        if (nums.size > 3) nums[3].toInt() else 255)
                    "color", "solidcolor" -> if (nums.size >= 4) {
                        val c1 = intArrayOf(nums[1].toInt(), nums[2].toInt(), nums[3].toInt(), 255)
                        val c2 = if (nums.size >= 7 && key == "color") intArrayOf(nums[4].toInt(), nums[5].toInt(), nums[6].toInt(), 255) else null
                        ct.entries.add(Entry(nums[0], c1, c2, key == "solidcolor"))
                    }
                    "color4", "solidcolor4" -> if (nums.size >= 5) {
                        val c1 = intArrayOf(nums[1].toInt(), nums[2].toInt(), nums[3].toInt(), nums[4].toInt())
                        val c2 = if (nums.size >= 9 && key == "color4") intArrayOf(nums[5].toInt(), nums[6].toInt(), nums[7].toInt(), nums[8].toInt()) else null
                        ct.entries.add(Entry(nums[0], c1, c2, key == "solidcolor4"))
                    }
                }
            }
            ct.entries.sortBy { it.value }
            return ct
        }

        private val FAMILY = mapOf(
            "BR" to "REF", "REF" to "REF", "BREF" to "REF", "CR" to "REF", "CREF" to "REF", "Z" to "REF", "DBZ" to "REF",
            "N0Q" to "REF", "N0B" to "REF", "N0R" to "REF",
            "BV" to "VEL", "VEL" to "VEL", "V" to "VEL", "SRV" to "VEL", "SRM" to "VEL", "DV" to "VEL", "N0U" to "VEL",
            "N0G" to "VEL", "N0S" to "VEL",
            "SW" to "SW", "ZDR" to "ZDR", "DR" to "ZDR", "N0X" to "ZDR", "CC" to "CC", "RHO" to "CC", "RHOHV" to "CC",
            "N0C" to "CC", "KDP" to "KDP", "N0K" to "KDP", "PHI" to "PHI", "PHIDP" to "PHI", "DP" to "PHI",
        )

        /** Which palette family ("REF", "VEL", ...) a table was made for, or null. */
        fun family(ct: ColorTable): String? {
            val key = ct.product.trim().uppercase().replace(" ", "")
            FAMILY[key]?.let { return it }
            if (key.isEmpty() && ct.units.trim().uppercase() == "DBZ") return "REF"
            return null
        }
    }
}
