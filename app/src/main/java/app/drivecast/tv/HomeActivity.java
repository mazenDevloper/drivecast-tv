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
import java.util.HashMap;
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
    WebView web;   // Remote: «كمّل على التلفزيون» asks through the page
    /** the home screen while it is in front (the phone remote drives it); null when another app is open */
    static volatile HomeActivity front;
    /** the home screen while it exists at all (the web version calls its bridge even when a film is in front) */
    static volatile HomeActivity alive;
    Bridge bridge;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Iptv.init(this);
        Crash.install(this);
        alive = this;
        Update.afterStart(getApplicationContext());
        Reminder.schedule(this);
        Adhan.schedule(this);
        MessageServer.start(this);
        bridge = new Bridge();
        makeWeb();
        if (!booted) { booted = true; if (android.os.SystemClock.elapsedRealtime() < 5 * 60_000L) wake = "boot"; }
        if (waker == null) {
            waker = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, Intent i) {
                    HomeActivity a = front;
                    if (a != null && a.web != null) a.web.postDelayed(() -> { if (front == a && a.web != null) a.web.evaluateJavascript("window.onTvWake && onTvWake('screen')", null); }, 2500);
                }
            };
            getApplicationContext().registerReceiver(waker, new android.content.IntentFilter(Intent.ACTION_SCREEN_ON));
        }
    }
    static boolean booted; static String wake = ""; static android.content.BroadcastReceiver waker;

    /**
     * The page's WebView. While a channel plays, Android may stop the WebView's drawing process to free memory (a TV box
     * has little); unhandled, that took the whole app down and the TV jumped back to the home screen in the middle of
     * zapping. Now the dead view is dropped and a new one is made when the home screen is next shown.
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    /** 🔇 no clicks when the focus moves: the system's navigation sounds off for this view and all inside it */
    static void quiet(android.view.View v) {
        if (v == null) return;
        v.setSoundEffectsEnabled(false);
        if (v instanceof android.view.ViewGroup) { android.view.ViewGroup g = (android.view.ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) quiet(g.getChildAt(i)); }
    }

    private void makeWeb() {
        web = new WebView(this);
        web.setSoundEffectsEnabled(false);
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
        // ⚡ the home answers the remote first: drawn on the GPU, its renderer kept at the top priority (not dropped to save
        // memory while it is in front), the parts just off the screen drawn ahead so a move never waits for them
        web.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
        if (android.os.Build.VERSION.SDK_INT >= 23) s.setOffscreenPreRaster(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) { web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false); s.setSafeBrowsingEnabled(false); }
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
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
        // the page's DevTools only while the TV's own adb debugging is on (whoever has adb has the TV already): to look into a
        // problem on the TV itself
        try { android.webkit.WebView.setWebContentsDebuggingEnabled(android.provider.Settings.Global.getInt(getContentResolver(), android.provider.Settings.Global.ADB_ENABLED, 0) == 1); } catch (Throwable ignored) { }
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

    /** the TV's address on the home network: the Wi-Fi's, or the cable's (Ethernet) when the TV isn't on Wi-Fi */
    /** the TV's address on ZeroTier (the ZeroTier One app running on the TV: a VPN, «tun0»/«zt…») — the phone remote from anywhere */
    static String ztIp() {
        String lan = lanIp();
        try {
            for (java.net.NetworkInterface n : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                String nm = n.getName();
                if (!n.isUp() || !(nm.startsWith("tun") || nm.startsWith("zt") || nm.startsWith("ppp"))) continue;
                for (java.net.InetAddress ad : java.util.Collections.list(n.getInetAddresses()))
                    if (ad instanceof java.net.Inet4Address && !ad.getHostAddress().equals(lan)) return ad.getHostAddress();
            }
        } catch (Exception ignored) {}
        return "";
    }

    static String lanIp() {
        try {
            for (java.net.NetworkInterface n : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || n.isVirtual()) continue;
                String nm = n.getName();
                if (!(nm.startsWith("wlan") || nm.startsWith("eth") || nm.startsWith("en") || nm.startsWith("wl"))) continue;
                for (java.net.InetAddress ad : java.util.Collections.list(n.getInetAddresses()))
                    if (ad instanceof java.net.Inet4Address && ad.isSiteLocalAddress()) return ad.getHostAddress();
            }
            for (java.net.NetworkInterface n : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
                if (n.isUp() && !n.isLoopback()) for (java.net.InetAddress ad : java.util.Collections.list(n.getInetAddresses()))
                    if (ad instanceof java.net.Inet4Address && ad.isSiteLocalAddress()) return ad.getHostAddress();
        } catch (Exception ignored) {}
        return "";
    }

    @Override
    protected void onResume() {
        super.onResume();
        Access.keep(this);   // the mouse / remote keys service back in the list if anything took it out
        MessageServer.start(this);   // the phone remote's server: back if the system stopped it while the TV slept
        Reminder.regrant(getApplicationContext());   // 🔑 the box may have taken the apps' permissions back
        quiet(getWindow().getDecorView());
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
    /** the page a channel's own site plays its stream from: some CDNs answer only with it (Referer / Origin), and a browser's name */
    static String siteOf(String url) {
        if (url == null) return null;
        if (url.contains("alkassdigital.net")) return "https://www.alkass.net";
        if (url.contains("cdn.mangomolo.com") || url.contains("cdn.mgmlcdn.com")) {
            String h = android.net.Uri.parse(url).getHost();
            return h != null && h.startsWith("adm") ? "https://adtv.ae" : h != null && h.startsWith("dmi") ? "https://www.dubaisports.ae" : "https://www.awaan.ae";
        }
        return null;
    }
    static final String BROWSER = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    static String download(String url) { return download(url, 0); }
    /** (a redirect from http to https — some channel sites send one — is followed by hand: Java does not cross protocols) */
    static String download(String url, int hops) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12000); c.setReadTimeout(30000);
            if (url.contains("youtube.com/results")) {   // YouTube search: the normal page, in Arabic
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
                c.setRequestProperty("Accept-Language", "ar,en;q=0.8"); c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI");
            } else if (url.contains("365scores.com")) {   // the fixtures API answers a browser
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Safari/537.36");
                c.setRequestProperty("Origin", "https://www.365scores.com"); c.setRequestProperty("Referer", "https://www.365scores.com/");
            } else if (siteOf(url) != null) {
                c.setConnectTimeout(6000); c.setReadTimeout(8000);
                Iptv.apply(c, url);
            } else Iptv.apply(c, url);   // 📡 an IPTV list / stream: as an IPTV player (or the list's own headers)
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && hops < 5) { String to = c.getHeaderField("Location"); c.disconnect(); return to == null ? null : download(new java.net.URL(new java.net.URL(url), to).toString(), hops + 1); }
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
    private final Runnable watch = new Runnable() { public void run() { if (hasWindowFocus()) Reminder.remoteWatch(HomeActivity.this); h2.postDelayed(this, 60_000); } };
    private final android.os.Handler h2 = new android.os.Handler(android.os.Looper.getMainLooper());
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); h2.removeCallbacks(watch); if (f) h2.postDelayed(watch, 20_000); }
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

    private boolean colorEaten;
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // 🎙️ the remote's mic / search key: speech recognition in Arabic
        if (e.getKeyCode() == KeyEvent.KEYCODE_SEARCH) { if (e.getAction() == KeyEvent.ACTION_UP) voice(this, 77); return true; }
        // the colour keys (the Hisense remote over CEC): red YouTube, green the channels, yellow the Quran, blue the matches
        int ck = e.getKeyCode();
        if (ck == KeyEvent.KEYCODE_PROG_RED || ck == KeyEvent.KEYCODE_PROG_GREEN || ck == KeyEvent.KEYCODE_PROG_YELLOW || ck == KeyEvent.KEYCODE_PROG_BLUE) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && PlayerActivity.colorBack(this, ck)) { colorEaten = true; return true; }   // T6 sent the player away: back to it
            if (colorEaten) { if (e.getAction() == KeyEvent.ACTION_UP) colorEaten = false; return true; }
            if (e.getAction() == KeyEvent.ACTION_UP && web != null) web.evaluateJavascript("window.onTvColor && onTvColor('" + (ck == KeyEvent.KEYCODE_PROG_RED ? "red" : ck == KeyEvent.KEYCODE_PROG_GREEN ? "green" : ck == KeyEvent.KEYCODE_PROG_YELLOW ? "yellow" : "blue") + "')", null);
            return true;
        }
        // the remote's Menu key opens the page's settings
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            if (web != null) web.evaluateJavascript("window.onTvMenu && onTvMenu()", null);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 🎙️ Android's speech recognition in Arabic (the TV's own, usually Google's); none on the box → the page says so */
    static void voice(android.app.Activity a, int code) {
        try {
            Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ar-SA").putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA")
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "قل اسم القناة أو المباراة أو البرنامج").putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            a.startActivityForResult(i, code);
        } catch (Exception e) { HomeActivity h = alive; if (h != null) h.run("window.voiceNone && voiceNone()"); }
    }
    /** the words heard → the page's voiceQuery (the best guesses, in order) */
    static void voiceResult(Intent data) {
        if (data == null) return;
        java.util.ArrayList<String> r = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (r == null || r.isEmpty()) return;
        HomeActivity h = alive; if (h != null) h.run("window.voiceQuery && voiceQuery(" + new JSONArray(r) + ")");
    }
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 77 && res == RESULT_OK) voiceResult(data);
    }

    /** the player for a list of channels [{url,name,logo,group,cc,ua,ref,org,epg,alts}] — from the page, or from the player itself
     *  (a match's channels: the player opens them without the home page, which Android may have put away) */
    static Intent playerIntent(android.content.Context ctx, JSONArray a, int index) throws org.json.JSONException {
        int n = a.length(); String[] us = new String[n], ns = new String[n], ls = new String[n], gs = new String[n], cs = new String[n], ps = new String[n];
        java.util.Map<String, JSONArray> epg = new HashMap<>(); java.util.Map<String, String[]> alts = new HashMap<>();
        for (int k = 0; k < n; k++) { JSONObject c = a.getJSONObject(k); us[k] = c.optString("url"); ns[k] = c.optString("name"); ls[k] = c.optString("logo"); gs[k] = c.optString("group"); ps[k] = c.optString("pl"); cs[k] = c.optString("cc");
            if (!c.optString("ua").isEmpty() || !c.optString("ref").isEmpty() || !c.optString("org").isEmpty()) Iptv.HDR.put(us[k], new String[]{c.optString("ua"), c.optString("ref"), c.optString("org")});
            JSONArray e = c.optJSONArray("epg"); if (e != null && e.length() > 0) epg.put(us[k], e);
            JSONArray al = c.optJSONArray("alts"); if (al != null && al.length() > 0) { String[] x = new String[al.length()]; for (int q = 0; q < x.length; q++) x[q] = al.optString(q); alts.put(us[k], x); } }
        PlayerActivity.EPG = epg; PlayerActivity.ALTS = alts;
        boolean auto = n > 0 && a.getJSONObject(0).optInt("auto") == 1;
        return PlayerActivity.big(ctx, new Intent().putExtra("auto", auto).putExtra("urls", us).putExtra("names", ns).putExtra("logos", ls).putExtra("groups", gs).putExtra("pls", ps).putExtra("ccs", cs).putExtra("i", index));
    }

    class Bridge {
        /** 🎛️ the remote's keys: learn the next key pressed (→ keyLearned(id, name, src)), the map {"src:code": action}, the guard, the last keys */
        @JavascriptInterface public boolean keyLearn() { if (MouseService.on == null) return false; Keys.learn = true; return true; }
        @JavascriptInterface public void keyLearnStop() { Keys.learn = false; }
        @JavascriptInterface public String keyMap() { return Access.set(HomeActivity.this).getString("keymap", "{}"); }
        @JavascriptInterface public void keyMapSet(String j) { Access.set(HomeActivity.this).edit().putString("keymap", j).apply(); }
        @JavascriptInterface public String keyGuard() { return Access.set(HomeActivity.this).getString("irguard", "on"); }
        @JavascriptInterface public void keyGuardSet(String v) { Access.set(HomeActivity.this).edit().putString("irguard", v).apply(); }
        @JavascriptInterface public String keyLog() { return Keys.log(); }
        /** 🎙️ the page's mic button */
        @JavascriptInterface
        public void voice() { runOnUiThread(() -> HomeActivity.voice(HomeActivity.this, 77)); }

        /** the lite edition (Arabic channels only, the radio tab, وضع المسلسلات first) */
        @JavascriptInterface
        public boolean lite() { return BuildConfig.LITE; }
        /** ▶ the player plays YouTube itself (YouTube's own embedded player inside it) */
        @JavascriptInterface
        public boolean ytIn() { return true; }
        /** "boot" once, just after the TV started; then "" */
        @JavascriptInterface
        public String wake() { String w = wake; wake = ""; return w; }
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
        public String ztUrl() { String a = ztIp(); return a.isEmpty() ? "" : "http://" + a + ":" + MessageServer.PORT; }
        @JavascriptInterface
        public String phoneUrl() {
            String a = lanIp();
            return a.isEmpty() ? "" : "http://" + a + ":" + MessageServer.PORT;
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
                startActivity(PlayerActivity.big(HomeActivity.this, new Intent().putExtra("urls", us).putExtra("names", ns).putExtra("i", index)));
            } catch (Exception ignored) { }
        }

        /** IPTV with each channel's logo and group too (the player's info bar): [{url,name,logo,group}] */
        @JavascriptInterface
        public void watchFull(String channelsJson, int index) {
            try { startActivity(playerIntent(HomeActivity.this, new JSONArray(channelsJson), index)); } catch (Exception ignored) { }
        }
        private void watchFullOld(String channelsJson, int index) {
            try {
                JSONArray a = new JSONArray(channelsJson);
                int n = a.length(); String[] us = new String[n], ns = new String[n], ls = new String[n], gs = new String[n], cs = new String[n], ps = new String[n];
                for (int k = 0; k < n; k++) { JSONObject c = a.getJSONObject(k); us[k] = c.optString("url"); ns[k] = c.optString("name"); ls[k] = c.optString("logo"); gs[k] = c.optString("group"); ps[k] = c.optString("pl"); cs[k] = c.optString("cc");
                    if (!c.optString("ua").isEmpty() || !c.optString("ref").isEmpty() || !c.optString("org").isEmpty()) Iptv.HDR.put(us[k], new String[]{c.optString("ua"), c.optString("ref"), c.optString("org")}); }
                PlayerActivity.EPG = new HashMap<>();
                for (int k = 0; k < n; k++) { JSONArray e = a.getJSONObject(k).optJSONArray("epg"); if (e != null && e.length() > 0) PlayerActivity.EPG.put(us[k], e); }
                PlayerActivity.ALTS = new HashMap<>();
                for (int k = 0; k < n; k++) { JSONArray al = a.getJSONObject(k).optJSONArray("alts"); if (al == null || al.length() == 0) continue; String[] x = new String[al.length()]; for (int q = 0; q < x.length; q++) x[q] = al.optString(q); PlayerActivity.ALTS.put(us[k], x); }
                startActivity(PlayerActivity.big(HomeActivity.this, new Intent().putExtra("urls", us).putExtra("names", ns).putExtra("logos", ls).putExtra("groups", gs).putExtra("pls", ps).putExtra("ccs", cs).putExtra("i", index)));
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
        public String font() { return fontFile("font."); }
        /** the titles' font the user sent (Thmanyah Serif Display…), as a data URL (empty when none) */
        @JavascriptInterface
        public String titleFont() { return fontFile("tfont."); }
        private String fontFile(String pre) {
            for (String e : new String[]{"ttf", "otf", "woff2", "woff"}) {
                java.io.File f = new java.io.File(getFilesDir(), pre + e);
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

        /** 📡 the channels' own headers from their lists: {"url": ["ua", "referer", "origin"]} */
        @JavascriptInterface
        public void iptvHeaders(String json) { Iptv.put(json); }

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

        /** the scan waits while the arrows are in use (ms from now) */
        @JavascriptInterface
        public void scanHold(int ms) { Scan.holdUntil = System.currentTimeMillis() + Math.max(0, Math.min(ms, 60000)); }

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
        public void jsError(String m) { if (m != null && !m.isEmpty()) Crash.note(HomeActivity.this, "page: " + m.substring(0, Math.min(400, m.length()))); }
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

        /** 🔢 a channel's own 3-digit shortcut: {name, url} or "" */
        @JavascriptInterface
        public String chNum(String code) { org.json.JSONObject j = PlayerActivity.chNumGet(HomeActivity.this, code); return j == null ? "" : j.toString(); }
        /** ⬆️ 999: the newest build installed with nothing to press (the TV's own debugging shell, like the remote's «تحديث تلقائي») */
        @JavascriptInterface
        public void updAuto() { Update.auto(getApplicationContext(), true); }
        @JavascriptInterface
        public String updStatus() { return Update.status(getApplicationContext()); }

        /** ⬆️ is there a newer build on GitHub? cb(json {have, latest, newer, url}) */
        @JavascriptInterface
        public void updCheck(String cb) {
            new Thread(() -> { String j = Update.check(HomeActivity.this); runOnUiThread(() -> { if (web != null) web.evaluateJavascript(cb + "(" + j + ")", null); }); }, "upd").start();
        }
        /** ⬆️ download it and hand it to Android's installer; cb('down', pct) … cb('install', 100) / cb('allow') / cb('error') */
        @JavascriptInterface
        public void updGo(String url, String cb) {
            if (url == null || !url.startsWith("https://github.com/mazenDevloper/drivecast-tv/releases/")) return;   // only our own releases
            new Thread(() -> Update.install(HomeActivity.this, url, (st, pct) -> runOnUiThread(() -> { if (web != null) web.evaluateJavascript(cb + "('" + st + "'," + pct + ")", null); })), "upd").start();
        }
        @JavascriptInterface
        public String updFail() { String f = getSharedPreferences("upd", MODE_PRIVATE).getString("fail", ""); getSharedPreferences("upd", MODE_PRIVATE).edit().remove("fail").apply(); return f; }
        /** an app's page in the TV's store (Google Play), else on the web */
        @JavascriptInterface
        public void setMatches(String j) { PlayerActivity.MATCHES = j == null ? "[]" : j; }

        @JavascriptInterface
        public void setTicker(String t) { PlayerActivity.TICKER = t == null ? "" : t.length() > 600 ? t.substring(0, 600) : t; }

        /** ⚽ the live scores of the 🔔 matches for the player's bottom-left corner: a JSON array of lines */
        @JavascriptInterface
        public void playerScores(String j) { runOnUiThread(() -> PlayerActivity.scores(j)); }
        /** ⚽ a goal over a playing channel: the card with «حوّل للقناة» (OK), gone after 6 s; false when no channel plays */
        @JavascriptInterface
        public boolean playerGoal(String t) {
            if (PlayerActivity.live == null) return false;
            runOnUiThread(() -> PlayerActivity.goal(t)); return true;
        }

        /** the launcher's alert over a playing channel: true when a channel is playing (the alert went there) */
        @JavascriptInterface
        public boolean playerAlert(String t) {
            if (PlayerActivity.live == null) return false;
            runOnUiThread(() -> PlayerActivity.offer(t)); return true;
        }

        @JavascriptInterface
        public String watchStats() { return PlayerActivity.stats(HomeActivity.this); }

        /** an app's page in the TV's store, by a search */
        @JavascriptInterface
        public void storeFind(String q) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?c=apps&q=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?c=apps&q=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { } }
        }

        /** an app's page in the store */
        @JavascriptInterface
        public void store(String pkg) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { } }
        }

        /** the settings' time simulation: the adhan / iqamah of that prayer (its own sound: the fajr adhan for fajr) */
        @JavascriptInterface
        public void tryAdhanFor(String what, int prayer) {
            Context app = getApplicationContext();
            int p = Math.max(0, Math.min(4, prayer));
            new Thread(() -> { Adhan.sounds(app); runOnUiThread(() -> Adhan.fire(app, p, true, "iqama".equals(what))); }, "adhan-sim").start();
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

    static String png(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 90, o);
        return "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
    }
}
