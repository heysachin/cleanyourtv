#!/usr/bin/env bash
# Push bundled wallpapers to /sdcard/Wallpapers on the TV over Wi-Fi
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 tools/push-wallpapers.sh}"

PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
[ -x "$PY" ] || PY=python3

echo "Creating /sdcard/Wallpapers on TV ($TV_HOST)..."
"$PY" -I tools/tv.py "mkdir -p /sdcard/Wallpapers"

if command -v adb >/dev/null 2>&1 && adb connect "$TV_HOST:5555" 2>&1 | grep -qE "connected to|already connected"; then
  echo "Pushing wallpapers via adb..."
  adb -s "$TV_HOST:5555" push wallpapers/*.jpg /sdcard/Wallpapers/
else
  echo "Pushing wallpapers via fallback stream..."
  for f in wallpapers/*.jpg; do
    echo "Uploading $(basename "$f")..."
    "$PY" -I tools/b64push.py "$f" "/sdcard/Wallpapers/$(basename "$f")"
  done
fi
echo "Done! All wallpapers uploaded to /sdcard/Wallpapers/"
