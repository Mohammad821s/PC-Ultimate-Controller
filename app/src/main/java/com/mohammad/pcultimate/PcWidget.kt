package com.mohammad.pcultimate

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

/**
 * All three widget entries share one adaptive builder: whatever size a widget is resized to,
 * it picks the layout that fits (small, bar, medium, large) and fills it. Look and content are
 * per widget (WidgetStyle). The class names stay the same so widgets placed earlier keep working.
 */
open class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        ids.forEach { Widgets.update(context, manager, it) }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) =
        Widgets.update(context, manager, id)

    override fun onDeleted(context: Context, ids: IntArray) = ids.forEach { WidgetStyle.delete(context, it) }
}

/** Medium (4 x 2) */
class PcWidget : BaseWidget()
class WidgetSmall : BaseWidget()
class WidgetLarge : BaseWidget()

object Widgets {
    private enum class Kind { SMALL, BAR, MEDIUM, LARGE }

    private val providers = listOf(WidgetSmall::class.java, PcWidget::class.java, WidgetLarge::class.java)

    fun allIds(c: Context): List<Int> {
        val m = AppWidgetManager.getInstance(c)
        return providers.flatMap { m.getAppWidgetIds(ComponentName(c, it)).toList() }
    }

    fun updateAll(c: Context) {
        PcClient.init(c)
        val m = AppWidgetManager.getInstance(c)
        allIds(c).forEach { update(c, m, it) }
    }

    fun update(c: Context, m: AppWidgetManager, id: Int) {
        PcClient.init(c)
        val style = WidgetStyle.load(c, id)
        val views = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // the launcher shows the biggest of these that fits the widget's current size
                val sizes = listOf(
                    SizeF(100f, 100f), SizeF(150f, 150f),
                    SizeF(200f, 48f), SizeF(300f, 48f),
                    SizeF(200f, 110f), SizeF(290f, 110f), SizeF(370f, 110f),
                    SizeF(200f, 210f), SizeF(290f, 210f), SizeF(370f, 210f), SizeF(290f, 300f), SizeF(370f, 300f),
                )
                RemoteViews(sizes.associateWith { build(c, id, style, it.width, it.height) })
            } else {
                val o = m.getAppWidgetOptions(id)
                val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).toFloat()
                val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110).toFloat()
                build(c, id, style, w, h)
            }
        } catch (e: Exception) { null } ?: return
        m.updateAppWidget(id, views)
    }

    private fun kindFor(w: Float, h: Float) = when {
        h < 90 -> Kind.BAR
        w < 180 -> Kind.SMALL
        h < 200 -> Kind.MEDIUM
        else -> Kind.LARGE
    }

    // ------------------------------------------------------------ intents

    private fun pending(c: Context, code: Int, action: String, extra: String?): PendingIntent =
        PendingIntent.getForegroundService(
            c, code, PcService.intent(c, action, extra),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun openApp(c: Context, code: Int): PendingIntent = PendingIntent.getActivity(
        c, code, Intent(c, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // ------------------------------------------------------------ build

    /** Same views the home screen gets, for the live preview in WidgetConfigActivity. */
    fun preview(c: Context, s: WidgetStyle, wDp: Float, hDp: Float): RemoteViews = build(c, 0, s, wDp, hDp)

    private fun build(c: Context, id: Int, s: WidgetStyle, wDp: Float, hDp: Float): RemoteViews {
        val kind = kindFor(wDp, hDp)
        val layout = when (kind) {
            Kind.SMALL -> R.layout.widget_small
            Kind.BAR -> R.layout.widget_bar
            Kind.MEDIUM -> R.layout.widget_medium
            Kind.LARGE -> R.layout.widget_large
        }
        val col = WidgetPalette.resolve(c, s)
        val v = RemoteViews(c.packageName, layout)
        val d = c.resources.displayMetrics.density
        val media = PcClient.media
        val code = id * 100

        // background (drawn, so any color, opacity and the album-art style work everywhere)
        v.setImageViewBitmap(R.id.w_bg, background(c, col, media?.thumb, wDp, hDp, d))

        // now playing
        val artDp = when (kind) { Kind.LARGE -> 72; Kind.MEDIUM -> 52; Kind.SMALL -> 44; Kind.BAR -> 40 }
        v.setImageViewBitmap(R.id.w_art, artBitmap(c, col, media?.thumb, (artDp * d).toInt()))
        v.setOnClickPendingIntent(R.id.w_art, openApp(c, code + 99))
        v.setTextViewText(R.id.w_title, media?.let { it.title.ifBlank { it.app } } ?: "Nothing playing")
        v.setTextColor(R.id.w_title, col.text)
        run {
            val sub = if (media == null) "Play something on your PC"
            else listOf(media.artist, media.app).filter { it.isNotBlank() }.joinToString(", ")
            v.setTextViewText(R.id.w_artist, sub)
            v.setTextColor(R.id.w_artist, col.muted)
        }

        val playIcon = if (media?.playing == true) R.drawable.ic_pause else R.drawable.ic_play
        val px = { dp: Int -> (dp * d).toInt() }

        fun button(viewId: Int, icon: Int, sizeDp: Int, accent: Boolean, round: Boolean, action: String, extra: String?, n: Int) {
            v.setImageViewBitmap(viewId, buttonBitmap(c, icon, px(sizeDp), if (accent) col.accent else col.tile,
                if (accent) col.onAccent else col.text, round))
            v.setOnClickPendingIntent(viewId, pending(c, code + n, action, extra))
        }

        when (kind) {
            Kind.SMALL -> {
                button(R.id.w_prev, R.drawable.ic_prev, 44, false, false, PcService.ACTION_MEDIA, "prev", 1)
                button(R.id.w_play, playIcon, 52, true, false, PcService.ACTION_MEDIA, "playpause", 2)
                button(R.id.w_next, R.drawable.ic_next, 44, false, false, PcService.ACTION_MEDIA, "next", 3)
            }
            Kind.BAR -> {
                button(R.id.w_prev, R.drawable.ic_prev, 40, false, true, PcService.ACTION_MEDIA, "prev", 1)
                button(R.id.w_play, playIcon, 40, true, true, PcService.ACTION_MEDIA, "playpause", 2)
                button(R.id.w_next, R.drawable.ic_next, 40, false, true, PcService.ACTION_MEDIA, "next", 3)
                if (s.showPad) button(R.id.w_pad, R.drawable.ic_pad, 40, false, true, PcService.ACTION_TOUCHPAD, null, 4)
                else v.setViewVisibility(R.id.w_pad, View.GONE)
            }
            Kind.MEDIUM, Kind.LARGE -> {
                button(R.id.w_play, playIcon, if (kind == Kind.LARGE) 56 else 46, true, true, PcService.ACTION_MEDIA, "playpause", 2)
                if (s.showControls) {
                    val h = if (kind == Kind.LARGE) 48 else 42
                    button(R.id.w_back, R.drawable.ic_back10, h, false, false, PcService.ACTION_MEDIA, "left", 5)
                    button(R.id.w_prev, R.drawable.ic_prev, h, false, false, PcService.ACTION_MEDIA, "prev", 1)
                    button(R.id.w_play2, playIcon, h, true, false, PcService.ACTION_MEDIA, "playpause", 6)
                    button(R.id.w_next, R.drawable.ic_next, h, false, false, PcService.ACTION_MEDIA, "next", 3)
                    button(R.id.w_fwd, R.drawable.ic_fwd10, h, false, false, PcService.ACTION_MEDIA, "right", 7)
                    // the big play button in the header is enough on the medium size
                    if (kind == Kind.MEDIUM) v.setViewVisibility(R.id.w_play2, View.GONE)
                } else v.setViewVisibility(R.id.w_row_ctl, View.GONE)

                if (kind == Kind.LARGE) {
                    if (s.showVolume) {
                        button(R.id.w_voldown, R.drawable.ic_vol_low, 40, false, false, PcService.ACTION_MEDIA, "voldown", 8)
                        button(R.id.w_mute, R.drawable.ic_vol_off, 40, false, false, PcService.ACTION_MEDIA, "mute", 9)
                        button(R.id.w_volup, R.drawable.ic_vol_high, 40, false, false, PcService.ACTION_MEDIA, "volup", 10)
                    } else v.setViewVisibility(R.id.w_row_vol, View.GONE)
                }
                shortcuts(c, v, s, col, kind == Kind.LARGE, wDp, hDp, code, px)
            }
        }
        return v
    }

    private fun shortcuts(c: Context, v: RemoteViews, s: WidgetStyle, col: WidgetColors, large: Boolean,
                          wDp: Float, hDp: Float, code: Int, px: (Int) -> Int) {
        val row1 = listOf(R.id.w_sc1, R.id.w_sc2, R.id.w_sc3, R.id.w_sc4, R.id.w_sc5, R.id.w_sc6)
        val row2 = listOf(R.id.w_sc7, R.id.w_sc8, R.id.w_sc9, R.id.w_sc10, R.id.w_sc11, R.id.w_sc12)
        if (!s.showShortcuts && !s.showPad) {
            v.setViewVisibility(R.id.w_row_sc, View.GONE)
            if (large) v.setViewVisibility(R.id.w_row_sc2, View.GONE)
            return
        }
        // as many columns as fit nicely: about 60dp each
        val cols = ((wDp - 28f) / 60f).toInt().coerceIn(3, 6) + 1
        val rows = if (large && hDp >= 280f) 2 else 1
        val all = PcState.shortcuts
        val chosen = if (s.shortcutIds.isEmpty()) all else s.shortcutIds.mapNotNull { sid -> all.firstOrNull { it.id == sid } }
        val items = if (s.showShortcuts) chosen else emptyList()
        val capacity = cols * rows - (if (s.showPad) 1 else 0)
        val shown = items.take(capacity)
        val tile = px(if (large) 54 else 46)

        val slots = (row1.take(cols) + if (rows == 2) row2.take(cols) else emptyList())
        (row1 + row2).forEach { v.setViewVisibility(it, View.GONE) }
        v.setViewVisibility(R.id.w_pad, View.GONE)
        if (large) v.setViewVisibility(R.id.w_pad2, View.GONE)
        if (rows < 2 && large) v.setViewVisibility(R.id.w_row_sc2, View.GONE)

        shown.forEachIndexed { i, sc ->
            val viewId = slots[i]
            v.setViewVisibility(viewId, View.VISIBLE)
            v.setImageViewBitmap(viewId, shortcutBitmap(c, sc, tile, col))
            v.setContentDescription(viewId, sc.name)
            v.setOnClickPendingIntent(viewId, pending(c, code + 20 + i, PcService.ACTION_SHORTCUT, sc.id))
        }
        if (s.showPad) {
            val padView = if (rows == 2) R.id.w_pad2 else R.id.w_pad
            v.setViewVisibility(padView, View.VISIBLE)
            v.setImageViewBitmap(padView, buttonBitmap(c, R.drawable.ic_pad, tile, col.tile, col.accent, false))
            v.setOnClickPendingIntent(padView, pending(c, code + 11, PcService.ACTION_TOUCHPAD, null))
        }
        // keep empty columns so tiles stay square-ish instead of stretching
        val used = shown.size + (if (s.showPad) 1 else 0)
        if (used == 0) v.setViewVisibility(R.id.w_row_sc, View.GONE)
    }

    // ------------------------------------------------------------ drawing

    private fun background(c: Context, col: WidgetColors, art: Bitmap?, wDp: Float, hDp: Float, d: Float): Bitmap {
        // keep the bitmap small: widgets have a memory budget
        val scale = min(d, 600f / max(wDp, hDp))
        val w = max(1, (wDp * scale).toInt())
        val h = max(1, (hDp * scale).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val r = 26f * scale
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val clip = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
        cv.clipPath(clip)
        if (col.artBackground && art != null) {
            // cheap blur: shrink a lot, then stretch back with filtering
            val tiny = Bitmap.createScaledBitmap(art, 12, 12, true)
            val blurred = Bitmap.createScaledBitmap(tiny, w, h, true)
            cv.drawBitmap(blurred, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            cv.drawRect(rect, Paint().apply { color = if (col.dark) 0x99000000.toInt() else 0x88FFFFFF.toInt() })
        } else {
            cv.drawRect(rect, Paint().apply { color = col.bg })
        }
        return bmp
    }

    private fun buttonBitmap(c: Context, icon: Int, size: Int, bg: Int, fg: Int, round: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        if (round) cv.drawOval(rect, p) else cv.drawRoundRect(rect, size * 0.3f, size * 0.3f, p)
        ContextCompat.getDrawable(c, icon)?.mutate()?.let {
            it.setTint(fg)
            val pad = (size * 0.27f).toInt()
            it.setBounds(pad, pad, size - pad, size - pad)
            it.draw(cv)
        }
        return bmp
    }

    private val tints = intArrayOf(
        0xFFE5534B.toInt(), 0xFFF0883E.toInt(), 0xFFD29922.toInt(), 0xFF3FB950.toInt(),
        0xFF2EA6A6.toInt(), 0xFF4C8DFF.toInt(), 0xFF8B6CEF.toInt(), 0xFFDB61A2.toInt()
    )

    fun shortcutBitmap(c: Context, s: Shortcut, size: Int, col: WidgetColors? = null): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = size * 0.3f
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val icon = s.icon
        val tileColor = col?.tile ?: ContextCompat.getColor(c, R.color.w_tile)
        if (icon != null) {
            canvas.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tileColor })
            val inset = size * 0.18f
            canvas.drawBitmap(icon, null, RectF(inset, inset, size - inset, size - inset), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val tint = tints[(s.name.hashCode() and 0x7fffffff) % tints.size]
            canvas.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (tint and 0x00FFFFFF) or 0x33000000 })
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tint; textAlign = Paint.Align.CENTER; textSize = size * 0.4f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(s.name.take(1).uppercase(), size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
        }
        return bmp
    }

    private fun artBitmap(c: Context, col: WidgetColors, art: Bitmap?, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = size * 0.24f
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        if (art != null) {
            val shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = max(size.toFloat() / art.width, size.toFloat() / art.height)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate((size - art.width * scale) / 2f, (size - art.height * scale) / 2f)
            })
            canvas.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        } else {
            canvas.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (col.accent and 0x00FFFFFF) or 0x2E000000 })
            ContextCompat.getDrawable(c, R.drawable.ic_music)?.mutate()?.let {
                it.setTint(col.accent)
                val pad = (size * 0.28f).toInt()
                it.setBounds(pad, pad, size - pad, size - pad)
                it.draw(canvas)
            }
        }
        return bmp
    }
}
