package com.mohammad.pcultimate

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build

/**
 * How one placed widget looks. Saved per widget id, edited in WidgetConfigActivity
 * (opens when the widget is added, and from long-press > Customize).
 */
data class WidgetStyle(
    /** app | light | dark | black | glass | art */
    val theme: String = "app",
    /** "app", "wallpaper", or "#RRGGBB" */
    val accent: String = "app",
    /** background opacity 0..100 */
    val opacity: Int = 100,
    val showControls: Boolean = true,
    val showVolume: Boolean = true,
    val showShortcuts: Boolean = true,
    val showPad: Boolean = true,
    /** empty = your shortcuts in their app order */
    val shortcutIds: List<String> = emptyList(),
) {
    companion object {
        private fun p(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)

        fun load(c: Context, id: Int): WidgetStyle {
            val s = p(c)
            val k = "w$id."
            val d = WidgetStyle()
            return WidgetStyle(
                theme = s.getString(k + "theme", d.theme) ?: d.theme,
                accent = s.getString(k + "accent", d.accent) ?: d.accent,
                opacity = s.getInt(k + "opacity", d.opacity),
                showControls = s.getBoolean(k + "controls", d.showControls),
                showVolume = s.getBoolean(k + "volume", d.showVolume),
                showShortcuts = s.getBoolean(k + "shortcuts", d.showShortcuts),
                showPad = s.getBoolean(k + "pad", d.showPad),
                shortcutIds = (s.getString(k + "ids", "") ?: "").split(',').filter { it.isNotBlank() },
            )
        }

        fun save(c: Context, id: Int, w: WidgetStyle) {
            val k = "w$id."
            p(c).edit()
                .putString(k + "theme", w.theme).putString(k + "accent", w.accent).putInt(k + "opacity", w.opacity)
                .putBoolean(k + "controls", w.showControls).putBoolean(k + "volume", w.showVolume)
                .putBoolean(k + "shortcuts", w.showShortcuts).putBoolean(k + "pad", w.showPad)
                .putString(k + "ids", w.shortcutIds.joinToString(","))
                .apply()
        }

        fun delete(c: Context, id: Int) {
            val k = "w$id."
            val e = p(c).edit()
            p(c).all.keys.filter { it.startsWith(k) }.forEach { e.remove(it) }
            e.apply()
        }
    }
}

/** The concrete colors a style resolves to right now (system dark mode, wallpaper, app accent). */
data class WidgetColors(
    val bg: Int,
    val tile: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val artBackground: Boolean,
    val dark: Boolean,
)

object WidgetPalette {
    fun resolve(c: Context, s: WidgetStyle): WidgetColors {
        AppSettings.load(c)
        val systemDark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val theme = if (s.theme == "app") when (AppSettings.themeMode) {
            "light" -> "light"; "dark" -> "dark"; "black" -> "black"; else -> if (systemDark) "dark" else "light"
        } else s.theme
        val dark = theme != "light"
        val alpha = (s.opacity.coerceIn(0, 100) * 255 / 100)
        val base = when (theme) {
            "light" -> 0xF4F5F8
            "black" -> 0x000000
            "glass" -> 0x0E1014
            "art" -> 0x101216
            else -> 0x16181D
        }
        val glassAlpha = if (theme == "glass") minOf(alpha, 150) else alpha
        val accent = accentColor(c, s.accent, dark)
        return WidgetColors(
            bg = (glassAlpha shl 24) or base,
            tile = if (dark) 0x1FFFFFFF else 0x0F000000,
            text = if (dark) 0xFFF2F3F5.toInt() else 0xFF14161B.toInt(),
            muted = if (dark) 0xFFA0A6B1.toInt() else 0xFF5C6370.toInt(),
            accent = accent,
            onAccent = if (luminance(accent) > 0.55) 0xFF0B0C0F.toInt() else Color.WHITE,
            artBackground = theme == "art",
            dark = dark,
        )
    }

    fun accentColor(c: Context, a: String, dark: Boolean): Int = when {
        a == "wallpaper" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            c.getColor(if (dark) android.R.color.system_accent1_300 else android.R.color.system_accent1_600)
        a.startsWith("#") -> try { Color.parseColor(a) } catch (e: Exception) { AppSettings.accent }
        else -> currentAccent(c)
    }

    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }
}
