"""Checks the PC sound reaches a phone: plays a quiet 2-second tone on the PC, and listens as
the phone would, at home (stream WebSocket) and from outside (WebRTC through the relay)."""
import asyncio, json, os, sys, time, threading, math
HERE = os.path.dirname(os.path.abspath(__file__))
import numpy as np, soundcard as sc, websockets
from aiortc import RTCPeerConnection, RTCSessionDescription, RTCConfiguration, RTCIceServer
LOG = os.path.join(HERE, "test_audio.log"); lines = []
def log(m):
    lines.append(time.strftime("%H:%M:%S ") + str(m)); open(LOG, "w", encoding="utf-8").write("\n".join(lines))
TOKEN = "claude-remote-test-token-0001"

def tone(seconds=2.5):
    import ctypes; ctypes.windll.ole32.CoInitializeEx(None, 0)
    t = np.arange(int(48000 * seconds)) / 48000
    wave = (0.04 * np.sin(2 * math.pi * 440 * t)).astype(np.float32)
    sc.default_speaker().play(np.stack([wave, wave], 1), samplerate=48000)

async def home():
    cfg = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))
    async with websockets.connect("ws://127.0.0.1:8765", max_size=2**24) as ws:
        await ws.send(json.dumps({"t": "auth", "pin": cfg["pin"], "mode": "stream", "quality": "480", "audio": True}))
        threading.Thread(target=tone, daemon=True).start()
        got, rms, t0 = 0, [], time.time()
        while time.time() - t0 < 3:
            m = await asyncio.wait_for(ws.recv(), 5)
            if isinstance(m, bytes):
                if m[:1] == b"A":
                    a = np.frombuffer(m[1:], dtype="<i2").astype(float); got += len(m) - 1; rms.append(np.sqrt((a**2).mean()))
                else:
                    await ws.send('{"t":"ack"}')
            elif '"stream_info"' in m:
                log(f"home info: {m}")
        log(f"home audio: {got/192000:.2f} s of sound received in 3 s, loudest rms {max(rms) if rms else 0:.0f}")

async def outside():
    cfg = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))
    pc = RTCPeerConnection(RTCConfiguration(iceServers=[RTCIceServer(urls=["stun:stun.cloudflare.com:3478"])]))
    pc.addTransceiver("video", direction="recvonly"); pc.addTransceiver("audio", direction="recvonly")
    ctl = pc.createDataChannel("ctl"); inbox = asyncio.Queue(); ctl.on("message", lambda m: inbox.put_nowait(m))
    rms = []
    @pc.on("track")
    def on_track(track):
        async def pull():
            try:
                while True:
                    f = await track.recv()
                    if track.kind == "audio":
                        a = f.to_ndarray().astype(float); rms.append(np.sqrt((a**2).mean()))
            except Exception:
                pass
        asyncio.create_task(pull())
    await pc.setLocalDescription(await pc.createOffer())
    async with websockets.connect(f"wss://puc-relay.erenjeager078.workers.dev/phone?id={cfg['remote']['id']}") as ws:
        await ws.recv()
        await ws.send(json.dumps({"t": "offer", "mode": "screen", "sdp": pc.localDescription.sdp, "token": TOKEN, "device": "Remote test (Claude)"}))
        while True:
            m = json.loads(await asyncio.wait_for(ws.recv(), 30))
            if m["t"] in ("answer", "auth_fail"): break
        if m["t"] != "answer":
            log(f"outside refused {m}"); return
        await pc.setRemoteDescription(RTCSessionDescription(sdp=m["sdp"], type="answer"))
    log(f"outside info: {await asyncio.wait_for(inbox.get(), 30)}")
    await asyncio.sleep(1)
    threading.Thread(target=tone, daemon=True).start()
    await asyncio.sleep(3)
    loud = [r for r in rms if r > 50]
    log(f"outside audio: {len(rms)} frames, {len(loud)} with sound, loudest rms {max(rms) if rms else 0:.0f}")
    await pc.close()

async def main():
    await home()
    await asyncio.sleep(1)
    await outside()
    log("done")
try:
    asyncio.run(main())
except Exception:
    import traceback; log("FAILED " + traceback.format_exc())
