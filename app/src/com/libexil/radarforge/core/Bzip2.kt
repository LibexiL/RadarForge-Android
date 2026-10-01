package com.libexil.radarforge.core

/**
 * bzip2 decompressor (Android has none built in; every Level II record is bzip2).
 *
 * Handles multi-block and concatenated streams. A truncated stream (a live chunk
 * still being written) returns the blocks that were complete.
 */
object Bzip2 {
    private class Eof : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    class Error(msg: String) : RuntimeException(msg)

    private const val MAX_GROUPS = 6
    private const val MAX_ALPHA = 258
    private const val MAX_CODE_LEN = 20
    private const val LOOKUP_BITS = 10
    private const val MAX_SELECTORS = 18002

    fun isBzip2(b: ByteArray, off: Int = 0): Boolean =
        b.size >= off + 4 && b[off] == 'B'.code.toByte() && b[off + 1] == 'Z'.code.toByte() &&
            b[off + 2] == 'h'.code.toByte() && b[off + 3] in '1'.code.toByte()..'9'.code.toByte()

    /** Decompress [len] bytes of [src] starting at [off]. */
    fun decompress(src: ByteArray, off: Int = 0, len: Int = src.size - off): ByteArray =
        Decoder(src, off, off + len).run()

    private class Decoder(val src: ByteArray, var pos: Int, val end: Int) {
        var bitBuf = 0L
        var bitCount = 0

        var out = ByteArray(maxOf(1 shl 16, (end - pos) * 6))
        var outLen = 0

        // per-block working storage
        var tt = IntArray(0)
        val unzftab = IntArray(256)
        val selectors = ByteArray(MAX_SELECTORS)
        val lens = Array(MAX_GROUPS) { ByteArray(MAX_ALPHA) }
        val limit = Array(MAX_GROUPS) { IntArray(MAX_CODE_LEN + 2) }
        val base = Array(MAX_GROUPS) { IntArray(MAX_CODE_LEN + 2) }
        val perm = Array(MAX_GROUPS) { IntArray(MAX_ALPHA) }
        val minLens = IntArray(MAX_GROUPS)
        val maxLens = IntArray(MAX_GROUPS)
        val lookup = Array(MAX_GROUPS) { IntArray(1 shl LOOKUP_BITS) }   // (sym << 8) | len, 0 = slow path

        fun fill() {
            while (bitCount <= 56) {
                if (pos >= end) {
                    if (bitCount == 0) throw Eof()
                    return
                }
                bitBuf = (bitBuf shl 8) or (src[pos++].toLong() and 0xff)
                bitCount += 8
            }
        }

        fun bits(n: Int): Int {
            if (bitCount < n) {
                fill()
                if (bitCount < n) throw Eof()
            }
            bitCount -= n
            return ((bitBuf ushr bitCount) and ((1L shl n) - 1)).toInt()
        }

        fun bit(): Int = bits(1)

        /** Next [n] bits without consuming them (zero-padded past the end of input). */
        fun peek(n: Int): Int {
            if (bitCount < n) fill()
            return if (bitCount >= n) ((bitBuf ushr (bitCount - n)) and ((1L shl n) - 1)).toInt()
            else ((bitBuf shl (n - bitCount)) and ((1L shl n) - 1)).toInt()
        }

        fun skip(n: Int) {
            if (bitCount < n) throw Eof()
            bitCount -= n
        }

        fun alignToByte() {
            bitCount -= bitCount % 8
        }

        fun ensureOut(extra: Int) {
            if (outLen + extra > out.size) {
                var n = out.size * 2
                while (n < outLen + extra) n *= 2
                out = out.copyOf(n)
            }
        }

        fun run(): ByteArray {
            try {
                while (true) {
                    // stream header (streams may be concatenated)
                    if (end - pos < 4 && bitCount == 0) break
                    alignToByte()
                    val b = bits(8); val z = bits(8); val h = bits(8); val lvl = bits(8)
                    if (b != 'B'.code || z != 'Z'.code || h != 'h'.code || lvl < '1'.code || lvl > '9'.code) {
                        if (outLen == 0) throw Error("not a bzip2 stream")
                        break
                    }
                    val blockSize = (lvl - '0'.code) * 100_000
                    if (tt.size < blockSize) tt = IntArray(blockSize)
                    while (true) {
                        val m1 = bits(24); val m2 = bits(24)
                        if (m1 == 0x314159 && m2 == 0x265359) {
                            bits(16); bits(16)     // block CRC (not checked)
                            val savedOut = outLen
                            try {
                                block(blockSize)
                            } catch (e: Eof) {
                                outLen = savedOut      // drop the partial block
                                throw e
                            }
                        } else if (m1 == 0x177245 && m2 == 0x385090) {
                            bits(16); bits(16)     // combined CRC
                            break
                        } else {
                            throw Error("bad block header")
                        }
                    }
                    if (pos >= end && bitCount < 32) break
                }
            } catch (_: Eof) {
                // truncated input: keep the blocks that were complete
            }
            return out.copyOf(outLen)
        }

        private fun buildTables(g: Int, alphaSize: Int) {
            val len = lens[g]
            var minLen = 32
            var maxLen = 0
            for (i in 0 until alphaSize) {
                val l = len[i].toInt()
                if (l > maxLen) maxLen = l
                if (l < minLen) minLen = l
            }
            minLens[g] = minLen
            maxLens[g] = maxLen
            val p = perm[g]
            var pp = 0
            for (l in minLen..maxLen) for (s in 0 until alphaSize) if (len[s].toInt() == l) p[pp++] = s
            val bs = base[g]
            val lim = limit[g]
            java.util.Arrays.fill(bs, 0)
            java.util.Arrays.fill(lim, 0)
            // canonical codes: count per length
            val count = IntArray(MAX_CODE_LEN + 2)
            for (s in 0 until alphaSize) count[len[s].toInt()]++
            var code = 0
            var idx = 0
            for (l in minLen..maxLen) {
                bs[l] = idx - code                       // perm index = code + base
                code += count[l]
                idx += count[l]
                lim[l] = code - 1                        // largest code of this length
                code = code shl 1
            }
            // fast lookup for short codes
            val lk = lookup[g]
            java.util.Arrays.fill(lk, 0)
            for (l in minLen..minOf(maxLen, LOOKUP_BITS)) {
                val first = if (l == minLen) 0 else (lim[l - 1] + 1) shl 1
                val last = lim[l]
                if (last < first) continue
                for (c in first..last) {
                    val sym = p[c + bs[l]]
                    val shift = LOOKUP_BITS - l
                    val startIdx = c shl shift
                    val n = 1 shl shift
                    val v = (sym shl 8) or l
                    for (k in 0 until n) lk[startIdx + k] = v
                }
            }
        }

        private fun decodeSym(g: Int): Int {
            val lk = lookup[g][peek(LOOKUP_BITS)]
            if (lk != 0) {
                skip(lk and 0xff)
                return lk ushr 8
            }
            val maxLen = maxLens[g]
            val v = peek(maxLen)
            val lim = limit[g]
            for (l in maxOf(minLens[g], 1)..maxLen) {
                val c = v ushr (maxLen - l)
                if (c <= lim[l]) {
                    skip(l)
                    return perm[g][c + base[g][l]]
                }
            }
            throw Error("bad Huffman code")
        }

        private fun block(blockSize: Int) {
            if (bit() != 0) throw Error("randomised blocks are not supported")
            val origPtr = bits(24)

            // symbol map
            val inUse16 = bits(16)
            val seqToUnseq = IntArray(256)
            var nInUse = 0
            for (i in 0 until 16) {
                if (inUse16 and (0x8000 ushr i) != 0) {
                    val w = bits(16)
                    for (j in 0 until 16) if (w and (0x8000 ushr j) != 0) seqToUnseq[nInUse++] = i * 16 + j
                }
            }
            if (nInUse == 0) throw Error("empty symbol map")
            val alphaSize = nInUse + 2

            val nGroups = bits(3)
            if (nGroups < 2 || nGroups > MAX_GROUPS) throw Error("bad group count")
            val nSelectors = bits(15)
            if (nSelectors < 1) throw Error("bad selector count")
            val mtfGroups = ByteArray(MAX_GROUPS) { it.toByte() }
            for (i in 0 until nSelectors) {
                var j = 0
                while (bit() == 1) {
                    j++
                    if (j >= nGroups) throw Error("bad selector")
                }
                // move to front
                val v = mtfGroups[j]
                while (j > 0) { mtfGroups[j] = mtfGroups[j - 1]; j-- }
                mtfGroups[0] = v
                if (i < MAX_SELECTORS) selectors[i] = v
            }
            val nSel = minOf(nSelectors, MAX_SELECTORS)

            // code lengths
            for (g in 0 until nGroups) {
                var curr = bits(5)
                val l = lens[g]
                for (s in 0 until alphaSize) {
                    while (true) {
                        if (curr < 1 || curr > MAX_CODE_LEN) throw Error("bad code length")
                        if (bit() == 0) break
                        if (bit() == 0) curr++ else curr--
                    }
                    l[s] = curr.toByte()
                }
                buildTables(g, alphaSize)
            }

            // MTF / RLE2 decode into tt (low 8 bits = byte)
            java.util.Arrays.fill(unzftab, 0)
            val yy = IntArray(256) { it }
            val eob = nInUse + 1
            var nblock = 0
            var groupNo = -1
            var groupPos = 0
            var g = 0
            var runLen = 0
            var runN = 1
            var inRun = false
            while (true) {
                if (groupPos == 0) {
                    groupNo++
                    if (groupNo >= nSel) throw Error("ran out of selectors")
                    groupPos = 50
                    g = selectors[groupNo].toInt()
                }
                groupPos--
                val sym = decodeSym(g)
                if (sym <= 1) {                      // RUNA / RUNB
                    if (!inRun) { inRun = true; runLen = 0; runN = 1 }
                    runLen += (sym + 1) * runN
                    runN = runN shl 1
                    if (runN > (1 shl 21)) throw Error("run too long")
                    continue
                }
                if (inRun) {
                    inRun = false
                    val uc = seqToUnseq[yy[0]]
                    if (nblock + runLen > blockSize) throw Error("block overflow")
                    unzftab[uc] += runLen
                    for (k in 0 until runLen) tt[nblock++] = uc
                }
                if (sym == eob) break
                // MTF position sym-1
                var nn = sym - 1
                val v = yy[nn]
                while (nn > 0) { yy[nn] = yy[nn - 1]; nn-- }
                yy[0] = v
                val uc = seqToUnseq[v]
                if (nblock >= blockSize) throw Error("block overflow")
                unzftab[uc]++
                tt[nblock++] = uc
            }
            if (origPtr < 0 || origPtr >= nblock) throw Error("bad origPtr")

            // inverse BWT
            val cftab = IntArray(257)
            for (i in 0 until 256) cftab[i + 1] = cftab[i] + unzftab[i]
            for (i in 0 until nblock) {
                val uc = tt[i] and 0xff
                tt[cftab[uc]] = tt[cftab[uc]] or (i shl 8)
                cftab[uc]++
            }

            // walk + undo the initial run-length encoding (4 equal bytes + count)
            ensureOut(nblock + 1024)
            var tPos = tt[origPtr] ushr 8
            var last = -1
            var same = 0
            var i = 0
            var o = outLen
            var buf = out
            while (i < nblock) {
                val e = tt[tPos]
                val ch = e and 0xff
                tPos = e ushr 8
                i++
                if (same == 4) {
                    if (ch > 0) {
                        if (o + ch > buf.size) { outLen = o; ensureOut(ch + (nblock - i) + 1024); buf = out }
                        val bb = last.toByte()
                        for (k in 0 until ch) buf[o++] = bb
                    }
                    same = 0
                    last = -1
                    continue
                }
                if (ch == last) same++ else { last = ch; same = 1 }
                if (o >= buf.size) { outLen = o; ensureOut(nblock - i + 1024); buf = out }
                buf[o++] = ch.toByte()
            }
            outLen = o
        }
    }
}
