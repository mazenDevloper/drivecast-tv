#!/bin/bash
# From noor-tv (MIT, majedphotos).
# Disables (never uninstalls) the given packages, logs each one for restore.sh, then checks the TV still answers.
# Usage: tools/disable.sh <tv-ip> <backup-folder> <package>...
set -e
IP="$1"; OUT="$2"; shift 2; D="$IP:5555"
for p in "$@"; do
  if adb -s "$D" shell pm disable-user --user 0 "$p" | grep -q "disabled-user"; then
    echo "$p" >> "$OUT/disabled-by-us.txt"; echo "✅ $p"
  else
    echo "⚠️ $p (not changed)"
  fi
done
ping -c 2 -W 3 "$IP" >/dev/null && echo "TV is answering" || echo "⛔ TV stopped answering - run $OUT/restore.sh"
