package com.libexil.radarforge.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pop-out menus: a small card that opens next to the button that asked for it (above a button in the bottom
 * dock, below one in the top bar). A tap outside it or Back closes it. One at a time.
 */
class PopHost(ctx: Context) : FrameLayout(ctx) {
    private var card: View? = null
    private var anchorView: View? = null
    /** The pop-out's key (e.g. "product"), so the same button closes it again. */
    var key: String? = null
        private set
    var onClosed: (() -> Unit)? = null
    val isOpen: Boolean get() = card != null

    init {
        visibility = GONE
        isClickable = true
    }

    // this view is GONE (and unmeasured) while no pop-out is open: go by the window it fills
    private fun hostW() = (parent as? View)?.width?.takeIf { it > 0 } ?: width
    private fun hostH() = (parent as? View)?.height?.takeIf { it > 0 } ?: height

    /**
     * Shows [content] next to [anchor]. [above]: opens upwards (bottom dock); otherwise downwards (top bar).
     * [alignEnd]: the card's right edge lines up with the anchor's. [widthDp] 0 = as wide as the content needs.
     */
    fun show(key: String, anchor: View, content: View, widthDp: Float = 0f, above: Boolean = true,
             alignEnd: Boolean = false, scroll: Boolean = false, scrollToEnd: Boolean = false) {
        close(immediate = true)
        this.key = key
        anchorView = anchor
        visibility = VISIBLE
        setBackgroundColor(0x2e000000)
        val inner: View = if (scroll) ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            addView(content)
            if (scrollToEnd) post { fullScroll(View.FOCUS_DOWN) }
        } else content
        val c = object : FrameLayout(context) {
            override fun onMeasure(w: Int, h: Int) {
                // never taller than the space on its side of the button
                val room = (if (above) anchorTop() else hostH() - anchorBottom()) - dpi(12f)
                val hs = MeasureSpec.makeMeasureSpec(maxOf(dpi(120f), minOf(room, MeasureSpec.getSize(h))), MeasureSpec.AT_MOST)
                super.onMeasure(w, hs)
            }
        }.apply {
            background = rounded(Themes.withAlpha(C.panel, 0xf8), dp(18f), C.border, dpi(1f))
            elevation = dp(10f)
            setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(10f))
            addView(inner, LayoutParams(-1, -2))
            alpha = 0f
            isClickable = true                 // taps inside don't close it
        }
        card = c
        val w = if (widthDp > 0f) minOf(dpi(widthDp), hostW() - dpi(20f)).coerceAtLeast(dpi(120f)) else LayoutParams.WRAP_CONTENT
        addView(c, LayoutParams(w, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        // place it once it has its size (the first layout after showing)
        c.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (v.width == 0 || this@PopHost.width == 0) return
                v.removeOnLayoutChangeListener(this)
                if (card === v) place(c, above, alignEnd)
            }
        })
    }

    private fun anchorTop(): Int = anchorView?.let { relY(it) } ?: hostH()
    private fun anchorBottom(): Int = anchorView?.let { relY(it) + it.height } ?: 0

    private fun relY(v: View): Int {
        val a = IntArray(2); val b = IntArray(2)
        v.getLocationInWindow(a); getLocationInWindow(b)
        return a[1] - b[1]
    }

    private fun relX(v: View): Int {
        val a = IntArray(2); val b = IntArray(2)
        v.getLocationInWindow(a); getLocationInWindow(b)
        return a[0] - b[0]
    }

    private fun place(c: View, above: Boolean, alignEnd: Boolean) {
        val a = anchorView ?: return
        val m = dpi(10f)
        val ax = relX(a)
        var x = if (alignEnd) ax + a.width - c.width else ax
        x = x.coerceIn(m, maxOf(m, width - m - c.width))
        // above: clear of the whole bottom dock, not just the button, so it never covers the dock
        val dockTop = (a.parent as? View)?.let { p -> relY(p) } ?: relY(a)
        var y = if (above) minOf(relY(a), dockTop) - c.height - dpi(8f) else relY(a) + a.height + dpi(8f)
        y = y.coerceIn(m, maxOf(m, height - m - c.height))
        c.x = x.toFloat(); c.y = y.toFloat()
        c.pivotX = (ax + a.width / 2 - x).toFloat().coerceIn(0f, c.width.toFloat())
        c.pivotY = if (above) c.height.toFloat() else 0f
        c.scaleX = 0.92f; c.scaleY = 0.92f
        c.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
    }

    /** Lays the card out again (its content changed size) and keeps it beside its button. */
    fun refit() {
        val c = card ?: return
        c.requestLayout()
        c.post {
            val a = anchorView ?: return@post
            val above = c.pivotY > 0f
            val m = dpi(10f)
            val dockTop = (a.parent as? View)?.let { p -> relY(p) } ?: relY(a)
            val y = if (above) minOf(relY(a), dockTop) - c.height - dpi(8f) else relY(a) + a.height + dpi(8f)
            c.y = y.coerceIn(m, maxOf(m, height - m - c.height)).toFloat()
        }
    }

    fun close(immediate: Boolean = false) {
        val c = card ?: return
        card = null
        key = null
        anchorView = null
        if (immediate) {
            removeView(c)
            visibility = GONE
        } else {
            c.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(100).withEndAction {
                removeView(c)
                if (card == null) visibility = GONE
            }.start()
            setBackgroundColor(0)
        }
        onClosed?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isOpen) close()
        return isOpen
    }
}

/** Pieces the pop-out menus are made of. */
object PopUi {
    fun title(ctx: Context, s: String) = W.text(ctx, s.uppercase(), 11f, C.dim, true).apply {
        letterSpacing = 0.08f
        setPadding(ctx.dpi(4f), ctx.dpi(2f), 0, ctx.dpi(8f))
    }

    private fun selBg(ctx: Context, on: Boolean) =
        if (on) rounded(Themes.withAlpha(C.accent, 0x2e), ctx.dp(12f), C.accent, ctx.dpi(1.5f))
        else rounded(Themes.withAlpha(C.text, 0x0d), ctx.dp(12f))

    /** A product tile: code, name and a colour strip. */
    fun tile(ctx: Context, code: String, name: String, strip: Bitmap?, on: Boolean, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(10f), ctx.dpi(8f), ctx.dpi(10f), ctx.dpi(9f))
            background = ripple(selBg(ctx, on), rounded(0xffffffff.toInt(), ctx.dp(12f)))
            isClickable = true
            contentDescription = name
            setOnClickListener { onClick() }
            addView(W.text(ctx, code, 14.5f, C.text, true))
            addView(W.text(ctx, name, 11f, C.dim).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dpi(1f) })
            if (strip != null) addView(View(ctx).apply {
                background = BitmapDrawable(ctx.resources, strip).apply { isFilterBitmap = true }
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, v.height / 2f) }
                }
            }, LinearLayout.LayoutParams(-1, ctx.dpi(4f)).apply { topMargin = ctx.dpi(6f) })
        }

    /** A menu row: icon, title and an optional line under it. */
    fun row(ctx: Context, icon: Icon?, title: String, sub: String? = null, on: Boolean = false, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(9f), ctx.dpi(8f), ctx.dpi(10f), ctx.dpi(8f))
            background = ripple(if (on) selBg(ctx, true) else null, rounded(0xffffffff.toInt(), ctx.dp(12f)))
            isClickable = true
            contentDescription = title
            setOnClickListener { onClick() }
            if (icon != null) addView(IconView(ctx, icon, if (on) C.accent else C.text),
                LinearLayout.LayoutParams(ctx.dpi(22f), ctx.dpi(22f)).apply { rightMargin = ctx.dpi(12f) })
            val col = W.vCol(ctx)
            col.addView(W.text(ctx, title, 14.5f, C.text, on))
            if (sub != null) col.addView(W.text(ctx, sub, 11.5f, C.dim))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }

    /** A text row for lists (tilts): label left, a dim note right. */
    fun listItem(ctx: Context, label: String, note: String?, on: Boolean, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(12f), ctx.dpi(9f), ctx.dpi(12f), ctx.dpi(9f))
            background = ripple(if (on) selBg(ctx, true) else null, rounded(0xffffffff.toInt(), ctx.dp(11f)))
            isClickable = true
            setOnClickListener { onClick() }
            addView(W.text(ctx, label, 15f, C.text, on), LinearLayout.LayoutParams(0, -2, 1f))
            if (note != null) addView(W.text(ctx, note, 11.5f, C.dim))
        }

    /** A small button that fills its share of a row. */
    fun pill(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        W.text(ctx, label, 13f, if (primary) C.accentText else C.text, primary).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dpi(10f), ctx.dpi(8f), ctx.dpi(10f), ctx.dpi(8f))
            background = ripple(if (primary) rounded(C.accent, ctx.dp(11f)) else rounded(Themes.withAlpha(C.text, 0x12), ctx.dp(11f)),
                rounded(0xffffffff.toInt(), ctx.dp(11f)))
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** A picture choice (panel layouts): icon over a label. */
    fun choice(ctx: Context, icon: Icon, label: String, on: Boolean, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dpi(8f), ctx.dpi(9f), ctx.dpi(8f), ctx.dpi(7f))
            background = ripple(selBg(ctx, on), rounded(0xffffffff.toInt(), ctx.dp(12f)))
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(IconView(ctx, icon, if (on) C.text else C.dim), LinearLayout.LayoutParams(ctx.dpi(26f), ctx.dpi(26f)))
            addView(W.text(ctx, label, 11.5f, if (on) C.text else C.dim), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dpi(3f) })
        }

    /** A see-through rounded background for the floating bars. */
    fun glass(ctx: Context, radiusDp: Float) = rounded(Themes.withAlpha(C.panel, 0xdc), ctx.dp(radiusDp), Themes.withAlpha(C.text, 0x1c), ctx.dpi(1f))
}
