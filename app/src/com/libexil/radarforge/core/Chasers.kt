package com.libexil.radarforge.core

import java.util.Locale
import java.util.TimeZone

/** A storm chaser / spotter position from Spotter Network. */
class Chaser(
    val name: String,                 // "Richard Gantt (K8RCG)"
    val label: String,                // what Spotter Network shows on the map: "K8RCG"
    val lat: Double,
    val lon: Double,
    val timeMs: Long,                 // position time; 0 if unknown
    val heading: Float?,              // direction of travel (degrees), null when stationary
    val info: List<Pair<String, String>>,   // "Web" to "https://...", "Note" to "...", "Ham" to "147.52"
) {
    val moving: Boolean get() = heading != null
}

/**
 * Spotter Network's public position feeds (GRLevelX placefiles, refreshed every minute,
 * free for non-commercial use). Phone numbers and e-mail addresses in the feed are not kept.
 */
object Chasers {
    const val URL_ALL = "https://www.spotternetwork.org/feeds/gr.txt"
    const val URL_ACTIVE = "https://www.spotternetwork.org/feeds/gr-p.txt"
    private val HIDDEN = setOf("phone", "email", "e-mail", "heading")
    private val HEADING = Regex("""^Heading:\s*([A-Z]{0,3})\s*\((\d{1,3})\)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<Chaser> {
        val pf = Placefile.parse(text)
        val out = ArrayList<Chaser>()
        for (o in pf.objects) {
            val hover = o.icons.firstOrNull { it.text.isNotBlank() }?.text ?: continue
            val lines = hover.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val name = lines.firstOrNull() ?: continue
            var time = 0L
            var heading: Float? = null
            var stationary = false
            val info = ArrayList<Pair<String, String>>()
            for (l in lines.drop(1)) {
                if (time == 0L) { val t = parseTime(l); if (t > 0) { time = t; continue } }
                if (l.equals("STATIONARY", true)) { stationary = true; continue }
                val hm = HEADING.find(l)
                if (hm != null) { heading = hm.groupValues[2].toFloatOrNull()?.rem(360f); continue }
                val c = l.indexOf(':')
                if (c > 0) {
                    val k = l.substring(0, c).trim()
                    val v = l.substring(c + 1).trim()
                    if (k.lowercase(Locale.US) !in HIDDEN && v.isNotEmpty()) info.add(k to v)
                }
            }
            if (heading == null && !stationary) {
                // no text: the arrow icon (an icon without hover text) points the way
                heading = o.icons.firstOrNull { it.text.isBlank() && pf.iconFiles[it.file]?.contains("Arrow", true) == true }?.angle
            }
            val label = o.texts.firstOrNull()?.takeIf { it.isNotBlank() } ?: name
            out.add(Chaser(name, label, o.lat, o.lon, time, if (stationary) null else heading, info))
        }
        return out
    }

    /** "2026-10-01 21:59:38 UTC" -> epoch ms (0 if it isn't a time). */
    fun parseTime(s: String): Long {
        val m = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s*(UTC|Z|GMT)?$""").find(s.trim()) ?: return 0
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(m.groupValues[1])?.time ?: 0
        } catch (_: Exception) { 0 }
    }
}
