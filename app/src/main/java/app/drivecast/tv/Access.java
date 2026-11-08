package app.drivecast.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Keeps the phone page's mouse (MouseService) switched on. Some Android TV builds switch accessibility services off on
 * their own (after a reboot, an update, or a «clean-up»). Once the setup grants WRITE_SECURE_SETTINGS over adb
 * (tools/force-mouse.sh), the app turns it back on by itself: at boot, when the home screen comes back, every minute
 * from the message service, and the moment the setting is changed. «إبقاء الماوس مفعّل» in the settings turns this off.
 */
final class Access {
    static final String ME = new ComponentName("app.drivecast.tv", MouseService.class.getName()).flattenToString();
    private static boolean watching;

    private Access() { }

    static SharedPreferences set(Context c) { return c.getApplicationContext().getSharedPreferences("set", Context.MODE_PRIVATE); }

    /** the mouse service is in the enabled list and accessibility is on */
    static boolean enabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean in = list != null && (":" + list + ":").toLowerCase().contains(":" + ME.toLowerCase() + ":")
                || list != null && list.toLowerCase().contains("app.drivecast.tv/.mouseservice");
        return in && Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
    }

    /** the adb grant is there: the app may write the setting itself */
    static boolean canForce(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean wanted(Context c) { return !"off".equals(set(c).getString("force", "on")); }

    /** turns the mouse on (keeps every other service the TV has on); true if it is on afterwards */
    static boolean force(Context c) {
        if (enabled(c)) return true;
        if (!canForce(c)) return false;
        try {
            String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || list.trim().isEmpty() || "null".equals(list)) list = ME;
            else if (!list.toLowerCase().contains("app.drivecast.tv/")) list = list + ":" + ME;
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception e) { return false; }
        return enabled(c);
    }

    /** if wanted and allowed: on now */
    static void keep(Context c) { if (wanted(c)) force(c); }

    /** from the long-lived message service: back on the moment the TV switches it off, and a check every minute */
    static void watch(Context c) {
        if (watching) return;
        watching = true;
        Context app = c.getApplicationContext();
        Handler h = new Handler(Looper.getMainLooper());
        ContentObserver o = new ContentObserver(h) {
            @Override public void onChange(boolean self) { h.postDelayed(() -> keep(app), 1500); }
        };
        try {
            app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, o);
            app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, o);
        } catch (Exception ignored) { }
        h.post(new Runnable() {
            @Override public void run() { keep(app); h.postDelayed(this, 60_000); }
        });
    }

    /** the TV's own accessibility page (no adb grant: switch it on by hand there) */
    static void openSettings(Context c) {
        try { c.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) {
            try { c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        }
    }
}
