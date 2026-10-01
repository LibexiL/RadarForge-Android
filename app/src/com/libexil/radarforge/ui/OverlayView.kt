package com.libexil.radarforge.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.libexil.radarforge.core.Alert
import com.libexil.radarforge.core.ColorTable
import com.libexil.radarforge.core.Field
import com.libexil.radarforge.core.Geo
import com.libexil.radarforge.core.Product
import com.libexil.radarforge.core.ProjectedCities
import com.libexil.radarforge.core.Site
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Drawn above the GL map: city labels, radar sites, panel headers, colour legends,
 * the inspector cross-hair and readout. Also turns touches into pan / zoom / taps.
 */
class OverlayView(ctx: Context, private val state: MapState) : View(ctx) {

    interface Callbacks {
        fun onMapMoved()
        fun onSiteTapped(site: Site)
        fun onAlertsTapped(alerts: List<Alert>)
        fun onPanelTapped(index: Int)
    }

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
            if (tapSite(i, e.x, e.y)) return true
            if (tapAlerts(i, e.x, e.y)) return true
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

    // ---------------------------------------------------------------- drawing
    override fun onDraw(c: Canvas) {
        val rects = state.panelRects()
        for ((i, r) in rects.withIndex()) {
            c.save()
            c.clipRect(r)
            occupied.clear()
            drawLocation(c, r)
            if (showSites) drawSites(c, r)
            if (showCities) drawCities(c, r)
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
