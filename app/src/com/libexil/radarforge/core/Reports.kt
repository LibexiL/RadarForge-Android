package com.libexil.radarforge.core

import java.util.Locale

/** How a storm report is drawn: a coloured disc with a letter. [group] is the filter it belongs to. */
enum class ReportKind(val letter: String, val color: Int, val label: String, val group: String) {
    TORNADO("T", 0xffff2828.toInt(), "Tornado", "tornado"),
    FUNNEL("FC", 0xffff9696.toInt(), "Funnel cloud", "tornado"),
    WALL_CLOUD("WC", 0xffdcdcdc.toInt(), "Wall cloud", "tornado"),
    HAIL("H", 0xff3cdc3c.toInt(), "Hail", "hail"),
    WIND_DAMAGE("W", 0xff50a0ff.toInt(), "Wind damage", "wind"),
    WIND_GUST("G", 0xff96d2ff.toInt(), "Wind gust", "wind"),
    FLOOD("F", 0xff1ec8b4.toInt(), "Flooding", "flood"),
    OTHER("•", 0xffb4b4be.toInt(), "Other", "other");

    companion object {
        val GROUPS = listOf("tornado" to "Tornado", "hail" to "Hail", "wind" to "Wind", "flood" to "Flood", "other" to "Other")
    }
}

/** A local storm report from the NWS (via the Iowa Environmental Mesonet) or a Spotter Network report. */
class StormReport(
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val kind: ReportKind,
    val type: String,                 // as reported: "TSTM WND GST", "HAIL", "Tornado"...
    val magnitude: String,            // "1.75 in", "60 mph", "" when none
    val place: String,                // "3 N Norman, OK (Cleveland Co.)"
    val source: String,               // "Trained Spotter", "Emergency Mngr"...
    val remark: String,
    val origin: String,               // "NWS Norman (OUN)" / "Spotter Network"
) {
    val title: String get() = if (magnitude.isNotEmpty()) "${typeTitle()} $magnitude" else typeTitle()

    private fun typeTitle(): String = if (kind == ReportKind.OTHER && type.isNotBlank())
        type.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) } else kind.label
}

object Reports {
    /** NWS local storm reports of the last [hours] hours, whole country (the feed ignores a bounding box). */
    fun lsrUrl(hours: Int) = "https://mesonet.agron.iastate.edu/geojson/lsr.geojson?hours=${hours.coerceIn(1, 72)}"

    /** Spotter Network reports (GRLevelX placefile). */
    const val SN_URL = "https://www.spotternetwork.org/feeds/reports.txt"

    fun kindOf(type: String): ReportKind {
        val u = type.uppercase(Locale.US)
        return when {
            "FUNNEL" in u -> ReportKind.FUNNEL
            "WALL CLOUD" in u -> ReportKind.WALL_CLOUD
            "TORNADO" in u || "WATERSPOUT" in u || "LANDSPOUT" in u -> ReportKind.TORNADO
            "HAIL" in u -> ReportKind.HAIL
            "CHILL" in u -> ReportKind.OTHER
            "WND DMG" in u || "WIND DMG" in u || "WIND DAMAGE" in u || "DOWNBURST" in u || "MICROBURST" in u ->
                ReportKind.WIND_DAMAGE
            "WND" in u || "WIND" in u || "GUST" in u -> ReportKind.WIND_GUST
            "FLOOD" in u || "DEBRIS FLOW" in u -> ReportKind.FLOOD
            else -> ReportKind.OTHER
        }
    }

    private fun unitText(u: String): String = when (u.trim().lowercase(Locale.US)) {
        "inch", "inches", "in" -> "in"
        "mph" -> "mph"
        "knots", "kts", "kt" -> "kt"
        "f", "ef", "" -> ""
        else -> u.trim().lowercase(Locale.US)
    }

    private fun magText(mag: Double, unit: String): String {
        if (mag.isNaN() || mag <= 0.0) return ""
        val v = if (mag == Math.floor(mag)) mag.toLong().toString() else String.format(Locale.US, "%.2f", mag).trimEnd('0').trimEnd('.')
        val u = unitText(unit)
        return if (u.isEmpty()) v else "$v $u"
    }

    /** IEM's lsr.geojson. */
    fun parseLsr(json: String): List<StormReport> {
        val root = Json.parse(json).obj()
        val out = ArrayList<StormReport>()
        for (f in root["features"].arr()) {
            val fo = f.obj()
            val p = fo["properties"].obj()
            val g = fo["geometry"].obj()
            val c = g["coordinates"].arr()
            var lon = c.getOrNull(0).num()
            var lat = c.getOrNull(1).num()
            if (lat.isNaN() || lon.isNaN()) { lat = p["lat"].num(); lon = p["lon"].num() }
            if (lat.isNaN() || lon.isNaN()) continue
            val type = p["typetext"].str().trim()
            val mag = p["magf"].num().let { if (it.isNaN()) p["magnitude"].str().toDoubleOrNull() ?: Double.NaN else it }
            val city = p["city"].str().trim()
            val st = p["st"].str(p["state"].str()).trim()
            val county = p["county"].str().trim()
            val place = buildString {
                append(city)
                if (st.isNotEmpty()) append(if (isEmpty()) st else ", $st")
                if (county.isNotEmpty()) append(" ($county Co.)")
            }
            val wfo = p["wfo"].str().trim()
            out.add(StormReport(lat, lon, Time.parseIso(p["valid"].str()), kindOf(type), type, magText(mag, p["unit"].str()),
                place, p["source"].str().trim(), p["remark"].str().trim(), if (wfo.isNotEmpty()) "NWS $wfo" else "NWS"))
        }
        return out
    }

    private val SN_TIME = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})""")

    /** Spotter Network's reports placefile: each report is an icon whose hover text describes it. */
    fun parseSpotterNetwork(text: String): List<StormReport> {
        val out = ArrayList<StormReport>()
        for (o in Placefile.parse(text).objects) {
            val hover = o.icons.firstOrNull { it.text.isNotBlank() }?.text ?: o.texts.firstOrNull() ?: continue
            val lines = hover.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            // the reporter's name must not decide the type ("Reported By: Hailey ...")
            val body = lines.filterNot { it.startsWith("Reported By", true) || it.startsWith("Reporter", true) }
            // the type line decides ("Tornado"); notes ("no tornado seen") only when it doesn't say
            val typeLine = body.firstOrNull { !SN_TIME.containsMatchIn(it) && !it.startsWith("Notes", true) && !it.startsWith("Note:", true) }
            val kind = typeLine?.let { kindOf(it) }?.takeIf { it != ReportKind.OTHER } ?: kindOf(body.joinToString(" "))
            val time = SN_TIME.find(hover)?.let { Chasers.parseTime(it.groupValues[1]) } ?: 0L
            val type = body.firstOrNull { kindOf(it) == kind && !SN_TIME.containsMatchIn(it) } ?: kind.label
            out.add(StormReport(o.lat, o.lon, time, kind, type.substringAfter(':').trim().ifEmpty { kind.label }, "",
                String.format(Locale.US, "%.3f, %.3f", o.lat, o.lon), "Spotter Network member", lines.joinToString("\n"), "Spotter Network"))
        }
        return out
    }
}
