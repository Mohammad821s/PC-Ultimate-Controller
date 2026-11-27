package com.mohammad.pcultimate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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

    fun installed(c: Context): Int = try {
        val i = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) i.longVersionCode.toInt() else @Suppress("DEPRECATION") i.versionCode
    } catch (e: Exception) { 0 }

    /** Removes the APK an earlier build (28/29) may have left in the app cache. */
    fun cleanup(c: Context) { java.io.File(c.cacheDir, "update.apk").delete() }

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
            .setContentText("Build ${r.build} is available. Tap to download.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    /**
     * Opens the APK download in the browser. The app itself never downloads or installs code:
     * Play Protect treats apps that do that as harmful.
     */
    fun install(c: Context) {
        val r = available ?: return
        c.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        status = "Downloading in your browser. Open the file when it finishes."
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
