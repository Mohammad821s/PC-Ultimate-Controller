"""End-to-end check that acts as the phone: connects to the running server with the PIN,
taps the volume icon on the taskbar, then taps the wallpaper, and records what happened."""
import asyncio
import ctypes
import json
import os
import time
from ctypes import wintypes

import uiautomation as auto
import websockets

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "test_e2e.log")
lines = []
u = ctypes.windll.user32
u.GetForegroundWindow.restype = wintypes.HWND


def log(m):
    lines.append(time.strftime("%H:%M:%S ") + str(m))
    with open(LOG, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def fg():
    h = u.GetForegroundWindow()
    c = ctypes.create_unicode_buffer(128); t = ctypes.create_unicode_buffer(128)
    u.GetClassNameW(h, c, 128); u.GetWindowTextW(h, t, 128)
    return f"{c.value}|{t.value}"


def held():
    return bool(u.GetAsyncKeyState(1) & 0x8000)


async def main():
    pin = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))["pin"]
    async with websockets.connect("ws://127.0.0.1:8765") as ws:
        await ws.send(json.dumps({"t": "auth", "pin": pin}))
        log(f"server says: {await ws.recv()}")
        orig = wintypes.POINT(); u.GetCursorPos(ctypes.byref(orig))

        tray = auto.PaneControl(ClassName="Shell_TrayWnd", searchDepth=1)
        vol = next(c for c, d in auto.WalkControl(tray, maxDepth=6)
                   if c.ControlTypeName == "ButtonControl" and ("headphones" in c.Name.lower() or "speaker" in c.Name.lower()))
        r = vol.BoundingRectangle
        for round_no in (1, 2):
            u.SetCursorPos(r.xcenter(), r.ycenter())
            await asyncio.sleep(0.3)
            await ws.send(json.dumps({"t": "click", "b": "left"}))
            await asyncio.sleep(1.5)
            log(f"round {round_no}: tap on volume icon -> fg={fg()} held={held()}")
            u.SetCursorPos(u.GetSystemMetrics(0) - 150, 300)
            await asyncio.sleep(0.3)
            await ws.send(json.dumps({"t": "click", "b": "left"}))
            await asyncio.sleep(1.0)
            log(f"round {round_no}: tap on wallpaper -> fg={fg()} held={held()}")
        u.SetCursorPos(orig.x, orig.y)
    log("done")


time.sleep(4)
asyncio.run(main())
