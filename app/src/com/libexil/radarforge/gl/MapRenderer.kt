package com.libexil.radarforge.gl

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import com.libexil.radarforge.RfLog
import com.libexil.radarforge.core.ColorTable
import com.libexil.radarforge.core.Field
import com.libexil.radarforge.core.ProjectedLayer
import com.libexil.radarforge.core.RenderData
import com.libexil.radarforge.ui.MapState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the panels: background, radar (shader decodes raw gate codes through the
 * colour table), map outlines and warnings as anti-aliased instanced lines.
 * All GL objects are rebuilt from the Scene when the context is (re)created.
 */
class MapRenderer(private val ctx: Context, private val state: MapState) : GLSurfaceView.Renderer {
    @Volatile var scene: Scene = Scene.EMPTY
    @Volatile var glInfo: String = ""
        private set
    var onError: ((String) -> Unit)? = null

    private var radarProg = 0
    private var lineProg = 0
    private val ru = HashMap<String, Int>()
    private val lu = HashMap<String, Int>()
    private var cornerBuf = 0
    private var lineVao = 0
    private var ready = false

    private class FieldGpu(val tex: Int, val mesh: Int, val vao: Int, val verts: Int, var lastUse: Long)
    private val fields = HashMap<String, FieldGpu>()
    private val luts = IdentityHashMap<ColorTable, Int>()
    private val lineBufs = IdentityHashMap<FloatArray, Int>()
    private var frameNo = 0L

    // ---------------------------------------------------------------- lifecycle
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // a new context: every old object id is gone
        fields.clear(); luts.clear(); lineBufs.clear(); ru.clear(); lu.clear()
        ready = false
        try {
            radarProg = program(asset("shaders/radar.vert"), asset("shaders/radar.frag"), "radar")
            lineProg = program(asset("shaders/line.vert"), asset("shaders/line.frag"), "line")
            for (n in listOf("u_center", "u_scale", "u_vp", "u_data", "u_lut", "u_first", "u_spacing", "u_elev", "u_ngates",
                "u_nrays", "u_cscale", "u_coffset", "u_dscale", "u_doffset", "u_lutmin", "u_lutmax", "u_rf", "u_smooth",
                "u_nyq", "u_storm")) ru[n] = glGetUniformLocation(radarProg, n)
            for (n in listOf("u_center", "u_scale", "u_vp", "u_width", "u_color")) lu[n] = glGetUniformLocation(lineProg, n)
            cornerBuf = buffer(RenderData.LINE_CORNERS)
            val vaos = IntArray(1)
            glGenVertexArrays(1, vaos, 0)
            lineVao = vaos[0]
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            glInfo = "${glGetString(GL_RENDERER)} | ${glGetString(GL_VERSION)}"
            RfLog.i("GL ready: $glInfo")
            ready = true
        } catch (e: Exception) {
            RfLog.e("GL setup failed", e)
            onError?.invoke("Graphics setup failed: ${e.message}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        state.surfaceW = width
        state.surfaceH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        frameNo++
        val sc = scene
        val v = state.snapshot()
        glDisable(GL_SCISSOR_TEST)
        glViewport(0, 0, state.surfaceW, state.surfaceH)
        clear(sc.gapBg)
        if (!ready) return
        glEnable(GL_BLEND)
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_SCISSOR_TEST)
        for ((i, r) in v.panels.withIndex()) {
            val vx = r.left.toInt()
            val vy = v.surfaceH - r.bottom.toInt()
            val vw = r.width().toInt()
            val vh = r.height().toInt()
            if (vw <= 0 || vh <= 0) continue
            glViewport(vx, vy, vw, vh)
            glScissor(vx, vy, vw, vh)
            clear(sc.mapBg)
            val p = sc.panels.getOrNull(i)
            if (p?.field != null) drawRadar(p, v, vw, vh)
            val viewKm = vw / v.scale
            val halfW = vw / 2f / v.scale
            val halfH = vh / 2f / v.scale
            for (l in sc.layers) if (viewKm <= l.maxViewKm) drawLines(l, v, vw, vh, halfW, halfH)
            for (l in sc.overlays) drawLines(l, v, vw, vh, halfW, halfH)
        }
        glDisable(GL_SCISSOR_TEST)
        if (sc.keep.isNotEmpty()) preload(2)
        collect(sc)
        val err = glGetError()
        if (err != GL_NO_ERROR && frameNo % 120 == 1L) RfLog.w("GL error 0x${Integer.toHexString(err)}")
    }

    private fun clear(argb: Int) {
        glClearColor(((argb shr 16) and 0xff) / 255f, ((argb shr 8) and 0xff) / 255f, (argb and 0xff) / 255f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
    }

    // ---------------------------------------------------------------- radar
    private fun fieldGpu(f: Field): FieldGpu {
        fields[f.key]?.let { it.lastUse = frameNo; return it }
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        val tex = ids[0]
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, tex)
        if (f.codes8 != null) {
            val b = ByteBuffer.allocateDirect(f.codes8.size).order(ByteOrder.nativeOrder())
            b.put(f.codes8).position(0)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8UI, f.nGates, f.nRays, 0, GL_RED_INTEGER, GL_UNSIGNED_BYTE, b)
        } else {
            val src = f.codes16!!
            val b = ByteBuffer.allocateDirect(src.size * 2).order(ByteOrder.nativeOrder())
            val sb = b.asShortBuffer()
            for (c in src) sb.put(c.code.toShort())
            b.position(0)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R16UI, f.nGates, f.nRays, 0, GL_RED_INTEGER, GL_UNSIGNED_SHORT, b)
        }
        // integer textures must use NEAREST, or they are incomplete and read as zero
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

        val mesh = RenderData.radarMesh(f)
        val vbo = buffer(mesh)
        glGenVertexArrays(1, ids, 0)
        val vao = ids[0]
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        val stride = RenderData.RADAR_STRIDE * 4
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 1, GL_FLOAT, false, stride, 8)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 12)
        glBindVertexArray(0)
        val g = FieldGpu(tex, vbo, vao, mesh.size / RenderData.RADAR_STRIDE, frameNo)
        fields[f.key] = g
        return g
    }

    private fun lutTex(ct: ColorTable): Int {
        luts[ct]?.let { return it }
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        val bytes = ct.lut(1024)
        val b = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        b.put(bytes).position(0)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1024, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, b)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        luts[ct] = ids[0]
        return ids[0]
    }

    private fun drawRadar(p: PanelDraw, v: MapState.View, vw: Int, vh: Int) {
        val f = p.field ?: return
        val g = fieldGpu(f)
        val lut = lutTex(p.table)
        val d = RenderData.display(f.product, p.table)
        glUseProgram(radarProg)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, g.tex)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, lut)
        glUniform1i(ru.getValue("u_data"), 0)
        glUniform1i(ru.getValue("u_lut"), 1)
        glUniform2f(ru.getValue("u_center"), v.cx, v.cy)
        glUniform1f(ru.getValue("u_scale"), v.scale)
        glUniform2f(ru.getValue("u_vp"), vw.toFloat(), vh.toFloat())
        glUniform1f(ru.getValue("u_first"), f.firstGate)
        glUniform1f(ru.getValue("u_spacing"), f.gateSpacing)
        glUniform1f(ru.getValue("u_elev"), Math.toRadians(f.elevation.toDouble()).toFloat())
        glUniform1i(ru.getValue("u_ngates"), f.nGates)
        glUniform1i(ru.getValue("u_nrays"), f.nRays)
        glUniform1f(ru.getValue("u_cscale"), f.scale)
        glUniform1f(ru.getValue("u_coffset"), f.offset)
        glUniform1f(ru.getValue("u_dscale"), d.dscale)
        glUniform1f(ru.getValue("u_doffset"), d.doffset)
        glUniform1f(ru.getValue("u_lutmin"), d.lutMin)
        glUniform1f(ru.getValue("u_lutmax"), d.lutMax)
        val rf = p.table.rf
        glUniform4f(ru.getValue("u_rf"), rf[0] / 255f, rf[1] / 255f, rf[2] / 255f, rf[3] / 255f)
        glUniform1i(ru.getValue("u_smooth"), if (p.smooth) 1 else 0)
        glUniform1f(ru.getValue("u_nyq"), if (f.product.isDoppler) f.nyquist else 0f)
        glUniform2f(ru.getValue("u_storm"), f.stormU, f.stormV)
        glBindVertexArray(g.vao)
        glDrawArrays(GL_TRIANGLES, 0, g.verts)
        glBindVertexArray(0)
    }

    // ---------------------------------------------------------------- lines
    private fun lineBuf(l: ProjectedLayer): Int = lineBufs.getOrPut(l.pts) { buffer(l.pts) }

    private fun drawLines(l: LayerDraw, v: MapState.View, vw: Int, vh: Int, halfW: Float, halfH: Float) {
        val pl = l.layer
        if (pl.chunks.isEmpty()) return
        val buf = lineBuf(pl)
        glUseProgram(lineProg)
        glBindVertexArray(lineVao)
        glBindBuffer(GL_ARRAY_BUFFER, cornerBuf)
        glEnableVertexAttribArray(2)
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 8, 0)
        glVertexAttribDivisor(2, 0)
        glBindBuffer(GL_ARRAY_BUFFER, buf)
        glEnableVertexAttribArray(0)
        glEnableVertexAttribArray(1)
        glVertexAttribDivisor(0, 1)
        glVertexAttribDivisor(1, 1)
        glUniform2f(lu.getValue("u_center"), v.cx, v.cy)
        glUniform1f(lu.getValue("u_scale"), v.scale)
        glUniform2f(lu.getValue("u_vp"), vw.toFloat(), vh.toFloat())
        val margin = l.widthPx / v.scale + 1f
        val x0 = v.cx - halfW - margin; val x1 = v.cx + halfW + margin
        val y0 = v.cy - halfH - margin; val y1 = v.cy + halfH + margin
        // dark halo, the colour, then the black centre stripe of "center" / "double" lines
        val passes = ArrayList<Pair<Int, Float>>(3)
        if (l.halo) passes.add(0xd0000000.toInt() to l.widthPx + 2.5f)
        passes.add(l.color to l.widthPx)
        if (l.innerPx > 0f) passes.add(0xff000000.toInt() to maxOf(1f, l.innerPx))
        for ((c, w) in passes) {
            glUniform1f(lu.getValue("u_width"), w)
            glUniform4f(lu.getValue("u_color"), ((c shr 16) and 0xff) / 255f, ((c shr 8) and 0xff) / 255f,
                (c and 0xff) / 255f, ((c ushr 24) and 0xff) / 255f)
            for (ch in pl.chunks) {
                if (ch.count < 2 || ch.maxX < x0 || ch.minX > x1 || ch.maxY < y0 || ch.minY > y1) continue
                // per-instance attributes start at this chunk (ES 3.0 has no base instance)
                glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, ch.first * 8)
                glVertexAttribPointer(1, 2, GL_FLOAT, false, 8, ch.first * 8 + 8)
                glDrawArraysInstanced(GL_TRIANGLES, 0, 6, ch.count - 1)
            }
        }
        glVertexAttribDivisor(0, 0)
        glVertexAttribDivisor(1, 0)
        glBindVertexArray(0)
    }

    // ---------------------------------------------------------------- housekeeping
    /** Frees GPU objects the scene no longer uses. */
    private fun collect(sc: Scene) {
        if (frameNo % 30 != 0L) return
        val wantFields = HashSet<String>()
        for (p in sc.panels) p.field?.let { wantFields.add(it.key) }
        for (f in sc.keep) wantFields.add(f.key)
        val drop = fields.filter { (k, g) -> k !in wantFields && frameNo - g.lastUse > 60 }
        for ((k, g) in drop) {
            glDeleteTextures(1, intArrayOf(g.tex), 0)
            glDeleteBuffers(1, intArrayOf(g.mesh), 0)
            glDeleteVertexArrays(1, intArrayOf(g.vao), 0)
            fields.remove(k)
        }
        val wantBufs = java.util.Collections.newSetFromMap(IdentityHashMap<FloatArray, Boolean>())
        for (l in sc.layers) wantBufs.add(l.layer.pts)
        for (l in sc.overlays) wantBufs.add(l.layer.pts)
        val dropBufs = lineBufs.keys.filter { it !in wantBufs }
        for (k in dropBufs) glDeleteBuffers(1, intArrayOf(lineBufs.remove(k)!!), 0)
        val wantLuts = java.util.Collections.newSetFromMap(IdentityHashMap<ColorTable, Boolean>())
        for (p in sc.panels) wantLuts.add(p.table)
        val dropLuts = luts.keys.filter { it !in wantLuts }
        for (k in dropLuts) glDeleteTextures(1, intArrayOf(luts.remove(k)!!), 0)
    }

    /** Uploads loop frames ahead of time so playback doesn't stutter. */
    fun preload(maxPerFrame: Int = 2) {
        if (!ready) return
        var n = 0
        for (f in scene.keep) {
            if (f.key !in fields) {
                fieldGpu(f)
                if (++n >= maxPerFrame) return
            }
        }
    }

    private fun buffer(data: FloatArray): Int {
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        val b = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        b.asFloatBuffer().put(data)
        b.position(0)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, b, GL_STATIC_DRAW)
        return ids[0]
    }

    private fun asset(name: String) = ctx.assets.open(name).bufferedReader().use { it.readText() }

    private fun program(vs: String, fs: String, name: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            val okv = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, okv, 0)
            if (okv[0] == 0) throw RuntimeException("$name shader: ${glGetShaderInfoLog(s)}")
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val okv = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, okv, 0)
        if (okv[0] == 0) throw RuntimeException("$name link: ${glGetProgramInfoLog(p)}")
        return p
    }
}
