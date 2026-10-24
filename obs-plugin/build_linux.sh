#!/usr/bin/env bash
# Build + install the CineRemote Camera OBS source on Linux.
# Needs OBS with its development headers (Ubuntu: `sudo add-apt-repository ppa:obsproject/obs-studio`,
# then `sudo apt install obs-studio build-essential` - the PPA package includes /usr/include/obs).
#   ./build_linux.sh            build into out/
#   ./build_linux.sh --install  also copy into ~/.config/obs-studio/plugins (restart OBS afterwards)
set -euo pipefail
cd "$(dirname "$0")"
OBS_INC=${OBS_INC:-/usr/include/obs}
[ -f "$OBS_INC/obs-module.h" ] || { echo "OBS headers not found in $OBS_INC (install obs-studio from the obsproject PPA)"; exit 1; }
mkdir -p out
cc -O2 -fPIC -shared -std=gnu11 -Wall -Wno-unused-parameter -fvisibility=hidden \
   -I"$OBS_INC" -Isrc src/plugin-main.c -o out/cineremote-obs.so -lobs -lpthread -lrt
echo "[build] OK -> out/cineremote-obs.so"
if [ "${1:-}" = "--install" ]; then
  dest="$HOME/.config/obs-studio/plugins/cineremote-obs"
  mkdir -p "$dest/bin/64bit" "$dest/data/locale"
  cp out/cineremote-obs.so "$dest/bin/64bit/"
  echo 'CineRemote Camera="CineRemote Camera"' > "$dest/data/locale/en-US.ini"
  echo "[install] $dest - restart OBS, then Add Source -> CineRemote Camera"
fi
