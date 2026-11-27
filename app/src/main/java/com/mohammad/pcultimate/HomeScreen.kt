package com.mohammad.pcultimate

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun HomeScreen(
    onSettings: () -> Unit,
    onAddShortcut: () -> Unit,
    onEditShortcut: (Shortcut) -> Unit,
    onFloating: () -> Unit,
    onConnect: () -> Unit,
    onScreen: () -> Unit = {},
) {
    var showKeyboard by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Header(onSettings, onConnect, onScreen)
        if (!PcState.connected && PcWaker.linked) WakeCard()
        NowPlayingCard()
        ShortcutsRow(onAddShortcut, onEditShortcut)
        TouchpadPanel(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { showKeyboard = true },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = cs.surface, contentColor = cs.onSurface)
            ) {
                Icon(painterResource(R.drawable.ic_keyboard), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Keyboard")
            }
            Button(
                onClick = onFloating,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(painterResource(R.drawable.ic_pad), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Floating pad")
            }
        }
    }

    if (showKeyboard) KeyboardSheet { showKeyboard = false }
}

@Composable
private fun Header(onSettings: () -> Unit, onConnect: () -> Unit, onScreen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                PcState.pcName.ifBlank { "PC Ultimate Controller" },
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (PcState.connected) Color(0xFF3FCF8E) else Color(0xFFE5534B)))
                Text(PcState.status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                if (!PcState.connected) {
                    Text(
                        "Connect", style = MaterialTheme.typography.labelMedium, color = cs.primary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).combinedClickableCompat(onConnect).padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
        IconTile(R.drawable.ic_screen, "Show PC screen", onClick = onScreen)
        Spacer(Modifier.width(8.dp))
        IconTile(R.drawable.ic_settings, "Settings", onClick = onSettings)
    }
}

@Composable
fun IconTile(icon: Int, label: String, size: Dp = 44.dp, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(cs.surface)
            .combinedClickableCompat(onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { Icon(painterResource(icon), null, Modifier.size(20.dp), tint = cs.onSurfaceVariant) }
}

@Composable
private fun RoundControl(icon: Int, label: String, size: Dp, bg: Color, fg: Color, iconSize: Dp = 20.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).combinedClickableCompat(onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { Icon(painterResource(icon), null, Modifier.size(iconSize), tint = fg) }
}

@Composable
private fun NowPlayingCard() {
    val cs = MaterialTheme.colorScheme
    val m = PcState.media
    Surface(color = cs.primaryContainer, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(cs.onPrimaryContainer.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    val art = m?.thumb
                    if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(painterResource(R.drawable.ic_music), null, Modifier.size(26.dp), tint = cs.onPrimaryContainer)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        m?.let { it.title.ifBlank { it.app } } ?: "Nothing playing",
                        style = MaterialTheme.typography.titleMedium, color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (m == null) "Play something on your PC" else listOf(m.artist, m.app).filter { it.isNotBlank() }.joinToString(", on "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                RoundControl(R.drawable.ic_back10, "Back", 44.dp, Color.Transparent, cs.onSurfaceVariant, 22.dp) { PcClient.media("left") }
                RoundControl(R.drawable.ic_prev, "Previous", 48.dp, cs.surface.copy(alpha = 0.7f), cs.onSurface) { PcClient.media("prev") }
                RoundControl(
                    if (m?.playing == true) R.drawable.ic_pause else R.drawable.ic_play, "Play or pause",
                    64.dp, cs.primary, cs.onPrimary, 24.dp
                ) { PcClient.media("playpause") }
                RoundControl(R.drawable.ic_next, "Next", 48.dp, cs.surface.copy(alpha = 0.7f), cs.onSurface) { PcClient.media("next") }
                RoundControl(R.drawable.ic_fwd10, "Forward", 44.dp, Color.Transparent, cs.onSurfaceVariant, 22.dp) { PcClient.media("right") }
            }
            VolumeRow()
        }
    }
}

@Composable
private fun VolumeRow() {
    val cs = MaterialTheme.colorScheme
    val remote = PcState.volume
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(remote ?: 0.5f) }
    var lastSent by remember { mutableLongStateOf(0L) }
    LaunchedEffect(remote) { if (!dragging && remote != null) local = remote }

    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).combinedClickableCompat { PcClient.media("voldown") }, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_vol_low), "Volume down", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
        }
        if (remote != null) {
            Slider(
                value = local,
                onValueChange = {
                    dragging = true; local = it
                    val now = System.currentTimeMillis()
                    if (now - lastSent > 80) { lastSent = now; PcClient.setVolume(it) }
                },
                onValueChangeFinished = { dragging = false; PcClient.setVolume(local) },
                modifier = Modifier.weight(1f).height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = cs.primary, activeTrackColor = cs.primary,
                    inactiveTrackColor = cs.onPrimaryContainer.copy(alpha = 0.18f)
                )
            )
        } else {
            Text("Volume", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Box(Modifier.size(32.dp).clip(CircleShape).combinedClickableCompat { PcClient.media("volup") }, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_vol_high), "Volume up", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
        }
    }
}

private val letterTints = listOf(
    0xFFE5534B, 0xFFF0883E, 0xFFD29922, 0xFF3FB950, 0xFF2EA6A6, 0xFF4C8DFF, 0xFF8B6CEF, 0xFFDB61A2
)

@Composable
fun ShortcutIcon(s: Shortcut, size: Dp = 56.dp) {
    val cs = MaterialTheme.colorScheme
    val tint = Color(letterTints[(s.name.hashCode() and 0x7fffffff) % letterTints.size])
    val icon = s.icon
    if (icon != null) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(cs.surface),
            contentAlignment = Alignment.Center
        ) { Image(icon.asImageBitmap(), null, Modifier.size(size * 0.62f)) }
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                s.name.take(1).uppercase(), color = tint, fontFamily = Manrope,
                fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.36f).sp
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutsRow(onAdd: () -> Unit, onEdit: (Shortcut) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Shortcuts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("Hold to edit", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(PcState.shortcuts, key = { it.id }) { s ->
                Column(
                    Modifier.width(64.dp).clip(RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { PcClient.shortcut(s.id) }, onLongClick = { onEdit(s) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ShortcutIcon(s)
                    Text(s.name, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                Column(
                    Modifier.width(64.dp).clip(RoundedCornerShape(14.dp)).combinedClickableCompat(onAdd),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).border(1.5.dp, cs.outline, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(20.dp), tint = cs.onSurfaceVariant) }
                    Text("Add", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun TouchpadPanel(modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(cs.surface)) {
        TouchpadFxView(Modifier.weight(1f).fillMaxWidth(), radius = 0.dp)
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            TextButton(onClick = { PcClient.click("left") }, modifier = Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(0.dp)) {
                Text("Left click", color = cs.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
            Box(Modifier.width(1.dp).fillMaxSize().background(cs.outlineVariant))
            TextButton(onClick = { PcClient.click("right") }, modifier = Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(0.dp)) {
                Text("Right click", color = cs.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyboardSheet(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val send = { if (text.isNotEmpty()) { PcClient.text(text); text = "" } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = cs.surface) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Type on your PC", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Text goes to the window in focus") },
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundControl(R.drawable.ic_backspace, "Backspace", 52.dp, cs.surfaceContainerHigh, cs.onSurface) { PcClient.key("backspace") }
                RoundControl(R.drawable.ic_enter, "Enter", 52.dp, cs.surfaceContainerHigh, cs.onSurface) { PcClient.key("enter") }
                Button(onClick = send, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(painterResource(R.drawable.ic_send), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("Send")
                }
            }
        }
    }
}

/** Plain click with the standard ripple. */
fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/** Touchpad with the chosen effect background. [live] = false for the settings preview. */
@Composable
internal fun TouchpadFxView(modifier: Modifier, radius: androidx.compose.ui.unit.Dp, live: Boolean = true, hint: String = "Touchpad") {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { radius.toPx() }
    TouchpadFx.load(ctx)
    // reading these makes the view update when they change
    val key = listOf(TouchpadFx.bg, TouchpadFx.liquid, TouchpadFx.dotsBase, TouchpadFx.dotsActive, TouchpadFx.ripple)
    AndroidView(
        factory = { TouchpadSurface(it, px).apply { setBackgroundTint(FX_PAD_BG) } },
        update = {
            key.hashCode()
            it.pad.setColors(FX_PAD_BG, 0, 0x66C9C3D6, 0)
            it.pad.hint = hint
            it.pad.live = live
            it.pad.sensitivity = AppSettings.sensitivity
            it.pad.naturalScroll = AppSettings.naturalScroll
            it.pad.haptics = AppSettings.haptics
            it.push()
        },
        onRelease = { it.destroy() },
        modifier = modifier
    )
}

/** Dark base the effects are designed on (the same in light and dark themes). */
internal const val FX_PAD_BG = 0xFF0A0910.toInt()

/** Shown while the PC is off, only when it has a PC Waker linked. */
@Composable
private fun WakeCard() {
    val cs = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.primaryContainer).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Your PC is off", style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
            Text(PcWaker.status.ifBlank { "Turn it on from here, from anywhere" },
                style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer.copy(alpha = 0.8f))
        }
        Button(onClick = { PcWaker.wake(ctx) }, enabled = !PcWaker.busy, shape = RoundedCornerShape(14.dp)) {
            if (PcWaker.busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.onPrimary)
            else Text("Turn on")
        }
    }
}
