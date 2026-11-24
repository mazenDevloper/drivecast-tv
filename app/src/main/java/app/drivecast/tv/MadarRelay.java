package app.drivecast.tv;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 📡 Madar's always-on link, kept by DriveCast. Some boxes (T6: an Allwinner Android 12 build) never let Madar run its own
 * background link (KeepService): no boot signal, no background start. DriveCast always runs there, so it stands in for it on
 * «المركز» as a Madar device (app=sys): «أيقظ أجهزة مدار» (fit/tv) reaches it, and it opens Madar's share — Madar captures the
 * screen with no prompt, steps aside, and its page tells the viewer which code to watch. Only when Madar is installed.
 */
final class MadarRelay {
    private MadarRelay() { }
    private static final String HUB = "wss://studio.c-p.workers.dev/hub/ws";
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static volatile WebSocket ws; private static Context app; private static long backoff = 2000;

    static boolean madar(Context c) { try { c.getPackageManager().getPackageInfo("com.madar.app", 0); return true; } catch (Exception e) { return false; } }

    static synchronized void start(Context c) {
        app = c.getApplicationContext();
        if (ws != null || !madar(app)) return;
        String code = Access.set(app).getString("madarRelay", "");
        if (code.isEmpty()) { code = String.valueOf(100000 + new SecureRandom().nextInt(900000)); Access.set(app).edit().putString("madarRelay", code).apply(); }
        String url = HUB + "?id=sys-" + code + "&name=" + android.net.Uri.encode("مدار · " + Build.MODEL) + "&kind=tablet&app=sys";
        ws = HTTP.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response r) { backoff = 2000; }
            @Override public void onMessage(WebSocket s, String text) {
                try {
                    JSONObject d = new JSONObject(text);
                    if (!"msg".equals(d.optString("type"))) return;
                    JSONObject m = d.optJSONObject("msg"), from = d.optJSONObject("from");
                    if (m == null) return;
                    String t = m.optString("type");
                    if ("wake".equals(t) || "open".equals(t)) H.post(() -> share(from == null ? "" : from.optString("id", ""), !"open".equals(t) && m.optBoolean("share", true)));
                } catch (Exception ignored) { }
            }
            @Override public void onClosed(WebSocket s, int code, String reason) { gone(s); }
            @Override public void onFailure(WebSocket s, Throwable e, Response r) { gone(s); }
        });
    }

    private static synchronized void gone(WebSocket s) {
        if (ws != s) return;
        ws = null;
        H.postDelayed(() -> start(app), backoff); backoff = Math.min(60_000, backoff * 2);
    }

    /** woken: Madar opens to share this screen with whoever woke it (it captures with no prompt, then steps aside) */
    private static void share(String to, boolean share) {
        try {
            Intent i = new Intent().setClassName("com.madar.app", "com.madar.app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            if (share) i.putExtra("autoshare", true).putExtra("shareTo", to);
            app.startActivity(i);
        } catch (Exception e) { Crash.note(app, "madar wake: " + e); }
    }
}
