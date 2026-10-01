package com.libexil.radarforge.tests

import com.libexil.radarforge.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dumps a scene (radar fields, meshes, colour tables, projected map layers) for
 * tools/glcheck/harness.html, which renders it with the app's own shaders in WebGL2
 * (GLSL ES 3.00, the same language as OpenGL ES 3.0 on phones).
 *
 *   java -cp ... com.libexil.radarforge.tests.ExportSceneKt <level2 file> <out dir> [products]
 */
fun main(args: Array<String>) {
    val src = File(args[0])
    val out = File(args[1]).apply { mkdirs() }
    val products = (args.getOrNull(2) ?: "REF,SRV,CC,ZDR").split(",").map { Product.byId(it) }
    val smooth = (args.getOrNull(3) ?: "0") == "1"
    val vol = Level2.read(src.readBytes())
    val tilts = Tilts.build(vol)
    val tilt = tilts.first()
    val basemap = Basemap.read(File("app/assets/basemap.bin").readBytes())
    val proj = Geo.Aeqd(vol.lat, vol.lon)

    fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    fun floats(f: FloatArray, name: String) {
        val b = le(f.size * 4); b.asFloatBuffer().put(f); File(out, name).writeBytes(b.array())
    }

    val panels = StringBuilder()
    for ((i, p) in products.withIndex()) {
        val sw = tilt.sweep(p.moment) ?: continue
        val f = Field.from(p, vol.site, vol.startMs, sw, 240f, 30f) ?: continue
        val ct = ColorTable.parse(File("app/assets/palettes/${p.palette}.pal").readText(), p.palette)
        val d = RenderData.display(p, ct)
        floats(RenderData.radarMesh(f), "mesh$i.bin")
        File(out, "lut$i.bin").writeBytes(ct.lut(1024))
        if (f.codes8 != null) File(out, "codes$i.bin").writeBytes(f.codes8!!)
        else { val b = le(f.codes16!!.size * 2); for (c in f.codes16!!) b.putShort(c.code.toShort()); File(out, "codes$i.bin").writeBytes(b.array()) }
        if (panels.isNotEmpty()) panels.append(",\n")
        panels.append("""{"product":"${p.id}","nRays":${f.nRays},"nGates":${f.nGates},"first":${f.firstGate},"spacing":${f.gateSpacing},""" +
            """"elev":${Math.toRadians(f.elevation.toDouble())},"cscale":${f.scale},"coffset":${f.offset},"bits":${if (f.codes8 != null) 8 else 16},""" +
            """"dscale":${d.dscale},"doffset":${d.doffset},"lutMin":${d.lutMin},"lutMax":${d.lutMax},""" +
            """"rf":[${ct.rf.joinToString { (it / 255f).toString() }}],"nyq":${if (p.isDoppler) f.nyquist else 0f},""" +
            """"storm":[${f.stormU},${f.stormV}],"smooth":${if (smooth) 1 else 0},"label":"${vol.site} ${p.title} ${tilt.label} ${Time.hmsZ(f.sweepMs)}"}""")
    }
    // map layers, colours from the RadarForge Dark theme
    val layers = listOf(
        Triple("counties", "0.41,0.41,0.41,0.90", 1.0f), Triple("lakes", "0.27,0.43,0.67,0.78", 1.0f),
        Triple("roads2", "0.47,0.33,0.24,0.86", 1.0f), Triple("roads", "0.69,0.27,0.27,0.92", 1.4f),
        Triple("countries", "0.84,0.84,0.84,1.0", 1.8f), Triple("states", "0.88,0.88,0.88,1.0", 1.8f))
    val ls = StringBuilder()
    for ((name, color, width) in layers) {
        val pl = ProjectedLayer.project(basemap.layers.getValue(name), proj, 1000.0)
        floats(pl.pts, "layer_$name.bin")
        if (ls.isNotEmpty()) ls.append(",")
        ls.append("""{"name":"$name","color":[$color],"width":$width,"points":${pl.pts.size / 2}}""")
    }
    floats(RenderData.rangeRings(460f, 50f), "rings.bin")
    File(out, "scene.json").writeText("""{"panels":[$panels],"layers":[$ls],"corners":[${RenderData.LINE_CORNERS.joinToString()}]}""")
    println("exported ${products.size} panels for ${vol.site} ${Time.iso(vol.startMs)} to $out")
}
