package com.libexil.radarforge.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Map outlines and cities (assets/basemap.bin, made by tools/build_assets.py).
 *
 * Format (little endian): "RFMAP1" u16 layerCount, then per layer:
 *   u8 nameLen, name, u32 nLines, u32 nPts, i32 starts[nLines], i32 extra[nLines], f32 lonlat[2*nPts]
 * then cities: u32 n, f32 lat[n], f32 lon[n], i32 pop[n], (u8 len, utf8 name) * n
 */
class Basemap(val layers: Map<String, Layer>, val cities: Cities) {

    class Layer(val name: String, val starts: IntArray, val extra: IntArray, val pts: FloatArray) {
        val nLines: Int get() = starts.size
        val nPoints: Int get() = pts.size / 2
        fun lineEnd(i: Int): Int = if (i + 1 < starts.size) starts[i + 1] else nPoints

        // per-line bounding boxes (lon/lat), for skipping far-away lines quickly
        val box: FloatArray by lazy {
            val b = FloatArray(nLines * 4)
            for (i in 0 until nLines) {
                var x0 = 999f; var x1 = -999f; var y0 = 999f; var y1 = -999f
                for (p in starts[i] until lineEnd(i)) {
                    val x = pts[2 * p]; val y = pts[2 * p + 1]
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                }
                b[4 * i] = x0; b[4 * i + 1] = y0; b[4 * i + 2] = x1; b[4 * i + 3] = y1
            }
            b
        }
    }

    class Cities(val lat: FloatArray, val lon: FloatArray, val pop: IntArray, val names: Array<String>)

    private val countyIndex: Map<Int, List<Int>> by lazy {
        val l = layers["counties"] ?: return@lazy emptyMap()
        val m = HashMap<Int, MutableList<Int>>()
        for (i in 0 until l.nLines) if (l.extra[i] != 0) m.getOrPut(l.extra[i]) { ArrayList() }.add(i)
        m
    }

    /** County outline rings (lon,lat) for a 5-digit FIPS code. */
    fun countyRings(fips: Int): List<FloatArray> {
        val l = layers["counties"] ?: return emptyList()
        return countyIndex[fips].orEmpty().map { i -> l.pts.copyOfRange(2 * l.starts[i], 2 * l.lineEnd(i)) }
    }

    companion object {
        fun read(bytes: ByteArray): Basemap {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(6).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == "RFMAP1") { "not a RadarForge map file" }
            val nLayers = b.short.toInt() and 0xffff
            val layers = LinkedHashMap<String, Layer>()
            repeat(nLayers) {
                val nameLen = b.get().toInt() and 0xff
                val name = String(ByteArray(nameLen).also { b.get(it) }, Charsets.UTF_8)
                val nLines = b.int
                val nPts = b.int
                val starts = IntArray(nLines).also { b.asIntBuffer().get(it); b.position(b.position() + nLines * 4) }
                val extra = IntArray(nLines).also { b.asIntBuffer().get(it); b.position(b.position() + nLines * 4) }
                val pts = FloatArray(nPts * 2).also { b.asFloatBuffer().get(it); b.position(b.position() + nPts * 8) }
                layers[name] = Layer(name, starts, extra, pts)
            }
            val n = b.int
            val lat = FloatArray(n).also { b.asFloatBuffer().get(it); b.position(b.position() + n * 4) }
            val lon = FloatArray(n).also { b.asFloatBuffer().get(it); b.position(b.position() + n * 4) }
            val pop = IntArray(n).also { b.asIntBuffer().get(it); b.position(b.position() + n * 4) }
            val names = Array(n) {
                val len = b.get().toInt() and 0xff
                String(ByteArray(len).also { a -> b.get(a) }, Charsets.UTF_8)
            }
            return Basemap(layers, Cities(lat, lon, pop, names))
        }
    }
}

/**
 * A map layer projected around one radar: x,y km pairs with a separator point
 * (SEP, SEP) after each line, grouped in chunks with bounding boxes so only the
 * visible part is drawn.
 */
class ProjectedLayer(val pts: FloatArray, val chunks: List<Chunk>) {
    class Chunk(val first: Int, val count: Int, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    companion object {
        const val SEP = 1.0e9f
        private const val CHUNK_POINTS = 4096

        fun project(layer: Basemap.Layer, proj: Geo.Aeqd, maxKm: Double): ProjectedLayer {
            val box = layer.box
            val keep = BooleanArray(layer.nLines)
            var total = 0
            val cosLat = Math.cos(Math.toRadians(proj.lat0))
            for (i in 0 until layer.nLines) {
                // distance from the radar to the nearest point of the line's box (flat-earth estimate)
                val lon = proj.lon0.coerceIn(box[4 * i].toDouble(), box[4 * i + 2].toDouble())
                val lat = proj.lat0.coerceIn(box[4 * i + 1].toDouble(), box[4 * i + 3].toDouble())
                val dx = (lon - proj.lon0) * 111.2 * cosLat
                val dy = (lat - proj.lat0) * 111.2
                if (dx * dx + dy * dy <= maxKm * maxKm) {
                    keep[i] = true
                    total += layer.lineEnd(i) - layer.starts[i] + 1
                }
            }
            val out = FloatArray(total * 2)
            val chunks = ArrayList<Chunk>()
            var n = 0
            var chunkStart = 0
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (i in 0 until layer.nLines) {
                if (!keep[i]) continue
                for (p in layer.starts[i] until layer.lineEnd(i)) {
                    proj.forward(layer.pts[2 * p + 1].toDouble(), layer.pts[2 * p].toDouble(), out, 2 * n)
                    val x = out[2 * n]; val y = out[2 * n + 1]
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                    n++
                }
                out[2 * n] = SEP; out[2 * n + 1] = SEP
                n++
                if (n - chunkStart >= CHUNK_POINTS) {
                    chunks.add(Chunk(chunkStart, n - chunkStart, x0, y0, x1, y1))
                    chunkStart = n
                    x0 = Float.MAX_VALUE; y0 = Float.MAX_VALUE; x1 = -Float.MAX_VALUE; y1 = -Float.MAX_VALUE
                }
            }
            if (n > chunkStart) chunks.add(Chunk(chunkStart, n - chunkStart, x0, y0, x1, y1))
            return ProjectedLayer(out, chunks)
        }

        /** Builds a projected layer from lon/lat rings (warnings, watch counties). */
        fun fromRings(rings: List<FloatArray>, proj: Geo.Aeqd): ProjectedLayer {
            val total = rings.sumOf { it.size / 2 + 1 }
            val out = FloatArray(total * 2)
            var n = 0
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (r in rings) {
                var i = 0
                while (i + 1 < r.size) {
                    proj.forward(r[i + 1].toDouble(), r[i].toDouble(), out, 2 * n)
                    val x = out[2 * n]; val y = out[2 * n + 1]
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                    n++; i += 2
                }
                out[2 * n] = SEP; out[2 * n + 1] = SEP; n++
            }
            return ProjectedLayer(out, if (n > 0) listOf(Chunk(0, n, x0, y0, x1, y1)) else emptyList())
        }
    }
}

/** Cities near one radar, projected, biggest first. */
class ProjectedCities(val x: FloatArray, val y: FloatArray, val pop: IntArray, val names: Array<String>) {
    companion object {
        fun project(c: Basemap.Cities, proj: Geo.Aeqd, maxKm: Double, minPop: Int = 2000): ProjectedCities {
            val idx = ArrayList<Int>()
            val tmp = FloatArray(2)
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
            val cosLat = Math.cos(Math.toRadians(proj.lat0))
            for (i in c.lat.indices) {
                if (c.pop[i] < minPop) continue
                val dx = (c.lon[i] - proj.lon0) * 111.2 * cosLat
                val dy = (c.lat[i] - proj.lat0) * 111.2
                if (dx * dx + dy * dy > maxKm * maxKm * 1.2) continue
                idx.add(i)
            }
            idx.sortByDescending { c.pop[it] }
            for (i in idx) {
                proj.forward(c.lat[i].toDouble(), c.lon[i].toDouble(), tmp, 0)
                xs.add(tmp[0]); ys.add(tmp[1])
            }
            return ProjectedCities(xs.toFloatArray(), ys.toFloatArray(), IntArray(idx.size) { c.pop[idx[it]] },
                Array(idx.size) { c.names[idx[it]] })
        }
    }
}
