"""Diagnose next/previous episode: lists media sessions and what they allow, opens Crunchyroll
and dumps the buttons its window exposes to UI Automation."""
import asyncio, os, subprocess, sys, time, json
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
LOG = os.path.join(HERE, "test_media.log")
lines = []
def log(m):
    lines.append(time.strftime("%H:%M:%S ") + str(m))
    open(LOG, "w", encoding="utf-8").write("\n".join(lines))

from winrt.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as MM
import uiautomation as auto

async def sessions():
    mgr = await MM.request_async()
    cur = mgr.get_current_session()
    log(f"current: {cur.source_app_user_model_id if cur else None}")
    for s in mgr.get_sessions():
        try:
            p = await s.try_get_media_properties_async()
            c = s.get_playback_info().controls
            log(f"session {s.source_app_user_model_id} | {p.title!r} | next={c.is_next_enabled} prev={c.is_previous_enabled} play={c.is_play_pause_toggle_enabled}")
        except Exception as e:
            log(f"session err {e}")

def dump(win, max_lines=500):
    n = 0
    for c, d in auto.WalkControl(win, maxDepth=40):
        nm = (c.Name or "").strip()
        if not nm and c.ControlTypeName not in ("ButtonControl",):
            continue
        log(f"{'  '*min(d,12)}{c.ControlTypeName} '{nm[:80]}' aid={c.AutomationId[:30]!r}")
        n += 1
        if n >= max_lines:
            log("... cut"); break

mode = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else "all"
asyncio.run(sessions())
log(f"mode {mode}")
if mode in ("all", "open"):
    import library
    app_id = library.find_app_id("Crunchyroll")
    log(f"opening {app_id}")
    subprocess.Popen(["explorer.exe", f"shell:AppsFolder\\{app_id}"])
    time.sleep(15)
import faulthandler, traceback
faulthandler.dump_traceback_later(90, exit=True, file=open(os.path.join(HERE, "test_media.hang"), "w"))
try:
    log("looking for window")
    w = auto.WindowControl(searchDepth=1, SubName="Crunchyroll")
    if w.Exists(5, 0.5):
        log(f"window: {w.Name!r} {w.ClassName} pid={w.ProcessId}")
        dump(w)
    else:
        log("no Crunchyroll window")
except Exception:
    log(traceback.format_exc())
asyncio.run(sessions())
log("done")
