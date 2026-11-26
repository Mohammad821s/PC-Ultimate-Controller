"""Screen streaming for the phone: captures the main monitor, scales it, draws the mouse
pointer and encodes JPEG frames. Quality is picked by the phone or adapted automatically
("auto") from how fast the phone acknowledges frames."""
import ctypes
import io
import threading
import time
import zlib
from ctypes import wintypes

try:
    import mss
    from PIL import Image, ImageDraw
    AVAILABLE = True
except Exception:  # pragma: no cover - reported to the phone
    mss = Image = ImageDraw = None
    AVAILABLE = False

# height, JPEG quality
PRESETS = {"1080": (1080, 80), "720": (720, 72), "480": (480, 62)}
AUTO_LADDER = [(360, 55), (480, 60), (540, 64), (720, 70), (900, 74), (1080, 78)]
AUTO_START = 3  # 720p

_user32 = ctypes.windll.user32
_ARROW = [(0, 0), (0, 17), (4, 13), (7, 20), (10, 19), (7, 12), (12, 12)]


class Capturer:
    """Lives on one thread (mss is not thread-safe)."""

    def __init__(self):
        self.sct = mss.mss()
        mon = self.sct.monitors[1]
        self.left, self.top, self.width, self.height = mon["left"], mon["top"], mon["width"], mon["height"]
        self.mon = mon
        self.last_key = None

    def grab(self, target_h, quality, force=False):
        """Returns JPEG bytes, or None when nothing changed since the last frame."""
        raw = self.sct.grab(self.mon)
        pt = wintypes.POINT()
        _user32.GetCursorPos(ctypes.byref(pt))
        key = (zlib.crc32(raw.bgra), pt.x, pt.y, target_h, quality)
        if not force and key == self.last_key:
            return None
        self.last_key = key
        img = self._compose(raw, pt, target_h)
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=quality, optimize=False)
        return buf.getvalue()

    def grab_image(self, target_h):
        """Current screen (with the pointer drawn) as an RGB image, for the video encoder.
        Width and height are kept even, as the encoder requires."""
        raw = self.sct.grab(self.mon)
        pt = wintypes.POINT()
        _user32.GetCursorPos(ctypes.byref(pt))
        img = self._compose(raw, pt, target_h)
        w, h = img.width & ~1, img.height & ~1
        if (w, h) != img.size:
            img = img.crop((0, 0, w, h))
        return img

    def _compose(self, raw, pt, target_h):
        img = Image.frombuffer("RGB", raw.size, raw.bgra, "raw", "BGRX")
        h = min(target_h, self.height)
        if h != self.height:
            w = round(self.width * h / self.height)
            img = img.resize((w, h), Image.BILINEAR)
        scale = img.height / self.height
        cx, cy = (pt.x - self.left) * scale, (pt.y - self.top) * scale
        if 0 <= cx < img.width and 0 <= cy < img.height:
            k = max(0.8, img.height / 900)
            poly = [(cx + x * k, cy + y * k) for x, y in _ARROW]
            d = ImageDraw.Draw(img)
            d.polygon(poly, fill=(255, 255, 255), outline=(0, 0, 0))
        return img

    def to_screen(self, nx, ny):
        nx = min(max(float(nx), 0.0), 1.0)
        ny = min(max(float(ny), 0.0), 1.0)
        return int(self.left + nx * (self.width - 1)), int(self.top + ny * (self.height - 1))


class AutoQuality:
    """Moves up/down a ladder of sizes from the measured frame rate and phone round trip."""

    def __init__(self):
        self.idx = AUTO_START
        self.since = time.time()
        self.times = []
        self.rtts = []

    def sent(self):
        self.times.append(time.time())

    def acked(self, rtt):
        self.rtts.append(rtt)

    def current(self):
        return AUTO_LADDER[self.idx]

    def step(self):
        now = time.time()
        if now - self.since < 1.5:
            return False
        self.times = [t for t in self.times if now - t < 2.0]
        fps = len(self.times) / 2.0
        rtt = sorted(self.rtts)[len(self.rtts) // 2] if self.rtts else 0
        self.rtts = []
        changed = False
        # only judge speed when the screen is actually changing (fps would be 0 on a still screen)
        if rtt > 0.25 and self.idx > 0:
            self.idx -= 1
            changed = True
        elif self.times and rtt and rtt < 0.07 and fps >= 18 and self.idx < len(AUTO_LADDER) - 1:
            self.idx += 1
            changed = True
        if changed:
            self.since = now
        return changed
