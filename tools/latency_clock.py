"""Big on-screen millisecond clock for glass-to-glass latency tests.

Point the phone at this window; a screenshot then shows the live clock and the clock as seen through
OBS / the webcam. latency = live value - value in the video (both are seconds.milliseconds).
"""
import sys
import time

from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QColor, QFont, QPainter
from PySide6.QtWidgets import QApplication, QWidget


class Clock(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Latency Clock")
        self.resize(760, 300)
        self.t0 = time.perf_counter()
        self.timer = QTimer(self)
        self.timer.setTimerType(Qt.PreciseTimer)
        self.timer.timeout.connect(self.update)
        self.timer.start(1)

    def paintEvent(self, e):
        p = QPainter(self)
        p.fillRect(self.rect(), QColor("#000000"))
        t = time.perf_counter() - self.t0
        p.setPen(QColor("#ffffff"))
        f = QFont("Consolas")
        f.setPixelSize(int(self.height() * 0.62))
        f.setBold(True)
        p.setFont(f)
        p.drawText(self.rect(), Qt.AlignCenter, f"{t % 100:06.3f}")
        # moving bar: position = milliseconds within the second (coarse cross-check)
        x = int((t % 1.0) * self.width())
        p.fillRect(x, self.height() - 18, 12, 18, QColor("#ff3030"))
        p.end()


app = QApplication(sys.argv)
w = Clock()
w.move(20, 20)
w.show()
sys.exit(app.exec())
