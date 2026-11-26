"""Opens the volume flyout through UI Automation, then checks that simulated clicks
still work (one on the empty wallpaper closes the flyout) and that nothing gets stuck."""
import ctypes
import os
import time
from ctypes import wintypes

import uiautomation as auto

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "test_tray2.log")
lines = []


def log(m):
    lines.append(time.strftime("%H:%M:%S ") + str(m))
    with open(LOG, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


u = ctypes.WinDLL("user32", use_last_error=True)
u.GetForegroundWindow.restype = wintypes.HWND
u.mouse_event.argtypes = (wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, ctypes.c_size_t)


def fg():
    h = u.GetForegroundWindow()
    c = ctypes.create_unicode_buffer(128); t = ctypes.create_unicode_buffer(128)
    u.GetClassNameW(h, c, 128); u.GetWindowTextW(h, t, 128)
    return f"{c.value}|{t.value}"


def held():
    return bool(u.GetAsyncKeyState(1) & 0x8000)


time.sleep(4)
log(f"start fg={fg()} held={held()}")
orig = wintypes.POINT(); u.GetCursorPos(ctypes.byref(orig))
tray = auto.PaneControl(ClassName="Shell_TrayWnd", searchDepth=1)
target = None
for c, d in auto.WalkControl(tray, maxDepth=6):
    if c.ControlTypeName == "ButtonControl" and ("headphones" in c.Name.lower() or "speaker" in c.Name.lower()):
        target = c
        break
r = target.BoundingRectangle
u.SetCursorPos(r.xcenter(), r.ycenter())  # like the phone pointer resting on the icon
time.sleep(0.3)
ctl = auto.ControlFromPoint(r.xcenter(), r.ycenter())
log(f"control under pointer: {ctl.ControlTypeName} '{ctl.Name}'")
ctl.GetInvokePattern().Invoke()
time.sleep(1.2)
log(f"after invoke fg={fg()} held={held()}")

# click on empty wallpaper to close the flyout (simulated, like a phone tap)
x, y = u.GetSystemMetrics(0) - 150, 300
u.SetCursorPos(x, y)
time.sleep(0.3)
under = auto.ControlFromPoint(x, y)
log(f"wallpaper point {x},{y} is: {under.ControlTypeName} '{under.Name}' cls={under.ClassName}")
u.mouse_event(0x0002, 0, 0, 0, 0); time.sleep(0.03); u.mouse_event(0x0004, 0, 0, 0, 0)
time.sleep(1.0)
log(f"after wallpaper click fg={fg()} held={held()}")

# second simulated click to confirm input still flows
u.mouse_event(0x0002, 0, 0, 0, 0); time.sleep(0.03); u.mouse_event(0x0004, 0, 0, 0, 0)
time.sleep(0.5)
log(f"after second click fg={fg()} held={held()}")
u.SetCursorPos(orig.x, orig.y)
log("done")
