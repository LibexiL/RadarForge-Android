package com.libexil.radarforge.core

/**
 * Σ max-value trail (as on the desktop app): every gate shows the most extreme value seen there over
 * the loop up to the frame shown – hail swaths from reflectivity, rotation tracks from velocity, a
 * debris trail from CC (its lowest value).
 */
object Trail {
    enum class Rule { MAX, MIN, ABSMAX }

    fun rule(p: Product): Rule = when (p) {
        Product.CC -> Rule.MIN                     // low CC is the interesting part (debris)
        Product.VEL, Product.SRV -> Rule.ABSMAX    // strongest either way, keeping the sign
        else -> Rule.MAX
    }

    /**
     * [srcVals] (gate values of [src]) at [target]'s gates: nearest ray and gate, NaN where [src]
     * has no ray (gaps, sector scans) or no gate there.
     */
    fun resample(src: Field, srcVals: FloatArray, target: Field): FloatArray {
        val nt = target.nRays; val gt = target.nGates
        val out = FloatArray(nt * gt) { Float.NaN }
        val ns = src.nRays
        if (ns == 0) return out
        // gate index in src for each target gate (both are slant ranges at almost the same angle)
        val gmap = IntArray(gt) { g ->
            val r = target.firstGate + target.gateSpacing * g
            val k = Math.floor(((r - (src.firstGate - src.gateSpacing / 2)) / src.gateSpacing).toDouble()).toInt()
            if (k in 0 until src.nGates) k else -1
        }
        for (t in 0 until nt) {
            val az = target.azCenter[t]
            var lo = 0; var hi = ns
            while (lo < hi) { val m = (lo + hi) ushr 1; if (src.azCenter[m] < az) lo = m + 1 else hi = m }
            val i = lo % ns
            val j = (lo - 1 + ns) % ns
            fun diff(k: Int) = Math.abs(((src.azCenter[k] - az + 540f) % 360f) - 180f)
            val di = diff(i); val dj = diff(j)
            val row = if (di <= dj) i else j
            val width = maxOf(Math.abs(((src.azHi[row] - src.azLo[row] + 540f) % 360f) - 180f), 0.5f)
            if (minOf(di, dj) > width) continue
            val so = row * src.nGates
            val to = t * gt
            for (g in 0 until gt) {
                val k = gmap[g]
                if (k >= 0) out[to + g] = srcVals[so + k]
            }
        }
        return out
    }

    /**
     * [target] with the trail of [target] and [older] (a frame, or the trail so far). The result keeps
     * target's rays and gates; SRV values have the storm motion removed already, so its storm motion is 0.
     */
    fun combine(target: Field, older: Field, rule: Rule, variant: String): Field {
        val base = target.values()
        val other = resample(older, older.values(), target)
        val out = FloatArray(base.size)
        for (i in base.indices) {
            val b = base[i]; val o = other[i]
            val bv = if (b == Float.POSITIVE_INFINITY) Float.NaN else b
            val ov = if (o == Float.POSITIVE_INFINITY) Float.NaN else o
            var r = when {
                bv.isNaN() -> ov
                ov.isNaN() -> bv
                rule == Rule.MAX -> maxOf(bv, ov)
                rule == Rule.MIN -> minOf(bv, ov)
                else -> if (Math.abs(ov) > Math.abs(bv)) ov else bv
            }
            // range folded only where nothing better was seen
            if (r.isNaN() && (b == Float.POSITIVE_INFINITY || o == Float.POSITIVE_INFINITY)) r = Float.POSITIVE_INFINITY
            out[i] = r
        }
        val (c8, c16) = target.encode(out)
        return target.withCodes(c8, c16, variant, nyquist = 0f, stormU = 0f, stormV = 0f)
    }
}
