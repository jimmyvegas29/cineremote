#!/usr/bin/env bash
# Build dist/CineRemote-<version>-linux-x86_64.AppImage from the PyInstaller bundle.
# Run from the repository root after: pyinstaller packaging/cineremote.spec --noconfirm
set -euo pipefail
VERSION=$(python3 -c 'import re;print(re.search(r"__version__ = \"(.+?)\"", open("cineremote/__init__.py").read()).group(1))')
APPDIR=build/AppDir
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/lib" "$APPDIR/usr/share/applications" "$APPDIR/usr/share/icons/hicolor/256x256/apps"
cp -a dist/CineRemote "$APPDIR/usr/lib/cineremote"
cp packaging/linux/cineremote.desktop "$APPDIR/cineremote.desktop"
cp packaging/linux/cineremote.desktop "$APPDIR/usr/share/applications/"
cp assets/icon-256.png "$APPDIR/cineremote.png"
cp assets/icon-256.png "$APPDIR/usr/share/icons/hicolor/256x256/apps/cineremote.png"
cat > "$APPDIR/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
# harmless Wayland input-method noise from Qt
export QT_LOGGING_RULES="qt.qpa.wayland.textinput=false${QT_LOGGING_RULES:+;$QT_LOGGING_RULES}"
exec "$HERE/usr/lib/cineremote/CineRemote" "$@"
RUN
chmod +x "$APPDIR/AppRun"
TOOL=build/appimagetool-x86_64.AppImage
if [ ! -x "$TOOL" ]; then
  curl -fsSL -o "$TOOL" https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
  chmod +x "$TOOL"
fi
# extract-and-run: works without FUSE (CI runners, containers)
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$APPDIR" "dist/CineRemote-$VERSION-linux-x86_64.AppImage"
echo "[appimage] dist/CineRemote-$VERSION-linux-x86_64.AppImage"
