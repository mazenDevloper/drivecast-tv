package app.drivecast.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 📡 the TV's screen for the phone or a browser (instead of RustDesk / Madar, whose control the box's system switches off):
 * Android's own screen capture into a small picture (960 wide), handed out as JPEG by the message server (/screen.jpg).
 * The capture is asked once from the home screen; with the PROJECT_MEDIA app-op allowed over adb, Android grants it with no
 * dialog. It runs only while someone watches: 3 min with no picture asked → it stops, and the next viewer starts it again.
 * The control (taps, swipes, keys) goes through DriveCast's own mouse service — the one accessibility service this box keeps.
 */
public class Cast extends Service {
    static volatile Cast on;
    static volatile Intent grant;          // the capture permission (Android hands it to the home screen once)
    static volatile long askedAt;          // the last picture asked (the capture stops when no one asks)
    private MediaProjection mp; private VirtualDisplay vd; private ImageReader ir;
    private HandlerThread ht; private Handler h;
    private int w, hh; private volatile byte[] jpeg; private volatile long jpegAt;

    /** a picture of the screen now (JPEG), or null while the capture starts */
    static byte[] frame(Context c) {
        askedAt = System.currentTimeMillis();
        Cast s = on;
        if (s == null) { start(c); return null; }
        return s.grab();
    }

    static void start(Context c) {
        if (on != null) return;
        if (grant == null) {   // no permission yet: the home screen asks Android (no dialog once the app-op is allowed)
            HomeActivity a = HomeActivity.alive;
            if (a != null) a.runOnUiThread(a::askCapture);
            return;
        }
        Intent i = new Intent(c, Cast.class);
        try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i); } catch (Exception e) { Crash.note(c, "cast start: " + e); }
    }

    @Override public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("cast", "بث الشاشة", NotificationManager.IMPORTANCE_LOW));
            Notification n = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "cast") : new Notification.Builder(this))
                .setContentTitle("DriveCast · بث الشاشة").setSmallIcon(android.R.drawable.ic_menu_view).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(7, n);
        } catch (Exception e) { Crash.note(this, "cast fg: " + e); stopSelf(); return; }
        Intent g = grant;
        if (g == null) { stopSelf(); return; }
        try {
            MediaProjectionManager pm = getSystemService(MediaProjectionManager.class);
            mp = pm.getMediaProjection(android.app.Activity.RESULT_OK, (Intent) g.clone());
        } catch (Exception e) { Crash.note(this, "cast projection: " + e); grant = null; stopSelf(); return; }
        if (mp == null) { grant = null; stopSelf(); return; }
        DisplayMetrics m = getResources().getDisplayMetrics();
        android.view.WindowManager wm = getSystemService(android.view.WindowManager.class);
        DisplayMetrics real = new DisplayMetrics(); wm.getDefaultDisplay().getRealMetrics(real);
        w = 960; hh = Math.round(960f * real.heightPixels / Math.max(1, real.widthPixels)); if (hh % 2 == 1) hh++;
        ht = new HandlerThread("cast"); ht.start(); h = new Handler(ht.getLooper());
        ir = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 2);
        try {
            mp.registerCallback(new MediaProjection.Callback() { @Override public void onStop() { stopSelf(); } }, h);
            vd = mp.createVirtualDisplay("drivecast-cast", w, hh, m.densityDpi / 2, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.getSurface(), null, h);
        } catch (Exception e) { Crash.note(this, "cast display: " + e); stopSelf(); return; }
        on = this;
        h.postDelayed(idle, 15000);
    }

    // no picture asked for 3 minutes: stop (no work at all while no one watches)
    private final Runnable idle = new Runnable() { @Override public void run() {
        if (System.currentTimeMillis() - askedAt > 180000) { stopSelf(); return; }
        h.postDelayed(this, 15000);
    } };

    /** the latest frame as JPEG (re-encoded at most ~10 times a second, whatever the number of viewers) */
    private byte[] grab() {
        long now = System.currentTimeMillis();
        if (jpeg != null && now - jpegAt < 90) return jpeg;
        Image img = null;
        try {
            img = ir.acquireLatestImage();
            if (img == null) return jpeg;
            Image.Plane p = img.getPlanes()[0]; ByteBuffer buf = p.getBuffer();
            int stride = p.getRowStride() / p.getPixelStride();
            Bitmap bm = Bitmap.createBitmap(stride, hh, Bitmap.Config.ARGB_8888); bm.copyPixelsFromBuffer(buf);
            Bitmap crop = stride == w ? bm : Bitmap.createBitmap(bm, 0, 0, w, hh);
            ByteArrayOutputStream o = new ByteArrayOutputStream(80_000);
            crop.compress(Bitmap.CompressFormat.JPEG, 62, o);
            if (crop != bm) crop.recycle(); bm.recycle();
            jpeg = o.toByteArray(); jpegAt = now;
        } catch (Exception e) { /* a frame lost: the next one comes */ }
        finally { if (img != null) img.close(); }
        return jpeg;
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        on = null; grant = null;   // a capture permission serves one capture: the next viewer asks again (no dialog)
        try { if (vd != null) vd.release(); } catch (Exception ignored) { }
        try { if (ir != null) ir.close(); } catch (Exception ignored) { }
        try { if (mp != null) mp.stop(); } catch (Exception ignored) { }
        if (ht != null) ht.quitSafely();
        super.onDestroy();
    }
}
