package com.libexil.radarforge.tests

import com.libexil.radarforge.core.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders model maps the way the phone's Models view builds them (MapRaster: raster, contours, barbs) to PNGs,
 * from a local folder of HRRR files (fNN.grib2 + fNN.idx), to check them by eye.
 *
 *   java -cp ... com.libexil.radarforge.tests.RenderModelKt <dir> <out dir> <fh> <region> field,field,...
 */
fun main(args: Array<String>) {
    val dir = File(args[0]); val out = File(args[1]).apply { mkdirs() }
    val fh = args[2].toInt(); val region = args[3]
    val fields = args[4].split(",")
    Models.cacheDir = null
    Models.fetcher = { url, a, b ->
        val name = Regex("""f(\d\d)\.grib2(\.idx)?$""").find(url.replace("wrfsfcf", "f"))!!
        val f = File(dir, "f${name.groupValues[1]}" + (if (name.groupValues[2].isNotEmpty()) ".idx" else ".grib2"))
        val bytes = f.readBytes()
        if (a < 0) bytes else bytes.copyOfRange(a.toInt(), if (b < 0) bytes.size else b.toInt() + 1)
    }
    val run = Time.parseIso("2026-10-15T12:00:00Z")
    val (lat, lon, wd) = Models.REGIONS.getValue(region)
    val w = wd.toFloat()
    val proj = Geo.Aeqd(lat, lon)
    val bm = Basemap.read(File("app/assets/basemap.bin").readBytes())
    val states = ProjectedLayer.project(bm.layers.getValue("states"), proj, w * 0.85)
    val W = 1000; val H = (W * 0.68).toInt()
    val scale = W / w.toFloat()
    fun sx(x: Float) = W / 2f + x * scale
    fun sy(y: Float) = H / 2f - y * scale
    for (fid in fields) {
        val fd = Models.FIELDS.getValue(fid)
        val t0 = System.currentTimeMillis()
        val f = Models.compute("hrrr", run, fh, fid)
        val frame = RasterFrame.around(proj, w * 1.15f, (w * 0.68f) * 1.3f, 2.55f, 900)
        val idx = MapRaster.cachedIndex(f.grid, frame)
        val vals = MapRaster.resample(f.grid, f.value, idx, !fd.categorical)
        val ref = ColorTable.parse(File("app/assets/palettes/REF.pal").readText(), "REF")
        val img = if (fd.palette == "REF") MapRaster.colorizeTable(vals, ref) else MapRaster.colorize(vals, fd.stops!!)
        val t1 = System.currentTimeMillis()
        val bi = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = bi.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x10, 0x12, 0x18); g.fillRect(0, 0, W, H)
        val ras = BufferedImage(frame.w, frame.h, BufferedImage.TYPE_INT_ARGB)
        ras.setRGB(0, 0, frame.w, frame.h, img, 0, frame.w)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(ras, sx(frame.x0).toInt(), sy(frame.y1).toInt(), (sx(frame.x1) - sx(frame.x0)).toInt(), (sy(frame.y0) - sy(frame.y1)).toInt(), null)
        fun lines(l: ProjectedLayer, c: Color, wpx: Float) {
            g.color = c; g.stroke = BasicStroke(wpx)
            var i = 0
            while (i + 3 < l.pts.size) {
                if (l.pts[i] < 1e8f && l.pts[i + 2] < 1e8f) g.drawLine(sx(l.pts[i]).toInt(), sy(l.pts[i + 1]).toInt(), sx(l.pts[i + 2]).toInt(), sy(l.pts[i + 3]).toInt())
                i += 2
            }
        }
        lines(states, Color(200, 200, 200), 1f)
        fd.contour?.let { ct ->
            val v = MapRaster.resample(f.grid, f.extra.getValue(ct.input), idx, true)
            val cl = MapRaster.contours(v, frame, ct.interval)
            lines(MapRaster.layerOf(cl.map { it.pts }), Color(220, 220, 220), 1.3f)
            g.color = Color.WHITE
            for (s in MapRaster.labelSpots(cl, frame.cellKm * 25, w / 14f)) g.drawString(String.format("%.0f", s[2]), sx(s[0]), sy(s[1]))
        }
        fd.zeroLine?.let { z -> lines(MapRaster.layerOf(MapRaster.contours(vals, frame, 1f, only = z).map { it.pts }), Color(60, 110, 255), 2f) }
        if (fd.barbs) {
            val b = MapRaster.barbs(MapRaster.resample(f.grid, f.extra.getValue("u"), idx, true), MapRaster.resample(f.grid, f.extra.getValue("v"), idx, true), frame, w / 30f)
            g.color = Color.WHITE; g.stroke = BasicStroke(1.2f)
            var k = 0
            while (k + 3 < b.size) {
                val x = sx(b[k]); val y = sy(b[k + 1])
                val rot = MapRaster.northAngle(proj, b[k], b[k + 1])
                val ang = Math.atan2(-b[k + 2].toDouble(), -b[k + 3].toDouble()) + rot
                g.drawLine(x.toInt(), y.toInt(), (x + 16 * Math.sin(ang)).toInt(), (y - 16 * Math.cos(ang)).toInt())
                g.fillOval(x.toInt() - 2, y.toInt() - 2, 4, 4)
                k += 4
            }
        }
        g.color = Color.WHITE; g.drawString("HRRR ${fd.name}  F$fh  ${f.note}  (${t1 - t0} ms)", 10, 18)
        g.dispose()
        ImageIO.write(bi, "png", File(out, "$fid.png"))
        println("$fid: ${frame.w}x${frame.h} in ${t1 - t0} ms")
    }
}
