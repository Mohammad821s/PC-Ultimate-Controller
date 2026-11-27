package com.mohammad.pcultimate

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Turning the PC on through the PC Waker (an ESP32 at home). The button exists only when this
 * phone's PC has a waker linked. The request goes to the relay with this phone's pairing token;
 * the relay checks the token against the phones the PC approved, then tells the waker.
 */
object PcWaker {
    /** this PC has a waker (remembered from the PC's hello, so it shows while the PC is off) */
    var linked by mutableStateOf(false)
    var online by mutableStateOf<Boolean?>(null)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var linkBusy by mutableStateOf(false)
    var linkMessage by mutableStateOf("")

    private val main = Handler(Looper.getMainLooper())
    private fun p(c: Context) = c.getSharedPreferences("pc", Context.MODE_PRIVATE)

    fun load(c: Context) { linked = p(c).getBoolean("waker", false) }

    fun setLinked(c: Context, v: Boolean) {
        p(c).edit().putBoolean("waker", v).apply()
        main.post { linked = v }
    }

    /** Owner tools (linking a waker) appear after 7 taps on the version in Settings. */
    fun owner(c: Context) = p(c).getBoolean("owner", false)
    fun setOwner(c: Context, v: Boolean) = p(c).edit().putBoolean("owner", v).apply()

    fun onPcMessage(c: Context, j: JSONObject) {
        when (j.optString("t")) {
            "waker_state" -> {
                if (j.has("linked")) setLinked(c, j.optBoolean("linked"))
                main.post { online = j.optBoolean("online") }
            }
            "waker_link" -> {
                val ok = j.optBoolean("ok")
                if (ok) setLinked(c, !j.optBoolean("removed"))
                main.post {
                    linkBusy = false
                    linkMessage = when {
                        ok && j.optBoolean("removed") -> "PC Waker removed"
                        ok -> "Linked. The Turn on PC button now shows when your PC is off."
                        else -> j.optString("error").ifBlank { "Couldn't link the PC Waker" }
                    }
                    if (ok && !j.optBoolean("removed")) online = true
                }
            }
        }
    }

    fun wake(c: Context) {
        if (busy) return
        val relay = Prefs.relay(c)
        val rid = Prefs.roomId(c)
        if (relay.isBlank() || rid.isBlank()) { status = "Connect to your PC once at home first"; return }
        busy = true
        status = "Sending…"
        val token = Prefs.token(c)
        var done = false
        val finish = { text: String ->
            main.post {
                if (!done) {
                    done = true; busy = false; status = text
                    // the result is only news for a minute; don't leave it on the card
                    main.postDelayed({ if (status == text) status = "" }, 60_000)
                }
            }
        }
        val req = Request.Builder().url("$relay/phone?id=$rid").build()
        val ws = PcClient.web.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (e: Exception) { return }
                when (j.optString("t")) {
                    "relay_hello" -> webSocket.send(JSONObject().put("t", "wake").put("token", token).toString())
                    "wake_result" -> {
                        finish(when {
                            j.optBoolean("already_on") -> "Your PC is already on"
                            j.optBoolean("ok") -> "Turning on… it connects by itself in about a minute"
                            j.optString("error") == "waker_offline" -> "The PC Waker is offline. Is it plugged in?"
                            j.optString("error") == "not_allowed" -> "This phone isn't allowed to turn the PC on"
                            else -> "Couldn't turn the PC on"
                        })
                        webSocket.close(1000, null)
                        if (j.optBoolean("ok") && !j.optBoolean("already_on")) {
                            // keep trying to connect while it boots
                            for (s in listOf(25L, 45L, 70L, 100L)) main.postDelayed({ if (!PcClient.connected) PcClient.connect() }, s * 1000)
                        }
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish("No internet connection to the relay")
            }
        })
        main.postDelayed({ if (!done) { ws.cancel(); finish("No answer. Try again") } }, 12000)
    }

    fun link() { linkBusy = true; linkMessage = "Looking for the PC Waker on your home network…"; PcClient.sendRaw(JSONObject().put("t", "waker_link")) }
    fun unlink() { linkBusy = true; linkMessage = ""; PcClient.sendRaw(JSONObject().put("t", "waker_unlink")) }
}
