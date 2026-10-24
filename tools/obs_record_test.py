"""End-to-end OBS proof: generate a throwaway OBS profile + scene collection containing the
cineremote_camera source, launch OBS recording while the phone publishes, then extract a
frame from the recording to prove video flows all the way through OBS. With --demo the app's demo
mode publishes instead of a phone (no camera needed):

    python tools/obs_record_test.py --host PHONE_IP --password PASSWORD
    python tools/obs_record_test.py --demo Non-destructive: uses its
own profile/collection names and --multi, never touches the user's setup.

Windows: uses the user's OBS config dir with a separate profile/collection.
Linux:   isolated XDG_CONFIG_HOME (own copy of the plugin), OBS runs under xvfb-run.
macOS:   ~/Library/Application Support/obs-studio, OBS.app launched with `open -n`.
"""
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid

NAME = "CineRemoteRec"
OUTDIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "out", "obsrec"))
HERE = os.path.dirname(os.path.abspath(__file__))
if sys.platform == "win32":
    CONFIG = os.path.join(os.environ["APPDATA"], "obs-studio")
    OBS = r"C:\Program Files\obs-studio\bin\64bit\obs64.exe"
    OBS_WD = r"C:\Program Files\obs-studio\bin\64bit"
elif sys.platform == "darwin":
    CONFIG = os.path.expanduser("~/Library/Application Support/obs-studio")
else:
    XDG = os.path.join(tempfile.gettempdir(), "cineremote_obs_test")
    CONFIG = os.path.join(XDG, "obs-studio")


def write_collection():
    src_uuid = str(uuid.uuid4())
    scene_uuid = str(uuid.uuid4())
    coll = {
        "name": NAME,
        "sources": [
            {
                "prev_ver": 536936450, "name": "BM Remote Cam", "uuid": src_uuid,
                "id": "cineremote_camera", "versioned_id": "cineremote_camera",
                "settings": {"camera_id": "", "audio": True},
                "mixers": 255, "sync": 0, "flags": 0, "volume": 1.0, "balance": 0.5,
                "enabled": True, "muted": False, "monitoring_type": 0, "private_settings": {},
            },
            {
                "prev_ver": 536936450, "name": "Scene", "uuid": scene_uuid,
                "id": "scene", "versioned_id": "scene",
                "settings": {
                    "id_counter": 1, "custom_size": False,
                    "items": [{
                        "name": "BM Remote Cam", "source_uuid": src_uuid, "visible": True,
                        "locked": False, "rot": 0.0, "align": 5, "bounds_type": 2,
                        "bounds_align": 0, "bounds": {"x": 1920.0, "y": 1080.0},
                        "scale": {"x": 1.0, "y": 1.0}, "pos": {"x": 0.0, "y": 0.0},
                        "crop_left": 0, "crop_top": 0, "crop_right": 0, "crop_bottom": 0, "id": 1,
                    }],
                },
                "mixers": 0, "sync": 0, "flags": 0, "volume": 1.0, "balance": 0.5,
                "enabled": True, "muted": False, "private_settings": {},
            },
        ],
        "scene_order": [{"name": "Scene"}],
        "current_scene": "Scene", "current_program_scene": "Scene",
        "groups": [], "version": 2, "resolution": {"x": 1920, "y": 1080},
    }
    d = os.path.join(CONFIG, "basic", "scenes")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, NAME + ".json"), "w", encoding="utf-8") as f:
        json.dump(coll, f)


def write_profile():
    d = os.path.join(CONFIG, "basic", "profiles", NAME)
    os.makedirs(d, exist_ok=True)
    os.makedirs(OUTDIR, exist_ok=True)
    fp = OUTDIR.replace("\\", "\\\\") if sys.platform == "win32" else OUTDIR
    ini = f"""[General]
Name={NAME}

[Video]
BaseCX=1920
BaseCY=1080
OutputCX=1920
OutputCY=1080
FPSType=0
FPSCommon=30

[Output]
Mode=Simple

[SimpleOutput]
FilePath={fp}
RecFormat2=mkv
VBitrate=6000
RecQuality=Stream
StreamEncoder=x264
RecEncoder=x264
RecTracks=1

[Audio]
SampleRate=48000
ChannelSetup=Stereo
"""
    with open(os.path.join(d, "basic.ini"), "w", encoding="utf-8") as f:
        f.write(ini)


def prepare_linux():
    """Fresh, isolated OBS config with the freshly built plugin and the first-run wizard disabled."""
    shutil.rmtree(XDG, ignore_errors=True)
    os.makedirs(CONFIG)
    for ini in ("global.ini", "user.ini"):
        with open(os.path.join(CONFIG, ini), "w") as f:
            f.write("[General]\nFirstRun=false\nConfirmOnExit=false\n")
    so = os.path.join(HERE, "..", "obs-plugin", "out", "cineremote-obs.so")
    dest = os.path.join(CONFIG, "plugins", "cineremote-obs")
    os.makedirs(os.path.join(dest, "bin", "64bit"))
    os.makedirs(os.path.join(dest, "data", "locale"))
    shutil.copy(so, os.path.join(dest, "bin", "64bit"))
    with open(os.path.join(dest, "data", "locale", "en-US.ini"), "w") as f:
        f.write('CineRemote Camera="CineRemote Camera"\n')


def launch_obs():
    args = ["--multi", "--minimize-to-tray", "--collection", NAME, "--profile", NAME, "--startrecording",
            "--disable-updater", "--disable-shutdown-check"]
    if sys.platform == "win32":
        ps = (f"$p = Start-Process -FilePath '{OBS}' -WorkingDirectory '{OBS_WD}' -ArgumentList "
              + ",".join(f"'{x}'" for x in args) + " -PassThru; $p.Id")
        return int(subprocess.check_output(["powershell", "-NoProfile", "-Command", ps], text=True).strip())
    if sys.platform == "darwin":
        app = next(p for p in ("/Applications/OBS.app", os.path.expanduser("~/Applications/OBS.app"))
                   if os.path.isdir(p))
        return subprocess.Popen(["open", "-n", "-W", app, "--args"] + args)
    env = dict(os.environ, XDG_CONFIG_HOME=XDG)
    return subprocess.Popen(["xvfb-run", "-a", "-s", "-screen 0 1920x1080x24", "obs"] + args, env=env,
                            stdout=open(os.path.join(OUTDIR, "obs.log"), "w"), stderr=subprocess.STDOUT,
                            start_new_session=True)


def stop_obs(proc):
    """Graceful stop so the mkv finalizes."""
    if sys.platform == "win32":
        pid = proc
        subprocess.run(["powershell", "-NoProfile", "-Command",
                        f"$p=Get-Process -Id {pid} -ErrorAction SilentlyContinue; if($p){{$p.CloseMainWindow() | Out-Null}}"],
                       check=False)
        time.sleep(4)
        subprocess.run(["powershell", "-NoProfile", "-Command",
                        f"Stop-Process -Id {pid} -Force -ErrorAction SilentlyContinue"], check=False)
        return
    # OBS quits cleanly (finalizing the recording) on SIGINT on Linux and macOS
    subprocess.run(["pkill", "-INT", "-f", "OBS.app/Contents/MacOS/OBS --multi" if sys.platform == "darwin"
                    else "obs --multi"], check=False)
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid, signal.SIGKILL) if sys.platform != "darwin" else proc.kill()


def main(password, host, demo=False):
    for f in os.listdir(OUTDIR) if os.path.isdir(OUTDIR) else []:
        if f.endswith(".mkv"):
            os.remove(os.path.join(OUTDIR, f))
    os.makedirs(OUTDIR, exist_ok=True)
    if sys.platform.startswith("linux"):
        prepare_linux()
    elif sys.platform == "darwin":
        os.makedirs(CONFIG, exist_ok=True)
        for ini in ("global.ini", "user.ini"):          # skip the first-run wizard (keeps other settings)
            path = os.path.join(CONFIG, ini)
            if not os.path.exists(path):
                with open(path, "w") as f:
                    f.write("[General]\nFirstRun=false\nConfirmOnExit=false\n")
    write_collection()
    write_profile()

    # start publisher
    if demo:
        cmd = [sys.executable, os.path.join(HERE, "..", "cineremote.py"), "--demo", "--quit-after", "26"]
        env = dict(os.environ, QT_QPA_PLATFORM="offscreen")
    else:
        cmd = [sys.executable, os.path.join(HERE, "headless_publish.py"), "--host", host, "--password", password,
               "--seconds", "22"]
        env = None
    pub = subprocess.Popen(cmd, env=env, stdout=open(os.path.join(OUTDIR, "pub.log"), "w"), stderr=subprocess.STDOUT)
    print("publisher started, waiting for frames...")
    time.sleep(7)

    # launch OBS recording
    proc = launch_obs()
    print("OBS recording...")
    time.sleep(11)
    stop_obs(proc)
    pub.wait(timeout=20)

    recs = [f for f in os.listdir(OUTDIR) if f.endswith(".mkv")]
    print("recordings:", recs)
    if not recs:
        print("FAIL: no recording produced")
        return 1
    rec = os.path.join(OUTDIR, max(recs, key=lambda f: os.path.getsize(os.path.join(OUTDIR, f))))
    print(f"recording {rec} ({os.path.getsize(rec)} bytes)")
    # decode a frame a few seconds in with PyAV and analyze it
    import av
    import numpy as np
    frame = os.path.join(OUTDIR, "obs_frame.png")
    nframes, saved_std, dims = 0, 0.0, (0, 0)
    container = av.open(rec)
    for i, fr in enumerate(container.decode(video=0)):
        nframes += 1
        if i == 0 or nframes == 90:  # ~first and ~3s in @30fps
            arr = np.ascontiguousarray(fr.to_ndarray(format="rgb24"))
            saved_std = float(arr.std())
            dims = (fr.width, fr.height)
            from PySide6.QtGui import QImage
            QImage(arr.data, fr.width, fr.height, arr.strides[0], QImage.Format_RGB888).save(frame)
        if nframes >= 90:
            break
    container.close()
    print(f"OBS recording: {nframes}+ frames decoded, {dims[0]}x{dims[1]}, pixel std={saved_std:.1f} -> {frame}")
    ok = nframes >= 30 and saved_std > 5
    print("PASS" if ok else "FAIL (no/blank frames)")
    return 0 if ok else 1


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--password")
    ap.add_argument("--host")
    ap.add_argument("--demo", action="store_true", help="publish the app's demo cameras instead of a phone")
    a = ap.parse_args()
    if not a.demo and not (a.host and a.password):
        ap.error("--host and --password are required (or use --demo)")
    sys.exit(main(a.password, a.host, a.demo))
