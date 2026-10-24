#!/usr/bin/env bash
# Build dist/CineRemote-<version>-macos-<arch>.zip (the .app, used by the in-app updater) and the
# .dmg people download (packaging/macos/build_dmg.sh). Run from the repository root on macOS.
# The app is ad-hoc signed, not notarized: first launch needs System Settings > Privacy & Security >
# Open Anyway, or  xattr -dr com.apple.quarantine /Applications/CineRemote.app
set -euo pipefail
VERSION=$(python3 -c 'import re;print(re.search(r"__version__ = \"(.+?)\"", open("cineremote/__init__.py").read()).group(1))')
ARCH=$(uname -m)
python3 -m PyInstaller packaging/cineremote.spec --noconfirm
codesign --force --deep --sign - dist/CineRemote.app
rm -f "dist/CineRemote-$VERSION-macos-$ARCH.zip"
ditto -c -k --sequesterRsrc --keepParent dist/CineRemote.app "dist/CineRemote-$VERSION-macos-$ARCH.zip"
echo "[app] dist/CineRemote-$VERSION-macos-$ARCH.zip"
bash packaging/macos/build_dmg.sh
