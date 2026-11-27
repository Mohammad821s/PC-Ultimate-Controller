"""Acts as the phone's screen viewer: connects in stream mode, measures frames per second and
frame sizes for each quality, saves one frame to test_frame.jpg, and moves the mouse with an
absolute 'abs' command (then puts it back). Clicks nothing."""
import asyncio, ctypes, json, os, time, uuid
from ctypes import wintypes
import websockets

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "test_stream.log")
lines = []
u = ctypes.windll.user32


def log(m):
    lines.append(time.strftime("%H:%M:%S ") + str(m))
    with open(LOG, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def cursor():
    p = wintypes.POINT(); u.GetCursorPos(ctypes.byref(p)); return p.x, p.y


async def run(quality, seconds, wiggle=False):
    pin = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))["pin"]
    async with websockets.connect("ws://127.0.0.1:8765", max_size=2**24) as ws:
        await ws.send(json.dumps({"t": "auth", "pin": pin, "mode": "stream", "quality": quality}))
        frames, sizes, infos, t0 = 0, [], [], time.time()
        first = None
        while time.time() - t0 < seconds:
            if wiggle:  # keep the picture changing so frames keep coming
                x, y = cursor(); u.SetCursorPos(x + (3 if frames % 2 else -3), y)
            try:
                m = await asyncio.wait_for(ws.recv(), timeout=3)
            except asyncio.TimeoutError:
                continue
            if isinstance(m, bytes):
                frames += 1; sizes.append(len(m))
                if first is None:
                    first = m
                await ws.send(json.dumps({"t": "ack"}))
            else:
                infos.append(json.loads(m))
        dt = time.time() - t0
        log(f"{quality}: {frames} frames in {dt:.1f}s = {frames/dt:.1f} fps, avg {sum(sizes)//max(1,len(sizes))//1024} KB, infos={infos}")
        return first, None


async def main():
    first, _ = await run("auto", 8, wiggle=True)
    if first:
        open(os.path.join(HERE, "test_frame.jpg"), "wb").write(first)
    for q in ("1080", "720", "480"):
        await run(q, 4, wiggle=True)
    # absolute pointer move: to the centre of the screen, then back
    pin = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))["pin"]
    before = cursor()
    async with websockets.connect("ws://127.0.0.1:8765", max_size=2**24) as ws:
        await ws.send(json.dumps({"t": "auth", "pin": pin, "mode": "stream", "quality": "480"}))
        await ws.send(json.dumps({"t": "abs", "x": 0.5, "y": 0.5}))
        await asyncio.sleep(0.5)
        mid = cursor()
        await ws.send(json.dumps({"t": "abs", "x": 0.25, "y": 0.75}))
        await asyncio.sleep(0.5)
        q = cursor()
    u.SetCursorPos(*before)
    sw, sh = u.GetSystemMetrics(0), u.GetSystemMetrics(1)
    log(f"abs centre -> {mid}, abs (0.25,0.75) -> {q}, screen metrics {sw}x{sh}, restored {before}")
    # a second viewer takes over from the first one
    async with websockets.connect("ws://127.0.0.1:8765", max_size=2**24) as a:
        await a.send(json.dumps({"t": "auth", "pin": pin, "mode": "stream", "quality": "480"}))
        await asyncio.sleep(1)
        async with websockets.connect("ws://127.0.0.1:8765", max_size=2**24) as b:
            await b.send(json.dumps({"t": "auth", "pin": pin, "mode": "stream"}))
            got = None
            for _ in range(10):
                m = await asyncio.wait_for(b.recv(), timeout=6)
                if not isinstance(m, bytes):
                    got = m; break
            log(f"second viewer got: {got}")
            a_closed = False
            try:
                while True:
                    await asyncio.wait_for(a.recv(), timeout=2)
            except websockets.exceptions.ConnectionClosed:
                a_closed = True
            except asyncio.TimeoutError:
                pass
            log(f"first viewer closed: {a_closed}")
    log("done")

try:
    asyncio.run(main())
except Exception as e:
    import traceback; log("FAILED: " + traceback.format_exc())
