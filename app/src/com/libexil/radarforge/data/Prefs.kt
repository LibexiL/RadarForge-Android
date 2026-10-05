package com.libexil.radarforge.data

import android.content.Context
import android.content.SharedPreferences
import com.libexil.radarforge.core.Product

/** All saved settings. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("radarforge", Context.MODE_PRIVATE)

    private fun s(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(f: SharedPreferences.Editor.() -> Unit) = p.edit().apply(f).apply()

    var site: String
        get() = s("site", "")
        set(v) = put { putString("site", v) }

    var panels: Int
        get() = p.getInt("panels", 1).let { if (it in listOf(1, 2, 4)) it else 1 }
        set(v) = put { putInt("panels", v) }

    var panelProducts: List<Product>
        get() = s("panel_products", "REF,VEL,CC,ZDR").split(",").map { Product.byId(it) }.let {
            (it + listOf(Product.REF, Product.VEL, Product.CC, Product.ZDR)).take(4)
        }
        set(v) = put { putString("panel_products", v.joinToString(",") { it.id }) }

    var tilt: Float
        get() = p.getFloat("tilt", 0.5f)
        set(v) = put { putFloat("tilt", v) }

    var smooth: Boolean
        get() = p.getBoolean("smooth", false)
        set(v) = put { putBoolean("smooth", v) }

    /** "kts", "mph" or "m/s" */
    var velUnits: String
        get() = s("vel_units", "kts")
        set(v) = put { putString("vel_units", v) }

    /** "mi", "km" or "nm" */
    var distUnits: String
        get() = s("dist_units", "mi")
        set(v) = put { putString("dist_units", v) }

    var stormDir: Float
        get() = p.getFloat("storm_dir", 240f)
        set(v) = put { putFloat("storm_dir", v) }

    var stormKts: Float
        get() = p.getFloat("storm_kts", 30f)
        set(v) = put { putFloat("storm_kts", v) }

    /** How many scans before the newest are kept loaded for the loop (they load as soon as a radar is picked). */
    var previousScans: Int
        get() = p.getInt("previous_scans", 10).coerceIn(0, MAX_PREVIOUS)
        set(v) = put { putInt("previous_scans", v.coerceIn(0, MAX_PREVIOUS)) }

    /** Load the previous scans automatically on mobile data too (otherwise only on Wi-Fi, or when the loop is played). */
    var prefetchOnMobile: Boolean
        get() = p.getBoolean("prefetch_mobile", true)
        set(v) = put { putBoolean("prefetch_mobile", v) }

    var loopSpeedMs: Int
        get() = p.getInt("loop_speed", 350).coerceIn(80, 1500)
        set(v) = put { putInt("loop_speed", v) }

    var keepScreenOn: Boolean
        get() = p.getBoolean("keep_screen_on", true)
        set(v) = put { putBoolean("keep_screen_on", v) }

    var showLegend: Boolean
        get() = p.getBoolean("legend", true)
        set(v) = put { putBoolean("legend", v) }

    /** Unfold aliased velocity (BV and SRV). */
    var dealias: Boolean
        get() = p.getBoolean("dealias", false)
        set(v) = put { putBoolean("dealias", v) }

    /** Σ max-value trail over the loaded scans. */
    var trail: Boolean
        get() = p.getBoolean("trail", false)
        set(v) = put { putBoolean("trail", v) }

    /** Learn mode: explain the values under the cross-hair in plain words. */
    var learn: Boolean
        get() = p.getBoolean("learn", false)
        set(v) = put { putBoolean("learn", v) }

    // ---- look
    var theme: String
        get() = s("theme", "RadarForge Dark")
        set(v) = put { putString("theme", v) }

    /** Accent colour (0xAARRGGBB), or 0 for the theme's own. */
    var accent: Int
        get() = p.getInt("accent", 0)
        set(v) = put { putInt("accent", v) }

    /** Use the light theme when the phone is in light mode. */
    var followSystemTheme: Boolean
        get() = p.getBoolean("follow_system_theme", false)
        set(v) = put { putBoolean("follow_system_theme", v) }

    /** Size of map labels (city names, panel titles, colour bars, inspector). */
    var mapTextScale: Float
        get() = p.getFloat("map_text_scale", 1f).coerceIn(0.7f, 1.6f)
        set(v) = put { putFloat("map_text_scale", v) }

    /** Map layers; the newer ones (storm reports, chasers, SPC) start switched off. */
    fun layer(name: String) = p.getBoolean("layer_$name", name !in OFF_BY_DEFAULT)
    fun setLayer(name: String, on: Boolean) = put { putBoolean("layer_$name", on) }

    fun warnGroup(name: String) = p.getBoolean("warn_$name", true)
    fun setWarnGroup(name: String, on: Boolean) = put { putBoolean("warn_$name", on) }

    /** A custom warning line (colour, width, style) for a code like TORP, or null for the default. */
    fun warnLine(code: String): com.libexil.radarforge.core.Alerts.Line? {
        val v = p.getString("wl_$code", null) ?: return null
        val parts = v.split(",")
        if (parts.size != 3) return null
        val c = parts[0].toLongOrNull()?.toInt() ?: return null
        val w = parts[1].toFloatOrNull() ?: return null
        val k = parts[2].takeIf { it in com.libexil.radarforge.core.Alerts.KINDS } ?: "solid"
        return com.libexil.radarforge.core.Alerts.Line(c, w.coerceIn(0.5f, 12f), k)
    }
    fun setWarnLine(code: String, line: com.libexil.radarforge.core.Alerts.Line?) = put {
        if (line == null) remove("wl_$code") else putString("wl_$code", "${line.color.toLong() and 0xffffffffL},${line.width},${line.kind}")
    }

    var goToNearestRadar: Boolean
        get() = p.getBoolean("go_nearest_radar", true)
        set(v) = put { putBoolean("go_nearest_radar", v) }

    /** (1.1.0) A custom outline colour per warning type; now part of warnLine. */
    fun warnColor(event: String): Int? = if (p.contains("wcol_$event")) p.getInt("wcol_$event", 0) else null
    fun setWarnColor(event: String, argb: Int?) = put { if (argb == null) remove("wcol_$event") else putInt("wcol_$event", argb) }

    /** Selected colour table per palette family: "" = built-in, else an imported file name. */
    fun palette(family: String) = s("pal_$family", "")
    fun setPalette(family: String, file: String) = put { putString("pal_$family", file) }

    var mapScale: Float
        get() = p.getFloat("map_scale", 1.6f)
        set(v) = put { putFloat("map_scale", v) }

    /** Where the map was left (km from [mapSite]), so it opens there again. */
    fun saveMapCenter(siteId: String, x: Float, y: Float) = put { putString("map_site", siteId); putFloat("map_x", x); putFloat("map_y", y) }
    fun mapCenterFor(siteId: String): FloatArray? =
        if (s("map_site", "") == siteId) floatArrayOf(p.getFloat("map_x", 0f), p.getFloat("map_y", 0f)) else null

    var askedLocation: Boolean
        get() = p.getBoolean("asked_location", false)
        set(v) = put { putBoolean("asked_location", v) }

    var seenWelcome: Boolean
        get() = p.getBoolean("seen_welcome", false)
        set(v) = put { putBoolean("seen_welcome", v) }

    /** The newest version whose "what's new" sheet has been shown. */
    var seenWhatsNew: String
        get() = s("seen_whats_new", "")
        set(v) = put { putString("seen_whats_new", v) }

    // ---- storm reports
    /** How far back storm reports go: 1, 3, 6, 12 or 24 hours. */
    var reportHours: Int
        get() = p.getInt("report_hours", 6).let { if (it in REPORT_HOURS) it else 6 }
        set(v) = put { putInt("report_hours", v) }

    /** Report filters: tornado, hail, wind, flood, other (rain, snow...). */
    fun reportGroup(name: String) = p.getBoolean("rep_$name", name != "other")
    fun setReportGroup(name: String, on: Boolean) = put { putBoolean("rep_$name", on) }

    /** SPC outlook day: 1, 2 or 3. */
    var outlookDay: Int
        get() = p.getInt("outlook_day", 1).coerceIn(1, 3)
        set(v) = put { putInt("outlook_day", v.coerceIn(1, 3)) }

    var spotterReports: Boolean
        get() = p.getBoolean("sn_reports", true)
        set(v) = put { putBoolean("sn_reports", v) }

    // ---- storm chasers
    var chasersActiveOnly: Boolean
        get() = p.getBoolean("chasers_active", false)
        set(v) = put { putBoolean("chasers_active", v) }

    var chaserNames: Boolean
        get() = p.getBoolean("chaser_names", true)
        set(v) = put { putBoolean("chaser_names", v) }

    // ---- radars
    var favorites: List<String>
        get() = s("favorites", "").split(",").filter { it.isNotBlank() }
        set(v) = put { putString("favorites", v.distinct().joinToString(",")) }

    // ---- my location
    /** Keep the location dot up to date while the app is open (off until asked for: it shows the location indicator). */
    var liveLocation: Boolean
        get() = p.getBoolean("live_location", false)
        set(v) = put { putBoolean("live_location", v) }

    /** While following your location, switch to the nearest radar as you travel. */
    var autoSwitchRadar: Boolean
        get() = p.getBoolean("auto_switch_radar", true)
        set(v) = put { putBoolean("auto_switch_radar", v) }

    /** Open a warning (and vibrate) when a new one covers where you are. */
    var warnAtLocation: Boolean
        get() = p.getBoolean("warn_at_location", true)
        set(v) = put { putBoolean("warn_at_location", v) }

    /** Warnings already opened for your location: warning key -> (threat priority, expiry ms). */
    var notifiedWarnings: Map<String, Pair<Float, Long>>
        get() = s("notified_warnings", "").split(";").mapNotNull { e ->
            val f = e.split("~")
            if (f.size != 3) null else f[0] to ((f[1].toFloatOrNull() ?: 0f) to (f[2].toLongOrNull() ?: 0L))
        }.toMap()
        set(v) = put { putString("notified_warnings", v.entries.joinToString(";") { "${it.key}~${it.value.first}~${it.value.second}" }) }

    // ---- measuring
    var trackMinutes: Int
        get() = p.getInt("track_minutes", 60).let { if (it in TRACK_MINUTES) it else 60 }
        set(v) = put { putInt("track_minutes", v) }

    companion object {
        val OFF_BY_DEFAULT = setOf("reports", "chasers", "outlook", "mcd")
        val REPORT_HOURS = listOf(1, 3, 6, 12, 24)
        val TRACK_MINUTES = listOf(30, 60, 90, 120)
        const val MAX_PREVIOUS = 20
    }
}
