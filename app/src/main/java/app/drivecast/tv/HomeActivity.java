package app.drivecast.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The home screen: one WebView showing assets/home/index.html (the DriveCast-style shelves), with a small bridge
 * («TV») for what a page cannot do: list and open the installed apps, open a video in YouTube, the prayer times,
 * the city, the phone page's address. Pressing Home always comes back here.
 */
public class HomeActivity extends Activity {
    private WebView web;
    /** the home screen while it is in front (the phone remote drives it); null when another app is open */
    static volatile HomeActivity front;
    /** the home screen while it exists at all (the web version calls its bridge even when a film is in front) */
    static volatile HomeActivity alive;
    Bridge bridge;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Crash.install(this);
        alive = this;
        Reminder.schedule(this);
        Adhan.schedule(this);
        MessageServer.start(this);
        bridge = new Bridge();
        makeWeb();
    }

    /**
     * The page's WebView. While a channel plays, Android may stop the WebView's drawing process to free memory (a TV box
     * has little); unhandled, that took the whole app down and the TV jumped back to the home screen in the middle of
     * zapping. Now the dead view is dropped and a new one is made when the home screen is next shown.
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void makeWeb() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // the page is laid out for 1920×1080 and scaled to the screen: the TV's own «font size» setting must not make
        // its text bigger again (that wrapped the clock, the date and the salawat line word by word)
        s.setTextZoom(100);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        web.setBackgroundColor(0xFF0A0E13);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail d) {
                Crash.note(HomeActivity.this, "page " + (d.didCrash() ? "crashed" : "stopped to free memory"));
                if (view == web) {
                    android.view.ViewGroup p = (android.view.ViewGroup) view.getParent();
                    if (p != null) p.removeView(view);
                    view.destroy();
                    web = null;
                    if (front == HomeActivity.this) makeWeb();   // in front: back at once; else on the next onResume
                }
                return true;
            }
        });
        web.addJavascriptInterface(bridge, "TV");
        web.loadUrl("file:///android_asset/home/index.html");
        setContentView(web);
        web.requestFocus();
    }

    @Override
    protected void onDestroy() {
        if (alive == this) alive = null;
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (web != null && level >= TRIM_MEMORY_RUNNING_LOW) web.evaluateJavascript("window.onTvLowMemory && onTvLowMemory()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        front = this;
        alive = this;
        if (web == null) makeWeb();
        Access.keep(this);
        if (web != null) web.evaluateJavascript("window.onTvResume && onTvResume()", null);
    }

    @Override
    protected void onPause() {
        if (front == this) front = null;
        super.onPause();
    }

    /** a page from the Internet as text, with the headers each site wants (the page's getText; the web version too) */
    static String download(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(12000); c.setReadTimeout(30000);
            if (url.contains("youtube.com/results")) {   // YouTube search: the normal page, in Arabic
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
                c.setRequestProperty("Accept-Language", "ar,en;q=0.8"); c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI");
            } else if (url.contains("365scores.com")) {   // the fixtures API answers a browser
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Safari/537.36");
                c.setRequestProperty("Origin", "https://www.365scores.com"); c.setRequestProperty("Referer", "https://www.365scores.com/");
            } else c.setRequestProperty("User-Agent", "VLC/3.0.20");
            java.io.InputStream in = new java.io.BufferedInputStream(c.getInputStream());
            in.mark(2);
            int b0 = in.read(), b1 = in.read();
            in.reset();
            if (b0 == 0x1f && b1 == 0x8b) in = new java.util.zip.GZIPInputStream(in);
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int r; long total = 0;
            while ((r = in.read(buf)) > 0 && total < 12_000_000) { o.write(buf, 0, r); total += r; }
            return o.toString("UTF-8");
        } catch (Exception e) { return null; }
    }

    /** «لوحتي» as JSON (an empty board when none yet) */
    static String readBoard(Context c) {
        java.io.File f = new java.io.File(c.getFilesDir(), "board.json");
        if (!f.exists()) return "{\"bg\":\"paper\",\"items\":[]}";
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()]; int got = 0, r;
            while (got < b.length && (r = in.read(b, got, b.length - got)) > 0) got += r;
            return new String(b, 0, got, "UTF-8");
        } catch (Exception e) { return "{\"items\":[]}"; }
    }

    /** a line of the page's script (the phone remote's Quran) */
    void run(String js) { if (web != null) web.evaluateJavascript(js, null); }

    /** a key from the phone remote: up/down/left/right/ok/back/menu/ambient/quran/live/match/stop */
    void remote(String k) {
        if (web != null && k.matches("[a-z]{1,10}")) web.evaluateJavascript("window.onRemote && onRemote('" + k + "')", null);
    }

    /** Back on the home screen does nothing (it is home); the page closes its own panels first. */
    @Override
    public void onBackPressed() {
        if (web != null) web.evaluateJavascript("window.onTvBack ? onTvBack() : false", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // the remote's Menu key opens the page's settings
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            if (web != null) web.evaluateJavascript("window.onTvMenu && onTvMenu()", null);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    class Bridge {
        /** installed apps that can be opened: [{pkg, label, art (PNG data URL: TV banner, else icon), tv}] */
        @JavascriptInterface
        public String apps() {
            PackageManager pm = getPackageManager();
            JSONArray out = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                List<ResolveInfo> l = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
                for (ResolveInfo r : l) {
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        Drawable art = ai.loadBanner(pm);
                        boolean banner = art != null;
                        if (art == null) art = ai.loadIcon(pm);
                        out.put(new JSONObject().put("pkg", pkg).put("label", String.valueOf(ai.loadLabel(pm)))
                                .put("art", png(art, banner ? 320 : 128, banner ? 180 : 128)).put("tv", cat.equals(Intent.CATEGORY_LEANBACK_LAUNCHER)));
                    } catch (Exception ignored) {
                    }
                }
            }
            return out.toString();
        }

        @JavascriptInterface
        public boolean open(String pkg) {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        }

        /** a YouTube video (Quran, adhkar) in the YouTube TV app, else any player */
        @JavascriptInterface
        public void youtube(String id) {
            Uri v = Uri.parse("https://www.youtube.com/watch?v=" + id);
            try { startActivity(new Intent(Intent.ACTION_VIEW, v).setPackage("com.google.android.youtube.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { try { startActivity(new Intent(Intent.ACTION_VIEW, v).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { } }
        }

        @JavascriptInterface
        public void link(String url) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        }

        /** today's prayer times «HH:mm» ×5 and the city, as JSON; null times while offline on a first day */
        @JavascriptInterface
        public String prayers() {
            try {
                String[] t = Adhan.times(HomeActivity.this, Calendar.getInstance());
                Calendar tm = Calendar.getInstance(); tm.add(Calendar.DAY_OF_YEAR, 1);
                String[] n = Adhan.times(HomeActivity.this, tm);
                return new JSONObject().put("today", t == null ? JSONObject.NULL : new JSONArray(t))
                        .put("tomorrow", n == null ? JSONObject.NULL : new JSONArray(n)).put("city", City.name(HomeActivity.this))
                        .put("sunrise", String.valueOf(Adhan.sunrise(HomeActivity.this, Calendar.getInstance())))
                        .put("lat", City.lat(HomeActivity.this)).put("lon", City.lon(HomeActivity.this)).put("method", City.method(HomeActivity.this)).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void setCity(String lat, String lon, int method, String name) {
            City.set(HomeActivity.this, Double.parseDouble(lat), Double.parseDouble(lon), method, name);
            Adhan.schedule(HomeActivity.this);
            Remote.event("city");
        }

        /** the phone page's address, http://<tv-ip>:8080 */
        @JavascriptInterface
        public String phoneUrl() {
            try {
                int ip = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).getConnectionInfo().getIpAddress();
                if (ip == 0) return "";
                return String.format(Locale.US, "http://%d.%d.%d.%d:%d", ip & 255, (ip >> 8) & 255, (ip >> 16) & 255, (ip >> 24) & 255, MessageServer.PORT);
            } catch (Exception e) { return ""; }
        }

        /** a YouTube channel (the subscriptions from DriveCast) */
        @JavascriptInterface
        public void channel(String id) { link("https://www.youtube.com/channel/" + id); }

        /** IPTV: the player with the whole list, up / down change channel */
        @JavascriptInterface
        public void watch(String urlsJson, String namesJson, int index) {
            try {
                JSONArray u = new JSONArray(urlsJson), n = new JSONArray(namesJson);
                String[] us = new String[u.length()], ns = new String[u.length()];
                for (int k = 0; k < us.length; k++) { us[k] = u.getString(k); ns[k] = n.optString(k, ""); }
                startActivity(new Intent(HomeActivity.this, PlayerActivity.class).putExtra("urls", us).putExtra("names", ns).putExtra("i", index));
            } catch (Exception ignored) { }
        }

        /** IPTV with each channel's logo and group too (the player's info bar): [{url,name,logo,group}] */
        @JavascriptInterface
        public void watchFull(String channelsJson, int index) {
            try {
                JSONArray a = new JSONArray(channelsJson);
                int n = a.length(); String[] us = new String[n], ns = new String[n], ls = new String[n], gs = new String[n];
                for (int k = 0; k < n; k++) { JSONObject c = a.getJSONObject(k); us[k] = c.optString("url"); ns[k] = c.optString("name"); ls[k] = c.optString("logo"); gs[k] = c.optString("group"); }
                startActivity(new Intent(HomeActivity.this, PlayerActivity.class).putExtra("urls", us).putExtra("names", ns).putExtra("logos", ls).putExtra("groups", gs).putExtra("i", index));
            } catch (Exception ignored) { }
        }

        /** a text from the internet without the page's CORS limits (IPTV lists, DriveCast's data); answers cb(text|null) */
        @JavascriptInterface public String board() { return readBoard(HomeActivity.this); }
        @JavascriptInterface public void saveBoard(String json) {
            if (json == null || !json.startsWith("{")) return;
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "board.json"))) { o.write(json.getBytes("UTF-8")); } catch (Exception ignored) { }
            Remote.event("board");
        }

        /** the line the user wrote for the photo view (phone page → «اكتب») */
        @JavascriptInterface
        public String phrase() { return getSharedPreferences("ui", MODE_PRIVATE).getString("phrase", ""); }

        /** the font the user sent from the phone page, as a data URL (empty when none) */
        @JavascriptInterface
        public String font() {
            for (String e : new String[]{"ttf", "otf", "woff2", "woff"}) {
                java.io.File f = new java.io.File(getFilesDir(), "font." + e);
                if (!f.exists()) continue;
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    byte[] b = new byte[(int) f.length()]; int got = 0, r;
                    while (got < b.length && (r = in.read(b, got, b.length - got)) > 0) got += r;
                    String mime = e.equals("otf") ? "font/otf" : e.equals("woff2") ? "font/woff2" : e.equals("woff") ? "font/woff" : "font/ttf";
                    return "data:" + mime + ";base64," + android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
                } catch (Exception ignored) { }
            }
            return "";
        }

        @JavascriptInterface
        public void fetch(String url, String cb) {
            new Thread(() -> {
                String text = download(url);
                final String js = cb + "(" + (text == null ? "null" : JSONObject.quote(text)) + ")";
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
            }, "fetch").start();
        }

        /** ⭐ the favourite channels, shared with DriveCast: saved in its IPTV favourites (that one cloud list only), cb(true|false) */
        @JavascriptInterface
        public void saveFavs(String json, String cb) {
            new Thread(() -> {
                boolean ok = false;
                try {
                    new JSONObject(json);
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://studio.c-p.workers.dev/bin/69a87b8bd0ea881f40eeec0c?from=dctv-" + Remote.id(HomeActivity.this)).openConnection();
                    c.setRequestMethod("PUT"); c.setDoOutput(true); c.setConnectTimeout(10000); c.setReadTimeout(15000);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (java.io.OutputStream o = c.getOutputStream()) { o.write(json.getBytes("UTF-8")); }
                    ok = c.getResponseCode() == 200; c.disconnect();
                } catch (Exception ignored) { }
                final String js = cb + "(" + ok + ")";
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
            }, "favs").start();
        }

        /** the daily channel scan: starts it when the last one is over a day old (or force), true if it started */
        @JavascriptInterface
        public boolean scanStart(String urlsJson, boolean force) { return Scan.start(HomeActivity.this, urlsJson, force); }

        /** {t, running, done, total, r: {url: 1|0}} */
        @JavascriptInterface
        public String scanState() { return Scan.state(HomeActivity.this); }

        /** every setting of the TV's settings panel, as JSON (the adhan, the player and the mouse read them too) */
        @JavascriptInterface
        public String prefs() { return new JSONObject(Access.set(HomeActivity.this).getAll()).toString(); }

        @JavascriptInterface
        public void setPref(String k, String v) {
            if (k == null || !k.matches("[a-z0-9-]{1,20}") || k.startsWith("webpin") || k.equals("webid")) return;
            Access.set(HomeActivity.this).edit().putString(k, v == null ? "" : v).apply();
            if ("force".equals(k)) Access.keep(HomeActivity.this);
            if ("web".equals(k)) { if ("off".equals(v)) Remote.stop(); else Remote.start(HomeActivity.this); }
            Remote.event("prefs");
        }

        /** the web version: {pin, online, url} for the settings */
        @JavascriptInterface
        public String webInfo() {
            try {
                return new JSONObject().put("pin", Remote.pin(HomeActivity.this)).put("online", Remote.online()).put("id", Remote.id(HomeActivity.this))
                        .put("url", "https://mazendevloper.github.io/drivecast-tv/").toString();
            } catch (Exception e) { return "{}"; }
        }

        /** the last problem the app met (Crash.java), for the settings */
        @JavascriptInterface
        public String lastProblem() { String c = Crash.read(HomeActivity.this); int n = c.indexOf('\n'); return n < 0 ? c : c.substring(0, Math.min(n, 220)); }

        @JavascriptInterface
        public String webNewPin() { return Remote.newPin(HomeActivity.this); }

        /** the mouse: {on: switched on in Android, live: connected now, force: the adb grant is there} */
        @JavascriptInterface
        public String mouse() {
            return "{\"on\":" + Access.enabled(HomeActivity.this) + ",\"live\":" + (MouseService.on != null) + ",\"force\":" + Access.canForce(HomeActivity.this) + "}";
        }

        @JavascriptInterface
        public boolean mouseOn() {
            boolean ok = Access.force(HomeActivity.this);
            if (!ok) runOnUiThread(() -> Access.openSettings(HomeActivity.this));
            return ok;
        }

        @JavascriptInterface
        public void accessSettings() { runOnUiThread(() -> Access.openSettings(HomeActivity.this)); }

        /** «جرّب» in the settings: the adhan or the iqamah now */
        @JavascriptInterface
        public void tryAdhan(String what) {
            Context app = getApplicationContext();
            if ("stop".equals(what)) { runOnUiThread(Adhan::stop); return; }
            new Thread(() -> { Adhan.sounds(app); runOnUiThread(() -> Adhan.fire(app, 3, true, "iqama".equals(what))); }, "adhan-try").start();
        }

        /** OK held on a channel: the stream in another player on the TV (VLC, MX…), YouTube links in YouTube */
        @JavascriptInterface
        public void external(String url) {
            runOnUiThread(() -> {
                try {
                    if (url.contains("youtu")) { link(url); return; }
                    Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(url), "video/*");
                    startActivity(Intent.createChooser(v, "شغّل القناة في…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) { android.widget.Toast.makeText(HomeActivity.this, "ما فيه مشغّل ثاني على التلفزيون", android.widget.Toast.LENGTH_LONG).show(); }
            });
        }

        @JavascriptInterface
        public boolean canOverlay() { return Settings.canDrawOverlays(HomeActivity.this); }

        @JavascriptInterface
        public void settings() {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        }
    }

    private static String png(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 90, o);
        return "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
    }
}
