"""Shared-memory frame publisher consumed by the native OBS source plugin (obs-plugin/).

One shared-memory block per camera slot (N = 0..MAX_SLOTS-1):
  Windows       named file mapping Local\\CineRemote_v1_slot<N> + auto-reset event ..._evt per new chunk
  Linux/macOS   POSIX shm /cineremote_v1_s<N> (shm_open); the plugin polls the header every 2 ms

Layout (little endian, must match obs-plugin/src/cineremote-shm.h):
  0    u32 magic 'BMCR'        4  u32 version (1)       8  u32 active        12 u32 generation
  16   u64 heartbeat_ns (perf_counter_ns of last write)
  24   char name[128]          152 char cam_id[64]
  216  u64 video_seq           224 u32 video_index      228 u32 color_range (1 = limited, 2 = full)
  232  VFrame[3] (40 bytes each): u32 width, height, format(1=I420), stride_y, stride_u, stride_v,
                                  u64 pts_ns, u32 data_size, u32 pad
  352  u32 audio_rate          356 u32 audio_channels   360 u32 audio_ring_frames  364 u32 pad
  368  u64 audio_write_frames (monotonic count of frames written)
  376  u64 audio_pts_ns (timestamp of the sample at audio_write_frames)
  HEADER_SIZE = 4096
  [HEADER_SIZE ...]                       audio ring: audio_ring_frames * channels * int16
  [HEADER_SIZE + AUDIO_BYTES ...]         3 video buffers of VIDEO_BUF_SIZE each (I420 planes packed)

Counters are written with single aligned 4/8-byte stores (struct.pack_into('<Q') writes byte by
byte, so a concurrent reader could see a half-written value). `generation` changes per publisher
instance so the reader can tell a restart from a torn read.
Timestamps use time.perf_counter_ns(), the same clock OBS uses for os_gettime_ns() on every platform
(QueryPerformanceCounter / CLOCK_MONOTONIC / mach_absolute_time), so the plugin hands them to OBS directly.
"""
import ctypes
import mmap
import os
import platform
import struct
import sys
import threading
import time

import numpy as np

from av.video.reformatter import VideoReformatter

MAGIC = 0x52434D42  # 'BMCR'
VERSION = 1
MAX_SLOTS = 8
HEADER_SIZE = 4096
AUDIO_RATE = 48000
AUDIO_CH = 2
AUDIO_RING_FRAMES = AUDIO_RATE * 2
AUDIO_BYTES = AUDIO_RING_FRAMES * AUDIO_CH * 2
MAX_W, MAX_H = 3840, 3840
MAX_PIXELS = MAX_W * MAX_H
VIDEO_BUF_SIZE = MAX_PIXELS * 3 // 2
TOTAL_SIZE = HEADER_SIZE + AUDIO_BYTES + 3 * VIDEO_BUF_SIZE
STALE_NS = 3_000_000_000  # a slot whose heartbeat is older than this is considered abandoned

# u64 / u32 indexes of header fields
Q_HEARTBEAT, Q_VSEQ, Q_AWRITE, Q_APTS = 16 // 8, 216 // 8, 368 // 8, 376 // 8
I_ACTIVE, I_GEN, I_VINDEX, I_CRANGE = 8 // 4, 12 // 4, 224 // 4, 228 // 4

WINDOWS = sys.platform == "win32"
# x86 keeps stores in program order; ARM (Apple Silicon, ARM Linux) may not, so publish the frame
# index/sequence only after a full barrier (a mutex round trip is one on every platform).
_NEEDS_FENCE = platform.machine().lower() not in ("amd64", "x86_64", "x86", "i386", "i686")
_fence_lock = threading.Lock()


def _fence():
    if _NEEDS_FENCE:
        with _fence_lock:
            pass


def slot_name(n):
    """Windows: named file mapping. POSIX: shm_open name (macOS caps these at 31 chars)."""
    return f"Local\\CineRemote_v1_slot{n}" if WINDOWS else f"/cineremote_v1_s{n}"


class _WinMapping:
    """Named file mapping + auto-reset event (the plugin waits on the event: zero-latency wakeups)."""

    def __init__(self):
        from ctypes import wintypes
        k = ctypes.WinDLL("kernel32", use_last_error=True)
        k.CreateEventW.restype = wintypes.HANDLE
        k.CreateEventW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.BOOL, wintypes.LPCWSTR]
        k.SetEvent.argtypes = [wintypes.HANDLE]
        k.CloseHandle.argtypes = [wintypes.HANDLE]
        k.CreateFileMappingW.restype = wintypes.HANDLE
        k.CreateFileMappingW.argtypes = [wintypes.HANDLE, wintypes.LPVOID, wintypes.DWORD, wintypes.DWORD,
                                         wintypes.DWORD, wintypes.LPCWSTR]
        k.MapViewOfFile.restype = ctypes.c_void_p
        k.MapViewOfFile.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, ctypes.c_size_t]
        k.UnmapViewOfFile.argtypes = [ctypes.c_void_p]
        self.k = k
        self.h = self.p = self.evt = None

    def open(self, name):
        """-> (memoryview, existed) or None."""
        k = self.k
        h = k.CreateFileMappingW(ctypes.c_void_p(-1).value, None, 0x04, 0, TOTAL_SIZE, name)
        existed = ctypes.get_last_error() == 183           # ERROR_ALREADY_EXISTS
        if not h:
            return None
        p = k.MapViewOfFile(h, 0xF001F, 0, 0, TOTAL_SIZE)
        if not p:
            k.CloseHandle(h)
            return None
        self.h, self.p = h, p
        return memoryview((ctypes.c_char * TOTAL_SIZE).from_address(p)).cast("B"), existed

    def open_event(self, name):
        self.evt = self.k.CreateEventW(None, False, False, name + "_evt")

    def signal(self):
        if self.evt:
            self.k.SetEvent(self.evt)

    def close(self, unlink):
        if self.p:
            self.k.UnmapViewOfFile(self.p)
        for hd in (self.h, self.evt):
            if hd:
                self.k.CloseHandle(hd)
        self.h = self.p = self.evt = None


class _PosixMapping:
    """POSIX shared memory (shm_open + mmap) on Linux and macOS. There are no named auto-reset events,
    so the plugin polls the header every 2 ms; signal() is a no-op."""

    def __init__(self):
        import _posixshmem
        self._shm = _posixshmem
        self.mm = None
        self.name = None

    def open(self, name):
        try:
            fd = self._shm.shm_open(name, os.O_CREAT | os.O_RDWR, 0o600)
        except OSError:
            return None
        try:
            size = os.fstat(fd).st_size
            existed = size >= TOTAL_SIZE
            if size < TOTAL_SIZE:
                os.ftruncate(fd, TOTAL_SIZE)     # macOS allows this only once, on a fresh object
            self.mm = mmap.mmap(fd, TOTAL_SIZE)
        except OSError:
            os.close(fd)
            return None
        os.close(fd)
        self.name = name
        return memoryview(self.mm), existed

    def open_event(self, name):
        pass

    def signal(self):
        pass

    def close(self, unlink):
        if self.mm is not None:
            self.mm.close()
            self.mm = None
        if unlink and self.name:
            try:                       # the plugin notices active=0, detaches and re-opens by name later
                self._shm.shm_unlink(self.name)
            except OSError:
                pass


def _mapping():
    return _WinMapping() if WINDOWS else _PosixMapping()


class ShmPublisher:
    """Publishes one camera's decoded video (av.VideoFrame) and audio (av.AudioFrame) to OBS."""

    _lock = threading.Lock()
    _taken = set()

    def __init__(self, name, cam_id):
        self._m = None
        with ShmPublisher._lock:
            for n in range(MAX_SLOTS):
                if n in ShmPublisher._taken:
                    continue
                if self._claim(n):
                    self.slot = n
                    ShmPublisher._taken.add(n)
                    break
            else:
                raise RuntimeError("no free OBS output slots")
        self._m.open_event(slot_name(self.slot))
        self._reformatter = VideoReformatter()  # never share frame.reformat() across threads
        self._vseq = 0
        self._vidx = 0
        self._apos = 0
        self._closed = False
        hdr = bytearray(HEADER_SIZE)
        struct.pack_into("<IIII", hdr, 0, MAGIC, VERSION, 0, 0)
        hdr[24:24 + 128] = name.encode("utf-8")[:127].ljust(128, b"\0")
        hdr[152:152 + 64] = str(cam_id).encode("utf-8")[:63].ljust(64, b"\0")
        struct.pack_into("<IIII", hdr, 352, AUDIO_RATE, AUDIO_CH, AUDIO_RING_FRAMES, 0)
        self._mem[0:HEADER_SIZE] = bytes(hdr)
        self._u32[I_GEN] = int.from_bytes(os.urandom(4), "little") | 1
        self._u64[Q_HEARTBEAT] = time.perf_counter_ns()
        self._u32[I_ACTIVE] = 1

    def _claim(self, n):
        """Create (or take over an abandoned) mapping for slot n. Returns True if we own it."""
        m = _mapping()
        r = m.open(slot_name(n))
        if r is None:
            return False
        mem, existed = r
        u32, u64 = mem.cast("I"), mem.cast("Q")
        if existed and u32[0] == MAGIC and u32[I_ACTIVE] and \
                time.perf_counter_ns() - u64[Q_HEARTBEAT] < STALE_NS:
            for v in (u64, u32, mem):
                v.release()
            m.close(unlink=False)
            return False  # a live publisher (another app instance) owns this slot
        self._m, self._mem, self._u32, self._u64 = m, mem, u32, u64
        return True

    def set_name(self, name):
        self._mem[24:24 + 128] = name.encode("utf-8")[:127].ljust(128, b"\0")

    def push_video(self, frame, pts_ns=None):
        if self._closed:
            return
        if frame.format.name != "yuv420p":
            frame = self._reformatter.reformat(frame, format="yuv420p", threads=1)   # see VideoView
        w, h = frame.width, frame.height
        if w * h > MAX_PIXELS:
            s = (MAX_PIXELS / (w * h)) ** 0.5
            frame = self._reformatter.reformat(frame, width=int(w * s) & ~1, height=int(h * s) & ~1, threads=1)
            w, h = frame.width, frame.height
        idx = (self._vidx + 1) % 3
        base = HEADER_SIZE + AUDIO_BYTES + idx * VIDEO_BUF_SIZE
        off = base
        strides = []
        mem = self._mem
        for i, plane in enumerate(frame.planes[:3]):
            pw = w if i == 0 else (w + 1) // 2
            ph = h if i == 0 else (h + 1) // 2
            ls = plane.line_size
            buf = memoryview(plane)
            if ls == pw:
                mem[off:off + pw * ph] = buf[:pw * ph]
            else:
                for row in range(ph):
                    mem[off + row * pw:off + (row + 1) * pw] = buf[row * ls:row * ls + pw]
            strides.append(pw)
            off += pw * ph
        pts = pts_ns if pts_ns is not None else time.perf_counter_ns()
        # descriptor of a buffer the reader is not using (it reads video_index, which still points elsewhere)
        struct.pack_into("<IIIIIIQII", mem, 232 + idx * 40, w, h, 1, strides[0], strides[1], strides[2],
                         pts, off - base, 0)
        cr = getattr(frame, "color_range", 0)
        self._u32[I_CRANGE] = 2 if cr == 2 else 1
        self._vidx = idx
        self._vseq += 1
        _fence()                                # frame data + descriptor visible before the index
        self._u32[I_VINDEX] = idx
        self._u64[Q_VSEQ] = self._vseq          # publish (single aligned stores)
        self._u64[Q_HEARTBEAT] = pts
        self._m.signal()

    def push_audio(self, frame, pts_ns=None):
        """frame: av.AudioFrame (any format/layout) -> interleaved s16 stereo 48k."""
        if self._closed:
            return
        if frame.format.name != "s16" or frame.layout.name != "stereo" or frame.sample_rate != AUDIO_RATE:
            if not hasattr(self, "_resampler"):
                import av
                self._resampler = av.AudioResampler(format="s16", layout="stereo", rate=AUDIO_RATE)
            frames = self._resampler.resample(frame)
        else:
            frames = [frame]
        mem = self._mem
        for fr in frames:
            n = fr.samples
            data = memoryview(fr.planes[0])[:n * AUDIO_CH * 2]
            start = self._apos % AUDIO_RING_FRAMES
            first = min(n, AUDIO_RING_FRAMES - start)
            a0 = HEADER_SIZE + start * AUDIO_CH * 2
            mem[a0:a0 + first * AUDIO_CH * 2] = data[:first * AUDIO_CH * 2]
            if n > first:
                mem[HEADER_SIZE:HEADER_SIZE + (n - first) * AUDIO_CH * 2] = data[first * AUDIO_CH * 2:]
            self._apos += n
            end_pts = pts_ns if pts_ns is not None else time.perf_counter_ns()
            _fence()
            self._u64[Q_APTS] = end_pts
            self._u64[Q_AWRITE] = self._apos
            self._u64[Q_HEARTBEAT] = end_pts
        self._m.signal()  # wake the reader now; don't make audio wait for the next video frame

    def push_pcm(self, pcm, pts_ns=None):
        """pcm: int16 ndarray, interleaved stereo 48 kHz (already gain-adjusted by the backend)."""
        if self._closed:
            return
        data = memoryview(np.ascontiguousarray(pcm, dtype=np.int16)).cast("B")
        n = len(data) // (AUDIO_CH * 2)
        if not n:
            return
        mem = self._mem
        start = self._apos % AUDIO_RING_FRAMES
        first = min(n, AUDIO_RING_FRAMES - start)
        a0 = HEADER_SIZE + start * AUDIO_CH * 2
        mem[a0:a0 + first * AUDIO_CH * 2] = data[:first * AUDIO_CH * 2]
        if n > first:
            mem[HEADER_SIZE:HEADER_SIZE + (n - first) * AUDIO_CH * 2] = data[first * AUDIO_CH * 2:n * AUDIO_CH * 2]
        self._apos += n
        end_pts = pts_ns if pts_ns is not None else time.perf_counter_ns()
        _fence()
        self._u64[Q_APTS] = end_pts
        self._u64[Q_AWRITE] = self._apos
        self._u64[Q_HEARTBEAT] = end_pts
        self._m.signal()

    def close(self):
        if self._closed:
            return
        self._closed = True
        try:
            self._u32[I_ACTIVE] = 0
            self._m.signal()
        finally:
            for v in (self._u64, self._u32, self._mem):
                v.release()
            self._m.close(unlink=True)
            with ShmPublisher._lock:
                ShmPublisher._taken.discard(self.slot)
