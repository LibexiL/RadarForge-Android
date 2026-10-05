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

/**
 * The current theme's colours (RadarForge Dark until [Themes.apply] sets another). Views read these
 * when they're built, so a theme change rebuilds the screen.
 */
object C {
    var themeName = "RadarForge Dark"
    var dark = true
    var window = 0xff26272d.toInt()
    var panel = 0xff1e1f24.toInt()
    var alt = 0xff2a2b31.toInt()
    var header = 0xff1f2025.toInt()
    var button = 0xff303138.toInt()
    var border = 0xff3a3c45.toInt()
    var text = 0xffe1e1e6.toInt()
    var dim = 0xff8d909b.toInt()
    var accent = 0xff3c6ec8.toInt()
    var accentText = 0xffffffff.toInt()
    val danger = 0xffe0524f.toInt()
    val ok = 0xff3fae6a.toInt()
    val amber = 0xffe8a33a.toInt()

    var mapBg = 0xff08080c.toInt()
    var mapGap = 0xff1f1f24.toInt()
    var states = 0xffe1e1e1.toInt()
    var countries = 0xffd7d7d7.toInt()
    var counties = 0xe6696969.toInt()
    var roads = 0xebaf4646.toInt()
    var roads2 = 0xdc78553c.toInt()
    var lakes = 0xc8466eaa.toInt()
    var rings = 0x96c8c8d7.toInt()
    var cityText = 0xffe1e1e1.toInt()
    var cityDot = 0xffe6e6e6.toInt()
    var siteText = 0xffcde6d2.toInt()
    var site88d = 0xff28965a.toInt()
    var siteCurrent = 0xffffd700.toInt()
    var labelBg = 0xb9000000.toInt()
    var labelText = 0xfff0f0f5.toInt()
    var halo = 0xdc000000.toInt()
    var panelBorder = 0xff464650.toInt()
    var activeBorder = 0xff5a8cdc.toInt()
    var location = 0xff4aa3ff.toInt()

    // derived from the theme (see Themes.apply)
    var ripple = 0x33ffffff
    var handle = 0xff55575f.toInt()
    var link = 0xff8fb4ff.toInt()
    var accentSoft = 0x333c6ec8
    var warnText = 0xffffa060.toInt()
    var noteText = 0xffffc35a.toInt()
    var switchOff = 0xffb0b2ba.toInt()
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
    RippleDrawable(ColorStateList.valueOf(C.ripple), base, mask ?: base ?: ColorDrawable(Color.WHITE))

// ------------------------------------------------------------------------- icons
enum class Icon { MORE, LAYERS, WARNING, PLAY, PAUSE, LOCATE, PANELS1, PANELS2, PANELS4, UP, DOWN, CLOSE, SEARCH, REFRESH, RADAR, CHECK, SLIDERS,
    RULER, SHARE, STAR, STAR_ON, UNDO, TRASH, PREV, NEXT, PALETTE, BOOK }

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
            Icon.RULER -> {
                // a ruler lying diagonally, with tick marks along its lower edge
                val ux = 0.7071f; val uy = -0.7071f          // along the ruler
                val nx = 0.7071f; val ny = 0.7071f           // across it
                fun pt(t: Float, n: Float) = floatArrayOf(12f + t * ux + n * nx, 12f + t * uy + n * ny)
                val a = pt(11f, 4f); val b = pt(11f, -4f); val d = pt(-11f, -4f); val e = pt(-11f, 4f)
                path.moveTo(a[0], a[1]); path.lineTo(b[0], b[1]); path.lineTo(d[0], d[1]); path.lineTo(e[0], e[1]); path.close()
                c.drawPath(path, p)
                p.strokeWidth = 1.7f
                for ((k, t) in floatArrayOf(-6f, -2f, 2f, 6f).withIndex()) {
                    val s0 = pt(t, -4f); val s1 = pt(t, if (k % 2 == 0) 0f else -1.5f)
                    c.drawLine(s0[0], s0[1], s1[0], s1[1], p)
                }
            }
            Icon.SHARE -> {
                c.drawLine(7f, 12f, 17f, 6f, p); c.drawLine(7f, 12f, 17f, 18f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(17.5f, 5.5f, 3f, p); c.drawCircle(6.5f, 12f, 3f, p); c.drawCircle(17.5f, 18.5f, 3f, p)
            }
            Icon.STAR, Icon.STAR_ON -> {
                for (k in 0 until 10) {
                    val ang = Math.toRadians(-90.0 + k * 36.0)
                    val r = if (k % 2 == 0) 10.5f else 4.4f
                    val x = 12f + (r * Math.cos(ang)).toFloat(); val y = 12.8f + (r * Math.sin(ang)).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                if (icon == Icon.STAR_ON) p.style = Paint.Style.FILL_AND_STROKE
                c.drawPath(path, p)
            }
            Icon.UNDO -> {
                path.moveTo(9f, 4.5f); path.lineTo(4f, 9.5f); path.lineTo(9f, 14.5f)
                path.moveTo(4.5f, 9.5f); path.lineTo(14f, 9.5f); path.quadTo(20f, 9.5f, 20f, 15f); path.quadTo(20f, 20.5f, 14f, 20.5f)
                path.lineTo(10f, 20.5f)
                c.drawPath(path, p)
            }
            Icon.PREV -> { path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f); c.drawPath(path, p) }
            Icon.NEXT -> { path.moveTo(9f, 5f); path.lineTo(16f, 12f); path.lineTo(9f, 19f); c.drawPath(path, p) }
            Icon.PALETTE -> {
                path.moveTo(12f, 2.5f)
                path.cubicTo(6f, 2.5f, 2.5f, 7f, 2.5f, 12f); path.cubicTo(2.5f, 17.5f, 7f, 21.5f, 11.5f, 21.5f)
                path.cubicTo(13.5f, 21.5f, 13.5f, 19f, 12.5f, 18f); path.cubicTo(11.5f, 16.5f, 12.5f, 15f, 14.5f, 15f)
                path.lineTo(17f, 15f); path.cubicTo(19.8f, 15f, 21.5f, 13.2f, 21.5f, 11f); path.cubicTo(21.5f, 6f, 17.5f, 2.5f, 12f, 2.5f)
                c.drawPath(path, p)
                p.style = Paint.Style.FILL
                c.drawCircle(7.5f, 11.5f, 1.6f, p); c.drawCircle(10f, 7f, 1.6f, p); c.drawCircle(15f, 7.2f, 1.6f, p)
            }
            Icon.BOOK -> {
                path.moveTo(12f, 6f); path.cubicTo(9.5f, 4.3f, 6f, 4f, 3f, 4.8f); path.lineTo(3f, 19f); path.cubicTo(6f, 18.2f, 9.5f, 18.5f, 12f, 20f)
                path.cubicTo(14.5f, 18.5f, 18f, 18.2f, 21f, 19f); path.lineTo(21f, 4.8f); path.cubicTo(18f, 4f, 14.5f, 4.3f, 12f, 6f); path.close()
                c.drawPath(path, p)
                c.drawLine(12f, 6f, 12f, 20f, p)
            }
            Icon.TRASH -> {
                c.drawLine(3.5f, 6f, 20.5f, 6f, p)
                path.moveTo(9f, 6f); path.lineTo(9f, 3.2f); path.lineTo(15f, 3.2f); path.lineTo(15f, 6f)
                path.moveTo(6f, 6f); path.lineTo(7f, 21f); path.lineTo(17f, 21f); path.lineTo(18f, 6f)
                c.drawPath(path, p)
                c.drawLine(10f, 10f, 10f, 17f, p); c.drawLine(14f, 10f, 14f, 17f, p)
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

        /** Grid chips: square-ish corners and room for two words. */
        fun asTile() {
            textSize = 13f
            minWidth = 0
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpi(6f), dpi(9f), dpi(6f), dpi(9f))
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
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(C.accent, C.switchOff))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Themes.withAlpha(C.accent, 0x99), 0x66808390))
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
            background = ripple(if (highlighted) rounded(C.accentSoft, ctx.dp(10f)) else null, rounded(Color.WHITE, ctx.dp(10f)))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val col = vCol(ctx)
        col.addView(text(ctx, title, 15.5f, if (highlighted) C.link else C.text, true).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
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

/**
 * Lays its children out in equal columns, as many as fit (at least two): the Quick switches grid.
 * Children are measured to the column width.
 */
class ChipGrid(ctx: Context, private val minColDp: Float = 104f, private val gapDp: Float = 6f) : ViewGroup(ctx) {
    private fun cols(w: Int): Int = maxOf(2, minOf(5, ((w + dpi(gapDp)) / dpi(minColDp + gapDp))))

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        val n = cols(w)
        val gap = dpi(gapDp)
        val cw = (w - gap * (n - 1)) / n
        var h = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            rowH = maxOf(rowH, c.measuredHeight)
            if (i % n == n - 1 || i == childCount - 1) { h += rowH + if (i == childCount - 1) 0 else gap; rowH = 0 }
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l - paddingLeft - paddingRight
        val n = cols(w)
        val gap = dpi(gapDp)
        val cw = (w - gap * (n - 1)) / n
        var y = paddingTop
        var i = 0
        while (i < childCount) {
            var rowH = 0
            for (k in 0 until n) {
                if (i + k >= childCount) break
                val c = getChildAt(i + k)
                val x = paddingLeft + k * (cw + gap)
                c.layout(x, y, x + cw, y + c.measuredHeight)
                rowH = maxOf(rowH, c.measuredHeight)
            }
            y += rowH + gap
            i += n
        }
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
        col.addView(View(ctx).apply { background = rounded(C.handle, dp(2f)) },
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
