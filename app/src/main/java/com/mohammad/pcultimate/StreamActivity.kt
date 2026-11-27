package com.mohammad.pcultimate

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * The PC screen on the phone. Normal mode: picture on top, touchpad below.
 * Full screen: landscape, the finger works directly on the picture.
 * Leaving the app keeps it playing in a small movable window (picture-in-picture).
 */
class StreamActivity : ComponentActivity() {
    private lateinit var view: StreamView
    private lateinit var client: StreamClient
    private var fullscreen by mutableStateOf(false)
    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PcClient.init(this)
        AppSettings.load(this)
        enableEdgeToEdge()
        view = StreamView(this).apply { haptics = AppSettings.haptics }
        client = StreamClient(this) { view.setFrame(it) }
        view.client = client
        setContent {
            AppTheme {
                BackHandler(enabled = fullscreen) { setFull(false) }
                StreamScreen()
            }
        }
        updatePip()
    }

    override fun onStart() {
        super.onStart()
        client.start(StreamQuality.load(this))
        if (!PcClient.connected) PcClient.connect()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) client.stop()
    }

    override fun onDestroy() {
        client.release()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters picture-in-picture by itself (setAutoEnterEnabled)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && StreamState.live) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        view.interactive = fullscreen && !inPip
        // closing the small window stops the activity without coming back: stop streaming then
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) client.stop()
    }

    private fun pipParams(): PictureInPictureParams {
        val w = StreamState.screenW.coerceAtLeast(1)
        val h = StreamState.screenH.coerceAtLeast(1)
        // Android only accepts ratios between 1:2.39 and 2.39:1
        val r = (w.toFloat() / h).coerceIn(0.42f, 2.39f)
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(StreamState.live).setSeamlessResizeEnabled(true)
        return b.build()
    }

    fun updatePip() = try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}

    fun enterPip() = try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) { false }

    fun setFull(on: Boolean) {
        fullscreen = on
        view.interactive = on && !inPip
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------ UI

    @Composable
    private fun StreamScreen() {
        val live = StreamState.live
        LaunchedEffect(live, StreamState.screenW, StreamState.screenH) { updatePip() }
        when {
            inPip -> Picture(Modifier.fillMaxSize().background(Color.Black), rounded = false)
            fullscreen -> FullScreen()
            else -> Normal()
        }
    }

    @Composable
    private fun Picture(modifier: Modifier, rounded: Boolean) {
        Box(modifier.then(if (rounded) Modifier.clip(RoundedCornerShape(18.dp)) else Modifier).background(Color.Black),
            contentAlignment = Alignment.Center) {
            AndroidView(factory = { (view.parent as? ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxSize())
            if (!StreamState.live && !inPip) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (StreamState.status in WAITING) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
                    }
                    Text(StreamState.status.ifBlank { "Connecting…" }, color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    @Composable
    private fun Normal() {
        val cs = MaterialTheme.colorScheme
        var keyboard by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(R.drawable.ic_arrow_back, "Back") { finish() }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Screen", style = MaterialTheme.typography.titleLarge)
                    Text(statusLine(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                QualityButton()
                Spacer(Modifier.width(8.dp))
                IconTile(R.drawable.ic_pip, "Small window") { enterPip() }
                Spacer(Modifier.width(8.dp))
                IconTile(R.drawable.ic_fullscreen, "Full screen") { setFull(true) }
            }
            val ratio = StreamState.screenW.toFloat() / StreamState.screenH.coerceAtLeast(1)
            Picture(Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 3f)), rounded = true)
            TouchpadPanel(Modifier.weight(1f))
            Button(
                onClick = { keyboard = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = cs.surface, contentColor = cs.onSurface)
            ) {
                Icon(painterResource(R.drawable.ic_keyboard), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Keyboard")
            }
        }
        if (keyboard) KeyboardSheet { keyboard = false }
    }

    @Composable
    private fun FullScreen() {
        var keyboard by remember { mutableStateOf(false) }
        var controls by remember { mutableStateOf(true) }
        LaunchedEffect(controls) { if (controls) { delay(4000); controls = false } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Picture(Modifier.fillMaxSize(), rounded = false)
            // a thin strip at the top brings the buttons back
            Box(Modifier.fillMaxWidth().height(28.dp).align(Alignment.TopCenter).clickable { controls = true })
            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd)) {
                Row(
                    Modifier.padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(statusLine(), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp))
                    QualityButton(dark = true)
                    OverlayButton(R.drawable.ic_keyboard, "Keyboard") { keyboard = true }
                    OverlayButton(R.drawable.ic_pip, "Small window") { enterPip() }
                    OverlayButton(R.drawable.ic_fullscreen_exit, "Exit full screen") { setFull(false) }
                }
            }
            AnimatedVisibility(!controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Box(Modifier.padding(top = 6.dp).size(width = 44.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
            }
        }
        if (keyboard) KeyboardSheet { keyboard = false }
    }

    @Composable
    private fun OverlayButton(icon: Int, label: String, onClick: () -> Unit) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), label, Modifier.size(20.dp), tint = Color.White)
        }
    }

    @Composable
    private fun QualityButton(dark: Boolean = false) {
        val cs = MaterialTheme.colorScheme
        var open by remember { mutableStateOf(false) }
        Box {
            Text(
                StreamQuality.label(StreamState.quality),
                color = if (dark) Color.White else cs.onSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (dark) Color.White.copy(alpha = 0.12f) else cs.surface)
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                StreamQuality.options.forEach { (q, label) ->
                    DropdownMenuItem(
                        text = { Text(if (q == "auto") "Auto (adapts to your Wi-Fi)" else label) },
                        onClick = { open = false; client.setQuality(q) },
                        trailingIcon = if (q == StreamState.quality) {
                            { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }
        }
    }

    private fun statusLine(): String =
        if (StreamState.live) {
            val d = StreamState.debug
            if (StreamState.fps == 0 && d.isNotEmpty()) "${StreamState.height}p, $d" else "${StreamState.height}p, ${StreamState.fps} fps"
        } else StreamState.status.ifBlank { "Connecting…" }

    companion object {
        private val WAITING = setOf("", "Connecting…", "Reconnecting…", "Approve this phone on your PC")
    }
}
