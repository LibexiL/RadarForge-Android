package com.libexil.radarforge.core

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan

/**
 * A small GRIB2 reader, the same as the desktop app's: regular latitude / longitude (template 3.0) and
 * Lambert conformal (3.30) grids; simple (5.0), complex (5.2), complex with spatial differencing (5.3) and
 * PNG (5.41) packing, with or without a bitmap. JPEG 2000 (5.40) isn't read (NOAA's models don't use it).
 */
object Grib2 {
    class GribException(msg: String) : Exception(msg)

    class Lcc(val n: Double, val f: Double, val lov: Double, val r: Double) {
        var x1 = 0.0; var y1 = 0.0; var dx = 3000.0; var dy = 3000.0; var uvGrid = true

        fun forward(lat: Double, lon: Double, out: DoubleArray) {
            val p = Math.toRadians(lat.coerceIn(-89.9, 89.9))
            val dl = Math.toRadians(((lon - lov + 540.0) % 360.0) - 180.0)
            val rho = r * f / tan(PI / 4 + p / 2).pow(n)
            val th = n * dl
            out[0] = rho * sin(th)
            out[1] = -rho * cos(th)
        }

        fun inverse(x: Double, y: Double, out: DoubleArray) {
            val s = sign(n)
            val rho = s * hypot(x, y)
            val th = atan2(x * s, -y * s)
            val lat = 2 * atan((r * f / (if (rho == 0.0) 1e-9 else rho)).pow(1 / n)) - PI / 2
            out[0] = Math.toDegrees(lat)
            out[1] = ((lov + Math.toDegrees(th / n) + 540.0) % 360.0) - 180.0
        }

        companion object {
            fun of(latin1: Double, latin2: Double, lov: Double, r: Double): Lcc {
                val p1 = Math.toRadians(latin1); val p2 = Math.toRadians(latin2)
                val n = if (Math.abs(latin1 - latin2) < 1e-9) sin(p1)
                    else ln(cos(p1) / cos(p2)) / ln(tan(PI / 4 + p2 / 2) / tan(PI / 4 + p1 / 2))
                val f = cos(p1) * tan(PI / 4 + p1 / 2).pow(n) / n
                return Lcc(n, f, lov, r)
            }
        }
    }

    /** One field: values row by row (row 0 = the file's first row), NaN where there is none. */
    class Grid(
        val values: FloatArray, val ni: Int, val nj: Int,
        val la1: Double, val lo1: Double, val di: Double, val dj: Double, val scan: Int,
        val lcc: Lcc?, val refTimeMs: Long,
    ) {
        val globalLatLon: Boolean get() = lcc == null && ni * di >= 359.0

        /** Fractional (row, col) of a point. */
        fun index(lat: Double, lon: Double, out: DoubleArray) {
            val l = lcc
            if (l != null) {
                l.forward(lat, lon, out)
                var col = (out[0] - l.x1) / l.dx
                var row = (out[1] - l.y1) / l.dy
                if (scan and 0x40 == 0) row = -row
                if (scan and 0x80 != 0) col = -col
                out[0] = row; out[1] = col
                return
            }
            var dlon = ((lon - lo1 + 540.0) % 360.0) - 180.0
            if (globalLatLon && dlon < 0) dlon += 360.0
            out[1] = if (scan and 0x80 != 0) -dlon / di else dlon / di
            out[0] = if (scan and 0x40 != 0) (lat - la1) / dj else (la1 - lat) / dj
        }

        /** lat / lon of fractional grid indices. */
        fun latLon(row: Double, col: Double, out: DoubleArray) {
            val l = lcc
            if (l != null) {
                val sr = if (scan and 0x40 != 0) row else -row
                val sc = if (scan and 0x80 != 0) -col else col
                l.inverse(l.x1 + sc * l.dx, l.y1 + sr * l.dy, out)
                return
            }
            out[0] = la1 + if (scan and 0x40 != 0) row * dj else -row * dj
            out[1] = ((lo1 + (if (scan and 0x80 != 0) -col * di else col * di) + 540.0) % 360.0) - 180.0
        }

        fun at(r: Int, c: Int): Float = values[r * ni + c]

        /** The value at a point: nearest grid point, or bilinear between the four around it. */
        fun sample(lat: Double, lon: Double, bilinear: Boolean, tmp: DoubleArray = DoubleArray(2)): Float =
            sampleRc(values, tmp.also { index(lat, lon, it) }[0], tmp[1], bilinear)

        fun sampleRc(src: FloatArray, row: Double, col: Double, bilinear: Boolean): Float {
            if (!bilinear) {
                val r = Math.round(row).toInt()
                var c = Math.round(col).toInt()
                if (globalLatLon) c = ((c % ni) + ni) % ni
                if (r < 0 || r >= nj || c < 0 || c >= ni) return Float.NaN
                return src[r * ni + c]
            }
            val r0 = Math.floor(row).toInt()
            var c0 = Math.floor(col).toInt()
            if (r0 < 0 || r0 >= nj - 1 || c0 < 0 || (c0 >= ni - 1 && !globalLatLon)) return Float.NaN
            val fr = (row - r0).toFloat(); val fc = (col - c0).toFloat()
            val c1 = if (globalLatLon) (c0 + 1) % ni else c0 + 1
            val a = src[r0 * ni + c0]; val b = src[r0 * ni + c1]
            val c = src[(r0 + 1) * ni + c0]; val d = src[(r0 + 1) * ni + c1]
            val v = (a * (1 - fc) + b * fc) * (1 - fr) + (c * (1 - fc) + d * fc) * fr
            if (!v.isNaN()) return v
            return if (fr < 0.5f) (if (fc < 0.5f) a else b) else (if (fc < 0.5f) c else d)
        }
    }

    // ---------------------------------------------------------------- reading
    private fun u(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[off + i].toLong() and 0xff)
        return v
    }

    /** GRIB2 signed integers are sign and magnitude. */
    private fun s(b: ByteArray, off: Int, n: Int): Long {
        val v = u(b, off, n)
        val top = 1L shl (8 * n - 1)
        return if (v and top != 0L) -(v - top) else v
    }

    /** Splits a file (or byte range, gzipped or not) into its messages. */
    fun messages(raw0: ByteArray): List<ByteArray> {
        val raw = if (raw0.size > 2 && raw0[0] == 0x1f.toByte() && raw0[1] == 0x8b.toByte())
            GZIPInputStream(raw0.inputStream()).readBytes() else raw0
        val out = ArrayList<ByteArray>()
        var pos = 0
        while (true) {
            val start = find(raw, pos)
            if (start < 0 || start + 16 > raw.size) break
            if (raw[start + 7].toInt() != 2) throw GribException("GRIB edition ${raw[start + 7]} isn't supported")
            val total = u(raw, start + 8, 8).toInt()
            if (total < 16 || start + total > raw.size) throw GribException("truncated GRIB message")
            out.add(raw.copyOfRange(start, start + total))
            pos = start + total
        }
        return out
    }

    private fun find(b: ByteArray, from: Int): Int {
        var i = from
        while (i + 4 <= b.size) {
            if (b[i] == 'G'.code.toByte() && b[i + 1] == 'R'.code.toByte() && b[i + 2] == 'I'.code.toByte() &&
                b[i + 3] == 'B'.code.toByte()) return i
            i++
        }
        return -1
    }

    fun decodeAll(raw: ByteArray): List<Grid> = messages(raw).map { decode(it) }

    private class Drs(val n: Int, val tmpl: Int, val r: Float, val e: Int, val d: Int, val nbits: Int) {
        var missMgmt = 0; var ng = 0; var gwRef = 0; var gwBits = 0; var glRef = 0L; var glInc = 0; var glLast = 0L
        var glBits = 0; var order = 0; var octets = 0
    }

    /**
     * The first field of a message (or file). For a big PNG-packed lat/lon grid (MRMS) only the rows between
     * latitudes [latN] and [latS] are kept, every [step]th row and column.
     */
    fun decode(msg0: ByteArray, latN: Double = 90.0, latS: Double = -90.0, step: Int = 1): Grid {
        val msg = messages(msg0).firstOrNull() ?: throw GribException("not a GRIB file")
        val total = msg.size
        var pos = 16
        var gridSec: ByteArray? = null
        var drs: Drs? = null
        var bitmap: ByteArray? = null
        var data: ByteArray? = null
        var refMs = 0L
        while (pos < total - 4) {
            if (msg[pos] == '7'.code.toByte() && msg[pos + 1] == '7'.code.toByte() && msg[pos + 2] == '7'.code.toByte()) break
            val len = u(msg, pos, 4).toInt()
            if (len < 5) throw GribException("corrupt section")
            val num = msg[pos + 4].toInt()
            val sec = msg.copyOfRange(pos, pos + len)
            when (num) {
                1 -> refMs = try {
                    java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                        clear(); set(u(sec, 12, 2).toInt(), sec[14].toInt() - 1, sec[15].toInt(), sec[16].toInt(), sec[17].toInt(),
                        sec[18].toInt())
                    }.timeInMillis
                } catch (_: Exception) { 0L }
                3 -> gridSec = sec
                5 -> drs = drsOf(sec)
                6 -> {
                    val ind = sec[5].toInt() and 0xff
                    if (ind == 0) bitmap = sec.copyOfRange(6, sec.size)
                    else if (ind != 255) throw GribException("predefined bitmaps aren't supported")
                }
                7 -> { data = sec.copyOfRange(5, sec.size); pos = total }
            }
            if (num == 7) break
            pos += len
        }
        val g = gridSec ?: throw GribException("no grid section")
        val dr = drs ?: throw GribException("no data representation")
        val dat = data ?: throw GribException("no data")
        val tmpl3 = u(g, 12, 2).toInt()
        val npts = u(g, 6, 4).toInt()
        val ni: Int; val nj: Int; val la1: Double; val lo1: Double; var di = 0.0; var dj = 0.0; val scan: Int
        var lcc: Lcc? = null
        when (tmpl3) {
            0 -> {
                ni = u(g, 30, 4).toInt(); nj = u(g, 34, 4).toInt()
                val basic = u(g, 38, 4); val sub = u(g, 42, 4)
                val unit = if (basic != 0L && basic != 0xFFFFFFFFL && sub != 0L && sub != 0xFFFFFFFFL) basic.toDouble() / sub else 1e-6
                la1 = s(g, 46, 4) * unit
                val lo1r = u(g, 50, 4) * unit
                val la2 = s(g, 55, 4) * unit
                val lo2 = u(g, 59, 4) * unit
                di = u(g, 63, 4) * unit; dj = u(g, 67, 4) * unit
                scan = g[71].toInt() and 0xff
                if (di <= 0 || di > 360) di = Math.abs(((lo2 - lo1r + 360.0) % 360.0)) / maxOf(ni - 1, 1)
                if (dj <= 0 || dj > 180) dj = Math.abs(la2 - la1) / maxOf(nj - 1, 1)
                lo1 = ((lo1r + 540.0) % 360.0) - 180.0
            }
            30 -> {
                val r = earthRadius(g, 14)
                ni = u(g, 30, 4).toInt(); nj = u(g, 34, 4).toInt()
                la1 = s(g, 38, 4) * 1e-6
                val lo1r = u(g, 42, 4) * 1e-6
                val lov = u(g, 51, 4) * 1e-6
                val dx = u(g, 55, 4) * 1e-3; val dy = u(g, 59, 4) * 1e-3
                scan = g[64].toInt() and 0xff
                val latin1 = s(g, 65, 4) * 1e-6; val latin2 = s(g, 69, 4) * 1e-6
                if ((g[63].toInt() and 0x80) != 0) throw GribException("south-pole Lambert grids aren't supported")
                lcc = Lcc.of(latin1, latin2, ((lov + 540.0) % 360.0) - 180.0, r)
                val xy = DoubleArray(2)
                lcc.forward(la1, lo1r, xy)
                lcc.x1 = xy[0]; lcc.y1 = xy[1]; lcc.dx = dx; lcc.dy = dy
                lcc.uvGrid = (g[46].toInt() and 0x08) != 0
                lo1 = ((lo1r + 540.0) % 360.0) - 180.0
            }
            else -> throw GribException("grid template 3.$tmpl3 isn't supported")
        }
        if (ni * nj != npts) throw GribException("grid size mismatch")
        if (scan and 0x20 != 0) throw GribException("column-major grids aren't supported")
        val band = tmpl3 == 0 && dr.tmpl == 41 && bitmap == null && scan and 0x10 == 0 && dr.nbits > 0 &&
            (step > 1 || latN < 90.0 || latS > -90.0)
        if (band) {
            // rows run north to south unless the scan flag says otherwise
            val north = scan and 0x40 == 0
            fun rowOf(lat: Double) = if (north) (la1 - lat) / dj else (lat - la1) / dj
            val r0 = maxOf(0, Math.floor(minOf(rowOf(latN), rowOf(latS))).toInt() - 1)
            val r1 = minOf(nj, Math.ceil(maxOf(rowOf(latN), rowOf(latS))).toInt() + 2)
            val img = Png.decode(dat, r0, r1, step)
            val e2 = 2.0.pow(dr.e).toFloat()
            val d10 = 10.0.pow(dr.d).toFloat()
            val vals = FloatArray(img.values.size) { (dr.r + img.values[it] * e2) / d10 }
            val nla1 = la1 + (if (north) -r0 * dj else r0 * dj)
            return Grid(vals, img.width, img.height, nla1, lo1, di * step, dj * step, scan, null, refMs)
        }
        // values
        val n = dr.n
        val packed = FloatArray(n)
        var missing: BooleanArray? = null
        when (dr.tmpl) {
            0 -> { val br = Bits(dat); for (i in 0 until n) packed[i] = br.read(dr.nbits).toFloat() }
            2, 3 -> if (dr.ng > 0 && n > 0) missing = complex(dr, dat, packed)
            41 -> if (dr.nbits > 0 && dat.isNotEmpty()) {
                val img = Png.decodeGray(dat)
                if (img.values.size < n) throw GribException("image smaller than the field")
                for (i in 0 until n) packed[i] = img.values[i].toFloat()
            }
            else -> throw GribException("data template 5.${dr.tmpl} isn't supported on the phone")
        }
        val e2 = 2.0.pow(dr.e).toFloat()
        val d10 = 10.0.pow(dr.d).toFloat()
        for (i in 0 until n) packed[i] = (dr.r + packed[i] * e2) / d10
        if (missing != null) for (i in 0 until n) if (missing[i]) packed[i] = Float.NaN
        val values: FloatArray
        val bm = bitmap
        if (bm != null) {
            values = FloatArray(npts) { Float.NaN }
            var k = 0
            for (i in 0 until npts) {
                if ((bm[i ushr 3].toInt() shr (7 - (i and 7))) and 1 == 1) { values[i] = if (k < n) packed[k] else Float.NaN; k++ }
            }
        } else {
            if (n != npts) throw GribException("value count mismatch")
            values = packed
        }
        if (scan and 0x10 != 0) {        // every other row runs backwards
            for (j in 1 until nj step 2) {
                var a = j * ni; var b = a + ni - 1
                while (a < b) { val t = values[a]; values[a] = values[b]; values[b] = t; a++; b-- }
            }
        }
        return Grid(values, ni, nj, la1, lo1, di, dj, scan, lcc, refMs)
    }

    private fun earthRadius(g: ByteArray, off: Int): Double = when (g[off].toInt() and 0xff) {
        0 -> 6367470.0
        1 -> { val f = g[off + 1].toInt(); val v = u(g, off + 2, 4); if (v == 0L || v == 0xFFFFFFFFL) 6371229.0 else v / 10.0.pow(f) }
        8 -> 6371200.0
        else -> 6371229.0
    }

    private fun drsOf(sec: ByteArray): Drs {
        val tmpl = u(sec, 9, 2).toInt()
        if (tmpl !in listOf(0, 2, 3, 40, 41)) throw GribException("data template 5.$tmpl isn't supported")
        val r = java.lang.Float.intBitsToFloat(u(sec, 11, 4).toInt())
        val d = Drs(u(sec, 5, 4).toInt(), tmpl, r, s(sec, 15, 2).toInt(), s(sec, 17, 2).toInt(), sec[19].toInt() and 0xff)
        if (tmpl == 2 || tmpl == 3) {
            d.missMgmt = sec[22].toInt() and 0xff
            d.ng = u(sec, 31, 4).toInt()
            d.gwRef = sec[35].toInt() and 0xff
            d.gwBits = sec[36].toInt() and 0xff
            d.glRef = u(sec, 37, 4)
            d.glInc = sec[41].toInt() and 0xff
            d.glLast = u(sec, 42, 4)
            d.glBits = sec[46].toInt() and 0xff
            if (tmpl == 3) { d.order = sec[47].toInt() and 0xff; d.octets = sec[48].toInt() and 0xff }
        }
        return d
    }

    /** Reads big-endian bit fields. */
    private class Bits(val b: ByteArray, var pos: Long = 0) {
        fun read(n: Int): Long {
            if (n == 0) return 0
            var v = 0L
            var left = n
            while (left > 0) {
                val byte = (pos ushr 3).toInt()
                val cur = if (byte < b.size) b[byte].toInt() and 0xff else 0
                val off = (pos and 7).toInt()
                val take = minOf(8 - off, left)
                val bits = (cur shr (8 - off - take)) and ((1 shl take) - 1)
                v = (v shl take) or bits.toLong()
                left -= take
                pos += take
            }
            return v
        }
        fun align() { pos = (pos + 7) / 8 * 8 }
    }

    /** Templates 5.2 / 5.3 into [out] (unscaled); returns the missing-value mask (or null). */
    private fun complex(d: Drs, data: ByteArray, out: FloatArray): BooleanArray? {
        val n = d.n
        val br = Bits(data)
        var ival1 = 0L; var ival2 = 0L; var minsd = 0L
        if (d.tmpl == 3 && d.order > 0) {
            val nb = d.octets
            fun sval(): Long {
                val v = br.read(8 * nb)
                val top = 1L shl (8 * nb - 1)
                return if (v and top != 0L) -(v - top) else v
            }
            ival1 = sval()
            if (d.order == 2) ival2 = sval()
            minsd = sval()
        }
        val ng = d.ng
        val refs = LongArray(ng) { br.read(d.nbits) }
        br.align()
        val widths = IntArray(ng) { (br.read(d.gwBits) + d.gwRef).toInt() }
        br.align()
        val lens = LongArray(ng) { br.read(d.glBits) * d.glInc + d.glRef }
        br.align()
        if (ng > 0) lens[ng - 1] = d.glLast
        if (lens.sum() != n.toLong()) throw GribException("complex packing: group lengths add up to ${lens.sum()}, not $n")
        val vals = LongArray(n)
        val miss = if (d.missMgmt == 1 || d.missMgmt == 2) BooleanArray(n) else null
        val grefAll = (1L shl d.nbits) - 1
        var k = 0
        for (g in 0 until ng) {
            val w = widths[g]
            val maxv = (1L shl w) - 1
            for (i in 0 until lens[g].toInt()) {
                val raw = br.read(w)
                if (miss != null) {
                    val m = if (w == 0) refs[g] == grefAll || (d.missMgmt == 2 && refs[g] == grefAll - 1)
                        else raw == maxv || (d.missMgmt == 2 && raw == maxv - 1)
                    miss[k] = m
                }
                vals[k++] = raw + refs[g]
            }
        }
        if (d.tmpl == 3 && d.order > 0) {
            // undo the spatial differencing over the values that aren't missing
            var idx = 0
            var f1 = 0L; var f2 = 0L
            for (i in 0 until n) {
                if (miss != null && miss[i]) continue
                val v: Long = when {
                    idx == 0 -> ival1
                    idx == 1 && d.order == 2 -> ival2
                    d.order == 1 -> vals[i] + minsd + f1
                    else -> vals[i] + minsd + 2 * f1 - f2
                }
                f2 = f1; f1 = v
                vals[i] = v
                idx++
            }
        }
        for (i in 0 until n) out[i] = vals[i].toFloat()
        return miss
    }
}

/** Grey PNG images (GRIB 5.41 and MRMS): decoded without Android's image classes (those squash 16 bits to 8). */
object Png {
    class Image(val width: Int, val height: Int, val values: IntArray)

    /** The whole image. */
    fun decodeGray(png: ByteArray): Image = decode(png, 0, Int.MAX_VALUE, 1)

    /**
     * Rows [row0, row1) of the image, keeping every [step]th row and column (for huge MRMS grids: only the part
     * on screen, a little coarser). values: (rows kept) x (columns kept).
     */
    fun decode(png: ByteArray, row0: Int, row1: Int, step: Int): Image {
        if (png.size < 8 || png[1] != 'P'.code.toByte() || png[2] != 'N'.code.toByte()) throw Grib2.GribException("not a PNG")
        var pos = 8
        var w = 0; var h = 0; var depth = 8; var color = 0
        val idat = ByteArrayOutputStream()
        while (pos + 8 <= png.size) {
            val len = ((png[pos].toInt() and 0xff) shl 24) or ((png[pos + 1].toInt() and 0xff) shl 16) or
                ((png[pos + 2].toInt() and 0xff) shl 8) or (png[pos + 3].toInt() and 0xff)
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            val d = pos + 8
            when (type) {
                "IHDR" -> {
                    w = ((png[d].toInt() and 0xff) shl 24) or ((png[d + 1].toInt() and 0xff) shl 16) or
                        ((png[d + 2].toInt() and 0xff) shl 8) or (png[d + 3].toInt() and 0xff)
                    h = ((png[d + 4].toInt() and 0xff) shl 24) or ((png[d + 5].toInt() and 0xff) shl 16) or
                        ((png[d + 6].toInt() and 0xff) shl 8) or (png[d + 7].toInt() and 0xff)
                    depth = png[d + 8].toInt() and 0xff
                    color = png[d + 9].toInt() and 0xff
                    if (png[d + 12].toInt() != 0) throw Grib2.GribException("interlaced PNGs aren't supported")
                }
                "IDAT" -> idat.write(png, d, len)
                "IEND" -> break
            }
            pos = d + len + 4
        }
        val channels = when (color) { 0 -> 1; 2 -> 3; 4 -> 2; 6 -> 4; else -> throw Grib2.GribException("PNG colour type $color") }
        val bpp = maxOf(1, channels * depth / 8)
        val rowBytes = (w * channels * depth + 7) / 8
        val inf = Inflater()
        inf.setInput(idat.toByteArray())
        var prev = ByteArray(rowBytes)
        var cur = ByteArray(rowBytes)
        val r1 = minOf(row1, h)
        val keepRows = if (r1 <= row0) 0 else (r1 - row0 + step - 1) / step
        val keepCols = (w + step - 1) / step
        val out = IntArray(keepRows * keepCols)
        val line = ByteArray(rowBytes + 1)
        var kr = 0
        for (y in 0 until r1) {
            var got = 0
            while (got < line.size) {
                val k = inf.inflate(line, got, line.size - got)
                if (k == 0) { if (inf.finished() || inf.needsInput()) break }
                got += k
            }
            if (got < line.size) throw Grib2.GribException("PNG data ended early")
            val filter = line[0].toInt()
            for (i in 0 until rowBytes) {
                val x = line[i + 1].toInt() and 0xff
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xff else 0
                val b = prev[i].toInt() and 0xff
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xff else 0
                val v = when (filter) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + ((a + b) ushr 1)
                    4 -> {
                        val p = a + b - c
                        val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> throw Grib2.GribException("PNG filter $filter")
                }
                cur[i] = v.toByte()
            }
            if (y >= row0 && (y - row0) % step == 0 && kr < keepRows) {
                var kc = 0
                var x = 0
                while (x < w && kc < keepCols) {
                    out[kr * keepCols + kc] = when {
                        depth == 16 -> { val o = x * channels * 2; ((cur[o].toInt() and 0xff) shl 8) or (cur[o + 1].toInt() and 0xff) }
                        depth == 8 && channels >= 3 -> {
                            val o = x * channels
                            ((cur[o].toInt() and 0xff) shl 16) or ((cur[o + 1].toInt() and 0xff) shl 8) or (cur[o + 2].toInt() and 0xff)
                        }
                        depth == 8 -> cur[x * channels].toInt() and 0xff
                        else -> {
                            val bit = x * depth
                            (cur[bit ushr 3].toInt() shr (8 - depth - (bit and 7))) and ((1 shl depth) - 1)
                        }
                    }
                    kc++; x += step
                }
                kr++
            }
            val t = prev; prev = cur; cur = t
        }
        inf.end()
        return Image(keepCols, keepRows, out)
    }
}
