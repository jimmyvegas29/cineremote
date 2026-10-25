"""Colors, fonts, global stylesheet and small painting helpers shared by both views.

Fonts: on Windows the built-ins Segoe UI Variable (UI text), Bahnschrift (Blackmagic-style numerals and
timecode) and Segoe Fluent Icons. On Linux/macOS the bundled open equivalents in ui/fonts/: Inter,
Barlow Semi Condensed (DIN-like) and Microsoft's Fluent UI System Icons (MIT) - same icon set, other
code points. Licenses are next to the fonts.
"""
import os
import sys

from PySide6.QtCore import QRectF, Qt
from PySide6.QtGui import QColor, QFont, QFontDatabase, QFontMetrics, QPainter, QPainterPath, QPalette, QPen

BG = QColor("#0b0c0e")
BG1 = QColor("#121418")
BG2 = QColor("#181b20")
BG3 = QColor("#20242b")
LINE = QColor(255, 255, 255, 20)
LINE2 = QColor(255, 255, 255, 36)
TEXT = QColor("#f2f4f7")
TEXT2 = QColor("#a9b0bb")
TEXT3 = QColor("#6c7380")
ACCENT = QColor("#2f7cf6")
ACCENT_SOFT = QColor(47, 124, 246, 46)
SEL_BG = QColor("#17202f")
REC = QColor("#e5322d")
REC_DARK = QColor("#c0201c")
OK = QColor("#2ecc71")
WARN = QColor("#f5c518")
GLASS = QColor(14, 16, 19, 190)
METER_G, METER_Y, METER_R = QColor("#22c55e"), QColor("#f59e0b"), QColor("#ef4444")   # green, orange, red

WINDOWS = sys.platform == "win32"
FONT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fonts")

if WINDOWS:
    UI_FAMILY = "Segoe UI Variable Text"
    NUM_FAMILY = "Bahnschrift"
    ICON_FAMILY = "Segoe Fluent Icons"
    # Segoe Fluent Icons code points
    IC_ADD, IC_MULTI, IC_MONITOR, IC_CAMERA = "\uE710", "\uE8A9", "\uE7F4", "\uE722"
    IC_SETTINGS, IC_HELP, IC_STOP, IC_RECORD = "\uE713", "\uE897", "\uE71A", "\uE7C8"
    IC_MIC, IC_HEADPHONE, IC_MUTE, IC_VOLUME = "\uE720", "\uE7F6", "\uE74F", "\uE767"
    IC_TAG, IC_FULLSCREEN, IC_HISTO, IC_SLIDERS = "\uE8EC", "\uE740", "\uE9D2", "\uE9E9"
    IC_SUN, IC_BULB, IC_FOCUS, IC_STAB, IC_CLOUD = "\uE706", "\uEA80", "\uE81D", "\uE7AD", "\uE753"
    IC_LUT, IC_DOWNLOAD = "\uE790", "\uE896"
else:
    UI_FAMILY = "Inter"
    NUM_FAMILY = "Barlow Semi Condensed"
    ICON_FAMILY = "FluentSystemIcons-Regular"
    # Fluent UI System Icons (24 px regular) code points for the same glyphs
    IC_ADD, IC_MULTI, IC_MONITOR, IC_CAMERA = "\uF10A", "\uF463", "\uF35A", "\uF255"
    IC_SETTINGS, IC_HELP, IC_STOP, IC_RECORD = "\uF6AA", "\uF63E", "\uF72B", "\uF662"
    IC_MIC, IC_HEADPHONE, IC_MUTE, IC_VOLUME = "\uE7FA", "\uF472", "\uEB4B", "\uEB43"
    IC_TAG, IC_FULLSCREEN, IC_HISTO, IC_SLIDERS = "\uF77D", "\uE685", "\uF342", "\uF588"
    IC_SUN, IC_BULB, IC_FOCUS, IC_STAB, IC_CLOUD = "\uF8A2", "\uF4D7", "\uF782", "\uF186", "\uF86F"
    IC_LUT, IC_DOWNLOAD = "\uF2F6", "\uF151"


def load_fonts():
    """Register the bundled fonts (Linux/macOS). Must run after QApplication exists."""
    global ICON_FAMILY
    if WINDOWS:
        return
    for f in sorted(os.listdir(FONT_DIR)):
        if f.lower().endswith(".ttf"):
            fid = QFontDatabase.addApplicationFont(os.path.join(FONT_DIR, f))
            fams = QFontDatabase.applicationFontFamilies(fid) if fid >= 0 else []
            if f.startswith("FluentSystemIcons") and fams:
                ICON_FAMILY = fams[0]


def ui_font(px, weight=QFont.Normal):
    f = QFont(UI_FAMILY)
    f.setPixelSize(max(1, int(round(px))))
    f.setWeight(weight)
    return f


def num_font(px, weight=QFont.DemiBold):
    f = QFont(NUM_FAMILY)
    f.setPixelSize(max(1, int(round(px))))
    f.setWeight(weight)
    return f


def icon_font(px):
    f = QFont(ICON_FAMILY)
    f.setPixelSize(max(1, int(round(px))))
    return f


def label_font(px=11):
    f = ui_font(px, QFont.DemiBold)
    f.setLetterSpacing(QFont.AbsoluteSpacing, px * 0.08)
    f.setCapitalization(QFont.AllUppercase)
    return f


def text_width(font, text):
    return QFontMetrics(font).horizontalAdvance(text)


def rounded(p: QPainter, rect, radius, fill=None, pen=None):
    path = QPainterPath()
    path.addRoundedRect(QRectF(rect), radius, radius)
    if fill is not None:
        p.fillPath(path, fill)
    if pen is not None:
        p.strokePath(path, pen if isinstance(pen, QPen) else QPen(pen, 1))


def chip(p: QPainter, x, y, text, font=None, fill=QColor(0, 0, 0, 140), color=TEXT, border=LINE2, h=24, pad=9,
         dot=None):
    """Draw a pill label; returns its width."""
    font = font or ui_font(12, QFont.DemiBold)
    w = text_width(font, text) + pad * 2 + (14 if dot is not None else 0)
    rounded(p, QRectF(x, y, w, h), 6, fill, border)
    tx = x + pad
    if dot is not None:
        p.setPen(Qt.NoPen)
        p.setBrush(dot)
        p.drawEllipse(QRectF(tx, y + h / 2 - 4, 8, 8))
        tx += 14
    p.setPen(color)
    p.setFont(font)
    p.drawText(QRectF(tx, y, w, h), Qt.AlignVCenter | Qt.AlignLeft, text)
    return w


def badge_a(p: QPainter, x, y, size=15):
    rounded(p, QRectF(x, y, size, size), 3, ACCENT)
    p.setPen(QColor("white"))
    p.setFont(ui_font(int(size * 0.68), QFont.Bold))
    p.drawText(QRectF(x, y, size, size), Qt.AlignCenter, "A")


def slate_badge(p: QPainter, x, y, letter, size=20, fit=False):
    """White slate tag; fit=True widens it to the whole text (a long slate name). Returns its width."""
    f = ui_font(int(size * 0.55), QFont.ExtraBold)
    w = max(size, text_width(f, letter) + size * 0.6) if fit else size
    rounded(p, QRectF(x, y, w, size), size * 0.25, QColor("white"))
    p.setPen(QColor("black"))
    p.setFont(f)
    p.drawText(QRectF(x, y, w, size), Qt.AlignCenter, letter)
    return w


def apply(app):
    load_fonts()
    app.setStyle("Fusion")
    pal = QPalette()
    for role, c in ((QPalette.Window, BG1), (QPalette.WindowText, TEXT), (QPalette.Base, BG), (QPalette.AlternateBase, BG2),
                    (QPalette.Text, TEXT), (QPalette.Button, BG2), (QPalette.ButtonText, TEXT), (QPalette.Highlight, ACCENT),
                    (QPalette.HighlightedText, QColor("white")), (QPalette.ToolTipBase, BG2), (QPalette.ToolTipText, TEXT),
                    (QPalette.PlaceholderText, TEXT3)):
        pal.setColor(role, c)
    pal.setColor(QPalette.Disabled, QPalette.Text, TEXT3)
    pal.setColor(QPalette.Disabled, QPalette.ButtonText, TEXT3)
    pal.setColor(QPalette.Disabled, QPalette.WindowText, TEXT3)
    app.setPalette(pal)
    app.setFont(ui_font(13))
    app.setStyleSheet(f"""
        QToolTip {{ background: {BG2.name()}; color: {TEXT.name()}; border: 1px solid #2a2f37; padding: 5px 7px; border-radius: 6px; }}
        QScrollArea {{ border: none; background: transparent; }}
        QScrollBar:vertical {{ background: transparent; width: 8px; margin: 2px; }}
        QScrollBar::handle:vertical {{ background: #2a2f37; border-radius: 4px; min-height: 30px; }}
        QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; }}
        QPushButton {{ background: {BG2.name()}; border: 1px solid #262a31; border-radius: 8px; padding: 6px 12px; color: {TEXT.name()}; }}
        QPushButton:hover {{ background: {BG3.name()}; }}
        QPushButton:pressed {{ background: #2a2f37; }}
        QPushButton:disabled {{ color: {TEXT3.name()}; }}
        QPushButton#flat {{ background: transparent; border: none; color: {TEXT2.name()}; padding: 6px 10px; border-radius: 7px; }}
        QPushButton#flat:hover {{ background: {BG3.name()}; color: {TEXT.name()}; }}
        QPushButton#flat:checked {{ background: {BG3.name()}; color: {TEXT.name()}; }}
        QPushButton#flatRec {{ background: transparent; border: none; color: #ff8f8b; padding: 6px 10px; border-radius: 7px; }}
        QPushButton#flatRec:hover {{ background: rgba(229,50,45,0.15); }}
        QPushButton#pill {{ background: rgba(255,255,255,0.07); border: 1px solid rgba(255,255,255,0.08); border-radius: 7px;
                            padding: 5px 10px; font-weight: 600; font-size: 12px; }}
        QPushButton#pill:checked {{ background: rgba(47,124,246,0.20); border-color: rgba(47,124,246,0.55); color: #cfe0ff; }}
        QPushButton#toggle {{ background: rgba(0,0,0,0.6); border: 1px solid rgba(255,255,255,0.14); border-radius: 7px;
                              padding: 4px 10px; font-weight: 600; font-size: 11.5px; color: {TEXT2.name()}; }}
        QPushButton#toggle:checked {{ color: white; border-color: rgba(47,124,246,0.7); background: rgba(47,124,246,0.28); }}
        QComboBox {{ background: {BG.name()}; border: 1px solid #262a31; border-radius: 7px; padding: 4px 10px; min-height: 22px; }}
        QComboBox QAbstractItemView {{ background: {BG2.name()}; border: 1px solid #2a2f37; selection-background-color: {ACCENT.name()}; }}
        QLineEdit, QSpinBox {{ background: {BG.name()}; border: 1px solid #262a31; border-radius: 7px; padding: 5px 8px; }}
        QTabWidget::pane {{ border: 1px solid #262a31; border-radius: 8px; }}
        QTabBar::tab {{ background: transparent; padding: 7px 12px; color: {TEXT2.name()}; }}
        QTabBar::tab:selected {{ color: {TEXT.name()}; border-bottom: 2px solid {ACCENT.name()}; }}
        QMenu {{ background: {BG2.name()}; border: 1px solid #2a2f37; padding: 4px; border-radius: 8px; }}
        QMenu::item {{ padding: 6px 18px; border-radius: 5px; }}
        QMenu::item:selected {{ background: {BG3.name()}; }}
        QStatusBar {{ background: #08090b; color: {TEXT3.name()}; border-top: 1px solid #16181c; }}
        QDialog {{ background: {BG1.name()}; }}
    """)
