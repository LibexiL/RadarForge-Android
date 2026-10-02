package com.libexil.radarforge.core

/**
 * The parts of a GRLevelX placefile that Spotter Network's feeds use: Object blocks
 * holding Icon and Text lines (plus loose Icon / Text lines with their own lat/lon).
 */
object Placefile {
    /** One icon. [text] is its hover text with "\n" turned into real line breaks. */
    class IconLine(val angle: Float, val file: Int, val index: Int, val text: String)

    class Obj(val lat: Double, val lon: Double, val icons: List<IconLine>, val texts: List<String>)

    class Parsed(val refreshMin: Int, val title: String, val iconFiles: Map<Int, String>, val objects: List<Obj>)

    fun parse(text: String): Parsed {
        var refresh = 0
        var title = ""
        val files = HashMap<Int, String>()
        val out = ArrayList<Obj>()
        var lat = Double.NaN
        var lon = Double.NaN
        var icons = ArrayList<IconLine>()
        var texts = ArrayList<String>()
        var inObject = false

        fun close() {
            if (inObject && !lat.isNaN() && !lon.isNaN()) out.add(Obj(lat, lon, icons, texts))
            inObject = false
            icons = ArrayList(); texts = ArrayList()
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val rest = line.substring(colon + 1).trim()
            when (key) {
                "refresh" -> refresh = rest.toIntOrNull() ?: refresh
                "refreshseconds" -> rest.toIntOrNull()?.let { refresh = maxOf(1, it / 60) }
                "title" -> title = rest
                "iconfile" -> {
                    val f = fields(rest)
                    val n = f.getOrNull(0)?.toIntOrNull()
                    if (n != null && f.size >= 6) files[n] = f[5]
                }
                "object" -> {
                    close()
                    val f = fields(rest)
                    lat = f.getOrNull(0)?.toDoubleOrNull() ?: Double.NaN
                    lon = f.getOrNull(1)?.toDoubleOrNull() ?: Double.NaN
                    inObject = true
                }
                "end" -> close()
                "icon" -> {
                    val f = fields(rest)
                    if (f.size < 5) continue
                    val ic = IconLine(f[2].toFloatOrNull() ?: 0f, f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: 0,
                        unescape(f.getOrNull(5) ?: ""))
                    if (inObject) icons.add(ic)
                    else {
                        // a loose icon: the first two fields are its lat / lon
                        val la = f[0].toDoubleOrNull(); val lo = f[1].toDoubleOrNull()
                        if (la != null && lo != null) out.add(Obj(la, lo, listOf(ic), emptyList()))
                    }
                }
                "text" -> {
                    val f = fields(rest)
                    if (f.size < 4) continue
                    if (inObject) texts.add(unescape(f[3]))
                    else {
                        val la = f[0].toDoubleOrNull(); val lo = f[1].toDoubleOrNull()
                        if (la != null && lo != null) out.add(Obj(la, lo, emptyList(), listOf(unescape(f[3]))))
                    }
                }
            }
        }
        close()
        return Parsed(refresh, title, files, out)
    }

    /** Splits on commas outside double quotes; quotes are removed and fields trimmed. */
    fun fields(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var wasQuoted = false
        for (c in s) {
            when {
                c == '"' -> { quoted = !quoted; wasQuoted = true }
                c == ',' && !quoted -> {
                    out.add(cur.toString().trim())
                    cur.setLength(0); wasQuoted = false
                }
                quoted -> cur.append(c)
                c == ' ' && cur.isEmpty() -> {}
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty() || wasQuoted || s.trimEnd().endsWith(",")) out.add(cur.toString().trim())
        return out
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").trim()
}
