"""Tests opening taskbar/tray items through UI Automation instead of simulated clicks."""
import ctypes
import os
import sys
import time
import traceback
from ctypes import wintypes

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "test_tray.log")
lines = []


def log(m):
    lines.append(time.strftime("%H:%M:%S ") + str(m))
    with open(LOG, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


u = ctypes.windll.user32
u.GetForegroundWindow.restype = wintypes.HWND


def fg():
    h = u.GetForegroundWindow()
    c = ctypes.create_unicode_buffer(128); t = ctypes.create_unicode_buffer(128)
    u.GetClassNameW(h, c, 128); u.GetWindowTextW(h, t, 128)
    return f"{c.value}|{t.value}"


time.sleep(4)  # let the remote-control session finish its own clicks first
log(f"start; left button held at start={bool(u.GetAsyncKeyState(1) & 0x8000)} fg={fg()}")
try:
    import uiautomation as auto
except Exception as e:
    log(f"uiautomation import failed: {e}")
    sys.exit(1)

try:
    tray = auto.PaneControl(ClassName="Shell_TrayWnd", searchDepth=1)
    names = []
    for c, depth in auto.WalkControl(tray, maxDepth=6):
        if c.ControlTypeName in ("ButtonControl", "MenuItemControl"):
            r = c.BoundingRectangle
            names.append(f"{c.ControlTypeName} '{c.Name}' cls={c.ClassName} at {r.xcenter()},{r.ycenter()}")
    log("taskbar buttons:\n  " + "\n  ".join(names))
except Exception:
    log(traceback.format_exc())


u.FindWindowW.restype = wintypes.HWND


def popups():
    out = []
    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def cb(h, l):
        if u.IsWindowVisible(h):
            c = ctypes.create_unicode_buffer(128); t = ctypes.create_unicode_buffer(128)
            u.GetClassNameW(h, c, 128); u.GetWindowTextW(h, t, 128)
            if c.value in ("Windows.UI.Core.CoreWindow", "#32768") or "Flyout" in c.value or "Tray" in c.value and "Overflow" in c.value:
                out.append(f"{c.value}|{t.value}")
        return True
    u.EnumWindows(cb, 0)
    return out


def try_open(match_words):
    tray = auto.PaneControl(ClassName="Shell_TrayWnd", searchDepth=1)
    for c, depth in auto.WalkControl(tray, maxDepth=6):
        n = (c.Name or "").lower()
        if c.ControlTypeName == "ButtonControl" and any(w in n for w in match_words):
            log(f"target: '{c.Name}'")
            for how in ("invoke", "legacy"):
                try:
                    if how == "invoke":
                        c.GetInvokePattern().Invoke()
                    else:
                        c.GetLegacyIAccessiblePattern().DoDefaultAction()
                    seen = set()
                    for _ in range(10):
                        time.sleep(0.2)
                        seen.update(popups()); seen.add("fg=" + fg())
                    log(f"  {how}: called; seen={sorted(seen)} held={bool(u.GetAsyncKeyState(1) & 0x8000)}")
                    time.sleep(1.5)
                except Exception as e:
                    log(f"  {how} failed: {e}")
    log(f"no button matching {match_words}")
    return False


log(f"popups before: {popups()}")
try_open(["headphones", "speaker", "volume", "مكبر", "الصوت"])
time.sleep(2)
try_open(["network", "internet", "ethernet", "الشبكة", "إنترنت"])
log("done")
