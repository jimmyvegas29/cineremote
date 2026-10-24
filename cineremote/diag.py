"""Optional performance diagnostics (--diag or CINEREMOTE_DIAG=1): every few seconds, log per camera

  fps, PC-side latency (last RTP packet of a frame arrives -> decoded frame reaches the app; p50/p95/max),
  frame gaps > 2.5 frame intervals, and damaged frames the jitter buffer had to skip (lost packets),

plus asyncio loop lag (how long a ready callback waits before it runs: the loop carries every
camera's RTP, so this is where Python-side contention shows first) and process CPU.

The completion time is stamped on aiortc's JitterFrame, which reaches our H265Decoder unchanged, and
copied onto the decoded frame's .opaque (aiortc rewrites RTP timestamps, so pts can't be matched).
"""
import asyncio
import collections
import logging
import os
import statistics
import threading
import time

log = logging.getLogger("cineremote.diag")

REPORT_S = 5.0
_lock = threading.Lock()
_skipped = collections.Counter()          # jitter buffer id -> damaged frames skipped
_jb_cam = {}                              # jitter buffer id -> camera name
_cams = {}                                # camera name -> _CamStats
_loop_lag = []
_installed = False


def enabled():
    return os.environ.get("CINEREMOTE_DIAG") == "1"


class _CamStats:
    def __init__(self):
        self.lat = []
        self.frames = 0
        self.gaps = 0
        self.unmatched = 0
        self.last = None
        self.interval = None
        self.size = ""


def _pct(v, p):
    return sorted(v)[min(len(v) - 1, int(len(v) * p))] if v else float("nan")


def install(loop):
    """Hook the jitter buffer and start the loop-lag probe + reporter on the backend loop."""
    global _installed
    if _installed:
        return
    _installed = True
    from . import rtc_compat as rc

    orig_remove = rc._LowLatencyJitterBuffer._remove_frame
    orig_skip = rc._LowLatencyJitterBuffer._skip_hole

    def remove_frame(self, seq):
        f = orig_remove(self, seq)
        if f is not None and self._is_video:
            f.cineremote_diag = (time.perf_counter(), id(self))
        return f

    orig_decode = rc.H265Decoder.decode

    def decode(self, encoded_frame):
        frames = orig_decode(self, encoded_frame)
        stamp = getattr(encoded_frame, "cineremote_diag", None)
        for fr in frames:
            fr.opaque = stamp
        return frames

    def skip_hole(self, count):
        if self._is_video:
            _skipped[id(self)] += 1
        return orig_skip(self, count)

    rc._LowLatencyJitterBuffer._remove_frame = remove_frame
    rc._LowLatencyJitterBuffer._skip_hole = skip_hole
    rc.H265Decoder.decode = decode
    asyncio.run_coroutine_threadsafe(_probe(), loop)
    threading.Thread(target=_reporter, name="cineremote-diag", daemon=True).start()
    log.info("diagnostics on: reporting every %.0fs", REPORT_S)


def frame(cam, fr):
    """Call when a decoded video frame reaches the app (on the backend loop)."""
    now = time.perf_counter()
    with _lock:
        st = _cams.setdefault(cam, _CamStats())
        st.frames += 1
        st.size = f"{fr.width}x{fr.height}"
        hit = getattr(fr, "opaque", None)
        if hit:
            st.lat.append((now - hit[0]) * 1000)
            _jb_cam[hit[1]] = cam
        else:
            st.unmatched += 1
        if st.last is not None:
            dt = now - st.last
            st.interval = dt if st.interval is None else st.interval * 0.95 + dt * 0.05
            if st.interval and dt > 2.5 * st.interval:
                st.gaps += 1
        st.last = now


async def _probe():
    while True:
        await asyncio.sleep(0.01)
        t = time.perf_counter()
        await asyncio.sleep(0)          # back of the ready queue: measures the wait
        _loop_lag.append((time.perf_counter() - t) * 1000)


def _reporter():
    import psutil
    proc = psutil.Process()
    proc.cpu_percent(None)
    skipped_seen = collections.Counter()
    prev_t = {}

    def thread_split():
        """CPU% by thread group since last call: Python threads (they share the GIL) vs native
        threads (FFmpeg decode/scale, OS), keyed by Python thread name prefix."""
        names = {t.native_id: t.name for t in threading.enumerate()}
        groups = collections.Counter()
        for th in proc.threads():
            busy = th.user_time + th.system_time
            d = busy - prev_t.get(th.id, busy)
            prev_t[th.id] = busy
            n = names.get(th.id)
            key = "native" if n is None else ("py:" + n.rstrip("0123456789-_ ").split("(")[0].strip())
            groups[key] += d
        return groups

    thread_split()
    while True:
        time.sleep(REPORT_S)
        cpu = proc.cpu_percent(None)
        split = thread_split()
        with _lock:
            cams = {k: v for k, v in _cams.items()}
            for k in list(_cams):
                _cams[k] = _CamStats()
                _cams[k].last, _cams[k].interval, _cams[k].size = cams[k].last, cams[k].interval, cams[k].size
            lag, _loop_lag[:] = list(_loop_lag), []
            skipped = {}
            for jb, n in _skipped.items():
                cam = _jb_cam.get(jb, "?")
                skipped[cam] = skipped.get(cam, 0) + n - skipped_seen[jb]
                skipped_seen[jb] = n
        for name, st in sorted(cams.items()):
            log.info("%-22s %9s fps %5.1f | latency p50 %5.1f p95 %5.1f max %6.1f ms | gaps %d | skipped %d | unmatched %d",
                     name[:22], st.size, st.frames / REPORT_S, statistics.median(st.lat) if st.lat else float("nan"),
                     _pct(st.lat, 0.95), max(st.lat) if st.lat else float("nan"), st.gaps,
                     skipped.get(name, 0), st.unmatched)
        log.info("loop lag p50 %.1f p99 %.1f max %.1f ms | cpu %.0f%% (of one core) | threads %d",
                 statistics.median(lag) if lag else float("nan"), _pct(lag, 0.99), max(lag) if lag else float("nan"),
                 cpu, proc.num_threads())
        log.info("cpu by thread: %s", ", ".join(f"{k} {v / REPORT_S * 100:.0f}%" for k, v in split.most_common(8)))
