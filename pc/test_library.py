"""Checks the new PC-side features without touching the running server."""
import base64
import json
import os
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "test_library.log")
lines = []


def log(m):
    lines.append(time.strftime("%H:%M:%S ") + str(m))
    with open(LOG, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


try:
    import library
    library.set_logger(lambda m: log("lib: " + m))
    t = time.time(); apps = library.list_apps(force=True)
    log(f"apps: {len(apps)} in {time.time() - t:.1f}s; sample {[a['name'] for a in apps[:6]]}")
    cr = library.find_app_id("Crunchyroll"); log(f"crunchyroll id: {cr}")
    br = library.list_browsers()
    log("browsers: " + json.dumps([(b['name'], os.path.basename(b['path']), [p['name'] for p in b['profiles']]) for b in br], ensure_ascii=False))
    out = os.path.join(HERE, "test_icon.png")
    if os.path.exists(out):
        os.remove(out)
    if cr:
        t = time.time(); ok = library.extract_app_icon(cr, out)
        log(f"crunchyroll icon: {ok} {os.path.getsize(out) if ok else 0} bytes in {time.time() - t:.1f}s")
    ok = library.fetch_favicon("youtube.com", os.path.join(HERE, "test_fav.png"))
    log(f"favicon youtube: {ok}")
    import comtypes
    comtypes.CoInitialize()
    log(f"volume: {library.get_volume()}")
except Exception:
    log(traceback.format_exc())
log("done")
