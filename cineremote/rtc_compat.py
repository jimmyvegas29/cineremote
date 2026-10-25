"""aiortc <-> GStreamer webrtcbin (Blackmagic Camera app) compatibility patches.

1. DTLS: webrtcbin presents an RSA certificate, aiortc only allows ECDHE-ECDSA suites -> widen.
2. H.265: the app's preview is HEVC-only on many phones (HelloResponse.encoding_video_codecs).
   aiortc has no H.265 support, so we register video/H265 with an RFC 7798 depayloader and a
   PyAV (FFmpeg) hevc decoder.
3. Low-latency jitter buffer: aiortc only completes a video frame when the NEXT frame's first
   packet arrives (+1 frame, 33-42 ms) and holds 4 audio packets (~80 ms). We complete video frames
   on the RTP marker bit (last packet of an access unit), release audio packets immediately, and
   enlarge the video buffer (1080p keyframes span well over aiortc's 128 packets).
4. Decoder threading: FFmpeg frame threading holds back (threads-1) frames - 15 frames / 625 ms on a
   16-thread machine. The HEVC decoder uses low_delay + slice threads instead (0 frames held).
"""
import time
from struct import unpack_from
from typing import cast

import av
from OpenSSL import SSL

import aiortc.codecs as _codecs
import aiortc.rtcdtlstransport as _dtls
import aiortc.rtcrtpreceiver as _receiver
from aiortc.codecs.base import Decoder
from aiortc.jitterbuffer import JitterBuffer, JitterFrame
from aiortc.rtcrtpparameters import RTCRtcpFeedback, RTCRtpCodecParameters

# ---------------------------------------------------------------- DTLS ciphers
_CIPHERS = (
    b"ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:"
    b"ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:"
    b"ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:"
    b"ECDHE-ECDSA-AES128-SHA:ECDHE-RSA-AES128-SHA:ECDHE-ECDSA-AES256-SHA:ECDHE-RSA-AES256-SHA"
)


class _PatchedSSL:
    """Proxy for OpenSSL.SSL used inside aiortc.rtcdtlstransport that widens the cipher list."""

    def __getattr__(self, name):
        return getattr(SSL, name)

    class Context(SSL.Context):
        def set_cipher_list(self, cipher_list):
            return super().set_cipher_list(_CIPHERS)


# ---------------------------------------------------------------- H.265 (RFC 7798)
_START = b"\x00\x00\x00\x01"
H265_PT = 103


def h265_depayload(payload: bytes) -> bytes:
    # Never raise: a dropped packet leaves a hole the jitter buffer can't fill (NACK can't help),
    # so unusable payloads become empty data and the decoder/keyframe logic copes.
    if len(payload) < 3:
        return b""
    nal_type = (payload[0] >> 1) & 0x3F
    if nal_type < 48:  # single NAL unit packet
        return _START + payload
    if nal_type == 48:  # aggregation packet
        out = bytearray()
        pos = 2
        while pos + 2 <= len(payload):
            size = unpack_from("!H", payload, pos)[0]
            pos += 2
            if pos + size > len(payload):
                break
            out += _START + payload[pos:pos + size]
            pos += size
        return bytes(out)
    if nal_type == 49:  # fragmentation unit
        fu = payload[2]
        if fu & 0x80:  # start bit: rebuild the original 2-byte NAL header
            hdr0 = (payload[0] & 0x81) | ((fu & 0x3F) << 1)
            return _START + bytes([hdr0, payload[1]]) + payload[3:]
        return payload[3:]
    return b""  # e.g. PACI (50): no media data we use


class H265Decoder(Decoder):
    def __init__(self) -> None:
        self.codec = av.CodecContext.create("hevc", "r")
        # Never frame threading: it delays output by (threads-1) frames. low_delay + slice threads
        # outputs each frame as soon as its access unit is decoded (~5 ms for 1080p).
        self.codec.thread_type = "SLICE"
        self.codec.thread_count = 4
        self.codec.flags |= av.codec.context.Flags.low_delay

    def decode(self, encoded_frame):
        try:
            packet = av.Packet(encoded_frame.data)
            packet.pts = encoded_frame.timestamp
            packet.time_base = _codecs.h264.VIDEO_TIME_BASE
            return cast(list, self.codec.decode(packet))
        except av.FFmpegError:
            return []


_orig_depayload = _codecs.depayload
_orig_get_decoder = _codecs.get_decoder


def _depayload(codec, payload):
    if codec.name.upper() == "H265":
        return h265_depayload(payload)
    return _orig_depayload(codec, payload)


def _get_decoder(codec):
    if codec.mimeType.lower() == "video/h265":
        return H265Decoder()
    return _orig_get_decoder(codec)


HOLE_DEADLINE_S = 0.1     # sender retransmits on NACK: wait this long for the missing packet
REORDER_DEADLINE_S = 0.02  # sender never retransmits: only wait for a reordered packet


def offers_retransmission(sdp):
    """True if the offer negotiates generic NACK ("a=rtcp-fb:<pt> nack", not just "nack pli") for
    video. The Blackmagic apps offer only PLI/FIR, so a lost packet is never re-sent and waiting
    the full NACK deadline just freezes the picture longer before the keyframe request goes out."""
    import re
    return re.search(r"^a=rtcp-fb:\S+ nack\s*$", sdp, re.M) is not None


def opus_stereo(sdp):
    """Add stereo=1 to every Opus line of our answer. Without it a WebRTC sender encodes mono (the
    receiver's fmtp is what allows stereo), and the decoder then plays that one channel on both sides."""
    import re
    nl = "\r\n" if "\r\n" in sdp else "\n"
    lines = sdp.split(nl)
    for pt in re.findall(r"^a=rtpmap:(\d+) opus/48000/2\s*$", sdp, re.M | re.I):
        i = next((n for n, ln in enumerate(lines) if ln.startswith(f"a=fmtp:{pt} ")), None)
        if i is None:
            j = next(n for n, ln in enumerate(lines) if ln.startswith(f"a=rtpmap:{pt} "))
            lines.insert(j + 1, f"a=fmtp:{pt} stereo=1;sprop-stereo=1")
        else:
            for p in ("stereo=1", "sprop-stereo=1"):
                if not re.search(rf"[ ;]{p}(;|$)", lines[i]):
                    lines[i] += ";" + p
    return nl.join(lines)


def set_hole_deadline(pc, seconds):
    for t in pc.getTransceivers():
        jb = getattr(t.receiver, "_RTCRtpReceiver__jitter_buffer", None)
        if isinstance(jb, _LowLatencyJitterBuffer):
            jb.hole_deadline = seconds


class _LowLatencyJitterBuffer(JitterBuffer):
    def __init__(self, capacity, prefetch=0, is_video=False):
        super().__init__(capacity=1024 if is_video else capacity, prefetch=0, is_video=is_video)
        self._hole_since = None
        self._want_pli = False
        self.hole_deadline = HOLE_DEADLINE_S

    def add(self, packet):
        pli, frame = super().add(packet)
        if self._want_pli:
            self._want_pli = False
            pli = True
        return pli, frame

    def _skip_hole(self, count):
        """A packet at offset `count` never arrived. Audio: drop it. Video: drop the damaged frame
        (up to and including the next marker packet) and ask the sender for a keyframe."""
        if not self._is_video:
            self.remove(count + 1)
            return
        for k in range(count + 1, self.capacity):
            p = self._packets[(self._origin + k) % self._capacity]
            if p is not None and p.marker:
                self.remove(k + 1)
                break
        else:
            return  # end of the damaged frame not here yet
        self._want_pli = True
        self._hole_since = None

    def _remove_frame(self, sequence_number):
        packets = []
        timestamp = None
        for count in range(self.capacity):
            packet = self._packets[(self._origin + count) % self._capacity]
            if packet is None:
                # Gap. If later packets exist, it's a real hole: give retransmission a moment, then skip.
                later = any(self._packets[(self._origin + k) % self._capacity] is not None
                            for k in range(count + 1, min(count + 64, self.capacity)))
                if not later:
                    self._hole_since = None
                    return None
                now = time.monotonic()
                if self._hole_since is None:
                    self._hole_since = now
                if not self._is_video or now - self._hole_since > self.hole_deadline:
                    before = self._origin
                    self._skip_hole(count)
                    if self._origin != before:
                        return self._remove_frame(sequence_number)  # release what queued behind the hole
                return None
            if timestamp is None:
                timestamp = packet.timestamp
            elif packet.timestamp != timestamp:
                # timestamp changed without a marker on the previous packet: that frame is complete
                frame = JitterFrame(data=b"".join(x._data for x in packets), timestamp=timestamp)
                self.remove(count)
                return frame
            packets.append(packet)
            # Audio: every RTP packet is a whole Opus frame. Video: the marker bit ends the access unit.
            if not self._is_video or packet.marker:
                frame = JitterFrame(data=b"".join(x._data for x in packets), timestamp=timestamp)
                self.remove(count + 1)
                return frame
        return None


_applied = False


def apply():
    global _applied
    if _applied:
        return
    _dtls.SSL = _PatchedSSL()
    if not any(c.mimeType.lower() == "video/h265" for c in _codecs.CODECS["video"]):
        fb = [RTCRtcpFeedback(type="nack"), RTCRtcpFeedback(type="nack", parameter="pli"),
              RTCRtcpFeedback(type="goog-remb")]
        _codecs.CODECS["video"].insert(0, RTCRtpCodecParameters(
            mimeType="video/H265", clockRate=90000, payloadType=H265_PT, rtcpFeedback=fb))
    _codecs.depayload = _depayload
    _codecs.get_decoder = _get_decoder
    _receiver.depayload = _depayload
    _receiver.get_decoder = _get_decoder
    _receiver.JitterBuffer = _LowLatencyJitterBuffer
    _applied = True


apply()
