"""Plays the phone from outside: pairs a test token over the home connection (PIN), then
connects through the internet relay with WebRTC exactly like the app does on mobile data,
sends commands and pointer movement, and reports timings and the route (direct / relay)."""
import asyncio, json, os, sys, time, ctypes
from ctypes import wintypes
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import websockets
from aiortc import RTCPeerConnection, RTCSessionDescription, RTCConfiguration, RTCIceServer

LOG = os.path.join(HERE, "test_remote.log")
lines = []
def log(m):
    lines.append(time.strftime("%H:%M:%S ") + str(m))
    open(LOG, "w", encoding="utf-8").write("\n".join(lines))

TOKEN = "claude-remote-test-token-0001"
u = ctypes.windll.user32
def cursor():
    p = wintypes.POINT(); u.GetCursorPos(ctypes.byref(p)); return p.x, p.y

async def main():
    cfg = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))
    async with websockets.connect("ws://127.0.0.1:8765") as ws:
        await ws.send(json.dumps({"t": "auth", "pin": cfg["pin"], "token": TOKEN, "device": "Remote test (Claude)"}))
        hello = json.loads(await asyncio.wait_for(ws.recv(), 5))
    relay, rid = hello.get("relay"), hello.get("rid")
    log(f"home hello: relay={relay} rid={rid}")
    pc = RTCPeerConnection(RTCConfiguration(iceServers=[RTCIceServer(urls=["stun:stun.cloudflare.com:3478"])]))
    ctl = pc.createDataChannel("ctl")
    fast = pc.createDataChannel("fast", ordered=False, maxRetransmits=0)
    inbox = asyncio.Queue()
    ctl.on("message", lambda m: inbox.put_nowait(json.loads(m)))
    t0 = time.time()
    await pc.setLocalDescription(await pc.createOffer())
    async with websockets.connect(f"{relay}/phone?id={rid}") as sig:
        log(f"relay: {await sig.recv()}")
        await sig.send(json.dumps({"t": "offer", "sdp": pc.localDescription.sdp, "token": TOKEN, "device": "Remote test (Claude)"}))
        while True:
            m = json.loads(await asyncio.wait_for(sig.recv(), 30))
            if m["t"] in ("answer", "auth_fail"):
                break
        log(f"relay answered: {m['t']} {m.get('reason','')}")
        if m["t"] != "answer":
            return
        await pc.setRemoteDescription(RTCSessionDescription(sdp=m["sdp"], type="answer"))
    first = await asyncio.wait_for(inbox.get(), 30)
    log(f"connected in {time.time()-t0:.1f}s; first: {first.get('t')} remote={first.get('remote')} name={first.get('name')}")
    t1 = time.time(); ctl.send(json.dumps({"t": "list_shortcuts"}))
    while True:
        r = await asyncio.wait_for(inbox.get(), 10)
        if r.get("t") == "shortcuts":
            break
    log(f"shortcuts over remote: {len(r['items'])} items in {(time.time()-t1)*1000:.0f} ms")
    before = cursor()
    for _ in range(10):
        fast.send("m,5,0"); await asyncio.sleep(0.02)
    await asyncio.sleep(0.4)
    after = cursor()
    log(f"pointer moved by fast channel: {before} -> {after}")
    u.SetCursorPos(*before)
    await pc.close()
    log("done")

try:
    asyncio.run(main())
except Exception:
    import traceback; log("FAILED " + traceback.format_exc())
