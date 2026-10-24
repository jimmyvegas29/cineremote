"""Connect to each camera, save one decoded preview frame as JPEG, disconnect cleanly.

usage: python tools/grab_frames.py PASSWORD OUTDIR HOST [HOST ...]
"""
import asyncio
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from cineremote.session import CameraSession  # noqa: E402


async def grab(host, pw, outdir):
    s = CameraSession(host, 2222, pw, "Frame Grab")
    frames = []
    s.on_video = frames.append
    try:
        await s.connect()
        await s.start_preview()
        await asyncio.sleep(3.0)               # let auto exposure settle
        name = (s.remote_info.get("device_name") or host).replace(" ", "_")
        if frames:
            path = os.path.join(outdir, f"{name}.jpg")
            frames[-1].to_image().save(path, quality=92)
            print("saved", path, frames[-1].width, "x", frames[-1].height)
        else:
            print("no frames from", host)
    finally:
        await s.close()


async def main():
    pw, outdir, hosts = sys.argv[1], sys.argv[2], sys.argv[3:]
    os.makedirs(outdir, exist_ok=True)
    await asyncio.gather(*(grab(h, pw, outdir) for h in hosts), return_exceptions=False)


asyncio.run(main())
