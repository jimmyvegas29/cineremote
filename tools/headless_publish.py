"""Headless: connect to a camera and publish its video+audio to OBS shared memory (no GUI).
Used to exercise the shared-memory path that the OBS plugin reads.

  python tools/headless_publish.py --host PHONE_IP --password PW [--seconds 20]
"""
import argparse
import asyncio
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from cineremote.session import CameraSession, SessionError  # noqa: E402
from cineremote.obs_shm import ShmPublisher  # noqa: E402


async def main(a):
    pub = ShmPublisher("Headless Test Cam", "testcam-001")
    print(f"publishing to OBS slot {pub.slot}")
    n = [0, 0]

    def on_video(fr):
        pub.push_video(fr)
        n[0] += 1
        if n[0] % 48 == 0:
            print(f"  video {n[0]} frames  {fr.width}x{fr.height}")

    def on_audio(fr):
        pub.push_audio(fr)
        n[1] += 1

    s = None
    try:
        # Like the app: a phone that just had a failed controller needs one polite attempt (which
        # ends in a bye) before it offers again, so retry a couple of times.
        for attempt in range(3):
            s = CameraSession(a.host, a.port, a.password, "Headless Publisher")
            s.on_video = on_video
            s.on_audio = on_audio
            try:
                await s.connect()
                break
            except SessionError as e:
                print(f"connect attempt {attempt + 1} failed: {e}")
                await s.close()
                s = None
                await asyncio.sleep(2)
        if s is None:
            raise SystemExit("could not connect")
        ok = await s.start_preview()
        print("preview:", "OK" if ok else "FAILED")
        await asyncio.sleep(a.seconds)
    finally:
        if s is not None:
            await s.close()
        pub.close()
    print(f"done: {n[0]} video, {n[1]} audio frames")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=2222)
    ap.add_argument("--password", required=True)
    ap.add_argument("--seconds", type=float, default=20)
    a = ap.parse_args()
    import logging
    logging.basicConfig(level=logging.WARNING)
    asyncio.run(main(a))
