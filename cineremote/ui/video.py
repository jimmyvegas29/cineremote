"""Live video view + PC-side monitoring tools (histogram, zebras, focus peaking, false color, guides).

The analysis runs on the display-size RGB frame we already make for painting, so its cost scales with
the window, not the 1080p stream, and only runs for tools that are switched on.
"""
import logging
import time

import numpy as np
from av.video.reformatter import VideoReformatter
from PySide6.QtCore import QPointF, QRect, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QFont, QImage, QPainter, QPainterPath, QPen, QPixmap
from PySide6.QtWidgets import QPushButton, QSizePolicy, QWidget

from . import model as M
from . import theme as T
from .widgets import METER_HOT_DB, METER_WARN_DB, level_to_frac, paint_histogram

log = logging.getLogger("cineremote.ui.video")

ZEBRA_LEVEL = 242          # ~95 % (of 0..255 luma)
PEAK_THRESHOLD = 46


def _false_color_lut():
    """IRE-style false color: purple crushed blacks, blue shadows, grey mids, green ~skin/18%,
    yellow highlights, red clipping."""
    lut = np.zeros((256, 3), np.uint8)
    for v in range(256):
        y = v / 255
        if y < 0.03:
            c = (130, 40, 170)
        elif y < 0.10:
            c = (40, 90, 220)
        elif y < 0.38:
            g = int(60 + y * 260)
            c = (g, g, g)
        elif y < 0.48:
            c = (60, 190, 90)
        elif y < 0.56:
            c = (230, 120, 170)
        elif y < 0.90:
            g = int(110 + y * 130)
            c = (g, g, g)
        elif y < 0.97:
            c = (240, 210, 40)
        else:
            c = (230, 40, 40)
        lut[v] = c
    return lut


FALSE_LUT = _false_color_lut()


FALSE_LUT32 = (0xFF000000 | (FALSE_LUT[:, 0].astype(np.uint32) << 16) | (FALSE_LUT[:, 1].astype(np.uint32) << 8)
               | FALSE_LUT[:, 2].astype(np.uint32))
FALSE_TABLE = [int(v) for v in FALSE_LUT32]          # QImage Indexed8 color table
PEAK_RGB32 = np.uint32(0xFFFF32CD)
ZEBRA_RGB32 = np.uint32(0xFFFAFAFA)


class Analysis:
    """Per-view toggles + the numpy work. Inputs are the display-size BGRA frame (as uint32 pixels)
    and, for the pixel tools, the decoder's own Y plane scaled to the same size (true luma, no RGB math)."""

    def __init__(self):
        self.histogram = False
        self.zebra = False
        self.peaking = False
        self.false_color = False
        self.guides = False          # 2.39:1 frame lines
        self.grid = False            # rule of thirds
        self.hist = None
        self._hist_t = 0.0
        self._zebra_stripes = None

    def any_pixels(self):
        return self.zebra or self.peaking or self.false_color

    def update_hist(self, bgra_u8):
        """bgra_u8: (h, w*4) bytes. Recomputed at most ~8x a second."""
        if time.monotonic() - self._hist_t < 0.12:
            return
        self._hist_t = time.monotonic()
        px = bgra_u8[::4].reshape(-1, bgra_u8.shape[1] // 4, 4)[:, ::4]   # every 4th row, every 4th pixel
        hist = {}
        for c, off in (("r", 2), ("g", 1), ("b", 0)):
            h = np.bincount(px[:, :, off].ravel() >> 2, minlength=64).astype(np.float32)
            h = np.sqrt(h)
            hist[c] = (h / (h.max() or 1)).tolist()
        self.hist = hist

    def render(self, bgra32, luma):
        """('indexed', luma) when false color alone can be done by QImage's color table, else
        ('rgb32', pixels) with peaking / zebras painted over the picture or the false-color map."""
        if self.false_color and not (self.peaking or self.zebra):
            return "indexed", luma
        out = FALSE_LUT32[luma] if self.false_color else bgra32.copy()
        if self.peaking:
            edge = np.zeros(luma.shape, bool)
            a, b = luma[:, 2:], luma[:, :-2]
            edge[:, 1:-1] = (np.maximum(a, b) - np.minimum(a, b)) > PEAK_THRESHOLD
            a, b = luma[2:, :], luma[:-2, :]
            edge[1:-1, :] |= (np.maximum(a, b) - np.minimum(a, b)) > PEAK_THRESHOLD
            np.copyto(out, PEAK_RGB32, where=edge)
        if self.zebra:
            h, w = luma.shape
            if self._zebra_stripes is None or self._zebra_stripes.shape != (h, w):
                yy, xx = np.mgrid[0:h, 0:w]
                self._zebra_stripes = ((xx + yy) // 5) % 2 == 0
            np.copyto(out, ZEBRA_RGB32, where=(luma >= ZEBRA_LEVEL) & self._zebra_stripes)
        return "rgb32", out


class VideoView(QWidget):
    """mode: 'studio' (concept C monitor), 'camera' (concept A full-bleed) or 'tile' (multiview)."""
    tapped = Signal(float, float)
    clicked = Signal(str)
    doubleClicked = Signal(str)
    expandRequested = Signal(str)       # tile: the enlarge / back-to-grid icon in its corner

    def __init__(self, backend, key=None, mode="studio", parent=None):
        super().__init__(parent)
        self.backend, self.key, self.mode = backend, key, mode
        self.analysis = Analysis()
        self.selected = False
        self.expanded = False                   # tile: enlarged to fill the multiview
        self._img = None
        self._img_rect = QRect()
        self._seq = -1
        self._reformatter = VideoReformatter()  # per view: never share a reformat across threads
        self._gray = VideoReformatter()         # Y plane for the pixel tools
        self._keep = None                       # owner of the buffer self._img points into
        self._hist_pm = None                    # cached histogram drawing (data changes ~8 Hz)
        self._hist_src = None
        self._last_msg = None
        self._reticle = None                    # (x, y, t) normalized
        self.toggles = {}
        self.setMinimumSize(160, 90)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        self.setMouseTracking(False)
        if mode == "studio":
            self._make_toggles()
        elif mode == "tile":
            self.setToolTip("Click to select · double-click to set the focus point · corner icon to enlarge / back to the grid")
        # Frames are pushed: the backend signals each new frame, so nothing waits on a poll interval.
        # The slow timer only covers "no video" states and the fading focus reticle.
        backend.frameReady.connect(self._on_frame)
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._idle_tick)
        self._timer.start(100)

    # ------------------------------------------------------------------ toggles (studio)
    def _make_toggles(self):
        for key, label, tip in (("histogram", "Histogram", "RGB histogram of the picture"),
                                ("guides", "2.39:1 guides", "Frame lines for a 2.39:1 crop"),
                                ("peaking", "Focus peaking", "Highlights in-focus edges (computed on this PC)"),
                                ("zebra", "Zebras 95%", "Stripes over areas near clipping"),
                                ("false_color", "False color", "Exposure map: blue shadows, green mids, red clipping"),
                                ("grid", "Grid", "Rule-of-thirds grid")):
            b = QPushButton(label, self)
            b.setObjectName("toggle")
            b.setCheckable(True)
            b.setToolTip(tip)
            b.setCursor(Qt.PointingHandCursor)
            b.setChecked(getattr(self.analysis, key))
            b.toggled.connect(lambda on, k=key: self.set_tool(k, on))
            self.toggles[key] = b

    def set_tool(self, key, on):
        setattr(self.analysis, key, on)
        if key in self.toggles and self.toggles[key].isChecked() != on:
            self.toggles[key].blockSignals(True)
            self.toggles[key].setChecked(on)
            self.toggles[key].blockSignals(False)
        self._seq = -1          # re-render the current frame with the new tools
        self.update()

    def resizeEvent(self, e):
        self._seq = -1
        self._place_toggles()
        super().resizeEvent(e)

    def _place_toggles(self):
        if not self.toggles:
            return
        r = self._img_rect if self._img is not None else self.rect()
        x = r.right() - 10
        for b in reversed(list(self.toggles.values())):
            b.adjustSize()
            x -= b.width()
            b.move(x, r.bottom() - b.height() - 10)
            x -= 6

    # ------------------------------------------------------------------ data
    def set_key(self, key):
        if key != self.key:
            self.key = key
            self._img = self._keep = None
            self._seq = -1
            self._reticle = None
            self.analysis.hist = None          # never show the previous camera's histogram
            self.update()

    @property
    def h(self):
        return self.backend.cams.get(self.key) if self.key else None

    def _on_frame(self, key):
        if key == self.key and self.isVisible():
            self._refresh()

    def _idle_tick(self):
        if not self.isVisible():
            return
        h = self.h
        if h is None or h.latest is None:
            if self._img is not None:
                self._img = self._keep = None
                self.analysis.hist = None
                self.update()
            msg = self._message(h)
            if msg != self._last_msg:
                self._last_msg = msg
                self.update()
        elif self._reticle or self._seq == -1:
            self._refresh()

    def _message(self, h):
        if h is None:
            return "No camera selected"
        return {"connecting": "Connecting…", "reconnecting": "Reconnecting…", "connected": "Starting video…",
                "previewing": "Starting video…"}.get(h.state, "Not connected - double-click the camera to connect")

    def _refresh(self):
        h = self.h
        if h is None:
            return
        with h.lock:
            fr, seq = h.latest, h.latest_seq
        if fr is None:
            return
        if seq == self._seq and self._img is not None:
            if self._reticle:
                self.update()
            return
        self._seq = seq
        W, H = max(self.width(), 2), max(self.height(), 2)
        scale = min(W / fr.width, H / fr.height)
        tw, th = max(2, int(fr.width * scale) & ~1), max(2, int(fr.height * scale) & ~1)
        a = self.analysis
        try:
            # threads=1: FFmpeg's slice-threaded scaler leaves marks in the left columns at every slice seam
            out = self._reformatter.reformat(fr, width=tw, height=th, format="bgra", threads=1)
            plane = out.planes[0]
            ls = plane.line_size
            bgra_u8 = None
            if a.histogram or a.any_pixels():
                bgra_u8 = np.frombuffer(plane, np.uint8)[:ls * th].reshape(th, ls)[:, :tw * 4]
                if a.histogram:
                    a.update_hist(bgra_u8)
            if a.any_pixels():
                luma = np.ascontiguousarray(
                    self._gray.reformat(fr, width=tw, height=th, format="gray", threads=1).to_ndarray())
                kind, pix = a.render(bgra_u8.view(np.uint32), luma)
                if kind == "indexed":
                    img = QImage(pix.data, tw, th, pix.strides[0], QImage.Format_Indexed8)
                    img.setColorTable(FALSE_TABLE)
                else:
                    img = QImage(pix.data, tw, th, pix.strides[0], QImage.Format_RGB32)
                self._keep = pix
            else:
                # No copy: the QImage reads the reformatted frame's own buffer, kept alive in _keep.
                img = QImage(plane, tw, th, ls, QImage.Format_RGB32)
                self._keep = out
            self._img = img
            new_rect = QRect((W - tw) // 2, (H - th) // 2, tw, th)
            moved = new_rect != self._img_rect
            self._img_rect = new_rect
            if moved:
                self._place_toggles()
        except Exception:
            log.exception("frame convert")
        self.update()

    def image_rect(self):
        return self._img_rect if self._img is not None else self.rect()

    # ------------------------------------------------------------------ input
    def _expand_rect(self):
        r = QRectF(self.image_rect())
        return QRectF(r.right() - 36, r.top() + 8, 26, 26)

    def _tap(self, pos):
        """Focus/exposure point at a click inside the picture (normalized to the picture)."""
        if self._img is None or not self._img_rect.contains(pos.toPoint()):
            return
        x = (pos.x() - self._img_rect.left()) / self._img_rect.width()
        y = (pos.y() - self._img_rect.top()) / self._img_rect.height()
        self._reticle = (x, y, time.monotonic())
        self.tapped.emit(x, y)
        self.update()

    def mousePressEvent(self, e):
        if self.mode == "tile" and self.key and self._expand_rect().contains(e.position()):
            self.expandRequested.emit(self.key)
            return
        if self.key:
            self.clicked.emit(self.key)
        if self.mode != "tile":         # in the multiview a click selects; double-click sets the focus point
            self._tap(e.position())
        super().mousePressEvent(e)

    def mouseDoubleClickEvent(self, e):
        if self.mode == "tile":
            if not self._expand_rect().contains(e.position()):
                self._tap(e.position())
        elif self.key:
            self.doubleClicked.emit(self.key)

    # ------------------------------------------------------------------ paint
    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.fillRect(self.rect(), QColor("#050506") if self.mode != "camera" else QColor("black"))
        h = self.h
        r = QRectF(self.image_rect())
        if self.mode == "tile":         # rounded picture, matching the tile's highlight border
            clip = QPainterPath()
            clip.addRoundedRect(r, 7, 7)
            p.setClipPath(clip)
        if self._img is not None:
            p.drawImage(self._img_rect, self._img)
        else:
            p.setPen(T.TEXT3)
            p.setFont(T.ui_font(14, QFont.DemiBold))
            p.drawText(self.rect(), Qt.AlignCenter, self._message(h))
        if self._img is not None:
            self._paint_tools(p, r)
        if h is None:
            return
        if self.mode == "studio":
            self._paint_studio(p, r, h)
        elif self.mode == "tile":
            self._paint_tile(p, r, h)
        self._paint_reticle(p, r)

    def _paint_tools(self, p, r):
        a = self.analysis
        if a.guides:
            ratio = 2.39
            if r.width() / r.height() < ratio:
                gh = r.width() / ratio
                y0 = r.top() + (r.height() - gh) / 2
                p.fillRect(QRectF(r.left(), r.top(), r.width(), y0 - r.top()), QColor(0, 0, 0, 110))
                p.fillRect(QRectF(r.left(), y0 + gh, r.width(), r.bottom() - y0 - gh), QColor(0, 0, 0, 110))
                p.setPen(QPen(QColor(255, 255, 255, 150), 1.4, Qt.DashLine))
                p.drawLine(QPointF(r.left(), y0), QPointF(r.right(), y0))
                p.drawLine(QPointF(r.left(), y0 + gh), QPointF(r.right(), y0 + gh))
        if a.grid:
            p.setPen(QPen(QColor(255, 255, 255, 90), 1))
            for k in (1, 2):
                x = r.left() + r.width() * k / 3
                y = r.top() + r.height() * k / 3
                p.drawLine(QPointF(x, r.top()), QPointF(x, r.bottom()))
                p.drawLine(QPointF(r.left(), y), QPointF(r.right(), y))
        if a.histogram and a.hist and self.mode == "studio":
            if a.hist is not self._hist_src or self._hist_pm is None:
                self._hist_src = a.hist
                self._hist_pm = hist_pixmap(a.hist, 244, 80, self.devicePixelRatioF())
            p.drawPixmap(QPointF(r.left() + 12, r.bottom() - 92), self._hist_pm)

    def _paint_reticle(self, p, r):
        if not self._reticle:
            return
        x, y, t = self._reticle
        age = time.monotonic() - t
        if age > 2.5:
            self._reticle = None
            return
        alpha = 255 if age < 1.8 else int(255 * (2.5 - age) / 0.7)
        s, k = (28.0, 9.0) if self.mode == "tile" else (46.0, 14.0)
        cx, cy = r.left() + x * r.width(), r.top() + y * r.height()
        p.setPen(QPen(QColor(255, 212, 59, alpha), 2.5))
        for dx, dy in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
            ox, oy = cx + dx * s, cy + dy * s
            p.drawLine(QPointF(ox, oy), QPointF(ox - dx * k, oy))
            p.drawLine(QPointF(ox, oy), QPointF(ox, oy - dy * k))

    def _paint_studio(self, p, r, h):
        x = r.left() + 12
        y = r.top() + 12
        f = T.ui_font(12, QFont.DemiBold)
        name = M.short_name(h)
        w = T.text_width(f, name) + 18 + 22
        T.rounded(p, QRectF(x, y, w, 26), 6, QColor(0, 0, 0, 150), T.LINE2)
        T.slate_badge(p, x + 6, y + 5, M.slate_letter(h), 16)
        p.setPen(T.TEXT)
        p.setFont(f)
        p.drawText(QRectF(x + 28, y, w - 28, 26), Qt.AlignVCenter, name)
        fmt = M.video_format(h)
        if fmt:
            T.chip(p, x + w + 6, y, fmt, color=T.TEXT2, h=26)
        rec = M.recording(h)
        tc = M.timecode(h)
        tf = T.num_font(16)
        tw = T.text_width(tf, tc) + 24
        box = QRectF(r.center().x() - tw / 2, y, tw, 28)
        T.rounded(p, box, 6, T.REC if rec else QColor(0, 0, 0, 160), None if rec else T.LINE2)
        p.setFont(tf)
        p.setPen(QColor("white"))
        p.drawText(box, Qt.AlignCenter, tc)
        status = "● REC" if rec else ("STANDBY" if h.session else h.state.upper())
        sf = T.ui_font(11.5, QFont.DemiBold)
        sw = T.text_width(sf, status) + 18
        T.chip(p, r.right() - 12 - sw, y, status, font=sf, h=26,
               fill=T.REC if rec else QColor(0, 0, 0, 150), color=QColor("white") if rec else T.TEXT2,
               border=None if rec else T.LINE2)
        if rec:
            p.setPen(QPen(T.REC, 3))
            p.setBrush(Qt.NoBrush)
            p.drawRect(r.adjusted(1.5, 1.5, -1.5, -1.5))

    def _paint_tile(self, p, r, h):
        rec = M.recording(h)
        border = T.REC if rec else (T.ACCENT if self.selected else None)
        if border is not None:
            p.setPen(QPen(border, 2.5))
            p.setBrush(Qt.NoBrush)
            p.drawRoundedRect(r.adjusted(1.25, 1.25, -1.25, -1.25), 6, 6)
        x, y = r.left() + 10, r.top() + 10
        T.slate_badge(p, x, y, M.slate_letter(h), 22)
        T.chip(p, x + 28, y, M.short_name(h), h=22, font=T.ui_font(11.5, QFont.DemiBold))
        ex = self._expand_rect()
        T.rounded(p, ex, 6, QColor(0, 0, 0, 90), QColor(255, 255, 255, 40))
        # enlarge: arrows out to the corners; back to the grid: arrows in to the middle
        p.setPen(QPen(QColor(255, 255, 255, 180), 1.6, Qt.SolidLine, Qt.RoundCap))
        c, d, k = ex.center(), ex.width() / 2 - 7, 4.0
        for sx, sy in ((1, -1), (-1, 1)):
            outer, inner = QPointF(c.x() + sx * d, c.y() + sy * d), QPointF(c.x() + sx * 1.5, c.y() + sy * 1.5)
            tip, back = (inner, 1) if self.expanded else (outer, -1)
            p.drawLine(outer, inner)
            p.drawLine(tip, QPointF(tip.x() + back * sx * k, tip.y()))
            p.drawLine(tip, QPointF(tip.x(), tip.y() + back * sy * k))
        right = ex.left() - 6
        b = M.battery_pct(h)
        if b is not None:
            t = f"{b:.0f}%"
            w = T.text_width(T.ui_font(11.5, QFont.DemiBold), t) + 18
            right -= w
            T.chip(p, right, y, t, h=22, font=T.ui_font(11.5, QFont.DemiBold))
            right -= 6
        if rec:
            t = "● REC " + M.timecode(h)[3:8]
            w = T.text_width(T.ui_font(11.5, QFont.DemiBold), t) + 18
            T.chip(p, right - w, y, t, h=22, font=T.ui_font(11.5, QFont.DemiBold), fill=T.REC, border=None)
        # bottom readout straight on the picture, with a soft shadow so it reads on bright shots
        band = QRectF(r.left(), r.bottom() - 54, r.width(), 54)
        bx = r.left() + 10
        for key, lab in (("fps", "FPS"), ("shutter", "Shutter"), ("iso", "ISO"), ("wb", "WB"), ("lens", "Lens")):
            st = M.BY_KEY[key].state(h)
            p.setFont(T.label_font(9))
            _shadow_text(p, QRectF(bx, band.bottom() - 36, 80, 12), Qt.AlignLeft, lab, QColor("#d6dae0"))
            nf = T.num_font(17)
            p.setFont(nf)
            _shadow_text(p, QRectF(bx, band.bottom() - 24, 120, 20), Qt.AlignLeft | Qt.AlignVCenter, st.text, T.TEXT)
            bx += max(T.text_width(nf, st.text), T.text_width(T.label_font(9), lab)) + 14
            if bx > r.right() - 60:
                break
        # mini meters
        mx, top, bot = r.right() - 22, r.top() + 44, r.bottom() - 62
        zones = ((0.0, level_to_frac(METER_WARN_DB), T.METER_G),
                 (level_to_frac(METER_WARN_DB), level_to_frac(METER_HOT_DB), T.METER_Y),
                 (level_to_frac(METER_HOT_DB), 1.0, T.METER_R))
        span = bot - top
        for ch in range(2):
            frac = level_to_frac(h.audio_db[ch])
            x = mx + ch * 8
            for z0, z1, col in zones:       # each zone: dim tint, lit up to the level (green/orange/red)
                p.fillRect(QRectF(x, bot - z1 * span, 5, (z1 - z0) * span), QColor(col.red(), col.green(), col.blue(), 45))
                if frac > z0:
                    lit = min(frac, z1)
                    p.fillRect(QRectF(x, bot - lit * span, 5, (lit - z0) * span), col)


_SHADOW_OFFSETS = [(dx, dy) for dx in (-1.5, 0, 1.5) for dy in (-1, 0.5, 2) if (dx, dy) != (0, 0.5)]


def _shadow_text(p, rect, flags, text, color):
    """Text with a soft dark halo (the same text drawn faintly around it, slightly lower)."""
    p.setPen(QColor(0, 0, 0, 70))
    for dx, dy in _SHADOW_OFFSETS:
        p.drawText(rect.translated(dx, dy), flags, text)
    p.setPen(color)
    p.drawText(rect, flags, text)


def hist_pixmap(hist, w, h, dpr=1.0, title=None):
    """Histogram in a glass box, drawn once per histogram update and then just blitted."""
    pm = QPixmap(int(w * dpr), int(h * dpr))
    pm.setDevicePixelRatio(dpr)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    box = QRectF(0.5, 0.5, w - 1, h - 1)
    T.rounded(p, box, 11 if title else 9, T.GLASS, T.LINE)
    if title:
        p.setFont(T.label_font(10.5))
        p.setPen(QColor("white"))
        p.drawText(QRectF(14, 10, 200, 14), Qt.AlignLeft | Qt.AlignVCenter, title)
        paint_histogram(p, QRectF(14, 32, w - 46, h - 46), hist)
        T.rounded(p, QRectF(w - 24, 32, 10, h - 46), 2, None, T.LINE2)
    else:
        paint_histogram(p, box.adjusted(10, 8, -10, -8), hist)
    p.end()
    return pm


class ThumbSource:
    """Small, rate-limited thumbnails for the camera cards / rail."""

    def __init__(self, w, h, interval=0.25):
        self.size, self.interval = (w, h), interval
        self._reformatter = VideoReformatter()
        self._img, self._t, self._seq = None, 0.0, -1

    def image(self, h):
        if h is None:
            return None
        now = time.monotonic()
        if now - self._t < self.interval:
            return self._img
        with h.lock:
            fr, seq = h.latest, h.latest_seq
        if fr is None:
            self._img = None
            return None
        if seq != self._seq:
            self._t, self._seq = now, seq
            bw, bh = self.size
            sc = min(bw / fr.width, bh / fr.height)       # keep aspect (phones can stream portrait)
            tw, th = max(2, int(fr.width * sc) & ~1), max(2, int(fr.height * sc) & ~1)
            try:
                rgb = self._reformatter.reformat(fr, width=tw, height=th, format="rgb24",
                                                 interpolation="FAST_BILINEAR", threads=1)
                arr = np.ascontiguousarray(rgb.to_ndarray())
                self._img = QImage(arr.data, tw, th, arr.strides[0], QImage.Format_RGB888).copy()
            except Exception:
                log.exception("thumbnail")
        return self._img
