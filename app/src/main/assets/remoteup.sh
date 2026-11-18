X=/data/local/tmp/dcu.xml
dump(){ uiautomator dump $X >/dev/null 2>&1; }
tap(){ b=$(grep -oE "<node[^>]*$1[^>]*>" $X | head -1 | grep -oE 'bounds="[^"]*"' | sed -E 's/[^0-9]+/ /g'); [ -z "$b" ] && return 1; set -- $b; input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); return 0; }
L=""
if ! ip addr | grep -q 'inet 10\.99\.175\.' && pm path com.zerotier.one >/dev/null 2>&1; then
  monkey -p com.zerotier.one 1 >/dev/null 2>&1; sleep 5; dump
  tap 'resource-id="com.zerotier.one:id/network_start_network_switch"[^>]*checked="false"' && L="$L zt-on"
  sleep 6
fi
if pm path com.carriez.flutter_hbb >/dev/null 2>&1 && ! dumpsys media_projection | grep -q flutter_hbb; then
  am start -n com.carriez.flutter_hbb/.MainActivity >/dev/null 2>&1; sleep 5; dump
  tap 'content-desc="(مشاركة الشاشة|Share screen)&#10;' ; sleep 2; dump
  if tap 'content-desc="(بدء الخدمة|Start service)"'; then L="$L rd-start"; sleep 3; dump
    # T6 has no folder picker: the log-folder box is closed, then «Screen capture» → «RustDesk storage» (its own folder)
    if grep -q 'content-desc="Choose folder"' $X; then input keyevent 4; sleep 2; dump
      tap 'content-desc="(لقط الشاشة|Screen capture)"'; sleep 3; dump; fi
    tap 'content-desc="(تخزين RustDesk|RustDesk storage)"' && { L="$L rd-store"; sleep 3; dump; }
    tap 'content-desc="(موافق|OK)"' && L="$L rd-ok"; sleep 3; dump
    tap 'text="(ابدأ الآن|Start now|بدء الآن)"' && L="$L proj-ok"; sleep 2
  fi
fi
[ -n "$L" ] && am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
rm -f $X; echo "up:$L"
