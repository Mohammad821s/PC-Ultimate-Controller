"""PC Ultimate Controller - Windows server.

Runs on the PC. The Android app connects to it over the local network and
sends mouse, keyboard, media and shortcut commands. The server sends back
what is currently playing (title, app, cover) for the phone's media controls.
"""
import asyncio
import base64
import ctypes
import json
import os
import random
import socket
import subprocess
import sys
import threading
import time
from ctypes import wintypes

import websockets

PORT = 8765
DISCOVERY_PORT = 8766
INPUT_PORT = 8767  # fast UDP channel for pointer movement
HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")
CREATE_NO_WINDOW = 0x08000000
LOG_PATH = os.path.join(HERE, "server.log")


def log(msg):
    try:
        if os.path.exists(LOG_PATH) and os.path.getsize(LOG_PATH) > 200_000:
            os.remove(LOG_PATH)
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(time.strftime("%H:%M:%S ") + str(msg) + "\n")
    except Exception:
        pass

DEFAULT_CONFIG = {
    "pin": "",
    "chrome_profile": "Song",
    "shortcuts": {
        "youtube": {"type": "chrome", "url": "https://www.youtube.com"},
        "netflix": {"type": "chrome", "url": "https://www.netflix.com"},
        "crunchyroll": {"type": "app", "name": "Crunchyroll"},
        "fortnite": {
            "type": "uri",
            "uri": "com.epicgames.launcher://apps/fn%3A4fe75bbc5a674f4f9b356b5c90567da5%3AFortnite?action=launch&silent=true",
        },
    },
}


# ---------------------------------------------------------------- config

def load_config():
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    new_pin = False
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, encoding="utf-8") as f:
                user = json.load(f)
            cfg.update({k: v for k, v in user.items() if k != "shortcuts"})
            cfg["shortcuts"].update(user.get("shortcuts", {}))
        except Exception as e:
            print("config.json could not be read:", e)
    if not cfg.get("pin"):
        cfg["pin"] = "".join(random.choice("0123456789") for _ in range(6))
        new_pin = True
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    return cfg, new_pin


CONFIG, NEW_PIN = load_config()


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def message_box(text, title="PC Ultimate Controller"):
    threading.Thread(
        target=lambda: ctypes.windll.user32.MessageBoxW(0, text, title, 0x40),
        daemon=True,
    ).start()


# ---------------------------------------------------------------- input (SendInput)

ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("u", _INPUTUNION)]


INPUT_MOUSE, INPUT_KEYBOARD = 0, 1
MOUSE_MOVE, MOUSE_WHEEL, MOUSE_HWHEEL = 0x0001, 0x0800, 0x1000
BUTTONS = {"left": (0x0002, 0x0004), "right": (0x0008, 0x0010), "middle": (0x0020, 0x0040)}
KEYUP, UNICODE, EXTENDED = 0x0002, 0x0004, 0x0001

VK = {
    "enter": 0x0D, "backspace": 0x08, "esc": 0x1B, "space": 0x20, "tab": 0x09,
    "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28, "f": 0x46,
    "playpause": 0xB3, "next": 0xB0, "prev": 0xB1,
    "volup": 0xAF, "voldown": 0xAE, "mute": 0xAD,
}
EXTENDED_KEYS = {0x25, 0x26, 0x27, 0x28, 0xB3, 0xB0, 0xB1, 0xAF, 0xAE, 0xAD}

if sys.platform == "win32":
    _user32 = ctypes.WinDLL("user32", use_last_error=True)
    _send_input = _user32.SendInput
    _send_input.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
    _send_input.restype = wintypes.UINT
    _user32.mouse_event.argtypes = (wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, ULONG_PTR)
    _user32.keybd_event.argtypes = (wintypes.BYTE, wintypes.BYTE, wintypes.DWORD, ULONG_PTR)
    _user32.FindWindowW.restype = wintypes.HWND
    _user32.FindWindowW.argtypes = (wintypes.LPCWSTR, wintypes.LPCWSTR)
    _user32.GetForegroundWindow.restype = wintypes.HWND
    _user32.SendMessageW.argtypes = (wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
    _user32.PostMessageW.argtypes = (wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
    _user32.MapVirtualKeyW.argtypes = (wintypes.UINT, wintypes.UINT)
    _user32.MapVirtualKeyW.restype = wintypes.UINT
    _user32.GetShellWindow.restype = wintypes.HWND
    _user32.SwitchToThisWindow.argtypes = (wintypes.HWND, wintypes.BOOL)
    _user32.GetCursorPos.argtypes = (ctypes.POINTER(wintypes.POINT),)
else:
    _user32 = _send_input = None


def _legacy(i):
    """Fallback through the older mouse_event/keybd_event APIs."""
    if i.type == INPUT_MOUSE:
        m = i.u.mi
        _user32.mouse_event(m.dwFlags, m.dx & 0xFFFFFFFF, m.dy & 0xFFFFFFFF, m.mouseData, 0)
    else:
        k = i.u.ki
        if k.dwFlags & UNICODE:
            return
        _user32.keybd_event(k.wVk & 0xFF, 0, k.dwFlags, 0)


def _raw_send(inputs):
    arr = (INPUT * len(inputs))(*inputs)
    return _send_input(len(inputs), arr, ctypes.sizeof(INPUT))


def _send(*inputs):
    if not _send_input:
        return 0
    n = _raw_send(inputs)
    if n != len(inputs):
        for i in inputs[n:]:
            _legacy(i)
    return n


def _mouse(flags, dx=0, dy=0, data=0):
    i = INPUT(type=INPUT_MOUSE)
    i.u.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
    return i


def _key(vk=0, scan=0, flags=0):
    i = INPUT(type=INPUT_KEYBOARD)
    i.u.ki = KEYBDINPUT(vk, scan, flags, 0, 0)
    return i


def mouse_move(dx, dy):
    _send(_mouse(MOUSE_MOVE, int(dx), int(dy)))


def mouse_click(button):
    # mouse_event is accepted on this PC where SendInput button events are rejected
    down, up = BUTTONS.get(button, BUTTONS["left"])
    if not _user32:
        return
    _user32.mouse_event(down, 0, 0, 0, 0)
    time.sleep(0.03)
    _user32.mouse_event(up, 0, 0, 0, 0)


def mouse_button(button, pressed):
    down, up = BUTTONS.get(button, BUTTONS["left"])
    if _user32:
        _user32.mouse_event(down if pressed else up, 0, 0, 0, 0)


def mouse_scroll(steps, horizontal=False):
    _send(_mouse(MOUSE_HWHEEL if horizontal else MOUSE_WHEEL, data=int(steps) * 120))


def press_key(name):
    vk = VK.get(name)
    if vk is None:
        return
    ext = EXTENDED if vk in EXTENDED_KEYS else 0
    if not _user32:
        return
    scan = _user32.MapVirtualKeyW(vk, 0) & 0xFF
    _user32.keybd_event(vk, scan, ext, 0)
    time.sleep(0.02)
    _user32.keybd_event(vk, scan, ext | KEYUP, 0)


WM_APPCOMMAND = 0x0319
APPCOMMANDS = {"mute": 8, "voldown": 9, "volup": 10, "next": 11, "prev": 12, "playpause": 14}


def app_command(name):
    """Media/volume through WM_APPCOMMAND to the taskbar: no simulated key press needed."""
    cmd = APPCOMMANDS.get(name)
    if cmd is None or not _user32:
        return False
    hwnd = _user32.FindWindowW("Shell_TrayWnd", None)
    if not hwnd:
        return False
    _user32.SendMessageW(hwnd, WM_APPCOMMAND, hwnd, cmd << 16)
    return True


async def media_session_command(name):
    """Play/pause/next/previous on the current media session (Spotify, Chrome...)."""
    if MediaManager is None:
        return False
    try:
        mgr = await MediaManager.request_async()
        s = mgr.get_current_session()
        if s is None:
            return False
        if name == "playpause":
            return bool(await s.try_toggle_play_pause_async())
        if name == "next":
            return bool(await s.try_skip_next_async())
        if name == "prev":
            return bool(await s.try_skip_previous_async())
    except Exception as e:
        log(f"media session error {e}")
    return False


CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002


def _clipboard_set(text):
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.GlobalAlloc.restype = ctypes.c_void_p
    k32.GlobalAlloc.argtypes = (wintypes.UINT, ctypes.c_size_t)
    k32.GlobalLock.restype = ctypes.c_void_p
    k32.GlobalLock.argtypes = (ctypes.c_void_p,)
    k32.GlobalUnlock.argtypes = (ctypes.c_void_p,)
    _user32.SetClipboardData.argtypes = (wintypes.UINT, ctypes.c_void_p)
    _user32.OpenClipboard.argtypes = (wintypes.HWND,)
    data = text.encode("utf-16-le") + b"\x00\x00"
    for _ in range(10):
        if _user32.OpenClipboard(None):
            break
        time.sleep(0.02)
    else:
        return False
    try:
        _user32.EmptyClipboard()
        h = k32.GlobalAlloc(GMEM_MOVEABLE, len(data))
        ptr = k32.GlobalLock(h)
        ctypes.memmove(ptr, data, len(data))
        k32.GlobalUnlock(h)
        _user32.SetClipboardData(CF_UNICODETEXT, h)
        return True
    finally:
        _user32.CloseClipboard()


def paste_text(text):
    """Typing fallback: put the text on the clipboard and press Ctrl+V."""
    if not _user32 or not _clipboard_set(text):
        return
    ctrl, v = 0x11, 0x56
    _user32.keybd_event(ctrl, 0, 0, 0)
    _user32.keybd_event(v, 0, 0, 0)
    time.sleep(0.02)
    _user32.keybd_event(v, 0, KEYUP, 0)
    _user32.keybd_event(ctrl, 0, KEYUP, 0)


def type_text(text):
    data = text.encode("utf-16-le")
    events = []
    for i in range(0, len(data), 2):
        unit = int.from_bytes(data[i:i + 2], "little")
        if unit == 10:  # newline -> Enter
            events += [_key(VK["enter"]), _key(VK["enter"], 0, KEYUP)]
            continue
        events += [_key(0, unit, UNICODE), _key(0, unit, UNICODE | KEYUP)]
    if events and _raw_send(events) == 0:
        paste_text(text.replace("\n", "\r\n"))


# ---------------------------------------------------------------- shortcuts

def chrome_path():
    try:
        import winreg
        for root in (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE):
            try:
                with winreg.OpenKey(root, r"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe") as k:
                    p = winreg.QueryValue(k, None)
                    if p and os.path.exists(p):
                        return p
            except OSError:
                pass
    except ImportError:
        pass
    for p in (r"C:\Program Files\Google\Chrome\Application\chrome.exe",
              r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"):
        if os.path.exists(p):
            return p
    return None


def chrome_profile_dir(name):
    if not name:
        return None
    try:
        path = os.path.join(os.environ["LOCALAPPDATA"], "Google", "Chrome", "User Data", "Local State")
        with open(path, encoding="utf-8") as f:
            cache = json.load(f)["profile"]["info_cache"]
        for folder, info in cache.items():
            if folder.lower() == name.lower() or info.get("name", "").strip().lower() == name.lower():
                return folder
    except Exception:
        pass
    return None


_app_ids = {}


def launch_store_app(name):
    app_id = _app_ids.get(name)
    if not app_id:
        safe = name.replace("'", "")
        ps = f"(Get-StartApps | Where-Object {{ $_.Name -like '*{safe}*' }} | Select-Object -First 1).AppID"
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                             capture_output=True, text=True, creationflags=CREATE_NO_WINDOW)
        app_id = out.stdout.strip()
        if app_id:
            _app_ids[name] = app_id
    if app_id:
        subprocess.Popen(["explorer.exe", f"shell:AppsFolder\\{app_id}"])
    else:
        print("App not found:", name)


def run_shortcut(sid):
    sc = CONFIG["shortcuts"].get(sid)
    if not sc:
        print("Unknown shortcut:", sid)
        return
    kind = sc.get("type")
    if kind == "chrome":
        exe = chrome_path()
        if exe:
            args = [exe]
            prof = chrome_profile_dir(CONFIG.get("chrome_profile", ""))
            if prof:
                args.append(f"--profile-directory={prof}")
            args.append(sc["url"])
            subprocess.Popen(args)
        else:
            os.startfile(sc["url"])
    elif kind == "app":
        launch_store_app(sc["name"])
    elif kind == "uri":
        os.startfile(sc["uri"])
    elif kind == "command":
        subprocess.Popen(sc["command"], shell=True)


# ---------------------------------------------------------------- now playing (optional)

MediaManager = DataReader = None
try:
    from winrt.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as MediaManager
    from winrt.windows.storage.streams import DataReader
except Exception:
    try:
        from winsdk.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as MediaManager
        from winsdk.windows.storage.streams import DataReader
    except Exception:
        MediaManager = None


def app_label(aumid):
    a = (aumid or "").lower()
    for key, label in (("chrome", "Chrome"), ("crunchyroll", "Crunchyroll"), ("netflix", "Netflix"),
                       ("spotify", "Spotify"), ("msedge", "Edge"), ("firefox", "Firefox"), ("vlc", "VLC")):
        if key in a:
            return label
    return (aumid or "").split("!")[0].split("\\")[-1].replace(".exe", "")


async def read_thumbnail(ref):
    try:
        stream = await ref.open_read_async()
        size = int(stream.size)
        if size <= 0 or size > 500_000:
            return ""
        reader = DataReader(stream.get_input_stream_at(0))
        await reader.load_async(size)
        buf = bytearray(size)
        reader.read_bytes(buf)
        return base64.b64encode(bytes(buf)).decode()
    except Exception:
        return ""


async def get_media():
    if MediaManager is None:
        return {"has": False}
    try:
        mgr = await MediaManager.request_async()
        s = mgr.get_current_session()
        if s is None:
            return {"has": False}
        props = await s.try_get_media_properties_async()
        playing = int(s.get_playback_info().playback_status) == 4
        state = {
            "has": True,
            "title": props.title or "",
            "artist": props.artist or "",
            "app": app_label(s.source_app_user_model_id),
            "playing": playing,
        }
        state["_thumbref"] = props.thumbnail
        return state
    except Exception:
        return {"has": False}


# ---------------------------------------------------------------- networking

clients = set()
authed_ips = {}  # ip -> number of open connections; only these may use the UDP input channel
last_media = {"has": False}
last_thumb_key = None
last_thumb = ""


async def broadcast(msg):
    data = json.dumps(msg, ensure_ascii=False)
    for ws in list(clients):
        try:
            await ws.send(data)
        except Exception:
            clients.discard(ws)


def public_media(m, with_thumb):
    out = {k: v for k, v in m.items() if not k.startswith("_")}
    out["t"] = "media"
    if with_thumb:
        out["thumb"] = last_thumb
    return out


async def media_watcher():
    global last_media, last_thumb_key, last_thumb
    while True:
        await asyncio.sleep(1.0)
        if not clients:
            continue
        m = await get_media()
        key = (m.get("title"), m.get("app"))
        new_thumb = False
        if m.get("has") and key != last_thumb_key:
            last_thumb_key = key
            ref = m.get("_thumbref")
            last_thumb = await read_thumbnail(ref) if ref is not None else ""
            new_thumb = True
        if public_media(m, False) != public_media(last_media, False) or new_thumb:
            last_media = m
            await broadcast(public_media(m, new_thumb))


async def handle(msg):
    t = msg.get("t")
    loop = asyncio.get_running_loop()
    if t != "move":
        log(f"cmd {msg}")
    if t == "move":
        mouse_move(msg.get("dx", 0), msg.get("dy", 0))
    elif t == "click":
        mouse_click(msg.get("b", "left"))
    elif t == "button":
        mouse_button(msg.get("b", "left"), bool(msg.get("down")))
    elif t == "scroll":
        mouse_scroll(msg.get("n", 0), bool(msg.get("h")))
    elif t == "key":
        press_key(msg.get("k", ""))
    elif t == "text":
        type_text(msg.get("s", ""))
    elif t == "media":
        a = msg.get("a", "")
        if a in ("playpause", "next", "prev") and await media_session_command(a):
            pass
        elif a in APPCOMMANDS and app_command(a):
            pass
        else:
            press_key("f" if a == "fullscreen" else a)
    elif t == "shortcut":
        await loop.run_in_executor(None, run_shortcut, msg.get("id", ""))


async def client_handler(ws, *_):
    peer = getattr(ws, "remote_address", ("?",))[0]
    try:
        first = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
    except Exception:
        return
    if first.get("t") != "auth" or str(first.get("pin", "")) != str(CONFIG["pin"]):
        print("Wrong PIN from", peer)
        log(f"wrong PIN from {peer}")
        try:
            await ws.send(json.dumps({"t": "auth_fail"}))
        except Exception:
            pass
        await ws.close()
        return
    print("Phone connected:", peer)
    log(f"phone connected {peer}")
    clients.add(ws)
    authed_ips[peer] = authed_ips.get(peer, 0) + 1
    await ws.send(json.dumps({"t": "hello", "name": socket.gethostname(), "udp": INPUT_PORT}))
    await ws.send(json.dumps(public_media(last_media, True), ensure_ascii=False))
    try:
        async for raw in ws:
            try:
                await handle(json.loads(raw))
            except Exception as e:
                print("Command error:", e)
    except Exception:
        pass
    finally:
        clients.discard(ws)
        authed_ips[peer] = authed_ips.get(peer, 1) - 1
        if authed_ips[peer] <= 0:
            authed_ips.pop(peer, None)
        print("Phone disconnected:", peer)


class FastInput(asyncio.DatagramProtocol):
    """Pointer movement and scrolling over UDP: no head-of-line blocking, lowest latency.
    Packets: b"m,dx,dy" or b"s,steps". Accepted only from phones with an open, PIN-checked connection."""

    def datagram_received(self, data, addr):
        if addr[0] not in authed_ips:
            return
        try:
            parts = data.decode("ascii").split(",")
            if parts[0] == "m":
                mouse_move(int(parts[1]), int(parts[2]))
            elif parts[0] == "s":
                mouse_scroll(int(parts[1]))
        except Exception:
            pass


class Discovery(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        if data.strip() == b"PUC?":
            reply = json.dumps({"name": socket.gethostname(), "port": PORT}).encode()
            self.transport.sendto(reply, addr)


PID_PATH = os.path.join(HERE, "server.pid")


def replace_old_instance():
    try:
        with open(PID_PATH) as f:
            old = int(f.read().strip())
        if old != os.getpid():
            subprocess.run(["taskkill", "/PID", str(old), "/F"], capture_output=True, creationflags=CREATE_NO_WINDOW)
            time.sleep(1.0)
    except Exception:
        pass
    with open(PID_PATH, "w") as f:
        f.write(str(os.getpid()))


async def main():
    replace_old_instance()
    log("server started")
    loop = asyncio.get_running_loop()
    try:
        await loop.create_datagram_endpoint(Discovery, local_addr=("0.0.0.0", DISCOVERY_PORT))
    except OSError as e:
        print("Discovery disabled:", e)
    try:
        await loop.create_datagram_endpoint(FastInput, local_addr=("0.0.0.0", INPUT_PORT))
    except OSError as e:
        print("Fast input disabled:", e)
    async with websockets.serve(client_handler, "0.0.0.0", PORT, compression=None, max_size=2 ** 20):
        ip = local_ip()
        print(f"PC Ultimate Controller running on {ip}:{PORT}  PIN: {CONFIG['pin']}")
        print("Now-playing info:", "on" if MediaManager else "off (run setup.bat to install it)")
        if NEW_PIN:
            message_box(f"PC Ultimate Controller is running.\n\nPC address: {ip}\nPIN: {CONFIG['pin']}\n\n"
                        "Enter the PIN in the phone app. You can always find it in config.json.")
        asyncio.create_task(media_watcher())
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except OSError as e:
        print("Could not start (is it already running?):", e)
