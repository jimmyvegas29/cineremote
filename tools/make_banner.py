"""Render docs/images/banner.png (README header) and docs/images/social-preview.png (1280x640, for the
repository's social preview) from the icon, the bundled fonts and the Studio screenshot."""
import os
import sys

from PySide6.QtCore import QRectF, Qt
from PySide6.QtGui import QColor, QFont, QFontDatabase, QGuiApplication, QImage, QLinearGradient, QPainter
from PySide6.QtSvg import QSvgRenderer

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
FONTS = os.path.join(ROOT, "cineremote", "ui", "fonts")
OUT = os.path.join(ROOT, "docs", "images")


def family(name):
    return QFontDatabase.applicationFontFamilies(QFontDatabase.addApplicationFont(os.path.join(FONTS, name)))[0]


def render(w, h, path, shot_scale):
    img = QImage(w, h, QImage.Format_ARGB32)
    img.fill(QColor("#0b0d11"))
    p = QPainter(img)
    p.setRenderHints(QPainter.Antialiasing | QPainter.SmoothPixmapTransform | QPainter.TextAntialiasing)
    shot = QImage(os.path.join(OUT, "multiview.png"))
    sw = int(w * shot_scale)
    sh = int(shot.height() * sw / shot.width())
    p.setOpacity(0.55)
    p.drawImage(QRectF(w - sw * 0.92, (h - sh) / 2 + h * 0.08, sw, sh), shot)
    p.setOpacity(1.0)
    g = QLinearGradient(0, 0, w, 0)
    g.setColorAt(0.0, QColor(11, 13, 17, 255))
    g.setColorAt(0.45, QColor(11, 13, 17, 245))
    g.setColorAt(1.0, QColor(11, 13, 17, 60))
    p.fillRect(0, 0, w, h, g)
    icon = h * 0.42
    QSvgRenderer(os.path.join(ROOT, "assets", "icon.svg")).render(p, QRectF(h * 0.14, (h - icon) / 2 - h * 0.12, icon, icon))
    x = h * 0.14 + icon + h * 0.08
    title = QFont(family("BarlowSemiCondensed-Bold.ttf"))
    title.setPixelSize(int(h * 0.22))
    p.setFont(title)
    p.setPen(QColor("#f2f4f7"))
    p.drawText(QRectF(x, h * 0.18, w, h * 0.3), Qt.AlignLeft | Qt.AlignVCenter, "CineRemote")
    body = QFont(family("Inter-Variable.ttf"))
    body.setPixelSize(int(min(h * 0.06, w * 0.025)))
    body.setWeight(QFont.Medium)
    p.setFont(body)
    p.setPen(QColor("#c4cad4"))
    p.drawText(QRectF(x, h * 0.47, w - x - 20, h * 0.2), Qt.AlignLeft | Qt.AlignTop | Qt.TextWordWrap,
               "Remote control, live monitoring and OBS output\nfor the Blackmagic Camera app")
    small = QFont(body)
    small.setPixelSize(int(min(h * 0.045, w * 0.022)))
    p.setFont(small)
    p.setPen(QColor("#5b9bff"))
    p.drawText(QRectF(h * 0.14, h * 0.80, w, h * 0.1), Qt.AlignLeft | Qt.AlignVCenter,
               "iPhone · iPad · Android   →   Windows · macOS · Linux · OBS Studio")
    p.end()
    img.save(path)
    print("wrote", path)


if __name__ == "__main__":
    app = QGuiApplication(sys.argv)
    render(1600, 400, os.path.join(OUT, "banner.png"), 0.62)
    render(1280, 640, os.path.join(OUT, "social-preview.png"), 0.85)
