"""Audio popover (PC-side level to OBS, mute, listen) and the PC headphone monitor."""
import collections
import threading

from PySide6.QtCore import QObject, QSettings, Qt, QTimer, Signal
from PySide6.QtMultimedia import QAudioFormat, QAudioSink, QMediaDevices
from PySide6.QtWidgets import QHBoxLayout, QLabel, QPushButton, QSlider, QVBoxLayout, QWidget

from . import theme as T
from .widgets import Knob, Popover


def gain_text(db, mute=False):
    if mute:
        return "MUTED"
    if db <= Knob.LO:
        return "-∞ dB"
    return f"{db:+.1f} dB" if db else "0.0 dB"


class AudioMonitor(QObject):
    """Plays one camera's (post-gain) audio on the PC's default output. The backend's audio thread
    pushes PCM into a small queue; a GUI timer feeds QAudioSink and drops backlog beyond ~150 ms so
    listening never drifts behind the picture."""
    changed = Signal()
    MAX_BACKLOG = 48000 * 4 * 15 // 100      # bytes of s16 stereo 48 kHz = 150 ms

    def __init__(self, backend, parent=None):
        super().__init__(parent)
        self.backend = backend
        self.key = None
        self.volume = 0.8
        self._q = collections.deque()
        self._qbytes = 0
        self._lock = threading.Lock()
        self._sink = self._dev = None
        self._timer = QTimer(self)
        self._timer.setInterval(10)
        self._timer.timeout.connect(self._pump)

    def listening(self, key=None):
        return self.key is not None and (key is None or key == self.key)

    def start(self, key):
        self.stop()
        fmt = QAudioFormat()
        fmt.setSampleRate(48000)
        fmt.setChannelCount(2)
        fmt.setSampleFormat(QAudioFormat.Int16)
        self._sink = QAudioSink(QMediaDevices.defaultAudioOutput(), fmt, self)
        self._sink.setBufferSize(48000 * 4 // 10)          # 100 ms device buffer
        self._sink.setVolume(self.volume)
        self._dev = self._sink.start()
        self.key = key
        self.backend.audio_tap_key = key
        self.backend.audio_tap = self._push
        self._timer.start()
        self.changed.emit()

    def stop(self):
        self.backend.audio_tap = None
        self.backend.audio_tap_key = None
        self._timer.stop()
        if self._sink is not None:
            self._sink.stop()
            self._sink.deleteLater()
        self._sink = self._dev = None
        with self._lock:
            self._q.clear()
            self._qbytes = 0
        was, self.key = self.key, None
        if was is not None:
            self.changed.emit()

    def set_volume(self, v):
        self.volume = v
        if self._sink is not None:
            self._sink.setVolume(v)

    def device_name(self):
        return QMediaDevices.defaultAudioOutput().description()

    def _push(self, pcm):                       # backend audio thread
        b = pcm.tobytes()
        with self._lock:
            self._q.append(b)
            self._qbytes += len(b)
            while self._qbytes > self.MAX_BACKLOG and len(self._q) > 1:
                self._qbytes -= len(self._q.popleft())

    def _pump(self):
        if self._dev is None:
            return
        free = self._sink.bytesFree()
        while free > 0:
            with self._lock:
                if not self._q:
                    break
                b = self._q.popleft()
                if len(b) > free:
                    self._q.appendleft(b[free:])
                    b = b[:free]
                self._qbytes -= len(b)
            self._dev.write(b)
            free -= len(b)


class AudioPanel(QWidget):
    """Contents of the audio popover; bound to one camera at a time."""

    def __init__(self, backend, monitor: AudioMonitor, parent=None):
        super().__init__(parent)
        self.backend, self.monitor = backend, monitor
        self.key = None
        self.settings = QSettings("CineRemote", "CineRemote")
        self.setFixedWidth(330)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 14, 16, 14)
        lay.setSpacing(10)
        hdr = QHBoxLayout()
        t = QLabel("AUDIO")
        t.setFont(T.label_font(11))
        self.src = QLabel("")
        self.src.setStyleSheet(f"color: {T.TEXT2.name()}; font-size: 12px;")
        hdr.addWidget(t)
        hdr.addSpacing(6)
        hdr.addWidget(self.src, 1)
        lay.addLayout(hdr)
        row = QHBoxLayout()
        self.knob = Knob()
        self.knob.changed.connect(self._gain)
        row.addWidget(self.knob)
        col = QVBoxLayout()
        cap = QLabel("Level sent to OBS")
        cap.setStyleSheet(f"color: {T.TEXT2.name()}; font-size: 12px; font-weight: 600;")
        self.val = QLabel("0.0 dB")
        self.val.setFont(T.num_font(30))
        hint = QLabel("drag or scroll · double-click = 0 dB")
        hint.setStyleSheet(f"color: {T.TEXT3.name()}; font-size: 11px;")
        for w in (cap, self.val, hint):
            col.addWidget(w)
        row.addLayout(col, 1)
        lay.addLayout(row)
        btns = QHBoxLayout()
        btns.setSpacing(6)
        self.b_mute = QPushButton("Mute")
        self.b_listen = QPushButton("Listen on PC")
        self.b_reset = QPushButton("Reset peaks")
        for b in (self.b_mute, self.b_listen, self.b_reset):
            b.setObjectName("pill")
            b.setCursor(Qt.PointingHandCursor)
            btns.addWidget(b)
        self.b_mute.setCheckable(True)
        self.b_listen.setCheckable(True)
        self.b_mute.toggled.connect(self._mute)
        self.b_listen.toggled.connect(self._listen)
        self.b_reset.clicked.connect(self._reset)
        lay.addLayout(btns)
        self.vol_row = QWidget()
        vr = QHBoxLayout(self.vol_row)
        vr.setContentsMargins(0, 0, 0, 0)
        self.vol_lbl = QLabel("Headphones")
        self.vol_lbl.setStyleSheet(f"color: {T.TEXT2.name()}; font-size: 11.5px;")
        self.vol = QSlider(Qt.Horizontal)
        self.vol.setRange(0, 100)
        self.vol.setValue(int(monitor.volume * 100))
        self.vol.valueChanged.connect(lambda v: monitor.set_volume(v / 100))
        vr.addWidget(self.vol_lbl)
        vr.addWidget(self.vol, 1)
        lay.addWidget(self.vol_row)
        note = QLabel("The phone's own mic gain is set on the phone; the remote protocol doesn't expose it. "
                      "This is the level the PC passes to OBS.")
        note.setWordWrap(True)
        note.setStyleSheet(f"color: {T.TEXT3.name()}; font-size: 11px;")
        lay.addWidget(note)
        self.peak_reset = None      # callable set by the owning view

    def bind(self, key):
        self.key = key
        h = self.backend.cams.get(key)
        if h is None:
            return
        src = h.props.get("audio_source")
        self.src.setText(f"{src.active_source_name or 'Phone mic'} · {src.num_of_channels or 2} ch · 48 kHz" if src
                         else "Phone mic · 48 kHz")
        for w, v in ((self.knob, None), (self.b_mute, h.audio_mute), (self.b_listen, self.monitor.listening(key))):
            w.blockSignals(True)
            if w is self.knob:
                w.set_value(h.audio_gain_db)
            else:
                w.setChecked(bool(v))
            w.blockSignals(False)
        self._sync()

    def _sync(self):
        h = self.backend.cams.get(self.key)
        if h is None:
            return
        self.val.setText(gain_text(h.audio_gain_db, h.audio_mute))
        self.vol_row.setVisible(self.monitor.listening(self.key))
        self.vol_lbl.setText(f"Headphones ({self.monitor.device_name()})"[:48])

    def _gain(self, db):
        h = self.backend.cams.get(self.key)
        if h:
            h.audio_gain_db = db
            self.settings.setValue(f"audio/{self.key}/gain_db", db)
            self._sync()

    def _mute(self, on):
        h = self.backend.cams.get(self.key)
        if h:
            h.audio_mute = on
            self.settings.setValue(f"audio/{self.key}/mute", on)
            self._sync()

    def _listen(self, on):
        if on and self.key:
            self.monitor.start(self.key)
        elif self.monitor.listening(self.key):
            self.monitor.stop()
        self._sync()

    def _reset(self):
        if self.peak_reset:
            self.peak_reset()


class AudioPopover(Popover):
    def __init__(self, backend, monitor, parent=None):
        super().__init__(parent)
        self.panel = AudioPanel(backend, monitor, self)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(self.panel)


def restore_audio_settings(backend, key):
    """Apply a camera's saved PC-side gain/mute (call when it is first seen)."""
    h = backend.cams.get(key)
    if h is None:
        return
    s = QSettings("CineRemote", "CineRemote")
    try:
        h.audio_gain_db = float(s.value(f"audio/{key}/gain_db", 0.0))
    except (TypeError, ValueError):
        h.audio_gain_db = 0.0
    h.audio_mute = str(s.value(f"audio/{key}/mute", "false")).lower() == "true"
