"""Qt-facing backend: owns the asyncio loop thread, mDNS discovery, camera sessions, OBS publishers."""
import asyncio
import logging
import math
import threading
import time
import uuid

import numpy as np
from PySide6.QtCore import QObject, Signal

from . import diag
from . import vcam as vcam_mod
from .obs_shm import ShmPublisher
from .session import CameraSession, SessionError

log = logging.getLogger("cineremote.backend")
SERVICE = "_bmd-cam-control._tcp.local."


class CamHandle:
    def __init__(self, key, host, port, name):
        self.key, self.host, self.port, self.name = key, host, port, name
        self.session = None
        self.task = None              # the one connect loop for this camera (concurrent Future)
        self.publisher = None
        self.password = None
        self.want_connected = False
        self.obs_enabled = True
        self.vcam_enabled = True
        self.state = "idle"
        self.detail = ""
        self.lock = threading.Lock()
        self.latest = None            # latest av.VideoFrame
        self.latest_seq = 0
        self.audio_db = [-90.0, -90.0]   # RMS per channel after gain (fast attack, slow release)
        self.audio_peak_db = [-90.0, -90.0]
        self.audio_gain_db = 0.0          # PC-side level sent to OBS / the listen tap
        self.audio_mute = False
        self.resampler = None
        self.discovered = False
        self.instances = set()        # live mDNS instance names that map to this camera
        self.props = {}
        self.demo = None              # (photo, property overrides) for a --demo camera
        self.armed = False            # record-armed: "Record armed" rolls only these cameras
        self.rec_started = None       # monotonic time we saw recording begin (fallback timecode clock)


def _camera_key(service_name):
    """Stable camera id from an mDNS instance name. The phone registers under its participant UUID;
    when the app restarts before the old record expires, Android NSD renames it "<uuid> (2)",
    "<uuid> (3)", ... The Blackmagic app strips that suffix too, so all of them are one camera."""
    inst = service_name.split("." + SERVICE.split(".")[0])[0] if "." in service_name else service_name
    return inst.split(" (")[0].strip().lower()


class Backend(QObject):
    cameraListChanged = Signal()
    sessionState = Signal(str, str, str)          # key, state, detail
    propertyChanged = Signal(str, str, object)    # key, field, value
    eventReceived = Signal(str, str, object)      # key, kind, msg
    error = Signal(str, str)                      # key, message
    frameReady = Signal(str)                      # key: a new decoded frame is in CamHandle.latest

    def __init__(self, controller_name="Windows Controller"):
        super().__init__()
        self.controller_name = controller_name
        self.controller_id = uuid.uuid5(uuid.NAMESPACE_DNS, "cineremote-remote-" + controller_name)
        self.cams = {}                # key -> CamHandle
        self.loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._run_loop, name="cineremote-asyncio", daemon=True)
        self._thread.start()
        self._zc = None
        self.password_provider = None  # callable(handle) -> password or None (called on GUI side)
        self.vcam = None              # VirtualCameraManager once started
        self.vcam_error = None if vcam_mod.is_installed() else "virtual camera not installed"
        self.audio_tap = None         # callable(int16 interleaved stereo ndarray) for "listen on PC"
        self.audio_tap_key = None

    # ------------------------------------------------------------------ loop plumbing
    def _run_loop(self):
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    def submit(self, coro):
        return asyncio.run_coroutine_threadsafe(coro, self.loop)

    def shutdown(self):
        """Clean teardown of every camera (DisconnectEvent -> data channel -> peer connection)."""
        if getattr(self, "_shut", False):
            return
        self._shut = True
        handles = list(self.cams.values())
        for h in handles:
            h.want_connected = False
            if h.task is not None:
                h.task.cancel()

        async def _all():
            await asyncio.gather(*(self._disconnect(h, user=True) for h in handles), return_exceptions=True)
        try:
            self.submit(_all()).result(timeout=10)
        except Exception:
            log.warning("shutdown: some cameras did not close cleanly")
        if self.vcam is not None:
            try:
                self.vcam.close()
            except Exception:
                pass
        if self._zc is not None:
            try:
                self._zc.close()
            except Exception:
                pass
        self.loop.call_soon_threadsafe(self.loop.stop)

    # ------------------------------------------------------------------ discovery
    def start_discovery(self):
        from zeroconf import ServiceBrowser, ServiceListener, Zeroconf

        backend = self

        class _L(ServiceListener):
            def add_service(self, zc, t, name):
                info = zc.get_service_info(t, name, 3000)
                if not info:
                    return
                addrs = [a for a in info.parsed_addresses() if ":" not in a] or info.parsed_addresses()
                props = {k.decode(): (v.decode() if v else "") for k, v in (info.properties or {}).items()}
                backend._on_discovered(_camera_key(name), name, addrs[0], info.port, props)

            def update_service(self, zc, t, name):
                self.add_service(zc, t, name)

            def remove_service(self, zc, t, name):
                backend._on_lost(_camera_key(name), name)

        self._zc = Zeroconf()
        self._browser = ServiceBrowser(self._zc, SERVICE, _L())

    def _on_discovered(self, key, instance, host, port, props):
        name = props.get("name") or props.get("model_name") or host
        slate = props.get("slate_name")
        label = f"{name} ({slate})" if slate and slate != name else name
        h = self.cams.get(key)
        if h is None:
            h = self.cams[key] = CamHandle(key, host, port, label)
        else:
            h.host, h.port, h.name = host, port, label
        h.instances.add(instance)
        h.discovered = True
        self.cameraListChanged.emit()
        if h.want_connected and (h.task is None or h.task.done()):
            self.connect_camera(key)

    def _on_lost(self, key, instance):
        h = self.cams.get(key)
        if h:
            h.instances.discard(instance)
            h.discovered = bool(h.instances)  # an older "(2)" record expiring doesn't mean offline
            self.cameraListChanged.emit()

    def start_demo(self):
        """Simulated phones fed from still photos (see demo.py); most of them connect right away."""
        from . import demo
        for i, (name, photo, connected, overrides) in enumerate(demo.CAMERAS):
            key = f"demo-{i}"
            h = self.cams[key] = CamHandle(key, f"demo{i}", 0, name)
            h.demo = (demo.photo_path(photo), overrides)
            h.password = "demo"
            h.discovered = True
            h.vcam_enabled = False    # don't register system webcams for pretend cameras
            h.armed = bool(overrides.get("_armed"))
            if connected:
                self.connect_camera(key)
        self.cameraListChanged.emit()

    def add_manual(self, host, port=2222, name=None):
        key = f"manual-{host}:{port}"
        if key not in self.cams:
            self.cams[key] = CamHandle(key, host, port, name or host)
        self.cameraListChanged.emit()
        return key

    def remove(self, key):
        h = self.cams.get(key)
        if h:
            self.disconnect_camera(key)
            if not h.discovered:
                self.cams.pop(key, None)
            self.cameraListChanged.emit()

    # ------------------------------------------------------------------ sessions
    def connect_camera(self, key, password=None):
        h = self.cams[key]
        if password is not None:
            h.password = password
        if h.password is None:
            self.error.emit(key, "password required")
            return
        h.want_connected = True
        if h.task is None or h.task.done():   # exactly one connect loop per camera
            h.task = self.submit(self._connect(h))

    def disconnect_camera(self, key):
        h = self.cams.get(key)
        if h:
            h.want_connected = False
            if h.task is not None:
                h.task.cancel()               # stops a connect that is still retrying
            self.submit(self._disconnect(h, user=True))

    async def _connect(self, h):
        try:
            await self._connect_loop(h)
        except asyncio.CancelledError:
            pass
        finally:
            s = h.session
            if s is not None:
                await asyncio.shield(s.close())
                h.session = None

    async def _connect_loop(self, h):
        backoff = 2
        while h.want_connected:
            if h.demo is not None:
                from .demo import DemoSession
                s = DemoSession(h.name, *h.demo)
            else:
                s = CameraSession(h.host, h.port, h.password, self.controller_name, self.controller_id)
            s.on_state = lambda st, d, h=h: self._state(h, st, d)
            s.on_property = lambda f, v, h=h: self._prop(h, f, v)
            s.on_event = lambda k, m, h=h: self.eventReceived.emit(h.key, k, m)
            s.on_video = lambda fr, h=h: self._video(h, fr)
            s.on_audio = lambda fr, h=h: self._audio(h, fr)
            h.session = s
            started = time.monotonic()
            try:
                await s.connect()
                if not h.want_connected:
                    break
                if h.obs_enabled and h.publisher is None:
                    try:
                        h.publisher = ShmPublisher(h.name, h.key)
                    except Exception as e:
                        self.error.emit(h.key, f"OBS output unavailable: {e}")
                ok = await s.start_preview()
                if not ok and h.want_connected:
                    log.warning("%s: no video after StartPreview, asking again", h.name)
                    ok = await s.start_preview()
                if not ok and h.want_connected:
                    # e.g. the phone was still streaming to a previous controller: a connected session
                    # without video is useless, so reconnect instead of sitting on a black monitor
                    self.error.emit(h.key, "camera did not start the video feed - reconnecting")
                    raise SessionError("no video from camera")
                if not h.want_connected:      # user disconnected while the preview was starting
                    break
                if h.vcam_enabled:
                    await self._attach_vcam(h)
                await self._watch(h, s)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                msg = str(e) or e.__class__.__name__
                if "wrong password" in msg:
                    h.want_connected = False
                    h.password = None
                self.error.emit(h.key, msg)
            try:
                await s.close()
            except Exception:
                pass
            h.session = None
            with h.lock:
                h.latest = None               # never show a frozen frame as if it were live
            if h.want_connected:
                if time.monotonic() - started > 30:
                    backoff = 2               # the last session was healthy: retry quickly
                self._state(h, "reconnecting", f"retrying in {backoff}s")
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 15)

    async def _watch(self, h, s):
        while s.state not in ("disconnected", "error"):
            await asyncio.sleep(0.5)

    async def _disconnect(self, h, user=False):
        s = h.session
        if s is not None:
            await s.close()
        h.session = None
        await self._detach_vcam(h)
        if h.publisher is not None:
            h.publisher.close()
            h.publisher = None
        with h.lock:
            h.latest = None

    def _state(self, h, st, detail):
        h.state, h.detail = st, detail
        self.sessionState.emit(h.key, st, detail)

    def _prop(self, h, field, value):
        if field == "recording_state":
            if value.is_recording and h.rec_started is None:
                h.rec_started = time.monotonic()
            elif not value.is_recording:
                h.rec_started = None
        h.props[field] = value
        self.propertyChanged.emit(h.key, field, value)

    def _video(self, h, frame):
        pts = time.perf_counter_ns()
        if diag.enabled():
            diag.frame(h.name, frame)
        with h.lock:
            h.latest = frame
            h.latest_seq += 1
        self.frameReady.emit(h.key)    # queued to the GUI thread: views repaint now instead of polling
        p = h.publisher
        if p is not None and h.obs_enabled:
            try:
                p.push_video(frame, pts)
            except Exception:
                log.exception("obs push_video")
        if self.vcam is not None and h.vcam_enabled:
            self.vcam.push(h.key, frame)

    def _audio(self, h, frame):
        """Phone audio -> s16 stereo 48k -> PC-side gain/mute -> meters, OBS, listen tap.
        (The phone's own mic gain isn't remotely settable; this is the level we pass on.)"""
        try:
            if frame.format.name == "s16" and frame.layout.name == "stereo" and frame.sample_rate == 48000:
                frames = [frame]
            else:
                if h.resampler is None:
                    import av
                    h.resampler = av.AudioResampler(format="s16", layout="stereo", rate=48000)
                frames = h.resampler.resample(frame)
        except Exception:
            log.exception("audio resample")
            return
        p = h.publisher
        tap = self.audio_tap if self.audio_tap_key == h.key else None
        for fr in frames:
            pcm = fr.to_ndarray().reshape(-1)
            g = 0.0 if (h.audio_mute or h.audio_gain_db <= -60) else 10 ** (h.audio_gain_db / 20)
            if g != 1.0:
                pcm = np.clip(pcm.astype(np.float32) * g, -32768, 32767).astype(np.int16)
            if pcm.size >= 2:
                st = pcm.reshape(-1, 2).astype(np.float32) / 32768.0
                rms = np.sqrt(np.mean(st * st, axis=0))
                pk = np.max(np.abs(st), axis=0)
                for c in range(2):
                    h.audio_db[c] = max(20 * math.log10(max(float(rms[c]), 1e-5)), h.audio_db[c] - 1.5)
                    h.audio_peak_db[c] = 20 * math.log10(max(float(pk[c]), 1e-5))
            if p is not None and h.obs_enabled:
                try:
                    p.push_pcm(pcm)
                except Exception:
                    log.exception("obs push_pcm")
            if tap is not None:
                try:
                    tap(pcm)
                except Exception:
                    log.exception("audio tap")

    def set_obs_enabled(self, key, on):
        h = self.cams[key]
        h.obs_enabled = on

        async def _apply():
            if on and h.publisher is None and h.session is not None:
                h.publisher = ShmPublisher(h.name, h.key)
            elif not on and h.publisher is not None:
                h.publisher.close()
                h.publisher = None
        self.submit(_apply())

    # ------------------------------------------------------------------ virtual webcam
    def vcam_available(self):
        return vcam_mod.is_installed()

    # Host process I/O and writer-thread joins block, so they run in the default executor rather
    # than on the event loop (which carries heartbeats and RTP for every camera).
    def _attach_vcam_sync(self, h):
        if not vcam_mod.is_installed():
            self.vcam_error = "virtual camera not installed"
            return
        try:
            if self.vcam is None:
                self.vcam = vcam_mod.VirtualCameraManager()
                self.vcam_error = None
            slot = self.vcam.attach(h.key, h.name)
            self.eventReceived.emit(h.key, "vcam", f"webcam '{h.name}' (slot {slot + 1})")
        except Exception as e:
            self.vcam_error = str(e)
            self.error.emit(h.key, f"virtual webcam: {e}")

    def _detach_vcam_sync(self, h, unregister=False):
        try:
            if self.vcam is None and unregister and vcam_mod.is_installed():
                self.vcam = vcam_mod.VirtualCameraManager()   # webcam left registered by an earlier run
            if self.vcam is not None:
                self.vcam.detach(h.key, unregister)
        except Exception:
            log.exception("vcam detach")

    async def _attach_vcam(self, h):
        await asyncio.get_running_loop().run_in_executor(None, self._attach_vcam_sync, h)

    async def _detach_vcam(self, h, unregister=False):
        await asyncio.get_running_loop().run_in_executor(None, self._detach_vcam_sync, h, unregister)

    def set_vcam_enabled(self, key, on):
        h = self.cams[key]
        h.vcam_enabled = on

        async def _apply():
            if on and h.session is not None:
                await self._attach_vcam(h)
            elif not on:
                await self._detach_vcam(h, unregister=True)   # user turned it off: remove the device
        self.submit(_apply())

    # ------------------------------------------------------------------ commands
    def call(self, key, method, *args, **kwargs):
        """Run CameraSession.<method>(*args) on the loop; returns concurrent Future."""
        h = self.cams.get(key)
        if not h or h.session is None:
            return None

        async def _run():
            try:
                return await getattr(h.session, method)(*args, **kwargs)
            except Exception as e:
                self.error.emit(key, f"{method}: {e}")
                raise
        return self.submit(_run())
