package com.libexil.radarforge.core

/** Helpers for loop frames: stop reading a file early, keep only what a frame needs. */
object LoopSupport {
    /**
     * A stop test for [Level2.readStreaming]: true once the tilt nearest [tilt] has a
     * complete sweep for every moment in [moments] (or the radar has gone above that
     * angle without it, as some scan patterns skip angles).
     */
    fun enoughFor(tilt: Float, moments: Set<String>): (SweepBuilder) -> Boolean {
        var lastElev = -1
        return { b ->
            if (b.lastElevNum == lastElev) false else {
                lastElev = b.lastElevNum
                val v = b.build()
                val tilts = Tilts.build(v)
                val i = Tilts.closest(tilts, tilt)
                if (i < 0) false else {
                    val t = tilts[i]
                    val near = Math.abs(t.elevation - tilt) < 0.3f
                    val passed = tilts.any { it.elevation > tilt + 0.3f }
                    (near || passed) && moments.all { m -> t.sweep(m)?.complete == true }
                }
            }
        }
    }

    /** A copy of [v] holding only the sweeps of the tilt nearest [tilt]. */
    fun trimTo(v: Volume, tilt: Float): Volume {
        val tilts = Tilts.build(v)
        val i = Tilts.closest(tilts, tilt)
        if (i < 0) return v
        val keep = tilts[i].scans.flatMap { it.sweeps.values }.toSet()
        return Volume(v.site, v.startMs, v.lat, v.lon, v.heightM, v.vcp, v.sweeps.filter { it in keep }, false)
    }
}
