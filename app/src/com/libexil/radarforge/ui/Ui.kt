package com.libexil.radarforge.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** RadarForge Dark (same colours as the PC version's default theme). */
object C {
    val window = 0xff26272d.toInt()
    val panel = 0xff1e1f24.toInt()
    val alt = 0xff2a2b31.toInt()
    val header = 0xff1f2025.toInt()
    val button = 0xff303138.toInt()
    val border = 0xff3a3c45.toInt()
    val text = 0xffe1e1e6.toInt()
    val dim = 0xff8d909b.toInt()
    val accent = 0xff3c6ec8.toInt()
    val accentText = 0xffffffff.toInt()
    val danger = 0xffe0524f.toInt()
    val ok = 0xff3fae6a.toInt()

    val mapBg = 0xff08080c.toInt()
    val mapGap = 0xff1f1f24.toInt()
    val states = 0xffe1e1e1.toInt()
    val countries = 0xffd7d7d7.toInt()
    val counties = 0xe6696969.toInt()
    val roads = 0xebaf4646.toInt()
    val roads2 = 0xdc78553c.toInt()
    val lakes = 0xc8466eaa.toInt()
    val rings = 0x96c8c8d7.toInt()
    val cityText = 0xffe1e1e1.toInt()
    val siteText = 0xffcde6d2.toInt()
    val site88d = 0xff28965a.toInt()
    val siteCurrent = 0xffffd700.toInt()
    val labelBg = 0xb9000000.toInt()
    val halo = 0xdc000000.toInt()
    val panelBorder = 0xff464650.toInt()
    val activeBorder = 0xff5a8cdc.toInt()
    val location = 0xff4aa3ff.toInt()
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Float): Float = context.dp(v)
fun View.dpi(v: Float): Int = context.dpi(v)

fun rounded(color: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
    if (strokePx > 0) setStroke(strokePx, stroke)
}

fun ripple(base: android.graphics.drawable.Drawable?, mask: android.graphics.drawable.Drawable? = null) =
    RippleDrawable(ColorStateList.valueOf(0x33ffffff), base, mask ?: base ?: ColorDrawable(Color.WHITE))

// ------------------------------------------------------------------------- icons
enum class Icon { MORE, LAYERS, WARNING, PLAY, PAUSE, LOCATE, PANELS1, PANELS2, PANELS4, UP, DOWN, CLOSE, SEARCH, REFRESH, RADAR, CHECK, SLIDERS }

/** Small vector icons drawn in code (no image resources needed). */
class IconView(ctx: Context, icon: Icon, private var tint: Int = C.text) : View(ctx) {
    var icon: Icon = icon
        set(v) { field = v; invalidate() }
    var badge: String? = null
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    fun setTint(c: Int) { tint = c; invalidate() }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) * 0.62f
        c.save()
        c.translate((width - s) / 2f, (height - s) / 2f)
        c.scale(s / 24f, s / 24f)
        p.color = tint
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.1f
        path.reset()
        when (icon) {
            Icon.MORE -> { p.style = Paint.Style.FILL; for (y in floatArrayOf(5f, 12f, 19f)) c.drawCircle(12f, y, 2.1f, p) }
            Icon.LAYERS -> {
                path.moveTo(12f, 3f); path.lineTo(22f, 8.5f); path.lineTo(12f, 14f); path.lineTo(2f, 8.5f); path.close()
                c.drawPath(path, p)
                path.reset(); path.moveTo(2f, 13f); path.lineTo(12f, 18.5f); path.lineTo(22f, 13f); c.drawPath(path, p)
                path.reset(); path.moveTo(2f, 17.5f); path.lineTo(12f, 23f); path.lineTo(22f, 17.5f); c.drawPath(path, p)
            }
            Icon.WARNING -> {
                path.moveTo(12f, 2.5f); path.lineTo(22.5f, 21f); path.lineTo(1.5f, 21f); path.close(); c.drawPath(path, p)
                c.drawLine(12f, 9f, 12f, 14.5f, p)
                p.style = Paint.Style.FILL; c.drawCircle(12f, 17.7f, 1.3f, p)
            }
            Icon.PLAY -> { p.style = Paint.Style.FILL; path.moveTo(6f, 3.5f); path.lineTo(20f, 12f); path.lineTo(6f, 20.5f); path.close(); c.drawPath(path, p) }
            Icon.PAUSE -> { p.style = Paint.Style.FILL; c.drawRoundRect(5f, 4f, 10f, 20f, 1.2f, 1.2f, p); c.drawRoundRect(14f, 4f, 19f, 20f, 1.2f, 1.2f, p) }
            Icon.LOCATE -> {
                c.drawCircle(12f, 12f, 7f, p)
                p.style = Paint.Style.FILL; c.drawCircle(12f, 12f, 2.6f, p); p.style = Paint.Style.STROKE
                c.drawLine(12f, 1f, 12f, 5f, p); c.drawLine(12f, 19f, 12f, 23f, p); c.drawLine(1f, 12f, 5f, 12f, p); c.drawLine(19f, 12f, 23f, 12f, p)
            }
            Icon.PANELS1 -> c.drawRoundRect(3f, 4f, 21f, 20f, 2f, 2f, p)
            Icon.PANELS2 -> { c.drawRoundRect(3f, 4f, 21f, 20f, 2f, 2f, p); c.drawLine(12f, 4f, 12f, 20f, p) }
            Icon.PANELS4 -> { c.drawRoundRect(3f, 4f, 21f, 20f, 2f, 2f, p); c.drawLine(12f, 4f, 12f, 20f, p); c.drawLine(3f, 12f, 21f, 12f, p) }
            Icon.UP -> { path.moveTo(5f, 15f); path.lineTo(12f, 8f); path.lineTo(19f, 15f); c.drawPath(path, p) }
            Icon.DOWN -> { path.moveTo(5f, 9f); path.lineTo(12f, 16f); path.lineTo(19f, 9f); c.drawPath(path, p) }
            Icon.CLOSE -> { c.drawLine(6f, 6f, 18f, 18f, p); c.drawLine(18f, 6f, 6f, 18f, p) }
            Icon.SEARCH -> { c.drawCircle(10.5f, 10.5f, 6.5f, p); c.drawLine(15.5f, 15.5f, 21f, 21f, p) }
            Icon.REFRESH -> {
                c.drawArc(RectF(4f, 4f, 20f, 20f), -60f, 290f, false, p)
                p.style = Paint.Style.FILL
                path.moveTo(20.5f, 3f); path.lineTo(21f, 10f); path.lineTo(14.5f, 8f); path.close(); c.drawPath(path, p)
            }
            Icon.RADAR -> {
                c.drawCircle(12f, 12f, 9.5f, p)
                c.drawCircle(12f, 12f, 5f, p)
                c.drawLine(12f, 12f, 19f, 5.5f, p)
                p.style = Paint.Style.FILL; c.drawCircle(12f, 12f, 1.8f, p)
            }
            Icon.CHECK -> { path.moveTo(4.5f, 12.5f); path.lineTo(9.5f, 17.5f); path.lineTo(19.5f, 6.5f); c.drawPath(path, p) }
            Icon.SLIDERS -> {
                c.drawLine(4f, 6f, 20f, 6f, p); c.drawLine(4f, 12f, 20f, 12f, p); c.drawLine(4f, 18f, 20f, 18f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(15f, 6f, 2.6f, p); c.drawCircle(8f, 12f, 2.6f, p); c.drawCircle(13f, 18f, 2.6f, p)
            }
        }
        c.restore()
        badge?.let { b ->
            p.style = Paint.Style.FILL
            p.color = C.danger
            val r = height * 0.19f
            val cx = width * 0.74f
            val cy = height * 0.26f
            p.textSize = r * 1.25f
            p.typeface = Typeface.DEFAULT_BOLD
            val w = maxOf(r * 2, p.measureText(b) + r)
            c.drawRoundRect(cx - w / 2, cy - r, cx + w / 2, cy + r, r, r, p)
            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            c.drawText(b, cx, cy + r * 0.45f, p)
            p.textAlign = Paint.Align.LEFT
        }
    }
}

// ------------------------------------------------------------------------- widgets
object W {
    fun text(ctx: Context, s: CharSequence = "", sizeSp: Float = 14f, color: Int = C.text, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    fun iconButton(ctx: Context, icon: Icon, desc: String, onClick: (View) -> Unit) = IconView(ctx, icon).apply {
        contentDescription = desc
        background = ripple(null, rounded(Color.WHITE, ctx.dp(22f)))
        isClickable = true
        isFocusable = true
        setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(ctx.dpi(44f), ctx.dpi(44f))
    }

    /** A pill-shaped toggle chip. */
    class Chip(ctx: Context, label: String) : TextView(ctx) {
        var selectedState = false
            set(v) { field = v; refresh() }

        init {
            text = label
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = ctx.dpi(46f)
            setPadding(ctx.dpi(12f), ctx.dpi(7f), ctx.dpi(12f), ctx.dpi(7f))
            isClickable = true
            isSingleLine = true
            refresh()
        }

        private fun refresh() {
            val bg = if (selectedState) C.accent else C.button
            background = ripple(rounded(bg, dp(16f), if (selectedState) C.accent else C.border, dpi(1f)))
            setTextColor(if (selectedState) C.accentText else C.text)
        }
    }

    fun chip(ctx: Context, label: String, selected: Boolean = false, onClick: (Chip) -> Unit) = Chip(ctx, label).apply {
        selectedState = selected
        setOnClickListener { onClick(this) }
    }

    fun hRow(ctx: Context, spacingDp: Float = 6f) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        tag = spacingDp
    }

    fun vCol(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun gap(ctx: Context, wDp: Float, hDp: Float = 1f) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dpi(wDp), ctx.dpi(hDp)) }

    fun weightSpace(ctx: Context) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    fun section(ctx: Context, title: String) = text(ctx, title.uppercase(), 12f, C.dim, true).apply {
        letterSpacing = 0.08f
        setPadding(ctx.dpi(4f), ctx.dpi(18f), 0, ctx.dpi(8f))
    }

    fun note(ctx: Context, s: CharSequence) = text(ctx, s, 13f, C.dim).apply {
        setLineSpacing(0f, 1.2f)
        setPadding(ctx.dpi(4f), ctx.dpi(2f), ctx.dpi(4f), ctx.dpi(8f))
    }

    /** A settings row with a switch. */
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    fun switchRow(ctx: Context, label: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = hRow(ctx).apply {
            setPadding(ctx.dpi(4f), ctx.dpi(8f), ctx.dpi(2f), ctx.dpi(8f))
            background = ripple(null, ColorDrawable(Color.WHITE))
        }
        val col = vCol(ctx)
        col.addView(text(ctx, label, 15f))
        if (desc != null) col.addView(text(ctx, desc, 12.5f, C.dim).apply { setPadding(0, ctx.dpi(3f), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(C.accent, 0xffb0b2ba.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x993c6ec8.toInt(), 0x66808390))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    /** A row of mutually exclusive chips. */
    fun segmented(ctx: Context, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): View {
        val row = hRow(ctx).apply { setPadding(ctx.dpi(2f), ctx.dpi(4f), 0, ctx.dpi(6f)) }
        val chips = ArrayList<Chip>()
        for ((value, label) in options) {
            val c = chip(ctx, label, value == selected) { me ->
                chips.forEach { it.selectedState = it === me }
                onSelect(value)
            }
            chips.add(c)
            row.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = ctx.dpi(6f) })
        }
        return row
    }

    /** Label + live value + slider. */
    fun slider(ctx: Context, label: String, min: Int, max: Int, value: Int, format: (Int) -> String, onChange: (Int) -> Unit): View {
        val col = vCol(ctx).apply { setPadding(ctx.dpi(4f), ctx.dpi(6f), ctx.dpi(4f), ctx.dpi(6f)) }
        val top = hRow(ctx)
        top.addView(text(ctx, label, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = text(ctx, format(value), 14f, C.dim, true)
        top.addView(v)
        col.addView(top)
        val sb = SeekBar(ctx).apply {
            this.max = max - min
            progress = value - min
            progressTintList = ColorStateList.valueOf(C.accent)
            thumbTintList = ColorStateList.valueOf(C.accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x66808390)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { v.text = format(p + min); if (fromUser) onChange(p + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(4f) })
        return col
    }

    /** A tappable list row: title, optional subtitle and trailing text. */
    fun listRow(ctx: Context, title: CharSequence, subtitle: CharSequence?, trailing: CharSequence? = null,
                highlighted: Boolean = false, onClick: () -> Unit): View {
        val row = hRow(ctx).apply {
            setPadding(ctx.dpi(12f), ctx.dpi(11f), ctx.dpi(12f), ctx.dpi(11f))
            background = ripple(if (highlighted) rounded(0x333c6ec8, ctx.dp(10f)) else null, rounded(Color.WHITE, ctx.dp(10f)))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val col = vCol(ctx)
        col.addView(text(ctx, title, 15.5f, if (highlighted) 0xff8fb4ff.toInt() else C.text, true).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
        if (subtitle != null) col.addView(text(ctx, subtitle, 13f, C.dim).apply { setPadding(0, ctx.dpi(3f), 0, 0); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) row.addView(text(ctx, trailing, 13f, C.dim).apply { setPadding(ctx.dpi(8f), 0, 0, 0) })
        return row
    }

    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (primary) C.accentText else C.text)
        setPadding(ctx.dpi(16f), ctx.dpi(11f), ctx.dpi(16f), ctx.dpi(11f))
        background = ripple(rounded(if (primary) C.accent else C.button, ctx.dp(10f), C.border, if (primary) 0 else ctx.dpi(1f)))
        isClickable = true
        setOnClickListener { onClick() }
    }
}

// ------------------------------------------------------------------------- bottom sheet
/**
 * Slide-up panel over the map with a dimmed backdrop. One at a time; the back
 * button or a tap on the backdrop closes it.
 */
class SheetHost(ctx: Context) : FrameLayout(ctx) {
    private val scrim = View(ctx).apply { setBackgroundColor(0x88000000.toInt()); alpha = 0f }
    private var card: View? = null
    var onClosed: (() -> Unit)? = null
    val isOpen: Boolean get() = card != null

    init {
        visibility = GONE
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scrim.setOnClickListener { close() }
    }

    /** Shows [content] under a title bar. [fullHeight] makes the sheet use most of the screen (lists). */
    fun show(title: String, content: View, fullHeight: Boolean = false, scroll: Boolean = true, actions: List<View> = emptyList()) {
        removeCard(immediate = true)
        onClosed = null
        val ctx = context
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(C.panel)
                cornerRadii = floatArrayOf(dp(18f), dp(18f), dp(18f), dp(18f), 0f, 0f, 0f, 0f)
            }
            isClickable = true      // don't let taps fall through to the backdrop
            elevation = dp(12f)
        }
        // grab handle
        col.addView(View(ctx).apply { background = rounded(0xff55575f.toInt(), dp(2f)) },
            LinearLayout.LayoutParams(dpi(36f), dpi(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dpi(8f) })
        val head = W.hRow(ctx).apply { setPadding(dpi(18f), dpi(6f), dpi(6f), dpi(2f)) }
        head.addView(W.text(ctx, title, 18f, C.text, true), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        for (a in actions) head.addView(a)
        head.addView(W.iconButton(ctx, Icon.CLOSE, "Close") { close() })
        col.addView(head)
        val body: View = if (scroll) ScrollView(ctx).apply {
            isFillViewport = false
            addView(content.apply { setPadding(dpi(14f), dpi(2f), dpi(14f), dpi(28f)) })
        } else content
        col.addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, if (fullHeight) 0 else LayoutParams.WRAP_CONTENT, if (fullHeight) 1f else 0f))
        // full-height sheets fill the host below a small gap, so they shrink with the keyboard;
        // others wrap their content, up to 80% of the host
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, if (fullHeight) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        if (fullHeight) lp.topMargin = dpi(56f)
        val wrapper = object : FrameLayout(ctx) {
            override fun onMeasure(w: Int, h: Int) {
                if (fullHeight) { super.onMeasure(w, h); return }
                val host = (parent as? View)?.height ?: MeasureSpec.getSize(h)
                val cap = maxOf((host * 0.80f).toInt(), dpi(260f))
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(h), cap), MeasureSpec.AT_MOST))
            }
        }
        wrapper.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, if (fullHeight) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT))
        // keep wide screens readable
        val maxW = dpi(620f)
        val hostW = maxOf(width, (parent as? View)?.width ?: 0)
        if (hostW > maxW) { lp.width = maxW; lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        addView(wrapper, lp)
        card = wrapper
        visibility = VISIBLE
        wrapper.translationY = dp(600f)
        wrapper.animate().translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
        scrim.animate().alpha(1f).setDuration(200).start()
    }

    fun close() {
        if (card == null) return
        removeCard(immediate = false)
        onClosed?.invoke()
    }

    private fun removeCard(immediate: Boolean) {
        val c = card ?: return
        card = null
        if (immediate) {
            removeView(c)
            return
        }
        c.animate().translationY(c.height.toFloat() + dp(40f)).setDuration(200).withEndAction {
            removeView(c)
            if (card == null) visibility = GONE
        }.start()
        scrim.animate().alpha(0f).setDuration(200).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = isOpen
}

/** Animates a float from a to b on the UI thread. */
fun animateFloat(from: Float, to: Float, ms: Long, onUpdate: (Float) -> Unit): ValueAnimator =
    ValueAnimator.ofFloat(from, to).apply {
        duration = ms
        interpolator = DecelerateInterpolator(1.6f)
        addUpdateListener { onUpdate(it.animatedValue as Float) }
        start()
    }
