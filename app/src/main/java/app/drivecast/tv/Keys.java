package app.drivecast.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.json.JSONObject;

/**
 * 🎛️ The remote's keys, everywhere on the box (through the accessibility service, MouseService):
 *  - re-programming: any key, from the box's own IR remote or from the TV's remote over HDMI-CEC, can do another thing
 *    (learned in Settings → «الماوس والريموت»: press the key, pick what it does);
 *  - no clashes: the Hisense remote's IR also reaches the box's IR eye, and some of its codes are the box's own keys
 *    (its OK = the box's BACK, its Menu = an arrow). While the TV's remote is in use (a CEC key in the last 8 s) the box's
 *    IR back / arrows / OK / menu are ignored; a lone IR BACK waits 0.25 s, and a CEC key in that time cancels it.
 */
final class Keys {
    static volatile boolean learn;
    private static volatile long lastCec;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final java.util.ArrayDeque<String> LOG = new java.util.ArrayDeque<>();
    private static JSONObject map; private static String mapRaw;
    private static final java.util.Set<String> held = new java.util.HashSet<>();

    static String src(KeyEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_HDMI) == InputDevice.SOURCE_HDMI) return "cec";
        InputDevice d = e.getDevice(); String n = d == null ? "" : String.valueOf(d.getName()).toLowerCase(java.util.Locale.ROOT);
        if (n.contains("cec") || n.contains("hdmi")) return "cec";
        if (n.contains("uinput") && n.contains("ir")) return "irx";   // T6: multi_ir turns other brands' remotes (by their IR address) into keys on a second device
        if (n.contains("-ir") || n.startsWith("ir") || n.contains(" ir") || n.contains("_ir")) return "ir";
        if (d == null || e.getDeviceId() <= 0 || d.isVirtual()) return "cec";   // CEC keys are injected on the virtual keyboard
        return "dev";
    }
    static String name(KeyEvent e) { return e.getKeyCode() == 0 ? "زر #" + e.getScanCode() : name(e.getKeyCode()); }
    static String name(int code) { String s = KeyEvent.keyCodeToString(code); return s.startsWith("KEYCODE_") ? s.substring(8) : s; }

    private static JSONObject map(Context c) {
        String raw = Access.set(c).getString("keymap", "{}");
        if (!raw.equals(mapRaw)) { try { map = new JSONObject(raw); } catch (Exception x) { map = new JSONObject(); } mapRaw = raw; }
        return map;
    }
    private static boolean collides(int k) {
        return k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT
            || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_MENU;
    }

    /** true = the key is eaten here */
    static boolean on(AccessibilityService s, KeyEvent e) {
        int code = e.getKeyCode(); String src = src(e), id = src + ":" + (code == 0 ? "s" + e.getScanCode() : String.valueOf(code));; boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        long now = SystemClock.uptimeMillis();
        if (down && e.getRepeatCount() == 0) {
            synchronized (LOG) { LOG.addFirst(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + "  " + (src.equals("cec") ? "📺 التلفاز" : src.equals("ir") ? "📡 أشعة ١" : src.equals("irx") ? "📡 أشعة ٢ (ريموت آخر)" : "⌨️ " + src) + "  " + name(e) + "  ·" + e.getScanCode()); while (LOG.size() > 12) LOG.removeLast(); }
        }
        if ("cec".equals(src)) lastCec = now;
        if (learn) {
            if (down && e.getRepeatCount() == 0) {
                learn = false; HomeActivity h = HomeActivity.alive;
                if (h != null) h.runOnUiThread(() -> h.run("window.keyLearned && keyLearned('" + id + "','" + name(e) + "','" + src + "')"));
                held.add(id);
            }
            return true;
        }
        if (!down && held.remove(id)) return true;
        // a re-programmed key
        JSONObject m = map(s); String a = m.optString(id, m.optString("any:" + code, ""));
        if (!a.isEmpty()) { if (down) { held.add(id); if (e.getRepeatCount() == 0) act(s, a); } return true; }
        // the clash guard
        if (("ir".equals(src) || "irx".equals(src)) && collides(code) && !"off".equals(Access.set(s).getString("irguard", "on"))) {
            if (now - lastCec < 8000) return true;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (down && e.getRepeatCount() == 0) { long at = now; H.postDelayed(() -> { if (lastCec < at) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); }, 250); }
                return true;
            }
        }
        return false;
    }

    static String log() { synchronized (LOG) { return android.text.TextUtils.join("\n", LOG); } }

    /** what a re-programmed key does */
    static void act(AccessibilityService s, String a) {
        try {
            switch (a) {
                case "none": return;
                case "back": s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); return;
                case "home": s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); return;
                case "recents": s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); return;
                case "notif": s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); return;
                case "settings": s.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return;
                case "red": case "green": case "yellow": case "blue": color(s, a); return;
                case "iptv": case "quran": case "matches": home(s, "show('" + a + "')"); return;
                case "dcset": home(s, "settings(true)"); return;
            }
            if (a.startsWith("app:")) { Intent i = s.getPackageManager().getLeanbackLaunchIntentForPackage(a.substring(4)); if (i == null) i = s.getPackageManager().getLaunchIntentForPackage(a.substring(4)); if (i != null) s.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
            if (a.startsWith("key:")) { String k = a.substring(4).replaceAll("[^0-9A-Z_]", ""); new Thread(() -> { try { SelfAdb.shell(s, "input keyevent " + k, 0, 5000); } catch (Exception ignored) { } }, "keyact").start(); }
        } catch (Exception x) { Crash.note(s, "keys " + a + ": " + x); }
    }
    private static void color(AccessibilityService s, String c) {
        int code = c.equals("red") ? KeyEvent.KEYCODE_PROG_RED : c.equals("green") ? KeyEvent.KEYCODE_PROG_GREEN : c.equals("yellow") ? KeyEvent.KEYCODE_PROG_YELLOW : KeyEvent.KEYCODE_PROG_BLUE;
        PlayerActivity p = PlayerActivity.live;
        if (p != null && !p.isFinishing() && p.hasWindowFocus()) { p.runOnUiThread(() -> p.colorKey(code)); return; }
        home(s, "window.onTvColor && onTvColor('" + c + "')");
    }
    private static void home(Context s, String js) {
        HomeActivity h = HomeActivity.alive;
        boolean front = h != null && h.hasWindowFocus();
        if (!front) s.startActivity(new Intent(s, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        H.postDelayed(() -> { HomeActivity x = HomeActivity.alive; if (x != null) x.run(js); }, front ? 0 : 700);
    }
}
