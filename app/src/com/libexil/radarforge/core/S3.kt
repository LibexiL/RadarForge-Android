package com.libexil.radarforge.core

import java.net.URLEncoder

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

    fun listUrl(bucket: String, prefix: String, delimiter: String? = null, token: String? = null, maxKeys: Int = 1000): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val sb = StringBuilder(bucketUrl(bucket)).append("/?list-type=2&max-keys=").append(maxKeys)
        sb.append("&prefix=").append(enc(prefix))
        if (delimiter != null) sb.append("&delimiter=").append(enc(delimiter))
        if (token != null) sb.append("&continuation-token=").append(enc(token))
        return sb.toString()
    }

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

    /** Lists everything under [prefix] (follows continuation pages). */
    fun listAll(bucket: String, prefix: String, delimiter: String? = null, maxPages: Int = 10): Listing {
        val objs = ArrayList<Obj>()
        val prefixes = ArrayList<String>()
        var token: String? = null
        for (page in 0 until maxPages) {
            val l = parseListing(Net.getText(listUrl(bucket, prefix, delimiter, token)))
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

    fun listLevel2(site: String, dayMs: Long): List<L2File> =
        listAll(L2, Time.ymd(dayMs) + "/" + site.uppercase() + "/").objects.mapNotNull(::parseL2).sortedBy { it.timeMs }

    /** The newest [count] archive volumes (looks at yesterday too when needed). */
    fun latestLevel2(site: String, count: Int, now: Long = System.currentTimeMillis()): List<L2File> {
        var files = listLevel2(site, now)
        if (files.size < count) files = listLevel2(site, now - 86_400_000L) + files
        return files.takeLast(count)
    }

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
     * Finds the newest volume number. The numbers form a rotated sorted sequence
     * (1..999, wrapping), so binary search on the stamps of the volumes present.
     */
    fun findLatestVolume(nums: List<Int>, stampOf: (Int) -> String): Int? {
        if (nums.isEmpty()) return null
        val cache = HashMap<Int, String>()
        fun stamp(i: Int): String = cache.getOrPut(nums[i]) { stampOf(nums[i]) }
        var lo = 0
        var hi = nums.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (stamp(mid) > stamp(hi)) lo = mid + 1 else hi = mid
        }
        val idx = (lo - 1 + nums.size) % nums.size
        var best = idx
        for (j in intArrayOf(idx - 1, idx + 1)) {
            val k = (j + nums.size) % nums.size
            if (stamp(k) > stamp(best)) best = k
        }
        return nums[best]
    }

    fun findLatestVolume(site: String): Int? =
        findLatestVolume(chunkVolumes(site)) { n -> listChunks(site, n).lastOrNull()?.stamp ?: "" }

    fun nextVolume(n: Int) = if (n >= 999) 1 else n + 1
}
