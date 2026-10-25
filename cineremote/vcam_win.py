"""Windows 11 virtual webcam output (one webcam per connected phone).

Pieces (see vcam/):
  cineremote-vcam.dll       Media Foundation virtual camera source, loaded by the Camera Frame Server
                       service. Installed once (admin) by vcam/install_vcam.bat.
  cineremote-vcam-host.exe  Creates/removes the virtual cameras (MFCreateVirtualCamera, System lifetime).
  this module          Runs the host and writes frames into the Global\\ shared memory the DLL reads.

Cameras are persistent registrations (current user, no admin): they survive this app exiting, so OBS /
Teams keep a valid device and see black until we feed it again - instead of a dead device frozen on
its last frame. A registration is (friendly name, slot CLSID), so the slot -> name map is saved in
SLOTS_FILE and the same phone gets the same webcam every time. Unticking "Virtual webcam" (or
`python -m cineremote.vcam --remove-all`) unregisters.

The DLL creates the shared section when an app starts using the camera, so writers poll for it and
only convert frames while a reader is active (reader_tick fresh) to save CPU.
"""
import ctypes
import json
import logging
import os
import queue
import struct
import subprocess
import sys
import threading
import time
import winreg
from ctypes import wintypes

import numpy as np
from av.video.reformatter import VideoReformatter

log = logging.getLogger("cineremote.vcam")

SLOTS = 4
WIDTH, HEIGHT, FPS = 1920, 1080, 30
HEADER_SIZE = 4096
FRAME_SIZE = WIDTH * HEIGHT * 3 // 2
BUFFERS = 3
NO_BUFFER = 0xFFFFFFFF
MAP_SIZE = HEADER_SIZE + BUFFERS * FRAME_SIZE
MAGIC = 0x43564D42
MAP_NAME = "Global\\CineRemoteVCam_v2_s{}"
EVT_NAME = "Global\\CineRemoteVCam_v2_s{}_evt"
CLSID0 = "{98ECAD93-96D3-4645-9A5A-D0B60C18F786}"
INSTALL_DIR = r"C:\Program Files\CineRemote"
SLOTS_FILE = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "CineRemote", "vcam_slots.json")

_k32 = ctypes.WinDLL("kernel32", use_last_error=True)
_k32.OpenFileMappingW.restype = wintypes.HANDLE
_k32.OpenFileMappingW.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR]
_k32.MapViewOfFile.restype = ctypes.c_void_p
_k32.MapViewOfFile.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, ctypes.c_size_t]
_k32.UnmapViewOfFile.argtypes = [ctypes.c_void_p]
_k32.CloseHandle.argtypes = [wintypes.HANDLE]
_k32.GetTickCount64.restype = ctypes.c_ulonglong
_k32.OpenEventW.restype = wintypes.HANDLE
_k32.OpenEventW.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR]
_k32.SetEvent.argtypes = [wintypes.HANDLE]
FILE_MAP_RW = 0x0002 | 0x0004          # the DLL's DACL grants interactive users exactly this
EVENT_MODIFY_STATE = 0x0002


def is_installed():
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, rf"SOFTWARE\Classes\CLSID\{CLSID0}\InprocServer32") as k:
            path, _ = winreg.QueryValueEx(k, "")
        return os.path.exists(path)
    except OSError:
        return False


def host_exe():
    # The host runs as the user, so the copy shipped with the app wins (updating it needs no admin);
    # the Program Files copy from install_vcam.bat is the fallback.
    for p in (os.path.join(getattr(sys, "_MEIPASS", ""), "cineremote-vcam-host.exe"),
              os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vcam", "out",
                           "cineremote-vcam-host.exe"),
              os.path.join(INSTALL_DIR, "cineremote-vcam-host.exe")):
        if os.path.exists(p):
            return p
    return None


class VcamHost:
    """Owns cineremote-vcam-host.exe, which registers / unregisters the (persistent) virtual cameras."""

    def __init__(self):
        exe = host_exe()
        if not exe:
            raise RuntimeError("cineremote-vcam-host.exe not found")
        self.proc = subprocess.Popen([exe], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     text=True, encoding="utf-8", bufsize=1,
                                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self._lock = threading.Lock()
        self._lines = queue.Queue()
        threading.Thread(target=self._pump, daemon=True).start()
        line = self._read(10)
        if not line.startswith("ready"):
            raise RuntimeError(f"vcam host failed: {line}")
        self.supported = "supported=1" in line
        if not self.supported:
            raise RuntimeError("this Windows build does not support virtual cameras (needs Windows 11)")

    def _pump(self):
        for line in self.proc.stdout:
            self._lines.put(line.strip())
        self._lines.put("err host-exited")

    def _read(self, timeout):
        try:
            return self._lines.get(timeout=timeout)
        except queue.Empty:
            return "err timeout"

    def _cmd(self, cmd, timeout=10):
        with self._lock:
            self.proc.stdin.write(cmd + "\n")
            self.proc.stdin.flush()
            return self._read(timeout)

    def add(self, slot, name):
        r = self._cmd(f"add {slot} {name}")
        if not r.startswith("ok"):
            raise RuntimeError(f"could not create virtual camera: {r}")

    def remove(self, slot, name):
        r = self._cmd(f"remove {slot} {name}")
        if not r.startswith("ok"):
            log.warning("vcam: could not unregister '%s' (slot %d): %s", name, slot, r)

    def close(self):
        try:
            self.proc.stdin.write("quit\n")
            self.proc.stdin.flush()
            self.proc.wait(timeout=5)
        except Exception:
            self.proc.kill()


class VcamWriter:
    """Feeds one virtual camera slot. push() is cheap; conversion happens on a worker thread."""

    def __init__(self, slot):
        self.slot = slot
        self._frame = None
        self._evt = threading.Event()
        self._stop = False
        self._map = None
        self._ptr = None
        self._evt_h = None
        self._buf = None
        self._seq = 0
        self._geom = [None] * BUFFERS   # letterbox geometry last drawn into each buffer
        self._last_write = 0.0
        self.frames_written = 0
        # Own scaler: frame.reformat() caches one SwsContext per frame object, and the GUI/OBS paths
        # reformat the same frames on other threads; sharing it across threads deadlocked in sws_scale.
        self._reformatter = VideoReformatter()
        self._t = threading.Thread(target=self._run, name=f"vcam-writer-{slot}", daemon=True)
        self._t.start()

    def push(self, frame):
        self._frame = frame
        self._evt.set()

    def close(self):
        self._stop = True
        self._evt.set()
        self._t.join(timeout=5)
        if not self._t.is_alive():   # never unmap under a writer that is still copying into it
            self._unmap()

    # -- shared memory
    def _try_map(self):
        h = _k32.OpenFileMappingW(FILE_MAP_RW, False, MAP_NAME.format(self.slot))
        if not h:
            return False
        p = _k32.MapViewOfFile(h, FILE_MAP_RW, 0, 0, MAP_SIZE)
        if not p:
            _k32.CloseHandle(h)
            return False
        self._map, self._ptr = h, p
        raw = (ctypes.c_uint8 * MAP_SIZE).from_address(p)
        self._buf = np.ctypeslib.as_array(raw)
        if struct.unpack_from("<I", self._buf, 0)[0] != MAGIC:
            log.warning("vcam slot %d: bad magic", self.slot)
        self._evt_h = _k32.OpenEventW(EVENT_MODIFY_STATE, False, EVT_NAME.format(self.slot))
        self._seq = struct.unpack_from("<Q", self._buf, 16)[0]
        struct.pack_into("<Q", self._buf, 24, 0)   # no valid frame until we write one (no stale image)
        self._geom = [None] * BUFFERS
        log.info("vcam slot %d: attached to frame server shared memory", self.slot)
        return True

    def _unmap(self):
        if self._ptr:
            self._buf = None
            _k32.UnmapViewOfFile(self._ptr)
            _k32.CloseHandle(self._map)
            if self._evt_h:
                _k32.CloseHandle(self._evt_h)
            self._ptr = self._map = self._evt_h = None

    def _reader_active(self):
        rt = struct.unpack_from("<Q", self._buf, 32)[0]
        return rt and _k32.GetTickCount64() - rt < 2000

    # -- conversion: letterbox any frame into 1920x1080 NV12
    def _write(self, frame):
        fw, fh = frame.width, frame.height
        scale = min(WIDTH / fw, HEIGHT / fh)
        tw = max(2, int(fw * scale) & ~1)
        th = max(2, int(fh * scale) & ~1)
        nv = self._reformatter.reformat(frame, width=tw, height=th, format="nv12", threads=1)  # threaded: seam marks
        y_src = np.frombuffer(nv.planes[0], np.uint8).reshape(-1, nv.planes[0].line_size)[:th, :tw]
        uv_src = np.frombuffer(nv.planes[1], np.uint8).reshape(-1, nv.planes[1].line_size)[:th // 2, :tw]
        # Never write the published buffer or the one the camera service is copying right now.
        cur, busy = struct.unpack_from("<II", self._buf, 40)
        idx = next(i for i in range(BUFFERS) if i != cur and i != busy)
        base = HEADER_SIZE + idx * FRAME_SIZE
        y = self._buf[base:base + WIDTH * HEIGHT].reshape(HEIGHT, WIDTH)
        uv = self._buf[base + WIDTH * HEIGHT:base + FRAME_SIZE].reshape(HEIGHT // 2, WIDTH)
        x0, y0 = ((WIDTH - tw) // 2) & ~1, ((HEIGHT - th) // 2) & ~1
        if self._geom[idx] != (tw, th):   # borders only need drawing when the geometry changes
            y[:] = 16
            uv[:] = 128
            self._geom[idx] = (tw, th)
        y[y0:y0 + th, x0:x0 + tw] = y_src
        uv[y0 // 2:y0 // 2 + th // 2, x0:x0 + tw] = uv_src
        self._seq += 1
        struct.pack_into("<I", self._buf, 40, idx)
        struct.pack_into("<Q", self._buf, 24, _k32.GetTickCount64())
        struct.pack_into("<Q", self._buf, 16, self._seq)
        if self._evt_h:
            _k32.SetEvent(self._evt_h)    # the service delivers this frame immediately
        self.frames_written += 1

    def _run(self):
        last_try = 0.0
        while not self._stop:
            self._evt.wait(0.5)
            self._evt.clear()
            if self._stop:
                break
            if self._ptr is None:
                if time.monotonic() - last_try < 1.0:
                    continue
                last_try = time.monotonic()
                if not self._try_map():
                    continue
            fr, self._frame = self._frame, None
            if fr is None or not self._reader_active():
                continue
            now = time.monotonic()
            if now - self._last_write < 1.0 / FPS - 0.004:
                if self._frame is None:     # faster than the webcam's 30 fps: keep newest, write later
                    self._frame = fr
                self._evt.set()
                time.sleep(0.002)
                continue
            try:
                self._write(fr)
                self._last_write = now
            except Exception:
                log.exception("vcam write")


def webcam_name(name, slot):
    return " ".join((name or "").split())[:120] or f"CineRemote Camera {slot + 1}"


def _load_registry():
    """{slot: {"key", "name", "used"}} for the cameras we have registered (persisted)."""
    try:
        with open(SLOTS_FILE, encoding="utf-8") as f:
            raw = json.load(f)
        return {int(k): v for k, v in raw.items() if 0 <= int(k) < SLOTS and v.get("name")}
    except (OSError, ValueError, AttributeError):
        return {}


def _save_registry(reg):
    try:
        os.makedirs(os.path.dirname(SLOTS_FILE), exist_ok=True)
        tmp = SLOTS_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({str(k): v for k, v in reg.items()}, f, indent=1)
        os.replace(tmp, SLOTS_FILE)
    except OSError:
        log.exception("vcam: could not save %s", SLOTS_FILE)


class VirtualCameraManager:
    """Assigns phones to virtual camera slots (sticky across runs) and keeps their writers fed."""

    def __init__(self):
        self.host = VcamHost()
        self.slots = {}   # cam key -> (slot, writer), cameras being fed by this run
        self._reg_lock = threading.Lock()
        self.registry = _load_registry()

    def _pick_slot(self, key):
        busy = {s for s, _ in self.slots.values()}
        for slot, e in self.registry.items():
            if e.get("key") == key and slot not in busy:
                return slot                                   # this phone's webcam from before
        free = [s for s in range(SLOTS) if s not in busy and s not in self.registry]
        if free:
            return free[0]
        idle = [s for s in self.registry if s not in busy]    # recycle the longest-unused webcam
        if not idle:
            raise RuntimeError("all virtual camera slots in use")
        return min(idle, key=lambda s: self.registry[s].get("used", 0))

    def attach(self, key, name):
        if key in self.slots:
            return self.slots[key][0]
        with self._reg_lock:
            slot = self._pick_slot(key)
            want = webcam_name(name, slot)
            old = self.registry.get(slot)
            if old and old["name"] != want:
                self.host.remove(slot, old["name"])   # renamed phone / recycled slot: drop the old device
            self.host.add(slot, want)                 # reopens the existing device when unchanged
            self.registry[slot] = {"key": key, "name": want, "used": time.time()}
            _save_registry(self.registry)
        self.slots[key] = (slot, VcamWriter(slot))
        log.info("virtual camera '%s' -> slot %d", want, slot)
        return slot

    def detach(self, key, unregister=False):
        """Stop feeding a phone's webcam. The device stays registered (apps see black) unless
        unregister is set - the user turned the webcam off for this camera."""
        item = self.slots.pop(key, None)
        if item:
            item[1].close()
        if unregister:
            with self._reg_lock:
                for slot, e in list(self.registry.items()):
                    if e.get("key") == key:
                        self.host.remove(slot, e["name"])
                        del self.registry[slot]
                _save_registry(self.registry)

    def remove_all(self):
        for key in list(self.slots):
            self.detach(key)
        with self._reg_lock:
            for slot, e in list(self.registry.items()):
                self.host.remove(slot, e["name"])
            self.registry.clear()
            _save_registry(self.registry)

    def push(self, key, frame):
        item = self.slots.get(key)
        if item:
            item[1].push(frame)

    def close(self):
        for key in list(self.slots):
            self.detach(key)
        self.host.close()


if __name__ == "__main__":
    if "--remove-all" in sys.argv:
        m = VirtualCameraManager()
        n = len(m.registry)
        m.remove_all()
        m.close()
        print(f"removed {n} virtual camera(s)")
    else:
        print("usage: python -m cineremote.vcam --remove-all")
