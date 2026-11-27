package com.mohammad.pcultimate

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

private const val REPO = "Mohammad821s/PC-Ultimate-Controller"

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun persist() = AppSettings.save(ctx)

    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(horizontal = 18.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(R.drawable.ic_arrow_back, "Back", onClick = onBack)
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ConnectionSection()

            Section("Theme") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark", "black" to "Black").forEach { (key, label) ->
                        val on = AppSettings.themeMode == key
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .background(if (on) cs.primary.copy(alpha = 0.10f) else Color.Transparent)
                                .border(if (on) 2.dp else 1.dp, if (on) cs.primary else cs.outlineVariant, RoundedCornerShape(14.dp))
                                .combinedClickableCompat { AppSettings.themeMode = key; persist() }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val swatch = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                            when (key) {
                                "system" -> Box(swatch.background(Brush.horizontalGradient(0.5f to Color(0xFFEEF1F5), 0.5f to Color(0xFF1A1F28))))
                                "light" -> Box(swatch.background(Color(0xFFEEF1F5)).border(1.dp, Color(0xFFD9DEE6), RoundedCornerShape(12.dp)))
                                "dark" -> Box(swatch.background(Color(0xFF171B22)))
                                else -> Box(swatch.background(Color(0xFF000000)).border(1.dp, Color(0xFF2A3242), RoundedCornerShape(12.dp)))
                            }
                            Text(label, style = if (on) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Section("Accent color") {
                val canDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ToggleRow("Match my wallpaper",
                    if (canDynamic) "Material You colors from your wallpaper" else "Needs Android 12 or newer",
                    AppSettings.dynamicColor && canDynamic, canDynamic) { AppSettings.dynamicColor = it; persist() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    AccentPresets.forEach { (c, label) ->
                        val on = !AppSettings.dynamicColor && AppSettings.accent == c
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .border(if (on) 2.dp else 0.dp, if (on) Color(c) else Color.Transparent, CircleShape)
                                .padding(if (on) 4.dp else 0.dp).clip(CircleShape).background(Color(c))
                                .combinedClickableCompat { AppSettings.accent = c; AppSettings.dynamicColor = false; persist() }
                                .semantics { contentDescription = label }
                        )
                    }
                }
                CustomColor { AppSettings.accent = it; AppSettings.dynamicColor = false; persist() }
            }

            WidgetsSection()

            Section("Touchpad") {
                Row(Modifier.fillMaxWidth()) {
                    Text("Pointer speed", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(String.format("%.1fx", AppSettings.sensitivity), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Slider(
                    value = AppSettings.sensitivity, valueRange = 0.5f..5.5f,
                    onValueChange = { AppSettings.sensitivity = it },
                    onValueChangeFinished = { persist(); Prefs.saveSens(ctx, AppSettings.sensitivity) }
                )
                ToggleRow("Natural scrolling", "Content follows your fingers", AppSettings.naturalScroll) { AppSettings.naturalScroll = it; persist() }
                ToggleRow("Haptic feedback", "A light tap when you click", AppSettings.haptics) { AppSettings.haptics = it; persist() }
            }

            Section("About") {
                val pkg = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("App version ${pkg.versionName}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (PcState.pcVersion.isNotBlank()) "PC app build ${PcState.pcVersion}" else "PC app version shows once connected",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { Updater.check(ctx, force = true) },
                        enabled = Updater.progress == null,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.primary.copy(alpha = 0.12f), contentColor = cs.primary)
                    ) { Text("Check for updates") }
                }
                UpdatePanel()
            }
        }
    }
}

/** Status, progress and the Update button. Shared by Settings and the update dialog. */
@Composable
fun UpdatePanel() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val r = Updater.available
    val pr = Updater.progress
    if (r != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Build ${r.build} is ready", style = MaterialTheme.typography.bodyMedium)
                Text(String.format("%.1f MB", r.size / 1_000_000.0), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Button(onClick = { Updater.install(ctx) }, enabled = pr == null, shape = RoundedCornerShape(12.dp)) {
                Text("Download")
            }
        }
    }
    if (pr != null) {
        androidx.compose.material3.LinearProgressIndicator(progress = { pr }, modifier = Modifier.fillMaxWidth())
    }
    if (Updater.status.isNotEmpty()) {
        Text(Updater.status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun CustomColor(onPick: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var hue by remember { mutableStateOf(220f) }
    var hex by remember { mutableStateOf(String.format("#%06X", AppSettings.accent and 0xFFFFFF)) }
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh)
            .combinedClickableCompat { open = !open }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Custom color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(String.format("#%06X", AppSettings.accent and 0xFFFFFF), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(Color(AppSettings.accent)))
    }
    if (open) {
        val hues = (0..6).map { Color.hsv(it * 60f % 360f, 0.75f, 0.85f) }
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Brush.horizontalGradient(hues)))
        Slider(value = hue, valueRange = 0f..359f, onValueChange = {
            hue = it
            val c = Color.hsv(it, 0.72f, 0.82f).toArgb()
            hex = String.format("#%06X", c and 0xFFFFFF)
            onPick(c)
        })
        OutlinedTextField(
            value = hex, onValueChange = {
                hex = it
                val clean = it.trim().removePrefix("#")
                if (clean.length == 6) clean.toLongOrNull(16)?.let { v -> onPick((0xFF000000 or v).toInt()) }
            },
            label = { Text("Hex color") }, singleLine = true, shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ConnectionSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(Prefs.host(ctx)) }
    var port by remember { mutableStateOf(Prefs.port(ctx).toString()) }
    var note by remember { mutableStateOf("") }
    Section("Connection") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (PcState.connected) Color(0xFF3FCF8E) else Color(0xFFE5534B)))
            Text(if (PcState.connected) "Connected to ${PcState.pcName}" else PcState.status, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = {
                note = "Looking for your PC…"
                scope.launch {
                    val r = withContext(Dispatchers.IO) { PcClient.discover() }
                    if (r == null) note = "No PC found. Check the PC app is running and allowed through the firewall."
                    else {
                        host = r.first; port = r.second.toString()
                        Prefs.save(ctx, host, r.second)
                        PcClient.reconnect()
                        note = "Found ${r.third}. Choose Yes in the box that appears on your PC."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = cs.surfaceContainerHigh, contentColor = cs.onSurface)
        ) { Text("Find my PC") }
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { host = it }, label = { Text("PC address") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(2f))
            OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Button(
            onClick = {
                Prefs.save(ctx, host.trim(), port.toIntOrNull() ?: 8765)
                PcClient.reconnect()
                note = "Connecting… If this phone is new, choose Yes on your PC."
            },
            modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)
        ) { Text("Connect") }
    }
}

@Composable
private fun WidgetsSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val m = android.appwidget.AppWidgetManager.getInstance(ctx)
    val ids = remember { Widgets.allIds(ctx) }
    Section("Widgets") {
        if (ids.isEmpty()) {
            Text("Add a widget from your home screen, then customize its colors and buttons here.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        ids.forEachIndexed { i, id ->
            val cls = m.getAppWidgetInfo(id)?.provider?.className ?: ""
            val kind = when {
                cls.endsWith("WidgetSmall") -> "Now playing"
                cls.endsWith("WidgetLarge") -> "Full remote"
                else -> "Player and shortcuts"
            }
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh)
                    .combinedClickableCompat {
                        ctx.startActivity(Intent(ctx, WidgetConfigActivity::class.java)
                            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$kind ${i + 1}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("Customize", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
        }
    }
}
