package com.libexil.radarforge.core

import kotlin.math.abs
import kotlin.math.hypot

/** Maths for the measuring tools (distance ruler and storm track). */
object Measure {
    /** A city the storm track passes near: [minutes] after the track's start, [offKm] to the side. */
    class Eta(val name: String, val minutes: Double, val offKm: Double, val x: Float, val y: Float)

    /** Great-circle length of a path of (lat, lon) points, km. */
    fun pathKm(pts: List<DoubleArray>): Double {
        var t = 0.0
        for (i in 1 until pts.size) t += Geo.distanceKm(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
        return t
    }

    /**
     * Cities within [halfWidthKm] of the track from (ax, ay) to (bx, by) (projected km) that the
     * storm reaches within the [minutes] the track covers, soonest first. Each name appears once.
     */
    fun etas(ax: Float, ay: Float, bx: Float, by: Float, minutes: Double, cities: ProjectedCities?,
             halfWidthKm: Double, max: Int = 12): List<Eta> {
        if (cities == null) return emptyList()
        val dx = (bx - ax).toDouble(); val dy = (by - ay).toDouble()
        val len = hypot(dx, dy)
        if (len < 0.5 || minutes <= 0) return emptyList()
        val ux = dx / len; val uy = dy / len
        val out = ArrayList<Eta>()
        for (k in cities.x.indices) {
            val px = (cities.x[k] - ax).toDouble(); val py = (cities.y[k] - ay).toDouble()
            // quick reject: farther than the track plus the corridor
            if (abs(px) > len + halfWidthKm || abs(py) > len + halfWidthKm) continue
            val along = px * ux + py * uy
            if (along < 0 || along > len) continue
            val across = -px * uy + py * ux
            if (abs(across) > halfWidthKm) continue
            out.add(Eta(cities.names[k], along / len * minutes, across, cities.x[k], cities.y[k]))
        }
        return out.sortedBy { it.minutes }.distinctBy { it.name }.take(max)
    }

    /** Minutes until the track reaches the point (px, py), or null when the point is off the corridor (or far beyond the arrow). */
    fun etaAt(ax: Float, ay: Float, bx: Float, by: Float, minutes: Double, px: Float, py: Float, halfWidthKm: Double): Double? {
        val dx = (bx - ax).toDouble(); val dy = (by - ay).toDouble()
        val len = hypot(dx, dy)
        if (len < 0.5) return null
        val ux = dx / len; val uy = dy / len
        val qx = (px - ax).toDouble(); val qy = (py - ay).toDouble()
        val along = qx * ux + qy * uy
        val across = -qx * uy + qy * ux
        // past the arrow the storm is assumed to keep going, up to three times as far
        if (along < 0 || along > len * 3 || abs(across) > halfWidthKm) return null
        return along / len * minutes
    }

    /** Tick spacing for a track lasting [minutes]. */
    fun tickMinutes(minutes: Int): Int = when {
        minutes <= 30 -> 10
        minutes <= 60 -> 15
        else -> 30
    }
}
