package com.libexil.radarforge.core

/** One older scan kept for the loop: a volume trimmed to one tilt and the moments on screen. */
class LoopFrame(val timeMs: Long, val tilt: Float, val moments: Set<String>, val volume: Volume) {
    fun covers(tilt: Float, needed: Set<String>) = Math.abs(this.tilt - tilt) < 0.05f && this.moments.containsAll(needed)
}

/** Helpers for loop frames: stop reading a file early, keep only what a frame needs, pick the frames to show. */
object LoopSupport {
    /** Two scans less than this apart are the same volume (file names and decoded times differ by seconds). */
    const val SAME_SCAN_MS = 90_000L

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

    /**
     * A copy of [v] holding only the tilt nearest [tilt]. With [moments], only what a loop frame shows is
     * kept: for each moment the sweep [Tilt.sweep] would use (the newest scan's), holding just that moment –
     * a SAILS volume scans the lowest tilt up to four times, and frames must not keep every pass.
     */
    fun trimTo(v: Volume, tilt: Float, moments: Set<String>? = null): Volume {
        val tilts = Tilts.build(v)
        val i = Tilts.closest(tilts, tilt)
        if (i < 0) return v
        val t = tilts[i]
        val sweeps: List<Sweep> = if (moments == null) {
            val keep = t.scans.flatMap { it.sweeps.values }.toSet()
            v.sweeps.filter { it in keep }
        } else {
            val picks = java.util.IdentityHashMap<Sweep, MutableSet<String>>()
            for (m in moments) { val sw = t.sweep(m) ?: continue; picks.getOrPut(sw) { HashSet() }.add(m) }
            v.sweeps.filter { picks.containsKey(it) }.map { sw ->
                val want = picks.getValue(sw)
                Sweep(sw.index, sw.elevNum, sw.elevation, sw.azimuths, sw.azRes, sw.nyquist, sw.unambRange, sw.startMs,
                    sw.moments.filterKeys { it in want }, sw.complete)
            }
        }
        return Volume(v.site, v.startMs, v.lat, v.lon, v.heightM, v.vcp, sweeps, false)
    }

    /**
     * The older scans to loop through, oldest first: frames for [tilt] holding every moment in
     * [needed], older than [cutMs] (the scan shown as "now"), at most [count] of the newest.
     */
    fun select(frames: List<LoopFrame>, tilt: Float, needed: Set<String>, cutMs: Long, count: Int): List<LoopFrame> {
        if (count <= 0) return emptyList()
        val ok = frames.filter { it.timeMs < cutMs && it.covers(tilt, needed) }.sortedBy { it.timeMs }
        // one frame per scan (a scan can arrive twice: from the live feed and from the archive)
        val out = ArrayList<LoopFrame>()
        for (f in ok) {
            val last = out.lastOrNull()
            if (last != null && f.timeMs - last.timeMs < SAME_SCAN_MS) out[out.size - 1] = f else out.add(f)
        }
        return if (out.size > count) out.subList(out.size - count, out.size).toList() else out
    }

    /**
     * Adds [fresh] to [frames]: a fresh frame replaces any older copy of the same scan with the same
     * tilt; frames for other tilts or products are kept until they're pushed out. At most [max] are kept,
     * preferring frames that match [fresh]'s tilt and the newest.
     */
    fun merge(frames: List<LoopFrame>, fresh: LoopFrame, max: Int): List<LoopFrame> {
        val rest = frames.filter { !(Math.abs(it.timeMs - fresh.timeMs) < SAME_SCAN_MS && Math.abs(it.tilt - fresh.tilt) < 0.05f) }
        val all = rest + fresh
        if (all.size <= max) return all.sortedBy { it.timeMs }
        val same = all.filter { Math.abs(it.tilt - fresh.tilt) < 0.05f && it.moments.containsAll(fresh.moments) }.sortedByDescending { it.timeMs }
        val other = all.filter { it !in same }.sortedByDescending { it.timeMs }
        return (same + other).take(max).sortedBy { it.timeMs }
    }
}
