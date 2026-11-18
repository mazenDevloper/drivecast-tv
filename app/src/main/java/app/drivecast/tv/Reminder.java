package app.drivecast.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Calendar;

/**
 * Every 45 minutes, a small line under the top of the screen for 10 seconds: the salawat line, and on Friday
 * before maghrib the Surat Al-Kahf reminder first and the salawat line right after it. Adapted from noor-tv (MIT, majedphotos).
 */
public class Reminder extends BroadcastReceiver {
    static final String SHOW = "app.drivecast.tv.SHOW";
    static final long EVERY = 45 * 60 * 1000L;
    static final long SHOWN = 10_000;

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent show = new Intent(c, Reminder.class).setAction(SHOW);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, show,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 60_000, EVERY, pi);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (!SHOW.equals(i.getAction())) {
            schedule(c);
            Adhan.schedule(c);
            MessageServer.start(c);
            Access.keep(c);
            if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) { unstickBoot(c.getApplicationContext()); remoteUp(c.getApplicationContext()); }
            return;
        }
        if (!Settings.canDrawOverlays(c)) return;
        if ("off".equals(Access.set(c).getString("salawat", "on"))) return;   // switched off in the settings
        Calendar now = Calendar.getInstance();
        boolean friday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY && now.get(Calendar.HOUR_OF_DAY) < 17;
        Context app = c.getApplicationContext();
        if (!friday) {
            show(app, "صلّ على النبي ﷺ");
            return;
        }
        // On Friday the Kahf line first, then the salawat line as soon as it clears.
        show(app, "اليوم الجمعة... لا تنسى سورة الكهف");
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> show(app, "لا تنسى الصلاة على الحبيب محمد ﷺ"), SHOWN + 500);
    }

    static void show(Context c, String text) {
        WindowManager wm = c.getSystemService(WindowManager.class);
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(22);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(36, 18, 36, 18);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC0F3D2E);
        bg.setCornerRadius(40);
        v.setBackground(bg);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = 365; // under the adhan banner, so the two never overlap
        try {
            wm.addView(v, lp);
        } catch (Exception e) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                wm.removeView(v);
            } catch (Exception ignored) {
            }
        }, SHOWN);
    }

    // 🎞️ some boxes (TX15) keep the boot animation on screen after the boot is done (it ignores «service.bootanim.exit»):
    // the home is up behind it, the remote reaches a hidden window. Checked for 3 minutes after the boot; still running →
    // stopped through the box's own su (where there is one), and the home brought to the front.
    /** 🛰 after a boot: ZeroTier's network back on and RustDesk's screen-sharing service started (with its warning and the
     *  capture prompt answered) — on Android < 10 the capture permission is lost at each reboot, so the TV vanished from
     *  RustDesk until someone pressed «بدء الخدمة». The script (assets/remoteup.sh) does nothing when both are already up;
     *  it runs as the shell through the app's own adb, at 75 s and again at 4 min */
    /** 🔑 the TV boxes take permissions back (leaving Settings, a «force» clean-up): DriveCast TV, Madar and DriveCast get every
     *  permission they ask for again, their overlay / install / background app-ops and the power-saving exemption — through the
     *  app's own adb (assets/grant.sh), after a boot and each time the home screen shows, at most every 10 minutes */
    private static long grantAt;
    static void regrant(Context c) {
        long now = android.os.SystemClock.elapsedRealtime(); if (grantAt != 0 && now - grantAt < 10 * 60_000L) return; grantAt = now;
        new Thread(() -> {
            try (java.io.InputStream in = c.getAssets().open("grant.sh")) { SelfAdb.shell(c, new String(readAll(in), "UTF-8"), 0, 90_000); } catch (Exception e) {}
        }, "regrant").start();
    }
    static void remoteUp(Context c) {
        new Thread(() -> {
            String sc;
            try (java.io.InputStream in = c.getAssets().open("remoteup.sh")) { sc = new String(readAll(in), "UTF-8"); } catch (Exception e) { return; }
            for (long wait : new long[]{75_000, 165_000}) {
                try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
                try { SelfAdb.shell(c, sc, 0, 120_000); } catch (Exception e) {}
                if (wait == 75_000) { grantAt = 0; regrant(c); }
            }
        }, "remoteUp").start();
    }
    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toByteArray();
    }

    static void unstickBoot(Context c) {
        new Thread(() -> {
            for (int n = 0; n < 18; n++) {
                try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }
                if (!"running".equals(sh("getprop init.svc.bootanim"))) return;
                if (n < 2) continue;   // 30 s: the normal end of the animation
                for (String cmd : new String[]{"su 0 stop bootanim", "su -c stop bootanim"}) {
                    try { Runtime.getRuntime().exec(cmd).waitFor(); } catch (Exception e) {}
                    if (!"running".equals(sh("getprop init.svc.bootanim"))) break;
                }
                // the app's own su refused → through its own network debugging (SelfAdb, as the shell), where it was allowed
                if ("running".equals(sh("getprop init.svc.bootanim")))
                    try { SelfAdb.shell(c, "su 0 stop bootanim || setprop ctl.stop bootanim", 0, 5000); } catch (Exception e) {}
                if (!"running".equals(sh("getprop init.svc.bootanim"))) {
                    try { c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) {}
                    return;
                }
            }
        }).start();
    }

    private static String sh(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String l = r.readLine(); p.waitFor();
            return l == null ? "" : l.trim();
        } catch (Exception e) { return ""; }
    }
}
