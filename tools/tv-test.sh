#!/usr/bin/env bash
# DriveCast TV on an Android TV (emulator or a real box): install, make it the home screen, walk it with the
# remote's arrows, try the phone page, and keep screenshots + a report in ./tv-test/.
#   bash tools/tv-test.sh [DriveCastTV.apk] [device-serial]
set -u
APK=${1:-DriveCastTV.apk}; D=${2:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}
PKG=app.drivecast.tv; OUT=tv-test; mkdir -p "$OUT"; : > "$OUT/report.md"
a() { adb -s "$D" "$@"; }
key() { a shell input keyevent "$1"; sleep "${2:-0.7}"; }
shot() { a exec-out screencap -p > "$OUT/$1.png"; echo "- $1.png: $2" >> "$OUT/report.md"; }
ok() { echo "- ✅ $1" >> "$OUT/report.md"; }; bad() { echo "- ❌ $1" >> "$OUT/report.md"; FAIL=1; }
FAIL=0
echo "# DriveCast TV test ($(date -u +%F' '%H:%MZ), device $D, $(a shell getprop ro.build.version.release | tr -d '\r'))" >> "$OUT/report.md"

a install -r -g "$APK" >/dev/null && ok "installed" || { bad "install failed"; exit 1; }
a shell appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
a shell cmd alarm set-timezone Asia/Muscat >/dev/null 2>&1 || a shell setprop persist.sys.timezone Asia/Muscat >/dev/null 2>&1
a shell settings put secure enabled_accessibility_services $PKG/$PKG.MouseService; a shell settings put secure accessibility_enabled 1
a shell cmd role add-role-holder --user 0 android.app.role.HOME $PKG >/dev/null 2>&1 || a shell cmd package set-home-activity $PKG/.HomeActivity >/dev/null 2>&1 || true
a logcat -c
key KEYCODE_HOME 8
if [ "$(a shell dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity|ResumedActivity:' | grep -c $PKG)" = 1 ]; then ok "HOME opens DriveCast TV"
else
  if [ -n "${CI:-}" ]; then   # the emulator only: what the setup does on a real TV (tools/disable.sh) — the stock launcher off
    for L in com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.android.tv.launcher; do a shell pm disable-user --user 0 $L >/dev/null 2>&1; done
    key KEYCODE_HOME 8
  fi
  if [ "$(a shell dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity|ResumedActivity:' | grep -c $PKG)" = 1 ]; then ok "HOME opens DriveCast TV once the stock launcher is off"
  else bad "HOME opens another launcher"; a shell am start -n $PKG/.HomeActivity >/dev/null; sleep 8; fi
fi
shot 01-home "the home page after HOME"

# the remote: every row of the home page, then the pages
key KEYCODE_DPAD_DOWN; shot 02-focus "first DOWN: focus visible on the prayer / match row"
for i in 1 2 3; do key KEYCODE_DPAD_DOWN; done; shot 03-shelves "scrolled down: the mosque comes closer, shelves readable"
for i in 1 2 3 4 5 6; do key KEYCODE_DPAD_DOWN 0.4; done; shot 04-apps "the apps grid at the bottom"
for i in 1 2 3 4 5 6 7 8 9 10 11; do key KEYCODE_DPAD_UP 0.3; done
key KEYCODE_DPAD_UP 3; shot 05-ambient "UP from the tabs: the photo, the day, prayer + match bars"
key KEYCODE_DPAD_DOWN 1
for p in 1 2 3 4 5; do key KEYCODE_DPAD_LEFT 0.4; key KEYCODE_DPAD_CENTER 3; shot "06-page-$p" "tab $p"; done
key KEYCODE_MENU 1.5; shot 07-settings "MENU: settings panel"; key KEYCODE_BACK
key KEYCODE_BACK; key KEYCODE_HOME 3; shot 07b-after-home "after BACK + HOME"
pid=$(a shell pidof $PKG | tr -d '\r'); [ -n "$pid" ] && ok "still running after BACK / HOME (pid $pid)" || bad "the app is not running"

# the phone page (port 8080 on the TV)
a shell "netstat -tln 2>/dev/null | grep 8080 || ss -tln 2>/dev/null | grep 8080" | sed 's/^/- port: /' >> "$OUT/report.md"
a shell dumpsys activity services $PKG | grep -m3 -E "ServiceRecord|isForeground" | sed 's/^/- service: /' >> "$OUT/report.md"
a forward tcp:18080 tcp:8080 >/dev/null
curl -fsS -m 10 http://127.0.0.1:18080/ | grep -q 'ريموت' && ok "phone page served" || bad "phone page not served"
curl -fsS -m 10 http://127.0.0.1:18080/status > "$OUT/status.json" && ok "status: $(cat "$OUT/status.json")" || bad "status"
n=$(curl -fsS -m 25 http://127.0.0.1:18080/reciters | grep -o '"id":' | wc -l); [ "$n" -gt 0 ] && ok "reciters from DriveCast: $n" || bad "no reciters"
curl -fsS -m 10 -X POST --data 'ambient' http://127.0.0.1:18080/key >/dev/null; sleep 2; shot 08-phone-ambient "phone: «الصورة»"
curl -fsS -m 10 -X POST --data 'ambient' http://127.0.0.1:18080/key >/dev/null
curl -fsS -m 10 -X POST --data 'd7 18' http://127.0.0.1:18080/quran >/dev/null; sleep 6; shot 09-quran "phone: Al-Kahf, Mishary"
a shell dumpsys audio | grep -q 'state:started' && ok "audio is playing" || echo "- ⚠️ no started audio player seen (emulator audio may be off)" >> "$OUT/report.md"
curl -fsS -m 10 -X POST --data 'stop' http://127.0.0.1:18080/key >/dev/null
curl -fsS -m 10 -X POST --data $'m 300 120\nt' http://127.0.0.1:18080/mouse >/dev/null; sleep 1; shot 10-mouse "phone mouse: pointer moved + tap"

# «لوحتي»: a sample board from the "phone", then shown on the TV (the user's own board is put back after)
curl -fsS -m 10 http://127.0.0.1:18080/board > "$OUT/board-before.json" 2>/dev/null || echo '{"items":[]}' > "$OUT/board-before.json"
curl -fsS -m 10 -X POST --data '{"bg":"paper","items":[{"id":"t1","type":"quote","color":"lav","text":"وقل ربّ زدني علماً"},{"id":"t2","type":"reminder","color":"pink","text":"موعد","time":"16:30"},{"id":"t3","type":"sticker","color":"butter","emoji":"☕"},{"id":"t4","type":"note","color":"mint","text":"ملاحظة"}]}' http://127.0.0.1:18080/board | grep -q ok && ok "board saved from the phone" || bad "board not saved"
curl -fsS -m 10 -X POST --data 'board' http://127.0.0.1:18080/key >/dev/null; sleep 3; shot 11-board "«لوحتي» with a sample board"
key KEYCODE_DPAD_DOWN 1; shot 12-board-focus "a board card in focus"
key KEYCODE_DPAD_CENTER 1.5; shot 13-board-edit "OK on a card: the edit panel"; key KEYCODE_BACK 1
for n in 1 2 3 4 5 6 7; do a shell input keyevent KEYCODE_$n; sleep 1.2; done; shot 14-number-7 "number 7: the 7th tab"; key KEYCODE_1
curl -fsS -m 10 -X POST --data-binary @"$OUT/board-before.json" http://127.0.0.1:18080/board >/dev/null
key KEYCODE_HOME 2

# 5 minutes of use, then crashes / memory
for i in $(seq 1 40); do key KEYCODE_DPAD_RIGHT 0.2; key KEYCODE_DPAD_DOWN 0.2; key KEYCODE_DPAD_UP 0.2; done
a logcat -d -b crash > "$OUT/crash.txt"; a logcat -d '*:E' | grep -iE "drivecast|chromium|AndroidRuntime" > "$OUT/errors.txt"
[ -s "$OUT/crash.txt" ] && grep -q $PKG "$OUT/crash.txt" && bad "crash: see crash.txt" || ok "no crash"
grep -q "ANR in $PKG" <(a logcat -d) && bad "ANR" || ok "no ANR"
a shell dumpsys meminfo $PKG | grep -m1 'TOTAL' | sed 's/^/- memory: /' >> "$OUT/report.md"
a logcat -d | grep -iE "drivecast|MessageServer|MouseService|FATAL|AndroidRuntime" | tail -120 > "$OUT/app-log.txt"
echo; cat "$OUT/report.md"; echo "---- errors"; tail -40 "$OUT/errors.txt"; echo "---- app log"; tail -60 "$OUT/app-log.txt"; exit $FAIL
