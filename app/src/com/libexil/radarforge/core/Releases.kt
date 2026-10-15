package com.libexil.radarforge.core

/**
 * The newest RadarForge for Android on GitHub (the updater). Plain Kotlin, so it is tested on the JVM;
 * downloading and installing are in Updater.kt.
 */
object Releases {
    const val REPO = "LibexiL/RadarForge-Android"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val PAGE_URL = "https://github.com/$REPO/releases/latest"

    data class Release(
        val tag: String,
        val version: String,        // "1.6.0" (the tag without its "v")
        val name: String,
        val notes: String,          // release notes, as plain text
        val apkUrl: String,         // "" when the release has no APK attached
        val apkSize: Long,
        val pageUrl: String,
        val publishedMs: Long,
    )

    /** A GitHub "release" object; null for drafts, pre-releases or anything unreadable. */
    fun parse(json: String): Release? {
        val o = try { Json.parse(json).obj() } catch (_: Exception) { return null }
        if (o["draft"] == true || o["prerelease"] == true) return null
        val tag = o["tag_name"].str()
        val version = versionOf(tag)
        if (version.isEmpty()) return null
        val apks = o["assets"].arr().map { it.obj() }.filter { it["name"].str().endsWith(".apk", ignoreCase = true) }
        val apk = apks.firstOrNull { it["name"].str().contains("RadarForge", ignoreCase = true) } ?: apks.firstOrNull()
        return Release(
            tag = tag,
            version = version,
            name = o["name"].str().ifBlank { "RadarForge $version" },
            notes = plainNotes(o["body"].str()),
            apkUrl = apk?.get("browser_download_url").str(),
            apkSize = apk?.get("size").num(0.0).toLong(),
            pageUrl = o["html_url"].str().ifBlank { PAGE_URL },
            publishedMs = Time.parseIso(o["published_at"].str()),
        )
    }

    /** "v1.6.0" → "1.6.0", "RadarForge-Android-1.6.0" → "1.6.0"; "" if there is no version number in it. */
    fun versionOf(tag: String): String = Regex("""\d+(\.\d+)*""").find(tag)?.value ?: ""

    /** Compares dotted version numbers: 1.10.0 > 1.9.2, 1.6 == 1.6.0. Anything after a "-" is ignored. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = versionOf(v.substringBefore('-')).split('.').mapNotNull { it.toIntOrNull() }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0).compareTo(y.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(candidate: String, installed: String): Boolean =
        versionOf(installed).isNotEmpty() && compare(candidate, installed) > 0

    /** Markdown release notes made readable as plain text. */
    fun plainNotes(md: String): String = md.replace("\r\n", "\n").lines().joinToString("\n") { line ->
        var s = line.trimEnd()
        s = s.replace(Regex("""^#{1,6}\s*"""), "")
        s = s.replace(Regex("""^(\s*)[*-]\s+"""), "$1• ")
        s = s.replace(Regex("""\*\*(.+?)\*\*"""), "$1").replace(Regex("""__(.+?)__"""), "$1")
        s = s.replace(Regex("""`([^`]*)`"""), "$1")
        s = s.replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
        s
    }.replace(Regex("""\n{3,}"""), "\n\n").trim()
}
