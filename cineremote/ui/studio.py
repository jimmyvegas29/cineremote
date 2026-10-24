"""Studio view (concept C): camera cards, monitor with PC-side tools, vertical audio meter, control deck."""
from PySide6.QtCore import QRectF, QSettings, QSize, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QFont, QPainter, QPainterPath, QPen
from PySide6.QtWidgets import (QGridLayout, QHBoxLayout, QLabel, QPushButton, QScrollArea, QSizePolicy, QStackedWidget,
                               QVBoxLayout, QWidget)

from .. import vcam as vcam_mod
from . import model as M
from . import theme as T
from .audio import gain_text
from .video import ThumbSource, VideoView
from .widgets import ArmButton, ParamTile, RecordButton, ScrubRuler, Switch, VMeter


# ---------------------------------------------------------------------------- sidebar cards
class CameraCard(QWidget):
    """One camera in the sidebar. The blue ring marks the selected camera; the Live switch turns the
    card's moving thumbnail off (saved per camera) for people who don't want motion in two places.
    The red ring arms the camera for recording, so Record armed rolls a chosen group (also saved)."""
    clicked = Signal(str)
    armChanged = Signal(str, bool)
    doubleClicked = Signal(str)
    menuRequested = Signal(str, object)
    PAD = 10

    def __init__(self, backend, key, parent=None):
        super().__init__(parent)
        self.backend, self.key = backend, key
        self.selected = False
        self.thumb = ThumbSource(256, 144)
        self.settings = QSettings("CineRemote", "CineRemote")
        self.live_preview = str(self.settings.value(f"cards/{key}/live", "true")).lower() == "true"
        self.setCursor(Qt.PointingHandCursor)
        sp = QSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        sp.setHeightForWidth(True)          # thumbnail height follows the card's actual width
        self.setSizePolicy(sp)
        self.sw = Switch(self.live_preview, self)
        self.sw.setFixedSize(30, 17)
        self.sw.setToolTip("Moving picture in this card (off: status only)")
        self.sw.toggled.connect(self._toggle_live)
        self.arm = ArmButton(self)
        self.arm.setToolTip("Arm for recording: when any camera is armed, Record armed starts only those")
        saved = self.settings.value(f"cards/{key}/armed")
        self.arm.setChecked(str(saved).lower() == "true" if saved is not None else bool(self.h and self.h.armed))
        if self.h is not None:
            self.h.armed = self.arm.isChecked()
        self.arm.toggled.connect(self._toggle_arm)

    @property
    def h(self):
        return self.backend.cams.get(self.key)

    def compact(self):
        h = self.h
        return h is None or not (h.session or h.want_connected)

    def _toggle_live(self, on):
        self.live_preview = on
        self.settings.setValue(f"cards/{self.key}/live", on)
        self.updateGeometry()
        self.update()

    def _toggle_arm(self, on):
        if self.h is not None:
            self.h.armed = on
        self.settings.setValue(f"cards/{self.key}/armed", on)
        self.armChanged.emit(self.key, on)
        self.update()

    # -- layout (shared by sizeHint, paint and the switch position)
    def _thumb_h(self, w):
        if self.compact() or not self.live_preview:
            return 0.0
        return (w - 16) * 9 / 16 * (1 if self.selected else 0.75)

    def hasHeightForWidth(self):
        return True

    def heightForWidth(self, w):
        if self.compact():
            return 60
        th = self._thumb_h(w)
        # name row + battery row (+ thumbnail, or + status chip row when the thumbnail is off)
        return int(th + 74) if th else 92

    def sizeHint(self):
        return QSize(250, self.heightForWidth(max(self.width(), 250)))

    def _name_y(self):
        th = self._thumb_h(self.width())
        return 8 + th + 10 if th else self.PAD

    def resizeEvent(self, e):
        self._place_switch()
        super().resizeEvent(e)

    def _place_switch(self):
        show = not self.compact()
        self.sw.setVisible(show)
        self.arm.setVisible(show)
        if show:
            y = int(self._name_y())
            self.sw.move(self.width() - 12 - self.sw.width(), y + 2)
            self.arm.move(self.sw.x() - 8 - self.arm.width(), y - 1)

    def mousePressEvent(self, e):
        if e.button() == Qt.RightButton:
            self.menuRequested.emit(self.key, e.globalPosition().toPoint())
        else:
            self.clicked.emit(self.key)

    def mouseDoubleClickEvent(self, e):
        self.doubleClicked.emit(self.key)

    # -- paint
    def paintEvent(self, e):
        h = self.h
        if h is None:
            return
        self._place_switch()
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        sel = self.selected
        T.rounded(p, r, 12, T.SEL_BG if sel else T.BG2, QPen(QColor(47, 124, 246, 205) if sel else T.LINE, 1))
        if self.compact():
            self._paint_compact(p, r, h)
            return
        rec = M.recording(h)
        live = h.state == "previewing"
        tally = T.REC if rec else (T.OK if live else T.WARN)
        status = ("● REC " + M.timecode(h)[3:8]) if rec else ("LIVE" if live else h.state.upper())
        cf = T.ui_font(10.5, QFont.DemiBold)
        sess = h.session
        fps = f"{sess.stats['fps']:.0f} fps" if sess is not None and sess.stats.get("fps") else ""
        th_h = self._thumb_h(r.width())
        if th_h:
            th = QRectF(8, 8, r.width() - 16, th_h)
            T.rounded(p, th, 8, QColor("#050506"))
            img = self.thumb.image(h)
            p.save()
            clip = QPainterPath()
            clip.addRoundedRect(th, 8, 8)
            p.setClipPath(clip)
            if img is not None:
                sc = max(th.width() / img.width(), th.height() / img.height())   # cover-fit
                w, hh = img.width() * sc, img.height() * sc
                p.drawImage(QRectF(th.center().x() - w / 2, th.center().y() - hh / 2, w, hh), img)
            else:
                p.setPen(T.TEXT3)
                p.setFont(T.ui_font(12))
                p.drawText(th, Qt.AlignCenter, {"connecting": "Connecting…", "reconnecting": "Reconnecting…"}
                           .get(h.state, "Starting…"))
            p.fillRect(QRectF(th.left(), th.top(), 4, th.height()), tally)
            p.restore()
            T.chip(p, th.left() + 8, th.top() + 8, status, font=cf, h=21, fill=T.REC if rec else QColor(0, 0, 0, 140),
                   border=None if rec else T.LINE2, dot=None if rec else tally)
            if fps:
                T.chip(p, th.right() - 8 - (T.text_width(cf, fps) + 18), th.top() + 8, fps, font=cf, h=21)
        else:
            # no thumbnail: tally stripe down the card's left edge, status chip in the body
            p.save()
            clip = QPainterPath()
            clip.addRoundedRect(r, 12, 12)
            p.setClipPath(clip)
            p.fillRect(QRectF(r.left(), r.top(), 4, r.height()), tally)
            p.restore()
        y = self._name_y()
        T.slate_badge(p, 12, y, M.slate_letter(h), 20)
        p.setPen(T.TEXT)
        p.setFont(T.ui_font(13.5, QFont.DemiBold))
        name_right = self.arm.x() - 6 if self.arm.isVisible() else r.width() - 12
        p.drawText(QRectF(39, y, max(20, name_right - 39), 20), Qt.AlignVCenter,
                   p.fontMetrics().elidedText(M.short_name(h), Qt.ElideRight, int(max(20, name_right - 39))))
        y += 28
        if not th_h:
            wchip = T.chip(p, 12, y, status, font=cf, h=21, fill=T.REC if rec else QColor(255, 255, 255, 10),
                           border=None if rec else T.LINE2, dot=None if rec else tally)
            if fps:
                T.chip(p, 12 + wchip + 6, y, fps, font=cf, h=21, fill=QColor(255, 255, 255, 10))
            y += 29
        b = M.battery_pct(h)
        x = 12.0
        p.setFont(T.num_font(11.5))
        if b is not None:
            body = QRectF(x, y + 2, 24, 11)
            T.rounded(p, body, 3, None, QPen(QColor("#9aa3af"), 1.4))
            p.fillRect(QRectF(x + 25, y + 5, 2, 5), QColor("#9aa3af"))
            fillc = T.REC if b < 15 else (T.WARN if b < 30 else T.OK)
            p.fillRect(QRectF(x + 2, y + 4, max(1.0, 20 * b / 100), 7), fillc)
            p.setPen(T.TEXT2)
            p.drawText(QRectF(x + 32, y, 40, 15), Qt.AlignVCenter, f"{b:.0f}%")
        rt, gb = M.media(h)
        if rt:
            p.setPen(T.TEXT2)
            p.drawText(QRectF(0, y, r.width() - 12, 15), Qt.AlignVCenter | Qt.AlignRight, f"{rt} · {gb:.0f} GB")

    def _paint_compact(self, p, r, h):
        """Not connected: name on the first line, what to do on the second (never on one line - a long
        name would run into the hint)."""
        p.setPen(Qt.NoPen)
        p.setBrush(T.TEXT3 if h.discovered else QColor("#4b515c"))
        p.drawEllipse(QRectF(14, 14, 8, 8))
        p.setFont(T.ui_font(13, QFont.DemiBold))
        p.setPen(T.TEXT2 if h.discovered else T.TEXT3)
        w = int(r.width() - 44)
        p.drawText(QRectF(30, 8, w, 20), Qt.AlignVCenter, p.fontMetrics().elidedText(M.short_name(h), Qt.ElideRight, w))
        p.setFont(T.ui_font(11.5))
        p.setPen(T.TEXT3)
        hint = "Available · double-click to connect" if h.discovered else "Offline"
        p.drawText(QRectF(30, 30, w, 18), Qt.AlignVCenter, p.fontMetrics().elidedText(hint, Qt.ElideRight, w))


class Sidebar(QWidget):
    def __init__(self, win, parent=None):
        super().__init__(parent)
        self.win, self.backend = win, win.backend
        self.setFixedWidth(284)
        self.cards = {}
        lay = QVBoxLayout(self)
        lay.setContentsMargins(12, 12, 12, 12)
        lay.setSpacing(8)
        hdr = QHBoxLayout()
        t = QLabel("CAMERAS")
        t.setFont(T.label_font(11))
        t.setStyleSheet(f"color: {T.TEXT2.name()};")
        self.count = QLabel("")
        self.count.setFont(T.label_font(10.5))
        self.count.setStyleSheet(f"color: {T.TEXT3.name()};")
        hdr.addWidget(t)
        hdr.addStretch(1)
        hdr.addWidget(self.count)
        lay.addLayout(hdr)
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        inner = QWidget()
        self.list = QVBoxLayout(inner)
        self.list.setContentsMargins(0, 0, 4, 0)
        self.list.setSpacing(10)
        self.list.addStretch(1)
        self.scroll.setWidget(inner)
        lay.addWidget(self.scroll, 1)
        self.empty = QLabel("Phones running Blackmagic Camera in\nRemote Camera mode appear here.\n\n"
                            "Not listed? Use Add camera.")
        self.empty.setAlignment(Qt.AlignCenter)
        self.empty.setStyleSheet(f"color: {T.TEXT3.name()}; font-size: 12px;")
        lay.addWidget(self.empty)
        line = QWidget()
        line.setFixedHeight(1)
        line.setStyleSheet("background: rgba(255,255,255,0.08);")
        lay.addWidget(line)
        self.sw_obs, self.lbl_obs = self._opt(lay, "Publish to OBS", 'Source "CineRemote Camera"')
        self.sw_vcam, self.lbl_vcam = self._opt(lay, "Virtual webcam", "")
        self.btn_install = QPushButton({"windows": "Install virtual webcam…", "linux": "Set up virtual webcam…"}
                                       .get(vcam_mod.PLATFORM, "Webcam on macOS…"))
        self.btn_install.setToolTip(vcam_mod.SETUP_HINT)
        lay.addWidget(self.btn_install)
        self.btn_connect = QPushButton("Connect")
        self.btn_connect.setMinimumHeight(34)
        lay.addWidget(self.btn_connect)
        self.sw_obs.toggled.connect(lambda on: win.current and self.backend.set_obs_enabled(win.current, on))
        self.sw_vcam.toggled.connect(lambda on: win.current and self.backend.set_vcam_enabled(win.current, on))
        self.btn_install.clicked.connect(win.install_vcam)
        self.btn_connect.clicked.connect(lambda: win.current and win.toggle_connect(win.current))
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._tick)
        self._timer.start(250)

    def _opt(self, lay, title, sub):
        row = QHBoxLayout()
        row.setSpacing(10)
        sw = Switch(True)
        col = QVBoxLayout()
        col.setSpacing(1)
        a = QLabel(title)
        a.setStyleSheet("font-weight: 600; font-size: 12.5px;")
        b = QLabel(sub)
        b.setStyleSheet(f"color: {T.TEXT3.name()}; font-size: 11px;")
        col.addWidget(a)
        col.addWidget(b)
        row.addWidget(sw)
        row.addLayout(col, 1)
        lay.addLayout(row)
        return sw, b

    def rebuild(self, items):
        keys = [k for k, _ in items]
        for k in list(self.cards):
            if k not in keys:
                self.cards.pop(k).deleteLater()
        for i, (k, h) in enumerate(items):
            c = self.cards.get(k)
            if c is None:
                c = self.cards[k] = CameraCard(self.backend, k)
                c.clicked.connect(self.win.select_key)
                c.doubleClicked.connect(self.win.toggle_connect)
                c.menuRequested.connect(self.win.camera_menu)
                c.armChanged.connect(lambda *_: self.win.update_record_button())
            self.list.removeWidget(c)
            self.list.insertWidget(i, c)
        live = sum(1 for _, h in items if h.session)
        self.count.setText(f"{live} live" if live else (f"{len(items)} found" if items else ""))
        self.empty.setVisible(not items)
        self.set_current(self.win.current)

    def set_current(self, key):
        for k, c in self.cards.items():
            c.selected = k == key
            c.updateGeometry()
            c.update()
        h = self.backend.cams.get(key) if key else None
        self.btn_connect.setEnabled(h is not None)
        self.btn_connect.setText("Disconnect" if h and h.want_connected else "Connect")
        installed = self.backend.vcam_available()
        for sw, v in ((self.sw_obs, bool(h and h.obs_enabled)), (self.sw_vcam, bool(h and h.vcam_enabled and installed))):
            sw.blockSignals(True)
            sw.setChecked(v)
            sw.blockSignals(False)
        label = h.name if h else ""
        if h and self.backend.vcam is not None and hasattr(self.backend.vcam, "device_label"):
            label = self.backend.vcam.device_label(h.key) or label     # Linux: the loopback device's name
        self.lbl_vcam.setText(f'"{label}"' if label and installed else
                              ("not installed" if vcam_mod.PLATFORM != "macos" else "use OBS Virtual Camera"))
        self.sw_vcam.setEnabled(installed)
        self.btn_install.setVisible(not installed)

    def _tick(self):
        for c in self.cards.values():
            if c.isVisible():
                shape = (c.compact(), c.selected)
                if shape != getattr(c, "_shape", None):
                    c._shape = shape
                    c.updateGeometry()
                c.update()


# ---------------------------------------------------------------------------- multiview
class MultiGrid(QWidget):
    def __init__(self, win, parent=None):
        super().__init__(parent)
        self.win = win
        self.grid = QGridLayout(self)
        self.grid.setContentsMargins(12, 12, 12, 12)
        self.grid.setSpacing(10)
        self.tiles = []

    def rebuild(self, keys):
        if [t.key for t in self.tiles] == list(keys):
            self.set_current(self.win.current)
            return
        for t in self.tiles:
            t.setParent(None)
            t.deleteLater()
        self.tiles = []
        n = max(1, len(keys))
        cols = 1 if n == 1 else 2 if n <= 4 else 3
        for i, k in enumerate(keys):
            t = VideoView(self.win.backend, k, mode="tile")
            t.clicked.connect(self.win.select_key)
            t.doubleClicked.connect(self.win.open_single)
            self.grid.addWidget(t, i // cols, i % cols)
            self.tiles.append(t)
        self.set_current(self.win.current)

    def set_current(self, key):
        for t in self.tiles:
            t.selected = t.key == key
            t.update()


# ---------------------------------------------------------------------------- control deck
class Deck(QWidget):
    def __init__(self, win, parent=None):
        super().__init__(parent)
        self.win, self.ctl = win, win.ctl
        self.sel = "iso"
        self.setFixedHeight(214)
        outer = QHBoxLayout(self)
        outer.setContentsMargins(14, 12, 14, 12)
        outer.setSpacing(12)
        left = QVBoxLayout()
        left.setSpacing(12)
        outer.addLayout(left, 1)
        top = QHBoxLayout()
        top.setSpacing(10)
        self.r_label = QLabel("ISO")
        self.r_label.setFont(T.label_font(11))
        self.r_label.setFixedWidth(64)
        self.r_auto_lbl = QLabel("Auto")
        self.r_auto_lbl.setStyleSheet(f"color: {T.TEXT2.name()}; font-size: 12px; font-weight: 600;")
        self.r_auto = Switch()
        self.r_auto.toggled.connect(self._auto_toggled)
        self.ruler = ScrubRuler()
        self.ruler.hint = ""
        self.ruler.setToolTip("Drag, scroll or use ← → to change the value")
        self.ruler.changing.connect(lambda v: self._ruler(v, False))
        self.ruler.committed.connect(lambda v: self._ruler(v, True))
        self.presets = QWidget()
        pl = QHBoxLayout(self.presets)
        pl.setContentsMargins(0, 0, 0, 0)
        pl.setSpacing(4)
        for name, k in M.WB_PRESETS:
            b = QPushButton({"Daylight": T.IC_SUN, "Cloudy": T.IC_CLOUD, "Tungsten": T.IC_BULB}.get(name, "F"))
            b.setFont(T.icon_font(14) if name != "Fluorescent" else T.ui_font(12, QFont.Bold))
            b.setToolTip(f"{name} {k}K")
            b.setFixedSize(34, 30)
            b.clicked.connect(lambda _=False, k=k: self.ctl.set_param(M.BY_KEY["wb"], k))
            pl.addWidget(b)
        top.addWidget(self.r_label)
        top.addWidget(self.r_auto_lbl)
        top.addWidget(self.r_auto)
        top.addWidget(self.ruler, 1)
        top.addWidget(self.presets)
        left.addLayout(top)
        tiles = QHBoxLayout()
        tiles.setSpacing(8)
        self.tiles = {}
        for p in M.PARAMS:
            t = ParamTile(p.key, p.label)
            t.clicked.connect(self.select)
            tiles.addWidget(t)
            self.tiles[p.key] = t
        left.addLayout(tiles)
        # record cluster
        rec = QWidget()
        rec.setFixedWidth(206)
        rec.setObjectName("recbox")
        rec.setStyleSheet(f"QWidget#recbox {{ background: {T.BG2.name()}; border: 1px solid rgba(255,255,255,0.08); border-radius: 12px; }}")
        rl = QVBoxLayout(rec)
        rl.setContentsMargins(8, 10, 8, 10)
        rl.setSpacing(6)
        self.tc = QLabel("00:00:00:00")
        self.tc.setFont(T.num_font(24))
        self.tc.setAlignment(Qt.AlignCenter)
        self.rec_btn = RecordButton(76)
        self.rec_btn.clicked.connect(self._rec)
        self.media = QLabel("")
        self.media.setAlignment(Qt.AlignCenter)
        self.media.setFont(T.num_font(12, QFont.Medium))
        self.media.setStyleSheet(f"color: {T.TEXT2.name()};")
        rl.addWidget(self.tc)
        rl.addWidget(self.rec_btn, 0, Qt.AlignHCenter)
        rl.addWidget(self.media)
        outer.addWidget(rec)
        self.select("iso")

    def select(self, key):
        self.sel = key
        for k, t in self.tiles.items():
            t.set_selected(k == key)
        self.refresh()
        self.ruler.setFocus()

    def _ruler(self, v, final):
        p = M.BY_KEY[self.sel]
        st = p.state(self.ctl.h) if self.ctl.h else None
        # Lens / FPS switch hardware: only on release, never once per item passed while dragging.
        if final or not (st and st.discrete and p.key in ("lens", "fps")):
            self.ctl.set_param(p, v, throttled=not final)
        if st is not None:
            st.text = dict(st.choices).get(v, p.fmt(v)) if st.choices else p.fmt(v)
            if st.auto is not None:
                st.auto = False
                st.sub = "manual"
            self.tiles[self.sel].set_state(st)

    def _auto_toggled(self, on):
        self.ctl.set_auto(M.BY_KEY[self.sel], on)

    def _rec(self, checked):
        self.rec_btn.setChecked(not checked)     # wait for the phone to confirm
        self.win.toggle_record()

    def refresh(self):
        h = self.ctl.h
        live = self.ctl.live()
        for p in M.PARAMS:
            st = p.state(h) if h else M.State()
            self.tiles[p.key].set_state(st)
            self.tiles[p.key].setEnabled(live)
        p = M.BY_KEY[self.sel]
        st = p.state(h) if h else M.State()
        self.r_label.setText(p.label.upper())
        self.ruler.set_state(st, p.fmt, key=(self.ctl.key, p.key))
        self.ruler.setEnabled(live and st.enabled and st.value is not None and (st.discrete or st.hi > st.lo))
        self.r_auto.setVisible(p.has_auto)
        self.r_auto_lbl.setVisible(p.has_auto)
        self.r_auto.blockSignals(True)
        self.r_auto.setChecked(bool(st.auto))
        self.r_auto.blockSignals(False)
        self.r_auto.setEnabled(live)
        self.presets.setVisible(p.key == "wb")
        self.tc.setText(M.timecode(h))
        rec = M.recording(h)
        self.tc.setStyleSheet(f"color: {T.REC.name() if rec else T.TEXT.name()};")
        self.rec_btn.setChecked(rec)
        self.rec_btn.setEnabled(live)
        rt, gb = M.media(h)
        self.media.setText(f"{rt} left · {gb:.0f} GB" if rt else "")


# ---------------------------------------------------------------------------- the view
class StudioView(QWidget):
    def __init__(self, win, parent=None):
        super().__init__(parent)
        self.win, self.backend = win, win.backend
        root = QHBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        self.sidebar = Sidebar(win)
        self.sidebar.setObjectName("sidebar")
        self.sidebar.setStyleSheet(f"QWidget#sidebar {{ background: {T.BG1.name()}; border-right: 1px solid #1b1e23; }}")
        self.sidebar.setAttribute(Qt.WA_StyledBackground, True)
        root.addWidget(self.sidebar)
        right = QVBoxLayout()
        right.setContentsMargins(0, 0, 0, 0)
        right.setSpacing(0)
        root.addLayout(right, 1)
        mon = QWidget()
        mon.setObjectName("monArea")
        mon.setStyleSheet("QWidget#monArea { background: #050506; }")
        ml = QHBoxLayout(mon)
        ml.setContentsMargins(14, 12, 12, 12)
        ml.setSpacing(12)
        self.stack = QStackedWidget()
        self.video = VideoView(self.backend, mode="studio")
        self.video.tapped.connect(win.tap_focus)
        self.multi = MultiGrid(win)
        self.stack.addWidget(self.video)
        self.stack.addWidget(self.multi)
        ml.addWidget(self.stack, 1)
        self.meter = VMeter()
        self.meter.clicked.connect(lambda: win.open_audio(self.meter, "left"))
        ml.addWidget(self.meter)
        right.addWidget(mon, 1)
        self.deck = Deck(win)
        self.deck.setObjectName("deck")
        self.deck.setStyleSheet(f"QWidget#deck {{ background: {T.BG1.name()}; border-top: 1px solid #1b1e23; }}")
        self.deck.setAttribute(Qt.WA_StyledBackground, True)
        right.addWidget(self.deck)
        self._mt = QTimer(self)
        self._mt.timeout.connect(self._meter_tick)
        self._mt.start(40)

    def set_current(self, key):
        self.video.set_key(key)
        self.sidebar.set_current(key)
        self.multi.set_current(key)
        self.meter.peaks[0].reset()
        self.meter.peaks[1].reset()
        self.deck.refresh()

    def set_multiview(self, on, keys=()):
        self.stack.setCurrentIndex(1 if on else 0)
        if on:
            self.multi.rebuild(keys)

    def reset_peaks(self):
        for ph in self.meter.peaks:
            ph.reset()

    def _meter_tick(self):
        if not self.isVisible():
            return
        h = self.win.ctl.h
        if h is None or not h.session:
            self.meter.gain_text = ""
            self.meter.set_levels([-90, -90])
            return
        self.meter.gain_text = gain_text(h.audio_gain_db, h.audio_mute).replace(" dB", "")
        self.meter.active = self.win.audio_pop.isVisible()
        self.meter.set_levels(h.audio_db, h.audio_peak_db)
