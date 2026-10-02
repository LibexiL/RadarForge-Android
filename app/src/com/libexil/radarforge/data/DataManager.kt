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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads and decodes radar data in the background:
 *  - the newest complete volume from the archive bucket (shown right away)
 *  - the volume being scanned now, chunk by chunk (new tilts appear as they arrive)
 *  - older volumes for the loop, reading each file only as far as the tilt needs
 *  - NWS warnings, storm reports, storm chaser positions and SPC products ("feeds")
 * Results are published to [listener] on the main thread.
 */
class DataManager(ctx: Context, private val countyRings: (Int) -> List<FloatArray>) {
    interface Listener {
        fun onVolumes()
        fun onFrames()
        fun onAlerts()
        fun onStatus(text: String, busy: Boolean, error: Boolean)
        /** New data for a feed: "reports", "chasers", "outlook" or "mcd". */
        fun onFeed(name: String)
    }

    class Frame(val key: String, val timeMs: Long, val volume: Volume)

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
    @Volatile var frames: List<Frame> = emptyList()
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
    private var running = false
    @Volatile var wantAlerts = true
    @Volatile private var alertsFilterRejected = false

    private fun post(f: () -> Unit) = main.post(f)
    private fun status(text: String, busy: Boolean = false, error: Boolean = false) =
        post { listener?.onStatus(text, busy, error) }

    // ------------------------------------------------------------------ control
    fun setSite(newSite: String) {
        if (newSite == site && running) return
        site = newSite
        val g = gen.incrementAndGet()
        complete = null
        live = null
        frames = emptyList()
        loopWanted = null
        tracker = ChunkTracker(newSite, pool)
        post { listener?.onVolumes(); listener?.onFrames() }
        status("Loading $newSite…", busy = true)
        pool.execute { loadNewestArchive(newSite, g) }
        if (running) startLive()
    }

    fun start() {
        if (running) return
        running = true
        startLive()
        startAlerts()
        startFeeds()
        // e.g. the app was opened offline: try the newest complete volume again
        if (site.isNotEmpty() && complete == null) {
            val g = gen.get()
            val s = site
            pool.execute { loadNewestArchive(s, g) }
        }
    }

    fun stop() {
        running = false
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
            synchronized(this) {
                if (!current(g)) return
                if ((complete?.startMs ?: 0) < v.startMs) complete = v
            }
            lastDataMs = System.currentTimeMillis()
            RfLog.i("$s archive volume ${f.name}: ${v.sweeps.size} sweeps")
            post { listener?.onVolumes() }
            status("")
        } catch (e: Exception) {
            RfLog.e("$s archive load failed", e)
            if (current(g)) status(friendly(e), error = true)
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
        if (enough != null && part.exists() && part.length() > 0) {
            // decode the cached start; if it holds enough, no download needed
            var ok = false
            val v = part.inputStream().buffered().use { input ->
                Level2.readStreaming(input, s, null, skip, keep) { b -> enough(b).also { ok = it } }
            }
            if (ok) { part.setLastModified(System.currentTimeMillis()); return v }
        }
        val url = S3.objectUrl(S3.L2, f.key)
        val c = Net.open(url, 30_000)
        try {
            val copy = ByteArrayOutputStream(if (enough == null) f.size.toInt().coerceAtLeast(1 shl 16) else 4 shl 20)
            val stop: (SweepBuilder) -> Boolean = { b -> !current(g) || (enough?.invoke(b) ?: false) }
            val v = Level2.readStreaming(Net.stream(c).buffered(1 shl 16), s, copy, skip, keep, stop)
            if (!current(g)) return null
            val bytes = copy.toByteArray()
            try {
                // write to a unique temp file first: two jobs may fetch the same file at once
                if (v.complete || bytes.size.toLong() >= f.size) {
                    File.createTempFile("dl", ".tmp", cacheDir).apply { writeBytes(bytes); if (!renameTo(full)) delete() }
                    part.delete()
                } else if (bytes.size > part.length()) {
                    File.createTempFile("dl", ".tmp", cacheDir).apply { writeBytes(bytes); if (!renameTo(part)) delete() }
                }
            } catch (e: IOException) {
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
                    synchronized(this) {
                        if (!current(g)) return@scheduleWithFixedDelay
                        if (v.complete) {
                            if ((complete?.startMs ?: 0) <= v.startMs) complete = v
                            live = null
                        } else {
                            live = if ((complete?.startMs ?: 0) < v.startMs) v else null
                        }
                    }
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

    // ------------------------------------------------------------------ loop frames
    private class LoopRequest(val count: Int, val tilt: Float, val moments: Set<String>)
    @Volatile private var loopWanted: LoopRequest? = null
    @Volatile private var loopRunning = false

    /** Fetches the older volumes for a loop of [count] frames at [tilt], keeping [moments]. */
    fun requestLoop(count: Int, tilt: Float, moments: Set<String>) {
        val req = LoopRequest(count, tilt, moments)
        val old = loopWanted
        if (old != null && old.count == req.count && Math.abs(old.tilt - req.tilt) < 0.05f && old.moments == req.moments &&
            frames.isNotEmpty()) return
        loopWanted = req
        if (old == null || Math.abs(old.tilt - req.tilt) >= 0.05f || !old.moments.containsAll(req.moments)) frames = emptyList()
        runLoopJob()
    }

    fun stopLoop() {
        if (loopWanted != null) status("")
        loopWanted = null
    }

    private fun runLoopJob() {
        if (loopRunning) return
        loopRunning = true
        val g = gen.get()
        val s = site
        pool.execute {
            try {
                while (current(g)) {
                    val req = loopWanted ?: break
                    val files = S3.latestLevel2(s, req.count)
                    val got = ArrayList<Frame>()
                    val have = frames.associateBy { it.key }
                    var n = 0
                    for (f in files.reversed()) {                       // newest first, so the loop fills from now
                        if (!current(g) || loopWanted !== req) break
                        val key = "${f.name}@${req.tilt}@${req.moments.sorted().joinToString(",")}"
                        val existing = have[key]
                        if (existing != null) { got.add(existing); continue }
                        n++
                        status("Loading loop ${n}/${files.size}…", busy = true)
                        val v = try {
                            readCachedOrDownload(f, s, g, ChunkTracker.SKIP, req.moments, LoopSupport.enoughFor(req.tilt, req.moments))
                        } catch (e: Exception) {
                            RfLog.w("loop frame ${f.name} failed: ${e.message}")
                            null
                        } ?: continue
                        got.add(Frame(key, v.startMs, LoopSupport.trimTo(v, req.tilt)))
                        frames = got.sortedBy { it.timeMs }
                        post { listener?.onFrames() }
                    }
                    if (current(g) && loopWanted === req) {
                        frames = got.sortedBy { it.timeMs }
                        post { listener?.onFrames() }
                        status("")
                        break
                    }
                }
            } catch (e: Exception) {
                RfLog.e("loop failed", e)
                if (current(g)) status(friendly(e), error = true)
            } finally {
                loopRunning = false
                // this job belonged to an old radar but a loop is wanted for the new one
                if (!current(g) && loopWanted != null) main.post { runLoopJob() }
                // nothing loaded (e.g. offline): try again a bit later while the loop is still wanted
                if (current(g) && loopWanted != null && frames.isEmpty()) main.postDelayed({ if (current(g)) runLoopJob() }, 15_000)
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
        for (url in Spc.outlookUrls(System.currentTimeMillis())) {
            try {
                val areas = Spc.parseOutlook(Net.getText(url, 20_000, "application/geo+json", attempts = 1))
                if (areas.isNotEmpty()) { got = areas; break }
                if (got == null) got = areas          // not issued yet (or no thunder anywhere): try the one before
            } catch (e: Exception) {
                last = e
            }
        }
        val areas = got ?: throw last ?: IOException("no outlook")
        outlook = areas
        outlookTime = System.currentTimeMillis()
        RfLog.i("SPC outlook: ${areas.size} areas")
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
