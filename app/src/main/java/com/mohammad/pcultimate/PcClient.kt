package com.mohammad.pcultimate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

data class MediaState(
    val title: String,
    val artist: String,
    val app: String,
    val playing: Boolean,
    val thumb: Bitmap?
)

data class Shortcut(
    val id: String,
    val name: String,
    val type: String,          // website | app | command
    val url: String = "",
    val browser: String = "",
    val profile: String = "",
    val appId: String = "",
    val appName: String = "",
    val command: String = "",
    val iconB64: String? = null,
    val icon: Bitmap? = null
) {
    fun toJson(includeIcon: Boolean): JSONObject = JSONObject().apply {
        if (id.isNotEmpty()) put("id", id)
        put("name", name); put("type", type)
        put("url", url); put("browser", browser); put("profile", profile)
        put("app_id", appId); put("app_name", appName); put("command", command)
        if (includeIcon && iconB64 != null) put("icon", iconB64)
    }

    companion object {
        fun fromJson(j: JSONObject): Shortcut {
            val b64 = if (j.isNull("icon")) null else j.optString("icon").ifEmpty { null }
            return Shortcut(
                id = j.optString("id"), name = j.optString("name"), type = j.optString("type"),
                url = j.optString("url"), browser = j.optString("browser"), profile = j.optString("profile"),
                appId = j.optString("app_id"), appName = j.optString("app_name"), command = j.optString("command"),
                iconB64 = b64, icon = b64?.let { decodeBitmap(it) }
            )
        }
    }
}

data class AppEntry(val name: String, val id: String)
data class BrowserEntry(val name: String, val path: String, val profiles: List<String>)

fun decodeBitmap(b64: String): Bitmap? = try {
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
} catch (e: Exception) { null }

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("pc", Context.MODE_PRIVATE)
    fun host(c: Context): String = p(c).getString("host", "") ?: ""
    fun port(c: Context): Int = p(c).getInt("port", 8765)
    fun pin(c: Context): String = p(c).getString("pin", "") ?: ""
    fun sens(c: Context): Float = p(c).getFloat("sens", 2.0f)
    fun save(c: Context, host: String, port: Int) {
        p(c).edit().putString("host", host).putInt("port", port).apply()
    }
    /** Random id this phone uses to identify itself once the PC has approved it. */
    fun token(c: Context): String {
        p(c).getString("token", null)?.let { return it }
        val t = java.util.UUID.randomUUID().toString().replace("-", "")
        p(c).edit().putString("token", t).apply()
        return t
    }
    fun deviceName(c: Context): String =
        (try { android.provider.Settings.Global.getString(c.contentResolver, "device_name") } catch (e: Exception) { null })
            ?.takeIf { it.isNotBlank() } ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    fun relay(c: Context): String = p(c).getString("relay", "") ?: ""
    fun roomId(c: Context): String = p(c).getString("rid", "") ?: ""
    fun saveRemote(c: Context, relay: String, rid: String) {
        p(c).edit().putString("relay", relay).putString("rid", rid).apply()
    }
    fun saveSens(c: Context, v: Float) { p(c).edit().putFloat("sens", v).apply() }
}

/** Observable state for the Compose UI. Always changed on the main thread. */
object PcState {
    var connected by mutableStateOf(false)
    var status by mutableStateOf("Not connected")
    var pcName by mutableStateOf("")
    var pcVersion by mutableStateOf("")
    var media by mutableStateOf<MediaState?>(null)
    var volume by mutableStateOf<Float?>(null)
    var shortcuts by mutableStateOf<List<Shortcut>>(emptyList())
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
    var browsers by mutableStateOf<List<BrowserEntry>>(emptyList())
    var lastSavedId by mutableStateOf("")
}

object PcClient {
    interface Listener {
        fun onStatus(connected: Boolean, text: String) {}
        fun onMedia(m: MediaState?) {}
        fun onShortcuts(items: List<Shortcut>) {}
    }

    /** Turns off Nagle's algorithm so every small message leaves immediately. */
    private object NoDelaySocketFactory : SocketFactory() {
        private val base = SocketFactory.getDefault()
        private fun tune(s: Socket): Socket = s.apply { tcpNoDelay = true }
        override fun createSocket(): Socket = tune(base.createSocket())
        override fun createSocket(host: String?, port: Int): Socket = tune(base.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            tune(base.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket = tune(base.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            tune(base.createSocket(address, port, localAddress, localPort))
    }

    private val http = OkHttpClient.Builder()
        .socketFactory(NoDelaySocketFactory)
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .build()
    val web: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentLinkedQueue<String>()
    val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var remote: RemoteLink? = null
    /** true while connected from outside the home network (WebRTC through the relay) */
    @Volatile var viaRemote = false
        private set
    @Volatile var connected = false
        private set
    @Volatile var statusText = "Not connected"
        private set
    @Volatile var media: MediaState? = null
        private set
    @Volatile private var badPin = false
    @Volatile private var failText = ""

    private val udpThread = HandlerThread("pc-udp").apply { start() }
    private val udpHandler = Handler(udpThread.looper)
    private var udpSocket: DatagramSocket? = null
    @Volatile private var udpTarget: InetAddress? = null
    @Volatile private var udpPort = 0

    private var appCtx: Context? = null
    private var lastThumbKey = ""
    private var lastThumb: Bitmap? = null

    fun init(c: Context) {
        if (appCtx != null) return
        appCtx = c.applicationContext
        loadShortcutCache()
    }

    // ------------------------------------------------------------ shortcut cache (for widgets and offline use)

    private fun cacheFile(): File? = appCtx?.let { File(it.filesDir, "shortcuts.json") }

    private fun loadShortcutCache() {
        val f = cacheFile() ?: return
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            PcState.shortcuts = (0 until arr.length()).map { Shortcut.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
        }
    }

    private fun storeShortcutCache(raw: JSONArray) {
        try { cacheFile()?.writeText(raw.toString()) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ connection

    private fun setStatus(isConnected: Boolean, text: String) {
        if (!isConnected) udpPort = 0
        connected = isConnected
        statusText = text
        main.post {
            PcState.connected = isConnected
            PcState.status = text
            listeners.forEach { it.onStatus(isConnected, text) }
        }
    }

    private fun canGoRemote(c: Context) = Prefs.relay(c).isNotBlank() && Prefs.roomId(c).isNotBlank()

    private fun onWifi(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
    } catch (e: Exception) { true }

    @Synchronized
    fun connect() {
        val c = appCtx ?: return
        if (ws != null || remote != null) return
        val host = Prefs.host(c)
        if (host.isBlank()) {
            if (canGoRemote(c)) startRemote(c) else setStatus(false, "Set up your PC to connect")
            return
        }
        // on mobile data the home address can't work: go straight to the remote path
        if (!onWifi(c) && canGoRemote(c)) { startRemote(c); return }
        setStatus(false, "Connecting…")
        val request = Request.Builder().url("ws://$host:${Prefs.port(c)}").build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val auth = JSONObject().put("t", "auth").put("token", Prefs.token(c)).put("device", Prefs.deviceName(c))
                Prefs.pin(c).takeIf { it.isNotBlank() }?.let { auth.put("pin", it) }
                webSocket.send(auth.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text) { webSocket.send(it) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropped(webSocket, if (badPin) failText else "Disconnected")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // not at home (or the PC is off): try from outside if this phone knows the way
                if (!badPin && !connected && canGoRemote(c)) {
                    synchronized(this@PcClient) { if (ws === webSocket) ws = null }
                    startRemote(c)
                    return
                }
                dropped(webSocket, if (badPin) failText else "Can't reach your PC")
            }
        })
    }

    @Synchronized
    private fun startRemote(c: Context) {
        if (remote != null) return
        setStatus(false, "Connecting from outside your home…")
        lateinit var link: RemoteLink
        link = RemoteLink(c, Prefs.relay(c), Prefs.roomId(c),
            onOpen = { viaRemote = true },
            onText = { text -> handle(text) { link.sendCtl(it) } },
            onClosed = { why, fatal ->
                synchronized(this) { if (remote === link) remote = null }
                viaRemote = false
                if (fatal) { badPin = true; failText = why }
                setStatus(false, why)
                if (!fatal) main.postDelayed({ if (!connected && listeners.isNotEmpty()) connect() }, 5000)
            })
        remote = link
        link.start()
    }

    private fun dropped(w: WebSocket, why: String) {
        synchronized(this) {
            if (ws !== w) return
            ws = null
        }
        setStatus(false, why)
        if (!badPin) main.postDelayed({ if (!connected && listeners.isNotEmpty()) connect() }, 3000)
    }

    fun reconnect() {
        badPin = false
        synchronized(this) { ws?.cancel(); ws = null; remote?.close(); remote = null }
        viaRemote = false
        connect()
    }

    private fun handle(text: String, reply: (String) -> Unit) {
        val j = try { JSONObject(text) } catch (e: Exception) { return }
        when (j.optString("t")) {
            "hello" -> {
                badPin = false
                udpPort = j.optInt("udp", 0)
                val name = j.optString("name")
                val version = j.optString("version")
                val host = appCtx?.let { Prefs.host(it) } ?: ""
                udpHandler.post { udpTarget = try { InetAddress.getByName(host) } catch (e: Exception) { null } }
                main.post { PcState.pcName = name; PcState.pcVersion = version }
                val isRemote = j.optBoolean("remote")
                if (!isRemote) {
                    val relay = j.optString("relay"); val rid = j.optString("rid")
                    if (relay.isNotBlank() && rid.isNotBlank()) appCtx?.let { Prefs.saveRemote(it, relay, rid) }
                }
                setStatus(true, if (isRemote) "Connected from outside" else "Connected")
                while (true) { val s = pending.poll() ?: break; reply(s) }
                send(JSONObject().put("t", "list_shortcuts"))
            }
            "pair_wait" -> setStatus(false, "Approve this phone on your PC")
            "auth_fail" -> {
                failText = when (j.optString("reason")) {
                    "denied" -> "Not allowed on your PC. Tap Connect to ask again"
                    "busy" -> "Your PC is answering another phone. Try again"
                    "wrong_pin" -> "Wrong PIN"
                    else -> "Your PC didn't accept this phone"
                }
                badPin = true
            }
            "media" -> {
                val m = if (!j.optBoolean("has")) null else {
                    val key = j.optString("title") + "|" + j.optString("app")
                    if (j.has("thumb")) {
                        val b64 = j.optString("thumb")
                        lastThumb = if (b64.isEmpty()) null else decodeBitmap(b64)
                        lastThumbKey = key
                    } else if (key != lastThumbKey) {
                        lastThumb = null; lastThumbKey = key
                    }
                    MediaState(j.optString("title"), j.optString("artist"), j.optString("app"),
                        j.optBoolean("playing"), lastThumb)
                }
                media = m
                main.post { PcState.media = m; listeners.forEach { it.onMedia(m) } }
            }
            "volume" -> {
                val v = if (j.isNull("v")) null else j.optDouble("v").toFloat()
                main.post { PcState.volume = v }
            }
            "shortcuts" -> {
                val arr = j.optJSONArray("items") ?: JSONArray()
                storeShortcutCache(arr)
                val items = (0 until arr.length()).map { Shortcut.fromJson(arr.getJSONObject(it)) }
                main.post { PcState.shortcuts = items; listeners.forEach { it.onShortcuts(items) } }
            }
            "apps" -> {
                val arr = j.optJSONArray("items") ?: JSONArray()
                val items = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it); AppEntry(o.optString("name"), o.optString("id"))
                }
                main.post { PcState.apps = items }
            }
            "browsers" -> {
                val arr = j.optJSONArray("items") ?: JSONArray()
                val items = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    val profs = o.optJSONArray("profiles") ?: JSONArray()
                    BrowserEntry(o.optString("name"), o.optString("path"),
                        (0 until profs.length()).map { i -> profs.getJSONObject(i).optString("name") })
                }
                main.post { PcState.browsers = items }
            }
            "saved" -> { val id = j.optString("id"); main.post { PcState.lastSavedId = id } }
        }
    }

    private fun send(o: JSONObject, queueIfOffline: Boolean = true) {
        val s = o.toString()
        val w = ws
        val r = remote
        val sent = connected && (if (w != null) w.send(s) else r?.sendCtl(s) == true)
        if (!sent) {
            if (queueIfOffline && pending.size < 10) pending.add(s)
            connect()
        }
    }

    private fun sendFast(payload: String): Boolean {
        remote?.let { if (it.open) return it.sendFast(payload) }
        val target = udpTarget
        if (!connected || target == null || udpPort == 0) return false
        val port = udpPort
        udpHandler.post {
            try {
                val sock = udpSocket ?: DatagramSocket().also { udpSocket = it }
                val b = payload.toByteArray()
                sock.send(DatagramPacket(b, b.size, target, port))
            } catch (_: Exception) {
            }
        }
        return true
    }

    fun move(dx: Int, dy: Int) {
        if (!sendFast("m,$dx,$dy")) send(JSONObject().put("t", "move").put("dx", dx).put("dy", dy), false)
    }
    fun scroll(steps: Int) {
        if (!sendFast("s,$steps")) send(JSONObject().put("t", "scroll").put("n", steps), false)
    }
    fun click(button: String) = send(JSONObject().put("t", "click").put("b", button))
    fun key(k: String) = send(JSONObject().put("t", "key").put("k", k))
    fun text(s: String) = send(JSONObject().put("t", "text").put("s", s))
    fun media(action: String) = send(JSONObject().put("t", "media").put("a", action))
    fun shortcut(id: String) = send(JSONObject().put("t", "shortcut").put("id", id))
    fun setVolume(v: Float) = send(JSONObject().put("t", "volume").put("v", v.toDouble()), false)
    fun requestApps(refresh: Boolean = false) = send(JSONObject().put("t", "list_apps").put("refresh", refresh))
    fun requestBrowsers() = send(JSONObject().put("t", "list_browsers"))
    fun saveShortcut(s: Shortcut) = send(JSONObject().put("t", "save_shortcut").put("item", s.toJson(true)))
    fun testShortcut(s: Shortcut) = send(JSONObject().put("t", "test_shortcut").put("item", s.toJson(false)))
    fun deleteShortcut(id: String) = send(JSONObject().put("t", "delete_shortcut").put("id", id))

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

    /** Downloads a site's icon (PNG, base64). Call from a background thread. */
    fun fetchFavicon(url: String): String? = try {
        val host = android.net.Uri.parse(if ("://" in url) url else "https://$url").host ?: ""
        if (host.isEmpty()) null else {
            val req = Request.Builder().url("https://www.google.com/s2/favicons?domain=$host&sz=128").build()
            web.newCall(req).execute().use { r ->
                val bytes = r.body?.bytes()
                if (!r.isSuccessful || bytes == null || bytes.size < 100) null
                else Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }
    } catch (e: Exception) { null }
}
