package com.libexil.radarforge.data

import com.libexil.radarforge.RfLog
import com.libexil.radarforge.core.Net
import com.libexil.radarforge.core.S3
import com.libexil.radarforge.core.SweepBuilder
import com.libexil.radarforge.core.Volume
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * Follows the newest volume of one radar chunk by chunk (unidata-nexrad-level2-chunks),
 * so new tilts appear while the radar is still scanning.
 */
class ChunkTracker(val site: String, private val pool: ExecutorService) {
    companion object {
        val SKIP = setOf("CFP")
        /** The last volume number seen per radar (this session): the next search starts there. */
        private val lastVolume = ConcurrentHashMap<String, Int>()

        /** Waiting this long for the next volume to appear means the numbering moved on: look again. */
        const val WAIT_NEW_MS = 4 * 60_000L
        /** No new chunk of a volume in progress for this long: look again. */
        const val WAIT_CHUNK_MS = 8 * 60_000L

        /**
         * Downloads [chunks] (one volume, in order) a few at a time and decodes them; null when [isCurrent]
         * turns false or a chunk can't be had.
         */
        fun downloadVolume(site: String, chunks: List<S3.Chunk>, pool: ExecutorService, isCurrent: () -> Boolean,
                           keep: Set<String>? = null, enough: ((SweepBuilder) -> Boolean)? = null): Volume? {
            if (chunks.isEmpty()) return null
            val b = SweepBuilder(SKIP, keep)
            val window = 6                                   // ahead of the one being decoded (stops early cheaply)
            val futures = arrayOfNulls<Future<ByteArray>>(chunks.size)
            fun submit(i: Int) {
                if (i < chunks.size && futures[i] == null)
                    futures[i] = pool.submit<ByteArray> { if (isCurrent()) Net.getBytes(S3.objectUrl(S3.CHUNKS, chunks[i].key), 30_000) else ByteArray(0) }
            }
            var stopped = false
            try {
                for (i in 0 until minOf(window, chunks.size)) submit(i)
                for (i in chunks.indices) {
                    if (!isCurrent()) return null
                    submit(i + window)
                    b.addBytes(futures[i]!!.get())
                    if (enough != null && enough(b)) { stopped = true; break }
                }
            } catch (e: Exception) {
                RfLog.w("$site chunk volume failed: ${e.cause?.message ?: e.message}")
                return null
            } finally {
                futures.forEach { it?.cancel(false) }
            }
            return b.build(site).also { it.complete = !stopped && chunks.last().kind == 'E' }
        }
    }
    private var volume: Int? = null
    private var stamp: String? = null
    private var prevStamp = ""
    private var builder = SweepBuilder(SKIP)
    private var nextChunk = 1
    private var lastProgress = System.currentTimeMillis()
    private var complete = false
    private var lastComplete = -1

    /** The volume number being followed (null until found). */
    @Volatile var currentVolume: Int? = null
        private set

    private fun start(vol: Int) {
        volume = vol
        currentVolume = vol
        stamp = null
        builder = SweepBuilder(SKIP)
        nextChunk = 1
        complete = false
        lastProgress = System.currentTimeMillis()
    }

    private fun find(): Int? {
        val hint = volume ?: lastVolume[site]
        val n = (if (hint != null) S3.findLatestVolumeNear(site, hint) else null) ?: S3.findLatestVolume(site)
        if (n != null) lastVolume[site] = n
        return n
    }

    /** Returns the updated live volume when new chunks arrived, else null. One poll at a time. */
    @Synchronized
    fun poll(isCurrent: () -> Boolean): Volume? {
        val waited = System.currentTimeMillis() - lastProgress
        // the next volume never showed up (a skipped number, a restart) or a volume stalled: look again
        val stale = waited > (if (stamp == null) WAIT_NEW_MS else WAIT_CHUNK_MS)
        if (volume == null || stale) {
            var n = find() ?: return null
            if (n == lastComplete) n = S3.nextVolume(n)                // finished already: wait for the one after
            if (n != volume || volume == null) {
                RfLog.i("$site live volume number $n")
                start(n)
            } else {
                lastProgress = System.currentTimeMillis()          // still the newest: keep waiting
            }
        }
        val vol = volume ?: return null
        var chunks = S3.listChunks(site, vol)
        if (chunks.isNotEmpty() && chunks[0].stamp <= prevStamp) chunks = emptyList()   // left over from 999 volumes ago
        if (chunks.isEmpty()) return null
        if (stamp == null) {
            stamp = chunks[0].stamp
        } else if (chunks[0].stamp != stamp) {
            start(vol)
            stamp = chunks[0].stamp
        }
        // the contiguous run of new chunks, downloaded side by side but added in order
        val todo = ArrayList<S3.Chunk>()
        var expect = nextChunk
        for (c in chunks) {
            if (c.number < expect) continue
            if (c.number != expect) break
            todo.add(c)
            expect++
        }
        if (todo.isEmpty()) return null
        var added = false
        val futures: List<Future<ByteArray>> = todo.map { c -> pool.submit<ByteArray> { Net.getBytes(S3.objectUrl(S3.CHUNKS, c.key), 30_000) } }
        try {
            for ((k, f) in futures.withIndex()) {
                if (!isCurrent()) break
                val data = try { f.get() } catch (e: Exception) {
                    RfLog.w("$site chunk ${todo[k].number} failed: ${e.cause?.message ?: e.message}")
                    null
                } ?: break                                             // the rest waits for the next poll
                builder.addBytes(data)
                nextChunk++
                added = true
                if (todo[k].kind == 'E') complete = true
            }
        } finally {
            futures.forEach { it.cancel(false) }
        }
        if (!added) return null
        lastProgress = System.currentTimeMillis()
        lastVolume[site] = vol
        val v = builder.build(site)
        v.complete = complete
        if (complete) {
            prevStamp = stamp ?: ""
            lastComplete = vol
            start(S3.nextVolume(vol))
        }
        return v
    }
}
