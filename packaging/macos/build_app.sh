#!/usr/bin/env bash
# Build dist/CineRemote.app and dist/CineRemote-<version>-macos-<arch>.dmg (packaging/macos/build_dmg.sh) on
# macOS - the .dmg is both what people download and what the in-app updater installs. Run from the repo root.
#
# Signing: SIGN_IDENTITY = the stable self-signed identity's hash (CI: import_signing_identity.sh), so every
# build has the same designated requirement and macOS keeps privacy grants (Local Network) across updates.
# Without it the app is signed ad-hoc ("-"), which is fine for local builds.
# Either way it isn't notarized: first launch needs System Settings > Privacy & Security > Open Anyway.
set -euo pipefail
ID=${SIGN_IDENTITY:--}
python3 -m PyInstaller packaging/cineremote.spec --noconfirm
codesign --force --deep --sign "$ID" dist/CineRemote.app
codesign --verify --deep --strict dist/CineRemote.app
codesign -d -r- dist/CineRemote.app 2>&1 | grep designated || true
bash packaging/macos/build_dmg.sh
