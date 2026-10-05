package com.libexil.radarforge.tests

import com.libexil.radarforge.core.*
import java.io.File

/** 1.4.0: previous scans, dealiasing, the Σ trail, learn mode, SPC day 2/3. */
private fun ok(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

fun registerLoop() {
    Extra.all.add("previous scans: pick and merge frames" to ::frames)
    Extra.all.add("dealias: synthetic folded velocity" to ::dealiasSynthetic)
    Extra.all.add("dealias: real velocity sweep" to ::dealiasReal)
    Extra.all.add("Σ trail: resample and combine" to ::trailSynthetic)
    Extra.all.add("Σ trail: real scans" to ::trailReal)
    Extra.all.add("learn mode notes" to ::learn)
    Extra.all.add("SPC day 2 / 3 outlook URLs" to ::spcAhead)
    Extra.all.add("trim a volume to a tilt and moments" to ::trimMoments)
}

private fun vol(t: Long) = Volume("KTLX", t, 35.0, -97.0, 370.0, 212, emptyList(), true)

private fun frames() {
    val min = 60_000L
    val base = 1_700_000_000_000L
    val f = (0 until 14).map { LoopFrame(base + it * 5 * min, 0.5f, setOf("REF", "VEL"), vol(base + it * 5 * min)) }
    // newest shown is frame 13: the 10 before it, oldest first
    val sel = LoopSupport.select(f, 0.5f, setOf("REF"), f[13].timeMs - 30_000, 10)
    ok(sel.size == 10 && sel.first().timeMs == f[3].timeMs && sel.last().timeMs == f[12].timeMs, "10 previous: ${sel.map { (it.timeMs - base) / min }}")
    // other tilts and missing moments don't count
    ok(LoopSupport.select(f, 0.9f, setOf("REF"), Long.MAX_VALUE, 10).isEmpty(), "other tilt")
    ok(LoopSupport.select(f, 0.5f, setOf("RHO"), Long.MAX_VALUE, 10).isEmpty(), "missing moment")
    ok(LoopSupport.select(f, 0.5f, setOf("REF"), Long.MAX_VALUE, 0).isEmpty(), "none wanted")
    // the same scan twice (live feed + archive, a few seconds apart) counts once
    val dup = f + LoopFrame(f[12].timeMs + 20_000, 0.5f, setOf("REF", "VEL"), vol(f[12].timeMs + 20_000))
    val s2 = LoopSupport.select(dup, 0.5f, setOf("REF"), Long.MAX_VALUE, 50)
    ok(s2.size == 14, "duplicate scan dropped: ${s2.size}")
    // merge: a fresh copy replaces the old one, the list stays capped and keeps the newest of the tilt shown
    var m = emptyList<LoopFrame>()
    for (fr in f) m = LoopSupport.merge(m, fr, 12)
    ok(m.size == 12 && m.last().timeMs == f[13].timeMs && m.first().timeMs == f[2].timeMs, "merge cap: ${m.size}")
    val again = LoopSupport.merge(m, LoopFrame(f[13].timeMs + 5_000, 0.5f, setOf("REF", "VEL"), vol(0)), 12)
    ok(again.size == 12 && again.count { Math.abs(it.timeMs - f[13].timeMs) < 90_000 } == 1, "replaced, not added")
    // frames of another tilt give way to the tilt being loaded
    val other = LoopSupport.merge(m, LoopFrame(f[13].timeMs, 1.3f, setOf("REF"), vol(0)), 12)
    ok(other.size == 12 && other.any { it.tilt == 1.3f }, "new tilt kept")
}

/** A velocity field from values (8-bit codes, 0.5 m/s steps). */
private fun velField(values: FloatArray, nRays: Int, nGates: Int, nyq: Float): Field {
    val az = FloatArray(nRays) { it * 360f / nRays + 0.5f }
    val lo = FloatArray(nRays) { az[it] - 180f / nRays }
    val hi = FloatArray(nRays) { az[it] + 180f / nRays }
    val f0 = Field(Product.VEL, "KTLX", 0L, 0L, 0.5f, nRays, nGates, 2.125f, 0.25f, 2f, 129f, az, lo, hi,
        ByteArray(nRays * nGates), null, nyq, 0f, 0f, true)
    val (c8, _) = f0.encode(values)
    return f0.withCodes(c8, null, "")
}

private fun dealiasSynthetic() {
    val nr = 360; val ng = 400; val nyq = 20f
    // a broad flow that peaks at 34 m/s (folded once beyond 20 m/s), with a gap of no echo
    val truth = FloatArray(nr * ng)
    for (r in 0 until nr) for (g in 0 until ng) {
        val a = Math.toRadians(r.toDouble())
        val v = (34.0 * Math.cos(a) * Math.min(1.0, g / 120.0)).toFloat()
        truth[r * ng + g] = if (g in 200..210) Float.NaN else v
    }
    fun fold(x: Float) = if (x.isNaN()) x else (x + nyq).mod(2 * nyq) - nyq
    val folded = FloatArray(truth.size) { fold(truth[it]) }
    val wrong = truth.indices.count { !truth[it].isNaN() && Math.abs(folded[it] - truth[it]) > 1f }
    ok(wrong > 10_000, "the test data is folded ($wrong gates)")
    val out = Dealias.unfold(folded, nr, ng, nyq)!!
    var bad = 0
    for (i in truth.indices) {
        if (truth[i].isNaN()) { ok(out[i].isNaN(), "no-echo gate stays empty"); continue }
        if (Math.abs(out[i] - truth[i]) > 0.75f) bad++
    }
    ok(bad < truth.size / 200, "unfolded: $bad gates still wrong")
    // through a Field and back (codes)
    val f = velField(folded, nr, ng, nyq)
    val d = Dealias.field(f)!!
    ok(d.nyquist == 0f && d.variant.contains("dealiased") && d.key != f.key, "dealiased field: nyquist 0, own key")
    val dv = d.values()
    var bad2 = 0
    for (i in truth.indices) if (!truth[i].isNaN() && Math.abs(dv[i] - truth[i]) > 0.75f) bad2++
    ok(bad2 < truth.size / 200, "field codes: $bad2 gates wrong")
    // SRV keeps its storm motion; dealiasing works on the raw velocity
    val srv = f.withCodes(f.codes8, null, "", product = Product.SRV, stormU = 5f, stormV = 7f)
    val ds = Dealias.field(srv)!!
    ok(ds.product == Product.SRV && ds.stormU == 5f && ds.stormV == 7f, "SRV keeps storm motion")
    ok(ds.codes8!!.contentEquals(d.codes8!!), "SRV and BV share the unfolded codes")
    // nothing to do
    ok(Dealias.unfold(FloatArray(100) { 3f }, 10, 10, nyq) == null, "one region: null")
    ok(Dealias.field(f.withCodes(f.codes8, null, "", nyquist = 0f)) == null, "no nyquist: null")
}

private fun realVelocity(): Pair<Volume, Field>? {
    val file = File(dataDir, "KTLX20130520_201643_V06.gz")
    if (!file.exists()) return null
    val v = Level2.read(file.readBytes())
    val t = Tilts.build(v).first()
    val f = Field.from(Product.VEL, v.site, v.startMs, t.sweep("VEL")!!) ?: return null
    return v to f
}

private fun dealiasReal() {
    val (_, f) = realVelocity() ?: run { println("       (KTLX20130520_201643_V06.gz not found - skipped)"); return }
    val vals = f.values()
    val t0 = System.nanoTime()
    val out = Dealias.unfold(vals, f.nRays, f.nGates, f.nyquist)!!
    val ms = (System.nanoTime() - t0) / 1e6
    val changed = vals.indices.count { !vals[it].isNaN() && vals[it].isFinite() && Math.abs(out[it] - vals[it]) > 0.1f }
    val valid = vals.count { it.isFinite() }
    // every change is a whole number of Nyquist intervals
    val interval = 2 * f.nyquist
    val offGrid = vals.indices.count { vals[it].isFinite() && Math.abs(out[it] - vals[it]) > 0.1f &&
        Math.abs((out[it] - vals[it]) / interval - Math.round((out[it] - vals[it]) / interval)) > 0.01f }
    ok(offGrid == 0, "$offGrid gates moved by a fraction of an interval")
    ok(changed in 1 until valid / 4, "changed $changed of $valid gates")
    // neighbours agree better after unfolding (fewer jumps of more than the Nyquist velocity)
    fun jumps(a: FloatArray): Int {
        var n = 0
        for (r in 0 until f.nRays) for (g in 1 until f.nGates) {
            val x = a[r * f.nGates + g]; val y = a[r * f.nGates + g - 1]
            if (x.isFinite() && y.isFinite() && Math.abs(x - y) > f.nyquist) n++
        }
        return n
    }
    val before = jumps(vals); val after = jumps(out)
    ok(after < before, "jumps $before -> $after")
    println(String.format("       %d x %d gates, nyquist %.1f m/s: %.0f ms, %d gates unfolded, jumps %d -> %d", f.nRays, f.nGates, f.nyquist, ms, changed, before, after))
    // export for comparing with the desktop app's numpy version (tools/dealias_compare.py)
    System.getenv("RF_DEALIAS_OUT")?.let { dir ->
        val d = File(dir).apply { mkdirs() }
        fun dump(a: FloatArray, name: String) {
            val b = java.nio.ByteBuffer.allocate(a.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (x in a) b.putFloat(if (x == Float.POSITIVE_INFINITY) Float.NaN else x)
            File(d, name).writeBytes(b.array())
        }
        dump(vals, "in.f32"); dump(out, "out.f32")
        dump(Dealias.unfold(vals, f.nRays, f.nGates, f.nyquist, centered = false)!!, "out_raw.f32")
        File(d, "meta.txt").writeText("${f.nRays} ${f.nGates} ${f.nyquist}\n")
    }
}

private fun trailSynthetic() {
    val nr = 360; val ng = 100
    fun refField(values: FloatArray, t: Long, rot: Float = 0f): Field {
        val az = FloatArray(nr) { (it + 0.5f + rot) % 360f }.sortedArray()
        val lo = FloatArray(nr) { az[it] - 0.5f }
        val hi = FloatArray(nr) { az[it] + 0.5f }
        val f0 = Field(Product.REF, "KTLX", t, t, 0.5f, nr, ng, 2.125f, 0.25f, 2f, 66f, az, lo, hi, ByteArray(nr * ng), null, 0f, 0f, 0f, true)
        return f0.withCodes(f0.encode(values).first, null, "")
    }
    // a 60 dBZ blob moving 10 rays per frame
    fun blob(at: Int) = FloatArray(nr * ng) { i -> val r = i / ng; val g = i % ng; if (r in at until at + 5 && g in 40 until 50) 60f else if (g < 20) 20f else Float.NaN }
    val f1 = refField(blob(100), 1)
    val f2 = refField(blob(110), 2)
    val f3 = refField(blob(120), 3, rot = 0.3f)          // slightly different ray positions
    // resampling onto itself changes nothing
    val same = Trail.resample(f2, f2.values(), f2)
    val v2 = f2.values()
    ok(same.indices.all { (same[it].isNaN() && v2[it].isNaN()) || same[it] == v2[it] }, "resample to itself")
    val t12 = Trail.combine(f2, f1, Trail.Rule.MAX, "trail2")
    val t123 = Trail.combine(f3, t12, Trail.Rule.MAX, "trail3")
    val tv = t123.values()
    for (at in listOf(100, 110, 120)) ok(tv[(at + 2) * ng + 45] == 60f, "blob at ray $at kept in the trail")
    ok(tv[200 * ng + 45].isNaN(), "empty stays empty")
    ok(tv[200 * ng + 5] == 20f, "background")
    ok(t123.key != f3.key && t123.variant == "trail3", "trail key")
    // CC: lowest wins; velocity: strongest either way, keeping the sign
    fun cc(v: Float) = Field(Product.CC, "K", 0, 0, 0.5f, 1, 1, 2f, 0.25f, 300f, -60.5f, floatArrayOf(10f), floatArrayOf(9.5f), floatArrayOf(10.5f), ByteArray(1), null, 0f, 0f, 0f, true)
        .let { it.withCodes(it.encode(floatArrayOf(v)).first, null, "") }
    ok(Math.abs(Trail.combine(cc(0.98f), cc(0.62f), Trail.Rule.MIN, "t").values()[0] - 0.62f) < 0.01f, "CC minimum")
    fun vel(v: Float) = velField(FloatArray(4) { v }, 2, 2, 30f)
    ok(Trail.combine(vel(10f), vel(-25f), Trail.Rule.ABSMAX, "t").values()[0] == -25f, "velocity: strongest, sign kept")
    ok(Trail.rule(Product.CC) == Trail.Rule.MIN && Trail.rule(Product.SRV) == Trail.Rule.ABSMAX && Trail.rule(Product.REF) == Trail.Rule.MAX, "rules")
    // SRV: storm motion is removed before comparing, and the result has none left
    val srv = vel(10f).withCodes(vel(10f).codes8, null, "", product = Product.SRV, stormU = 0f, stormV = 0f)
    val srvT = Trail.combine(srv, srv, Trail.Rule.ABSMAX, "t")
    ok(srvT.stormU == 0f && srvT.stormV == 0f && srvT.nyquist == 0f, "trail has no storm motion / nyquist")
}

private fun trailReal() {
    val a = File(dataDir, "KTLX20130520_201643_V06.gz")
    if (!a.exists()) { println("       (sample file not found - skipped)"); return }
    val v = Level2.read(a.readBytes())
    val t = Tilts.build(v).first()
    val ref = Field.from(Product.REF, v.site, v.startMs, t.sweep("REF")!!)!!
    // the tilt's SAILS repeat (or the same scan) as an "older" frame
    val older = t.scans.first().sweeps["REF"]?.let { Field.from(Product.REF, v.site, v.startMs - 300_000, it) } ?: ref
    val t0 = System.nanoTime()
    val tr = Trail.combine(ref, older, Trail.Rule.MAX, "trail2")
    val ms = (System.nanoTime() - t0) / 1e6
    val a1 = ref.values(); val a2 = Trail.resample(older, older.values(), ref); val out = tr.values()
    var bad = 0
    for (i in out.indices) {
        val want = when { a1[i].isFinite() && a2[i].isFinite() -> maxOf(a1[i], a2[i]); a1[i].isFinite() -> a1[i]; a2[i].isFinite() -> a2[i]; else -> Float.NaN }
        if (want.isNaN()) continue
        if (Math.abs(out[i] - want) > 0.26f) bad++
    }
    ok(bad == 0, "$bad gates differ from max(a, b)")
    println(String.format("       %d x %d gates: %.0f ms", ref.nRays, ref.nGates, ms))
}

private fun learn() {
    val n = Learn.explain(mapOf(Product.REF to 62f, Product.CC to 0.72f, Product.ZDR to 0.3f, Product.VEL to -30f), 12_000.0)
    ok(n.any { it.startsWith("62 dBZ") && it.contains("hail") }, "reflectivity note: $n")
    ok(n.any { it.contains("debris") }, "debris hint with high reflectivity and low CC")
    ok(n.any { it.contains("classic hail signature") }, "hail signature")
    ok(n.any { it.startsWith("58 kt toward") && it.contains("damaging") }, "velocity note: $n")
    ok(n.any { it.contains("12,000 ft") }, "beam height note")
    ok(Learn.explain(emptyMap()).isEmpty(), "nothing to say")
    ok(Learn.explain(mapOf(Product.SRV to 10f, Product.VEL to -40f)).single().contains("away from"), "SRV preferred over VEL")
    ok(Product.entries.all { Learn.PRODUCT_HELP.containsKey(it) }, "help for every product")
}

private fun spcAhead() {
    fun at(s: String) = Time.parseIso(s)
    // 03 UTC on the 5th: the date hasn't flipped yet, so day 2 covers the 5th
    val u = Spc.outlookUrlsAhead(at("2026-10-05T03:00:00Z"), 2)
    ok(u.first().contains("day=2&valid=2026-10-05&cycle=17"), "day 2 before 06 UTC: ${u.first()}")
    ok(u.size == 8 && u.any { it.contains("valid=2026-10-04") }, "falls back a day")
    val v = Spc.outlookUrlsAhead(at("2026-10-05T18:00:00Z"), 3)
    ok(v.first().contains("day=3&valid=2026-10-07&cycle=20"), "day 3 after 06 UTC: ${v.first()}")
    ok(Spc.outlookUrlsFor(at("2026-10-05T18:00:00Z"), 1).first().contains("day=1"), "day 1 unchanged")
    ok(Spc.outlookUrlsAhead(at("2026-12-31T12:00:00Z"), 3).first().contains("valid=2027-01-02"), "year rollover")
}

private fun trimMoments() {
    val file = File(dataDir, "Level2_KDDC_20200823_204121.ar2v")
    if (!file.exists()) { println("       (sample file not found - skipped)"); return }
    val v = Level2.read(file.readBytes())
    val tr = LoopSupport.trimTo(v, 0.5f, setOf("REF", "VEL"))
    val ts = Tilts.build(tr)
    ok(ts.size == 1 && Math.abs(ts[0].elevation - 0.5f) < 0.3f, "one tilt")
    ok(tr.sweeps.all { s -> s.moments.keys.all { it in setOf("REF", "VEL") } }, "only REF and VEL: ${tr.sweeps.map { it.moments.keys }}")
    ok(ts[0].sweep("REF") != null && ts[0].sweep("VEL") != null, "both kept")
    // one sweep per moment at most (the newest scan's), not every SAILS pass or both halves of a split cut
    ok(tr.sweeps.size <= 2, "REF + VEL: ${tr.sweeps.size} sweeps kept")
    val full = Tilts.build(v)[Tilts.closest(Tilts.build(v), 0.5f)]
    ok(ts[0].sweep("REF")!!.startMs == full.sweep("REF")!!.startMs && ts[0].sweep("VEL")!!.startMs == full.sweep("VEL")!!.startMs,
        "the same sweeps a full volume would show")
    ok(LoopSupport.trimTo(v, 0.5f, setOf("REF")).sweeps.size == 1, "REF only: one sweep")
    ok(LoopSupport.trimTo(v, 0.5f, setOf("ZZZ")).sweeps.isEmpty(), "nothing left")
}
