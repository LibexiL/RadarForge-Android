package com.libexil.radarforge

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
        RfLog.i("RadarForge ${versionName()} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}

/** Recent log lines kept in memory so they can be shown in Settings -> Diagnostics. */
object RfLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    private fun add(level: String, msg: String) {
        lines.addLast("${fmt.format(Date())} $level $msg")
        while (lines.size > 400) lines.removeFirst()
    }

    fun i(msg: String) { Log.i("RadarForge", msg); add("I", msg) }
    fun w(msg: String) { Log.w("RadarForge", msg); add("W", msg) }
    fun e(msg: String, t: Throwable? = null) {
        Log.e("RadarForge", msg, t)
        add("E", if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}

/** Saves an uncaught crash so the next launch can show it (and the user can send it). */
object Crash {
    private lateinit var file: File

    fun install(app: App) {
        file = File(app.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file.writeText(buildString {
                    append("RadarForge ${app.versionName()} crashed\n")
                    append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                    append("Thread: ${t.name}\nTime: ${Date()}\n\n")
                    append(sw.toString())
                    append("\n--- recent log ---\n")
                    append(RfLog.text())
                })
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** The saved report, removed once read. */
    fun takeReport(): String? {
        if (!::file.isInitialized || !file.exists()) return null
        val s = try { file.readText() } catch (_: Exception) { null }
        file.delete()
        return s
    }
}
