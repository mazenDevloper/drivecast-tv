package app.drivecast.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** ⏺ «تسجيلاتي»: the TV records in the background, never in the way of what you watch.
 *  - a programme chosen in the guide: recorded from 2 min before to 5 min after, kept 2 days
 *  - the goals of your 🔔 matches: while one is live its carrier is kept in a 5-minute loop; a goal cuts ~3 minutes of it
 *    into a clip, kept 1 hour
 *  Gentle on watching: a low-priority thread, a stream of at most ~3 Mbps, and while the player shows a channel of the same
 *  subscription (one allowed connection) the recording waits instead of taking the player's connection.
 *  Each recording is HLS on disk (segments + index.m3u8): the TV's media player and the phone's browser both play it. */
final class Recorder {
    private static final long BUF_MS = 5 * 60_000L, CLIP_BEFORE = 150_000L, CLIP_AFTER = 30_000L, GOAL_KEEP = 3600_000L, EPG_KEEP = 2 * 86400_000L;
    private static final long MIN_FREE = 800L * 1024 * 1024;
    private static Context app; private static Thread tick;
    private static final Map<String, Cap> RUN = new HashMap<>();
    private static JSONArray jobs = new JSONArray();
    private static volatile boolean diskBad;

    // ---------- storage: the USB SSD only — never the TV's own memory (null when no SSD is mounted)
    static final String NOSSD = "الـSSD مو موصول — التسجيلات بس على الـSSD";
    static synchronized File root(Context c) {
        try {
            File[] all = c.getExternalFilesDirs(null);
            if (all != null) for (File f : all) if (f != null && Environment.isExternalStorageRemovable(f) && f.canWrite()) return new File(f, "recordings");
        } catch (Throwable ignored) { }
        return null;
    }
    private static void wipe(File f) { try { File[] k = f.listFiles(); if (k != null) for (File x : k) wipe(x); f.delete(); } catch (Throwable ignored) { } }
    private static long size(File d) { long s = 0; File[] k = d.listFiles(); if (k != null) for (File x : k) s += x.length(); return s; }

    // ---------- the jobs (kept in prefs)
    private static SharedPreferences prefs() { return app.getSharedPreferences("rec", Context.MODE_PRIVATE); }
    private static void save() { try { prefs().edit().putString("jobs", jobs.toString()).apply(); } catch (Throwable ignored) { } }
    private static JSONObject job(String id) { for (int k = 0; k < jobs.length(); k++) { JSONObject j = jobs.optJSONObject(k); if (j != null && id.equals(j.optString("id"))) return j; } return null; }

    static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        try { jobs = new JSONArray(prefs().getString("jobs", "[]")); } catch (Throwable e) { jobs = new JSONArray(); }
        startTick();
    }

    /** the guide's ⏺: {t, ch, logo, url, s, e} (seconds) → the job's id, or why not */
    static synchronized String add(Context c, String json) {
        init(c);
        try {
            JSONObject o = new JSONObject(json); String url = o.optString("url");
            if (!url.startsWith("http") || url.contains("youtube.com") || url.contains("youtu.be")) return "err:هذي القناة ما تتسجّل (يوتيوب أو رابط خاص)";
            long s = o.optLong("s") * 1000, e = o.optLong("e") * 1000, now = System.currentTimeMillis();
            if (e <= now) return "err:البرنامج خلص";
            for (int k = 0; k < jobs.length(); k++) { JSONObject j = jobs.optJSONObject(k); if (j != null && "epg".equals(j.optString("kind")) && j.optString("ch").equals(o.optString("ch")) && j.optLong("s") == s) return j.optString("id"); }
            JSONObject j = new JSONObject();
            String id = "e" + Long.toString(now, 36);
            j.put("id", id).put("kind", "epg").put("t", o.optString("t")).put("ch", o.optString("ch")).put("logo", o.optString("logo")).put("url", url)
             .put("s", s).put("e", e).put("from", Math.max(now, s - 120_000)).put("to", e + 300_000).put("keep", e + 300_000 + EPG_KEEP).put("st", "wait").put("at", now);
            jobs.put(j); save();
            startTick(); tick.interrupt();
            return root(c) == null ? "nossd:" + id : id;
        } catch (Throwable t) { return "err:" + t.getMessage(); }
    }
    private static void startTick() {
        if (tick != null && tick.isAlive()) return;
        tick = new Thread(() -> { while (true) { try { step(); } catch (Throwable e) { Crash.note(app, "rec: " + e); } try { Thread.sleep(15000); } catch (InterruptedException ie) { /* woken early */ } } }, "rec-tick");
        tick.setDaemon(true); tick.setPriority(Thread.MIN_PRIORITY); tick.start();
    }

    static synchronized void del(Context c, String id) {
        init(c);
        Cap cap = RUN.remove(id); if (cap != null) cap.stop();
        JSONArray keep = new JSONArray();
        for (int k = 0; k < jobs.length(); k++) { JSONObject j = jobs.optJSONObject(k); if (j == null) continue; if (id.equals(j.optString("id"))) { String d = j.optString("dir"); if (!d.isEmpty()) { final File f = new File(d); new Thread(() -> wipe(f)).start(); } } else keep.put(j); }
        jobs = keep; save();
    }

    /** the list for the TV's row and the phone (the 5-minute goal loop is not shown) */
    static synchronized String list(Context c) {
        init(c);
        JSONArray out = new JSONArray();
        try {
            for (int k = jobs.length() - 1; k >= 0; k--) {
                JSONObject j = jobs.optJSONObject(k); if (j == null || "buf".equals(j.optString("kind"))) continue;
                JSONObject o = new JSONObject(j.toString()); o.remove("url"); o.remove("dir");
                Cap cap = RUN.get(j.optString("id"));
                if (cap != null) { o.put("dur", cap.durMs()); o.put("size", cap.bytes); if (cap.waiting) o.put("note", "⏸ ينتظر — تشاهد قناة من نفس الاشتراك"); }
                out.put(o);
            }
        } catch (Throwable ignored) { }
        return out.toString();
    }

    /** the 🔔 matches live now: [{k, t, ch, logo, url}] — the first one's carrier kept in a 5-minute loop for its goals */
    static synchronized void watch(Context c, String json) {
        init(c);
        try {
            JSONArray a = new JSONArray(json); JSONObject want = a.length() > 0 ? a.getJSONObject(0) : null;
            long now = System.currentTimeMillis();
            for (int k = 0; k < jobs.length(); k++) {
                JSONObject j = jobs.optJSONObject(k); if (j == null || !"buf".equals(j.optString("kind"))) continue;
                if (want != null && want.optString("url").equals(j.optString("url"))) { j.put("k", want.optString("k")).put("t", want.optString("t")).put("to", now + 4 * 60_000); save(); return; }
            }
            if (want == null || !want.optString("url").startsWith("http") || want.optString("url").contains("youtube")) return;
            for (int k = 0; k < jobs.length(); k++) { JSONObject j = jobs.optJSONObject(k); if (j != null && "buf".equals(j.optString("kind"))) j.put("to", now); }   // one loop at a time
            JSONObject j = new JSONObject();
            j.put("id", "b" + Long.toString(now, 36)).put("kind", "buf").put("k", want.optString("k")).put("t", want.optString("t")).put("ch", want.optString("ch")).put("logo", want.optString("logo"))
             .put("url", want.optString("url")).put("from", now).put("to", now + 4 * 60_000).put("keep", now + 6 * 3600_000L).put("st", "wait").put("at", now);
            jobs.put(j); save(); startTick(); tick.interrupt();
        } catch (Throwable ignored) { }
    }

    /** ⚽ a goal: ~2.5 min before it to 30 s after, cut from the loop into its own clip (kept an hour) */
    static synchronized void goal(Context c, String key, String title) {
        init(c);
        Cap buf = null; JSONObject bj = null;
        for (Map.Entry<String, Cap> e : RUN.entrySet()) { JSONObject j = job(e.getKey()); if (j == null || !"buf".equals(j.optString("kind"))) continue; if (bj == null || key.equals(j.optString("k"))) { buf = e.getValue(); bj = j; } }
        if (buf == null || bj == null) return;
        long now = System.currentTimeMillis();
        if (now - buf.lastGoal < 60_000) return; buf.lastGoal = now;
        final Cap b = buf; final JSONObject src = bj; final String t = title == null || title.isEmpty() ? bj.optString("t") : title;
        new Thread(() -> {
            try { Thread.sleep(CLIP_AFTER); } catch (InterruptedException ignored) { }
            clip(b, src, t, now - CLIP_BEFORE, now + CLIP_AFTER);
        }, "rec-clip").start();
    }
    private static void clip(Cap b, JSONObject src, String title, long from, long to) {
        try {
            ArrayList<Seg> pick = b.between(from, to); if (pick.isEmpty()) return;
            String id = "g" + Long.toString(System.currentTimeMillis(), 36);
            File r = root(app); if (r == null) return;
            File d = new File(r, id); if (!d.mkdirs() && !d.isDirectory()) return;
            ArrayList<Seg> out = new ArrayList<>();
            for (Seg s : pick) { File f = new File(d, s.f.getName()); copy(s.f, f); Seg n = new Seg(); n.f = f; n.dur = s.dur; n.disc = s.disc; n.at = s.at; out.add(n); }
            writeList(d, out, true, 0);
            double dur = 0; for (Seg s : out) dur += s.dur;
            JSONObject j = new JSONObject();
            j.put("id", id).put("kind", "goal").put("t", title).put("ch", src.optString("ch")).put("logo", src.optString("logo")).put("k", src.optString("k"))
             .put("from", from).put("to", to).put("keep", System.currentTimeMillis() + GOAL_KEEP).put("st", "done").put("dir", d.getAbsolutePath())
             .put("dur", (long) (dur * 1000)).put("size", size(d)).put("at", System.currentTimeMillis());
            synchronized (Recorder.class) { jobs.put(j); save(); }
        } catch (Throwable e) { Crash.note(app, "rec clip: " + e); }
    }
    private static void copy(File a, File b) throws IOException {
        try (InputStream in = new FileInputStream(a); OutputStream o = new FileOutputStream(b)) { byte[] x = new byte[64 * 1024]; int n; while ((n = in.read(x)) > 0) o.write(x, 0, n); }
    }

    /** a file of a recording, for the phone (null when it is not one) */
    static synchronized File file(Context c, String id, String name) {
        init(c);
        if (!id.matches("[0-9a-z]{2,20}") || !name.matches("[0-9a-zA-Z_]{1,40}\\.(ts|m3u8)")) return null;
        JSONObject j = job(id); if (j == null || j.optString("dir").isEmpty()) return null;
        File f = new File(j.optString("dir"), name); return f.isFile() ? f : null;
    }
    /** the recording's playlist as the TV's media player opens it */
    static synchronized String playUrl(Context c, String id) {
        File f = file(c, id, "index.m3u8"); return f == null ? "" : android.net.Uri.fromFile(f).toString();
    }

    // ---------- the clock: start, stop, tidy (every 15 s)
    private static void step() {
        long now = System.currentTimeMillis();
        String player = PlayerActivity.nowUrl();
        ArrayList<String> gone = new ArrayList<>();
        synchronized (Recorder.class) {
            File rt = root(app);
            long free = rt != null && rt.getParentFile() != null ? rt.getParentFile().getUsableSpace() : 0;
            for (int k = 0; k < jobs.length(); k++) {
                JSONObject j = jobs.optJSONObject(k); if (j == null) continue;
                String id = j.optString("id"), kind = j.optString("kind"), st = j.optString("st");
                try {
                    if (now > j.optLong("keep")) { gone.add(id); continue; }
                    if ("goal".equals(kind)) continue;
                    Cap cap = RUN.get(id);
                    boolean inWin = now >= j.optLong("from") && now < j.optLong("to");
                    if (cap != null && (!inWin || cap.dead())) {
                        RUN.remove(id); cap.stop();
                        if ("buf".equals(kind)) { gone.add(id); continue; }
                        j.put("st", cap.dur() > 5 ? (cap.dead() && inWin ? "fail" : "done") : "fail").put("dur", cap.durMs()).put("size", cap.bytes);
                        if (cap.fail != null) j.put("note", cap.fail);
                        continue;
                    }
                    if (cap == null && inWin && ("wait".equals(st) || "rec".equals(st)) && rt == null) { if ("buf".equals(kind)) gone.add(id); else j.put("st", "wait").put("note", "⚠️ " + NOSSD); continue; }
                    if (cap == null && inWin && ("wait".equals(st) || "rec".equals(st))) {
                        if (free < MIN_FREE) { if (!"buf".equals(kind)) j.put("st", "fail").put("note", "المساحة ما تكفي"); else gone.add(id); continue; }
                        File d = j.optString("dir").isEmpty() ? new File(rt, id) : new File(j.optString("dir"));
                        j.remove("note");
                        if (!d.mkdirs() && !d.isDirectory()) { j.put("st", "fail").put("note", "ما قدرت أكتب على القرص"); continue; }
                        j.put("dir", d.getAbsolutePath()).put("st", "rec");
                        cap = new Cap(d, j.optString("url"), "buf".equals(kind)); RUN.put(id, cap); cap.start();
                    }
                    if (cap == null && !inWin && "buf".equals(kind)) gone.add(id);
                    else if (cap == null && now >= j.optLong("to") && "wait".equals(st)) j.put("st", "fail").put("note", rt == null ? "الـSSD ما كان موصول وقت البرنامج" : "التلفزيون كان مطفّي وقت البرنامج");
                    if (cap != null) {
                        cap.waiting = clash(cap.src, player);
                        if (free < MIN_FREE / 2) { cap.fail = "المساحة امتلأت"; cap.stop(); }
                    }
                } catch (Throwable e) { Crash.note(app, "rec step: " + e); }
            }
            boolean changed = !gone.isEmpty();
            for (String id : gone) {
                Cap cap = RUN.remove(id); if (cap != null) cap.stop();
                JSONObject j = job(id); if (j != null && !j.optString("dir").isEmpty()) wipe(new File(j.optString("dir")));
            }
            if (changed) { JSONArray keep = new JSONArray(); for (int k = 0; k < jobs.length(); k++) { JSONObject j = jobs.optJSONObject(k); if (j != null && !gone.contains(j.optString("id"))) keep.put(j); } jobs = keep; }
            save();
        }
    }

    // ---------- never take the player's connection: a subscription that allows one connection waits while its channel plays
    private static final Pattern XT = Pattern.compile("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/\\d+(?:\\.\\w+)?$");
    private static final Map<String, long[]> CONS = new HashMap<>();   // host → {max connections, when asked}
    private static boolean clash(String rec, String player) {
        if (player == null || !player.startsWith("http")) return false;
        String h1 = host(rec), h2 = host(player); if (h1 == null || !h1.equals(h2)) return false;
        return maxCons(rec) < 2;
    }
    private static String host(String u) { try { return new URL(u).getHost(); } catch (Throwable t) { return null; } }
    private static int maxCons(String u) {
        Matcher m = XT.matcher(u); if (!m.find()) return 1;
        String key = m.group(1); long[] c; synchronized (CONS) { c = CONS.get(key); }
        if (c != null && System.currentTimeMillis() - c[1] < 30 * 60_000) return (int) c[0];
        if (c == null || System.currentTimeMillis() - c[1] > 5 * 60_000) { synchronized (CONS) { CONS.put(key, new long[]{c == null ? 1 : c[0], System.currentTimeMillis() - 25 * 60_000}); }   // asked in the background (never holds the list); 1 meanwhile
            new Thread(() -> askCons(key, m.group(2), m.group(3), u), "rec-cons").start(); }
        return c == null ? 1 : (int) c[0];
    }
    private static void askCons(String key, String user, String pass, String u) {
        int max = 1;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(key + "/player_api.php?username=" + user + "&password=" + pass).openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(8000);
            String ua = Iptv.uaFor(u); if (ua != null) h.setRequestProperty("User-Agent", ua);
            try (InputStream in = h.getInputStream()) { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0 && o.size() < 64 * 1024) o.write(b, 0, n);
                JSONObject ui = new JSONObject(o.toString("UTF-8")).optJSONObject("user_info"); if (ui != null) max = Math.max(1, Integer.parseInt(ui.optString("max_connections", "1").trim())); }
            finally { h.disconnect(); }
        } catch (Throwable ignored) { }
        synchronized (CONS) { CONS.put(key, new long[]{max, System.currentTimeMillis()}); }
    }

    // ---------- one recording: HLS segments (the channel's own, or the raw stream cut every 6 s)
    private static final class Seg { File f; double dur; boolean disc; long at; }
    private static void writeList(File d, ArrayList<Seg> list, boolean end, long seq0) throws IOException {
        StringBuilder sb = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
        double mx = 2; for (Seg s : list) mx = Math.max(mx, Math.ceil(s.dur));
        sb.append("#EXT-X-TARGETDURATION:").append((int) mx).append("\n#EXT-X-MEDIA-SEQUENCE:").append(seq0).append("\n");
        if (end) sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n"); else if (seq0 == 0) sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n");
        for (int k = 0; k < list.size(); k++) { Seg s = list.get(k); if (s.disc && k > 0) sb.append("#EXT-X-DISCONTINUITY\n"); sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.dur)).append(",\n").append(s.f.getName()).append("\n"); }
        if (end) sb.append("#EXT-X-ENDLIST\n");
        File tmp = new File(d, "index.m3u8.tmp"), out = new File(d, "index.m3u8");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(sb.toString().getBytes("UTF-8")); }
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out); }
    }

    private static final class Cap {
        final File dir; final String src; final boolean loop; final String ua, ref, org;
        final ArrayList<Seg> segs = new ArrayList<>();
        volatile boolean run = true, waiting, finished; volatile String fail; volatile long bytes; long lastGoal, seq0;
        private long lastSeq = -1; private int target = 6, n; private String media; private boolean first = true, gap;
        Cap(File dir, String src, boolean loop) { this.dir = dir; this.src = src; this.loop = loop; ua = Iptv.uaFor(src); ref = Iptv.refererFor(src); org = Iptv.originFor(src); resume(); }
        void start() { Thread t = new Thread(this::main, "rec-cap"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); t.start(); }
        void stop() { run = false; try { if (!loop) synchronized (this) { writeList(dir, segs, true, seq0); } } catch (Throwable ignored) { } }
        boolean dead() { return finished; }
        synchronized double dur() { double s = 0; for (Seg g : segs) s += g.dur; return s; }
        long durMs() { return (long) (dur() * 1000); }
        synchronized ArrayList<Seg> between(long a, long b) { ArrayList<Seg> o = new ArrayList<>(); for (Seg s : segs) if (s.at >= a && s.at - s.dur * 1000 <= b) o.add(s); return o; }
        /** after a restart (the TV went off mid-programme): the segments already on disk carry on */
        private void resume() {
            try {
                File f = new File(dir, "index.m3u8"); if (!f.isFile()) return;
                String body; try (InputStream in = new FileInputStream(f)) { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int k; while ((k = in.read(b)) > 0) o.write(b, 0, k); body = o.toString("UTF-8"); }
                double d = 0; boolean disc = false;
                for (String l : body.split("\n")) {
                    if (l.startsWith("#EXTINF:")) { try { d = Double.parseDouble(l.substring(8).split(",")[0]); } catch (Exception e) { d = 6; } }
                    else if (l.startsWith("#EXT-X-DISCONTINUITY")) disc = true;
                    else if (!l.startsWith("#") && !l.trim().isEmpty()) { Seg s = new Seg(); s.f = new File(dir, l.trim()); s.dur = d; s.disc = disc; disc = false; if (s.f.isFile()) { segs.add(s); bytes += s.f.length(); } }
                }
                n = segs.size() + 1; gap = true;
            } catch (Throwable ignored) { }
        }
        private void main() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            int bad = 0; boolean raw = false;
            try {
                while (run) {
                    if (waiting) { gap = true; media = null; first = true; sleep(5000); continue; }
                    try {
                        if (raw) { rawLoop(); bad = 0; continue; }
                        if (media == null) {
                            String u = src.replaceAll("\\.ts$", ".m3u8"), t;
                            try { t = text(u); } catch (IOException e) { if (u.equals(src)) throw e; t = text(u = src); }
                            media = t.contains("#EXT-X-STREAM-INF") ? pick(u, t) : u; if (media == null) throw new IOException("no variant");
                        }
                        hlsOnce(); bad = 0;
                    } catch (NotHls e) { raw = true; }
                    catch (Throwable e) {
                        if (!run) break;
                        if (e instanceof DiskError) { diskBad = true; fail = "الـSSD ما يقبل الكتابة (الكيبل أو الطاقة)"; break; }
                        if (++bad > 12) { fail = "انقطع البث"; break; }
                        media = null; gap = true; sleep(3000);
                    }
                    if (!raw) sleep(Math.max(1500, target * 500L));
                }
            } finally { finished = true; }
        }
        private void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

        private HttpURLConnection open(String u, int read) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(12000); c.setReadTimeout(read); c.setInstanceFollowRedirects(true);
            if (ua != null) c.setRequestProperty("User-Agent", ua); if (ref != null) c.setRequestProperty("Referer", ref); if (org != null) c.setRequestProperty("Origin", org);
            return c;
        }
        private String text(String u) throws IOException {
            HttpURLConnection c = open(u, 12000);
            try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int k;
                while ((k = in.read(b)) > 0) { o.write(b, 0, k); if (o.size() >= 7 && o.size() == k && !new String(b, 0, 7).equals("#EXTM3U")) throw new NotHls(); if (o.size() > 512 * 1024) throw new IOException("big"); }
                return o.toString("UTF-8");
            } finally { c.disconnect(); }
        }
        /** the variant closest under ~3 Mbps (else the smallest): light on the line the player shares */
        private String pick(String master, String body) throws IOException {
            String best = null, low = null; long bw = -1, lw = Long.MAX_VALUE, cur = 0; boolean next = false;
            for (String l : body.split("\r?\n")) {
                if (l.startsWith("#EXT-X-STREAM-INF")) { cur = num(l); next = true; }
                else if (next && !l.startsWith("#") && !l.trim().isEmpty()) { next = false; String u = new URL(new URL(master), l.trim()).toString(); if (cur <= 3_000_000L && cur > bw) { bw = cur; best = u; } if (cur < lw) { lw = cur; low = u; } }
            }
            return best != null ? best : low;
        }
        private long num(String l) { int p = l.indexOf("BANDWIDTH="); if (p < 0) return 0; p += 10; int e = p; while (e < l.length() && Character.isDigit(l.charAt(e))) e++; try { return Long.parseLong(l.substring(p, e)); } catch (Exception x) { return 0; } }

        private void hlsOnce() throws Exception {
            String body = text(media);
            if (body.contains("#EXT-X-STREAM-INF")) { media = null; return; }
            if (body.contains("#EXT-X-MAP") || body.matches("(?s).*#EXT-X-KEY:[^\\n]*METHOD=(?!NONE).*")) { fail = "القناة مشفّرة"; run = false; return; }
            long s0 = 0; double d = 0; int idx = 0; boolean disc = false; ArrayList<Object[]> found = new ArrayList<>();
            for (String l : body.split("\r?\n")) {
                if (l.startsWith("#EXT-X-TARGETDURATION:")) { try { target = Math.max(2, Integer.parseInt(l.substring(22).trim())); } catch (Exception ignored) { } }
                else if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) { try { s0 = Long.parseLong(l.substring(22).trim()); } catch (Exception ignored) { } }
                else if (l.startsWith("#EXT-X-DISCONTINUITY") && !l.startsWith("#EXT-X-DISCONTINUITY-")) disc = true;
                else if (l.startsWith("#EXTINF:")) { try { d = Double.parseDouble(l.substring(8).split(",")[0].trim()); } catch (Exception e) { d = target; } }
                else if (!l.startsWith("#") && !l.trim().isEmpty()) { found.add(new Object[]{s0 + idx, d, disc, new URL(new URL(media), l.trim()).toString()}); idx++; disc = false; }
            }
            int from = first ? Math.max(0, found.size() - 3) : 0; first = false;
            for (int k = from; k < found.size() && run && !waiting; k++) {
                Object[] o = found.get(k); long sq = (Long) o[0]; if (sq <= lastSeq) continue;
                Seg s = new Seg(); s.dur = (Double) o[1]; s.disc = (Boolean) o[2] || gap || (lastSeq >= 0 && sq > lastSeq + 1); gap = false;
                s.f = new File(dir, String.format(Locale.US, "s%06d.ts", n++));
                download((String) o[3], s.f); s.at = System.currentTimeMillis();
                lastSeq = sq; add(s);
            }
        }
        private void download(String u, File to) throws IOException {
            HttpURLConnection c = open(u, 15000); File part = new File(to.getPath() + ".part");
            try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                OutputStream out; try { out = new FileOutputStream(part); } catch (IOException e) { throw new DiskError(); }
                try (OutputStream o = out) { byte[] b = new byte[64 * 1024]; int k; while ((k = in.read(b)) > 0) { try { o.write(b, 0, k); } catch (IOException e) { throw new DiskError(); } } }
            } finally { c.disconnect(); }
            if (!part.renameTo(to)) { to.delete(); if (!part.renameTo(to)) throw new DiskError(); }
        }
        /** a raw MPEG-TS channel: read as it comes, cut into 6-second files on a packet (188 bytes) edge */
        private void rawLoop() throws IOException {
            HttpURLConnection c = open(src, 20000);
            try (InputStream in = new BufferedInputStream(c.getInputStream(), 256 * 1024)) {
                byte[] b = new byte[188 * 350]; boolean d0 = true;
                while (run && !waiting) {
                    Seg s = new Seg(); s.f = new File(dir, String.format(Locale.US, "s%06d.ts", n++)); s.disc = d0 || gap; d0 = false; gap = false;
                    long t0 = System.currentTimeMillis();
                    try (OutputStream o = new FileOutputStream(s.f)) {
                        while (run && !waiting && System.currentTimeMillis() - t0 < 6000) {
                            int got = 0; while (got < b.length) { int k = in.read(b, got, b.length - got); if (k < 0) break; got += k; }
                            if (got == 0) throw new IOException("ended");
                            try { o.write(b, 0, got); } catch (IOException e) { throw new DiskError(); }
                            if (got < b.length) throw new IOException("ended");
                        }
                    } catch (DiskError e) { throw e; }
                    catch (IOException e) { if (s.f.length() > 0) { s.dur = (System.currentTimeMillis() - t0) / 1000.0; s.at = System.currentTimeMillis(); add(s); } else s.f.delete(); throw e; }
                    s.dur = (System.currentTimeMillis() - t0) / 1000.0; s.at = System.currentTimeMillis(); add(s);
                }
            } finally { c.disconnect(); }
            if (waiting) gap = true;
        }
        /** a new segment: into the list, the loop trimmed to 5 minutes, the playlist written again */
        private void add(Seg s) throws IOException {
            ArrayList<Seg> drop = new ArrayList<>(), copy;
            synchronized (this) {
                segs.add(s); bytes += s.f.length();
                if (loop) { double sum = 0; for (Seg g : segs) sum += g.dur; while (segs.size() > 3 && sum * 1000 > BUF_MS) { Seg g = segs.remove(0); sum -= g.dur; bytes -= g.f.length(); drop.add(g); seq0++; } }
                copy = new ArrayList<>(segs);
            }
            for (Seg g : drop) g.f.delete();
            if (!run) return;   // stopped: stop() wrote the final list
            try { writeList(dir, copy, false, seq0); } catch (IOException e) { throw new DiskError(); }
        }
    }
    private static final class NotHls extends IOException { }
    private static final class DiskError extends IOException { }
}
