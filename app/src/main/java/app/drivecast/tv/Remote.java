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

    static String newPin(Context c) {
        String old = key(c);
        Access.set(c).edit().remove("webpin").apply();
        String p = pin(c);
        cloudPut(old);   // the cloud copy moves to the new code's key
        return p;
    }

    /** the cloud copy's key: sha256("<tv id>:<code>"), the same in the web page */
    static String key(Context c) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest((id(c) + ":" + pin(c)).getBytes("UTF-8"));
            StringBuilder b = new StringBuilder(); for (byte x : d) b.append(String.format("%02x", x)); return b.toString();
        } catch (Exception e) { return ""; }
    }

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
            @Override public void onOpen(WebSocket s, Response res) { backoff = 2; cloudGet(); }
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

    // ---- the cloud copy (the hub keeps it privately): the board, the phrase and the settings, so the web version works with
    // the TV off. Whichever side changed last wins; the TV takes the web's changes the moment it is back online.
    private static final Runnable PUT = () -> cloudPut(null);
    private static boolean applying;

    private static JSONObject doc() throws Exception {
        JSONObject p = new JSONObject(Access.set(app).getAll());
        p.remove("webpin"); p.remove("webid"); p.remove("cloudat");
        JSONObject o = new JSONObject().put("board", HomeActivity.readBoard(app))
                .put("phrase", app.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("phrase", "")).put("prefs", p);
        HomeActivity a = HomeActivity.alive;
        if (a != null && a.bridge != null) {
            try { o.put("prayers", a.bridge.prayers()); } catch (Exception ignored) { }
            try { String apps = a.bridge.apps(); if (apps.length() < 400_000) o.put("apps", apps); } catch (Exception ignored) { }
        }
        return o;
    }

    static void cloudPut(String oldKey) {
        WebSocket s = ws; if (s == null || app == null || applying) return;
        new Thread(() -> {
            try {
                JSONObject m = new JSONObject().put("op", "dctv-put").put("tv", id).put("key", key(app)).put("data", doc().toString()).put("rid", "tv-put");
                if (oldKey != null) m.put("oldKey", oldKey);
                s.send(m.toString());
            } catch (Exception ignored) { }
        }, "cloud-put").start();
    }

    private static void cloudGet() {
        WebSocket s = ws; if (s == null) return;
        try { s.send(new JSONObject().put("op", "dctv-get").put("tv", id).put("key", key(app)).put("rid", "tv-get").toString()); } catch (Exception ignored) { }
    }

    /** the cloud copy came back: newer than what the TV has → take it; else (or none yet) the TV's goes up */
    private static void cloudGot(JSONObject r) {
        long at = r.optLong("at"), mine = set().getLong("cloudat", 0);
        String data = r.optString("data", "");
        if (!r.optBoolean("ok")) return;
        if (data.isEmpty() || "null".equals(data) || at < mine) { cloudPut(null); return; }   // none yet, or the TV's is newer
        if (at == mine) return;
        try {
            JSONObject d = new JSONObject(data);
            applying = true;
            if (d.has("board")) try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(app.getFilesDir(), "board.json"))) { o.write(d.getString("board").getBytes("UTF-8")); }
            if (d.has("phrase")) app.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("phrase", d.getString("phrase")).apply();
            JSONObject p = d.optJSONObject("prefs");
            if (p != null) {
                SharedPreferences.Editor e = Access.set(app).edit();
                for (java.util.Iterator<String> it = p.keys(); it.hasNext(); ) { String k = it.next(); if (!k.startsWith("web") && !k.equals("cloudat")) e.putString(k, p.optString(k)); }
                e.apply();
            }
            set().edit().putLong("cloudat", at).apply();
            HomeActivity a = HomeActivity.alive;
            if (a != null) a.runOnUiThread(() -> a.run("window.loadBoard && loadBoard(); window.loadPhrase && loadPhrase(); loadSet(); homeLayout(); window.applyWall && applyWall()"));
        } catch (Exception ignored) { }
        finally { applying = false; }
    }

    /** something changed on the TV: every open web page refreshes that part, and the cloud copy follows (3 s later) */
    static void event(String what) {
        h.removeCallbacks(PUT); h.postDelayed(PUT, 3000);
        WebSocket s = ws; if (s == null) return;
        try { s.send(new JSONObject().put("op", "send").put("to", "kind:dctv-web").put("msg", new JSONObject().put("type", "dctv-ev").put("what", what)).toString()); }
        catch (Exception ignored) { }
    }

    private static void handle(WebSocket s, String text) {
        JSONObject d, m;
        try { d = new JSONObject(text); } catch (Exception e) { return; }
        if ("dctv-store".equals(d.optString("type"))) {
            if ("tv-get".equals(d.optString("rid"))) cloudGot(d);
            else if ("tv-put".equals(d.optString("rid")) && d.optBoolean("ok")) set().edit().putLong("cloudat", d.optLong("at")).apply();
            return;
        }
        if ("dctv-changed".equals(d.optString("type")) && id.equals(d.optString("tv"))) { cloudGet(); return; }
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
