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
        goForeground()
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
    override fun onMedia(m: MediaState?) = refreshNotification()

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

        val params = WindowManager.LayoutParams(
            dp(310f), dp(250f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dp(24f)
            y = dp(220f)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(this@PcService, 0xF0101620.toInt(), 22f)
            val p = dp(8f)
            setPadding(p, p, p, p)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val handle = TextView(this).apply {
            text = "⠿  اسحب من هنا"
            setTextColor(Ui.MUTED)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), 0, 0, 0)
        }
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
        val close = Ui.button(this, "✕") { removeOverlay() }
        header.addView(handle, LinearLayout.LayoutParams(0, dp(40f), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(44f), dp(40f)))

        val pad = TouchpadView(this).apply {
            sensitivity = Prefs.sens(this@PcService)
            hint = "تاتش باد"
        }
        overlayPad = pad

        val clicks = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val lp = { LinearLayout.LayoutParams(0, dp(40f), 1f).apply { setMargins(dp(3f), dp(6f), dp(3f), 0) } }
        clicks.addView(Ui.button(this, "يسار") { PcClient.click("left") }, lp())
        clicks.addView(Ui.button(this, "⏯") { PcClient.media("playpause") }, lp())
        clicks.addView(Ui.button(this, "يمين") { PcClient.click("right") }, lp())

        box.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40f)))
        box.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(6f)
        })
        box.addView(clicks, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        wm.addView(box, params)
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
