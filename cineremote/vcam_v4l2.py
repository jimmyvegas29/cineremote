"""Linux virtual webcam: one v4l2loopback output device per connected phone.

Setup (once, needs root; the app can run it through pkexec):
    sudo modprobe v4l2loopback devices=4 video_nr=40,41,42,43 exclusive_caps=1 \
        card_label="CineRemote Camera 1,CineRemote Camera 2,CineRemote Camera 3,CineRemote Camera 4"
plus membership of the `video` group for the user. Each phone gets the first free loopback device;
apps (OBS "Video Capture Device (V4L2)", Zoom, browsers) see it as a normal webcam. Frames are
letterboxed into 1920x1080 I420 (fourcc YU12) and written with write(2), the v4l2loopback output API.
"""
import errno
import fcntl
import glob
import logging
import os
import struct
import threading
import time

import numpy as np
from av.video.reformatter import VideoReformatter

log = logging.getLogger("cineremote.vcam")

WIDTH, HEIGHT, FPS = 1920, 1080, 30
FRAME_SIZE = WIDTH * HEIGHT * 3 // 2
LABEL_PREFIX = "CineRemote Camera"

VIDIOC_QUERYCAP = 0x80685600            # _IOR('V', 0, struct v4l2_capability[104])
VIDIOC_S_FMT = 0xC0D05605               # _IOWR('V', 5, struct v4l2_format[208])
V4L2_BUF_TYPE_VIDEO_OUTPUT = 2
V4L2_FIELD_NONE = 1
V4L2_COLORSPACE_REC709 = 3
V4L2_QUANTIZATION_LIM_RANGE = 2
FOURCC_YU12 = int.from_bytes(b"YU12", "little")

SETUP_COMMAND = ("modprobe v4l2loopback devices=4 video_nr=40,41,42,43 exclusive_caps=1 "
                 "card_label=\"" + ",".join(f"{LABEL_PREFIX} {i}" for i in range(1, 5)) + "\"")
SETUP_HINT = ("Linux webcams use the v4l2loopback kernel module. Install it (Ubuntu: sudo apt install "
              "v4l2loopback-dkms, or use the one your kernel ships), add yourself to the 'video' group, "
              "then load it:\n  sudo " + SETUP_COMMAND)


def _loopback_devices():
    """[(path, label)] of v4l2loopback devices this user can write to."""
    out = []
    for path in sorted(glob.glob("/dev/video*"), key=lambda p: int(p[10:]) if p[10:].isdigit() else 999):
        try:
            fd = os.open(path, os.O_RDWR | os.O_NONBLOCK)
        except OSError:
            continue
        try:
            cap = bytearray(104)
            fcntl.ioctl(fd, VIDIOC_QUERYCAP, cap)
            driver = bytes(cap[0:16]).split(b"\0")[0].decode(errors="replace")
            card = bytes(cap[16:48]).split(b"\0")[0].decode(errors="replace")
            if "loopback" in driver.lower():
                out.append((path, card))
        except OSError:
            pass
        finally:
            os.close(fd)
    return out


def is_installed():
    return bool(_loopback_devices())


class V4l2Writer:
    """Feeds one loopback device. push() is cheap; conversion + write happen on a worker thread."""

    def __init__(self, path):
        self.path = path
        self.fd = os.open(path, os.O_RDWR)
        fmt = bytearray(208)
        struct.pack_into("<I4xIIIIIIIIIIII", fmt, 0, V4L2_BUF_TYPE_VIDEO_OUTPUT, WIDTH, HEIGHT, FOURCC_YU12,
                         V4L2_FIELD_NONE, WIDTH, FRAME_SIZE, V4L2_COLORSPACE_REC709, 0, 0, 0,
                         V4L2_QUANTIZATION_LIM_RANGE, 0)
        fcntl.ioctl(self.fd, VIDIOC_S_FMT, fmt)
        self._buf = np.empty(FRAME_SIZE, np.uint8)
        self._buf[:WIDTH * HEIGHT] = 16                 # black, letterbox borders
        self._buf[WIDTH * HEIGHT:] = 128
        self._geom = None
        self._frame = None
        self._evt = threading.Event()
        self._stop = False
        self._last = 0.0
        self.frames_written = 0
        self._reformatter = VideoReformatter()          # own scaler (never shared across threads)
        self._t = threading.Thread(target=self._run, name=f"v4l2-writer-{os.path.basename(path)}", daemon=True)
        self._t.start()

    def push(self, frame):
        self._frame = frame
        self._evt.set()

    def close(self):
        self._stop = True
        self._evt.set()
        self._t.join(timeout=5)
        try:
            os.close(self.fd)
        except OSError:
            pass

    def _write(self, frame):
        sc = min(WIDTH / frame.width, HEIGHT / frame.height)
        tw, th = max(2, int(frame.width * sc) & ~1), max(2, int(frame.height * sc) & ~1)
        f = self._reformatter.reformat(frame, width=tw, height=th, format="yuv420p")
        x0, y0 = ((WIDTH - tw) // 2) & ~1, ((HEIGHT - th) // 2) & ~1
        if self._geom != (tw, th):
            self._buf[:WIDTH * HEIGHT] = 16
            self._buf[WIDTH * HEIGHT:] = 128
            self._geom = (tw, th)
        y = self._buf[:WIDTH * HEIGHT].reshape(HEIGHT, WIDTH)
        u = self._buf[WIDTH * HEIGHT:WIDTH * HEIGHT * 5 // 4].reshape(HEIGHT // 2, WIDTH // 2)
        v = self._buf[WIDTH * HEIGHT * 5 // 4:].reshape(HEIGHT // 2, WIDTH // 2)
        for plane, dst, w, h, ox, oy in ((f.planes[0], y, tw, th, x0, y0),
                                          (f.planes[1], u, tw // 2, th // 2, x0 // 2, y0 // 2),
                                          (f.planes[2], v, tw // 2, th // 2, x0 // 2, y0 // 2)):
            src = np.frombuffer(plane, np.uint8)[:plane.line_size * h].reshape(h, plane.line_size)[:, :w]
            dst[oy:oy + h, ox:ox + w] = src
        os.write(self.fd, self._buf)
        self.frames_written += 1

    def _run(self):
        while not self._stop:
            self._evt.wait(0.5)
            self._evt.clear()
            fr, self._frame = self._frame, None
            if self._stop or fr is None:
                continue
            if time.monotonic() - self._last < 1.0 / FPS - 0.004:
                if self._frame is None:                 # faster than 30 fps: keep newest, write later
                    self._frame = fr
                time.sleep(0.003)
                self._evt.set()
                continue
            try:
                self._write(fr)
                self._last = time.monotonic()
            except OSError as e:
                if e.errno not in (errno.EAGAIN, errno.EINTR):
                    log.warning("v4l2 write %s: %s", self.path, e)
                    time.sleep(0.5)
            except Exception:
                log.exception("v4l2 write")


class VirtualCameraManager:
    """Same interface as the Windows manager: one loopback device per phone."""

    def __init__(self):
        self.slots = {}          # cam key -> (path, writer)

    def attach(self, key, name):
        if key in self.slots:
            return self._index(self.slots[key][0])
        busy = {p for p, _ in self.slots.values()}
        for path, label in _loopback_devices():
            if path in busy:
                continue
            try:
                w = V4l2Writer(path)
            except OSError as e:
                log.info("v4l2 %s unavailable (%s)", path, e)
                continue
            self.slots[key] = (path, w)
            log.info("virtual camera '%s' -> %s (%s)", name, path, label)
            return self._index(path)
        raise RuntimeError("no free v4l2loopback device (load the module with more devices)")

    @staticmethod
    def _index(path):
        devs = [p for p, _ in _loopback_devices()]
        return devs.index(path) if path in devs else 0

    def detach(self, key, unregister=False):
        item = self.slots.pop(key, None)
        if item:
            item[1].close()

    def push(self, key, frame):
        item = self.slots.get(key)
        if item:
            item[1].push(frame)

    def remove_all(self):
        for key in list(self.slots):
            self.detach(key)

    def close(self):
        self.remove_all()

    def device_label(self, key):
        item = self.slots.get(key)
        if not item:
            return None
        return next((label for p, label in _loopback_devices() if p == item[0]), item[0])
