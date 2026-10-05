package com.libexil.radarforge.tests

import com.libexil.radarforge.core.*
import com.libexil.radarforge.data.DataManager
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * The real DataManager against a fake S3 (archive files made from one sample file with new times):
 * picking a radar loads the newest volume and the previous scans, a higher tilt downloads only the
 * missing part of each file, another product comes from the cache.
 */
private fun ok(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

private object FakeS3 {
    val files = TreeMap<String, ByteArray>()
    val requests: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
    @Volatile var failGets = false
    @Volatile var bytes = 0L
    var installed = false

    fun install() {
        if (installed) return
        installed = true
        URL.setURLStreamHandlerFactory { p -> if (p == "https") object : URLStreamHandler() { override fun openConnection(u: URL) = Conn(u) } else null }
    }

    class Conn(u: URL) : HttpURLConnection(u) {
        private val props = HashMap<String, String>()
        private var body = ByteArray(0)
        private var code = 200
        private var done = false
        override fun setRequestProperty(k: String, v: String) { props[k] = v }
        override fun connect() {
            if (done) return
            done = true
            when (url.host) {
                "unidata-nexrad-level2.s3.amazonaws.com" -> if (url.path.isEmpty() || url.path == "/") {
                    val prefix = URLDecoder.decode(Regex("prefix=([^&]*)").find(url.query ?: "")?.groupValues?.get(1) ?: "", "UTF-8")
                    val xml = StringBuilder("<ListBucketResult><IsTruncated>false</IsTruncated>")
                    for ((k, b) in files) if (k.startsWith(prefix)) xml.append("<Contents><Key>$k</Key><Size>${b.size}</Size></Contents>")
                    body = xml.append("</ListBucketResult>").toString().toByteArray()
                    requests.add("LIST $prefix")
                } else {
                    val key = url.path.removePrefix("/")
                    val data = files[key]
                    if (failGets || data == null) { code = if (data == null) 404 else 503; requests.add("FAIL $key"); return }
                    val r = props["Range"]
                    body = if (r != null) { code = 206; data.copyOfRange(r.removePrefix("bytes=").removeSuffix("-").toInt(), data.size) } else data
                    requests.add("GET $key ${r ?: ""}".trim())
                }
                "unidata-nexrad-level2-chunks.s3.amazonaws.com" -> body = "<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>".toByteArray()
                else -> code = 404
            }
        }
        override fun getResponseCode(): Int { connect(); return code }
        override fun getInputStream(): InputStream = object : FilterInputStream(ByteArrayInputStream(body)) {
            override fun read(b: ByteArray, o: Int, l: Int): Int { val n = super.read(b, o, l); if (n > 0) bytes += n; return n }
        }
        override fun getContentEncoding(): String? = null
        override fun disconnect() {}
        override fun usingProxy() = false
    }
}

fun registerData() {
    Extra.all.add("data manager: previous scans load (fake S3)" to ::previousScansLoad)
}

private fun previousScansLoad() {
    val sample = File(dataDir, "Level2_KDDC_20200823_204121.ar2v")
    if (!sample.exists()) { println("       (sample file not found - skipped)"); return }
    FakeS3.install()
    val src = sample.readBytes()
    val now = System.currentTimeMillis()
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val day = SimpleDateFormat("yyyy/MM/dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    for (k in 0 until 14) {
        val t = now - 8 * 60_000L - (13 - k) * 5 * 60_000L
        val b = src.copyOf()
        fun put(off: Int, v: Int) { for (i in 0..3) b[off + i] = (v shr (24 - 8 * i)).toByte() }
        put(12, (t / 86_400_000L + 1).toInt()); put(16, (t % 86_400_000L).toInt())
        FakeS3.files["${day.format(Date(t))}/KDDC/KDDC${name.format(Date(t))}_V06"] = b
    }
    val cache = File(System.getProperty("java.io.tmpdir"), "rf-test-cache-${System.nanoTime()}").apply { mkdirs() }
    val dm = DataManager(android.content.Context(cache)) { emptyList() }
    dm.wantAlerts = false
    dm.listener = object : DataManager.Listener {
        override fun onVolumes() {}
        override fun onFrames() {}
        override fun onAlerts() {}
        override fun onStatus(text: String, busy: Boolean, error: Boolean) {}
        override fun onFeed(name: String) {}
    }
    fun waitLoop(maxMs: Long = 60_000) {
        val t = System.currentTimeMillis()
        Thread.sleep(600)
        while (System.currentTimeMillis() - t < maxMs && (dm.complete == null || dm.loopTotal != 0)) Thread.sleep(200)
        Thread.sleep(200)
    }
    fun loop(tilt: Float, moments: Set<String>): List<LoopFrame> {
        val sel = LoopSupport.select(dm.frames, tilt, moments, (dm.complete?.startMs ?: Long.MAX_VALUE) - 30_000, 10)
        for (fr in sel) {
            val ts = Tilts.build(fr.volume)
            val i = Tilts.closest(ts, tilt)
            ok(i >= 0 && moments.all { m -> (ts[i].sweep(m)?.nRays ?: 0) >= 40 }, "frame ${Time.iso(fr.timeMs)} has no $moments at $tilt°")
        }
        return sel
    }
    try {
        // what the app does: onResume -> start(); picking a radar -> setSite + requestLoop
        dm.start()
        dm.setSite("KDDC")
        dm.requestLoop(10, 0.5f, setOf("REF"))
        waitLoop()
        ok(dm.complete != null, "newest volume loaded (log: ${com.libexil.radarforge.RfLog.lines.takeLast(5)})")
        val l2 = File(cache, "l2")
        ok(l2.listFiles()!!.count { it.name.endsWith("_V06") } == 1, "newest volume cached: ${l2.list()?.toList()}")
        val l1 = loop(0.5f, setOf("REF"))
        ok(l1.size == 10, "10 previous scans, got ${l1.size} (log: ${com.libexil.radarforge.RfLog.lines.takeLast(5)})")
        ok(l2.listFiles()!!.count { it.name.endsWith(".part") } == 10, "starts of the older files cached: ${l2.list()?.toList()}")
        ok(FakeS3.requests.count { it.startsWith("GET") } == 11, "one download per file: ${FakeS3.requests}")
        val mb1 = FakeS3.bytes
        // a higher tilt: only the rest of each file is fetched
        FakeS3.requests.clear()
        dm.requestLoop(10, 1.5f, setOf("REF", "VEL"))
        waitLoop()
        ok(loop(1.5f, setOf("REF", "VEL")).size == 10, "10 scans at 1.5°")
        val ranged = FakeS3.requests.filter { it.startsWith("GET") }
        ok(ranged.size == 10 && ranged.all { it.contains("bytes=") }, "ranged downloads: $ranged")
        // another product at the first tilt: from the cache, nothing downloaded
        FakeS3.requests.clear()
        val before = FakeS3.bytes
        dm.requestLoop(10, 0.5f, setOf("RHO"))
        waitLoop()
        ok(loop(0.5f, setOf("RHO")).size == 10, "10 scans of CC")
        ok(FakeS3.requests.none { it.startsWith("GET") } && FakeS3.bytes - before < 10_000, "CC from the cache: ${FakeS3.requests}")
        println(String.format("       newest + 10 scans: %.1f MB; up to 1.5°: +%.1f MB; CC: cached", mb1 / 1e6, (before - mb1) / 1e6))
    } finally {
        dm.shutdown()
        cache.deleteRecursively()
    }
}
