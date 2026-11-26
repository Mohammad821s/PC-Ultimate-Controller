package com.mohammad.pcultimate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

sealed class Screen {
    data object Home : Screen()
    data object Settings : Screen()
    data class Editor(val shortcut: Shortcut?) : Screen()
}

class MainActivity : ComponentActivity(), PcClient.Listener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PcClient.init(this)
        AppSettings.load(this)
        enableEdgeToEdge()

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(PcService.intent(this, PcService.ACTION_START))

        val firstRun = Prefs.host(this).isBlank()
        setContent {
            AppTheme {
                var screen by remember { mutableStateOf<Screen>(if (firstRun) Screen.Settings else Screen.Home) }
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "screen"
                ) { s ->
                    when (s) {
                        Screen.Home -> HomeScreen(
                            onSettings = { screen = Screen.Settings },
                            onAddShortcut = { screen = Screen.Editor(null) },
                            onEditShortcut = { screen = Screen.Editor(it) },
                            onFloating = { openFloating() },
                            onConnect = { screen = Screen.Settings },
                            onScreen = { startActivity(Intent(this@MainActivity, StreamActivity::class.java)) },
                        )
                        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
                        is Screen.Editor -> EditorScreen(s.shortcut, onDone = { screen = Screen.Home })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PcClient.listeners.add(this)
        if (!PcClient.connected) PcClient.connect()
    }

    override fun onPause() {
        PcClient.listeners.remove(this)
        super.onPause()
    }

    private fun openFloating() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Appear on top\" for PC Ultimate Controller, then come back", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(PcService.intent(this, PcService.ACTION_TOUCHPAD))
        moveTaskToBack(true)
    }
}
