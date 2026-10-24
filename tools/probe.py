"""Protocol probe: discover/connect to a Blackmagic Camera remote, authenticate, open WebRTC,
exchange control messages and dump everything. Used to verify the reverse-engineered protocol.

  python tools/probe.py [--host IP --port N] --password PW [--preview] [--seconds 20]
"""
import argparse
import asyncio
import logging
import os
import sys
import time
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from aiortc import RTCPeerConnection, RTCSessionDescription, RTCConfiguration
from aiortc.sdp import candidate_from_sdp
from google.protobuf import text_format

from cineremote import rtc_compat  # noqa: F401  (DTLS cipher patch)
from cineremote import proto_msgs as pm
from cineremote.signaling import SignalingClient, participant_info

log = logging.getLogger("probe")


def discover(timeout=6):
    from zeroconf import Zeroconf, ServiceBrowser, ServiceListener
    found = []

    class L(ServiceListener):
        def add_service(self, zc, t, name):
            i = zc.get_service_info(t, name, 3000)
            if i:
                found.append((name, i.parsed_addresses(), i.port,
                              {k.decode(): (v.decode() if v else "") for k, v in (i.properties or {}).items()}))

        def update_service(self, *a): pass
        def remove_service(self, *a): pass

    zc = Zeroconf()
    ServiceBrowser(zc, "_bmd-cam-control._tcp.local.", L())
    time.sleep(timeout)
    zc.close()
    return found


async def main(a):
    host, port = a.host, a.port
    if not host:
        f = discover()
        for x in f:
            print("FOUND", x)
        if not f:
            print("no cameras found"); return
        host = [ip for ip in f[0][1] if ":" not in ip][0]; port = f[0][2]

    sig = SignalingClient(host, port, a.password, participant_info(uuid.UUID(int=0x42), "Windows Probe"))
    await sig.connect()
    await sig.authenticate()
    print("REMOTE INFO", sig.remote_info)

    pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
    msg_id = [0]
    dc_holder = {}
    got = asyncio.Event()
    sdp_n = [0]
    answered = asyncio.Event()

    def send_ctrl(**kw):
        msg_id[0] += 1
        m = pm.ControllerMessage(message_id=msg_id[0], **kw)
        ms = pm.ControllerMessages(messages=[m])
        print(">>", text_format.MessageToString(m, as_one_line=True)[:400])
        dc_holder["dc"].send(ms.SerializeToString())

    def on_dc_message(data):
        if isinstance(data, str):
            print("<< (text)", data[:300]); return
        ms = pm.SubordinateMessages()
        ms.ParseFromString(data)
        for m in ms.messages:
            kind = m.WhichOneof("message")
            if kind in ("webrtc_sdp_request", "webrtc_sdp_response"):
                sdp_n[0] += 1
                open(os.path.join(a.out, f"sdp{sdp_n[0]}_remote_{kind}.txt"), "w").write(getattr(m, kind).data)
            print("<<", kind, text_format.MessageToString(m, as_one_line=True)[:600])
            if kind == "heart_beat_request":
                send_ctrl(subordinate_message_id=m.message_id, heart_beat_response=pm.msg("HeartBeatResponse")())
            if kind == "webrtc_sdp_request":
                asyncio.ensure_future(handle_reoffer(m))
            if kind == "webrtc_sdp_response":
                print("REMOTE MEDIA ANSWER:", m.webrtc_sdp_response.data)
                asyncio.ensure_future(pc.setRemoteDescription(RTCSessionDescription(m.webrtc_sdp_response.data, "answer")))
                answered.set()

    async def handle_reoffer(m):
        await pc.setRemoteDescription(RTCSessionDescription(m.webrtc_sdp_request.data, "offer"))
        ans = await pc.createAnswer()
        await pc.setLocalDescription(ans)
        print("REOFFER answered; local sdp:\n", pc.localDescription.sdp[:1500])
        send_ctrl(subordinate_message_id=m.message_id,
                  webrtc_sdp_response=pm.msg("WebRtcSdpResponse")(data=pc.localDescription.sdp))

    def wire_dc(dc):
        dc_holder["dc"] = dc
        print("DATACHANNEL", dc.label, dc.id, dc.readyState)

        @dc.on("open")
        def _o():
            print("DC OPEN")
            got.set()
        if dc.readyState == "open":
            got.set()
        dc.on("message", on_dc_message)

    @pc.on("datachannel")
    def on_datachannel(dc):
        wire_dc(dc)

    @pc.on("track")
    def on_track(track):
        print("TRACK", track.kind)
        async def pump():
            n = 0
            while True:
                fr = await track.recv()
                n += 1
                if track.kind == "video" and n in (1, 30, 120):
                    img = fr.to_image()
                    p = os.path.join(a.out, f"frame_{n}.png"); img.save(p)
                    print("VIDEO FRAME", n, fr.width, fr.height, fr.format.name, "->", p)
                if track.kind == "audio" and n in (1, 100):
                    print("AUDIO FRAME", n, fr.sample_rate, fr.layout.name, fr.samples)
        asyncio.ensure_future(pump())

    @pc.on("connectionstatechange")
    def _cs():
        print("PC STATE", pc.connectionState)

    # The camera always sends the offer. NEVER offer from the controller side: the app's native
    # pipeline segfaults (GsPipeline::OnSdpAnswerCreated) if it has to answer.
    async def sig_pump():
        while True:
            t, d = await sig.queue.get()
            try:
                await handle_sig(t, d)
            except Exception as e:
                print("SIG HANDLER ERROR", t, repr(e))
            if t == "closed":
                print("SIGNALING CLOSED"); return

    async def handle_sig(t, d):
            print("SIG <", t, str(d)[:300])
            if t == "sdp_offer" and "\nm=" not in d["value"]:
                print("  (empty offer -> minimal answer)")
                await sig.send("sdp_answer", {"value": "v=0\r\no=- 1 0 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\na=group:BUNDLE\r\n"})
                return
            if t == "sdp_answer":
                print("REMOTE ANSWER:\n", d["value"])
                await pc.setRemoteDescription(RTCSessionDescription(d["value"], "answer"))
            elif t == "ice_candidate":
                v = d["value"]
                if v.startswith("candidate:"):
                    c = candidate_from_sdp(v.split(":", 1)[1]); c.sdpMLineIndex = 0
                    await pc.addIceCandidate(c)
            elif t == "sdp_offer":
                await pc.setRemoteDescription(RTCSessionDescription(d["value"], "offer"))
                ans = await pc.createAnswer(); await pc.setLocalDescription(ans)
                await sig.send("sdp_answer", {"value": pc.localDescription.sdp})
    asyncio.ensure_future(sig_pump())

    await asyncio.wait_for(got.wait(), 20)
    app = pm.AppInfo(name="Blackmagic Camera", version="3.5.0", platform=pm.enum_value("AppPlatform", "APP_PLATFORM_ANDROID"),
                     platform_version="16", device_model_name="Windows PC", language_code="en")
    send_ctrl(hello_request=pm.msg("HelloRequest")(app_info=app))
    await asyncio.sleep(1.5)
    for pid in pm.PROPERTY_IDS.values():
        if pid:
            send_ctrl(read_property_request=pm.msg("ReadPropertyRequest")(property_id=pid))
            await asyncio.sleep(0.02)
    await asyncio.sleep(2)
    if a.preview and not a.offer:
        send_ctrl(start_preview_request=pm.msg("StartPreviewRequest")(
            video_codec=pm.enum_value("VideoCodec", a.codec), audio_num_channels_to_stream=2,
                frame_rate=pm.msg("FrameRate")(num=a.fps, den=1)))
    if a.preview and a.offer:
        from aiortc.rtcrtpsender import RTCRtpSender
        vt = pc.addTransceiver("video", direction="recvonly")
        caps = RTCRtpSender.getCapabilities("video")
        vt.setCodecPreferences([c for c in caps.codecs if c.mimeType.lower() in ("video/h265", "video/h264")])
        pc.addTransceiver("audio", direction="recvonly")
        off = await pc.createOffer(); await pc.setLocalDescription(off)
        print("LOCAL MEDIA OFFER:", pc.localDescription.sdp)
        if a.preview_first:
            send_ctrl(start_preview_request=pm.msg("StartPreviewRequest")(
                video_codec=pm.enum_value("VideoCodec", a.codec), audio_num_channels_to_stream=2,
                frame_rate=pm.msg("FrameRate")(num=a.fps, den=1)))
        send_ctrl(webrtc_sdp_request=pm.msg("WebRtcSdpRequest")(data=pc.localDescription.sdp))
        if not a.nowait:
            try:
                await asyncio.wait_for(answered.wait(), 8)
            except asyncio.TimeoutError:
                print("NO MEDIA ANSWER")
        if not a.preview_first:
            send_ctrl(start_preview_request=pm.msg("StartPreviewRequest")(
                video_codec=pm.enum_value("VideoCodec", a.codec), audio_num_channels_to_stream=2,
                frame_rate=pm.msg("FrameRate")(num=a.fps, den=1)))
    await asyncio.sleep(a.seconds)
    send_ctrl(disconnect_event=pm.msg("DisconnectEvent")())
    await asyncio.sleep(0.5)
    dc_holder["dc"].close()
    await asyncio.sleep(1.0)
    await pc.close(); await sig.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host"); ap.add_argument("--port", type=int, default=2222)
    ap.add_argument("--password", required=True)
    ap.add_argument("--preview", action="store_true")
    ap.add_argument("--codec", default="VIDEO_CODEC_H265_MAIN")
    ap.add_argument("--fps", type=int, default=24)
    ap.add_argument("--nowait", action="store_true")
    ap.add_argument("--preview-first", action="store_true")
    ap.add_argument("--offer", action="store_true", help="controller sends the media offer (experimental)")
    ap.add_argument("--seconds", type=float, default=15)
    ap.add_argument("--out", default=".")
    ap.add_argument("-v", action="store_true")
    a = ap.parse_args()
    logging.basicConfig(level=logging.DEBUG if a.v else logging.INFO, format="%(asctime)s %(name)s %(message)s")
    asyncio.run(main(a))
