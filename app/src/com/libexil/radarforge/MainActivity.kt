package com.libexil.radarforge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
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
import com.libexil.radarforge.core.Chaser
import com.libexil.radarforge.core.ColorTable
import com.libexil.radarforge.core.Dealias
import com.libexil.radarforge.core.Learn
import com.libexil.radarforge.core.LoopFrame
import com.libexil.radarforge.core.LoopSupport
import com.libexil.radarforge.core.Trail
import com.libexil.radarforge.core.Field
import com.libexil.radarforge.core.Geo
import com.libexil.radarforge.core.MesoDiscussion
import com.libexil.radarforge.core.Product
import com.libexil.radarforge.core.ProjectedCities
import com.libexil.radarforge.core.ProjectedLayer
import com.libexil.radarforge.core.RenderData
import com.libexil.radarforge.core.Site
import com.libexil.radarforge.core.Sites
import com.libexil.radarforge.core.Spc
import com.libexil.radarforge.core.StormReport
import com.libexil.radarforge.core.Tilt
import com.libexil.radarforge.core.Tilts
import com.libexil.radarforge.core.Time
import com.libexil.radarforge.core.Volume
import com.libexil.radarforge.data.DataManager
import com.libexil.radarforge.data.DerivedFields
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
import com.libexil.radarforge.ui.Themes
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
    /** Dealiased velocity and Σ trails, made in the background. */
    lateinit var derived: DerivedFields
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
    private var spcLayers: List<LayerDraw> = emptyList()
    var visibleAlerts: List<Alert> = emptyList()
    /** Storm reports near the radar that pass the filters (what's drawn). */
    var visibleReports: List<StormReport> = emptyList()

    var tilts: List<Tilt> = emptyList()
    // big enough for every loop frame of every panel, so playback never rebuilds fields
    private val fieldCache = object : LinkedHashMap<String, Field>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Field>?) =
            size > maxOf(24, (prefs.previousScans + 1) * state.panelCount + 12)
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
    /** What survives a theme change (the activity is rebuilt, the data isn't). */
    private class Retained(val dm: DataManager, val derived: DerivedFields, val basemap: Basemap?, val sites: List<Site>,
                           val siteId: String?, val cx: Float, val cy: Float, val scale: Float, val activePanel: Int, val reopen: String?)
    private var reopenSheet: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        applyTheme()
        palettes = Palettes(this, prefs)
        state.panelCount = prefs.panels
        state.scale = prefs.mapScale
        @Suppress("DEPRECATION")
        val kept = lastNonConfigurationInstance as? Retained
        buildViews()
        if (kept != null) {
            dm = kept.dm
            derived = kept.derived
        } else {
            dm = DataManager(this) { emptyList() }
            derived = DerivedFields { }
        }
        dm.countyRings = { fips -> basemap?.countyRings(fips) ?: emptyList() }
        derived.onReady = { if (!isDestroyed) rebuild() }
        derived.maxBytes = minOf(96L shl 20, Runtime.getRuntime().maxMemory() / 6)
        dm.listener = this
        dm.wantAlerts = prefs.layer("warnings")
        applyFeedPrefs()
        applyKeepScreenOn()
        if (kept != null && kept.basemap != null && kept.sites.isNotEmpty()) {
            restore(kept)
        } else {
            worker.execute { loadAssets() }
            Crash.takeReport()?.let { report -> main.postDelayed({ Sheets.crashReport(this, report) }, 600) }
        }
    }

    /** Sets the theme's colours (and the system bars) before any view is built. */
    private fun applyTheme() {
        Themes.apply(currentTheme(), prefs.accent)
        try {
            window.statusBarColor = C.panel
            window.navigationBarColor = C.panel
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C.window))
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            flags = if (!C.dark) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else flags and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        } catch (_: Exception) {}
    }

    private fun systemDark() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO

    private fun currentTheme() = Themes.resolve(prefs.theme, prefs.followSystemTheme, systemDark())

    /**
     * Rebuilds the screen in the new colours, keeping the radar data, the view and the open sheet
     * ([reopen]: "themes" reopens the theme picker).
     */
    fun restyle(reopen: String? = null) {
        reopenSheet = reopen
        prefs.mapScale = state.scale
        recreate()
    }

    @Deprecated("Deprecated in Java")
    override fun onRetainNonConfigurationInstance(): Any? {
        if (!::dm.isInitialized) return null
        return Retained(dm, derived, basemap, sites, site?.id, state.cx, state.cy, state.scale, state.activePanel, reopenSheet)
    }

    /** After a theme change: the same radar, data and view. */
    private fun restore(k: Retained) {
        basemap = k.basemap
        sites = k.sites
        val s = sites.firstOrNull { it.id == k.siteId } ?: sites.firstOrNull { it.id == prefs.site }
        if (s == null) { worker.execute { loadAssets() }; return }
        state.activePanel = k.activePanel.coerceIn(0, state.panelCount - 1)
        selectSite(s, resetView = false, keepData = true)
        state.cx = k.cx; state.cy = k.cy; state.scale = k.scale
        refreshProductChips()
        when (k.reopen) {
            "themes" -> main.postDelayed({ if (!sheets.isOpen) Sheets.themes(this) }, 120)
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        dm.start()
        if (playing) main.post(loopTick)
        startLiveLocation()
        main.removeCallbacks(statusTick)
        main.postDelayed(statusTick, 30_000)
        // e.g. back on Wi-Fi: the previous scans may load now
        if (site != null) requestLoopFrames()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        dm.stop()
        main.removeCallbacks(loopTick)
        main.removeCallbacks(statusTick)
        prefs.mapScale = state.scale
        site?.let { prefs.saveMapCenter(it.id, state.cx, state.cy) }
        stopLiveLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        dm.listener = null
        if (!isChangingConfigurations) {
            dm.shutdown()
            derived.shutdown()
        }
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        stopLocation()
        stopLiveLocation()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // the phone switched between light and dark mode
        if (prefs.followSystemTheme && currentTheme().name != C.themeName) { restyle(); return }
        arrangeBottomBar()
        // layout listeners recompute the map area; just redraw
        root.post { updateArea(); requestRender() }
    }

    /** Keeps "3 min ago" (and its colour) current. */
    private val statusTick = object : Runnable {
        override fun run() {
            updateStatusLine()
            main.postDelayed(this, 30_000)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            sheets.isOpen -> sheets.close()
            overlay.inspectKm != null -> overlay.hideInspector()
            overlay.tool != OverlayView.Tool.NONE -> closeTools()
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
        overlay.textScale = prefs.mapTextScale
        rebuildRings()
        rebuildAlerts()
        rebuildFeeds()
        rebuildSpc()
        fieldCache.clear()
        derived.cancelQueued()
        rebuild()
        onToolChanged()
        updateLearnCard()
        requestLoopFrames()
        if (!prefs.liveLocation && !following) stopLiveLocation() else startLiveLocation()
    }

    /** Smoothing, dealiasing, the Σ trail or learn mode changed. */
    fun displayChanged() {
        derived.cancelQueued()
        rebuild()
        updateLearnCard()
    }

    /** Starts the live feed again and reloads anything missing (Settings, or the radar list). */
    fun reloadData() {
        dm.reload()
        dm.refreshNow()
        requestLoopFrames()
        toast("Reloading ${site?.id ?: "radar"} data…")
    }

    /** A map layer or one of its options changed. */
    fun layersChanged() {
        applyFeedPrefs()
        rebuildAlerts()
        rebuildFeeds()
        rebuildSpc()
        rebuild()
    }

    /** Tells the data manager which feeds to fetch; anything just turned on is fetched now. */
    fun applyFeedPrefs() {
        val before = mapOf("reports" to dm.wantReports, "chasers" to dm.wantChasers, "outlook" to dm.wantOutlook, "mcd" to dm.wantMcd)
        dm.reportHours = prefs.reportHours
        dm.wantSpotterReports = prefs.spotterReports
        dm.chasersActiveOnly = prefs.chasersActiveOnly
        dm.wantReports = prefs.layer("reports")
        dm.wantChasers = prefs.layer("chasers")
        dm.wantOutlook = prefs.layer("outlook")
        dm.wantMcd = prefs.layer("mcd")
        val dayChanged = dm.outlookDay != prefs.outlookDay
        dm.outlookDay = prefs.outlookDay
        for ((name, was) in before) if (prefs.layer(name) && !was) dm.refreshFeed(name)
        if (dayChanged && prefs.layer("outlook")) dm.refreshFeed("outlook")
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
                if (saved != null) {
                    selectSite(saved, resetView = true)
                    prefs.mapCenterFor(saved.id)?.let { c -> state.cx = c[0]; state.cy = c[1] }
                    if (prefs.seenWhatsNew != WHATS_NEW) main.postDelayed({ if (!sheets.isOpen) Sheets.whatsNew(this) }, 900)
                } else firstLaunch()
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

    fun selectSite(s: Site, resetView: Boolean = true, keepData: Boolean = false) {
        val changed = site?.id != s.id
        site = s
        prefs.site = s.id
        siteTitle.text = s.id
        if (changed) {
            fieldCache.clear()
            if (!keepData) derived.clear()
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
            spcLayers = emptyList()
            overlay.reports = emptyList(); overlay.chasers = emptyList(); overlay.mcds = emptyList(); overlay.outlookLabels = emptyList()
            overlay.resetTools()          // measurements are in the old radar's coordinates
        }
        if (resetView) { state.cx = 0f; state.cy = 0f }
        if (!keepData) dm.setSite(s.id)
        // the previous scans load as soon as the newest volume is in
        requestLoopFrames()
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
                rebuildFeeds()
                rebuildSpc()
                updateLocationKm()
                rebuild()
                val zoom = pendingZoom
                val center = pendingCenter
                pendingZoom = null; pendingCenter = null
                when {
                    zoom != null -> zoomTo(zoom)
                    center != null -> centerOn(center[0], center[1])
                    following -> lastLocation?.let { centerOn(it.latitude, it.longitude, zoomIn = false) }
                }
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
            textScale = prefs.mapTextScale
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
        topBar.addView(W.iconButton(this, Icon.SHARE, "Share a picture of the map") { shareScreenshot() })
        topBar.addView(W.iconButton(this, Icon.SLIDERS, "Settings") { Sheets.settings(this) }.apply {
            setOnLongClickListener { Sheets.themes(this@MainActivity); true }
        })
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // ---- bottom bar: loop row, products, tilt + tools
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.panel)
            setPadding(0, dpi(4f), 0, dpi(4f))
            elevation = dp(4f)
        }
        loopRow = W.hRow(this).apply { setPadding(dpi(2f), dpi(2f), dpi(4f), dpi(2f)); visibility = View.GONE }
        loopRow.addView(W.iconButton(this, Icon.PREV, "Previous scan") { stepFrame(-1) }.apply {
            layoutParams = LinearLayout.LayoutParams(dpi(40f), dpi(40f))
        })
        loopTime = W.text(this, "", 13f, C.text, true).apply { minWidth = dpi(92f); gravity = Gravity.CENTER }
        loopRow.addView(loopTime)
        loopRow.addView(W.iconButton(this, Icon.NEXT, "Next scan") { stepFrame(+1) }.apply {
            layoutParams = LinearLayout.LayoutParams(dpi(40f), dpi(40f))
        })
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
        buildToolCard()
        bottomBar.addView(toolCard)
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
        loopBtn = W.iconButton(this, Icon.PLAY, "Loop the previous scans") { toggleLoop() }
        tools.addView(loopBtn)
        toolBtn = W.iconButton(this, Icon.RULER, "Measure distance / storm track") { if (overlay.tool == OverlayView.Tool.NONE) openTools() else closeTools() }
        tools.addView(toolBtn)
        locateBtn = W.iconButton(this, Icon.LOCATE, "My location (tap again to stop following)") { locateMe() }
        tools.addView(locateBtn)
        barRow.addView(tools)
        bottomBar.addView(barRow, LinearLayout.LayoutParams(-1, -2))
        arrangeBottomBar()
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // learn mode: what the values under the cross-hair mean, floating just above the bottom bar
        learnCard = W.text(this, "", 13.5f, C.text).apply {
            setLineSpacing(0f, 1.18f)
            setPadding(dpi(14f), dpi(10f), dpi(14f), dpi(10f))
            visibility = View.GONE
            isClickable = true
            background = ripple(rounded(Themes.withAlpha(C.panel, 0xf2), dp(12f), C.border, dpi(1f)))
            elevation = dp(6f)
            setOnClickListener { Sheets.radarGuide(this@MainActivity) }
        }
        root.addView(learnCard, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dpi(8f); rightMargin = dpi(8f); bottomMargin = dpi(120f)
        })
        sheets = SheetHost(this).apply { elevation = dp(8f) }   // above the bars (elevation 4dp)
        root.addView(sheets, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateArea() }
        root.addOnLayoutChangeListener(relayout)
        topBar.addOnLayoutChangeListener(relayout)
        bottomBar.addOnLayoutChangeListener(relayout)
        refreshProductChips()
    }

    private lateinit var learnCard: TextView
    private lateinit var barRow: LinearLayout
    private lateinit var productScroll: HorizontalScrollView
    private lateinit var toolsRow: LinearLayout
    private lateinit var toolBtn: IconView
    private lateinit var locateBtn: IconView

    // ---------------------------------------------------------------- measuring tools
    private lateinit var toolCard: LinearLayout
    private lateinit var toolDistChip: W.Chip
    private lateinit var toolTrackChip: W.Chip
    private lateinit var toolText: TextView
    private lateinit var toolTrackRow: LinearLayout
    private lateinit var toolTrackScroll: HorizontalScrollView
    private lateinit var toolMinutesChip: W.Chip
    private var lastTool = OverlayView.Tool.DISTANCE

    private fun buildToolCard() {
        toolCard = W.vCol(this).apply {
            setPadding(dpi(10f), dpi(2f), dpi(4f), dpi(4f))
            visibility = View.GONE
        }
        val row = W.hRow(this)
        toolDistChip = W.chip(this, "Distance") { overlay.setTool(OverlayView.Tool.DISTANCE) }
        toolTrackChip = W.chip(this, "Storm track") { overlay.setTool(OverlayView.Tool.TRACK) }
        row.addView(toolDistChip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dpi(6f) })
        row.addView(toolTrackChip, LinearLayout.LayoutParams(-2, -2))
        row.addView(W.weightSpace(this))
        row.addView(W.iconButton(this, Icon.UNDO, "Undo the last point") { overlay.undoTool() })
        row.addView(W.iconButton(this, Icon.TRASH, "Clear") { overlay.clearTool() })
        row.addView(W.iconButton(this, Icon.CLOSE, "Close the measuring tools") { closeTools() })
        toolCard.addView(row, LinearLayout.LayoutParams(-1, -2))
        toolText = W.text(this, "", 13.5f, C.text).apply {
            setLineSpacing(0f, 1.18f)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dpi(2f), dpi(2f), dpi(8f), dpi(4f))
        }
        toolCard.addView(toolText, LinearLayout.LayoutParams(-1, -2))
        toolTrackRow = W.hRow(this).apply { setPadding(0, dpi(2f), 0, dpi(2f)) }
        toolMinutesChip = W.chip(this, "${prefs.trackMinutes} min") {
            val list = Prefs.TRACK_MINUTES
            val next = list[(list.indexOf(prefs.trackMinutes) + 1) % list.size]
            prefs.trackMinutes = next
            overlay.setTrackMinutes(next)
        }
        toolTrackRow.addView(toolMinutesChip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dpi(6f) })
        toolTrackRow.addView(W.chip(this, "Use for SRV") { useTrackForSrv() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dpi(6f) })
        toolTrackRow.addView(W.chip(this, "Reset motion") {
            val a = overlay.trackA ?: return@chip
            overlay.setTrackEnd(trackEndFor(a, overlay.trackMinutes))
        }, LinearLayout.LayoutParams(-2, -2))
        toolTrackScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(toolTrackRow); visibility = View.GONE }
        toolCard.addView(toolTrackScroll, LinearLayout.LayoutParams(-1, -2))
    }

    private fun openTools() {
        hideKeyboardSheets()
        overlay.hideInspector()
        toolCard.visibility = View.VISIBLE
        overlay.setTool(lastTool)
        if (overlay.trackMinutes != prefs.trackMinutes) overlay.setTrackMinutes(prefs.trackMinutes)
        onToolChanged()
    }

    fun closeTools() {
        if (overlay.tool != OverlayView.Tool.NONE) lastTool = overlay.tool
        overlay.resetTools()
        overlay.setTool(OverlayView.Tool.NONE)
        toolCard.visibility = View.GONE
        toolBtn.setTint(C.text)
    }

    private fun hideKeyboardSheets() { if (sheets.isOpen) sheets.close() }

    override fun onToolChanged() {
        if (!::toolBtn.isInitialized) return
        val t = overlay.tool
        toolDistChip.selectedState = t == OverlayView.Tool.DISTANCE
        toolTrackChip.selectedState = t == OverlayView.Tool.TRACK
        toolBtn.setTint(if (t != OverlayView.Tool.NONE) C.accent else C.text)
        toolTrackScroll.visibility = if (t == OverlayView.Tool.TRACK && overlay.trackA != null) View.VISIBLE else View.GONE
        toolMinutesChip.text = "${overlay.trackMinutes} min"
        // a fixed number of lines per tool, so the bar (and the map) doesn't jump while dragging
        val lines = if (t == OverlayView.Tool.TRACK) 3 else 2
        if (toolText.minLines != lines || toolText.maxLines != lines) toolText.setLines(lines)
        toolText.text = when (t) {
            OverlayView.Tool.DISTANCE -> distanceText()
            OverlayView.Tool.TRACK -> trackText()
            OverlayView.Tool.NONE -> ""
        }
    }

    private fun distanceText(): String {
        val legs = overlay.rulerLegs()
        val u = prefs.distUnits
        return when {
            overlay.ruler.isEmpty() -> "Tap the map to start measuring."
            legs.isEmpty() -> "Tap another point. Drag a point to move it."
            else -> {
                val last = legs.last()
                val line1 = "${Geo.distText(last.first, u)}  ·  ${Geo.compass(last.second)} (${last.second.toInt()}°)"
                if (legs.size == 1) line1 + "\nTap to add more points; drag a point to move it."
                else line1 + "\nTotal ${Geo.distText(legs.sumOf { it.first }, u)} over ${legs.size} legs"
            }
        }
    }

    private fun trackText(): String {
        val m = overlay.trackMotion() ?: return "Tap a storm to place it, then drag the yellow arrowhead to where it's heading " +
            "(the arrow is ${overlay.trackMinutes} minutes of travel)."
        val (kmh, heading) = m
        val from = (heading + 180) % 360
        val now = System.currentTimeMillis()
        fun whenText(min: Double): String {
            val t = overlay.trackStartMs + (min * 60_000).toLong()
            val inMin = Math.round((t - now) / 60_000.0)
            return "${Time.local(t)} (" + (if (inMin > 0) "in $inMin min" else if (inMin == 0L) "now" else "passed") + ")"
        }
        val sb = StringBuilder()
        sb.append("Moving ${Geo.compass(heading)} at ${Geo.speedText(kmh, prefs.velUnits)}  ·  from ${Math.round(from)}°")
        val loc = overlay.locationKm
        val a = overlay.trackA; val b = overlay.trackB
        if (loc != null && a != null && b != null) {
            val eta = com.libexil.radarforge.core.Measure.etaAt(a[0], a[1], b[0], b[1], overlay.trackMinutes.toDouble(), loc[0], loc[1], overlay.trackHalfWidthKm)
            sb.append("\nYou: ").append(if (eta != null) whenText(eta) else "not in its path")
        } else {
            sb.append("\nYou: location off")
        }
        val etas = overlay.trackEtas
        if (etas.isEmpty()) sb.append("\nNo towns on this track in the next ${overlay.trackMinutes} min.")
        else sb.append("\n").append(etas.take(2).joinToString("  ·  ") { "${it.name} ${whenText(it.minutes)}" })
        return sb.toString()
    }

    override fun trackEndFor(startKm: FloatArray, minutes: Int): FloatArray {
        val g = geo ?: return floatArrayOf(startKm[0] + 40f, startKm[1] + 20f)
        val ll = g.proj.inverse(startKm[0].toDouble(), startKm[1].toDouble())
        val heading = (prefs.stormDir + 180.0) % 360.0
        val kts = if (prefs.stormKts >= 1f) prefs.stormKts.toDouble() else 30.0
        val d = Geo.destination(ll[0], ll[1], heading, kts * 1.852 * minutes / 60.0)
        val xy = FloatArray(2)
        g.proj.forward(d[0], d[1], xy, 0)
        return xy
    }

    private fun useTrackForSrv() {
        val (kmh, heading) = overlay.trackMotion() ?: return
        val from = (Math.round(((heading + 180) % 360) / 5.0) * 5 % 360).toFloat()
        val kts = Math.round(kmh / 1.852).toFloat().coerceIn(0f, 80f)
        prefs.stormDir = from
        prefs.stormKts = kts
        settingsChanged()
        toast("SRV storm motion set to ${from.toInt()}° at ${kts.toInt()} kts")
    }

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
        (learnCard.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val want = root.height - bottomBar.top + dpi(8f)
            if (lp.bottomMargin != want) { lp.bottomMargin = want; learnCard.requestLayout() }
        }
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
        requestLoopFrames()
        rebuild()
        updateLearnCard()
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
        requestLoopFrames()
        rebuild()
    }

    fun setTiltIndex(i: Int) {
        val t = tilts.getOrNull(i) ?: return
        if (Math.abs(prefs.tilt - t.elevation) > 0.01f) derived.cancelQueued()
        prefs.tilt = t.elevation
        // stepping through tilts quickly shouldn't start a download for every one
        main.removeCallbacks(loopRequestTask)
        main.postDelayed(loopRequestTask, if (looping) 150L else 600L)
        rebuild()
        updateLearnCard()
    }

    private val loopRequestTask = Runnable { requestLoopFrames() }

    private fun stepTilt(d: Int) {
        if (tilts.isEmpty()) return
        val i = Tilts.closest(tilts, prefs.tilt)
        setTiltIndex((i + d).coerceIn(0, tilts.size - 1))
    }

    // ---------------------------------------------------------------- loop (previous scans)
    private fun toggleLoop() {
        if (!looping) {
            startLoop(play = true)
        } else {
            setPlaying(!playing)
        }
    }

    private fun startLoop(play: Boolean) {
        looping = true
        frameIndex = -1
        loopRow.visibility = View.VISIBLE
        requestLoopFrames()
        setPlaying(play)
        rebuild()
    }

    /** One scan back or forward (opens the loop bar paused when it isn't showing). */
    private fun stepFrame(d: Int) {
        if (!looping) startLoop(play = false)
        setPlaying(false)
        val n = loopLength()
        val cur = if (frameIndex < 0) n - 1 else frameIndex
        val next = (cur + d).coerceIn(0, n - 1)
        frameIndex = if (next == n - 1) -1 else next
        rebuild()
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
        if (!wantPreviousScans()) dm.stopLoop()
        rebuild()
    }

    private fun onMeteredNetwork(): Boolean = try {
        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) { false }

    /**
     * Whether the previous scans should be kept loaded right now: always while the loop is open or the
     * Σ trail is on; otherwise on Wi-Fi, or on mobile data (when allowed) for the lowest tilts, where a
     * scan costs a couple of MB – higher tilts need most of each file.
     */
    fun wantPreviousScans(): Boolean {
        if (prefs.previousScans <= 0) return false
        if (looping || prefs.trail) return true
        if (!onMeteredNetwork()) return true
        return prefs.prefetchOnMobile && prefs.tilt <= 1.05f
    }

    /** Asks for the previous scans of the tilt and products on screen (or stops asking when not wanted). */
    fun requestLoopFrames() {
        if (!::dm.isInitialized || site == null) return
        main.removeCallbacks(loopRequestTask)
        if (!wantPreviousScans()) { dm.stopLoop(); return }
        dm.requestLoop(prefs.previousScans, prefs.tilt, neededMoments())
    }

    private fun neededMoments(): Set<String> = prefs.panelProducts.take(state.panelCount).map { it.moment }.toSet()

    /** The previous scans for the tilt and products on screen, oldest first (the newest volume comes after them). */
    private fun loopFrameList(): List<LoopFrame> {
        val cut = (nowVolumeMs() ?: Long.MAX_VALUE) - 30_000
        return LoopSupport.select(dm.frames, prefs.tilt, neededMoments(), cut, prefs.previousScans)
    }

    /**
     * Start time of the volume shown as "now": the scan in progress once it has reached the tilt on
     * screen (as [pick] decides), else the newest complete one.
     */
    private fun nowVolumeMs(): Long? {
        val live = dm.live
        if (live != null) {
            val ts = tiltsOf(live)
            val i = Tilts.closest(ts, prefs.tilt)
            if (i >= 0 && abs(ts[i].elevation - prefs.tilt) < 0.3f &&
                neededMoments().any { m -> (ts[i].sweep(m)?.nRays ?: 0) >= 40 }) return live.startMs
        }
        return dm.complete?.startMs ?: live?.startMs
    }

    /** Loop frames (oldest first) followed by the newest data. */
    private fun loopLength() = loopFrameList().size + 1

    // ---------------------------------------------------------------- data -> scene
    override fun onVolumes() {
        rebuild()
        updateStatusLine()
    }

    override fun onFrames() {
        // the trail and the loop bar use the previous scans
        if (looping || prefs.trail) rebuild()
    }

    override fun onAlerts() {
        rebuildAlerts()
        rebuild()
        checkWarningsAtLocation()
    }

    override fun onFeed(name: String) {
        when (name) {
            "reports", "chasers" -> rebuildFeeds()
            "outlook", "mcd" -> { rebuildSpc(); rebuild() }
        }
    }

    // ---------------------------------------------------------------- storm reports, chasers, SPC
    /** Projects storm reports and chaser positions near the radar for the overlay. */
    fun rebuildFeeds() {
        val g = geo
        if (g == null) {
            overlay.reports = emptyList(); overlay.chasers = emptyList(); visibleReports = emptyList()
            overlay.invalidate()
            return
        }
        val s = g.site
        val maxKm = 1500.0
        val cut = System.currentTimeMillis() - prefs.reportHours * 3_600_000L
        val reps = if (prefs.layer("reports")) dm.reports.filter {
            prefs.reportGroup(it.kind.group) && (it.timeMs == 0L || it.timeMs >= cut) && Geo.distanceKm(s.lat, s.lon, it.lat, it.lon) < maxKm
        } else emptyList()
        val rxy = FloatArray(reps.size * 2)
        for ((k, r) in reps.withIndex()) g.proj.forward(r.lat, r.lon, rxy, 2 * k)
        val chs = if (prefs.layer("chasers")) dm.chasers.filter { Geo.distanceKm(s.lat, s.lon, it.lat, it.lon) < maxKm } else emptyList()
        val cxy = FloatArray(chs.size * 2)
        for ((k, c) in chs.withIndex()) g.proj.forward(c.lat, c.lon, cxy, 2 * k)
        visibleReports = reps
        overlay.reports = reps; overlay.reportXY = rxy; overlay.reportHours = prefs.reportHours
        overlay.chasers = chs; overlay.chaserXY = cxy; overlay.chaserNames = prefs.chaserNames
        overlay.invalidate()
    }

    /** SPC outlook outlines + labels and mesoscale discussion outlines, projected around the radar. */
    fun rebuildSpc() {
        val g = geo
        if (g == null) { spcLayers = emptyList(); overlay.outlookLabels = emptyList(); overlay.mcds = emptyList(); return }
        val d = resources.displayMetrics.density
        val s = g.site
        val out = ArrayList<LayerDraw>()
        val labels = ArrayList<OverlayView.MapLabel>()
        fun nearRadar(minLat: Float, maxLat: Float, minLon: Float, maxLon: Float) =
            Geo.distanceKm(s.lat, s.lon, s.lat.coerceIn(minLat.toDouble(), maxLat.toDouble()), s.lon.coerceIn(minLon.toDouble(), maxLon.toDouble())) < 2200
        if (prefs.layer("outlook") && dm.outlookShownDay == prefs.outlookDay) {
            val cats = dm.outlook.filter { it.category == "CATEGORICAL" && Spc.catIndex(it.threshold) >= 0 }.sortedBy { Spc.catIndex(it.threshold) }
            val xy = FloatArray(2)
            for (a in cats) {
                val color = Spc.CAT_COLOR[a.threshold] ?: continue
                val rings = a.rings.filter { r -> val b = Geo.ringsBox(listOf(r)); nearRadar(b[0], b[1], b[2], b[3]) }
                if (rings.isEmpty()) continue
                out.add(LayerDraw(ProjectedLayer.fromRings(rings, g.proj), color, 2f * d, halo = true))
                for (r in rings) {
                    val b = Geo.ringsBox(listOf(r))
                    if (Geo.distanceKm(b[0].toDouble(), b[2].toDouble(), b[1].toDouble(), b[3].toDouble()) < 80) continue   // too small to label
                    // label in the middle of the ring if that's inside it, else just inside its northernmost point
                    var lat = (b[0] + b[1]) / 2.0; var lon = (b[2] + b[3]) / 2.0
                    if (!a.contains(lat, lon)) {
                        var k = 1; var best = -999f; var bi = 0
                        while (k < r.size) { if (r[k] > best) { best = r[k]; bi = k - 1 }; k += 2 }
                        lat = r[bi + 1] - 0.25; lon = r[bi].toDouble()
                    }
                    g.proj.forward(lat, lon, xy, 0)
                    labels.add(OverlayView.MapLabel(a.threshold, color, xy[0], xy[1]))
                }
            }
        }
        val mcds = if (prefs.layer("mcd")) dm.mcds.filter { nearRadar(it.minLat, it.maxLat, it.minLon, it.maxLon) } else emptyList()
        val mxy = FloatArray(mcds.size * 2)
        for ((k, m) in mcds.withIndex()) {
            out.add(LayerDraw(ProjectedLayer.fromRings(m.rings, g.proj), Spc.MCD_COLOR, 2.2f * d, halo = true))
            // label above the northernmost point
            var best = -999f; var bi = 0
            for (r in m.rings) { var i = 1; while (i < r.size) { if (r[i] > best) { best = r[i]; bi = i - 1; g.proj.forward(r[i].toDouble(), r[i - 1].toDouble(), mxy, 2 * k) }; i += 2 } }
        }
        spcLayers = out
        overlay.outlookLabels = labels
        overlay.mcds = mcds
        overlay.mcdXY = mxy
        overlay.invalidate()
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
        if (statusText.isNotEmpty() || newest == null) {
            siteSub.text = statusText.ifEmpty { "${s.place}, ${s.state}" }
            siteSub.setTextColor(if (statusError) C.warnText else C.dim)
            return
        }
        // a dot showing how fresh the data is: green while scanning or recent, then amber, then red
        val ageMin = (System.currentTimeMillis() - newest) / 60_000.0
        val scanning = dm.live != null && System.currentTimeMillis() - dm.lastDataMs < 3 * 60_000L
        val dot = when { scanning || ageMin < 12 -> C.ok; ageMin < 25 -> C.amber; else -> C.danger }
        val sb = SpannableStringBuilder()
        sb.append("● ")
        sb.setSpan(ForegroundColorSpan(dot), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(if (scanning) "Live · scanning · " else if (dm.live != null) "Live · " else "")
        sb.append(Time.age(newest)).append(" · ${s.place}, ${s.state}")
        siteSub.text = sb
        siteSub.setTextColor(C.dim)
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

    /** A loop frame's field for [p] at the tilt on screen. */
    private fun frameField(fr: LoopFrame, p: Product): Field? {
        val ts = tiltsOf(fr.volume)
        val i = Tilts.closest(ts, prefs.tilt)
        return if (i >= 0) fieldOf(fr.volume, ts[i], p) else null
    }

    /** The field to show for a product: the scan in progress when it has reached this tilt, else the newest complete volume. */
    private fun pick(p: Product, frames: List<LoopFrame> = if (looping) loopFrameList() else emptyList()): Pick {
        val angle = prefs.tilt
        if (looping && frameIndex >= 0 && frameIndex < frames.size) {
            val f = frameField(frames[frameIndex], p)
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

    // ---------------------------------------------------------------- dealiasing and the Σ trail
    /** The dealiased copy of a velocity / SRV field once it's ready (the original until then). */
    private fun dealiased(f: Field, force: Boolean = true): Field {
        if (!prefs.dealias || !f.product.isDoppler || f.nyquist <= 0f) return f
        // velocity and SRV share the same unfolded data: one job per sweep
        val key = "D|${f.site}/${f.volumeMs}/${f.sweepMs}/${f.elevation}/${f.nRays}/${f.variant}"
        val vel = derived.get(key, force) { Dealias.field(f.withCodes(f.codes8, f.codes16, f.variant, product = Product.VEL, stormU = 0f, stormV = 0f)) } ?: return f
        return if (f.product == Product.VEL) vel else vel.withCodes(vel.codes8, vel.codes16, vel.variant, product = f.product, stormU = f.stormU, stormV = f.stormV)
    }

    private fun dealiasKey(f: Field) = "D|${f.site}/${f.volumeMs}/${f.sweepMs}/${f.elevation}/${f.nRays}/${f.variant}"

    /** The dealiased velocity [vel] as [f]'s product (SRV keeps its storm motion). */
    private fun asProduct(vel: Field, f: Field) =
        if (f.product == Product.VEL) vel else vel.withCodes(vel.codes8, vel.codes16, vel.variant, product = f.product, stormU = f.stormU, stormV = f.stormV)

    /** Dealiased (when on) without caching the result – for trail steps when memory is short. Background thread. */
    private fun dealiasOnly(f: Field): Field {
        if (!prefs.dealias || !f.product.isDoppler || f.nyquist <= 0f) return f
        val vel = Dealias.field(f.withCodes(f.codes8, f.codes16, f.variant, product = Product.VEL, stormU = 0f, stormV = 0f)) ?: return f
        return asProduct(vel, f)
    }

    /** [dealiased] if it's ready, without queueing anything. */
    private fun dealiasedPeek(f: Field): Field {
        if (!prefs.dealias || !f.product.isDoppler || f.nyquist <= 0f) return f
        val vel = derived.peek("D|${f.site}/${f.volumeMs}/${f.sweepMs}/${f.elevation}/${f.nRays}/${f.variant}") ?: return f
        return if (f.product == Product.VEL) vel else vel.withCodes(vel.codes8, vel.codes16, vel.variant, product = f.product, stormU = f.stormU, stormV = f.stormV)
    }

    /** Same as [dealiased], on the background thread (inside a trail job). */
    private fun dealiasedNow(f: Field): Field {
        if (!prefs.dealias || !f.product.isDoppler || f.nyquist <= 0f) return f
        val key = "D|${f.site}/${f.volumeMs}/${f.sweepMs}/${f.elevation}/${f.nRays}/${f.variant}"
        val vel = derived.now(key) { Dealias.field(f.withCodes(f.codes8, f.codes16, f.variant, product = Product.VEL, stormU = 0f, stormV = 0f)) } ?: return f
        return if (f.product == Product.VEL) vel else vel.withCodes(vel.codes8, vel.codes16, vel.variant, product = f.product, stormU = f.stormU, stormV = f.stormV)
    }

    private fun trailKey(chain: List<Field>) =
        "T|${prefs.dealias}|" + chain.joinToString("|") { it.key }.hashCode() + "|" + chain.size + "|" + chain.last().key

    /**
     * The Σ trail ending with [chain]'s last field (chain: the frames up to the one shown, oldest first),
     * or null while it's being made. One job builds every step of the chain, so playing the loop
     * afterwards finds each frame's trail ready.
     */
    private fun trailOf(chain: List<Field>, p: Product, request: Boolean = true): Field? {
        if (chain.size < 2) return null
        val key = trailKey(chain)
        if (!request) return derived.peek(key)
        return derived.get(key, force = true) {
            // keep every step (each loop frame's trail) only when they all fit; otherwise just the end
            val stepBytes = (chain.last().codes8?.size?.toLong() ?: (chain.last().codes16!!.size * 2L))
            val keepSteps = derived.roomFor(stepBytes * chain.size * if (prefs.dealias) 2 else 1)
            var acc: Field? = null
            for (k in chain.indices) {
                val cur = if (keepSteps) dealiasedNow(chain[k]) else derived.peek(dealiasKey(chain[k]))?.let { vel -> asProduct(vel, chain[k]) } ?: dealiasOnly(chain[k])
                val prev = acc
                acc = if (prev == null) cur else {
                    val sub = trailKey(chain.subList(0, k + 1))
                    // the variant names the exact chain, so a different chain never reuses this one's GPU copy
                    val variant = "trail${k + 1}#${Integer.toHexString(sub.hashCode())}"
                    // a scan still in progress covers only part of the circle: add it onto the trail so far
                    val make = { if (!cur.complete) Trail.combine(prev, cur, Trail.rule(p), variant) else Trail.combine(cur, prev, Trail.rule(p), variant) }
                    if (keepSteps || k == chain.size - 1) derived.now(sub, make) else (derived.peek(sub) ?: make())
                }
            }
            acc
        }
    }

    /** What a panel shows for [base] (frame [index] of [frames], -1 = newest): dealiased and/or as a Σ trail. */
    private fun display(base: Field?, p: Product, frames: List<LoopFrame>, index: Int, request: Boolean = true): Field? {
        base ?: return null
        if (!request) {
            // preloading loop frames for the GPU: only what's ready, nothing new queued
            if (!prefs.trail || frames.isEmpty()) return if (prefs.dealias && p.isDoppler) dealiasedPeek(base) else base
        } else if (!prefs.trail || frames.isEmpty()) return dealiased(base, force = index < 0 || index == shownFrame())
        // wait for the previous scans to finish loading: they arrive newest first, which would restart the trail each time
        if (dm.loopTotal > 0 && dm.loopReady < dm.loopTotal) return if (request) dealiased(base) else dealiasedPeek(base)
        val upto = if (index < 0 || index >= frames.size) frames else frames.subList(0, index + 1)
        val chain = ArrayList<Field>(upto.size + 1)
        for (fr in upto) frameField(fr, p)?.let { chain.add(it) }
        if (index < 0 || index >= frames.size) chain.add(base) else if (chain.lastOrNull() !== base) chain.add(base)
        return trailOf(chain, p, request) ?: if (request) dealiased(base) else dealiasedPeek(base)
    }

    /** Index of the loop frame on screen (-1: the newest). */
    private fun shownFrame() = if (looping) frameIndex else -1

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

    /** The line for a warning code (TOR, TORP, SVRD...): the user's choice, else the default (NWS colours). */
    fun warnLine(code: String): Alerts.Line {
        prefs.warnLine(code)?.let { return it }
        val v = Alerts.VARIANTS[code] ?: Alerts.VARIANTS.getValue("SPS")
        // 1.1.0 kept one colour per event: still honoured for the event's base line
        val legacy = if (Alerts.BASE_CODE[v.event] == code) prefs.warnColor(v.event) else null
        return if (legacy != null) Alerts.Line(legacy, v.line.width, v.line.kind) else v.line
    }

    fun warnColor(a: Alert): Int = warnLine(a.variant).color

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
        // higher threat levels drawn last, on top
        alertLayers = near.sortedBy { Alerts.VARIANTS[it.variant]?.priority ?: 0f }.map { a ->
            val ln = warnLine(a.variant)
            val w = ln.width * d * 1.1f
            LayerDraw(ProjectedLayer.fromRings(a.rings, g.proj), ln.color, w, halo = !a.isWatch, innerPx = w * ln.innerShare)
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
        val frames = if (looping || prefs.trail) loopFrameList() else emptyList()
        val shownIndex = shownFrame()
        val baseTimes = ArrayList<Long?>()          // the scan each panel shows (a trail's own times can be older)
        for (i in 0 until state.panelCount) {
            val p = products[i]
            val table = palettes.table(p.palette)
            val pk = pick(p, frames)
            val base = pk.field
            baseTimes.add(base?.sweepMs)
            val f = display(base, p, frames, shownIndex)
            panelDraws.add(PanelDraw(f, table, prefs.smooth))
            var title = "$siteId  ${p.short} ${if (base != null) String.format(java.util.Locale.US, "%.1f°", base.elevation) else ""}".trim()
            if (base != null) {
                val v = f?.variant ?: ""
                val n = TRAIL_RE.find(v)?.groupValues?.get(1)
                if (n != null) title += "  Σ $n scans" else if (prefs.trail && frames.isNotEmpty()) title += "  Σ …"
                if (prefs.dealias && p.isDoppler && base.nyquist > 0f) title += if (n != null || v.contains("dealiased")) "  dealiased" else "  dealiasing…"
            }
            val sub = if (base != null) "${Time.hmsZ(base.sweepMs)} · ${Time.local(base.sweepMs)}" else (if (ref == null) "waiting for data" else "")
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

        // loop frames to keep on the GPU so playback is smooth (what each frame shows: trail / dealiased)
        val keep = ArrayList<Field>()
        if (looping) {
            for ((k, fr) in frames.withIndex()) {
                for (pi in 0 until state.panelCount) {
                    val p = products[pi]
                    // dealiasing every frame up front is worth it (playback then never waits); trails come from the shown frame's job
                    display(frameField(fr, p), p, frames, k, request = !prefs.trail)?.let { keep.add(it) }
                }
            }
            val n = frames.size + 1
            loopSeek.max = maxOf(0, n - 1)
            val idx = if (frameIndex < 0 || frameIndex >= frames.size) n - 1 else frameIndex
            loopSeek.progress = idx
            val t = baseTimes.firstOrNull { it != null }
            val loading = dm.loopTotal > 0 && dm.loopReady < dm.loopTotal
            loopTime.text = when {
                loading && n <= 1 -> "Loading ${dm.loopReady}/${dm.loopTotal}…"
                t != null -> "${Time.local(t)}  ${idx + 1}/$n" + if (loading) " …" else ""
                else -> "${idx + 1}/$n"
            }
            if (n <= 1 && !loading && dm.loopError != null) loopTime.text = "No earlier scans yet"
        }

        renderer.scene = Scene(C.mapBg, C.mapGap, layers, if (spcLayers.isEmpty()) alertLayers else spcLayers + alertLayers, panelDraws, keep)
        overlay.panels = infos
        overlay.dataTimeMs = baseTimes.getOrNull(state.activePanel) ?: baseTimes.firstOrNull { it != null } ?: 0L
        overlay.showCities = prefs.layer("cities")
        overlay.showSites = prefs.layer("sites")
        overlay.invalidate()
        requestRender()
        if (inspectAt != null && prefs.learn) updateLearnCard()
    }

    // ---------------------------------------------------------------- overlay callbacks
    override fun onMapMoved() {
        requestRender()
    }

    override fun onSiteTapped(site: Site) {
        if (site.id == this.site?.id) return
        stopFollowing()
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

    override fun onReportsTapped(reports: List<StormReport>) {
        if (reports.size == 1) Sheets.reportDetail(this, reports[0]) else Sheets.reportList(this, reports, "Storm reports here")
    }

    override fun onChasersTapped(chasers: List<Chaser>) {
        if (chasers.size == 1) Sheets.chaserDetail(this, chasers[0]) else Sheets.chaserList(this, chasers)
    }

    override fun onMcdTapped(mcds: List<MesoDiscussion>) {
        Sheets.mcdDetail(this, mcds[0])
    }

    override fun onMapTapped(lat: Double, lon: Double): Boolean {
        if (!prefs.layer("outlook") || dm.outlook.isEmpty() || dm.outlookShownDay != prefs.outlookDay) return false
        val hits = dm.outlook.filter { it.contains(lat, lon) }
        if (hits.none { it.category == "CATEGORICAL" }) return false
        Sheets.outlookDetail(this, lat, lon, hits)
        return true
    }

    override fun onUserMovedMap() {
        if (following) setFollowing(false)
    }

    private var inspectAt: FloatArray? = null

    override fun onInspect(km: FloatArray?, panel: Int) {
        inspectAt = km
        updateLearnCard()
    }

    /** Learn mode: plain-language notes for the spot under the cross-hair (all products of the scan shown there). */
    fun updateLearnCard() {
        if (!::learnCard.isInitialized) return
        val km = inspectAt
        if (!prefs.learn || km == null) { learnCard.visibility = View.GONE; return }
        val frames = if (looping) loopFrameList() else emptyList()
        val ground = Math.hypot(km[0].toDouble(), km[1].toDouble())
        val az = Geo.azimuthDeg(km[0].toDouble(), km[1].toDouble())
        val shown = prefs.panelProducts.take(state.panelCount)
        val velProduct = if (Product.SRV in shown) Product.SRV else Product.VEL
        val values = HashMap<Product, Float>()
        var beamFt: Double? = null
        for (p in listOf(Product.REF, velProduct, Product.ZDR, Product.CC)) {
            val base = pick(p, frames).field ?: continue
            val sm = dealiased(base).sample(az, ground) ?: continue
            if (!sm.rangeFolded) values[p] = sm.value
            if (beamFt == null) beamFt = sm.heightKm * 3280.84
        }
        val notes = Learn.explain(values, beamFt)
        learnCard.text = if (notes.isEmpty()) "No echo here. Press and hold on the coloured radar data to see what it means."
            else notes.joinToString("\n") + "\nTap for the radar guide."
        learnCard.visibility = View.VISIBLE
    }

    private var pendingZoom: Alert? = null
    private var pendingCenter: DoubleArray? = null

    /** Goes to a point (a storm report, a chaser): switches to the nearest radar when it's far away, then centres on it. */
    fun goToPoint(lat: Double, lon: Double) {
        val g = geo
        val far = g == null || Geo.distanceKm(g.site.lat, g.site.lon, lat, lon) > 230
        if (far && prefs.goToNearestRadar) {
            val n = Sites.nearest(sites, lat, lon)
            if (n != null && n.id != site?.id) {
                setFollowing(false)
                pendingCenter = doubleArrayOf(lat, lon)
                selectSite(n)
                toast("Switched to ${n.id} – the nearest radar")
                return
            }
        }
        setFollowing(false)
        centerOn(lat, lon)
    }

    /** Centres the map on (lat, lon), zooming in a little if the view is wide. */
    fun centerOn(lat: Double, lon: Double, zoomIn: Boolean = true) {
        val g = geo ?: return
        val xy = FloatArray(2)
        g.proj.forward(lat, lon, xy, 0)
        state.cx = xy[0]; state.cy = xy[1]
        if (zoomIn && state.scale < 2.5f) state.scale = 2.5f
        overlay.invalidate()
        requestRender()
    }

    /** Goes to a warning: switches to the radar nearest it (when that setting is on), then zooms in. */
    fun goToAlert(a: Alert) {
        stopFollowing()
        if (prefs.goToNearestRadar) {
            val lat = (a.minLat + a.maxLat) / 2.0
            val lon = (a.minLon + a.maxLon) / 2.0
            val n = Sites.nearest(sites, lat, lon)
            if (n != null && n.id != site?.id) {
                pendingZoom = a                 // zoom once the new radar's map is ready
                selectSite(n)
                toast("Switched to ${n.id} – the nearest radar to this warning")
                return
            }
        }
        zoomTo(a)
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

    fun requestLocationPermission() =
        requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION_LIVE)

    private fun locateMe() {
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION_CENTER)
            return
        }
        if (following) {
            setFollowing(false)
            toast("Stopped following your location")
            return
        }
        setFollowing(true)
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
        if (overlay.tool == OverlayView.Tool.TRACK) onToolChanged()      // "You: ..." arrival time
    }

    // ---------------------------------------------------------------- live location, following
    var following = false
        private set
    private var liveListener: LocationListener? = null

    private fun setFollowing(on: Boolean) {
        if (following == on) return
        following = on
        if (::locateBtn.isInitialized) locateBtn.setTint(if (on) C.accent else C.text)
        // following uses GPS; otherwise the cheaper network location is enough for the dot
        stopLiveLocation()
        startLiveLocation()
    }

    /** The user went somewhere else on purpose (picked a radar, opened a warning): stop following. */
    fun stopFollowing() {
        if (following) {
            setFollowing(false)
            toast("Stopped following your location")
        }
    }

    /** Keeps the location dot current while the app is open (Settings → My location), and while following. */
    @SuppressLint("MissingPermission")
    fun startLiveLocation() {
        if (liveListener != null || !hasLocationPermission() || !(prefs.liveLocation || following)) return
        val lm = locationManager ?: (getSystemService(LOCATION_SERVICE) as LocationManager).also { locationManager = it }
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) = onLiveLocation(loc)
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        fun enabled(p: String) = try { lm.isProviderEnabled(p) } catch (_: Exception) { false }
        // GPS while following (or when there's no network location); network location otherwise
        val providers = ArrayList<String>()
        if (fine && (following || !enabled(LocationManager.NETWORK_PROVIDER))) providers.add(LocationManager.GPS_PROVIDER)
        providers.add(LocationManager.NETWORK_PROVIDER)
        var any = false
        for (p in providers) {
            try {
                if (!enabled(p)) continue
                lm.requestLocationUpdates(p, if (following) 5_000L else 30_000L, if (following) 15f else 100f, l, Looper.getMainLooper())
                any = true
            } catch (e: Exception) {
                RfLog.w("live location ($p) failed: ${e.message}")
            }
        }
        if (any) liveListener = l
    }

    fun stopLiveLocation() {
        val l = liveListener ?: return
        liveListener = null
        try { locationManager?.removeUpdates(l) } catch (_: Exception) {}
    }

    private fun onLiveLocation(loc: Location) {
        // a coarse network fix shouldn't replace a recent, much better GPS fix
        val prev = lastLocation
        if (prev != null && loc.time - prev.time < 20_000 && loc.hasAccuracy() && prev.hasAccuracy() &&
            loc.accuracy > prev.accuracy * 3 && loc.provider != prev.provider) return
        setLocation(loc)
        checkWarningsAtLocation()
        if (!following) return
        val g = geo
        if (prefs.autoSwitchRadar && g != null) {
            val n = Sites.nearest(sites, loc.latitude, loc.longitude)
            if (n != null && n.id != g.site.id &&
                Geo.distanceKm(loc.latitude, loc.longitude, g.site.lat, g.site.lon) - Geo.distanceKm(loc.latitude, loc.longitude, n.lat, n.lon) > 30) {
                toast("Switched to ${n.id} – now the nearest radar")
                selectSite(n)            // centres on you again once its map is ready
                return
            }
        }
        centerOn(loc.latitude, loc.longitude, zoomIn = false)
    }

    // ---------------------------------------------------------------- warning for my location
    /** Warnings already opened for your location (kept across restarts until they expire). */
    private val notified: MutableMap<String, Pair<Float, Long>> by lazy { prefs.notifiedWarnings.toMutableMap() }

    /**
     * Opens a tornado / severe / flash flood warning that covers where you are (and vibrates):
     * once per warning, and again only if it's upgraded (e.g. to a PDS or emergency).
     */
    private fun checkWarningsAtLocation() {
        if (!prefs.warnAtLocation || !prefs.layer("warnings")) return
        val loc = lastLocation ?: return
        val now = System.currentTimeMillis()
        if (now - loc.time > 30 * 60_000L) return
        fun prio(a: Alert) = Alerts.VARIANTS[a.variant]?.priority ?: 0f
        val hits = dm.alerts.filter {
            !it.isWatch && Alerts.group(it.event) in setOf("tornado", "severe", "flood") && it.action !in setOf("CAN", "EXP") &&
                (it.expiresMs == 0L || it.expiresMs > now) && it.contains(loc.latitude, loc.longitude)
        }
        val fresh = hits.filter { h -> notified[h.trackKey].let { it == null || prio(h) > it.first } }
        if (fresh.isEmpty()) return
        notified.entries.removeAll { it.value.second < now - 3_600_000L }
        for (h in hits) {
            val old = notified[h.trackKey]
            notified[h.trackKey] = maxOf(prio(h), old?.first ?: 0f) to (if (h.expiresMs > 0) h.expiresMs else now + 2 * 3_600_000L)
        }
        prefs.notifiedWarnings = notified
        val top = fresh.maxByOrNull { prio(it) } ?: return
        try {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 450, 200, 450, 200, 450), -1))
        } catch (_: Exception) {}
        if (!sheets.isOpen) Sheets.alertDetail(this, top, forYou = true) else toast("${top.variantLabel} for your location")
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
        startLiveLocation()
        when (requestCode) {
            REQ_LOCATION_NEAREST -> pickNearestRadar()
            REQ_LOCATION_CENTER -> locateMe()
        }
    }

    // ---------------------------------------------------------------- share a picture of the map
    private var sharing = false

    /** Captures the map (radar + overlay) and opens the share sheet. */
    fun shareScreenshot() {
        if (sharing) return
        sharing = true
        renderer.capture = { bmp -> main.post { finishScreenshot(bmp) } }
        requestRender()
        main.postDelayed({ if (sharing) { sharing = false; renderer.capture = null; toast("Couldn't capture the map") } }, 4000)
    }

    private fun finishScreenshot(full: Bitmap?) {
        if (!sharing) { full?.recycle(); return }
        sharing = false
        if (full == null) { toast("Couldn't capture the map"); return }
        try {
            val a = state.area
            val left = a.left.toInt().coerceIn(0, full.width - 1)
            val top = a.top.toInt().coerceIn(0, full.height - 1)
            val w = a.width().toInt().coerceAtMost(full.width - left)
            val h = a.height().toInt().coerceAtMost(full.height - top)
            val d = resources.displayMetrics.density
            val t = overlay.dataTimeMs.takeIf { it > 0 } ?: System.currentTimeMillis()
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * resources.displayMetrics.scaledDensity }
            val left1 = "RadarForge  ·  ${site?.id ?: ""}  ·  ${Time.ymd(t)} ${Time.hmZ(t)}"
            val right1 = "Not an official warning source"
            val oneLine = p.measureText(left1) + p.measureText(right1) * 1.08f + 30 * d < w
            val line = p.fontSpacing
            val footer = ((if (oneLine) 1 else 2) * line + 10 * d).toInt()
            val out = Bitmap.createBitmap(w, h + footer, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(full, android.graphics.Rect(left, top, left + w, top + h), android.graphics.Rect(0, 0, w, h), null)
            full.recycle()
            // labels, legend, warnings text etc. drawn by the overlay, shifted so the map area starts at 0,0
            c.save()
            c.clipRect(0, 0, w, h)
            c.translate(-left.toFloat(), -top.toFloat())
            overlay.draw(c)
            c.restore()
            p.color = C.panel
            c.drawRect(0f, h.toFloat(), w.toFloat(), (h + footer).toFloat(), p)
            p.color = C.dim
            val base = h + 5 * d - p.fontMetrics.ascent
            c.drawText(left1, 10 * d, base, p)
            p.typeface = Typeface.DEFAULT_BOLD
            if (oneLine) {
                p.textAlign = Paint.Align.RIGHT
                c.drawText(right1, w - 10 * d, base, p)
            } else {
                c.drawText(right1, 10 * d, base + line, p)
            }
            val name = "RadarForge-${site?.id ?: "map"}-${Time.iso(t).replace(Regex("[^0-9]"), "").take(12)}.png"
            // encoding a full-screen PNG takes a moment: off the main thread
            worker.execute {
                try {
                    val dir = java.io.File(cacheDir, "shots").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }                 // only ever keep the newest
                    java.io.File(dir, name).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    out.recycle()
                    main.post {
                        val uri = Uri.parse("content://${ShotProvider.AUTHORITY}/$name")
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try { startActivity(Intent.createChooser(send, "Share the radar picture")) }
                        catch (e: Exception) { toast("No app to share with") }
                    }
                } catch (e: Throwable) {
                    RfLog.e("screenshot save failed", e)
                    main.post { toast("Couldn't save the picture: ${e.message ?: e.javaClass.simpleName}") }
                }
            }
        } catch (e: Throwable) {
            RfLog.e("screenshot failed", e)
            toast("Couldn't share the picture: ${e.message ?: e.javaClass.simpleName}")
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
        const val REQ_LOCATION_LIVE = 13
        const val REQ_IMPORT_PAL = 21
        /** Bump to show the "what's new" sheet once after an update. */
        const val WHATS_NEW = "1.4.0"
        private val TRAIL_RE = Regex("trail(\\d+)")
    }
}
