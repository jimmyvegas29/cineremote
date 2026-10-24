# PyInstaller spec for CineRemote (Windows, macOS, Linux). Build from the repository root:
#     pyinstaller packaging/cineremote.spec --noconfirm
# Windows: build vcam\build.bat first so the virtual webcam binaries get bundled.
import os
import re
import sys

from PyInstaller.utils.hooks import collect_submodules

ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))
VERSION = re.search(r'__version__ = "([^"]+)"', open(os.path.join(ROOT, "cineremote", "__init__.py")).read()).group(1)
WIN, MAC = sys.platform == "win32", sys.platform == "darwin"


def p(*parts):
    return os.path.join(ROOT, *parts)


datas = [
    (p("cineremote", "proto", "schema.pb"), "cineremote/proto"),
    (p("cineremote", "ui", "fonts"), "cineremote/ui/fonts"),
    (p("cineremote", "ui", "icon.png"), "cineremote/ui"),
    (p("cineremote", "demo_props.json"), "cineremote"),
    (p("assets", "demo"), "cineremote/assets/demo"),
]
binaries = []
if WIN:
    vc = p("vcam", "out")
    if os.path.exists(os.path.join(vc, "cineremote-vcam.dll")):
        # host next to the app (it runs as the user); DLL + install script for "Install virtual webcam..."
        binaries += [(os.path.join(vc, "cineremote-vcam-host.exe"), "."),
                     (os.path.join(vc, "cineremote-vcam.dll"), "vcam/out"),
                     (os.path.join(vc, "cineremote-vcam-host.exe"), "vcam/out")]
        datas += [(p("vcam", "install_vcam.bat"), "vcam"), (p("vcam", "uninstall_vcam.bat"), "vcam")]
    else:
        print("WARNING: vcam/out not built - the virtual webcam won't be bundled")

hidden = collect_submodules("zeroconf") + collect_submodules("aiortc") + ["keyring.backends", "certifi"]
hidden += collect_submodules("keyring.backends")

# Qt modules the app never imports (keeps the bundle smaller)
excludes = ["tkinter", "PySide6.QtWebEngineCore", "PySide6.QtWebEngineWidgets", "PySide6.QtQml", "PySide6.QtQuick",
            "PySide6.Qt3DCore", "PySide6.QtCharts", "PySide6.QtDataVisualization", "PySide6.QtPdf",
            "PySide6.QtDesigner", "PySide6.QtBluetooth", "PySide6.QtPositioning", "PySide6.QtSql",
            "PySide6.QtTest", "PySide6.QtSerialPort", "PySide6.QtSensors", "PySide6.QtNetworkAuth"]

a = Analysis([p("cineremote.py")], pathex=[ROOT], binaries=binaries, datas=datas, hiddenimports=hidden,
             excludes=excludes, noarchive=False)


def _unused(dest):
    """Qt pieces pulled in by plugins the app doesn't need: software OpenGL, QML/Quick (only the virtual
    keyboard plugin wants them), PDF (only the qpdf image plugin) and Qt's translations. ~45 MB."""
    d = dest.replace("\\", "/").lower()
    name = d.rsplit("/", 1)[-1]
    return (name.startswith(("opengl32sw", "qt6quick", "qt6qml", "qt6pdf", "qt6virtualkeyboard", "libqt6quick",
                             "libqt6qml", "libqt6pdf", "libqt6virtualkeyboard", "qpdf", "libqpdf",
                             "qtvirtualkeyboard", "libqtvirtualkeyboard"))
            or "/translations/" in d or "qtquick" in name)


a.binaries = [b for b in a.binaries if not _unused(b[0])]
a.datas = [b for b in a.datas if not _unused(b[0])]
pyz = PYZ(a.pure)
icon = p("assets", "cineremote.ico") if WIN else p("assets", "cineremote.icns") if MAC else None
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="CineRemote", console=False, icon=icon,
          upx=False, codesign_identity=None, entitlements_file=None)
coll = COLLECT(exe, a.binaries, a.datas, name="CineRemote", upx=False)

if MAC:
    app = BUNDLE(coll, name="CineRemote.app", icon=icon, bundle_identifier="com.cineremote.app",
                 version=VERSION, info_plist={
                     "CFBundleName": "CineRemote",
                     "CFBundleDisplayName": "CineRemote",
                     "CFBundleShortVersionString": VERSION,
                     "CFBundleVersion": VERSION,
                     "LSMinimumSystemVersion": "12.0",
                     "NSHighResolutionCapable": True,
                     # macOS asks the user before an app may talk to devices on the LAN; these name why
                     # and which Bonjour service it browses (the phones advertise _bmd-cam-control._tcp).
                     "NSLocalNetworkUsageDescription":
                         "CineRemote finds and controls phones running the Blackmagic Camera app on your network.",
                     "NSBonjourServices": ["_bmd-cam-control._tcp"],
                 })
