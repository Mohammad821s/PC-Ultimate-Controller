package com.mohammad.pcultimate

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Opens when a widget is added, and again from long-press > Customize (reconfigurable).
 * Every change shows on the live preview, which is built by the same code as the real widget.
 */
class WidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // leaving with back keeps the widget with its current (or default) look
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        AppSettings.load(this)
        PcClient.init(this)
        enableEdgeToEdge()

        val m = AppWidgetManager.getInstance(this)
        val o = m.getAppWidgetOptions(widgetId)
        val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 }?.toFloat()
        val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 }?.toFloat()
        val provider = m.getAppWidgetInfo(widgetId)?.provider?.className ?: ""
        val (dw, dh) = when {
            wDp != null && hDp != null -> wDp to hDp
            provider.endsWith("WidgetSmall") -> 150f to 150f
            provider.endsWith("WidgetLarge") -> 330f to 300f
            else -> 330f to 150f
        }

        setContent {
            AppTheme {
                ConfigScreen(WidgetStyle.load(this, widgetId), dw, dh,
                    onSave = { s ->
                        WidgetStyle.save(this, widgetId, s)
                        Widgets.update(this, m, widgetId)
                        finish()
                    },
                    onCancel = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigScreen(initial: WidgetStyle, wDp: Float, hDp: Float, onSave: (WidgetStyle) -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(initial) }
    var size by remember { mutableStateOf(wDp to hDp) }

    Scaffold(
        bottomBar = {
            Row(Modifier.fillMaxWidth().background(cs.surface).navigationBarsPadding().padding(16.dp, 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(50.dp)) { Text("Cancel") }
                Button(onClick = { onSave(s) }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Save") }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // ---- live preview, on a wallpaper-like backdrop so glass and opacity read right
            Box(
                Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp).clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF3A4A6B), Color(0xFF8A5A7A), Color(0xFFC08A5A))))
                    .padding(vertical = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                val previewKey = s to size
                AndroidView(
                    factory = { FrameLayout(it) },
                    update = { frame ->
                        frame.removeAllViews()
                        val rv = Widgets.preview(ctx, previewKey.first, size.first, size.second)
                        val v = rv.apply(ctx, frame)
                        val d = ctx.resources.displayMetrics.density
                        frame.addView(v, FrameLayout.LayoutParams((size.first * d).toInt(), (size.second * d).toInt()))
                    },
                    modifier = Modifier.width(size.first.dp).height(size.second.dp)
                )
            }
            // preview size switcher: the widget adapts when resized on the home screen
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Small" to (150f to 150f), "Bar" to (330f to 64f), "Medium" to (330f to 150f), "Large" to (330f to 300f))
                    .forEach { (label, sz) ->
                        FilterChip(selected = size == sz, onClick = { size = sz }, label = { Text(label) })
                    }
            }

            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {

                Group("Background") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("app" to "Match app", "dark" to "Dark", "black" to "Black", "light" to "Light",
                            "glass" to "Glass", "art" to "Album art").forEach { (k, label) ->
                            FilterChip(selected = s.theme == k, onClick = { s = s.copy(theme = k) }, label = { Text(label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Opacity", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(80.dp))
                        Slider(value = s.opacity.toFloat(), valueRange = 0f..100f, steps = 19,
                            onValueChange = { s = s.copy(opacity = it.toInt()) }, modifier = Modifier.weight(1f),
                            enabled = s.theme != "art")
                        Text("${s.opacity}%", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            modifier = Modifier.width(44.dp))
                    }
                }

                Group("Accent color") {
                    val canWall = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = s.accent == "app", onClick = { s = s.copy(accent = "app") }, label = { Text("Match app") })
                        if (canWall) FilterChip(selected = s.accent == "wallpaper", onClick = { s = s.copy(accent = "wallpaper") },
                            label = { Text("Wallpaper") })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AccentPresets.forEach { (c, _) ->
                            val hex = String.format("#%06X", c and 0xFFFFFF)
                            Swatch(Color(c), s.accent.equals(hex, true)) { s = s.copy(accent = hex) }
                        }
                    }
                    var hue by remember { mutableStateOf(200f) }
                    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
                        .background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.75f, 0.85f) })))
                    Slider(value = hue, valueRange = 0f..359f, onValueChange = {
                        hue = it
                        s = s.copy(accent = String.format("#%06X", Color.hsv(it, 0.72f, 0.85f).toArgb() and 0xFFFFFF))
                    })
                }

                Group("Show") {
                    Toggle("Player controls", "Back 10s, previous, next, forward 10s", s.showControls) { s = s.copy(showControls = it) }
                    Toggle("Volume", "On the large size", s.showVolume) { s = s.copy(showVolume = it) }
                    Toggle("Shortcuts", null, s.showShortcuts) { s = s.copy(showShortcuts = it) }
                    Toggle("Touchpad button", "Opens the floating touchpad", s.showPad) { s = s.copy(showPad = it) }
                }

                if (s.showShortcuts) Group("Shortcuts on this widget") {
                    val all = PcState.shortcuts
                    if (all.isEmpty()) {
                        Text("Connect to your PC once to pick shortcuts. Until then the widget shows all of them.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    } else {
                        Text(if (s.shortcutIds.isEmpty()) "All, in your app order. Tap to choose and order."
                        else "Shown in the order you tap them.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            all.forEach { sc ->
                                val idx = s.shortcutIds.indexOf(sc.id)
                                FilterChip(
                                    selected = idx >= 0,
                                    onClick = {
                                        s = s.copy(shortcutIds = if (idx >= 0) s.shortcutIds - sc.id else s.shortcutIds + sc.id)
                                    },
                                    label = { Text(if (idx >= 0) "${idx + 1}. ${sc.name}" else sc.name) }
                                )
                            }
                        }
                        if (s.shortcutIds.isNotEmpty()) TextButton(onClick = { s = s.copy(shortcutIds = emptyList()) }) {
                            Text("Show all")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Toggle(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun Swatch(c: Color, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape)
            .border(if (on) 2.dp else 0.dp, if (on) c else Color.Transparent, CircleShape)
            .padding(if (on) 4.dp else 0.dp).clip(CircleShape).background(c)
            .combinedClickableCompat(onClick)
    )
}
