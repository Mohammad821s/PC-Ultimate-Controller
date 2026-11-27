"""PC sound for the phone: records what the PC is playing (WASAPI loopback, so the PC's own
speakers/headphones keep playing normally) and hands 20 ms chunks to whoever listens.

Chunks are 16-bit little-endian stereo PCM at 48 kHz (960 frames = 3840 bytes)."""
import ctypes
import queue
import threading
import time

RATE = 48000
CHANNELS = 2
FRAME = 960                      # 20 ms
CHUNK_BYTES = FRAME * CHANNELS * 2
SILENCE = bytes(CHUNK_BYTES)

try:
    import numpy as np
    import soundcard as sc
    AVAILABLE = True
except Exception:  # pragma: no cover - reported to the phone
    np = sc = None
    AVAILABLE = False


def loopback_source(active, log=print):
    """Yields chunks of whatever the default output device is playing."""
    try:
        ctypes.windll.ole32.CoInitializeEx(None, 0)  # soundcard uses COM on this thread
    except Exception:
        pass
    speaker = sc.default_speaker()
    mic = sc.get_microphone(id=str(speaker.name), include_loopback=True)
    log(f"audio: capturing {speaker.name}")
    with mic.recorder(samplerate=RATE, channels=CHANNELS, blocksize=FRAME) as rec:
        while active():
            data = rec.record(numframes=FRAME)
            yield (np.clip(data, -1.0, 1.0) * 32767).astype("<i2").tobytes()


class AudioHub:
    """One capture shared by every listener; it runs only while someone listens."""

    def __init__(self, source=None, log=print):
        self.source = source or (lambda active: loopback_source(active, log))
        self.log = log
        self.subs = set()
        self.lock = threading.Lock()
        self.thread = None

    def subscribe(self, maxsize=10):
        q = queue.Queue(maxsize)
        with self.lock:
            self.subs.add(q)
            if self.thread is None:
                self.thread = threading.Thread(target=self._run, name="audio-capture", daemon=True)
                self.thread.start()
        return q

    def unsubscribe(self, q):
        with self.lock:
            self.subs.discard(q)

    def _active(self):
        with self.lock:
            return bool(self.subs)

    def _run(self):
        try:
            for chunk in self.source(self._active):
                with self.lock:
                    subs = list(self.subs)
                if not subs:
                    break
                for q in subs:
                    # keep latency low: a slow listener loses its oldest audio, not the newest
                    try:
                        q.put_nowait(chunk)
                    except queue.Full:
                        try:
                            q.get_nowait()
                        except queue.Empty:
                            pass
                        try:
                            q.put_nowait(chunk)
                        except queue.Full:
                            pass
        except Exception as e:
            self.log(f"audio: capture stopped ({e!r})")
        finally:
            with self.lock:
                self.thread = None
                restart = bool(self.subs)
            if restart:
                time.sleep(1)
                with self.lock:
                    if self.thread is None and self.subs:
                        self.thread = threading.Thread(target=self._run, name="audio-capture", daemon=True)
                        self.thread.start()
