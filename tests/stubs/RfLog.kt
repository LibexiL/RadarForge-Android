package com.libexil.radarforge

/** Test stand-in for the app's log (App.kt needs Android). */
object RfLog {
    val lines: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
    var echo = false
    fun i(msg: String) { lines.add("I $msg"); if (echo) println("       [I] $msg") }
    fun w(msg: String) { lines.add("W $msg"); if (echo) println("       [W] $msg") }
    fun e(msg: String, t: Throwable? = null) { lines.add("E $msg ${t ?: ""}"); if (echo) println("       [E] $msg ${t ?: ""}") }
    fun text() = lines.joinToString("\n")
}
