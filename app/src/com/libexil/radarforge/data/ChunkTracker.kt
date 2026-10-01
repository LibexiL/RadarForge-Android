package com.libexil.radarforge.data

import com.libexil.radarforge.RfLog
import com.libexil.radarforge.core.Net
import com.libexil.radarforge.core.S3
import com.libexil.radarforge.core.SweepBuilder
import com.libexil.radarforge.core.Volume
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * Follows the newest volume of one radar chunk by chunk (unidata-nexrad-level2-chunks),
 * so new tilts appear while the radar is still scanning.
 */
class ChunkTracker(val site: String, private val pool: ExecutorService) {
    companion object {
        val SKIP = setOf("CFP")
    }
    private var volume: Int? = null
    private var stamp: String? = null
    private var prevStamp = ""
    private var builder = SweepBuilder(SKIP)
    private var nextChunk = 1
    private var lastProgress = System.currentTimeMillis()
    private var complete = false

    private fun start(vol: Int) {
        volume = vol
        stamp = null
        builder = SweepBuilder(SKIP)
        nextChunk = 1
        complete = false
        lastProgress = System.currentTimeMillis()
    }

    /** Returns the updated live volume when new chunks arrived, else null. One poll at a time. */
    @Synchronized
    fun poll(isCurrent: () -> Boolean): Volume? {
        if (volume == null || System.currentTimeMillis() - lastProgress > 15 * 60_000L) {
            val n = S3.findLatestVolume(site) ?: return null
            RfLog.i("$site live volume number $n")
            start(n)
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
        // the contiguous run of new chunks, downloaded a few at a time but added in order
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
        var i = 0
        while (i < todo.size && isCurrent()) {
            val batch = todo.subList(i, minOf(todo.size, i + 4))
            val futures: List<Future<ByteArray>> = batch.map { c -> pool.submit<ByteArray> { Net.getBytes(S3.objectUrl(S3.CHUNKS, c.key), 30_000) } }
            for ((k, f) in futures.withIndex()) {
                val data = try { f.get() } catch (e: Exception) {
                    RfLog.w("$site chunk ${batch[k].number} failed: ${e.cause?.message ?: e.message}")
                    null
                } ?: break
                builder.addBytes(data)
                nextChunk++
                added = true
                if (batch[k].kind == 'E') complete = true
            }
            if (nextChunk <= batch.last().number) break   // a download failed; retry next poll
            i += batch.size
        }
        if (!added) return null
        lastProgress = System.currentTimeMillis()
        val v = builder.build(site)
        v.complete = complete
        if (complete) {
            prevStamp = stamp ?: ""
            start(S3.nextVolume(vol))
        }
        return v
    }
}
