#!/usr/bin/env bash
# Build dist/CineRemote.app and dist/CineRemote-<version>-macos-<arch>.dmg (packaging/macos/build_dmg.sh) on
# macOS - the .dmg is both what people download and what the in-app updater installs. Run from the repo root.
# The app is ad-hoc signed, not notarized: first launch needs System Settings > Privacy & Security >
# Open Anyway, or  xattr -dr com.apple.quarantine /Applications/CineRemote.app
set -euo pipefail
python3 -m PyInstaller packaging/cineremote.spec --noconfirm
codesign --force --deep --sign - dist/CineRemote.app
bash packaging/macos/build_dmg.sh
