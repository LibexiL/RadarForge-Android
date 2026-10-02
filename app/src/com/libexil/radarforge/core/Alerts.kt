package com.libexil.radarforge.core

/** NWS warnings and watches from api.weather.gov (GeoJSON). */
class Alert(
    val id: String,
    val event: String,
    val variant: String,              // warning-line code: TOR, TORR, TORP, TORE, SVR, SVRC, SVRD, FFW...
    val style: Alerts.Style,
    val rings: List<FloatArray>,      // lon,lat pairs
    val sentMs: Long,
    val expiresMs: Long,
    val headline: String,
    val description: String,
    val instruction: String,
    val area: String,
    val office: String,
    val tags: List<String>,
    /** The same for every update of one warning: VTEC office.phenomenon.significance.number (else the id). */
    val trackKey: String = id,
    /** VTEC action: NEW, CON, EXT, EXA, EXB, UPG, CAN, EXP... ("" when there's none). */
    val action: String = "",
) {
    val minLat: Float; val maxLat: Float; val minLon: Float; val maxLon: Float

    init {
        var a = 999f; var b = -999f; var c = 999f; var d = -999f
        for (r in rings) {
            var i = 0
            while (i + 1 < r.size) {
                c = minOf(c, r[i]); d = maxOf(d, r[i]); a = minOf(a, r[i + 1]); b = maxOf(b, r[i + 1]); i += 2
            }
        }
        minLat = a; maxLat = b; minLon = c; maxLon = d
    }

    val isWatch: Boolean get() = event.endsWith("Watch")
    val variantLabel: String get() = Alerts.VARIANTS[variant]?.label ?: event

    /** Point-in-polygon (any ring). */
    fun contains(lat: Double, lon: Double): Boolean {
        if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false
        for (r in rings) {
            var inside = false
            val n = r.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = r[2 * i]; val yi = r[2 * i + 1]
                val xj = r[2 * j]; val yj = r[2 * j + 1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            if (inside) return true
        }
        return false
    }
}

object Alerts {
    /** colour as 0xAARRGGBB, outline width in dp, fill alpha 0-255, priority (higher drawn on top). */
    class Style(val color: Int, val width: Float, val fillAlpha: Int, val priority: Int)

    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * Default outline colours: the National Weather Service hazard map colours (weather.gov/help-map).
     * Emergencies have no NWS colour of their own, so they use the warning's colour, drawn thicker.
     */
    val NWS_COLORS: Map<String, Int> = linkedMapOf(
        "Tornado Emergency" to rgb(0xff, 0x00, 0x00),
        "Tornado Warning" to rgb(0xff, 0x00, 0x00),
        "Extreme Wind Warning" to rgb(0xff, 0x8c, 0x00),
        "Flash Flood Emergency" to rgb(0x8b, 0x00, 0x00),
        "Severe Thunderstorm Warning" to rgb(0xff, 0xa5, 0x00),
        "Flash Flood Warning" to rgb(0x8b, 0x00, 0x00),
        "Special Marine Warning" to rgb(0xff, 0xa5, 0x00),
        "Snow Squall Warning" to rgb(0xc7, 0x15, 0x85),
        "Dust Storm Warning" to rgb(0xff, 0xe4, 0xc4),
        "Special Weather Statement" to rgb(0xff, 0xe4, 0xb5),
        "Tornado Watch" to rgb(0xff, 0xff, 0x00),
        "Severe Thunderstorm Watch" to rgb(0xdb, 0x70, 0x93),
    )

    /** How one warning line is drawn: colour (0xAARRGGBB), width (dp) and kind. */
    class Line(val color: Int, val width: Float, val kind: String) {
        /** Width of the black centre stripe ("center" / "double"), as a share of the width. */
        val innerShare: Float get() = when (kind) { "center" -> 0.25f; "double" -> 0.46f; else -> 0f }
    }

    class Variant(val code: String, val event: String, val label: String, val line: Line, val priority: Float)

    val KINDS = listOf("solid", "center", "double")

    /**
     * Warning lines: every warning type and threat level has a code and its own line. Defaults
     * use the NWS colours; the NWS has no separate colours for threat levels, so those are told
     * apart by the line style.
     */
    val VARIANTS: Map<String, Variant> = listOf(
        Variant("TORE", "Tornado Emergency", "Tornado – Emergency", Line(rgb(0xff, 0, 0), 6.0f, "double"), 14f),
        Variant("TORP", "Tornado Warning", "Tornado – PDS", Line(rgb(0xff, 0, 0), 4.0f, "center"), 13f),
        Variant("TORR", "Tornado Warning", "Tornado – Reported", Line(rgb(0xff, 0, 0), 4.0f, "solid"), 12f),
        Variant("TOR", "Tornado Warning", "Tornado", Line(rgb(0xff, 0, 0), 3.0f, "solid"), 11f),
        Variant("EWW", "Extreme Wind Warning", "Extreme Wind", Line(rgb(0xff, 0x8c, 0), 3.0f, "solid"), 10f),
        Variant("FFWE", "Flash Flood Emergency", "Flash Flood – Emergency", Line(rgb(0x8b, 0, 0), 5.5f, "double"), 9.5f),
        Variant("SVRD", "Severe Thunderstorm Warning", "Severe Thunderstorm – Destructive", Line(rgb(0xff, 0xa5, 0), 4.0f, "center"), 9f),
        Variant("SVRC", "Severe Thunderstorm Warning", "Severe Thunderstorm – Considerable", Line(rgb(0xff, 0xa5, 0), 3.5f, "solid"), 8.5f),
        Variant("SVR", "Severe Thunderstorm Warning", "Severe Thunderstorm", Line(rgb(0xff, 0xa5, 0), 2.5f, "solid"), 8f),
        Variant("FFWC", "Flash Flood Warning", "Flash Flood – Considerable", Line(rgb(0x8b, 0, 0), 3.5f, "solid"), 7.5f),
        Variant("FFW", "Flash Flood Warning", "Flash Flood", Line(rgb(0x8b, 0, 0), 2.5f, "solid"), 7f),
        Variant("SMW", "Special Marine Warning", "Special Marine", Line(rgb(0xff, 0xa5, 0), 2.0f, "solid"), 6f),
        Variant("SQW", "Snow Squall Warning", "Snow Squall", Line(rgb(0xc7, 0x15, 0x85), 2.5f, "solid"), 6f),
        Variant("DSW", "Dust Storm Warning", "Dust Storm", Line(rgb(0xff, 0xe4, 0xc4), 2.0f, "solid"), 5f),
        Variant("SPS", "Special Weather Statement", "Special Weather Statement", Line(rgb(0xff, 0xe4, 0xb5), 1.5f, "solid"), 2f),
        Variant("TOA", "Tornado Watch", "Tornado Watch", Line(rgb(0xff, 0xff, 0), 1.5f, "solid"), 1f),
        Variant("SVA", "Severe Thunderstorm Watch", "Severe Thunderstorm Watch", Line(rgb(0xdb, 0x70, 0x93), 1.5f, "solid"), 1f),
    ).associateBy { it.code }

    val BASE_CODE = mapOf("Tornado Warning" to "TOR", "Tornado Emergency" to "TORE", "Severe Thunderstorm Warning" to "SVR",
        "Flash Flood Warning" to "FFW", "Flash Flood Emergency" to "FFWE", "Special Marine Warning" to "SMW",
        "Extreme Wind Warning" to "EWW", "Snow Squall Warning" to "SQW", "Dust Storm Warning" to "DSW",
        "Special Weather Statement" to "SPS", "Tornado Watch" to "TOA", "Severe Thunderstorm Watch" to "SVA")

    /** "Classic colours" preset: green flash flood, yellow severe, magenta reported/PDS/emergency tornado. */
    val CLASSIC_PRESET: Map<String, Line> = mapOf(
        "SQW" to Line(rgb(0x80, 0x80, 0xff), 2.5f, "solid"), "SMW" to Line(rgb(0, 0xe0, 0xe0), 2.0f, "solid"),
        "FFW" to Line(rgb(0, 0xff, 0), 2.5f, "solid"), "FFWC" to Line(rgb(0, 0xff, 0), 3.5f, "solid"),
        "FFWE" to Line(rgb(0, 0xff, 0), 5.5f, "double"),
        "SVR" to Line(rgb(0xff, 0xff, 0), 2.5f, "solid"), "SVRC" to Line(rgb(0xff, 0xff, 0), 3.5f, "solid"),
        "SVRD" to Line(rgb(0xff, 0xff, 0), 4.0f, "center"),
        "TOR" to Line(rgb(0xff, 0, 0), 3.0f, "solid"), "TORR" to Line(rgb(0xff, 0, 0xff), 3.5f, "solid"),
        "TORP" to Line(rgb(0xff, 0, 0xff), 4.0f, "center"), "TORE" to Line(rgb(0xff, 0, 0xff), 6.0f, "double"),
    )

    /** Warning-line code from the NWS impact tags in an alert's parameters. */
    fun variantOf(event: String, params: Map<String, Any?>): String {
        fun first(k: String) = params[k].arr().firstOrNull()?.str()?.trim()?.uppercase() ?: ""
        val dmg = first("tornadoDamageThreat").ifEmpty { first("thunderstormDamageThreat") }.ifEmpty { first("flashFloodDamageThreat") }
        return when (event) {
            "Tornado Emergency" -> "TORE"
            "Tornado Warning" -> when {
                dmg == "CATASTROPHIC" -> "TORE"
                dmg == "CONSIDERABLE" -> "TORP"
                first("tornadoDetection") == "OBSERVED" -> "TORR"
                else -> "TOR"
            }
            "Severe Thunderstorm Warning" -> when (dmg) { "DESTRUCTIVE" -> "SVRD"; "CONSIDERABLE" -> "SVRC"; else -> "SVR" }
            "Flash Flood Emergency" -> "FFWE"
            "Flash Flood Warning" -> when (dmg) { "CATASTROPHIC" -> "FFWE"; "CONSIDERABLE" -> "FFWC"; else -> "FFW" }
            else -> BASE_CODE[event] ?: "SPS"
        }
    }

    private fun style(event: String, width: Float, fill: Int, priority: Int) =
        Style(NWS_COLORS.getValue(event), width, fill, priority)

    val STYLES: Map<String, Style> = linkedMapOf(
        "Tornado Emergency" to style("Tornado Emergency", 4.0f, 0, 11),
        "Tornado Warning" to style("Tornado Warning", 3.0f, 0, 10),
        "Extreme Wind Warning" to style("Extreme Wind Warning", 3.0f, 0, 10),
        "Flash Flood Emergency" to style("Flash Flood Emergency", 3.5f, 0, 9),
        "Severe Thunderstorm Warning" to style("Severe Thunderstorm Warning", 2.5f, 0, 8),
        "Flash Flood Warning" to style("Flash Flood Warning", 2.5f, 0, 7),
        "Special Marine Warning" to style("Special Marine Warning", 2.0f, 0, 6),
        "Snow Squall Warning" to style("Snow Squall Warning", 2.5f, 0, 6),
        "Dust Storm Warning" to style("Dust Storm Warning", 2.0f, 0, 5),
        "Special Weather Statement" to style("Special Weather Statement", 1.5f, 0, 2),
        "Tornado Watch" to style("Tornado Watch", 1.5f, 45, 1),
        "Severe Thunderstorm Watch" to style("Severe Thunderstorm Watch", 1.5f, 45, 1),
    )

    /** Groups for the filter switches in the app. */
    fun group(event: String): String = when {
        event.endsWith("Watch") -> "watch"            // first: "Tornado Watch" also starts with "Tornado W"
        event == "Tornado Warning" || event == "Tornado Emergency" -> "tornado"
        event == "Severe Thunderstorm Warning" -> "severe"
        event.startsWith("Flash Flood") -> "flood"
        else -> "other"
    }

    const val URL_ALL = "https://api.weather.gov/alerts/active?status=actual"

    /** Active alerts of the kinds drawn (filtering on the server keeps the download small). */
    val URL: String
        get() = "https://api.weather.gov/alerts/active?status=actual&event=" +
            STYLES.keys.filter { !it.endsWith("Emergency") }.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /**
     * Parses the alerts feed. [countyRings] gives county outlines (lon,lat) by 5-digit
     * FIPS, used for watches, which the feed sends without a polygon.
     */
    fun parse(json: String, countyRings: (Int) -> List<FloatArray> = { emptyList() }): List<Alert> {
        val root = Json.parse(json).obj()
        val out = ArrayList<Alert>()
        for (f in root["features"].arr()) {
            val fo = f.obj()
            val p = fo["properties"].obj()
            var ev = p["event"].str()
            if (ev !in STYLES) continue
            val desc = p["description"].str()
            val params = p["parameters"].obj()
            fun param(k: String) = params[k].arr().firstOrNull()?.str()?.trim()?.uppercase() ?: ""
            if (ev == "Tornado Warning" && (desc.uppercase().contains("TORNADO EMERGENCY") || param("tornadoDamageThreat") == "CATASTROPHIC"))
                ev = "Tornado Emergency"
            if (ev == "Flash Flood Warning" && (desc.uppercase().contains("FLASH FLOOD EMERGENCY") || param("flashFloodDamageThreat") == "CATASTROPHIC"))
                ev = "Flash Flood Emergency"
            val rings = ArrayList<FloatArray>()
            val g = fo["geometry"].obj()
            val coords = g["coordinates"].arr()
            when (g["type"].str()) {
                "Polygon" -> coords.firstOrNull()?.let { rings.add(ring(it)) }
                "MultiPolygon" -> for (poly in coords) poly.arr().firstOrNull()?.let { rings.add(ring(it)) }
            }
            if (rings.isEmpty() && ev.endsWith("Watch")) {
                for (same in p["geocode"].obj()["SAME"].arr()) {
                    val fips = same.str().drop(1).toIntOrNull() ?: continue
                    rings.addAll(countyRings(fips))
                }
            }
            if (rings.isEmpty()) continue
            val tags = ArrayList<String>()
            fun first(k: String) = params[k].arr().firstOrNull()?.str()?.takeIf { it.isNotBlank() }
            first("tornadoDetection")?.let { tags.add(it) }
            first("tornadoDamageThreat")?.let { tags.add("$it tornado threat") }
            first("thunderstormDamageThreat")?.let { tags.add("$it damage threat") }
            first("flashFloodDamageThreat")?.let { tags.add("$it flood threat") }
            first("maxHailSize")?.let { tags.add("hail $it in") }
            first("maxWindGust")?.let { tags.add("wind $it") }
            val variant = variantOf(ev, params)
            val vtec = params["VTEC"].arr().firstNotNullOfOrNull { VTEC.find(it.str()) }
            out.add(Alert(
                id = p["id"].str(fo["id"].str()),
                event = ev,
                variant = variant,
                style = STYLES.getValue(ev),
                rings = rings,
                sentMs = Time.parseIso(p["sent"].str()),
                expiresMs = Time.parseIso(p["ends"].str().ifEmpty { p["expires"].str() }),
                headline = p["headline"].str(),
                description = desc,
                instruction = p["instruction"].str(),
                area = p["areaDesc"].str(),
                office = p["senderName"].str().removePrefix("NWS "),
                tags = tags,
                trackKey = vtec?.let { "${it.groupValues[2]}.${it.groupValues[3]}.${it.groupValues[4]}.${it.groupValues[5]}" } ?: p["id"].str(fo["id"].str()),
                action = vtec?.groupValues?.get(1) ?: "",
            ))
        }
        out.sortBy { VARIANTS[it.variant]?.priority ?: 0f }
        return out
    }

    /** "/O.CON.KOUN.TO.W.0042.261001T2130Z-261001T2200Z/" -> action, office, phenomenon, significance, number */
    private val VTEC = Regex("""/[OTEX]\.([A-Z]{3})\.([A-Z]{4})\.([A-Z]{2})\.([A-Z])\.(\d{4})\.""")

    private fun ring(coords: Any?): FloatArray {
        val pts = coords.arr()
        val r = FloatArray(pts.size * 2)
        for ((i, pt) in pts.withIndex()) {
            val c = pt.arr()
            r[2 * i] = c.getOrNull(0).num(0.0).toFloat()
            r[2 * i + 1] = c.getOrNull(1).num(0.0).toFloat()
        }
        return r
    }
}
