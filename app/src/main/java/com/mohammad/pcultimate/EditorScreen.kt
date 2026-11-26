package com.mohammad.pcultimate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun EditorScreen(existing: Shortcut?, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(existing?.type ?: "app") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var browser by remember { mutableStateOf(existing?.browser ?: "") }
    var profile by remember { mutableStateOf(existing?.profile ?: "") }
    var appId by remember { mutableStateOf(existing?.appId ?: "") }
    var appName by remember { mutableStateOf(existing?.appName ?: "") }
    var command by remember { mutableStateOf(existing?.command ?: "") }
    var iconB64 by remember { mutableStateOf<String?>(null) }
    var iconBmp by remember { mutableStateOf<Bitmap?>(existing?.icon) }
    var customIcon by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        PcClient.requestApps()
        PcClient.requestBrowsers()
        PcState.lastSavedId = ""
    }
    // pick Chrome (or the first browser) for new website shortcuts
    LaunchedEffect(PcState.browsers) {
        if (browser.isEmpty() && PcState.browsers.isNotEmpty()) {
            browser = (PcState.browsers.firstOrNull { it.path.lowercase().endsWith("chrome.exe") } ?: PcState.browsers.first()).path
        }
    }
    // site icon follows the address, unless the user picked their own
    LaunchedEffect(url, tab) {
        if (tab != "website" || customIcon || url.length < 4) return@LaunchedEffect
        delay(700)
        val b64 = withContext(Dispatchers.IO) { PcClient.fetchFavicon(url) }
        if (b64 != null) { iconB64 = b64; iconBmp = decodeBitmap(b64) }
    }
    LaunchedEffect(PcState.lastSavedId) { if (saving && PcState.lastSavedId.isNotEmpty()) onDone() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bmp != null) {
                val scaled = Bitmap.createScaledBitmap(bmp, 128, 128, true)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                iconB64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                iconBmp = scaled
                customIcon = true
            }
        }
    }

    fun current() = Shortcut(
        id = existing?.id ?: "", name = name.trim(), type = tab,
        url = if (tab == "website") url.trim() else "", browser = if (tab == "website") browser else "",
        profile = if (tab == "website") profile else "",
        appId = if (tab == "app") appId else "", appName = if (tab == "app") appName else "",
        command = if (tab == "command") command.trim() else "", iconB64 = iconB64
    )
    val valid = name.isNotBlank() && when (tab) {
        "website" -> url.isNotBlank()
        "app" -> appId.isNotBlank()
        else -> command.isNotBlank()
    }

    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(R.drawable.ic_arrow_back, "Back", onClick = onDone)
            Text(if (existing == null) "New shortcut" else "Edit shortcut", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (existing != null) {
                TextButton(onClick = { PcClient.deleteShortcut(existing.id); onDone() }) {
                    Text("Delete", color = Color(0xFFE5534B))
                }
            }
        }

        Segmented(listOf("app" to "App", "website" to "Website", "command" to "Command"), tab) {
            tab = it
            if (!customIcon) { iconB64 = null; iconBmp = existing?.takeIf { e -> e.type == it }?.icon }
        }

        Surface(color = cs.surface, shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShortcutIcon(Shortcut("", name.ifBlank { "?" }, tab, icon = iconBmp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            customIcon -> "Your own icon"
                            tab == "app" -> "Icon comes from your PC"
                            tab == "website" && iconBmp != null -> "Site icon found automatically"
                            tab == "website" -> "Site icon appears once the address is in"
                            else -> "Pick an icon if you like"
                        },
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                }
                TextButton(onClick = { picker.launch("image/*") }) { Text("Change") }
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (tab) {
                "app" -> {
                    Field("Name", name) { name = it }
                    Field("Search apps on ${PcState.pcName.ifBlank { "your PC" }}", query) { query = it }
                    val list = PcState.apps.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                    if (PcState.apps.isEmpty()) {
                        Text(if (PcState.connected) "Loading apps from your PC…" else "Connect to your PC to see its apps",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surface)
                    ) {
                        items(list, key = { it.id }) { a ->
                            val selected = a.id == appId
                            Row(
                                Modifier.fillMaxWidth().combinedClickableCompat {
                                    if (name.isBlank() || name == appName) name = a.name
                                    appId = a.id; appName = a.name
                                }.background(if (selected) cs.primaryContainer else Color.Transparent)
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(a.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (selected) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = cs.primary)
                            }
                        }
                    }
                }
                "website" -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Field("Name", name) { name = it }
                    Field("Web address", url, placeholder = "netflix.com") { url = it }
                    Text("Open with, found on ${PcState.pcName.ifBlank { "your PC" }}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (PcState.browsers.isEmpty()) {
                        Text(if (PcState.connected) "Looking for browsers…" else "Connect to your PC to pick a browser",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    PcState.browsers.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { b -> Choice(b.name, b.path == browser, Modifier.weight(1f)) { browser = b.path; profile = "" } }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                    val profiles = PcState.browsers.firstOrNull { it.path == browser }?.profiles.orEmpty()
                    if (profiles.size > 1) {
                        Text("Profile", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        profiles.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { p -> Choice(p, p == profile, Modifier.weight(1f)) { profile = p } }
                                if (row.size == 1) Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
                else -> {
                    Field("Name", name) { name = it }
                    Field("Command or link", command, placeholder = "e.g. notepad.exe or steam://open/games", singleLine = false) { command = it }
                    Text("Runs on your PC exactly as typed. Links like steam:// or com.epicgames.launcher:// open their app.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { PcClient.testShortcut(current()) }, enabled = valid && PcState.connected,
                modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = cs.surface, contentColor = cs.onSurface)
            ) { Text("Test on PC") }
            Button(
                onClick = { saving = true; PcClient.saveShortcut(current()) }, enabled = valid && PcState.connected && !saving,
                modifier = Modifier.weight(2f).height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text(if (saving) "Saving…" else "Save shortcut") }
        }
    }
}

@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (on) cs.surfaceContainerHighest else Color.Transparent)
                    .combinedClickableCompat { onSelect(key) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, style = if (on) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                    color = if (on) cs.onSurface else cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) cs.primary.copy(alpha = 0.12f) else cs.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant, RoundedCornerShape(14.dp))
            .combinedClickableCompat(onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = cs.primary)
    }
}

@Composable
fun Field(label: String, value: String, placeholder: String = "", singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = singleLine, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
    )
}
