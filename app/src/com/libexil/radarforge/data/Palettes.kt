package com.libexil.radarforge.data

import android.content.Context
import com.libexil.radarforge.RfLog
import com.libexil.radarforge.core.ColorTable
import java.io.File

/** Built-in colour tables (assets/palettes) plus .pal files the user imported. */
class Palettes(private val ctx: Context, private val prefs: Prefs) {
    val families = listOf("REF", "VEL", "SW", "ZDR", "CC", "PHI")
    private val dir = File(ctx.filesDir, "palettes").apply { mkdirs() }
    private val cache = HashMap<String, ColorTable>()

    private fun builtin(family: String): ColorTable = cache.getOrPut("builtin:$family") {
        val text = ctx.assets.open("palettes/$family.pal").bufferedReader().use { it.readText() }
        ColorTable.parse(text, "RadarForge $family").also { it.source = "built-in" }
    }

    /** The table in use for a palette family. */
    fun table(family: String): ColorTable {
        val file = prefs.palette(family)
        if (file.isNotEmpty()) {
            cache["file:$file"]?.let { return it }
            val f = File(dir, file)
            if (f.exists()) {
                try {
                    val ct = ColorTable.parse(f.readText(), f.nameWithoutExtension).also { it.source = f.name }
                    if (ct.entries.isNotEmpty()) {
                        cache["file:$file"] = ct
                        return ct
                    }
                } catch (e: Exception) {
                    RfLog.e("bad colour table $file", e)
                }
            }
        }
        return builtin(family)
    }

    /** Imported tables made for a family (or for no recognisable family). */
    fun imported(family: String): List<String> = dir.listFiles().orEmpty().filter { it.isFile }.mapNotNull { f ->
        val ct = try { ColorTable.parse(f.readText(), f.nameWithoutExtension) } catch (_: Exception) { return@mapNotNull null }
        val fam = ColorTable.family(ct)
        if (fam == family || fam == null) f.name else null
    }.sorted()

    /** Saves an imported table; returns (family or null, file name) or throws with a readable message. */
    fun import(name: String, text: String): Pair<String?, String> {
        val ct = ColorTable.parse(text, name)
        if (ct.entries.isEmpty()) throw IllegalArgumentException("That file has no Color: lines, so it isn't a colour table.")
        val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").let {
            if (it.lowercase().endsWith(".pal") || it.lowercase().endsWith(".txt")) it.substringBeforeLast('.') + ".pal" else "$it.pal"
        }
        File(dir, safe).writeText(text)
        cache.remove("file:$safe")
        val fam = ColorTable.family(ct)
        if (fam != null && fam in families) prefs.setPalette(fam, safe)
        return fam to safe
    }

    fun delete(file: String) {
        File(dir, file).delete()
        cache.remove("file:$file")
        for (f in families) if (prefs.palette(f) == file) prefs.setPalette(f, "")
    }

    fun select(family: String, file: String) {
        prefs.setPalette(family, file)
    }
}
