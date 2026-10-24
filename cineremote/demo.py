"""Demo mode (`--demo`): simulated phones fed from still photos, for trying the app and OBS without a camera.

DemoSession stands in for CameraSession behind the same interface, so the backend's normal connect,
preview, OBS and recording paths run unchanged. The camera properties start from a real phone's
property set (demo_props.json) with per-camera variations; setters update them like the phone would.
"""
import asyncio
import json
import math
import os
import random
import threading
import time

import av
import numpy as np
from google.protobuf import json_format
from PySide6.QtCore import Qt
from PySide6.QtGui import QImage

from . import proto_msgs as pm

HERE = os.path.dirname(os.path.abspath(__file__))
PHOTO_DIRS = [os.path.join(HERE, "..", "assets", "demo"), os.path.join(HERE, "assets", "demo")]
MM_AUTO, MM_MANUAL = 1, 2

# name, photo, connected at start, overrides of the base property set
# (+ "_rec": seconds already recorded, "_armed": armed for Record armed)
CAMERAS = [
    ("Pixel 10 Pro (A)", "studio-wide.jpg", True, {
        "_rec": 754, "_armed": True,
        "battery": {"available_percentage": 0.86},
        "cam_sensibility": {"metering_mode": "METERING_MODE_MANUAL", "sensibility_iso": 400},
        "cam_shutter": {"metering_mode": "METERING_MODE_MANUAL", "shutter_speed": {"num": 1, "den": 60}},
        "cam_white_balance": {"metering_mode": "METERING_MODE_MANUAL", "white_balance_kelvin": 5600},
        "cam_zoom": {"zoom_factor": 1.0},
        "active_cam": {"camera_id": "Lens25mm", "is_auto": False},
        "cam_stabilization": {"level": "STABILIZATION_LEVEL_STANDARD"}}),
    ("Galaxy S24 Ultra (B)", "studio-angle.jpg", True, {
        "_rec": 751, "_armed": True,
        "battery": {"available_percentage": 0.64},
        "cam_sensibility": {"metering_mode": "METERING_MODE_MANUAL", "sensibility_iso": 640},
        "cam_shutter": {"metering_mode": "METERING_MODE_MANUAL", "shutter_speed": {"num": 1, "den": 60}},
        "cam_white_balance": {"metering_mode": "METERING_MODE_AUTO", "white_balance_kelvin": 4300},
        "cam_zoom": {"zoom_factor": 1.6}}),
    ("iPhone 16 Pro (C)", "studio-mixer.jpg", True, {
        "battery": {"available_percentage": 0.41},
        "cam_sensibility": {"metering_mode": "METERING_MODE_AUTO", "sensibility_iso": 250},
        "cam_shutter": {"metering_mode": "METERING_MODE_MANUAL", "shutter_speed": {"num": 1, "den": 50}},
        "cam_white_balance": {"metering_mode": "METERING_MODE_MANUAL", "white_balance_kelvin": 3200},
        "cam_zoom": {"zoom_factor": 3.0},
        "cam_focus": {"focus_value": 0.42, "is_transitioning": False, "is_locked": False}}),
    ("iPhone 15 (D)", "studio-mic.jpg", True, {
        "battery": {"available_percentage": 0.93},
        "cam_sensibility": {"metering_mode": "METERING_MODE_AUTO", "sensibility_iso": 800},
        "cam_white_balance": {"metering_mode": "METERING_MODE_AUTO", "white_balance_kelvin": 5100},
        "cam_zoom": {"zoom_factor": 2.0}}),
    ("Pixel 8 (E)", "studio-room.jpg", False, {"battery": {"available_percentage": 0.55}}),
]


def photo_path(name):
    for d in PHOTO_DIRS:
        p = os.path.normpath(os.path.join(d, name))
        if os.path.exists(p):
            return p
    return None


def _base_props():
    with open(os.path.join(HERE, "demo_props.json"), encoding="utf-8") as f:
        return json.load(f)


def _to_msg(field, value):
    prop = pm.Property()
    json_format.ParseDict({field: value}, prop, ignore_unknown_fields=True)
    return getattr(prop, field)


class DemoSession:
    """Same surface as session.CameraSession, driven by a timer instead of a phone."""

    def __init__(self, name, photo, overrides, fps=30):
        self.name, self.photo, self.fps = name, photo, fps
        self.state = "idle"
        self.stats = {"video_frames": 0, "audio_frames": 0, "width": 0, "height": 0, "fps": 0.0}
        self.on_state = self.on_property = self.on_event = self.on_video = self.on_audio = lambda *a: None
        raw = _base_props()
        raw.update(overrides)
        rec = raw.pop("_rec", None)
        raw.pop("_armed", None)
        self.props = {k: _to_msg(k, v) for k, v in raw.items() if k in pm.Property.DESCRIPTOR.fields_by_name}
        self._stop = threading.Event()
        self._thread = None
        self._rec_t0 = None if rec is None else time.monotonic() - rec
        if rec is not None:
            self.props["recording_state"] = pm.msg("RecordingStateProperty")(is_recording=True,
                                                                              is_writing_to_storage=True)
        self._t0 = time.monotonic()
        self._img = self._load(photo)

    @staticmethod
    def _load(path):
        """The photo, slightly larger than 1080p so the frame can drift like a handheld camera."""
        w, h = 2016, 1134
        im = QImage(path) if path else QImage()
        if im.isNull():      # no photo: a neutral gradient test card
            y, x = np.mgrid[0:h, 0:w]
            return np.stack([(x * 255 // w), (y * 255 // h), np.full_like(x, 96)], -1).astype(np.uint8)
        im = im.scaled(w, h, Qt.IgnoreAspectRatio, Qt.SmoothTransformation).convertToFormat(QImage.Format_RGB888)
        rows = np.frombuffer(im.constBits(), np.uint8, im.sizeInBytes()).reshape(h, im.bytesPerLine())
        return rows[:, :w * 3].reshape(h, w, 3).copy()     # own the pixels: `im` is freed on return

    def _set_state(self, s, detail=""):
        self.state = s
        self.on_state(s, detail)

    def _store(self, field, msg):
        self.props[field] = msg
        self.on_property(field, msg)

    # ------------------------------------------------------------------ lifecycle
    async def connect(self, timeout=20, busy_retry_secs=40):
        self._set_state("connecting", "demo")
        await asyncio.sleep(0.3)
        self._set_state("connected", "demo camera")
        for field, msg in list(self.props.items()):
            self.on_property(field, msg)

    async def start_preview(self, fps=None, audio_channels=2):
        # Frames come from a thread: time.sleep is precise on every OS (asyncio's timers on Windows
        # tick at ~15 ms, which would pace the feed at 24-25 fps instead of 30).
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name=f"demo-{self.name}", daemon=True)
        self._thread.start()
        await asyncio.sleep(0.2)
        self._set_state("previewing", "demo feed")
        return True

    async def stop_preview(self):
        self._stop.set()

    async def close(self, clean=True):
        self._stop.set()
        if self._thread is not None:
            await asyncio.get_running_loop().run_in_executor(None, self._thread.join, 2)
            self._thread = None
        if self.state != "disconnected":
            self._set_state("disconnected", "")

    async def read_all_properties(self, settle=2.0):
        return None

    # ------------------------------------------------------------------ media
    def _run(self):
        period = 1.0 / self.fps
        h, w = 1080, 1920
        mh, mw = self._img.shape[0] - h, self._img.shape[1] - w
        phase = random.random() * 10
        n, t_fps, n_fps = 0, time.monotonic(), 0
        next_t = time.monotonic()
        while not self._stop.is_set():
            t = time.monotonic() - self._t0 + phase
            x = int(mw / 2 + mw / 2 * 0.8 * math.sin(t * 0.21))
            y = int(mh / 2 + mh / 2 * 0.8 * math.sin(t * 0.17 + 1.3))
            frame = av.VideoFrame.from_ndarray(np.ascontiguousarray(self._img[y:y + h, x:x + w]), format="rgb24")
            self.on_video(frame)
            n += 1
            n_fps += 1
            self.stats["video_frames"] = n
            self.stats["width"], self.stats["height"] = w, h
            now = time.monotonic()
            if now - t_fps >= 1.0:
                self.stats["fps"] = n_fps / (now - t_fps)
                t_fps, n_fps = now, 0
            self._audio(t)
            if n % 10 == 0:
                self._tick_props()
            next_t += period
            delay = next_t - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            else:
                next_t = time.monotonic()     # fell behind: don't try to catch up with a burst

    def _audio(self, t):
        """1/30 s of speech-like noise, so the meters (all three zones) and OBS audio move."""
        samples = 1600
        # speech-like: room tone around -30 dBFS, phrases peaking into orange, the odd red burst
        env = 0.03 + 0.30 * max(0.0, math.sin(t * 2.3)) * (0.55 + 0.45 * math.sin(t * 7.1)) ** 2
        noise = np.random.default_rng().standard_normal((samples, 2)) * env
        pcm = np.clip(noise * 32767, -32768, 32767).astype(np.int16).reshape(1, -1)
        fr = av.AudioFrame.from_ndarray(pcm, format="s16", layout="stereo")
        fr.sample_rate = 48000
        self.stats["audio_frames"] += 1
        self.on_audio(fr)

    def _tick_props(self):
        rs = self.props.get("recording_state") or pm.msg("RecordingStateProperty")()
        m = pm.msg("RecordingStateProperty")()
        m.CopyFrom(rs)
        lt = time.localtime()
        secs = lt.tm_hour * 3600 + lt.tm_min * 60 + lt.tm_sec + time.time() % 1
        m.system_timecode.seconds, m.system_timecode.frame_count = int(secs), int((secs % 1) * self.fps)
        if self._rec_t0 is not None:
            d = time.monotonic() - self._rec_t0
            m.recorded_duration.seconds, m.recorded_duration.frame_count = int(d), int((d % 1) * self.fps)
        self._store("recording_state", m)

    # ------------------------------------------------------------------ commands (same names as CameraSession)
    async def write(self, field, value_msg, override_confirm=False):
        self._store(field, value_msg)

    async def set_iso(self, iso=None):
        cur = self.props.get("cam_sensibility")
        m = pm.msg("CamSensibilityProperty")(metering_mode=MM_AUTO if iso is None else MM_MANUAL,
                                             sensibility_iso=int(iso if iso is not None else
                                                                 (cur.sensibility_iso if cur else 400)))
        self._store("cam_sensibility", m)

    async def set_shutter(self, den=None):
        cur = self.props.get("cam_shutter")
        m = pm.msg("CamShutterProperty")(metering_mode=MM_AUTO if den is None else MM_MANUAL)
        m.shutter_speed.num, m.shutter_speed.den = 1, int(den or (cur.shutter_speed.den if cur else 60))
        self._store("cam_shutter", m)

    async def set_white_balance(self, kelvin=None):
        cur = self.props.get("cam_white_balance")
        m = pm.msg("CamWhiteBalanceProperty")(metering_mode=MM_AUTO if kelvin is None else MM_MANUAL,
                                              white_balance_kelvin=int(kelvin or (cur.white_balance_kelvin
                                                                                  if cur else 5600)))
        self._store("cam_white_balance", m)

    async def set_tint(self, tint=None):
        self._store("cam_tint", pm.msg("CamTintProperty")(metering_mode=MM_AUTO if tint is None else MM_MANUAL,
                                                          tint=int(tint or 0)))

    async def set_exposure_comp(self, stops):
        self._store("cam_exposure_comp", pm.msg("CamExposureCompProperty")(metering_mode=MM_AUTO,
                                                                           exposure_comp_stops=float(stops)))

    async def set_focus(self, value=None):
        m = pm.msg("CamFocusProperty")(is_auto=value is None)
        if value is not None:
            m.focus_value = float(value)
        self._store("cam_focus", m)

    async def set_zoom(self, factor):
        self._store("cam_zoom", pm.msg("CamZoomProperty")(zoom_factor=float(factor)))

    async def set_lens(self, camera_id, is_auto=False):
        self._store("active_cam", pm.msg("ActiveCamProperty")(camera_id=camera_id, is_auto=is_auto))

    async def set_stabilization(self, level):
        self._store("cam_stabilization", pm.msg("CamStabilizationProperty")(level=int(level)))

    async def set_torch(self, on, value=None):
        m = pm.msg("CamLightProperty")(is_enabled=bool(on))
        if value is not None:
            m.light_value = int(value)
        self._store("cam_light", m)

    async def set_lut(self, name, display=True, record=False):
        self._store("lut", pm.msg("LutProperty")(selected_lut=name, is_display_lut=display, is_record_lut=record))

    async def set_frame_rate(self, fps):
        num, den = (int(fps[0]), int(fps[1])) if isinstance(fps, (tuple, list)) else (int(fps), 1)
        m = pm.msg("CamFramerateProperty")()
        m.CopyFrom(self.props["cam_framerate"])
        m.playback_frame_rate.num, m.playback_frame_rate.den = num, max(den, 1)
        self._store("cam_framerate", m)

    async def tap_focus(self, x, y, kind=None):
        await self.set_focus(None)

    async def set_slate(self, **fields):
        m = pm.msg("SlateProperty")()
        m.CopyFrom(self.props["slate"])
        for k, v in fields.items():
            setattr(m, k, v)
        self._store("slate", m)

    async def start_recording(self, dim_screen=False):
        self._rec_t0 = time.monotonic()
        m = pm.msg("RecordingStateProperty")(is_recording=True, is_writing_to_storage=True)
        self._store("recording_state", m)
        self._tick_props()
        return "START_RECORDING_STATUS_OK"

    async def stop_recording(self):
        self._rec_t0 = None
        self._store("recording_state", pm.msg("RecordingStateProperty")(is_recording=False))
        self._tick_props()
        return "STOP_RECORDING_STATUS_OK"

    async def pause_recording(self):
        return "PAUSE_RECORDING_STATUS_OK"

    async def identify(self):
        return None

    async def get_settings(self):
        raise RuntimeError("the camera settings menu needs a real phone")

    async def write_settings(self, settings, done=True):
        return [], ""
