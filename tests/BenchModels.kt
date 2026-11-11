package com.libexil.radarforge.tests

import com.libexil.radarforge.core.*
import java.io.File

/** Times the phone's model map steps (compute, resample, colours, contours) per field after JIT warm-up. */
fun main(args: Array<String>) {
    val dir = File(args[0]); val fields = args[1].split(",")
    Models.cacheDir = null
    Models.fetcher = { url, a, b ->
        val f = File(dir, if (url.endsWith(".idx")) "f02.idx" else "f02.grib2")
        val bytes = f.readBytes()
        if (a < 0) bytes else bytes.copyOfRange(a.toInt(), if (b < 0) bytes.size else b.toInt() + 1)
    }
    val run = Time.parseIso("2026-10-15T12:00:00Z")
    val (lat, lon, wd) = Models.REGIONS.getValue("CONUS")
    val proj = Geo.Aeqd(lat, lon)
    val w = wd.toFloat()
    val frame = RasterFrame.around(proj, w * 1.15f, (w * 0.68f) * 1.3f, 2.55f, 900)
    val ref = ColorTable.parse(File("app/assets/palettes/REF.pal").readText(), "REF")
    for (round in 0 until 3) {
        Models.clearMemory()
        for (fid in fields) {
            val fd = Models.FIELDS.getValue(fid)
            val t0 = System.nanoTime()
            val f = Models.compute("hrrr", run, 2, fid)
            val t1 = System.nanoTime()
            val idx = MapRaster.cachedIndex(f.grid, frame)
            val vals = MapRaster.resample(f.grid, f.value, idx, !fd.categorical)
            val t2 = System.nanoTime()
            if (fd.palette == "REF") MapRaster.colorizeTable(vals, ref) else MapRaster.colorize(vals, fd.stops!!)
            val t3 = System.nanoTime()
            fd.contour?.let { ct -> f.extra[ct.input]?.let { MapRaster.contours(MapRaster.resample(f.grid, it, idx, true), frame, ct.interval) } }
            val t4 = System.nanoTime()
            if (round == 2) println(String.format("%-8s compute %4.0f  resample %4.0f  colours %4.0f  contours %4.0f ms", fid,
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, (t4 - t3) / 1e6))
        }
    }
}
