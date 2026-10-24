"""Unit tests for the low-latency video jitter buffer in cineremote/rtc_compat.py."""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from aiortc.rtp import RtpPacket  # noqa: E402

from cineremote import rtc_compat as rc  # noqa: E402


def pkt(seq, ts, marker=False):
    p = RtpPacket(payload_type=123, sequence_number=seq, timestamp=ts, marker=marker, payload=b"\x02\x01xyz")
    p._data = p.payload          # aiortc's receiver depayloads into _data before the jitter buffer
    return p


def feed(jb, packets):
    out = []
    for p in packets:
        pli, frame = jb.add(p)
        out.append((pli, frame))
    return out


def test_marker_completes_frame():
    jb = rc._LowLatencyJitterBuffer(128, is_video=True)
    r = feed(jb, [pkt(1, 1000), pkt(2, 1000), pkt(3, 1000, marker=True)])
    assert r[-1][1] is not None and r[-1][1].timestamp == 1000, "frame must complete on its marker packet"
    assert all(f is None for _, f in r[:-1])


def test_hole_skipped_after_deadline_with_pli(deadline):
    jb = rc._LowLatencyJitterBuffer(128, is_video=True)
    jb.hole_deadline = deadline
    feed(jb, [pkt(1, 1000), pkt(2, 1000, marker=True)])              # frame 1 ok
    feed(jb, [pkt(3, 2000), pkt(5, 2000, marker=True)])              # frame 2 lost packet 4
    t0 = time.monotonic()
    got = None
    seq = 6
    while got is None and time.monotonic() - t0 < 1.0:
        pli, frame = jb.add(pkt(seq, 3000 + seq, marker=True))       # later frames keep arriving
        seq += 1
        if frame is not None and frame.timestamp >= 3000:
            got = (pli, frame, time.monotonic() - t0)
        time.sleep(0.002)
    assert got is not None, "buffer must move past the hole"
    waited = got[2]
    assert waited >= deadline * 0.8, f"skipped too early ({waited * 1000:.0f} ms < {deadline * 1000:.0f} ms)"
    assert waited < deadline + 0.05, f"waited too long ({waited * 1000:.0f} ms)"
    return waited


def test_reordered_packet_not_skipped():
    jb = rc._LowLatencyJitterBuffer(128, is_video=True)
    jb.hole_deadline = rc.REORDER_DEADLINE_S
    feed(jb, [pkt(1, 1000), pkt(3, 1000, marker=True)])
    pli, frame = jb.add(pkt(2, 1000))                                # late but within deadline
    assert frame is not None and frame.timestamp == 1000 and not pli, "reordered packet must complete the frame"


def test_sdp_detection():
    assert not rc.offers_retransmission("a=rtcp-fb:123 nack pli\na=rtcp-fb:123 ccm fir\n")
    assert rc.offers_retransmission("a=rtcp-fb:123 nack pli\na=rtcp-fb:123 nack\n")


if __name__ == "__main__":
    test_marker_completes_frame()
    w_rtx = test_hole_skipped_after_deadline_with_pli(rc.HOLE_DEADLINE_S)
    w_reorder = test_hole_skipped_after_deadline_with_pli(rc.REORDER_DEADLINE_S)
    test_reordered_packet_not_skipped()
    test_sdp_detection()
    print(f"jitter tests PASS (hole skipped after {w_rtx * 1000:.0f} ms with NACK, {w_reorder * 1000:.0f} ms without)")
