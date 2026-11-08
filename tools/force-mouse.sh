#!/bin/bash
# Lets DriveCast TV keep its phone-page mouse switched on by itself. Some Android TV builds turn accessibility services off
# (after a reboot, an update or a «clean-up»); with this one-time grant the app switches it back on at once.
# Usage: tools/force-mouse.sh <tv-ip>      (the TV: Developer options → USB/network debugging on)
set -e
IP="$1"; [ -n "$IP" ] || { echo "usage: $0 <tv-ip>"; exit 1; }
D="$IP:5555"; adb connect "$D" >/dev/null
S="app.drivecast.tv/app.drivecast.tv.MouseService"
# Android 13+: sideloaded apps need «restricted settings» allowed before accessibility can be on
adb -s "$D" shell appops set app.drivecast.tv ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true
adb -s "$D" shell pm grant app.drivecast.tv android.permission.WRITE_SECURE_SETTINGS && echo "✅ the app may switch its mouse back on"
CUR=$(adb -s "$D" shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in *app.drivecast.tv/*) ;; null|"") adb -s "$D" shell settings put secure enabled_accessibility_services "$S" ;;
  *) adb -s "$D" shell settings put secure enabled_accessibility_services "$CUR:$S" ;; esac
adb -s "$D" shell settings put secure accessibility_enabled 1
echo "✅ mouse on: $(adb -s "$D" shell settings get secure enabled_accessibility_services | tr -d '\r')"
