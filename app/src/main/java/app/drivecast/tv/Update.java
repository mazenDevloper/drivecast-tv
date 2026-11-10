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
            String url = r.optJSONArray("assets") != null && r.getJSONArray("assets").length() > 0 ? r.getJSONArray("assets").getJSONObject(0).optString("browser_download_url") : "";
            o.put("latest", latest).put("url", url).put("newer", latest > have(c) && !url.isEmpty()).put("at", r.optString("published_at"));
        } catch (Exception e) { try { o.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { } }
        return o.toString();
    }

    interface Progress { void at(String state, int pct); }

    /** download the APK and start the install (Android's own confirm screen). Runs on a worker thread. */
    static void install(Activity a, String url, Progress p) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                p.at("allow", 0);   // the person allows «install unknown apps» for DriveCast TV once
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            File f = new File(a.getCacheDir(), "update.apk");
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
            p.at("install", 100);
            PackageInstaller pi = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(a.getPackageName());
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("app", 0, f.length())) {
                    byte[] b = new byte[64 * 1024]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); s.fsync(out);
                }
                Intent done = new Intent(a, Update.class).setAction("app.drivecast.tv.UPDATED");
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(a, id, done, fl).getIntentSender());
            }
        } catch (Exception e) {
            Crash.note(a, "update: " + e);
            p.at("error", 0);
        }
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
