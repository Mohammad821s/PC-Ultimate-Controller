"""Acts as the phone against the running server: checks hello/version, shortcuts, apps,
browsers, volume, then saves a temporary shortcut and deletes it. Opens nothing on screen."""
import asyncio, json, os, time
import websockets

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "test_proto.log")
lines = []


def log(m):
    lines.append(time.strftime("%H:%M:%S ") + str(m))
    with open(LOG, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


async def until(ws, t, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        m = json.loads(await asyncio.wait_for(ws.recv(), timeout=end - time.time()))
        if m.get("t") == t:
            return m
    raise TimeoutError(t)


async def main():
    pin = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))["pin"]
    async with websockets.connect("ws://127.0.0.1:8765", max_size=2**23) as ws:
        await ws.send(json.dumps({"t": "auth", "pin": pin}))
        h = await until(ws, "hello"); log(f"hello: name={h.get('name')} version={h.get('version')}")
        v = await until(ws, "volume"); log(f"volume: {v}")
        await ws.send(json.dumps({"t": "list_shortcuts"}))
        s = await until(ws, "shortcuts")
        log("shortcuts: " + json.dumps([(i.get("id"), i.get("type"), i.get("name"), len(i.get("icon") or "")) for i in s["items"]]))
        t0 = time.time(); await ws.send(json.dumps({"t": "list_apps"}))
        a = await until(ws, "apps"); log(f"apps: {len(a['items'])} in {time.time()-t0:.1f}s, first={a['items'][:2]}")
        await ws.send(json.dumps({"t": "list_browsers"}))
        b = await until(ws, "browsers"); log(f"browsers: {json.dumps(b['items'])[:400]}")
        if v.get("v") is not None:
            await ws.send(json.dumps({"t": "volume", "v": v["v"]})); log("volume set to same level: ok")
        item = {"type": "website", "name": "Test Wiki", "url": "https://www.wikipedia.org"}
        await ws.send(json.dumps({"t": "save_shortcut", "item": item}))
        sv = await until(ws, "saved"); log(f"saved: {sv}")
        await ws.send(json.dumps({"t": "list_shortcuts"}))
        s2 = await until(ws, "shortcuts")
        mine = [i for i in s2["items"] if i.get("id") == sv["id"]]
        log(f"new item present={bool(mine)} icon_len={len(mine[0].get('icon') or '') if mine else 0}")
        await ws.send(json.dumps({"t": "delete_shortcut", "id": sv["id"]}))
        s3 = await until(ws, "shortcuts")
        log(f"after delete count={len(s3['items'])} (was {len(s['items'])})")
    log("done")

try:
    asyncio.run(main())
except Exception as e:
    log(f"FAILED: {e!r}")
