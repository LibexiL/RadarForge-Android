package com.libexil.radarforge.core

import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The NOAA / Unidata NEXRAD buckets on AWS (anonymous access):
 *   unidata-nexrad-level2         YYYY/MM/DD/SITE/SITEYYYYMMDD_HHMMSS_V06
 *   unidata-nexrad-level2-chunks  SITE/<volume 1-999>/YYYYMMDD-HHMMSS-<nnn>-<S|I|E>
 */
object S3 {
    const val L2 = "unidata-nexrad-level2"
    const val CHUNKS = "unidata-nexrad-level2-chunks"

    fun bucketUrl(bucket: String) = "https://$bucket.s3.amazonaws.com"
    fun objectUrl(bucket: String, key: String) = bucketUrl(bucket) + "/" + key

    class Obj(val key: String, val size: Long)
    class Listing(val objects: List<Obj>, val prefixes: List<String>, val truncated: Boolean, val nextToken: String?)

    fun listUrl(bucket: String, prefix: String, delimiter: String? = null, token: String? = null, maxKeys: Int = 1000,
                startAfter: String? = null): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val sb = StringBuilder(bucketUrl(bucket)).append("/?list-type=2&max-keys=").append(maxKeys)
        sb.append("&prefix=").append(enc(prefix))
        if (delimiter != null) sb.append("&delimiter=").append(enc(delimiter))
        if (token != null) sb.append("&continuation-token=").append(enc(token))
        else if (startAfter != null) sb.append("&start-after=").append(enc(startAfter))
        return sb.toString()
    }

    /** Threads for looking at several volume folders at once (finding the live volume). */
    private val probePool = Executors.newFixedThreadPool(8) { r -> Thread(r, "rf-s3").apply { isDaemon = true } }

    private val CONTENTS = Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL)
    private val KEY = Regex("<Key>(.*?)</Key>")
    private val SIZE = Regex("<Size>(\\d+)</Size>")
    private val PREFIX = Regex("<CommonPrefixes>\\s*<Prefix>(.*?)</Prefix>", RegexOption.DOT_MATCHES_ALL)
    private val TRUNC = Regex("<IsTruncated>(true|false)</IsTruncated>")
    private val TOKEN = Regex("<NextContinuationToken>(.*?)</NextContinuationToken>")

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")

    fun parseListing(xml: String): Listing {
        val objs = CONTENTS.findAll(xml).mapNotNull { m ->
            val body = m.groupValues[1]
            val k = KEY.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
            Obj(unescape(k), SIZE.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0)
        }.toList()
        val prefixes = PREFIX.findAll(xml).map { unescape(it.groupValues[1]) }.toList()
        val trunc = TRUNC.find(xml)?.groupValues?.get(1) == "true"
        return Listing(objs, prefixes, trunc, TOKEN.find(xml)?.groupValues?.get(1)?.let(::unescape))
    }

    /** Lists everything under [prefix] (follows continuation pages); [startAfter]: only keys after it. */
    fun listAll(bucket: String, prefix: String, delimiter: String? = null, maxPages: Int = 10,
                startAfter: String? = null): Listing {
        val objs = ArrayList<Obj>()
        val prefixes = ArrayList<String>()
        var token: String? = null
        for (page in 0 until maxPages) {
            val l = parseListing(Net.getText(listUrl(bucket, prefix, delimiter, token, startAfter = startAfter), 15_000))
            objs.addAll(l.objects)
            prefixes.addAll(l.prefixes)
            if (!l.truncated || l.nextToken == null) break
            token = l.nextToken
        }
        return Listing(objs, prefixes, false, null)
    }

    // ---------------------------------------------------------------- archive
    class L2File(val key: String, val timeMs: Long, val size: Long) {
        val name: String get() = key.substringAfterLast('/')
    }

    private val L2_RE = Regex("([A-Z0-9]{4})(\\d{8})_(\\d{6})(_V\\d\\d)?(\\.gz|\\.bz2)?$")

    fun parseL2(o: Obj): L2File? {
        val name = o.key.substringAfterLast('/')
        if (name.endsWith("_MDM") || name.endsWith(".tar")) return null
        val m = L2_RE.find(name) ?: return null
        val t = parseStamp(m.groupValues[2] + m.groupValues[3]) ?: return null
        return L2File(o.key, t, o.size)
    }

    private fun parseStamp(s: String): Long? = try {
        java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(s)?.time
    } catch (_: Exception) { null }

    private const val DAY = 86_400_000L

    private fun stampName(ms: Long): String = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    /** A day's archive files for [site]; with [afterMs], only those after that time (a much shorter listing). */
    fun listLevel2(site: String, dayMs: Long, afterMs: Long? = null): List<L2File> {
        val prefix = Time.ymd(dayMs) + "/" + site.uppercase() + "/"
        val after = afterMs?.let { prefix + site.uppercase() + stampName(it) }
        return listAll(L2, prefix, startAfter = after).objects.mapNotNull(::parseL2).sortedBy { it.timeMs }
    }

    private class Recent(val site: String, val count: Int, val atMs: Long, val files: List<L2File>)
    private val recent = ConcurrentHashMap<String, Recent>()

    /**
     * The newest [count] archive volumes. Only the last couple of hours are listed (a whole day's listing
     * is ~300 entries, sent again for every request); the full day, and yesterday, only when that's not enough
     * (a radar that was down, or slow clear-air scans). A listing is reused for a few seconds, so the newest
     * volume and the previous scans asked for together cost one request.
     */
    fun latestLevel2(site: String, count: Int, now: Long = System.currentTimeMillis()): List<L2File> {
        val key = site.uppercase()
        recent[key]?.let { r -> if (now - r.atMs in 0..20_000 && r.count >= count) return r.files.takeLast(count) }
        val from = now - (count + 4) * 12 * 60_000L
        val sameDay = Time.ymd(from) == Time.ymd(now)
        var files = listLevel2(site, now, if (sameDay) from else null)
        if (files.size < count && !sameDay) files = listLevel2(site, now - DAY, from) + files
        if (files.size < count) {
            val full = listLevel2(site, now)
            files = if (full.size >= count) full else listLevel2(site, now - DAY) + full
        }
        val out = files.takeLast(count)
        recent[key] = Recent(key, count, now, out)
        return out
    }

    /** Forget the remembered listing (e.g. a reload). */
    fun forgetListings() = recent.clear()

    // ---------------------------------------------------------------- live chunks
    class Chunk(val key: String, val volume: Int, val stamp: String, val number: Int, val kind: Char) {
        val timeMs: Long get() = parseStamp(stamp.replace("-", "")) ?: 0
    }

    private val CHUNK_RE = Regex("/(\\d+)/(\\d{8}-\\d{6})-(\\d{3})-([SIE])$")

    fun parseChunk(key: String): Chunk? {
        val m = CHUNK_RE.find(key) ?: return null
        return Chunk(key, m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[4][0])
    }

    /** Chunks of the newest volume using this number (numbers are reused every ~999 volumes). */
    fun newestChunks(objs: List<Obj>): List<Chunk> {
        val chunks = objs.mapNotNull { parseChunk(it.key) }
        if (chunks.isEmpty()) return emptyList()
        val newest = chunks.maxOf { it.stamp }
        return chunks.filter { it.stamp == newest }.sortedBy { it.number }
    }

    fun listChunks(site: String, volume: Int): List<Chunk> =
        newestChunks(listAll(CHUNKS, "${site.uppercase()}/$volume/").objects)

    fun chunkVolumes(site: String): List<Int> =
        listAll(CHUNKS, "${site.uppercase()}/", "/").prefixes.mapNotNull {
            it.trimEnd('/').substringAfterLast('/').toIntOrNull()
        }.sorted()

    /**
     * Finds the newest volume number. The numbers form a rotated sorted sequence (1..999, wrapping): the stamps
     * rise to the newest volume and then drop. Instead of a binary search (about a dozen requests one after
     * another), 16 volumes spread over the range are looked at together; the newest lies between the newest of
     * them and the next one, which is then looked at the same way – three rounds of parallel requests.
     */
    fun findLatestVolume(nums: List<Int>, stampOf: (Int) -> String): Int? {
        val n = nums.size
        if (n == 0) return null
        val cache = ConcurrentHashMap<Int, String>()
        fun fetch(idx: Collection<Int>) {
            val todo = idx.distinct().filter { !cache.containsKey(it) }
            if (todo.size <= 1) { for (i in todo) cache[i] = stampOf(nums[i]); return }
            val fs = todo.map { i -> probePool.submit<String> { stampOf(nums[i]) } }
            for ((k, f) in fs.withIndex()) cache[todo[k]] = try { f.get() } catch (e: java.util.concurrent.ExecutionException) {
                throw (e.cause as? Exception) ?: e
            }
        }
        val k = 16
        var start = 0
        var len = n
        while (true) {
            if (len <= k) {
                val idx = (0 until len).map { (start + it) % n }
                fetch(idx)
                return nums[idx.maxByOrNull { cache[it] ?: "" }!!]
            }
            val offs = (0 until k).map { it * len / k }
            val idx = offs.map { (start + it) % n }
            fetch(idx)
            var j = 0
            for (t in idx.indices) if ((cache[idx[t]] ?: "") > (cache[idx[j]] ?: "")) j = t
            val end = if (j + 1 < k) offs[j + 1] else len
            start = (start + offs[j]) % n
            len = end - offs[j]
            if (len <= 1) return nums[idx[j]]
        }
    }

    /** Newest stamp in a volume folder ("" when empty). */
    fun volumeStamp(site: String, volume: Int): String = listChunks(site, volume).lastOrNull()?.stamp ?: ""

    fun findLatestVolume(site: String): Int? =
        findLatestVolume(chunkVolumes(site)) { n -> volumeStamp(site, n) }

    /**
     * The newest volume, starting from [hint] (the last one seen): it and the next few are looked at
     * together. Null when that doesn't settle it (the hint is too old) – then use [findLatestVolume].
     */
    fun findLatestVolumeNear(site: String, hint: Int, nowMs: Long = System.currentTimeMillis()): Int? {
        val cands = (0..4).map { (hint - 1 + it) % 999 + 1 }              // hint .. hint+4, wrapping at 999
        val fs = cands.map { v -> probePool.submit<String> { volumeStamp(site, v) } }
        val stamps = fs.map { f -> try { f.get() } catch (e: Exception) { "" } }
        var best = -1
        for (i in stamps.indices) if (stamps[i].isNotEmpty() && (best < 0 || stamps[i] > stamps[best])) best = i
        if (best < 0 || best == cands.size - 1) return null                 // all empty, or it may go further
        val t = parseStamp(stamps[best].replace("-", "")) ?: return null
        if (nowMs - t > 20 * 60_000L) return null                           // stale: the radar moved on elsewhere
        return cands[best]
    }

    fun nextVolume(n: Int) = if (n >= 999) 1 else n + 1
    fun prevVolume(n: Int) = if (n <= 1) 999 else n - 1
}
