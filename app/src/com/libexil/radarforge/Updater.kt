package com.libexil.radarforge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import com.libexil.radarforge.core.Net
import com.libexil.radarforge.core.Releases
import com.libexil.radarforge.core.Releases.Release
import com.libexil.radarforge.ui.C
import com.libexil.radarforge.ui.W
import com.libexil.radarforge.ui.dpi
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Keeps RadarForge up to date from its GitHub releases: looks for a newer version about once a day,
 * offers it, downloads the APK and hands it to Android's package installer (which asks the user to
 * confirm). Updates install over the old version only when both are signed with the same key – the
 * GitHub builds all are.
 */
object Updater {
    private const val ACTION_STATUS = "com.libexil.radarforge.UPDATE_STATUS"
    private const val CHECK_EVERY_MS = 20L * 3600_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var checking = false
    @Volatile private var downloading: Release? = null
    @Volatile private var cancelled = false
    private var receiverOn = false
    /** A downloaded update waiting for "Install unknown apps" to be allowed. */
    private var waitingForPermission: File? = null

    private fun installed(ctx: Context) = (ctx.applicationContext as App).versionName()
    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates")

    // ------------------------------------------------------------------ checking
    /** From onResume: a daily look for a new version, and an install waiting for permission. */
    fun onResume(a: MainActivity) {
        waitingForPermission?.let { f ->
            if (canInstall(a)) { waitingForPermission = null; install(a, f) }
        }
        if (!a.prefs.autoUpdateCheck || checking || downloading != null) return
        if (System.currentTimeMillis() - a.prefs.lastUpdateCheck < CHECK_EVERY_MS) return
        main.postDelayed({ if (!a.isDestroyed) check(a, manual = false) }, 5_000)
    }

    /** Looks for a new version; [manual]: the user asked, so say what was found either way. */
    fun check(a: MainActivity, manual: Boolean) {
        if (checking) return
        if (downloading != null) { if (manual) a.toast("The update is downloading…"); return }
        checking = true
        if (manual) a.toast("Checking for updates…")
        Thread({
            var rel: Release? = null
            var err: String? = null
            var reached = true          // GitHub answered (even "no releases"): no need to ask again today
            try {
                rel = Releases.parse(Net.getText(Releases.LATEST_URL, accept = "application/vnd.github+json", attempts = 2))
                if (rel == null) err = "no published version found"
            } catch (e: Net.HttpError) {
                err = if (e.code == 404) "no versions have been published on GitHub yet" else "GitHub answered ${e.code}"
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                reached = false
            }
            main.post {
                checking = false
                if (a.isDestroyed) return@post
                if (reached) a.prefs.lastUpdateCheck = System.currentTimeMillis()
                cleanOld(a)
                val have = installed(a)
                when {
                    rel == null -> { if (manual) a.toast("Couldn't check for updates: $err"); RfLog.w("update check: $err") }
                    !Releases.isNewer(rel.version, have) -> {
                        RfLog.i("update check: newest is ${rel.version}, have $have")
                        if (manual) a.toast("RadarForge $have is the newest version")
                    }
                    !manual && rel.version == a.prefs.skippedVersion -> RfLog.i("update ${rel.version} skipped by the user")
                    manual -> offer(a, rel)
                    else -> offerWhenFree(a, rel, 0)
                }
            }
        }, "update-check").apply { isDaemon = true }.start()
    }

    /** Waits (up to about two minutes) for the user to close whatever sheet is open. */
    private fun offerWhenFree(a: MainActivity, rel: Release, tries: Int) {
        if (a.isDestroyed) return
        if (!a.sheets.isOpen) offer(a, rel)
        else if (tries < 24) main.postDelayed({ offerWhenFree(a, rel, tries + 1) }, 5_000)
    }

    private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

    fun offer(a: MainActivity, rel: Release) {
        val col = W.vCol(a)
        col.addView(W.text(a, "RadarForge ${rel.version}", 20f, C.text, true), lp(top = a.dpi(4f)))
        val size = if (rel.apkSize > 0) " · ${mb(rel.apkSize)} download" else ""
        col.addView(W.note(a, "You have ${installed(a)}$size"))
        if (rel.notes.isNotBlank()) {
            col.addView(W.section(a, "What's new"))
            col.addView(W.text(a, rel.notes, 14f, C.text).apply { setLineSpacing(0f, 1.15f); setTextIsSelectable(true) }, lp())
        }
        if (rel.apkUrl.isEmpty()) {
            col.addView(W.note(a, "This version has no app file attached yet. Try again later, or open its page on GitHub."), lp(top = a.dpi(10f)))
            col.addView(W.button(a, "Open the release page", primary = true) { openPage(a, rel) }, lp(top = a.dpi(12f)))
        } else {
            val buttons = W.hRow(a)
            buttons.addView(W.button(a, "Update now", primary = true) { download(a, rel) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = a.dpi(8f) })
            buttons.addView(W.button(a, "Later") { a.sheets.close() }, LinearLayout.LayoutParams(0, -2, 1f))
            col.addView(buttons, lp(top = a.dpi(14f)))
            col.addView(W.note(a, "Your settings, colour tables and favourites are kept. Android asks you to confirm the install."), lp(top = a.dpi(8f)))
        }
        col.addView(W.listRow(a, "Skip this version", "Don't offer ${rel.version} again (Settings → Updates still finds it)", null) {
            a.prefs.skippedVersion = rel.version
            a.sheets.close()
        }, lp(top = a.dpi(8f)))
        col.addView(W.listRow(a, "Release page", "github.com/${Releases.REPO}", "›") { openPage(a, rel) }, lp())
        a.sheets.show("Update available", col)
    }

    private fun openPage(a: MainActivity, rel: Release) {
        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.pageUrl))) } catch (_: Exception) { a.toast(rel.pageUrl) }
    }

    // ------------------------------------------------------------------ downloading
    private fun download(a: MainActivity, rel: Release) {
        val col = W.vCol(a)
        val label = W.text(a, "Starting…", 14f, C.text)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = true }
        col.addView(W.note(a, "RadarForge ${rel.version}"))
        col.addView(bar, lp(top = a.dpi(10f)))
        col.addView(label, lp(top = a.dpi(6f)))
        col.addView(W.button(a, "Cancel") { cancelled = true; a.sheets.close() }, lp(top = a.dpi(14f)))
        col.addView(W.note(a, "You can close this – the install starts when the download finishes."), lp(top = a.dpi(8f)))
        a.sheets.show("Downloading update", col)
        if (downloading != null) return
        downloading = rel
        cancelled = false
        val app = a.applicationContext
        val out = File(dir(a), "RadarForge-Android-${rel.version}.apk")
        Thread({
            var err: String? = null
            try {
                fetch(rel, out) { got, total ->
                    main.post {
                        if (total > 0) { bar.isIndeterminate = false; bar.progress = (got * 1000 / total).toInt() }
                        label.text = if (total > 0) "${mb(got)} of ${mb(total)}" else mb(got)
                    }
                }
                verify(app, out, rel)
            } catch (e: Exception) {
                out.delete()
                err = if (cancelled) null else e.message ?: e.javaClass.simpleName
            }
            main.post {
                downloading = null
                if (cancelled) { RfLog.i("update download cancelled"); return@post }
                if (err != null) {
                    RfLog.w("update download failed: $err")
                    Toast.makeText(app, "The update didn't download: $err", Toast.LENGTH_LONG).show()
                    if (col.isAttachedToWindow) a.sheets.close()
                    return@post
                }
                RfLog.i("update ${rel.version} downloaded (${out.length()} bytes)")
                if (col.isAttachedToWindow) a.sheets.close()
                if (!a.isDestroyed) install(a, out) else installFile(app, out)
            }
        }, "update-download").apply { isDaemon = true }.start()
    }

    private fun fetch(rel: Release, out: File, progress: (Long, Long) -> Unit) {
        out.parentFile?.mkdirs()
        val part = File(out.path + ".part")
        val c = Net.open(rel.apkUrl, timeoutMs = 30_000, accept = "application/octet-stream")
        try {
            val total = if (rel.apkSize > 0) rel.apkSize else c.contentLengthLong
            Net.stream(c).use { input ->
                part.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    var last = 0L
                    while (true) {
                        if (cancelled) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        got += n
                        val now = System.currentTimeMillis()
                        if (now - last > 150) { last = now; progress(got, total) }
                    }
                    progress(got, total)
                    if (rel.apkSize > 0 && got != rel.apkSize) throw IOException("incomplete download (${mb(got)} of ${mb(rel.apkSize)})")
                }
            }
        } finally {
            c.disconnect()
        }
        if (!part.renameTo(out)) throw IOException("couldn't save the download")
    }

    /** The file is RadarForge, and newer than what's installed. */
    private fun verify(ctx: Context, f: File, rel: Release) {
        val info = ctx.packageManager.getPackageArchiveInfo(f.path, 0) ?: throw IOException("the file isn't a readable app")
        if (info.packageName != ctx.packageName) throw IOException("the file is a different app (${info.packageName})")
        val ver = info.versionName ?: rel.version
        if (!Releases.isNewer(ver, installed(ctx))) throw IOException("the file is version $ver, not newer than ${installed(ctx)}")
    }

    /** Removes downloads left over from earlier updates. */
    private fun cleanOld(ctx: Context) {
        if (downloading != null || waitingForPermission != null) return
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ installing
    private fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    private fun install(a: MainActivity, f: File) {
        if (canInstall(a)) { installFile(a.applicationContext, f); return }
        waitingForPermission = f
        val col = W.vCol(a)
        col.addView(W.note(a, "Android needs your OK once before RadarForge can install its own updates. " +
            "Turn on \"Allow from this source\" on the next screen, then come back – the update installs straight away."))
        col.addView(W.button(a, "Open the setting", primary = true) {
            a.sheets.close()
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            } catch (_: Exception) {
                a.toast("Allow RadarForge under Settings → Apps → Special app access → Install unknown apps")
            }
        }, lp(top = a.dpi(12f)))
        col.addView(W.button(a, "Not now") { waitingForPermission = null; a.sheets.close() }, lp(top = a.dpi(8f)))
        a.sheets.show("Allow updates", col)
    }

    /** Hands [f] to Android's package installer; [Status] hears how it went. */
    private fun installFile(ctx: Context, f: File) {
        try {
            registerReceiver(ctx)
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            params.setSize(f.length())
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                f.inputStream().use { input ->
                    s.openWrite("base.apk", 0, f.length()).use { o ->
                        input.copyTo(o, 64 * 1024)
                        s.fsync(o)
                    }
                }
                val intent = Intent(ACTION_STATUS).setPackage(ctx.packageName)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE   // the installer adds its result
                s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
            }
            RfLog.i("update handed to the package installer (session $id)")
        } catch (e: Exception) {
            RfLog.e("update install failed", e)
            Toast.makeText(ctx, "Couldn't start the install: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun registerReceiver(ctx: Context) {
        if (receiverOn) return
        val app = ctx.applicationContext
        val filter = IntentFilter(ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(Status, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(Status, filter)
        receiverOn = true
    }

    private object Status : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            RfLog.i("update install status $status ${msg ?: ""}")
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { ctx.startActivity(confirm) } catch (e: Exception) { RfLog.e("couldn't show the install prompt", e) }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> Unit      // (this process is replaced by the new version)
                else -> {
                    val text = when (status) {
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled"
                        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                            "The update is signed differently from the installed app. Uninstall RadarForge, then install the new version from GitHub."
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free space for the update"
                        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the update"
                        else -> "The update didn't install${if (msg.isNullOrBlank()) "" else ": $msg"}"
                    }
                    Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
}
