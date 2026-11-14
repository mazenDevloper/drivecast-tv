package app.drivecast.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** One full scan of the channels, once a day: each stream is opened and read a little; works → green, does not → red. */
public class Scan {
    private static final AtomicInteger done = new AtomicInteger(), total = new AtomicInteger();
    private static volatile boolean running = false;
    static volatile boolean paused = false;
    /** the user is moving around the home: the scan waits till the arrows rest (it held the network and the UI's thread) */
    static volatile long holdUntil = 0;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("scan", Context.MODE_PRIVATE); }

    /** start (only if the last full scan is over a day old, or force) */
    public static synchronized boolean start(Context ctx, String urlsJson, boolean force) {
        final Context app = ctx.getApplicationContext();
        if (running) return false;
        SharedPreferences p = sp(app);
        final boolean fresh = !force && System.currentTimeMillis() - p.getLong("t", 0) < 23L * 3600_000;
        final JSONObject res = new JSONObject();
        // the results kept: a forced scan of some channels replaces only theirs
        { try { JSONObject old = new JSONObject(p.getString("r", "{}")); java.util.Iterator<String> it = old.keys(); while (it.hasNext()) { String k = it.next(); res.put(k, old.get(k)); } } catch (Exception ignored) { } }
        final List<String> urls = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(urlsJson);
            // a day's scan is done: only the channels it has not seen yet (the foreign lists load later) are tested, and join its results
            for (int k = 0; k < a.length(); k++) { String u = a.optString(k); if (u.startsWith("http") && !urls.contains(u) && !(fresh && res.has(u))) urls.add(u); }
        } catch (Exception e) { return false; }
        if (urls.isEmpty()) return false;
        final boolean top = fresh;
        // 🤝 gentle on each server: its channels spread through the scan (one of each server in turn), at most 2 at a time and
        // 0.8 s apart; a server that stops answering (6 failures in a row) is left for the next scan — never asked thousands of
        // times in a row (a list of 10 000 channels on one host got the home's line blocked there)
        java.util.LinkedHashMap<String, java.util.ArrayDeque<String>> byHost = new java.util.LinkedHashMap<>();
        for (String u : urls) { String h = hostOf(u); java.util.ArrayDeque<String> q = byHost.get(h); if (q == null) byHost.put(h, q = new java.util.ArrayDeque<>()); q.add(u); }
        urls.clear();
        for (boolean any = true; any; ) { any = false; for (java.util.ArrayDeque<String> q : byHost.values()) { String u = q.poll(); if (u != null) { urls.add(u); any = true; } } }
        final java.util.Map<String, java.util.concurrent.Semaphore> gate = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Map<String, Long> last = new java.util.HashMap<>();
        final java.util.Map<String, AtomicInteger> fails = new java.util.concurrent.ConcurrentHashMap<>();
        running = true; done.set(0); total.set(urls.size());
        new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(4);   // gentle on the home network: 4 at a time (10 filled some routers, and the TV felt offline)
            for (final String u : urls) pool.execute(() -> {
                while (paused || System.currentTimeMillis() < holdUntil) { try { Thread.sleep(500); } catch (InterruptedException e) { return; } }
                final String h = hostOf(u);
                AtomicInteger miss = fails.get(h); if (miss == null) { fails.putIfAbsent(h, new AtomicInteger()); miss = fails.get(h); }
                java.util.concurrent.Semaphore g = gate.get(h); if (g == null) { gate.putIfAbsent(h, new java.util.concurrent.Semaphore(2)); g = gate.get(h); }
                if (miss.get() < 6) {
                    try { g.acquire(); } catch (InterruptedException e) { return; }
                    try {
                        long wait;
                        synchronized (last) { long now = System.currentTimeMillis(), at = Math.max(now, (last.containsKey(h) ? last.get(h) : 0L) + 800); last.put(h, at); wait = at - now; }
                        if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
                        if (miss.get() < 6) {
                            boolean ok = false;
                            try { ok = works(u); } catch (Throwable ignored) { }
                            JSONObject inf = null;
                            if (ok) { try { inf = info(u); } catch (Throwable ignored) { } }
                            if (inf == null) inf = new JSONObject();
                            if (ok) miss.set(0); else miss.incrementAndGet();
                            synchronized (res) { try { res.put(u, ok ? inf.put("ok", 1) : 0); } catch (Exception ignored) { } }
                        }
                    } finally { g.release(); }
                }
                int d = done.incrementAndGet();
                if (d % 40 == 0) synchronized (res) { p.edit().putString("r", res.toString()).apply(); }   // partial results show while it runs
            });
            pool.shutdown();
            try { pool.awaitTermination(8, java.util.concurrent.TimeUnit.HOURS); } catch (InterruptedException ignored) { }   // a big list on one host takes a while at 0.8 s apart
            synchronized (res) { SharedPreferences.Editor ed = p.edit().putString("r", res.toString()); if (!top) ed.putLong("t", System.currentTimeMillis()); ed.apply(); }
            running = false;
        }, "scan").start();
        return true;
    }

    private static String hostOf(String u) { try { String h = new URL(u).getHost(); return h == null ? "" : h.toLowerCase(java.util.Locale.ROOT); } catch (Exception e) { return ""; } }

    /** {t: last full scan (ms), running, done, total, r: {url: 1|0}} */
    public static String state(Context ctx) {
        SharedPreferences p = sp(ctx);
        try {
            JSONObject o = new JSONObject();
            o.put("t", p.getLong("t", 0)); o.put("running", running); o.put("done", done.get()); o.put("total", total.get());
            o.put("r", new JSONObject(p.getString("r", "{}")));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    private static HttpURLConnection open(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(6000); c.setReadTimeout(7000); c.setInstanceFollowRedirects(true);
        Iptv.apply(c, u);   // 📡 as the player asks: a channel that plays is found working
        return c;
    }

    private static byte[] head(HttpURLConnection c, int max) throws Exception {
        try (InputStream in = c.getInputStream()) {
            byte[] b = new byte[max]; int n = 0, r;
            while (n < max && (r = in.read(b, n, max - n)) > 0) n += r;
            byte[] o = new byte[n]; System.arraycopy(b, 0, o, 0, n); return o;
        }
    }

    /** what a working HLS channel offers, read from its master list's best variant: h (height), c (avc|hevc|av1|vp9), b (bit depth),
     *  r (SDR|HDR10|HLG|DV), f (fps), m (Mbps). Missing when the list does not say. */
    private static JSONObject info(String u) throws Exception {
        JSONObject o = new JSONObject();
        if (!u.toLowerCase().contains(".m3u8")) return o;
        HttpURLConnection c = open(u);
        try {
            if (c.getResponseCode() != 200) return o;
            String t = new String(head(c, 65536), "UTF-8");
            String bestLine = null; int bestH = -1; long bestBw = -1;
            String[] ls = t.split("\r?\n");
            for (String l : ls) {
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
                int hh = 0; java.util.regex.Matcher m = java.util.regex.Pattern.compile("RESOLUTION=\\d+x(\\d+)").matcher(l);
                if (m.find()) hh = Integer.parseInt(m.group(1));
                long bw = 0; m = java.util.regex.Pattern.compile("[^-]BANDWIDTH=(\\d+)").matcher(l);
                if (m.find()) bw = Long.parseLong(m.group(1));
                if (hh > bestH || (hh == bestH && bw > bestBw)) { bestH = hh; bestBw = bw; bestLine = l; }
            }
            if (bestLine == null) return o;
            if (bestH > 0) o.put("h", bestH);
            if (bestBw > 0) o.put("m", Math.round(bestBw / 1e5) / 10.0);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("FRAME-RATE=([\\d.]+)").matcher(bestLine);
            if (m.find()) o.put("f", (int) Math.round(Double.parseDouble(m.group(1))));
            m = java.util.regex.Pattern.compile("VIDEO-RANGE=(\\w+)").matcher(bestLine);
            String range = m.find() ? m.group(1).toUpperCase() : "SDR";
            m = java.util.regex.Pattern.compile("CODECS=\"([^\"]*)\"").matcher(bestLine);
            String codecs = m.find() ? m.group(1).toLowerCase() : "";
            int bits = 8; String codec = "";
            if (codecs.contains("hvc1") || codecs.contains("hev1")) { codec = "hevc"; if (codecs.matches(".*h[ve][c1]1?\\.2\\..*") || codecs.matches(".*(hvc1|hev1)\\.2\\..*")) bits = 10; }
            else if (codecs.contains("avc1") || codecs.contains("avc3")) { codec = "avc"; if (codecs.contains("avc1.6e") || codecs.contains("avc1.7a") || codecs.contains("avc1.f4")) bits = 10; }
            else if (codecs.contains("av01")) { codec = "av1"; if (codecs.contains(".10.")) bits = 10; }
            else if (codecs.contains("vp09") || codecs.contains("vp9")) { codec = "vp9"; if (codecs.contains("vp09.02")) bits = 10; }
            if (codecs.contains("dvh1") || codecs.contains("dvhe")) { range = "DV"; bits = 10; }
            if (!codec.isEmpty()) { o.put("c", codec); o.put("b", bits); }
            o.put("r", range.equals("PQ") ? "HDR10" : range);
            return o;
        } finally { c.disconnect(); }
    }

    private static boolean works(String u) {
        HttpURLConnection c = null;
        try {
            boolean hls = u.toLowerCase().contains(".m3u8");
            c = open(u);
            if (c.getResponseCode() != 200) return false;
            String type = String.valueOf(c.getContentType()).toLowerCase();
            if (!hls) {   // a plain stream (ts / mp4 / flv ...): some bytes come, and it is not a web page
                if (type.contains("text/html")) return false;
                return head(c, 4096).length >= 1024;
            }
            String t = new String(head(c, 65536), "UTF-8");
            if (!t.contains("#EXTM3U")) return false;
            URL base = c.getURL();
            // a master list: follow its first stream; a media list: its first piece must answer
            String next = null;
            for (String line : t.split("\r?\n")) { line = line.trim(); if (!line.isEmpty() && !line.startsWith("#")) { next = line; break; } }
            if (next == null) return false;
            String nu = new URL(base, next).toString();
            c.disconnect();
            if (nu.toLowerCase().contains(".m3u8")) {
                c = open(nu);
                if (c.getResponseCode() != 200) return false;
                String t2 = new String(head(c, 65536), "UTF-8");
                if (!t2.contains("#EXTM3U")) return false;
                URL b2 = c.getURL(); String seg = null;
                for (String line : t2.split("\r?\n")) { line = line.trim(); if (!line.isEmpty() && !line.startsWith("#")) { seg = line; break; } }
                if (seg == null) return false;
                nu = new URL(b2, seg).toString();
                c.disconnect();
            }
            c = open(nu);
            if (c.getResponseCode() != 200) return false;
            return head(c, 2048).length >= 512;
        } catch (Throwable e) {
            return false;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }
}
