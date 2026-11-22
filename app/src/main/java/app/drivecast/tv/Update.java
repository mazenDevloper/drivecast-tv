package app.drivecast.tv;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⬆️ DriveCast TV updates itself from GitHub: the «latest» release (built by .github/workflows/apk.yml, titled «… (build N)»)
 * against this install's version code (= the build number), its APK downloaded and handed to Android's installer — Android
 * asks the person to confirm (its rule for any app that installs). The same key signs every build, so it installs over this one.
 */
public class Update extends BroadcastReceiver {
    static final String API = "https://api.github.com/repos/mazenDevloper/drivecast-tv/releases/latest";

    static long have(Context c) {
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }

    /** {have, latest, url, newer, at} — or {error} */
    static String check(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("have", have(c));
            HttpURLConnection h = (HttpURLConnection) new URL(API).openConnection();
            h.setRequestProperty("User-Agent", "DriveCastTV"); h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setConnectTimeout(10000); h.setReadTimeout(15000);
            String body; try (InputStream in = h.getInputStream()) { body = new String(readAll(in), "UTF-8"); }
            JSONObject r = new JSONObject(body);
            Matcher m = Pattern.compile("build (\\d+)").matcher(r.optString("name"));
            long latest = m.find() ? Long.parseLong(m.group(1)) : 0;
            // the release carries both editions: DriveCastTV.apk (full, first) and DriveCastTVLite.apk
            String url = "", want = BuildConfig.LITE ? "DriveCastTVLite.apk" : "DriveCastTV.apk";
            org.json.JSONArray as = r.optJSONArray("assets");
            for (int k = 0; as != null && k < as.length(); k++) if (want.equals(as.getJSONObject(k).optString("name"))) url = as.getJSONObject(k).optString("browser_download_url");
            if (url.isEmpty() && !BuildConfig.LITE && as != null && as.length() > 0) url = as.getJSONObject(0).optString("browser_download_url");
            o.put("latest", latest).put("url", url).put("newer", latest > have(c) && !url.isEmpty()).put("at", r.optString("published_at"));
        } catch (Exception e) { try { o.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { } }
        return o.toString();
    }

    interface Progress { void at(String state, int pct); }

    /** download the APK and start the install (Android's own confirm screen). Runs on a worker thread. */
    static void install(Context a, String url, Progress p) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                p.at("allow", 0);   // the person allows «install unknown apps» for DriveCast TV once
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            File f = download(a, url, p);
            p.at("install", 100);
            commit(a, f);
        } catch (Exception e) {
            Crash.note(a, "update: " + e);
            p.at("error", 0);
        }
    }

    /** the APK into the app's own folder on the TV's storage (which the TV's debugging shell can read too) */
    static File download(Context a, String url, Progress p) throws Exception {
            File dir = a.getExternalFilesDir(null); if (dir == null) dir = a.getCacheDir();
            File f = new File(dir, "update.apk");
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(true); h.setConnectTimeout(15000); h.setReadTimeout(30000);
            long len = h.getContentLengthLong(), got = 0; int last = -1;
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] b = new byte[64 * 1024]; int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n); got += n;
                    int pct = len > 0 ? (int) (got * 100 / len) : 0;
                    if (pct != last && pct % 5 == 0) { last = pct; p.at("down", pct); }
                }
            }
            return f;
    }

    /** Android's installer: on Android 12+ an app updating itself needs no confirm; older ones show the confirm screen */
    static void commit(Context a, File f) throws Exception {
            PackageInstaller pi = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(a.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("app", 0, f.length())) {
                    byte[] b = new byte[64 * 1024]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); s.fsync(out);
                }
                Intent done = new Intent(a, Update.class).setAction("app.drivecast.tv.UPDATED");
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(a, id, done, fl).getIntentSender());
            }
    }

    // ---- ⬆️ «حدّث التلفزيون» from the phone remote (at home or through the hub): nobody needs to be at the TV
    static volatile String stage = "", note = "";
    static volatile int pct;
    private static volatile boolean running;

    /** {stage, pct, have, running, adb, note} for the remote's line */
    static String status(Context c) {
        try {
            return new JSONObject().put("stage", stage).put("pct", pct).put("have", have(c)).put("running", running).put("adb", SelfAdb.last).put("note", note)
                    .put("lite", BuildConfig.LITE).toString();
        } catch (Exception e) { return "{}"; }
    }

    static void auto(Context c) { auto(c, false); }
    /** after a background install: did it take? pm's own words go to «آخر مشكلة» when it did not */
    static void afterStart(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("upd", Context.MODE_PRIVATE);
        long at = sp.getLong("pending", 0), from = sp.getLong("from", 0); if (at == 0) return;
        sp.edit().remove("pending").apply();
        if (have(c) > from) return;   // ✅ it is the new build
        new Thread(() -> {
            String log = "";
            try { log = SelfAdb.shell(c, "cat /data/local/tmp/dctv.log 2>&1", 3000, 4000).trim(); } catch (Exception e) { log = String.valueOf(e.getMessage()); }
            Crash.note(c, "update (background) did not install: " + (log.isEmpty() ? "no answer from pm" : log));
        }, "upd-after").start();
    }
    /** quiet: the debugging shell only (999) — never Android's installer screen over what is playing */
    static void auto(Context c, boolean quiet) {
        if (running) return;
        running = true; note = ""; stage = "check"; pct = 0;
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(check(c));
                if (j.has("error")) { stage = "error"; note = j.optString("error"); return; }
                long hv = have(c), lt = j.optLong("latest"); note = hv + "|" + lt;
                if (!j.optBoolean("newer")) { stage = "latest"; pct = 100; return; }
                // 1) say what was found — «عندك نسخة N وفيه نسخة M» — for a moment, then the download
                stage = "found"; Thread.sleep(3000);
                File f = download(c, j.optString("url"), (st, n) -> { stage = st; pct = n; });
                stage = "install"; pct = 100;
                // 2) the TV's own debugging shell, in the foreground: pm installs it, Android closes this app at once to replace it
                // (that ends this thread too), and a small helper started first brings the home screen back the moment it is gone.
                // (The old way put the install itself in the background — it said «started» and nothing happened.)
                String p = f.getAbsolutePath().replace("'", "");
                try {
                    String pkg = c.getPackageName(), home = pkg + "/app.drivecast.tv.HomeActivity";
                    String back = "for i in $(seq 1 45); do sleep 2; pidof " + pkg + " > /dev/null 2>&1 || am start -n " + home + " > /dev/null 2>&1; done";
                    c.getSharedPreferences("upd", Context.MODE_PRIVATE).edit().putLong("pending", System.currentTimeMillis()).putLong("from", hv).commit();
                    String out = SelfAdb.shell(c, "cp '" + p + "' /data/local/tmp/dctv.apk && chmod 644 /data/local/tmp/dctv.apk && "
                            + "( (setsid sh -c '" + back + "' < /dev/null > /dev/null 2>&1 &) || true ); "
                            + "pm install -r -d /data/local/tmp/dctv.apk 2>&1; rm -f /data/local/tmp/dctv.apk", 60000, 180000).trim();
                    // still here: pm said no (its own words on the screen), or it took and Android has not closed this app yet
                    if (out.contains("Success")) { stage = "restart"; Thread.sleep(4000); android.os.Process.killProcess(android.os.Process.myPid()); return; }
                    note = out.isEmpty() ? "ما رد pm" : out.length() > 160 ? out.substring(out.length() - 160) : out;
                    Crash.note(c, "update (pm): " + note);
                    if (quiet) { stage = "error"; return; }
                } catch (Exception e) { note = String.valueOf(e.getMessage()); Crash.note(c, "update adb: " + e.getMessage()); }
                if (quiet && Build.VERSION.SDK_INT < 31) { stage = "noadb"; if (note.isEmpty()) note = SelfAdb.last; return; }
                // 2) Android's installer (Android 12+: no confirm; older: its confirm screen on the TV)
                stage = Build.VERSION.SDK_INT >= 31 ? "install" : "confirm";
                commit(c, f);
            } catch (Exception e) {
                stage = "error"; note = String.valueOf(e.getMessage());
                Crash.note(c, "update auto: " + e);
            } finally { running = false; }
        }, "upd-auto").start();
    }

    /** Android's answer: its confirm screen to show, or the result */
    @Override
    public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Crash.note(c, "update not installed: " + st + " " + m);
            c.getSharedPreferences("upd", Context.MODE_PRIVATE).edit().putString("fail", st == PackageInstaller.STATUS_FAILURE_CONFLICT || (m != null && m.contains("signatures")) ? "key" : String.valueOf(m)).apply();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toByteArray();
    }
}
