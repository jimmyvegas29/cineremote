"""Custom-painted controls shared by the Studio and Camera views."""
import math
import time

from PySide6.QtCore import QPointF, QRectF, QSize, Qt, Signal
from PySide6.QtGui import QColor, QFont, QLinearGradient, QPainter, QPainterPath, QPen
from PySide6.QtWidgets import QAbstractButton, QApplication, QFrame, QSizePolicy, QWidget

from . import theme as T


# ---------------------------------------------------------------------------- basic controls
class Switch(QAbstractButton):
    def __init__(self, checked=False, parent=None):
        super().__init__(parent)
        self.setCheckable(True)
        self.setChecked(checked)
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedSize(36, 20)

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        on = self.isChecked()
        r = QRectF(0.5, 0.5, self.width() - 1, self.height() - 1)
        col = T.ACCENT if on else T.BG3
        if not self.isEnabled():
            col = QColor(col.red(), col.green(), col.blue(), 90)
        T.rounded(p, r, r.height() / 2, col, None if on else T.LINE2)
        d = r.height() - 6
        x = r.right() - d - 3 if on else r.left() + 3
        p.setPen(Qt.NoPen)
        p.setBrush(QColor("white") if on else QColor("#8b93a1"))
        p.drawEllipse(QRectF(x, r.top() + 3, d, d))


class IconButton(QAbstractButton):
    """Flat toolbar button: Fluent icon glyph + optional text. kind: 'flat' | 'rec' | 'rail'."""

    def __init__(self, glyph, text="", kind="flat", checkable=False, tooltip="", parent=None):
        super().__init__(parent)
        self.glyph, self.kind = glyph, kind
        self.setText(text)
        self.setCheckable(checkable)
        self.setToolTip(tooltip)
        self.setCursor(Qt.PointingHandCursor)
        self._hover = False
        if kind == "rail":
            self.setFixedSize(64, 64 if text else 52)
        else:
            w = 34 + (T.text_width(T.ui_font(13, QFont.DemiBold), text) + 10 if text else 0)
            self.setFixedSize(w, 34)

    def enterEvent(self, e):
        self._hover = True
        self.update()

    def leaveEvent(self, e):
        self._hover = False
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect())
        if self.kind == "rail":
            col = T.ACCENT if self.isChecked() else (T.TEXT if self.isEnabled() else T.TEXT3)
            if self._hover:
                T.rounded(p, r.adjusted(6, 0, -6, 0), 12, QColor(255, 255, 255, 14))
            p.setPen(col)
            p.setFont(T.icon_font(26))
            p.drawText(QRectF(0, 4, r.width(), 36), Qt.AlignCenter, self.glyph)
            if self.text():
                p.setFont(T.label_font(9))
                p.setPen(T.TEXT3 if not self.isChecked() else T.ACCENT)
                p.drawText(QRectF(0, 42, r.width(), 16), Qt.AlignCenter, self.text())
            return
        if self.isChecked() or self._hover:
            T.rounded(p, r, 7, T.BG3 if self.kind != "rec" else QColor(229, 50, 45, 38))
        col = QColor("#ff8f8b") if self.kind == "rec" else (T.TEXT if (self.isChecked() or self._hover) else T.TEXT2)
        if not self.isEnabled():
            col = T.TEXT3
        p.setPen(col)
        p.setFont(T.icon_font(15))
        p.drawText(QRectF(10, 0, 18, r.height()), Qt.AlignCenter, self.glyph)
        if self.text():
            p.setFont(T.ui_font(13, QFont.DemiBold))
            p.drawText(QRectF(34, 0, r.width() - 34, r.height()), Qt.AlignVCenter | Qt.AlignLeft, self.text())


    def set_label(self, text):
        """Change the text and resize to fit (toolbar buttons have a fixed width)."""
        if text != self.text():
            self.setText(text)
            self.setFixedSize(34 + (T.text_width(T.ui_font(13, QFont.DemiBold), text) + 10 if text else 0), 34)


class ArmButton(QAbstractButton):
    """Record-arm toggle for a camera card: a ring that fills red when armed."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setCheckable(True)
        self.setFixedSize(22, 22)
        self.setCursor(Qt.PointingHandCursor)
        self._hover = False

    def enterEvent(self, e):
        self._hover = True
        self.update()

    def leaveEvent(self, e):
        self._hover = False
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        c = QRectF(self.rect()).center()
        on = self.isChecked()
        ring = T.REC if on else (T.TEXT2 if self._hover else T.TEXT3)
        p.setPen(QPen(ring, 1.6))
        p.setBrush(QColor(229, 50, 45, 45) if on else Qt.NoBrush)
        p.drawEllipse(c, 9, 9)
        p.setPen(Qt.NoPen)
        p.setBrush(T.REC if on else (QColor(229, 50, 45, 140) if self._hover else T.TEXT3))
        p.drawEllipse(c, 4.5, 4.5)


class Segmented(QWidget):
    """Two or more mutually exclusive options in a pill track."""
    changed = Signal(int)

    def __init__(self, items, parent=None):
        super().__init__(parent)
        self.items, self.index = items, 0
        self._font = T.ui_font(12, QFont.DemiBold)
        self._w = [T.text_width(self._font, t) + 24 for t in items]
        self.setFixedSize(sum(self._w) + 6, 32)
        self.setCursor(Qt.PointingHandCursor)

    def set_index(self, i):
        if i != self.index:
            self.index = i
            self.update()

    def mousePressEvent(self, e):
        x = 3
        for i, w in enumerate(self._w):
            if x <= e.position().x() < x + w:
                if i != self.index:
                    self.index = i
                    self.update()
                    self.changed.emit(i)
                return
            x += w

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        T.rounded(p, QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5), 8, T.BG, T.LINE)
        x = 3
        p.setFont(self._font)
        for i, (t, w) in enumerate(zip(self.items, self._w)):
            r = QRectF(x, 3, w, self.height() - 6)
            if i == self.index:
                T.rounded(p, r, 6, T.BG3)
            p.setPen(T.TEXT if i == self.index else T.TEXT2)
            p.drawText(r, Qt.AlignCenter, t)
            x += w


# ---------------------------------------------------------------------------- parameter tile
class ParamTile(QWidget):
    """Blackmagic-style readout: small caps label (+ blue A when auto), big value, one context line."""
    clicked = Signal(str)

    def __init__(self, key, label, big=27, parent=None):
        super().__init__(parent)
        self.key, self.label = key, label
        self.text, self.sub, self.auto = "—", "", None
        self.selected = False
        self.big = big
        self.setCursor(Qt.PointingHandCursor)
        self.setMinimumSize(92, 96)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)

    def set_state(self, st):
        if (st.text, st.sub, st.auto) != (self.text, self.sub, self.auto):
            self.text, self.sub, self.auto = st.text, st.sub, st.auto
            self.update()

    def set_selected(self, on):
        if on != self.selected:
            self.selected = on
            self.update()

    def mousePressEvent(self, e):
        self.clicked.emit(self.key)

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        T.rounded(p, r, 10, T.SEL_BG if self.selected else T.BG2,
                  QPen(QColor(47, 124, 246, 215) if self.selected else T.LINE, 1))
        f = T.label_font(10.5 if self.width() > 100 else 10)
        p.setFont(f)
        p.setPen(QColor("#cfe0ff") if self.selected else T.TEXT2)
        p.drawText(QRectF(12, 11, r.width() - 20, 14), Qt.AlignLeft | Qt.AlignVCenter, self.label)
        if self.auto:
            T.badge_a(p, 12 + T.text_width(f, self.label.upper()) + 6, 10.5, 14)
        size = self.big
        p.setFont(T.num_font(size))
        while size > 16 and T.text_width(p.font(), self.text) > r.width() - 22:
            size -= 1
            p.setFont(T.num_font(size))
        p.setPen(T.TEXT)
        p.drawText(QRectF(12, 30, r.width() - 18, size + 8), Qt.AlignLeft | Qt.AlignVCenter, self.text)
        p.setFont(T.ui_font(11))
        p.setPen(QColor("#9fc3ff") if self.selected else T.TEXT3)
        p.drawText(QRectF(12, r.height() - 24, r.width() - 18, 16), Qt.AlignLeft | Qt.AlignVCenter, self.sub)


# ---------------------------------------------------------------------------- scrub ruler
class ScrubRuler(QWidget):
    """Horizontal ruler under a fixed center marker, like the phone's dials. Drag, wheel or arrow keys
    move it. Works on a list of choices (snaps to them) or a numeric range (step-quantized).
    changing(value) fires while dragging, committed(value) when the gesture ends."""
    changing = Signal(object)
    committed = Signal(object)

    ITEM_PX = 64     # spacing between choices

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFocusPolicy(Qt.WheelFocus)
        self.setCursor(Qt.SizeHorCursor)
        self.setMinimumHeight(50)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self.choices, self.lo, self.hi, self.step = [], 0.0, 1.0, 1.0
        self.fmt = str
        self.pos = 0.0               # index (choices) or value (range)
        self.has_value = False       # False while the phone hasn't reported a value: nothing to commit
        self._drag = None
        self._hold_until = 0.0       # ignore phone echoes briefly after a local change (fast wheel)
        self.hint = "drag · scroll · ← →"
        self.setEnabled(False)

    # -- model
    def set_state(self, st, fmt=str, key=None):
        """key identifies the parameter: switching parameters always resets, even mid-gesture."""
        switched = key != getattr(self, "_key", None)
        self._key = key
        if switched:
            self._drag = None
            self._hold_until = 0.0
        elif self._drag is not None or time.monotonic() < self._hold_until:
            return                   # never yank the ruler out from under the user's hand
        self.fmt = fmt
        self.choices = list(st.choices)
        self.lo, self.hi, self.step = float(st.lo), float(st.hi), float(st.step or 1)
        self.has_value = False
        if self.choices:
            keys = [c[0] for c in self.choices]
            if st.value in keys:
                self.pos, self.has_value = float(keys.index(st.value)), True
            elif st.value is not None and all(isinstance(k, (int, float)) for k in keys):
                self.pos = float(min(range(len(keys)), key=lambda i: abs(keys[i] - st.value)))
                self.has_value = True
            else:
                self.pos = (len(keys) - 1) / 2
        elif st.value is not None:
            self.pos, self.has_value = float(st.value), True
        else:
            self.pos = (self.lo + self.hi) / 2
        self._clamp()
        self.update()

    def changeEvent(self, e):
        if e.type() == e.Type.EnabledChange and not self.isEnabled():
            self._drag = None        # a release can't arrive while disabled: don't stay stuck mid-drag
        super().changeEvent(e)

    def focusOutEvent(self, e):
        if self._drag is not None and not (QApplication.mouseButtons() & Qt.LeftButton):
            self._drag = None
        super().focusOutEvent(e)

    def value(self):
        if self.choices:
            i = int(round(max(0, min(len(self.choices) - 1, self.pos))))
            return self.choices[i][0]
        v = round((self.pos - self.lo) / self.step) * self.step + self.lo
        return max(self.lo, min(self.hi, v))

    def _label(self):
        if self.choices:
            i = int(round(max(0, min(len(self.choices) - 1, self.pos))))
            return self.choices[i][1]
        return self.fmt(self.value())

    def _px_per_unit(self):
        if self.choices:
            return self.ITEM_PX
        span = max(self.hi - self.lo, self.step * 10)
        return max(self.width(), 200) / max(span / 2.5, self.step * 24)

    def _clamp(self):
        if self.choices:
            self.pos = max(0.0, min(len(self.choices) - 1.0, self.pos))
        else:
            self.pos = max(self.lo, min(self.hi, self.pos))

    def nudge(self, n):
        if not self.has_value:
            return
        before = self.value()
        if self.choices:
            self.pos = round(self.pos) + n
        else:
            self.pos = self.value() + n * self.step
        self._clamp()
        self._hold_until = time.monotonic() + 0.6
        self.update()
        if self.value() != before:
            self.committed.emit(self.value())

    # -- input
    def mousePressEvent(self, e):
        self.setFocus()
        if e.button() != Qt.LeftButton or not self.has_value:
            return
        v = self.value()
        self._drag = (e.position().x(), self.pos, v, v)      # x0, pos0, last emitted, start value

    def mouseMoveEvent(self, e):
        if self._drag is None:
            return
        x0, p0, last, start = self._drag
        self.pos = p0 - (e.position().x() - x0) / self._px_per_unit()
        self._clamp()
        v = self.value()
        if v != last:
            self._drag = (x0, p0, v, start)
            self.changing.emit(v)
        self.update()

    def mouseReleaseEvent(self, e):
        if self._drag is None or e.button() != Qt.LeftButton:
            return
        start = self._drag[3]
        self._drag = None
        if self.choices:
            self.pos = float(round(self.pos))
        self._hold_until = time.monotonic() + 0.6
        self.update()
        if self.value() != start:        # a plain click is not a command (and must not leave auto)
            self.committed.emit(self.value())

    def wheelEvent(self, e):
        d = e.angleDelta().y() or e.angleDelta().x()
        if d:   # one step per notch (120); a fast spin arrives as one event. Wheel up = higher, like ↑
            n = max(1, abs(d) // 120)
            self.nudge(n if d > 0 else -n)

    def keyPressEvent(self, e):
        if e.key() in (Qt.Key_Left, Qt.Key_Down):
            self.nudge(-1)
        elif e.key() in (Qt.Key_Right, Qt.Key_Up):
            self.nudge(1)
        else:
            super().keyPressEvent(e)

    # -- paint
    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        T.rounded(p, r, 10, T.BG, T.LINE)
        p.save()
        clip = QPainterPath()
        clip.addRoundedRect(r, 10, 10)
        p.setClipPath(clip)
        cx = r.center().x()
        ppu = self._px_per_unit()
        h = r.height()
        dim = not self.isEnabled()
        tick = QColor(255, 255, 255, 60 if dim else 75)
        major = QColor(255, 255, 255, 90 if dim else 150)
        p.setFont(T.num_font(13))
        if self.choices:
            first = int(math.floor(self.pos - cx / ppu)) - 1
            last = int(math.ceil(self.pos + cx / ppu)) + 1
            for i in range(max(first, 0), min(last, len(self.choices) - 1) + 1):
                x = cx + (i - self.pos) * ppu
                p.setPen(QPen(major, 1))
                p.drawLine(QPointF(x, h - 18), QPointF(x, h))
                p.setPen(T.TEXT3 if dim else T.TEXT2)
                p.drawText(QRectF(x - 40, 6, 80, 18), Qt.AlignCenter, self.choices[i][1])
                if i < len(self.choices) - 1:
                    p.setPen(QPen(tick, 1))
                    for k in (1, 2, 3):
                        xx = x + k * ppu / 4
                        p.drawLine(QPointF(xx, h - 9), QPointF(xx, h))
        else:
            span_px = r.width()
            units = span_px / ppu
            mj = _nice(units / 6)
            mn = mj / 5
            start = math.floor((self.pos - units / 2) / mn) * mn
            v = start
            while v <= self.pos + units / 2 + mn:
                if self.lo - 1e-9 <= v <= self.hi + 1e-9:
                    x = cx + (v - self.pos) * ppu
                    is_major = abs(v / mj - round(v / mj)) < 1e-6
                    p.setPen(QPen(major if is_major else tick, 1))
                    p.drawLine(QPointF(x, h - (18 if is_major else 9)), QPointF(x, h))
                    if is_major:
                        p.setPen(T.TEXT3 if dim else T.TEXT2)
                        p.drawText(QRectF(x - 40, 6, 80, 18), Qt.AlignCenter, self.fmt(round(v, 6)))
                v += mn
        g = QLinearGradient(r.left(), 0, r.right(), 0)
        bg = QColor(T.BG)
        clear = QColor(bg.red(), bg.green(), bg.blue(), 0)
        g.setColorAt(0, bg)
        g.setColorAt(0.1, clear)
        g.setColorAt(0.9, clear)
        g.setColorAt(1, bg)
        p.fillRect(r, g)
        if self.isEnabled():
            p.setPen(QPen(T.ACCENT, 2))
            p.drawLine(QPointF(cx, 0), QPointF(cx, h))
            lab = self._label()
            f = T.num_font(13, QFont.Bold)
            p.setFont(f)
            w = T.text_width(f, lab) + 16
            pill = QRectF(cx - w / 2, 4, w, 20)
            T.rounded(p, pill, 5, T.ACCENT)
            p.setPen(QColor("white"))
            p.drawText(pill, Qt.AlignCenter, lab)
            p.setFont(T.ui_font(10.5, QFont.DemiBold))
            p.setPen(T.TEXT3)
            p.drawText(QRectF(r.right() - 150, h - 17, 140, 14), Qt.AlignRight | Qt.AlignVCenter, self.hint)
        p.restore()


def _nice(x):
    if x <= 0:
        return 1
    e = 10 ** math.floor(math.log10(x))
    for m in (1, 2, 5, 10):
        if x <= m * e:
            return m * e
    return 10 * e


# ---------------------------------------------------------------------------- chip strip
class ChipStrip(QWidget):
    """Row of one-click values; the current one is filled blue."""
    picked = Signal(object)

    def __init__(self, parent=None, height=36, font_px=16):
        super().__init__(parent)
        self.items, self.current = [], None
        self.setFixedHeight(height)
        self.font_px = font_px
        self.setCursor(Qt.PointingHandCursor)

    def set_items(self, items, current):
        self.items, self.current = list(items), current
        self.update()

    def _rects(self):
        n = max(len(self.items), 1)
        gap = 6
        w = (self.width() - gap * (n - 1)) / n
        return [QRectF(i * (w + gap), 0, w, self.height()) for i in range(len(self.items))]

    def mousePressEvent(self, e):
        for (v, _), r in zip(self.items, self._rects()):
            if r.contains(e.position()):
                self.current = v
                self.update()
                self.picked.emit(v)

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setFont(T.num_font(self.font_px))
        for (v, label), r in zip(self.items, self._rects()):
            on = v == self.current
            T.rounded(p, r, 7, T.ACCENT if on else QColor(255, 255, 255, 16))
            p.setPen(QColor("white") if on else T.TEXT)
            p.drawText(r, Qt.AlignCenter, label)


# ---------------------------------------------------------------------------- meters
def level_to_frac(db, floor=-50.0):
    return max(0.0, min(1.0, (db - floor) / -floor))


METER_WARN_DB, METER_HOT_DB = -12.0, -6.0     # orange from -12 dBFS, red from -6 dBFS


def meter_zone(frac, floor=-50.0):
    """Zone colour of the meter segment at `frac` (0 = floor, 1 = 0 dBFS)."""
    db = floor + frac * -floor
    return T.METER_R if db >= METER_HOT_DB else (T.METER_Y if db >= METER_WARN_DB else T.METER_G)


def meter_segment(i, n, lit, pk):
    """Colour of segment i of n: lit in its zone colour, the peak-hold segment bright, the rest a dim tint of
    the zone so the orange and red sections always show (as on the phone app's meters)."""
    base = meter_zone((i + 0.5) / n)
    if i < lit:
        return base
    if i == pk and pk > 0:
        return base.lighter(135)
    return QColor(base.red(), base.green(), base.blue(), 45)


class PeakHold:
    """Peak marker that holds for 1.5 s and then falls 20 dB/s."""

    def __init__(self):
        self.db, self.t, self.last = -90.0, 0.0, time.monotonic()

    def feed(self, db):
        now = time.monotonic()
        dt, self.last = now - self.last, now
        if db >= self.db:
            self.db, self.t = db, now
        elif now - self.t > 1.5:
            self.db = max(db, self.db - 20 * dt)
        return self.db

    def reset(self):
        self.db, self.t = -90.0, 0.0


class VMeter(QWidget):
    """Vertical stereo LED meter with dB scale and peak hold (Blackmagic style). Click -> clicked."""
    clicked = Signal()
    SCALE = (0, -6, -12, -20, -30, -45)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.levels = [-90.0, -90.0]
        self.peaks = [PeakHold(), PeakHold()]
        self.gain_text = ""
        self.active = False
        self.setFixedWidth(64)
        self.setCursor(Qt.PointingHandCursor)
        self.setToolTip("Audio - click for level, mute and listen")

    def set_levels(self, rms_db, peak_db=None):
        self.levels = list(rms_db)
        for ph, v in zip(self.peaks, peak_db or rms_db):
            ph.feed(v)
        sig = (tuple(int(level_to_frac(x) * 80) for x in self.levels),
               tuple(int(level_to_frac(ph.db) * 80) for ph in self.peaks), self.gain_text, self.active)
        if sig != getattr(self, "_sig", None):
            self._sig = sig
            self.update()

    def mousePressEvent(self, e):
        self.clicked.emit()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        T.rounded(p, r, 10, T.BG1, QPen(QColor(47, 124, 246, 180) if self.active else T.LINE, 1))
        p.setFont(T.label_font(9))
        p.setPen(QColor("#cfe0ff") if self.active else T.TEXT2)
        p.drawText(QRectF(0, 8, r.width(), 12), Qt.AlignCenter, "Audio")
        top, bot = 30.0, r.height() - 30
        seg_h, gap = 6.0, 2.0
        n = int((bot - top + gap) // (seg_h + gap))
        bot = top + n * (seg_h + gap) - gap
        p.setFont(T.num_font(9, QFont.Medium))
        p.setPen(T.TEXT3)
        for db in self.SCALE:
            y = bot - level_to_frac(db) * (bot - top)
            p.drawText(QRectF(2, y - 6, 20, 12), Qt.AlignRight | Qt.AlignVCenter, str(db))
        for ch in range(2):
            x = 28 + ch * 15
            lit = level_to_frac(self.levels[ch]) * n
            pk = int(level_to_frac(self.peaks[ch].db) * n)
            for i in range(n):
                y = bot - (i + 1) * (seg_h + gap) + gap
                p.fillRect(QRectF(x, y, 11, seg_h), meter_segment(i, n, lit, pk))
        p.setFont(T.num_font(11))
        p.setPen(T.TEXT)
        p.drawText(QRectF(0, r.height() - 24, r.width(), 16), Qt.AlignCenter, self.gain_text)


class HMeter(QWidget):
    """Horizontal two-channel LED meter with scale (the phone app's audio panel)."""
    SCALE = (-45, -30, -20, -10, -6, -3, 0)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.levels = [-90.0, -90.0]
        self.peaks = [PeakHold(), PeakHold()]
        self.setFixedHeight(56)

    def set_levels(self, rms_db, peak_db=None):
        self.levels = list(rms_db)
        for ph, v in zip(self.peaks, peak_db or rms_db):
            ph.feed(v)
        sig = (tuple(int(level_to_frac(x) * 80) for x in self.levels),
               tuple(int(level_to_frac(ph.db) * 80) for ph in self.peaks))
        changed = sig != getattr(self, "_sig", None)
        self._sig = sig
        if changed:
            self.update()
        return changed

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        self.render_into(p)

    def render_into(self, p):
        x0, x1 = 22.0, self.width() - 4.0
        seg_w, gap = 9.0, 2.0
        n = int((x1 - x0 + gap) // (seg_w + gap))
        x1 = x0 + n * (seg_w + gap) - gap
        p.setFont(T.num_font(12))
        for ch in range(2):
            y = 4 + ch * 20
            p.setPen(T.TEXT)
            p.drawText(QRectF(0, y - 3, 16, 16), Qt.AlignCenter, str(ch + 1))
            lit = level_to_frac(self.levels[ch]) * n
            pk = int(level_to_frac(self.peaks[ch].db) * n)
            for i in range(n):
                p.fillRect(QRectF(x0 + i * (seg_w + gap), y, seg_w, 10), meter_segment(i, n, lit, pk))
        p.setFont(T.num_font(10.5, QFont.Medium))
        p.setPen(T.TEXT2)
        for db in self.SCALE:
            x = x0 + level_to_frac(db) * (x1 - x0)
            p.drawText(QRectF(x - 16, 42, 32, 12), Qt.AlignCenter, str(db))


# ---------------------------------------------------------------------------- knob / record
class Knob(QWidget):
    """Rotary gain control, -60..+12 dB (-60 shows as -inf). Drag up/down or scroll; double-click = 0 dB."""
    changed = Signal(float)
    LO, HI = -60.0, 12.0

    def __init__(self, parent=None):
        super().__init__(parent)
        self.value = 0.0
        self.setFixedSize(78, 78)
        self.setCursor(Qt.SizeVerCursor)
        self._drag = None

    def set_value(self, v, emit=False):
        v = max(self.LO, min(self.HI, round(v * 2) / 2))
        if v != self.value:
            self.value = v
            self.update()
            if emit:
                self.changed.emit(v)

    def mousePressEvent(self, e):
        self._drag = (e.position().y(), self.value)

    def mouseMoveEvent(self, e):
        if self._drag:
            y0, v0 = self._drag
            self.set_value(v0 + (y0 - e.position().y()) * 0.25, emit=True)

    def mouseReleaseEvent(self, e):
        self._drag = None

    def mouseDoubleClickEvent(self, e):
        self.set_value(0.0, emit=True)

    def wheelEvent(self, e):
        d = e.angleDelta().y()
        if d:
            self.set_value(self.value + 0.5 * max(1, abs(d) // 120) * (1 if d > 0 else -1), emit=True)

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(4, 4, self.width() - 8, self.height() - 8)
        frac = (self.value - self.LO) / (self.HI - self.LO)
        p.setPen(QPen(T.BG3, 6, Qt.SolidLine, Qt.RoundCap))
        p.drawArc(r, 225 * 16, -270 * 16)
        p.setPen(QPen(T.ACCENT, 6, Qt.SolidLine, Qt.RoundCap))
        p.drawArc(r, 225 * 16, int(-270 * frac * 16))
        inner = r.adjusted(9, 9, -9, -9)
        g = QLinearGradient(inner.topLeft(), inner.bottomRight())
        g.setColorAt(0, QColor("#3a404a"))
        g.setColorAt(1, QColor("#1b1e23"))
        p.setPen(Qt.NoPen)
        p.setBrush(g)
        p.drawEllipse(inner)
        ang = math.radians(225 - 270 * frac)
        c = inner.center()
        p.setPen(QPen(QColor("white"), 3, Qt.SolidLine, Qt.RoundCap))
        p.drawLine(QPointF(c.x() + math.cos(ang) * 10, c.y() - math.sin(ang) * 10),
                   QPointF(c.x() + math.cos(ang) * (inner.width() / 2 - 5), c.y() - math.sin(ang) * (inner.width() / 2 - 5)))


class RecordButton(QAbstractButton):
    """Big white ring with a red dot; while recording the dot becomes a rounded square."""

    def __init__(self, size=72, parent=None):
        super().__init__(parent)
        self.setCheckable(True)
        self.setFixedSize(size, size)
        self.setCursor(Qt.PointingHandCursor)
        self.setToolTip("Start / stop recording on the phone")

    def sizeHint(self):
        return QSize(self.width(), self.height())

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        s = self.width()
        ring = s * 0.05
        p.setPen(QPen(QColor("white") if self.isEnabled() else T.TEXT3, ring))
        p.setBrush(Qt.NoBrush)
        p.drawEllipse(QRectF(ring / 2 + 1, ring / 2 + 1, s - ring - 2, s - ring - 2))
        col = T.REC if self.isChecked() else T.REC_DARK
        if not self.isEnabled():
            col = QColor(110, 40, 38)
        p.setPen(Qt.NoPen)
        p.setBrush(col)
        if self.isChecked():
            q = s * 0.36
            T.rounded(p, QRectF((s - q) / 2, (s - q) / 2, q, q), q * 0.22, col)
        else:
            d = s * 0.5
            p.drawEllipse(QRectF((s - d) / 2, (s - d) / 2, d, d))


# ---------------------------------------------------------------------------- popover / panels
class Popover(QFrame):
    """Floating glass panel that closes when you click elsewhere."""

    def __init__(self, parent=None):
        super().__init__(parent, Qt.Popup | Qt.FramelessWindowHint | Qt.NoDropShadowWindowHint)
        self.setAttribute(Qt.WA_TranslucentBackground)

    closed = Signal()

    def hideEvent(self, e):
        super().hideEvent(e)
        self.closed.emit()

    def show_near(self, widget, where="below", gap=8):
        self.adjustSize()
        g = widget.mapToGlobal(widget.rect().topLeft())
        if where == "below":
            x, y = g.x(), g.y() + widget.height() + gap
        elif where == "left":
            x, y = g.x() - self.width() - gap, g.y() + (widget.height() - self.height()) // 2
        else:   # above
            x, y = g.x() + widget.width() - self.width(), g.y() - self.height() - gap
        scr = widget.screen().availableGeometry()
        x = max(scr.left() + 4, min(x, scr.right() - self.width() - 4))
        y = max(scr.top() + 4, min(y, scr.bottom() - self.height() - 4))
        self.move(x, y)
        self.show()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        T.rounded(p, QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5), 12, QColor(18, 20, 24, 246), T.LINE2)


class GlassPanel(QWidget):
    """Translucent rounded panel drawn over the video (child widgets lay out inside)."""

    def __init__(self, parent=None, radius=12):
        super().__init__(parent)
        self.radius = radius
        self.setAttribute(Qt.WA_StyledBackground, False)

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        T.rounded(p, QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5), self.radius, T.GLASS, T.LINE)


def paint_histogram(p: QPainter, rect: QRectF, hist):
    """hist: {'r','g','b': list of 0..1 heights}. Additive RGB like the phone's histogram."""
    if not hist:
        return
    p.save()
    p.setPen(QPen(QColor(255, 255, 255, 20), 1))
    for gx in (0.25, 0.5, 0.75):
        x = rect.left() + rect.width() * gx
        p.drawLine(QPointF(x, rect.top()), QPointF(x, rect.bottom()))
    p.setCompositionMode(QPainter.CompositionMode_Plus)
    for key, col in (("r", QColor(255, 77, 77)), ("g", QColor(61, 220, 132)), ("b", QColor(77, 141, 255))):
        vals = hist.get(key)
        if vals is None or len(vals) < 2:
            continue
        path = QPainterPath(QPointF(rect.left(), rect.bottom()))
        n = len(vals)
        for i, v in enumerate(vals):
            path.lineTo(rect.left() + rect.width() * i / (n - 1), rect.bottom() - float(v) * (rect.height() - 2))
        path.lineTo(rect.right(), rect.bottom())
        path.closeSubpath()
        fill = QColor(col)
        fill.setAlpha(110)
        p.fillPath(path, fill)
        p.strokePath(path, QPen(col, 1))
    p.restore()
