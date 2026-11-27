"""Opens a YouTube video in Chrome (muted PC), then tests the real server code for
Next/Previous: media session first, then the on-screen Next button via UI Automation."""
import asyncio, os, subprocess, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
LOG = os.path.join(HERE, "test_skip.log")
lines = []
def log(m):
    lines.append(time.strftime("%H:%M:%S ") + str(m))
    open(LOG, "w", encoding="utf-8").write("\n".join(lines))

import faulthandler
faulthandler.dump_traceback_later(150, exit=True, file=open(os.path.join(HERE, "test_skip.hang"), "w"))
try:
    import comtypes; comtypes.CoInitialize()
    import library
    vol = library.get_volume()
    log(f"volume before {vol}")
    library.set_volume(0.0)
    import server
    server.log = log
    chrome = next((b["path"] for b in library.list_browsers() if "chrome" in b["path"].lower()), None)
    log(f"chrome {chrome}")
    if "noopen" not in sys.argv:
        subprocess.Popen([chrome, "--profile-directory=Default", "https://www.youtube.com/watch?v=jNQXAC9IVRw"])
        time.sleep(14)

    async def state():
        mgr = await server.MediaManager.request_async()
        out = []
        for s in mgr.get_sessions():
            p = await s.try_get_media_properties_async()
            c = s.get_playback_info().controls
            out.append((s.source_app_user_model_id, p.title, c.is_next_enabled, c.is_previous_enabled))
        return out
    log(f"sessions {asyncio.run(state())}")
    log(f"windows {server.player_windows()}")
    log(f"foreground title: {server.window_title(server._user32.GetForegroundWindow())!r}")
    log(f"skip_episode next -> {server.skip_episode('next')}")
    time.sleep(6)
    log(f"sessions after {asyncio.run(state())}")
    log("skip prev test")
    time.sleep(4)
    log(f"sessions after prev {asyncio.run(state())}")
except Exception:
    import traceback; log(traceback.format_exc())
finally:
    try:
        library.set_volume(vol[0] if vol else 1.0)
        log("volume restored")
    except Exception as e:
        log(f"restore failed {e}")
log("done")
os._exit(0)
