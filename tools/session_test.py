"""Functional test of cineremote.session.CameraSession against a live camera.

  python tools/session_test.py --host PHONE_IP --password PW [--record]
"""
import argparse
import asyncio
import logging
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from cineremote.session import CameraSession  # noqa: E402
from cineremote import proto_msgs as pm  # noqa: E402

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail else ""))


async def one_session(a, n):
    s = CameraSession(a.host, a.port, a.password, controller_name="Windows Test")
    frames = []
    s.on_video = lambda f: frames.append((time.monotonic(), f.width, f.height))
    audio = []
    s.on_audio = lambda f: audio.append(f.samples)
    t0 = time.monotonic()
    try:
        await _body(a, n, s, frames, audio, t0)
    finally:
        await s.close()
    check(f"[{n}] clean close", s.state == "disconnected")


async def _body(a, n, s, frames, audio, t0):
    await s.connect()
    check(f"[{n}] connect+auth+hello", s.hello is not None, f"{time.monotonic()-t0:.2f}s codecs={list(s.hello.encoding_video_codecs)}")
    check(f"[{n}] properties read", len(s.props) > 25, f"{len(s.props)} props")
    ok = await s.start_preview()
    check(f"[{n}] start_preview response OK", ok)
    await asyncio.sleep(4)
    check(f"[{n}] video frames", len(frames) > 30, f"{len(frames)} frames, {s.stats['width']}x{s.stats['height']} @ {s.stats['fps']:.1f}fps")
    check(f"[{n}] audio frames", len(audio) > 50, f"{len(audio)} frames")
    if frames:
        print(f"   first frame after {frames[0][0]-t0:.2f}s from connect start")

    if n == 1:
        r = await s.set_iso(200)
        await asyncio.sleep(0.6)
        iso = s.props.get("cam_sensibility")
        check("set ISO 200 manual", iso and iso.sensibility_iso == 200 and iso.metering_mode == 2,
              f"status={pm.enum_name('WritePropertyStatus', r.status) if r else None} now={iso.sensibility_iso if iso else None}")
        r = await s.set_iso(None)
        await asyncio.sleep(0.6)
        iso = s.props.get("cam_sensibility")
        check("ISO back to auto", iso and iso.metering_mode == 1, f"status={pm.enum_name('WritePropertyStatus', r.status) if r else None}")

        r = await s.set_shutter(100)
        await asyncio.sleep(0.6)
        sh = s.props.get("cam_shutter")
        check("shutter 1/100", sh and sh.shutter_speed.den == 100, f"{(pm.enum_name('WritePropertyStatus', r.status) if r else None)} {sh.shutter_speed.num if sh else ''}/{sh.shutter_speed.den if sh else ''}")
        await s.set_shutter(None)

        r = await s.set_white_balance(4000)
        await asyncio.sleep(0.6)
        wb = s.props.get("cam_white_balance")
        check("WB 4000K", wb and wb.white_balance_kelvin == 4000, f"{(pm.enum_name('WritePropertyStatus', r.status) if r else None)} {wb.white_balance_kelvin if wb else ''}")
        await s.set_white_balance(None)

        r = await s.set_zoom(2.0)
        for _ in range(30):   # the zoom event arrives after the ~1 s zoom transition
            await asyncio.sleep(0.1)
            z = s.props.get("cam_zoom")
            if z and abs(z.zoom_factor - 2.0) < 0.05:
                break
        z = s.props.get("cam_zoom")
        check("zoom 2x", z and abs(z.zoom_factor - 2.0) < 0.05, f"{(pm.enum_name('WritePropertyStatus', r.status) if r else None)} {z.zoom_factor if z else ''}")
        await s.set_zoom(1.0)

        st, avail = await s.get_settings()
        n_items = sum(len(sec.settings_descriptions) for sec in avail.sections)
        check("settings menu", st.endswith("OKAY") and n_items > 0, f"{st}, {len(avail.sections)} sections, {n_items} items")
        for sec in avail.sections[:6]:
            print("   ", sec.label, [d.label for d in sec.settings_descriptions][:8])
        try:
            await s.write_settings([], done=True)  # close the edit session
        except Exception as e:
            print("   write_settings(done) ->", e)

        if a.record:
            r = await s.start_recording()
            check("start recording", r.endswith("OKAY"), r)
            await asyncio.sleep(3)
            rs = s.props.get("recording_state")
            check("recording_state.is_recording", rs and rs.is_recording)
            r = await s.stop_recording()
            check("stop recording", r.endswith("OKAY"), r)

    await s.stop_preview()


async def main(a):
    await one_session(a, 1)
    await asyncio.sleep(3)
    await one_session(a, 2)
    print(f"\n{sum(ok for _, ok in results)}/{len(results)} checks passed")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=2222)
    ap.add_argument("--password", required=True)
    ap.add_argument("--record", action="store_true")
    a = ap.parse_args()
    logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    asyncio.run(main(a))
