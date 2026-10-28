"""CameraSession: one controller connection to a Blackmagic Camera app running as "Remote Camera".

Lifecycle (all verified against Blackmagic Camera 3.5.0 on Android):
  1. WebSocket signaling + password auth (signaling.py)
  2. Camera sends the SDP offer (data channel "signaling-channel"); we only ever answer.
     The first offer is sometimes empty (no m-lines): answer with a minimal SDP and wait.
  3. On the data channel: HelloRequest/HelloResponse, then property reads / change events.
  4. Preview: StartPreviewRequest (frame_rate REQUIRED) -> camera re-offers over the data channel
     (webrtc_sdp_request) with sendonly H.265/H.264 + Opus -> we answer (webrtc_sdp_response).
  5. Disconnect cleanly: DisconnectEvent -> close data channel -> close peer connection.
     Skipping this wedges the camera's listener until the app restarts.
"""
import asyncio
import logging
import os
import time
import uuid as uuidlib

from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import MediaStreamError
from aiortc.sdp import candidate_from_sdp

from . import rtc_compat  # (must be imported before any RTCPeerConnection is created)
from . import proto_msgs as pm
from .signaling import AuthError, BusyError, SignalingClient, participant_info

log = logging.getLogger("cineremote.session")

_EMPTY_ANSWER = "v=0\r\no=- 1 0 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\na=group:BUNDLE\r\n"

MM_AUTO = pm.enum_value("MeteringMode", "METERING_MODE_AUTO")
MM_MANUAL = pm.enum_value("MeteringMode", "METERING_MODE_MANUAL")
MM_LOCKED = pm.enum_value("MeteringMode", "METERING_MODE_LOCKED")


class SessionError(Exception):
    pass


class CameraSession:
    """Async controller session. All callbacks are invoked on the asyncio loop thread.

    Callbacks (assign attributes):
      on_state(state:str, detail:str)          'connecting','connected','previewing','disconnected','error'
      on_property(name:str, value_msg)         name = Property oneof field (e.g. 'cam_sensibility')
      on_video(av.VideoFrame)                  decoded frames (yuv420p)
      on_audio(av.AudioFrame)                  decoded audio (s16 48k stereo)
      on_event(kind:str, msg)                  other subordinate messages (error_event, settings, ...)
    """

    def __init__(self, host, port, password, controller_name="Windows Controller", controller_id=None):
        self.host, self.port, self.password = host, port, password
        self.controller_name = controller_name
        self.controller_id = controller_id or uuidlib.uuid4()
        self.on_state = lambda s, d="": None
        self.on_property = lambda n, v: None
        self.on_video = lambda f: None
        self.on_audio = lambda f: None
        self.on_event = lambda k, m: None

        self.remote_info = {}
        self.hello = None            # HelloResponse
        self.props = {}              # field name -> sub-message
        self._rec_cmd = None         # "start" / "stop" while a record command waits for its answer
        self.state = "idle"
        self.preview_active = False
        self._sig = None
        self._pc = None
        self._dc = None
        self._msg_id = 0
        self._pending = {}           # response kind -> [Future] (FIFO)
        self._tasks = []
        self._dc_open = asyncio.Event()
        self._hello_evt = asyncio.Event()
        self._first_frame = asyncio.Event()
        self._closing = False
        self._close_task = None
        self._close_evt = asyncio.Event()
        self._media_lock = asyncio.Lock()
        self._last_rx = time.monotonic()
        self.stats = {"video_frames": 0, "audio_frames": 0, "width": 0, "height": 0, "fps": 0.0}
        self._fps_t0, self._fps_n = time.monotonic(), 0

    # ------------------------------------------------------------------ connect / close
    def _set_state(self, s, detail=""):
        self.state = s
        try:
            self.on_state(s, detail)
        except Exception:
            log.exception("on_state")

    async def connect(self, timeout=20, busy_retry_secs=40):
        self._set_state("connecting", f"{self.host}:{self.port}")
        deadline = time.monotonic() + busy_retry_secs
        while True:
            self._check_open()
            self._sig = SignalingClient(self.host, self.port, self.password,
                                        participant_info(self.controller_id, self.controller_name))
            try:
                await self._sig.connect()
                await self._sig.authenticate()
                break
            except (BusyError, ConnectionRefusedError, OSError) as e:
                await self._sig.close()
                if time.monotonic() > deadline:
                    raise SessionError(f"camera unavailable: {e}")
                self._check_open()
                self._set_state("connecting", f"waiting for camera ({e.__class__.__name__})")
                try:   # retry delay that ends immediately if close() is called
                    await asyncio.wait_for(self._close_evt.wait(), 3)
                except asyncio.TimeoutError:
                    pass
        self._check_open()
        self.remote_info = self._sig.remote_info

        self._pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        self._pc.on("datachannel", self._on_datachannel)
        self._pc.on("track", self._on_track)
        self._pc.on("connectionstatechange", self._on_pc_state)
        self._tasks.append(asyncio.ensure_future(self._signaling_pump()))

        if not await self._wait_or_close(self._dc_open, timeout):
            self._check_open()
            await self.close(clean=False)
            raise SessionError("data channel did not open (camera did not send a usable offer)")
        self._check_open()

        # Protocol identity: the phone expects a controller that presents itself as the camera app
        # (same protocol version) - this is interoperability metadata, not branding.
        app = pm.AppInfo(name="Blackmagic Camera", version="3.5.0",
                         platform=pm.enum_value("AppPlatform", "APP_PLATFORM_ANDROID"),
                         platform_version="Windows", device_model_name=self.controller_name, language_code="en")
        self._send(hello_request=pm.msg("HelloRequest")(app_info=app))
        if not await self._wait_or_close(self._hello_evt, 10):
            self._check_open()
            await self.close()
            raise SessionError("no HelloResponse from camera")
        self._check_open()
        self._tasks.append(asyncio.ensure_future(self._heartbeat()))
        await self.read_all_properties()
        self._check_open()
        self._set_state("connected", self.remote_info.get("device_name", ""))

    def _check_open(self):
        if self._closing:
            raise SessionError("session closed")

    async def _wait_or_close(self, event, timeout):
        """Wait for `event`, but give up at once if close() is called. True if the event fired."""
        waiters = {asyncio.ensure_future(event.wait()), asyncio.ensure_future(self._close_evt.wait())}
        try:
            await asyncio.wait(waiters, timeout=timeout, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for w in waiters:
                w.cancel()
        return event.is_set() and not self._closing

    async def close(self, clean=True):
        """Idempotent; every caller waits for the same teardown. The phone needs the data channel
        closed before the peer connection (and a DisconnectEvent first when we initiate)."""
        if self._close_task is None:
            self._closing = True
            self._close_evt.set()
            self._close_task = asyncio.ensure_future(self._do_close(clean))
        await asyncio.shield(self._close_task)

    async def _do_close(self, clean):
        try:
            if (self._dc is None or self._dc.readyState != "open") and self._sig is not None                     and self._sig.authorized and self._sig.ws is not None and self._sig.ws.state.name == "OPEN":
                # Torn down before the data channel came up: say bye on the signaling socket so the
                # phone resets its listener (a silent close here wedges it until the app restarts).
                try:
                    await asyncio.wait_for(self._sig.send("bye_message", {"reason": "app_suspended"}), 2)
                    await asyncio.sleep(0.3)
                except Exception:
                    log.debug("bye", exc_info=True)
            if self._dc is not None and self._dc.readyState == "open":
                try:
                    if clean:
                        self._send(disconnect_event=pm.msg("DisconnectEvent")())
                        await asyncio.sleep(0.4)
                    self._dc.close()
                    await asyncio.sleep(0.8)
                except Exception:
                    log.debug("data channel close", exc_info=True)
            current = asyncio.current_task()
            for t in self._tasks:
                if t is not current:
                    t.cancel()
            if self._pc is not None:
                try:
                    await asyncio.wait_for(self._pc.close(), 5)
                except Exception:
                    log.debug("pc close", exc_info=True)
            if self._sig is not None:
                try:
                    await asyncio.wait_for(self._sig.close(), 3)
                except Exception:
                    log.debug("ws close", exc_info=True)
        finally:
            for q in self._pending.values():
                for f in q:
                    if not f.done():
                        f.set_exception(SessionError("closed"))
            self._pending.clear()
            self.preview_active = False
            self._set_state("disconnected", "")

    # ------------------------------------------------------------------ signaling
    async def _signaling_pump(self):
        while True:
            t, d = await self._sig.queue.get()
            if t == "closed":
                return  # normal: camera closes the WS once the data channel is up
            try:
                if t == "sdp_offer":
                    sdp = d.get("value", "")
                    if "\nm=" not in sdp:
                        await self._sig.send("sdp_answer", {"value": _EMPTY_ANSWER})
                        continue
                    await self._pc.setRemoteDescription(RTCSessionDescription(sdp, "offer"))
                    ans = await self._pc.createAnswer()
                    await self._pc.setLocalDescription(ans)
                    await self._sig.send("sdp_answer", {"value": self._pc.localDescription.sdp})
                elif t == "ice_candidate":
                    v = d.get("value", "")
                    if v.startswith("candidate:"):
                        c = candidate_from_sdp(v.split(":", 1)[1])
                        c.sdpMid, c.sdpMLineIndex = None, 0
                        await self._pc.addIceCandidate(c)
                elif t == "bye_message":
                    log.info("camera said bye: %s", d)
            except Exception:
                log.exception("signaling message %s", t)

    def _on_pc_state(self):
        st = self._pc.connectionState
        log.info("peer connection %s", st)
        if st in ("failed", "closed") and not self._closing:
            asyncio.ensure_future(self.close(clean=False))

    # ------------------------------------------------------------------ data channel
    def _on_datachannel(self, dc):
        self._dc = dc

        @dc.on("open")
        def _open():
            self._dc_open.set()

        @dc.on("close")
        def _closed():
            if not self._closing:
                asyncio.ensure_future(self.close(clean=False))

        dc.on("message", self._on_dc_message)
        if dc.readyState == "open":
            self._dc_open.set()

    def _send(self, subordinate_message_id=None, **kw):
        if self._dc is None or self._dc.readyState != "open":
            raise SessionError("not connected")
        self._msg_id += 1
        m = pm.ControllerMessage(message_id=self._msg_id, **kw)
        if subordinate_message_id is not None:
            m.subordinate_message_id = subordinate_message_id
        self._dc.send(pm.ControllerMessages(messages=[m]).SerializeToString())
        return self._msg_id

    async def _request(self, timeout=5.0, **kw):
        """Send a request and await its response. The camera does not echo controller_message_id,
        so responses are matched FIFO by response type (xxx_request -> xxx_response)."""
        (req_field,) = kw.keys()
        resp_kind = req_field.replace("_request", "_response")
        fut = asyncio.get_event_loop().create_future()
        self._pending.setdefault(resp_kind, []).append(fut)
        self._send(**kw)
        try:
            return await asyncio.wait_for(fut, timeout)
        except asyncio.TimeoutError:
            log.info("no %s within %.1fs", resp_kind, timeout)
            return None
        finally:
            q = self._pending.get(resp_kind, [])
            if fut in q:
                q.remove(fut)

    def _on_dc_message(self, data):
        if isinstance(data, str):
            return
        self._last_rx = time.monotonic()
        ms = pm.SubordinateMessages()
        try:
            ms.ParseFromString(data)
        except Exception:
            log.warning("bad SubordinateMessages (%d bytes)", len(data))
            return
        for m in ms.messages:
            kind = m.WhichOneof("message")
            body = getattr(m, kind) if kind else None
            q = self._pending.get(kind)
            while q:
                fut = q.pop(0)
                if not fut.done():
                    fut.set_result(body)
                    break
            if kind == "heart_beat_request":
                self._send(subordinate_message_id=m.message_id, heart_beat_response=pm.msg("HeartBeatResponse")())
            elif kind == "hello_response":
                self.hello = body
                self._hello_evt.set()
            elif kind in ("property_changed_event", "read_property_response"):
                self._store_property(body.property)
            elif kind == "write_property_response":
                if body.HasField("current_value"):
                    self._store_property(body.current_value)
            elif kind == "webrtc_sdp_request":
                asyncio.ensure_future(self._answer_media_offer(m.message_id, body.data))
            elif kind == "disconnect_event":
                log.info("camera sent DisconnectEvent")
                asyncio.ensure_future(self.close(clean=False))  # still closes the data channel first
            if kind not in ("heart_beat_request", "heart_beat_response", "property_changed_event",
                            "read_property_response"):
                try:
                    self.on_event(kind, body)
                except Exception:
                    log.exception("on_event")

    def _store_property(self, prop):
        field = prop.WhichOneof("value")
        if not field:
            return
        val = getattr(prop, field)
        if field == "recording_state":
            old = self.props.get(field)
            now = (val.is_recording, val.is_writing_to_storage)
            if old is None or (old.is_recording, old.is_writing_to_storage) != now:
                log.info("record: %s: camera %s (writing to storage: %s)", self._who(),
                         "recording" if val.is_recording else "idle",
                         "yes" if val.is_writing_to_storage else "no")
        self.props[field] = val
        try:
            self.on_property(field, val)
        except Exception:
            log.exception("on_property")

    async def _heartbeat(self):
        while True:
            await asyncio.sleep(2.0)
            try:
                self._send(heart_beat_request=pm.msg("HeartBeatRequest")())
            except SessionError:
                return
            if time.monotonic() - self._last_rx > 12:
                log.warning("camera silent for 12s, closing")
                asyncio.ensure_future(self.close(clean=True))  # try the polite teardown anyway
                return

    # ------------------------------------------------------------------ media
    async def _answer_media_offer(self, sub_msg_id, sdp):
        async with self._media_lock:   # two re-offers in flight must not interleave
            try:
                await self._pc.setRemoteDescription(RTCSessionDescription(sdp, "offer"))
                ans = await self._pc.createAnswer()
                await self._pc.setLocalDescription(ans)
                rtx = rtc_compat.offers_retransmission(sdp)
                rtc_compat.set_hole_deadline(self._pc, rtc_compat.HOLE_DEADLINE_S if rtx
                                             else rtc_compat.REORDER_DEADLINE_S)
                log.info("media: %s", "NACK retransmission offered" if rtx else
                         "no retransmission offered: lost packets -> immediate keyframe request")
                answer = self._pc.localDescription.sdp
                if os.environ.get("CINEREMOTE_MONO_AUDIO") != "1":     # A/B testing switch
                    answer = rtc_compat.opus_stereo(answer)
                self._send(subordinate_message_id=sub_msg_id,
                           webrtc_sdp_response=pm.msg("WebRtcSdpResponse")(data=answer))
            except Exception:
                log.exception("media renegotiation failed")

    def _on_track(self, track):
        async def pump():
            while True:
                try:
                    fr = await track.recv()
                except MediaStreamError:
                    return
                if track.kind == "video":
                    self.stats["video_frames"] += 1
                    self.stats["width"], self.stats["height"] = fr.width, fr.height
                    self._fps_n += 1
                    now = time.monotonic()
                    if now - self._fps_t0 >= 1.0:
                        self.stats["fps"] = self._fps_n / (now - self._fps_t0)
                        self._fps_t0, self._fps_n = now, 0
                    self._first_frame.set()
                    self.on_video(fr)
                else:
                    self.stats["audio_frames"] += 1
                    self.on_audio(fr)
        self._tasks.append(asyncio.ensure_future(pump()))

    def preferred_codec(self):
        # H.265 Main first: our depayloader/decoder take any H.265 profile, whereas aiortc only
        # accepts H.264 Constrained Baseline and would reject a High/Main H.264 offer.
        codecs = list(self.hello.encoding_video_codecs) if self.hello else []
        for name in ("VIDEO_CODEC_H265_MAIN", "VIDEO_CODEC_H264"):
            v = pm.enum_value("VideoCodec", name)
            if v in codecs:
                return v
        return pm.enum_value("VideoCodec", "VIDEO_CODEC_H265_MAIN")

    async def start_preview(self, fps=None, audio_channels=2):
        # frame_rate is mandatory (omitting it crashes the phone app). Keep num/den exact so
        # 30000/1001 or 24000/1001 projects aren't truncated to 29/23.
        if fps is not None:
            num, den = int(fps), 1
        else:
            fr = self.props.get("cam_framerate")
            num, den = (fr.playback_frame_rate.num, fr.playback_frame_rate.den) if fr else (30, 1)
            if not num or not den:
                num, den = 30, 1
        req = pm.msg("StartPreviewRequest")(video_codec=self.preferred_codec(),
                                           frame_rate=pm.msg("FrameRate")(num=num, den=den),
                                           audio_num_channels_to_stream=audio_channels)
        # The camera never sends start_preview_response; success = it re-offers media and frames arrive.
        self._first_frame.clear()
        self._send(start_preview_request=req)
        try:
            await asyncio.wait_for(self._first_frame.wait(), 12)
            ok = True
        except asyncio.TimeoutError:
            ok = False
        self.preview_active = ok
        if ok:
            self._set_state("previewing", "")
        return ok

    async def stop_preview(self):
        try:
            await self._request(timeout=3, stop_preview_request=pm.msg("StopPreviewRequest")())
        finally:
            self.preview_active = False
            if self.state == "previewing":
                self._set_state("connected", "")

    # ------------------------------------------------------------------ properties
    async def read_all_properties(self, settle=2.0):
        for name, pid in pm.PROPERTY_IDS.items():
            if pid:
                self._send(read_property_request=pm.msg("ReadPropertyRequest")(property_id=pid))
                await asyncio.sleep(0.005)
        # Wait for the reply burst to settle so callers (and the GUI) start from a complete state.
        deadline = time.monotonic() + settle
        last = -1
        while time.monotonic() < deadline and len(self.props) != last:
            last = len(self.props)
            await asyncio.sleep(0.25)

    async def write(self, field, value_msg, override_confirm=False):
        """Write a Property. field = oneof name (e.g. 'cam_sensibility'). Returns WritePropertyResponse."""
        pid = next(n for n, f in pm.PROPERTY_FIELD.items() if f == field)
        prop = pm.Property(id=pid)
        getattr(prop, field).CopyFrom(value_msg)
        r = await self._request(write_property_request=pm.msg("WritePropertyRequest")(
            property=prop, override_confirm=override_confirm))
        if r is not None and not r.HasField("current_value"):
            # The iOS app applies the write but neither returns current_value nor sends a
            # PropertyChangedEvent for it (e.g. frame rate), so read it back for the real value.
            self._send(read_property_request=pm.msg("ReadPropertyRequest")(property_id=pid))
        return r

    # Convenience setters mirroring the official controller's encodings
    async def set_iso(self, iso=None):
        m = pm.msg("CamSensibilityProperty")(metering_mode=MM_AUTO if iso is None else MM_MANUAL)
        if iso is not None:
            m.sensibility_iso = int(iso)
        return await self.write("cam_sensibility", m)

    async def set_shutter(self, den=None):
        """den=None -> auto; else 1/den seconds."""
        m = pm.msg("CamShutterProperty")(metering_mode=MM_AUTO if den is None else MM_MANUAL)
        if den is not None:
            m.shutter_speed.num, m.shutter_speed.den = 1, int(den)
        return await self.write("cam_shutter", m)

    async def set_white_balance(self, kelvin=None):
        m = pm.msg("CamWhiteBalanceProperty")(metering_mode=MM_AUTO if kelvin is None else MM_MANUAL)
        if kelvin is not None:
            m.white_balance_kelvin = int(kelvin)
        return await self.write("cam_white_balance", m)

    async def set_tint(self, tint=None):
        m = pm.msg("CamTintProperty")(metering_mode=MM_AUTO if tint is None else MM_MANUAL)
        if tint is not None:
            m.tint = int(tint)
        return await self.write("cam_tint", m)

    async def set_exposure_comp(self, stops):
        m = pm.msg("CamExposureCompProperty")(metering_mode=MM_AUTO, exposure_comp_stops=float(stops))
        return await self.write("cam_exposure_comp", m)

    async def set_focus(self, value=None):
        """value=None -> autofocus; else manual focus value within cam_focus_range."""
        cur = self.props.get("cam_focus")
        m = pm.msg("CamFocusProperty")(is_auto=value is None, is_locked=bool(cur and cur.is_locked))
        if value is not None:
            m.focus_value = float(value)
        return await self.write("cam_focus", m)

    async def set_zoom(self, factor):
        return await self.write("cam_zoom", pm.msg("CamZoomProperty")(zoom_factor=float(factor)))

    async def set_lens(self, camera_id, is_auto=False):
        return await self.write("active_cam", pm.msg("ActiveCamProperty")(camera_id=camera_id, is_auto=is_auto))

    async def set_stabilization(self, level):
        return await self.write("cam_stabilization", pm.msg("CamStabilizationProperty")(level=int(level)))

    async def set_torch(self, on, value=None):
        m = pm.msg("CamLightProperty")(is_enabled=bool(on))
        if value is not None:
            m.light_value = int(value)
        return await self.write("cam_light", m)

    async def set_lut(self, name, display=True, record=False):
        return await self.write("lut", pm.msg("LutProperty")(selected_lut=name, is_display_lut=display,
                                                            is_record_lut=record))

    async def set_frame_rate(self, fps):
        """fps: int, or (num, den) to keep fractional rates exact (30000/1001, not 29)."""
        cur = self.props.get("cam_framerate")
        m = pm.msg("CamFramerateProperty")()
        if cur is not None:
            m.CopyFrom(cur)
        num, den = (int(fps[0]), int(fps[1])) if isinstance(fps, (tuple, list)) else (int(fps), 1)
        m.playback_frame_rate.num, m.playback_frame_rate.den = num, max(den, 1)
        return await self.write("cam_framerate", m)

    async def tap_focus(self, x, y, kind="CAM_POE_TYPE_SINGLE_SHOT_AE_AF"):
        """x, y normalized 0..1 in the camera's coordinate space."""
        m = pm.msg("CamPointOfInterestProperty")(type=pm.enum_value("PointOfInterestType", kind))
        m.point.x, m.point.y = float(x), float(y)
        return await self.write("cam_point_of_interest", m)

    async def set_slate(self, **fields):
        return await self.write("slate", pm.msg("SlateProperty")(**fields))

    # ------------------------------------------------------------------ recording
    def _who(self):
        return self.remote_info.get("device_name") or self.host

    def _is_recording(self):
        rs = self.props.get("recording_state")
        return bool(rs and rs.is_recording)

    async def start_recording(self, dim_screen=False, source=""):
        """One start at a time, and none while the camera already records: Blackmagic Camera accepts two
        starts that arrive together, and a second start mid-take is never what the user meant. Every
        command and its answer is logged (doubled clips on 25/27 Oct 2026 left no trace to check)."""
        if self._rec_cmd is not None:
            log.info("record: %s: start (%s) ignored - %s still waiting for its answer", self._who(), source or "?",
                     self._rec_cmd)
            return "IGNORED_PENDING"
        if self._is_recording():
            log.info("record: %s: start (%s) ignored - already recording", self._who(), source or "?")
            return "IGNORED_RECORDING"
        return await self._rec_request("start", source, start_recording_request=pm.msg("StartRecordingRequest")(
            dim_screen=dim_screen))

    async def stop_recording(self, source=""):
        if self._rec_cmd is not None:
            log.info("record: %s: stop (%s) ignored - %s still waiting for its answer", self._who(), source or "?",
                     self._rec_cmd)
            return "IGNORED_PENDING"
        return await self._rec_request("stop", source, stop_recording_request=pm.msg("StopRecordingRequest")())

    async def _rec_request(self, what, source, **kw):
        self._rec_cmd = what
        t = time.monotonic()
        log.info("record: %s: %s (%s) sent, camera %s", self._who(), what, source or "?",
                 "recording" if self._is_recording() else "idle")
        try:
            r = await self._request(**kw)
        finally:
            self._rec_cmd = None
        status = pm.enum_name("StartRecordingStatus" if what == "start" else "StopRecordingStatus", r.status) \
            if r else "NO_RESPONSE"
        log.info("record: %s: %s (%s) -> %s in %.0f ms", self._who(), what, source or "?", status,
                 (time.monotonic() - t) * 1000)
        return status

    async def pause_recording(self):
        r = await self._request(pause_recording_request=pm.msg("PauseRecordingRequest")())
        return pm.enum_name("PauseRecordingStatus", r.status) if r else "NO_RESPONSE"

    async def identify(self):
        return await self._request(identify_request=pm.msg("IdentifyCamRequest")())

    # ------------------------------------------------------------------ camera settings menu
    async def get_settings(self):
        """Returns (status_name, AvailableSettings)."""
        r = await self._request(timeout=8, start_settings_edit_request=pm.msg("StartSettingsEditRequest")())
        if r is None:
            raise SessionError("camera did not answer the settings request")
        return pm.enum_name("StartSettingsEditStatus", r.status), r.available_settings

    async def write_settings(self, settings, done=True):
        """settings: list of (key, value) where value is str (combo key) or bool (checkbox)."""
        req = pm.msg("WriteSettingsRequest")(is_done_editing=done)
        for key, val in settings:
            ws = req.settings.add(setting_key=key)
            if isinstance(val, bool):
                ws.check_box.is_set = val
            else:
                ws.combo_box.selected_option_key = str(val)
        r = await self._request(timeout=8, write_settings_request=req)
        if r is None:
            raise SessionError("camera did not answer")
        return [pm.enum_name("WriteSettingStatus", s) for s in r.status], r.confirmation_message
