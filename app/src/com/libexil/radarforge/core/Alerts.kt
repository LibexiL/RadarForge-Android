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
    /** When this polygon applies (an update can shrink a warning: Iowa State's archive has one piece per polygon). */
    val validFromMs: Long = sentMs,
    val validToMs: Long = expiresMs,
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
    /** Winter, cold and tropical alerts cover whole counties / zones: shaded rather than outlined. */
    val isArea: Boolean get() = event in Alerts.AREA_EVENTS
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
        // (1.7) winter, cold and tropical: whole counties / zones, shaded
        "Hurricane Warning" to rgb(0xdc, 0x14, 0x3c),
        "Storm Surge Warning" to rgb(0xb5, 0x24, 0xf7),
        "Tropical Storm Warning" to rgb(0xb2, 0x22, 0x22),
        "Hurricane Watch" to rgb(0xff, 0x00, 0xff),
        "Storm Surge Watch" to rgb(0xdb, 0x7f, 0xf7),
        "Tropical Storm Watch" to rgb(0xf0, 0x80, 0x80),
        "Blizzard Warning" to rgb(0xff, 0x45, 0x00),
        "Ice Storm Warning" to rgb(0x8b, 0x00, 0x8b),
        "Winter Storm Warning" to rgb(0xff, 0x69, 0xb4),
        "Lake Effect Snow Warning" to rgb(0x00, 0x8b, 0x8b),
        "Extreme Cold Warning" to rgb(0x00, 0x00, 0xff),
        "Winter Storm Watch" to rgb(0x46, 0x82, 0xb4),
        "Freezing Rain Advisory" to rgb(0xda, 0x70, 0xd6),
        "Winter Weather Advisory" to rgb(0x7b, 0x68, 0xee),
        "Extreme Cold Watch" to rgb(0x5f, 0x9e, 0xa0),
        "Cold Weather Advisory" to rgb(0xaf, 0xee, 0xee),
        "Freeze Warning" to rgb(0x48, 0x3d, 0x8b),
        "Frost Advisory" to rgb(0x64, 0x95, 0xed),
    )

    val WINTER_EVENTS = listOf("Blizzard Warning", "Ice Storm Warning", "Winter Storm Warning", "Lake Effect Snow Warning",
        "Winter Storm Watch", "Freezing Rain Advisory", "Winter Weather Advisory", "Extreme Cold Warning", "Extreme Cold Watch",
        "Cold Weather Advisory", "Freeze Warning", "Frost Advisory")
    val TROPICAL_EVENTS = listOf("Hurricane Warning", "Storm Surge Warning", "Tropical Storm Warning", "Hurricane Watch",
        "Storm Surge Watch", "Tropical Storm Watch")
    /** Issued for whole counties / zones and shaded rather than outlined. */
    val AREA_EVENTS: Set<String> = (WINTER_EVENTS + TROPICAL_EVENTS).toSet()

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
        Variant("HUW", "Hurricane Warning", "Hurricane Warning", Line(rgb(0xdc, 0x14, 0x3c), 2.0f, "solid"), 4.6f),
        Variant("SSW", "Storm Surge Warning", "Storm Surge Warning", Line(rgb(0xb5, 0x24, 0xf7), 2.0f, "solid"), 4.5f),
        Variant("TRW", "Tropical Storm Warning", "Tropical Storm Warning", Line(rgb(0xb2, 0x22, 0x22), 1.8f, "solid"), 4.4f),
        Variant("HUA", "Hurricane Watch", "Hurricane Watch", Line(rgb(0xff, 0, 0xff), 1.5f, "solid"), 4.3f),
        Variant("SSA", "Storm Surge Watch", "Storm Surge Watch", Line(rgb(0xdb, 0x7f, 0xf7), 1.5f, "solid"), 4.2f),
        Variant("TRA", "Tropical Storm Watch", "Tropical Storm Watch", Line(rgb(0xf0, 0x80, 0x80), 1.5f, "solid"), 4.1f),
        Variant("BZW", "Blizzard Warning", "Blizzard Warning", Line(rgb(0xff, 0x45, 0), 2.0f, "solid"), 3.9f),
        Variant("ISW", "Ice Storm Warning", "Ice Storm Warning", Line(rgb(0x8b, 0, 0x8b), 2.0f, "solid"), 3.8f),
        Variant("WSW", "Winter Storm Warning", "Winter Storm Warning", Line(rgb(0xff, 0x69, 0xb4), 1.8f, "solid"), 3.7f),
        Variant("LEW", "Lake Effect Snow Warning", "Lake Effect Snow Warning", Line(rgb(0, 0x8b, 0x8b), 1.8f, "solid"), 3.6f),
        Variant("ECW", "Extreme Cold Warning", "Extreme Cold Warning", Line(rgb(0, 0, 0xff), 1.5f, "solid"), 3.4f),
        Variant("WSA", "Winter Storm Watch", "Winter Storm Watch", Line(rgb(0x46, 0x82, 0xb4), 1.5f, "solid"), 3.3f),
        Variant("ZRY", "Freezing Rain Advisory", "Freezing Rain Advisory", Line(rgb(0xda, 0x70, 0xd6), 1.5f, "solid"), 3.2f),
        Variant("WWY", "Winter Weather Advisory", "Winter Weather Advisory", Line(rgb(0x7b, 0x68, 0xee), 1.5f, "solid"), 3.1f),
        Variant("ECA", "Extreme Cold Watch", "Extreme Cold Watch", Line(rgb(0x5f, 0x9e, 0xa0), 1.2f, "solid"), 2.9f),
        Variant("CWY", "Cold Weather Advisory", "Cold Weather Advisory", Line(rgb(0xaf, 0xee, 0xee), 1.2f, "solid"), 2.8f),
        Variant("FZW", "Freeze Warning", "Freeze Warning", Line(rgb(0x48, 0x3d, 0x8b), 1.2f, "solid"), 2.7f),
        Variant("FRY", "Frost Advisory", "Frost Advisory", Line(rgb(0x64, 0x95, 0xed), 1.2f, "solid"), 2.6f),
    ).associateBy { it.code }

    val BASE_CODE = mapOf("Tornado Warning" to "TOR", "Tornado Emergency" to "TORE", "Severe Thunderstorm Warning" to "SVR",
        "Flash Flood Warning" to "FFW", "Flash Flood Emergency" to "FFWE", "Special Marine Warning" to "SMW",
        "Extreme Wind Warning" to "EWW", "Snow Squall Warning" to "SQW", "Dust Storm Warning" to "DSW",
        "Special Weather Statement" to "SPS", "Tornado Watch" to "TOA", "Severe Thunderstorm Watch" to "SVA",
        "Hurricane Warning" to "HUW", "Storm Surge Warning" to "SSW", "Tropical Storm Warning" to "TRW", "Hurricane Watch" to "HUA",
        "Storm Surge Watch" to "SSA", "Tropical Storm Watch" to "TRA", "Blizzard Warning" to "BZW", "Ice Storm Warning" to "ISW",
        "Winter Storm Warning" to "WSW", "Lake Effect Snow Warning" to "LEW", "Extreme Cold Warning" to "ECW",
        "Winter Storm Watch" to "WSA", "Freezing Rain Advisory" to "ZRY", "Winter Weather Advisory" to "WWY",
        "Extreme Cold Watch" to "ECA", "Cold Weather Advisory" to "CWY", "Freeze Warning" to "FZW", "Frost Advisory" to "FRY")

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

    val STYLES: Map<String, Style> = linkedMapOf<String, Style>(
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
    ).also { m ->
        for (ev in WINTER_EVENTS + TROPICAL_EVENTS) m[ev] = style(ev, 1.5f, if (ev.endsWith("Watch")) 40 else 64, 1)
    }

    /** Groups for the filter switches in the app. */
    fun group(event: String): String = when {
        event in WINTER_EVENTS -> "winter"
        event in TROPICAL_EVENTS -> "tropical"
        event.endsWith("Watch") -> "watch"            // first: "Tornado Watch" also starts with "Tornado W"
        event == "Tornado Warning" || event == "Tornado Emergency" -> "tornado"
        event == "Severe Thunderstorm Warning" -> "severe"
        event.startsWith("Flash Flood") -> "flood"
        else -> "other"
    }

    const val URL_ALL = "https://api.weather.gov/alerts/active?status=actual"

    /** The warnings that can't wait: checked on their own every few seconds (a small download). */
    val URGENT_EVENTS = listOf("Tornado Warning", "Severe Thunderstorm Warning", "Flash Flood Warning",
        "Extreme Wind Warning", "Special Marine Warning", "Snow Squall Warning", "Dust Storm Warning",
        "Tornado Watch", "Severe Thunderstorm Watch")
    private val URGENT_SET = URGENT_EVENTS.toSet() + setOf("Tornado Emergency", "Flash Flood Emergency")
    val URL_URGENT: String
        get() = "https://api.weather.gov/alerts/active?status=actual&event=" +
            URGENT_EVENTS.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** Iowa State's live storm-based warnings: it reads the NWS text feed directly, so a new warning is often
     *  there a minute or more before api.weather.gov lists it. */
    private val IEM_SBW_PHENOMENA = setOf("TO", "SV", "FF", "MA", "EW", "SQ", "DS")

    /** The storm-based warnings in force now from Iowa State (one per warning: its newest polygon). */
    fun iemActive(json: String, nowMs: Long = System.currentTimeMillis()): List<Alert> {
        val newest = HashMap<String, Alert>()
        for (a in parseIem(json)) {
            val ph = a.trackKey.split(".").getOrNull(1) ?: continue
            if (ph !in IEM_SBW_PHENOMENA || a.action.uppercase() in setOf("CAN", "EXP", "UPG")) continue
            if (a.expiresMs in 1..nowMs) continue
            val k = eventKey(a.trackKey)
            val old = newest[k]
            if (old == null || a.validFromMs > old.validFromMs) newest[k] = a
        }
        return newest.values.toList()
    }

    private fun Alert.copyWith(variant: String = this.variant, event: String = this.event, rings: List<FloatArray> = this.rings,
                               tags: List<String> = this.tags, headline: String = this.headline,
                               description: String = this.description, instruction: String = this.instruction,
                               area: String = this.area, office: String = this.office) =
        Alert(id, event, variant, STYLES[event] ?: style, rings, sentMs, expiresMs, headline, description, instruction,
            area, office, tags, trackKey, action, validFromMs, validToMs)

    /** One list from the live sources: the urgent warnings from the quick check when it is newer than the full
     *  list; a warning Iowa State has that the NWS service doesn't list yet is added, and one Iowa State shows
     *  upgraded (a tornado warning becoming observed) takes the higher threat until the NWS catches up. */
    fun merge(full: List<Alert>, fast: List<Alert>?, fastNewer: Boolean, iem: List<Alert>): List<Alert> {
        val out = ArrayList<Alert>()
        if (fast != null && fastNewer) {
            full.filterTo(out) { it.event !in URGENT_SET }
            out.addAll(fast)
        } else out.addAll(full)
        val at = HashMap<String, Int>()
        out.forEachIndexed { i, a -> at[eventKey(a.trackKey)] = i }
        for (a in iem) {
            val k = eventKey(a.trackKey)
            val i = at[k]
            val pri = VARIANTS[a.variant]?.priority ?: 0f
            if (i == null) {
                out.add(a.copyWith(headline = "From Iowa State's live feed (the NWS alert service hasn't listed it yet)"))
                at[k] = out.size - 1
            } else if (pri > (VARIANTS[out[i].variant]?.priority ?: 0f)) {
                val nws = out[i]
                out[i] = nws.copyWith(variant = a.variant, event = a.event, rings = a.rings, tags = a.tags.ifEmpty { nws.tags })
            }
        }
        out.sortBy { VARIANTS[it.variant]?.priority ?: 0f }
        return out
    }

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
            if (rings.isEmpty() && (ev.endsWith("Watch") || ev in AREA_EVENTS)) {
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

    // ---------------------------------------------------------------- warnings at a scan's time
    const val IEM_SBW = "https://mesonet.agron.iastate.edu/geojson/sbw.geojson"

    private val IEM_EVENT = mapOf("TO.W" to "Tornado Warning", "SV.W" to "Severe Thunderstorm Warning",
        "FF.W" to "Flash Flood Warning", "MA.W" to "Special Marine Warning", "EW.W" to "Extreme Wind Warning",
        "SQ.W" to "Snow Squall Warning", "DS.W" to "Dust Storm Warning", "TO.A" to "Tornado Watch",
        "SV.A" to "Severe Thunderstorm Watch")

    /** The address of every warning polygon in force between two times (Iowa State's archive). */
    fun historyUrl(fromMs: Long, toMs: Long): String =
        "$IEM_SBW?sts=${Time.isoMinute(fromMs)}&ets=${Time.isoMinute(toMs)}"

    /** A warning's identity whatever the source: "OUN.TO.W.42" from "KOUN.TO.W.0042" (NWS) or "OUN.TO.W.42"
     *  (Iowa State). Watches are numbered nationally, so the office is left out for them. */
    fun eventKey(key: String): String {
        val p = key.split(".")
        if (p.size < 4) return key
        val n = p[3].toIntOrNull() ?: return key
        val office = if (p[2] == "A") "" else p[0].takeLast(3).uppercase()
        return "$office.${p[1]}.${p[2]}.$n"
    }

    /** Iowa State's sbw.geojson: one Alert per polygon piece, each with the time it applied. */
    fun parseIem(json: String): List<Alert> {
        val out = ArrayList<Alert>()
        for (f in Json.parse(json).obj()["features"].arr()) {
            val fo = f.obj()
            val p = fo["properties"].obj()
            val ph = p["phenomena"].str(); val sig = p["significance"].str()
            var ev = IEM_EVENT["$ph.$sig"] ?: continue
            fun tag(k: String) = p[k].str().trim().uppercase()
            val dmg = tag("damagetag")
            val emergency = p["is_emergency"] == true
            val pds = p["is_pds"] == true
            if (ev == "Tornado Warning" && (emergency || dmg == "CATASTROPHIC")) ev = "Tornado Emergency"
            if (ev == "Flash Flood Warning" && (emergency || dmg == "CATASTROPHIC")) ev = "Flash Flood Emergency"
            val variant = when (ev) {
                "Tornado Emergency" -> "TORE"
                "Tornado Warning" -> when { dmg == "CONSIDERABLE" || pds -> "TORP"; tag("tornadotag") == "OBSERVED" -> "TORR"; else -> "TOR" }
                "Severe Thunderstorm Warning" -> when (dmg) { "DESTRUCTIVE" -> "SVRD"; "CONSIDERABLE" -> "SVRC"; else -> "SVR" }
                "Flash Flood Emergency" -> "FFWE"
                "Flash Flood Warning" -> if (dmg == "CONSIDERABLE") "FFWC" else "FFW"
                else -> BASE_CODE[ev] ?: "SPS"
            }
            val rings = ArrayList<FloatArray>()
            val g = fo["geometry"].obj()
            val coords = g["coordinates"].arr()
            when (g["type"].str()) {
                "Polygon" -> coords.firstOrNull()?.let { rings.add(ring(it)) }
                "MultiPolygon" -> for (poly in coords) poly.arr().firstOrNull()?.let { rings.add(ring(it)) }
            }
            if (rings.isEmpty()) continue
            val issue = Time.parseIso(p["issue"].str())
            val expire = Time.parseIso(p["expire"].str())
            val begin = Time.parseIso(p["polygon_begin"].str()).takeIf { it > 0 } ?: issue
            var end = Time.parseIso(p["polygon_end"].str())
            if (end <= begin) end = expire          // (the archive has the odd polygon that ends before it begins)
            val tags = ArrayList<String>()
            p["tornadotag"].str().takeIf { it.isNotBlank() }?.let { tags.add(it) }
            p["damagetag"].str().takeIf { it.isNotBlank() }?.let { tags.add("$it damage threat") }
            p["hailtag"].str().takeIf { it.isNotBlank() }?.let { tags.add("hail $it in") }
            p["windtag"].str().takeIf { it.isNotBlank() }?.let { tags.add("wind $it mph") }
            val wfo = p["wfo"].str()
            val etn = p["eventid"].str().ifEmpty { p["eventid"].num(0.0).toInt().toString() }
            val key = "$wfo.$ph.$sig.$etn"
            out.add(Alert(id = "$key@${p["polygon_begin"].str()}", event = ev, variant = variant,
                style = STYLES.getValue(ev), rings = rings, sentMs = if (issue > 0) issue else begin,
                expiresMs = if (expire > 0) expire else end, headline = "", description = "", instruction = "",
                area = "", office = wfo, tags = tags, trackKey = key, action = p["status"].str(),
                validFromMs = begin, validToMs = end))
        }
        out.sortBy { VARIANTS[it.variant]?.priority ?: 0f }
        return out
    }

    /** The warnings in force at [t]: the archive's polygons of that time, plus live ones already in force then
     *  that the archive (a few minutes behind) doesn't have yet. */
    fun at(t: Long, live: List<Alert>, history: List<Alert>): List<Alert> {
        val out = ArrayList<Alert>()
        val seen = HashSet<String>()
        for (a in history) if (a.validFromMs <= t && t < a.validToMs) { out.add(a); seen.add(eventKey(a.trackKey)) }
        for (a in live) {
            if (eventKey(a.trackKey) in seen) continue
            if (a.validFromMs > t || (a.validToMs in 1 until t)) continue
            out.add(a)
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
