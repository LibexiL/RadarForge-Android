package com.libexil.radarforge.core

/**
 * Small JSON parser: objects -> Map<String, Any?>, arrays -> List<Any?>,
 * numbers -> Double, plus String / Boolean / null.
 */
object Json {
    class Error(msg: String) : RuntimeException(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i < text.length) throw Error("trailing data at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else break
            }
        }

        fun value(): Any? {
            if (i >= s.length) throw Error("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw Error("unexpected '$c' at $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw Error("bad literal at $i")
            i += word.length
            return v
        }

        fun num(): Double {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(st, i).toDouble()
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw Error("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws(); out.add(value()); ws()
                if (i >= s.length) throw Error("unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw Error("expected , or ] at ${i - 1}")
                }
            }
        }

        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw Error("expected key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i++] != ':') throw Error("expected : at ${i - 1}")
                ws()
                out[k] = value()
                ws()
                if (i >= s.length) throw Error("unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw Error("expected , or } at ${i - 1}")
                }
            }
        }
    }
}

// convenient typed access
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
@Suppress("UNCHECKED_CAST")
fun Any?.arr(): List<Any?> = this as? List<Any?> ?: emptyList()
fun Any?.str(def: String = ""): String = (this as? String) ?: def
fun Any?.num(def: Double = Double.NaN): Double = (this as? Number)?.toDouble() ?: def
