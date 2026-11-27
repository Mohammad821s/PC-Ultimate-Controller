"""Remote access: lets an already-paired phone control this PC from any network.

The PC keeps a small WebSocket open to the relay (a Cloudflare Worker). When a phone wants
to connect it sends a WebRTC offer through the relay together with its pairing token; if the
token belongs to a phone that was approved on this PC, the PC answers and the two connect
directly (or through Cloudflare's TURN relay when the networks don't allow a direct path).

Two data channels carry the same messages as the local connection:
  "ctl"  - reliable JSON commands and replies (same protocol as the local WebSocket)
  "fast" - unreliable pointer movement / scrolling ("m,dx,dy" / "s,n"), like the UDP port
"""
import asyncio
import json
import secrets
import string
import time

try:
    from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription
    import websockets
    AVAILABLE = True
except Exception:  # pragma: no cover
    AVAILABLE = False

MAX_PEERS = 3
TURN_REFRESH_S = 5 * 3600


def new_identity():
    alphabet = string.ascii_lowercase + string.digits
    return {
        "id": "".join(secrets.choice(alphabet) for _ in range(20)),
        "key": secrets.token_urlsafe(32),
    }


class ChannelClient:
    """Looks like a WebSocket to the rest of the server (it only needs .send)."""

    def __init__(self, channel):
        self.channel = channel

    async def send(self, data):
        if self.channel.readyState != "open":
            raise ConnectionError("channel closed")
        self.channel.send(data)


class RemoteAgent:
    def __init__(self, relay_url, room_id, key, *, is_paired, handle, fast, hello_messages,
                 add_client, remove_client, log, on_status=None, screen_factory=None):
        self.relay_url = relay_url.rstrip("/")
        self.room_id = room_id
        self.key = key
        self.is_paired = is_paired          # token -> bool
        self.handle = handle                # async msg -> reply dict | None
        self.fast = fast                    # "m,dx,dy" / "s,n"
        self.hello_messages = hello_messages  # () -> list of dicts sent when a phone connects
        self.add_client = add_client
        self.remove_client = remove_client
        self.log = log
        self.on_status = on_status or (lambda s: None)
        # async () -> object with .track, .info() -> dict, async .command(dict) -> dict|None, .close()
        self.screen_factory = screen_factory
        self.ws = None
        self.peers = {}                     # relay sid -> RTCPeerConnection
        self.ice = None
        self.ice_at = 0
        self.turn_waiters = {}
        self.turn_req = 0
        self.status = "starting"

    def _status(self, s):
        if s != self.status:
            self.status = s
            self.log(f"remote: {s}")
            self.on_status(s)

    # ------------------------------------------------------------ relay connection

    async def run(self):
        delay = 2
        while True:
            url = f"{self.relay_url}/pc?id={self.room_id}&key={self.key}"
            try:
                async with websockets.connect(url, ping_interval=None, max_size=2 ** 20) as ws:
                    self.ws = ws
                    delay = 2
                    self._status("online")
                    keep = asyncio.create_task(self._keepalive(ws))
                    try:
                        async for raw in ws:
                            try:
                                await self._on_relay(json.loads(raw))
                            except Exception as e:
                                self.log(f"remote: message error {e!r}")
                    finally:
                        keep.cancel()
            except Exception as e:
                self._status(f"offline ({type(e).__name__})")
            self.ws = None
            await asyncio.sleep(delay)
            delay = min(delay * 2, 60)

    async def _keepalive(self, ws):
        # answered by the relay itself without waking it (see relay/src/index.js)
        while True:
            await asyncio.sleep(25)
            await ws.send('{"t":"ping"}')

    async def _send_relay(self, msg):
        if self.ws:
            await self.ws.send(json.dumps(msg))

    async def _ice_servers(self):
        if self.ice and time.time() - self.ice_at < TURN_REFRESH_S:
            return self.ice
        self.turn_req += 1
        rid = self.turn_req
        fut = asyncio.get_running_loop().create_future()
        self.turn_waiters[rid] = fut
        try:
            await self._send_relay({"t": "turn", "req": rid})
            servers = await asyncio.wait_for(fut, 8)
        except Exception:
            servers = [{"urls": ["stun:stun.cloudflare.com:3478"]}]
        finally:
            self.turn_waiters.pop(rid, None)
        has_turn = any("turn:" in u or "turns:" in u for sv in servers for u in
                       (sv.get("urls") if isinstance(sv.get("urls"), list) else [sv.get("urls", "")]))
        # without TURN, ask again in 10 minutes instead of keeping the answer for hours
        self.ice = servers
        self.ice_at = time.time() if has_turn else time.time() - TURN_REFRESH_S + 600
        return servers

    async def _on_relay(self, m):
        t = m.get("t")
        if t == "turn":
            fut = self.turn_waiters.get(m.get("req"))
            if fut and not fut.done():
                fut.set_result(m.get("iceServers") or [])
        elif t == "offer":
            asyncio.create_task(self._answer(m))
        elif t == "peer_left":
            pass  # the WebRTC connection keeps working without the relay
        elif t == "pong":
            pass

    # ------------------------------------------------------------ WebRTC

    async def _answer(self, m):
        sid = m.get("from")
        token = str(m.get("token") or "")
        name = str(m.get("device") or "Phone")[:60]
        if not sid:
            return
        if not token or not self.is_paired(token):
            self.log(f"remote: refused {name} (not paired)")
            await self._send_relay({"t": "auth_fail", "reason": "not_paired", "to": sid})
            return
        if len(self.peers) >= MAX_PEERS:
            await self._send_relay({"t": "auth_fail", "reason": "busy", "to": sid})
            return
        screen = None
        if m.get("mode") == "screen":
            if not self.screen_factory:
                await self._send_relay({"t": "auth_fail", "reason": "no_screen", "to": sid})
                return
            try:
                screen = await self.screen_factory()
            except Exception as e:
                self.log(f"remote: screen unavailable {e!r}")
                await self._send_relay({"t": "auth_fail", "reason": "no_screen", "to": sid})
                return
        servers = await self._ice_servers()
        config = RTCConfiguration(iceServers=[
            RTCIceServer(urls=s["urls"], username=s.get("username"), credential=s.get("credential"))
            for s in servers
        ])
        pc = RTCPeerConnection(configuration=config)
        self.peers[sid] = pc
        clients = []

        @pc.on("datachannel")
        def on_channel(ch):
            if screen is not None:
                # the screen connection: touch / quality commands, no broadcasts
                async def screen_open():
                    ch.send(json.dumps(screen.info()))

                @ch.on("message")
                def on_screen_msg(msg):
                    async def run():
                        try:
                            reply = await screen.command(json.loads(msg))
                            if reply and ch.readyState == "open":
                                ch.send(json.dumps(reply))
                        except Exception as e:
                            self.log(f"remote: screen command error {e!r}")
                    asyncio.create_task(run())

                if ch.readyState == "open":
                    asyncio.create_task(screen_open())
                else:
                    ch.on("open", lambda: asyncio.create_task(screen_open()))
                return
            if ch.label == "fast":
                @ch.on("message")
                def on_fast(msg):
                    if isinstance(msg, str):
                        self.fast(msg)
                return
            client = ChannelClient(ch)
            clients.append(client)

            async def opened():
                for hm in self.hello_messages():
                    await client.send(json.dumps(hm, ensure_ascii=False))
                self.add_client(client)

            @ch.on("message")
            def on_ctl(msg):
                asyncio.create_task(self._command(client, msg))

            @ch.on("close")
            def on_close():
                self.remove_client(client)

            if ch.readyState == "open":
                asyncio.create_task(opened())
            else:
                ch.on("open", lambda: asyncio.create_task(opened()))

        @pc.on("connectionstatechange")
        async def on_state():
            st = pc.connectionState
            if st == "connected":
                kind = await self._route(pc)
                what = "screen" if screen is not None else "control"
                self.log(f"remote: {name} connected, {what} ({kind})")
            if st in ("failed", "closed", "disconnected"):
                await self._drop(sid, pc, clients, screen)

        try:
            await pc.setRemoteDescription(RTCSessionDescription(sdp=m["sdp"], type="offer"))
            if screen is not None:
                pc.addTrack(screen.track)
                if getattr(screen, "audio_track", None) is not None:
                    pc.addTrack(screen.audio_track)
            await pc.setLocalDescription(await pc.createAnswer())  # gathers all candidates
            await self._send_relay({"t": "answer", "sdp": pc.localDescription.sdp, "to": sid})
        except Exception as e:
            self.log(f"remote: answer failed {e!r}")
            await self._drop(sid, pc, clients, screen)
            return
        # give up if the phone never manages to connect
        await asyncio.sleep(45)
        if pc.connectionState not in ("connected", "closed"):
            self.log(f"remote: {name} could not connect")
            await self._drop(sid, pc, clients, screen)

    async def _route(self, pc):
        """'direct' or 'relay' (TURN), for the log and the phone's status line."""
        try:
            ice = pc.sctp.transport.transport  # RTCDtlsTransport -> RTCIceTransport
            pair = ice._connection._nominated.get(1) if hasattr(ice, "_connection") else None
            if pair and (pair.local_candidate.type == "relay" or pair.remote_candidate.type == "relay"):
                return "relay"
        except Exception:
            pass
        return "direct"

    async def _command(self, client, raw):
        try:
            reply = await self.handle(json.loads(raw))
            if reply:
                await client.send(json.dumps(reply, ensure_ascii=False))
        except Exception as e:
            self.log(f"remote: command error {e!r}")

    async def _drop(self, sid, pc, clients, screen=None):
        if screen is not None:
            try:
                screen.close()
            except Exception:
                pass
        if self.peers.get(sid) is pc:
            self.peers.pop(sid, None)
        for c in clients:
            self.remove_client(c)
        try:
            await pc.close()
        except Exception:
            pass
