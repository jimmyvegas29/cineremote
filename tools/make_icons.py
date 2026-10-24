"""Render assets/icon.svg into the app/installer icons (needs PySide6 + Pillow):
    assets/icon-1024.png, assets/icon-256.png, assets/cineremote.ico (16-256), assets/cineremote.icns
    and cineremote/ui/icon.png (window icon shipped with the app).
"""
import os
import sys

from PIL import Image
from PySide6.QtCore import QRectF, Qt
from PySide6.QtGui import QGuiApplication, QImage, QPainter
from PySide6.QtSvg import QSvgRenderer

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.normpath(os.path.join(HERE, "..", "assets"))


def render(size):
    r = QSvgRenderer(os.path.join(ASSETS, "icon.svg"))
    img = QImage(size, size, QImage.Format_ARGB32)
    img.fill(Qt.transparent)
    p = QPainter(img)
    p.setRenderHint(QPainter.Antialiasing)
    p.setRenderHint(QPainter.SmoothPixmapTransform)
    r.render(p, QRectF(0, 0, size, size))
    p.end()
    path = os.path.join(ASSETS, f"_tmp_{size}.png")
    img.save(path)
    im = Image.open(path).convert("RGBA")
    im.load()
    os.remove(path)
    return im


if __name__ == "__main__":
    app = QGuiApplication(sys.argv)
    big = render(1024)
    big.save(os.path.join(ASSETS, "icon-1024.png"))
    render(256).save(os.path.join(ASSETS, "icon-256.png"))
    render(256).save(os.path.join(ASSETS, "..", "cineremote", "ui", "icon.png"))
    big.save(os.path.join(ASSETS, "cineremote.ico"), sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
    big.save(os.path.join(ASSETS, "cineremote.icns"))
    print("icons written to", ASSETS)
