package app.drivecast.tv;

// Adapted from noor-tv (MIT, majedphotos): the phone page that writes or draws on the TV.

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A tiny web page on port 8080: open http://<tv-ip>:8080 on a phone on the same Wi-Fi, then write a message
 * (shown in the middle of the TV for 20 seconds) or draw with a finger (drawn on the TV live, gone 15 seconds
 * after the last line). Only reachable from the home network.
 */
public class MessageServer extends Service {
    static final int PORT = 8080;
    static final int MAX = 200;
    static final long DRAW_IDLE = 15_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket server;
    private View shown;
    private DrawLayer drawing;
    private final Runnable hideDrawing = this::hideDrawing;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, MessageServer.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("msg", "رسائل الجوال", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "msg")
                .setContentTitle("رسائل الجوال")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .build();
        startForeground(1, n);
        new Thread(this::serve, "msg-server").start();
        Access.watch(this);   // the mouse stays on, even when the TV switches it off
        Remote.start(this);   // the web version: DriveCast TV from anywhere, through DriveCast's hub
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private void serve() {
        try {
            server = new ServerSocket(PORT);
            while (true) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(15000);
                    handle(s);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            // Port taken (a second start): the first server keeps running.
        }
    }

    private void handle(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        String head = readHead(in);
        String first = head.split("\r\n", 2)[0];
        if (!first.startsWith("POST ")) {
            if (first.startsWith("GET /reciters")) reply(s.getOutputStream(), "application/json", reciters());
            else if (first.startsWith("GET /board")) reply(s.getOutputStream(), "application/json", HomeActivity.readBoard(this));
            else if (first.startsWith("GET /phrase")) reply(s.getOutputStream(), "text/plain", getSharedPreferences("ui", MODE_PRIVATE).getString("phrase", ""));
            else if (first.startsWith("GET /status")) reply(s.getOutputStream(), "application/json", "{\"mouse\":" + (MouseService.on != null) + ",\"enabled\":" + Access.enabled(this) + ",\"force\":" + Access.canForce(this) + ",\"home\":" + (HomeActivity.front != null) + "}");
            else if (first.startsWith("GET /pair")) pair(s.getOutputStream(), first);   // the web version pairs by the TV's address (home Wi-Fi)
            else if (first.startsWith("GET /web")) reply(s.getOutputStream(), "application/json", "{\"pin\":\"" + Remote.pin(this) + "\",\"id\":\"" + Remote.id(this) + "\",\"online\":" + Remote.online() + "}");   // on the home Wi-Fi only
            else if (first.startsWith("GET /crash")) reply(s.getOutputStream(), "text/plain", Crash.read(this));   // the last problems (Crash.java)
            else reply(s.getOutputStream(), "text/html", page());
            return;
        }
        if (first.startsWith("POST /board")) {   // «لوحتي»: the whole board (notes, reminders, quotes, stickers, drawings) as JSON
            byte[] j = readBytes(in, head, 12 << 20);
            boolean ok = j.length > 1 && j[0] == '{';
            if (ok) {
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "board.json"))) { o.write(j); }
                main.post(() -> { HomeActivity h = HomeActivity.front; if (h != null) h.run("window.loadBoard && loadBoard()"); });
                Remote.event("board");
            }
            reply(s.getOutputStream(), "text/plain", ok ? "ok" : "bad");
            return;
        }
        if (first.startsWith("POST /font")) {   // the user's own font file (e.g. Thmanyah from its official page), kept on the TV only
            byte[] f = readBytes(in, head, 4 << 20);
            String ext = first.contains("ext=otf") ? "otf" : first.contains("ext=woff2") ? "woff2" : first.contains("ext=woff") ? "woff" : "ttf";
            boolean ok = f.length > 1000;
            if (ok) {
                for (String e : new String[]{"ttf", "otf", "woff", "woff2"}) new java.io.File(getFilesDir(), "font." + e).delete();
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "font." + ext))) { o.write(f); }
                main.post(() -> { HomeActivity h = HomeActivity.front; if (h != null) h.run("window.loadFont && loadFont()"); });
            }
            reply(s.getOutputStream(), "text/plain", ok ? "ok" : "empty");
            return;
        }
        String body = readBody(in, head);
        if (first.startsWith("POST /send")) {
            String text = body.trim();
            if (text.length() > MAX) text = text.substring(0, MAX);
            if (!text.isEmpty()) {
                String t = text;
                main.post(() -> show(t));
            }
        } else if (first.startsWith("POST /draw")) {
            String segs = body;
            main.post(() -> draw(segs));
        } else if (first.startsWith("POST /mouse")) {
            String cmds = body;
            main.post(() -> { MouseService m = MouseService.on; if (m != null) for (String l : cmds.split("\n")) m.command(l); });
        } else if (first.startsWith("POST /quran")) {
            String[] q = body.trim().split("\\s+");
            String rec = q.length > 1 ? q[0] : "", n = q[q.length - 1];
            if (rec.matches("[a-z]{0,3}[0-9a-z.]{0,30}") && n.matches("\\d{1,3}")) main.post(() -> toHome("playRemote('" + rec + "'," + n + ")"));
        } else if (first.startsWith("POST /phrase")) {   // the line of the photo view, kept on the TV
            String t = body.trim(); if (t.length() > 120) t = t.substring(0, 120);
            getSharedPreferences("ui", MODE_PRIVATE).edit().putString("phrase", t).apply();
            main.post(() -> { HomeActivity h = HomeActivity.front; if (h != null) h.run("window.loadPhrase && loadPhrase()"); });
            Remote.event("phrase");
        } else if (first.startsWith("POST /mouse-on")) {   // the phone's «شغّل الماوس»: on now if the adb grant is there
            main.post(() -> { if (!Access.force(this)) Access.openSettings(this); });
        } else if (first.startsWith("POST /adhan")) {   // try the Haram adhan / iqamah from the phone (body: adhan | iqama | stop)
            String w = body.trim();
            if (w.equals("stop")) main.post(Adhan::stop);
            else new Thread(() -> { Adhan.sounds(this); main.post(() -> Adhan.fire(this, 3, true, w.equals("iqama"))); }, "adhan-try").start();
        } else if (first.startsWith("POST /key")) {
            String k = body.trim();
            main.post(() -> key(k));
        } else if (first.startsWith("POST /clear")) {
            main.post(this::hideDrawing);
        }
        reply(s.getOutputStream(), "text/plain", "ok");
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, state = 0;
        while ((c = in.read()) != -1 && b.size() < 8192) {
            b.write(c);
            state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) break;
        }
        return b.toString("UTF-8");
    }

    private static byte[] readBytes(InputStream in, String head, int max) throws Exception {
        int len = 0;
        for (String line : head.split("\r\n")) if (line.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
        byte[] b = new byte[Math.min(len, max)];
        int got = 0;
        while (got < b.length) { int r = in.read(b, got, b.length - got); if (r < 0) break; got += r; }
        return got == b.length ? b : java.util.Arrays.copyOf(b, got);
    }

    private static String readBody(InputStream in, String head) throws Exception {
        int len = 0;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
        }
        byte[] body = new byte[Math.min(len, 65536)];
        int got = 0;
        while (got < body.length) {
            int r = in.read(body, got, body.length - got);
            if (r < 0) break;
            got += r;
        }
        return new String(body, 0, got, StandardCharsets.UTF_8);
    }

    /**
     * The web version (mazendevloper.github.io/drivecast-tv) pairs by the TV's address: the browser, on the home Wi-Fi, opens
     * http://<tv-ip>:8080/pair?back=<the web page>, and the TV sends it straight back with its id and code in the address's
     * #fragment (never sent to any server). Only back to the web version's own pages.
     */
    private void pair(OutputStream out, String first) throws Exception {
        String back = "";
        int q = first.indexOf("back=");
        if (q > 0) back = java.net.URLDecoder.decode(first.substring(q + 5).split("[ &]")[0], "UTF-8");
        if (!back.startsWith("https://mazendevloper.github.io/drivecast-tv/")) back = "https://mazendevloper.github.io/drivecast-tv/";
        back = back.split("#")[0] + "#pair=" + Remote.id(this) + "." + Remote.pin(this);
        byte[] b = ("<meta http-equiv=refresh content=\"0;url=" + back + "\"><a href=\"" + back + "\">DriveCast TV</a>").getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 302 Found\r\nLocation: " + back + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static void reply(OutputStream out, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static WindowManager.LayoutParams overlay(int size) {
        return new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
    }

    private void draw(String segs) {
        if (!Settings.canDrawOverlays(this)) return;
        if (drawing == null) {
            drawing = new DrawLayer(this);
            try {
                getSystemService(WindowManager.class).addView(drawing, overlay(WindowManager.LayoutParams.MATCH_PARENT));
            } catch (Exception e) {
                drawing = null;
                return;
            }
        }
        DrawLayer d = drawing;
        // The view has no size until its first layout; draw after it.
        d.post(() -> {
            for (String seg : segs.split("\n")) {
                if (!seg.isEmpty()) d.add(seg);
            }
        });
        main.removeCallbacks(hideDrawing);
        main.postDelayed(hideDrawing, DRAW_IDLE);
    }

    /**
     * The phone remote. Volume and play/pause reach any app; «home» brings DriveCast TV to the front;
     * the arrows, OK and back drive the home screen (another app's own menus need the TV's remote).
     */
    private void key(String k) {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        switch (k) {
            case "volup": am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI); return;
            case "voldown": am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI); return;
            case "mute": am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_TOGGLE_MUTE, android.media.AudioManager.FLAG_SHOW_UI); return;
            case "play": media(am, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return;
            case "next": media(am, android.view.KeyEvent.KEYCODE_MEDIA_NEXT); return;
            case "prev": media(am, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); return;
            case "home":
                try { startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); } catch (Exception ignored) { }
                return;
        }
        HomeActivity h = HomeActivity.front;
        if (h != null) { h.remote(k); return; }
        MouseService m = MouseService.on;
        if (m != null && k.equals("back")) { m.command("b"); return; }
        // another app is open: the shortcuts first bring the home screen back
        if (k.equals("ambient") || k.equals("board") || k.equals("quran") || k.equals("live") || k.equals("match")) {
            try { startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); } catch (Exception ignored) { }
            main.postDelayed(() -> { HomeActivity a = HomeActivity.front; if (a != null) a.remote(k); }, 900);
        }
    }

    /** bring the home screen to the front, then run a line of its page */
    private void toHome(String js) {
        HomeActivity h = HomeActivity.front;
        if (h != null) { h.run(js); return; }
        try { startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); } catch (Exception ignored) { }
        main.postDelayed(() -> { HomeActivity a = HomeActivity.front; if (a != null) a.run(js); }, 1200);
    }

    private String pageCache;
    private String page() {
        if (pageCache == null) {
            try (InputStream in = getAssets().open("phone/index.html")) {
                ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int r;
                while ((r = in.read(b)) > 0) o.write(b, 0, r);
                pageCache = o.toString("UTF-8");
            } catch (Exception e) { return "<h1>DriveCast TV</h1>"; }
        }
        return pageCache;
    }

    /** DriveCast's reciters (its cloud: quran.com through drivecast.c-p.workers.dev), kept for 6 hours */
    private String recCache; private long recAt;
    private String reciters() {
        if (recCache != null && System.currentTimeMillis() - recAt < 6 * 3600_000L) return recCache;
        for (String u : new String[]{"https://drivecast.c-p.workers.dev/api/quran?path=resources/recitations&language=ar",
                "https://api.quran.com/api/v4/resources/recitations?language=ar"}) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(12000); c.setRequestProperty("User-Agent", "Mozilla/5.0 DriveCastTV");
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int r;
                    while ((r = in.read(b)) > 0) o.write(b, 0, r);
                    String t = o.toString("UTF-8");
                    if (t.contains("recitations")) { recCache = t; recAt = System.currentTimeMillis(); return t; }
                }
            } catch (Exception ignored) { }
        }
        return recCache != null ? recCache : "{\"recitations\":[]}";
    }

    private static void media(android.media.AudioManager am, int code) {
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
    }

    private void hideDrawing() {
        main.removeCallbacks(hideDrawing);
        if (drawing == null) return;
        DrawLayer d = drawing;
        drawing = null;
        d.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            try {
                getSystemService(WindowManager.class).removeView(d);
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void show(String text) {
        if (!Settings.canDrawOverlays(this)) return;
        WindowManager wm = getSystemService(WindowManager.class);
        if (shown != null) {
            try {
                wm.removeView(shown);
            } catch (Exception ignored) {
            }
        }
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(54);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setPadding(80, 50, 80, 50);
        v.setMaxWidth(1500);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9101418);
        bg.setCornerRadius(48);
        bg.setStroke(3, 0x66FFFFFF);
        v.setBackground(bg);
        v.setAlpha(0f);
        WindowManager.LayoutParams lp = overlay(WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        try {
            wm.addView(v, lp);
        } catch (Exception e) {
            return;
        }
        shown = v;
        v.animate().alpha(1f).setDuration(400).start();
        main.postDelayed(() -> v.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            try {
                wm.removeView(v);
            } catch (Exception ignored) {
            }
            if (shown == v) shown = null;
        }).start(), 20_000);
    }

}
