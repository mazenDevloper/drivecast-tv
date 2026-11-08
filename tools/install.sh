#!/bin/bash
# Installs DriveCast TV (the latest build from this repo's releases, or a local APK), makes it the home screen,
# gives it the two permissions the adhan needs (draw over films, know what is on screen) and starts it once.
# Usage: tools/install.sh <tv-ip> [app.apk]
set -e
IP="$1"; D="$IP:5555"; [ -n "$IP" ] || { echo "usage: $0 <tv-ip> [apk]"; exit 1; }
APK="$2"
if [ -z "$APK" ]; then
  APK=$(mktemp -d)/DriveCastTV.apk
  curl -fsSL -o "$APK" https://github.com/mazenDevloper/drivecast-tv/releases/download/latest/DriveCastTV.apk
fi
adb -s "$D" install -r "$APK" >/dev/null && echo "✅ installed"
adb -s "$D" shell appops set app.drivecast.tv SYSTEM_ALERT_WINDOW allow
adb -s "$D" shell appops set app.drivecast.tv GET_USAGE_STATS allow
# the phone page's mouse (an accessibility service: pointer + taps/scroll, reads nothing); keeps any other enabled service
CUR=$(adb -s "$D" shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in *app.drivecast.tv/*) ;; null|"") adb -s "$D" shell settings put secure enabled_accessibility_services app.drivecast.tv/app.drivecast.tv.MouseService ;;
  *) adb -s "$D" shell settings put secure enabled_accessibility_services "$CUR:app.drivecast.tv/app.drivecast.tv.MouseService" ;; esac
adb -s "$D" shell settings put secure accessibility_enabled 1
# the app may switch the mouse back on whenever the TV turns it off (Android TV does that now and then)
adb -s "$D" shell appops set app.drivecast.tv ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true
adb -s "$D" shell pm grant app.drivecast.tv android.permission.WRITE_SECURE_SETTINGS 2>/dev/null && echo "✅ mouse kept on" || true
adb -s "$D" shell pm grant app.drivecast.tv android.permission.READ_MEDIA_AUDIO 2>/dev/null || adb -s "$D" shell pm grant app.drivecast.tv android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
# the home button now opens DriveCast TV (the old launcher stays installed; setup may disable it afterwards)
# Android 10+ keeps the home app as a «role»; older ones as the preferred home activity
adb -s "$D" shell cmd role add-role-holder --user 0 android.app.role.HOME app.drivecast.tv >/dev/null 2>&1 \
  || adb -s "$D" shell cmd package set-home-activity app.drivecast.tv/.HomeActivity >/dev/null 2>&1 \
  || echo "⚠️ choose «DriveCast TV» when the TV asks for the home app"
sleep 6
adb -s "$D" shell am broadcast -n app.drivecast.tv/.Reminder >/dev/null
adb -s "$D" shell input keyevent KEYCODE_HOME
echo "Phone page: http://$IP:8080"
