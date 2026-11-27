package com.mohammad.pcultimate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * In-app updates from the GitHub releases: checks for a newer build, downloads the APK into the
 * app's own cache (never Downloads), hands it to the system installer, and deletes it afterwards.
 */
object Updater {
    private const val API = "https://api.github.com/repos/Mohammad821s/PC-Ultimate-Controller/releases/latest"
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 7
    const val EXTRA_OPEN = "open_update"

    data class Release(val build: Int, val url: String, val size: Long, val page: String)

    var available by mutableStateOf<Release?>(null)
    /** 0..1 while downloading, null otherwise */
    var progress by mutableStateOf<Float?>(null)
    var status by mutableStateOf("")
    var dismissed by mutableStateOf(false)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    fun installed(c: Context): Int = try {
        val i = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) i.longVersionCode.toInt() else @Suppress("DEPRECATION") i.versionCode
    } catch (e: Exception) { 0 }

    private fun apkFile(c: Context) = File(c.cacheDir, "update.apk")

    /** Leftover from an update that finished (or failed) while the app was gone. */
    fun cleanup(c: Context) { apkFile(c).delete() }

    /** Checks at most every 3 hours unless [force]. [onDone] runs on the main thread. */
    fun check(c: Context, force: Boolean = false, notify: Boolean = false, onDone: (() -> Unit)? = null) {
        val app = c.applicationContext
        val p = app.getSharedPreferences("pc", Context.MODE_PRIVATE)
        val last = p.getLong("upd_check", 0)
        if (!force && System.currentTimeMillis() - last < 3 * 3600_000L) { onDone?.let { main.post(it) }; return }
        if (force) main.post { status = "Checking…" }
        thread(name = "update-check") {
            var found: Release? = null
            var error = ""
            try {
                http.newCall(Request.Builder().url(API).header("Accept", "application/vnd.github+json").build()).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                    val j = JSONObject(r.body!!.string())
                    val build = j.optString("tag_name").removePrefix("build-").toIntOrNull() ?: 0
                    val assets = j.optJSONArray("assets")
                    for (i in 0 until (assets?.length() ?: 0)) {
                        val a = assets!!.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            found = Release(build, a.optString("browser_download_url"), a.optLong("size"), j.optString("html_url"))
                        }
                    }
                }
                p.edit().putLong("upd_check", System.currentTimeMillis()).apply()
            } catch (e: Exception) { error = "Couldn't check for updates" }
            val mine = installed(app)
            val newer = found?.takeIf { it.build > mine }
            main.post {
                available = newer
                status = when {
                    error.isNotEmpty() -> if (force) error else ""
                    newer != null -> ""
                    force -> "You're on the latest version"
                    else -> ""
                }
                if (newer != null && notify && p.getInt("upd_notified", 0) != newer.build) {
                    p.edit().putInt("upd_notified", newer.build).apply()
                    notifyUpdate(app, newer)
                }
                onDone?.invoke()
            }
        }
    }

    /** Every 6 hours while the background service runs, so updates are noticed without opening the app. */
    fun startPeriodic(c: Context) {
        val app = c.applicationContext
        val tick = object : Runnable {
            override fun run() {
                check(app, notify = true)
                main.postDelayed(this, 6 * 3600_000L)
            }
        }
        main.postDelayed(tick, 20_000L)
    }

    private fun notifyUpdate(c: Context, r: Release) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 70,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_OPEN, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIF_ID, Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Update ready")
            .setContentText("Build ${r.build} is available. Tap to install.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    /** True if the app may install; otherwise opens the one-time "install unknown apps" switch. */
    fun canInstall(c: Context): Boolean {
        if (c.packageManager.canRequestPackageInstalls()) return true
        status = "Allow installing updates, then come back and tap Update again"
        c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }

    fun install(c: Context) {
        val r = available ?: return
        if (busy) return
        val app = c.applicationContext
        if (!canInstall(c)) return
        busy = true
        progress = 0f
        status = "Downloading…"
        app.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        thread(name = "update-download") {
            val f = apkFile(app)
            try {
                http.newCall(Request.Builder().url(r.url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    val body = resp.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: r.size
                    f.outputStream().use { out ->
                        body.byteStream().use { inp ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPost = 0L
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastPost > 120) {
                                    lastPost = now
                                    val pr = if (total > 0) done.toFloat() / total else 0f
                                    main.post { progress = pr.coerceIn(0f, 1f) }
                                }
                            }
                        }
                    }
                }
                main.post { progress = 1f; status = "Installing…" }
                commit(app, f)
            } catch (e: Exception) {
                f.delete()
                busy = false
                main.post { progress = null; status = "Download failed. Check your internet and try again." }
            }
        }
    }

    private fun commit(c: Context, f: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            f.delete()   // the session has its own copy now
            val cb = PendingIntent.getBroadcast(c, id,
                Intent(c, UpdateReceiver::class.java).setAction("com.mohammad.pcultimate.UPDATE_STATUS"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(cb.intentSender)
        }
    }

    internal fun onStatus(c: Context, i: Intent) {
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
                main.post { status = "Confirm the update" }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                busy = false
                main.post { progress = null; available = null; status = "Updated" }
            }
            else -> {
                busy = false
                cleanup(c)
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                main.post {
                    progress = null
                    status = if (st == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled"
                    else "Update failed ($st) $msg".trim()
                }
            }
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Updater.onStatus(c, i)
}
