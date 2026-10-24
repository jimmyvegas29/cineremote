#!/usr/bin/env bash
# Build dist/CineRemote-<version>-macos-<arch>.zip (the .app) on macOS. Run from the repository root.
# The app is ad-hoc signed, not notarized: on first launch right-click > Open, or run
#   xattr -dr com.apple.quarantine /Applications/CineRemote.app
set -euo pipefail
VERSION=$(python3 -c 'import re;print(re.search(r"__version__ = \"(.+?)\"", open("cineremote/__init__.py").read()).group(1))')
ARCH=$(uname -m)
python3 -m PyInstaller packaging/cineremote.spec --noconfirm
codesign --force --deep --sign - dist/CineRemote.app
rm -f "dist/CineRemote-$VERSION-macos-$ARCH.zip"
ditto -c -k --sequesterRsrc --keepParent dist/CineRemote.app "dist/CineRemote-$VERSION-macos-$ARCH.zip"
echo "[app] dist/CineRemote-$VERSION-macos-$ARCH.zip"
