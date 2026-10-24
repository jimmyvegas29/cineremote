"""Measure PC-side latency: last RTP packet of a frame arrives -> decoded frame delivered to the app
(the point where it is copied to OBS / the webcam). Uses the low-latency jitter buffer hook.

    python tools/latency_probe.py PHONE_IP PASSWORD
"""
import asyncio, collections, os, statistics, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from cineremote import rtc_compat as rc
from cineremote.session import CameraSession

completed = collections.deque()
orig = rc._LowLatencyJitterBuffer._remove_frame
def hooked(self, seq):
    f = orig(self, seq)
    if f is not None and self._is_video:
        completed.append(time.perf_counter())
    return f
rc._LowLatencyJitterBuffer._remove_frame = hooked

async def main(host, pw):
    s = CameraSession(host, 2222, pw, "Latency Probe")
    deltas = []
    def on_video(fr):
        if completed:
            deltas.append((time.perf_counter() - completed.popleft()) * 1000)
    s.on_video = on_video
    try:
        await s.connect(); await s.start_preview(); await asyncio.sleep(10)
    finally:
        await s.close()
    d = deltas[24:]  # skip warm-up
    print(f"frames={len(d)}  frame-complete -> decoded-in-app: median {statistics.median(d):.1f} ms, "
          f"p95 {sorted(d)[int(len(d)*0.95)]:.1f} ms, max {max(d):.1f} ms")
asyncio.run(main(sys.argv[1], sys.argv[2]))
