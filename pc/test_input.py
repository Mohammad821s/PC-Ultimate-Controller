"""Tests click methods on the taskbar clock (opens a protected Windows pop-up)
and records objectively whether the button got stuck and whether the pop-up opened."""
import ctypes
import os
import sys
import time
from ctypes import wintypes

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "test.log")
u = ctypes.WinDLL("user32", use_last_error=True)
ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class _U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("u", _U)]


u.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
u.FindWindowW.restype = wintypes.HWND
u.FindWindowExW.restype = wintypes.HWND
u.FindWindowExW.argtypes = (wintypes.HWND, wintypes.HWND, wintypes.LPCWSTR, wintypes.LPCWSTR)
u.GetForegroundWindow.restype = wintypes.HWND
u.GetWindowRect.argtypes = (wintypes.HWND, ctypes.POINTER(wintypes.RECT))
u.mouse_event.argtypes = (wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, ULONG_PTR)
u.GetClassNameW.argtypes = (wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)
u.GetWindowTextW.argtypes = (wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)

DOWN, UP = 0x0002, 0x0004
lines = []


def log(m):
    lines.append(time.strftime("%H:%M:%S ") + m)
    with open(LOG, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def mi(flags):
    i = INPUT(type=0)
    i.u.mi = MOUSEINPUT(0, 0, 0, flags, 0, 0)
    return i


def send(*inputs):
    arr = (INPUT * len(inputs))(*inputs)
    n = u.SendInput(len(inputs), arr, ctypes.sizeof(INPUT))
    return n, ctypes.get_last_error()


def btn_down():
    return bool(u.GetAsyncKeyState(0x01) & 0x8000)


def fg():
    h = u.GetForegroundWindow()
    c = ctypes.create_unicode_buffer(128); t = ctypes.create_unicode_buffer(128)
    u.GetClassNameW(h, c, 128); u.GetWindowTextW(h, t, 128)
    return f"{c.value}|{t.value}"


def clock_center():
    tray = u.FindWindowW("Shell_TrayWnd", None)
    notify = u.FindWindowExW(tray, None, "TrayNotifyWnd", None)
    clock = u.FindWindowExW(notify, None, "TrayClockWClass", None)
    r = wintypes.RECT()
    u.GetWindowRect(clock or notify, ctypes.byref(r))
    return (r.left + r.right) // 2, (r.top + r.bottom) // 2


def desktop_point():
    return u.GetSystemMetrics(0) // 2, u.GetSystemMetrics(1) // 3


def m_sendinput_separate():
    a = send(mi(DOWN)); time.sleep(0.03); b = send(mi(UP)); return f"down={a} up={b}"


def m_sendinput_batch():
    return f"batch={send(mi(DOWN), mi(UP))}"


def m_mouse_event():
    u.mouse_event(DOWN, 0, 0, 0, 0); time.sleep(0.03); u.mouse_event(UP, 0, 0, 0, 0); return "mouse_event"


def m_mouse_event_fast():
    u.mouse_event(DOWN, 0, 0, 0, 0); u.mouse_event(UP, 0, 0, 0, 0); return "mouse_event no gap"


METHODS = [m_sendinput_separate, m_sendinput_batch, m_mouse_event, m_mouse_event_fast]


def close_popup():
    # click on the desktop to close any pop-up, then make sure the button is up
    u.SetCursorPos(*desktop_point())
    time.sleep(0.2)
    r = send(mi(DOWN), mi(UP))
    time.sleep(0.6)
    if btn_down():
        u.mouse_event(UP, 0, 0, 0, 0)
    return r


log(f"admin={bool(ctypes.windll.shell32.IsUserAnAdmin())} python={sys.version.split()[0]}")
orig = wintypes.POINT(); u.GetCursorPos(ctypes.byref(orig))
cx, cy = clock_center()
log(f"clock at {cx},{cy}; screen {u.GetSystemMetrics(0)}x{u.GetSystemMetrics(1)}")
for m in METHODS:
    for attempt in range(2):
        u.SetCursorPos(cx, cy)
        time.sleep(0.3)
        before = fg()
        res = m()
        time.sleep(1.0)
        after = fg()
        stuck = btn_down()
        log(f"{m.__name__} #{attempt + 1}: {res} | stuck={stuck} | fg before={before} after={after}")
        r = close_popup()
        log(f"   cleanup click on desktop -> {r} | stuck after cleanup={btn_down()} | fg={fg()}")
        time.sleep(0.8)
u.SetCursorPos(orig.x, orig.y)
log("done")
