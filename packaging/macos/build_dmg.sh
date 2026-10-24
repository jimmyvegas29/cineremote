#!/usr/bin/env bash
# Build dist/CineRemote-<version>-macos-<arch>.dmg from dist/CineRemote.app: a drag-to-Applications window
# (app icon, arrow, Applications link). Also what the in-app updater installs (it mounts the image).
# Run from the repository root after packaging/macos/build_app.sh (or call this from it).
set -euo pipefail
VERSION=$(python3 -c 'import re;print(re.search(r"__version__ = \"(.+?)\"", open("cineremote/__init__.py").read()).group(1))')
ARCH=$(uname -m)
APP=dist/CineRemote.app
DMG="dist/CineRemote-$VERSION-macos-$ARCH.dmg"
VOL="CineRemote $VERSION"
[ -d "$APP" ] || { echo "build $APP first"; exit 1; }

STAGE=$(mktemp -d)
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
mkdir "$STAGE/.background"
# one HiDPI-aware background (1x + 2x in a single TIFF)
tiffutil -cathidpicheck assets/dmg-background.png assets/dmg-background@2x.png -out "$STAGE/.background/background.tiff" >/dev/null
cp assets/cineremote.icns "$STAGE/.VolumeIcon.icns"

# hdiutil fails now and then on CI runners ("Resource busy"): retry, and let its errors show.
retry() { for i in 1 2 3 4; do "$@" && return 0; echo "[dmg] retry $i: $*" >&2; sleep $((i * 3)); done; return 1; }
RW=$(mktemp -u).dmg
retry hdiutil create -volname "$VOL" -srcfolder "$STAGE" -fs HFS+ -format UDRW -ov "$RW"
MNT=""
for i in 1 2 3 4; do
  MNT=$(hdiutil attach -readwrite -noverify -noautoopen "$RW" | awk -F'\t' '/\/Volumes\//{print $NF}') && [ -n "$MNT" ] && break
  echo "[dmg] attach retry $i" >&2; sleep $((i * 3))
done
[ -n "$MNT" ] || { echo "[dmg] could not mount $RW"; exit 1; }
echo "[dmg] mounted $MNT"
SetFile -a C "$MNT" 2>/dev/null || true          # use .VolumeIcon.icns as the disk icon

# Window layout through Finder. Needs a GUI session (GitHub's macOS runners have one); if Finder can't be
# scripted the image still works, just with default icon positions.
if ! osascript <<EOF
tell application "Finder"
  tell disk "$VOL"
    open
    set current view of container window to icon view
    set toolbar visible of container window to false
    set statusbar visible of container window to false
    set the bounds of container window to {200, 120, 860, 520}
    set opts to the icon view options of container window
    set arrangement of opts to not arranged
    set icon size of opts to 128
    set text size of opts to 13
    set background picture of opts to file ".background:background.tiff"
    set position of item "CineRemote.app" of container window to {170, 190}
    set position of item "Applications" of container window to {490, 190}
    update without registering applications
    delay 1
    close
  end tell
end tell
EOF
then
  echo "[dmg] Finder layout skipped (no scriptable Finder) - default layout"
fi
rm -rf "$MNT/.fseventsd"
sync
# Finder can hold the volume for a few seconds after the layout script: retry, then force.
detached=""
for i in 1 2 3 4 5 6; do
  if hdiutil detach "$MNT" >/dev/null 2>&1; then detached=1; break; fi
  echo "[dmg] detach retry $i" >&2; sleep $((i * 2))
done
[ -n "$detached" ] || hdiutil detach -force "$MNT"
rm -f "$DMG"
retry hdiutil convert "$RW" -format UDZO -imagekey zlib-level=9 -o "$DMG"
rm -rf "$RW" "$STAGE"
echo "[dmg] $DMG"
