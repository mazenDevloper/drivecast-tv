#!/usr/bin/env bash
# RustDesk on a TV box over adb, ready to be watched and controlled from anywhere without anyone pressing «allow» on the TV:
#   bash tools/rustdesk/tv-setup.sh 192.168.100.147 [rustdesk.apk]
# - picks the APK for the box's own Android (many TV boxes run a 32-bit Android on a 64-bit chip: arm64-v8a won't install there)
# - screen capture allowed once (no «Start now?» on the TV each time the service starts)
# - its input (accessibility) service on, beside DriveCast's mouse — never replacing what is on already
# - no battery «optimisation» for it (the box doesn't stop it in the background)
# Then, on the TV once: open RustDesk → Settings → Network: ID server, Relay server, Key (from setup-server.sh); set a permanent
# password; turn on «Start on boot» if the version has it.
set -euo pipefail
IP="${1:?TV address, e.g. 192.168.100.147}"; APK="${2:-}"
D="$IP:5555"; PKG=com.carriez.flutter_hbb
adb connect "$D" >/dev/null
ABI=$(adb -s "$D" shell getprop ro.product.cpu.abi | tr -d '\r')
SDK=$(adb -s "$D" shell getprop ro.build.version.sdk | tr -d '\r')
echo "TV: Android SDK $SDK, $ABI"
if [ -z "$APK" ]; then
  case "$ABI" in arm64-v8a) A=aarch64 ;; armeabi-v7a|armeabi) A=armv7 ;; x86_64) A=x86_64 ;; *) echo "unknown ABI $ABI"; exit 1 ;; esac
  URL=$(curl -fsSL https://api.github.com/repos/rustdesk/rustdesk/releases/latest | grep -o "https://[^\"]*/rustdesk-[0-9.]*-${A}-signed\.apk" | head -1)
  [ -n "$URL" ] || { echo "no $A APK found in the latest release — download it by hand from github.com/rustdesk/rustdesk/releases"; exit 1; }
  APK=$(mktemp -d)/rustdesk.apk; echo "downloading $URL"; curl -fsSL "$URL" -o "$APK"
fi
adb -s "$D" install -r "$APK"
# screen capture without the TV's «Start now» each time
adb -s "$D" shell appops set $PKG PROJECT_MEDIA allow || true
# its input service, added to what is on (DriveCast's mouse stays)
S="$PKG/$PKG.InputService"
CUR=$(adb -s "$D" shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in *"$S"*) ;; null|"") adb -s "$D" shell settings put secure enabled_accessibility_services "$S" ;;
  *) adb -s "$D" shell settings put secure enabled_accessibility_services "$CUR:$S" ;; esac
adb -s "$D" shell settings put secure accessibility_enabled 1
# not stopped in the background
adb -s "$D" shell dumpsys deviceidle whitelist +$PKG >/dev/null || true
adb -s "$D" shell cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
adb -s "$D" shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb -s "$D" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
echo "done — on the TV: RustDesk → Settings → Network (ID / Relay / Key), a permanent password, then «Start service»"
