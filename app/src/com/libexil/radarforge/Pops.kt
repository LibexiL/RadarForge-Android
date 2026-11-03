package com.libexil.radarforge

import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import com.libexil.radarforge.core.Product
import com.libexil.radarforge.core.Tilts
import com.libexil.radarforge.ui.C
import com.libexil.radarforge.ui.Icon
import com.libexil.radarforge.ui.OverlayView
import com.libexil.radarforge.ui.PopUi
import com.libexil.radarforge.ui.W
import com.libexil.radarforge.ui.dpi

/** The pop-out menus behind the dock and top-bar buttons. */
object Pops {
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }

    /** Opens [key]'s pop-out, or closes it when it's already open (tapping the button again). */
    private fun toggle(a: MainActivity, key: String): Boolean {
        if (a.pops.isOpen && a.pops.key == key) { a.pops.close(); return false }
        if (a.sheets.isOpen) a.sheets.close()
        return true
    }

    private fun opened(a: MainActivity, anchor: View) {
        a.markPicker(anchor, true)
        a.pops.onClosed = { a.markPicker(anchor, false); a.pops.onClosed = null }
    }

    /** Every product with its full name and colours, plus the display options that go with them. */
    fun product(a: MainActivity) {
        if (!toggle(a, "product")) return
        val col = W.vCol(a)
        col.addView(PopUi.title(a, if (a.state.panelCount > 1) "Product · panel ${a.state.activePanel + 1}" else "Product"))
        val cur = a.prefs.panelProducts.getOrNull(a.state.activePanel) ?: Product.REF
        val grid = GridLayout(a).apply { columnCount = 2 }
        val gap = a.dpi(7f)
        for ((k, p) in Product.entries.withIndex()) {
            val t = PopUi.tile(a, p.short, p.title, a.legendStrip(p), p == cur) {
                a.pops.close()
                a.setProduct(p)
            }
            grid.addView(t, GridLayout.LayoutParams(GridLayout.spec(k / 2), GridLayout.spec(k % 2, 1f)).apply {
                width = 0
                setMargins(if (k % 2 == 1) gap / 2 else 0, if (k >= 2) gap else 0, if (k % 2 == 0) gap / 2 else 0, 0)
            })
        }
        col.addView(grid, lp())
        col.addView(View(a).apply { setBackgroundColor(C.border) }, LinearLayout.LayoutParams(-1, a.dpi(1f)).apply { topMargin = a.dpi(10f); bottomMargin = a.dpi(2f) })
        col.addView(W.switchRow(a, "Smooth the radar", null, a.prefs.smooth) { a.prefs.smooth = it; a.displayChanged() })
        if (cur.isVelocity) col.addView(W.switchRow(a, "Dealias velocity", null, a.prefs.dealias) { a.prefs.dealias = it; a.displayChanged() })
        a.pops.show("product", a.productBtn, col, widthDp = 340f, above = true, scroll = true)
        opened(a, a.productBtn)
    }

    /** Every tilt of the scan, highest at the top, with step buttons that leave the pop-out open. */
    fun tilt(a: MainActivity) {
        if (!toggle(a, "tilt")) return
        val col = W.vCol(a)
        fun fill() {
            col.removeAllViews()
            col.addView(PopUi.title(a, "Tilt"))
            val tilts = a.tilts
            if (tilts.isEmpty()) {
                col.addView(W.note(a, "The tilts show once the first scan has loaded."))
            } else {
                val cur = Tilts.closest(tilts, a.prefs.tilt)
                for (i in tilts.indices.reversed()) {
                    val t = tilts[i]
                    val label = String.format(java.util.Locale.US, "%.1f°", t.elevation)
                    col.addView(PopUi.listItem(a, label, if (t.scans.size > 1) "×${t.scans.size} scans" else null, i == cur) {
                        a.setTiltIndex(i); a.pops.close()
                    }, lp())
                }
            }
            val steps = W.hRow(a, 0f)
            steps.addView(PopUi.pill(a, "▼  Lower") { a.stepTilt(-1); fill(); a.pops.refit() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = a.dpi(7f) })
            steps.addView(PopUi.pill(a, "▲  Higher") { a.stepTilt(1); fill(); a.pops.refit() }, LinearLayout.LayoutParams(0, -2, 1f))
            col.addView(steps, lp(a.dpi(8f)))
        }
        fill()
        a.pops.show("tilt", a.tiltBtn, col, widthDp = 200f, above = true, scroll = true, scrollToEnd = true)
        opened(a, a.tiltBtn)
    }

    /** One, two or four panels, as pictures. */
    fun panels(a: MainActivity) {
        if (!toggle(a, "panels")) return
        val col = W.vCol(a)
        col.addView(PopUi.title(a, "Panels"))
        for ((n, icon, label) in listOf(Triple(1, Icon.PANELS1, "One"), Triple(2, Icon.PANELS2, "Two"), Triple(4, Icon.PANELS4, "Four"))) {
            col.addView(PopUi.choice(a, icon, label, a.state.panelCount == n) { a.pops.close(); a.setPanels(n) },
                lp(if (n > 1) a.dpi(7f) else 0))
        }
        a.pops.show("panels", a.panelsBtn, col, widthDp = 96f, above = true, alignEnd = true)
    }

    /** The measuring tools, the pen and the value inspector. */
    fun tools(a: MainActivity) {
        if (!toggle(a, "tools")) return
        val t = a.overlay.tool
        val col = W.vCol(a)
        col.addView(PopUi.title(a, "Tools"))
        fun pick(tool: OverlayView.Tool) { a.pops.close(); a.openTool(tool) }
        col.addView(PopUi.row(a, Icon.RULER, "Distance", "Tap points to measure", t == OverlayView.Tool.DISTANCE) { pick(OverlayView.Tool.DISTANCE) }, lp())
        col.addView(PopUi.row(a, Icon.TRACK, "Storm track", "Arrival times for towns", t == OverlayView.Tool.TRACK) { pick(OverlayView.Tool.TRACK) }, lp())
        col.addView(PopUi.row(a, Icon.PEN, "Pen", "Draw on the map", t == OverlayView.Tool.PEN) { pick(OverlayView.Tool.PEN) }, lp())
        col.addView(PopUi.row(a, Icon.INSPECT, "Learn mode", if (a.prefs.learn) "On · press and hold the map" else "Explains the values under your finger", a.prefs.learn) {
            a.pops.close(); a.prefs.learn = !a.prefs.learn; a.displayChanged()
            a.toast(if (a.prefs.learn) "Learn mode on: press and hold the map" else "Learn mode off")
        }, lp())
        if (t != OverlayView.Tool.NONE) {
            col.addView(PopUi.pill(a, "Close the tools", primary = true) { a.pops.close(); a.closeTools() }, lp(a.dpi(8f)))
        }
        a.pops.show("tools", a.toolBtn, col, widthDp = 250f, above = true, alignEnd = true)
    }

    /** Settings, looks, guides: what used to sit behind the settings button. */
    fun more(a: MainActivity) {
        if (!toggle(a, "more")) return
        val col = W.vCol(a)
        fun go(f: () -> Unit) { a.pops.close(); f() }
        col.addView(PopUi.row(a, Icon.SLIDERS, "Settings", "Units, loop, alerts, data") { go { Sheets.settings(a) } }, lp())
        col.addView(PopUi.row(a, Icon.PALETTE, "Theme & colours", "Dark, light, accent colour") { go { Sheets.themes(a) } }, lp())
        col.addView(PopUi.row(a, Icon.LAYERS, "Severe · Winter · Tropical", "Their pages and one-tap setups") { go { SheetsWx.layers(a) } }, lp())
        col.addView(PopUi.row(a, Icon.SHARE, "Share a picture of the map", null) { go { a.shareScreenshot() } }, lp())
        col.addView(PopUi.row(a, Icon.BOOK, "Radar guide", "What the products and colours mean") { go { Sheets.radarGuide(a) } }, lp())
        col.addView(PopUi.row(a, Icon.STAR, "What's new", null) { go { Sheets.whatsNew(a) } }, lp())
        a.pops.show("more", a.moreBtn, col, widthDp = 270f, above = false, alignEnd = true, scroll = true)
    }
}
