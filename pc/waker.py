"""PC Waker (ESP32 at home that turns this PC on): find it on the home network and link it."""
import json
import secrets
import socket
import subprocess
import time
import urllib.request

DISCOVERY_PORT = 8768


def discover(timeout=2.5):
    """Broadcasts a question on the home network; returns [(ip, info), ...] of wakers that answered."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.4)
    found = {}
    end = time.time() + timeout
    try:
        while time.time() < end:
            try:
                s.sendto(b"PUC_WAKER?", ("255.255.255.255", DISCOVERY_PORT))
            except OSError:
                pass
            t_end = min(end, time.time() + 0.8)
            while time.time() < t_end:
                try:
                    data, (ip, _) = s.recvfrom(1024)
                    info = json.loads(data.decode("utf-8", "replace"))
                    if info.get("t") == "waker":
                        found[ip] = info
                except (socket.timeout, ValueError):
                    break
    finally:
        s.close()
    return list(found.items())


def pc_mac():
    """MAC of the wired adapter that is up (the one Wake-on-LAN uses)."""
    ps = ("Get-NetAdapter -Physical | Where-Object { $_.Status -eq 'Up' -and $_.MediaType -eq '802.3' } | "
          "Select-Object -First 1 -ExpandProperty MacAddress")
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True,
                             timeout=15, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout.strip()
        if len(out) == 17:
            return out.replace("-", ":").upper()
    except Exception:
        pass
    return ""


def new_key():
    return secrets.token_urlsafe(32)


def link(ip, payload, timeout=6):
    req = urllib.request.Request(f"http://{ip}/link", data=json.dumps(payload).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode() or "{}")
