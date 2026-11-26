"""PC Ultimate Controller - Windows server.

Runs on the PC. The Android app connects to it over the local network and
sends mouse, keyboard, media and shortcut commands. The server sends back
what is currently playing (title, app, cover) for the phone's media controls.
"""
import asyncio
import base64
import hashlib
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

VERSION = "dev"  # replaced with the build number by the release workflow
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
    "devices": [],
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


def save_config():
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(CONFIG, f, ensure_ascii=False, indent=2)


import library  # noqa: E402

library.set_logger(lambda m: log(m))
SHORTCUTS = library.Shortcuts(CONFIG, save_config)


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
    _user32.GetGUIThreadInfo.argtypes = (wintypes.DWORD, ctypes.c_void_p)
    _user32.GetClassNameW.argtypes = (wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)
    _user32.WindowFromPoint.argtypes = (wintypes.POINT,)
    _user32.WindowFromPoint.restype = wintypes.HWND
    _user32.GetAncestor.argtypes = (wintypes.HWND, wintypes.UINT)
    _user32.GetAncestor.restype = wintypes.HWND
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


TASKBAR_CLASSES = {"Shell_TrayWnd", "Shell_SecondaryTrayWnd", "NotifyIconOverflowWindow",
                   "TopLevelWindowForOverflowXamlIsland"}

import queue
import threading as _threading

_uia_jobs = queue.Queue()
_uia_ready = _threading.Event()
_uia_ok = False


def _uia_worker():
    """UI Automation and other COM work (volume) happens on this one thread, which owns its own COM apartment."""
    global _uia_ok
    auto = None
    try:
        import comtypes
        comtypes.CoInitialize()
    except Exception:
        pass
    try:
        import uiautomation as auto
        init = auto.UIAutomationInitializerInThread()
        init.__enter__()
        auto.ControlFromPoint(1, 1)  # warm up: the first call is slow
        _uia_ok = True
    except Exception as e:
        log(f"uiautomation unavailable: {e}")
        auto = None
    _uia_ready.set()
    while True:
        fn, done, result = _uia_jobs.get()
        try:
            result.append(fn(auto))
        except Exception as e:
            log(f"COM job error: {e}")
            result.append(None)
        done.set()


def run_com(fn, timeout=10):
    """Runs fn(auto) on the COM thread and returns its result (None on timeout)."""
    _uia_ready.wait(10)
    done, result = _threading.Event(), []
    _uia_jobs.put((fn, done, result))
    if not done.wait(timeout):
        log("COM job timed out")
        return None
    return result[0]


_threading.Thread(target=_uia_worker, daemon=True).start()


def _root_class_at_cursor():
    pt = wintypes.POINT()
    _user32.GetCursorPos(ctypes.byref(pt))
    hwnd = _user32.WindowFromPoint(pt)
    root = _user32.GetAncestor(hwnd, 2) if hwnd else None
    buf = ctypes.create_unicode_buffer(128)
    if root:
        _user32.GetClassNameW(root, buf, 128)
    return buf.value, pt


def _invoke_with(auto, pt):
    ctl = auto.ControlFromPoint(pt.x, pt.y)
    for _ in range(4):
        if ctl is None:
            break
        for how in ("invoke", "toggle", "expand", "legacy"):
            try:
                if how == "invoke":
                    ctl.GetInvokePattern().Invoke()
                elif how == "toggle":
                    ctl.GetTogglePattern().Toggle()
                elif how == "expand":
                    ctl.GetExpandCollapsePattern().Expand()
                else:
                    ctl.GetLegacyIAccessiblePattern().DoDefaultAction()
                log(f"taskbar {how}: '{ctl.Name}'")
                return True
            except Exception:
                continue
        ctl = ctl.GetParentControl()
    log("taskbar: nothing to activate under the pointer")
    return False


def invoke_at(pt):
    """Activates the taskbar item under the pointer without a simulated click.
    Simulated clicks on the taskbar make Windows block all further simulated input."""
    _uia_ready.wait(10)
    if not _uia_ok:
        log("taskbar click skipped (uiautomation not available)")
        return False
    return bool(run_com(lambda auto: _invoke_with(auto, pt)))


def mouse_click(button):
    if not _user32:
        return
    cls, pt = _root_class_at_cursor()
    if cls in TASKBAR_CLASSES:
        if button == "left":
            invoke_at(pt)
        else:
            log(f"skipped {button} click on the taskbar")
        return
    # mouse_event is accepted on this PC where SendInput button events are rejected
    down, up = BUTTONS.get(button, BUTTONS["left"])
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
    if _raw_send([_key(vk, scan, ext)]) == 0:
        post_key(vk)
        return
    time.sleep(0.02)
    _raw_send([_key(vk, scan, ext | KEYUP)])


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


PLAYER_CLASSES = {"Chrome_WidgetWin_1", "ApplicationFrameWindow", "MozillaWindowClass", "Windows.UI.Core.CoreWindow"}


def player_windows(limit=4):
    """Foreground window first, then other visible browser/app windows, top to bottom."""
    out = []
    fg = _user32.GetForegroundWindow()
    if fg:
        out.append(fg)
    buf = ctypes.create_unicode_buffer(64)

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def cb(h, _):
        if len(out) >= limit:
            return False
        if h != fg and _user32.IsWindowVisible(h) and not _user32.IsIconic(h):
            _user32.GetClassNameW(h, buf, 64)
            if buf.value in PLAYER_CLASSES and _user32.GetWindowTextLengthW(h) > 0:
                out.append(h)
        return True

    _user32.EnumWindows(cb, 0)
    return out


def key_combo(mod_vk, vk):
    """Shift/Ctrl + key through keybd_event (works where SendInput is blocked on this PC)."""
    kb = _user32.keybd_event
    kb(mod_vk, 0, 0, 0)
    kb(vk, 0, 0, 0)
    time.sleep(0.03)
    kb(vk, 0, 2, 0)
    kb(mod_vk, 0, 2, 0)


def window_title(hwnd):
    buf = ctypes.create_unicode_buffer(256)
    _user32.GetWindowTextW(hwnd, buf, 256)
    return buf.value


def skip_episode(which):
    """Next/previous episode for players that don't expose it to Windows (Crunchyroll, Netflix, YouTube in a browser)."""
    fg = _user32.GetForegroundWindow()
    title = window_title(fg).lower() if fg else ""
    if "youtube" in title:
        key_combo(0x10, 0x4E if which == "next" else 0x50)  # Shift+N / Shift+P
        log(f"skip {which}: youtube shortcut")
        return True
    cls = ctypes.create_unicode_buffer(64)
    if fg:
        _user32.GetClassNameW(fg, cls, 64)
    if cls.value not in PLAYER_CLASSES:
        log(f"skip {which}: foreground is not a player ({cls.value})")
        return False
    t0 = time.time()
    name = run_com(lambda auto: library.press_skip_button(auto, which, [fg]), 5)
    log(f"skip {which}: button {name!r} in {time.time() - t0:.2f}s ({title[:40]})")
    return bool(name)


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
        controls = s.get_playback_info().controls
        if name == "next":
            return bool(controls.is_next_enabled) and bool(await s.try_skip_next_async())
        if name == "prev":
            return bool(controls.is_previous_enabled) and bool(await s.try_skip_previous_async())
    except Exception as e:
        log(f"media session error {e}")
    return False


class GUITHREADINFO(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("flags", wintypes.DWORD), ("hwndActive", wintypes.HWND),
                ("hwndFocus", wintypes.HWND), ("hwndCapture", wintypes.HWND), ("hwndMenuOwner", wintypes.HWND),
                ("hwndMoveSize", wintypes.HWND), ("hwndCaret", wintypes.HWND), ("rcCaret", wintypes.RECT)]


WM_KEYDOWN, WM_KEYUP, WM_CHAR = 0x0100, 0x0101, 0x0102


def focus_window():
    info = GUITHREADINFO(cbSize=ctypes.sizeof(GUITHREADINFO))
    if _user32.GetGUIThreadInfo(0, ctypes.byref(info)) and (info.hwndFocus or info.hwndActive):
        return info.hwndFocus or info.hwndActive
    return _user32.GetForegroundWindow()


def _key_lparam(vk, up):
    scan = _user32.MapVirtualKeyW(vk, 0) & 0xFF
    lp = 1 | (scan << 16)
    if vk in EXTENDED_KEYS:
        lp |= 1 << 24
    if up:
        lp |= (1 << 30) | (1 << 31)
    return lp


def post_key(vk):
    """Key press posted straight to the focused window (used where simulated input is blocked)."""
    target = focus_window()
    if not target:
        return
    _user32.PostMessageW(target, WM_KEYDOWN, vk, _key_lparam(vk, False))
    _user32.PostMessageW(target, WM_KEYUP, vk, _key_lparam(vk, True) & 0xFFFFFFFF)


def _class_name(hwnd):
    buf = ctypes.create_unicode_buffer(128)
    _user32.GetClassNameW(hwnd, buf, 128)
    return buf.value


def post_text(text):
    """Characters posted straight to the focused control."""
    target = focus_window()
    if not target:
        return
    for ch in text:
        if ch == "\n":
            post_key(VK["enter"])
        else:
            for unit in range(0, len(ch.encode("utf-16-le")), 2):
                code = int.from_bytes(ch.encode("utf-16-le")[unit:unit + 2], "little")
                _user32.PostMessageW(target, WM_CHAR, code, 1)


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
        post_text(text)


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
    sc = SHORTCUTS.get(sid)
    if not sc:
        log(f"unknown shortcut {sid}")
        return
    library.run(sc)


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
last_volume = None
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


def volume_msg():
    v = last_volume
    return {"t": "volume", "v": v[0], "muted": v[1]} if v else {"t": "volume", "v": None}


async def media_watcher():
    global last_media, last_thumb_key, last_thumb, last_volume
    loop = asyncio.get_running_loop()
    while True:
        await asyncio.sleep(1.0)
        if not clients:
            continue
        vol = await loop.run_in_executor(None, run_com, lambda auto: library.get_volume(), 5)
        if vol is not None:
            vol = (round(vol[0], 3), vol[1])
        if vol != last_volume:
            last_volume = vol
            await broadcast(volume_msg())
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
    if t not in ("move", "save_shortcut", "test_shortcut"):
        log(f"cmd {msg}")
    elif t != "move":
        log(f"cmd {t} {str(msg.get('item', {}).get('name', ''))}")
    if t == "move":
        mouse_move(msg.get("dx", 0), msg.get("dy", 0))
    elif t == "click":
        await loop.run_in_executor(None, mouse_click, msg.get("b", "left"))
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
        elif a in ("next", "prev") and await loop.run_in_executor(None, skip_episode, a):
            pass
        elif a in APPCOMMANDS and app_command(a):
            pass
        else:
            press_key("f" if a == "fullscreen" else a)
    elif t == "shortcut":
        await loop.run_in_executor(None, run_shortcut, msg.get("id", ""))
    elif t == "volume":
        global last_volume
        level = float(msg.get("v", 0))
        await loop.run_in_executor(None, run_com, lambda auto: library.set_volume(level), 5)
        last_volume = (round(level, 3), False)
    elif t == "list_shortcuts":
        return {"t": "shortcuts", "items": await loop.run_in_executor(None, SHORTCUTS.public_list)}
    elif t == "list_apps":
        return {"t": "apps", "items": await loop.run_in_executor(None, library.list_apps, bool(msg.get("refresh")))}
    elif t == "list_browsers":
        return {"t": "browsers", "items": await loop.run_in_executor(None, library.list_browsers)}
    elif t == "save_shortcut":
        sid = await loop.run_in_executor(None, SHORTCUTS.save, dict(msg.get("item") or {}))
        await broadcast({"t": "shortcuts", "items": await loop.run_in_executor(None, SHORTCUTS.public_list)})
        return {"t": "saved", "id": sid}
    elif t == "delete_shortcut":
        await loop.run_in_executor(None, SHORTCUTS.delete, msg.get("id", ""))
        await broadcast({"t": "shortcuts", "items": await loop.run_in_executor(None, SHORTCUTS.public_list)})
    elif t == "reorder_shortcuts":
        await loop.run_in_executor(None, SHORTCUTS.reorder, list(msg.get("ids") or []))
        await broadcast({"t": "shortcuts", "items": await loop.run_in_executor(None, SHORTCUTS.public_list)})
    elif t == "test_shortcut":
        await loop.run_in_executor(None, library.run, dict(msg.get("item") or {}))


# ---------------------------------------------------------------- pairing (approve the phone on the PC)

MB_YESNO, MB_ICONQUESTION, MB_TOPMOST, MB_SETFOREGROUND, MB_SYSTEMMODAL = 0x4, 0x20, 0x40000, 0x10000, 0x1000
IDYES = 6
PAIR_TIMEOUT_S = 60
pair_pending = {}  # token hash -> Future[bool]


def token_hash(token):
    return hashlib.sha256(str(token).encode("utf-8")).hexdigest()


def known_device(th):
    return next((d for d in CONFIG.get("devices", []) if d.get("token") == th), None)


def add_device(th, name):
    devices = [d for d in CONFIG.get("devices", []) if d.get("token") != th]
    devices.append({"token": th, "name": name[:60], "added": time.strftime("%Y-%m-%d %H:%M")})
    CONFIG["devices"] = devices
    save_config()


def ask_approval(name, ip):
    """Blocking Yes/No box on the PC; closes itself after PAIR_TIMEOUT_S (counts as No)."""
    text = (f'"{name}" wants to control this PC.\n\nPhone address: {ip}\n\n'
            "Allow it? Choose Yes only if this is your phone.")
    flags = MB_YESNO | MB_ICONQUESTION | MB_TOPMOST | MB_SETFOREGROUND | MB_SYSTEMMODAL
    try:
        fn = ctypes.windll.user32.MessageBoxTimeoutW
        fn.argtypes = [wintypes.HWND, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.UINT, wintypes.WORD, wintypes.DWORD]
        r = fn(None, text, "PC Ultimate Controller - new phone", flags, 0, PAIR_TIMEOUT_S * 1000)
    except Exception:
        r = ctypes.windll.user32.MessageBoxW(None, text, "PC Ultimate Controller - new phone", flags)
    return r == IDYES


async def authorize(ws, first, peer):
    """Returns (ok, reason). Accepts a paired phone's token, or the old PIN, or asks on the PC."""
    if first.get("t") != "auth":
        return False, "bad_request"
    token = str(first.get("token") or "")
    name = str(first.get("device") or "Phone").strip() or "Phone"
    th = token_hash(token) if token else ""
    pin = str(first.get("pin") or "")
    if pin and pin == str(CONFIG.get("pin")):
        if th and not known_device(th):
            add_device(th, name)
        return True, ""
    if not token:
        return False, "wrong_pin" if pin else "not_paired"
    if known_device(th):
        return True, ""
    for k in [k for k, f in pair_pending.items() if f.done() and k != th]:
        pair_pending.pop(k, None)
    fut = pair_pending.get(th)
    if fut is None:
        if pair_pending:
            return False, "busy"
        loop = asyncio.get_running_loop()
        fut = loop.run_in_executor(None, ask_approval, name, peer)
        pair_pending[th] = fut
        log(f"pairing request from {name} {peer}")
    try:
        await ws.send(json.dumps({"t": "pair_wait", "timeout": PAIR_TIMEOUT_S}))
        approved = await asyncio.shield(fut)
    finally:
        if fut.done():
            pair_pending.pop(th, None)
    if not approved:
        return False, "denied"
    if not known_device(th):
        add_device(th, name)
        log(f"paired {name} {peer}")
    return True, ""


# ---------------------------------------------------------------- screen streaming

import concurrent.futures  # noqa: E402

import screen  # noqa: E402

_capture_pool = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="capture")
_capturer = None
stream_lock = asyncio.Lock()
_active_stream = {"ws": None}


def _get_capturer():
    global _capturer
    if _capturer is None:
        _capturer = screen.Capturer()
    return _capturer


async def stream_session(ws, first, peer):
    """One phone watching the screen. Frames are binary JPEG messages; the phone acks each one.
    At most two frames are in flight, so a slow phone or network lowers the frame rate, not the delay."""
    loop = asyncio.get_running_loop()
    if not screen.AVAILABLE:
        await ws.send(json.dumps({"t": "stream_error", "reason": "missing_modules"}))
        return
    if stream_lock.locked() and _active_stream.get("ws") is not None:
        # a newer viewer (the same phone reconnecting, or another one) takes over
        try:
            await _active_stream["ws"].close()
        except Exception:
            pass
    try:
        await asyncio.wait_for(stream_lock.acquire(), timeout=4)
    except asyncio.TimeoutError:
        await ws.send(json.dumps({"t": "stream_error", "reason": "busy"}))
        return
    _active_stream["ws"] = ws
    try:
        await _stream_body(ws, first, peer, loop)
    finally:
        if _active_stream.get("ws") is ws:
            _active_stream["ws"] = None
        stream_lock.release()


async def _stream_body(ws, first, peer, loop):
    cap = await loop.run_in_executor(_capture_pool, _get_capturer)
    state = {"quality": str(first.get("quality") or "auto"), "inflight": [], "force": True, "open": True}
    auto_q = screen.AutoQuality()
    wake = asyncio.Event()
    log(f"stream start {peer} quality={state['quality']}")

    def settings():
        if state["quality"] in screen.PRESETS:
            return screen.PRESETS[state["quality"]]
        return auto_q.current()

    async def info():
        h, q = settings()
        await ws.send(json.dumps({"t": "stream_info", "w": cap.width, "h": cap.height,
                                  "quality": state["quality"], "height": min(h, cap.height)}))

    async def receiver():
        async for raw in ws:
            if isinstance(raw, bytes):
                continue
            try:
                m = json.loads(raw)
                t = m.get("t")
                if t == "ack":
                    if state["inflight"]:
                        auto_q.acked(time.time() - state["inflight"].pop(0))
                    wake.set()
                elif t == "quality":
                    state["quality"] = str(m.get("q") or "auto")
                    state["force"] = True
                    await info()
                    wake.set()
                elif t == "abs":
                    x, y = cap.to_screen(m.get("x", 0), m.get("y", 0))
                    _user32.SetCursorPos(x, y)
                elif t == "tap":
                    x, y = cap.to_screen(m.get("x", 0), m.get("y", 0))
                    _user32.SetCursorPos(x, y)
                    await loop.run_in_executor(None, mouse_click, m.get("b", "left"))
                else:
                    await handle(m)
            except Exception as e:
                log(f"stream command error: {e}")
        state["open"] = False
        wake.set()

    rx = asyncio.create_task(receiver())
    try:
        await info()
        while state["open"]:
            if len(state["inflight"]) >= 2:
                wake.clear()
                try:
                    await asyncio.wait_for(wake.wait(), timeout=2.0)
                except asyncio.TimeoutError:
                    state["inflight"].clear()  # lost acks: start over
                continue
            h, q = settings()
            force, state["force"] = state["force"], False
            t0 = time.time()
            frame = await loop.run_in_executor(_capture_pool, cap.grab, h, q, force)
            if frame is None:
                await asyncio.sleep(0.03)
                continue
            state["inflight"].append(time.time())
            auto_q.sent()
            await ws.send(frame)
            if state["quality"] == "auto" and auto_q.step():
                state["force"] = True
                await info()
            # cap at ~30 fps
            await asyncio.sleep(max(0.0, 1 / 30 - (time.time() - t0)))
    except Exception as e:
        log(f"stream ended: {e}")
    finally:
        rx.cancel()
        log(f"stream stop {peer}")


async def client_handler(ws, *_):
    peer = getattr(ws, "remote_address", ("?",))[0]
    try:
        first = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
    except Exception:
        return
    ok, reason = await authorize(ws, first, peer)
    if not ok:
        print("Refused", peer, reason)
        log(f"refused {peer}: {reason}")
        try:
            await ws.send(json.dumps({"t": "auth_fail", "reason": reason}))
        except Exception:
            pass
        await ws.close()
        return
    if first.get("mode") == "stream":
        await stream_session(ws, first, peer)
        return
    print("Phone connected:", peer)
    log(f"phone connected {peer}")
    clients.add(ws)
    authed_ips[peer] = authed_ips.get(peer, 0) + 1
    for m in hello_messages(remote_access=False):
        await ws.send(json.dumps(m, ensure_ascii=False))
    try:
        async for raw in ws:
            try:
                reply = await handle(json.loads(raw))
                if reply:
                    await ws.send(json.dumps(reply, ensure_ascii=False))
            except Exception as e:
                log(f"command error: {e}")
    except Exception:
        pass
    finally:
        clients.discard(ws)
        authed_ips[peer] = authed_ips.get(peer, 1) - 1
        if authed_ips[peer] <= 0:
            authed_ips.pop(peer, None)
        print("Phone disconnected:", peer)


# ---------------------------------------------------------------- remote access (any network)

import remote  # noqa: E402

RELAY_URL = "wss://puc-relay.mohammad821s.workers.dev"
remote_agent = None


def relay_url():
    return CONFIG.get("relay_url") or RELAY_URL


def remote_identity():
    ident = CONFIG.get("remote")
    if not ident or not ident.get("id") or not ident.get("key"):
        ident = remote.new_identity()
        CONFIG["remote"] = ident
        save_config()
    return ident


def hello_messages(remote_access):
    hello = {"t": "hello", "name": socket.gethostname(), "udp": INPUT_PORT, "version": VERSION,
             "remote": remote_access}
    if remote.AVAILABLE:
        hello["relay"] = relay_url()
        hello["rid"] = remote_identity()["id"]
    return [hello, public_media(last_media, True), volume_msg()]


def start_remote():
    global remote_agent
    if not remote.AVAILABLE or not relay_url():
        log("remote access off (aiortc missing or no relay)")
        return
    ident = remote_identity()
    remote_agent = remote.RemoteAgent(
        relay_url(), ident["id"], ident["key"],
        is_paired=lambda token: known_device(token_hash(token)) is not None,
        handle=handle,
        fast=lambda t: fast_input(t),
        hello_messages=lambda: hello_messages(remote_access=True),
        add_client=clients.add,
        remove_client=clients.discard,
        log=log,
    )
    asyncio.create_task(remote_agent.run())


class FastInput(asyncio.DatagramProtocol):
    """Pointer movement and scrolling over UDP: no head-of-line blocking, lowest latency.
    Packets: b"m,dx,dy" or b"s,steps". Accepted only from phones with an open, PIN-checked connection."""

    def datagram_received(self, data, addr):
        if addr[0] not in authed_ips:
            return
        try:
            fast_input(data.decode("ascii"))
        except Exception:
            pass


def fast_input(text):
    parts = text.split(",")
    if parts[0] == "m":
        mouse_move(int(parts[1]), int(parts[2]))
    elif parts[0] == "s":
        mouse_scroll(int(parts[1]))


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
    async with websockets.serve(client_handler, "0.0.0.0", PORT, compression=None, max_size=2 ** 22):
        ip = local_ip()
        print(f"PC Ultimate Controller running on {ip}:{PORT}  PIN: {CONFIG['pin']}")
        print("Now-playing info:", "on" if MediaManager else "off (run setup.bat to install it)")
        if NEW_PIN:
            message_box(f"PC Ultimate Controller is running.\n\nPC address: {ip}\n\n"
                        "On your phone open the app and tap Find my PC.\n"
                        "A box will appear here asking you to allow the phone - choose Yes.")
        asyncio.create_task(media_watcher())
        start_remote()
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except OSError as e:
        print("Could not start (is it already running?):", e)
