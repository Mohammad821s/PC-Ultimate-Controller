package com.mohammad.pcultimate

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Keeps the connection to the PC alive, shows the media notification
 * (which also appears in the Now Bar / quick-settings media player),
 * runs widget actions and hosts the floating touchpad.
 */
class PcService : Service(), PcClient.Listener {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_SHORTCUT = "shortcut"
        const val ACTION_MEDIA = "media"
        const val ACTION_TOUCHPAD = "touchpad"
        const val ACTION_STOP = "stop"
        const val EXTRA = "extra"
        private const val CHANNEL = "pc"
        private const val NOTIF_ID = 1

        fun intent(c: Context, action: String, extra: String? = null): Intent =
            Intent(c, PcService::class.java).setAction(action).also { if (extra != null) it.putExtra(EXTRA, extra) }
    }

    private lateinit var session: MediaSession
    private var overlay: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayPad: TouchpadView? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PcClient.init(this)
        Updater.startPeriodic(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "التحكم بالكمبيوتر", NotificationManager.IMPORTANCE_LOW)
        )
        session = MediaSession(this, "PcUltimate").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = PcClient.media("playpause")
                override fun onPause() = PcClient.media("playpause")
                override fun onSkipToNext() = PcClient.media("next")
                override fun onSkipToPrevious() = PcClient.media("prev")
            })
            isActive = true
        }
        // Keeps Wi-Fi out of power-save while the service runs, so pointer packets are not delayed.
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PcUltimate:input")
            ?.apply { setReferenceCounted(false); acquire() }
        PcClient.listeners.add(this)
        AppSettings.load(this)
        goForeground()
        Widgets.updateAll(this)
        PcClient.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val extra = intent?.getStringExtra(EXTRA)
        when (intent?.action) {
            ACTION_SHORTCUT -> if (extra != null) PcClient.shortcut(extra)
            ACTION_MEDIA -> if (extra != null) PcClient.media(extra)
            ACTION_TOUCHPAD -> toggleOverlay()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> PcClient.connect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        PcClient.listeners.remove(this)
        session.release()
        try { wifiLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onStatus(connected: Boolean, text: String) = refreshNotification()
    override fun onMedia(m: MediaState?) {
        refreshNotification()
        Widgets.updateAll(this)
    }
    override fun onShortcuts(items: List<Shortcut>) = Widgets.updateAll(this)

    // ------------------------------------------------------------ notification

    private fun pending(code: Int, action: String, extra: String): PendingIntent =
        PendingIntent.getService(
            this, code, intent(this, action, extra),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val m = PcClient.media
        val playing = m?.playing == true
        val title = m?.title?.takeIf { it.isNotBlank() } ?: "PC Ultimate Controller"
        val sub = when {
            m != null -> listOf(m.artist, m.app).filter { it.isNotBlank() }.joinToString(" • ")
            else -> PcClient.statusText
        }

        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
                .apply { m?.thumb?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .build()
        )

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(icon: Int, label: String, code: Int, a: String) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), label, pending(code, ACTION_MEDIA, a)).build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(sub)
            .setLargeIcon(m?.thumb)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "السابق", 10, "prev"))
            .addAction(
                action(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    "تشغيل/إيقاف", 11, "playpause"
                )
            )
            .addAction(action(android.R.drawable.ic_media_next, "التالي", 12, "next"))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun goForeground() {
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    // ------------------------------------------------------------ floating touchpad

    private fun toggleOverlay() {
        if (overlay != null) {
            removeOverlay()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "افتح التطبيق وفعّل إذن الظهور فوق التطبيقات", Toast.LENGTH_LONG).show()
            return
        }
        showOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val dp = { v: Float -> Ui.dp(this, v) }
        val accent = currentAccent(this)
        val tile = 0xFF1F2632.toInt()
        val fg = 0xFFC9D1DD.toInt()

        val params = WindowManager.LayoutParams(
            dp(330f), dp(300f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dp(30f)
            y = dp(260f)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(this@PcService, 0xEB11151C.toInt(), 28f).apply {
                setStroke(dp(1f), 0x14FFFFFF)
            }
            elevation = dp(12f).toFloat()
            val p = dp(10f)
            setPadding(p, p, p, p)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val handle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, 0, 0)
        }
        handle.addView(View(this).apply { background = Ui.round(this@PcService, 0xFF4A5363.toInt(), 3f) },
            LinearLayout.LayoutParams(dp(44f), dp(5f)))
        handle.addView(TextView(this).apply {
            text = PcState.pcName.ifBlank { "PC Ultimate Controller" }
            setTextColor(0xFF8A94A6.toInt()); textSize = 12.5f; maxLines = 1
            setPadding(dp(12f), 0, 0, 0)
        })
        var startX = 0f; var startY = 0f; var origX = 0; var origY = 0
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX; startY = ev.rawY; origX = params.x; origY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = origX + (ev.rawX - startX).toInt()
                    params.y = origY + (ev.rawY - startY).toInt()
                    wm.updateViewLayout(box, params)
                }
            }
            true
        }
        val close = Ui.iconButton(this, R.drawable.ic_close, "Close floating touchpad", tile, fg, 10f) { removeOverlay() }
        header.addView(handle, LinearLayout.LayoutParams(0, dp(36f), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(34f), dp(34f)))

        val pad = TouchpadView(this).apply {
            AppSettings.load(this@PcService)
            sensitivity = AppSettings.sensitivity
            naturalScroll = AppSettings.naturalScroll
            haptics = AppSettings.haptics
            cornerRadius = dp(20f).toFloat()
            hint = "Touchpad"
            setColors(0xFF161B23.toInt(), 0xFF252D39.toInt(), 0xFF6D7789.toInt(), accent)
        }
        overlayPad = pad

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val lp = { LinearLayout.LayoutParams(0, dp(46f), 1f).apply { setMargins(dp(3f), dp(10f), dp(3f), 0) } }
        controls.addView(Ui.button(this, "L", tile, fg) { PcClient.click("left") }, lp())
        controls.addView(Ui.iconButton(this, R.drawable.ic_back10, "Back", tile, fg) { PcClient.media("left") }, lp())
        controls.addView(Ui.iconButton(this, R.drawable.ic_pause, "Play or pause", accent, 0xFFFFFFFF.toInt()) { PcClient.media("playpause") }, lp())
        controls.addView(Ui.iconButton(this, R.drawable.ic_fwd10, "Forward", tile, fg) { PcClient.media("right") }, lp())
        controls.addView(Ui.button(this, "R", tile, fg) { PcClient.click("right") }, lp())

        box.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f)))
        box.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8f) })
        box.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        box.alpha = 0f
        box.scaleX = 0.96f
        box.scaleY = 0.96f
        wm.addView(box, params)
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
            .setInterpolator(android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f)).start()
        overlay = box
        overlayParams = params
    }

    private fun removeOverlay() {
        overlay?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
        overlayParams = null
        overlayPad = null
    }
}
