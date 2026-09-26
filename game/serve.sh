#!/usr/bin/env bash
# Serves this folder so the APK can be downloaded straight from the browser
# (handy for grabbing it on the phone over the same network).
cd "$(dirname "$0")"
PORT="${1:-8000}"
echo "download page: http://localhost:$PORT/"
exec python3 -m http.server "$PORT" --bind 0.0.0.0
