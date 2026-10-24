"""Render the macOS disk image window background: assets/dmg-background.png (660x400) and @2x.
The app icon sits at (170, 190) and the Applications link at (490, 190) - see packaging/macos/build_dmg.sh."""
import os
import sys

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import QColor, QFont, QFontDatabase, QGuiApplication, QImage, QPainter, QPainterPath, QPen

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
FONTS = os.path.join(ROOT, "cineremote", "ui", "fonts")
W, H = 660, 400


def family(name):
    return QFontDatabase.applicationFontFamilies(QFontDatabase.addApplicationFont(os.path.join(FONTS, name)))[0]


def render(scale, path):
    img = QImage(W * scale, H * scale, QImage.Format_ARGB32)
    dpm = round(72 * scale / 0.0254)       # 72 dpi at 1x, 144 dpi at 2x: macOS pairs them as one HiDPI image
    img.setDotsPerMeterX(dpm)
    img.setDotsPerMeterY(dpm)
    img.fill(QColor("#f4f5f7"))   # light: Finder draws icon labels in black
    p = QPainter(img)
    p.setRenderHints(QPainter.Antialiasing | QPainter.TextAntialiasing)
    p.scale(scale, scale)
    # subtle top glow
    for i in range(60):
        p.fillRect(QRectF(0, i, W, 1), QColor(47, 124, 246, max(0, 14 - i // 4)))
    title = QFont(family("BarlowSemiCondensed-Bold.ttf"))
    title.setPixelSize(30)
    p.setFont(title)
    p.setPen(QColor("#111827"))
    p.drawText(QRectF(0, 28, W, 40), Qt.AlignCenter, "CineRemote")
    body = QFont(family("Inter-Variable.ttf"))
    body.setPixelSize(14)
    p.setFont(body)
    p.setPen(QColor("#4b5563"))
    p.drawText(QRectF(0, 66, W, 22), Qt.AlignCenter, "Drag CineRemote into Applications to install")
    # arrow between the two icon positions
    pen = QPen(QColor("#2f7cf6"), 5, Qt.SolidLine, Qt.RoundCap, Qt.RoundJoin)
    p.setPen(pen)
    p.drawLine(QPointF(262, 190), QPointF(388, 190))
    head = QPainterPath()
    head.moveTo(398, 190)
    head.lineTo(380, 178)
    head.lineTo(380, 202)
    head.closeSubpath()
    p.setPen(Qt.NoPen)
    p.setBrush(QColor("#2f7cf6"))
    p.drawPath(head)
    small = QFont(body)
    small.setPixelSize(12)
    p.setFont(small)
    p.setPen(QColor("#6b7280"))
    p.drawText(QRectF(0, 340, W, 18), Qt.AlignCenter,
               "First launch: System Settings ▸ Privacy & Security ▸ Open Anyway (the app isn't notarized)")
    p.drawText(QRectF(0, 360, W, 18), Qt.AlignCenter, "github.com/jimmyvegas29/cineremote")
    p.end()
    img.save(path)
    print("wrote", path)


if __name__ == "__main__":
    app = QGuiApplication(sys.argv)
    render(1, os.path.join(ROOT, "assets", "dmg-background.png"))
    render(2, os.path.join(ROOT, "assets", "dmg-background@2x.png"))
