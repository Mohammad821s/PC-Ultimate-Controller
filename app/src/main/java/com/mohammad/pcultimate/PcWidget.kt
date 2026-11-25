package com.mohammad.pcultimate

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/** Home-screen widget: shortcuts, media buttons and the floating-touchpad button. */
class PcWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    private fun views(c: Context): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget)
        fun bind(viewId: Int, code: Int, action: String, extra: String?) {
            val pi = PendingIntent.getForegroundService(
                c, code, PcService.intent(c, action, extra),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(viewId, pi)
        }
        bind(R.id.btn_youtube, 100, PcService.ACTION_SHORTCUT, "youtube")
        bind(R.id.btn_netflix, 101, PcService.ACTION_SHORTCUT, "netflix")
        bind(R.id.btn_crunchy, 102, PcService.ACTION_SHORTCUT, "crunchyroll")
        bind(R.id.btn_fortnite, 103, PcService.ACTION_SHORTCUT, "fortnite")
        bind(R.id.btn_back, 104, PcService.ACTION_MEDIA, "left")
        bind(R.id.btn_play, 105, PcService.ACTION_MEDIA, "playpause")
        bind(R.id.btn_next, 106, PcService.ACTION_MEDIA, "next")
        bind(R.id.btn_fwd, 108, PcService.ACTION_MEDIA, "right")
        bind(R.id.btn_touch, 107, PcService.ACTION_TOUCHPAD, null)
        return v
    }
}
