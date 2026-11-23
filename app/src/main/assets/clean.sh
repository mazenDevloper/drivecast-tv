# 🧹 one-press clean and speed-up (run by the app through its own adb, as the shell user)
mem() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
dsk() { df /data | awk 'NR==2{print int($4/1024)}'; }
m0=$(mem); d0=$(dsk)
# kept running: this app, the remote / VPN / remote-desktop apps, the keyboard and the launcher
keep="app.drivecast.tv app.drivecast.tv.lite com.zerotier.one com.carriez.flutter_hbb"
ime=$(settings get secure default_input_method | cut -d/ -f1)
home=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | cut -d/ -f1)
n=0
for p in $(pm list packages -3 | cut -d: -f2); do
  case " $keep $ime $home " in *" $p "*) continue ;; esac
  if pidof "$p" >/dev/null 2>&1; then am force-stop "$p"; n=$((n+1)); fi
done
am kill-all
# every app's cache (keeps their data and logins)
pm trim-caches 999G
logcat -c 2>/dev/null
# lighter animations: the box feels quicker
settings put global window_animation_scale 0.5
settings put global transition_animation_scale 0.5
settings put global animator_duration_scale 0.5
sleep 2
m1=$(mem); d1=$(dsk)
echo "OK apps=$n ram0=$m0 ram1=$m1 disk0=$d0 disk1=$d1"
