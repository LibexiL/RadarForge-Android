package com.libexil.radarforge.tests

import com.libexil.radarforge.core.Grib2
import com.libexil.radarforge.core.Jpx
import java.io.DataInputStream
import java.io.File

/** Checks Jpx against eccodes (JPEG 2000 GRIB messages + truth values) and Pillow/OpenJPEG (raw codestreams + .npy). */
fun main(args: Array<String>) {
    val dir = File(args[0])
    var bad = 0
    // ---- GRIB messages
    val raw = File(dir, "jpeg.grib2").readBytes()
    val truth = DataInputStream(File(dir, "jpeg.truth").inputStream().buffered())
    var pos = 0; var k = 0
    while (pos + 16 <= raw.size) {
        var len = 0L
        for (i in 8 until 16) len = (len shl 8) or (raw[pos + i].toLong() and 0xff)
        val msg = raw.copyOfRange(pos, pos + len.toInt()); pos += len.toInt()
        val n = truth.readInt(); val m = truth.readInt()
        val idx = IntArray(m) { truth.readInt() }
        val want = FloatArray(m) { truth.readFloat() }
        val t0 = System.nanoTime()
        try {
            val g = Grib2.decode(msg)
            val ms = (System.nanoTime() - t0) / 1e6
            var worst = 0f; var nanBad = 0
            if (g.values.size != n) throw IllegalStateException("expected $n values, got ${g.values.size}")
            for (q in 0 until m) {
                val a = g.values[idx[q]]; val b = want[q]
                if (a.isNaN() != b.isNaN()) nanBad++
                else if (!a.isNaN()) worst = maxOf(worst, Math.abs(a - b) / maxOf(1f, Math.abs(b)))
            }
            val ok = nanBad == 0 && worst < 1e-5f
            if (!ok) bad++
            println(String.format("%s msg %d: %d values, %4.0f ms, worst rel err %.2e, nan mismatches %d", if (ok) "ok  " else "FAIL", k, n, ms, worst, nanBad))
        } catch (e: Exception) { bad++; println("FAIL msg $k: $e"); e.printStackTrace() }
        k++
    }
    // ---- raw codestreams vs Pillow
    for (name in listOf("row.j2k", "odd.j2k", "lossy.j2k")) {
        val f = File(dir, name); if (!f.exists()) continue
        val want = readNpy(File(dir, "$name.npy"))
        try {
            val img = Jpx.decode(f.readBytes())
            var worst = 0
            for (i in want.indices) worst = maxOf(worst, Math.abs(img.samples[i] - want[i]))
            val tol = if (name.startsWith("lossy")) 2 else 0
            val ok = img.samples.size == want.size && worst <= tol
            if (!ok) bad++
            println(String.format("%s %s: %dx%d prec %d, worst diff %d", if (ok) "ok  " else "FAIL", name, img.width, img.height, img.precision, worst))
        } catch (e: Exception) { bad++; println("FAIL $name: $e"); e.printStackTrace() }
    }
    println(if (bad == 0) "ALL OK" else "$bad FAILED")
}

/** A little-endian uint8/uint16 .npy (what the test data uses). */
private fun readNpy(f: File): IntArray {
    val b = f.readBytes()
    val hlen = (b[8].toInt() and 0xff) or ((b[9].toInt() and 0xff) shl 8)
    val header = String(b, 10, hlen, Charsets.US_ASCII)
    val two = header.contains("<u2") || header.contains("u2")
    val start = 10 + hlen
    val n = if (two) (b.size - start) / 2 else b.size - start
    return IntArray(n) { i -> if (two) (b[start + 2 * i].toInt() and 0xff) or ((b[start + 2 * i + 1].toInt() and 0xff) shl 8) else b[start + i].toInt() and 0xff }
}
