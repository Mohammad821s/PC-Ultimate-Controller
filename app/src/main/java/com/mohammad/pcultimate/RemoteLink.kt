package com.mohammad.pcultimate

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpTransceiver
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Connection to the PC from any network: the relay introduces us, then WebRTC data channels
 * carry the same messages as the local WebSocket ("ctl") and the fast pointer input ("fast").
 * The PC only answers phones it has already approved (same pairing token as at home).
 */
class RemoteLink(
    private val ctx: Context,
    private val relay: String,
    private val roomId: String,
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit,
    private val onClosed: (why: String, fatal: Boolean) -> Unit,
    /** screen mode: receive the PC screen as video; frames arrive here as bitmaps */
    private val onFrame: ((android.graphics.Bitmap) -> Unit)? = null,
) {
    private val screen = onFrame != null
    private var converter: FrameConverter? = null
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var signal: WebSocket? = null
    private var pc: PeerConnection? = null
    private var ctl: DataChannel? = null
    private var fast: DataChannel? = null
    @Volatile private var finished = false
    @Volatile var open = false
        private set
    private var offerSent = false

    fun start() {
        val factory = factory(ctx)
        val servers = listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val peer = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) sendOffer()
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                when (state) {
                    PeerConnection.PeerConnectionState.FAILED -> finish("Couldn't reach your PC from this network", false)
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.CLOSED -> if (open) finish("Disconnected", false)
                    else -> {}
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
            override fun onTrack(t: RtpTransceiver) {
                main.post { StreamState.debug = "video: track arrived" }
                val track = t.receiver.track() as? VideoTrack ?: run {
                    main.post { StreamState.debug = "video: track is not video (${t.mediaType})" }
                    return
                }
                val cb = onFrame ?: return
                val conv = FrameConverter(cb).also { converter = it }
                track.addSink(conv)
            }
        }) ?: run { finish("WebRTC isn't available on this phone", true); return }
        pc = peer

        ctl = peer.createDataChannel("ctl", DataChannel.Init()).also { ch ->
            ch.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(prev: Long) {}
                override fun onStateChange() {
                    if (ch.state() == DataChannel.State.OPEN && !open) {
                        open = true
                        signal?.close(1000, null) // the relay isn't needed any more
                        signal = null
                        main.post(onOpen)
                    } else if (ch.state() == DataChannel.State.CLOSED && open) {
                        finish("Disconnected", false)
                    }
                }
                override fun onMessage(b: DataChannel.Buffer) {
                    val bytes = ByteArray(b.data.remaining()).also { b.data.get(it) }
                    onText(String(bytes, StandardCharsets.UTF_8))
                }
            })
        }
        if (screen) {
            peer.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        } else {
            fast = peer.createDataChannel("fast", DataChannel.Init().apply { ordered = false; maxRetransmits = 0 })
        }

        peer.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peer.setLocalDescription(SdpAdapter(), sdp)
            }
            override fun onCreateFailure(e: String?) = finish("Couldn't start the connection", false)
        }, MediaConstraints())
        // don't wait forever for slow candidates
        main.postDelayed({ sendOffer() }, 4000)
        main.postDelayed({ if (!open) finish("Couldn't reach your PC from this network", false) }, 30000)

        val url = relay.trimEnd('/') + "/phone?id=" + roomId
        signal = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = onSignal(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!open) finish("No internet, or the relay can't be reached", false)
            }
        })
    }

    @Synchronized
    private fun sendOffer() {
        if (offerSent || finished) return
        val peer = pc ?: return
        val local = peer.localDescription ?: return
        val ws = signal ?: return
        offerSent = true
        ws.send(
            JSONObject().put("t", "offer").put("sdp", local.description)
                .put("mode", if (screen) "screen" else "control")
                .put("token", Prefs.token(ctx)).put("device", Prefs.deviceName(ctx)).toString()
        )
    }

    private fun onSignal(text: String) {
        val j = try { JSONObject(text) } catch (e: Exception) { return }
        when (j.optString("t")) {
            "relay_hello" -> if (!j.optBoolean("pc_online")) finish("Your PC is off or not connected to the internet", false)
            "pc_offline" -> if (!open) finish("Your PC is off or not connected to the internet", false)
            "answer" -> pc?.setRemoteDescription(SdpAdapter(), SessionDescription(SessionDescription.Type.ANSWER, j.optString("sdp")))
            "auth_fail" -> finish(
                when (j.optString("reason")) {
                    "not_paired" -> "Pair this phone with your PC at home first"
                    "busy" -> "Your PC is busy with other phones"
                    "no_screen" -> "Update the PC app (run setup.bat) to share the screen"
                    else -> "Your PC didn't accept this phone"
                }, true
            )
        }
    }

    fun sendCtl(s: String): Boolean {
        val ch = ctl ?: return false
        if (ch.state() != DataChannel.State.OPEN) return false
        return ch.send(DataChannel.Buffer(ByteBuffer.wrap(s.toByteArray(StandardCharsets.UTF_8)), false))
    }

    fun sendFast(s: String): Boolean {
        val ch = fast ?: return false
        if (ch.state() != DataChannel.State.OPEN) return false
        return ch.send(DataChannel.Buffer(ByteBuffer.wrap(s.toByteArray(StandardCharsets.US_ASCII)), false))
    }

    @Synchronized
    private fun finish(why: String, fatal: Boolean) {
        if (finished) return
        finished = true
        open = false
        main.post { onClosed(why, fatal) }
        close()
    }

    fun close() {
        finished = true
        open = false
        signal?.close(1000, null); signal = null
        val p = pc
        pc = null
        main.post {
            converter?.stop()
            try { ctl?.dispose(); fast?.dispose(); p?.dispose() } catch (_: Exception) {}
            ctl = null; fast = null
        }
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    companion object {
        private var shared: PeerConnectionFactory? = null
        @Synchronized
        fun factory(c: Context): PeerConnectionFactory {
            shared?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(c.applicationContext).createInitializationOptions()
            )
            // software decoding hands us plain I420 frames, simple to turn into bitmaps
            return PeerConnectionFactory.builder()
                .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
                .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
                .createPeerConnectionFactory().also { shared = it }
        }
    }
}

/**
 * Turns decoded video frames into bitmaps for StreamView. Converts on its own thread and
 * skips frames while busy, so a slow phone shows fewer frames instead of falling behind.
 */
class FrameConverter(private val onFrame: (android.graphics.Bitmap) -> Unit) : VideoSink {
    // (counters below are shown on screen while no picture has appeared, to find where it stops)
    private val thread = android.os.HandlerThread("frame-convert").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    @Volatile private var stopped = false
    private var pixels = IntArray(0)
    private val pool = ArrayDeque<android.graphics.Bitmap>()

    private var rx = 0
    private var shown = 0
    private var lastError = ""
    private val ticker = object : Runnable {
        override fun run() {
            if (stopped) return
            StreamState.debug = if (shown == 0) "video: received $rx, shown $shown${if (lastError.isNotEmpty()) ", $lastError" else ""}" else ""
            main.postDelayed(this, 1000)
        }
    }
    init { main.postDelayed(ticker, 1500) }

    override fun onFrame(frame: VideoFrame) {
        rx++
        if (busy || stopped) return
        busy = true
        frame.retain()
        handler.post {
            try {
                val i420 = frame.buffer.toI420()
                if (i420 != null) {
                    val bmp = convert(i420, frame.rotation)
                    i420.release()
                    shown++
                    val deliver = this.onFrame
                    main.post { if (!stopped) deliver(bmp) }
                }
            } catch (e: Throwable) {
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            } finally {
                frame.release()
                busy = false
            }
        }
    }

    private fun convert(b: VideoFrame.I420Buffer, rotation: Int): android.graphics.Bitmap {
        val w = b.width
        val h = b.height
        if (pixels.size != w * h) pixels = IntArray(w * h)
        val y = b.dataY; val u = b.dataU; val v = b.dataV
        val sy = b.strideY; val su = b.strideU; val sv = b.strideV
        var o = 0
        for (j in 0 until h) {
            val yr = j * sy
            val ur = (j shr 1) * su
            val vr = (j shr 1) * sv
            for (i in 0 until w) {
                val yy = ((y.get(yr + i).toInt() and 0xff) - 16).coerceAtLeast(0) * 298
                val uu = (u.get(ur + (i shr 1)).toInt() and 0xff) - 128
                val vv = (v.get(vr + (i shr 1)).toInt() and 0xff) - 128
                val r = ((yy + 409 * vv + 128) shr 8).coerceIn(0, 255)
                val g = ((yy - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255)
                val bl = ((yy + 516 * uu + 128) shr 8).coerceIn(0, 255)
                pixels[o++] = -0x1000000 or (r shl 16) or (g shl 8) or bl
            }
        }
        // three bitmaps in turn: one shown, one drawn, one being filled
        val bmp = synchronized(pool) {
            val reuse = pool.firstOrNull { it.width == w && it.height == h }
            if (reuse != null && pool.size >= 3) { pool.remove(reuse); reuse } else
                android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        synchronized(pool) { pool.addLast(bmp); while (pool.size > 3) pool.removeFirst() }
        if (rotation == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
    }

    fun stop() {
        stopped = true
        thread.quitSafely()
    }
}
