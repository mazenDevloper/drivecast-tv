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
    }

    /** one line (dated) at the top of crash.txt; the file keeps the last ~12 KB */
    static synchronized void note(Context c, String what) {
        java.io.File f = new java.io.File(c.getFilesDir(), "crash.txt");
        String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "  " + what + "\n";
        String rest = read(c);
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
            o.write((line + rest.substring(0, Math.min(rest.length(), 12000))).getBytes("UTF-8"));
        } catch (Exception ignored) { }
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
