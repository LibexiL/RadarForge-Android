package com.libexil.radarforge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.libexil.radarforge.core.Alert
import com.libexil.radarforge.core.Alerts
import com.libexil.radarforge.core.Basemap
import com.libexil.radarforge.core.ColorTable
import com.libexil.radarforge.core.Field
import com.libexil.radarforge.core.Geo
import com.libexil.radarforge.core.Product
import com.libexil.radarforge.core.ProjectedCities
import com.libexil.radarforge.core.ProjectedLayer
import com.libexil.radarforge.core.RenderData
import com.libexil.radarforge.core.Site
import com.libexil.radarforge.core.Sites
import com.libexil.radarforge.core.Tilt
import com.libexil.radarforge.core.Tilts
import com.libexil.radarforge.core.Time
import com.libexil.radarforge.core.Volume
import com.libexil.radarforge.data.DataManager
import com.libexil.radarforge.data.Palettes
import com.libexil.radarforge.data.Prefs
import com.libexil.radarforge.gl.LayerDraw
import com.libexil.radarforge.gl.MapRenderer
import com.libexil.radarforge.gl.PanelDraw
import com.libexil.radarforge.gl.Scene
import com.libexil.radarforge.ui.C
import com.libexil.radarforge.ui.Icon
import com.libexil.radarforge.ui.IconView
import com.libexil.radarforge.ui.MapState
import com.libexil.radarforge.ui.OverlayView
import com.libexil.radarforge.ui.SheetHost
import com.libexil.radarforge.ui.W
import com.libexil.radarforge.ui.dp
import com.libexil.radarforge.ui.dpi
import com.libexil.radarforge.ui.ripple
import com.libexil.radarforge.ui.rounded
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity(), DataManager.Listener, OverlayView.Callbacks {

    // ---------------------------------------------------------------- state
    lateinit var prefs: Prefs
    lateinit var palettes: Palettes
    val state = MapState()
    private lateinit var glView: GLSurfaceView
    lateinit var renderer: MapRenderer
    lateinit var overlay: OverlayView
    lateinit var sheets: SheetHost
    lateinit var dm: DataManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rf-geo").apply { isDaemon = true } }

    var basemap: Basemap? = null
    var sites: List<Site> = emptyList()
    var site: Site? = null

    /** Map data projected around the current radar. */
    class SiteGeo(val site: Site, val proj: Geo.Aeqd, val layers: Map<String, ProjectedLayer>,
                  val cities: ProjectedCities, val siteXY: FloatArray)
    var geo: SiteGeo? = null
    private var rings: ProjectedLayer? = null
    private var alertLayers: List<LayerDraw> = emptyList()
    var visibleAlerts: List<Alert> = emptyList()

    var tilts: List<Tilt> = emptyList()
    // big enough for every loop frame of every panel, so playback never rebuilds fields
    private val fieldCache = object : LinkedHashMap<String, Field>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Field>?) =
            size > maxOf(24, prefs.loopFrames * state.panelCount + 12)
    }

    // loop
    var looping = false
    private var playing = false
    var frameIndex = -1                    // -1 = the newest (live) frame
    private val loopTick = object : Runnable {
        override fun run() {
            if (!playing) return
            val n = loopLength()
            if (n > 1) {
                frameIndex = if (frameIndex < 0 || frameIndex >= n - 1) 0 else frameIndex + 1
                rebuild()
            }
            val last = frameIndex < 0 || frameIndex == n - 1
            main.postDelayed(this, prefs.loopSpeedMs.toLong() * if (last) 4 else 1)
        }
    }

    // views
    private lateinit var root: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var siteTitle: TextView
    private lateinit var siteSub: TextView
    private lateinit var busy: ProgressBar
    private lateinit var warnBtn: IconView
    private lateinit var productRow: LinearLayout
    private val productChips = HashMap<Product, W.Chip>()
    private lateinit var tiltChip: W.Chip
    private lateinit var panelsBtn: IconView
    private lateinit var loopBtn: IconView
    private lateinit var loopRow: LinearLayout
    private lateinit var loopSeek: SeekBar
    private lateinit var loopTime: TextView
    private var statusText = ""
    private var statusError = false

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        palettes = Palettes(this, prefs)
        state.panelCount = prefs.panels
        state.scale = prefs.mapScale
        buildViews()
        dm = DataManager(this) { fips -> basemap?.countyRings(fips) ?: emptyList() }
        dm.listener = this
        dm.wantAlerts = prefs.layer("warnings")
        applyKeepScreenOn()
        worker.execute { loadAssets() }
        Crash.takeReport()?.let { report -> main.postDelayed({ Sheets.crashReport(this, report) }, 600) }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        dm.start()
        if (playing) main.post(loopTick)
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        dm.stop()
        main.removeCallbacks(loopTick)
        prefs.mapScale = state.scale
    }

    override fun onDestroy() {
        super.onDestroy()
        dm.listener = null
        dm.shutdown()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        stopLocation()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrangeBottomBar()
        // layout listeners recompute the map area; just redraw
        root.post { updateArea(); requestRender() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            sheets.isOpen -> sheets.close()
            overlay.inspectKm != null -> overlay.hideInspector()
            looping -> stopLoop()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun applyKeepScreenOn() {
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun settingsChanged() {
        applyKeepScreenOn()
        overlay.velUnits = prefs.velUnits
        overlay.distUnits = prefs.distUnits
        overlay.showLegend = prefs.showLegend
        rebuildRings()
        rebuildAlerts()
        fieldCache.clear()
        rebuild()
    }

    // ---------------------------------------------------------------- assets + sites
    private fun loadAssets() {
        try {
            val t0 = System.currentTimeMillis()
            val bm = Basemap.read(assets.open("basemap.bin").use { it.readBytes() })
            val st = Sites.parse(assets.open("sites.json").bufferedReader().use { it.readText() })
            RfLog.i("map data loaded in ${System.currentTimeMillis() - t0} ms")
            main.post {
                basemap = bm
                sites = st
                val saved = st.firstOrNull { it.id == prefs.site }
                if (saved != null) selectSite(saved, resetView = true)
                else firstLaunch()
            }
        } catch (e: Exception) {
            RfLog.e("asset load failed", e)
            main.post { toast("Couldn't load the map data: ${e.message}") }
        }
    }

    private fun firstLaunch() {
        val fallback = sites.firstOrNull { it.id == "KTLX" } ?: sites.first()
        selectSite(fallback, resetView = true)
        if (hasLocationPermission()) {
            pickNearestRadar(quiet = true)
        } else if (!prefs.askedLocation) {
            prefs.askedLocation = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION_NEAREST)
        }
        main.postDelayed({ if (!prefs.seenWelcome) Sheets.welcome(this) }, 900)
    }

    fun selectSite(s: Site, resetView: Boolean = true) {
        val changed = site?.id != s.id
        site = s
        prefs.site = s.id
        siteTitle.text = s.id
        if (changed) {
            fieldCache.clear()
            tilts = emptyList()
            stopLoop()
            overlay.hideInspector()
            // the old map outlines are centred on the old radar: hide them until the new ones are ready
            geo = null
            overlay.cities = null
            overlay.sites = emptyList()
            overlay.siteXY = FloatArray(0)
            overlay.proj = null
            overlay.locationKm = null
            alertLayers = emptyList()
        }
        if (resetView) { state.cx = 0f; state.cy = 0f }
        dm.setSite(s.id)
        updateStatusLine()
        val bm = basemap ?: return
        worker.execute {
            val proj = Geo.Aeqd(s.lat, s.lon)
            val names = listOf("countries", "states", "lakes", "counties", "roads", "roads2")
            val layers = names.associateWith { ProjectedLayer.project(bm.layers.getValue(it), proj, 1700.0) }
            val cities = ProjectedCities.project(bm.cities, proj, 1700.0)
            val xy = FloatArray(sites.size * 2)
            for ((i, o) in sites.withIndex()) proj.forward(o.lat, o.lon, xy, 2 * i)
            val g = SiteGeo(s, proj, layers, cities, xy)
            main.post {
                if (site?.id != s.id) return@post
                geo = g
                overlay.proj = proj
                overlay.cities = cities
                overlay.sites = sites
                overlay.siteXY = xy
                overlay.currentSite = s.id
                rebuildRings()
                rebuildAlerts()
                updateLocationKm()
                rebuild()
            }
        }
    }

    // ---------------------------------------------------------------- layout
    @SuppressLint("SetTextI18n")
    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(C.mapGap) }
        renderer = MapRenderer(this, state)
        renderer.onError = { msg -> main.post { toast(msg) } }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        overlay = OverlayView(this, state).apply {
            callbacks = this@MainActivity
            velUnits = prefs.velUnits
            distUnits = prefs.distUnits
            showLegend = prefs.showLegend
            showCities = prefs.layer("cities")
            showSites = prefs.layer("sites")
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // ---- top bar: site + status, warnings, layers, menu
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.panel)
            setPadding(dpi(6f), dpi(4f), dpi(2f), dpi(4f))
            elevation = dp(4f)
        }
        val siteBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(10f), dpi(5f), dpi(10f), dpi(5f))
            background = ripple(null, rounded(Color.WHITE, dp(10f)))
            isClickable = true
            setOnClickListener { Sheets.sites(this@MainActivity) }
        }
        val titleRow = W.hRow(this)
        siteTitle = W.text(this, "RadarForge", 19f, C.text, true)
        titleRow.addView(siteTitle)
        titleRow.addView(IconView(this, Icon.DOWN, C.dim), LinearLayout.LayoutParams(dpi(22f), dpi(22f)))
        busy = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(C.accent)
            visibility = View.GONE
        }
        titleRow.addView(busy, LinearLayout.LayoutParams(dpi(16f), dpi(16f)).apply { leftMargin = dpi(6f) })
        siteBlock.addView(titleRow)
        siteSub = W.text(this, "Loading map…", 12.5f, C.dim).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        siteBlock.addView(siteSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(2f) })
        topBar.addView(siteBlock, LinearLayout.LayoutParams(0, -2, 1f))
        warnBtn = W.iconButton(this, Icon.WARNING, "Warnings") { Sheets.warnings(this) }
        topBar.addView(warnBtn)
        topBar.addView(W.iconButton(this, Icon.LAYERS, "Map layers") { Sheets.layers(this) })
        topBar.addView(W.iconButton(this, Icon.SLIDERS, "Settings") { Sheets.settings(this) })
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // ---- bottom bar: loop row, products, tilt + tools
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.panel)
            setPadding(0, dpi(4f), 0, dpi(4f))
            elevation = dp(4f)
        }
        loopRow = W.hRow(this).apply { setPadding(dpi(12f), dpi(2f), dpi(4f), dpi(2f)); visibility = View.GONE }
        loopTime = W.text(this, "", 13f, C.text, true).apply { minWidth = dpi(86f) }
        loopRow.addView(loopTime)
        loopSeek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(C.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(C.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    setPlaying(false)
                    frameIndex = p
                    rebuild()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        loopRow.addView(loopSeek, LinearLayout.LayoutParams(0, -2, 1f))
        loopRow.addView(W.iconButton(this, Icon.CLOSE, "Stop loop") { stopLoop() })
        bottomBar.addView(loopRow)

        productRow = W.hRow(this).apply { setPadding(dpi(8f), dpi(4f), dpi(8f), dpi(4f)) }
        for (p in Product.entries) {
            val chip = W.chip(this, p.short) { setProduct(p) }
            productChips[p] = chip
            productRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dpi(6f) })
        }
        productScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(productRow) }
        barRow = LinearLayout(this)
        barRow.addView(productScroll)

        val tools = W.hRow(this).apply { setPadding(dpi(4f), 0, dpi(4f), 0) }
        toolsRow = tools
        tools.addView(W.iconButton(this, Icon.DOWN, "Lower tilt") { stepTilt(-1) })
        tiltChip = W.chip(this, "0.5°") { Sheets.tilts(this) }
        tools.addView(tiltChip, LinearLayout.LayoutParams(-2, -2))
        tools.addView(W.iconButton(this, Icon.UP, "Higher tilt") { stepTilt(+1) })
        tools.addView(W.weightSpace(this))
        panelsBtn = W.iconButton(this, panelIcon(), "Panels") { cyclePanels() }
        tools.addView(panelsBtn)
        loopBtn = W.iconButton(this, Icon.PLAY, "Loop") { toggleLoop() }
        tools.addView(loopBtn)
        tools.addView(W.iconButton(this, Icon.LOCATE, "My location") { locateMe() })
        barRow.addView(tools)
        bottomBar.addView(barRow, LinearLayout.LayoutParams(-1, -2))
        arrangeBottomBar()
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        sheets = SheetHost(this).apply { elevation = dp(8f) }   // above the bars (elevation 4dp)
        root.addView(sheets, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateArea() }
        root.addOnLayoutChangeListener(relayout)
        topBar.addOnLayoutChangeListener(relayout)
        bottomBar.addOnLayoutChangeListener(relayout)
        refreshProductChips()
    }

    private lateinit var barRow: LinearLayout
    private lateinit var productScroll: HorizontalScrollView
    private lateinit var toolsRow: LinearLayout

    /** Portrait: products above the tools. Landscape: one row, so the map keeps its height. */
    private fun arrangeBottomBar() {
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        barRow.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        barRow.gravity = Gravity.CENTER_VERTICAL
        productScroll.layoutParams = if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2)
        toolsRow.layoutParams = if (wide) LinearLayout.LayoutParams(-2, -2) else LinearLayout.LayoutParams(-1, -2)
        // the spacer inside the tools row only makes sense when the row is full width
        for (i in 0 until toolsRow.childCount) {
            val v = toolsRow.getChildAt(i)
            if (v.layoutParams is LinearLayout.LayoutParams && (v.layoutParams as LinearLayout.LayoutParams).weight > 0f) {
                v.visibility = if (wide) View.GONE else View.VISIBLE
            }
        }
    }

    private fun updateArea() {
        val a = RectF(0f, topBar.bottom.toFloat(), root.width.toFloat(), bottomBar.top.toFloat())
        if (a.height() <= 0 || a.width() <= 0) return
        if (a != state.area) {
            state.area = a
            overlay.invalidate()
            requestRender()
        }
    }

    private fun panelIcon() = when (state.panelCount) { 2 -> Icon.PANELS2; 4 -> Icon.PANELS4; else -> Icon.PANELS1 }

    fun requestRender() {
        glView.requestRender()
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- user actions
    private fun setProduct(p: Product) {
        val list = prefs.panelProducts.toMutableList()
        val i = state.activePanel.coerceIn(0, 3)
        list[i] = p
        prefs.panelProducts = list
        refreshProductChips()
        if (looping) requestLoopFrames()
        rebuild()
    }

    private fun refreshProductChips() {
        val cur = prefs.panelProducts.getOrNull(state.activePanel) ?: Product.REF
        for ((p, c) in productChips) c.selectedState = p == cur
    }

    private fun cyclePanels() {
        state.panelCount = when (state.panelCount) { 1 -> 2; 2 -> 4; else -> 1 }
        prefs.panels = state.panelCount
        if (state.activePanel >= state.panelCount) state.activePanel = 0
        panelsBtn.icon = panelIcon()
        refreshProductChips()
        if (looping) requestLoopFrames()
        rebuild()
    }

    fun setTiltIndex(i: Int) {
        val t = tilts.getOrNull(i) ?: return
        prefs.tilt = t.elevation
        if (looping) requestLoopFrames()
        rebuild()
    }

    private fun stepTilt(d: Int) {
        if (tilts.isEmpty()) return
        val i = Tilts.closest(tilts, prefs.tilt)
        setTiltIndex((i + d).coerceIn(0, tilts.size - 1))
    }

    // ---------------------------------------------------------------- loop
    private fun toggleLoop() {
        if (!looping) {
            looping = true
            frameIndex = -1
            loopRow.visibility = View.VISIBLE
            requestLoopFrames()
            setPlaying(true)
        } else {
            setPlaying(!playing)
        }
    }

    private fun setPlaying(p: Boolean) {
        playing = p
        loopBtn.icon = if (p) Icon.PAUSE else Icon.PLAY
        main.removeCallbacks(loopTick)
        if (p) main.postDelayed(loopTick, prefs.loopSpeedMs.toLong())
    }

    fun stopLoop() {
        if (!looping) return
        looping = false
        setPlaying(false)
        frameIndex = -1
        loopRow.visibility = View.GONE
        dm.stopLoop()
        rebuild()
    }

    private fun requestLoopFrames() {
        val moments = prefs.panelProducts.take(state.panelCount).map { it.moment }.toSet()
        dm.requestLoop(prefs.loopFrames - 1, prefs.tilt, moments)
    }

    /** Archive frames older than the newest volume (that one is always the last frame). */
    private fun loopFrameList(): List<DataManager.Frame> {
        val newest = (dm.live ?: dm.complete)?.startMs ?: Long.MAX_VALUE
        val completeMs = dm.complete?.startMs ?: Long.MAX_VALUE
        val cut = minOf(newest, if (dm.live != null) Long.MAX_VALUE else completeMs) - 30_000
        return dm.frames.filter { it.timeMs < cut }
    }

    /** Loop frames (oldest first) followed by the newest data. */
    private fun loopLength() = loopFrameList().size + 1

    // ---------------------------------------------------------------- data -> scene
    override fun onVolumes() {
        rebuild()
        updateStatusLine()
    }

    override fun onFrames() {
        if (looping) rebuild()
    }

    override fun onAlerts() {
        rebuildAlerts()
        rebuild()
    }

    override fun onStatus(text: String, busy: Boolean, error: Boolean) {
        statusText = text
        statusError = error
        this.busy.visibility = if (busy) View.VISIBLE else View.GONE
        updateStatusLine()
    }

    fun updateStatusLine() {
        val s = site ?: return
        val newest = listOfNotNull(dm.live, dm.complete).maxOfOrNull { it.startMs }
        val text = when {
            statusText.isNotEmpty() -> statusText
            newest != null -> "${s.place}, ${s.state} · ${if (dm.live != null) "Live · " else ""}${Time.age(newest)}"
            else -> "${s.place}, ${s.state}"
        }
        siteSub.text = text
        siteSub.setTextColor(if (statusError) 0xffffa060.toInt() else C.dim)
    }

    private fun tiltsOf(v: Volume?): List<Tilt> = if (v == null) emptyList() else Tilts.build(v)

    private fun fieldOf(v: Volume, t: Tilt, p: Product): Field? {
        val sw = t.sweep(p.moment) ?: return null
        if (sw.nRays < 40) return null
        val key = "${v.site}/${System.identityHashCode(sw)}/${sw.startMs}/${sw.elevNum}/${sw.nRays}/${p.id}/${prefs.stormDir}/${prefs.stormKts}"
        fieldCache[key]?.let { return it }
        val f = Field.from(p, v.site, v.startMs, sw, prefs.stormDir, prefs.stormKts) ?: return null
        fieldCache[key] = f
        return f
    }

    private class Pick(val field: Field?, val note: String?)

    /** The field to show for a product: the scan in progress when it has reached this tilt, else the newest complete volume. */
    private fun pick(p: Product): Pick {
        val angle = prefs.tilt
        val frames = if (looping) loopFrameList() else emptyList()
        if (looping && frameIndex >= 0 && frameIndex < frames.size) {
            val fr = frames[frameIndex]
            val ts = tiltsOf(fr.volume)
            val i = Tilts.closest(ts, angle)
            val f = if (i >= 0) fieldOf(fr.volume, ts[i], p) else null
            return Pick(f, if (f == null) "not in this frame" else null)
        }
        val live = dm.live
        val complete = dm.complete
        if (live != null) {
            val ts = tiltsOf(live)
            val i = Tilts.closest(ts, angle)
            if (i >= 0 && abs(ts[i].elevation - angle) < 0.3f) {
                fieldOf(live, ts[i], p)?.let { return Pick(it, if (!it.complete) "scanning…" else null) }
            }
        }
        if (complete != null) {
            val ts = tiltsOf(complete)
            val i = Tilts.closest(ts, angle)
            if (i >= 0) {
                val f = fieldOf(complete, ts[i], p)
                if (f != null) return Pick(f, if (live != null) "previous volume" else null)
            }
            return Pick(null, "${p.short} not available at this tilt")
        }
        return Pick(null, "loading…")
    }

    private fun layerColor(name: String) = when (name) {
        "states" -> C.states; "countries" -> C.countries; "counties" -> C.counties; "lakes" -> C.lakes
        "roads" -> C.roads; "roads2" -> C.roads2; else -> C.text
    }

    private fun rebuildRings() {
        val toKm = when (prefs.distUnits) { "km" -> 1f; "nm" -> 1.852f; else -> 1.609344f }
        val maxKm = 250 * toKm
        val pts = RenderData.rangeRings(maxKm, 50 * toKm)
        rings = ProjectedLayer(pts, listOf(ProjectedLayer.Chunk(0, pts.size / 2, -maxKm, -maxKm, maxKm, maxKm)))
    }

    /** Outline colour for a warning type: the user's choice, else the NWS colour. */
    fun warnColor(event: String): Int = prefs.warnColor(event) ?: Alerts.NWS_COLORS[event] ?: Alerts.STYLES[event]?.color ?: C.text

    fun rebuildAlerts() {
        val wasWanted = dm.wantAlerts
        dm.wantAlerts = prefs.layer("warnings")
        if (dm.wantAlerts && !wasWanted) dm.refreshNow()
        val g = geo
        if (g == null || !prefs.layer("warnings")) {
            alertLayers = emptyList(); visibleAlerts = emptyList(); overlay.alerts = emptyList()
            warnBtn.badge = null
            return
        }
        val s = g.site
        val near = dm.alerts.filter { a ->
            prefs.warnGroup(Alerts.group(a.event)) &&
                Geo.distanceKm(s.lat, s.lon, (a.minLat + a.maxLat) / 2.0, (a.minLon + a.maxLon) / 2.0) < 900
        }
        visibleAlerts = near
        overlay.alerts = near
        val d = resources.displayMetrics.density
        alertLayers = near.map { a ->
            LayerDraw(ProjectedLayer.fromRings(a.rings, g.proj), warnColor(a.event), a.style.width * d * 1.1f, halo = !a.isWatch)
        }
        val warnings = near.count { !it.isWatch }
        warnBtn.badge = if (warnings > 0) (if (warnings > 99) "99+" else warnings.toString()) else null
        warnBtn.setTint(if (near.any { it.event.startsWith("Tornado") && !it.isWatch }) 0xffff5a5a.toInt() else C.text)
    }

    /** Rebuilds what is drawn from the current data and settings. Cheap; call freely. */
    fun rebuild() {
        val ref = dm.live ?: dm.complete
        val newTilts = if (looping && frameIndex >= 0) tilts else tiltsOf(dm.complete ?: dm.live)
        if (newTilts.isNotEmpty()) tilts = newTilts
        val ti = Tilts.closest(tilts, prefs.tilt)
        tiltChip.text = if (ti >= 0) tilts[ti].label else String.format(java.util.Locale.US, "%.1f°", prefs.tilt)

        val products = prefs.panelProducts
        val panelDraws = ArrayList<PanelDraw>()
        val infos = ArrayList<OverlayView.PanelInfo>()
        val siteId = site?.id ?: ""
        for (i in 0 until state.panelCount) {
            val p = products[i]
            val table = palettes.table(p.palette)
            val pk = pick(p)
            panelDraws.add(PanelDraw(pk.field, table, prefs.smooth))
            val f = pk.field
            val title = "$siteId  ${p.short} ${if (f != null) String.format(java.util.Locale.US, "%.1f°", f.elevation) else ""}".trim()
            val sub = if (f != null) "${Time.hmsZ(f.sweepMs)} · ${Time.local(f.sweepMs)}" else (if (ref == null) "waiting for data" else "")
            infos.add(OverlayView.PanelInfo(p, f, table, title, sub, pk.note))
        }

        val layers = ArrayList<LayerDraw>()
        val d = resources.displayMetrics.density
        val g = geo
        if (g != null) {
            fun add(name: String, width: Float, maxKm: Float = Float.MAX_VALUE) {
                val l = g.layers[name] ?: return
                layers.add(LayerDraw(l, layerColor(name), width * d, maxKm))
            }
            if (prefs.layer("counties")) add("counties", 0.9f, 1300f)
            add("lakes", 0.9f)
            if (prefs.layer("roads")) { add("roads2", 0.9f, 450f); add("roads", 1.3f, 1600f) }
            add("countries", 1.6f)
            add("states", 1.6f)
            if (prefs.layer("rings")) rings?.let { layers.add(LayerDraw(it, C.rings, 1.0f * d, 2500f)) }
        }

        // loop frames to keep on the GPU so playback is smooth
        val keep = ArrayList<Field>()
        if (looping) {
            for (fr in loopFrameList()) {
                val ts = tiltsOf(fr.volume)
                val i = Tilts.closest(ts, prefs.tilt)
                if (i < 0) continue
                for (k in 0 until state.panelCount) fieldOf(fr.volume, ts[i], products[k])?.let { keep.add(it) }
            }
            val n = loopLength()
            loopSeek.max = maxOf(0, n - 1)
            val idx = if (frameIndex < 0) n - 1 else frameIndex
            loopSeek.progress = idx
            val t = infos.firstOrNull()?.field?.sweepMs
            loopTime.text = if (t != null) "${Time.local(t)}  ${idx + 1}/$n" else "${idx + 1}/$n"
        }

        renderer.scene = Scene(C.mapBg, C.mapGap, layers, alertLayers, panelDraws, keep)
        overlay.panels = infos
        overlay.showCities = prefs.layer("cities")
        overlay.showSites = prefs.layer("sites")
        overlay.invalidate()
        requestRender()
    }

    // ---------------------------------------------------------------- overlay callbacks
    override fun onMapMoved() {
        requestRender()
    }

    override fun onSiteTapped(site: Site) {
        if (site.id == this.site?.id) return
        selectSite(site, resetView = true)
        toast("Switched to ${site.id} – ${site.title}")
    }

    override fun onAlertsTapped(alerts: List<Alert>) {
        if (alerts.size == 1) Sheets.alertDetail(this, alerts[0]) else Sheets.alertList(this, alerts, "Warnings here")
    }

    override fun onPanelTapped(index: Int) {
        if (state.panelCount > 1 && index != state.activePanel) {
            state.activePanel = index
            refreshProductChips()
            overlay.invalidate()
        }
    }

    /** Centres the map on a polygon (warnings). */
    fun zoomTo(a: Alert) {
        val g = geo ?: return
        val xy = FloatArray(2)
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (r in a.rings) {
            var i = 0
            while (i + 1 < r.size) {
                g.proj.forward(r[i + 1].toDouble(), r[i].toDouble(), xy, 0)
                x0 = minOf(x0, xy[0]); x1 = maxOf(x1, xy[0]); y0 = minOf(y0, xy[1]); y1 = maxOf(y1, xy[1])
                i += 2
            }
        }
        if (x0 > x1) return
        state.cx = (x0 + x1) / 2
        state.cy = (y0 + y1) / 2
        val r = state.panelRects().first()
        state.scale = (minOf(r.width() / (x1 - x0 + 20), r.height() / (y1 - y0 + 20)) * 0.8f).coerceIn(state.minScale, 40f)
        overlay.invalidate()
        requestRender()
    }

    // ---------------------------------------------------------------- location
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    var lastLocation: Location? = null

    fun hasLocationPermission() = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locateMe() {
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION_CENTER)
            return
        }
        withLocation { loc ->
            val g = geo ?: return@withLocation
            val d = Geo.distanceKm(loc.latitude, loc.longitude, g.site.lat, g.site.lon)
            if (d > 400) {
                val n = Sites.nearest(sites, loc.latitude, loc.longitude)
                if (n != null && n.id != g.site.id) {
                    toast("You're ${(d * 0.621).toInt()} mi from ${g.site.id} – switching to ${n.id}")
                    selectSite(n)
                    return@withLocation
                }
            }
            val xy = FloatArray(2)
            g.proj.forward(loc.latitude, loc.longitude, xy, 0)
            state.cx = xy[0]; state.cy = xy[1]
            if (state.scale < 2.5f) state.scale = 2.5f
            overlay.invalidate()
            requestRender()
        }
    }

    fun pickNearestRadar(quiet: Boolean = false) {
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION_NEAREST)
            return
        }
        withLocation { loc ->
            val n = Sites.nearest(sites, loc.latitude, loc.longitude) ?: return@withLocation
            if (n.id != site?.id) selectSite(n)
            if (!quiet) toast("Nearest radar: ${n.id} – ${n.title}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun withLocation(then: (Location) -> Unit) {
        val lm = locationManager ?: (getSystemService(LOCATION_SERVICE) as LocationManager).also { locationManager = it }
        val known = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
        if (known != null && System.currentTimeMillis() - known.time < 30 * 60_000L) {
            setLocation(known)
            then(known)
            return
        }
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull {
            try { lm.isProviderEnabled(it) } catch (_: Exception) { false }
        }
        if (provider == null) {
            if (known != null) { setLocation(known); then(known) } else toast("Location is turned off on this phone")
            return
        }
        toast("Finding your location…")
        stopLocation()
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                stopLocation()
                setLocation(loc)
                then(loc)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        locationListener = l
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, l, Looper.getMainLooper())
        } catch (e: Exception) {
            RfLog.w("location request failed: ${e.message}")
        }
        main.postDelayed({
            if (locationListener === l) {
                stopLocation()
                if (known != null) { setLocation(known); then(known) } else toast("Couldn't get your location")
            }
        }, 15_000)
    }

    private fun stopLocation() {
        val l = locationListener ?: return
        locationListener = null
        try { locationManager?.removeUpdates(l) } catch (_: Exception) {}
    }

    private fun setLocation(loc: Location) {
        lastLocation = loc
        updateLocationKm()
    }

    private fun updateLocationKm() {
        val loc = lastLocation ?: return
        val g = geo ?: return
        val xy = FloatArray(2)
        g.proj.forward(loc.latitude, loc.longitude, xy, 0)
        overlay.locationKm = xy
        overlay.invalidate()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        if (!granted) return
        when (requestCode) {
            REQ_LOCATION_NEAREST -> pickNearestRadar()
            REQ_LOCATION_CENTER -> locateMe()
        }
    }

    // ---------------------------------------------------------------- colour table import
    fun importColorTable() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_IMPORT_PAL)
        } catch (e: Exception) {
            toast("No file picker available")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT_PAL || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            var name = "imported.pal"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            if (bytes.size > 1_000_000) throw IllegalArgumentException("That file is too big to be a colour table.")
            val (family, file) = palettes.import(name, String(bytes, Charsets.UTF_8))
            fieldCache.clear()
            rebuild()
            toast(if (family != null) "Colour table \"$file\" is now used for $family" else
                "Imported \"$file\". It doesn't say which product it's for – pick it under Settings → Colour tables.")
            if (sheets.isOpen) Sheets.colorTables(this)
        } catch (e: Exception) {
            RfLog.e("import failed", e)
            toast(e.message ?: "Couldn't read that file")
        }
    }

    companion object {
        const val REQ_LOCATION_NEAREST = 11
        const val REQ_LOCATION_CENTER = 12
        const val REQ_IMPORT_PAL = 21
    }
}
