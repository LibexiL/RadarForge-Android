package com.libexil.radarforge.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * NEXRAD Level II decoder: Archive II files (AR2V), AWS real-time chunks (S/I/E),
 * message 31 (2008+, super-res, dual-pol) and legacy message 1.
 *
 * Gate data stays as raw 8/16-bit codes; value = (code - offset) / scale, with
 * code 0 = below threshold and 1 = range folded.
 */
class Moment(
    val name: String,
    val nRays: Int,
    val nGates: Int,
    val firstGate: Float,      // km, centre of the first gate
    val gateSpacing: Float,    // km
    val scale: Float,
    val offset: Float,
    val data8: ByteArray?,     // nRays * nGates, row = ray (collection order)
    val data16: CharArray?,
) {
    val wordBits: Int get() = if (data16 != null) 16 else 8
    fun code(ray: Int, gate: Int): Int {
        val i = ray * nGates + gate
        return if (data8 != null) data8[i].toInt() and 0xff else data16!![i].code
    }
    val maxRange: Float get() = firstGate + gateSpacing * (nGates - 0.5f)
}

class Sweep(
    var index: Int,
    val elevNum: Int,
    val elevation: Float,        // median elevation angle (deg)
    val azimuths: FloatArray,    // per ray, collection order
    val azRes: Float,            // nominal radial spacing (deg)
    val nyquist: Float,          // m/s (0 = unknown)
    val unambRange: Float,       // km (0 = unknown)
    val startMs: Long,           // epoch ms of the first radial
    val moments: Map<String, Moment>,
    var complete: Boolean = true,
) {
    val nRays: Int get() = azimuths.size
}

class Volume(
    val site: String,
    val startMs: Long,
    val lat: Double,
    val lon: Double,
    val heightM: Double,
    val vcp: Int,
    val sweeps: List<Sweep>,
    var complete: Boolean,
) {
    fun summary(): String = buildString {
        append("$site ${Time.iso(startMs)} VCP $vcp  ${sweeps.size} sweeps\n")
        for (s in sweeps) append(String.format(java.util.Locale.US, "  #%2d el%2d %5.2f° %d rays [%s] nyq=%.1f\n",
            s.index, s.elevNum, s.elevation, s.nRays, s.moments.keys.joinToString(","), s.nyquist))
    }
}

internal object Bytes {
    fun u8(b: ByteArray, p: Int) = b[p].toInt() and 0xff
    fun u16(b: ByteArray, p: Int) = (u8(b, p) shl 8) or u8(b, p + 1)
    fun i16(b: ByteArray, p: Int) = u16(b, p).toShort().toInt()
    fun i32(b: ByteArray, p: Int) = (u8(b, p) shl 24) or (u8(b, p + 1) shl 16) or (u8(b, p + 2) shl 8) or u8(b, p + 3)
    fun u32(b: ByteArray, p: Int) = i32(b, p).toLong() and 0xffffffffL
    fun f32(b: ByteArray, p: Int) = Float.fromBits(i32(b, p))
    fun ascii(b: ByteArray, p: Int, n: Int) = String(b, p, n, Charsets.ISO_8859_1)
}

private const val DAY_MS = 86_400_000L
internal fun nexradMs(mjd: Int, ms: Long): Long = (mjd - 1L) * DAY_MS + ms

/** One radial's moment, still in message byte order. */
private class RadialMoment(
    val firstGate: Float, val spacing: Float, val scale: Float, val offset: Float,
    val wordBits: Int, val nGates: Int, val bytes: ByteArray,
)

private class Radial(
    val azimuth: Float, val elevation: Float, val elevNum: Int, val status: Int, val timeMs: Long,
    val azRes: Float, val nyquist: Float, val unamb: Float, val moments: Map<String, RadialMoment>,
)

/**
 * Accumulates radials (possibly across several live chunks or records) into sweeps.
 * Moments named in [skip] are not stored (saves memory for ones that are never shown)
 * and [keep], when not null, limits storage to those moments.
 */
class SweepBuilder(private val skip: Set<String> = emptySet(), private val keep: Set<String>? = null) {
    var icao: String = ""
        private set
    var headerMs: Long = 0
        private set
    private var lat = Double.NaN
    private var lon = Double.NaN
    private var height = 0.0
    private var vcp = 0
    private val groups = ArrayList<Pair<Int, ArrayList<Radial>>>()
    private val built = HashMap<Int, Pair<Int, Sweep>>()   // closed group -> (radial count, sweep)

    /** Highest RDA elevation number seen so far (streaming callers stop early with it). */
    val lastElevNum: Int get() = if (groups.isEmpty()) 0 else groups.last().first
    var sawEndOfVolume = false
        private set

    // ------------------------------------------------------------------ input
    /** A whole file, a real-time chunk, or any mix of header + LDM records. */
    fun addBytes(raw: ByteArray) {
        var data = raw
        if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            data = GZIPInputStream(ByteArrayInputStream(data)).readBytes()
        } else if (Bzip2.isBzip2(data) ) {
            data = Bzip2.decompress(data)
        }
        var off = 0
        if (data.size >= 24 && (Bytes.ascii(data, 0, 4) == "AR2V" || Bytes.ascii(data, 0, 4) == "ARCH")) {
            readHeader(data)
            off = 24
        }
        if (data.size >= off + 8 && Bzip2.isBzip2(data, off + 4)) {
            var p = off
            while (p + 4 <= data.size) {
                val size = Math.abs(Bytes.i32(data, p))
                p += 4
                if (size == 0) continue
                val n = minOf(size, data.size - p)
                if (n <= 0) break
                addRecord(data, p, n)
                p += n
            }
        } else if (data.size > off) {
            addMessages(data, off, data.size)
        }
    }

    internal fun readHeader(h: ByteArray) {
        val mjd = Bytes.i32(h, 12)
        val ms = Bytes.u32(h, 16)
        if (mjd > 0) headerMs = nexradMs(mjd, ms)
        icao = Bytes.ascii(h, 20, 4).trim { it <= ' ' }
    }

    /** One LDM record (bzip2 compressed, or raw messages). */
    fun addRecord(rec: ByteArray, off: Int = 0, len: Int = rec.size) {
        if (Bzip2.isBzip2(rec, off)) {
            val msgs = try { Bzip2.decompress(rec, off, len) } catch (e: Bzip2.Error) { return }
            addMessages(msgs, 0, msgs.size)
        } else {
            addMessages(rec, off, off + len)
        }
    }

    fun addMessages(b: ByteArray, start: Int, end: Int) {
        var pos = start
        while (pos + 28 <= end) {
            val sizeHw = Bytes.u16(b, pos + 12)
            val type = Bytes.u8(b, pos + 15)
            if (type == 31) {
                val msgEnd = pos + 12 + sizeHw * 2
                if (sizeHw == 0 || msgEnd > end) break
                parseMsg31(b, pos + 28, msgEnd)?.let { add(it) }
                pos = msgEnd
            } else {
                if (type == 1 && pos + 28 + 100 <= end) parseMsg1(b, pos + 28, minOf(end, pos + 2432))?.let { add(it) }
                else if (type == 5 && vcp == 0 && pos + 28 + 6 <= end) vcp = Bytes.u16(b, pos + 28 + 4)
                pos += 2432
            }
        }
    }

    // --------------------------------------------------------------- messages
    private fun parseMsg31(b: ByteArray, p: Int, end: Int): Radial? {
        if (p + 32 > end) return null
        val ms = Bytes.u32(b, p + 4)
        val mjd = Bytes.u16(b, p + 8)
        val az = Bytes.f32(b, p + 12)
        // layout: id(4) ms(4) mjd(2) azNum(2) az(4) comp(1) spare(1) len(2) azRes(1) status(1)
        //         elevNum(1) cut(1) elev(4) blank(1) azIdx(1) nBlocks(2)  = 32 bytes
        val azResCode = Bytes.u8(b, p + 20)
        val status = Bytes.u8(b, p + 21)
        val elevNum = Bytes.u8(b, p + 22)
        val elev = Bytes.f32(b, p + 24)
        val nBlocks = minOf(Bytes.u16(b, p + 30), 10)
        if (p + 32 + nBlocks * 4 > end) return null
        var nyq = 0f
        var unamb = 0f
        val moments = HashMap<String, RadialMoment>(8)
        for (k in 0 until nBlocks) {
            val ptr = Bytes.u32(b, p + 32 + k * 4).toInt()
            if (ptr <= 0) continue
            val q = p + ptr
            if (q + 4 > end) continue
            val type = b[q].toInt().toChar()
            val name = Bytes.ascii(b, q + 1, 3).trim()
            if (type == 'R') {
                if (name == "VOL" && q + 44 <= end) {
                    if (lat.isNaN()) {
                        lat = Bytes.f32(b, q + 8).toDouble()
                        lon = Bytes.f32(b, q + 12).toDouble()
                        height = Bytes.i16(b, q + 16).toDouble() + Bytes.u16(b, q + 18)
                    }
                    if (vcp == 0) vcp = Bytes.u16(b, q + 40)
                } else if (name == "RAD" && q + 18 <= end) {
                    unamb = Bytes.i16(b, q + 6) / 10f
                    nyq = Bytes.i16(b, q + 16) / 100f
                }
                continue
            }
            if (type != 'D' || q + 28 > end) continue
            if (name in skip || (keep != null && name !in keep)) continue
            val nGates = Bytes.u16(b, q + 8)
            val first = Bytes.i16(b, q + 10)
            val spacing = Bytes.i16(b, q + 12)
            val wordBits = Bytes.u8(b, q + 19)
            val scale = Bytes.f32(b, q + 20)
            val offset = Bytes.f32(b, q + 24)
            if (nGates == 0 || scale == 0f || spacing <= 0) continue
            val nBytes = if (wordBits == 16) nGates * 2 else nGates
            if (q + 28 + nBytes > end) continue
            moments[name] = RadialMoment(first / 1000f, spacing / 1000f, scale, offset,
                if (wordBits == 16) 16 else 8, nGates, b.copyOfRange(q + 28, q + 28 + nBytes))
        }
        return Radial(az, elev, elevNum, status, nexradMs(mjd, ms), if (azResCode == 1) 0.5f else 1.0f,
            nyq, unamb, moments)
    }

    private fun parseMsg1(b: ByteArray, p: Int, end: Int): Radial? {
        val ms = Bytes.u32(b, p)
        val mjd = Bytes.u16(b, p + 4)
        val unamb = Bytes.u16(b, p + 6)
        val az = Bytes.u16(b, p + 8) * 180f / 32768f
        val status = Bytes.u16(b, p + 12)
        val el = Bytes.u16(b, p + 14) * 180f / 32768f
        val elNum = Bytes.u16(b, p + 16)
        val sFirst = Bytes.i16(b, p + 18)
        val dFirst = Bytes.i16(b, p + 20)
        val sInt = Bytes.u16(b, p + 22)
        val dInt = Bytes.u16(b, p + 24)
        val sN = Bytes.u16(b, p + 26)
        val dN = Bytes.u16(b, p + 28)
        val refP = Bytes.u16(b, p + 36)
        val velP = Bytes.u16(b, p + 38)
        val swP = Bytes.u16(b, p + 40)
        val dopRes = Bytes.u16(b, p + 42)
        val vcpNum = Bytes.u16(b, p + 44)
        val nyq = Bytes.u16(b, p + 60) / 100f
        if (vcp == 0 && vcpNum != 0) vcp = vcpNum
        val moments = HashMap<String, RadialMoment>(4)
        fun take(name: String, ptr: Int, n: Int, first: Int, sp: Int, scale: Float, offset: Float) {
            if (n == 0 || ptr == 0 || sp == 0 || p + ptr + n > end) return
            moments[name] = RadialMoment(first / 1000f, sp / 1000f, scale, offset, 8, n, b.copyOfRange(p + ptr, p + ptr + n))
        }
        val res = if (dopRes == 2) 0.5f else 1.0f
        take("REF", refP, sN, sFirst, sInt, 2f, 66f)
        take("VEL", velP, dN, dFirst, dInt, 1f / res, 129f)
        take("SW", swP, dN, dFirst, dInt, 2f, 129f)
        return Radial(az, el, elNum, status, nexradMs(mjd, ms), 1.0f, nyq, if (unamb != 0) unamb / 10f else 0f, moments)
    }

    private fun add(r: Radial) {
        if (r.status == 4) sawEndOfVolume = true
        val last = groups.lastOrNull()
        if (last == null || last.first != r.elevNum || ((r.status == 0 || r.status == 3) && last.second.size > 10)) {
            if (last != null) closeGroup(groups.size - 1)
            groups.add(r.elevNum to arrayListOf(r))
        } else {
            last.second.add(r)
        }
    }

    /** An elevation finished: build its sweep now and let the raw radials go (halves peak memory). */
    private fun closeGroup(gi: Int) {
        val (en, rads) = groups[gi]
        if (rads.size >= 10 && gi !in built) assemble(0, en, rads)?.let { built[gi] = rads.size to it }
        rads.clear()
        rads.trimToSize()
    }

    // ------------------------------------------------------------------ output
    fun build(siteHint: String = ""): Volume {
        val sweeps = ArrayList<Sweep>()
        val lastGi = groups.size - 1
        for ((gi, g) in groups.withIndex()) {
            val (en, rads) = g
            val closed = built[gi]
            val sw: Sweep
            if (gi < lastGi) {
                sw = closed?.second ?: continue          // closed elevations were built when they ended
                sw.complete = true
            } else {
                if (rads.size < 10) continue
                sw = assemble(sweeps.size, en, rads) ?: continue
                sw.complete = rads.last().status == 2 || rads.last().status == 4
            }
            sw.index = sweeps.size
            sweeps.add(sw)
        }
        var t = headerMs
        if (t < 631_152_000_000L) t = sweeps.firstOrNull()?.startMs ?: System.currentTimeMillis()   // before 1990: unusable
        val lastRads = groups.lastOrNull()?.second
        val complete = lastRads != null && lastRads.isNotEmpty() && lastRads.last().status == 4
        return Volume(icao.ifEmpty { siteHint }, t, lat, lon, height, vcp, sweeps, complete)
    }

    private fun assemble(index: Int, elevNum: Int, rads: List<Radial>): Sweep? {
        val names = LinkedHashSet<String>()
        for (r in rads) names.addAll(r.moments.keys)
        val nr = rads.size
        val moments = HashMap<String, Moment>()
        for (name in names) {
            var ref: RadialMoment? = null
            var maxG = 0
            for (r in rads) {
                val m = r.moments[name] ?: continue
                if (ref == null) ref = m
                if (m.nGates > maxG) maxG = m.nGates
            }
            if (ref == null || maxG == 0) continue
            // find the last gate holding data so empty range isn't stored
            var used = 0
            val wide = ref.wordBits == 16
            for (r in rads) {
                val m = r.moments[name] ?: continue
                val bytes = m.bytes
                var g = m.nGates - 1
                while (g >= used) {
                    val c = if (wide) Bytes.u16(bytes, g * 2) else bytes[g].toInt() and 0xff
                    if (c > 1) break
                    g--
                }
                if (g + 1 > used) used = g + 1
            }
            val ng = maxOf(used, 1)
            if (wide) {
                val arr = CharArray(nr * ng)
                for ((i, r) in rads.withIndex()) {
                    val m = r.moments[name] ?: continue
                    val n = minOf(ng, m.nGates)
                    val bytes = m.bytes
                    val base = i * ng
                    for (g in 0 until n) arr[base + g] = Bytes.u16(bytes, g * 2).toChar()
                }
                moments[name] = Moment(name, nr, ng, ref.firstGate, ref.spacing, ref.scale, ref.offset, null, arr)
            } else {
                val arr = ByteArray(nr * ng)
                for ((i, r) in rads.withIndex()) {
                    val m = r.moments[name] ?: continue
                    System.arraycopy(m.bytes, 0, arr, i * ng, minOf(ng, m.nGates))
                }
                moments[name] = Moment(name, nr, ng, ref.firstGate, ref.spacing, ref.scale, ref.offset, arr, null)
            }
        }
        if (moments.isEmpty()) return null
        val az = FloatArray(nr) { rads[it].azimuth }
        val el = FloatArray(nr) { rads[it].elevation }.also { it.sort() }
        val nyqs = rads.map { it.nyquist }.filter { it > 0 }.sorted()
        val urs = rads.map { it.unamb }.filter { it > 0 }.sorted()
        return Sweep(index, elevNum, el[nr / 2], az, rads[nr / 2].azRes,
            if (nyqs.isEmpty()) 0f else nyqs[nyqs.size / 2], if (urs.isEmpty()) 0f else urs[urs.size / 2],
            rads[0].timeMs, moments)
    }
}

object Level2 {
    /** Decode a complete file. */
    fun read(data: ByteArray, siteHint: String = "", skip: Set<String> = emptySet()): Volume {
        val b = SweepBuilder(skip)
        b.addBytes(data)
        return b.build(siteHint).also { it.complete = true }
    }

    /**
     * Read an Archive II file from a stream record by record, stopping as soon as
     * [enough] says the sweeps needed so far are complete. Every byte read is copied
     * to [copy] (so a partial download can be cached and resumed).
     */
    fun readStreaming(input: InputStream, siteHint: String = "", copy: ByteArrayOutputStream? = null,
                      skip: Set<String> = emptySet(), keep: Set<String>? = null,
                      enough: (SweepBuilder) -> Boolean = { false }): Volume {
        val b = SweepBuilder(skip, keep)
        val din = DataInputStream(input)
        val head = ByteArray(24)
        try {
            din.readFully(head)
        } catch (e: EOFException) {
            return b.build(siteHint)
        }
        copy?.write(head)
        val tag = Bytes.ascii(head, 0, 4)
        if (tag != "AR2V" && tag != "ARCH") {
            // not record-structured (gzip / whole-file bzip2 / legacy): read everything
            val rest = din.readBytes()
            copy?.write(rest)
            b.addBytes(head + rest)
            return b.build(siteHint).also { it.complete = true }
        }
        b.readHeader(head)
        val sizeBuf = ByteArray(4)
        var firstRecord = true
        while (true) {
            try {
                din.readFully(sizeBuf)
            } catch (e: EOFException) {
                break
            }
            if (firstRecord) {
                firstRecord = false
                // records are "size + BZh..."; anything else is an uncompressed message stream
                val peek = ByteArray(4)
                val n = din.read(peek)
                if (n < 4 || !Bzip2.isBzip2(peek)) {
                    val rest = if (n > 0) peek.copyOf(n) + din.readBytes() else din.readBytes()
                    copy?.write(sizeBuf); copy?.write(rest)
                    b.addBytes(head + sizeBuf + rest)
                    return b.build(siteHint).also { it.complete = true }
                }
                val size = Math.abs(Bytes.i32(sizeBuf, 0))
                if (size < 4 || size > 64 shl 20) break
                val rec = ByteArray(size)
                System.arraycopy(peek, 0, rec, 0, 4)
                var got = 4
                while (got < size) {
                    val k = din.read(rec, got, size - got)
                    if (k < 0) break
                    got += k
                }
                copy?.write(sizeBuf)
                copy?.write(rec, 0, got)
                b.addRecord(rec, 0, got)
                if (got < size) break
                if (enough(b)) return b.build(siteHint).also { it.complete = false }
                continue
            }
            val size = Math.abs(Bytes.i32(sizeBuf, 0))
            if (size == 0) { copy?.write(sizeBuf); continue }
            if (size > 64 shl 20) break
            val rec = ByteArray(size)
            var got = 0
            while (got < size) {
                val n = din.read(rec, got, size - got)
                if (n < 0) break
                got += n
            }
            copy?.write(sizeBuf)
            copy?.write(rec, 0, got)
            b.addRecord(rec, 0, got)
            if (got < size) break
            if (enough(b)) return b.build(siteHint).also { it.complete = false }
        }
        return b.build(siteHint).also { it.complete = b.sawEndOfVolume || it.complete }
    }
}
