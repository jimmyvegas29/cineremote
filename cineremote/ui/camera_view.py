"""Camera view (concept A): the Blackmagic Camera app's layout for one camera at a time."""
from PySide6.QtCore import QPoint, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QFont, QPainter, QPainterPath, QPen, QPixmap
from PySide6.QtWidgets import QHBoxLayout, QLabel, QMenu, QPushButton, QSizePolicy, QVBoxLayout, QWidget

from . import model as M
from . import theme as T
from .audio import gain_text
from .video import ThumbSource, VideoView, hist_pixmap
from .widgets import ChipStrip, HMeter, IconButton, Popover, RecordButton, ScrubRuler, Switch


# ---------------------------------------------------------------------------- left rail
class CameraRail(QWidget):
    """Live thumbnails, slate letter and tally; click selects, double-click connects."""
    TH_W, TH_H = 58, 33

    def __init__(self, win, parent=None):
        super().__init__(parent)
        self.win, self.backend = win, win.backend
        self.keys = []
        self.thumbs = {}
        self.setFixedWidth(76)
        self.setMouseTracking(True)
        t = QTimer(self)
        t.timeout.connect(self.update)
        t.start(300)

    def set_keys(self, keys):
        self.keys = list(keys)
        for k in list(self.thumbs):
            if k not in self.keys:
                del self.thumbs[k]
        self.update()

    def _slot(self, i):
        return QRectF(9, 16 + i * 52, self.TH_W, self.TH_H)

    def _hit(self, pos):
        for i, k in enumerate(self.keys):
            if self._slot(i).adjusted(-6, -6, 6, 6).contains(pos):
                return k
        return None

    def mousePressEvent(self, e):
        k = self._hit(e.position())
        if k and e.button() == Qt.RightButton:
            self.win.camera_menu(k, e.globalPosition().toPoint())
        elif k:
            self.win.select_key(k)

    def mouseDoubleClickEvent(self, e):
        k = self._hit(e.position())
        if k:
            self.win.toggle_connect(k)

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.fillRect(self.rect(), QColor("#08090b"))
        p.setPen(T.LINE)
        p.drawLine(self.width() - 1, 0, self.width() - 1, self.height())
        for i, k in enumerate(self.keys):
            h = self.backend.cams.get(k)
            if h is None:
                continue
            r = self._slot(i)
            th = self.thumbs.setdefault(k, ThumbSource(self.TH_W * 2, self.TH_H * 2, interval=0.4))
            img = th.image(h) if h.session else None
            T.rounded(p, r, 6, QColor("#15171b"), T.LINE2)
            if img is not None:
                p.save()
                path = QPainterPath()
                path.addRoundedRect(r, 6, 6)
                p.setClipPath(path)
                sc = max(r.width() / img.width(), r.height() / img.height())
                w, hh = img.width() * sc, img.height() * sc
                p.drawImage(QRectF(r.center().x() - w / 2, r.center().y() - hh / 2, w, hh), img)
                p.restore()
            rec = M.recording(h)
            if k == self.win.current or rec:
                p.setPen(QPen(T.REC if rec else T.ACCENT, 2))
                p.setBrush(Qt.NoBrush)
                p.drawRoundedRect(r.adjusted(-3.5, -3.5, 3.5, 3.5), 8, 8)
            T.rounded(p, QRectF(r.left() - 6, r.top() - 6, 17, 17), 5, QColor("#2a2f37"), QColor("black"))
            p.setPen(T.TEXT)
            p.setFont(T.ui_font(10, QFont.Bold))
            p.drawText(QRectF(r.left() - 6, r.top() - 6, 17, 17), Qt.AlignCenter, M.slate_letter(h))
            dot = T.REC if rec else (T.OK if h.state == "previewing" else (T.WARN if h.want_connected else QColor("#4b515c")))
            p.setPen(QPen(QColor("#08090b"), 2))
            p.setBrush(dot)
            p.drawEllipse(QRectF(r.right() - 6, r.bottom() - 6, 11, 11))


# ---------------------------------------------------------------------------- HUD
class HudItem(QWidget):
    clicked = Signal(object)

    def __init__(self, param, parent=None):
        super().__init__(parent)
        self.param = param
        self.st = M.State()
        self.open = False
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedHeight(66)

    def set_state(self, st):
        self.st = st
        f = T.num_font(30)
        lab = self.param.label.upper()
        w = max(T.text_width(f, st.text), T.text_width(T.label_font(12), lab) + (20 if st.auto else 0)) + 22
        self.setFixedWidth(int(w))
        self.update()

    def mousePressEvent(self, e):
        self.clicked.emit(self)

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect())
        if self.open:
            T.rounded(p, r, 8, T.ACCENT_SOFT)
            p.fillRect(QRectF(r.left() + 4, r.bottom() - 2, r.width() - 8, 2), T.ACCENT)
        dim = not self.isEnabled()
        lf = T.label_font(12)
        p.setFont(lf)
        p.setPen(T.TEXT3 if dim else QColor("white"))
        lab = self.param.label.upper()
        p.drawText(QRectF(11, 8, r.width(), 16), Qt.AlignLeft | Qt.AlignVCenter, lab)
        if self.st.auto:
            T.badge_a(p, 11 + T.text_width(lf, lab) + 5, 8.5, 15)
        p.setFont(T.num_font(30))
        p.setPen(T.TEXT3 if dim else QColor("white"))
        p.drawText(QRectF(11, 26, r.width(), 34), Qt.AlignLeft | Qt.AlignVCenter, self.st.text)


class PickerPopover(Popover):
    """Picker strip: header with Auto switch, one-click chips, fine ruler."""

    def __init__(self, ctl, parent=None):
        super().__init__(parent)
        self.ctl = ctl
        self.param = None
        self.setFixedWidth(660)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(18, 16, 18, 14)
        lay.setSpacing(12)
        hdr = QHBoxLayout()
        self.title = QLabel("")
        self.title.setFont(T.label_font(12))
        self.info = QLabel("")
        self.info.setStyleSheet(f"color: {T.TEXT2.name()}; font-size: 12px;")
        self.auto_lbl = QLabel("Auto")
        self.auto_lbl.setStyleSheet(f"color: {T.TEXT2.name()}; font-size: 12px;")
        self.auto = Switch()
        self.auto.toggled.connect(lambda on: self.param and self.ctl.set_auto(self.param, on))
        hdr.addWidget(self.title)
        hdr.addSpacing(8)
        hdr.addWidget(self.info, 1)
        hdr.addWidget(self.auto_lbl)
        hdr.addWidget(self.auto)
        lay.addLayout(hdr)
        self.chips = ChipStrip(height=38, font_px=17)
        self.chips.picked.connect(lambda v: self.param and self.ctl.set_param(self.param, v))
        lay.addWidget(self.chips)
        self.ruler = ScrubRuler()
        self.ruler.changing.connect(lambda v: self._ruler(v, False))
        self.ruler.committed.connect(lambda v: self._ruler(v, True))
        lay.addWidget(self.ruler)
        self.hint = QLabel("Drag or scroll the ruler for in-between values · ← → steps · turning Auto off locks the current value")
        self.hint.setStyleSheet(f"color: {T.TEXT3.name()}; font-size: 11.5px;")
        lay.addWidget(self.hint)

    def _ruler(self, v, final):
        if self.param and (final or self.param.key not in ("lens", "fps")):
            self.ctl.set_param(self.param, v, throttled=not final)

    def bind(self, param):
        self.param = param
        self.refresh()

    def refresh(self):
        p, h = self.param, self.ctl.h
        if p is None or h is None:
            return
        st = p.state(h)
        self.title.setText(p.label.upper())
        self.info.setText(" · ".join(x for x in (st.text, st.sub) if x and x != "—"))
        self.auto.setVisible(p.has_auto)
        self.auto_lbl.setVisible(p.has_auto)
        self.auto.blockSignals(True)
        self.auto.setChecked(bool(st.auto))
        self.auto.blockSignals(False)
        self.chips.set_items(self._quick(p, st), st.value)
        self.ruler.set_state(st, p.fmt, key=(self.ctl.key, p.key))
        self.ruler.setEnabled(self.ctl.live() and st.enabled and st.value is not None and (st.discrete or st.hi > st.lo))
        self.ruler.setVisible(not (st.discrete and len(st.choices) <= 9))
        self.hint.setVisible(self.ruler.isVisible())
        self.adjustSize()

    @staticmethod
    def _quick(p, st):
        if st.discrete:
            if len(st.choices) <= 9:
                return st.choices
            keys = [c[0] for c in st.choices]
            i = keys.index(st.value) if st.value in keys else len(keys) // 2
            lo = max(0, min(i - 4, len(keys) - 9))
            return st.choices[lo:lo + 9]
        if p.key == "wb":
            return [(k, f"{k}K") for _, k in M.WB_PRESETS] + [(7500, "7500K")]
        if p.key == "tint":
            return [(v, p.fmt(v)) for v in (-30, -15, -5, 0, 5, 15, 30) if st.lo <= v <= st.hi]
        if p.key == "ev":
            return [(v, p.fmt(v)) for v in (-2.0, -1.0, -0.5, 0.0, 0.5, 1.0, 2.0) if st.lo - 1e-6 <= v <= st.hi + 1e-6]
        if p.key == "zoom":
            return [(v, p.fmt(v)) for v in (1.0, 1.5, 2.0, 3.0, 5.0, 10.0) if st.lo - 1e-6 <= v <= st.hi + 1e-6]
        if p.key == "focus":
            return [(st.lo + (st.hi - st.lo) * f, lbl) for f, lbl in ((0.0, "Near"), (0.25, "¼"), (0.5, "½"), (0.75, "¾"), (1.0, "Far"))]
        return []


# ---------------------------------------------------------------------------- overlay panels
class CachedPanel(QWidget):
    """Overlay panel drawn into a pixmap only when its content changes. It sits over the live video, so
    Qt repaints it on every video frame; with the cache that repaint is a single blit."""

    def __init__(self, w, h, parent=None):
        super().__init__(parent)
        self.setFixedSize(w, h)
        self._pm = None
        self._sig = object()

    def content_sig(self):
        raise NotImplementedError

    def draw(self, p):
        raise NotImplementedError

    def tick(self):
        sig = self.content_sig()
        if sig != self._sig:
            self._sig = sig
            self._pm = None
            self.update()

    def paintEvent(self, e):
        if self._pm is None:
            dpr = self.devicePixelRatioF()
            self._pm = QPixmap(int(self.width() * dpr), int(self.height() * dpr))
            self._pm.setDevicePixelRatio(dpr)
            self._pm.fill(Qt.transparent)
            p = QPainter(self._pm)
            p.setRenderHint(QPainter.Antialiasing)
            T.rounded(p, QRectF(0.5, 0.5, self.width() - 1, self.height() - 1), 12, T.GLASS, T.LINE)
            self.draw(p)
            p.end()
        QPainter(self).drawPixmap(0, 0, self._pm)


class HistPanel(CachedPanel):
    def __init__(self, view, parent=None):
        super().__init__(316, 128, parent)
        self.view = view

    def content_sig(self):
        return id(self.view.analysis.hist)

    def paintEvent(self, e):
        hist = self.view.analysis.hist
        if self._pm is None:
            self._pm = hist_pixmap(hist, self.width(), self.height(), self.devicePixelRatioF(), title="Histogram")
        QPainter(self).drawPixmap(0, 0, self._pm)


class MediaPanel(CachedPanel):
    def __init__(self, ctl, parent=None):
        super().__init__(330, 128, parent)
        self.ctl = ctl

    def content_sig(self):
        h = self.ctl.h
        b = M.battery_pct(h)
        return M.media(h), None if b is None else round(b)

    def draw(self, p):
        h = self.ctl.h
        T.rounded(p, QRectF(18, 34, 28, 52), 6, None, QPen(QColor("white"), 2.4))
        p.fillRect(QRectF(28, 40, 8, 2), QColor("white"))
        rt, gb = M.media(h)
        b = M.battery_pct(h)
        p.setFont(T.num_font(30))
        p.setPen(T.TEXT)
        p.drawText(QRectF(64, 24, 250, 36), Qt.AlignLeft | Qt.AlignVCenter, rt or "--:--:--")
        bar = QRectF(64, 68, 250, 5)
        T.rounded(p, bar, 2.5, T.BG3)
        if b is not None:
            T.rounded(p, QRectF(bar.left(), bar.top(), bar.width() * b / 100, bar.height()), 2.5,
                      T.REC if b < 15 else T.ACCENT)
        p.setFont(T.num_font(13))
        p.drawText(QRectF(64, 82, 125, 18), Qt.AlignLeft | Qt.AlignVCenter, f"{b:.0f}% battery" if b is not None else "")
        p.drawText(QRectF(189, 82, 125, 18), Qt.AlignRight | Qt.AlignVCenter, f"{gb:.0f} GB free" if gb is not None else "")


class AudioStrip(CachedPanel):
    """The phone app's audio panel; fully painted (no child widgets) so it caches as one pixmap."""
    clicked = Signal()

    def __init__(self, ctl, parent=None):
        super().__init__(470, 104, parent)
        self.ctl = ctl
        self.setCursor(Qt.PointingHandCursor)
        self.setToolTip("Click for level to OBS, mute and listen on PC")
        self.meter = HMeter()            # used for its level/peak state + painting, not shown as a widget
        self.meter.resize(438, 56)

    def mousePressEvent(self, e):
        self.clicked.emit()

    def content_sig(self):
        h = self.ctl.h
        if h is None or not h.session:
            self.meter.set_levels([-90, -90])
            return None
        self.meter.set_levels(h.audio_db, h.audio_peak_db)
        src = h.props.get("audio_source")
        return (self.meter._sig, h.audio_gain_db, h.audio_mute, src.active_source_name if src else "")

    def draw(self, p):
        h = self.ctl.h
        p.setFont(T.label_font(11))
        p.setPen(QColor("white"))
        p.drawText(QRectF(16, 10, 60, 18), Qt.AlignLeft | Qt.AlignVCenter, "AUDIO")
        if h is not None and h.session:
            src = h.props.get("audio_source")
            p.setFont(T.ui_font(11.5))
            p.setPen(T.TEXT2)
            p.drawText(QRectF(76, 10, 220, 18), Qt.AlignLeft | Qt.AlignVCenter,
                       f"{(src.active_source_name if src else '') or 'Phone mic'} · stereo")
            p.setFont(T.num_font(13))
            p.setPen(T.TEXT)
            p.drawText(QRectF(240, 10, 214, 18), Qt.AlignRight | Qt.AlignVCenter,
                       f"to OBS {gain_text(h.audio_gain_db, h.audio_mute)}")
        p.save()
        p.translate(16, 36)
        self.meter.render_into(p)
        p.restore()


class CamVideo(VideoView):
    """VideoView that keeps the overlay panels docked to the bottom of the picture."""

    def __init__(self, backend, parent=None):
        super().__init__(backend, mode="camera", parent=parent)
        self.panels = []    # (widget, anchor: 'left' | 'center' | 'right')

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self.place_panels()

    def place_panels(self):
        r = self.rect()
        y = r.bottom() - 18
        left_x = r.left() + 18
        for w, anchor in self.panels:
            if anchor == "left":
                w.move(left_x, y - w.height())
                left_x += w.width() + 66
            elif anchor == "right":
                w.move(r.right() - 18 - w.width(), y - w.height())
        # hide panels that would collide on a narrow window (right panel wins)
        rights = [w for w, a in self.panels if a == "right"]
        lim = rights[0].x() - 12 if rights else r.right()
        for w, a in self.panels:
            if a == "left":
                w.setVisible(w.x() + w.width() <= lim)


# ---------------------------------------------------------------------------- right rail
class RailButton(IconButton):
    def __init__(self, glyph, text, tooltip="", parent=None):
        super().__init__(glyph, text, kind="rail", tooltip=tooltip, parent=parent)
        self.auto = False

    def paintEvent(self, e):
        super().paintEvent(e)
        if self.auto:
            p = QPainter(self)
            p.setPen(QColor("white"))
            p.setFont(T.ui_font(13, QFont.Bold))
            p.drawText(QRectF(self.width() - 22, 22, 18, 18), Qt.AlignCenter, "A")


class LensButton(QPushButton):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFixedSize(58, 58)
        self.setCursor(Qt.PointingHandCursor)
        self.setToolTip("Lens")
        self.label = "1x"

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setPen(QPen(QColor("white") if self.isEnabled() else T.TEXT3, 2.5))
        p.setBrush(QColor(255, 255, 255, 18) if self.underMouse() else Qt.NoBrush)
        p.drawEllipse(QRectF(2, 2, 54, 54))
        p.setFont(T.num_font(15 if len(self.label) <= 4 else 12, QFont.Bold))
        p.drawText(QRectF(0, 0, 58, 58), Qt.AlignCenter, self.label)


# ---------------------------------------------------------------------------- the view
class CameraView(QWidget):
    HUD_ORDER = ("lens", "fps", "shutter", None, "iso", "wb", "tint", "ev")

    def __init__(self, win, parent=None):
        super().__init__(parent)
        self.win, self.backend, self.ctl = win, win.backend, win.ctl
        root = QHBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        # left rail
        lr = QWidget()
        lrl = QVBoxLayout(lr)
        lrl.setContentsMargins(0, 0, 0, 12)
        lrl.setSpacing(0)
        self.rail = CameraRail(win)
        self.rail.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Expanding)
        lrl.addWidget(self.rail, 1)
        for glyph, tip, fn in ((T.IC_ADD, "Add camera by IP", win.add_by_ip),
                               (T.IC_SETTINGS, "Camera settings", win.open_settings)):
            b = IconButton(glyph, tooltip=tip)
            b.clicked.connect(fn)
            lrl.addWidget(b, 0, Qt.AlignHCenter)
        lr.setFixedWidth(76)
        lr.setObjectName("camLeft")
        lr.setStyleSheet("QWidget#camLeft { background: #08090b; }")
        root.addWidget(lr)
        # center
        center = QWidget()
        center.setObjectName("camCenter")
        center.setStyleSheet("QWidget#camCenter { background: black; }")
        cl = QVBoxLayout(center)
        cl.setContentsMargins(0, 0, 0, 0)
        cl.setSpacing(0)
        hud = QWidget()
        hud.setFixedHeight(84)
        hl = QHBoxLayout(hud)
        hl.setContentsMargins(22, 0, 22, 0)
        hl.setSpacing(18)
        self.hud = {}
        self.tc = QLabel("00:00:00:00")
        self.tc.setFont(T.num_font(44))
        hl.addStretch(1)
        for key in self.HUD_ORDER:
            if key is None:
                hl.addSpacing(10)
                hl.addWidget(self.tc)
                hl.addSpacing(10)
                continue
            it = HudItem(M.BY_KEY[key])
            it.clicked.connect(self._open_picker)
            self.hud[key] = it
            hl.addWidget(it)
        hl.addStretch(1)
        cl.addWidget(hud)
        self.video = CamVideo(self.backend)
        self.video.tapped.connect(win.tap_focus)
        self.video.analysis.histogram = True
        self.hist = HistPanel(self.video, self.video)
        self.media = MediaPanel(self.ctl, self.video)
        self.audio = AudioStrip(self.ctl, self.video)
        self.audio.clicked.connect(lambda: win.open_audio(self.audio, "above"))
        self.video.panels = [(self.hist, "left"), (self.media, "left"), (self.audio, "right")]
        cl.addWidget(self.video, 1)
        cl.addWidget(self._status_bar())
        root.addWidget(center, 1)
        # right rail
        rr = QWidget()
        rr.setFixedWidth(108)
        rr.setObjectName("camRight")
        rr.setStyleSheet("QWidget#camRight { background: #08090b; border-left: 1px solid #16181c; }")
        rl = QVBoxLayout(rr)
        rl.setContentsMargins(0, 14, 0, 14)
        rl.setSpacing(10)
        self.b_studio = RailButton(T.IC_MONITOR, "Studio", "Back to Studio view (Ctrl+1)")
        self.b_studio.clicked.connect(lambda: win.set_view("studio"))
        self.b_focus = RailButton(T.IC_FOCUS, "Focus", "Auto focus on/off (tap the picture to focus)")
        self.b_focus.clicked.connect(self._toggle_af)
        self.b_expo = RailButton(T.IC_SLIDERS, "Exposure", "Auto exposure (shutter + ISO) on/off")
        self.b_expo.clicked.connect(self._toggle_ae)
        self.rec = RecordButton(90)
        self.rec.clicked.connect(self._rec)
        self.lens = LensButton()
        self.lens.clicked.connect(lambda: self._open_picker(self.hud["lens"], anchor=self.lens))
        self.b_lut = RailButton(T.IC_LUT, "LUT", "Display LUT")
        self.b_lut.clicked.connect(self._lut_menu)
        self.b_stab = RailButton(T.IC_STAB, "Stabilize", "Stabilization")
        self.b_stab.clicked.connect(self._stab_menu)
        self.b_torch = RailButton(T.IC_BULB, "Torch", "Torch / light")
        self.b_torch.setCheckable(True)
        self.b_torch.clicked.connect(lambda on: self.ctl.send("set_torch", on))
        for w in (self.b_studio, self.b_focus, self.b_expo):
            rl.addWidget(w, 0, Qt.AlignHCenter)
        rl.addStretch(1)
        rl.addWidget(self.rec, 0, Qt.AlignHCenter)
        rl.addStretch(1)
        for w in (self.lens, self.b_lut, self.b_stab, self.b_torch):
            rl.addWidget(w, 0, Qt.AlignHCenter)
        root.addWidget(rr)
        self.picker = PickerPopover(self.ctl, self)
        self.picker.closed.connect(self._picker_closed)
        self.picker.hide()
        self._open_item = None
        t = QTimer(self)
        t.timeout.connect(self._tick)
        t.start(50)

    def _status_bar(self):
        bar = QWidget()
        bar.setFixedHeight(66)
        bar.setObjectName("camStatus")
        bar.setStyleSheet("QWidget#camStatus { background: #08090b; border-top: 1px solid #16181c; }")
        l = QHBoxLayout(bar)
        l.setContentsMargins(22, 0, 18, 0)
        l.setSpacing(22)
        self.slate = {}
        for k, lab in (("reel", "Reel"), ("scene", "Scene"), ("take", "Take")):
            col = QVBoxLayout()
            col.setSpacing(0)
            a = QLabel(lab.upper())
            a.setFont(T.label_font(10))
            a.setStyleSheet(f"color: {T.TEXT3.name()};")
            v = QLabel("–")
            v.setFont(T.num_font(20))
            col.addWidget(a)
            col.addWidget(v)
            l.addLayout(col)
            self.slate[k] = v
        self.b_good = QPushButton("★  Good take")
        self.b_good.setObjectName("pill")
        self.b_good.setCheckable(True)
        self.b_good.setToolTip("Mark the last clip as a good take (phone slate)")
        self.b_good.clicked.connect(lambda on: self.ctl.send("set_slate", is_good_take_last_clip=on))
        b_edit = QPushButton("Edit slate…")
        b_edit.setObjectName("pill")
        b_edit.clicked.connect(self.win.open_slate)
        l.addWidget(self.b_good)
        l.addWidget(b_edit)
        l.addStretch(1)
        self.conn = QLabel("")
        self.conn.setStyleSheet(f"color: {T.TEXT2.name()}; font-size: 12px;")
        self.b_obs = QPushButton("OBS source")
        self.b_vcam = QPushButton("Webcam")
        for b in (self.b_obs, self.b_vcam):
            b.setObjectName("pill")
            b.setCheckable(True)
        self.b_obs.clicked.connect(lambda on: self.win.current and self.backend.set_obs_enabled(self.win.current, on))
        self.b_vcam.clicked.connect(lambda on: self.win.current and self.backend.set_vcam_enabled(self.win.current, on))
        l.addWidget(self.conn)
        l.addWidget(self.b_obs)
        l.addWidget(self.b_vcam)
        return bar

    # ------------------------------------------------------------------ binding
    def set_current(self, key):
        self.video.set_key(key)
        self.picker.hide()
        self.refresh()

    def set_keys(self, keys):
        self.rail.set_keys(keys)

    def refresh(self):
        h = self.ctl.h
        live = self.ctl.live()
        for key, it in self.hud.items():
            it.set_state(M.BY_KEY[key].state(h) if h else M.State())
            it.setEnabled(live)
        rec = M.recording(h)
        self.tc.setText(M.timecode(h))
        self.tc.setStyleSheet(f"color: {T.REC.name() if rec else '#ffffff'};")
        self.rec.setChecked(rec)
        for w in (self.rec, self.lens, self.b_focus, self.b_expo, self.b_lut, self.b_stab, self.b_torch, self.b_good):
            w.setEnabled(live)
        if h:
            f, s, i = (M.BY_KEY[k].state(h) for k in ("focus", "shutter", "iso"))
            self.b_focus.auto = bool(f.auto)
            self.b_expo.auto = bool(s.auto and i.auto)
            self.lens.label = M.BY_KEY["lens"].state(h).text if h.props.get("active_cam") else "1x"
            light = h.props.get("cam_light")
            self.b_torch.setChecked(bool(light and light.is_enabled))
            sl = h.props.get("slate")
            if sl:
                self.slate["reel"].setText(str(sl.reel) if sl.reel else "–")
                self.slate["scene"].setText(sl.scene or "–")
                self.slate["take"].setText(str(sl.take) if sl.take else "–")
                self.b_good.setChecked(sl.is_good_take_last_clip)
            self.b_obs.setChecked(h.obs_enabled)
            self.b_vcam.setChecked(h.vcam_enabled)
            self.b_vcam.setEnabled(self.backend.vcam_available())
            sess = h.session
            fps = f" · {sess.stats['fps']:.0f} fps" if sess is not None and sess.stats.get("fps") else ""
            self.conn.setText(f"● {M.short_name(h)}{fps}" if h.session else f"{M.short_name(h)} · {h.state}")
        for w in (self.b_focus, self.b_expo, self.lens):
            w.update()
        if self.picker.isVisible():
            self.picker.refresh()

    def _tick(self):
        if not self.isVisible():
            return
        self.audio.tick()
        self.hist.tick()
        self.media.tick()

    # ------------------------------------------------------------------ actions
    def _open_picker(self, item, anchor=None):
        if self._open_item is not None:
            self._open_item.open = False
            self._open_item.update()
        if not self.ctl.live():
            return
        self._open_item = item
        item.open = True
        item.update()
        self.picker.bind(item.param)
        self.picker.show_near(anchor or item, "left" if anchor is not None else "below")
        self.picker.ruler.setFocus()

    def hideEvent(self, e):
        self.picker.hide()
        super().hideEvent(e)

    def _picker_closed(self):
        if self._open_item is not None:
            self._open_item.open = False
            self._open_item.update()
            self._open_item = None

    def _rec(self, checked):
        self.rec.setChecked(not checked)
        self.win.toggle_record()

    def _toggle_af(self):
        h = self.ctl.h
        if h:
            self.ctl.set_auto(M.BY_KEY["focus"], not M.BY_KEY["focus"].state(h).auto)

    def _toggle_ae(self):
        h = self.ctl.h
        if h:
            on = not (M.BY_KEY["shutter"].state(h).auto and M.BY_KEY["iso"].state(h).auto)
            self.ctl.set_auto(M.BY_KEY["shutter"], on)
            self.ctl.set_auto(M.BY_KEY["iso"], on)

    def _lut_menu(self):
        h = self.ctl.h
        if not h:
            return
        m = QMenu(self)
        cur = h.props.get("lut")
        luts = h.props.get("available_luts")
        sel = cur.selected_lut if cur else ""
        for name in [""] + (list(luts.available_luts) if luts else []):
            a = m.addAction(name or "No LUT")
            a.setCheckable(True)
            a.setChecked(name == sel)
            a.triggered.connect(lambda _=False, n=name: self.ctl.send("set_lut", n, bool(n), False))
        m.exec(self.b_lut.mapToGlobal(QPoint(0, 0)) - QPoint(m.sizeHint().width() + 6, 0))

    def _stab_menu(self):
        h = self.ctl.h
        if not h:
            return
        vals = h.props.get("cam_stabilization_values")
        cur = h.props.get("cam_stabilization")
        m = QMenu(self)
        for lv in (vals.stabilization_levels if vals else []):
            a = m.addAction(M.STAB_NAMES.get(lv, str(lv)))
            a.setCheckable(True)
            a.setChecked(bool(cur and cur.level == lv))
            a.triggered.connect(lambda _=False, lv=lv: self.ctl.send("set_stabilization", lv))
        if not m.actions():
            m.addAction("Not supported on this lens").setEnabled(False)
        m.exec(self.b_stab.mapToGlobal(QPoint(0, 0)) - QPoint(m.sizeHint().width() + 6, 0))
