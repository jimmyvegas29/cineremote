#!/usr/bin/env bash
# Start CineRemote on Linux (sets up the Python environment on first run).
# Ubuntu also needs: sudo apt install python3-venv libxcb-cursor0
cd "$(dirname "$(readlink -f "$0")")"
export QT_LOGGING_RULES="qt.qpa.wayland.textinput=false${QT_LOGGING_RULES:+;$QT_LOGGING_RULES}"  # harmless Wayland IME noise
if [ ! -x venv/bin/python ]; then
  echo "First run: creating the Python environment..."
  PY=$(command -v python3.13 || command -v python3.12 || command -v python3)   # 3.12+ required
  "$PY" -m venv venv && venv/bin/pip install -q -r requirements.txt || exit 1
fi
exec venv/bin/python cineremote.py "$@"
