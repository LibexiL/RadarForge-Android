package com.libexil.radarforge.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.libexil.radarforge.RfLog
import com.libexil.radarforge.core.Alert
import com.libexil.radarforge.core.Alerts
import com.libexil.radarforge.core.Chaser
import com.libexil.radarforge.core.Chasers
import com.libexil.radarforge.core.MesoDiscussion
import com.libexil.radarforge.core.OutlookArea
import com.libexil.radarforge.core.Reports
import com.libexil.radarforge.core.Spc
import com.libexil.radarforge.core.StormReport
import com.libexil.radarforge.core.Level2
import com.libexil.radarforge.core.LoopFrame
import com.libexil.radarforge.core.LoopSupport
import com.libexil.radarforge.core.Net
import com.libexil.radarforge.core.S3
import com.libexil.radarforge.core.SweepBuilder
import com.libexil.radarforge.core.Tilts
import com.libexil.radarforge.core.Volume
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads and decodes radar data in the background:
 *  - the newest complete volume from the archive bucket (shown right away)
 *  - the volume being scanned now, chunk by chunk (new tilts appear as they arrive)
 *  - the previous scans for the loop (10 by default, loaded straight after a radar switch),
 *    reading each file only as far as the tilt on screen needs
 *  - NWS warnings, storm reports, storm chaser positions and SPC products ("feeds")
 * Results are published to [listener] on the main thread.
 */
class DataManager(ctx: Context, @Volatile var countyRings: (Int) -> List<FloatArray>) {
    interface Listener {
        fun onVolumes()
        fun onFrames()
        fun onAlerts()
        fun onStatus(text: String, busy: Boolean, error: Boolean)
        /** New data for a feed: "reports", "chasers", "outlook" or "mcd". */
        fun onFeed(name: String)
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private fun daemon(name: String): ThreadFactory {
        val n = AtomicInteger()
        return ThreadFactory { r -> Thread(r, "$name-${n.incrementAndGet()}").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    }
    private val pool = Executors.newFixedThreadPool(4, daemon("rf-net"))
    private val sched = Executors.newScheduledThreadPool(2, daemon("rf-poll"))
    // storm reports, chasers and SPC have their own threads so a slow site never holds up the radar
    private val feedPool = Executors.newFixedThreadPool(2, daemon("rf-feed"))
    private val cacheDir = File(ctx.cacheDir, "l2").apply { mkdirs() }

    @Volatile var site = ""
        private set
    private val gen = AtomicInteger()

    /** The newest complete volume, and the one being scanned now (null when none newer). */
    @Volatile var complete: Volume? = null
        private set
    @Volatile var live: Volume? = null
        private set
    /** Previous scans for the loop (any tilt; the activity picks the ones for the tilt on screen). */
    @Volatile var frames: List<LoopFrame> = emptyList()
        private set
    /** Loop loading progress: scans ready / wanted (0 / 0 when idle). */
    @Volatile var loopReady = 0
        private set
    @Volatile var loopTotal = 0
        private set
    /** Set when loading the previous scans failed (shown in the loop bar). */
    @Volatile var loopError: String? = null
        private set
    @Volatile var alerts: List<Alert> = emptyList()
        private set
    @Volatile var alertsTime = 0L
        private set
    @Volatile var lastDataMs = 0L
        private set

    private var liveTask: ScheduledFuture<*>? = null
    private var alertTask: ScheduledFuture<*>? = null
    @Volatile private var tracker: ChunkTracker? = null
    @Volatile private var running = false
    @Volatile var wantAlerts = true
    @Volatile private var alertsFilterRejected = false

    private fun post(f: () -> Unit) = main.post(f)
    private fun status(text: String, busy: Boolean = false, error: Boolean = false) =
        post { listener?.onStatus(text, busy, error) }

    // ------------------------------------------------------------------ control
    fun setSite(newSite: String) {
        if (newSite == site && running) return
        val g: Int
        // under the lock, so a job of the old radar that's mid-update can't put its data back afterwards
        synchronized(this) {
            site = newSite
            g = gen.incrementAndGet()
            complete = null
            live = null
            frames = emptyList()
        }
        loopReady = 0; loopTotal = 0; loopError = null
        retryDelayMs = 20_000L
        main.removeCallbacks(loopRetry)
        // the loop request stays: its frames load for the new radar once its newest volume is in
        tracker = ChunkTracker(newSite, pool)
        post { listener?.onVolumes(); listener?.onFrames() }
        status("Loading $newSite…", busy = true)
        newestPending = true
        pool.execute { loadNewestArchive(newSite, g) }
        if (running) startLive()
    }

    /** Starts the live feed for the current radar again (Reload). Keeps what's loaded. */
    fun reload() {
        if (site.isEmpty()) return
        tracker = ChunkTracker(site, pool)
        if (running) { startLive(); startAlerts(); startFeeds() }
        if (complete == null && !newestPending) {
            val g = gen.get(); val s = site
            newestPending = true
            pool.execute { loadNewestArchive(s, g) }
        }
        if (loopWanted != null) runLoopJob()
    }

    fun start() {
        if (running) return
        running = true
        startLive()
        startAlerts()
        startFeeds()
        if (loopWanted != null && !newestPending) runLoopJob()
        // e.g. the app was opened offline: try the newest complete volume again
        if (site.isNotEmpty() && complete == null && !newestPending) {
            val g = gen.get()
            val s = site
            newestPending = true
            pool.execute { loadNewestArchive(s, g) }
        }
    }

    fun stop() {
        running = false
        main.removeCallbacks(loopRetry)              // the loop job itself stops at the next scan
        liveTask?.cancel(false); liveTask = null
        alertTask?.cancel(false); alertTask = null
        feedTask?.cancel(false); feedTask = null
    }

    fun refreshNow() {
        if (!running) return
        startLive()
        startAlerts()
        startFeeds()
    }

    private fun current(g: Int) = g == gen.get()

    // ------------------------------------------------------------------ newest archive volume
    @Volatile private var newestPending = false

    private fun loadNewestArchive(s: String, g: Int) {
        try {
            val files = S3.latestLevel2(s, 1)
            if (!current(g)) return
            val f = files.lastOrNull()
            if (f == null) {
                status("No recent data for $s – the radar may be down", error = true)
                return
            }
            val v = readCachedOrDownload(f, s, g, ChunkTracker.SKIP, null, null) ?: return
            if (!current(g)) return
            v.complete = true
            var newer = false
            synchronized(this) {
                if (!current(g)) return
                if ((complete?.startMs ?: 0) < v.startMs) { complete = v; newer = true }
            }
            lastDataMs = System.currentTimeMillis()
            RfLog.i("$s archive volume ${f.name}: ${v.sweeps.size} sweeps")
            if (newer) addCompleteToLoop(v, g)
            post { listener?.onVolumes() }
            status("")
        } catch (e: Exception) {
            RfLog.e("$s archive load failed", e)
            if (current(g)) {
                status(friendly(e), error = true)
                // try again shortly (the live feed may fill in meanwhile)
                main.postDelayed({
                    if (current(g) && running && complete == null && !newestPending) {
                        newestPending = true
                        pool.execute { loadNewestArchive(s, g) }
                    }
                }, 20_000)
            }
        } finally {
            if (current(g)) {
                newestPending = false
                // the previous scans wait for the newest volume, so it shows first and isn't downloaded twice
                if (loopWanted != null) runLoopJob()
            }
        }
    }

    /**
     * Reads an archive file from the disk cache or the bucket. With [enough] set, only
     * the start of the file is downloaded (the cache keeps that part, marked ".part").
     */
    private fun readCachedOrDownload(f: S3.L2File, s: String, g: Int, skip: Set<String>, keep: Set<String>?,
                                     enough: ((SweepBuilder) -> Boolean)?): Volume? {
        val full = File(cacheDir, f.name)
        if (full.exists() && full.length() > 0) {
            full.setLastModified(System.currentTimeMillis())
            return full.inputStream().buffered().use { Level2.readStreaming(it, s, null, skip, keep, enough ?: { false }) }
        }
        val part = File(cacheDir, f.name + ".part")
        var cachedStart: ByteArray? = null
        if (part.exists() && part.length() > 0) {
            if (f.size > 0 && part.length() >= f.size) {
                // the "start" is the whole file after all
                part.renameTo(full)
                return full.inputStream().buffered().use { Level2.readStreaming(it, s, null, skip, keep, enough ?: { false }) }
            }
            if (enough != null) {
                // decode the cached start; if it holds enough, no download needed
                var ok = false
                val v = part.inputStream().buffered().use { input ->
                    Level2.readStreaming(input, s, null, skip, keep) { b -> enough(b).also { ok = it } }
                }
                if (ok) { part.setLastModified(System.currentTimeMillis()); return v }
            }
            // not enough: download only the rest (a higher tilt doesn't fetch the lower ones again)
            cachedStart = try { part.readBytes() } catch (e: IOException) { null }
        }
        val url = S3.objectUrl(S3.L2, f.key)
        val c = Net.open(url, 30_000, range = cachedStart?.size?.toLong() ?: -1)
        try {
            val copy = ByteArrayOutputStream(if (enough == null) f.size.toInt().coerceAtLeast(1 shl 16) else 4 shl 20)
            val stop: (SweepBuilder) -> Boolean = { b -> !current(g) || (enough?.invoke(b) ?: false) }
            // 206: the server sent only the rest; anything else is the whole file again
            val resumed = cachedStart != null && c.responseCode == 206
            if (cachedStart != null && !resumed) RfLog.w("${f.name}: range not honoured, downloading it all")
            val body = Net.stream(c)
            val input = if (resumed) java.io.SequenceInputStream(ByteArrayInputStream(cachedStart), body) else body
            val v = Level2.readStreaming(input.buffered(1 shl 16), s, copy, skip, keep, stop)
            if (!current(g)) return null
            val bytes = copy.toByteArray()
            try {
                // write to a unique temp file first: two jobs may fetch the same file at once
                // (the prefix needs at least 3 characters, or Android refuses it)
                if (v.complete || bytes.size.toLong() >= f.size) {
                    File.createTempFile("rfdl", ".tmp", cacheDir).apply { writeBytes(bytes); if (!renameTo(full)) delete() }
                    part.delete()
                } else if (bytes.size > part.length()) {
                    File.createTempFile("rfdl", ".tmp", cacheDir).apply { writeBytes(bytes); if (!renameTo(part)) delete() }
                }
            } catch (e: Exception) {
                // a cache problem must never throw away data that was just downloaded
                RfLog.w("cache write failed: ${e.message}")
            }
            trimCache()
            return v
        } finally {
            c.disconnect()
        }
    }

    private fun trimCache(maxBytes: Long = 350L shl 20) {
        val now = System.currentTimeMillis()
        // leftover temp files from an interrupted download
        cacheDir.listFiles()?.filter { it.name.endsWith(".tmp") && now - it.lastModified() > 10 * 60_000L }?.forEach { it.delete() }
        val files = cacheDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    // ------------------------------------------------------------------ live chunks
    private fun startLive() {
        liveTask?.cancel(false)
        val g = gen.get()
        val s = site
        if (s.isEmpty()) return
        var failures = 0
        val t = tracker ?: return
        liveTask = sched.scheduleWithFixedDelay({
            if (!current(g)) return@scheduleWithFixedDelay
            try {
                val v = t.poll { current(g) }
                failures = 0
                if (v != null && current(g)) {
                    var finished: Volume? = null
                    synchronized(this) {
                        if (!current(g)) return@scheduleWithFixedDelay
                        if (v.complete) {
                            if ((complete?.startMs ?: 0) <= v.startMs) { complete = v; finished = v }
                            live = null
                        } else {
                            live = if ((complete?.startMs ?: 0) < v.startMs) v else null
                        }
                    }
                    finished?.let { addCompleteToLoop(it, g) }
                    lastDataMs = System.currentTimeMillis()
                    post { listener?.onVolumes() }
                    status("")
                }
            } catch (e: Throwable) {
                // Throwable: an OutOfMemoryError must not silently end live updates
                failures++
                RfLog.w("$s live poll failed: $e")
                if (failures >= 2 && current(g)) status(if (e is Exception) friendly(e) else "Low on memory – retrying", error = true)
            }
        }, 0, 12, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ loop frames (previous scans)
    private class LoopRequest(val previous: Int, val tilt: Float, val moments: Set<String>) {
        fun same(o: LoopRequest?) = o != null && o.previous == previous && Math.abs(o.tilt - tilt) < 0.05f && o.moments == moments
    }
    @Volatile private var loopWanted: LoopRequest? = null
    private val loopRunning = AtomicBoolean(false)

    /** Keeps the [previous] scans before the newest loaded, at [tilt], holding [moments]. */
    fun requestLoop(previous: Int, tilt: Float, moments: Set<String>) {
        val req = LoopRequest(previous, tilt, moments)
        val old = loopWanted
        if (req.same(old) && frames.count { it.covers(tilt, moments) } >= minOf(previous, 1) && loopError == null) return
        loopWanted = req
        if (loopError != null) { main.removeCallbacks(loopRetry); loopError = null }
        if (!newestPending && running) runLoopJob()
    }

    /** Stops loading previous scans (what's loaded stays until the radar changes). */
    fun stopLoop() {
        loopWanted = null
        main.removeCallbacks(loopRetry)
        if (loopTotal != 0) { loopReady = 0; loopTotal = 0; post { listener?.onFrames() } }
    }

    val loopActive: Boolean get() = loopWanted != null

    /** A volume just finished (live feed or archive): it becomes the newest loop frame at once. */
    private fun addCompleteToLoop(v: Volume, g: Int) {
        val req = loopWanted ?: return
        if (!current(g)) return
        val fr = LoopFrame(v.startMs, req.tilt, req.moments, LoopSupport.trimTo(v, req.tilt, req.moments))
        if (fr.volume.sweeps.isEmpty()) return
        synchronized(this) { if (current(g)) frames = LoopSupport.merge(frames, fr, req.previous + 3) else return }
        post { listener?.onFrames() }
    }

    private fun runLoopJob() {
        if (!loopRunning.compareAndSet(false, true)) return
        val g = gen.get()
        val s = site
        try {
            pool.execute { loopJob(s, g) }
        } catch (e: Exception) {
            loopRunning.set(false)            // the pool is shutting down
        }
    }

    /** The request the last loop job finished (so a request that slipped in at its very end isn't lost). */
    @Volatile private var loopDone: LoopRequest? = null
    @Volatile private var retryDelayMs = 20_000L
    private val loopRetry = Runnable { if (loopWanted != null && running) { loopError = null; runLoopJob() } }

    private fun loopJob(s: String, g: Int) {
        try {
            while (current(g) && running) {
                val req = loopWanted ?: break
                if (req.previous <= 0) { loopDone = req; break }
                // one more than wanted: the newest file is usually the volume shown as "now"
                val files = S3.latestLevel2(s, req.previous + 1)
                if (files.isEmpty()) { loopError = "No earlier scans found for $s"; break }
                val newestFirst = files.reversed()
                if (!current(g)) break
                loopTotal = newestFirst.size
                loopReady = 0
                post { listener?.onFrames() }
                for (f in newestFirst) {
                    if (!current(g) || loopWanted !== req || !running) break
                    val have = frames.any { Math.abs(it.timeMs - f.timeMs) < LoopSupport.SAME_SCAN_MS && it.covers(req.tilt, req.moments) }
                    if (!have) {
                        val c = complete
                        val v: Volume? = if (c != null && Math.abs(c.startMs - f.timeMs) < LoopSupport.SAME_SCAN_MS) {
                            c                                            // already in memory: no download
                        } else try {
                            val enough = LoopSupport.enoughFor(req.tilt, req.moments)
                            readCachedOrDownload(f, s, g, ChunkTracker.SKIP, req.moments) { b -> loopWanted !== req || enough(b) }
                        } catch (e: Exception) {
                            RfLog.w("loop frame ${f.name} failed: ${e.message}")
                            if (current(g)) loopError = friendly(e)
                            null
                        }
                        if (v != null && current(g) && loopWanted === req) {
                            val fr = LoopFrame(v.startMs, req.tilt, req.moments, LoopSupport.trimTo(v, req.tilt, req.moments))
                            if (fr.volume.sweeps.isNotEmpty()) synchronized(this) { if (current(g)) frames = LoopSupport.merge(frames, fr, req.previous + 3) }
                        }
                    }
                    if (!current(g)) break
                    loopReady++
                    post { listener?.onFrames() }
                }
                if (current(g) && loopWanted === req && running) {
                    loopReady = 0; loopTotal = 0
                    loopDone = req
                    if (loopError == null) retryDelayMs = 20_000L
                    post { listener?.onFrames() }
                    break
                }
            }
        } catch (e: Exception) {
            RfLog.e("loop failed", e)
            if (current(g)) { loopError = friendly(e); post { listener?.onFrames() } }
        } finally {
            loopRunning.set(false)
            val want = loopWanted
            when {
                want == null || newestPending || !running -> {}
                // something failed (e.g. offline): try the missing scans again later, backing off
                current(g) && loopError != null -> {
                    loopReady = 0; loopTotal = 0
                    main.removeCallbacks(loopRetry)
                    main.postDelayed(loopRetry, retryDelayMs)
                    retryDelayMs = minOf(retryDelayMs * 2, 5 * 60_000L)
                }
                // an old radar's job, or a new request arrived as this one was finishing
                !current(g) || want !== loopDone -> main.post { runLoopJob() }
            }
        }
    }

    // ------------------------------------------------------------------ warnings
    private fun startAlerts() {
        alertTask?.cancel(false)
        alertTask = sched.scheduleWithFixedDelay({
            if (!wantAlerts) return@scheduleWithFixedDelay
            try {
                val json = try {
                    if (alertsFilterRejected) throw Net.HttpError(400, Alerts.URL)
                    Net.getText(Alerts.URL, 30_000, "application/geo+json")
                } catch (e: Net.HttpError) {
                    if (e.code != 400) throw e
                    // the server didn't accept the list of event types: use the whole feed
                    if (!alertsFilterRejected) RfLog.w("alert filter rejected, using the full feed")
                    alertsFilterRejected = true
                    Net.getText(Alerts.URL_ALL, 45_000, "application/geo+json")
                }
                val a = Alerts.parse(json, countyRings)
                alerts = a
                alertsTime = System.currentTimeMillis()
                RfLog.i("warnings: ${a.size}")
                post { listener?.onAlerts() }
            } catch (e: Throwable) {
                RfLog.w("warnings failed: $e")
            }
        }, 0, 90, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ feeds: reports, chasers, SPC
    @Volatile var reports: List<StormReport> = emptyList()
        private set
    @Volatile var reportsTime = 0L
        private set
    @Volatile var chasers: List<Chaser> = emptyList()
        private set
    @Volatile var chasersTime = 0L
        private set
    @Volatile var outlook: List<OutlookArea> = emptyList()
        private set
    @Volatile var outlookTime = 0L
        private set
    @Volatile var mcds: List<MesoDiscussion> = emptyList()
        private set
    @Volatile var mcdTime = 0L
        private set

    // what to fetch (set from the settings on the main thread)
    @Volatile var wantReports = false
    @Volatile var reportHours = 6
    @Volatile var wantSpotterReports = true
    @Volatile var wantChasers = false
    @Volatile var chasersActiveOnly = false
    @Volatile var wantOutlook = false
    /** SPC outlook day: 1, 2 or 3. */
    @Volatile var outlookDay = 1
    /** The day the outlook in [outlook] is for. */
    @Volatile var outlookShownDay = 1
        private set
    @Volatile var wantMcd = false

    /** A feed fetched every [periodMs] while it's wanted; failures retry sooner. */
    private inner class Feed(val name: String, val periodMs: Long, val wanted: () -> Boolean, val job: () -> Unit) {
        @Volatile var due = 0L
        @Volatile var busy = false
        @Volatile var failures = 0
        /** Fetch again as soon as the current fetch ends (its settings changed meanwhile). */
        @Volatile var again = false

        @Synchronized
        fun tick(force: Boolean) {
            if (!running || !wanted() || busy) return
            if (!force && System.currentTimeMillis() < due) return
            busy = true
            again = false
            try {
                feedPool.execute {
                    try {
                        job()
                        failures = 0
                        due = System.currentTimeMillis() + periodMs
                    } catch (e: Throwable) {
                        failures++
                        RfLog.w("$name failed: $e")
                        due = System.currentTimeMillis() + minOf(periodMs, 20_000L * failures)
                    } finally {
                        if (again) { again = false; due = 0 }       // picked up by the next 5-second tick
                        busy = false
                    }
                }
            } catch (e: Exception) {
                busy = false          // the pool is shutting down
            }
        }
    }

    private val feeds: Map<String, Feed> = linkedMapOf(
        "reports" to Feed("storm reports", 120_000L, { wantReports }) { fetchReports() },
        "chasers" to Feed("storm chasers", 60_000L, { wantChasers }) { fetchChasers() },
        "outlook" to Feed("SPC outlook", 15 * 60_000L, { wantOutlook }) { fetchOutlook() },
        "mcd" to Feed("SPC discussions", 3 * 60_000L, { wantMcd }) { fetchMcd() },
    )
    private var feedTask: ScheduledFuture<*>? = null

    private fun startFeeds() {
        feedTask?.cancel(false)
        feedTask = sched.scheduleWithFixedDelay({ for (f in feeds.values) f.tick(false) }, 0, 5, TimeUnit.SECONDS)
    }

    /** Fetches a feed now (it was just turned on, or its settings changed). */
    fun refreshFeed(name: String) {
        val f = feeds[name] ?: return
        f.due = 0
        if (f.busy) f.again = true else f.tick(true)
    }

    private fun fetchReports() {
        val hours = reportHours
        val withSn = wantSpotterReports
        var error: Throwable? = null
        val lsr = try { Reports.parseLsr(Net.getText(Reports.lsrUrl(hours), 25_000, "application/geo+json", attempts = 1)) }
            catch (e: Exception) { error = e; null }
        val sn = if (!withSn) emptyList() else try { Reports.parseSpotterNetwork(Net.getText(Reports.SN_URL, 15_000, attempts = 1)) }
            catch (e: Exception) { RfLog.w("Spotter Network reports failed: $e"); null }
        if (lsr == null && sn == null) throw error ?: IOException("no reports")
        if (hours != reportHours || withSn != wantSpotterReports) { feeds.getValue("reports").again = true; return }   // settings changed meanwhile
        val now = System.currentTimeMillis()
        val cut = now - hours * 3_600_000L
        // a source that failed this time keeps its previous reports
        val all = (lsr ?: reports.filter { it.origin != "Spotter Network" }) + (sn ?: reports.filter { it.origin == "Spotter Network" })
        reports = all.filter { it.timeMs == 0L || it.timeMs >= cut }.sortedByDescending { it.timeMs }
        reportsTime = now
        RfLog.i("storm reports: ${reports.size} (${hours} h)")
        post { listener?.onFeed("reports") }
    }

    private fun fetchChasers() {
        val active = chasersActiveOnly
        val list = Chasers.parse(Net.getText(if (active) Chasers.URL_ACTIVE else Chasers.URL_ALL, 20_000, attempts = 1))
        if (active != chasersActiveOnly) { feeds.getValue("chasers").again = true; return }
        chasers = list
        chasersTime = System.currentTimeMillis()
        RfLog.i("storm chasers: ${list.size}")
        post { listener?.onFeed("chasers") }
    }

    private fun fetchOutlook() {
        var last: Exception? = null
        var got: List<OutlookArea>? = null
        val day = outlookDay
        for (url in Spc.outlookUrlsFor(System.currentTimeMillis(), day)) {
            try {
                val areas = Spc.parseOutlook(Net.getText(url, 20_000, "application/geo+json", attempts = 1))
                if (areas.isNotEmpty()) { got = areas; break }
                if (got == null) got = areas          // not issued yet (or no thunder anywhere): try the one before
            } catch (e: Exception) {
                last = e
            }
        }
        val areas = got ?: throw last ?: IOException("no outlook")
        if (day != outlookDay) { feeds.getValue("outlook").again = true; return }      // day changed meanwhile
        outlook = areas
        outlookShownDay = day
        outlookTime = System.currentTimeMillis()
        RfLog.i("SPC day $day outlook: ${areas.size} areas")
        post { listener?.onFeed("outlook") }
    }

    private fun fetchMcd() {
        val now = System.currentTimeMillis()
        val list = Spc.parseMcd(Net.getText(Spc.MCD_URL, 20_000, "application/geo+json", attempts = 1)).filter { it.expireMs == 0L || it.expireMs > now }
        mcds = list
        mcdTime = now
        RfLog.i("SPC discussions: ${list.size}")
        post { listener?.onFeed("mcd") }
    }

    /** Stops every thread (the activity is going away). */
    fun shutdown() {
        stop()
        gen.incrementAndGet()
        loopWanted = null
        pool.shutdownNow()
        sched.shutdownNow()
        feedPool.shutdownNow()
    }

    private fun friendly(e: Exception): String = when {
        e is java.net.UnknownHostException -> "No internet connection – retrying"
        e is java.net.SocketTimeoutException -> "The radar server is slow to answer – retrying"
        e is Net.HttpError && e.code == 404 -> "Data not found for $site"
        e is Net.HttpError -> "Server error ${e.code} – retrying"
        else -> "Couldn't load data: ${e.message ?: e.javaClass.simpleName}"
    }
}
