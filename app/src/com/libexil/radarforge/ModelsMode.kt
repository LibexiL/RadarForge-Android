package com.libexil.radarforge

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.libexil.radarforge.core.Geo
import com.libexil.radarforge.core.MapRaster
import com.libexil.radarforge.core.Models
import com.libexil.radarforge.core.ProjectedCities
import com.libexil.radarforge.core.ProjectedLayer
import com.libexil.radarforge.core.Raster
import com.libexil.radarforge.core.RasterFrame
import com.libexil.radarforge.core.Time
import com.libexil.radarforge.gl.LayerDraw
import com.libexil.radarforge.gl.PanelDraw
import com.libexil.radarforge.gl.Scene
import com.libexil.radarforge.ui.C
import com.libexil.radarforge.ui.Icon
import com.libexil.radarforge.ui.IconView
import com.libexil.radarforge.ui.OverlayView
import com.libexil.radarforge.ui.W
import com.libexil.radarforge.ui.dpi
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The Models view: HRRR, NAM 3 km, RAP and GFS maps like Pivotal Weather's, on the same map as the radar.
 * Up to four panels, each with its own field; one model, run and forecast hour for all of them.
 */
class ModelsMode(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newFixedThreadPool(2) { r -> Thread(r, "rf-models").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val p get() = a.prefs

    // ---------------------------------------------------------------- the map for a region
    class ModelGeo(val region: String, val proj: Geo.Aeqd, val layers: Map<String, ProjectedLayer>, val cities: ProjectedCities,
                   val widthKm: Float, val heightKm: Float)
    var geo: ModelGeo? = null
        private set
    private var geoLoading: String? = null

    private fun ensureGeo() {
        val name = p.modelRegion.takeIf { it in Models.REGIONS } ?: "CONUS"
        if (geo?.region == name || geoLoading == name) return
        val bm = a.basemap ?: return
        geoLoading = name
        exec.execute {
            val (lat, lon, w) = Models.REGIONS.getValue(name)
            val proj = Geo.Aeqd(lat, lon)
            val reach = w * 0.85
            val layers = listOf("countries", "states", "lakes", "counties").associateWith { ProjectedLayer.project(bm.layers.getValue(it), proj, reach) }
            val cities = ProjectedCities.project(bm.cities, proj, reach, minPop = 20_000)
            val g = ModelGeo(name, proj, layers, cities, w.toFloat(), (w * 0.68).toFloat())
            main.post {
                geoLoading = null
                if (p.modelRegion != name && !(name == "CONUS" && p.modelRegion !in Models.REGIONS)) return@post
                geo = g
                pictures.clear()
                if (active) { applyOverlayGeo(); fitRegion(); request() }
            }
        }
    }

    // ---------------------------------------------------------------- runs
    private val runs = HashMap<String, List<Models.Run>>()
    private var runsLoading = HashSet<String>()
    private var runsError: String? = null
    private var runsTime = HashMap<String, Long>()

    fun model(): Models.ModelDef = Models.MODELS[p.modelId] ?: Models.MODELS.getValue("hrrr")
    fun runList(): List<Models.Run> = runs[model().id] ?: emptyList()
    fun run(): Models.Run? = runList().let { l -> l.firstOrNull { it.timeMs == p.modelRun } ?: l.firstOrNull() }

    fun refreshRuns(force: Boolean = false) {
        val m = model()
        if (m.id in runsLoading) return
        if (!force && runs[m.id] != null && System.currentTimeMillis() - (runsTime[m.id] ?: 0L) < 8 * 60_000L) return
        runsLoading.add(m.id)
        updateBar()
        exec.execute {
            val r = try { Models.latestRuns(m, 4) } catch (e: Exception) { RfLog.w("model runs: $e"); null }
            main.post {
                runsLoading.remove(m.id)
                if (r != null && r.isNotEmpty()) { runs[m.id] = r; runsTime[m.id] = System.currentTimeMillis(); runsError = null }
                else if (runs[m.id] == null) runsError = "Couldn't find a recent ${m.name} run – check your connection."
                clampHour()
                if (active) { request(); updateBar() }
            }
        }
    }

    /** Keeps the forecast hour inside what the run has. */
    private fun clampHour() {
        val hrs = run()?.hours ?: return
        if (hrs.isEmpty()) return
        if (p.modelFh !in hrs) p.modelFh = hrs.minByOrNull { Math.abs(it - p.modelFh) } ?: hrs.first()
    }

    // ---------------------------------------------------------------- pictures
    class Picture(
        val key: String, val fid: String, val validMs: Long, val note: String, val frame: RasterFrame, val raster: Raster,
        val values: FloatArray, val lines: ProjectedLayer?, val lineColor: Int, val zero: ProjectedLayer?,
        val labels: List<OverlayView.MapLabel>, val barbs: FloatArray?,
    ) {
        fun valueAt(x: Float, y: Float): Float {
            val c = ((x - frame.x0) / frame.cellKm).toInt(); val r = ((frame.y1 - y) / frame.cellKm).toInt()
            return if (r in 0 until frame.h && c in 0 until frame.w) values[r * frame.w + c] else Float.NaN
        }
    }

    private val pictures = object : LinkedHashMap<String, Picture>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Picture>?) = size > 7
    }
    private val pending = HashSet<String>()
    private val errors = HashMap<String, String>()

    private fun keyOf(fid: String, fh: Int): String? {
        val r = run() ?: return null
        val g = geo ?: return null
        return "${model().id}/${r.timeMs}/$fh/$fid/${g.region}"
    }

    /** Pictures for the panels on screen, then the next hours (so stepping and playing don't wait). */
    fun request() {
        if (!active) return
        val r = run() ?: return
        geo ?: return
        val fh = p.modelFh
        val fields = p.modelFields.take(p.modelPanels)
        for (f in fields) want(f, fh)
        val hrs = r.hours
        val i = hrs.indexOf(fh)
        if (i >= 0 && fields.all { keyOf(it, fh)?.let { k -> k in pictures || k in errors } == true }) {
            for (d in 1..2) hrs.getOrNull(i + d)?.let { h -> for (f in fields) want(f, h) }
        }
    }

    private fun want(fid: String, fh: Int) {
        val key = keyOf(fid, fh) ?: return
        if (key in pictures || key in pending || key in errors) return
        val m = model(); val r = run() ?: return; val g = geo ?: return
        val refTable = a.palettes.table("REF")
        pending.add(key)
        a.runOnUiThread { updateBar() }
        exec.execute {
            val pic = try { make(key, m, r.timeMs, fh, fid, g, refTable, r.hours) } catch (e: Throwable) {
                val msg = when (e) {
                    is Models.Unavailable -> e.message ?: "not available"
                    is OutOfMemoryError -> { Models.clearMemory(); "not enough memory – try fewer panels" }
                    else -> "couldn't load (${e.message ?: e.javaClass.simpleName})"
                }
                main.post { pending.remove(key); errors[key] = msg; if (active) { a.rebuild(); updateBar() } }
                null
            }
            if (pic != null) main.post {
                pending.remove(key)
                pictures[key] = pic
                Models.prune()
                if (active) { a.rebuild(); updateBar(); request() }
            }
        }
    }

    private fun make(key: String, m: Models.ModelDef, run: Long, fh: Int, fid: String, g: ModelGeo,
                     refTable: com.libexil.radarforge.core.ColorTable, hours: List<Int>): Picture {
        val fd = Models.FIELDS.getValue(fid)
        val f = try { Models.compute(m.id, run, fh, fid) } catch (e: Models.Unavailable) {
            if (fd.accum != null) Models.accumulate(m.id, run, fh, fid, hours) else throw e
        }
        val frame = RasterFrame.around(g.proj, g.widthKm * 1.15f, g.heightKm * 1.3f, maxOf(m.resKm * 0.85f, 2.5f), 900)
        val idx = MapRaster.cachedIndex(f.grid, frame)
        val vals = MapRaster.resample(f.grid, f.value, idx, !fd.categorical)
        val img = if (fd.palette == "REF") MapRaster.colorizeTable(vals, refTable) else MapRaster.colorize(vals, fd.stops!!)
        var lines: ProjectedLayer? = null
        var labels: List<OverlayView.MapLabel> = emptyList()
        var lineColor = 0
        fd.contour?.let { ct ->
            val src = f.extra[ct.input] ?: return@let
            val v = MapRaster.resample(f.grid, src, idx, true)
            val cl = MapRaster.contours(v, frame, ct.interval)
            lines = MapRaster.layerOf(cl.map { it.pts })
            lineColor = ct.color
            val labelColor = if ((ct.color and 0xffffff) < 0x404040) 0xffffffff.toInt() else ct.color   // drawn with a dark halo
            labels = MapRaster.labelSpots(cl, frame.cellKm * 25, g.widthKm / 14).map {
                OverlayView.MapLabel(String.format(Locale.US, "%.0f", it[2]), labelColor, it[0], it[1])
            }
        }
        val zero = fd.zeroLine?.let { z -> MapRaster.layerOf(MapRaster.contours(vals, frame, 1f, only = z).map { it.pts }) }
        val barbs = if (fd.barbs) {
            val u = f.extra["u"]; val v = f.extra["v"]
            if (u != null && v != null) MapRaster.barbs(MapRaster.resample(f.grid, u, idx, true), MapRaster.resample(f.grid, v, idx, true),
                frame, maxOf(g.widthKm / 55, m.resKm * 2)) else null
        } else null
        val raster = Raster(key, frame.w, frame.h, img, frame.x0, frame.y0, frame.x1, frame.y1, !fd.categorical)
        return Picture(key, fid, f.validMs, f.note, frame, raster, vals, lines, lineColor, zero, labels, barbs)
    }

    // ---------------------------------------------------------------- the scene
    var active = false
        private set

    /** Builds what's drawn: called by MainActivity.rebuild() while the Models view is on. */
    fun rebuild() {
        val g = geo
        ensureGeo()
        val d = a.resources.displayMetrics.density
        val layers = ArrayList<LayerDraw>()
        if (g != null) {
            fun add(name: String, color: Int, w: Float, maxKm: Float = Float.MAX_VALUE) { g.layers[name]?.let { layers.add(LayerDraw(it, color, w * d, maxKm)) } }
            if (p.layer("counties")) add("counties", C.counties, 0.7f, 900f)
            add("lakes", C.lakes, 0.8f)
            add("countries", C.countries, 1.5f)
            add("states", C.states, 1.4f)
        }
        val panels = ArrayList<PanelDraw>()
        val infos = ArrayList<OverlayView.PanelInfo>()
        val m = model()
        val r = run()
        val fh = p.modelFh
        val refTable = a.palettes.table("REF")
        for (i in 0 until p.modelPanels) {
            val fid = p.modelFields.getOrElse(i) { "refc" }
            val fd = Models.FIELDS[fid] ?: Models.FIELDS.getValue("refc")
            val key = keyOf(fid, fh)
            var pic = key?.let { pictures[it] }
            var stale = false
            if (pic == null) {
                // keep showing this panel's last picture while the new one loads (no flashing to empty)
                pic = pictures.values.lastOrNull { it.fid == fid && it.key.startsWith("${m.id}/") && it.key.endsWith("/${g?.region}") }
                stale = pic != null
            }
            val pd = ArrayList<LayerDraw>()
            if (pic != null) {
                // dark height lines turn light on a dark map, or they'd vanish where nothing is shaded
                val lc = if (C.dark && (pic.lineColor and 0xffffff) < 0x404040) 0xffdcdcdc.toInt() else pic.lineColor
                pic.lines?.let { pd.add(LayerDraw(it, lc, 1.3f * d)) }
                pic.zero?.let { pd.add(LayerDraw(it, 0xff3a6dff.toInt(), 2.0f * d, halo = true)) }
            }
            panels.add(PanelDraw(null, refTable, false, if (pic != null) listOf(pic.raster.withAlpha(p.modelOpacity)) else emptyList(), pd))
            val title = "${m.name}  ${fd.name}"
            val sub = if (r != null) {
                val valid = r.timeMs + fh * Models.HOUR
                "${Time.local(valid, "EEE h a")} · F${String.format(Locale.US, "%02d", fh)} · ${Time.hmZ(r.timeMs).take(2)}Z run"
            } else if (runsLoading.isNotEmpty()) "Finding the newest run…" else runsError ?: ""
            val note = when {
                key != null && key in errors -> errors[key]
                key != null && key in pending -> if (stale) "loading F${String.format(Locale.US, "%02d", fh)}…" else "loading…"
                pic?.note?.isNotEmpty() == true -> pic.note
                else -> null
            }
            val legend = if (fd.palette == "REF") null else
                OverlayView.Legend(fd.stops!!, fd.units, if (fd.id == "ptype") Models.PTYPE_NAMES.mapIndexed { k, n -> (k * 100 + 1) to n } else null, fd.decimals)
            val thePic = pic
            infos.add(OverlayView.PanelInfo(com.libexil.radarforge.core.Product.REF, null, refTable, title, sub, note,
                legend = legend, readout = { km -> readout(fd, thePic, km) }, barbs = pic?.barbs, barbProj = g?.proj,
                labels = pic?.labels ?: emptyList()))
        }
        a.renderer.scene = Scene(C.mapBg, C.mapGap, layers, emptyList(), panels, emptyList())
        a.overlay.panels = infos
        a.overlay.dataTimeMs = r?.let { it.timeMs + fh * Models.HOUR } ?: 0L
        a.overlay.invalidate()
        a.requestRender()
    }

    private fun readout(fd: Models.FieldDef, pic: Picture?, km: FloatArray): List<String> {
        val g = geo ?: return emptyList()
        val ll = g.proj.inverse(km[0].toDouble(), km[1].toDouble())
        val v = pic?.valueAt(km[0], km[1]) ?: Float.NaN
        val value = if (pic == null) "Loading…" else if (v.isNaN()) "${fd.name}: none" else Models.fmtValue(fd, v)
        val where = Geo.latLonText(ll[0], ll[1])
        val valid = pic?.let { "Valid ${Time.local(it.validMs, "EEE h:mm a")}" }
        return listOfNotNull(value, fd.name, valid, where)
    }

    // ---------------------------------------------------------------- switching views
    private var radarCx = 0f; private var radarCy = 0f; private var radarScale = 1.6f; private var radarPanels = 1

    fun enter() {
        if (active) return
        active = true
        radarCx = a.state.cx; radarCy = a.state.cy; radarScale = a.state.scale; radarPanels = a.state.panelCount
        a.state.panelCount = p.modelPanels
        a.state.activePanel = a.state.activePanel.coerceIn(0, p.modelPanels - 1)
        a.overlay.resetTools()
        a.overlay.hideInspector()
        geo?.let { applyOverlayGeo() }
        ensureGeo()
        if (geo != null) fitRegion()
        refreshRuns()
        bar.visibility = View.VISIBLE
        updateBar()
        request()
        a.rebuild()
        main.removeCallbacks(runTick)
        main.postDelayed(runTick, 8 * 60_000L)
    }

    fun exit() {
        if (!active) return
        active = false
        setPlaying(false)
        main.removeCallbacks(runTick)
        bar.visibility = View.GONE
        a.state.panelCount = radarPanels
        a.state.activePanel = a.state.activePanel.coerceIn(0, radarPanels - 1)
        a.state.minScale = 0.12f
        a.state.limitKm = 2500f
        a.state.cx = radarCx; a.state.cy = radarCy; a.state.scale = radarScale
        a.overlay.resetTools()
        a.overlay.hideInspector()
        // hand the overlay back to the radar's projection
        a.restoreRadarOverlay()
    }

    private val runTick = object : Runnable {
        override fun run() {
            if (!active) return
            refreshRuns(force = true)
            main.postDelayed(this, 8 * 60_000L)
        }
    }

    private fun applyOverlayGeo() {
        val g = geo ?: return
        a.overlay.proj = g.proj
        a.overlay.cities = g.cities
        a.overlay.sites = emptyList(); a.overlay.siteXY = FloatArray(0)
        a.overlay.alerts = emptyList(); a.overlay.reports = emptyList(); a.overlay.chasers = emptyList()
        a.overlay.mcds = emptyList(); a.overlay.outlookLabels = emptyList()
        a.overlay.decor = emptyList()
        a.overlay.locationKm = a.lastLocation?.let { loc -> FloatArray(2).also { g.proj.forward(loc.latitude, loc.longitude, it, 0) } }
        a.state.limitKm = maxOf(g.widthKm, g.heightKm) * 0.7f
        a.state.minScale = 0.05f
    }

    /** Fits the whole region in the panels. */
    fun fitRegion() {
        val g = geo ?: return
        val r = a.state.panelRects().firstOrNull() ?: return
        a.state.cx = 0f; a.state.cy = 0f
        a.state.scale = minOf(r.width() / g.widthKm, r.height() / g.heightKm).coerceIn(a.state.minScale, a.state.maxScale)
        a.overlay.invalidate()
        a.requestRender()
    }

    // ---------------------------------------------------------------- user actions
    fun setModel(id: String) {
        if (id == p.modelId) return
        p.modelId = id
        p.modelRun = 0L
        setPlaying(false)
        errors.clear()
        // the GFS reaches the tropics; the 3 km models only the lower 48
        if (id != "gfs" && p.modelRegion == "Atlantic tropics") p.modelRegion = "CONUS"
        refreshRuns()
        clampHour()
        changed()
    }

    fun setRun(t: Long) { p.modelRun = t; errors.clear(); clampHour(); changed() }

    fun setRegion(name: String) {
        if (name == p.modelRegion) return
        p.modelRegion = name
        if (name == "Atlantic tropics" && p.modelId != "gfs") { p.modelId = "gfs"; p.modelRun = 0L; refreshRuns() }
        errors.clear()
        ensureGeo()
        changed()
    }

    fun setField(panel: Int, fid: String) {
        val l = p.modelFields.toMutableList()
        l[panel.coerceIn(0, 3)] = fid
        p.modelFields = l
        changed()
        a.showHint("model_inspect", "Press and hold the map to read the value at a spot.")
    }

    fun setPanels(n: Int) {
        p.modelPanels = n
        a.state.panelCount = n
        a.state.activePanel = a.state.activePanel.coerceIn(0, n - 1)
        panelsBtn.icon = when (n) { 2 -> Icon.PANELS2; 4 -> Icon.PANELS4; else -> Icon.PANELS1 }
        changed()
    }

    fun cyclePanels() = setPanels(when (p.modelPanels) { 1 -> 2; 2 -> 4; else -> 1 })

    fun setOpacity(f: Float) { p.modelOpacity = f; a.rebuild() }

    fun step(d: Int) {
        val hrs = run()?.hours ?: return
        if (hrs.isEmpty()) return
        val i = hrs.indexOf(p.modelFh).let { if (it < 0) 0 else it }
        p.modelFh = hrs[(i + d).coerceIn(0, hrs.size - 1)]
        changed()
    }

    fun goToIndex(i: Int) {
        val hrs = run()?.hours ?: return
        p.modelFh = hrs.getOrNull(i) ?: return
        changed()
    }

    private fun changed() {
        request()
        updateBar()
        a.rebuild()
    }

    // play through the forecast hours, waiting for each picture
    private var playing = false
    private val playTick = object : Runnable {
        override fun run() {
            if (!playing || !active) return
            val hrs: List<Int> = this@ModelsMode.run()?.hours ?: return
            val fields = p.modelFields.take(p.modelPanels)
            val ready = fields.all { f -> keyOf(f, p.modelFh)?.let { it in pictures || it in errors } == true }
            if (ready) {
                val i = hrs.indexOf(p.modelFh)
                p.modelFh = if (i < 0 || i >= hrs.size - 1) hrs.first() else hrs[i + 1]
                changed()
            }
            main.postDelayed(this, if (hrs.indexOf(p.modelFh) == hrs.size - 1) 1500L else 600L)
        }
    }

    fun setPlaying(on: Boolean) {
        playing = on
        if (::playBtn.isInitialized) playBtn.icon = if (on) Icon.PAUSE else Icon.PLAY
        main.removeCallbacks(playTick)
        if (on) main.postDelayed(playTick, 400L)
    }

    // ---------------------------------------------------------------- the bottom bar
    lateinit var bar: LinearLayout
        private set
    private lateinit var fieldRow: LinearLayout
    private val fieldChips = HashMap<String, W.Chip>()
    private lateinit var modelChip: W.Chip
    private lateinit var timeText: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: IconView
    private lateinit var panelsBtn: IconView

    /** The fields most people want, one tap away (the rest are under "All fields"). */
    val QUICK_FIELDS = listOf("refc", "mslp", "cape_ml", "stp", "srh01", "shear06", "uh25", "t2m", "td2m", "wind10", "qpf", "ptype",
        "snow10", "h500", "w250")

    fun buildBar(): LinearLayout {
        bar = W.vCol(a).apply { visibility = View.GONE; setPadding(0, a.dpi(2f), 0, 0) }
        fieldRow = W.hRow(a).apply { setPadding(a.dpi(8f), a.dpi(3f), a.dpi(8f), a.dpi(3f)) }
        fieldRow.addView(W.chip(a, "All fields ›") { SheetsWx.modelFields(a) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = a.dpi(6f) })
        for (fid in QUICK_FIELDS) {
            val fd = Models.FIELDS.getValue(fid)
            val chip = W.chip(a, shortName(fd)) { setField(a.state.activePanel, fid) }
            fieldChips[fid] = chip
            fieldRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = a.dpi(6f) })
        }
        bar.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(fieldRow) }, LinearLayout.LayoutParams(-1, -2))
        val row = W.hRow(a).apply { setPadding(a.dpi(6f), 0, a.dpi(4f), 0) }
        modelChip = W.chip(a, "HRRR ▾") { SheetsWx.models(a) }
        row.addView(modelChip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = a.dpi(2f) })
        row.addView(W.iconButton(a, Icon.PREV, "Previous forecast hour") { setPlaying(false); step(-1) }.apply {
            layoutParams = LinearLayout.LayoutParams(a.dpi(40f), a.dpi(40f))
        })
        timeText = W.text(a, "", 13f, C.text, true).apply { gravity = Gravity.CENTER; minWidth = a.dpi(96f); maxLines = 2 }
        row.addView(timeText)
        row.addView(W.iconButton(a, Icon.NEXT, "Next forecast hour") { setPlaying(false); step(+1) }.apply {
            layoutParams = LinearLayout.LayoutParams(a.dpi(40f), a.dpi(40f))
        })
        row.addView(W.weightSpace(a))
        playBtn = W.iconButton(a, Icon.PLAY, "Play through the forecast") { setPlaying(!playing) }
        row.addView(playBtn)
        panelsBtn = W.iconButton(a, when (p.modelPanels) { 2 -> Icon.PANELS2; 4 -> Icon.PANELS4; else -> Icon.PANELS1 }, "Panels") { cyclePanels() }
        row.addView(panelsBtn)
        row.addView(W.iconButton(a, Icon.LAYERS, "Model, run, area and fields") { SheetsWx.models(a) })
        bar.addView(row, LinearLayout.LayoutParams(-1, -2))
        seek = SeekBar(a).apply {
            progressTintList = ColorStateList.valueOf(C.accent)
            thumbTintList = ColorStateList.valueOf(C.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    setPlaying(false)
                    goToIndex(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        bar.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = a.dpi(2f) })
        return bar
    }

    fun shortName(fd: Models.FieldDef): String = when (fd.id) {
        "refc" -> "Sim. radar"; "mslp" -> "Radar + MSLP"; "cape_ml" -> "MLCAPE"; "cape_sb" -> "SBCAPE"; "cape_mu" -> "MUCAPE"
        "stp" -> "STP"; "scp" -> "SCP"; "srh01" -> "0–1 km SRH"; "srh03" -> "0–3 km SRH"; "shear06" -> "0–6 km shear"
        "uh25" -> "Updraft hel."; "t2m" -> "Temp"; "td2m" -> "Dewpoint"; "wind10" -> "Wind"; "qpf" -> "Total precip"
        "ptype" -> "P-type"; "snow10" -> "Snow 10:1"; "h500" -> "500 mb"; "w250" -> "Jet 250 mb"; else -> fd.name
    }

    fun updateBar() {
        if (!::bar.isInitialized) return
        val m = model()
        val r = run()
        modelChip.text = if (r != null) "${m.name} ${Time.hmZ(r.timeMs).take(2)}Z ▾" else "${m.name} ▾"
        val cur = p.modelFields.getOrElse(a.state.activePanel) { "refc" }
        for ((fid, c) in fieldChips) c.selectedState = fid == cur
        val hrs = r?.hours ?: emptyList()
        val fh = p.modelFh
        seek.max = maxOf(0, hrs.size - 1)
        seek.progress = hrs.indexOf(fh).coerceAtLeast(0)
        timeText.text = if (r != null) "F${String.format(Locale.US, "%02d", fh)}\n${Time.local(r.timeMs + fh * Models.HOUR, "EEE h a")}"
            else if (runsLoading.isNotEmpty()) "Finding\nnewest run…" else "No run"
        a.setModelTitle(m.name, statusLine())
    }

    fun statusLine(): String {
        val r = run()
        val busy = pending.isNotEmpty() || runsLoading.isNotEmpty()
        return when {
            r == null && runsError != null -> runsError!!
            r == null -> "Finding the newest run…"
            else -> "${Time.hmZ(r.timeMs).take(2)}Z run · ${geo?.region ?: p.modelRegion}" + if (busy) " · loading…" else ""
        }
    }

    val busy: Boolean get() = pending.isNotEmpty() || runsLoading.isNotEmpty()

    fun runsFor(id: String) = runs[id] ?: emptyList()

    fun shutdown() { exec.shutdownNow() }
}
