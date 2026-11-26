package com.mohammad.pcultimate

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/** Medium (4 x 2). Keeps the original class name so widgets placed earlier keep working. */
class PcWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.updateAll(context)
}

class WidgetSmall : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.updateAll(context)
}

class WidgetLarge : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.updateAll(context)
}

object Widgets {
    private enum class Kind { SMALL, MEDIUM, LARGE }

    fun updateAll(c: Context) {
        PcClient.init(c)
        val m = AppWidgetManager.getInstance(c)
        listOf(
            WidgetSmall::class.java to Kind.SMALL,
            PcWidget::class.java to Kind.MEDIUM,
            WidgetLarge::class.java to Kind.LARGE,
        ).forEach { (cls, kind) ->
            val ids = m.getAppWidgetIds(ComponentName(c, cls))
            if (ids.isNotEmpty()) {
                val views = try { build(c, kind) } catch (e: Exception) { null }
                if (views != null) ids.forEach { m.updateAppWidget(it, views) }
            }
        }
    }

    private fun pending(c: Context, code: Int, action: String, extra: String?): PendingIntent =
        PendingIntent.getForegroundService(
            c, code, PcService.intent(c, action, extra),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 900, android.content.Intent(c, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun build(c: Context, kind: Kind): RemoteViews {
        val layout = when (kind) {
            Kind.SMALL -> R.layout.widget_small
            Kind.MEDIUM -> R.layout.widget_medium
            Kind.LARGE -> R.layout.widget_large
        }
        val v = RemoteViews(c.packageName, layout)
        val media = PcClient.media
        val d = c.resources.displayMetrics.density
        val artSize = ((if (kind == Kind.LARGE) 64 else 50) * d).toInt()

        v.setTextViewText(R.id.w_title, media?.let { it.title.ifBlank { it.app } } ?: "Nothing playing")
        if (kind != Kind.SMALL) {
            v.setTextViewText(R.id.w_artist,
                if (media == null) "Play something on your PC" else listOf(media.artist, media.app).filter { it.isNotBlank() }.joinToString(", "))
        }
        v.setImageViewBitmap(R.id.w_art, artBitmap(c, media?.thumb, artSize))
        v.setImageViewResource(R.id.w_play, if (media?.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
        v.setOnClickPendingIntent(R.id.w_play, pending(c, 200, PcService.ACTION_MEDIA, "playpause"))
        v.setOnClickPendingIntent(R.id.w_art, openApp(c))

        when (kind) {
            Kind.SMALL -> {
                v.setOnClickPendingIntent(R.id.w_next, pending(c, 201, PcService.ACTION_MEDIA, "next"))
            }
            Kind.MEDIUM, Kind.LARGE -> {
                if (kind == Kind.LARGE) {
                    v.setOnClickPendingIntent(R.id.w_back, pending(c, 202, PcService.ACTION_MEDIA, "left"))
                    v.setOnClickPendingIntent(R.id.w_prev, pending(c, 203, PcService.ACTION_MEDIA, "prev"))
                    v.setOnClickPendingIntent(R.id.w_next, pending(c, 204, PcService.ACTION_MEDIA, "next"))
                    v.setOnClickPendingIntent(R.id.w_fwd, pending(c, 205, PcService.ACTION_MEDIA, "right"))
                }
                v.setOnClickPendingIntent(R.id.w_pad, pending(c, 206, PcService.ACTION_TOUCHPAD, null))
                val slots = listOf(R.id.w_sc1, R.id.w_sc2, R.id.w_sc3, R.id.w_sc4)
                val items = PcState.shortcuts
                val tileSize = ((if (kind == Kind.LARGE) 56 else 48) * d).toInt()
                slots.forEachIndexed { i, id ->
                    val s = items.getOrNull(i)
                    if (s == null) {
                        v.setViewVisibility(id, android.view.View.INVISIBLE)
                    } else {
                        v.setViewVisibility(id, android.view.View.VISIBLE)
                        v.setImageViewBitmap(id, shortcutBitmap(c, s, tileSize))
                        v.setContentDescription(id, s.name)
                        v.setOnClickPendingIntent(id, pending(c, 300 + i, PcService.ACTION_SHORTCUT, s.id))
                    }
                }
            }
        }
        return v
    }

    private val tints = intArrayOf(
        0xFFE5534B.toInt(), 0xFFF0883E.toInt(), 0xFFD29922.toInt(), 0xFF3FB950.toInt(),
        0xFF2EA6A6.toInt(), 0xFF4C8DFF.toInt(), 0xFF8B6CEF.toInt(), 0xFFDB61A2.toInt()
    )

    private fun roundRectPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    fun shortcutBitmap(c: Context, s: Shortcut, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = size * 0.32f
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val icon = s.icon
        if (icon != null) {
            canvas.drawRoundRect(rect, r, r, roundRectPaint(ContextCompat.getColor(c, R.color.w_tile)))
            val inset = size * 0.2f
            canvas.drawBitmap(icon, null, RectF(inset, inset, size - inset, size - inset), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val tint = tints[(s.name.hashCode() and 0x7fffffff) % tints.size]
            canvas.drawRoundRect(rect, r, r, roundRectPaint((tint and 0x00FFFFFF) or 0x33000000))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tint; textAlign = Paint.Align.CENTER; textSize = size * 0.38f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val y = size / 2f - (p.descent() + p.ascent()) / 2f
            canvas.drawText(s.name.take(1).uppercase(), size / 2f, y, p)
        }
        return bmp
    }

    private fun artBitmap(c: Context, art: Bitmap?, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = size * 0.26f
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        if (art != null) {
            val shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = maxOf(size.toFloat() / art.width, size.toFloat() / art.height)
            val mtx = Matrix().apply {
                setScale(scale, scale)
                postTranslate((size - art.width * scale) / 2f, (size - art.height * scale) / 2f)
            }
            shader.setLocalMatrix(mtx)
            canvas.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        } else {
            canvas.drawRoundRect(rect, r, r, roundRectPaint(ContextCompat.getColor(c, R.color.w_art_bg)))
            val note = ContextCompat.getDrawable(c, R.drawable.ic_music)?.mutate()
            if (note != null) {
                note.setTint(ContextCompat.getColor(c, R.color.w_art_fg))
                val pad = (size * 0.28f).toInt()
                note.setBounds(pad, pad, size - pad, size - pad)
                note.draw(canvas)
            }
        }
        return bmp
    }
}
