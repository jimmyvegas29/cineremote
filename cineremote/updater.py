"""Updates from GitHub Releases.

The app asks the GitHub API for the latest release, compares its tag with __version__, and, when it
runs as a packaged build, downloads the matching asset and installs it:

  Windows   CineRemote-<ver>-windows-x64-setup.exe   run silently; the installer closes and relaunches the app
  macOS     CineRemote-<ver>-macos-<arch>.dmg        the .app is copied out and swapped in place after the app quits
  Linux     CineRemote-<ver>-linux-x86_64.AppImage   replaces the running AppImage file, then re-executes it

Running from a source checkout, it only reports the new version (update with `git pull`).
Downloads are verified against the release's SHA256SUMS.txt before anything is replaced.
"""
import hashlib
import json
import os
import platform
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import urllib.request

from . import __version__

REPO = "jimmyvegas29/cineremote"
API = f"https://api.github.com/repos/{REPO}/releases/latest"
RELEASES_URL = f"https://github.com/{REPO}/releases"
UA = f"CineRemote/{__version__} (+https://github.com/{REPO})"


class UpdateError(Exception):
    pass


def parse_version(v):
    """'v1.2.10' -> (1, 2, 10); pre-release suffixes sort before the release ('1.2.0-rc1' < '1.2.0')."""
    m = re.match(r"v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(.*)", v.strip())
    if not m:
        return (0, 0, 0, 0)
    major, minor, patch, rest = m.groups()
    return (int(major), int(minor or 0), int(patch or 0), 0 if rest else 1)


def is_newer(latest, current=__version__):
    return parse_version(latest) > parse_version(current)


def install_kind():
    """How this copy was installed: 'windows', 'macos', 'appimage' or 'source'."""
    if not getattr(sys, "frozen", False):
        return "source"
    if sys.platform == "win32":
        return "windows"
    if sys.platform == "darwin":
        return "macos" if _app_bundle() else "source"
    return "appimage" if os.environ.get("APPIMAGE") else "source"


def _app_bundle():
    """The .app bundle this process runs from (e.g. /Applications/CineRemote.app), else None."""
    p = os.path.abspath(sys.executable)
    while p != os.path.dirname(p):
        if p.endswith(".app"):
            return p
        p = os.path.dirname(p)
    return None


def _untranslocate(path):
    """macOS runs a quarantined app that the Finder didn't move (e.g. opened straight from Downloads) from a
    random read-only copy under .../AppTranslocation/. Updating that copy fails, so ask the Security
    framework for the original location (what Sparkle does). Returns path unchanged if not translocated,
    None if the original can't be determined."""
    if "/AppTranslocation/" not in path:
        return path
    try:
        import ctypes
        import ctypes.util
        cf = ctypes.cdll.LoadLibrary(ctypes.util.find_library("CoreFoundation"))
        sec = ctypes.cdll.LoadLibrary(ctypes.util.find_library("Security"))
        cf.CFURLCreateFromFileSystemRepresentation.restype = ctypes.c_void_p
        cf.CFURLCreateFromFileSystemRepresentation.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_long,
                                                              ctypes.c_bool]
        cf.CFURLGetFileSystemRepresentation.restype = ctypes.c_bool
        cf.CFURLGetFileSystemRepresentation.argtypes = [ctypes.c_void_p, ctypes.c_bool, ctypes.c_char_p, ctypes.c_long]
        cf.CFRelease.argtypes = [ctypes.c_void_p]
        sec.SecTranslocateCreateOriginalPathForURL.restype = ctypes.c_void_p
        sec.SecTranslocateCreateOriginalPathForURL.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
        raw = path.encode()
        url = cf.CFURLCreateFromFileSystemRepresentation(None, raw, len(raw), True)
        orig = sec.SecTranslocateCreateOriginalPathForURL(url, None) if url else None
        if url:
            cf.CFRelease(url)
        if not orig:
            return None
        buf = ctypes.create_string_buffer(4096)
        ok = cf.CFURLGetFileSystemRepresentation(orig, True, buf, len(buf))
        cf.CFRelease(orig)
        return buf.value.decode() if ok else None
    except Exception:
        return None


def macos_install_location():
    """Where the running app is installed - the bundle the update must replace (see _untranslocate)."""
    app = _app_bundle()
    return _untranslocate(app) if app else None


FAILED_MARKER = os.path.join(tempfile.gettempdir(), "cineremote-update-failed.txt")


def take_failed_update_message():
    """Message left by an update that couldn't be installed after the app quit (shown once on next start)."""
    try:
        with open(FAILED_MARKER, encoding="utf-8") as f:
            msg = f.read().strip()
        os.remove(FAILED_MARKER)
        return msg or None
    except OSError:
        return None


def asset_pattern():
    kind = install_kind()
    if kind == "windows":
        return re.compile(r"CineRemote-.*-windows-x64-setup\.exe$")
    if kind == "macos":
        arch = "arm64" if platform.machine() == "arm64" else "x86_64"
        return re.compile(rf"CineRemote-.*-macos-{arch}\.dmg$")
    if kind == "appimage":
        return re.compile(r"CineRemote-.*-linux-x86_64\.AppImage$")
    return None


def _ssl_context():
    """TLS with certifi's CA bundle: the python.org builds (and so the macOS app) ship no root certificates,
    so the system default would fail to verify github.com there."""
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:
        return ssl.create_default_context()


def _get(url, timeout=10):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/vnd.github+json"})
    return urllib.request.urlopen(req, timeout=timeout, context=_ssl_context())


def check(timeout=10):
    """Latest release info: {version, tag, notes, url, asset (dict|None), sums_url, newer}."""
    try:
        with _get(API, timeout) as r:
            rel = json.load(r)
    except Exception as e:
        raise UpdateError(f"could not reach GitHub: {e}") from e
    tag = rel.get("tag_name") or ""
    pat = asset_pattern()
    assets = rel.get("assets") or []
    asset = next((a for a in assets if pat and pat.search(a["name"])), None)
    sums = next((a["browser_download_url"] for a in assets if a["name"] == "SHA256SUMS.txt"), None)
    return {"version": tag.lstrip("v"), "tag": tag, "notes": rel.get("body") or "",
            "url": rel.get("html_url") or RELEASES_URL, "asset": asset, "sums_url": sums,
            "newer": bool(tag) and is_newer(tag)}


def download(info, progress=None):
    """Download the release asset into a temp dir and verify its SHA-256. Returns the file path."""
    a = info.get("asset")
    if not a:
        raise UpdateError("this release has no download for this platform")
    expected = None
    if info.get("sums_url"):
        with _get(info["sums_url"]) as r:
            for line in r.read().decode("utf-8", "replace").splitlines():
                parts = line.split()
                if len(parts) == 2 and parts[1].lstrip("*") == a["name"]:
                    expected = parts[0].lower()
    if expected is None:
        raise UpdateError("the release has no checksum for this download")
    d = tempfile.mkdtemp(prefix="cineremote-update-")
    path = os.path.join(d, a["name"])
    h = hashlib.sha256()
    total = a.get("size") or 0
    done = 0
    with _get(a["browser_download_url"], timeout=30) as r, open(path, "wb") as f:
        while True:
            chunk = r.read(1 << 16)
            if not chunk:
                break
            f.write(chunk)
            h.update(chunk)
            done += len(chunk)
            if progress:
                progress(done, total)
    if h.hexdigest() != expected:
        shutil.rmtree(d, ignore_errors=True)
        raise UpdateError("download is corrupt (checksum mismatch)")
    return path


def apply(path, relaunch=True):
    """Start installing the downloaded update. The caller quits the app right after this returns."""
    kind = install_kind()
    if kind == "windows":
        subprocess.Popen(windows_installer_command(path, os.getpid(), relaunch), close_fds=True,
                         creationflags=0x08000000)       # CREATE_NO_WINDOW
    elif kind == "macos":
        _apply_macos(path, relaunch)
    elif kind == "appimage":
        _apply_appimage(path, relaunch)
    else:
        raise UpdateError("running from source: update with git pull")


def windows_installer_command(path, pid, relaunch=True):
    """Hidden PowerShell that waits for the app (pid) to exit - its clean camera disconnects included - and
    only then starts the Inno Setup installer: no wizard pages, relaunch when done (see cineremote.iss).
    Starting setup while the app is still closing made it fail to replace the files and roll back."""
    args = ["/SILENT", "/SP-", "/NOCANCEL", "/CLOSEAPPLICATIONS"]
    args += ["/relaunch=1"] if relaunch else ["/VERYSILENT", "/SUPPRESSMSGBOXES"]
    exe = path.replace("'", "''")
    ps = (f"Wait-Process -Id {int(pid)} -Timeout 60 -ErrorAction SilentlyContinue; "
          f"Start-Process -FilePath '{exe}' -ArgumentList '{' '.join(args)}'")
    return ["powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", ps]


MOVE_TO_APPLICATIONS = ("Move CineRemote.app into your Applications folder (drag it there in the Finder), open it "
                        "from there and update again.")


def macos_preflight():
    """Raise UpdateError, before the app quits, if the update couldn't replace the installed bundle."""
    app = macos_install_location()
    if app is None or "/AppTranslocation/" in app:
        raise UpdateError("macOS is running CineRemote from a temporary read-only copy, so it can't be updated "
                          "in place. " + MOVE_TO_APPLICATIONS)
    parent = os.path.dirname(app)
    if not os.access(parent, os.W_OK) or not os.access(app, os.W_OK):
        raise UpdateError(f"No permission to replace CineRemote in {parent}. " + MOVE_TO_APPLICATIONS)
    return app


def _apply_macos(path, relaunch=True):
    app = macos_preflight()
    work = os.path.dirname(path)
    stage, mnt = os.path.join(work, "stage"), os.path.join(work, "mnt")
    os.makedirs(stage, exist_ok=True)
    # The release .dmg: mount it privately, copy the app out (ditto keeps permissions, symlinks and the
    # signature), unmount.
    subprocess.run(["hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mnt, path],
                   check=True, stdout=subprocess.DEVNULL)
    try:
        src = next((os.path.join(mnt, n) for n in os.listdir(mnt) if n.endswith(".app")), None)
        if not src:
            raise UpdateError("the download has no .app inside")
        new = os.path.join(stage, os.path.basename(src))
        subprocess.run(["ditto", src, new], check=True)
    finally:
        subprocess.run(["hdiutil", "detach", "-quiet", mnt], check=False)
    # Swap after this process exits, then start the (new, or on failure the old) copy. A failed swap
    # leaves a note the app shows on its next start instead of failing silently.
    fail = f"The update couldn't replace {app}. " + MOVE_TO_APPLICATIONS
    script = (f'while kill -0 {os.getpid()} 2>/dev/null; do sleep 0.3; done\n'
              f'rm -rf "{app}.old"\n'
              f'if mv "{app}" "{app}.old" && mv "{new}" "{app}"; then\n'
              f'  rm -rf "{app}.old"; xattr -dr com.apple.quarantine "{app}" 2>/dev/null\n'
              f'else\n'
              f'  [ -e "{app}" ] || mv "{app}.old" "{app}"\n'
              f'  printf "%s" "{fail}" > "{FAILED_MARKER}"\n'
              f'fi\n'
              + (f'open "{app}"\n' if relaunch else ""))
    subprocess.Popen(["/bin/sh", "-c", script], start_new_session=True, close_fds=True)


def _apply_appimage(path, relaunch=True):
    target = os.environ["APPIMAGE"]
    tmp = target + ".new"
    shutil.copyfile(path, tmp)
    os.chmod(tmp, 0o755)
    os.replace(tmp, target)       # the running image stays mapped; the file on disk is the new one
    if not relaunch:
        return
    script = f'while kill -0 {os.getpid()} 2>/dev/null; do sleep 0.3; done\nexec "{target}"\n'
    subprocess.Popen(["/bin/sh", "-c", script], start_new_session=True, close_fds=True)


def update_now(log=print):
    """Headless update (`CineRemote --update-now`): install the latest release if it's newer, no relaunch.
    Returns a process exit code: 0 updated or already current, 1 failed."""
    try:
        info = check()
        if not info["newer"]:
            log(f"up to date ({__version__}; latest {info['version'] or 'none'})")
            return 0
        log(f"updating {__version__} -> {info['version']}")
        apply(download(info), relaunch=False)
        log("update started")
        return 0
    except Exception as e:
        log(f"update failed: {e}")
        return 1
