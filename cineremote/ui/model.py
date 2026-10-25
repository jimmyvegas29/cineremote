"""Camera parameter model shared by the Studio and Camera views.

Each Param turns the camera's properties (CamHandle.props) into what a tile/readout shows - text, a
context line, the auto state, and either a list of choices or a numeric range for the scrub ruler - and
knows which session command sets it. Views only paint; they never interpret protobuf themselves.
"""
import time
from dataclasses import dataclass, field
from types import SimpleNamespace

from PySide6.QtCore import QObject, QTimer

STD_FPS = [(24000, 1001), (24, 1), (25, 1), (30000, 1001), (30, 1), (48, 1), (50, 1), (60000, 1001), (60, 1),
           (100, 1), (120, 1), (240, 1)]
SHUTTER_DENS = [24, 25, 30, 48, 50, 60, 96, 100, 120, 125, 160, 180, 200, 240, 250, 320, 360, 400, 480, 500, 640,
                800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000]
ISO_STOPS = [25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000,
             2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800]
STAB_NAMES = {1: "Off", 2: "Standard", 3: "Cinematic", 4: "Extreme", 5: "Optical"}
LENS_KIND = {1: "Ultra wide", 2: "Wide", 3: "Tele", 4: "Front", 5: "Ultra wide", 6: "Tele", 7: "Front wide"}
WB_PRESETS = [("Daylight", 5600), ("Cloudy", 6500), ("Tungsten", 3200), ("Fluorescent", 4500)]


def _auto(m):
    return getattr(m, "metering_mode", 0) == 1


def _opt(msg, name):
    """Value of an optional proto field, or None when the phone left it unset."""
    try:
        return getattr(msg, name) if msg.HasField(name) else None
    except ValueError:          # not an optional field: always present
        return getattr(msg, name)


def _fps_label(num, den):
    return str(num // den) if num % den == 0 else f"{num / den:.3f}".rstrip("0").rstrip(".")


@dataclass
class State:
    text: str = "—"
    sub: str = ""
    auto: object = None            # None: no auto mode; else bool
    value: object = None           # current numeric value / choice key
    choices: list = field(default_factory=list)   # [(value, label)] for discrete params
    lo: float = 0.0
    hi: float = 1.0
    step: float = 1.0
    enabled: bool = True

    @property
    def discrete(self):
        return bool(self.choices)


class Param:
    key = ""
    label = ""
    has_auto = False
    method = ""

    def state(self, h) -> State:
        raise NotImplementedError

    def fmt(self, v):
        return str(v)

    def send_value(self, ctl, v):
        ctl.send(self.method, v)

    def send_auto(self, ctl, on, st):
        ctl.send(self.method, None if on else st.value)


class Lens(Param):
    key, label = "lens", "Lens"

    @staticmethod
    def _label(c):
        if c.id == "LensAuto":
            return "Auto"
        if c.lens.HasField("optical_zoom_factor") and c.lens.optical_zoom_factor:
            z = c.lens.optical_zoom_factor
            return f"{z:g}x" if c.lens.type not in (4, 7) else f"Front {z:g}x"
        return f"{c.lens.focal_length_mm}mm" if c.lens.focal_length_mm else c.id

    def state(self, h):
        cams, ac = h.props.get("available_cams"), h.props.get("active_cam")
        st = State()
        if cams:
            for c in cams.cameras:
                if not (c.is_disabled or c.lens.is_unavailable):
                    st.choices.append((c.id, self._label(c)))
            # widest to longest, then the front cameras, then Auto (the phone's own order is arbitrary)
            kind = {c.id: (c.id == "LensAuto", c.lens.type in (4, 7), c.lens.optical_zoom_factor or 99)
                    for c in cams.cameras}
            st.choices.sort(key=lambda ch: kind[ch[0]])
        if ac:
            cur = "LensAuto" if ac.is_auto else ac.camera_id
            st.value = cur
            for c in (cams.cameras if cams else []):
                if c.id == ac.camera_id:
                    st.text = self._label(c)
                    st.sub = "Auto" if ac.is_auto else LENS_KIND.get(c.lens.type, "")
        return st

    def send_value(self, ctl, v):
        if v == "LensAuto":
            ac = ctl.h.props.get("active_cam") if ctl.h else None
            ctl.send("set_lens", ac.camera_id if ac else "Lens25mm", True)
        else:
            ctl.send("set_lens", v, False)


class Fps(Param):
    key, label, method = "fps", "FPS", "set_frame_rate"

    def state(self, h):
        # choices are (num, den) so 29.97 / 23.976 stay exact
        st = State()
        rng, fr = h.props.get("cam_frame_rate_range"), h.props.get("cam_framerate")
        vals = sorted({(f.num, max(f.den, 1)) for f in rng.frame_rate_values if f.num}, key=lambda t: t[0] / t[1]) \
            if rng else []
        if not vals and rng and rng.frame_rate_max.num:
            # iOS sends only min/max (e.g. 4-60): offer the standard rates inside that range
            lo = rng.frame_rate_min.num / max(rng.frame_rate_min.den, 1)
            hi = rng.frame_rate_max.num / max(rng.frame_rate_max.den, 1)
            vals = [v for v in STD_FPS if lo <= v[0] / v[1] <= hi + 1e-6]
        st.choices =[(v, _fps_label(*v)) for v in (vals or [(24, 1), (25, 1), (30, 1), (60, 1)])]
        if fr and fr.playback_frame_rate.num:
            f = fr.playback_frame_rate
            st.value = (f.num, max(f.den, 1))
            st.text = _fps_label(*st.value)
            if st.value not in dict(st.choices):
                st.choices = sorted(st.choices + [(st.value, st.text)], key=lambda c: c[0][0] / c[0][1])
        return st


class Shutter(Param):
    key, label, has_auto, method = "shutter", "Shutter", True, "set_shutter"

    def state(self, h):
        st = State()
        v, rng = h.props.get("cam_shutter"), h.props.get("cam_shutter_range")
        dens = SHUTTER_DENS
        if rng and rng.shutter_min.den and rng.shutter_max.den:
            fast, slow = rng.shutter_max.den / max(rng.shutter_max.num, 1), rng.shutter_min.den / max(rng.shutter_min.num, 1)
            lo, hi = min(fast, slow), max(fast, slow)
            dens = [d for d in SHUTTER_DENS if lo <= d <= hi] or SHUTTER_DENS
        if v:
            st.auto = _auto(v)
            if v.HasField("shutter_speed") and v.shutter_speed.den:
                st.value = round(v.shutter_speed.den / max(v.shutter_speed.num, 1))
                st.text = f"1/{st.value}"
            elif v.HasField("shutter_angle"):
                st.text = f"{v.shutter_angle:.0f}°"
        else:
            st.auto = False
        if st.value and st.value not in dens:
            dens = sorted(set(dens) | {st.value})
        st.choices = [(d, f"1/{d}") for d in dens]
        fps = Fps().state(h).value
        if fps:
            st.sub = "180° = 1/%d" % round(2 * fps[0] / fps[1])
        return st

    def send_auto(self, ctl, on, st):
        ctl.send("set_shutter", None if on else (st.value or 50))


class Iso(Param):
    key, label, has_auto, method = "iso", "ISO", True, "set_iso"

    def state(self, h):
        st = State()
        v, rng = h.props.get("cam_sensibility"), h.props.get("cam_sensibility_range")
        stops = [s for s in ISO_STOPS if not rng or rng.sensibility_iso_min <= s <= rng.sensibility_iso_max] or ISO_STOPS
        if v:
            st.auto = _auto(v)
            st.value = _opt(v, "sensibility_iso")
            st.text = str(st.value) if st.value else "—"
            st.sub = "auto" if st.auto else "manual"
        else:
            st.auto = False
        if st.value and st.value not in stops:
            stops = sorted(set(stops) | {st.value})
        st.choices = [(s, str(s)) for s in stops]
        return st

    def send_auto(self, ctl, on, st):
        ctl.send("set_iso", None if on else (st.value or 400))


class WhiteBalance(Param):
    key, label, has_auto, method = "wb", "WB", True, "set_white_balance"

    def state(self, h):
        st = State(step=50)
        v, rng = h.props.get("cam_white_balance"), h.props.get("cam_white_balance_range")
        st.lo, st.hi = (rng.white_balance_kelvin_min, rng.white_balance_kelvin_max) if rng else (2500, 10000)
        if v:
            st.auto = _auto(v)
            st.value = _opt(v, "white_balance_kelvin") or None
            st.text = f"{st.value}K" if st.value else "—"
            st.sub = "auto" if st.auto else next((n for n, k in WB_PRESETS if k == st.value), "manual")
        else:
            st.auto = False
        return st

    def fmt(self, v):
        return f"{int(v)}K"

    def send_auto(self, ctl, on, st):
        ctl.send("set_white_balance", None if on else int(st.value or 5600))

    def send_value(self, ctl, v):
        ctl.send("set_white_balance", int(round(v / 50) * 50))


class Tint(Param):
    key, label, method = "tint", "Tint", "set_tint"

    def state(self, h):
        st = State(step=1)
        v, rng = h.props.get("cam_tint"), h.props.get("cam_tint_range")
        st.lo, st.hi = (rng.tint_min, rng.tint_max) if rng else (-50, 50)
        if v:
            st.value = _opt(v, "tint")
            st.text = self.fmt(st.value) if st.value is not None else "—"
        return st

    def fmt(self, v):
        v = int(round(v))
        return f"{v:+d}" if v else "0"

    def send_value(self, ctl, v):
        ctl.send("set_tint", int(round(v)))


class Ev(Param):
    key, label, method = "ev", "EV", "set_exposure_comp"

    def state(self, h):
        st = State(step=0.1)
        v, rng = h.props.get("cam_exposure_comp"), h.props.get("cam_exposure_comp_range")
        st.lo, st.hi = (rng.exposure_comp_stops_min, rng.exposure_comp_stops_max) if rng else (-2.0, 2.0)
        if v and _opt(v, "exposure_comp_stops") is not None:
            st.value = round(v.exposure_comp_stops, 1)
            st.text = self.fmt(st.value)
        return st

    def fmt(self, v):
        return "±0.0" if abs(v) < 0.05 else f"{v:+.1f}"

    def send_value(self, ctl, v):
        ctl.send("set_exposure_comp", round(float(v), 1))


class Focus(Param):
    key, label, has_auto, method = "focus", "Focus", True, "set_focus"

    def state(self, h):
        st = State()
        v, rng = h.props.get("cam_focus"), h.props.get("cam_focus_range")
        st.lo, st.hi = (rng.focus_min, rng.focus_max or 1.0) if rng else (0.0, 1.0)
        st.step = (st.hi - st.lo) / 200 or 0.005
        if v:
            st.auto = v.is_auto
            has = v.HasField("focus_value")
            st.value = v.focus_value if has else st.lo
            if v.is_auto:
                st.text, st.sub = "AF", "tap the picture"
            elif has:
                st.text, st.sub = f"{st.value:.2f}", "manual"
            else:   # a tap-to-focus locks focus without reporting a position
                st.text, st.sub = "AF-L", "locked by tap"
        else:
            st.auto = False
        return st

    def fmt(self, v):
        return f"{v:.2f}"

    def send_auto(self, ctl, on, st):
        ctl.send("set_focus", None if on else float(st.value if st.value is not None else st.lo))

    def send_value(self, ctl, v):
        ctl.send("set_focus", float(v))


class Zoom(Param):
    key, label, method = "zoom", "Zoom", "set_zoom"

    def state(self, h):
        st = State(step=0.1, lo=1.0, hi=10.0)
        v, ac, cams = h.props.get("cam_zoom"), h.props.get("active_cam"), h.props.get("available_cams")
        if ac and cams:
            for c in cams.cameras:
                if c.id == ("LensAuto" if ac.is_auto else ac.camera_id) and c.lens.zoom_max > c.lens.zoom_min:
                    st.lo, st.hi = c.lens.zoom_min, c.lens.zoom_max
        if v and _opt(v, "zoom_factor") is not None:
            st.value = v.zoom_factor
            st.text = self.fmt(v.zoom_factor)
            st.enabled = not v.is_disabled
        return st

    def fmt(self, v):
        return f"{v:.1f}x"

    def send_value(self, ctl, v):
        ctl.send("set_zoom", round(float(v), 2))


PARAMS = [Lens(), Fps(), Shutter(), Iso(), WhiteBalance(), Tint(), Ev(), Focus(), Zoom()]
BY_KEY = {p.key: p for p in PARAMS}


# ---------------------------------------------------------------------------- non-parameter readouts
def recording(h):
    """Only a live session can be recording (props survive a disconnect)."""
    rs = h.props.get("recording_state") if h and h.session else None
    return bool(rs and rs.is_recording)


def _playback_fps(h):
    fr = h.props.get("cam_framerate") if h else None
    if fr and fr.playback_frame_rate.num:
        return fr.playback_frame_rate.num / max(fr.playback_frame_rate.den, 1)
    return 30.0


def _fmt_tc(seconds, fps):
    s = max(0, int(seconds))
    f = min(int((seconds - int(seconds)) * fps), max(int(round(fps)) - 1, 0)) if seconds > 0 else 0
    return f"{s // 3600:02d}:{s // 60 % 60:02d}:{s % 60:02d}:{f:02d}"


def recording_elapsed(h):
    """Seconds into the current recording. The phones don't send a running count: while recording,
    system_timecode is the wall-clock START (Unix seconds + frames) and the controller counts from it,
    as Blackmagic's own controller does. Without a usable start (missing, or the phone's clock disagrees
    with ours by more than a day) count from when we saw recording begin (CamHandle.rec_started)."""
    rs = h.props.get("recording_state") if h else None
    if rs is not None and rs.system_timecode.seconds:
        el = time.time() - (rs.system_timecode.seconds + rs.system_timecode.frame_count / _playback_fps(h))
        if -2 <= el < 86400:
            return max(0.0, el)
    t0 = getattr(h, "rec_started", None)
    return time.monotonic() - t0 if t0 else 0.0


def timecode(h):
    """Recording: elapsed time of the take. Idle: length of the last clip (recorded_duration), else zeros.
    (Never the raw system_timecode - on iOS that is a Unix timestamp, i.e. ~498000 hours.)"""
    rs = h.props.get("recording_state") if h else None
    if rs is None:
        return "00:00:00:00"
    fps = _playback_fps(h)
    if recording(h):
        return _fmt_tc(recording_elapsed(h), fps)
    if rs.HasField("recorded_duration"):
        d = rs.recorded_duration
        return _fmt_tc(d.seconds + d.frame_count / fps, fps)
    return "00:00:00:00"


def battery_pct(h):
    b = h.props.get("battery") if h else None
    return None if b is None else b.available_percentage * 100


def media(h):
    """(runtime text, free GB) or (None, None)."""
    st = h.props.get("active_storage") if h else None
    if st is None:
        return None, None
    sd = st.storage_device
    s = sd.runtime_secs
    return f"{s // 3600}:{s // 60 % 60:02d}:{s % 60:02d}", sd.free_size_bytes / 1e9


def slate_letter(h):
    """The phone's slate/camera letter ("A") from its name "Model (A)"."""
    if h and h.name.endswith(")") and "(" in h.name:
        tag = h.name.rsplit("(", 1)[1][:-1]
        if 0 < len(tag) <= 2:
            return tag
    return "•"


def short_name(h):
    return h.name.rsplit(" (", 1)[0] if h else ""


def video_format(h):
    sess = h.session if h else None
    if sess is None or not sess.stats.get("width"):
        return ""
    st = sess.stats
    return f"{st['width']}×{st['height']} · {st['fps']:.0f} fps · H.265"


# ---------------------------------------------------------------------------- controller
class Controller(QObject):
    """Sends commands for the selected camera. Continuous drags are throttled to one command per
    method every 120 ms (the newest value wins) so the phone isn't flooded."""

    def __init__(self, backend, parent=None):
        super().__init__(parent)
        self.backend = backend
        self.key = None
        self._pending = {}
        self._timer = QTimer(self)
        self._timer.setInterval(120)
        self._timer.timeout.connect(self._flush)
        self._timer.start()

    @property
    def h(self):
        return self.backend.cams.get(self.key) if self.key else None

    def live(self):
        h = self.h
        return bool(h and h.session and h.state in ("connected", "previewing"))

    def send(self, method, *args, **kw):
        if not self.key:
            return None
        self._pending.pop(method, None)      # an explicit value supersedes a queued drag value
        return self.backend.call(self.key, method, *args, **kw)

    def send_throttled(self, method, *args):
        self._pending[method] = args

    def _flush(self):
        if self._pending and self.key:
            pending, self._pending = self._pending, {}
            for m, args in pending.items():
                self.backend.call(self.key, m, *args)

    def set_param(self, p, value, throttled=False):
        if throttled:   # reuse Param.send_value's argument shaping, but queue instead of sending
            p.send_value(SimpleNamespace(h=self.h, send=self.send_throttled), value)
        else:
            p.send_value(self, value)

    def set_auto(self, p, on):
        h = self.h
        if h:
            p.send_auto(self, on, p.state(h))
