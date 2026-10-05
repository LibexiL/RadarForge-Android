package com.libexil.radarforge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.libexil.radarforge.core.Alert
import com.libexil.radarforge.core.Alerts
import com.libexil.radarforge.core.Chaser
import com.libexil.radarforge.core.Geo
import com.libexil.radarforge.core.MesoDiscussion
import com.libexil.radarforge.core.Net
import com.libexil.radarforge.core.OutlookArea
import com.libexil.radarforge.core.ReportKind
import com.libexil.radarforge.core.Site
import com.libexil.radarforge.core.Spc
import com.libexil.radarforge.core.StormReport
import com.libexil.radarforge.core.Time
import com.libexil.radarforge.data.Prefs
import com.libexil.radarforge.core.Learn
import com.libexil.radarforge.core.Product
import com.libexil.radarforge.ui.C
import com.libexil.radarforge.ui.ChipGrid
import com.libexil.radarforge.ui.Icon
import com.libexil.radarforge.ui.IconView
import com.libexil.radarforge.ui.Theme
import com.libexil.radarforge.ui.Themes
import com.libexil.radarforge.ui.W
import com.libexil.radarforge.ui.dp
import com.libexil.radarforge.ui.dpi
import com.libexil.radarforge.ui.rounded
import java.util.Locale

/** Contents of the slide-up sheets. */
object Sheets {
    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = top }

    // ------------------------------------------------------------------ radar picker
    fun sites(a: MainActivity) {
        val col = W.vCol(a)
        val search = EditText(a).apply {
            hint = "Search radar ID, city or state"
            textSize = 15f
            setTextColor(C.text)
            setHintTextColor(C.dim)
            isSingleLine = true
            background = rounded(C.alt, a.dp(10f), C.border, a.dpi(1f))
            setPadding(a.dpi(12f), a.dpi(10f), a.dpi(12f), a.dpi(10f))
        }
        col.addView(search, lp())
        val buttons = W.hRow(a)
        buttons.addView(W.button(a, "Nearest to me") {
            a.sheets.close()
            a.pickNearestRadar()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = a.dpi(8f) })
        buttons.addView(W.button(a, "Reload ${a.site?.id ?: "data"}") {
            a.sheets.close()
            a.reloadData()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(buttons, lp(top = a.dpi(10f)))
        val list = W.vCol(a)
        col.addView(list, lp(top = a.dpi(8f)))
        val loc = a.lastLocation
        lateinit var fill: (String) -> Unit
        fun row(s: Site): View {
            val dist = loc?.let {
                val km = Geo.distanceKm(it.latitude, it.longitude, s.lat, s.lon)
                if (a.prefs.distUnits == "km") "${km.toInt()} km" else "${(km * 0.621371).toInt()} mi"
            }
            val r = W.hRow(a)
            r.addView(W.listRow(a, s.id, s.title, dist, highlighted = s.id == a.site?.id) {
                a.sheets.close()
                if (s.id != a.site?.id) a.stopFollowing()
                a.selectSite(s)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val fav = s.id in a.prefs.favorites
            r.addView(W.iconButton(a, if (fav) Icon.STAR_ON else Icon.STAR, if (fav) "Remove ${s.id} from favourites" else "Add ${s.id} to favourites") {
                a.prefs.favorites = if (fav) a.prefs.favorites - s.id else a.prefs.favorites + s.id
                fill(search.text?.toString() ?: "")
            }.apply { setTint(if (fav) 0xffffd700.toInt() else C.dim) })
            return r
        }
        fill = { q ->
            list.removeAllViews()
            val qq = q.trim().lowercase(Locale.US)
            var items = a.sites.filter {
                qq.isEmpty() || it.id.lowercase().contains(qq) || it.place.lowercase().contains(qq) || it.state.lowercase() == qq ||
                    "${it.place}, ${it.state}".lowercase().contains(qq)
            }
            items = if (loc != null) items.sortedBy { Geo.distanceKm(loc.latitude, loc.longitude, it.lat, it.lon) } else items.sortedBy { it.id }
            val favs = a.prefs.favorites.mapNotNull { id -> a.sites.firstOrNull { it.id == id } }
            if (qq.isEmpty() && favs.isNotEmpty()) {
                list.addView(W.section(a, "Favourites"))
                for (s in favs) list.addView(row(s), lp())
                list.addView(W.section(a, if (loc != null) "Nearest first" else "All radars"))
            }
            for (s in items) list.addView(row(s), lp())
            if (items.isEmpty()) list.addView(W.note(a, "No radar matches \"$q\"."))
        }
        fill("")
        if (a.prefs.favorites.isEmpty()) col.addView(W.note(a, "Tap ☆ to keep a radar at the top of this list."), 2)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = fill(s?.toString() ?: "")
        })
        a.sheets.show("Choose a radar", col, fullHeight = true)
        a.sheets.onClosed = {
            (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        }
    }

    // ------------------------------------------------------------------ tilts
    fun tilts(a: MainActivity) {
        val col = W.vCol(a)
        if (a.tilts.isEmpty()) {
            col.addView(W.note(a, "Tilts appear once radar data has loaded."))
        } else {
            val cur = com.libexil.radarforge.core.Tilts.closest(a.tilts, a.prefs.tilt)
            val v = a.dm.complete ?: a.dm.live
            if (v != null) col.addView(W.note(a, "Volume coverage pattern ${v.vcp} · ${a.tilts.size} tilts. ×2 means the radar scanned that angle more than once (SAILS); the newest scan is shown."))
            for ((i, t) in a.tilts.withIndex()) {
                val moments = t.moments().mapNotNull { m -> when (m) { "REF" -> "BR"; "VEL" -> "BV"; "SW" -> "SW"; "ZDR" -> "ZDR"; "RHO" -> "CC"; "PHI" -> "PHI"; else -> null } }
                col.addView(W.listRow(a, t.label, moments.joinToString(" · "), null, highlighted = i == cur) {
                    a.sheets.close()
                    a.setTiltIndex(i)
                }, lp())
            }
        }
        a.sheets.show("Tilt", col)
    }

    // ------------------------------------------------------------------ layers
    /** Keeps the quick-switch chips and the switch rows of one sheet showing the same thing. */
    private class Sync {
        private val views = HashMap<String, MutableList<(Boolean) -> Unit>>()
        var busy = false
        fun on(key: String, update: (Boolean) -> Unit) { views.getOrPut(key) { ArrayList() }.add(update) }
        fun changed(key: String, value: Boolean) {
            busy = true
            try { views[key]?.forEach { it(value) } } finally { busy = false }
        }
    }

    /** A switch row tied to [key] in [sync]. */
    @android.annotation.SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun syncedSwitch(a: MainActivity, sync: Sync, key: String, label: String, desc: String?,
                             get: () -> Boolean, set: (Boolean) -> Unit): View {
        val row = W.switchRow(a, label, desc, get()) { on ->
            if (sync.busy) return@switchRow
            set(on)
            sync.changed(key, on)
        } as ViewGroup
        val sw = row.getChildAt(row.childCount - 1) as android.widget.Switch
        sync.on(key) { v -> if (sw.isChecked != v) sw.isChecked = v }
        return row
    }

    /** One-tap switches for everything on the map (the desktop app's Quick panel). */
    private fun quickSwitches(a: MainActivity, col: LinearLayout, sync: Sync) {
        val p = a.prefs
        col.addView(W.section(a, "Quick switches"))
        val grid = ChipGrid(a)
        fun sw(label: String, key: String, isOn: () -> Boolean, set: (Boolean) -> Unit) {
            val chip = W.chip(a, label, isOn()) { c ->
                set(!isOn())
                c.selectedState = isOn()
                sync.changed(key, isOn())
            }.apply { asTile() }
            sync.on(key) { v -> chip.selectedState = v }
            grid.addView(chip)
        }
        fun layer(label: String, key: String) = sw(label, "layer:$key", { p.layer(key) }) { p.setLayer(key, it); a.layersChanged() }
        layer("Warnings", "warnings")
        sw("Watches", "warn:watch", { p.warnGroup("watch") }) { p.setWarnGroup("watch", it); a.rebuildAlerts(); a.rebuild() }
        layer("Reports", "reports")
        layer("Chasers", "chasers")
        layer("SPC outlook", "outlook")
        layer("SPC MDs", "mcd")
        layer("Counties", "counties")
        layer("Highways", "roads")
        layer("Cities", "cities")
        layer("Range rings", "rings")
        layer("Radar sites", "sites")
        sw("Colour bar", "legend", { p.showLegend }) { p.showLegend = it; a.settingsChanged() }
        sw("Smoothing", "smooth", { p.smooth }) { p.smooth = it; a.displayChanged() }
        sw("Dealias", "dealias", { p.dealias }) { p.dealias = it; a.displayChanged() }
        sw("Σ Trail", "trail", { p.trail }) { p.trail = it; a.requestLoopFrames(); a.displayChanged() }
        sw("Learn mode", "learn", { p.learn }) { on ->
            p.learn = on; a.displayChanged()
            if (on) a.toast("Press and hold the map to see what the colours mean")
        }
        col.addView(grid, lp(top = a.dpi(2f)))
        col.addView(W.note(a, "Dealias unfolds aliased velocity (BV, SRV). Σ Trail shows the strongest value at each spot over the loaded scans – hail swaths and rotation tracks. Learn mode explains the values when you press and hold."), lp(top = a.dpi(6f)))
    }

    fun layers(a: MainActivity) {
        val p = a.prefs
        val col = W.vCol(a)
        val sync = Sync()
        quickSwitches(a, col, sync)
        col.addView(W.section(a, "Map"))
        fun layer(key: String, label: String, desc: String?) =
            col.addView(syncedSwitch(a, sync, "layer:$key", label, desc, { p.layer(key) }) { on -> p.setLayer(key, on); a.layersChanged() })
        layer("counties", "Counties", null)
        layer("roads", "Highways", null)
        layer("cities", "Cities", null)
        layer("rings", "Range rings", "Every 50 ${p.distUnits} around the radar")
        layer("sites", "Radar sites", "Tap one on the map to switch to it")
        col.addView(W.section(a, "Warnings"))
        layer("warnings", "NWS warnings", "Outlines from the National Weather Service, updated every 90 seconds")
        for ((key, label) in listOf("tornado" to "Tornado", "severe" to "Severe thunderstorm", "flood" to "Flash flood",
            "watch" to "Watches", "other" to "Other (extreme wind, marine, snow squall, dust storm, special statements)")) {
            col.addView(syncedSwitch(a, sync, "warn:$key", label, null, { p.warnGroup(key) }) { on -> p.setWarnGroup(key, on); a.rebuildAlerts(); a.rebuild() })
        }
        col.addView(W.listRow(a, "Warning lines", "Colour, width and style for each warning and threat level", "›") { warningLines(a) }, lp())

        col.addView(W.section(a, "Storm reports"))
        layer("reports", "Storm reports", "NWS local storm reports, plus Spotter Network reports: tornado, hail, wind, flooding. Tap one to read it.")
        col.addView(W.text(a, "Show the last", 14f, C.dim), lp(top = a.dpi(4f)))
        col.addView(W.segmented(a, Prefs.REPORT_HOURS.map { "$it" to "$it h" }, "${p.reportHours}") {
            p.reportHours = it.toInt(); a.applyFeedPrefs(); a.dm.refreshFeed("reports"); a.rebuildFeeds()
        })
        val groups = W.hRow(a)
        for ((g, label) in ReportKind.GROUPS) {
            groups.addView(W.chip(a, label, p.reportGroup(g)) { c ->
                val on = !p.reportGroup(g)
                p.setReportGroup(g, on)
                c.selectedState = on
                a.rebuildFeeds()
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = a.dpi(6f) })
        }
        col.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(groups) }, lp(top = a.dpi(4f)))
        col.addView(W.switchRow(a, "Include Spotter Network reports", "Sent in by trained spotters, often before the NWS posts them", p.spotterReports) {
            p.spotterReports = it; a.applyFeedPrefs(); a.dm.refreshFeed("reports"); a.rebuildFeeds()
        })
        col.addView(W.listRow(a, "List of storm reports", "Newest first, near this radar", "›") { reportList(a, a.visibleReports, "Storm reports") }, lp())

        col.addView(W.section(a, "Storm chasers"))
        layer("chasers", "Storm chasers", "Live positions from Spotter Network, updated every minute. Arrows point the way they're driving; colour shows how fresh the position is.")
        col.addView(W.segmented(a, listOf("all" to "Everyone", "active" to "Active reporters"), if (p.chasersActiveOnly) "active" else "all") {
            p.chasersActiveOnly = it == "active"; a.applyFeedPrefs(); a.dm.refreshFeed("chasers")
        })
        col.addView(W.note(a, "Active reporters: members with at least 5 accepted reports in the last 12 months. Cyan: updated in the last 15 minutes, amber: within the hour, grey: older."))
        col.addView(W.switchRow(a, "Show names", "When zoomed in", p.chaserNames) { p.chaserNames = it; a.rebuildFeeds() })

        col.addView(W.section(a, "Storm Prediction Center"))
        layer("outlook", "Convective outlook", "Risk areas: TSTM, MRGL, SLGT, ENH, MDT, HIGH. Tap inside one for the chances there.")
        col.addView(W.segmented(a, listOf("1" to "Today (day 1)", "2" to "Day 2", "3" to "Day 3"), "${p.outlookDay}") {
            p.outlookDay = it.toInt(); a.applyFeedPrefs(); a.rebuildSpc(); a.rebuild()
        })
        layer("mcd", "Mesoscale discussions", "Blue outlines – tap one to read it")
        col.addView(W.section(a, "Radar"))
        col.addView(syncedSwitch(a, sync, "smooth", "Smooth radar", "Blend neighbouring gates instead of showing them as blocks", { p.smooth }) { on ->
            p.smooth = on; a.displayChanged()
        })
        col.addView(syncedSwitch(a, sync, "dealias", "Dealias velocity", "Unfold aliased velocity, so strong winds don't flip colour (BV and SRV)", { p.dealias }) { on ->
            p.dealias = on; a.displayChanged()
        })
        col.addView(syncedSwitch(a, sync, "trail", "Σ Max-value trail", "Each panel shows the strongest value seen at every spot over the loaded scans (CC: the lowest)", { p.trail }) { on ->
            p.trail = on; a.requestLoopFrames(); a.displayChanged()
        })
        col.addView(syncedSwitch(a, sync, "legend", "Colour legend", null, { p.showLegend }) { on -> p.showLegend = on; a.settingsChanged() })
        a.sheets.show("Map layers", col, fullHeight = true)
    }

    // ------------------------------------------------------------------ warnings
    fun warnings(a: MainActivity) {
        if (!a.prefs.layer("warnings")) {
            val col = W.vCol(a)
            col.addView(W.note(a, "Warnings are turned off."))
            col.addView(W.button(a, "Turn warnings on", primary = true) {
                a.prefs.setLayer("warnings", true); a.rebuildAlerts(); a.rebuild(); warnings(a)
            }, lp())
            a.sheets.show("Warnings", col)
            return
        }
        val list = a.visibleAlerts.sortedWith(compareByDescending<Alert> { it.style.priority }.thenBy { it.expiresMs })
        alertList(a, list, "Warnings near ${a.site?.id ?: ""}") { col ->
            if (a.prefs.layer("reports")) {
                val n = a.visibleReports.size
                col.addView(W.listRow(a, "Storm reports", if (n == 0) "None near this radar in the last ${a.prefs.reportHours} h" else
                    "$n in the last ${a.prefs.reportHours} h", "›") { reportList(a, a.visibleReports, "Storm reports") }, lp())
            }
        }
    }

    fun alertList(a: MainActivity, alerts: List<Alert>, title: String, header: ((LinearLayout) -> Unit)? = null) {
        val col = W.vCol(a)
        header?.invoke(col)
        if (alerts.isEmpty()) {
            col.addView(W.note(a, if (a.dm.alertsTime == 0L) "Loading warnings…" else
                "No active warnings within about 550 miles of this radar.\nUpdated ${Time.age(a.dm.alertsTime)}."))
        } else {
            val now = System.currentTimeMillis()
            for (al in alerts) {
                val row = W.hRow(a).apply { setPadding(0, 0, 0, 0) }
                row.addView(View(a).apply { background = rounded(a.warnColor(al), a.dp(3f)) }, LinearLayout.LayoutParams(a.dpi(6f), a.dpi(40f)))
                val left = if (al.expiresMs > now) "${(al.expiresMs - now) / 60_000} min left" else "expired"
                val r = W.listRow(a, "${al.variantLabel}  (${al.variant})", listOf(al.area, al.tags.joinToString(" · ")).filter { it.isNotBlank() }.joinToString("\n"), left) {
                    alertDetail(a, al)
                }
                row.addView(r, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                col.addView(row, lp(top = a.dpi(2f)))
            }
            col.addView(W.note(a, "Updated ${Time.age(a.dm.alertsTime)} · tap a warning on the map to open it"))
        }
        a.sheets.show(title, col, fullHeight = alerts.size > 4)
    }

    fun alertDetail(a: MainActivity, al: Alert, forYou: Boolean = false) {
        val col = W.vCol(a)
        val color = a.warnColor(al)
        if (forYou) {
            col.addView(W.text(a, "This warning covers your location", 15f, 0xffffffff.toInt(), true).apply {
                background = rounded(0xffb3261e.toInt(), a.dp(10f))
                setPadding(a.dpi(12f), a.dpi(10f), a.dpi(12f), a.dpi(10f))
            }, lp(top = a.dpi(2f)))
            col.addView(W.gap(a, 1f, 10f))
        }
        col.addView(LineSample(a, a.warnLine(al.variant)), lp(h = a.dpi(16f)))
        col.addView(W.text(a, al.variantLabel, 20f, readable(color), true), lp(top = a.dpi(10f)))
        col.addView(W.text(a, "${al.variant} · ${al.event}", 13f, C.dim), lp(top = a.dpi(2f)))
        if (al.office.isNotEmpty()) col.addView(W.text(a, "NWS ${al.office}", 14f, C.dim), lp(top = a.dpi(4f)))
        val now = System.currentTimeMillis()
        val times = buildString {
            if (al.sentMs > 0) append("Issued ${Time.local(al.sentMs)}")
            if (al.expiresMs > 0) {
                append("   ·   Until ${Time.local(al.expiresMs)}")
                if (al.expiresMs > now) append(" (${(al.expiresMs - now) / 60_000} min)")
            }
        }
        col.addView(W.text(a, times, 14f, C.text), lp(top = a.dpi(8f)))
        if (al.tags.isNotEmpty()) {
            val tags = W.hRow(a)
            for (t in al.tags) tags.addView(W.text(a, t.uppercase(Locale.US), 12f, C.text, true).apply {
                background = rounded(C.button, a.dp(6f), C.border, a.dpi(1f))
                setPadding(a.dpi(8f), a.dpi(5f), a.dpi(8f), a.dpi(5f))
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = a.dpi(6f) })
            col.addView(android.widget.HorizontalScrollView(a).apply { addView(tags); isHorizontalScrollBarEnabled = false }, lp(top = a.dpi(10f)))
        }
        if (al.area.isNotEmpty()) col.addView(W.text(a, al.area, 13.5f, C.dim).apply { setLineSpacing(0f, 1.15f) }, lp(top = a.dpi(10f)))
        col.addView(W.button(a, "Show on map", primary = true) { a.sheets.close(); a.goToAlert(al) }, lp(top = a.dpi(12f)))
        if (al.headline.isNotEmpty()) col.addView(W.text(a, al.headline, 15f, C.text, true).apply { setLineSpacing(0f, 1.15f) }, lp(top = a.dpi(14f)))
        if (al.description.isNotEmpty()) col.addView(mono(a, al.description), lp(top = a.dpi(10f)))
        if (al.instruction.isNotEmpty()) {
            col.addView(W.section(a, "What to do"))
            col.addView(mono(a, al.instruction), lp())
        }
        a.sheets.show("Warning", col, fullHeight = true)
    }

    private fun mono(a: MainActivity, s: String) = TextView(a).apply {
        text = s.trim()
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setTextColor(C.text)
        setTextIsSelectable(true)
        setLineSpacing(0f, 1.1f)
    }

    // ------------------------------------------------------------------ settings
    fun settings(a: MainActivity) {
        val p = a.prefs
        val col = W.vCol(a)
        col.addView(W.section(a, "Look"))
        val accentName = Themes.ACCENTS.firstOrNull { it.first == p.accent }?.second ?: "Custom"
        col.addView(W.listRow(a, "Theme", "${C.themeName}${if (p.accent != 0) " · $accentName accent" else ""}" +
            if (p.followSystemTheme) " · follows the phone's dark mode" else "", "›") { themes(a) }, lp())
        col.addView(W.section(a, "Units"))
        col.addView(W.text(a, "Velocity", 14f, C.dim), lp())
        col.addView(W.segmented(a, listOf("kts" to "Knots", "mph" to "mph", "m/s" to "m/s"), p.velUnits) { p.velUnits = it; a.settingsChanged() })
        col.addView(W.text(a, "Distance", 14f, C.dim), lp(top = a.dpi(6f)))
        col.addView(W.segmented(a, listOf("mi" to "Miles", "km" to "Kilometres", "nm" to "Nautical mi"), p.distUnits) { p.distUnits = it; a.settingsChanged() })

        col.addView(W.section(a, "Storm-relative velocity"))
        col.addView(W.note(a, "SRV removes the storm's own motion so rotation stands out. Set the direction the storms are moving from, and their speed."))
        col.addView(W.slider(a, "Moving from", 0, 71, (p.stormDir / 5).toInt(), { "${it * 5}° (${Geo.compass(it * 5.0)})" }) {
            p.stormDir = it * 5f; a.settingsChanged()
        })
        col.addView(W.slider(a, "Speed", 0, 80, p.stormKts.toInt(), { "$it kts" }) { p.stormKts = it.toFloat(); a.settingsChanged() })

        col.addView(W.section(a, "Loop"))
        col.addView(W.note(a, "The previous scans load as soon as you pick a radar, so the loop (and the Σ trail) is ready when you want it. Only the tilt on screen is downloaded."))
        col.addView(W.slider(a, "Previous scans", 0, Prefs.MAX_PREVIOUS, p.previousScans, { if (it == 0) "off" else "$it + the newest" }) {
            p.previousScans = it; a.requestLoopFrames(); a.rebuild()
        })
        col.addView(W.switchRow(a, "Load them on mobile data", "For the lowest tilts (about 2–3 MB a scan). Higher tilts, or with this off: on Wi-Fi, or when you play the loop.", p.prefetchOnMobile) {
            p.prefetchOnMobile = it; a.requestLoopFrames()
        })
        col.addView(W.slider(a, "Speed", 1, 10, 11 - (p.loopSpeedMs / 120).coerceIn(1, 10), { listOf("", "slowest", "slower", "slow", "", "normal", "", "fast", "faster", "fastest", "fastest")[it].ifEmpty { "$it" } }) {
            p.loopSpeedMs = (11 - it) * 120
        })

        col.addView(W.section(a, "Colours"))
        col.addView(W.listRow(a, "Colour tables", "Built-in GR-style tables, or import your own .pal files", "›") { colorTables(a) }, lp())
        col.addView(W.listRow(a, "Warning lines", "Colour, width and style for each warning and threat level", "›") { warningLines(a) }, lp())

        col.addView(W.section(a, "My location"))
        col.addView(W.switchRow(a, "Show my location", "Keeps the blue dot up to date while RadarForge is open", p.liveLocation) {
            p.liveLocation = it
            if (it && !a.hasLocationPermission()) a.requestLocationPermission()
            a.settingsChanged()
        })
        col.addView(W.switchRow(a, "Switch radars as I travel", "While following your location (tap the location button at the bottom)", p.autoSwitchRadar) {
            p.autoSwitchRadar = it
        })
        col.addView(W.switchRow(a, "Alert me in a new warning", "Opens a tornado, severe thunderstorm or flash flood warning that covers where you are, and vibrates, while the app is open (needs Show my location, or following). Not a replacement for official alerts.", p.warnAtLocation) {
            p.warnAtLocation = it
        })

        col.addView(W.section(a, "Learning"))
        col.addView(W.switchRow(a, "Learn mode", "Press and hold the map: plain-language notes explain the values there", p.learn) {
            p.learn = it; a.displayChanged()
        })
        col.addView(W.listRow(a, "Radar guide", "What each product shows, and the storm signatures to look for", "›") { radarGuide(a) }, lp())

        col.addView(W.section(a, "General"))
        col.addView(W.switchRow(a, "Keep the screen on", "While RadarForge is open", p.keepScreenOn) { p.keepScreenOn = it; a.settingsChanged() })
        col.addView(W.listRow(a, "Reload radar data", "Starts the live feed for ${a.site?.id ?: "this radar"} again and fetches anything missing", null) {
            a.sheets.close(); a.reloadData()
        }, lp())
        col.addView(W.listRow(a, "Clear downloaded radar data", "Frees the space used by cached radar files", null) {
            val dir = java.io.File(a.cacheDir, "l2")
            val bytes = dir.listFiles()?.sumOf { it.length() } ?: 0
            dir.listFiles()?.forEach { it.delete() }
            a.toast("Cleared ${bytes / (1 shl 20)} MB")
        }, lp())
        col.addView(W.listRow(a, "About RadarForge", "Version, data sources, diagnostics", "›") { about(a) }, lp())
        a.sheets.show("Settings", col, fullHeight = true)
    }

    // ------------------------------------------------------------------ themes
    /** A small picture of a theme: the map with a few lines, a panel strip and its accent. */
    class ThemePreview(ctx: Context, private val t: Theme, private val accent: Int) : View(ctx) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
            val r = 8f * d
            val clip = android.graphics.Path().apply { addRoundRect(0f, 0f, w, h, r, r, android.graphics.Path.Direction.CW) }
            c.save(); c.clipPath(clip)
            c.drawColor(t.color("map_bg"))
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            fun line(key: String, width: Float, vararg pts: Float) {
                paint.color = t.color(key); paint.strokeWidth = width * d
                val path = android.graphics.Path(); path.moveTo(pts[0] * w, pts[1] * h)
                var i = 2; while (i + 1 < pts.size) { path.lineTo(pts[i] * w, pts[i + 1] * h); i += 2 }
                c.drawPath(path, paint)
            }
            line("counties", 1f, 0f, 0.42f, 0.55f, 0.42f, 0.55f, 0.95f)
            line("counties", 1f, 0.55f, 0.42f, 1f, 0.42f)
            line("roads", 1.4f, 0.05f, 0.95f, 0.45f, 0.55f, 0.95f, 0.35f)
            line("states", 1.8f, 0.3f, 0f, 0.3f, 1f)
            // a storm, and a warning outline
            paint.style = android.graphics.Paint.Style.FILL
            for ((rad, col) in listOf(0.24f to 0xff1a9e2b.toInt(), 0.16f to 0xffe5d600.toInt(), 0.09f to 0xffe0261f.toInt())) {
                paint.color = col; c.drawCircle(0.72f * w, 0.62f * h, rad * h, paint)
            }
            paint.style = android.graphics.Paint.Style.STROKE
            paint.color = 0xffff2020.toInt(); paint.strokeWidth = 1.6f * d
            c.drawRect(0.56f * w, 0.4f * h, 0.9f * w, 0.84f * h, paint)
            // the bar along the top, with an accent "chip"
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = t.color("panel"); c.drawRect(0f, 0f, w, 0.24f * h, paint)
            paint.color = t.color("text"); c.drawRoundRect(0.06f * w, 0.08f * h, 0.34f * w, 0.15f * h, 2 * d, 2 * d, paint)
            paint.color = if (accent != 0) accent else t.color("accent")
            c.drawRoundRect(0.66f * w, 0.05f * h, 0.94f * w, 0.19f * h, 6 * d, 6 * d, paint)
            c.restore()
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 1f * d; paint.color = C.border
            c.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, r, r, paint)
        }
    }

    fun themes(a: MainActivity) {
        val p = a.prefs
        val col = W.vCol(a)
        col.addView(W.note(a, "The same themes as RadarForge for PC. They colour the app and the map; your colour tables and warning lines stay as they are."))
        val grid = ChipGrid(a, minColDp = 150f, gapDp = 10f)
        for (t in Themes.BUILTIN) {
            val active = t.name == C.themeName
            val card = W.vCol(a).apply {
                setPadding(a.dpi(6f), a.dpi(6f), a.dpi(6f), a.dpi(8f))
                background = com.libexil.radarforge.ui.ripple(rounded(if (active) C.accentSoft else C.alt, a.dp(12f),
                    if (active) C.accent else C.border, a.dpi(if (active) 2f else 1f)))
                isClickable = true
                contentDescription = "${t.name} theme"
                setOnClickListener {
                    if (t.name == C.themeName && !p.followSystemTheme) return@setOnClickListener
                    p.theme = t.name
                    // picking the light theme while following the phone would be overridden in dark mode: stop following
                    if (p.followSystemTheme && (t.dark != C.dark || !t.dark)) p.followSystemTheme = false
                    a.restyle("themes")
                }
            }
            card.addView(ThemePreview(a, t, p.accent), LinearLayout.LayoutParams(-1, a.dpi(74f)))
            val name = W.hRow(a).apply { setPadding(a.dpi(4f), a.dpi(8f), a.dpi(2f), 0) }
            name.addView(W.text(a, t.name, 14f, C.text, true).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f))
            if (active) name.addView(IconView(a, Icon.CHECK, C.accent), LinearLayout.LayoutParams(a.dpi(20f), a.dpi(20f)))
            card.addView(name)
            card.addView(W.text(a, if (t.dark) "Dark" else "Light", 12f, C.dim).apply { setPadding(a.dpi(4f), a.dpi(2f), 0, 0) })
            grid.addView(card)
        }
        col.addView(grid, lp(top = a.dpi(4f)))

        col.addView(W.section(a, "Accent colour"))
        val accents = W.hRow(a)
        for ((c, label) in Themes.ACCENTS) {
            val chosen = p.accent == c
            val swatch = if (c == 0) Themes.byName(p.theme).color("accent") else c
            accents.addView(android.widget.FrameLayout(a).apply {
                background = com.libexil.radarforge.ui.ripple(rounded(swatch, a.dp(19f), if (chosen) C.text else Themes.withAlpha(C.text, 0x44), a.dpi(if (chosen) 3f else 1f)))
                isClickable = true
                contentDescription = "$label accent"
                if (c == 0) addView(W.text(a, "A", 13f, Themes.byName(p.theme).color("accent_text"), true).apply { gravity = Gravity.CENTER },
                    android.widget.FrameLayout.LayoutParams(-1, -1))
                setOnClickListener { if (!chosen) { p.accent = c; a.restyle("themes") } }
            }, LinearLayout.LayoutParams(a.dpi(38f), a.dpi(38f)).apply { rightMargin = a.dpi(10f) })
        }
        col.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(accents) }, lp(top = a.dpi(4f)))
        col.addView(W.note(a, "\"A\" uses the theme's own accent."), lp(top = a.dpi(4f)))

        col.addView(W.section(a, "Light and dark"))
        col.addView(W.switchRow(a, "Follow the phone's dark mode", "Daylight when the phone is in light mode; ${if (Themes.byName(p.theme).dark) p.theme else Themes.DEFAULT.name} in dark mode", p.followSystemTheme) {
            p.followSystemTheme = it
            a.restyle("themes")
        })

        col.addView(W.section(a, "Map text size"))
        col.addView(W.segmented(a, Themes.TEXT_SIZES.map { "${it.first}" to it.second },
            "${Themes.TEXT_SIZES.minByOrNull { Math.abs(it.first - p.mapTextScale) }?.first ?: 1f}") {
            p.mapTextScale = it.toFloat(); a.settingsChanged()
        })
        col.addView(W.note(a, "City names, panel titles, colour bars and the inspector."))
        a.sheets.show("Theme", col, fullHeight = true)
    }

    // ------------------------------------------------------------------ radar guide
    fun radarGuide(a: MainActivity) {
        val col = W.vCol(a)
        col.addView(W.note(a, "Press and hold the map with learn mode on (Map layers → Quick switches) to get these explanations for the exact spot under your finger."))
        col.addView(W.section(a, "Products"))
        for (pr in Product.entries) {
            val text = Learn.PRODUCT_HELP[pr] ?: continue
            val row = W.vCol(a).apply { setPadding(a.dpi(4f), a.dpi(6f), a.dpi(4f), a.dpi(8f)) }
            row.addView(W.text(a, "${pr.short}  ·  ${pr.title}", 15f, C.text, true))
            row.addView(W.text(a, text, 13.5f, C.dim).apply { setLineSpacing(0f, 1.18f); setPadding(0, a.dpi(4f), 0, 0) })
            col.addView(row, lp())
        }
        col.addView(W.section(a, "Signatures"))
        for ((name, text) in Learn.SIGNATURES) {
            val row = W.vCol(a).apply { setPadding(a.dpi(4f), a.dpi(6f), a.dpi(4f), a.dpi(8f)) }
            row.addView(W.text(a, name, 15f, C.text, true))
            row.addView(W.text(a, text, 13.5f, C.dim).apply { setLineSpacing(0f, 1.18f); setPadding(0, a.dpi(4f), 0, 0) })
            col.addView(row, lp())
        }
        col.addView(W.switchRow(a, "Learn mode", "Explain the values when you press and hold the map", a.prefs.learn) {
            a.prefs.learn = it; a.displayChanged()
        })
        col.addView(W.note(a, "Radar can't see everything, and it's not an official warning source. Always follow the National Weather Service."), lp(top = a.dpi(8f)))
        a.sheets.show("Radar guide", col, fullHeight = true)
    }

    // ------------------------------------------------------------------ colour tables
    fun colorTables(a: MainActivity) {
        val col = W.vCol(a)
        col.addView(W.note(a, "RadarForge reads GRLevelX / GR2Analyst .pal colour tables. Import one and it's used for the product it was made for."))
        col.addView(W.button(a, "Import a .pal file", primary = true) { a.importColorTable() }, lp())
        val names = mapOf("REF" to "Reflectivity", "VEL" to "Velocity / SRV", "SW" to "Spectrum width", "ZDR" to "ZDR", "CC" to "Correlation coefficient", "PHI" to "Differential phase")
        for (fam in a.palettes.families) {
            val t = a.palettes.table(fam)
            col.addView(W.listRow(a, names[fam] ?: fam, if (t.source == "built-in") "Built-in" else t.source, "›") { palettePicker(a, fam) }, lp(top = a.dpi(4f)))
        }
        a.sheets.show("Colour tables", col)
    }

    private fun palettePicker(a: MainActivity, family: String) {
        val col = W.vCol(a)
        val current = a.prefs.palette(family)
        col.addView(W.listRow(a, "Built-in", "RadarForge default", null, highlighted = current.isEmpty()) {
            a.palettes.select(family, ""); a.settingsChanged(); colorTables(a)
        }, lp())
        val files = a.palettes.imported(family)
        for (f in files) {
            val row = W.hRow(a)
            row.addView(W.listRow(a, f.removeSuffix(".pal"), "Imported", null, highlighted = f == current) {
                a.palettes.select(family, f); a.settingsChanged(); colorTables(a)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(W.iconButton(a, Icon.CLOSE, "Delete") { a.palettes.delete(f); a.settingsChanged(); palettePicker(a, family) })
            col.addView(row, lp())
        }
        if (files.isEmpty()) col.addView(W.note(a, "No imported tables for this product yet."))
        col.addView(W.button(a, "Import a .pal file") { a.importColorTable() }, lp(top = a.dpi(10f)))
        a.sheets.show("Colour table – $family", col)
    }

    // ------------------------------------------------------------------ warning colours
    /** Text colour that stays readable on the sheet (warning colours are lightened or darkened to suit the theme). */
    private fun readable(c: Int): Int = Themes.readable(c)

    private fun hex(c: Int) = String.format(Locale.US, "#%06X", c and 0xffffff)

    /** Draws a warning line sample (colour, width, style) across the view. */
    class LineSample(ctx: Context, line: Alerts.Line) : View(ctx) {
        var line: Alerts.Line = line
            set(v) { field = v; invalidate() }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }

        override fun onDraw(c: android.graphics.Canvas) {
            c.drawColor(C.mapBg)
            val d = resources.displayMetrics.density
            val y = height / 2f
            val w = line.width * d * 1.1f
            val x0 = 10f * d
            val x1 = width - 10f * d
            paint.color = line.color or 0xff000000.toInt()
            paint.strokeWidth = w
            c.drawLine(x0, y, x1, y, paint)
            val inner = w * line.innerShare
            if (inner > 0f) {
                paint.color = 0xff000000.toInt()
                paint.strokeWidth = maxOf(1f, inner)
                c.drawLine(x0, y, x1, y, paint)
            }
        }
    }

    /** Every warning type and threat level with its line; tap one to change it. */
    fun warningLines(a: MainActivity) {
        val col = W.vCol(a)
        col.addView(W.note(a, "Every warning type and threat level has its own line. Tap one to change its colour, width and style. The defaults use the NWS colours; the NWS has no separate colours for threat levels, so those are told apart by the line style."))
        for ((code, v) in Alerts.VARIANTS) {
            val row = W.hRow(a).apply {
                setPadding(a.dpi(4f), a.dpi(6f), a.dpi(4f), a.dpi(6f))
                background = com.libexil.radarforge.ui.ripple(null, rounded(0xffffffff.toInt(), a.dp(10f)))
                isClickable = true
                setOnClickListener { lineEditor(a, code) }
            }
            row.addView(LineSample(a, a.warnLine(code)), LinearLayout.LayoutParams(a.dpi(78f), a.dpi(26f)))
            row.addView(W.text(a, code, 14.5f, C.text, true).apply { minWidth = a.dpi(54f) },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = a.dpi(12f) })
            row.addView(W.text(a, v.label, 14.5f, C.text), LinearLayout.LayoutParams(0, -2, 1f))
            if (a.prefs.warnLine(code) != null) row.addView(W.text(a, "custom", 12f, C.dim))
            col.addView(row, lp())
        }
        col.addView(W.section(a, "Presets"))
        val presets = W.hRow(a)
        presets.addView(W.button(a, "NWS colours") {
            for (code in Alerts.VARIANTS.keys) a.prefs.setWarnLine(code, null)
            for (ev in Alerts.BASE_CODE.keys) a.prefs.setWarnColor(ev, null)
            a.rebuildAlerts(); a.rebuild(); warningLines(a)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = a.dpi(8f) })
        presets.addView(W.button(a, "Classic colours") {
            for (code in Alerts.VARIANTS.keys) a.prefs.setWarnLine(code, Alerts.CLASSIC_PRESET[code])
            a.rebuildAlerts(); a.rebuild(); warningLines(a)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(presets, lp())
        col.addView(W.note(a, "Classic colours: green flash flood, yellow severe thunderstorm, magenta reported / PDS / emergency tornado."))
        col.addView(W.section(a, "Going to a warning"))
        col.addView(W.switchRow(a, "Switch to the nearest radar", "When you open a warning's \"Show on map\"", a.prefs.goToNearestRadar) {
            a.prefs.goToNearestRadar = it
        })
        a.sheets.show("Warning lines", col, fullHeight = true)
    }

    private fun lineEditor(a: MainActivity, code: String) {
        val v = Alerts.VARIANTS.getValue(code)
        val start = a.warnLine(code)
        var color = start.color or 0xff000000.toInt()
        var width = start.width
        var kind = start.kind
        val col = W.vCol(a)
        val sample = LineSample(a, start)
        col.addView(sample, lp(h = a.dpi(48f), top = a.dpi(4f)))
        val hexField = EditText(a).apply {
            textSize = 16f
            setTextColor(C.text)
            isSingleLine = true
            typeface = Typeface.MONOSPACE
            background = rounded(C.alt, a.dp(10f), C.border, a.dpi(1f))
            setPadding(a.dpi(12f), a.dpi(9f), a.dpi(12f), a.dpi(9f))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val bars = ArrayList<android.widget.SeekBar>()
        val values = ArrayList<TextView>()
        var updating = false
        val kindChips = HashMap<String, W.Chip>()
        lateinit var widthBar: android.widget.SeekBar
        lateinit var widthText: TextView
        fun show(fromHex: Boolean = false) {
            updating = true
            sample.line = Alerts.Line(color, width, kind)
            if (!fromHex) hexField.setText(hex(color))
            val ch = intArrayOf((color shr 16) and 0xff, (color shr 8) and 0xff, color and 0xff)
            for (k in 0..2) { bars[k].progress = ch[k]; values[k].text = ch[k].toString() }
            widthBar.progress = (width * 2).toInt() - 1
            widthText.text = String.format(Locale.US, "%.1f", width)
            for ((k, chip) in kindChips) chip.selectedState = k == kind
            updating = false
        }
        // style
        col.addView(W.section(a, "Style"))
        val kinds = W.hRow(a)
        for ((k, label) in listOf("solid" to "Solid", "center" to "Centre line", "double" to "Double")) {
            val chip = W.chip(a, label, k == kind) { kind = k; show() }
            kindChips[k] = chip
            kinds.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = a.dpi(8f) })
        }
        col.addView(kinds, lp())
        // width
        val wrow = W.hRow(a).apply { setPadding(0, a.dpi(10f), 0, 0) }
        wrow.addView(W.text(a, "Width", 14f, C.dim), LinearLayout.LayoutParams(a.dpi(52f), -2))
        widthBar = android.widget.SeekBar(a).apply {
            max = 23                                    // 0.5 .. 12 in steps of 0.5
            progressTintList = android.content.res.ColorStateList.valueOf(C.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(C.accent)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (updating || !fromUser) return
                    width = (p + 1) / 2f
                    show()
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
        }
        wrow.addView(widthBar, LinearLayout.LayoutParams(0, -2, 1f))
        widthText = W.text(a, "", 13f, C.text).apply { minWidth = a.dpi(34f); gravity = Gravity.END }
        wrow.addView(widthText)
        col.addView(wrow, lp())
        // colour presets: NWS colours plus a few clear map colours
        val presets = (Alerts.VARIANTS.values.map { it.line.color } + Alerts.CLASSIC_PRESET.values.map { it.color } + listOf(
            0xffffffff, 0xffb0b0b0, 0xff00ffff, 0xff1e90ff, 0xff0000ff, 0xff008000, 0xff9400d3, 0xffff69b4, 0xffffd700, 0xff8b4513
        ).map { it.toInt() }).distinct()
        col.addView(W.section(a, "Colour"))
        var rowView: LinearLayout? = null
        for ((i, c) in presets.withIndex()) {
            if (i % 7 == 0) { rowView = W.hRow(a); col.addView(rowView, lp(top = a.dpi(6f))) }
            rowView!!.addView(View(a).apply {
                background = com.libexil.radarforge.ui.ripple(rounded(c, a.dp(17f), 0x88ffffff.toInt(), a.dpi(1f)))
                isClickable = true
                contentDescription = hex(c)
                setOnClickListener { color = c; show() }
            }, LinearLayout.LayoutParams(a.dpi(34f), a.dpi(34f)).apply { rightMargin = a.dpi(9f) })
        }
        col.addView(hexField, lp(top = a.dpi(12f)))
        for ((k, name) in listOf("Red", "Green", "Blue").withIndex()) {
            val row = W.hRow(a).apply { setPadding(0, a.dpi(8f), 0, 0) }
            row.addView(W.text(a, name, 14f, C.dim), LinearLayout.LayoutParams(a.dpi(52f), -2))
            val sb = android.widget.SeekBar(a).apply {
                max = 255
                val tint = intArrayOf(0xffe0524f.toInt(), 0xff3fae6a.toInt(), 0xff4a8cff.toInt())[k]
                progressTintList = android.content.res.ColorStateList.valueOf(tint)
                thumbTintList = android.content.res.ColorStateList.valueOf(tint)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        if (updating || !fromUser) return
                        val shift = 16 - 8 * k
                        color = (color and (0xff shl shift).inv()) or (p shl shift) or (0xff shl 24)
                        show()
                    }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
                })
            }
            bars.add(sb)
            row.addView(sb, LinearLayout.LayoutParams(0, -2, 1f))
            val tv = W.text(a, "", 13f, C.text).apply { minWidth = a.dpi(34f); gravity = Gravity.END }
            values.add(tv)
            row.addView(tv)
            col.addView(row, lp())
        }
        hexField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (updating) return
                val t = e?.toString()?.trim()?.removePrefix("#") ?: return
                if (t.length == 6) t.toIntOrNull(16)?.let { color = it or 0xff000000.toInt(); show(fromHex = true) }
            }
        })
        val buttons = W.hRow(a).apply { setPadding(0, a.dpi(16f), 0, 0) }
        buttons.addView(W.button(a, "Default") {
            color = v.line.color; width = v.line.width; kind = v.line.kind; show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = a.dpi(8f) })
        buttons.addView(W.button(a, "Save", primary = true) {
            val line = Alerts.Line(color, width, kind)
            val isDefault = color == (v.line.color or 0xff000000.toInt()) && width == v.line.width && kind == v.line.kind
            a.prefs.setWarnLine(code, if (isDefault) null else line)
            if (Alerts.BASE_CODE[v.event] == code) a.prefs.setWarnColor(v.event, null)   // the old 1.1.0 colour
            a.rebuildAlerts(); a.rebuild()
            warningLines(a)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(buttons, lp())
        show()
        a.sheets.show("$code – ${v.label}", col, fullHeight = true)
    }

    // ------------------------------------------------------------------ storm reports
    /** A coloured disc with the report's letter, like on the map. */
    private fun badge(a: MainActivity, k: ReportKind, sizeDp: Float = 30f) = TextView(a).apply {
        text = k.letter
        textSize = if (k.letter.length > 1) 10.5f else 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(0xff000000.toInt())
        background = rounded(k.color, a.dp(sizeDp / 2), 0xff000000.toInt(), a.dpi(1.5f))
        layoutParams = LinearLayout.LayoutParams(a.dpi(sizeDp), a.dpi(sizeDp))
    }

    private fun ago(ms: Long) = if (ms > 0) "${Time.local(ms)} · ${Time.age(ms)}" else "time not given"

    fun reportList(a: MainActivity, reports: List<StormReport>, title: String) {
        val col = W.vCol(a)
        if (!a.prefs.layer("reports")) {
            col.addView(W.note(a, "Storm reports are turned off."))
            col.addView(W.button(a, "Show storm reports", primary = true) {
                a.prefs.setLayer("reports", true); a.layersChanged(); a.sheets.close()
                a.toast("Loading storm reports…")
            }, lp())
            a.sheets.show(title, col)
            return
        }
        if (reports.isEmpty()) {
            col.addView(W.note(a, if (a.dm.reportsTime == 0L) "Loading storm reports…" else
                "No storm reports near this radar in the last ${a.prefs.reportHours} hours (with the types chosen under Map layers).\nUpdated ${Time.age(a.dm.reportsTime)}."))
        } else {
            for (r in reports.take(400)) {
                val row = W.hRow(a)
                row.addView(badge(a, r.kind))
                row.addView(W.listRow(a, r.title, "${r.place}\n${ago(r.timeMs)} · ${r.origin}", null) { reportDetail(a, r) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = a.dpi(4f) })
                col.addView(row, lp())
            }
            if (reports.size > 400) col.addView(W.note(a, "Showing the newest 400 of ${reports.size}."))
            col.addView(W.note(a, "Updated ${Time.age(a.dm.reportsTime)}. NWS reports via the Iowa Environmental Mesonet; preliminary and may change."))
        }
        a.sheets.show(title, col, fullHeight = reports.size > 4)
    }

    fun reportDetail(a: MainActivity, r: StormReport) {
        val col = W.vCol(a)
        val head = W.hRow(a)
        head.addView(badge(a, r.kind, 40f))
        head.addView(W.text(a, r.title, 20f, C.text, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dpi(12f) })
        col.addView(head, lp(top = a.dpi(4f)))
        if (r.type.isNotBlank() && !r.title.contains(r.type, true)) col.addView(W.text(a, r.type, 13f, C.dim), lp(top = a.dpi(6f)))
        col.addView(W.text(a, ago(r.timeMs), 14.5f, C.text), lp(top = a.dpi(10f)))
        col.addView(W.text(a, r.place, 14.5f, C.text), lp(top = a.dpi(6f)))
        val by = listOf(r.source, r.origin).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        if (by.isNotEmpty()) col.addView(W.text(a, by, 13.5f, C.dim), lp(top = a.dpi(6f)))
        col.addView(W.text(a, Geo.latLonText(r.lat, r.lon), 13f, C.dim), lp(top = a.dpi(4f)))
        col.addView(W.button(a, "Show on map", primary = true) { a.sheets.close(); a.goToPoint(r.lat, r.lon) }, lp(top = a.dpi(12f)))
        if (r.remark.isNotBlank()) col.addView(W.text(a, r.remark, 14.5f, C.text).apply { setLineSpacing(0f, 1.2f); setTextIsSelectable(true) }, lp(top = a.dpi(14f)))
        col.addView(W.note(a, "Storm reports are preliminary. Not an official warning source."), lp(top = a.dpi(12f)))
        a.sheets.show("Storm report", col)
    }

    // ------------------------------------------------------------------ storm chasers
    private fun chaserMotion(c: Chaser): String = c.heading?.let { "Driving ${Geo.compass(it.toDouble())} (${it.toInt()}°)" } ?: "Stationary"

    fun chaserList(a: MainActivity, chasers: List<Chaser>) {
        val col = W.vCol(a)
        for (c in chasers) {
            col.addView(W.listRow(a, c.name, "${chaserMotion(c)} · ${if (c.timeMs > 0) Time.age(c.timeMs) else "time not given"}", "›") {
                chaserDetail(a, c)
            }, lp())
        }
        a.sheets.show("Storm chasers here", col, fullHeight = chasers.size > 5)
    }

    fun chaserDetail(a: MainActivity, c: Chaser) {
        val col = W.vCol(a)
        col.addView(W.text(a, c.name, 20f, C.text, true), lp(top = a.dpi(4f)))
        if (c.label != c.name && !c.name.contains(c.label)) col.addView(W.text(a, c.label, 14f, C.dim), lp(top = a.dpi(4f)))
        col.addView(W.text(a, chaserMotion(c), 15f, C.text), lp(top = a.dpi(10f)))
        col.addView(W.text(a, if (c.timeMs > 0) "Position from ${ago(c.timeMs)}" else "Position time not given", 14f, C.dim), lp(top = a.dpi(4f)))
        a.lastLocation?.let { me ->
            val km = Geo.distanceKm(me.latitude, me.longitude, c.lat, c.lon)
            val b = Geo.bearingDeg(me.latitude, me.longitude, c.lat, c.lon)
            col.addView(W.text(a, "${Geo.distText(km, a.prefs.distUnits)} ${Geo.compass(b)} of you", 14f, C.dim), lp(top = a.dpi(4f)))
        }
        for ((k, v) in c.info) {
            col.addView(TextView(a).apply {
                text = "$k: $v"
                textSize = 14f
                setTextColor(C.text)
                setLinkTextColor(C.link)
                autoLinkMask = android.text.util.Linkify.WEB_URLS
                setTextIsSelectable(true)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            }, lp(top = a.dpi(6f)))
        }
        col.addView(W.button(a, "Show on map", primary = true) { a.sheets.close(); a.goToPoint(c.lat, c.lon) }, lp(top = a.dpi(14f)))
        col.addView(W.note(a, "Position shared through Spotter Network (spotternetwork.org) for non-commercial use."), lp(top = a.dpi(10f)))
        a.sheets.show("Storm chaser", col)
    }

    // ------------------------------------------------------------------ SPC
    private val mcdTexts = HashMap<String, String>()

    fun mcdDetail(a: MainActivity, m: MesoDiscussion) {
        val col = W.vCol(a)
        col.addView(W.text(a, "Mesoscale Discussion ${m.number}", 20f, C.link, true), lp(top = a.dpi(4f)))
        if (m.concerning.isNotBlank()) col.addView(W.text(a, "Concerning: ${m.concerning.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }}", 14.5f, C.text), lp(top = a.dpi(8f)))
        val now = System.currentTimeMillis()
        val times = buildString {
            if (m.issueMs > 0) append("Issued ${Time.local(m.issueMs)}")
            if (m.expireMs > 0) {
                append("   ·   Until ${Time.local(m.expireMs)}")
                if (m.expireMs > now) append(" (${(m.expireMs - now) / 60_000} min)")
            }
        }
        col.addView(W.text(a, times, 14f, C.dim), lp(top = a.dpi(6f)))
        m.watchChance?.let { col.addView(W.text(a, "Chance of a watch: $it%", 14.5f, C.text, true), lp(top = a.dpi(6f))) }
        val year = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = if (m.issueMs > 0) m.issueMs else now }.get(java.util.Calendar.YEAR)
        col.addView(W.listRow(a, "Open on the SPC website", "spc.noaa.gov – with the map", "›") {
            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Spc.mcdPage(m.number, year)))) } catch (_: Exception) {}
        }, lp(top = a.dpi(8f)))
        val body = mono(a, mcdTexts[m.productId] ?: "Loading the discussion…")
        col.addView(body, lp(top = a.dpi(8f)))
        if (m.productId.isNotBlank() && !mcdTexts.containsKey(m.productId)) {
            Thread {
                val text = try { Net.getText(Spc.mcdTextUrl(m.productId), 20_000).trim() } catch (e: Exception) { null }
                a.runOnUiThread {
                    if (text != null) mcdTexts[m.productId] = text
                    body.text = text ?: "Couldn't load the text – open it on the SPC website instead."
                }
            }.apply { isDaemon = true }.start()
        }
        a.sheets.show("SPC discussion", col, fullHeight = true)
    }

    fun outlookDetail(a: MainActivity, lat: Double, lon: Double, hits: List<OutlookArea>) {
        val col = W.vCol(a)
        val cat = hits.filter { it.category == "CATEGORICAL" }.maxByOrNull { Spc.catIndex(it.threshold) } ?: return
        val color = Themes.readable(Spc.CAT_COLOR[cat.threshold] ?: C.text)
        col.addView(W.text(a, Spc.CAT_NAME[cat.threshold] ?: cat.threshold, 20f, color, true), lp(top = a.dpi(4f)))
        col.addView(W.text(a, "SPC day ${a.dm.outlookShownDay} convective outlook (${cat.threshold})", 13.5f, C.dim), lp(top = a.dpi(4f)))
        if (cat.expireMs > 0) col.addView(W.text(a, "Valid until ${Time.local(cat.expireMs, "EEE h:mm a")}" +
            if (cat.issueMs > 0) "  ·  issued ${Time.local(cat.issueMs)}" else "", 13.5f, C.dim), lp(top = a.dpi(2f)))
        col.addView(W.section(a, "Chance within 25 miles of here"))
        // day 3 gives one "any severe" probability instead of separate tornado / wind / hail ones
        val anyKeys = setOf("ANY SEVERE", "ANYSEVERE", "PROBABILISTIC")
        val rows = if (hits.any { it.category in anyKeys }) listOf(Triple("ANY", "Any severe weather", "under 5%"))
            else listOf(Triple("TORNADO", "Tornado", "under 2%"), Triple("WIND", "Damaging wind", "under 5%"), Triple("HAIL", "Large hail", "under 5%"))
        for ((key, label, none) in rows) {
            val mine = hits.filter { if (key == "ANY") it.category in anyKeys else it.category == key }
            val best = mine.mapNotNull { it.threshold.toDoubleOrNull() }.maxOrNull()
            val sig = mine.any { it.threshold == "SIGN" }
            val text = (if (best != null) "${Math.round(best * 100)}%" else none) + if (sig) "  ·  significant (hatched)" else ""
            val row = W.hRow(a).apply { setPadding(a.dpi(4f), a.dpi(6f), a.dpi(4f), a.dpi(6f)) }
            row.addView(W.text(a, label, 15f), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(W.text(a, text, 15f, if (best != null) C.text else C.dim, best != null))
            col.addView(row, lp())
        }
        col.addView(W.note(a, "${Geo.latLonText(lat, lon)}\nFrom the Storm Prediction Center via the Iowa Environmental Mesonet. Updated ${Time.age(a.dm.outlookTime)}."), lp(top = a.dpi(8f)))
        a.sheets.show("Outlook", col)
    }

    // ------------------------------------------------------------------ about / diagnostics
    fun about(a: MainActivity) {
        val col = W.vCol(a)
        val ver = (a.application as App).versionName()
        col.addView(W.text(a, "RadarForge $ver", 20f, C.text, true), lp(top = a.dpi(6f)))
        col.addView(W.note(a, "A NEXRAD weather radar viewer for Android, in the spirit of the RadarForge desktop app."))
        col.addView(W.section(a, "Data"))
        col.addView(W.note(a, "Radar: NOAA NEXRAD Level II on AWS (Unidata real-time chunks and archive).\nWarnings: National Weather Service API.\nStorm reports, SPC outlooks and discussions: NWS and Storm Prediction Center via the Iowa Environmental Mesonet.\nStorm chasers and spotter reports: Spotter Network (non-commercial use).\nMap: US Census Bureau, Natural Earth, GeoNames.\n\nNot an official warning source – always follow the NWS and local officials."))
        col.addView(W.listRow(a, "Project page", "github.com/LibexiL", "›") {
            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LibexiL"))) } catch (_: Exception) {}
        }, lp())
        col.addView(W.listRow(a, "Diagnostics", "Graphics info and recent log – useful for bug reports", "›") { diagnostics(a) }, lp())
        a.sheets.show("About", col)
    }

    fun diagnostics(a: MainActivity) {
        val text = buildString {
            append("RadarForge ${(a.application as App).versionName()}\n")
            append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n")
            append("Graphics: ${a.renderer.glInfo}\n")
            append("Site: ${a.site?.id}  live: ${a.dm.live?.let { Time.iso(it.startMs) + " (" + it.sweeps.size + " sweeps)" } ?: "-"}\n")
            append("Complete: ${a.dm.complete?.let { Time.iso(it.startMs) + " VCP " + it.vcp } ?: "-"}\n")
            append("Warnings: ${a.dm.alerts.size} (updated ${if (a.dm.alertsTime > 0) Time.age(a.dm.alertsTime) else "never"})\n")
            fun upd(t: Long) = if (t > 0) Time.age(t) else "never"
            append("Reports: ${a.dm.reports.size} (${upd(a.dm.reportsTime)})  Chasers: ${a.dm.chasers.size} (${upd(a.dm.chasersTime)})\n")
            append("SPC: outlook ${a.dm.outlook.size} areas (${upd(a.dm.outlookTime)}), ${a.dm.mcds.size} discussions (${upd(a.dm.mcdTime)})\n")
            append("Memory: ${Runtime.getRuntime().totalMemory() / (1 shl 20)} / ${Runtime.getRuntime().maxMemory() / (1 shl 20)} MB\n\n")
            append(RfLog.text())
        }
        textSheet(a, "Diagnostics", text)
    }

    fun crashReport(a: MainActivity, report: String) {
        textSheet(a, "RadarForge closed unexpectedly", report,
            "Sorry about that. If it keeps happening, copy this report and send it to the developer.")
    }

    private fun textSheet(a: MainActivity, title: String, text: String, intro: String? = null) {
        val col = W.vCol(a)
        if (intro != null) col.addView(W.note(a, intro))
        val buttons = W.hRow(a)
        buttons.addView(W.button(a, "Copy", primary = true) {
            (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, text))
            a.toast("Copied")
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = a.dpi(8f) })
        buttons.addView(W.button(a, "Share") {
            try {
                a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, text)
                }, "Share report"))
            } catch (_: Exception) {}
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(buttons, lp())
        col.addView(mono(a, text).apply { textSize = 11f }, lp(top = a.dpi(12f)))
        a.sheets.show(title, col, fullHeight = true)
    }

    fun whatsNew(a: MainActivity) {
        a.prefs.seenWhatsNew = MainActivity.WHATS_NEW
        val col = W.vCol(a)
        val items = listOf(
            "The 10 previous scans load as soon as you pick a radar, so the loop is ready straight away. ◀ ▶ in the loop bar step one scan at a time. (Settings → Loop: how many, and whether to load them on mobile data.)",
            "Themes – the six from RadarForge for PC, an accent colour, and following the phone's dark mode (Settings → Theme, or press and hold the settings button).",
            "Quick switches at the top of Map layers: every layer and radar option one tap away.",
            "Σ max-value trail and velocity dealiasing, as on the desktop app.",
            "Learn mode and a radar guide: press and hold the map for plain-language notes about the values there.",
            "SPC day 2 and day 3 outlooks, a coloured dot showing how fresh the data is, and two-finger tap to zoom out.",
        )
        for (t in items) {
            val row = W.hRow(a).apply { setPadding(0, a.dpi(6f), 0, a.dpi(6f)) }
            row.addView(IconView(a, Icon.CHECK, C.accent), LinearLayout.LayoutParams(a.dpi(26f), a.dpi(26f)))
            row.addView(W.text(a, t, 14.5f, C.text).apply { setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dpi(8f) })
            col.addView(row, lp())
        }
        col.addView(W.section(a, "Your location"))
        col.addView(W.switchRow(a, "Keep my location up to date", "While the app is open. Needed to be alerted when a new warning covers where you are (Settings → My location).", a.prefs.liveLocation) {
            a.prefs.liveLocation = it
            if (it && !a.hasLocationPermission()) a.requestLocationPermission()
            a.stopLiveLocation(); a.startLiveLocation()        // starts again only if wanted (or following)
        })
        col.addView(W.button(a, "Got it", primary = true) { a.sheets.close() }, lp(top = a.dpi(14f)))
        a.sheets.show("New in RadarForge ${MainActivity.WHATS_NEW}", col)
    }

    fun welcome(a: MainActivity) {
        a.prefs.seenWelcome = true
        a.prefs.seenWhatsNew = MainActivity.WHATS_NEW
        val col = W.vCol(a)
        col.addView(W.note(a, "Live NEXRAD radar, tilt by tilt as the radar scans."))
        val tips = listOf(
            "Tap the radar name at the top to pick another radar (or tap a green square on the map).",
            "Drag to move, pinch to zoom, double-tap to zoom in.",
            "Press and hold the map to read the value under your finger.",
            "Pick products along the bottom; the arrows change the tilt.",
            "The panel button shows 2 or 4 linked panels – tap a panel to choose its product.",
            "Play loops the previous scans (they load as soon as you pick a radar); ◀ ▶ step through them.",
            "The ruler measures distances, or tracks a storm and shows when it reaches the towns ahead.",
            "Map layers has quick switches for everything: warnings, storm reports, chasers, SPC, smoothing, dealiasing, the Σ trail and learn mode.",
            "Settings → Theme changes the colours of the app and the map.",
        )
        for (t in tips) {
            val row = W.hRow(a).apply { setPadding(0, a.dpi(6f), 0, a.dpi(6f)) }
            row.addView(IconView(a, Icon.CHECK, C.accent), LinearLayout.LayoutParams(a.dpi(26f), a.dpi(26f)))
            row.addView(W.text(a, t, 14.5f, C.text).apply { setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dpi(8f) })
            col.addView(row, lp())
        }
        col.addView(W.switchRow(a, "Keep my location up to date", "While the app is open, so the map can show where you are and alert you when a new warning covers it.", a.prefs.liveLocation) {
            a.prefs.liveLocation = it
            if (it && !a.hasLocationPermission()) a.requestLocationPermission()
            a.stopLiveLocation(); a.startLiveLocation()
        })
        col.addView(W.button(a, "Got it", primary = true) { a.sheets.close() }, lp(top = a.dpi(14f)))
        a.sheets.show("Welcome to RadarForge", col)
    }
}
