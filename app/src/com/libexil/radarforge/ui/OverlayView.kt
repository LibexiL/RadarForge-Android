package com.libexil.radarforge.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.libexil.radarforge.core.Alert
import com.libexil.radarforge.core.Chaser
import com.libexil.radarforge.core.ColorTable
import com.libexil.radarforge.core.Field
import com.libexil.radarforge.core.Geo
import com.libexil.radarforge.core.Measure
import com.libexil.radarforge.core.MesoDiscussion
import com.libexil.radarforge.core.Product
import com.libexil.radarforge.core.ProjectedCities
import com.libexil.radarforge.core.Site
import com.libexil.radarforge.core.StormReport
import com.libexil.radarforge.core.Time
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Drawn above the GL map: city labels, radar sites, storm reports, chasers, panel headers,
 * colour legends, the inspector, and the measuring tools. Also turns touches into pan / zoom / taps.
 */
class OverlayView(ctx: Context, private val state: MapState) : View(ctx) {

    interface Callbacks {
        fun onMapMoved()
        fun onSiteTapped(site: Site)
        fun onAlertsTapped(alerts: List<Alert>)
        fun onPanelTapped(index: Int)
        fun onReportsTapped(reports: List<StormReport>)
        fun onChasersTapped(chasers: List<Chaser>)
        fun onMcdTapped(mcds: List<MesoDiscussion>)
        /** A tap on the map at (lat, lon) that nothing else claimed; true if it was used (SPC outlook). */
        fun onMapTapped(lat: Double, lon: Double): Boolean
        /** The user dragged the map (stops following their location; zooming doesn't). */
        fun onUserMovedMap()
        /** A measuring tool changed (points added / moved, mode switched). */
        fun onToolChanged()
        /** Where a storm placed at [startKm] will be after [minutes] (from the storm motion setting). */
        fun trackEndFor(startKm: FloatArray, minutes: Int): FloatArray
    }

    enum class Tool { NONE, DISTANCE, TRACK }

    /** A text label on the map at a km position (SPC outlook risk names). */
    class MapLabel(val text: String, val color: Int, val x: Float, val y: Float)

    class PanelInfo(
        val product: Product,
        val field: Field?,
        val table: ColorTable,
        val title: String,          // "KTLX  BR 0.5°"
        val subtitle: String,       // "19:42:10Z · 3 min ago"
        val note: String?,          // e.g. "previous volume" / "loading…"
    )

    var callbacks: Callbacks? = null
    var panels: List<PanelInfo> = emptyList()
    var cities: ProjectedCities? = null
    var sites: List<Site> = emptyList()
    var siteXY = FloatArray(0)
    var currentSite = ""
    var alerts: List<Alert> = emptyList()
    var proj: Geo.Aeqd? = null
    var locationKm: FloatArray? = null
    var showCities = true
    var showSites = true
    var showLegend = true
    var velUnits = "kts"
    var distUnits = "mi"

    // storm reports, chasers and SPC labels (positions in km, two floats per item)
    var reports: List<StormReport> = emptyList()
    var reportXY = FloatArray(0)
    var reportHours = 6
    var chasers: List<Chaser> = emptyList()
    var chaserXY = FloatArray(0)
    var chaserNames = true
    var mcds: List<MesoDiscussion> = emptyList()
    var mcdXY = FloatArray(0)
    var outlookLabels: List<MapLabel> = emptyList()

    /** Time of the scan shown in the active panel (storm-track times count from it). */
    var dataTimeMs = 0L

    // ---------------------------------------------------------------- measuring tools
    var tool = Tool.NONE
        private set
    /** Distance tool points (km). */
    val ruler = ArrayList<FloatArray>()
    /** Storm track: the storm now (A) and after [trackMinutes] (B), km. */
    var trackA: FloatArray? = null
        private set
    var trackB: FloatArray? = null
        private set
    var trackMinutes = 60
        private set
    var trackStartMs = 0L
        private set
    var trackEtas: List<Measure.Eta> = emptyList()
        private set
    val trackHalfWidthKm = 10.0
    private var dragHandle: Int? = null
    private var dragPanel = 0
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var dragMoved = false
    private val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

    /** Inspector position (km) or null when hidden. */
    var inspectKm: FloatArray? = null
        private set
    private var inspectDragging = false

    // ---------------------------------------------------------------- paints
    private val density = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * sp; color = C.cityText }
    private val cityHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.5f * sp; color = C.halo; style = Paint.Style.STROKE; strokeWidth = 3.2f * density; strokeJoin = Paint.Join.ROUND
    }
    private val headBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * sp; typeface = Typeface.DEFAULT_BOLD; color = C.text }
    private val headSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * sp; color = 0xffb9bcc6.toInt() }
    private val legendText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f * sp; color = C.text; textAlign = Paint.Align.CENTER }
    private val siteText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * sp; typeface = Typeface.DEFAULT_BOLD; color = C.siteText }
    private val siteHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * sp; typeface = Typeface.DEFAULT_BOLD; color = C.halo; style = Paint.Style.STROKE; strokeWidth = 3f * density
    }
    private val readBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f * sp; typeface = Typeface.DEFAULT_BOLD; color = C.text }
    private val readSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * sp; color = 0xffc4c7d0.toInt() }
    private val tmpRect = RectF()
    private val occupied = ArrayList<RectF>()
    private val legendBitmaps = IdentityHashMap<ColorTable, Bitmap>()

    // ---------------------------------------------------------------- gestures
    private val scroller = OverScroller(ctx)
    private var flingX = 0
    private var flingY = 0
    private var scaledInGesture = false

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            scroller.forceFinished(true)
            scaledInGesture = true
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val i = state.panelAt(d.focusX, d.focusY).coerceAtLeast(0)
            state.zoomAt(i, d.focusX, d.focusY, d.scaleFactor)
            moved()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            scaledInGesture = false
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (inspectDragging) return true
            state.panBy(-dx, -dy)
            if (!scaledInGesture && e2.pointerCount == 1 && hypot(dx, dy) > 0.5f) callbacks?.onUserMovedMap()
            moved()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (inspectDragging || scaledInGesture) return true
            flingX = 0; flingY = 0
            scroller.fling(0, 0, vx.toInt(), vy.toInt(), -100_000, 100_000, -100_000, 100_000)
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean = true

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && !scaledInGesture) {
                val fx = e.x
                val fy = e.y                     // the event object is reused after this call
                val i = state.panelAt(fx, fy).coerceAtLeast(0)
                var last = 1f
                animateFloat(1f, 2f, 260) { f ->
                    state.zoomAt(i, fx, fy, f / last)
                    last = f
                    moved()
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val i = state.panelAt(e.x, e.y)
            if (i < 0) return
            inspectKm = state.toKm(i, e.x, e.y)
            inspectDragging = true
            performHapticFeedback(HAPTIC_FEEDBACK_ENABLED_LONG_PRESS)
            invalidate()
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (inspectKm != null) {
                inspectKm = null
                invalidate()
                return true
            }
            val i = state.panelAt(e.x, e.y)
            if (i < 0) return false
            if (tool != Tool.NONE) {
                toolTap(i, e.x, e.y)
                return true
            }
            if (tapMarkers(i, e.x, e.y)) return true
            if (tapSite(i, e.x, e.y)) return true
            if (tapAlerts(i, e.x, e.y)) return true
            if (state.panelCount > 1 && i != state.activePanel) {
                callbacks?.onPanelTapped(i)
                return true
            }
            if (tapMcd(i, e.x, e.y)) return true
            val ll = proj?.let { p -> state.toKm(i, e.x, e.y).let { km -> p.inverse(km[0].toDouble(), km[1].toDouble()) } }
            if (ll != null && callbacks?.onMapTapped(ll[0], ll[1]) == true) return true
            callbacks?.onPanelTapped(i)
            return true
        }
    })

    private fun moved() {
        callbacks?.onMapMoved()
        invalidate()
    }

    /** HAPTIC_FEEDBACK_ENABLED_LONG_PRESS isn't public: use the long-press constant. */
    private val HAPTIC_FEEDBACK_ENABLED_LONG_PRESS = android.view.HapticFeedbackConstants.LONG_PRESS

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tool != Tool.NONE && !inspectDragging && handleDrag(e)) return true
        if (inspectDragging) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val i = state.panelAt(e.x, e.y)
                    if (i >= 0) { inspectKm = state.toKm(i, e.x, e.y); invalidate() }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    inspectDragging = false
                    gestures.onTouchEvent(e)
                    return true
                }
            }
        }
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val x = scroller.currX
            val y = scroller.currY
            state.panBy((x - flingX).toFloat(), (y - flingY).toFloat())
            flingX = x; flingY = y
            callbacks?.onMapMoved()
            postInvalidateOnAnimation()
        }
    }

    fun hideInspector() {
        inspectKm = null
        invalidate()
    }

    private fun tapSite(panel: Int, x: Float, y: Float): Boolean {
        if (!showSites || state.viewWidthKm() > 3000) return false
        val r = state.panelRects().getOrNull(panel) ?: return false
        val tol = 22f * density
        var best = -1
        var bestD = tol
        for (k in sites.indices) {
            val sx = state.toScreenX(r, siteXY[2 * k])
            val sy = state.toScreenY(r, siteXY[2 * k + 1])
            val d = hypot(sx - x, sy - y)
            if (d < bestD) { bestD = d; best = k }
        }
        if (best < 0) return false
        callbacks?.onSiteTapped(sites[best])
        return true
    }

    private fun tapAlerts(panel: Int, x: Float, y: Float): Boolean {
        val p = proj ?: return false
        if (alerts.isEmpty()) return false
        val km = state.toKm(panel, x, y)
        val ll = p.inverse(km[0].toDouble(), km[1].toDouble())
        val hits = alerts.filter { it.contains(ll[0], ll[1]) }.sortedByDescending { it.style.priority }
        if (hits.isEmpty()) return false
        callbacks?.onAlertsTapped(hits)
        return true
    }

    /** Storm reports and chasers under the finger: the closest kind wins, with everything of it nearby. */
    private fun tapMarkers(panel: Int, x: Float, y: Float): Boolean {
        if (reports.isEmpty() && chasers.isEmpty()) return false
        val r = state.panelRects().getOrNull(panel) ?: return false
        val tol = 20f * density
        fun dist(xy: FloatArray, k: Int) = hypot(state.toScreenX(r, xy[2 * k]) - x, state.toScreenY(r, xy[2 * k + 1]) - y)
        var best = tol
        var kind = 0
        for (k in reports.indices) if (2 * k + 1 < reportXY.size) dist(reportXY, k).let { if (it < best) { best = it; kind = 1 } }
        for (k in chasers.indices) if (2 * k + 1 < chaserXY.size) dist(chaserXY, k).let { if (it < best) { best = it; kind = 2 } }
        when (kind) {
            1 -> callbacks?.onReportsTapped(reports.indices.filter { 2 * it + 1 < reportXY.size && dist(reportXY, it) < tol }
                .sortedBy { dist(reportXY, it) }.map { reports[it] })
            2 -> callbacks?.onChasersTapped(chasers.indices.filter { 2 * it + 1 < chaserXY.size && dist(chaserXY, it) < tol }
                .sortedBy { dist(chaserXY, it) }.map { chasers[it] })
            else -> return false
        }
        return true
    }

    private fun tapMcd(panel: Int, x: Float, y: Float): Boolean {
        val p = proj ?: return false
        if (mcds.isEmpty()) return false
        val km = state.toKm(panel, x, y)
        val ll = p.inverse(km[0].toDouble(), km[1].toDouble())
        val hits = mcds.filter { it.contains(ll[0], ll[1]) }
        if (hits.isEmpty()) return false
        callbacks?.onMcdTapped(hits)
        return true
    }

    // ---------------------------------------------------------------- measuring tools
    fun setTool(t: Tool) {
        if (t == tool) return
        tool = t
        dragHandle = null
        invalidate()
        callbacks?.onToolChanged()
    }

    fun clearTool() {
        when (tool) {
            Tool.DISTANCE -> ruler.clear()
            Tool.TRACK -> { trackA = null; trackB = null; trackEtas = emptyList() }
            Tool.NONE -> {}
        }
        invalidate()
        callbacks?.onToolChanged()
    }

    fun undoTool() {
        when (tool) {
            Tool.DISTANCE -> if (ruler.isNotEmpty()) ruler.removeAt(ruler.size - 1)
            Tool.TRACK -> { trackA = null; trackB = null; trackEtas = emptyList() }
            Tool.NONE -> {}
        }
        invalidate()
        callbacks?.onToolChanged()
    }

    /** Changes how long the track covers, keeping the storm's speed. */
    fun setTrackMinutes(m: Int) {
        val a = trackA; val b = trackB
        if (a != null && b != null && trackMinutes > 0) {
            val f = m.toFloat() / trackMinutes
            trackB = floatArrayOf(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
        }
        trackMinutes = m
        updateTrack()
    }

    /** Sets the track's motion (e.g. from the storm motion setting) keeping the storm where it is. */
    fun setTrackEnd(b: FloatArray) {
        if (trackA == null) return
        trackB = b
        updateTrack()
    }

    /** Recomputes the towns on the track (also after the map data changes). */
    fun updateTrack() {
        val a = trackA; val b = trackB
        trackEtas = if (a != null && b != null) Measure.etas(a[0], a[1], b[0], b[1], trackMinutes.toDouble(), cities, trackHalfWidthKm) else emptyList()
        invalidate()
        callbacks?.onToolChanged()
    }

    /** The radar changed: km positions mean something else now. */
    fun resetTools() {
        ruler.clear(); trackA = null; trackB = null; trackEtas = emptyList()
        dragHandle = null
        invalidate()
        callbacks?.onToolChanged()
    }

    private fun toolTap(panel: Int, x: Float, y: Float) {
        val km = state.toKm(panel, x, y)
        when (tool) {
            Tool.DISTANCE -> {
                if (ruler.size >= 25) return
                ruler.add(floatArrayOf(km[0], km[1]))
                invalidate()
                callbacks?.onToolChanged()
            }
            Tool.TRACK -> {
                val a = trackA; val b = trackB
                if (a != null && b != null) {
                    // tap somewhere else: move the storm, keep its motion
                    trackB = floatArrayOf(b[0] + km[0] - a[0], b[1] + km[1] - a[1])
                    trackA = floatArrayOf(km[0], km[1])
                } else {
                    trackA = floatArrayOf(km[0], km[1])
                    trackB = callbacks?.trackEndFor(trackA!!, trackMinutes) ?: floatArrayOf(km[0] + 40f, km[1] + 20f)
                }
                trackStartMs = if (dataTimeMs > 0) dataTimeMs else System.currentTimeMillis()
                updateTrack()
            }
            Tool.NONE -> {}
        }
    }

    private val HANDLE_A = -1
    private val HANDLE_B = -2

    /** The handle (ruler point index, HANDLE_A, HANDLE_B) under the finger in panel [panel], or null. */
    private fun handleAt(panel: Int, x: Float, y: Float): Int? {
        val r = state.panelRects().getOrNull(panel) ?: return null
        var best: Int? = null
        var bestD = 26f * density
        fun test(h: Int, km: FloatArray?) {
            km ?: return
            val d = hypot(state.toScreenX(r, km[0]) - x, state.toScreenY(r, km[1]) - y)
            if (d < bestD) { bestD = d; best = h }
        }
        when (tool) {
            Tool.DISTANCE -> for (k in ruler.indices.reversed()) test(k, ruler[k])
            Tool.TRACK -> { test(HANDLE_B, trackB); test(HANDLE_A, trackA) }
            Tool.NONE -> {}
        }
        return best
    }

    /** Dragging a ruler point or the storm track's ends. True when the event was used. */
    private fun handleDrag(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val panel = state.panelAt(e.x, e.y)
                if (panel < 0) return false
                val h = handleAt(panel, e.x, e.y) ?: return false
                dragHandle = h
                dragPanel = panel
                dragDownX = e.x; dragDownY = e.y
                dragMoved = false
                scroller.forceFinished(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val h = dragHandle ?: return false
                if (!dragMoved) {
                    if (hypot(e.x - dragDownX, e.y - dragDownY) < touchSlop) return true
                    dragMoved = true
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                val km = state.toKm(dragPanel, e.x, e.y)
                when (h) {
                    HANDLE_A -> {
                        val a = trackA; val b = trackB
                        if (a != null && b != null) {
                            trackB = floatArrayOf(b[0] + km[0] - a[0], b[1] + km[1] - a[1])
                            trackA = floatArrayOf(km[0], km[1])
                        }
                        updateTrack()
                    }
                    HANDLE_B -> { trackB = floatArrayOf(km[0], km[1]); updateTrack() }
                    else -> if (h in ruler.indices) {
                        ruler[h] = floatArrayOf(km[0], km[1])
                        invalidate()
                        callbacks?.onToolChanged()
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return dragHandle != null
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val h = dragHandle ?: return false
                dragHandle = null
                // a tap (no drag) near, but not on, a ruler point still adds a point;
                // a tap on a point or on the track's ends does nothing
                if (!dragMoved && e.actionMasked == MotionEvent.ACTION_UP && h in ruler.indices) {
                    val r = state.panelRects().getOrNull(dragPanel)
                    val p = ruler[h]
                    if (r != null && hypot(state.toScreenX(r, p[0]) - e.x, state.toScreenY(r, p[1]) - e.y) > 8f * density) toolTap(dragPanel, e.x, e.y)
                }
                return true
            }
        }
        return dragHandle != null
    }

    /** Great-circle (km, bearing) of each ruler leg. */
    fun rulerLegs(): List<Pair<Double, Double>> {
        val p = proj ?: return emptyList()
        val ll = ruler.map { p.inverse(it[0].toDouble(), it[1].toDouble()) }
        return (1 until ll.size).map { k ->
            Geo.distanceKm(ll[k - 1][0], ll[k - 1][1], ll[k][0], ll[k][1]) to Geo.bearingDeg(ll[k - 1][0], ll[k - 1][1], ll[k][0], ll[k][1])
        }
    }

    /** Storm track: (km per hour, heading in degrees) or null. */
    fun trackMotion(): Pair<Double, Double>? {
        val p = proj ?: return null
        val a = trackA ?: return null
        val b = trackB ?: return null
        val la = p.inverse(a[0].toDouble(), a[1].toDouble())
        val lb = p.inverse(b[0].toDouble(), b[1].toDouble())
        val km = Geo.distanceKm(la[0], la[1], lb[0], lb[1])
        if (km < 0.05) return null
        return km / (trackMinutes / 60.0) to Geo.bearingDeg(la[0], la[1], lb[0], lb[1])
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(c: Canvas) {
        val rects = state.panelRects()
        for ((i, r) in rects.withIndex()) {
            c.save()
            c.clipRect(r)
            occupied.clear()
            if (showSites) drawSites(c, r)
            if (showCities) drawCities(c, r)
            drawOutlookLabels(c, r)
            drawMcdLabels(c, r)
            drawReports(c, r)
            drawChasers(c, r)
            drawLocation(c, r)
            when (tool) {
                Tool.DISTANCE -> drawRuler(c, r)
                Tool.TRACK -> drawTrack(c, r)
                Tool.NONE -> {}
            }
            val info = panels.getOrNull(i)
            if (info != null) {
                drawHeader(c, r, info)
                if (showLegend) drawLegend(c, r, info)
                inspectKm?.let { drawInspector(c, r, info, it) }
            }
            c.restore()
            if (rects.size > 1) {
                stroke.color = if (i == state.activePanel) C.activeBorder else C.panelBorder
                stroke.strokeWidth = if (i == state.activePanel) 2.2f * density else 1f * density
                val h = stroke.strokeWidth / 2
                c.drawRect(r.left + h, r.top + h, r.right - h, r.bottom - h, stroke)
            }
        }
    }

    private fun drawLocation(c: Canvas, r: RectF) {
        val l = locationKm ?: return
        val x = state.toScreenX(r, l[0]); val y = state.toScreenY(r, l[1])
        fill.color = 0x553a8dff
        c.drawCircle(x, y, 13f * density, fill)
        fill.color = 0xffffffff.toInt()
        c.drawCircle(x, y, 7f * density, fill)
        fill.color = C.location
        c.drawCircle(x, y, 5f * density, fill)
    }

    private fun drawSites(c: Canvas, r: RectF) {
        if (state.viewWidthKm() > 3000) return
        val s = 4.2f * density
        for (k in sites.indices) {
            val x = state.toScreenX(r, siteXY[2 * k])
            val y = state.toScreenY(r, siteXY[2 * k + 1])
            if (x < r.left - 40 || x > r.right + 40 || y < r.top - 20 || y > r.bottom + 20) continue
            val cur = sites[k].id == currentSite
            fill.color = 0xff000000.toInt()
            c.drawRect(x - s - 1.5f * density, y - s - 1.5f * density, x + s + 1.5f * density, y + s + 1.5f * density, fill)
            fill.color = if (cur) C.siteCurrent else C.site88d
            c.drawRect(x - s, y - s, x + s, y + s, fill)
            val label = sites[k].id
            val tx = x + s + 4f * density
            val ty = y + siteText.textSize * 0.36f
            c.drawText(label, tx, ty, siteHalo)
            siteText.color = if (cur) C.siteCurrent else C.siteText
            c.drawText(label, tx, ty, siteText)
            occupied.add(RectF(x - s, y - s, tx + siteText.measureText(label), y + s))
        }
    }

    private fun drawCities(c: Canvas, r: RectF) {
        val cs = cities ?: return
        val w = state.viewWidthKm()
        val minPop = when {
            w > 1500 -> 400_000
            w > 800 -> 150_000
            w > 400 -> 40_000
            w > 200 -> 12_000
            w > 100 -> 5_000
            else -> 2_000
        }
        val maxLabels = ((r.width() * r.height()) / (density * density * 9000f)).toInt().coerceIn(6, 60)
        var n = 0
        val dot = 2.6f * density
        val fm = cityPaint.fontMetrics
        for (k in cs.x.indices) {
            if (cs.pop[k] < minPop) break                       // sorted biggest first
            val x = state.toScreenX(r, cs.x[k])
            val y = state.toScreenY(r, cs.y[k])
            if (x < r.left || x > r.right || y < r.top + 40 * density || y > r.bottom - 10 * density) continue
            val name = cs.names[k]
            val tw = cityPaint.measureText(name)
            tmpRect.set(x - dot, y + fm.ascent * 0.6f, x + dot + 4 * density + tw, y - fm.ascent * 0.6f)
            if (occupied.any { RectF.intersects(it, tmpRect) }) continue
            occupied.add(RectF(tmpRect).apply { inset(-3 * density, -2 * density) })
            fill.color = C.halo
            c.drawCircle(x, y, dot + 1.2f * density, fill)
            fill.color = C.cityText
            c.drawCircle(x, y, dot, fill)
            val ty = y - (fm.ascent + fm.descent) / 2
            c.drawText(name, x + dot + 4 * density, ty, cityHalo)
            c.drawText(name, x + dot + 4 * density, ty, cityPaint)
            if (++n >= maxLabels) break
        }
    }

    // ---------------------------------------------------------------- reports, chasers, SPC labels
    private val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; color = 0xff000000.toInt() }
    private val smallLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * sp; typeface = Typeface.DEFAULT_BOLD }
    private val smallHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * sp; typeface = Typeface.DEFAULT_BOLD; color = C.halo; style = Paint.Style.STROKE; strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    private fun inView(r: RectF, x: Float, y: Float, pad: Float) = x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad

    private fun haloText(c: Canvas, s: String, x: Float, y: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) {
        smallLabel.textAlign = align; smallHalo.textAlign = align
        c.drawText(s, x, y, smallHalo)
        smallLabel.color = color
        c.drawText(s, x, y, smallLabel)
    }

    private fun drawOutlookLabels(c: Canvas, r: RectF) {
        if (outlookLabels.isEmpty() || state.viewWidthKm() > 4000) return
        for (l in outlookLabels) {
            val x = state.toScreenX(r, l.x); val y = state.toScreenY(r, l.y)
            if (!inView(r, x, y, 0f)) continue
            haloText(c, l.text, x, y, l.color, Paint.Align.CENTER)
        }
    }

    private fun drawMcdLabels(c: Canvas, r: RectF) {
        if (mcds.isEmpty() || state.viewWidthKm() > 4000) return
        for (k in mcds.indices) {
            if (2 * k + 1 >= mcdXY.size) break
            val x = state.toScreenX(r, mcdXY[2 * k]); val y = state.toScreenY(r, mcdXY[2 * k + 1])
            if (!inView(r, x, y, 0f)) continue
            haloText(c, "MD ${mcds[k].number}", x, y - 4f * density, com.libexil.radarforge.core.Spc.MCD_COLOR, Paint.Align.CENTER)
        }
    }

    private fun drawReports(c: Canvas, r: RectF) {
        if (reports.isEmpty()) return
        val far = state.viewWidthKm() > 1600
        val now = System.currentTimeMillis()
        val window = maxOf(2L, reportHours.toLong()) * 3_600_000L
        // the list is newest first: draw backwards so the newest end up on top
        for (k in reports.indices.reversed()) {
            if (2 * k + 1 >= reportXY.size) continue
            val x = state.toScreenX(r, reportXY[2 * k]); val y = state.toScreenY(r, reportXY[2 * k + 1])
            if (!inView(r, x, y, 12f * density)) continue
            val rep = reports[k]
            val age = if (rep.timeMs > 0) now - rep.timeMs else 0L
            // reports fade with age, from full colour in the first hour to about half at the end of the window
            val a = if (age <= 3_600_000L) 1f else (1f - 0.55f * (age - 3_600_000L).toFloat() / (window - 3_600_000L)).coerceIn(0.45f, 1f)
            val two = rep.kind.letter.length > 1
            val rad = (if (far) 4f else if (two) 10f else 9f) * density
            fill.color = 0xff000000.toInt()
            fill.alpha = (a * 230).toInt()
            c.drawCircle(x, y, rad + 1.4f * density, fill)
            fill.color = rep.kind.color
            fill.alpha = (a * 255).toInt()
            c.drawCircle(x, y, rad, fill)
            fill.alpha = 255
            if (!far) {
                markerText.textSize = (if (two) 8.5f else 11f) * sp
                markerText.alpha = (a * 255).toInt()
                c.drawText(rep.kind.letter, x, y - (markerText.descent() + markerText.ascent()) / 2, markerText)
            }
        }
    }

    /** Chaser colour by how fresh the position is. */
    private fun chaserColor(timeMs: Long, now: Long): Int {
        val age = if (timeMs > 0) now - timeMs else Long.MAX_VALUE
        return when {
            age < 15 * 60_000L -> 0xff4fe3ff.toInt()
            age < 60 * 60_000L -> 0xffffc35a.toInt()
            else -> 0xff9aa0ab.toInt()
        }
    }

    private fun drawChasers(c: Canvas, r: RectF) {
        if (chasers.isEmpty()) return
        val now = System.currentTimeMillis()
        val w = state.viewWidthKm()
        val small = w > 1600
        val names = chaserNames && w < 600
        for (k in chasers.indices) {
            if (2 * k + 1 >= chaserXY.size) continue
            val x = state.toScreenX(r, chaserXY[2 * k]); val y = state.toScreenY(r, chaserXY[2 * k + 1])
            if (!inView(r, x, y, 12f * density)) continue
            val ch = chasers[k]
            val col = chaserColor(ch.timeMs, now)
            val h = ch.heading
            if (h != null && !small) {
                // an arrowhead pointing the way they're driving
                val rad = Math.toRadians(h.toDouble())
                fun pt(ang: Double, len: Float) = floatArrayOf(x + (sin(ang) * len).toFloat(), y - (cos(ang) * len).toFloat())
                val tip = pt(rad, 10f * density)
                val l = pt(rad + Math.toRadians(140.0), 8f * density)
                val m = pt(rad + Math.PI, 3.5f * density)
                val rr = pt(rad - Math.toRadians(140.0), 8f * density)
                path.reset()
                path.moveTo(tip[0], tip[1]); path.lineTo(l[0], l[1]); path.lineTo(m[0], m[1]); path.lineTo(rr[0], rr[1]); path.close()
                fill.color = col
                c.drawPath(path, fill)
                stroke.color = 0xff000000.toInt()
                stroke.strokeWidth = 1.5f * density
                stroke.strokeJoin = Paint.Join.ROUND
                c.drawPath(path, stroke)
            } else {
                val rad = (if (small) 3f else 5.5f) * density
                fill.color = 0xff000000.toInt()
                c.drawCircle(x, y, rad + 1.5f * density, fill)
                fill.color = col
                c.drawCircle(x, y, rad, fill)
            }
            if (names) haloText(c, ch.label, x + 11f * density, y + smallLabel.textSize * 0.36f, 0xffe8eef5.toInt())
        }
    }

    // ---------------------------------------------------------------- measuring tools: drawing
    private val toolYellow = 0xffffd23c.toInt()

    private fun line2(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, w: Float) {
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = 0xe6000000.toInt(); stroke.strokeWidth = w + 2.6f * density
        c.drawLine(x0, y0, x1, y1, stroke)
        stroke.color = color; stroke.strokeWidth = w
        c.drawLine(x0, y0, x1, y1, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun handle(c: Canvas, x: Float, y: Float, color: Int, rad: Float = 6.5f * density) {
        fill.color = 0xff000000.toInt()
        c.drawCircle(x, y, rad + 1.6f * density, fill)
        fill.color = color
        c.drawCircle(x, y, rad, fill)
    }

    /** A small dark label box centred at (x, y). */
    private fun tag(c: Canvas, s: String, x: Float, y: Float, color: Int = C.text) {
        smallLabel.textAlign = Paint.Align.CENTER
        val w = smallLabel.measureText(s) + 10f * density
        val h = smallLabel.textSize + 7f * density
        tmpRect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        fill.color = 0xd9000000.toInt()
        c.drawRoundRect(tmpRect, 6f * density, 6f * density, fill)
        smallLabel.color = color
        c.drawText(s, x, y - (smallLabel.descent() + smallLabel.ascent()) / 2, smallLabel)
    }

    private fun drawRuler(c: Canvas, r: RectF) {
        if (ruler.isEmpty()) return
        val xs = FloatArray(ruler.size) { state.toScreenX(r, ruler[it][0]) }
        val ys = FloatArray(ruler.size) { state.toScreenY(r, ruler[it][1]) }
        for (k in 1 until ruler.size) line2(c, xs[k - 1], ys[k - 1], xs[k], ys[k], 0xffffffff.toInt(), 2.4f * density)
        val legs = rulerLegs()
        for ((k, leg) in legs.withIndex()) {
            val mx = (xs[k] + xs[k + 1]) / 2; val my = (ys[k] + ys[k + 1]) / 2
            // beside the line, not on it
            val dx = xs[k + 1] - xs[k]; val dy = ys[k + 1] - ys[k]
            val len = maxOf(1f, hypot(dx, dy))
            val off = 14f * density
            tag(c, Geo.distText(leg.first, distUnits), mx - dy / len * off, my + dx / len * off)
        }
        for (k in ruler.indices) handle(c, xs[k], ys[k], if (k == 0) C.location else 0xffffffff.toInt())
    }

    private fun drawTrack(c: Canvas, r: RectF) {
        val a = trackA ?: return
        val b = trackB ?: return
        val ax = state.toScreenX(r, a[0]); val ay = state.toScreenY(r, a[1])
        val bx = state.toScreenX(r, b[0]); val by = state.toScreenY(r, b[1])
        val dx = bx - ax; val dy = by - ay
        val len = hypot(dx, dy)
        // towns the storm passes: ring them and show the arrival time
        for (e in trackEtas) {
            val x = state.toScreenX(r, e.x); val y = state.toScreenY(r, e.y)
            if (!inView(r, x, y, 20f * density)) continue
            stroke.color = toolYellow; stroke.strokeWidth = 2f * density
            c.drawCircle(x, y, 6f * density, stroke)
            haloText(c, Time.local(trackStartMs + (e.minutes * 60_000).toLong(), "h:mm"), x, y + 17f * density, toolYellow, Paint.Align.CENTER)
        }
        line2(c, ax, ay, bx, by, toolYellow, 3f * density)
        if (len > 4f * density) {
            val ux = dx / len; val uy = dy / len
            // arrowhead
            val hl = 13f * density; val hw = 7f * density
            path.reset()
            path.moveTo(bx + ux * 4f * density, by + uy * 4f * density)
            path.lineTo(bx - ux * hl - uy * hw, by - uy * hl + ux * hw)
            path.lineTo(bx - ux * hl + uy * hw, by - uy * hl - ux * hw)
            path.close()
            stroke.color = 0xe6000000.toInt(); stroke.strokeWidth = 2.6f * density; stroke.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, stroke)
            fill.color = toolYellow
            c.drawPath(path, fill)
            // a tick and a time every 10 / 15 / 30 minutes
            val step = Measure.tickMinutes(trackMinutes)
            var m = step
            while (m < trackMinutes) {
                val f = m.toFloat() / trackMinutes
                val px = ax + dx * f; val py = ay + dy * f
                val tw = 7f * density
                line2(c, px - uy * tw, py + ux * tw, px + uy * tw, py - ux * tw, toolYellow, 2f * density)
                if (len > 90f * density) {
                    val lx = px + uy * 26f * density; val ly = py - ux * 26f * density
                    tag(c, Time.local(trackStartMs + m * 60_000L, "h:mm"), lx, ly, toolYellow)
                }
                m += step
            }
            tag(c, Time.local(trackStartMs + trackMinutes * 60_000L, "h:mm"), bx + ux * 26f * density, by + uy * 26f * density, toolYellow)
        }
        handle(c, ax, ay, 0xffffffff.toInt(), 7f * density)
        handle(c, bx, by, toolYellow, 5f * density)
    }

    private fun drawHeader(c: Canvas, r: RectF, p: PanelInfo) {
        val pad = 7f * density
        val w1 = headBold.measureText(p.title)
        val w2 = headSmall.measureText(p.subtitle)
        val w3 = if (p.note != null) headSmall.measureText(p.note) else 0f
        val w = maxOf(w1, w2, w3) + pad * 2
        val lines = if (p.note != null) 3 else 2
        val h = pad * 2 + headBold.textSize + (lines - 1) * (headSmall.textSize + 4 * density)
        tmpRect.set(r.left + 6 * density, r.top + 6 * density, r.left + 6 * density + w, r.top + 6 * density + h)
        fill.color = C.labelBg
        c.drawRoundRect(tmpRect, 8 * density, 8 * density, fill)
        var y = tmpRect.top + pad + headBold.textSize * 0.85f
        c.drawText(p.title, tmpRect.left + pad, y, headBold)
        y += headSmall.textSize + 4 * density
        c.drawText(p.subtitle, tmpRect.left + pad, y, headSmall)
        if (p.note != null) {
            y += headSmall.textSize + 4 * density
            val old = headSmall.color
            headSmall.color = 0xffffc35a.toInt()
            c.drawText(p.note, tmpRect.left + pad, y, headSmall)
            headSmall.color = old
        }
        occupied.add(RectF(tmpRect))
    }

    private fun legendBitmap(ct: ColorTable): Bitmap = legendBitmaps.getOrPut(ct) {
        val lut = ct.lut(256)
        val px = IntArray(256) { i ->
            val a = lut[i * 4 + 3].toInt() and 0xff
            // show the table over the map background so transparent ends read correctly
            fun ch(k: Int, bg: Int) = ((lut[i * 4 + k].toInt() and 0xff) * a + bg * (255 - a)) / 255
            (0xff shl 24) or (ch(0, 8) shl 16) or (ch(1, 8) shl 8) or ch(2, 12)
        }
        Bitmap.createBitmap(px, 256, 1, Bitmap.Config.ARGB_8888)
    }

    private fun drawLegend(c: Canvas, r: RectF, p: PanelInfo) {
        val ct = p.table
        if (ct.entries.isEmpty()) return
        val margin = 8f * density
        val barH = 9f * density
        val labelH = legendText.textSize + 4 * density
        val box = RectF(r.left + margin, r.bottom - margin - barH - labelH - 6 * density, r.right - margin, r.bottom - margin)
        if (box.width() < 120 * density) return
        fill.color = C.labelBg
        c.drawRoundRect(box, 7 * density, 7 * density, fill)
        val unit = legendUnits(p)
        val unitW = if (unit.isNotEmpty()) legendText.measureText(unit) + 10 * density else 0f
        val bar = RectF(box.left + 8 * density, box.bottom - 5 * density - barH, box.right - 8 * density - unitW, box.bottom - 5 * density)
        val bmp = legendBitmap(ct)
        fill.isFilterBitmap = true
        c.drawBitmap(bmp, null, bar, fill)
        val lo = ct.vmin; val hi = ct.vmax
        val stops = ct.legendStops(((bar.width() / (34 * density)).toInt()).coerceIn(3, 14))
        val ty = bar.top - 4 * density
        var lastX = -1e9f
        for (v in stops) {
            val x = bar.left + (v - lo) / (hi - lo) * bar.width()
            val label = fmtLegend(v, ct)
            val lw = legendText.measureText(label)
            if (x - lw / 2 < lastX + 3 * density || x + lw / 2 > bar.right + 8 * density) continue
            c.drawText(label, x, ty, legendText)
            lastX = x + lw / 2
        }
        if (unit.isNotEmpty()) {
            legendText.textAlign = Paint.Align.LEFT
            c.drawText(unit, bar.right + 6 * density, bar.bottom, legendText)
            legendText.textAlign = Paint.Align.CENTER
        }
        occupied.add(RectF(box))
    }

    private fun legendUnits(p: PanelInfo): String {
        val u = p.table.units.trim()
        if (u.isNotEmpty()) return u.uppercase(Locale.US).let { if (it == "DBZ") "dBZ" else if (it == "DB") "dB" else it }
        return when (p.product) { Product.REF -> "dBZ"; Product.ZDR -> "dB"; Product.PHI -> "°"; else -> "" }
    }

    private fun fmtLegend(v: Float, ct: ColorTable): String {
        val span = ct.vmax - ct.vmin
        return when {
            span <= 2f -> String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
            span <= 20f && v != v.toInt().toFloat() -> String.format(Locale.US, "%.1f", v)
            else -> v.toInt().toString()
        }
    }

    // ---------------------------------------------------------------- inspector
    private fun drawInspector(c: Canvas, r: RectF, p: PanelInfo, km: FloatArray) {
        val x = state.toScreenX(r, km[0])
        val y = state.toScreenY(r, km[1])
        stroke.color = 0xff000000.toInt()
        stroke.strokeWidth = 4f * density
        val a = 14f * density
        c.drawLine(x - a, y, x + a, y, stroke); c.drawLine(x, y - a, x, y + a, stroke)
        stroke.color = 0xffffffff.toInt()
        stroke.strokeWidth = 1.8f * density
        c.drawLine(x - a, y, x + a, y, stroke); c.drawLine(x, y - a, x, y + a, stroke)
        c.drawCircle(x, y, 5f * density, stroke)

        val lines = readout(p, km)
        val pad = 9f * density
        val w = maxOf(readBig.measureText(lines[0]), lines.drop(1).maxOfOrNull { readSmall.measureText(it) } ?: 0f) + pad * 2
        val h = pad * 2 + readBig.textSize + (lines.size - 1) * (readSmall.textSize + 5 * density)
        // keep the box away from the finger: above the point, flipped when near the top
        var left = x - w / 2
        var top = y - a - 14 * density - h
        if (top < r.top + 4 * density) top = y + a + 14 * density
        left = left.coerceIn(r.left + 4 * density, maxOf(r.left + 4 * density, r.right - w - 4 * density))
        tmpRect.set(left, top, left + w, top + h)
        fill.color = 0xe6121318.toInt()
        c.drawRoundRect(tmpRect, 9 * density, 9 * density, fill)
        stroke.color = C.border
        stroke.strokeWidth = 1f * density
        c.drawRoundRect(tmpRect, 9 * density, 9 * density, stroke)
        var ty = top + pad + readBig.textSize * 0.85f
        c.drawText(lines[0], left + pad, ty, readBig)
        for (k in 1 until lines.size) {
            ty += readSmall.textSize + 5 * density
            c.drawText(lines[k], left + pad, ty, readSmall)
        }
    }

    private fun readout(p: PanelInfo, km: FloatArray): List<String> {
        val ground = hypot(km[0].toDouble(), km[1].toDouble())
        val az = Geo.azimuthDeg(km[0].toDouble(), km[1].toDouble())
        val dist = when (distUnits) {
            "km" -> String.format(Locale.US, "%.1f km", ground)
            "nm" -> String.format(Locale.US, "%.1f nm", ground * 0.539957)
            else -> String.format(Locale.US, "%.1f mi", ground * 0.621371)
        }
        val where = "$dist ${Geo.compass(az)} (${az.toInt()}°)"
        val f = p.field
        val s = f?.sample(az, ground)
        val value: String
        var beam: String? = null
        if (f == null) {
            value = "No data"
        } else {
            val h = Geo.beamHeight(Geo.slantRange(ground, f.elevation.toDouble()), f.elevation.toDouble())
            beam = if (distUnits == "km") String.format(Locale.US, "Beam %,d m ARL", (h * 1000).toInt())
            else String.format(Locale.US, "Beam %,d ft ARL", (h * 3280.84).toInt())
            value = when {
                s == null -> "${p.product.short}: no echo"
                s.rangeFolded -> "Range folded"
                else -> formatValue(p.product, s.value)
            }
        }
        val ll = proj?.inverse(km[0].toDouble(), km[1].toDouble())
        return listOfNotNull(value, where, beam, ll?.let { Geo.latLonText(it[0], it[1]) })
    }

    fun formatValue(product: Product, v: Float): String = when {
        product.isVelocity -> {
            val conv = when (velUnits) { "mph" -> 2.236936f; "m/s" -> 1f; else -> 1.943844f }
            val u = when (velUnits) { "mph" -> "mph"; "m/s" -> "m/s"; else -> "kts" }
            val x = v * conv
            if (product == Product.SW) String.format(Locale.US, "%.0f %s", abs(x), u)
            else String.format(Locale.US, "%.0f %s %s", abs(x), u, if (x < 0) "inbound" else "outbound")
        }
        product == Product.REF -> String.format(Locale.US, "%.1f dBZ", v)
        product == Product.ZDR -> String.format(Locale.US, "%.2f dB", v)
        product == Product.CC -> String.format(Locale.US, "CC %.3f", v)
        product == Product.PHI -> String.format(Locale.US, "%.1f°", v)
        else -> String.format(Locale.US, "%.1f", v)
    }
}
