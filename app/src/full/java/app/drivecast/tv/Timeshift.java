package app.drivecast.tv;

import android.content.Context;
import android.os.Environment;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

/** ⏪ the channel you are watching, kept for the last 30 minutes (HLS channels): its segments go to the USB disk (the SSD, else the
 *  TV's own app storage) and a local playlist of them — a 30-minute live window — is what ◀ ▶ in the player wind through.
 *  Change channel (or leave the player) and all of it is deleted. Only the channel in front is ever recorded. */
final class Timeshift {
    static final long WINDOW_MS = 30 * 60_000L;
    private static final class Seg { long seq; double dur; boolean disc; File f; }

    private final String src, ua, ref, org; private final File dir;
    private final ArrayList<Seg> segs = new ArrayList<>();
    private volatile boolean run = true; private volatile String fail;
    private long lastSeq = -1; private int target = 6; private String media; private boolean first = true;

    private Timeshift(File dir, String src, String ua, String ref, String org) { this.dir = dir; this.src = src; this.ua = ua; this.ref = ref; this.org = org; }

    /** where the recording goes: a removable volume (the USB SSD) when one is mounted, else the app's own storage */
    static File root(Context c) {
        try {
            File[] all = c.getExternalFilesDirs(null);
            if (all != null) for (File f : all) if (f != null && Environment.isExternalStorageRemovable(f) && f.canWrite()) return new File(f, "timeshift");
            if (all != null && all.length > 1) for (int k = 1; k < all.length; k++) if (all[k] != null && all[k].canWrite()) return new File(all[k], "timeshift");
        } catch (Throwable ignored) { }
        return new File(c.getCacheDir(), "timeshift");
    }
    static boolean onDisk(Context c) { return !root(c).getAbsolutePath().startsWith(c.getCacheDir().getAbsolutePath()); }

    static Timeshift start(Context c, String url, String ua, String ref, String org) {
        try {
            File root = root(c); wipe(root); File d = new File(root, String.valueOf(System.currentTimeMillis())); if (!d.mkdirs()) return null;
            Timeshift t = new Timeshift(d, url, ua, ref, org);
            Thread th = new Thread(t::loop, "timeshift"); th.setDaemon(true); th.start();
            return t;
        } catch (Throwable e) { return null; }
    }
    String fail() { return fail; }
    synchronized long windowMs() { double s = 0; for (Seg g : segs) s += g.dur; return (long) (s * 1000); }
    String localUrl() { return android.net.Uri.fromFile(new File(dir, "live.m3u8")).toString(); }
    void stop() { run = false; new Thread(() -> wipe(dir), "ts-wipe").start(); }

    private static void wipe(File f) {
        try { File[] k = f.listFiles(); if (k != null) for (File x : k) wipe(x); f.delete(); } catch (Throwable ignored) { }
    }

    private HttpURLConnection open(String u, int read) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(read); c.setInstanceFollowRedirects(true);
        if (ua != null) c.setRequestProperty("User-Agent", ua);
        if (ref != null) c.setRequestProperty("Referer", ref);
        if (org != null) c.setRequestProperty("Origin", org);
        return c;
    }
    /** a playlist as text — up to 512 KB, and only if it really starts with #EXTM3U (a raw .ts channel is never read on) */
    private String text(String u) throws IOException {
        HttpURLConnection c = open(u, 12000);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) { o.write(b, 0, n); if (o.size() == n && n >= 7 && !new String(b, 0, 7).equals("#EXTM3U")) throw new IOException("not HLS"); if (o.size() > 512 * 1024) throw new IOException("big"); }
            return o.toString("UTF-8");
        } finally { c.disconnect(); }
    }
    private static String abs(String base, String rel) throws IOException { return new URL(new URL(base), rel.trim()).toString(); }

    /** a master playlist's variant: the best one up to ~8 Mbps (else the smallest) */
    private String pick(String master, String body) throws IOException {
        String best = null, low = null; long bw = -1, lw = Long.MAX_VALUE; long cur = 0; boolean next = false;
        for (String l : body.split("\r?\n")) {
            if (l.startsWith("#EXT-X-STREAM-INF")) { cur = num(l, "BANDWIDTH"); next = true; }
            else if (next && !l.startsWith("#") && !l.trim().isEmpty()) {
                next = false; String u = abs(master, l);
                if (cur <= 8_000_000L && cur > bw) { bw = cur; best = u; }
                if (cur < lw) { lw = cur; low = u; }
            }
        }
        return best != null ? best : low;
    }
    private static long num(String l, String k) { int p = l.indexOf(k + "="); if (p < 0) return 0; p += k.length() + 1; int e = p; while (e < l.length() && Character.isDigit(l.charAt(e))) e++; try { return Long.parseLong(l.substring(p, e)); } catch (Exception x) { return 0; } }

    private void loop() {
        int bad = 0;
        while (run) {
            try {
                if (media == null) { String t = text(src); media = t.contains("#EXT-X-STREAM-INF") ? pick(src, t) : src; if (media == null) throw new IOException("no variant"); }
                String body = text(media);
                if (body.contains("#EXT-X-STREAM-INF")) { media = null; continue; }
                if (body.contains("#EXT-X-MAP") || body.matches("(?s).*#EXT-X-KEY:[^\\n]*METHOD=(?!NONE).*")) { fail = "هالقناة مشفّرة أو بصيغة ما أقدر أسجّلها"; break; }
                long seq0 = 0; double dur = 0; boolean disc = false; int idx = 0;
                ArrayList<Object[]> found = new ArrayList<>();
                for (String l : body.split("\r?\n")) {
                    if (l.startsWith("#EXT-X-TARGETDURATION:")) { try { target = Math.max(2, Integer.parseInt(l.substring(22).trim())); } catch (Exception ignored) { } }
                    else if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) { try { seq0 = Long.parseLong(l.substring(22).trim()); } catch (Exception ignored) { } }
                    else if (l.startsWith("#EXT-X-DISCONTINUITY") && !l.startsWith("#EXT-X-DISCONTINUITY-")) disc = true;
                    else if (l.startsWith("#EXTINF:")) { try { dur = Double.parseDouble(l.substring(8).split(",")[0].trim()); } catch (Exception e) { dur = target; } }
                    else if (!l.startsWith("#") && !l.trim().isEmpty()) { found.add(new Object[]{seq0 + idx, dur, disc, abs(media, l)}); idx++; disc = false; }
                }
                int from = first ? Math.max(0, found.size() - 4) : 0; first = false;   // a start: the last 4 segments, not the whole list
                for (int k = from; k < found.size() && run; k++) {
                    Object[] o = found.get(k); long sq = (Long) o[0]; if (sq <= lastSeq) continue;
                    Seg s = new Seg(); s.seq = sq; s.dur = (Double) o[1]; s.disc = (Boolean) o[2]; s.f = new File(dir, sq + ".ts");
                    if (lastSeq >= 0 && sq > lastSeq + 1) s.disc = true;   // segments missed in between
                    download((String) o[3], s.f);
                    synchronized (this) { segs.add(s); } lastSeq = sq;
                    publish();
                }
                bad = 0;
            } catch (Throwable e) {
                if (!run) break;
                if (++bad > 8) { fail = "انقطع التسجيل: " + e.getMessage(); break; }
                media = null;   // maybe its token ran out: the master again
            }
            try { Thread.sleep(Math.max(1000, target * 500L)); } catch (InterruptedException ignored) { }
        }
    }

    private void download(String u, File to) throws IOException {
        HttpURLConnection c = open(u, 15000); File part = new File(to.getPath() + ".part");
        try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new FileOutputStream(part)) {
            byte[] b = new byte[64 * 1024]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally { c.disconnect(); }
        if (!part.renameTo(to)) { to.delete(); if (!part.renameTo(to)) throw new IOException("rename"); }
    }

    /** the local playlist (written whole, then renamed over the old one) and the 30-minute trim: the oldest leave the list first, then the disk */
    private void publish() throws IOException {
        ArrayList<Seg> gone = new ArrayList<>(), keep;
        synchronized (this) {
            while (segs.size() > 3 && windowMs() > WINDOW_MS + target * 1000L) gone.add(segs.remove(0));
            keep = new ArrayList<>(segs);
        }
        StringBuilder sb = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
        double mx = target; for (Seg s : keep) mx = Math.max(mx, Math.ceil(s.dur));
        sb.append("#EXT-X-TARGETDURATION:").append((int) mx).append("\n#EXT-X-MEDIA-SEQUENCE:").append(keep.isEmpty() ? 0 : keep.get(0).seq).append("\n");
        for (int k = 0; k < keep.size(); k++) {
            Seg s = keep.get(k); if (s.disc && k > 0) sb.append("#EXT-X-DISCONTINUITY\n");
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.dur)).append(",\n").append(s.f.getName()).append("\n");
        }
        File tmp = new File(dir, "live.m3u8.tmp"), live = new File(dir, "live.m3u8");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(sb.toString().getBytes("UTF-8")); }
        if (!tmp.renameTo(live)) { live.delete(); tmp.renameTo(live); }
        for (Seg s : gone) s.f.delete();
    }
}
