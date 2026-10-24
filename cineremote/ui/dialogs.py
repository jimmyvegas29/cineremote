"""Dialogs: the phone's own settings menu, slate editor, password prompt."""
from PySide6.QtWidgets import (QCheckBox, QComboBox, QDialog, QDialogButtonBox, QFormLayout, QLabel, QLineEdit,
                               QMessageBox, QScrollArea, QSpinBox, QTabWidget, QVBoxLayout, QWidget)

APP_NAME = "CineRemote"


class SettingsDialog(QDialog):
    """Renders the camera's own settings menu (AvailableSettings) and writes changes back."""

    def __init__(self, backend, key, available, parent=None):
        super().__init__(parent)
        self.backend, self.key = backend, key
        self.setWindowTitle("Camera Settings - " + backend.cams[key].name)
        self.resize(560, 640)
        self.widgets = {}
        self.initial = {}
        lay = QVBoxLayout(self)
        tabs = QTabWidget()
        lay.addWidget(tabs)
        for sec in available.sections:
            page = QWidget()
            form = QFormLayout(page)
            for d in sec.settings_descriptions:
                kind = d.WhichOneof("setting")
                if kind == "combo_box":
                    w = QComboBox()
                    for o in d.combo_box.options:
                        w.addItem(o.label, o.key)
                    i = w.findData(d.combo_box.selected_option_key)
                    if i >= 0:
                        w.setCurrentIndex(i)
                    self.initial[d.setting_key] = d.combo_box.selected_option_key
                elif kind == "check_box":
                    w = QCheckBox()
                    w.setChecked(d.check_box.is_set)
                    self.initial[d.setting_key] = d.check_box.is_set
                elif kind == "key_value_label":
                    w = QLabel(d.key_value_label.value_label)
                elif kind == "link_label":
                    w = QLabel(d.link_label.label)
                else:
                    continue
                w.setEnabled(not d.is_disabled)
                if kind in ("combo_box", "check_box"):
                    self.widgets[d.setting_key] = w
                form.addRow(d.label, w)
            scroll = QScrollArea()
            scroll.setWidgetResizable(True)
            scroll.setWidget(page)
            tabs.addTab(scroll, sec.label)
        bb = QDialogButtonBox(QDialogButtonBox.Apply | QDialogButtonBox.Close)
        bb.button(QDialogButtonBox.Apply).clicked.connect(self.apply)
        bb.rejected.connect(self.close_edit)
        lay.addWidget(bb)

    def changes(self):
        out = []
        for k, w in self.widgets.items():
            v = w.currentData() if isinstance(w, QComboBox) else w.isChecked()
            if v != self.initial.get(k):
                out.append((k, v))
        return out

    def apply(self):
        ch = self.changes()
        fut = self.backend.call(self.key, "write_settings", ch, True)
        if fut is None:
            return
        try:
            statuses, msg = fut.result(timeout=10)
        except Exception as e:
            QMessageBox.warning(self, APP_NAME, f"Failed to apply settings: {e}")
            return
        bad = [f"{k}: {s}" for (k, _), s in zip(ch, statuses) if not s.endswith("OKAY")]
        if bad or msg:
            QMessageBox.information(self, APP_NAME, "\n".join(bad + ([msg] if msg else [])) or "Applied")
        for k, v in ch:
            self.initial[k] = v
        self.accept()

    def close_edit(self):
        self.backend.call(self.key, "write_settings", [], True)
        self.reject()


class SlateDialog(QDialog):
    def __init__(self, backend, key, parent=None):
        super().__init__(parent)
        self.backend, self.key = backend, key
        self.setWindowTitle("Slate - " + backend.cams[key].name)
        s = backend.cams[key].props.get("slate")
        form = QFormLayout(self)
        self.reel = QSpinBox(); self.reel.setRange(1, 999); self.reel.setValue(s.reel if s else 1)
        self.scene = QLineEdit(s.scene if s else "1")
        self.take = QSpinBox(); self.take.setRange(1, 999); self.take.setValue(s.take if s else 1)
        self.prod = QLineEdit(s.production_name if s else "")
        self.director = QLineEdit(s.director if s else "")
        self.operator = QLineEdit(s.camera_operator if s else "")
        self.interior = QCheckBox("Interior"); self.interior.setChecked(not s or s.light != 2)
        self.day = QCheckBox("Day"); self.day.setChecked(not s or s.time_of_day != 2)
        self.good = QCheckBox("Last clip was a good take"); self.good.setChecked(bool(s and s.is_good_take_last_clip))
        for label, w in (("Reel", self.reel), ("Scene", self.scene), ("Take", self.take), ("Production", self.prod),
                         ("Director", self.director), ("Camera operator", self.operator), ("", self.interior),
                         ("", self.day), ("", self.good)):
            form.addRow(label, w)
        bb = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        bb.accepted.connect(self.save)
        bb.rejected.connect(self.reject)
        form.addRow(bb)

    def save(self):
        self.backend.call(self.key, "set_slate", reel=self.reel.value(), scene=self.scene.text(),
                          take=self.take.value(), production_name=self.prod.text(), director=self.director.text(),
                          camera_operator=self.operator.text(), light=1 if self.interior.isChecked() else 2,
                          time_of_day=1 if self.day.isChecked() else 2, is_good_take_last_clip=self.good.isChecked())
        self.accept()


class PasswordDialog(QDialog):
    def __init__(self, cam_name, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Remote Camera Password")
        form = QFormLayout(self)
        form.addRow(QLabel(f"Enter the Remote Camera password set on\n<b>{cam_name}</b>\n"
                           "(Blackmagic Camera ▸ Settings ▸ Remote Camera Control)"))
        self.pw = QLineEdit()
        self.pw.setEchoMode(QLineEdit.Password)
        form.addRow("Password", self.pw)
        self.remember = QCheckBox("Remember (Windows Credential Manager)")
        self.remember.setChecked(True)
        form.addRow(self.remember)
        bb = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        form.addRow(bb)
