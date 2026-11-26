package com.mohammad.pcultimate

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** User settings, observable by Compose and saved to SharedPreferences. */
object AppSettings {
    var themeMode by mutableStateOf("system")   // system | light | dark | black
    var dynamicColor by mutableStateOf(false)
    var accent by mutableStateOf(0xFF2F6BEB.toInt())
    var sensitivity by mutableStateOf(2.0f)
    var naturalScroll by mutableStateOf(true)
    var haptics by mutableStateOf(true)
    private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = c.getSharedPreferences("pc", Context.MODE_PRIVATE)
        themeMode = p.getString("theme", "system") ?: "system"
        dynamicColor = p.getBoolean("dynamic", false)
        accent = p.getInt("accent", 0xFF2F6BEB.toInt())
        sensitivity = p.getFloat("sens", 2.0f)
        naturalScroll = p.getBoolean("natural", true)
        haptics = p.getBoolean("haptics", true)
    }

    fun save(c: Context) {
        c.getSharedPreferences("pc", Context.MODE_PRIVATE).edit()
            .putString("theme", themeMode).putBoolean("dynamic", dynamicColor).putInt("accent", accent)
            .putFloat("sens", sensitivity).putBoolean("natural", naturalScroll).putBoolean("haptics", haptics)
            .apply()
    }

    fun isDark(systemDark: Boolean) = when (themeMode) {
        "light" -> false
        "dark", "black" -> true
        else -> systemDark
    }
}

val AccentPresets = listOf(
    0xFF2F6BEB.toInt() to "Cobalt",
    0xFF0E8F86.toInt() to "Teal",
    0xFF2E8B3E.toInt() to "Green",
    0xFFC27A06.toInt() to "Amber",
    0xFFD4530F.toInt() to "Orange",
    0xFFD2345E.toInt() to "Rose",
    0xFF6F4BD8.toInt() to "Violet",
    0xFF4A5363.toInt() to "Graphite",
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Manrope = FontFamily(
    variable(R.font.manrope, 600), variable(R.font.manrope, 700), variable(R.font.manrope, 800)
)
val InterFont = FontFamily(
    variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontFamily = InterFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = InterFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = InterFont, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = InterFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = InterFont, fontWeight = FontWeight.Medium, fontSize = 12.5.sp),
    labelSmall = TextStyle(fontFamily = InterFont, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
)

fun schemeFor(dark: Boolean, black: Boolean, accentInt: Int): ColorScheme {
    val accent = Color(accentInt)
    return if (dark) {
        val bg = if (black) Color(0xFF000000) else Color(0xFF0E1116)
        val surface = if (black) Color(0xFF0D0F12) else Color(0xFF171B22)
        darkColorScheme(
            primary = lerp(accent, Color.White, 0.08f),
            onPrimary = Color.White,
            primaryContainer = lerp(bg, accent, 0.22f),
            onPrimaryContainer = lerp(accent, Color.White, 0.75f),
            secondaryContainer = lerp(surface, accent, 0.18f),
            onSecondaryContainer = Color(0xFFE9EDF3),
            background = bg,
            onBackground = Color(0xFFE9EDF3),
            surface = surface,
            onSurface = Color(0xFFE9EDF3),
            surfaceVariant = if (black) Color(0xFF15181D) else Color(0xFF1F2530),
            onSurfaceVariant = Color(0xFF9AA4B5),
            surfaceContainer = surface,
            surfaceContainerHigh = if (black) Color(0xFF15181D) else Color(0xFF1F2530),
            surfaceContainerHighest = if (black) Color(0xFF1C2027) else Color(0xFF262D39),
            outline = Color(0xFF3A4354),
            outlineVariant = Color(0xFF2A3242),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = lerp(Color.White, accent, 0.14f),
            onPrimaryContainer = lerp(accent, Color.Black, 0.35f),
            secondaryContainer = lerp(Color.White, accent, 0.12f),
            onSecondaryContainer = Color(0xFF121821),
            background = Color(0xFFF3F5F8),
            onBackground = Color(0xFF121821),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF121821),
            surfaceVariant = Color(0xFFEAEEF3),
            onSurfaceVariant = Color(0xFF5B6576),
            surfaceContainer = Color(0xFFFFFFFF),
            surfaceContainerHigh = Color(0xFFEEF1F5),
            surfaceContainerHighest = Color(0xFFE3E7ED),
            outline = Color(0xFFB8C0CC),
            outlineVariant = Color(0xFFE1E5EB),
        )
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = AppSettings.isDark(isSystemInDarkTheme())
    val black = AppSettings.themeMode == "black"
    var scheme = schemeFor(dark, black, AppSettings.accent)
    if (AppSettings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dyn = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        scheme = scheme.copy(
            primary = dyn.primary, onPrimary = dyn.onPrimary,
            primaryContainer = dyn.primaryContainer, onPrimaryContainer = dyn.onPrimaryContainer,
            secondaryContainer = dyn.secondaryContainer, onSecondaryContainer = dyn.onSecondaryContainer,
            background = if (black) scheme.background else dyn.background,
            surface = if (black) scheme.surface else dyn.surfaceContainer,
            surfaceVariant = if (black) scheme.surfaceVariant else dyn.surfaceContainerHigh,
            surfaceContainerHigh = if (black) scheme.surfaceContainerHigh else dyn.surfaceContainerHigh,
            surfaceContainerHighest = if (black) scheme.surfaceContainerHighest else dyn.surfaceContainerHighest,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

/** The accent the overlay and widgets should use right now. */
fun currentAccent(c: Context): Int {
    AppSettings.load(c)
    if (AppSettings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return dynamicDarkColorScheme(c).primary.toArgb()
    }
    return AppSettings.accent
}
