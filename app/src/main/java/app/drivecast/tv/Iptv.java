package app.drivecast.tv;

import android.content.Context;

import java.net.HttpURLConnection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 📡 How DriveCast TV asks IPTV servers for lists and streams: as an IPTV player, not a web browser (many IPTV panels and
 * CDNs refuse a browser), and with each channel's own headers when its list gives them (#EXTVLCOPT:http-user-agent /
 * http-referrer / http-origin, #EXTHTTP, «url|User-Agent=…&Referer=…»). The player, the scan and the list downloads all ask
 * the same way, so a channel that plays is also found working by the scan.
 */
final class Iptv {
    static final String VLC = "VLC/3.0.20 LibVLC/3.0.20";
    private static Context app;
    /** a channel's own headers from its list: its address → {user agent, referer, origin} (each may be empty) */
    static final Map<String, String[]> HDR = new ConcurrentHashMap<>();

    private Iptv() { }

    static void init(Context c) { if (c != null) app = c.getApplicationContext(); }

    /** the client the servers are told (setting «ua»): an IPTV player (VLC's — the most widely accepted) by default */
    static String ua() {
        String w = app == null ? "vlc" : Access.set(app).getString("ua", "vlc");
        if ("app".equals(w)) return "DriveCastTV/" + BuildConfig.VERSION_NAME + " (Linux; Android " + android.os.Build.VERSION.RELEASE + "; TV) IPTV";
        if ("browser".equals(w)) return HomeActivity.BROWSER;
        return VLC;
    }

    /** the user agent for this address: its list's own, else the channel site's browser (siteOf), else the IPTV player */
    static String uaFor(String url) {
        String[] h = url == null ? null : HDR.get(url);
        if (h != null && h[0] != null && !h[0].isEmpty()) return h[0];
        return HomeActivity.siteOf(url) != null ? HomeActivity.BROWSER : ua();
    }

    /** Referer / Origin for this address (its list's own, else the channel's site); null when none */
    static String refererFor(String url) {
        String[] h = url == null ? null : HDR.get(url);
        if (h != null && h[1] != null && !h[1].isEmpty()) return h[1];
        String s = HomeActivity.siteOf(url); return s == null ? null : s + "/";
    }
    static String originFor(String url) {
        String[] h = url == null ? null : HDR.get(url);
        if (h != null && h[2] != null && !h[2].isEmpty()) return h[2];
        return HomeActivity.siteOf(url);
    }

    /** a connection asks as the player would */
    static void apply(HttpURLConnection c, String url) {
        c.setRequestProperty("User-Agent", uaFor(url));
        String r = refererFor(url), o = originFor(url);
        if (r != null) c.setRequestProperty("Referer", r);
        if (o != null) c.setRequestProperty("Origin", o);
    }

    /** the page tells the channels' own headers: {"url": ["ua", "referer", "origin"], …} */
    static void put(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String u = it.next(); org.json.JSONArray a = o.optJSONArray(u); if (a == null) continue;
                HDR.put(u, new String[]{a.optString(0, ""), a.optString(1, ""), a.optString(2, "")});
            }
        } catch (Exception ignored) { }
    }
}
