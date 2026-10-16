package com.libexil.radarforge.core

import kotlin.math.abs

/**
 * An ARGB picture laid flat on the map plane (km in the view's projection): a model field, a rainfall
 * or snowfall total. Row 0 is the northern edge (y1).
 */
class Raster(
    val key: String, val w: Int, val h: Int, val argb: IntArray,
    val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val smooth: Boolean = true, val alpha: Float = 1f,
) {
    fun withAlpha(a: Float) = Raster(key, w, h, argb, x0, y0, x1, y1, smooth, a)
}

/** A rectangle of the map plane split into w x h cells (cell centres sampled). */
class RasterFrame(val proj: Geo.Aeqd, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val w: Int, val h: Int) {
    val cellKm: Float get() = (x1 - x0) / w
    val key: String get() = "${proj.lat0},${proj.lon0}/$x0,$y0,$x1,$y1/$w,$h"

    fun x(col: Float) = x0 + (col + 0.5f) * (x1 - x0) / w
    fun y(row: Float) = y1 - (row + 0.5f) * (y1 - y0) / h

    companion object {
        /** A frame centred on the projection's origin, [widthKm] x [heightKm], at about [cellKm] (at most [maxCells] wide). */
        fun around(proj: Geo.Aeqd, widthKm: Float, heightKm: Float, cellKm: Float, maxCells: Int = 1100): RasterFrame {
            val c = maxOf(cellKm, widthKm / maxCells, heightKm / maxCells)
            val w = maxOf(8, (widthKm / c).toInt()); val h = maxOf(8, (heightKm / c).toInt())
            return RasterFrame(proj, -w * c / 2, -h * c / 2, w * c / 2, h * c / 2, w, h)
        }
    }
}

object MapRaster {
    /** Fractional grid (row, col) of every cell of [frame]: 2 floats per cell, NaN outside the grid. */
    fun sampleIndex(g: Grib2.Grid, frame: RasterFrame): FloatArray {
        val out = FloatArray(frame.w * frame.h * 2)
        val tmp = DoubleArray(2)
        // the inverse projection is the slow part: do it exactly on a coarse lattice and interpolate between
        val step = 4
        val lw = frame.w / step + 2; val lh = frame.h / step + 2
        val lat = DoubleArray(lw * lh); val lon = DoubleArray(lw * lh)
        for (j in 0 until lh) for (i in 0 until lw) {
            val ll = frame.proj.inverse(frame.x((i * step).toFloat()).toDouble(), frame.y((j * step).toFloat()).toDouble())
            lat[j * lw + i] = ll[0]; lon[j * lw + i] = ll[1]
        }
        // longitudes near the dateline would interpolate the long way round
        val wrap = lon.any { it > 170 } && lon.any { it < -170 }
        if (wrap) for (k in lon.indices) if (lon[k] < 0) lon[k] += 360.0
        for (r in 0 until frame.h) {
            val fj = r.toDouble() / step; val j0 = fj.toInt(); val tj = fj - j0
            for (c in 0 until frame.w) {
                val fi = c.toDouble() / step; val i0 = fi.toInt(); val ti = fi - i0
                val k = j0 * lw + i0
                val la = (lat[k] * (1 - ti) + lat[k + 1] * ti) * (1 - tj) + (lat[k + lw] * (1 - ti) + lat[k + lw + 1] * ti) * tj
                var lo = (lon[k] * (1 - ti) + lon[k + 1] * ti) * (1 - tj) + (lon[k + lw] * (1 - ti) + lon[k + lw + 1] * ti) * tj
                if (lo > 180) lo -= 360.0
                g.index(la, lo, tmp)
                val o = 2 * (r * frame.w + c)
                val inside = tmp[0] > -0.5 && tmp[0] < g.nj - 0.5 && (g.globalLatLon || (tmp[1] > -0.5 && tmp[1] < g.ni - 0.5))
                out[o] = if (inside) tmp[0].toFloat() else Float.NaN
                out[o + 1] = if (inside) tmp[1].toFloat() else Float.NaN
            }
        }
        return out
    }

    private val indexCache = object : LinkedHashMap<String, FloatArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 3
    }

    fun gridKey(g: Grib2.Grid): String {
        val l = g.lcc
        return "${g.ni}x${g.nj}/${g.la1}/${g.lo1}/${g.di}/${g.dj}/${g.scan}/" + (l?.let { "${it.n}/${it.lov}/${it.x1}/${it.y1}/${it.dx}" } ?: "")
    }

    fun cachedIndex(g: Grib2.Grid, frame: RasterFrame): FloatArray {
        val k = gridKey(g) + "|" + frame.key
        synchronized(indexCache) { indexCache[k]?.let { return it } }
        val idx = sampleIndex(g, frame)
        synchronized(indexCache) { indexCache[k] = idx }
        return idx
    }

    /** Values of [src] (on grid [g]) at each cell of the frame. */
    fun resample(g: Grib2.Grid, src: FloatArray, index: FloatArray, bilinear: Boolean): FloatArray {
        val n = index.size / 2
        val out = FloatArray(n)
        for (k in 0 until n) {
            val r = index[2 * k]
            out[k] = if (r.isNaN()) Float.NaN else g.sampleRc(src, r.toDouble(), index[2 * k + 1].toDouble(), bilinear)
        }
        return out
    }

    /** Colours through stops (value -> ARGB, blended between stops; transparent below the first). */
    fun colorize(values: FloatArray, stops: List<Pair<Float, Int>>): IntArray {
        // a lookup table over the stops' range is much faster than searching per cell
        val lo = stops.first().first; val hi = stops.last().first
        val n = 2048
        val lut = IntArray(n) { Models.colorOf(stops, lo + (hi - lo) * it / (n - 1)) }
        val top = stops.last().second
        return IntArray(values.size) { k ->
            val v = values[k]
            when {
                v.isNaN() || v < lo -> 0
                v >= hi -> top
                else -> lut[((v - lo) / (hi - lo) * (n - 1)).toInt()]
            }
        }
    }

    /** Categorical colours: each cell takes the stop at or below it, no blending across type boundaries. */
    fun colorizeTable(values: FloatArray, ct: ColorTable): IntArray {
        val lo = ct.vmin; val hi = ct.vmax
        val lutB = ct.lut(2048)
        return IntArray(values.size) { k ->
            val v = values[k]
            if (v.isNaN() || v < lo) 0 else {
                val i = (((v - lo) / (hi - lo)) * 2048).toInt().coerceIn(0, 2047) * 4
                ((lutB[i + 3].toInt() and 0xff) shl 24) or ((lutB[i].toInt() and 0xff) shl 16) or
                    ((lutB[i + 1].toInt() and 0xff) shl 8) or (lutB[i + 2].toInt() and 0xff)
            }
        }
    }

    // ---------------------------------------------------------------- contours
    class ContourLine(val level: Float, val pts: FloatArray)   // x,y km pairs

    /**
     * Contour lines of [values] (on [frame]) every [interval], from a smoothed copy at most [maxCells] wide.
     * Returns lines in km.
     */
    fun contours(values: FloatArray, frame: RasterFrame, interval: Float, maxCells: Int = 360, only: Float? = null): List<ContourLine> {
        val f = maxOf(1, (frame.w + maxCells - 1) / maxCells)
        val w = frame.w / f; val h = frame.h / f
        if (w < 3 || h < 3) return emptyList()
        // block average (ignoring NaN), then a light 3x3 blur
        val g = FloatArray(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            var s = 0f; var n = 0
            for (b in 0 until f) for (a in 0 until f) {
                val v = values[(j * f + b) * frame.w + i * f + a]
                if (!v.isNaN()) { s += v; n++ }
            }
            g[j * w + i] = if (n > 0) s / n else Float.NaN
        }
        val sm = FloatArray(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            var s = 0f; var n = 0
            for (b in -1..1) for (a in -1..1) {
                val jj = j + b; val ii = i + a
                if (jj < 0 || jj >= h || ii < 0 || ii >= w) continue
                val v = g[jj * w + ii]
                if (!v.isNaN()) { s += v; n++ }
            }
            sm[j * w + i] = if (g[j * w + i].isNaN() || n == 0) Float.NaN else s / n
        }
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in sm) if (!v.isNaN()) { if (v < lo) lo = v; if (v > hi) hi = v }
        if (lo > hi) return emptyList()
        val levels = if (only != null) listOf(only) else {
            val out = ArrayList<Float>()
            var lv = Math.ceil((lo / interval).toDouble()).toFloat() * interval
            while (lv <= hi && out.size < 80) { out.add(lv); lv += interval }
            out
        }
        val cell = frame.cellKm * f
        val xs = frame.x0 + cell / 2; val ys = frame.y1 - cell / 2
        val lines = ArrayList<ContourLine>()
        for (lv in levels) for (pl in march(sm, w, h, lv)) {
            val pts = FloatArray(pl.size)
            var k = 0
            while (k < pl.size) { pts[k] = xs + pl[k] * cell; pts[k + 1] = ys - pl[k + 1] * cell; k += 2 }
            lines.add(ContourLine(lv, pts))
        }
        return lines
    }

    /** Marching squares on a grid (x = column, y = row), segments joined into polylines (grid units). */
    fun march(v: FloatArray, w: Int, h: Int, level: Float): List<FloatArray> {
        // edge ids: horizontal edge (i, j)-(i+1, j) = 2 * (j * w + i); vertical (i, j)-(i, j+1) = 2 * (j * w + i) + 1
        val segA = ArrayList<Int>(); val segB = ArrayList<Int>()
        fun crosses(a: Float, b: Float) = (a < level) != (b < level)
        for (j in 0 until h - 1) for (i in 0 until w - 1) {
            val a = v[j * w + i]; val b = v[j * w + i + 1]; val c = v[(j + 1) * w + i + 1]; val d = v[(j + 1) * w + i]
            if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) continue
            val e = IntArray(4); var n = 0
            if (crosses(a, b)) e[n++] = 2 * (j * w + i)                // top
            if (crosses(b, c)) e[n++] = 2 * (j * w + i + 1) + 1        // right
            if (crosses(d, c)) e[n++] = 2 * ((j + 1) * w + i)          // bottom
            if (crosses(a, d)) e[n++] = 2 * (j * w + i) + 1            // left
            if (n == 2) { segA.add(e[0]); segB.add(e[1]) }
            else if (n == 4) {
                // saddle: decide by the centre value
                val m = (a + b + c + d) / 4
                if ((m < level) == (a < level)) { segA.add(e[0]); segB.add(e[1]); segA.add(e[2]); segB.add(e[3]) }
                else { segA.add(e[0]); segB.add(e[3]); segA.add(e[1]); segB.add(e[2]) }
            }
        }
        // join segments sharing an edge point
        val adj = HashMap<Int, IntArray>(segA.size * 2)      // edge -> up to two segment ids (-1 = none)
        fun link(edge: Int, s: Int) {
            val l = adj.getOrPut(edge) { intArrayOf(-1, -1) }
            if (l[0] < 0) l[0] = s else l[1] = s
        }
        for (s in segA.indices) { link(segA[s], s); link(segB[s], s) }
        val used = BooleanArray(segA.size)
        fun pointOf(edge: Int, out: ArrayList<Float>) {
            val cellIdx = edge / 2; val i = cellIdx % w; val j = cellIdx / w
            if (edge % 2 == 0) {
                val a = v[j * w + i]; val b = v[j * w + i + 1]
                out.add(i + ((level - a) / (b - a)).coerceIn(0f, 1f)); out.add(j.toFloat())
            } else {
                val a = v[j * w + i]; val d = v[(j + 1) * w + i]
                out.add(i.toFloat()); out.add(j + ((level - a) / (d - a)).coerceIn(0f, 1f))
            }
        }
        val lines = ArrayList<FloatArray>()
        for (s0 in segA.indices) {
            if (used[s0]) continue
            used[s0] = true
            // walk both ways from the first segment
            val fwd = ArrayList<Int>(); val back = ArrayList<Int>()
            fun walk(startEdge: Int, out: ArrayList<Int>) {
                var edge = startEdge
                while (true) {
                    val l = adj[edge] ?: break
                    val s = if (l[0] >= 0 && !used[l[0]]) l[0] else if (l[1] >= 0 && !used[l[1]]) l[1] else break
                    used[s] = true
                    edge = if (segA[s] == edge) segB[s] else segA[s]
                    out.add(edge)
                }
            }
            walk(segB[s0], fwd)
            walk(segA[s0], back)
            val edges = ArrayList<Int>(back.size + fwd.size + 2)
            for (k in back.indices.reversed()) edges.add(back[k])
            edges.add(segA[s0]); edges.add(segB[s0]); edges.addAll(fwd)
            if (edges.size < 3) continue
            val pts = ArrayList<Float>(edges.size * 2)
            for (e in edges) pointOf(e, pts)
            lines.add(pts.toFloatArray())
        }
        return lines
    }

    /** Lines (km pairs) as a drawable layer. */
    fun layerOf(lines: List<FloatArray>): ProjectedLayer {
        val total = lines.sumOf { it.size / 2 + 1 }
        val out = FloatArray(total * 2)
        val chunks = ArrayList<ProjectedLayer.Chunk>()
        var n = 0; var start = 0
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (l in lines) {
            var k = 0
            while (k + 1 < l.size) {
                val x = l[k]; val y = l[k + 1]
                out[2 * n] = x; out[2 * n + 1] = y
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                n++; k += 2
            }
            out[2 * n] = ProjectedLayer.SEP; out[2 * n + 1] = ProjectedLayer.SEP; n++
            if (n - start >= 4096) { chunks.add(ProjectedLayer.Chunk(start, n - start, x0, y0, x1, y1)); start = n; x0 = Float.MAX_VALUE; y0 = Float.MAX_VALUE; x1 = -Float.MAX_VALUE; y1 = -Float.MAX_VALUE }
        }
        if (n > start) chunks.add(ProjectedLayer.Chunk(start, n - start, x0, y0, x1, y1))
        return ProjectedLayer(out, chunks)
    }

    /** A label position for each long enough line: (x, y, level), spaced at least [minGapKm] apart. */
    fun labelSpots(lines: List<ContourLine>, minLenKm: Float, minGapKm: Float): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (l in lines) {
            val p = l.pts
            var len = 0f
            var k = 2
            while (k + 1 < p.size) { len += Math.hypot((p[k] - p[k - 2]).toDouble(), (p[k + 1] - p[k - 1]).toDouble()).toFloat(); k += 2 }
            if (len < minLenKm) continue
            // a spot every ~4 gaps along long lines
            val every = maxOf(minGapKm * 4, len / 3)
            var acc = every / 2; k = 2
            while (k + 1 < p.size) {
                acc += Math.hypot((p[k] - p[k - 2]).toDouble(), (p[k + 1] - p[k - 1]).toDouble()).toFloat()
                if (acc >= every) {
                    acc = 0f
                    val x = p[k]; val y = p[k + 1]
                    if (out.none { abs(it[0] - x) < minGapKm && abs(it[1] - y) < minGapKm }) out.add(floatArrayOf(x, y, l.level))
                }
                k += 2
            }
        }
        return out
    }

    /** Wind barbs on a regular lattice of the frame: x, y (km), u, v (kts) per barb, every [stepKm]. */
    fun barbs(u: FloatArray, v: FloatArray, frame: RasterFrame, stepKm: Float): FloatArray {
        val s = maxOf(1, (stepKm / frame.cellKm).toInt())
        val out = ArrayList<Float>()
        var j = s / 2
        while (j < frame.h) {
            var i = s / 2
            while (i < frame.w) {
                val k = j * frame.w + i
                if (!u[k].isNaN() && !v[k].isNaN()) { out.add(frame.x(i.toFloat())); out.add(frame.y(j.toFloat())); out.add(u[k]); out.add(v[k]) }
                i += s
            }
            j += s
        }
        return out.toFloatArray()
    }

    /** The barb's flags, full and half barbs for a speed (kts, rounded to 5). */
    fun barbParts(kts: Float): IntArray {
        var r = (Math.round(kts / 5f) * 5)
        val flags = r / 50; r -= flags * 50
        val full = r / 10; r -= full * 10
        return intArrayOf(flags, full, if (r >= 5) 1 else 0)
    }

    /** Grid north vs. map north: the angle (radians, clockwise) to turn a true-north vector at (x, y) for this projection. */
    fun northAngle(proj: Geo.Aeqd, x: Float, y: Float): Double {
        val ll = proj.inverse(x.toDouble(), y.toDouble())
        val p2 = proj.forward(ll[0] + 0.05, ll[1])
        return Math.atan2(p2[0] - x, p2[1] - y)
    }

}
