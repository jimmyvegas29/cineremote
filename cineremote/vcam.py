"""Virtual webcam output (one webcam per connected phone), per platform:

  Windows  Media Foundation virtual camera (vcam_win.py + vcam/ C++ source), named after the phone
  Linux    v4l2loopback devices (vcam_v4l2.py)
  macOS    none of our own: a system camera extension must be Apple-signed. Use OBS's built-in
           Virtual Camera instead (add the CineRemote Camera source, Start Virtual Camera).

Every backend exposes: is_installed(), VirtualCameraManager (attach/detach/push/remove_all/close),
SETUP_HINT, and PLATFORM.
"""
import sys

if sys.platform == "win32":
    from .vcam_win import HEIGHT, WIDTH, VirtualCameraManager, is_installed  # noqa: F401
    PLATFORM = "windows"
    SETUP_HINT = "Click Install virtual webcam (one-time, needs administrator approval)."
elif sys.platform.startswith("linux"):
    from .vcam_v4l2 import HEIGHT, SETUP_COMMAND, SETUP_HINT, WIDTH, VirtualCameraManager, is_installed  # noqa: F401
    PLATFORM = "linux"
else:
    PLATFORM = "macos"
    WIDTH, HEIGHT = 1920, 1080
    SETUP_HINT = ("macOS only allows Apple-signed camera extensions, so this app doesn't add its own webcam. "
                  "In OBS, add the CineRemote Camera source and click Start Virtual Camera: every app "
                  "on the Mac then sees it as \"OBS Virtual Camera\".")

    def is_installed():
        return False

    class VirtualCameraManager:
        def __init__(self):
            raise RuntimeError("virtual webcam isn't available on macOS - use OBS Virtual Camera")


if __name__ == "__main__":
    if "--remove-all" in sys.argv and PLATFORM == "windows":
        m = VirtualCameraManager()
        n = len(m.registry)
        m.remove_all()
        m.close()
        print(f"removed {n} virtual camera(s)")
    else:
        print("usage (Windows): python -m cineremote.vcam --remove-all")
