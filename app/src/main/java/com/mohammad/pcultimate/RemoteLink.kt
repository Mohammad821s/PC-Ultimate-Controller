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
) {
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
        fast = peer.createDataChannel("fast", DataChannel.Init().apply { ordered = false; maxRetransmits = 0 })

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
            return PeerConnectionFactory.builder().createPeerConnectionFactory().also { shared = it }
        }
    }
}
