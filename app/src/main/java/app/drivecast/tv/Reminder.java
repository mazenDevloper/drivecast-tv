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
            return;
        }
        if (!Settings.canDrawOverlays(c)) return;
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
}
