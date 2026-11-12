package com.libexil.radarforge.core

import kotlin.math.pow

/**
 * A JPEG 2000 decoder for the pictures inside GRIB2 fields (data template 5.40, how NOAA packs the RAP and
 * NAM): a raw codestream or a JP2 file, one component (the first is returned), one or more tiles, the
 * reversible 5/3 wavelet (lossless, what NOAA uses) and the irreversible 9/7, tag trees, the MQ coder and the
 * three EBCOT coding passes. Follows the structure of pdf.js's jpx.js. Not supported: COC, packed packet
 * headers (PPM/PPT), the bypass / termall / vertically-causal code-block modes, PCRL and CPRL progression
 * with several precincts.
 */
class JpxException(msg: String) : Exception(msg)

object Jpx {
    /** The first component's samples, row by row, as the codestream stores them (unsigned unless [signed]). */
    class Image(val width: Int, val height: Int, val precision: Int, val signed: Boolean, val samples: IntArray)

    private fun u8(d: ByteArray, p: Int) = d[p].toInt() and 0xff
    private fun u16(d: ByteArray, p: Int) = (u8(d, p) shl 8) or u8(d, p + 1)
    private fun u32(d: ByteArray, p: Int): Long = (u16(d, p).toLong() shl 16) or u16(d, p + 2).toLong()
    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b           // a >= 0
    private fun log2ceil(x: Int): Int { var n = 0; while ((1 shl n) < x) n++; return n }

    fun decode(data: ByteArray): Image {
        if (data.size >= 2 && u16(data, 0) == 0xFF4F) return codestream(data, 0, data.size)
        var pos = 0                                                // a JP2 file: find the codestream box
        while (pos + 8 <= data.size) {
            var len = u32(data, pos)
            val type = u32(data, pos + 4)
            var hdr = 8
            if (len == 1L) { len = (u32(data, pos + 8) shl 32) or u32(data, pos + 12); hdr = 16 }
            else if (len == 0L) len = (data.size - pos).toLong()
            if (type == 0x6A703263L) return codestream(data, pos + hdr, minOf(data.size.toLong(), pos + len).toInt())
            if (len < 8) break
            pos += len.toInt()
        }
        throw JpxException("no JPEG 2000 codestream")
    }

    // ------------------------------------------------------------------ headers
    private class Siz(val x1: Int, val y1: Int, val x0: Int, val y0: Int, val xt: Int, val yt: Int, val xt0: Int, val yt0: Int,
                      val csiz: Int, val precision: IntArray, val signed: BooleanArray, val xr: IntArray, val yr: IntArray) {
        val tilesWide = ceilDiv(x1 - xt0, xt)
    }

    private class Cod(val customPrecincts: Boolean, val sop: Boolean, val eph: Boolean, val progression: Int, val layers: Int,
                      val levels: Int, val xcb: Int, val ycb: Int, val bypass: Boolean, val reset: Boolean, val termall: Boolean,
                      val vcausal: Boolean, val segsym: Boolean, val reversible: Boolean, val ppx: IntArray, val ppy: IntArray)

    private class Qcd(val guardBits: Int, val derived: Boolean, val eps: IntArray, val mu: IntArray)

    private fun parseSiz(d: ByteArray, p: Int): Siz {
        val csiz = u16(d, p + 34)
        val prec = IntArray(csiz); val signed = BooleanArray(csiz); val xr = IntArray(csiz); val yr = IntArray(csiz)
        for (c in 0 until csiz) {
            val b = u8(d, p + 36 + c * 3)
            prec[c] = (b and 0x7f) + 1; signed[c] = b and 0x80 != 0
            xr[c] = u8(d, p + 37 + c * 3); yr[c] = u8(d, p + 38 + c * 3)
        }
        return Siz(u32(d, p + 2).toInt(), u32(d, p + 6).toInt(), u32(d, p + 10).toInt(), u32(d, p + 14).toInt(),
            u32(d, p + 18).toInt(), u32(d, p + 22).toInt(), u32(d, p + 26).toInt(), u32(d, p + 30).toInt(), csiz, prec, signed, xr, yr)
    }

    private fun parseCod(d: ByteArray, p: Int, end: Int): Cod {
        val scod = u8(d, p)
        val levels = u8(d, p + 5)
        val style = u8(d, p + 8)
        val custom = scod and 1 != 0
        val ppx = IntArray(levels + 1) { 15 }; val ppy = IntArray(levels + 1) { 15 }
        if (custom) for (r in 0..levels) { if (p + 10 + r >= end) break; val b = u8(d, p + 10 + r); ppx[r] = b and 15; ppy[r] = b shr 4 }
        val cod = Cod(custom, scod and 2 != 0, scod and 4 != 0, u8(d, p + 1), u16(d, p + 2), levels,
            (u8(d, p + 6) and 15) + 2, (u8(d, p + 7) and 15) + 2, style and 1 != 0, style and 2 != 0, style and 4 != 0,
            style and 8 != 0, style and 32 != 0, u8(d, p + 9) == 1, ppx, ppy)
        if (cod.bypass || cod.termall || cod.vcausal) throw JpxException("code-block style ${style} isn't supported")
        return cod
    }

    private fun parseQcd(d: ByteArray, p: Int, end: Int): Qcd {
        val sq = u8(d, p)
        val guard = sq shr 5
        val eps = ArrayList<Int>(); val mu = ArrayList<Int>()
        var j = p + 1
        when (sq and 0x1f) {
            0 -> while (j < end) { eps.add(u8(d, j) shr 3); mu.add(0); j++ }
            1 -> { val v = u16(d, j); eps.add(v shr 11); mu.add(v and 0x7ff) }
            2 -> while (j + 1 < end) { val v = u16(d, j); eps.add(v shr 11); mu.add(v and 0x7ff); j += 2 }
            else -> throw JpxException("quantization style $sq")
        }
        return Qcd(guard, sq and 0x1f == 1, eps.toIntArray(), mu.toIntArray())
    }

    // ------------------------------------------------------------------ tile structure
    private class Precinct(val cbxMin: Int, val cbyMin: Int) {
        var cbxMax = cbxMin; var cbyMax = cbyMin
        var inclusion: InclusionTree? = null
        var zeroPlanes: TagTree? = null
    }

    private class Chunk(val data: ByteArray, val start: Int, val end: Int, val passes: Int)

    private class CodeBlock(val cbx: Int, val cby: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val precinctNumber: Int) {
        lateinit var precinct: Precinct
        var everIncluded = false
        var lblock = 3
        var zeroBitPlanes = 0
        val chunks = ArrayList<Chunk>()
    }

    private class Subband(val type: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int) {       // 0 LL, 1 HL, 2 LH, 3 HH
        val codeblocks = ArrayList<CodeBlock>()
        val precincts = HashMap<Int, Precinct>()
    }

    private class Resolution(val r: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        var subbands: List<Subband> = emptyList()
        var numPrecinctsWide = 0; var numPrecinctsHigh = 0; var numPrecincts = 0
        var pwSub = 0; var phSub = 0
    }

    private class TileComp(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val cod: Cod, val qcd: Qcd) {
        var resolutions: List<Resolution> = emptyList()
    }

    private class Tile(val index: Int) {
        var cod: Cod? = null; var qcd: Qcd? = null; var qcc: HashMap<Int, Qcd> = HashMap()
        var partIndex = 0; var dataEnd = 0
        var comps: Array<TileComp> = emptyArray()
        var packets: PacketIterator? = null
        var built = false
    }

    private class Packet(val layer: Int, val codeblocks: List<CodeBlock>)

    private fun buildTile(siz: Siz, t: Tile) {
        val p = t.index % siz.tilesWide; val q = t.index / siz.tilesWide
        val tx0 = maxOf(siz.xt0 + p * siz.xt, siz.x0); val ty0 = maxOf(siz.yt0 + q * siz.yt, siz.y0)
        val tx1 = minOf(siz.xt0 + (p + 1) * siz.xt, siz.x1); val ty1 = minOf(siz.yt0 + (q + 1) * siz.yt, siz.y1)
        val cod = t.cod ?: throw JpxException("no COD"); val qcd = t.qcd ?: throw JpxException("no QCD")
        t.comps = Array(siz.csiz) { c ->
            val tc = TileComp(ceilDiv(tx0, siz.xr[c]), ceilDiv(ty0, siz.yr[c]), ceilDiv(tx1, siz.xr[c]), ceilDiv(ty1, siz.yr[c]), cod, t.qcc[c] ?: qcd)
            val res = ArrayList<Resolution>()
            for (r in 0..cod.levels) {
                val scale = 1 shl (cod.levels - r)
                val rs = Resolution(r, ceilDiv(tc.x0, scale), ceilDiv(tc.y0, scale), ceilDiv(tc.x1, scale), ceilDiv(tc.y1, scale))
                val ppx = cod.ppx[r]; val ppy = cod.ppy[r]
                val xcb = if (r > 0) minOf(cod.xcb, ppx - 1) else minOf(cod.xcb, ppx)
                val ycb = if (r > 0) minOf(cod.ycb, ppy - 1) else minOf(cod.ycb, ppy)
                val pw = 1 shl ppx; val ph = 1 shl ppy
                rs.pwSub = 1 shl (ppx + if (r == 0) 0 else -1); rs.phSub = 1 shl (ppy + if (r == 0) 0 else -1)
                rs.numPrecinctsWide = if (rs.x1 > rs.x0) ceilDiv(rs.x1, pw) - rs.x0 / pw else 0
                rs.numPrecinctsHigh = if (rs.y1 > rs.y0) ceilDiv(rs.y1, ph) - rs.y0 / ph else 0
                rs.numPrecincts = rs.numPrecinctsWide * rs.numPrecinctsHigh
                val bands = ArrayList<Subband>()
                if (r == 0) {
                    bands.add(Subband(0, ceilDiv(tc.x0, scale), ceilDiv(tc.y0, scale), ceilDiv(tc.x1, scale), ceilDiv(tc.y1, scale)))
                } else {
                    val bs = 1 shl (cod.levels - r + 1)
                    fun c(v: Int) = ceilDiv(v, bs)                          // ceil(v / bs)
                    fun ch(v: Int) = Math.ceil(v.toDouble() / bs - 0.5).toInt()   // ceil(v / bs - 1/2)
                    bands.add(Subband(1, ch(tc.x0), c(tc.y0), ch(tc.x1), c(tc.y1)))
                    bands.add(Subband(2, c(tc.x0), ch(tc.y0), c(tc.x1), ch(tc.y1)))
                    bands.add(Subband(3, ch(tc.x0), ch(tc.y0), ch(tc.x1), ch(tc.y1)))
                }
                for (sb in bands) buildCodeblocks(sb, rs, xcb, ycb)
                rs.subbands = bands
                res.add(rs)
            }
            tc.resolutions = res
            tc
        }
        t.packets = PacketIterator(t, cod)
        t.built = true
    }

    private fun buildCodeblocks(sb: Subband, rs: Resolution, xcb: Int, ycb: Int) {
        val cw = 1 shl xcb; val chh = 1 shl ycb
        val cbx0 = sb.x0 shr xcb; val cby0 = sb.y0 shr ycb
        val cbx1 = (sb.x1 + cw - 1) shr xcb; val cby1 = (sb.y1 + chh - 1) shr ycb
        for (j in cby0 until cby1) for (i in cbx0 until cbx1) {
            val x0 = maxOf(cw * i, sb.x0); val y0 = maxOf(chh * j, sb.y0)
            val x1 = minOf(cw * (i + 1), sb.x1); val y1 = minOf(chh * (j + 1), sb.y1)
            if (x1 <= x0 || y1 <= y0) continue
            val pi = (x0 - sb.x0) / rs.pwSub; val pj = (y0 - sb.y0) / rs.phSub
            val pn = pi + pj * rs.numPrecinctsWide
            val cb = CodeBlock(i, j, x0, y0, x1, y1, pn)
            val pr = sb.precincts.getOrPut(pn) { Precinct(i, j) }
            if (i < pr.cbxMin || j < pr.cbyMin) {            // (precincts are met in raster order; keep the box right anyway)
                val np = Precinct(minOf(i, pr.cbxMin), minOf(j, pr.cbyMin)).apply { cbxMax = maxOf(i, pr.cbxMax); cbyMax = maxOf(j, pr.cbyMax) }
                sb.precincts[pn] = np
                for (o in sb.codeblocks) if (o.precinctNumber == pn) o.precinct = np
                cb.precinct = np
            } else {
                pr.cbxMax = maxOf(pr.cbxMax, i); pr.cbyMax = maxOf(pr.cbyMax, j)
                cb.precinct = pr
            }
            sb.codeblocks.add(cb)
        }
    }

    /** Packets in progression order: the next (layer, resolution, component, precinct) each time. */
    private class PacketIterator(val t: Tile, cod: Cod) {
        private val layers = cod.layers
        private val comps = t.comps.size
        private val maxLevels = t.comps.maxOf { it.cod.levels }
        private val order = cod.progression
        private val seq = ArrayList<IntArray>()          // (l, r, c, k)
        private var at = 0

        init {
            fun precincts(c: Int, r: Int): Int { val tc = t.comps[c]; return if (r > tc.cod.levels) -1 else tc.resolutions[r].numPrecincts }
            when (order) {
                0 -> for (l in 0 until layers) for (r in 0..maxLevels) for (c in 0 until comps) { val n = precincts(c, r); for (k in 0 until n) seq.add(intArrayOf(l, r, c, k)) }
                1 -> for (r in 0..maxLevels) for (l in 0 until layers) for (c in 0 until comps) { val n = precincts(c, r); for (k in 0 until n) seq.add(intArrayOf(l, r, c, k)) }
                2 -> for (r in 0..maxLevels) { val n = (0 until comps).maxOf { precincts(it, r) }; for (k in 0 until n) for (c in 0 until comps) if (k < precincts(c, r)) for (l in 0 until layers) seq.add(intArrayOf(l, r, c, k)) }
                3, 4 -> {
                    if ((0 until comps).any { c -> (0..t.comps[c].cod.levels).any { precincts(c, it) > 1 } }) throw JpxException("progression order $order with several precincts")
                    if (order == 3) for (c in 0 until comps) for (r in 0..t.comps[c].cod.levels) for (l in 0 until layers) seq.add(intArrayOf(l, r, c, 0))
                    else for (c in 0 until comps) for (r in 0..t.comps[c].cod.levels) for (l in 0 until layers) seq.add(intArrayOf(l, r, c, 0))
                }
                else -> throw JpxException("progression order $order")
            }
        }

        fun next(): Packet? {
            if (at >= seq.size) return null
            val (l, r, c, k) = seq[at++]
            val rs = t.comps[c].resolutions[r]
            val cbs = ArrayList<CodeBlock>()
            for (sb in rs.subbands) for (cb in sb.codeblocks) if (cb.precinctNumber == k) cbs.add(cb)
            return Packet(l, cbs)
        }
    }

    // ------------------------------------------------------------------ tag trees (B.10.2)
    private class TagTree(width: Int, height: Int) {
        val widths = ArrayList<Int>(); val items = ArrayList<IntArray>()
        var level = 0; val index = ArrayList<Int>()
        var value = 0
        init {
            var w = width; var h = height
            for (i in 0..log2ceil(maxOf(width, height))) { widths.add(w); items.add(IntArray(w * h) { -1 }); index.add(0); w = ceilDiv(w, 2); h = ceilDiv(h, 2) }
        }
        fun reset(i0: Int, j0: Int) {
            var i = i0; var j = j0; var cur = 0; var v = 0
            while (cur < items.size) {
                val idx = i + j * widths[cur]
                if (items[cur][idx] >= 0) { v = items[cur][idx]; break }
                index[cur] = idx
                i = i shr 1; j = j shr 1; cur++
            }
            cur--
            items[cur][index[cur]] = v
            level = cur
        }
        fun increment() { items[level][index[level]]++ }
        fun nextLevel(): Boolean {
            val v = items[level][index[level]]
            level--
            if (level < 0) { value = v; return false }
            items[level][index[level]] = v
            return true
        }
    }

    private class InclusionTree(width: Int, height: Int, default: Int) {
        val widths = ArrayList<Int>(); val items = ArrayList<IntArray>()
        var level = 0; val index = ArrayList<Int>()
        init {
            var w = width; var h = height
            for (i in 0..log2ceil(maxOf(width, height))) { widths.add(w); items.add(IntArray(w * h) { default }); index.add(0); w = ceilDiv(w, 2); h = ceilDiv(h, 2) }
        }
        fun reset(i0: Int, j0: Int, stop: Int): Boolean {
            var i = i0; var j = j0; var cur = 0
            while (cur < items.size) {
                val idx = i + j * widths[cur]
                index[cur] = idx
                val v = items[cur][idx]
                if (v == 0xff) break
                if (v > stop) { level = cur; propagate(); return false }
                i = i shr 1; j = j shr 1; cur++
            }
            level = cur - 1
            return true
        }
        fun increment(stop: Int) { items[level][index[level]] = stop + 1; propagate() }
        fun propagate() {
            var li = level
            val v = items[li][index[li]]
            while (--li >= 0) items[li][index[li]] = v
        }
        fun nextLevel(): Boolean {
            val v = items[level][index[level]]
            items[level][index[level]] = 0xff
            level--
            if (level < 0) return false
            items[level][index[level]] = v
            return true
        }
    }

    // ------------------------------------------------------------------ packet headers (B.10)
    private fun parsePackets(t: Tile, d: ByteArray, offset: Int, dataEnd: Int) {
        val cod = t.cod!!
        val it = t.packets!!
        var position = 0
        val length = dataEnd - offset
        var buffer = 0; var bufferSize = 0; var skipNextBit = false
        fun readBits(count: Int): Int {
            while (bufferSize < count) {
                val b = if (offset + position < dataEnd) u8(d, offset + position) else 0
                position++
                if (skipNextBit) { buffer = (buffer shl 7) or b; bufferSize += 7; skipNextBit = false }
                else { buffer = (buffer shl 8) or b; bufferSize += 8 }
                if (b == 0xff) skipNextBit = true
            }
            bufferSize -= count
            return (buffer ushr bufferSize) and ((1 shl count) - 1)
        }
        fun alignToByte() { bufferSize = 0; if (skipNextBit) { position++; skipNextBit = false } }
        fun skipMarkerIfEqual(v: Int): Boolean {
            if (position > 0 && offset + position < dataEnd && u8(d, offset + position - 1) == 0xff && u8(d, offset + position) == v) { position++; return true }
            if (offset + position + 1 < dataEnd && u8(d, offset + position) == 0xff && u8(d, offset + position + 1) == v) { position += 2; return true }
            return false
        }
        fun readCodingPasses(): Int {
            if (readBits(1) == 0) return 1
            if (readBits(1) == 0) return 2
            var v = readBits(2)
            if (v < 3) return v + 3
            v = readBits(5)
            if (v < 31) return v + 6
            return readBits(7) + 37
        }
        while (position < length) {
            alignToByte()
            if (cod.sop && skipMarkerIfEqual(0x91)) position += 4
            val packet = it.next() ?: break
            if (readBits(1) == 0) {                       // an empty packet
                alignToByte()
                if (cod.eph) skipMarkerIfEqual(0x92)
                continue
            }
            val layer = packet.layer
            val queue = ArrayList<Triple<CodeBlock, Int, Int>>()
            for (cb in packet.codeblocks) {
                val pr = cb.precinct
                val col = cb.cbx - pr.cbxMin; val row = cb.cby - pr.cbyMin
                var included = false; var first = false
                if (cb.everIncluded) included = readBits(1) != 0
                else {
                    var inc = pr.inclusion
                    if (inc == null) {
                        val w = pr.cbxMax - pr.cbxMin + 1; val h = pr.cbyMax - pr.cbyMin + 1
                        inc = InclusionTree(w, h, layer); pr.inclusion = inc
                        pr.zeroPlanes = TagTree(w, h)
                        for (l in 0 until layer) if (readBits(1) != 0) throw JpxException("invalid tag tree")
                    }
                    if (inc.reset(col, row, layer)) {
                        while (true) {
                            if (readBits(1) != 0) {
                                if (!inc.nextLevel()) { cb.everIncluded = true; included = true; first = true; break }
                            } else { inc.increment(layer); break }
                        }
                    }
                }
                if (!included) continue
                if (first) {
                    val zt = pr.zeroPlanes!!
                    zt.reset(col, row)
                    while (true) {
                        if (readBits(1) != 0) { if (!zt.nextLevel()) break }
                        else zt.increment()
                    }
                    cb.zeroBitPlanes = zt.value
                }
                val passes = readCodingPasses()
                while (readBits(1) != 0) cb.lblock++
                var lg = 0; while ((1 shl (lg + 1)) <= passes) lg++          // floor(log2(passes))
                val len = readBits(cb.lblock + lg)
                queue.add(Triple(cb, passes, len))
            }
            alignToByte()
            if (cod.eph) skipMarkerIfEqual(0x92)
            for ((cb, passes, len) in queue) {
                cb.chunks.add(Chunk(d, offset + position, minOf(dataEnd, offset + position + len), passes))
                position += len
            }
        }
    }

    // ------------------------------------------------------------------ the MQ decoder (Annex C)
    private val QE = intArrayOf(0x5601, 0x3401, 0x1801, 0x0AC1, 0x0521, 0x0221, 0x5601, 0x5401, 0x4801, 0x3801, 0x3001, 0x2401, 0x1C01, 0x1601, 0x5601, 0x5401, 0x5101, 0x4801, 0x3801, 0x3401, 0x3001, 0x2801, 0x2401, 0x2201, 0x1C01, 0x1801, 0x1601, 0x1401, 0x1201, 0x1101, 0x0AC1, 0x09C1, 0x08A1, 0x0521, 0x0441, 0x02A1, 0x0221, 0x0141, 0x0111, 0x0085, 0x0049, 0x0025, 0x0015, 0x0009, 0x0005, 0x0001, 0x5601)
    private val NMPS = intArrayOf(1, 2, 3, 4, 5, 38, 7, 8, 9, 10, 11, 12, 13, 29, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 45, 46)
    private val NLPS = intArrayOf(1, 6, 9, 12, 29, 33, 6, 14, 14, 14, 17, 18, 20, 21, 14, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 46)
    private val SWITCH = intArrayOf(1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private class MQ(val data: ByteArray, var bp: Int, val end: Int) {
        var chigh: Int; var clow = 0; var ct = 0; var a: Int
        init {
            chigh = if (bp < end) data[bp].toInt() and 0xff else 0xff
            byteIn()
            chigh = ((chigh shl 7) and 0xffff) or ((clow shr 9) and 0x7f)
            clow = (clow shl 7) and 0xffff
            ct -= 7
            a = 0x8000
        }
        private fun at(i: Int) = if (i < end) data[i].toInt() and 0xff else 0xff
        fun byteIn() {
            if (at(bp) == 0xff) {
                if (at(bp + 1) > 0x8f) { clow += 0xff00; ct = 8 }
                else { bp++; clow += at(bp) shl 9; ct = 7 }
            } else {
                bp++; clow += if (bp < end) at(bp) shl 8 else 0xff00; ct = 8
            }
            if (clow > 0xffff) { chigh += clow shr 16; clow = clow and 0xffff }
        }
        /** [cx]: context states, [idx] / [mps] arrays. */
        fun readBit(idx: IntArray, mps: IntArray, cx: Int): Int {
            var i = idx[cx]; var m = mps[cx]
            val q = QE[i]
            var d: Int
            var aa = a - q
            if (chigh < q) {
                if (aa < q) { aa = q; d = m; i = NMPS[i] }
                else { aa = q; d = 1 xor m; if (SWITCH[i] == 1) m = d; i = NLPS[i] }
            } else {
                chigh -= q
                if (aa and 0x8000 != 0) { a = aa; return m }
                if (aa < q) { d = 1 xor m; if (SWITCH[i] == 1) m = d; i = NLPS[i] }
                else { d = m; i = NMPS[i] }
            }
            do {
                if (ct == 0) byteIn()
                aa = aa shl 1
                chigh = ((chigh shl 1) and 0xffff) or ((clow shr 15) and 1)
                clow = (clow shl 1) and 0xffff
                ct--
            } while (aa and 0x8000 == 0)
            a = aa
            idx[cx] = i; mps[cx] = m
            return d
        }
    }

    // ------------------------------------------------------------------ the bit-plane coder (Annex D)
    private const val UNIFORM = 17
    private const val RUNLENGTH = 18
    // context label by the packed neighbour count: h (bits 0-1) + v (bits 2-3) * 4 + d (bits 4-6) * 16
    private val LABELS_LL = IntArray(128); private val LABELS_HL = IntArray(128); private val LABELS_HH = IntArray(128)
    init {
        for (h in 0..2) for (v in 0..2) for (d in 0..4) {
            val k = h + 4 * v + 16 * d
            fun ll(h: Int, v: Int, d: Int) = when {
                h == 2 -> 8
                h == 1 -> if (v >= 1) 7 else if (d >= 1) 6 else 5
                v == 2 -> 4
                v == 1 -> 3
                else -> if (d >= 2) 2 else d
            }
            LABELS_LL[k] = ll(h, v, d)
            LABELS_HL[k] = ll(v, h, d)
            val hv = h + v
            LABELS_HH[k] = when {
                d >= 3 -> 8
                d == 2 -> if (hv >= 1) 7 else 6
                d == 1 -> if (hv >= 2) 5 else if (hv == 1) 4 else 3
                else -> if (hv >= 2) 2 else hv
            }
        }
    }

    private class BitModel(val width: Int, val height: Int, bandType: Int, zeroBitPlanes: Int) {
        val labels = when (bandType) { 3 -> LABELS_HH; 1 -> LABELS_HL; else -> LABELS_LL }
        val n = width * height
        val neighbors = IntArray(n)
        val sign = ByteArray(n)
        val magnitude = IntArray(n)
        val flags = ByteArray(n)                  // 1 processed this plane, 2 first refinement pending
        val bitsDecoded = IntArray(n) { zeroBitPlanes }
        val cxIndex = IntArray(19); val cxMps = IntArray(19)
        lateinit var mq: MQ

        init { reset() }

        fun reset() {
            cxIndex.fill(0); cxMps.fill(0)
            cxIndex[0] = 4; cxIndex[UNIFORM] = 46; cxIndex[RUNLENGTH] = 3
        }

        fun setSignificant(row: Int, col: Int, i: Int) {
            val left = col > 0; val right = col + 1 < width
            if (row > 0) { val k = i - width; if (left) neighbors[k - 1] += 0x10; if (right) neighbors[k + 1] += 0x10; neighbors[k] += 0x04 }
            if (row + 1 < height) { val k = i + width; if (left) neighbors[k - 1] += 0x10; if (right) neighbors[k + 1] += 0x10; neighbors[k] += 0x04 }
            if (left) neighbors[i - 1] += 0x01
            if (right) neighbors[i + 1] += 0x01
            neighbors[i] = neighbors[i] or 0x80
        }

        fun decodeSign(row: Int, col: Int, i: Int): Int {
            var contribution: Int
            var sig1 = col > 0 && magnitude[i - 1] != 0
            if (col + 1 < width && magnitude[i + 1] != 0) {
                val s1 = sign[i + 1].toInt()
                contribution = if (sig1) 1 - s1 - sign[i - 1] else 1 - s1 - s1
            } else contribution = if (sig1) 1 - 2 * sign[i - 1] else 0
            val hc = 3 * contribution
            sig1 = row > 0 && magnitude[i - width] != 0
            contribution = if (row + 1 < height && magnitude[i + width] != 0) {
                val s1 = sign[i + width].toInt()
                if (sig1) 1 - s1 - sign[i - width] + hc else 1 - s1 - s1 + hc
            } else if (sig1) 1 - 2 * sign[i - width] + hc else hc
            return if (contribution >= 0) mq.readBit(cxIndex, cxMps, 9 + contribution)
            else mq.readBit(cxIndex, cxMps, 9 - contribution) xor 1
        }

        fun significancePass() {
            for (i0 in 0 until height step 4) for (j in 0 until width) {
                var i = i0 * width + j
                for (i1 in 0 until 4) {
                    val row = i0 + i1
                    if (row >= height) break
                    flags[i] = (flags[i].toInt() and 1.inv()).toByte()
                    if (magnitude[i] != 0 || neighbors[i] == 0) { i += width; continue }
                    val cx = labels[neighbors[i] and 0x7f]
                    if (mq.readBit(cxIndex, cxMps, cx) != 0) {
                        sign[i] = decodeSign(row, j, i).toByte()
                        magnitude[i] = 1
                        setSignificant(row, j, i)
                        flags[i] = (flags[i].toInt() or 2).toByte()
                    }
                    bitsDecoded[i]++
                    flags[i] = (flags[i].toInt() or 1).toByte()
                    i += width
                }
            }
        }

        fun refinementPass() {
            val w4 = width * 4
            var i0 = 0
            while (i0 < n) {
                val next = minOf(n, i0 + w4)
                for (j in 0 until width) {
                    var i = i0 + j
                    while (i < next) {
                        if (magnitude[i] == 0 || flags[i].toInt() and 1 != 0) { i += width; continue }
                        var cx = 16
                        if (flags[i].toInt() and 2 != 0) {
                            flags[i] = (flags[i].toInt() xor 2).toByte()
                            cx = if (neighbors[i] and 0x7f == 0) 15 else 14
                        }
                        val bit = mq.readBit(cxIndex, cxMps, cx)
                        magnitude[i] = (magnitude[i] shl 1) or bit
                        bitsDecoded[i]++
                        flags[i] = (flags[i].toInt() or 1).toByte()
                        i += width
                    }
                }
                i0 = next
            }
        }

        fun cleanupPass() {
            var i0 = 0
            while (i0 < height) {
                val iNext = minOf(i0 + 4, height)
                val base = i0 * width
                val checkAll = i0 + 3 < height
                for (j in 0 until width) {
                    val idx0 = base + j
                    val allEmpty = checkAll && flags[idx0].toInt() == 0 && flags[idx0 + width].toInt() == 0 && flags[idx0 + 2 * width].toInt() == 0 && flags[idx0 + 3 * width].toInt() == 0 &&
                        neighbors[idx0] == 0 && neighbors[idx0 + width] == 0 && neighbors[idx0 + 2 * width] == 0 && neighbors[idx0 + 3 * width] == 0
                    var i1 = 0; var i = idx0; var row = i0
                    if (allEmpty) {
                        if (mq.readBit(cxIndex, cxMps, RUNLENGTH) == 0) {
                            bitsDecoded[idx0]++; bitsDecoded[idx0 + width]++; bitsDecoded[idx0 + 2 * width]++; bitsDecoded[idx0 + 3 * width]++
                            continue
                        }
                        i1 = (mq.readBit(cxIndex, cxMps, UNIFORM) shl 1) or mq.readBit(cxIndex, cxMps, UNIFORM)
                        if (i1 != 0) { row = i0 + i1; i += i1 * width }
                        sign[i] = decodeSign(row, j, i).toByte()
                        magnitude[i] = 1
                        setSignificant(row, j, i)
                        flags[i] = (flags[i].toInt() or 2).toByte()
                        i = idx0
                        for (i2 in i0..row) { bitsDecoded[i]++; i += width }
                        i1++
                    }
                    row = i0 + i1
                    while (row < iNext) {
                        if (magnitude[i] == 0 && flags[i].toInt() and 1 == 0) {
                            val cx = labels[neighbors[i] and 0x7f]
                            if (mq.readBit(cxIndex, cxMps, cx) == 1) {
                                sign[i] = decodeSign(row, j, i).toByte()
                                magnitude[i] = 1
                                setSignificant(row, j, i)
                                flags[i] = (flags[i].toInt() or 2).toByte()
                            }
                            bitsDecoded[i]++
                        }
                        row++; i += width
                    }
                }
                i0 = iNext
            }
        }

        fun checkSegmentationSymbol() {
            val s = (mq.readBit(cxIndex, cxMps, UNIFORM) shl 3) or (mq.readBit(cxIndex, cxMps, UNIFORM) shl 2) or
                (mq.readBit(cxIndex, cxMps, UNIFORM) shl 1) or mq.readBit(cxIndex, cxMps, UNIFORM)
            if (s != 0xa) throw JpxException("invalid segmentation symbol")
        }
    }

    // ------------------------------------------------------------------ coefficients and the inverse wavelet
    private fun decodeCodeblock(cb: CodeBlock, sb: Subband, out: FloatArray, rw: Int, u0: Int, v0: Int, interleave: Boolean,
                                delta: Float, mb: Int, reversible: Boolean, cod: Cod) {
        if (cb.chunks.isEmpty()) return
        val bw = cb.x1 - cb.x0; val bh = cb.y1 - cb.y0
        val bm = BitModel(bw, bh, sb.type, cb.zeroBitPlanes)
        var total = 0; var passes = 0
        for (c in cb.chunks) { total += c.end - c.start; passes += c.passes }
        val enc = ByteArray(total)
        var p = 0
        for (c in cb.chunks) { System.arraycopy(c.data, c.start, enc, p, c.end - c.start); p += c.end - c.start }
        bm.mq = MQ(enc, 0, total)
        var passType = 2                                         // the first bit-plane has only a cleanup pass
        for (k in 0 until passes) {
            when (passType) { 0 -> bm.significancePass(); 1 -> bm.refinementPass(); else -> { bm.cleanupPass(); if (cod.segsym) bm.checkSegmentationSymbol() } }
            if (cod.reset) bm.reset()
            passType = (passType + 1) % 3
        }
        val correction = if (reversible) 0f else 0.5f
        val dx = if (sb.type == 1 || sb.type == 3) 1 else 0
        val dy = if (sb.type == 2 || sb.type == 3) 1 else 0
        var pos = 0
        for (y in cb.y0 until cb.y1) for (x in cb.x0 until cb.x1) {
            val mag = bm.magnitude[pos]
            if (mag != 0) {
                var v = (mag + correction) * delta
                if (bm.sign[pos].toInt() != 0) v = -v
                val nb = bm.bitsDecoded[pos]
                if (!(reversible && nb >= mb)) v *= 2f.pow(mb - nb)
                val ox = if (interleave) 2 * x + dx - u0 else x - u0
                val oy = if (interleave) 2 * y + dy - v0 else y - v0
                if (ox in 0 until rw && oy >= 0 && oy * rw + ox < out.size) out[oy * rw + ox] = v
            }
            pos++
        }
    }

    /** One tile component: its samples (unsigned-shifted), row by row. */
    private fun decodeTileComponent(tc: TileComp, precision: Int, signed: Boolean): IntArray {
        val cod = tc.cod; val qcd = tc.qcd
        val reversible = cod.reversible
        var b = 0
        var prev: FloatArray? = null; var pw = 0; var ph = 0; var pu0 = 0; var pv0 = 0
        for (r in 0..cod.levels) {
            val rs = tc.resolutions[r]
            val rw = rs.x1 - rs.x0; val rh = rs.y1 - rs.y0
            val coefs = FloatArray(maxOf(0, rw * rh))
            for (sb in rs.subbands) {
                val eps: Int; val mu: Int
                if (qcd.derived) { eps = qcd.eps[0] + (if (r > 0) 1 - r else 0); mu = qcd.mu[0] }
                else { eps = qcd.eps[minOf(b, qcd.eps.size - 1)]; mu = qcd.mu[minOf(b, qcd.mu.size - 1)] }
                b++
                val gainLog2 = when (sb.type) { 0 -> 0; 3 -> 2; else -> 1 }
                val delta = if (reversible) 1f else (2.0.pow(precision + gainLog2 - eps) * (1 + mu / 2048.0)).toFloat()
                val mb = qcd.guardBits + eps - 1
                for (cb in sb.codeblocks) decodeCodeblock(cb, sb, coefs, rw, rs.x0, rs.y0, r > 0, delta, mb, reversible, cod)
            }
            if (r > 0) {
                // the lower resolution's picture goes to the even positions, then rows and columns are synthesised
                val ll = prev!!
                for (vb in 0 until ph) {
                    val v = 2 * (pv0 + vb) - rs.y0
                    if (v < 0 || v >= rh) continue
                    for (ub in 0 until pw) {
                        val u = 2 * (pu0 + ub) - rs.x0
                        if (u in 0 until rw) coefs[v * rw + u] = ll[vb * pw + ub]
                    }
                }
                synthesize(coefs, rw, rh, rs.x0, rs.y0, reversible)
            }
            prev = coefs; pw = rw; ph = rh; pu0 = rs.x0; pv0 = rs.y0
        }
        val out = IntArray(pw * ph)
        val shift = if (signed) 0 else 1 shl (precision - 1)
        val src = prev!!
        for (i in out.indices) out[i] = Math.round(src[i]) + shift
        return out
    }

    /** 2D_SR (F.3.2): rows then columns, each by the 1D synthesis with symmetric extension. */
    private fun synthesize(a: FloatArray, w: Int, h: Int, u0: Int, v0: Int, reversible: Boolean) {
        if (w == 0 || h == 0) return
        val pad = 4
        val buf = FloatArray(maxOf(w, h) + 2 * pad)
        for (y in 0 until h) {
            if (w == 1) { if (u0 and 1 != 0) a[y * w] = a[y * w] / 2; continue }
            System.arraycopy(a, y * w, buf, pad, w)
            sr1d(buf, pad, w, u0, reversible)
            System.arraycopy(buf, pad, a, y * w, w)
        }
        for (x in 0 until w) {
            if (h == 1) { if (v0 and 1 != 0) a[x] = a[x] / 2; continue }
            for (y in 0 until h) buf[pad + y] = a[y * w + x]
            sr1d(buf, pad, h, v0, reversible)
            for (y in 0 until h) a[y * w + x] = buf[pad + y]
        }
    }

    private const val K97 = 1.230174104914001f
    private const val A97 = -1.586134342059924f
    private const val B97 = -0.052980118572961f
    private const val G97 = 0.882911075530934f
    private const val D97 = 0.443506852043971f

    /** 1D_SR (F.3.7) on buf[off until off+len], which holds Y(i0 .. i1); [i0] gives the parity of buf[off]. */
    private fun sr1d(x: FloatArray, off: Int, len: Int, i0: Int, reversible: Boolean) {
        // periodic symmetric extension, 4 each side
        for (k in 1..4) { x[off - k] = x[off + k]; x[off + len - 1 + k] = x[off + len - 1 - k] }
        // the first buffer position at or after [lo] whose absolute index is even / odd
        fun from(lo: Int, even: Boolean): Int = if (((i0 + lo - off) and 1 == 0) == even) lo else lo + 1
        val hi = off + len
        if (reversible) {
            // STEP1 X(2n) = Y(2n) - floor((Y(2n-1) + Y(2n+1) + 2) / 4), STEP2 X(2n+1) = Y(2n+1) + floor((X(2n) + X(2n+2)) / 2)
            var j = from(off - 3, true)
            while (j < hi + 3) { x[j] -= Math.floorDiv((x[j - 1] + x[j + 1] + 2f).toInt(), 4).toFloat(); j += 2 }
            j = from(off - 2, false)
            while (j < hi + 2) { x[j] += Math.floorDiv((x[j - 1] + x[j + 1]).toInt(), 2).toFloat(); j += 2 }
        } else {
            var j = from(off - 4, true)
            while (j < hi + 4) { x[j] *= K97; j += 2 }                                  // STEP1
            j = from(off - 4, false)
            while (j < hi + 4) { x[j] /= K97; j += 2 }                                  // STEP2
            j = from(off - 3, true)
            while (j < hi + 3) { x[j] -= D97 * (x[j - 1] + x[j + 1]); j += 2 }          // STEP3
            j = from(off - 2, false)
            while (j < hi + 2) { x[j] -= G97 * (x[j - 1] + x[j + 1]); j += 2 }          // STEP4
            j = from(off - 1, true)
            while (j < hi + 1) { x[j] -= B97 * (x[j - 1] + x[j + 1]); j += 2 }          // STEP5
            j = from(off, false)
            while (j < hi) { x[j] -= A97 * (x[j - 1] + x[j + 1]); j += 2 }              // STEP6
        }
    }

    // ------------------------------------------------------------------ the codestream
    private fun codestream(d: ByteArray, start: Int, end: Int): Image {
        var siz: Siz? = null; var cod: Cod? = null; var qcd: Qcd? = null
        val qcc = HashMap<Int, Qcd>()
        val tiles = LinkedHashMap<Int, Tile>()
        var cur: Tile? = null
        var mainHeader = true
        var pos = start
        loop@ while (pos + 1 < end) {
            val code = u16(d, pos)
            pos += 2
            when (code) {
                0xFF4F -> {}
                0xFFD9 -> break@loop
                0xFF51 -> { val len = u16(d, pos); siz = parseSiz(d, pos + 2); pos += len }
                0xFF52 -> { val len = u16(d, pos); val c = parseCod(d, pos + 2, pos + len); if (mainHeader) cod = c else cur!!.cod = c; pos += len }
                0xFF5C -> { val len = u16(d, pos); val q = parseQcd(d, pos + 2, pos + len); if (mainHeader) qcd = q else cur!!.qcd = q; pos += len }
                0xFF5D -> {
                    val len = u16(d, pos)
                    val s = siz ?: throw JpxException("QCC before SIZ")
                    val c = if (s.csiz < 257) u8(d, pos + 2) else u16(d, pos + 2)
                    val q = parseQcd(d, pos + 2 + (if (s.csiz < 257) 1 else 2), pos + len)
                    if (mainHeader) qcc[c] = q else cur!!.qcc[c] = q
                    pos += len
                }
                0xFF53 -> throw JpxException("per-component coding styles (COC) aren't supported")
                0xFF60, 0xFF61 -> throw JpxException("packed packet headers aren't supported")
                0xFF90 -> {
                    val len = u16(d, pos)
                    val isot = u16(d, pos + 2)
                    var psot = u32(d, pos + 4)
                    val tpsot = u8(d, pos + 8)
                    mainHeader = false
                    if (psot == 0L) psot = (end - (pos - 2)).toLong()
                    val t = tiles.getOrPut(isot) { Tile(isot) }
                    t.partIndex = tpsot
                    t.dataEnd = minOf(end.toLong(), pos - 2 + psot).toInt()
                    if (tpsot == 0 || t.cod == null) { t.cod = cod; t.qcd = qcd; t.qcc = HashMap(qcc) }
                    cur = t
                    pos += len
                }
                0xFF93 -> {
                    val t = cur ?: throw JpxException("data before any tile")
                    if (!t.built) buildTile(siz ?: throw JpxException("no SIZ"), t)
                    parsePackets(t, d, pos, t.dataEnd)
                    pos = t.dataEnd
                }
                in 0xFF30..0xFF3F -> {}
                else -> pos += u16(d, pos)                    // TLM, PLM, PLT, COM, RGN, POC, CRG…: skipped
            }
        }
        val s = siz ?: throw JpxException("no SIZ")
        val cx0 = ceilDiv(s.x0, s.xr[0]); val cy0 = ceilDiv(s.y0, s.yr[0])
        val cw = ceilDiv(s.x1, s.xr[0]) - cx0; val ch = ceilDiv(s.y1, s.yr[0]) - cy0
        val out = IntArray(cw * ch)
        for (t in tiles.values) {
            if (!t.built) continue
            val tc = t.comps[0]
            val samples = decodeTileComponent(tc, s.precision[0], s.signed[0])
            val tw = tc.x1 - tc.x0
            for (y in tc.y0 until tc.y1) System.arraycopy(samples, (y - tc.y0) * tw, out, (y - cy0) * cw + (tc.x0 - cx0), tw)
        }
        return Image(cw, ch, s.precision[0], s.signed[0], out)
    }
}
