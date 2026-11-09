package app.drivecast.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * The web version of DriveCast TV (mazendevloper.github.io/drivecast-tv): the TV keeps one WebSocket open to DriveCast's
 * hub (studio.c-p.workers.dev/hub, the same «المركز» the car, the phone and Madar use) as a device of kind «tv», app «dctv».
 * A browser anywhere joins the hub too and sends the TV messages:
 *   {type:'dctv', rid, pin, call:'<bridge method>', args:[…]}     the home page's own bridge (apps, board, watch, youtube…)
 *   {type:'dctv', rid, pin, http:'GET|POST', path:'/key', body}   the phone page's routes (MessageServer), for the web remote
 * and gets {type:'dctv-r', rid, ok, value} back. Changes made on the TV (the board, the phrase, a setting, the city) go out as
 * {type:'dctv-ev', what}. Nothing is answered without the TV's 6-digit code (settings → «الجهاز»); ten wrong codes lock it
 * for ten minutes. «الويب» off in the settings closes the link.
 */
final class Remote {
    static final String HUB = "wss://studio.c-p.workers.dev/hub/ws";
    private static Context app;
    private static WebSocket ws;
    private static String id;
    private static int backoff = 2, wrong;
    private static long lockedUntil;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static final OkHttpClient http = new OkHttpClient.Builder().pingInterval(40, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build();

    private Remote() { }

    static SharedPreferences set() { return Access.set(app); }

    /** the TV's 6-digit code for the web version (made once; «رمز جديد» in the settings makes another) */
    static String pin(Context c) {
        SharedPreferences s = Access.set(c);
        String p = s.getString("webpin", "");
        if (!p.matches("\\d{6}")) { p = String.format(java.util.Locale.US, "%06d", new SecureRandom().nextInt(1_000_000)); s.edit().putString("webpin", p).apply(); }
        return p;
    }

    static String newPin(Context c) { Access.set(c).edit().remove("webpin").apply(); return pin(c); }

    static String id(Context c) {
        SharedPreferences s = Access.set(c);
        String v = s.getString("webid", "");
        if (v.isEmpty()) { v = "dctv-" + Long.toString(new SecureRandom().nextLong() & 0xffffffffffL, 36); s.edit().putString("webid", v).apply(); }
        return v;
    }

    static boolean online() { return ws != null; }

    static synchronized void start(Context c) {
        app = c.getApplicationContext();
        id = id(app); pin(app);
        if (ws != null || "off".equals(set().getString("web", "on"))) return;
        String name = "DriveCast TV";
        Request r = new Request.Builder().url(HUB + "?id=" + id + "&name=" + android.net.Uri.encode(name) + "&kind=tv&app=dctv").build();
        ws = http.newWebSocket(r, new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response res) { backoff = 2; }
            @Override public void onMessage(WebSocket s, String text) { new Thread(() -> handle(s, text), "web-call").start(); }
            @Override public void onClosed(WebSocket s, int code, String reason) { gone(s); }
            @Override public void onFailure(WebSocket s, Throwable t, Response res) { gone(s); }
        });
    }

    static synchronized void stop() { if (ws != null) { ws.close(1000, "off"); ws = null; } }

    private static synchronized void gone(WebSocket s) {
        if (ws != s) return;
        ws = null;
        if ("off".equals(set().getString("web", "on"))) return;
        h.postDelayed(() -> start(app), backoff * 1000L);   // the hub or the Wi-Fi went away: again, a little later each time
        backoff = Math.min(backoff * 2, 120);
    }

    /** something changed on the TV: every open web page refreshes that part */
    static void event(String what) {
        WebSocket s = ws; if (s == null) return;
        try { s.send(new JSONObject().put("op", "send").put("to", "kind:dctv-web").put("msg", new JSONObject().put("type", "dctv-ev").put("what", what)).toString()); }
        catch (Exception ignored) { }
    }

    private static void handle(WebSocket s, String text) {
        JSONObject d, m;
        try { d = new JSONObject(text); } catch (Exception e) { return; }
        if (!"msg".equals(d.optString("type")) || (m = d.optJSONObject("msg")) == null || !"dctv".equals(m.optString("type"))) return;
        String to = d.optJSONObject("from") != null ? d.optJSONObject("from").optString("id") : "";
        if (to.isEmpty()) return;
        Object value; boolean ok = true;
        if (System.currentTimeMillis() < lockedUntil) { ok = false; value = "locked"; }
        else if (!pin(app).equals(m.optString("pin"))) {
            ok = false; value = "pin";
            if (++wrong >= 10) { wrong = 0; lockedUntil = System.currentTimeMillis() + 10 * 60_000L; }
        } else {
            wrong = 0;
            try {
                if (m.has("http")) value = local(m.optString("http"), m.optString("path"), m.optString("body"), m.optBoolean("b64"));
                else value = call(m.optString("call"), m.optJSONArray("args"));
            } catch (Exception e) { ok = false; value = String.valueOf(e.getMessage()); }
        }
        try {
            JSONObject r = new JSONObject().put("type", "dctv-r").put("rid", m.opt("rid")).put("ok", ok).put("value", value == null ? JSONObject.NULL : value);
            s.send(new JSONObject().put("op", "send").put("to", to).put("msg", r).toString());
        } catch (Exception ignored) { }
    }

    /** the phone page's route on this TV (http://127.0.0.1:8080), its answer as text */
    private static String local(String method, String path, String body, boolean b64) throws Exception {
        if (!path.startsWith("/") || path.length() > 300) throw new Exception("path");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:" + MessageServer.PORT + path).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(30000);
        if ("POST".equals(method)) {
            byte[] b = b64 ? android.util.Base64.decode(body, android.util.Base64.DEFAULT) : body.getBytes("UTF-8");
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(b.length);
            try (java.io.OutputStream o = c.getOutputStream()) { o.write(b); }
        }
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int r; while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
            return o.toString("UTF-8");
        }
    }

    /** one of the home page's bridge methods (only those the page itself may call), or the web version's own */
    private static Object call(String name, JSONArray args) throws Exception {
        if (args == null) args = new JSONArray();
        switch (name) {
            case "fetch": { String u = args.optString(0); if (!u.startsWith("https://") && !u.startsWith("http://")) throw new Exception("url"); return HomeActivity.download(u); }
            case "snapshot": return snapshot();
            case "crash": return Crash.read(app);
            case "ping": return "pong";
        }
        HomeActivity a = HomeActivity.alive;
        if (a == null || a.bridge == null) throw new Exception("home");
        for (Method mt : a.bridge.getClass().getMethods()) {
            if (!mt.getName().equals(name) || mt.getAnnotation(JavascriptInterface.class) == null || mt.getParameterTypes().length != args.length()) continue;
            Class<?>[] t = mt.getParameterTypes(); Object[] v = new Object[t.length];
            for (int k = 0; k < t.length; k++) {
                if (t[k] == int.class) v[k] = args.optInt(k);
                else if (t[k] == boolean.class) v[k] = args.optBoolean(k);
                else if (t[k] == double.class) v[k] = args.optDouble(k);
                else v[k] = args.isNull(k) ? null : args.optString(k);
            }
            Object r = mt.invoke(a.bridge, v);
            // the TV's own page follows what the web changed
            String js = "saveBoard".equals(name) ? "window.loadBoard && loadBoard()" : "setPref".equals(name) ? "loadSet(); homeLayout(); window.applyWall && applyWall()"
                    : "setCity".equals(name) ? "setTimeout(() => window.loadPrayers && loadPrayers(), 1500)" : null;
            if (js != null) a.runOnUiThread(() -> a.run(js));
            return r;
        }
        throw new Exception("no " + name);
    }

    /** everything the web page reads at start, in one answer */
    private static JSONObject snapshot() throws Exception {
        JSONObject o = new JSONObject();
        for (String k : new String[]{"apps", "prayers", "prefs", "board", "phrase", "font", "mouse", "phoneUrl", "canOverlay"}) {
            try { o.put(k, call(k, new JSONArray())); } catch (Exception ignored) { }
        }
        o.put("tv", id).put("at", System.currentTimeMillis());
        return o;
    }
}
