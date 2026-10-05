package com.libexil.radarforge.data

import android.os.Handler
import android.os.Looper
import com.libexil.radarforge.RfLog
import com.libexil.radarforge.core.Field
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fields worked out from the radar's own (dealiased velocity, Σ trails) on a background thread.
 * [get] answers from the cache or queues the work and returns null; [onReady] runs on the main
 * thread when a result arrives, so the screen can be redrawn with it.
 */
class DerivedFields(@Volatile var onReady: () -> Unit) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "rf-derive").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val gen = AtomicInteger()
    private var bytes = 0L
    /** Memory the cache may use. */
    var maxBytes = 96L shl 20

    private val cache = object : LinkedHashMap<String, Field>(64, 0.75f, true) {}
    private val pending = HashMap<String, Int>()      // key -> job token; main thread only
    private val failed = HashSet<String>()            // main thread only
    /** How often each key was made this generation: an evicted result is made again at most once. */
    private val made = HashMap<String, Int>()         // main thread only
    private var token = 0

    private fun size(f: Field) = (f.codes8?.size?.toLong() ?: (f.codes16!!.size * 2L)) + f.nRays * 12L

    private fun cached(key: String): Field? = synchronized(cache) { cache[key] }

    private fun store(key: String, f: Field) = synchronized(cache) {
        cache.put(key, f)?.let { bytes -= size(it) }
        bytes += size(f)
        val it = cache.entries.iterator()
        while (bytes > maxBytes && cache.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            bytes -= size(e.value)
            it.remove()
        }
    }

    /** The cached result for [key], or null (nothing is queued). */
    fun peek(key: String): Field? = cached(key)

    /**
     * The cached result for [key], or null after queueing [job] (once) to make it. Main thread.
     * [force]: the panel is showing this one, so make it again even if it was made and pushed out before.
     */
    fun get(key: String, force: Boolean = false, job: () -> Field?): Field? {
        cached(key)?.let { return it }
        if (key in pending || key in failed) return null
        // made before and pushed out of the cache since: make it once more at most (no endless make / evict cycle)
        if (!force && (made[key] ?: 0) >= 2) return null
        if (made.size > 2000) made.clear()
        val t = ++token
        pending[key] = t
        val g = gen.get()
        try {
            exec.execute {
                val f = if (g != gen.get()) null else try { cached(key) ?: job() } catch (t2: Throwable) {
                    RfLog.w("derived field failed: $t2")
                    null
                }
                if (f != null) store(key, f)
                main.post {
                    if (pending[key] == t) pending.remove(key)
                    if (f != null) { made[key] = (made[key] ?: 0) + 1; onReady() }
                    else if (g == gen.get()) failed.add(key)
                }
            }
        } catch (e: Exception) {
            pending.remove(key)          // shutting down
        }
        return null
    }

    /** On the background thread (inside a job): the cached result, or [job]'s, stored for next time. */
    fun now(key: String, job: () -> Field?): Field? {
        cached(key)?.let { return it }
        val f = job() ?: return null
        store(key, f)
        return f
    }

    /** Whether [n] more bytes would fit without pushing anything out. */
    fun roomFor(n: Long): Boolean = synchronized(cache) { bytes + n <= maxBytes }

    /** True while work is queued or running. */
    val busy: Boolean get() = pending.isNotEmpty()

    /** Drops queued work (the radar, tilt or settings changed); results already made stay cached. */
    fun cancelQueued() {
        gen.incrementAndGet()
        pending.clear()
        failed.clear()
        made.clear()
    }

    fun clear() {
        cancelQueued()
        synchronized(cache) { cache.clear(); bytes = 0 }
    }

    fun shutdown() {
        cancelQueued()
        exec.shutdownNow()
    }
}
