package com.libexil.radarforge

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import com.libexil.radarforge.core.Alert
import com.libexil.radarforge.core.Geo
import com.libexil.radarforge.core.MapRaster
import com.libexil.radarforge.core.Net
import com.libexil.radarforge.core.Raster
import com.libexil.radarforge.core.Time
import com.libexil.radarforge.core.Tropical
import com.libexil.radarforge.core.Winter
import com.libexil.radarforge.gl.LayerDraw
import com.libexil.radarforge.ui.MapState
import com.libexil.radarforge.ui.OverlayView
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The winter and tropical layers on the radar map: the freezing line, snowfall and rainfall totals, county-shaded
 * winter / tropical alerts, and hurricanes (storms, cones, wind fields, coastal warnings, model tracks, the outlook,
 * past tracks and buoys).
 */
class WeatherLayers(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newFixedThreadPool(2) { r -> Thread(r, "rf-wx").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val p get() = a.prefs
    private val density get() = a.resources.displayMetrics.density

    // ---------------------------------------------------------------- winter
    private var frzGrid: Winter.SurfaceGrid? = null
    private var frzLayer: com.libexil.radarforge.core.ProjectedLayer? = null
    private var frzLabel: FloatArray? = null
    private var frzLoading: String? = null
    var snowfall: Winter.Total? = null; private set
    var rainfall: Winter.Total? = null; private set
    private var snowKey: String? = null
    private var rainKey: String? = null
    private val failedAt = HashMap<String, Long>()
    var status: String? = null; private set

    private fun site() = a.geo?.site

    /** Says once (per message) why a layer the user switched on isn't showing. */
    private fun failed(msg: String) {
        if (msg == status) return
        status = msg
        a.toast(msg)
    }

    /** The hour of the scan shown (0 = live). */
    var timeMs = 0L

    fun refreshWinter() {
        val s = site() ?: return
        val t = if (timeMs > 0) timeMs else System.currentTimeMillis()
        if (p.layer("freezing")) {
            val key = "${s.id}/${(t + 1_800_000L) / 3_600_000L}"
            val have = frzGrid?.let { g -> g.lat0 == s.lat && g.lon0 == s.lon && Math.abs(g.validMs - t) < 45 * 60_000L } == true
            if (!have && frzLoading != key && System.currentTimeMillis() - (failedAt["frz/$key"] ?: 0L) > 5 * 60_000L) {
                frzLoading = key
                exec.execute {
                    val g = try { Winter.surfaceGrid(s.lat, s.lon, t) } catch (e: Exception) { null }
                    val lines = g?.let { Winter.freezingLine(it) }
                    main.post {
                        frzLoading = null
                        if (g == null) { failedAt["frz/$key"] = System.currentTimeMillis(); failed("Freezing line: no HRRR temperatures for this hour yet") }
                        else if (site()?.id == s.id) {
                            frzGrid = g
                            frzLayer = MapRaster.layerOf(lines!!)
                            frzLabel = lines.maxByOrNull { it.size }?.let { l -> val i = (l.size / 4) * 2; floatArrayOf(l[i], l[i + 1]) }
                        }
                        a.rebuild()
                    }
                }
            }
        }
        fun total(kind: String, hours: Int, cur: String?, setKey: (String) -> Unit, set: (Winter.Total) -> Unit, make: () -> Winter.Total) {
            // the newest totals (the loop's frames span an hour or so: reloading them per frame would just churn)
            val key = "$kind/${s.id}/$hours/${System.currentTimeMillis() / 1_800_000L}"
            if (key == cur) return
            setKey(key)                     // (the old picture stays until the new one is in)
            exec.execute {
                val res = try { make() } catch (e: Throwable) {
                    RfLog.w("$kind: $e")
                    main.post { failed("${kind.replaceFirstChar { it.titlecase() }} totals: ${e.message ?: "couldn't load them"}") }
                    null
                }
                if (res != null) main.post { if (site()?.id == s.id) { set(res); a.rebuild() } }
            }
        }
        if (p.layer("snowfall")) total("snowfall", p.snowfallHours, snowKey, { snowKey = it }, { snowfall = it }) {
            Winter.snowfall(s.lat, s.lon, p.snowfallHours)
        } else { snowfall = null; snowKey = null }
        if (p.layer("rainfall")) total("rainfall", p.rainfallHours, rainKey, { rainKey = it }, { rainfall = it }) {
            Winter.rainfall(s.lat, s.lon, p.rainfallHours)
        } else { rainfall = null; rainKey = null }
    }

    /** Pictures under the radar (all panels). */
    fun rasters(): List<Raster> {
        val s = site() ?: return emptyList()
        val out = ArrayList<Raster>()
        rainfall?.takeIf { p.layer("rainfall") && it.frame.proj.lat0 == s.lat && it.frame.proj.lon0 == s.lon }?.let { out.add(it.raster) }
        snowfall?.takeIf { p.layer("snowfall") && it.frame.proj.lat0 == s.lat && it.frame.proj.lon0 == s.lon }?.let { out.add(it.raster) }
        return out
    }

    /** Lines over the map (all panels). */
    fun lines(): List<LayerDraw> {
        val s = site() ?: return emptyList()
        val g = frzGrid ?: return emptyList()
        if (!p.layer("freezing") || g.lat0 != s.lat || g.lon0 != s.lon) return emptyList()
        val l = frzLayer ?: return emptyList()
        return listOf(LayerDraw(l, 0xff3a6dff.toInt(), 2.4f * density, halo = true))
    }

    fun clearForSite() {
        frzGrid = null; frzLayer = null; snowfall = null; rainfall = null; snowKey = null; rainKey = null
        historyProj.clear(); proj.clear()
    }

    // ---------------------------------------------------------------- county-shaded alerts
    private class AreaShape(val path: Path, val color: Int, val alert: Alert, val projId: Any)
    private var areaShapes: List<AreaShape> = emptyList()

    fun setAreaAlerts(alerts: List<Alert>, colorOf: (Alert) -> Int) {
        val g = a.geo ?: run { areaShapes = emptyList(); return }
        val xy = FloatArray(2)
        areaShapes = alerts.filter { it.isArea }.sortedBy { com.libexil.radarforge.core.Alerts.VARIANTS[it.variant]?.priority ?: 0f }.map { al ->
            val path = Path()
            for (r in al.rings) {
                var i = 0
                var first = true
                while (i + 1 < r.size) {
                    g.proj.forward(r[i + 1].toDouble(), r[i].toDouble(), xy, 0)
                    if (first) path.moveTo(xy[0], -xy[1]) else path.lineTo(xy[0], -xy[1])
                    first = false; i += 2
                }
                path.close()
            }
            AreaShape(path, colorOf(al), al, g.proj)
        }
    }

    // ---------------------------------------------------------------- tropical data
    private val nhc = Tropical.NhcService { url -> Net.getText(url, 40_000) }
    var storms: List<Tropical.Storm> = emptyList(); private set
    var outlookAreas: List<Tropical.OutlookArea> = emptyList(); private set
    var outlookPoints: List<Tropical.OutlookPoint> = emptyList(); private set
    var aids: Map<String, List<Tropical.Aid>> = emptyMap(); private set
    var buoys: List<Tropical.Buoy> = emptyList(); private set
    var stormsTime = 0L; private set
    var tropicalError: String? = null; private set
    /** Past storms drawn as tracks (a picked past storm, or historic tracks near a place). */
    var history: List<Tropical.PastStorm> = emptyList()
        set(v) { field = v; historyProj.clear(); a.rebuild() }
    private var loading = HashSet<String>()
    private val lastLoad = HashMap<String, Long>()

    fun tropicalOn() = p.layer("tropical") || p.layer("tropical_outlook") || p.layer("buoys") || history.isNotEmpty()

    private val tick = object : Runnable {
        override fun run() {
            refreshTropical()
            main.postDelayed(this, 60_000L)
        }
    }

    fun start() { main.removeCallbacks(tick); main.post(tick) }
    fun stop() { main.removeCallbacks(tick) }

    private fun job(name: String, everyMs: Long, force: Boolean, work: () -> Unit) {
        if (name in loading) return
        if (!force && System.currentTimeMillis() - (lastLoad[name] ?: 0L) < everyMs) return
        loading.add(name)
        exec.execute {
            val err = try { work(); null } catch (e: Throwable) { RfLog.w("tropical $name: $e"); e.message ?: e.javaClass.simpleName }
            main.post {
                loading.remove(name)
                lastLoad[name] = System.currentTimeMillis()
                if (name == "storms") tropicalError = err?.let { "Couldn't reach the National Hurricane Center ($it)" }
                proj.clear()
                a.rebuild()
                a.onTropicalData()
            }
        }
    }

    fun refreshTropical(force: Boolean = false) {
        if (p.layer("tropical") || p.layer("tropical_outlook")) job("storms", 10 * 60_000L, force) {
            val list = Tropical.parseCurrent(Net.getText(Tropical.CURRENT_URL, 30_000))
            val old = storms.associateBy { it.id }
            for (s in list) {
                val o = old[s.id]
                if (o != null && o.advisory == s.advisory && System.currentTimeMillis() - o.loadedMs < 25 * 60_000L && o.forecast.isNotEmpty()) {
                    s.forecast = o.forecast; s.trackLine = o.trackLine; s.cone = o.cone; s.past = o.past
                    s.warnings = o.warnings; s.radii = o.radii; s.windNow = o.windNow; s.loadedMs = o.loadedMs
                    continue
                }
                try { Tropical.loadStormLayers(nhc, s) } catch (e: Exception) { RfLog.w("storm ${s.name}: $e") }
            }
            storms = list
            stormsTime = System.currentTimeMillis()
        }
        if (p.layer("tropical_outlook")) job("outlook", 30 * 60_000L, force) {
            val (ar, pt) = Tropical.loadOutlook(nhc)
            outlookAreas = ar; outlookPoints = pt
        }
        if (p.layer("tropical") && p.layer("tropical_models") && storms.isNotEmpty()) job("aids", 30 * 60_000L, force) {
            val out = HashMap<String, List<Tropical.Aid>>()
            for (s in storms) try {
                out[s.id] = Tropical.parseAdeck(Tropical.gunzipText(Net.getBytes(Tropical.adeckUrl(s.id), 60_000)), Tropical.MAIN_AIDS)
            } catch (e: Exception) { RfLog.w("a-deck ${s.id}: $e") }
            aids = out
        }
        if (p.layer("buoys")) job("buoys", 10 * 60_000L, force) {
            buoys = Tropical.parseNdbc(Net.getText(Tropical.NDBC_LATEST, 40_000))
        }
    }

    /** Loads a basin's past storms for the Tropical sheet (blocking: call off the main thread). */
    fun pastStorms(basin: String): List<Tropical.PastStorm> {
        val cache = java.io.File(a.cacheDir, "tropical").apply { mkdirs() }
        val idx = java.io.File(cache, "hurdat_index.html")
        val html = if (idx.exists() && System.currentTimeMillis() - idx.lastModified() < 7 * 86_400_000L) idx.readText()
            else Net.getText(Tropical.HURDAT_INDEX, 30_000).also { idx.writeText(it) }
        val name = Tropical.hurdatName(html, basin) ?: throw IllegalStateException("HURDAT2 isn't on the NHC data page")
        val f = java.io.File(cache, name)
        val text = if (f.exists()) f.readText(Charsets.ISO_8859_1) else String(Net.getBytes(Tropical.HURDAT_INDEX + "hurdat/" + name, 120_000), Charsets.ISO_8859_1).also {
            cache.listFiles()?.filter { it.name.startsWith("hurdat2") && it.name != name }?.forEach { old -> old.delete() }
            f.writeText(it, Charsets.ISO_8859_1)
        }
        return Tropical.parseHurdat2(text)
    }

    /** This season's storms (not in HURDAT2 yet), from the ATCF b-decks (blocking). */
    fun recentSeason(basin: String, year: Int): List<Tropical.PastStorm> {
        val html = Net.getText("${Tropical.ATCF}/btk/", 30_000)
        return Tropical.bdeckNames(html, basin, year).mapNotNull { fn ->
            try { Tropical.parseBdeck(Net.getText("${Tropical.ATCF}/btk/b$fn.dat", 30_000), fn) } catch (_: Exception) { null }
        }
    }

    fun shutdown() { stop(); exec.shutdownNow() }

    // ---------------------------------------------------------------- drawing helpers
    private val proj = HashMap<Any, FloatArray>()          // ring identity -> km
    private val historyProj = HashMap<Any, FloatArray>()

    private fun km(key: Any, lonLat: FloatArray, cache: HashMap<Any, FloatArray> = proj): FloatArray? {
        val g = a.geo ?: return null
        val k = key to g.proj
        cache[k]?.let { return it }
        val out = FloatArray(lonLat.size)
        var i = 0
        while (i + 1 < lonLat.size) { g.proj.forward(lonLat[i + 1].toDouble(), lonLat[i].toDouble(), out, i); i += 2 }
        if (cache.size > 4000) cache.clear()
        cache[k] = out
        return out
    }

    private fun kmOf(lat: Double, lon: Double): FloatArray? {
        val g = a.geo ?: return null
        return FloatArray(2).also { g.proj.forward(lat, lon, it, 0) }
    }

    private fun pointsKm(key: Any, pts: List<Tropical.TrackPoint>, cache: HashMap<Any, FloatArray> = proj): FloatArray? {
        val ll = FloatArray(pts.size * 2)
        for ((i, q) in pts.withIndex()) { ll[2 * i] = q.lon.toFloat(); ll[2 * i + 1] = q.lat.toFloat() }
        return km(key, ll, cache)
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; color = 0xe6000000.toInt(); strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val matrix = Matrix()

    private fun text(c: Canvas, s: String, x: Float, y: Float, color: Int) {
        val sp = a.resources.displayMetrics.scaledDensity * a.prefs.mapTextScale
        label.textSize = 11.5f * sp; halo.textSize = label.textSize; halo.strokeWidth = 3f * density
        c.drawText(s, x, y, halo)
        label.color = color
        c.drawText(s, x, y, label)
    }

    private fun polyPath(xy: FloatArray, r: RectF, st: MapState, close: Boolean): Path {
        path.reset()
        var i = 0
        while (i + 1 < xy.size) {
            val x = st.toScreenX(r, xy[i]); val y = st.toScreenY(r, xy[i + 1])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            i += 2
        }
        if (close) path.close()
        return path
    }

    private fun line2(c: Canvas, pth: Path, color: Int, w: Float, dash: Boolean = false) {
        stroke.pathEffect = null
        stroke.color = 0xc8000000.toInt(); stroke.strokeWidth = w + 2.2f * density
        c.drawPath(pth, stroke)
        stroke.color = color; stroke.strokeWidth = w
        if (dash) stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
        c.drawPath(pth, stroke)
        stroke.pathEffect = null
    }

    /** km across the widest panel. */
    private fun across(st: MapState) = st.viewWidthKm()

    // ---------------------------------------------------------------- the decor (overlay drawing)
    /** County-shaded winter / tropical alerts: under everything else. */
    inner class AreaDecor : OverlayView.Decor {
        override val below = true
        override fun draw(c: Canvas, r: RectF, state: MapState, density: Float) {
            val shapes = areaShapes
            if (shapes.isEmpty()) return
            // km (y up) -> screen
            matrix.reset()
            matrix.setScale(state.scale, state.scale)
            matrix.postTranslate(r.centerX() - state.cx * state.scale, r.centerY() + state.cy * state.scale)
            val tmp = Path()
            for (s in shapes) {
                s.path.transform(matrix, tmp)
                fill.style = Paint.Style.FILL
                fill.color = ((if (s.alert.isWatch) 0x48 else 0x6a) shl 24) or (s.color and 0xffffff)
                c.drawPath(tmp, fill)
                stroke.pathEffect = null
                stroke.color = (0xb0 shl 24) or (s.color and 0xffffff)
                stroke.strokeWidth = 1f * density
                c.drawPath(tmp, stroke)
            }
        }
        override fun tap(km: FloatArray, r: RectF, state: MapState, density: Float): Boolean = false
    }

    inner class TropicalDecor : OverlayView.Decor {
        override fun draw(c: Canvas, r: RectF, state: MapState, density: Float) {
            if (history.isNotEmpty()) drawHistory(c, r, state)
            if (p.layer("tropical_outlook")) drawOutlook(c, r, state)
            if (p.layer("tropical")) for (s in storms) drawStorm(c, r, state, s)
            if (p.layer("buoys")) drawBuoys(c, r, state)
        }

        override fun tap(km: FloatArray, r: RectF, state: MapState, density: Float): Boolean {
            val tol = 26f * density / state.scale
            if (p.layer("tropical")) for (s in storms) {
                val xy = kmOf(s.lat, s.lon) ?: continue
                if (hypot(xy[0] - km[0], xy[1] - km[1]) < tol) { SheetsWx.stormDetail(a, s); return true }
            }
            if (p.layer("buoys") && across(state) < 3000) {
                val b = buoys.minByOrNull { b -> kmOf(b.lat, b.lon)?.let { hypot(it[0] - km[0], it[1] - km[1]) } ?: 1e9f }
                val xy = b?.let { kmOf(it.lat, it.lon) }
                if (b != null && xy != null && hypot(xy[0] - km[0], xy[1] - km[1]) < tol * 0.8f) { SheetsWx.buoyDetail(a, b); return true }
            }
            if (p.layer("tropical_outlook")) for (pt in outlookPoints) {
                val xy = kmOf(pt.lat, pt.lon) ?: continue
                if (hypot(xy[0] - km[0], xy[1] - km[1]) < tol) {
                    a.toast("Area to watch (NHC outlook): ${pt.p2.ifEmpty { "–" }} chance of forming in 2 days, ${pt.p7.ifEmpty { "–" }} in 7 days")
                    return true
                }
            }
            return false
        }
    }

    private fun drawStorm(c: Canvas, r: RectF, st: MapState, s: Tropical.Storm) {
        val d = density
        if (p.layer("tropical_wind")) for ((kt, ring) in s.windNow.sortedBy { it.first }) {
            val xy = km(ring, ring.pts) ?: continue
            val col = when (kt) { 50 -> 0x55ff961e; 64 -> 0x64e61e1e; else -> 0x46ffe63c }
            fill.style = Paint.Style.FILL; fill.color = col
            c.drawPath(polyPath(xy, r, st, true), fill)
            stroke.color = (0xc8 shl 24) or (col and 0xffffff); stroke.strokeWidth = 1.2f * d
            c.drawPath(path, stroke)
        }
        for (ring in s.cone) {
            val xy = km(ring, ring.pts) ?: continue
            fill.style = Paint.Style.FILL; fill.color = 0x2dffffff
            c.drawPath(polyPath(xy, r, st, true), fill)
            stroke.color = 0xdcffffff.toInt(); stroke.strokeWidth = 1.6f * d
            c.drawPath(path, stroke)
        }
        for ((code, ring) in s.warnings) {
            val (col, w, _) = Tropical.WARN_STYLE[code] ?: Triple(0xffffffff.toInt(), 3f, code)
            val xy = km(ring, ring.pts) ?: continue
            line2(c, polyPath(xy, r, st, false), col, w * d * 0.75f)
        }
        drawTrack(c, r, st, s.past, "past:${s.id}:${s.past.size}", dots = true)
        if (s.forecast.size > 1) {
            val xy = pointsKm("fc:${s.id}:${s.forecast.size}:${s.advisory}", s.forecast)
            if (xy != null) line2(c, polyPath(xy, r, st, false), 0xffffffff.toInt(), 1.6f * d, dash = true)
            for (q in s.forecast.drop(1)) {
                val k = kmOf(q.lat, q.lon) ?: continue
                val x = st.toScreenX(r, k[0]); val y = st.toScreenY(r, k[1])
                if (x < r.left - 20 || x > r.right + 20 || y < r.top - 20 || y > r.bottom + 20) continue
                val w = q.wind ?: 0.0
                val letter = if (w >= 96) "M" else q.label.take(1).ifEmpty { if (w >= 64) "H" else if (w >= 34) "S" else "D" }
                dot(c, x, y, Tropical.catColor(q.wind), letter, 7.5f * d)
                if (across(st) < 2500 && q.timeMs > 0) text(c, Time.local(q.timeMs, "EEE h a"), x + 10 * d, y + 4 * d, 0xfff0f0f0.toInt())
            }
        }
        if (p.layer("tropical_models")) aids[s.id]?.let { drawAids(c, r, st, s.id, it) }
        val k = kmOf(s.lat, s.lon) ?: return
        val x = st.toScreenX(r, k[0]); val y = st.toScreenY(r, k[1])
        symbol(c, x, y, s.wind)
        text(c, "${s.title}  ${s.wind.toInt()} kt" + (s.pressure?.let { " · ${it.toInt()} mb" } ?: ""), x + 16 * d, y - 12 * d, 0xffffffff.toInt())
    }

    private fun drawTrack(c: Canvas, r: RectF, st: MapState, pts: List<Tropical.TrackPoint>, key: Any, dots: Boolean, width: Float = 2.2f,
                          cache: HashMap<Any, FloatArray> = proj) {
        if (pts.size < 2) return
        val xy = pointsKm(key, pts, cache) ?: return
        val d = density
        for (i in 0 until pts.size - 1) {
            val x0 = st.toScreenX(r, xy[2 * i]); val y0 = st.toScreenY(r, xy[2 * i + 1])
            val x1 = st.toScreenX(r, xy[2 * i + 2]); val y1 = st.toScreenY(r, xy[2 * i + 3])
            if (maxOf(x0, x1) < r.left || minOf(x0, x1) > r.right || maxOf(y0, y1) < r.top || minOf(y0, y1) > r.bottom) continue
            stroke.pathEffect = null
            stroke.color = 0xb4000000.toInt(); stroke.strokeWidth = (width + 2f) * d
            c.drawLine(x0, y0, x1, y1, stroke)
            stroke.color = Tropical.catColor(pts[i + 1].wind); stroke.strokeWidth = width * d
            c.drawLine(x0, y0, x1, y1, stroke)
        }
        if (dots && across(st) < 3500) for (i in pts.indices) {
            val x = st.toScreenX(r, xy[2 * i]); val y = st.toScreenY(r, xy[2 * i + 1])
            fill.style = Paint.Style.FILL
            fill.color = 0xc8000000.toInt(); c.drawCircle(x, y, 4f * d, fill)
            fill.color = Tropical.catColor(pts[i].wind); c.drawCircle(x, y, 3f * d, fill)
        }
    }

    private fun drawAids(c: Canvas, r: RectF, st: MapState, sid: String, list: List<Tropical.Aid>) {
        val d = density
        for (aid in list.sortedBy { it.tech == "OFCL" }) {
            if (aid.points.size < 2) continue
            val xy = pointsKm("aid:$sid:${aid.tech}:${aid.cycleMs}", aid.points) ?: continue
            val col = Tropical.AID_COLORS[aid.tech] ?: 0xffc8c8c8.toInt()
            val w = if (aid.tech == "OFCL") 2.4f else 1.4f
            stroke.pathEffect = null
            val pth = polyPath(xy, r, st, false)
            stroke.color = 0x96000000.toInt(); stroke.strokeWidth = (w + 1.6f) * d; c.drawPath(pth, stroke)
            stroke.color = col; stroke.strokeWidth = w * d; c.drawPath(pth, stroke)
            val ex = st.toScreenX(r, xy[xy.size - 2]); val ey = st.toScreenY(r, xy[xy.size - 1])
            if (r.contains(ex, ey)) text(c, (Tropical.AID_NAMES[aid.tech] ?: aid.tech).substringBefore(" ("), ex + 3 * d, ey + 4 * d, col)
        }
    }

    private fun drawOutlook(c: Canvas, r: RectF, st: MapState) {
        val d = density
        for (ar in outlookAreas) {
            val xy = km(ar.ring, ar.ring.pts) ?: continue
            val col = Tropical.outlookColor(ar.p7)
            val pth = polyPath(xy, r, st, true)
            fill.style = Paint.Style.FILL; fill.color = (0x40 shl 24) or (col and 0xffffff)
            c.drawPath(pth, fill)
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * d, 5f * d), 0f)
            stroke.color = col; stroke.strokeWidth = 2f * d
            c.drawPath(pth, stroke)
            stroke.pathEffect = null
            val b = RectF(); pth.computeBounds(b, true)
            if (r.contains(b.centerX(), b.centerY())) text(c, "2 d ${ar.p2.ifEmpty { "–" }} · 7 d ${ar.p7.ifEmpty { "–" }}", b.centerX() - 30 * d, b.centerY(), col)
        }
        for (pt in outlookPoints) {
            val k = kmOf(pt.lat, pt.lon) ?: continue
            val x = st.toScreenX(r, k[0]); val y = st.toScreenY(r, k[1])
            val col = Tropical.outlookColor(pt.p7.ifEmpty { pt.p2 })
            val e = 7f * d
            for ((w, cc) in listOf(5f to 0xff000000.toInt(), 3f to col)) {
                stroke.color = cc; stroke.strokeWidth = w * d
                c.drawLine(x - e, y - e, x + e, y + e, stroke); c.drawLine(x - e, y + e, x + e, y - e, stroke)
            }
        }
    }

    private fun drawHistory(c: Canvas, r: RectF, st: MapState) {
        val d = density
        for (ps in history) {
            drawTrack(c, r, st, ps.points, "hist:${ps.id}", dots = history.size == 1, width = if (history.size == 1) 2.4f else 1.6f, cache = historyProj)
            val best = ps.points.maxByOrNull { it.wind ?: 0.0 } ?: continue
            if (across(st) > 3500) continue
            val k = kmOf(best.lat, best.lon) ?: continue
            val x = st.toScreenX(r, k[0]); val y = st.toScreenY(r, k[1])
            if (r.contains(x, y)) text(c, "${ps.displayName} ${ps.year}", x + 4 * d, y - 4 * d, Tropical.catColor(best.wind))
        }
    }

    private fun drawBuoys(c: Canvas, r: RectF, st: MapState) {
        if (across(st) > 3000) return
        val d = density
        val cell = 56f * d
        val taken = HashSet<Long>()
        for (b in buoys) {
            val k = kmOf(b.lat, b.lon) ?: continue
            val x = st.toScreenX(r, k[0]); val y = st.toScreenY(r, k[1])
            if (!r.contains(x, y)) continue
            val ck = ((x / cell).toLong() shl 32) or (y / cell).toLong()
            if (!taken.add(ck)) continue
            val spd = b.wspdKt; val dir = b.wdir
            if (spd != null && dir != null && spd >= 2.5) {
                // a simple barb: shaft toward where the wind comes from, one tick per 10 kt
                val ang = Math.toRadians(dir)
                val dx = sin(ang).toFloat(); val dy = -cos(ang).toFloat()
                val len = 20f * d
                val tx = x + dx * len; val ty = y + dy * len
                for ((w, col) in listOf(3f to 0xc8000000.toInt(), 1.3f to 0xff8ce6ff.toInt())) {
                    stroke.color = col; stroke.strokeWidth = w * d
                    c.drawLine(x, y, tx, ty, stroke)
                    val parts = MapRaster.barbParts(spd.toFloat())
                    var pos = 0f
                    repeat(parts[0] * 5 + parts[1]) {
                        val bx = tx - dx * pos; val by = ty - dy * pos
                        c.drawLine(bx, by, bx + dy * len * 0.45f, by - dx * len * 0.45f, stroke)
                        pos += len * 0.14f
                    }
                    if (parts[2] == 1) { val bx = tx - dx * pos; val by = ty - dy * pos; c.drawLine(bx, by, bx + dy * len * 0.22f, by - dx * len * 0.22f, stroke) }
                }
            }
            fill.style = Paint.Style.FILL
            fill.color = 0xff000000.toInt(); c.drawRect(x - 4.5f * d, y - 4.5f * d, x + 4.5f * d, y + 4.5f * d, fill)
            fill.color = 0xffffd23c.toInt(); c.drawRect(x - 3.5f * d, y - 3.5f * d, x + 3.5f * d, y + 3.5f * d, fill)
            val bits = listOfNotNull(b.gustKt?.let { "G${it.toInt()}" }, b.waveFt?.let { "${it.toInt()} ft" })
            if (bits.isNotEmpty() && across(st) < 1200) text(c, bits.joinToString(" "), x + 6 * d, y + 12 * d, 0xff8ce6ff.toInt())
        }
    }

    private fun dot(c: Canvas, x: Float, y: Float, color: Int, letter: String, rad: Float) {
        fill.style = Paint.Style.FILL
        fill.color = 0xff000000.toInt(); c.drawCircle(x, y, rad + 1.2f * density, fill)
        fill.color = color; c.drawCircle(x, y, rad, fill)
        label.color = 0xff000000.toInt()
        label.textSize = rad * 1.25f
        label.textAlign = Paint.Align.CENTER
        c.drawText(letter, x, y - (label.descent() + label.ascent()) / 2, label)
        label.textAlign = Paint.Align.LEFT
    }

    /** The cyclone glyph: a disc with two curved arms (a plain disc for depressions). */
    private fun symbol(c: Canvas, x: Float, y: Float, wind: Double) {
        val d = density
        val r = 8f * d
        val col = Tropical.catColor(wind)
        if (wind >= 34) {
            path.reset()
            path.moveTo(x, y - r)
            path.cubicTo(x + r * 0.2f, y - r * 2.2f, x + r * 1.8f, y - r * 2.0f, x + r * 1.9f, y - r * 1.7f)
            path.cubicTo(x + r * 0.9f, y - r * 1.4f, x + r * 0.6f, y - r * 0.6f, x + r * 0.9f, y - r * 0.2f)
            path.moveTo(x, y + r)
            path.cubicTo(x - r * 0.2f, y + r * 2.2f, x - r * 1.8f, y + r * 2.0f, x - r * 1.9f, y + r * 1.7f)
            path.cubicTo(x - r * 0.9f, y + r * 1.4f, x - r * 0.6f, y + r * 0.6f, x - r * 0.9f, y + r * 0.2f)
            stroke.pathEffect = null
            stroke.color = 0xff000000.toInt(); stroke.strokeWidth = 4f * d; c.drawPath(path, stroke)
            stroke.color = col; stroke.strokeWidth = 2.4f * d; c.drawPath(path, stroke)
        }
        fill.style = Paint.Style.FILL
        fill.color = 0xff000000.toInt(); c.drawCircle(x, y, r + 1.6f * d, fill)
        fill.color = col; c.drawCircle(x, y, r, fill)
        fill.color = 0xa0000000.toInt(); c.drawCircle(x, y, r * 0.35f, fill)
    }

    /** Freezing-line label and the snowfall / rainfall readout. */
    inner class WinterDecor : OverlayView.Decor {
        override fun draw(c: Canvas, r: RectF, state: MapState, density: Float) {
            val l = frzLabel ?: return
            if (lines().isEmpty()) return
            val x = state.toScreenX(r, l[0]); val y = state.toScreenY(r, l[1])
            if (r.contains(x, y)) text(c, "32°F", x + 4 * density, y - 4 * density, 0xff82aaff.toInt())
        }
    }

    val decors: List<OverlayView.Decor> = listOf(AreaDecor(), WinterDecor(), TropicalDecor())

    /** Lines for the inspector: snowfall / rainfall / temperature at a point. */
    fun readout(km: FloatArray): List<String> {
        val out = ArrayList<String>()
        snowfall?.takeIf { p.layer("snowfall") }?.let { t -> val v = t.valueAt(km[0], km[1]); if (!v.isNaN()) out.add(String.format(Locale.US, "Snowfall %.1f in (%s)", v, t.label)) }
        rainfall?.takeIf { p.layer("rainfall") }?.let { t -> val v = t.valueAt(km[0], km[1]); if (!v.isNaN()) out.add(String.format(Locale.US, "Rainfall %.2f in (%s)", v, t.label)) }
        return out
    }

    /** HRRR temperature and type at a point, if the freezing-line / P-type grid is here. */
    fun surfaceAt(km: FloatArray, tMs: Long): Pair<Int, Float>? {
        val s = site() ?: return null
        val g = frzGrid?.takeIf { it.lat0 == s.lat && it.lon0 == s.lon } ?: Winter.peek(s.lat, s.lon, if (tMs > 0) tMs else System.currentTimeMillis()) ?: return null
        return g.code(km[0], km[1]) to g.t2(km[0], km[1])
    }

    fun distanceText(km: Double) = Geo.distText(km, p.distUnits)
}
