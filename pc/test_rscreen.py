"""Plays the phone from outside asking for the screen: connects through the internet relay in
screen mode, receives the video for 8 seconds and reports frame rate and size, then changes the
quality to 480p and checks the picture follows."""
import asyncio, json, os, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
import websockets
from aiortc import RTCPeerConnection, RTCSessionDescription, RTCConfiguration, RTCIceServer
LOG = os.path.join(HERE, "test_rscreen.log"); lines = []
def log(m):
    lines.append(time.strftime("%H:%M:%S ") + str(m)); open(LOG, "w", encoding="utf-8").write("\n".join(lines))
TOKEN = "claude-remote-test-token-0001"

async def main():
    cfg = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))
    relay = "wss://puc-relay.erenjeager078.workers.dev"; rid = cfg["remote"]["id"]
    pc = RTCPeerConnection(RTCConfiguration(iceServers=[RTCIceServer(urls=["stun:stun.cloudflare.com:3478"])]))
    pc.addTransceiver("video", direction="recvonly")
    ctl = pc.createDataChannel("ctl"); inbox = asyncio.Queue()
    ctl.on("message", lambda m: inbox.put_nowait(json.loads(m)))
    frames = []
    @pc.on("track")
    def on_track(track):
        async def pull():
            try:
                while True:
                    f = await track.recv(); frames.append((time.time(), f.width, f.height))
            except Exception:
                pass
        asyncio.create_task(pull())
    t0 = time.time()
    await pc.setLocalDescription(await pc.createOffer())
    async with websockets.connect(f"{relay}/phone?id={rid}") as ws:
        await ws.recv()
        await ws.send(json.dumps({"t": "offer", "mode": "screen", "sdp": pc.localDescription.sdp, "token": TOKEN, "device": "Remote test (Claude)"}))
        while True:
            m = json.loads(await asyncio.wait_for(ws.recv(), 30))
            if m["t"] in ("answer", "auth_fail"): break
        log(f"relay: {m['t']} {m.get('reason','')}")
        if m["t"] != "answer": return
        await pc.setRemoteDescription(RTCSessionDescription(sdp=m["sdp"], type="answer"))
    log(f"info: {await asyncio.wait_for(inbox.get(), 30)} after {time.time()-t0:.1f}s")
    await asyncio.sleep(8)
    if len(frames) > 1:
        log(f"720p check: {len(frames)} frames, {len(frames)/(frames[-1][0]-frames[0][0]):.1f} fps, {frames[-1][1]}x{frames[-1][2]}")
    else:
        log("NO FRAMES")
    ctl.send(json.dumps({"t": "quality", "q": "480"}))
    log(f"after quality: {await asyncio.wait_for(inbox.get(), 5)}")
    n0 = len(frames); await asyncio.sleep(4)
    if len(frames) > n0: log(f"480p: {len(frames)-n0} frames in 4s, {frames[-1][1]}x{frames[-1][2]}")
    await pc.close(); log("done")
try:
    asyncio.run(main())
except Exception:
    import traceback; log("FAILED " + traceback.format_exc())
