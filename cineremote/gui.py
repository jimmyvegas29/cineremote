"""CineRemote - main window.

Two views of the same cameras:
  Studio view (default)  camera cards, monitor with PC-side tools, control deck      (ui/studio.py)
  Camera view            the Blackmagic Camera app's own layout, one camera at a time (ui/camera_view.py)
Both read the same parameter model (ui/model.py) and send through one Controller.
"""
import logging
import os
import subprocess
import sys
import tempfile

import keyring
from PySide6.QtCore import QPoint, QSettings, Qt, QTimer
from PySide6.QtGui import QColor, QIcon, QKeySequence, QPainter, QPainterPath, QPixmap, QShortcut
from PySide6.QtWidgets import (QApplication, QDialog, QHBoxLayout, QInputDialog, QMainWindow, QMenu, QMessageBox,
                               QStackedWidget, QStatusBar, QVBoxLayout, QWidget)

from . import __version__
from . import proto_msgs as pm
from . import updater
from . import vcam as vcam_mod
from .backend import Backend
from .ui import model as M
from .ui import theme as T
from .ui.audio import AudioMonitor, AudioPopover, restore_audio_settings
from .ui.camera_view import CameraView
from .ui.dialogs import PasswordDialog, SettingsDialog, SlateDialog
from .ui.studio import StudioView
from .ui.update import UpdateChecker, UpdateDialog, show_check_result
from .ui.widgets import IconButton, Segmented

APP_NAME = "CineRemote"
KEYRING_SERVICE = "cineremote"

log = logging.getLogger("cineremote.gui")


def make_icon():
    for p in (os.path.join(os.path.dirname(os.path.abspath(__file__)), "ui", "icon.png"),
              os.path.join(getattr(sys, "_MEIPASS", ""), "cineremote", "ui", "icon.png")):
        if os.path.exists(p):
            return QIcon(p)
    pm_ = QPixmap(256, 256)
    pm_.fill(Qt.transparent)
    p = QPainter(pm_)
    p.setRenderHint(QPainter.Antialiasing)
    p.setBrush(QColor("#1d1f24"))
    p.setPen(Qt.NoPen)
    p.drawRoundedRect(8, 8, 240, 240, 48, 48)
    p.setBrush(QColor("#f2f2f2"))
    p.drawRoundedRect(44, 78, 128, 100, 18, 18)
    path = QPainterPath()
    path.moveTo(176, 112)
    path.lineTo(220, 86)
    path.lineTo(220, 170)
    path.lineTo(176, 144)
    path.closeSubpath()
    p.drawPath(path)
    p.setBrush(T.REC)
    p.drawEllipse(QPoint(108, 128), 26, 26)
    p.end()
    return QIcon(pm_)


class TopBar(QWidget):
    def __init__(self, win, parent=None):
        super().__init__(parent)
        self.setFixedHeight(50)
        self.setObjectName("topbar")
        self.setAttribute(Qt.WA_StyledBackground, True)
        self.setStyleSheet(f"QWidget#topbar {{ background: {T.BG1.name()}; border-bottom: 1px solid #1b1e23; }}")
        l = QHBoxLayout(self)
        l.setContentsMargins(10, 0, 10, 0)
        l.setSpacing(4)
        self.add = IconButton(T.IC_ADD, "Add camera", tooltip="Add a camera by IP address")
        self.view = Segmented(["Studio", "Camera"])
        self.view.setToolTip("Studio view (Ctrl+1) · Camera view, laid out like the phone app (Ctrl+2)")
        self.multi = IconButton(T.IC_MULTI, "Multiview", checkable=True, tooltip="See every connected camera (Ctrl+M)")
        self.rec_all = IconButton(T.IC_RECORD, "Record all", kind="rec", tooltip="Start recording on every connected "
                                  "phone (arm cameras with the red ring on their cards to record only those)")
        self.stop_all = IconButton(T.IC_STOP, "Stop all", tooltip="Stop recording on every phone")
        self.settings = IconButton(T.IC_SETTINGS, "Camera settings", tooltip="The phone's own settings menu")
        self.slate = IconButton(T.IC_TAG, "Slate", tooltip="Reel, scene, take and production details")
        self.help = IconButton(T.IC_HELP, "Help")
        self.update_btn = IconButton(T.IC_DOWNLOAD, "Update available", tooltip="A new CineRemote release is out")
        self.update_btn.hide()
        for w in (self.add, None, self.view, self.multi, None, self.rec_all, self.stop_all, None, self.settings, self.slate):
            if w is None:
                sep = QWidget()
                sep.setFixedSize(1, 22)
                sep.setStyleSheet("background: rgba(255,255,255,0.08);")
                l.addSpacing(6)
                l.addWidget(sep)
                l.addSpacing(6)
            else:
                l.addWidget(w)
        l.addStretch(1)
        l.addWidget(self.update_btn)
        l.addWidget(self.help)
        self.add.clicked.connect(win.add_by_ip)
        self.view.changed.connect(lambda i: win.set_view("camera" if i else "studio"))
        self.multi.toggled.connect(win.set_multiview)
        self.rec_all.clicked.connect(lambda: win.record_all(True))
        self.stop_all.clicked.connect(lambda: win.record_all(False))
        self.settings.clicked.connect(win.open_settings)
        self.slate.clicked.connect(win.open_slate)
        self.help.clicked.connect(win.show_help)
        self.update_btn.clicked.connect(win.show_update)


class MainWindow(QMainWindow):
    def __init__(self, backend):
        super().__init__()
        self.backend = backend
        self.settings = QSettings("CineRemote", "CineRemote")
        self.setWindowTitle(APP_NAME)
        self.setWindowIcon(make_icon())
        self.resize(1600, 1000)
        self.current = None
        self.ctl = M.Controller(backend, self)
        self.audio_monitor = AudioMonitor(backend, self)
        self.audio_pop = AudioPopover(backend, self.audio_monitor, self)
        self.audio_pop.hide()
        self._known = set()

        central = QWidget()
        cl = QVBoxLayout(central)
        cl.setContentsMargins(0, 0, 0, 0)
        cl.setSpacing(0)
        self.top = TopBar(self)
        cl.addWidget(self.top)
        self.stack = QStackedWidget()
        self.studio = StudioView(self)
        self.camera = CameraView(self)
        self.stack.addWidget(self.studio)
        self.stack.addWidget(self.camera)
        cl.addWidget(self.stack, 1)
        self.setCentralWidget(central)
        self.audio_pop.panel.peak_reset = self._reset_peaks

        self.status = QStatusBar()
        self.setStatusBar(self.status)
        self.status.showMessage("Searching for phones running Blackmagic Camera…")

        backend.cameraListChanged.connect(self.refresh_list)
        backend.sessionState.connect(self.on_state)
        backend.propertyChanged.connect(self.on_property)
        backend.error.connect(self.on_error)
        backend.eventReceived.connect(self.on_event)
        self._refresh_pending = False

        for entry in self.settings.value("manual_hosts", [], list) or []:
            host, _, port = str(entry).partition(":")
            if host:
                backend.add_manual(host, int(port) if port.isdigit() else 2222)
        self._stats_timer = QTimer(self)
        self._stats_timer.timeout.connect(self._update_status)
        self._stats_timer.start(1000)
        self._tc_timer = QTimer(self)            # the recording counter is computed here, not sent by the phone:
        self._tc_timer.timeout.connect(self._tick_timecode)   # repaint it often enough for the frames field
        self._tc_timer.start(100)

        self.updates = UpdateChecker(self)
        self.updates.result.connect(self._on_update_check)
        self._manual_update_check = False
        QTimer.singleShot(5000, self.updates.check_if_due)
        QTimer.singleShot(1500, self._report_failed_update)

        QShortcut(QKeySequence("Ctrl+1"), self, lambda: self.set_view("studio"))
        QShortcut(QKeySequence("Ctrl+2"), self, lambda: self.set_view("camera"))
        QShortcut(QKeySequence("Ctrl+M"), self, lambda: self.top.multi.isVisible() and self.top.multi.click())
        QShortcut(QKeySequence("F11"), self, self._toggle_fullscreen)
        self.set_view(self.settings.value("view", "studio"))

    # ------------------------------------------------------------------ views
    def set_view(self, name):
        cam = name == "camera"
        self.stack.setCurrentWidget(self.camera if cam else self.studio)
        self.top.view.set_index(1 if cam else 0)
        self.top.multi.setVisible(not cam)
        self.settings.setValue("view", "camera" if cam else "studio")
        self._refresh_views()

    def set_multiview(self, on):
        self.studio.set_multiview(on, self._multiview_keys())

    def refresh_multiview(self):
        if self.top.multi.isChecked():
            self.studio.multi.rebuild(self._multiview_keys())

    def _toggle_fullscreen(self):
        self.showNormal() if self.isFullScreen() else self.showFullScreen()

    def _live_keys(self):
        return [k for k, h in self._visible_cams() if h.session or h.want_connected]

    def _multiview_keys(self):
        """Live cameras whose card has the multiview (grid) icon on."""
        return [k for k in self._live_keys() if self.studio.sidebar.in_multiview(k)]

    # ------------------------------------------------------------------ camera list
    def _visible_cams(self):
        """One entry per physical camera: a saved manual IP and the mDNS entry for the same phone share
        host:port, so keep the one in use (or the discovered one). Live cameras first."""
        best = {}
        for key, h in list(self.backend.cams.items()):
            rank = (h.session is not None or h.want_connected, h.discovered, not key.startswith("manual-"))
            addr = (h.host, h.port)
            if addr not in best or rank > best[addr][0]:
                best[addr] = (rank, key, h)
        items = [(key, h) for _, key, h in best.values()]
        # live first, then by the phone's slate letter (A, B, C...), then name
        items.sort(key=lambda kv: (not (kv[1].session or kv[1].want_connected), not kv[1].discovered,
                                   M.slate_letter(kv[1]).replace("•", "~"), kv[1].name.casefold()))
        return items

    def refresh_list(self):
        items = self._visible_cams()
        for k, _ in items:
            if k not in self._known:
                self._known.add(k)
                restore_audio_settings(self.backend, k)
        self.studio.sidebar.rebuild(items)
        self.update_record_button()
        self.camera.set_keys([k for k, _ in items])
        self.refresh_multiview()
        if self.current is None and items:
            self.select_key(items[0][0])

    def select_key(self, key):
        self.current = key
        self.ctl.key = key
        self.studio.set_current(key)
        self.camera.set_current(key)
        if self.audio_pop.isVisible():
            self.audio_pop.panel.bind(key)
        self.camera.rail.update()

    def camera_menu(self, key, global_pos):
        h = self.backend.cams.get(key)
        if not h:
            return
        m = QMenu(self)
        m.addAction("Disconnect" if h.want_connected else "Connect", lambda: self.toggle_connect(key))
        m.addAction("Identify (flash the phone)", lambda: self.backend.call(key, "identify"))
        m.addAction("Forget saved password", lambda: self._forget_pw(h))
        if key.startswith("manual-"):
            m.addAction("Remove", lambda: self._remove_manual(key))
        m.exec(global_pos)

    def _forget_pw(self, h):
        try:
            keyring.delete_password(KEYRING_SERVICE, h.key)
        except Exception:
            pass
        h.password = None

    def _remove_manual(self, key):
        h = self.backend.cams[key]
        hosts = [x for x in (self.settings.value("manual_hosts", [], list) or [])
                 if str(x).partition(":")[0] != h.host]
        self.settings.setValue("manual_hosts", hosts)
        self.backend.remove(key)

    def toggle_connect(self, key):
        h = self.backend.cams.get(key)
        if not h:
            return
        if h.want_connected:
            self.backend.disconnect_camera(key)
        else:
            pw = h.password
            if pw is None:
                try:
                    pw = keyring.get_password(KEYRING_SERVICE, h.key) or \
                        keyring.get_password(KEYRING_SERVICE, h.name)  # entries saved by older builds
                except Exception:
                    pw = None
            if pw is None:
                dlg = PasswordDialog(h.name, self)
                if dlg.exec() != QDialog.Accepted or not dlg.pw.text():
                    return
                pw = dlg.pw.text()
                if dlg.remember.isChecked():
                    try:
                        keyring.set_password(KEYRING_SERVICE, h.key, pw)
                    except Exception:
                        pass
            self.backend.connect_camera(key, pw)
        self.select_key(key)
        self.refresh_list()

    def add_by_ip(self):
        ip, ok = QInputDialog.getText(self, "Add camera", "Phone IP address (port 2222, or IP:port):")
        if not ok or not ip.strip():
            return
        host, _, p = ip.strip().partition(":")
        if not host or (p and not p.isdigit()):
            QMessageBox.warning(self, APP_NAME, f"'{ip.strip()}' isn't an IP address or IP:port.")
            return
        port = int(p) if p else 2222
        entry = host if port == 2222 else f"{host}:{port}"
        hosts = self.settings.value("manual_hosts", [], list) or []
        if entry not in hosts:
            hosts.append(entry)
            self.settings.setValue("manual_hosts", hosts)
        self.select_key(self.backend.add_manual(host, port))

    # ------------------------------------------------------------------ backend events
    def on_property(self, key, field, value):
        if key == self.current and not self._refresh_pending:
            self._refresh_pending = True
            QTimer.singleShot(40, self._refresh_views)     # coalesce bursts of property updates

    def _refresh_views(self):
        self._refresh_pending = False
        if self.stack.currentWidget() is self.camera:
            self.camera.refresh()
        else:
            self.studio.deck.refresh()

    def on_state(self, key, state, detail):
        h = self.backend.cams.get(key)
        if h is None:
            return
        if key == self.current:
            self.studio.sidebar.set_current(key)
            self._refresh_views()
        self.status.showMessage(f"{h.name}: {state} {detail}", 6000)
        if state in ("connected", "previewing", "disconnected", "reconnecting"):
            self.refresh_list()

    def on_error(self, key, msg):
        h = self.backend.cams.get(key)
        name = h.name if h else key
        self.status.showMessage(f"{name}: {msg}", 10000)
        if "wrong password" in msg and h:
            self._forget_pw(h)
            QMessageBox.warning(self, APP_NAME, f"{name}: wrong Remote Camera password.")
        elif "password required" in msg:
            self.toggle_connect(key)

    def on_event(self, key, kind, m):
        h = self.backend.cams.get(key)
        if kind == "vcam":
            self.status.showMessage(f"{h.name if h else key}: virtual {m}", 8000)
        elif kind == "error_event":
            et = pm.enum_name("ErrorEventType", m.error_type).replace("ERROR_EVENT_TYPE_", "").replace("_", " ").title()
            self.status.showMessage(f"{h.name if h else key}: camera error - {et}", 15000)

    def _tick_timecode(self):
        h = self.ctl.h
        if h is not None and M.recording(h):
            t = M.timecode(h)
            self.studio.deck.tc.setText(t)
            self.camera.tc.setText(t)

    def _update_status(self):
        parts = []
        for h in list(self.backend.cams.values()):
            sess, pub = h.session, h.publisher
            if sess is None or h.state != "previewing":
                continue
            parts.append(f"{M.short_name(h)} ({M.slate_letter(h)}): {sess.stats['fps']:.0f} fps"
                         + (f" → OBS slot {pub.slot}" if pub else ""))
        if parts:
            self.status.showMessage("   |   ".join(parts))
        if self.stack.currentWidget() is self.studio:
            self.studio.deck.refresh()       # timecode / media / battery tick even without property bursts

    # ------------------------------------------------------------------ actions
    def tap_focus(self, x, y):
        h = self.ctl.h
        if not h or not h.session:
            return
        o = h.props.get("cam_orientation")
        if o is not None and o.orientation in (3, 4):
            # In landscape the app takes the point a quarter-turn from its picture, (1 - y, x), in both landscape
            # directions (the stream stays upright either way up). Calibrated on Blackmagic Camera 3.5 on a
            # Pixel 10 (normal + flipped landscape) and an iPhone 16 (landscape).
            px, py = 1.0 - y, x
        # the phone's point-of-interest space is sensor (landscape) oriented
        elif o is None or o.orientation == 1:
            px, py = y, 1.0 - x
        elif o.orientation == 2:
            px, py = 1.0 - y, x
        else:
            px, py = x, y
        self.ctl.send("tap_focus", px, py)
        self.status.showMessage(f"Focus/exposure point set ({px:.2f}, {py:.2f})", 3000)

    def toggle_record(self):
        h = self.ctl.h
        if h and h.session:
            self.ctl.send("stop_recording" if M.recording(h) else "start_recording")

    def record_targets(self):
        """Connected cameras the Record button starts: the armed ones, or all when none is armed."""
        live = [(k, h) for k, h in self._visible_cams() if h.session]
        armed = [(k, h) for k, h in live if h.armed]
        return armed or live, bool(armed)

    def update_record_button(self):
        targets, armed = self.record_targets()
        self.top.rec_all.set_label(f"Record armed ({len(targets)})" if armed else "Record all")

    def record_all(self, start):
        """Start: the armed cameras (all, if none is armed). Stop: every camera that's recording."""
        cams = self.record_targets()[0] if start else [(k, h) for k, h in self.backend.cams.items() if h.session]
        for k, h in cams:
            self.backend.call(k, "start_recording" if start else "stop_recording")

    def open_audio(self, anchor, where):
        if not self.current:
            return
        if not self.audio_pop.isVisible():
            self.audio_pop.panel.bind(self.current)
        self.audio_pop.toggle_near(anchor, where)

    def _reset_peaks(self):
        self.studio.reset_peaks()
        for ph in self.camera.audio.meter.peaks:
            ph.reset()

    def open_settings(self):
        h = self.ctl.h
        if not h or not h.session:
            return
        fut = self.ctl.send("get_settings")
        try:
            st, avail = fut.result(timeout=10)
        except Exception as e:
            QMessageBox.warning(self, APP_NAME, f"Could not read camera settings: {e}")
            return
        if not st.endswith("OKAY"):
            QMessageBox.warning(self, APP_NAME, f"Camera rejected settings edit ({st}).")
            return
        SettingsDialog(self.backend, self.current, avail, self).exec()

    def open_slate(self):
        if self.ctl.h and self.ctl.h.session:
            SlateDialog(self.backend, self.current, self).exec()

    def install_vcam(self):
        if vcam_mod.PLATFORM == "linux":
            return self._install_vcam_linux()
        if vcam_mod.PLATFORM != "windows":
            QMessageBox.information(self, APP_NAME, vcam_mod.SETUP_HINT)
            return
        import ctypes
        here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        bat = next((p for p in (os.path.join(here, "vcam", "install_vcam.bat"),
                                os.path.join(getattr(sys, "_MEIPASS", here), "vcam", "install_vcam.bat"))
                    if os.path.exists(p)), None)
        if not bat:
            QMessageBox.warning(self, APP_NAME, "install_vcam.bat not found next to the app.")
            return
        r = ctypes.windll.shell32.ShellExecuteW(None, "runas", bat, None, os.path.dirname(bat), 0)
        if r <= 32:
            QMessageBox.warning(self, APP_NAME, "Install was cancelled or failed.")
            return
        QTimer.singleShot(4000, lambda: self.studio.sidebar.set_current(self.current))
        self.status.showMessage("Installing virtual webcam…", 5000)

    def _install_vcam_linux(self):
        box = QMessageBox(self)
        box.setWindowTitle(APP_NAME)
        box.setText(vcam_mod.SETUP_HINT)
        run = box.addButton("Load it now", QMessageBox.AcceptRole)
        box.addButton(QMessageBox.Close)
        box.exec()
        if box.clickedButton() is not run:
            return
        # pkexec shows the desktop's own password prompt; this app never sees the password.
        try:
            r = subprocess.run(["pkexec", "sh", "-c", vcam_mod.SETUP_COMMAND], timeout=120)
        except (OSError, subprocess.TimeoutExpired) as e:
            QMessageBox.warning(self, APP_NAME, f"Could not run pkexec: {e}\n\nRun this in a terminal instead:\n"
                                f"sudo {vcam_mod.SETUP_COMMAND}")
            return
        if r.returncode != 0:
            QMessageBox.warning(self, APP_NAME, "Loading v4l2loopback failed or was cancelled. Is the module "
                                "installed (sudo apt install v4l2loopback-dkms)?")
        elif not vcam_mod.is_installed():
            QMessageBox.information(self, APP_NAME, "The webcam devices were created, but this user can't write "
                                    "to them. Add yourself to the video group (sudo usermod -aG video $USER) "
                                    "and log out and back in.")
        self.studio.sidebar.set_current(self.current)

    # ------------------------------------------------------------------ updates
    def _on_update_check(self, info, err):
        self.top.update_btn.setVisible(bool(info and info["newer"]))
        if self._manual_update_check:
            self._manual_update_check = False
            show_check_result(self, self.updates, info, err)

    def check_updates(self):
        self._manual_update_check = True
        self.status.showMessage("Checking for updates…", 4000)
        self.updates.check()

    def _report_failed_update(self):
        msg = updater.take_failed_update_message()
        if msg:
            QMessageBox.warning(self, APP_NAME, msg)

    def show_update(self):
        if self.updates.latest and self.updates.latest["newer"]:
            UpdateDialog(self.updates.latest, self.updates, self).exec()

    def show_help(self):
        box = QMessageBox(self)
        box.setWindowTitle(f"{APP_NAME} {__version__}")
        box.setIcon(QMessageBox.Information)
        upd = box.addButton("Check for updates", QMessageBox.ActionRole)
        box.addButton(QMessageBox.Close)
        box.setText((
            "<b>Set up each phone</b><br>"
            "1. Install Blackmagic Camera (iOS or Android) and join the same Wi-Fi/LAN as this PC.<br>"
            "2. In the app: Settings ▸ Remote Camera Control ▸ enable it, set <i>Use This Phone as</i> = "
            "<b>Remote Camera</b> and choose a password. On iPhone also allow <i>Local Network</i> access.<br>"
            "3. Keep the Blackmagic Camera app open in the foreground.<br><br>"
            "Cameras appear automatically. Double-click one to connect, click the picture to set focus/exposure.<br><br>"
            "<b>Views</b>: Studio (Ctrl+1) and Camera (Ctrl+2, laid out like the phone app). F11 = full screen, "
            "Ctrl+M = multiview.<br><br>"
            "<b>OBS</b>: add the source <i>CineRemote Camera</i> and pick the camera. Video and audio come in "
            "natively. The audio meter sets the level sent to OBS (the phone's mic gain stays on the phone).<br><br>"
            "<b>Virtual webcam</b>: each connected phone also appears as a webcam (1920x1080, 30 fps, video "
            "only). Windows: named after the phone, first use needs <i>Install virtual webcam…</i> (admin). "
            "Linux: v4l2loopback devices <i>CineRemote Camera 1-4</i>. macOS: use OBS's Start Virtual Camera.<br><br>"
            f"<a href='https://github.com/{updater.REPO}'>github.com/{updater.REPO}</a> · MIT license · "
            "not affiliated with Blackmagic Design"))
        box.setTextFormat(Qt.RichText)
        box.exec()
        if box.clickedButton() is upd:
            self.check_updates()

    def closeEvent(self, e):
        self.audio_monitor.stop()
        self.backend.shutdown()
        super().closeEvent(e)


def main():
    import argparse
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("--connect", help="dev/test: connect to HOST[:PORT][,HOST2...] at startup")
    ap.add_argument("--password", help="dev/test: password for --connect")
    ap.add_argument("--screenshot", help="dev/test: save a window screenshot to this file")
    ap.add_argument("--screenshot-after", type=float, default=12)
    ap.add_argument("--quit-after", type=float, default=0)
    ap.add_argument("--multiview", action="store_true")
    ap.add_argument("--view", choices=("studio", "camera"), help="start in this view")
    ap.add_argument("--tools", help="dev/test: comma list of monitor tools to enable (histogram,guides,peaking,"
                                    "zebra,false_color,grid)")
    ap.add_argument("--diag", action="store_true", help="log per-camera latency/fps/loss and loop lag")
    ap.add_argument("--demo", action="store_true", help="simulated cameras fed from stock photos (no phone needed)")
    ap.add_argument("--select", type=int, help="dev/test: select the Nth camera in the list")
    ap.add_argument("--remove-webcams", action="store_true", help="unregister this user's virtual webcams and exit "
                                                                    "(the Windows uninstaller runs this)")
    ap.add_argument("--update-now", action="store_true", help="install the latest GitHub release if newer, then exit")
    args, _ = ap.parse_known_args()
    if args.update_now:
        with open(os.path.join(tempfile.gettempdir(), "cineremote-update.log"), "a", encoding="utf-8") as f:
            def log(m):
                print(m)
                f.write(m + "\n")
                f.flush()
            return updater.update_now(log)
    if args.remove_webcams:
        if vcam_mod.PLATFORM == "windows" and vcam_mod.is_installed():
            m = vcam_mod.VirtualCameraManager()
            m.remove_all()
            m.close()
        return 0
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s",
                        handlers=[logging.FileHandler(os.path.join(tempfile.gettempdir(), "cineremote.log"),
                                                      encoding="utf-8"), logging.StreamHandler()])
    logging.getLogger("aioice").setLevel(logging.WARNING)
    logging.getLogger("aiortc").setLevel(logging.WARNING)
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setWindowIcon(make_icon())
    T.apply(app)
    import socket
    os_label = {"win32": "Windows", "darwin": "macOS"}.get(sys.platform, "Linux")
    backend = Backend(controller_name=f"{socket.gethostname()} ({os_label})")   # shown on the phone
    if args.diag:
        os.environ["CINEREMOTE_DIAG"] = "1"
    from . import diag
    if diag.enabled():
        diag.install(backend.loop)
    win = MainWindow(backend)
    if args.view:
        win.set_view(args.view)
    for tool in (args.tools.split(",") if args.tools else []):
        win.studio.video.set_tool(tool.strip(), True)
    app.aboutToQuit.connect(backend.shutdown)  # also covers logoff/shutdown paths that skip closeEvent
    # SIGTERM (logout, `kill`, service stop) and Ctrl-C would otherwise end Python on the spot, skipping
    # the DisconnectEvent the phone needs - it then refuses the next controller until restarted.
    # Route them through a normal window close; the no-op timer lets Python run signal handlers while
    # Qt's event loop is idle.
    import signal
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: QTimer.singleShot(0, win.close))
    sig_timer = QTimer()
    sig_timer.timeout.connect(lambda: None)
    sig_timer.start(250)
    win.show()
    if args.demo:
        backend.start_demo()
        win.status.showMessage("Demo mode: simulated cameras from stock photos")
    else:
        backend.start_discovery()
    win.refresh_list()
    if args.select is not None:
        QTimer.singleShot(1500, lambda: win.select_key(win._visible_cams()[args.select][0]))
    if args.connect:
        def _dev_connect():
            for target in args.connect.split(","):
                host, _, port = target.strip().partition(":")
                key = next((k for k, h in backend.cams.items() if h.host == host), None) or \
                    backend.add_manual(host, int(port or 2222))
                win.select_key(key)
                backend.connect_camera(key, args.password)
            win.refresh_list()
        QTimer.singleShot(3000, _dev_connect)
    if args.multiview:
        QTimer.singleShot(4000, lambda: win.top.multi.setChecked(True))
    if args.screenshot:
        QTimer.singleShot(int(args.screenshot_after * 1000), lambda: win.grab().save(args.screenshot))
    if args.quit_after:
        QTimer.singleShot(int(args.quit_after * 1000), win.close)
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
