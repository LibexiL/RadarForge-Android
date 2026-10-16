package com.libexil.radarforge

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.libexil.radarforge.core.Geo
import com.libexil.radarforge.core.Models
import com.libexil.radarforge.core.Product
import com.libexil.radarforge.core.ReportKind
import com.libexil.radarforge.core.Time
import com.libexil.radarforge.core.Tropical
import com.libexil.radarforge.data.Prefs
import com.libexil.radarforge.ui.C
import com.libexil.radarforge.ui.ChipGrid
import com.libexil.radarforge.ui.Icon
import com.libexil.radarforge.ui.IconView
import com.libexil.radarforge.ui.W
import com.libexil.radarforge.ui.dp
import com.libexil.radarforge.ui.dpi
import com.libexil.radarforge.ui.rounded
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The 1.7 sheets: Layers (with presets), Models, Tropical. */
object SheetsWx {
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    // ------------------------------------------------------------------ presets
    class Preset(val id: String, val label: String, val tip: String, val on: List<String>, val off: List<String>, val ptype: Boolean)

    val PRESETS = listOf(
        Preset("severe", "Severe", "Warnings, watches, storm reports, the SPC outlook and discussions",
            listOf("warnings", "reports", "outlook", "mcd"), listOf("freezing", "snowfall", "rainfall", "tropical", "tropical_models", "tropical_outlook", "buoys"), false),
        Preset("winter", "Winter", "Precipitation type on the radar, the freezing line, winter warnings and advisories, snow and ice reports",
            listOf("warnings", "reports", "mcd", "freezing"), listOf("outlook", "rainfall", "tropical", "tropical_models", "tropical_outlook", "buoys"), true),
        Preset("tropical", "Tropical", "Hurricanes: cones, tracks, wind fields, model tracks, the outlook, buoys, hurricane and storm surge warnings",
            listOf("warnings", "reports", "tropical", "tropical_wind", "tropical_outlook", "tropical_models", "buoys"), listOf("outlook", "mcd", "freezing", "snowfall"), false),
        Preset("clean", "Clean", "Just the radar, the map and warnings",
            listOf("warnings"), listOf("reports", "outlook", "mcd", "chasers", "freezing", "snowfall", "rainfall", "tropical", "tropical_models", "tropical_outlook", "buoys"), false),
    )

    fun applyPreset(a: MainActivity, ps: Preset) {
        val p = a.prefs
        for (k in ps.on) p.setLayer(k, true)
        for (k in ps.off) p.setLayer(k, false)
        p.setWarnGroup("watch", ps.id != "clean")
        when (ps.id) {
            "winter" -> { p.setWarnGroup("winter", true); p.setReportGroup("winter", true) }
            "tropical" -> p.setWarnGroup("tropical", true)
        }
        // precipitation type in the active panel for winter; back to reflectivity otherwise
        val prods = p.panelProducts.toMutableList()
        if (ps.ptype) { if (Product.PTYPE !in prods.take(a.state.panelCount)) prods[a.state.activePanel.coerceIn(0, 3)] = Product.PTYPE }
        else for (i in prods.indices) if (prods[i] == Product.PTYPE) prods[i] = Product.REF
        p.panelProducts = prods
        p.layerPreset = ps.id
        a.layersChanged()
        a.productsChanged()
        a.toast("${ps.label}: ${ps.tip.lowercase(Locale.US)}. Change any switch you like.")
        if (ps.id == "tropical") a.wx.refreshTropical(force = true)
    }

    // ------------------------------------------------------------------ the Layers sheet
    /** A section with a header that collapses (remembered). */
    private fun section(a: MainActivity, col: LinearLayout, id: String, title: String, build: (LinearLayout) -> Unit) {
        val p = a.prefs
        val body = W.vCol(a)
        val collapsed = id in p.collapsedSections
        val arrow = IconView(a, if (collapsed) Icon.DOWN else Icon.UP, C.dim)
        val head = W.hRow(a).apply {
            setPadding(a.dpi(4f), a.dpi(14f), a.dpi(4f), a.dpi(6f))
            isClickable = true
        }
        head.addView(W.text(a, title.uppercase(Locale.US), 12f, C.dim, true).apply { letterSpacing = 0.08f }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(arrow, LinearLayout.LayoutParams(a.dpi(20f), a.dpi(20f)))
        head.setOnClickListener {
            val now = body.visibility == View.VISIBLE
            body.visibility = if (now) View.GONE else View.VISIBLE
            arrow.icon = if (now) Icon.DOWN else Icon.UP
            p.collapsedSections = if (now) p.collapsedSections + id else p.collapsedSections - id
        }
        col.addView(head, lp())
        build(body)
        body.visibility = if (collapsed) View.GONE else View.VISIBLE
        col.addView(body, lp())
    }

    private fun layerRow(a: MainActivity, c: LinearLayout, key: String, label: String, desc: String?) =
        c.addView(W.switchRow(a, label, desc, a.prefs.layer(key)) { on ->
            a.prefs.setLayer(key, on); if (a.prefs.layerPreset.isNotEmpty()) a.prefs.layerPreset = ""; a.layersChanged()
        })

    private fun warnRow(a: MainActivity, c: LinearLayout, key: String, label: String, desc: String? = null) =
        c.addView(W.switchRow(a, label, desc, a.prefs.warnGroup(key)) { on ->
            a.prefs.setWarnGroup(key, on); if (a.prefs.layerPreset.isNotEmpty()) a.prefs.layerPreset = ""; a.layersChanged()
        })

    /** The big "Set up the map for …" button at the top of a weather sheet. */
    private fun setupButton(a: MainActivity, col: LinearLayout, id: String, reopen: () -> Unit) {
        val ps = PRESETS.first { it.id == id }
        val on = a.prefs.layerPreset == id
        col.addView(W.button(a, if (on) "✓ Set up for ${ps.label.lowercase(Locale.US)} weather" else "Set up the map for ${ps.label.lowercase(Locale.US)} weather",
            primary = !on) { applyPreset(a, ps); reopen() }, lp())
        col.addView(W.note(a, ps.tip + ". Change any switch below afterwards."), lp(a.dpi(4f)))
    }

    /** Buttons that open the Models view on a set of maps (several panels) or one field. */
    private fun modelButtons(a: MainActivity, c: LinearLayout, sets: List<Triple<String, List<String>, Pair<String?, String?>>>,
                             singles: List<Pair<String, String>>, model: String? = null, region: String? = null) {
        for ((label, fields, mr) in sets) c.addView(W.button(a, label, primary = false) {
            a.sheets.close(); a.models.openSet(fields, mr.first, mr.second)
        }, lp(a.dpi(6f)))
        val grid = ChipGrid(a, minColDp = 100f)
        for ((fid, label) in singles) grid.addView(W.chip(a, label) { a.sheets.close(); a.models.openSet(listOf(fid), model, region) }.apply { asTile() })
        c.addView(grid, lp(a.dpi(8f)))
        c.addView(W.note(a, "These open the Models view (Radar at the top goes back). Press and hold a map to read its values."), lp(a.dpi(4f)))
    }

    /** Layers: the weather sheets first, then the layers for any weather. */
    fun layers(a: MainActivity) {
        val p = a.prefs
        val col = W.vCol(a)
        col.addView(W.text(a, "WEATHER", 12f, C.dim, true).apply { letterSpacing = 0.08f; setPadding(a.dpi(4f), 0, 0, a.dpi(6f)) })
        for ((title, sub, open) in listOf(
            Triple("Severe", "Warnings, storm reports, SPC outlooks, severe model maps (CAPE, helicity, STP)") { severe(a) },
            Triple("Winter", "Precipitation type, freezing line, snowfall, winter warnings, winter model maps") { winter(a) },
            Triple("Tropical", "Hurricanes: cones, tracks, arrival times, past storms, tropical model maps") { tropical(a) })) {
            col.addView(W.listRow(a, title, sub, "›", highlighted = p.layerPreset == title.lowercase(Locale.US)) { open() }, lp())
        }
        val clean = PRESETS.first { it.id == "clean" }
        col.addView(W.chip(a, "Clean map: just the radar, the map and warnings", p.layerPreset == "clean") { applyPreset(a, clean); layers(a) }, lp(a.dpi(6f)))

        section(a, col, "warnings", "Warnings") { c ->
            layerRow(a, c, "warnings", "NWS warnings", "Updated every 90 seconds. Earlier scans in the loop show the warnings of their own time.")
            c.addView(W.note(a, "Which kinds are shown is in Severe, Winter and Tropical."))
            c.addView(W.listRow(a, "Warning lines", "Colours (classic or NWS), width, style and the flash for new warnings", "›") { Sheets.warningLines(a) }, lp())
        }
        section(a, col, "radar_extras", "Radar display") { c ->
            c.addView(W.switchRow(a, "Colour bars", "The colour scale along the edge of each panel", p.showLegend) { p.showLegend = it; a.settingsChanged() })
            c.addView(W.switchRow(a, "Smooth radar", "Blend neighbouring gates instead of showing blocks", p.smooth) { p.smooth = it; a.displayChanged() })
            c.addView(W.switchRow(a, "Dealias velocity", "Unfold aliased velocity (BV and SRV)", p.dealias) { p.dealias = it; a.displayChanged() })
            c.addView(W.switchRow(a, "Σ Max-value trail", "The strongest value at each spot over the loaded scans – hail swaths, rotation tracks", p.trail) {
                p.trail = it; a.requestLoopFrames(); a.displayChanged()
            })
            c.addView(W.switchRow(a, "Learn mode", "Press and hold the map: plain-language notes explain the values there", p.learn) {
                p.learn = it; a.displayChanged(); if (it) a.toast("Press and hold the map to see what the colours mean")
            })
            c.addView(W.listRow(a, "Colour tables", "Built-in, or import your own .pal files", "›") { Sheets.colorTables(a) }, lp())
        }
        section(a, col, "map", "Map") { c ->
            layerRow(a, c, "counties", "Counties", null)
            layerRow(a, c, "roads", "Highways", null)
            layerRow(a, c, "cities", "Cities", null)
            layerRow(a, c, "rings", "Range rings", "Every 50 ${p.distUnits} around the radar")
            layerRow(a, c, "sites", "Radar sites", "Tap one on the map to switch to it")
        }
        a.sheets.show("Layers", col, fullHeight = true)
    }

    private fun backAction(a: MainActivity) = listOf<View>(W.iconButton(a, Icon.PREV, "Back to Layers") { layers(a) })

    // ------------------------------------------------------------------ severe
    fun severe(a: MainActivity) {
        val p = a.prefs
        val col = W.vCol(a)
        setupButton(a, col, "severe") { severe(a) }
        section(a, col, "sev_warn", "Warnings & watches") { c ->
            layerRow(a, c, "warnings", "NWS warnings", null)
            warnRow(a, c, "tornado", "Tornado")
            warnRow(a, c, "severe", "Severe thunderstorm")
            warnRow(a, c, "flood", "Flash flood")
            warnRow(a, c, "watch", "Tornado & severe watches")
            warnRow(a, c, "other", "Other", "Extreme wind, marine, snow squall, dust storm, special statements")
        }
        section(a, col, "sev_reports", "Storm reports & chasers") { c ->
            layerRow(a, c, "reports", "Storm reports", "NWS and Spotter Network reports. Tap one to read it.")
            c.addView(W.segmented(a, Prefs.REPORT_HOURS.map { "$it" to "$it h" }, "${p.reportHours}") {
                p.reportHours = it.toInt(); a.applyFeedPrefs(); a.dm.refreshFeed("reports"); a.rebuildFeeds()
            })
            val groups = W.hRow(a)
            for ((g, label) in ReportKind.GROUPS) {
                groups.addView(W.chip(a, label, p.reportGroup(g)) { ch ->
                    val on = !p.reportGroup(g); p.setReportGroup(g, on); ch.selectedState = on; a.rebuildFeeds()
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = a.dpi(6f) })
            }
            c.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(groups) }, lp(a.dpi(2f)))
            c.addView(W.switchRow(a, "Include Spotter Network reports", "Often before the NWS posts them", p.spotterReports) {
                p.spotterReports = it; a.applyFeedPrefs(); a.dm.refreshFeed("reports"); a.rebuildFeeds()
            })
            c.addView(W.listRow(a, "List of storm reports", "Newest first, near this radar", "›") { Sheets.reportList(a, a.visibleReports, "Storm reports") }, lp())
            layerRow(a, c, "chasers", "Storm chasers", "Spotter Network positions, every minute")
            c.addView(W.segmented(a, listOf("all" to "Everyone", "active" to "Active reporters"), if (p.chasersActiveOnly) "active" else "all") {
                p.chasersActiveOnly = it == "active"; a.applyFeedPrefs(); a.dm.refreshFeed("chasers")
            })
            c.addView(W.switchRow(a, "Chaser names", "When zoomed in", p.chaserNames) { p.chaserNames = it; a.rebuildFeeds() })
        }
        section(a, col, "sev_spc", "Storm Prediction Center") { c ->
            layerRow(a, c, "outlook", "Severe weather outlook", "TSTM to HIGH risk areas. Tap inside one for the chances there.")
            c.addView(W.segmented(a, listOf("1" to "Today", "2" to "Day 2", "3" to "Day 3"), "${p.outlookDay}") {
                p.outlookDay = it.toInt(); a.applyFeedPrefs(); a.rebuildSpc(); a.rebuild()
            })
            layerRow(a, c, "mcd", "Mesoscale discussions", "Blue outlines – tap one to read it")
        }
        section(a, col, "sev_models", "Severe model maps") { c ->
            modelButtons(a, c, listOf(
                Triple("Severe set: CAPE, helicity, shear, STP", listOf("cape_ml", "srh01", "shear06", "stp"), "hrrr" to null),
                Triple("Storm-scale set: radar, updraft helicity, dewpoint, SCP", listOf("refc", "uh25", "td2m", "scp"), "hrrr" to null)),
                listOf("refc" to "Sim. radar", "cape_ml" to "MLCAPE", "cape_sb" to "SBCAPE", "stp" to "STP", "scp" to "SCP",
                    "srh01" to "0–1 km SRH", "srh03" to "0–3 km SRH", "shear06" to "0–6 km shear", "uh25" to "Updraft hel.",
                    "lapse75" to "Lapse rates", "td2m" to "Dewpoint", "cin_sb" to "CIN"))
        }
        a.sheets.show("Severe", col, fullHeight = true, actions = backAction(a))
    }

    // ------------------------------------------------------------------ winter
    fun winter(a: MainActivity) {
        val p = a.prefs
        val col = W.vCol(a)
        setupButton(a, col, "winter") { winter(a) }
        section(a, col, "win_radar", "On the radar") { c ->
            c.addView(W.switchRow(a, "Precipitation type", "The active panel shows P-Type: reflectivity coloured rain / snow / sleet / freezing rain (from the HRRR)",
                Product.PTYPE in p.panelProducts.take(a.state.panelCount)) { on -> a.togglePtype(on) })
            c.addView(W.switchRow(a, "Winter colours", "A reflectivity colour table that spreads light snow over more shades", p.winterColors) {
                p.winterColors = it; a.settingsChanged()
            })
        }
        section(a, col, "win_snow", "Snow & ice") { c ->
            layerRow(a, c, "freezing", "Freezing line", "The 32 °F line at the ground for the hour of the scan (HRRR)")
            layerRow(a, c, "snowfall", "Snowfall totals", "NOAA's snowfall analysis (NOHRSC), shaded")
            c.addView(W.segmented(a, listOf("24" to "24 h", "48" to "48 h", "72" to "72 h"), "${p.snowfallHours}") {
                p.snowfallHours = it.toInt(); a.layersChanged()
            })
        }
        section(a, col, "win_alerts", "Warnings & reports") { c ->
            warnRow(a, c, "winter", "Winter & cold warnings", "Winter storm, blizzard, ice storm, freeze… shaded by county")
            c.addView(W.switchRow(a, "Snow & ice reports", "With snow totals beside them", p.reportGroup("winter") && p.layer("reports")) { on ->
                p.setReportGroup("winter", on); if (on) p.setLayer("reports", true); a.layersChanged()
            })
            layerRow(a, c, "mcd", "Mesoscale discussions", "The SPC covers heavy snow and freezing rain too")
        }
        section(a, col, "win_models", "Winter model maps") { c ->
            modelButtons(a, c, listOf(
                Triple("Winter set: type, snow, ice, temperature", listOf("ptype", "snow10", "frzr", "t2m"), null to null),
                Triple("Snow set: 10:1, model snow, depth, 850 mb", listOf("snow10", "asnow", "snod", "t850"), null to null)),
                listOf("ptype" to "P-type", "snow10" to "Snow 10:1", "asnow" to "Model snow", "frzr" to "Freezing rain",
                    "t2m" to "2 m temp", "t850" to "850 mb temp", "snod" to "Snow depth", "qpf" to "Total precip"))
        }
        a.sheets.show("Winter", col, fullHeight = true, actions = backAction(a))
    }

    // ------------------------------------------------------------------ models
    fun models(a: MainActivity) {
        val mm = a.models
        val p = a.prefs
        val col = W.vCol(a)
        col.addView(W.section(a, "Model"))
        val grid = ChipGrid(a, minColDp = 76f)
        for (m in Models.MODELS.values) grid.addView(W.chip(a, m.name, m.id == p.modelId) { mm.setModel(m.id); models(a) }.apply { asTile() })
        col.addView(grid, lp())
        col.addView(W.note(a, mm.model().long + when (mm.model().id) {
            "hrrr" -> ". The best for storms in the next 18 hours (48 h every 6 hours)."
            "nam" -> ". Out to 60 hours: storms and winter weather a day or two ahead."
            "rap" -> ". Hourly, coarser than the HRRR; out to 21 hours (51 h every 6 hours)."
            else -> ". Out to 16 days, worldwide; the only one here that reaches the tropics."
        }), lp(a.dpi(4f)))

        col.addView(W.section(a, "Run"))
        val runs = mm.runList()
        if (runs.isEmpty()) col.addView(W.note(a, if (mm.busy) "Finding the newest runs…" else "No recent runs found yet."))
        val cur = mm.run()
        for (r in runs) {
            val age = Time.age(r.timeMs)
            col.addView(W.listRow(a, "${Time.hmZ(r.timeMs).take(2)}Z  ·  ${Time.local(r.timeMs, "EEE d MMM")}",
                "${r.hours.size} hours out so far (to F${r.hours.maxOrNull() ?: 0}) · started $age", null, highlighted = r.timeMs == cur?.timeMs) {
                mm.setRun(if (r === runs.first()) 0L else r.timeMs); models(a)
            }, lp())
        }
        col.addView(W.listRow(a, "Look for newer runs", "Runs appear an hour or two after they start", null) { mm.refreshRuns(force = true); a.toast("Looking for new runs…") }, lp())

        col.addView(W.section(a, "Area"))
        val areas = ChipGrid(a, minColDp = 110f)
        for (name in Models.REGIONS.keys) {
            areas.addView(W.chip(a, name, name == (mm.geo?.region ?: p.modelRegion)) { mm.setRegion(name); a.sheets.close() }.apply { asTile() })
        }
        col.addView(areas, lp())
        col.addView(W.note(a, "Atlantic tropics uses the GFS (the other models only cover the lower 48)."), lp(a.dpi(4f)))

        col.addView(W.section(a, "Panels"))
        col.addView(W.segmented(a, listOf("1" to "One", "2" to "Two", "4" to "Four"), "${p.modelPanels}") { mm.setPanels(it.toInt()); models(a) })
        val names = p.modelFields
        for (i in 0 until p.modelPanels) {
            val fd = Models.FIELDS[names[i]] ?: continue
            col.addView(W.listRow(a, "Panel ${i + 1}: ${fd.name}", fd.group, "Change ›", highlighted = i == a.state.activePanel) {
                a.state.activePanel = i; modelFields(a)
            }, lp())
        }
        col.addView(W.slider(a, "Opacity", 30, 100, (p.modelOpacity * 100).toInt(), { "$it%" }) { mm.setOpacity(it / 100f) })
        col.addView(W.note(a, "Tap a panel to make it active, then pick its field. Press and hold the map to read values. Model data: NOAA via Amazon Web Services."), lp(a.dpi(4f)))
        a.sheets.show("Models", col, fullHeight = true)
    }

    /** Every field, grouped, with what it shows: picks the active panel's field. */
    fun modelFields(a: MainActivity) {
        val mm = a.models
        val p = a.prefs
        val panel = a.state.activePanel.coerceIn(0, p.modelPanels - 1)
        val cur = p.modelFields.getOrElse(panel) { "refc" }
        val col = W.vCol(a)
        if (p.modelPanels > 1) col.addView(W.note(a, "For panel ${panel + 1} (tap another panel on the map to change that one)."))
        for (g in Models.GROUPS) {
            col.addView(W.section(a, g))
            for (fd in Models.FIELDS.values.filter { it.group == g }) {
                col.addView(W.listRow(a, fd.name, fd.desc, if (fd.units.isNotEmpty()) fd.units else null, highlighted = fd.id == cur) {
                    a.sheets.close(); mm.setField(panel, fd.id)
                }, lp())
            }
        }
        col.addView(W.note(a, "Some fields aren't in every model (the GFS has no simulated updraft helicity, for one); the panel says so."), lp(a.dpi(6f)))
        a.sheets.show("Fields", col, fullHeight = true)
    }

    // ------------------------------------------------------------------ tropical
    fun tropical(a: MainActivity) {
        val wx = a.wx
        val p = a.prefs
        val col = W.vCol(a)
        setupButton(a, col, "tropical") { tropical(a) }
        col.addView(W.section(a, "Active storms"))
        val storms = wx.storms
        when {
            !p.layer("tropical") && storms.isEmpty() -> {}
            wx.stormsTime == 0L -> col.addView(W.note(a, wx.tropicalError ?: "Loading from the National Hurricane Center…"))
            storms.isEmpty() -> col.addView(W.note(a, "No active tropical storms or hurricanes right now (Atlantic and eastern Pacific)." +
                "\nUpdated ${Time.age(wx.stormsTime)}."))
            else -> for (s in storms.sortedByDescending { it.wind }) {
                val row = W.hRow(a)
                row.addView(View(a).apply { background = rounded(Tropical.catColor(s.wind), a.dp(4f)) }, LinearLayout.LayoutParams(a.dpi(8f), a.dpi(40f)))
                row.addView(W.listRow(a, s.title, "${Tropical.catLabel(s.wind)} · ${s.wind.toInt()} kt" + (s.pressure?.let { " · ${it.toInt()} mb" } ?: "") +
                    "\n" + (etaLine(a, s) ?: movement(s)), "›") { stormDetail(a, s) }, LinearLayout.LayoutParams(0, -2, 1f))
                col.addView(row, lp(a.dpi(2f)))
            }
        }
        section(a, col, "tro_map", "On the map") { c ->
            for ((k, label, desc) in listOf(
                Triple("tropical", "Storms, cones & tracks", "From the National Hurricane Center, with coastal watches and warnings. Tap a storm for details."),
                Triple("tropical_wind", "Wind field", "Where tropical-storm (yellow), 50 kt (orange) and hurricane-force (red) winds are now"),
                Triple("tropical_models", "Model tracks", "GFS, European, HAFS, Canadian, UK and the consensus"),
                Triple("tropical_outlook", "Outlook", "Areas to watch, with the chance of a storm forming in 2 and 7 days"),
                Triple("buoys", "Buoys", "Wind, gusts and waves from buoys and coastal stations, when zoomed in"),
                Triple("rainfall", "Rainfall totals", "Radar and gauge estimates (MRMS)"))) {
                c.addView(W.switchRow(a, label, desc, p.layer(k)) { on -> p.setLayer(k, on); p.layerPreset = ""; a.layersChanged(); wx.refreshTropical() })
            }
            c.addView(W.segmented(a, listOf("24" to "Rain 24 h", "48" to "48 h", "72" to "72 h"), "${p.rainfallHours}") {
                p.rainfallHours = it.toInt(); a.layersChanged()
            })
            warnRow(a, c, "tropical", "Hurricane & storm surge warnings", "Shaded by county")
        }
        section(a, col, "tro_models", "Tropical model maps") { c ->
            modelButtons(a, c, listOf(Triple("Tropical set (GFS): pressure, wind, moisture, rain", listOf("mslp", "wind10", "pwat", "qpf"), "gfs" to "Atlantic tropics")),
                listOf("mslp" to "Pressure & radar", "wind10" to "10 m wind", "qpf" to "Total rain", "h500" to "Steering (500 mb)"),
                model = "gfs", region = "Atlantic tropics")
        }

        col.addView(W.section(a, "Past storms"))
        col.addView(W.note(a, "Draw the track of a past hurricane (best track: HURDAT2, and this season's from the NHC)."))
        val year = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.YEAR)
        val pickRow = W.hRow(a)
        pickRow.addView(W.button(a, "Atlantic…") { pastStorms(a, "AL", year) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = a.dpi(8f) })
        pickRow.addView(W.button(a, "East Pacific…") { pastStorms(a, "EP", year) }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(pickRow, lp())

        col.addView(W.section(a, "History"))
        col.addView(W.note(a, "Every hurricane and tropical storm that passed within 100 miles, since 1950."))
        val histRow = W.hRow(a)
        histRow.addView(W.button(a, "Near me") {
            val loc = a.lastLocation
            if (loc == null) a.toast("Your location isn't known yet – tap the location button first") else history(a, loc.latitude, loc.longitude, "you")
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = a.dpi(8f) })
        histRow.addView(W.button(a, "Near the map centre") {
            val ll = a.mapCenterLatLon() ?: return@button
            history(a, ll[0], ll[1], "the map centre")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(histRow, lp())
        if (wx.history.isNotEmpty()) col.addView(W.button(a, "Clear ${if (wx.history.size == 1) wx.history[0].displayName else "${wx.history.size} past tracks"} from the map") {
            wx.history = emptyList(); tropical(a)
        }, lp(a.dpi(8f)))
        a.sheets.show("Tropical", col, fullHeight = true, actions = backAction(a))
    }

    private fun movement(s: Tropical.Storm): String =
        if (s.moveDir != null && s.moveKt != null && s.moveKt > 0) "Moving ${Geo.compass(s.moveDir)} at ${s.moveKt.toInt()} kt" else "Stationary"

    /** "Closest to you Thu 3 PM, 40 mi" (or null without a location). */
    private fun etaLine(a: MainActivity, s: Tropical.Storm): String? {
        val loc = a.lastLocation ?: return null
        val ar = Tropical.arrival(s, loc.latitude, loc.longitude) ?: return null
        return "Closest to you ${Time.local(ar.closestMs, "EEE h a")}, ${Geo.distText(ar.closestKm, a.prefs.distUnits)}" +
            (ar.tsArrive?.let { " · TS winds from ${Time.local(it, "EEE h a")}" } ?: "")
    }

    fun stormDetail(a: MainActivity, s: Tropical.Storm) {
        val col = W.vCol(a)
        col.addView(W.text(a, s.title, 20f, Tropical.catColor(s.wind).let { if (C.dark) it else C.text }, true), lp())
        col.addView(W.text(a, "${Tropical.catLabel(s.wind)}: ${s.wind.toInt()} kt (${(s.wind * 1.15078).toInt()} mph)" +
            (s.pressure?.let { ", ${it.toInt()} mb" } ?: ""), 15f, C.text), lp(a.dpi(6f)))
        col.addView(W.text(a, movement(s) + "  ·  " + String.format(Locale.US, "%.1f°N %.1f°W", s.lat, -s.lon), 14f, C.dim), lp(a.dpi(4f)))
        if (s.updatedMs > 0) col.addView(W.text(a, "Advisory ${s.advisory} · ${Time.local(s.updatedMs, "EEE h:mm a")} (${Time.age(s.updatedMs)})", 13f, C.dim), lp(a.dpi(4f)))
        col.addView(W.button(a, "Show on map", primary = true) { a.sheets.close(); a.goToPoint(s.lat, s.lon) }, lp(a.dpi(12f)))

        col.addView(W.section(a, "Where you are"))
        val loc = a.lastLocation
        if (loc == null) col.addView(W.note(a, "Turn on your location (the button at the bottom) to see when this storm could reach you."))
        else {
            val ar = Tropical.arrival(s, loc.latitude, loc.longitude)
            if (ar == null) col.addView(W.note(a, "No forecast track yet."))
            else {
                val u = a.prefs.distUnits
                val lines = ArrayList<String>()
                lines.add("Closest: ${Geo.distText(ar.closestKm, u)} ${Geo.compass(ar.closestDir)} of you, ${Time.local(ar.closestMs, "EEE h a")}" +
                    (ar.closestWind?.let { " (${it.toInt()} kt then)" } ?: ""))
                lines.add(if (ar.tsArrive != null) "Tropical-storm-force winds (39 mph+): ${Time.local(ar.tsArrive, "EEE h a")} to ${Time.local(ar.tsLeave ?: ar.tsArrive, "EEE h a")}"
                    else "Tropical-storm-force winds aren't forecast to reach you.")
                ar.huArrive?.let { lines.add("Hurricane-force winds (74 mph+) from ${Time.local(it, "EEE h a")}") }
                for (l in lines) col.addView(W.text(a, l, 14.5f, C.text).apply { setLineSpacing(0f, 1.15f) }, lp(a.dpi(4f)))
                col.addView(W.note(a, "From the NHC's official forecast track and wind radii, between forecast times. The cone shows how far the centre may stray: follow your local officials."), lp(a.dpi(6f)))
            }
        }
        if (s.forecast.size > 1) {
            col.addView(W.section(a, "Forecast"))
            for (q in s.forecast.drop(1)) {
                col.addView(W.text(a, "${Time.local(q.timeMs, "EEE h a")}  ·  ${Tropical.catLabel(q.wind)}, ${q.wind?.toInt() ?: 0} kt", 14f, C.text), lp(a.dpi(3f)))
            }
        }
        if (s.links.isNotEmpty()) {
            col.addView(W.section(a, "From the NHC"))
            for ((label, url) in s.links) col.addView(W.listRow(a, label, url.removePrefix("https://"), "↗") {
                try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { a.toast("No browser to open it") }
            }, lp())
        }
        a.sheets.show("Storm", col, fullHeight = true)
    }

    fun buoyDetail(a: MainActivity, b: Tropical.Buoy) {
        val col = W.vCol(a)
        val lines = ArrayList<String>()
        if (b.timeMs > 0) lines.add("Observed ${Time.local(b.timeMs)} (${Time.age(b.timeMs)})")
        b.wspdKt?.let { lines.add("Wind ${Geo.compass(b.wdir ?: 0.0)} ${it.toInt()} kt" + (b.gustKt?.let { g -> ", gusts ${g.toInt()} kt" } ?: "")) }
        b.pres?.let { lines.add(String.format(Locale.US, "Pressure %.1f mb", it)) }
        b.waveFt?.let { lines.add("Waves ${it.toInt()} ft") }
        b.airF?.let { lines.add("Air ${it.toInt()} °F") }
        b.waterF?.let { lines.add("Water ${it.toInt()} °F") }
        for (l in lines) col.addView(W.text(a, l, 15f, C.text), lp(a.dpi(4f)))
        col.addView(W.listRow(a, "Station page", "ndbc.noaa.gov", "↗") {
            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.ndbc.noaa.gov/station_page.php?station=${b.id}"))) } catch (_: Exception) {}
        }, lp(a.dpi(8f)))
        a.sheets.show("Buoy ${b.id}", col)
    }

    private fun pastStorms(a: MainActivity, basin: String, thisYear: Int) {
        val col = W.vCol(a)
        col.addView(W.note(a, "Loading the best tracks…"))
        a.sheets.show(if (basin == "AL") "Atlantic storms" else "East Pacific storms", col, fullHeight = true)
        a.runInBackground({
            val recent = try { a.wx.recentSeason(basin.lowercase(), thisYear) } catch (_: Exception) { emptyList() }
            val all = a.wx.pastStorms(basin)
            recent + all.filter { it.year >= 1950 && it.year != thisYear }.sortedByDescending { it.year }
        }) { list, err ->
            col.removeAllViews()
            if (list == null) { col.addView(W.note(a, "Couldn't load them: ${err ?: "no connection"}")); return@runInBackground }
            val years = list.map { it.year }.distinct().sortedDescending()
            val yearRow = W.hRow(a)
            val listCol = W.vCol(a)
            val chips = ArrayList<W.Chip>()
            fun show(y: Int) {
                listCol.removeAllViews()
                for (ps in list.filter { it.year == y }.sortedByDescending { it.peak }) {
                    listCol.addView(W.listRow(a, ps.title, "${ps.points.size} positions" + if (ps.landfalls.isNotEmpty()) " · ${ps.landfalls.size} landfall(s)" else "", "›") {
                        a.wx.history = listOf(ps)
                        if (!a.prefs.layer("tropical")) { a.prefs.setLayer("tropical", true); a.layersChanged() }
                        a.sheets.close()
                        val focus = ps.landfalls.firstOrNull() ?: ps.points.maxByOrNull { it.wind ?: 0.0 }
                        focus?.let { a.goToPoint(it.lat, it.lon) }
                        a.toast("${ps.displayName} ${ps.year}: its best track, coloured by strength")
                    }, lp())
                }
            }
            for (y in years.take(60)) {
                val c = W.chip(a, "$y", y == years.first()) { me -> chips.forEach { it.selectedState = it === me }; show(y) }
                chips.add(c)
                yearRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = a.dpi(6f) })
            }
            col.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(yearRow) }, lp())
            col.addView(listCol, lp(a.dpi(8f)))
            years.firstOrNull()?.let { show(it) }
        }
    }

    private fun history(a: MainActivity, lat: Double, lon: Double, what: String) {
        a.toast("Looking up past storms near $what…")
        a.runInBackground({
            val all = a.wx.pastStorms("AL") + (try { a.wx.pastStorms("EP") } catch (_: Exception) { emptyList() })
            Tropical.tracksNear(all, lat, lon, 161.0)
        }) { near, err ->
            if (near == null) { a.toast("Couldn't load past storms: ${err ?: "no connection"}"); return@runInBackground }
            if (near.isEmpty()) { a.toast("No tropical storms passed within 100 miles of $what since 1950"); return@runInBackground }
            a.wx.history = near.map { it.first }
            if (!a.prefs.layer("tropical")) { a.prefs.setLayer("tropical", true); a.layersChanged() }
            a.sheets.close()
            val hurricanes = near.count { it.first.peak >= 64 }
            a.toast("${near.size} storms passed within 100 miles of $what since 1950 ($hurricanes were hurricanes at some point)")
        }
    }
}
