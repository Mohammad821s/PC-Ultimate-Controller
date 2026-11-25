package com.mohammad.pcultimate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class MediaState(
    val title: String,
    val artist: String,
    val app: String,
    val playing: Boolean,
    val thumb: Bitmap?
)

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("pc", Context.MODE_PRIVATE)
    fun host(c: Context): String = p(c).getString("host", "") ?: ""
    fun port(c: Context): Int = p(c).getInt("port", 8765)
    fun pin(c: Context): String = p(c).getString("pin", "") ?: ""
    fun sens(c: Context): Float = p(c).getFloat("sens", 2.0f)
    fun save(c: Context, host: String, port: Int, pin: String) {
        p(c).edit().putString("host", host).putInt("port", port).putString("pin", pin).apply()
    }
    fun saveSens(c: Context, v: Float) {
        p(c).edit().putFloat("sens", v).apply()
    }
}

object PcClient {
    interface Listener {
        fun onStatus(connected: Boolean, text: String) {}
        fun onMedia(m: MediaState?) {}
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentLinkedQueue<String>()
    val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile private var ws: WebSocket? = null
    @Volatile var connected = false
        private set
    @Volatile var statusText = "غير متصل"
        private set
    @Volatile var media: MediaState? = null
        private set
    @Volatile var pcName = ""
        private set
    @Volatile private var badPin = false

    private var appCtx: Context? = null
    private var lastThumbKey = ""
    private var lastThumb: Bitmap? = null

    fun init(c: Context) {
        if (appCtx == null) appCtx = c.applicationContext
    }

    private fun setStatus(isConnected: Boolean, text: String) {
        connected = isConnected
        statusText = text
        main.post { listeners.forEach { it.onStatus(isConnected, text) } }
    }

    @Synchronized
    fun connect() {
        val c = appCtx ?: return
        if (ws != null) return
        val host = Prefs.host(c)
        if (host.isBlank()) {
            setStatus(false, "اضبط عنوان الكمبيوتر من الإعدادات")
            return
        }
        setStatus(false, "جاري الاتصال…")
        val request = Request.Builder().url("ws://$host:${Prefs.port(c)}").build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("t", "auth").put("pin", Prefs.pin(c)).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropped(webSocket, if (badPin) "الرمز (PIN) غلط" else "انقطع الاتصال")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dropped(webSocket, if (badPin) "الرمز (PIN) غلط" else "ما كدرت أوصل للكمبيوتر")
            }
        })
    }

    private fun dropped(w: WebSocket, why: String) {
        synchronized(this) {
            if (ws !== w) return
            ws = null
        }
        setStatus(false, why)
        if (!badPin) {
            main.postDelayed({ if (!connected && listeners.isNotEmpty()) connect() }, 3000)
        }
    }

    fun reconnect() {
        badPin = false
        synchronized(this) {
            ws?.cancel()
            ws = null
        }
        connect()
    }

    private fun handle(w: WebSocket, text: String) {
        val j = try { JSONObject(text) } catch (e: Exception) { return }
        when (j.optString("t")) {
            "hello" -> {
                badPin = false
                pcName = j.optString("name")
                setStatus(true, "متصل بـ $pcName")
                while (true) {
                    val s = pending.poll() ?: break
                    w.send(s)
                }
            }
            "auth_fail" -> badPin = true
            "media" -> {
                val m = if (!j.optBoolean("has")) null else {
                    val key = j.optString("title") + "|" + j.optString("app")
                    if (j.has("thumb")) {
                        val b64 = j.optString("thumb")
                        lastThumb = if (b64.isEmpty()) null else try {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (e: Exception) { null }
                        lastThumbKey = key
                    } else if (key != lastThumbKey) {
                        lastThumb = null
                        lastThumbKey = key
                    }
                    MediaState(
                        j.optString("title"), j.optString("artist"), j.optString("app"),
                        j.optBoolean("playing"), lastThumb
                    )
                }
                media = m
                main.post { listeners.forEach { it.onMedia(m) } }
            }
        }
    }

    private fun send(o: JSONObject, queueIfOffline: Boolean = true) {
        val s = o.toString()
        val w = ws
        if (connected && w != null) {
            w.send(s)
        } else {
            if (queueIfOffline && pending.size < 10) pending.add(s)
            connect()
        }
    }

    fun move(dx: Int, dy: Int) = send(JSONObject().put("t", "move").put("dx", dx).put("dy", dy), false)
    fun click(button: String) = send(JSONObject().put("t", "click").put("b", button))
    fun scroll(steps: Int) = send(JSONObject().put("t", "scroll").put("n", steps), false)
    fun key(k: String) = send(JSONObject().put("t", "key").put("k", k))
    fun text(s: String) = send(JSONObject().put("t", "text").put("s", s))
    fun media(action: String) = send(JSONObject().put("t", "media").put("a", action))
    fun shortcut(id: String) = send(JSONObject().put("t", "shortcut").put("id", id))

    /** Finds the PC on the local network. Call from a background thread. */
    fun discover(): Triple<String, Int, String>? = try {
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 2000
            val d = "PUC?".toByteArray()
            s.send(DatagramPacket(d, d.size, InetAddress.getByName("255.255.255.255"), 8766))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val j = JSONObject(String(p.data, 0, p.length))
            Triple(p.address.hostAddress ?: "", j.optInt("port", 8765), j.optString("name"))
        }
    } catch (e: Exception) {
        null
    }
}
