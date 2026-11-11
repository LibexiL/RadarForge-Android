package com.libexil.radarforge.tests

import com.libexil.radarforge.core.Grib2
import java.io.File

/** Times Grib2.decode on each message of a GRIB2 file (best of 3). */
fun main(args: Array<String>) {
    val raw = File(args[0]).readBytes()
    val msgs = ArrayList<ByteArray>()
    var pos = 0
    while (pos + 16 < raw.size) {
        if (!(raw[pos] == 'G'.code.toByte() && raw[pos + 1] == 'R'.code.toByte() && raw[pos + 2] == 'I' .code.toByte() && raw[pos + 3] == 'B'.code.toByte())) { pos++; continue }
        var len = 0L
        for (k in 8 until 16) len = (len shl 8) or (raw[pos + k].toLong() and 0xff)
        if (pos + len > raw.size) break
        msgs.add(raw.copyOfRange(pos, pos + len.toInt())); pos += len.toInt()
    }
    repeat(2) { for (m in msgs) Grib2.decode(m) }          // warm the JIT
    var total = 0.0
    for (m in msgs) {
        var best = Double.MAX_VALUE
        repeat(3) { val t = System.nanoTime(); Grib2.decode(m); best = minOf(best, (System.nanoTime() - t) / 1e6) }
        total += best
        println(String.format("%.2f MB  %.1f ms", m.size / 1e6, best))
    }
    println(String.format("mean %.1f ms over %d messages", total / msgs.size, msgs.size))
}
