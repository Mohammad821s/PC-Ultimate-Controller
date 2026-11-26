"""Shortcuts, installed apps and browsers, icons and volume for PC Ultimate Controller."""
import base64
import json
import os
import re
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
ICON_DIR = os.path.join(HERE, "icons")
ICON_SCRIPT = os.path.join(HERE, "icon.ps1")
CREATE_NO_WINDOW = 0x08000000
os.makedirs(ICON_DIR, exist_ok=True)

_log = print


def set_logger(fn):
    global _log
    _log = fn


# ---------------------------------------------------------------- browsers

CHROMIUM_DATA = {
    "chrome.exe": ("Google", "Chrome", "User Data"),
    "msedge.exe": ("Microsoft", "Edge", "User Data"),
    "brave.exe": ("BraveSoftware", "Brave-Browser", "User Data"),
    "vivaldi.exe": ("Vivaldi", "User Data"),
}


def _chromium_profiles(exe_path):
    parts = CHROMIUM_DATA.get(os.path.basename(exe_path).lower())
    if not parts:
        return []
    try:
        path = os.path.join(os.environ["LOCALAPPDATA"], *parts, "Local State")
        with open(path, encoding="utf-8") as f:
            cache = json.load(f)["profile"]["info_cache"]
        return [{"dir": d, "name": (v.get("name") or d).strip()} for d, v in cache.items()]
    except Exception:
        return []


def list_browsers():
    """Browsers registered with Windows, with their Chromium profiles when they have them."""
    found = {}
    try:
        import winreg
    except ImportError:
        return []
    for root in (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE):
        try:
            base = winreg.OpenKey(root, r"SOFTWARE\Clients\StartMenuInternet")
        except OSError:
            continue
        i = 0
        while True:
            try:
                key = winreg.EnumKey(base, i)
            except OSError:
                break
            i += 1
            try:
                name = winreg.QueryValue(base, key) or key
                cmd = winreg.QueryValue(base, key + r"\shell\open\command")
            except OSError:
                continue
            m = re.match(r'\s*"([^"]+)"', cmd) or re.match(r"\s*(\S+\.exe)", cmd, re.I)
            if not m:
                continue
            path = m.group(1)
            if not os.path.exists(path):
                continue
            lower = path.lower()
            if lower not in found:
                found[lower] = {"name": name, "path": path, "profiles": _chromium_profiles(path)}
    return sorted(found.values(), key=lambda b: b["name"].lower())


def profile_dir(browser_path, profile_name):
    if not profile_name:
        return None
    for p in _chromium_profiles(browser_path):
        if profile_name.lower() in (p["name"].lower(), p["dir"].lower()):
            return p["dir"]
    return None


# ---------------------------------------------------------------- installed apps

_apps_cache = {"at": 0, "items": []}


def list_apps(force=False):
    """Everything in the Start menu (desktop and Store apps), as name + AppID."""
    if not force and _apps_cache["items"] and time.time() - _apps_cache["at"] < 300:
        return _apps_cache["items"]
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-StartApps | Select-Object Name,AppID | ConvertTo-Json -Compress"],
            capture_output=True, creationflags=CREATE_NO_WINDOW, timeout=60)
        data = json.loads(out.stdout.decode("utf-8", "replace") or "[]")
        if isinstance(data, dict):
            data = [data]
        items = [{"name": a["Name"], "id": a["AppID"]} for a in data if a.get("Name") and a.get("AppID")]
        items.sort(key=lambda a: a["name"].lower())
        _apps_cache.update(at=time.time(), items=items)
    except Exception as e:
        _log(f"list_apps failed: {e}")
    return _apps_cache["items"]


def find_app_id(name):
    for a in list_apps():
        if a["name"].lower() == name.lower():
            return a["id"]
    for a in list_apps():
        if name.lower() in a["name"].lower():
            return a["id"]
    return None


# ---------------------------------------------------------------- icons

ICON_PS1 = r'''
param([string]$Parse, [string]$Out, [int]$Size = 96)
$ErrorActionPreference = "Stop"
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @"
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
[StructLayout(LayoutKind.Sequential)] public struct PucSize { public int cx; public int cy; }
[ComImport, Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IPucImageFactory { [PreserveSig] int GetImage(PucSize size, int flags, out IntPtr phbm); }
public static class PucIcon {
  [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
  static extern void SHCreateItemFromParsingName(string path, IntPtr pbc, [MarshalAs(UnmanagedType.LPStruct)] Guid riid, [MarshalAs(UnmanagedType.Interface)] out IPucImageFactory ppv);
  [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr h);
  public static void Save(string parse, string outPath, int px) {
    IPucImageFactory f;
    SHCreateItemFromParsingName(parse, IntPtr.Zero, typeof(IPucImageFactory).GUID, out f);
    PucSize s; s.cx = px; s.cy = px;
    IntPtr hbm;
    int hr = f.GetImage(s, 0x4, out hbm);
    if (hr != 0) hr = f.GetImage(s, 0, out hbm);
    if (hr != 0) throw new Exception("GetImage failed " + hr);
    try {
      using (Bitmap src = Image.FromHbitmap(hbm)) {
        BitmapData bd = src.LockBits(new Rectangle(0, 0, src.Width, src.Height), ImageLockMode.ReadOnly, src.PixelFormat);
        try {
          using (Bitmap argb = new Bitmap(bd.Width, bd.Height, bd.Stride, PixelFormat.Format32bppArgb, bd.Scan0)) {
            argb.Save(outPath, ImageFormat.Png);
          }
        } finally { src.UnlockBits(bd); }
      }
    } finally { DeleteObject(hbm); }
  }
}
"@
[PucIcon]::Save($Parse, $Out, $Size)
'''


def _ensure_icon_script():
    if not os.path.exists(ICON_SCRIPT) or open(ICON_SCRIPT, encoding="utf-8").read() != ICON_PS1:
        with open(ICON_SCRIPT, "w", encoding="utf-8") as f:
            f.write(ICON_PS1)


def icon_path(sid):
    return os.path.join(ICON_DIR, re.sub(r"[^A-Za-z0-9_-]", "_", sid) + ".png")


def extract_app_icon(app_id, out):
    """Renders the Start-menu icon of an app (desktop or Store) to a PNG."""
    _ensure_icon_script()
    try:
        r = subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ICON_SCRIPT,
             "-Parse", "shell:AppsFolder\\" + app_id, "-Out", out, "-Size", "96"],
            capture_output=True, creationflags=CREATE_NO_WINDOW, timeout=30)
    except Exception as e:
        _log(f"icon extract failed for {app_id}: {e}")
        return False
    if r.returncode != 0 or not os.path.exists(out):
        _log(f"icon extract failed for {app_id}: {r.stderr.decode('utf-8', 'replace')[-300:]}")
        return False
    return True


def fetch_favicon(url, out):
    host = urllib.parse.urlparse(url if "://" in url else "https://" + url).netloc
    if not host:
        return False
    try:
        req = urllib.request.Request(
            f"https://www.google.com/s2/favicons?domain={host}&sz=128",
            headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=8) as r:
            data = r.read()
        if len(data) < 100:
            return False
        with open(out, "wb") as f:
            f.write(data)
        return True
    except Exception as e:
        _log(f"favicon failed for {host}: {e}")
        return False


def icon_b64(sid):
    p = icon_path(sid)
    if os.path.exists(p):
        with open(p, "rb") as f:
            return base64.b64encode(f.read()).decode()
    return None


# ---------------------------------------------------------------- shortcuts store

class Shortcuts:
    def __init__(self, config, save_config):
        self.config = config
        self.save_config = save_config
        self.lock = threading.Lock()
        self._icon_tried = set()
        self._migrate()

    def _migrate(self):
        if isinstance(self.config.get("shortcut_list"), list):
            return
        old = self.config.get("shortcuts", {}) or {}
        profile = self.config.get("chrome_profile", "")
        names = {"youtube": "YouTube", "netflix": "Netflix", "crunchyroll": "Crunchyroll", "fortnite": "Fortnite"}
        chrome = next((b["path"] for b in list_browsers() if os.path.basename(b["path"]).lower() == "chrome.exe"), "")
        items = []
        for sid, sc in old.items():
            name = names.get(sid, sid.title())
            kind = sc.get("type")
            if kind == "chrome":
                items.append({"id": sid, "name": name, "type": "website", "url": sc.get("url", ""),
                              "browser": chrome, "profile": profile})
            elif kind == "app":
                items.append({"id": sid, "name": name, "type": "app", "app_name": sc.get("name", name),
                              "app_id": find_app_id(sc.get("name", name)) or ""})
            elif kind == "uri":
                items.append({"id": sid, "name": name, "type": "command", "command": sc.get("uri", "")})
            elif kind == "command":
                items.append({"id": sid, "name": name, "type": "command", "command": sc.get("command", "")})
        self.config["shortcut_list"] = items
        self.save_config()

    @property
    def items(self):
        return self.config["shortcut_list"]

    def get(self, sid):
        return next((s for s in self.items if s["id"] == sid), None)

    def ensure_icon(self, sc):
        sid = sc["id"]
        p = icon_path(sid)
        if os.path.exists(p) or sid in self._icon_tried:
            return
        self._icon_tried.add(sid)
        if sc["type"] == "app" and sc.get("app_id"):
            extract_app_icon(sc["app_id"], p)
        elif sc["type"] == "website" and sc.get("url"):
            fetch_favicon(sc["url"], p)
        elif sc["type"] == "command" and "epicgames" in sc.get("command", ""):
            app = find_app_id(sc["name"])
            if app:
                extract_app_icon(app, p)

    def public_list(self):
        out = []
        for sc in self.items:
            self.ensure_icon(sc)
            d = dict(sc)
            d["icon"] = icon_b64(sc["id"])
            out.append(d)
        return out

    def save(self, item):
        with self.lock:
            item = {k: v for k, v in item.items() if k != "icon" or v}
            icon = item.pop("icon", None)
            sid = item.get("id") or uuid.uuid4().hex[:10]
            item["id"] = sid
            if item.get("type") == "app" and not item.get("app_id") and item.get("app_name"):
                item["app_id"] = find_app_id(item["app_name"]) or ""
            existing = self.get(sid)
            old = dict(existing) if existing else None
            if existing:
                existing.clear()
                existing.update(item)
            else:
                self.items.append(item)
            p = icon_path(sid)
            if icon:
                with open(p, "wb") as f:
                    f.write(base64.b64decode(icon))
            elif old and (old.get("url") != item.get("url") or old.get("app_id") != item.get("app_id")):
                if os.path.exists(p):
                    os.remove(p)
                self._icon_tried.discard(sid)
            self.save_config()
            return sid

    def delete(self, sid):
        with self.lock:
            self.config["shortcut_list"] = [s for s in self.items if s["id"] != sid]
            p = icon_path(sid)
            if os.path.exists(p):
                os.remove(p)
            self.save_config()

    def reorder(self, ids):
        with self.lock:
            by_id = {s["id"]: s for s in self.items}
            ordered = [by_id[i] for i in ids if i in by_id]
            ordered += [s for s in self.items if s["id"] not in ids]
            self.config["shortcut_list"] = ordered
            self.save_config()


def run(sc):
    """Opens a shortcut on the PC."""
    kind = sc.get("type")
    if kind == "website":
        url = sc.get("url", "")
        if url and "://" not in url:
            url = "https://" + url
        browser = sc.get("browser") or ""
        if browser and os.path.exists(browser):
            args = [browser]
            prof = profile_dir(browser, sc.get("profile", ""))
            if prof:
                args.append(f"--profile-directory={prof}")
            args.append(url)
            subprocess.Popen(args)
        else:
            os.startfile(url)
    elif kind == "app":
        app_id = sc.get("app_id") or find_app_id(sc.get("app_name") or sc.get("name", ""))
        if app_id:
            subprocess.Popen(["explorer.exe", f"shell:AppsFolder\\{app_id}"])
        else:
            _log(f"app not found for shortcut {sc.get('name')}")
    elif kind == "command":
        cmd = sc.get("command", "").strip()
        if re.match(r"^[A-Za-z][\w+.-]*://", cmd):
            os.startfile(cmd)
        elif cmd:
            subprocess.Popen(cmd, shell=True)


# ---------------------------------------------------------------- volume

def _endpoint():
    from pycaw.pycaw import AudioUtilities
    dev = AudioUtilities.GetSpeakers()
    ep = getattr(dev, "EndpointVolume", None)
    if ep is not None:
        return ep
    from ctypes import POINTER, cast
    from comtypes import CLSCTX_ALL
    from pycaw.pycaw import IAudioEndpointVolume
    iface = dev.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    return cast(iface, POINTER(IAudioEndpointVolume))


def get_volume():
    """Returns (level 0..1, muted) or None if volume control is unavailable."""
    try:
        ep = _endpoint()
        return float(ep.GetMasterVolumeLevelScalar()), bool(ep.GetMute())
    except Exception:
        return None


def set_volume(level):
    try:
        ep = _endpoint()
        ep.SetMasterVolumeLevelScalar(max(0.0, min(1.0, float(level))), None)
        if level > 0 and ep.GetMute():
            ep.SetMute(0, None)
        return True
    except Exception as e:
        _log(f"set volume failed: {e}")
        return False
