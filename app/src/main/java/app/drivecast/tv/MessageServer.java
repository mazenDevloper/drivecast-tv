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
                    s.setSoTimeout(5000);
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
            reply(s.getOutputStream(), "text/html", PAGE);
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

    private static final String PAGE = "<!doctype html><html dir=rtl lang=ar><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1,user-scalable=no'><title>رسالة للتلفزيون</title><style>"
            + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
            + "background:#0f1a17;font-family:-apple-system,system-ui,sans-serif;color:#fff}"
            + ".c{width:min(94vw,560px)}h1{font-size:24px;margin:0 0 14px}"
            + ".tabs{display:flex;gap:8px;margin-bottom:12px}.tabs button{flex:1;margin:0;background:#16241f;border:1px solid #2f4a42}"
            + ".tabs button.on{background:#1f6f50;border-color:#1f6f50}"
            + "textarea{width:100%;box-sizing:border-box;height:140px;font-size:22px;padding:14px;border-radius:16px;"
            + "border:1px solid #2f4a42;background:#16241f;color:#fff;resize:none}"
            + "canvas{width:100%;aspect-ratio:16/9;background:#16241f;border-radius:16px;border:1px solid #2f4a42;touch-action:none;display:block}"
            + ".row{display:flex;gap:10px;margin-top:12px;align-items:center}.dot{width:38px;height:38px;border-radius:50%;border:3px solid transparent}"
            + ".dot.on{border-color:#fff}"
            + "button{margin-top:14px;width:100%;padding:16px;font-size:20px;font-weight:700;border:0;border-radius:16px;"
            + "background:#1f6f50;color:#fff}.row button{margin:0;width:auto;flex:1;background:#3a2a2a}"
            + "p{opacity:.7;text-align:center;min-height:1.4em}.hide{display:none}</style></head><body>"
            + "<div class=c><h1>📺 للتلفزيون</h1>"
            + "<div class=tabs><button id=bw class=on onclick=tab(0)>✍️ اكتب</button><button id=bd onclick=tab(1)>🎨 ارسم</button></div>"
            + "<div id=w><textarea id=t maxlength=200 placeholder='اكتب هني...'></textarea><button onclick=send()>أرسل</button></div>"
            + "<div id=d class=hide><canvas id=cv></canvas><div class=row id=colors></div>"
            + "<div class=row><button onclick=clr()>امسح</button></div></div>"
            + "<p id=s></p></div><script>"
            + "function $(i){return document.getElementById(i)}"
            + "function tab(n){$('w').className=n?'hide':'';$('d').className=n?'':'hide';$('bw').className=n?'':'on';$('bd').className=n?'on':'';if(n)fit()}"
            + "function send(){var t=$('t');if(!t.value.trim())return;fetch('/send',{method:'POST',body:t.value})"
            + ".then(function(){$('s').textContent='وصلت ✅';t.value=''}).catch(function(){$('s').textContent='ما وصلت، جرّب مرة ثانية'})}"
            + "var cv=$('cv'),cx=cv.getContext('2d'),col='#ffffff',q=[],last=null,cont=false;"
            + "['#ffffff','#f5c542','#4ade80','#f87171','#60a5fa'].forEach(function(c,i){var b=document.createElement('div');"
            + "b.className='dot'+(i?'':' on');b.style.background=c;b.onclick=function(){col=c;"
            + "Array.prototype.forEach.call(document.querySelectorAll('.dot'),function(x){x.className='dot'});b.className='dot on'};$('colors').appendChild(b)});"
            + "function fit(){var r=cv.getBoundingClientRect();cv.width=r.width*2;cv.height=r.height*2}"
            + "function pt(e){var r=cv.getBoundingClientRect();return[(e.clientX-r.left)/r.width,(e.clientY-r.top)/r.height]}"
            + "function line(a,b){cx.strokeStyle=col;cx.lineWidth=6;cx.lineCap='round';cx.beginPath();"
            + "cx.moveTo(a[0]*cv.width,a[1]*cv.height);cx.lineTo(b[0]*cv.width,b[1]*cv.height);cx.stroke()}"
            + "cv.onpointerdown=function(e){cv.setPointerCapture(e.pointerId);last=pt(e);cont=false;q=[last]};"
            + "cv.onpointermove=function(e){if(!last)return;var p=pt(e);line(last,p);last=p;q.push(p)};"
            + "cv.onpointerup=cv.onpointercancel=function(){flush();last=null};"
            + "function flush(){if(q.length<1)return;var s=(cont?'+':'')+col+' '+q.map(function(p){return p[0].toFixed(4)+','+p[1].toFixed(4)}).join(' ');"
            + "if(last){q=[last];cont=true}else q=[];fetch('/draw',{method:'POST',body:s})}"
            + "setInterval(function(){if(last&&q.length>1)flush()},80);"
            + "function clr(){cx.clearRect(0,0,cv.width,cv.height);fetch('/clear',{method:'POST',body:''})}"
            + "</script></body></html>";
}
