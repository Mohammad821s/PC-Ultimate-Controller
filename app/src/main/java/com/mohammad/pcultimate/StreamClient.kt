package com.mohammad.pcultimate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Quality choices for the screen view. "auto" lets the PC adapt to the network. */
object StreamQuality {
    val options = listOf("auto" to "Auto", "1080" to "1080p", "720" to "720p", "480" to "480p")
    fun label(q: String) = options.firstOrNull { it.first == q }?.second ?: "Auto"
    fun load(c: Context): String = c.getSharedPreferences("pc", Context.MODE_PRIVATE).getString("stream_q", "auto") ?: "auto"
    fun save(c: Context, q: String) = c.getSharedPreferences("pc", Context.MODE_PRIVATE).edit().putString("stream_q", q).apply()
}

/** Observable state of the screen view, changed on the main thread. */
object StreamState {
    var status by mutableStateOf("")
    var live by mutableStateOf(false)
    var fps by mutableStateOf(0)
    var height by mutableStateOf(0)
    var screenW by mutableStateOf(16)
    var screenH by mutableStateOf(9)
    var quality by mutableStateOf("auto")
}

/**
 * Second WebSocket to the PC, used only while the screen is shown. Frames arrive as JPEG bytes;
 * each one is decoded on a background thread and acknowledged after decoding, so the PC never
 * sends faster than this phone can show.
 */
class StreamClient(private val ctx: Context, private val onFrame: (Bitmap) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val decodeThread = HandlerThread("stream-decode").apply { start() }
    private val decoder = Handler(decodeThread.looper)
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var fatal = false
    @Volatile private var pending: ByteArray? = null
    @Volatile private var decoding = false
    private val lock = Any()
    private var frames = 0
    private var fpsSince = System.currentTimeMillis()

    fun start(quality: String) {
        closed = false
        fatal = false
        StreamState.quality = quality
        val host = Prefs.host(ctx)
        if (host.isBlank()) { setStatus("Set up your PC first"); return }
        setStatus("Connecting…")
        val req = Request.Builder().url("ws://$host:${Prefs.port(ctx)}").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val auth = JSONObject().put("t", "auth").put("token", Prefs.token(ctx))
                    .put("device", Prefs.deviceName(ctx)).put("mode", "stream").put("quality", quality)
                Prefs.pin(ctx).takeIf { it.isNotBlank() }?.let { auth.put("pin", it) }
                webSocket.send(auth.toString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                synchronized(lock) {
                    if (decoding) {
                        // keep only the newest waiting frame; the one it replaces is done with
                        if (pending != null) webSocket.send(ACK)
                        pending = data
                        return
                    }
                    decoding = true
                }
                decode(webSocket, data)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (e: Exception) { return }
                when (j.optString("t")) {
                    "stream_info" -> main.post {
                        StreamState.screenW = j.optInt("w", 16)
                        StreamState.screenH = j.optInt("h", 9)
                        StreamState.height = j.optInt("height", 0)
                        StreamState.quality = j.optString("quality", "auto")
                        StreamState.live = true
                        StreamState.status = ""
                    }
                    "stream_error" -> { fatal = true; setStatus(
                        when (j.optString("reason")) {
                            "busy" -> "Another phone is watching the screen"
                            "missing_modules" -> "Update the PC app (run setup.bat) to share the screen"
                            else -> "The PC couldn't share the screen"
                        }
                    ) }
                    "pair_wait" -> setStatus("Approve this phone on your PC")
                    "auth_fail" -> { fatal = true; setStatus("Your PC didn't accept this phone") }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
        })
    }

    private fun decode(w: WebSocket, data: ByteArray) {
        decoder.post {
            var bytes: ByteArray? = data
            while (true) {
                val b = bytes ?: break
                val bmp = try { BitmapFactory.decodeByteArray(b, 0, b.size) } catch (e: Exception) { null }
                w.send(ACK)
                if (bmp != null) {
                    main.post { onFrame(bmp) }
                    countFrame(bmp.height)
                }
                val next: ByteArray?
                synchronized(lock) {
                    next = pending
                    pending = null
                    if (next == null) decoding = false
                }
                bytes = next
            }
        }
    }

    private fun countFrame(h: Int) {
        frames++
        val now = System.currentTimeMillis()
        if (now - fpsSince >= 1000) {
            val f = (frames * 1000 / (now - fpsSince)).toInt()
            frames = 0; fpsSince = now
            main.post { StreamState.fps = f; StreamState.height = h }
        }
    }

    private fun dropped(w: WebSocket) {
        if (ws !== w) return
        ws = null
        main.post { StreamState.live = false }
        if (closed || fatal) return
        setStatus("Reconnecting…")
        main.postDelayed({ if (!closed && ws == null) start(StreamState.quality) }, 2000)
    }

    private fun setStatus(s: String) = main.post { StreamState.status = s }

    private fun send(j: JSONObject) { ws?.send(j.toString()) }

    fun setQuality(q: String) {
        StreamQuality.save(ctx, q)
        StreamState.quality = q
        send(JSONObject().put("t", "quality").put("q", q))
    }

    /** x, y are 0..1 across the PC screen. */
    fun pointTo(x: Float, y: Float) = send(JSONObject().put("t", "abs").put("x", x.toDouble()).put("y", y.toDouble()))
    fun tap(x: Float, y: Float, button: String) =
        send(JSONObject().put("t", "tap").put("x", x.toDouble()).put("y", y.toDouble()).put("b", button))
    fun scroll(steps: Int) = send(JSONObject().put("t", "scroll").put("n", steps))

    fun stop() {
        closed = true
        ws?.close(1000, null)
        ws = null
        main.post { StreamState.live = false; StreamState.fps = 0 }
    }

    fun release() {
        stop()
        decodeThread.quitSafely()
    }

    companion object { private const val ACK = "{\"t\":\"ack\"}" }
}
