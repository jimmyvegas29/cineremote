"""Update check UI: a background check against GitHub Releases and the dialog that installs one."""
import threading
import time

from PySide6.QtCore import QObject, QSettings, Qt, QUrl, Signal
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (QCheckBox, QDialog, QHBoxLayout, QLabel, QMessageBox, QProgressBar, QPushButton,
                               QTextBrowser, QVBoxLayout)

from .. import __version__
from .. import updater

APP_NAME = "CineRemote"
CHECK_EVERY = 12 * 3600


class UpdateChecker(QObject):
    """Asks GitHub for the latest release on a worker thread. `result` carries the info dict, or None
    with an error message when the check failed."""
    result = Signal(object, str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.settings = QSettings("CineRemote", "CineRemote")
        self.latest = None

    @property
    def auto(self):
        return self.settings.value("updates/auto", True, bool)

    def set_auto(self, on):
        self.settings.setValue("updates/auto", bool(on))

    def check_if_due(self):
        if self.auto and time.time() - float(self.settings.value("updates/last_check", 0) or 0) > CHECK_EVERY:
            self.check()

    def check(self):
        def run():
            try:
                info = updater.check()
                self.settings.setValue("updates/last_check", time.time())
                self.latest = info
                self.result.emit(info, "")
            except Exception as e:
                self.result.emit(None, str(e))
        threading.Thread(target=run, name="update-check", daemon=True).start()


class UpdateDialog(QDialog):
    _progress = Signal(int, int)
    _done = Signal(str, str)        # path, error

    def __init__(self, info, checker, parent=None):
        super().__init__(parent)
        self.info, self.checker = info, checker
        self.setWindowTitle(f"{APP_NAME} update")
        self.resize(560, 460)
        lay = QVBoxLayout(self)
        kind = updater.install_kind()
        head = QLabel(f"<h3>{APP_NAME} {info['version']} is available</h3>"
                      f"You have {__version__}.")
        lay.addWidget(head)
        notes = QTextBrowser()
        notes.setOpenExternalLinks(True)
        notes.setMarkdown(info.get("notes") or "_No release notes._")
        lay.addWidget(notes, 1)
        self.bar = QProgressBar()
        self.bar.hide()
        lay.addWidget(self.bar)
        self.msg = QLabel()
        self.msg.setWordWrap(True)
        lay.addWidget(self.msg)
        if kind == "source":
            self.msg.setText("You're running from source: update with <code>git pull</code> "
                             "(and <code>pip install -r requirements.txt</code>).")
        elif not info.get("asset"):
            self.msg.setText("This release has no download for your platform yet - see the release page.")
        blocked = None
        if kind == "macos":
            try:
                updater.macos_preflight()       # e.g. opened straight from Downloads (App Translocation)
            except updater.UpdateError as e:
                blocked = str(e)
                self.msg.setText(blocked)
        auto = QCheckBox("Check for updates automatically")
        auto.setChecked(checker.auto)
        auto.toggled.connect(checker.set_auto)
        lay.addWidget(auto)
        row = QHBoxLayout()
        page = QPushButton("Release page")
        page.clicked.connect(lambda: QDesktopServices.openUrl(QUrl(info["url"])))
        later = QPushButton("Later")
        later.clicked.connect(self.reject)
        self.install = QPushButton("Install and restart")
        self.install.setDefault(True)
        self.install.setEnabled(kind != "source" and bool(info.get("asset")) and not blocked)
        self.install.clicked.connect(self._start)
        row.addWidget(page)
        row.addStretch(1)
        row.addWidget(later)
        row.addWidget(self.install)
        lay.addLayout(row)
        self._progress.connect(self._on_progress, Qt.QueuedConnection)
        self._done.connect(self._on_done, Qt.QueuedConnection)

    def _start(self):
        self.install.setEnabled(False)
        self.bar.show()
        self.bar.setRange(0, 0)
        self.msg.setText("Downloading…")

        def run():
            try:
                path = updater.download(self.info, lambda d, t: self._progress.emit(d, t))
                self._done.emit(path, "")
            except Exception as e:
                self._done.emit("", str(e))
        threading.Thread(target=run, name="update-download", daemon=True).start()

    def _on_progress(self, done, total):
        if total:
            self.bar.setRange(0, 1000)
            self.bar.setValue(int(done * 1000 / total))
            self.msg.setText(f"Downloading… {done / 1e6:.1f} of {total / 1e6:.1f} MB")

    def _on_done(self, path, err):
        if err:
            self.bar.hide()
            self.install.setEnabled(True)
            self.msg.setText(f"Update failed: {err}")
            return
        self.msg.setText("Installing - CineRemote will restart.")
        try:
            updater.apply(path)
        except Exception as e:
            self.msg.setText(f"Update failed: {e}")
            self.install.setEnabled(True)
            return
        self.accept()
        win = self.parent()
        if win is not None:
            win.close()        # clean camera disconnects, then the installer/swap takes over


def show_check_result(parent, checker, info, err):
    """For a check the user asked for: say what happened."""
    if err:
        QMessageBox.warning(parent, APP_NAME, f"Couldn't check for updates: {err}")
    elif info and info["newer"]:
        UpdateDialog(info, checker, parent).exec()
    else:
        QMessageBox.information(parent, APP_NAME, f"You have the latest version ({__version__}).")
