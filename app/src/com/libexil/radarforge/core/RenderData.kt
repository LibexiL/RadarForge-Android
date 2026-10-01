package com.libexil.radarforge.core

import kotlin.math.cos
import kotlin.math.sin

/** GPU-ready data built on the CPU (no GL calls here, so it is testable). */
object RenderData {
    /** Floats per radar vertex: x, y (km), row, azLo, azHi. */
    const val RADAR_STRIDE = 5

    /**
     * One triangle per ray: the radar, and the two far corners of the ray's wedge
     * a little beyond the last gate (the fragment shader trims to the real range).
     */
    fun radarMesh(f: Field): FloatArray {
        val n = f.nRays
        val out = FloatArray(n * 3 * RADAR_STRIDE)
        val reach = (f.maxRange + f.gateSpacing) * 1.02f
        var o = 0
        for (i in 0 until n) {
            val lo = f.azLo[i]
            val hi = f.azHi[i]
            val a0 = Math.toRadians(lo.toDouble())
            val a1 = Math.toRadians(hi.toDouble())
            // the chord between the far corners must still contain the arc: push corners out
            val half = (hi - lo) * 0.5
            val k = (reach / cos(Math.toRadians(half))).toFloat()
            fun put(x: Float, y: Float) {
                out[o++] = x; out[o++] = y; out[o++] = i.toFloat(); out[o++] = lo; out[o++] = hi
            }
            put(0f, 0f)
            put((k * sin(a0)).toFloat(), (k * cos(a0)).toFloat())
            put((k * sin(a1)).toFloat(), (k * cos(a1)).toFloat())
        }
        return out
    }

    /** Corners of the instanced line quad (two triangles). */
    val LINE_CORNERS = floatArrayOf(0f, -1f, 1f, -1f, 1f, 1f, 0f, -1f, 1f, 1f, 0f, 1f)

    class Display(val dscale: Float, val doffset: Float, val lutMin: Float, val lutMax: Float)

    /** How stored values (product units) map onto the colour table's own scale. */
    fun display(p: Product, ct: ColorTable): Display =
        Display(ct.dataScale(p.units), ct.offset, ct.vmin, ct.vmax)

    /** Circle polylines (km) for range rings every [stepKm], with separators. */
    fun rangeRings(maxKm: Float, stepKm: Float, segments: Int = 180): FloatArray {
        val rings = (maxKm / stepKm).toInt()
        val out = FloatArray(rings * (segments + 2) * 2)
        var o = 0
        for (r in 1..rings) {
            val rad = r * stepKm
            for (s in 0..segments) {
                val a = 2 * Math.PI * s / segments
                out[o++] = (rad * sin(a)).toFloat(); out[o++] = (rad * cos(a)).toFloat()
            }
            out[o++] = ProjectedLayer.SEP; out[o++] = ProjectedLayer.SEP
        }
        return out
    }
}
