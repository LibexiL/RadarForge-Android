package com.libexil.radarforge.core

import java.util.PriorityQueue
import kotlin.math.floor

/**
 * Region-based Doppler velocity dealiasing – the same method as the desktop app (and Py-ART's
 * region-based dealiaser): split the Nyquist interval into sub-intervals, label connected regions
 * inside each, then repeatedly merge the pair of regions sharing the longest boundary, unfolding the
 * smaller one by the whole number of Nyquist intervals that best matches the boundary.
 */
object Dealias {
    /**
     * Unfolded copy of [v] (nRays x nGates, row = ray sorted by azimuth; NaN = no echo, +Infinity = range
     * folded, both left as they are). Rays wrap around (the first and last ray are neighbours).
     * Returns null when there is nothing to do (no Nyquist velocity, fewer than two regions).
     */
    fun unfold(v: FloatArray, nRays: Int, nGates: Int, nyq: Float, splits: Int = 3, centered: Boolean = true): FloatArray? {
        if (nyq <= 0f || nRays < 2 || nGates < 2) return null
        val n = nRays * nGates
        val interval = 2.0 * nyq
        // which sub-interval each valid gate falls in
        val bin = ByteArray(n)
        var any = false
        for (i in 0 until n) {
            val x = v[i]
            if (x.isNaN() || x.isInfinite()) { bin[i] = -1; continue }
            bin[i] = floor((x + nyq) / interval * splits).toInt().coerceIn(0, splits - 1).toByte()
            any = true
        }
        if (!any) return null

        // ---- connected regions inside each sub-interval (4-neighbours, rays wrap)
        val labels = IntArray(n)
        val stack = IntArray(n)
        var nreg = 0
        for (i in 0 until n) {
            if (bin[i] < 0 || labels[i] != 0) continue
            nreg++
            val b = bin[i]
            var sp = 0
            stack[sp++] = i
            labels[i] = nreg
            while (sp > 0) {
                val p = stack[--sp]
                val r = p / nGates
                val g = p - r * nGates
                if (g > 0) { val q = p - 1; if (labels[q] == 0 && bin[q] == b) { labels[q] = nreg; stack[sp++] = q } }
                if (g < nGates - 1) { val q = p + 1; if (labels[q] == 0 && bin[q] == b) { labels[q] = nreg; stack[sp++] = q } }
                run { val q = ((r + 1) % nRays) * nGates + g; if (labels[q] == 0 && bin[q] == b) { labels[q] = nreg; stack[sp++] = q } }
                run { val q = ((r - 1 + nRays) % nRays) * nGates + g; if (labels[q] == 0 && bin[q] == b) { labels[q] = nreg; stack[sp++] = q } }
            }
        }
        if (nreg <= 1) return null

        // ---- boundary statistics between regions: count and sum(v(lo) - v(hi)) per (lo, hi) pair
        val edges = EdgeMap(nreg)
        for (p in 0 until n) {
            val la = labels[p]
            if (la == 0) continue
            val r = p / nGates
            val g = p - r * nGates
            if (g < nGates - 1) edges.add(la, labels[p + 1], v[p], v[p + 1])
            val q = ((r + 1) % nRays) * nGates + g
            edges.add(la, labels[q], v[p], v[q])
        }
        if (edges.size == 0) return null

        val sizes = LongArray(nreg + 1)
        for (l in labels) sizes[l]++
        val sizes0 = sizes.copyOf()                                    // sizes change while merging
        // adjacency: region -> (neighbour -> [sum(v_region - v_neighbour), count])
        val adj = arrayOfNulls<HashMap<Int, DoubleArray>>(nreg + 1)
        fun nb(a: Int): HashMap<Int, DoubleArray> = adj[a] ?: HashMap<Int, DoubleArray>(4).also { adj[a] = it }
        val heap = PriorityQueue<Entry>(edges.size)
        for (k in 0 until edges.size) {
            val a = edges.lo[k]; val b = edges.hi[k]; val c = edges.cnt[k]; val s = edges.sum[k]
            nb(a)[b] = doubleArrayOf(s, c)
            nb(b)[a] = doubleArrayOf(-s, c)
            heap.add(Entry(c, a, b))
        }
        val offset = IntArray(nreg + 1)
        val members = arrayOfNulls<IntList>(nreg + 1)
        val alive = BooleanArray(nreg + 1) { true }

        while (heap.isNotEmpty()) {
            val top = heap.poll() ?: break
            val a = top.a; val b = top.b
            if (!alive[a] || !alive[b]) continue
            val e = adj[a]?.get(b) ?: continue
            if (e[1] != top.count) continue                          // stale entry
            val big: Int; val small: Int; val s: Double
            if (sizes[a] >= sizes[b]) { big = a; small = b; s = e[0] } else { big = b; small = a; s = -e[0] }
            val k = Math.rint((s / e[1]) / interval).toInt()           // shift small by +k intervals
            val mem = members[small] ?: IntList().also { it.add(small) }
            members[small] = null
            if (k != 0) for (i in 0 until mem.size) offset[mem[i]] += k
            (members[big] ?: IntList().also { it.add(big); members[big] = it }).addAll(mem)
            sizes[big] += sizes[small]
            alive[small] = false
            val nbSmall = adj[small] ?: HashMap()
            adj[small] = null
            val nbBig = nb(big)
            nbBig.remove(small)
            for ((c, sc) in nbSmall) {
                if (c == big) continue
                val scNew = sc[0] + k * interval * sc[1]                // sum(v_small' - v_c)
                val nbC = nb(c)
                nbC.remove(small)
                val cur = nbBig[c]
                val merged = if (cur == null) doubleArrayOf(scNew, sc[1]).also { nbBig[c] = it } else { cur[0] += scNew; cur[1] += sc[1]; cur }
                nbC[big] = doubleArrayOf(-merged[0], merged[1])
                heap.add(Entry(merged[1], minOf(big, c), maxOf(big, c)))
            }
        }

        // The merging only fixes regions relative to each other: if the region an echo was matched to was
        // itself folded, that whole echo is a Nyquist interval off. Shift each separate echo back so most of
        // its gates keep their measured value (like Py-ART's "centered" option, per echo).
        if (centered) {
            for (root in 1..nreg) {
                if (!alive[root]) continue
                val mem = members[root]
                var sum = 0.0; var cnt = 0L
                if (mem == null) continue                                  // a lone region: never shifted
                for (i in 0 until mem.size) { val l = mem[i]; sum += offset[l].toDouble() * sizes0[l]; cnt += sizes0[l] }
                val k0 = if (cnt > 0) Math.rint(sum / cnt).toInt() else 0
                if (k0 != 0) for (i in 0 until mem.size) offset[mem[i]] -= k0
            }
        }
        val out = v.copyOf()
        for (i in 0 until n) {
            val l = labels[i]
            if (l != 0 && offset[l] != 0) out[i] = (v[i] + offset[l] * interval).toFloat()
        }
        return out
    }

    /** A dealiased copy of a velocity / SRV field (null when nothing changed or it isn't velocity). */
    fun field(f: Field): Field? {
        if (!f.product.isDoppler || f.nyquist <= 0f) return null
        // unfold the raw velocity (storm motion is removed afterwards, as for any SRV field)
        val raw = f.withCodes(f.codes8, f.codes16, f.variant, product = Product.VEL, stormU = 0f, stormV = 0f)
        val vals = raw.values()
        val out = unfold(vals, f.nRays, f.nGates, f.nyquist) ?: vals
        val (c8, c16) = raw.encode(out)
        // Nyquist 0: the data is continuous now, so smoothing mustn't unfold it again
        return f.withCodes(c8, c16, (f.variant + " dealiased").trim(), nyquist = 0f)
    }

    private class Entry(val count: Double, val a: Int, val b: Int) : Comparable<Entry> {
        // longest boundary first; ties by region numbers, like the desktop version's heap
        override fun compareTo(other: Entry): Int {
            val c = other.count.compareTo(count)
            if (c != 0) return c
            if (a != other.a) return a.compareTo(other.a)
            return b.compareTo(other.b)
        }
    }

    private class IntList {
        var data = IntArray(4)
        var size = 0
        fun add(x: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = x }
        fun addAll(o: IntList) {
            if (size + o.size > data.size) data = data.copyOf(maxOf(size + o.size, size * 2))
            System.arraycopy(o.data, 0, data, size, o.size)
            size += o.size
        }
        operator fun get(i: Int) = data[i]
    }

    /** Open-addressing map (lo, hi) -> edge index, with the per-edge count and sum. */
    private class EdgeMap(nreg: Int) {
        private val stride = nreg.toLong() + 1
        private var keys = LongArray(1 shl 12) { -1L }
        private var idx = IntArray(1 shl 12)
        var lo = IntArray(1024); var hi = IntArray(1024)
        var cnt = DoubleArray(1024); var sum = DoubleArray(1024)
        var size = 0

        fun add(la: Int, lb: Int, va: Float, vb: Float) {
            if (lb == 0 || la == lb) return
            val l: Int; val h: Int; val d: Double
            if (la < lb) { l = la; h = lb; d = (va - vb).toDouble() } else { l = lb; h = la; d = (vb - va).toDouble() }
            val key = l * stride + h
            var slot = (mix(key) and (keys.size - 1).toLong()).toInt()
            while (true) {
                val k = keys[slot]
                if (k == -1L) break
                if (k == key) { val e = idx[slot]; cnt[e] += 1.0; sum[e] += d; return }
                slot = (slot + 1) and (keys.size - 1)
            }
            if (size == lo.size) { lo = lo.copyOf(size * 2); hi = hi.copyOf(size * 2); cnt = cnt.copyOf(size * 2); sum = sum.copyOf(size * 2) }
            keys[slot] = key; idx[slot] = size
            lo[size] = l; hi[size] = h; cnt[size] = 1.0; sum[size] = d
            size++
            if (size * 2 > keys.size) grow()
        }

        private fun mix(k: Long): Long { var x = k * -0x61c8864680b583ebL; x = x xor (x ushr 29); return x }

        private fun grow() {
            val nk = LongArray(keys.size * 2) { -1L }
            val ni = IntArray(keys.size * 2)
            for (s in keys.indices) {
                val k = keys[s]
                if (k == -1L) continue
                var slot = (mix(k) and (nk.size - 1).toLong()).toInt()
                while (nk[slot] != -1L) slot = (slot + 1) and (nk.size - 1)
                nk[slot] = k; ni[slot] = idx[s]
            }
            keys = nk; idx = ni
        }
    }
}
