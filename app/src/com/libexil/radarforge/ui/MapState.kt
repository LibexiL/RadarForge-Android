package com.libexil.radarforge.ui

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * The shared view: centre (km, radar-centred projection) and zoom (pixels per km),
 * plus how the map area is split into 1, 2 or 4 linked panels. Read by the GL
 * thread and the overlay, written on the UI thread.
 */
class MapState {
    @Volatile var cx = 0f
    @Volatile var cy = 0f
    @Volatile var scale = 1.5f
    @Volatile var panelCount = 1
    @Volatile var activePanel = 0

    // surface size and the map area inside it (screen pixels, y down)
    @Volatile var surfaceW = 1
    @Volatile var surfaceH = 1
    @Volatile var area = RectF(0f, 0f, 1f, 1f)

    val minScale = 0.12f
    val maxScale = 120f

    class View(val cx: Float, val cy: Float, val scale: Float, val panels: List<RectF>, val surfaceH: Int)

    fun snapshot(): View = View(cx, cy, scale, panelRects(), surfaceH)

    /** Panel rectangles in screen pixels (y down). */
    fun panelRects(): List<RectF> {
        val a = area
        val gap = 2f
        return when (panelCount) {
            2 -> if (a.width() > a.height() * 1.1f) {
                val w = (a.width() - gap) / 2
                listOf(RectF(a.left, a.top, a.left + w, a.bottom), RectF(a.left + w + gap, a.top, a.right, a.bottom))
            } else {
                val h = (a.height() - gap) / 2
                listOf(RectF(a.left, a.top, a.right, a.top + h), RectF(a.left, a.top + h + gap, a.right, a.bottom))
            }
            4 -> {
                val w = (a.width() - gap) / 2
                val h = (a.height() - gap) / 2
                listOf(RectF(a.left, a.top, a.left + w, a.top + h), RectF(a.left + w + gap, a.top, a.right, a.top + h),
                    RectF(a.left, a.top + h + gap, a.left + w, a.bottom), RectF(a.left + w + gap, a.top + h + gap, a.right, a.bottom))
            }
            else -> listOf(RectF(a))
        }
    }

    fun panelAt(x: Float, y: Float): Int {
        val r = panelRects()
        for (i in r.indices) if (r[i].contains(x, y)) return i
        return -1
    }

    /** km coordinates of a screen point inside panel [i]. */
    fun toKm(i: Int, x: Float, y: Float, out: FloatArray = FloatArray(2)): FloatArray {
        val r = panelRects().getOrElse(i) { area }
        out[0] = cx + (x - r.centerX()) / scale
        out[1] = cy - (y - r.centerY()) / scale
        return out
    }

    fun toScreenX(r: RectF, kmX: Float) = r.centerX() + (kmX - cx) * scale
    fun toScreenY(r: RectF, kmY: Float) = r.centerY() - (kmY - cy) * scale

    fun panBy(dxPx: Float, dyPx: Float) {
        cx -= dxPx / scale
        cy += dyPx / scale
        clampCenter()
    }

    /** Zoom by [factor] keeping the km point under the screen point (fx, fy) of panel [i] fixed. */
    fun zoomAt(i: Int, fx: Float, fy: Float, factor: Float) {
        val before = toKm(i, fx, fy)
        scale = min(maxScale, max(minScale, scale * factor))
        val after = toKm(i, fx, fy)
        cx += before[0] - after[0]
        cy += before[1] - after[1]
        clampCenter()
    }

    private fun clampCenter() {
        cx = cx.coerceIn(-2500f, 2500f)
        cy = cy.coerceIn(-2500f, 2500f)
    }

    /** Width of the view in km (largest panel). */
    fun viewWidthKm(): Float = (panelRects().maxOfOrNull { it.width() } ?: 1f) / scale
}
