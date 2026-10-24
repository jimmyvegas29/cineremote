#!/bin/zsh
# Double-click to start CineRemote on macOS (opens in Terminal).
# Running it from Terminal makes Terminal the app macOS asks for Local Network access - allow it
# once ("Terminal would like to find and connect to devices on your local network").
cd "$(dirname "$0")"
if [ ! -x venv/bin/python ]; then
  echo "First run: creating the Python environment..."
  PY=$(command -v python3.12 || command -v python3.11 || echo /usr/local/bin/python3)
  "$PY" -m venv venv && venv/bin/pip install -q -r requirements.txt || exit 1
fi
exec venv/bin/python cineremote.py "$@"
