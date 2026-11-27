"""Pairing check against the running server: old PIN still works, a wrong PIN is refused,
and an unknown phone gets 'pair_wait' (a Yes/No box appears on the PC)."""
import asyncio, json, os, time, uuid
import websockets

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "test_pair.log")
lines = []


def log(m):
    lines.append(time.strftime("%H:%M:%S ") + str(m))
    with open(LOG, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


async def first(auth, wait=5):
    async with websockets.connect("ws://127.0.0.1:8765") as ws:
        await ws.send(json.dumps(auth))
        out = []
        try:
            while True:
                m = json.loads(await asyncio.wait_for(ws.recv(), timeout=wait))
                out.append(m.get("t") + (":" + m["reason"] if m.get("reason") else ""))
                if m.get("t") in ("hello", "auth_fail"):
                    break
        except Exception as e:
            out.append(type(e).__name__)
        return out


async def main():
    pin = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))["pin"]
    log(f"old pin only: {await first({'t': 'auth', 'pin': pin})}")
    log(f"wrong pin: {await first({'t': 'auth', 'pin': '000000'})}")
    log(f"no token, no pin: {await first({'t': 'auth'})}")
    log("unknown phone (waits for the box on the PC, up to 70s)...")
    log(f"unknown phone: {await first({'t': 'auth', 'token': uuid.uuid4().hex, 'device': 'Pairing test (choose No)'}, wait=70)}")
    log("done")

try:
    asyncio.run(main())
except Exception as e:
    log(f"FAILED: {e!r}")
