package com.libexil.radarforge.ui

/**
 * App themes: interface and map colours. The built-in themes are the desktop app's six
 * (same names and colours), so both versions can look the same.
 * Colours are written like the desktop theme files: "#rrggbb" or "#rrggbbaa".
 */
class Theme(val name: String, val dark: Boolean, val ui: Map<String, String>, val map: Map<String, String>) {
    fun color(key: String): Int = Themes.parse(ui[key] ?: map[key] ?: Themes.DEFAULT.ui[key] ?: Themes.DEFAULT.map.getValue(key))
}

object Themes {
    val DEFAULT = Theme("RadarForge Dark", true,
        ui = mapOf("window" to "#26272d", "panel" to "#1e1f24", "alt" to "#2a2b31", "header" to "#1f2025", "button" to "#303138",
            "border" to "#3a3c45", "text" to "#e1e1e6", "dim" to "#8d909b", "accent" to "#3c6ec8", "accent_text" to "#ffffff"),
        map = mapOf("map_bg" to "#08080c", "map_gap" to "#1f1f24", "states" to "#e1e1e1", "countries" to "#d7d7d7",
            "counties" to "#696969e6", "roads" to "#af4646eb", "roads2" to "#78553cdc", "lakes" to "#466eaac8",
            "city_text" to "#e1e1e1", "city_dot" to "#e6e6e6", "site_text" to "#cde6d2", "site_88d" to "#28965a",
            "site_current" to "#ffd700", "rings" to "#c8c8d796", "label_bg" to "#000000b9", "label_text" to "#f0f0f5",
            "halo" to "#000000dc", "panel_border" to "#464650", "active_border" to "#5a8cdc"))

    val BUILTIN: List<Theme> = listOf(
        DEFAULT,
        Theme("Midnight Blue", true,
            ui = mapOf("window" to "#1a2030", "panel" to "#131826", "alt" to "#1a2132", "header" to "#121724", "button" to "#232c40",
                "border" to "#2d3850", "text" to "#dde5f3", "dim" to "#8793ab", "accent" to "#4c8dff", "accent_text" to "#ffffff"),
            map = mapOf("map_bg" to "#05070d", "map_gap" to "#121724", "counties" to "#5a6478dc", "states" to "#d8e0f0",
                "countries" to "#c8d2e6", "roads" to "#b4505ae6", "label_bg" to "#0a0f1cc8", "active_border" to "#4c8dff",
                "panel_border" to "#2d3850", "site_88d" to "#2f8f9d")),
        Theme("GR Classic", true,
            ui = mapOf("window" to "#2d2d2d", "panel" to "#232323", "alt" to "#2b2b2b", "header" to "#262626", "button" to "#3a3a3a",
                "border" to "#474747", "text" to "#e6e6e6", "dim" to "#9a9a9a", "accent" to "#3a78c8", "accent_text" to "#ffffff"),
            map = mapOf("map_bg" to "#000000", "map_gap" to "#262626", "states" to "#ffffff", "countries" to "#ffffff",
                "counties" to "#808080d2", "roads" to "#c04040", "roads2" to "#8a6446", "lakes" to "#3c64a0",
                "city_text" to "#ffffff", "label_bg" to "#000000c8", "panel_border" to "#5a5a5a", "active_border" to "#3a78c8")),
        Theme("Nord", true,
            ui = mapOf("window" to "#2e3440", "panel" to "#272c36", "alt" to "#323845", "header" to "#242933", "button" to "#3b4252",
                "border" to "#434c5e", "text" to "#eceff4", "dim" to "#9aa3b5", "accent" to "#5e81ac", "accent_text" to "#eceff4"),
            map = mapOf("map_bg" to "#1b1f27", "map_gap" to "#242933", "states" to "#d8dee9", "countries" to "#e5e9f0",
                "counties" to "#4c566ae6", "roads" to "#bf616a", "roads2" to "#d0876f96", "lakes" to "#5e81acc8",
                "city_text" to "#e5e9f0", "site_88d" to "#8fbcbb", "site_current" to "#ebcb8b", "rings" to "#88c0d096",
                "label_bg" to "#1b1f27c8", "active_border" to "#88c0d0", "panel_border" to "#434c5e")),
        Theme("High Contrast", true,
            ui = mapOf("window" to "#000000", "panel" to "#000000", "alt" to "#111111", "header" to "#000000", "button" to "#1a1a1a",
                "border" to "#9a9a9a", "text" to "#ffffff", "dim" to "#d0d0d0", "accent" to "#ffd400", "accent_text" to "#000000"),
            map = mapOf("map_bg" to "#000000", "map_gap" to "#333333", "states" to "#ffffff", "countries" to "#ffffff",
                "counties" to "#b4b4b4", "roads" to "#ff4040", "roads2" to "#c08040", "lakes" to "#4080ff",
                "city_text" to "#ffffff", "city_dot" to "#ffffff", "site_text" to "#ffffff", "label_bg" to "#000000e6",
                "label_text" to "#ffffff", "halo" to "#000000", "panel_border" to "#9a9a9a", "active_border" to "#ffd400")),
        Theme("Daylight", false,
            ui = mapOf("window" to "#eceef2", "panel" to "#ffffff", "alt" to "#f4f5f8", "header" to "#e2e5eb", "button" to "#f7f8fa",
                "border" to "#c4c9d4", "text" to "#1d2129", "dim" to "#667085", "accent" to "#2f6fdb", "accent_text" to "#ffffff"),
            map = mapOf("map_bg" to "#e8e6e1", "map_gap" to "#c9ccd3", "states" to "#2d2d2d", "countries" to "#1e1e1e",
                "counties" to "#8c8c8cc8", "roads" to "#b43c3c", "roads2" to "#a0785a", "lakes" to "#7fa8d8",
                "city_text" to "#1e1e1e", "city_dot" to "#333333", "site_text" to "#12301e", "site_88d" to "#2a9a5c",
                "rings" to "#50506496", "label_bg" to "#ffffffd2", "label_text" to "#141414", "halo" to "#ffffffdc",
                "panel_border" to "#a5aab5", "active_border" to "#2f6fdb")),
    )

    const val LIGHT_THEME = "Daylight"

    /** Accent colours to pick from (0 = the theme's own). */
    val ACCENTS: List<Pair<Int, String>> = listOf(
        0 to "Theme", 0xff3c6ec8.toInt() to "Blue", 0xff13898a.toInt() to "Teal", 0xff2e9157.toInt() to "Green",
        0xffc25a14.toInt() to "Orange", 0xffcc3b2f.toInt() to "Red", 0xff8e5bd6.toInt() to "Purple",
        0xffc8458a.toInt() to "Pink", 0xffd4a017.toInt() to "Gold",
    )

    /** Map label sizes (multipliers). */
    val TEXT_SIZES: List<Pair<Float, String>> = listOf(0.85f to "Small", 1f to "Normal", 1.2f to "Large", 1.4f to "Larger")

    fun byName(name: String): Theme = BUILTIN.firstOrNull { it.name == name } ?: DEFAULT

    /**
     * The theme to use: [chosen], or – when following the phone's dark mode – Daylight in light mode
     * (and the chosen dark theme, or RadarForge Dark, in dark mode).
     */
    fun resolve(chosen: String, followSystem: Boolean, systemDark: Boolean): Theme {
        val t = byName(chosen)
        if (!followSystem) return t
        return if (systemDark) (if (t.dark) t else DEFAULT) else byName(LIGHT_THEME)
    }

    /** "#rrggbb" / "#rrggbbaa" -> 0xAARRGGBB. */
    fun parse(hex: String): Int {
        val h = hex.trim().removePrefix("#")
        require(h.length == 6 || h.length == 8) { "not a colour: $hex" }
        val rgb = h.substring(0, 6).toLong(16).toInt()
        val a = if (h.length == 8) h.substring(6, 8).toInt(16) else 255
        return (a shl 24) or (rgb and 0xffffff)
    }

    fun luminance(c: Int): Double {
        val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /** WCAG contrast ratio of two opaque colours (1..21). */
    fun contrast(a: Int, b: Int): Double {
        fun lin(ch: Int): Double { val x = ch / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        fun rel(c: Int) = 0.2126 * lin((c shr 16) and 0xff) + 0.7152 * lin((c shr 8) and 0xff) + 0.0722 * lin(c and 0xff)
        val la = rel(a); val lb = rel(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Mixes [a] toward [b] by [t] (0..1), opaque. */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xff) * (1 - t) + ((b shr s) and 0xff) * t).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun withAlpha(c: Int, a: Int) = (a shl 24) or (c and 0xffffff)

    /** Sets every colour in [C] from [t], with [accent] (0 = the theme's) replacing its accent. */
    fun apply(t: Theme, accent: Int = 0) {
        C.themeName = t.name
        C.dark = t.dark
        C.window = t.color("window"); C.panel = t.color("panel"); C.alt = t.color("alt"); C.header = t.color("header")
        C.button = t.color("button"); C.border = t.color("border"); C.text = t.color("text"); C.dim = t.color("dim")
        C.accent = if (accent != 0) accent or 0xff000000.toInt() else t.color("accent")
        // white text on the accent unless it's too light for it (gold)
        C.accentText = if (accent != 0) (if (contrast(C.accent, 0xffffffff.toInt()) >= 3.5) 0xffffffff.toInt() else 0xff000000.toInt()) else t.color("accent_text")
        C.mapBg = t.color("map_bg"); C.mapGap = t.color("map_gap"); C.states = t.color("states"); C.countries = t.color("countries")
        C.counties = t.color("counties"); C.roads = t.color("roads"); C.roads2 = t.color("roads2"); C.lakes = t.color("lakes")
        C.rings = t.color("rings"); C.cityText = t.color("city_text"); C.cityDot = t.color("city_dot"); C.siteText = t.color("site_text")
        C.site88d = t.color("site_88d"); C.siteCurrent = t.color("site_current"); C.labelBg = t.color("label_bg")
        C.labelText = t.color("label_text"); C.halo = t.color("halo"); C.panelBorder = t.color("panel_border")
        C.activeBorder = if (accent != 0) C.accent else t.color("active_border")
        // derived colours for things the theme files don't list
        C.ripple = if (t.dark) 0x33ffffff else 0x22000000
        C.handle = mix(C.panel, C.text, if (t.dark) 0.28f else 0.25f)
        C.link = if (t.dark) mix(C.accent, 0xffffffff.toInt(), 0.42f) else mix(C.accent, 0xff000000.toInt(), 0.15f)
        C.accentSoft = withAlpha(C.accent, if (t.dark) 0x33 else 0x22)
        C.warnText = if (t.dark) 0xffffa060.toInt() else 0xffb4540f.toInt()
        C.noteText = if (luminance(C.labelBg) < 128) 0xffffc35a.toInt() else 0xff9a5a00.toInt()
        C.switchOff = if (t.dark) 0xffb0b2ba.toInt() else 0xfff4f4f6.toInt()
        C.location = if (t.dark) 0xff4aa3ff.toInt() else 0xff1f6fe0.toInt()
    }

    /**
     * Text colour that stays readable on the sheet background (warning colours on the panel): mixed toward
     * white on dark themes, or black on light ones, just enough to reach a 3.5:1 contrast.
     */
    fun readable(c: Int): Int {
        val opaque = c or 0xff000000.toInt()
        val toward = if (C.dark) 0xffffffff.toInt() else 0xff000000.toInt()
        var t = 0f
        var out = opaque
        while (contrast(out, C.panel) < 3.5 && t < 0.95f) { t += 0.05f; out = mix(opaque, toward, t) }
        return out
    }
}
