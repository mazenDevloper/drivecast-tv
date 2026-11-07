package app.drivecast.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
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

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Reminder.schedule(this);
        Adhan.schedule(this);
        MessageServer.start(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(0xFF0A0E13);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "TV");
        web.loadUrl("file:///android_asset/home/index.html");
        setContentView(web);
        web.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onTvResume && onTvResume()", null);
    }

    /** Back on the home screen does nothing (it is home); the page closes its own panels first. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onTvBack ? onTvBack() : false", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // the remote's Menu key opens the page's settings
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            web.evaluateJavascript("window.onTvMenu && onTvMenu()", null);
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
                        .put("lat", City.lat(HomeActivity.this)).put("lon", City.lon(HomeActivity.this)).put("method", City.method(HomeActivity.this)).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void setCity(String lat, String lon, int method, String name) {
            City.set(HomeActivity.this, Double.parseDouble(lat), Double.parseDouble(lon), method, name);
            Adhan.schedule(HomeActivity.this);
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

        /** a text from the internet without the page's CORS limits (IPTV lists, DriveCast's data); answers cb(text|null) */
        @JavascriptInterface
        public void fetch(String url, String cb) {
            new Thread(() -> {
                String text = null;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(12000); c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "VLC/3.0.20");
                    java.io.InputStream in = new java.io.BufferedInputStream(c.getInputStream());
                    in.mark(2);
                    int b0 = in.read(), b1 = in.read();
                    in.reset();
                    if (b0 == 0x1f && b1 == 0x8b) in = new java.util.zip.GZIPInputStream(in);
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384]; int r; long total = 0;
                    while ((r = in.read(buf)) > 0 && total < 12_000_000) { o.write(buf, 0, r); total += r; }
                    text = o.toString("UTF-8");
                } catch (Exception ignored) { }
                final String js = cb + "(" + (text == null ? "null" : JSONObject.quote(text)) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }, "fetch").start();
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
