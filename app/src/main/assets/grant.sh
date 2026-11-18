# grant every runtime permission each app asks for, its special app-ops, and keep it out of the power saving
# NOT MANAGE_EXTERNAL_STORAGE: Android kills the app each time it changes, and the box resets it — that looped DriveCast (killed every 2 s, black screen)
for P in app.drivecast.tv app.drivecast.tv.lite com.madar.app com.drivecast.sovereign; do
  pm path $P >/dev/null 2>&1 || continue
  ok=0; no=0
  for perm in $(dumpsys package $P 2>/dev/null | sed -n '/requested permissions:/,/install permissions:/p' | grep -oE 'android\.permission\.[A-Z_]+' | sort -u); do
    pm grant $P $perm >/dev/null 2>&1 && ok=$((ok+1)) || no=$((no+1))
  done
  for op in SYSTEM_ALERT_WINDOW REQUEST_INSTALL_PACKAGES RUN_ANY_IN_BACKGROUND RUN_IN_BACKGROUND WRITE_SETTINGS GET_USAGE_STATS PROJECT_MEDIA START_FOREGROUND POST_NOTIFICATION; do
    appops set $P $op allow >/dev/null 2>&1
  done
  dumpsys deviceidle whitelist +$P >/dev/null 2>&1
  am set-standby-bucket $P active >/dev/null 2>&1
  echo "$P runtime:+$ok (not grantable:$no) overlay:$(appops get $P SYSTEM_ALERT_WINDOW | cut -d' ' -f2) install:$(appops get $P REQUEST_INSTALL_PACKAGES | cut -d' ' -f2) bg:$(appops get $P RUN_ANY_IN_BACKGROUND | cut -d' ' -f2) idle:$(dumpsys deviceidle whitelist | grep -c $P)"
done
