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

    var loopFrames: Int
        get() = p.getInt("loop_frames", 8).coerceIn(3, 20)
        set(v) = put { putInt("loop_frames", v) }

    var loopSpeedMs: Int
        get() = p.getInt("loop_speed", 350).coerceIn(80, 1500)
        set(v) = put { putInt("loop_speed", v) }

    var keepScreenOn: Boolean
        get() = p.getBoolean("keep_screen_on", true)
        set(v) = put { putBoolean("keep_screen_on", v) }

    var showLegend: Boolean
        get() = p.getBoolean("legend", true)
        set(v) = put { putBoolean("legend", v) }

    fun layer(name: String, default: Boolean = true) = p.getBoolean("layer_$name", default)
    fun setLayer(name: String, on: Boolean) = put { putBoolean("layer_$name", on) }

    fun warnGroup(name: String) = p.getBoolean("warn_$name", true)
    fun setWarnGroup(name: String, on: Boolean) = put { putBoolean("warn_$name", on) }

    /** A custom outline colour for a warning type (0xAARRGGBB), or null for the NWS colour. */
    fun warnColor(event: String): Int? = if (p.contains("wcol_$event")) p.getInt("wcol_$event", 0) else null
    fun setWarnColor(event: String, argb: Int?) = put { if (argb == null) remove("wcol_$event") else putInt("wcol_$event", argb) }

    /** Selected colour table per palette family: "" = built-in, else an imported file name. */
    fun palette(family: String) = s("pal_$family", "")
    fun setPalette(family: String, file: String) = put { putString("pal_$family", file) }

    var mapScale: Float
        get() = p.getFloat("map_scale", 1.6f)
        set(v) = put { putFloat("map_scale", v) }

    var askedLocation: Boolean
        get() = p.getBoolean("asked_location", false)
        set(v) = put { putBoolean("asked_location", v) }

    var seenWelcome: Boolean
        get() = p.getBoolean("seen_welcome", false)
        set(v) = put { putBoolean("seen_welcome", v) }
}
