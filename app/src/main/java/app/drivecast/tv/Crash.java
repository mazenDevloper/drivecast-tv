package app.drivecast.tv;

import android.content.Context;

/**
 * The last problems the app met (an exception that would close it, the page's drawing process going away), kept in
 * the app's files: the settings («الجهاز») and the phone page's /crash show them, so a sudden jump back to the home
 * screen leaves a trace instead of a mystery.
 */
final class Crash {
    private static boolean installed;
    private Crash() { }

    static void install(Context c) {
        if (installed) return;
        installed = true;
        Context app = c.getApplicationContext();
        Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            java.io.StringWriter w = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(w));
            String s = w.toString();
            note(app, "crash in " + t.getName() + ": " + s.substring(0, Math.min(1500, s.length())));
            if (old != null) old.uncaughtException(t, e);
        });
        flushSoon(app, 20_000);   // what happened last time (a crash closes the app before it can send) goes out after start
    }

    // 🧯 every problem also goes to DriveCast's server (studio.c-p.workers.dev/crash, links cut there), so the owner sees the
    // problems of every TV in one list without going to the TV
    private static volatile boolean pending;
    static void flushSoon(Context c, long ms) {
        if (pending) return; pending = true;
        Context app = c.getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> new Thread(() -> { pending = false; flush(app); }, "crash-send").start(), ms);
    }
    private static void flush(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("crash", Context.MODE_PRIVATE);
        String sent = p.getString("sent", ""), newest = null; int n = 0;
        for (String line : read(c).split("\n")) {
            if (line.length() < 21) continue;
            String at = line.substring(0, 19);
            if (at.compareTo(sent) <= 0) break;            // newest first: the rest were sent already
            if (newest == null) newest = at;
            if (++n > 15) break;
            try {
                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL("https://studio.c-p.workers.dev/crash").openConnection();
                h.setConnectTimeout(8000); h.setReadTimeout(8000); h.setRequestMethod("POST"); h.setDoOutput(true);
                h.setRequestProperty("content-type", "text/plain");
                String body = new org.json.JSONObject().put("app", BuildConfig.LITE ? "dctv-lite" : "dctv").put("ver", String.valueOf(BuildConfig.VERSION_CODE))
                        .put("dev", android.os.Build.MODEL).put("msg", line.substring(21)).toString();
                try (java.io.OutputStream o = h.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
                h.getResponseCode(); h.disconnect();
            } catch (Exception e) { return; }               // offline: try again next time
        }
        if (newest != null) p.edit().putString("sent", newest).apply();
    }

    /** one line (dated) at the top of crash.txt; the file keeps the last ~12 KB */
    static synchronized void note(Context c, String what) {
        java.io.File f = new java.io.File(c.getFilesDir(), "crash.txt");
        String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "  " + what + "\n";
        String rest = read(c);
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
            o.write((line + rest.substring(0, Math.min(rest.length(), 12000))).getBytes("UTF-8"));
        } catch (Exception ignored) { }
        flushSoon(c, 15_000);
    }

    static String read(Context c) {
        java.io.File f = new java.io.File(c.getFilesDir(), "crash.txt");
        if (!f.exists()) return "";
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 64000)]; int got = 0, r;
            while (got < b.length && (r = in.read(b, got, b.length - got)) > 0) got += r;
            return new String(b, 0, got, "UTF-8");
        } catch (Exception e) { return ""; }
    }
}
