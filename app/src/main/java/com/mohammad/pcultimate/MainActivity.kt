package com.mohammad.pcultimate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), PcClient.Listener {

    private lateinit var status: TextView
    private lateinit var dot: View
    private lateinit var mediaTitle: TextView
    private lateinit var mediaSub: TextView
    private lateinit var mediaArt: ImageView
    private lateinit var playBtn: TextView
    private lateinit var pad: TouchpadView

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PcClient.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val p = dp(12f)
            setPadding(p, p, p, p)
        }

        // ---- status bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(this).apply { background = Ui.round(this@MainActivity, 0xFFE5534B.toInt(), 6f) }
        status = TextView(this).apply {
            setTextColor(Ui.TEXT)
            textSize = 14f
            setPadding(dp(8f), 0, dp(8f), 0)
        }
        top.addView(dot, LinearLayout.LayoutParams(dp(12f), dp(12f)))
        top.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.button(this, "⚙ الإعدادات") { showSettings() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)))
        root.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- now playing
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(this@MainActivity, Ui.CARD, 18f)
            val p = dp(12f)
            setPadding(p, p, p, p)
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mediaArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.round(this@MainActivity, Ui.BTN, 10f)
            clipToOutline = true
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), 0, dp(10f), 0)
        }
        mediaTitle = TextView(this).apply { setTextColor(Ui.TEXT); textSize = 15f; maxLines = 1 }
        mediaSub = TextView(this).apply { setTextColor(Ui.MUTED); textSize = 13f; maxLines = 1 }
        texts.addView(mediaTitle)
        texts.addView(mediaSub)
        info.addView(mediaArt, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        info.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(info)

        val controls = row()
        playBtn = Ui.button(this, "⏯", Ui.ACCENT) { PcClient.media("playpause") }
        controls.addView(Ui.button(this, "⏮") { PcClient.media("prev") }, cell())
        controls.addView(playBtn, cell())
        controls.addView(Ui.button(this, "⏭") { PcClient.media("next") }, cell())
        controls.addView(Ui.button(this, "🔉") { PcClient.media("voldown") }, cell())
        controls.addView(Ui.button(this, "🔊") { PcClient.media("volup") }, cell())
        controls.addView(Ui.button(this, "🔇") { PcClient.media("mute") }, cell())
        controls.addView(Ui.button(this, "⛶") { PcClient.media("fullscreen") }, cell())
        card.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f)))
        root.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })

        // ---- shortcuts
        val shortcuts = row()
        shortcuts.addView(Ui.button(this, "YouTube", 0xFF7F1D1D.toInt()) { PcClient.shortcut("youtube") }, cell())
        shortcuts.addView(Ui.button(this, "Netflix", 0xFF5B1414.toInt()) { PcClient.shortcut("netflix") }, cell())
        shortcuts.addView(Ui.button(this, "Crunchyroll", 0xFF7C3A10.toInt()) { PcClient.shortcut("crunchyroll") }, cell())
        shortcuts.addView(Ui.button(this, "Fortnite", 0xFF3B2A6B.toInt()) { PcClient.shortcut("fortnite") }, cell())
        root.addView(shortcuts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56f)).apply { topMargin = dp(8f) })

        // ---- touchpad
        pad = TouchpadView(this)
        root.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8f) })

        val clicks = row()
        clicks.addView(Ui.button(this, "كلك يسار") { PcClient.click("left") }, cell())
        clicks.addView(Ui.button(this, "كلك يمين") { PcClient.click("right") }, cell())
        root.addView(clicks, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = dp(4f) })

        // ---- keyboard
        val kb = row()
        val input = EditText(this).apply {
            hint = "اكتب هنا وارسله للكمبيوتر"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 14f
            background = Ui.round(this@MainActivity, Ui.CARD, 14f)
            setPadding(dp(12f), 0, dp(12f), 0)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val sendText = {
            val s = input.text.toString()
            if (s.isNotEmpty()) PcClient.text(s)
            input.setText("")
        }
        input.setOnEditorActionListener { _, _, _ -> sendText(); true }
        kb.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f).apply { setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) })
        kb.addView(Ui.button(this, "إرسال") { sendText() }, cell())
        kb.addView(Ui.button(this, "⌫") { PcClient.key("backspace") }, cell())
        kb.addView(Ui.button(this, "↵") { PcClient.key("enter") }, cell())
        root.addView(kb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = dp(4f) })

        // ---- floating touchpad
        root.addView(
            Ui.button(this, "🖱 تاتش باد عائم", Ui.ACCENT) { openFloating() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48f)).apply { topMargin = dp(6f) }
        )

        setContentView(root)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(PcService.intent(this, PcService.ACTION_START))
        if (Prefs.host(this).isBlank()) showSettings()
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    private fun cell() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
        setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
    }

    override fun onResume() {
        super.onResume()
        PcClient.listeners.add(this)
        pad.sensitivity = Prefs.sens(this)
        onStatus(PcClient.connected, PcClient.statusText)
        onMedia(PcClient.media)
        if (!PcClient.connected) PcClient.connect()
    }

    override fun onPause() {
        PcClient.listeners.remove(this)
        super.onPause()
    }

    override fun onStatus(connected: Boolean, text: String) {
        status.text = text
        dot.background = Ui.round(this, if (connected) 0xFF3FB950.toInt() else 0xFFE5534B.toInt(), 6f)
    }

    override fun onMedia(m: MediaState?) {
        if (m == null) {
            mediaTitle.text = "ماكو شي يشتغل"
            mediaSub.text = "شغّل فيديو على الكمبيوتر"
            mediaArt.setImageDrawable(null)
            return
        }
        mediaTitle.text = m.title.ifBlank { m.app }
        mediaSub.text = listOf(m.artist, m.app).filter { it.isNotBlank() }.joinToString(" • ")
        mediaArt.setImageBitmap(m.thumb)
        playBtn.text = if (m.playing) "⏸" else "▶"
    }

    private fun openFloating() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل إذن الظهور فوق التطبيقات ثم ارجع", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(PcService.intent(this, PcService.ACTION_TOUCHPAD))
        moveTaskToBack(true)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20f)
            setPadding(p, dp(8f), p, 0)
        }
        fun field(hintText: String, value: String, numeric: Boolean) = EditText(this).apply {
            hint = hintText
            setText(value)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val host = field("عنوان IP للكمبيوتر", Prefs.host(this), false)
        val port = field("المنفذ", Prefs.port(this).toString(), true)
        val pin = field("الرمز (PIN)", Prefs.pin(this), true)
        val found = TextView(this).apply { setPadding(0, dp(6f), 0, dp(6f)) }
        val search = Ui.button(this, "🔎 بحث تلقائي عن الكمبيوتر") {
            found.text = "جاري البحث…"
            Thread {
                val r = PcClient.discover()
                runOnUiThread {
                    if (r == null) {
                        found.text = "ما لكيته. تأكد إن البرنامج شغّال على الكمبيوتر والجدار الناري سامح له."
                    } else {
                        host.setText(r.first)
                        port.setText(r.second.toString())
                        found.text = "لكيت: ${r.third}"
                    }
                }
            }.start()
        }
        val sensLabel = TextView(this).apply { text = "سرعة الماوس" ; setPadding(0, dp(12f), 0, 0) }
        val sens = SeekBar(this).apply {
            max = 50
            progress = ((Prefs.sens(this@MainActivity) - 0.5f) * 10f).toInt().coerceIn(0, 50)
        }

        box.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46f)))
        box.addView(found)
        box.addView(host)
        box.addView(port)
        box.addView(pin)
        box.addView(sensLabel)
        box.addView(sens)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("الاتصال بالكمبيوتر")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                Prefs.save(this, host.text.toString().trim(), port.text.toString().toIntOrNull() ?: 8765, pin.text.toString().trim())
                val s = 0.5f + sens.progress / 10f
                Prefs.saveSens(this, s)
                pad.sensitivity = s
                PcClient.reconnect()
            }
            .setNegativeButton("إلغاء", null)
            .show()

        if (Prefs.host(this).isBlank()) search.performClick()
    }
}
