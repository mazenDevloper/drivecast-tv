package app.drivecast.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * IPTV on the TV, like a satellite receiver with tvOS's glass: up / down (or CH+/−, or the channel's number on the
 * remote's digits) change channel; each change shows the channel's bar — its number, logo, name, group — and what is
 * really coming in: resolution, codec and profile, bit depth, frame rate, HDR, bitrate, audio, the decoder (hardware or
 * software) and a signal meter from the connection's speed. OK shows the bar again; OK held: another player (VLC, MX…).
 * A stream that drops or stalls is reconnected quietly (3 / 6 / 10 times, from the settings) before another player is offered.
 */
@UnstableApi
public class PlayerActivity extends Activity {
    private ExoPlayer player;
    // 🎞️ episodes: where each one starts (resume), and where it stopped — told to the page (vodPos) for «كمّل المشاهدة»
    private long[] starts; private String reportUrl;
    private int[] vods; private long hideAt; private int tk; private String lastSig;
    /** 🎞️ an episode (the page marks them): a list player — wind, pause, play, the episode before / after, its own progress bar */
    private boolean vod() { return vods != null && i >= 0 && i < vods.length && vods[i] == 1; }
    private int vIdx; private long vAt;
    private static final int[] V_STEPS = {10, 30, 60, 120, 300};
    /** ◀ ▶ in an episode: a tap 10 s, taps close together step further (30 s · 1 · 2 · 5 min); held, one step every ~0.4 s */
    private void vodWind(boolean right, KeyEvent e) {
        long now = System.currentTimeMillis(); int rc = e.getRepeatCount();
        if (rc == 0) { vIdx = now - vAt < 1500 ? Math.min(V_STEPS.length - 1, vIdx + 1) : 0; vAt = now; }
        else if (rc % 4 != 0) return; else vIdx = Math.max(1, vIdx);
        wind((right ? 1 : -1) * V_STEPS[vIdx] * 1000L);
    }
    private void vodReport() {
        try {
            if (player == null || reportUrl == null || player.isCurrentMediaItemLive() || player.getDuration() == C.TIME_UNSET || player.getDuration() < 60_000) return;
            final String js = "window.vodPos && vodPos(" + org.json.JSONObject.quote(reportUrl) + "," + player.getCurrentPosition() + "," + player.getDuration() + ")";
            HomeActivity a = HomeActivity.alive; if (a != null && a.web != null) a.web.post(() -> a.web.evaluateJavascript(js, null));
        } catch (Throwable ignored) { }
    }
    private DefaultBandwidthMeter meter;
    private String[] urls, names, logos, groups, ccs, pls;
    private int i;
    private final Handler h = new Handler(Looper.getMainLooper());      // the bar
    private final Handler net = new Handler(Looper.getMainLooper());    // reconnects, the stall watchdog
    static PlayerActivity live;
    // ---- ⚽ the launcher's live scores (sent by its page every minute): MENU / GUIDE shows them along the bottom for a while
    static volatile String TICKER = "";
    static volatile String MATCHES = "[]";   // ⚽ the matches on now, with their channels (the home page's)
    private TextView ticker;
    private final Runnable tickerHide = () -> { if (ticker != null) ticker.animate().alpha(0f).setDuration(300).withEndAction(() -> ticker.setVisibility(View.GONE)); };
    private void tickerShow() {
        if (ticker == null) return;
        String t = TICKER == null || TICKER.isEmpty() ? "⚽ ما فيه مباريات مباشرة الحين" : TICKER;
        ticker.setText(t); ticker.setSelected(true); ticker.setAlpha(0f); ticker.setVisibility(View.VISIBLE); ticker.animate().alpha(1f).setDuration(250);
        h.removeCallbacks(tickerHide); h.postDelayed(tickerHide, 12000);
    }
    // ---- ⏰ «a match of yours starts»: the launcher's alert over the channel; OK within 25 s plays it
    private static long offerUntil;
    // ---- ⚽ the scores of your 🔔 matches (and your teams'), live, in the bottom-left corner (the bottom-right one is «التالي»)
    static String SCORES = "[]";
    private LinearLayout sbox;
    // ⚽ two feeds fill the corner (the hub's minute push and the page's own 20 s loop); one of them answering with nothing for a
    // moment (a list reloading, a failed fetch) used to blank it for up to a minute — an empty answer now only clears it when
    // the last real one is older than 4 minutes, or when the page says there are no 🔔 left (clearScores)
    static long scoresAt = 0;
    static void scores(String json) {
        String j = json == null ? "[]" : json;
        long now = System.currentTimeMillis();
        if ("[]".equals(j)) { if (now - scoresAt < 4 * 60_000L) return; }
        else scoresAt = now;
        SCORES = j;
        PlayerActivity p = live; if (p != null) p.scoresFill();
    }
    static void clearScores() {
        scoresAt = 0; SCORES = "[]";
        PlayerActivity p = live; if (p != null) p.scoresFill();
    }
    // ✕ hides the corner till it is shown again (☰ → «النتائج في الزاوية»)
    static boolean scoresOff = false;
    private void scoresFill() {
        if (sbox == null) return;
        sbox.removeAllViews();
        boolean busy = scoresOff || (bar != null && bar.getAlpha() > 0.01f) || (listPanel != null && listPanel.getVisibility() == View.VISIBLE)
            || (menuPanel != null && menuPanel.getVisibility() == View.VISIBLE) || (gbox != null && gbox.getVisibility() == View.VISIBLE);
        try {
            org.json.JSONArray a = new org.json.JSONArray(SCORES);
            for (int k = 0; k < a.length() && k < 3; k++) {
                org.json.JSONObject o = a.optJSONObject(k);
                LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                GradientDrawable b = new GradientDrawable(); b.setColor(0xB8101216); b.setCornerRadius(dp(18)); b.setStroke((int) dp(1), 0x33FFFFFF); row.setBackground(b);
                row.setPadding((int) dp(10), (int) dp(5), (int) dp(8), (int) dp(5));
                if (o == null) { TextView t = text(16, Typeface.DEFAULT_BOLD, 0xFFFFFFFF); t.setText(a.optString(k)); row.addView(t); }
                else {
                    // the home's logo, the score, the away's logo (logos, not names: a narrow corner), the minute
                    ImageView hl = new ImageView(this), al = new ImageView(this); int L = (int) dp(30);
                    TextView sc = text(19, Typeface.create("sans-serif-condensed", Typeface.BOLD), 0xFFFFFFFF); sc.setText(o.optString("s")); sc.setPadding((int) dp(8), 0, (int) dp(8), 0);
                    TextView mi = text(13, Typeface.DEFAULT_BOLD, 0xFFFF7B7B); mi.setText(minuteOf(o)); mi.setPadding((int) dp(8), 0, 0, 0);
                    row.addView(hl, new LinearLayout.LayoutParams(L, L)); row.addView(sc); row.addView(al, new LinearLayout.LayoutParams(L, L)); row.addView(mi);
                    logoInto(hl, o.optString("hl"), o.optString("h")); logoInto(al, o.optString("al"), o.optString("a"));
                }
                TextView x = text(14, Typeface.DEFAULT_BOLD, 0x99FFFFFF); x.setText("✕"); x.setPadding((int) dp(10), 0, (int) dp(2), 0);
                x.setOnClickListener(v -> { scoresOff = true; scoresFill(); flash("⚽ النتائج انخفت · ☰ ← «النتائج في الزاوية» ترجعها"); });
                row.addView(x);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = (int) dp(6);
                sbox.addView(row, lp);
            }
        } catch (Throwable ignored) { }
        sbox.setVisibility(busy || sbox.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }
    /** ⏱ the minute, moving between two answers: 365's minute (e) + the minutes since it was read (at), never past the half's
     *  end (45 / 90 / 120); a word instead of a minute («استراحة») as it came */
    static String minuteOf(org.json.JSONObject o) {
        int e = o.optInt("e", 0); long at = o.optLong("at", 0);
        if (e <= 0 || at <= 0) return o.optString("t");
        int cap = e <= 45 ? 45 : e <= 90 ? 90 : 120;
        long since = Math.max(0, (System.currentTimeMillis() - at) / 60_000L);
        return Math.max(e, (int) Math.min(cap, e + since)) + "′";
    }
    // ⚽ with a channel in front the home page sleeps (its timers wait while it is hidden), so the corner froze on the minute and
    // the score it had when the channel opened: the player asks the page for fresh scores itself every 20 s, and moves the minute
    private final Handler sk = new Handler(Looper.getMainLooper());
    private final Runnable scoresKick = new Runnable() { public void run() {
        try { HomeActivity a = HomeActivity.alive; if (a != null && a.web != null) a.web.evaluateJavascript("window.remindLoop && remindLoop()", null); } catch (Throwable ignored) { }
        scoresFill();
        sk.postDelayed(this, 20_000);
    } };
    private void logoInto(ImageView v, String u, String name) {
        v.setContentDescription(name);
        if (u == null || u.isEmpty()) return;
        Bitmap c0 = LOGOS.get(u); if (c0 != null) { v.setImageBitmap(c0); return; }
        new Thread(() -> {
            try {
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                con.setConnectTimeout(6000); con.setReadTimeout(8000);
                Bitmap bm = small(con.getInputStream(), 96);
                if (bm != null) { LOGOS.put(u, bm); runOnUiThread(() -> v.setImageBitmap(bm)); }
            } catch (Exception ignored) { }
        }, "slogo").start();
    }
    // ---- ⚽ a goal in a 🔔 match: a card with «حوّل للقناة» ready for OK; untouched, it goes after 6 s
    private LinearLayout gbox; private TextView gtxt;
    private final Runnable goalHide = () -> { if (gbox != null) gbox.animate().alpha(0f).setDuration(300).withEndAction(() -> { gbox.setVisibility(View.GONE); scoresFill(); }); if (System.currentTimeMillis() < offerUntil) offerUntil = 0; };
    static void goal(String text) {
        PlayerActivity p = live; if (p == null || p.gbox == null) return;
        offerUntil = System.currentTimeMillis() + 6000;
        p.gtxt.setText(text);
        p.gbox.animate().cancel(); p.gbox.setAlpha(0f); p.gbox.setVisibility(View.VISIBLE); p.gbox.animate().alpha(1f).setDuration(250);
        p.h.removeCallbacks(p.goalHide); p.h.postDelayed(p.goalHide, 6000);
        p.scoresFill();
    }
    private void goalBuild(FrameLayout root) {
        gbox = new LinearLayout(this); gbox.setOrientation(LinearLayout.VERTICAL); gbox.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable b = new GradientDrawable(); b.setColor(0xEE101216); b.setCornerRadius(dp(22)); b.setStroke((int) dp(2), 0xFF34C759); gbox.setBackground(b);
        gbox.setPadding((int) dp(24), (int) dp(16), (int) dp(24), (int) dp(16));
        gtxt = text(22, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xFFFFFFFF); gtxt.setGravity(Gravity.CENTER); gtxt.setTextDirection(View.TEXT_DIRECTION_RTL);
        TextView btn = text(19, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xFF101216); btn.setText("▶  حوّل للقناة  ·  OK");
        GradientDrawable bb = new GradientDrawable(); bb.setColor(0xFFFFFFFF); bb.setCornerRadius(dp(16)); btn.setBackground(bb);   // drawn as the focused button: OK does it
        btn.setPadding((int) dp(22), (int) dp(10), (int) dp(22), (int) dp(10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2); blp.topMargin = (int) dp(12);
        gbox.addView(gtxt); gbox.addView(btn, blp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT); lp.setMargins((int) dp(36), 0, 0, (int) dp(30));
        root.addView(gbox, lp); gbox.setVisibility(View.GONE);
        sbox = new LinearLayout(this); sbox.setOrientation(LinearLayout.VERTICAL); sbox.setGravity(Gravity.LEFT);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT); sp.setMargins((int) dp(36), 0, 0, (int) dp(30));
        root.addView(sbox, sp); sbox.setVisibility(View.GONE);
        scoresFill();
        sk.removeCallbacks(scoresKick); sk.postDelayed(scoresKick, 1500);
    }
    static void offer(String text) {
        PlayerActivity p = live; if (p == null) return;
        offerUntil = System.currentTimeMillis() + 25000;
        p.setNote(text + "  ·  OK يشغّلها"); p.showBar(25000);
    }
    // ---- 🔥 what is watched: minutes per channel (and per part of the day), kept on the TV — the launcher's «الأكثر مشاهدة»
    private long since; private String sinceName;
    static int part() { int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); return h >= 5 && h < 12 ? 0 : h < 17 && h >= 12 ? 1 : h >= 17 && h < 22 ? 2 : 3; }
    private void tally() {
        try {
            if (sinceName == null || since == 0) return;
            long ms = Math.min(System.currentTimeMillis() - since, 6 * 3600_000L); String nm = sinceName; since = 0; sinceName = null;
            if (ms < 60_000L || nm.isEmpty()) return;
            android.content.SharedPreferences p = getSharedPreferences("watch", MODE_PRIVATE);
            org.json.JSONObject all = new org.json.JSONObject(p.getString("s", "{}"));
            org.json.JSONObject o = all.optJSONObject(nm); if (o == null) o = new org.json.JSONObject();
            double m = ms / 60000.0; String pk = "p" + part();
            o.put("m", Math.round((o.optDouble("m", 0) + m) * 10) / 10.0).put(pk, Math.round((o.optDouble(pk, 0) + m) * 10) / 10.0).put("t", System.currentTimeMillis());
            all.put(nm, o); p.edit().putString("s", all.toString()).apply();
        } catch (Throwable ignored) { }
    }
    static String stats(android.content.Context c) { return c.getSharedPreferences("watch", MODE_PRIVATE).getString("s", "{}"); }
    /** the channels watched most at this time of day (names), best first */
    private java.util.List<String> usual() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            org.json.JSONObject all = new org.json.JSONObject(stats(this)); final String pk = "p" + part();
            java.util.List<String> ks = new java.util.ArrayList<>(); java.util.Iterator<String> it = all.keys(); while (it.hasNext()) ks.add(it.next());
            final java.util.Map<String, Double> sc = new HashMap<>();
            for (String k : ks) { org.json.JSONObject o = all.getJSONObject(k); if (o.optDouble("m", 0) >= 5) sc.put(k, o.optDouble("m", 0) + 3 * o.optDouble(pk, 0)); }
            out.addAll(sc.keySet()); java.util.Collections.sort(out, (a, b) -> Double.compare(sc.get(b), sc.get(a)));
            if (out.size() > 20) out = new java.util.ArrayList<>(out.subList(0, 20));
        } catch (Throwable ignored) { }
        return out;
    }
    // ---- 📌 pinned to the top of the Channels tab (the launcher's own list, settings «pins»): a long OK in the side list
    private java.util.Set<String> pinned() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try { org.json.JSONArray a = new org.json.JSONArray(Access.set(this).getString("pins", "[]")); for (int k = 0; k < a.length(); k++) out.add(a.getJSONObject(k).optString("name").trim().toLowerCase(Locale.ROOT)); } catch (Throwable ignored) { }
        return out;
    }
    private boolean pinToggle(int ch) {
        try {
            String nm = names != null && ch < names.length && names[ch] != null ? names[ch] : ""; if (nm.isEmpty()) return false;
            String key = nm.trim().toLowerCase(Locale.ROOT);
            org.json.JSONArray a = new org.json.JSONArray(Access.set(this).getString("pins", "[]")), b = new org.json.JSONArray(); boolean had = false;
            for (int k = 0; k < a.length(); k++) { org.json.JSONObject o = a.getJSONObject(k); if (o.optString("name").trim().toLowerCase(Locale.ROOT).equals(key)) had = true; else b.put(o); }
            if (!had) b.put(new org.json.JSONObject().put("name", nm).put("url", key0(ch)).put("logo", logos != null && ch < logos.length && logos[ch] != null ? logos[ch] : "").put("group", gOf(ch)));
            Access.set(this).edit().putString("pins", b.toString()).apply();
            return !had;
        } catch (Throwable t) { return false; }
    }
    private java.util.Set<String> favSet() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try { org.json.JSONArray a = new org.json.JSONArray(Access.set(this).getString("favs", "[]")); for (int k = 0; k < a.length(); k++) out.add(a.getJSONObject(k).optString("name").trim().toLowerCase(Locale.ROOT)); } catch (Throwable ignored) { }
        return out;
    }
    /** ⭐ on / off: shown here at once; the home page saves it with DriveCast (its cloud list), as a star there does */
    /** the pinned matches — a set; the page may have saved «mpins» as text (it threw on every key: the remote looked dead) */
    private java.util.Set<String> mpins() {
        try { return Access.set(this).getStringSet("mpins", new java.util.HashSet<>()); }
        catch (ClassCastException e) { java.util.Set<String> o = new java.util.HashSet<>(); String t = Access.set(this).getString("mpins", ""); for (String x : t.replaceAll("[\\[\\]\"]", "").split(",")) if (!x.trim().isEmpty()) o.add(x.trim()); Access.set(this).edit().remove("mpins").putStringSet("mpins", o).apply(); return o; }
    }
    private boolean mpinHas(String key) { return mpins().contains(key); }
    private boolean favToggle(int ch) {
        try {
            String nm = names != null && ch < names.length && names[ch] != null ? names[ch] : ""; if (nm.isEmpty()) return false;
            String key = nm.trim().toLowerCase(Locale.ROOT);
            org.json.JSONObject c = new org.json.JSONObject().put("name", nm).put("url", key0(ch)).put("logo", logos != null && ch < logos.length && logos[ch] != null ? logos[ch] : "").put("group", gOf(ch));
            org.json.JSONArray a = new org.json.JSONArray(Access.set(this).getString("favs", "[]")), b = new org.json.JSONArray(); boolean had = false;
            for (int k = 0; k < a.length(); k++) { org.json.JSONObject o = a.getJSONObject(k); if (o.optString("name").trim().toLowerCase(Locale.ROOT).equals(key)) had = true; else b.put(o); }
            org.json.JSONArray out = new org.json.JSONArray(); if (!had) out.put(c); for (int k = 0; k < b.length(); k++) out.put(b.get(k));
            HomeActivity hm = HomeActivity.alive;
            if (hm != null) hm.run("window.toggleFav && toggleFav(" + c + ")");
            else Access.set(this).edit().putString("favs", out.toString()).apply();
            favs = new java.util.HashSet<>(); for (int k = 0; k < out.length(); k++) favs.add(out.getJSONObject(k).optString("name").trim().toLowerCase(Locale.ROOT));
            return !had;
        } catch (Throwable t) { return false; }
    }
    /** 📅 the guide: a channel's first address → its coming programmes [[start s, stop s, title], …] */
    static Map<String, org.json.JSONArray> EPG = new HashMap<>();
    /** 📦 the channel list is handed to the player in memory, not in the Intent: with every playlist's channels it reached ~800 KB,
     *  over Binder's 1 MB transaction limit — «Transaction too large», the player never opened and the app fell over */
    private static android.os.Bundle PEND;
    static Intent big(android.content.Context c, Intent extras) {
        PEND = extras.getExtras();
        Intent i = new Intent(c, PlayerActivity.class).putExtra("big", true);
        if (PEND != null) { i.putExtra("i", PEND.getInt("i")); i.putExtra("auto", PEND.getBoolean("auto")); }
        return i;
    }
    private TextView prog;
    private LinearLayout nowRow; private ImageView plogo; private View pdone, pleft; private String plogoUrl = "";
    /** channel k's programme on now: [start, stop, title, picture?, logo?] or null */
    private org.json.JSONArray nowRowOf(int k) {
        try {
            org.json.JSONArray a = EPG.get(key0(k)); if (a == null) return null;
            long now = System.currentTimeMillis() / 1000;
            for (int q = 0; q < a.length(); q++) { org.json.JSONArray r = a.getJSONArray(q); if (r.getLong(0) <= now && r.getLong(1) > now) return r; }
        } catch (Throwable ignored) { }
        return null;
    }
    private void progLogo(String u) {
        if (u == null) u = "";
        if (u.equals(plogoUrl)) return; plogoUrl = u;
        if (u.isEmpty()) { plogo.setImageDrawable(null); plogo.setVisibility(View.GONE); return; }
        Bitmap c = LOGOS.get(u); if (c != null) { plogo.setImageBitmap(c); plogo.setVisibility(View.VISIBLE); return; }
        plogo.setVisibility(View.GONE);
        final String want = u;
        new Thread(() -> {
            try {
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(want).openConnection();
                con.setConnectTimeout(6000); con.setReadTimeout(8000);
                Bitmap bm = small(con.getInputStream(), 256);
                if (bm != null) { LOGOS.put(want, bm); runOnUiThread(() -> { if (want.equals(plogoUrl)) { plogo.setImageBitmap(bm); plogo.setVisibility(View.VISIBLE); } }); }
            } catch (Exception ignored) { }
        }, "plogo").start();
    }
    private String key0(int k) { return first.containsKey(k) ? first.get(k) : urls[k]; }
    private static String clock(long s) { java.util.Calendar c = java.util.Calendar.getInstance(); c.setTimeInMillis(s * 1000); int h = c.get(java.util.Calendar.HOUR); return (h == 0 ? 12 : h) + ":" + String.format(Locale.US, "%02d", c.get(java.util.Calendar.MINUTE)); }
    /** {now, next} of channel k ("" where unknown) */
    private String[] nowNext(int k) {
        String[] out = { "", "" };
        try {
            org.json.JSONArray a = EPG.get(key0(k)); if (a == null) return out;
            long now = System.currentTimeMillis() / 1000;
            for (int q = 0; q < a.length(); q++) {
                org.json.JSONArray r = a.getJSONArray(q); long st = r.getLong(0), en = r.getLong(1);
                if (en <= now) continue;
                if (st <= now) { out[0] = r.getString(2); int left = (int) ((en - now) / 60); if (left > 0) out[0] += "  (باقي " + left + " د)"; }
                else { out[1] = clock(st) + "  " + r.getString(2); break; }
            }
        } catch (Throwable ignored) { }
        return out;
    }
    private void progFill() {
        if (prog == null) return;
        if (vod() && player != null && player.getDuration() != C.TIME_UNSET && player.getDuration() > 0) {
            long d = player.getDuration(), p = Math.min(d, player.getCurrentPosition());
            prog.setText("▶ " + mmss(p) + " / " + mmss(d) + "   ·   باقي " + mmss(d - p)); prog.setVisibility(View.VISIBLE);
            if (nowRow != null) {
                float f = Math.max(0.01f, Math.min(1f, p / (float) d));
                ((LinearLayout.LayoutParams) pdone.getLayoutParams()).weight = f; ((LinearLayout.LayoutParams) pleft.getLayoutParams()).weight = 1f - f;
                pdone.requestLayout(); nowRow.setVisibility(View.VISIBLE); progLogo("");
            }
            return;
        }
        String[] nn = nowNext(i);
        String t = (nn[0].isEmpty() ? "" : "▸ الآن: " + nn[0]) + (nn[1].isEmpty() ? "" : (nn[0].isEmpty() ? "" : "   ·   ") + "بعده " + nn[1]);
        prog.setText(t); prog.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
        if (nowRow == null) return;
        org.json.JSONArray r = nowRowOf(i);
        if (r == null) { nowRow.setVisibility(View.GONE); progLogo(""); return; }
        try {
            long now = System.currentTimeMillis() / 1000, st = r.getLong(0), en = r.getLong(1);
            float f = Math.max(0.02f, Math.min(1f, (now - st) / (float) Math.max(1, en - st)));
            ((LinearLayout.LayoutParams) pdone.getLayoutParams()).weight = f; ((LinearLayout.LayoutParams) pleft.getLayoutParams()).weight = 1f - f;
            pdone.requestLayout(); nowRow.setVisibility(View.VISIBLE);
            progLogo(r.length() > 4 ? r.optString(4, "") : "");
        } catch (Throwable ignored) { nowRow.setVisibility(View.GONE); }
    }
    /** a channel is playing here now (also in the corner) */
    static boolean playing() { PlayerActivity p = live; try { return p != null && p.player != null && p.player.getPlayWhenReady(); } catch (Throwable t) { return false; } }
    /** 🕌 the adhan / iqamah sounds: the channel's own sound off until it ends (the picture goes on, nothing written over it) */
    private boolean hushed;
    static void hush(boolean on, String why) {
        PlayerActivity p = live; if (p == null || p.player == null) return;
        try {
            if (on == p.hushed) return; p.hushed = on;
            p.player.setVolume(on ? 0f : 1f);
        } catch (Throwable ignored) { }
    }
    // ---- ⏳ under the clock, in the last 10 minutes only: the countdown to the adhan, then to the iqamah
    private TextView count;
    private final Handler cd = new Handler(Looper.getMainLooper());
    private static long[] parse(String v) { try { String[] a = v.split("@"); return new long[] { Long.parseLong(a[0]), Long.parseLong(a[1]) }; } catch (Exception e) { return null; } }
    private final Runnable countTick = new Runnable() { @Override public void run() {
        cd.removeCallbacks(this);
        if (count == null) return;
        String out = null; long now = System.currentTimeMillis();
        try {
            android.content.SharedPreferences ps = getSharedPreferences("pset", MODE_PRIVATE), st = Access.set(PlayerActivity.this);
            long[] iq = parse(ps.getString("iqnext", "")), ad = parse(ps.getString("next", ""));
            if (iq != null && !"off".equals(st.getString("iqama", "sound")) && iq[1] > now && iq[1] - now <= 600_000L) out = "إقامة " + Adhan.NAMES[(int) iq[0]] + " بعد " + mmss(iq[1] - now);
            else if (ad != null && !"off".equals(st.getString("adhan", "sound")) && ad[1] > now && ad[1] - now <= 600_000L) out = "أذان " + Adhan.NAMES[(int) ad[0]] + " بعد " + mmss(ad[1] - now);
        } catch (Throwable ignored) { }
        boolean pip = android.os.Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
        if (out == null || pip) count.setVisibility(View.GONE); else { count.setText("🕌 " + out); count.setVisibility(View.VISIBLE); }
        cd.postDelayed(this, out != null ? 1000 : 15000);
    } };

    /** other addresses of a channel (first address → the rest), tried in turn when it does not play */
    static Map<String, String[]> ALTS = new HashMap<>();
    private final Map<Integer, Integer> altAt = new HashMap<>();   // channel → how many of its other sources were tried
    private final Map<Integer, String> first = new HashMap<>();
    private boolean nextSource() {
        String key = first.containsKey(i) ? first.get(i) : urls[i]; String[] a = ALTS.get(key);
        int k = altAt.containsKey(i) ? altAt.get(i) : 0;
        if (a == null || k >= a.length) return false;
        first.put(i, key); altAt.put(i, k + 1); urls[i] = a[k]; errors = 0;
        retryNote = true; setNote("المصدر الأول ما اشتغل — أجرّب مصدر آخر (" + (k + 2) + " من " + (a.length + 1) + ")"); showBar(0);
        net.postDelayed(this::open, 400);
        return true;
    }
    // ---- 📺 picture in picture: HOME while a channel plays → it goes on in a corner over the home screen
    // the colour keys: red the matches on now, green the guide, yellow the channel before, blue search & play
    void colorKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_PROG_RED: if (listOn) listShow(false); if (guideOn) guideShow(false); menuShow(true); menuLv = 1; menuSel = 0; menuFill(); break;
            case KeyEvent.KEYCODE_PROG_GREEN: if (menuOn) menuShow(false); if (listOn) listShow(false); guideCh = cur >= 0 ? cur : i; guideAt = 0; guideShow(true); break;
            case KeyEvent.KEYCODE_PROG_YELLOW: recall(); break;
            case KeyEvent.KEYCODE_PROG_BLUE: searchShow(true); break;
        }
    }
    /** T6's firmware turns green / yellow / blue into HOME before any app sees the key: the player drops to the corner, then the
     *  key reaches the home screen. Called from there: when the player left less than 2 s ago, it comes back to full screen and
     *  does the key's action. true = handled */
    static volatile long leftAt;
    static boolean colorBack(android.content.Context c, int code) {
        PlayerActivity p = live;
        if (p == null || p.isFinishing() || android.os.SystemClock.elapsedRealtime() - leftAt > 2000) return false;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(ACTIVITY_SERVICE);
            am.moveTaskToFront(p.getTaskId(), 0);
        } catch (Throwable t) { Crash.note(c, "colorBack: " + t); return false; }
        p.h.postDelayed(() -> { if (!p.isFinishing()) p.colorKey(code); }, 450);
        return true;
    }
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        leftAt = android.os.SystemClock.elapsedRealtime();
        try {
            if (!"on".equals(Access.set(this).getString("pip", "on")) || player == null || !player.getPlayWhenReady()) return;
            if (!getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;
            int w = vw > 0 ? Math.round(vw) : 16, hh = vh > 0 ? Math.round(vh) : 9;
            float r = (float) w / hh; if (r < 1f || r > 2.3f) { w = 16; hh = 9; }
            enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().setAspectRatio(new android.util.Rational(w, hh)).build());
        } catch (Throwable t) { Crash.note(this, "pip: " + t); }
    }
    @Override
    public void onPictureInPictureModeChanged(boolean pip, android.content.res.Configuration c) {
        super.onPictureInPictureModeChanged(pip, c);
        int v = pip ? View.GONE : View.VISIBLE;
        if (bar != null) bar.setVisibility(v);
        if (hang != null && !"off".equals(Access.set(this).getString("pclock", "on"))) hang.setVisibility(v);
        if (pip && listOn) listShow(false);
        if (!pip) showBar(3000);
    }
    private final Handler zz = new Handler(Looper.getMainLooper());     // ⏲ the sleep timer
    private boolean zzWarned = false;
    /** ⏲ the sleep timer (Settings → «مؤقت النوم»): no key for N minutes → a minute's warning on the screen, then the channel stops */
    private void sleepArm() {
        zz.removeCallbacksAndMessages(null);
        if (zzWarned) { zzWarned = false; setNote(null); }
        int m = 0; try { m = Integer.parseInt(Access.set(this).getString("sleep", "0")); } catch (Exception ignored) { }
        if (m <= 0) return;
        zz.postDelayed(() -> { zzWarned = true; setNote("⏲ مؤقت النوم: تتوقف القناة بعد دقيقة — اضغط أي زر للاستمرار"); showBar(60000); zz.postDelayed(this::finish, 60000); }, (m - 1) * 60000L);
    }
    private int TRIES = 6, errors;
    private boolean offered;
    private String decoder = "", digits = "";

    private LinearLayout bar;
    private TextView num, name, group, note, clock, hint, hang;
    private final Runnable topTick = new Runnable() { @Override public void run() {
        if (hang == null) return;
        java.util.Calendar c = java.util.Calendar.getInstance(); int hh = c.get(java.util.Calendar.HOUR); if (hh == 0) hh = 12;
        hang.setText(String.format(Locale.US, "%d:%02d", hh, c.get(java.util.Calendar.MINUTE)) + (c.get(java.util.Calendar.AM_PM) == 0 ? " ص" : " م"));
        h.removeCallbacks(this); h.postDelayed(this, (60 - c.get(java.util.Calendar.SECOND)) * 1000L + 200);
    } };
    private int cur = -1, prevI = -1, audioPick = -1;   // the channel playing, the one before it (◀ / LAST), the sound track chosen
    private ImageView logo;
    private LinearLayout chips, chips2;
    private View[] signal = new View[5];
    // ⚡ the logos are kept small (256 px at most) and the cache has a ceiling (8 MB), the oldest leaving first
    private static final android.util.LruCache<String, Bitmap> LOGOS = new android.util.LruCache<String, Bitmap>(8 << 20) { @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); } };
    static Bitmap small(java.io.InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close(); byte[] b = bo.toByteArray();
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(b, 0, b.length, o);
        int ss = 1; while (Math.max(o.outWidth, o.outHeight) / (ss * 2) >= max) ss *= 2;
        o = new BitmapFactory.Options(); o.inSampleSize = ss; Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (bm == null) return null; int w = bm.getWidth(), h = bm.getHeight(), m = Math.max(w, h);
        if (m > max) { Bitmap s2 = Bitmap.createScaledBitmap(bm, Math.max(1, w * max / m), Math.max(1, h * max / m), true); if (s2 != bm) bm.recycle(); bm = s2; }
        return bm;
    }
    /** a programme's picture into v (tag = the address wanted, so a late one never lands on another programme) */
    private void artInto(ImageView v, String u) {
        if (u == null) u = ""; v.setTag(u);
        if (u.isEmpty()) { v.setImageDrawable(null); v.setVisibility(View.GONE); return; }
        Bitmap c = LOGOS.get(u); if (c != null) { v.setImageBitmap(c); v.setVisibility(View.VISIBLE); return; }
        v.setVisibility(View.GONE); final String want = u;
        new Thread(() -> {
            try {
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(want).openConnection();
                con.setConnectTimeout(6000); con.setReadTimeout(8000);
                Bitmap bm = small(con.getInputStream(), 256);
                if (bm != null) { LOGOS.put(want, bm); runOnUiThread(() -> { if (want.equals(v.getTag())) { v.setImageBitmap(bm); v.setVisibility(View.VISIBLE); } }); }
            } catch (Exception ignored) { }
        }, "art").start();
    }

    private float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (live != null && live != this) live.finish();   // one player at a time (the one in the corner gives way)
        live = this;
        try { TRIES = Integer.parseInt(Access.set(this).getString("tries", "6")); } catch (Exception ignored) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent in = getIntent();
        if (in.getBooleanExtra("big", false) && PEND != null) { in.putExtras(PEND); PEND = null; }   // the list handed over in memory
        urls = in.getStringArrayExtra("urls"); names = in.getStringArrayExtra("names");
        logos = in.getStringArrayExtra("logos"); groups = in.getStringArrayExtra("groups"); ccs = in.getStringArrayExtra("ccs"); pls = in.getStringArrayExtra("pls");
        i = in.getIntExtra("i", 0); starts = in.getLongArrayExtra("starts"); vods = in.getIntArrayExtra("vods");
        if (urls == null || urls.length == 0) { finish(); return; }

        FrameLayout root = new FrameLayout(this); rootF = root; auto = in.getBooleanExtra("auto", false);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setUseController(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setShutterBackgroundColor(Color.BLACK);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        buildBar(root); buildList(root); buildGuide(root); buildMenu(root); buildSearch(root);
        ticker = text(17, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xFFFFFFFF);
        ticker.setSingleLine(true); ticker.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE); ticker.setMarqueeRepeatLimit(-1); ticker.setHorizontallyScrolling(true);
        GradientDrawable tb = new GradientDrawable(); tb.setColor(0xE6101216); tb.setCornerRadius(dp(18)); ticker.setBackground(tb);
        ticker.setPadding((int) dp(18), (int) dp(8), (int) dp(18), (int) dp(8));
        FrameLayout.LayoutParams tkp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); tkp.setMargins((int) dp(60), (int) dp(22), (int) dp(220), 0);
        root.addView(ticker, tkp); ticker.setVisibility(View.GONE); Scan.paused = true;   // the daily scan waits while a channel plays
        setContentView(root);
        root.post(() -> HomeActivity.quiet(getWindow().getDecorView()));   // 🔇 the side list's moves without clicks

        // 🎞️ smooth and sharp: the first guess of the line is fast (so a channel starts at its top quality), the buffer is deep
        // (60 s ahead, 3 s to start, 6 s after a stall), the quality goes up after 3 s of room and down only after 25 s of a weak line.
        // Setting «pq» = max: the top quality always (never lower).
        boolean pqMax = "max".equals(Access.set(this).getString("pq", "smart"));
        meter = new DefaultBandwidthMeter.Builder(this).setInitialBitrateEstimate(25_000_000L).build();
        androidx.media3.exoplayer.trackselection.DefaultTrackSelector trackSel = new androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this,
                new androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.Factory(3_000, 25_000, 25_000, 0.8f));
        trackSel.setParameters(trackSel.buildUponParameters().setForceHighestSupportedBitrate(pqMax).setExceedVideoConstraintsIfNecessary(true).setExceedRendererCapabilitiesIfNecessary(false));
        androidx.media3.exoplayer.DefaultLoadControl loadCtl = new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 60_000, 3_000, 6_000).setPrioritizeTimeOverSizeThresholds(true).setBackBuffer(10_000, true).build();
        http = new androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent(Iptv.ua()).setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(12000).setReadTimeoutMs(15000);
        player = new ExoPlayer.Builder(this, new androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true)).setBandwidthMeter(meter).setTrackSelector(trackSel).setLoadControl(loadCtl)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(this, http))).build();
        view.setPlayer(player);
        up4k = "on".equals(Access.set(this).getString("up4k", "off"));
        if (up4k) try { player.setVideoEffects(Up4K.effects(this)); } catch (Throwable t) { up4k = false; }   // 🔷 sharpened at its own size; scaled only on a 4K output
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) { net.removeCallbacks(stall); net.postDelayed(() -> errors = 0, 20000); if (errors > 0 || retryNote) { retryNote = false; setNote(null); if (bar.getAlpha() > 0.01f && player.getPlayWhenReady()) showBar(2500); } fill(); h.removeCallbacks(warmNext); h.postDelayed(warmNext, 3000); }
                if (s == Player.STATE_BUFFERING) { net.removeCallbacks(stall); net.postDelayed(stall, 12000); }
                if (s == Player.STATE_ENDED) retry();   // a live stream that "ends" has dropped
            }
            @Override public void onPlayerError(PlaybackException e) {
                // the 4K picture asks the GPU more than some boxes give: an error in the picture's processing → plain picture
                if (up4k && (e.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED || e.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED)) {
                    up4kOff("تحسين الصورة ما يشتغل على هالجهاز — رجعت الصورة العادية", false);
                }
                retry();
            }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) { vw = vs.width * (vs.pixelWidthHeightRatio > 0 ? vs.pixelWidthHeightRatio : 1f); vh = vs.height; fit(); }
        });
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fit());
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime t, String d, long a, long b) { decoder = d; fill(); }
            @Override public void onVideoInputFormatChanged(EventTime t, Format f, DecoderReuseEvaluation r) { fill(); }
            @Override public void onBandwidthEstimate(EventTime t, int ms, long bytes, long bps) { fill(); }
            // 🔷 the box can't keep up with the picture's processing (≥ 30 frames dropped in under 20 s, past the channel's start):
            // the plain picture, smooth again — and the setting off, so it isn't asked of this box again
            @Override public void onDroppedVideoFrames(EventTime t, int n, long ms) {
                if (up4k && n >= 30 && ms > 0 && ms < 20000 && System.currentTimeMillis() - since > 6000) up4kOff("🔷 الجهاز ما لحق على تحسين الصورة — أطفأته لتبقى القنوات سلسة", true);
            }
        });
        play();
        tick.run();
        sleepArm();
    }

    // ---- the picture's width: 0 smart, 1 as it is (black sides stay), 2 fill the screen (crops)
    private PlayerView view;
    private boolean up4k;
    private void up4kOff(String why, boolean reopen) {
        if (!up4k) return; up4k = false;
        try { Access.set(this).edit().putString("up4k", "off").apply(); } catch (Throwable ignored) { }
        HomeActivity hm = HomeActivity.alive; if (hm != null) hm.run("try{SET.up4k='off'}catch(e){}");
        try { player.setVideoEffects(new java.util.ArrayList<>()); } catch (Throwable ignored) { }
        android.widget.Toast.makeText(this, why, android.widget.Toast.LENGTH_LONG).show();
        if (reopen) { player.stop(); open(); }
    }
    private float vw, vh;
    private int fitMode = 0;
    private static final String[] FIT_NAMES = {"عرض ذكي", "الأصلي", "ملء الشاشة"};

    /** smart: a picture narrower than the screen (4:3, or a 16:9 one inside black bars) is enlarged until the side bars shrink,
     *  cropping at most ~16% of its top and bottom (never more, so faces and scores stay); a wide one just fills. */
    private void fit() {
        if (view == null || vw <= 0 || vh <= 0) return;
        View p = (View) view.getParent();
        if (p == null || p.getWidth() == 0 || p.getHeight() == 0) return;
        float screen = p.getWidth() / (float) p.getHeight(), ar = vw / vh, z = 1f;
        if (fitMode == 2) z = Math.max(screen / ar, 1f);
        else if (fitMode == 0) z = ar >= screen ? 1f : Math.min(screen / ar, 1.16f);
        view.setScaleX(z); view.setScaleY(z);
    }

    // ---- the bar: a tvOS glass sheet at the bottom
    private void buildBar(FrameLayout root) {
        Typeface bold = Typeface.create("sans-serif", Typeface.BOLD), med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding((int) dp(22), (int) dp(14), (int) dp(22), (int) dp(14));
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB81E252C, 0xD0121820, 0xE00A0E12});
        glass.setCornerRadius(dp(24));
        glass.setStroke((int) dp(1), 0x40FFFFFF);
        bar.setBackground(glass);
        bar.setElevation(dp(16));
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        num = text(22, bold, Color.WHITE);
        num.setMinWidth((int) dp(46)); num.setGravity(Gravity.CENTER);
        GradientDrawable nb = new GradientDrawable(); nb.setColor(0x26FFFFFF); nb.setCornerRadius(dp(12));
        num.setBackground(nb); num.setPadding((int) dp(10), (int) dp(3), (int) dp(10), (int) dp(3));
        top.addView(num);
        logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams((int) dp(40), (int) dp(40)); ll.setMarginStart((int) dp(12));
        top.addView(logo, ll);
        LinearLayout names_ = new LinearLayout(this); names_.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1); nl.setMarginStart((int) dp(12));
        name = text(20, bold, Color.WHITE); name.setSingleLine(true);
        group = text(12, med, 0xCCE9EEF2); group.setSingleLine(true);
        prog = text(13, med, 0xFF9FE3B4); prog.setSingleLine(true); prog.setEllipsize(android.text.TextUtils.TruncateAt.END); prog.setVisibility(View.GONE);
        names_.addView(name); names_.addView(group); names_.addView(prog);
        // the programme on now: its logo (white on transparent) and a thin line of how far it has gone, as a receiver shows
        nowRow = new LinearLayout(this); nowRow.setGravity(Gravity.CENTER_VERTICAL); nowRow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        plogo = new ImageView(this); plogo.setAdjustViewBounds(true); plogo.setScaleType(ImageView.ScaleType.FIT_CENTER); plogo.setMaxWidth((int) dp(110)); plogo.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, (int) dp(28)); plp.setMarginEnd((int) dp(10));
        nowRow.addView(plogo, plp);
        LinearLayout track = new LinearLayout(this); track.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        GradientDrawable tb = new GradientDrawable(); tb.setColor(0x33FFFFFF); tb.setCornerRadius(dp(2)); track.setBackground(tb);
        pdone = new View(this); GradientDrawable pd = new GradientDrawable(); pd.setColor(0xFF3DDC97); pd.setCornerRadius(dp(2)); pdone.setBackground(pd);
        pleft = new View(this);
        track.addView(pdone, new LinearLayout.LayoutParams(0, -1, 0f)); track.addView(pleft, new LinearLayout.LayoutParams(0, -1, 1f));
        nowRow.addView(track, new LinearLayout.LayoutParams(0, (int) dp(4), 1f));
        LinearLayout.LayoutParams nrp = new LinearLayout.LayoutParams(-1, -2); nrp.topMargin = (int) dp(6);
        names_.addView(nowRow, nrp); nowRow.setVisibility(View.GONE);
        top.addView(names_, nl);
        // the signal meter (from the connection's speed against what the stream needs) and the clock
        LinearLayout sig = new LinearLayout(this); sig.setGravity(Gravity.BOTTOM);
        for (int k = 0; k < 5; k++) {
            View v = new View(this); GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(2)); d.setColor(0x40FFFFFF); v.setBackground(d);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams((int) dp(4), (int) dp(6 + k * 3)); p.setMarginStart((int) dp(2));
            sig.addView(v, p); signal[k] = v;
        }
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, (int) dp(20)); sp.setMarginStart((int) dp(12));
        top.addView(sig, sp);
        clock = text(15, med, 0xE6FFFFFF);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.setMarginStart((int) dp(12));
        top.addView(clock, cp);
        bar.addView(top);

        chips = new LinearLayout(this);
        chips.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(-1, -2); chp.topMargin = (int) dp(10);
        bar.addView(chips, chp);
        // the second row: sound and decoder (one long row would squeeze the last chip into a column of letters)
        chips2 = new LinearLayout(this);
        chips2.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout.LayoutParams chp2 = new LinearLayout.LayoutParams(-1, -2); chp2.topMargin = (int) dp(6);
        bar.addView(chips2, chp2);
        // what the remote does here (changes with the stream: a recording can be wound, a live channel cannot)
        hint = text(11, med, 0x99FFFFFF);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2); hp.topMargin = (int) dp(8);
        bar.addView(hint, hp);
        note = text(13, med, 0xFFFFD27A); note.setVisibility(View.GONE);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2); np.topMargin = (int) dp(8);
        bar.addView(note, np);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins((int) dp(40), 0, (int) dp(40), (int) dp(26));
        root.addView(bar, lp);
        // 🕒 the clock hung at the top centre, half see-through (50%), all the time; setting «pclock» = off hides it
        hang = text(30, Typeface.create("sans-serif-medium", Typeface.NORMAL), 0xFFFFFFFF);
        hang.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        hang.setAlpha(0.38f);   // a bit more see-through (asked 20 Nov)
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tp.setMargins(0, (int) dp(22), 0, 0);
        root.addView(hang, tp);
        if ("off".equals(Access.set(this).getString("pclock", "on"))) hang.setVisibility(View.GONE);
        topTick.run();
        count = text(17, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xFFFFFFFF);
        count.setShadowLayer(dp(5), 0, dp(1), 0xCC000000);
        GradientDrawable cb = new GradientDrawable(); cb.setColor(0xB3101216); cb.setCornerRadius(dp(16)); count.setBackground(cb);
        count.setPadding((int) dp(14), (int) dp(6), (int) dp(14), (int) dp(6));
        FrameLayout.LayoutParams cdp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cdp.setMargins(0, (int) dp(66), 0, 0);
        root.addView(count, cdp); count.setVisibility(View.GONE);
        countTick.run();
        // ⏭ «التالي» in the bottom-right corner while the channel plays: the next programme's logo (or its name), like a
        // broadcaster's own corner; hidden while the bar, the list or the menu is up
        // the programme's logo at the right; to its left «الآن / التالي · 9:30» and the programme's name under it; a little see-through
        nextBox = new LinearLayout(this); nextBox.setOrientation(LinearLayout.HORIZONTAL); nextBox.setGravity(Gravity.CENTER_VERTICAL);
        nextBox.setBackground(null);   // transparent: the text's shadow keeps it readable
        nextBox.setPadding((int) dp(12), (int) dp(8), (int) dp(12), (int) dp(8));
        nextTxt = text(16, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xFFFFFFFF); nextTxt.setShadowLayer(dp(6), 0, dp(1), 0xF0000000);
        nextImg = new ImageView(this); nextImg.setAdjustViewBounds(true); nextImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        nextImg.setMaxWidth((int) dp(120)); nextImg.setMaxHeight((int) dp(56));
        LinearLayout.LayoutParams nip = new LinearLayout.LayoutParams(-2, -2); nip.leftMargin = (int) dp(12);
        nextName = text(15, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xFFFFFFFF); nextName.setShadowLayer(dp(6), 0, dp(1), 0xF0000000);
        nextName.setGravity(Gravity.CENTER); nextName.setMaxWidth((int) dp(260)); nextName.setMaxLines(3); nextName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nextTxt.setGravity(Gravity.RIGHT); nextName.setGravity(Gravity.RIGHT);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams nnp = new LinearLayout.LayoutParams(-2, -2); nnp.topMargin = (int) dp(2);
        col.addView(nextTxt); col.addView(nextName, nnp);
        nextBox.addView(col); nextBox.addView(nextImg, nip); nextBox.setAlpha(0.7f);
        FrameLayout.LayoutParams nxp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
        nxp.setMargins(0, 0, (int) dp(36), (int) dp(30));
        root.addView(nextBox, nxp); nextBox.setVisibility(View.GONE);
        cd.postDelayed(nextTick, 4000);
        goalBuild(root);
    }
    // -1: by itself — «الآن» until 15 min before it ends, then «التالي»; ◀ ▶ choose one for 8 s
    private int nextAt = -1, nextShown = 0;
    private final Runnable nextBack = () -> { nextAt = -1; try { nextFill(); } catch (Throwable ignored) { } };
    private LinearLayout nextBox; private TextView nextTxt, nextName; private ImageView nextImg; private String nextUrl = "";
    // ⏱ when the channel settles (its bar gone): «الآن» for 10 s, then «التالي»
    private void nextNowFirst() { nextAt = -1; nextUrl = "\u0000"; try { nextFill(); } catch (Throwable ignored) { } }
    private final Runnable nextTick = new Runnable() { @Override public void run() {
        cd.removeCallbacks(this); cd.postDelayed(this, 20000);
        try { nextFill(); } catch (Throwable t) { if (nextBox != null) nextBox.setVisibility(View.GONE); }
    } };
    /** the guide's episode words made short: «… · الموسم 3 الحلقة 352» / «… - الحلقة 20» → «… ح352» */
    static String shortEp(String t) {
        String s = (t == null ? "" : t).trim();
        s = s.replaceAll("\\s*[·\\-–|،,]?\\s*(?:الموسم|موسم)\\s*\\d+\\s*[·\\-–|،,]?\\s*(?:الحلقة|الحلقه|حلقة)\\s*(\\d+)", " ح$1");
        s = s.replaceAll("\\s*[·\\-–|،,]?\\s*(?:الحلقة|الحلقه|حلقة)\\s*(\\d+)", " ح$1");
        s = s.replaceAll("\\s*[·\\-–|،,]?\\s*(?:الموسم|موسم)\\s*\\d+\\s*$", "").trim();
        return s.length() > 50 ? s.substring(0, 49) + "…" : s;
    }
    private void nextFill() {
        if (nextBox == null) return;
        boolean busy = (bar != null && bar.getAlpha() > 0.01f) || (listPanel != null && listPanel.getVisibility() == View.VISIBLE)
            || (menuPanel != null && menuPanel.getVisibility() == View.VISIBLE) || "off".equals(Access.set(this).getString("pnext", "on"));
        org.json.JSONArray nx = null;
        java.util.List<org.json.JSONArray> ps = progs(i);   // [0] on now, [1] next…
        int at = nextAt;
        // the guide's first programme still to end may not have begun (a gap in the guide: the one on air is missing) — then it is
        // «التالي» with its start, never «الآن» with a time that is really the end of what plays
        long nowS = System.currentTimeMillis() / 1000;
        boolean onAir = false; try { onAir = !ps.isEmpty() && ps.get(0).getLong(0) <= nowS; } catch (Throwable ignored) { }
        // by itself: the programme on air («الآن · حتى …»), and 15 min before it ends the next one («التالي · …»); ◀ ▶ choose for 8 s
        if (at < 0) { at = 0; try { if (onAir && ps.size() > 1 && ps.get(0).getLong(1) - nowS <= 15 * 60) at = 1; } catch (Throwable ignored) { } }
        if (at >= ps.size()) at = Math.max(0, ps.size() - 1);
        nextShown = at;
        if (!ps.isEmpty()) nx = ps.get(at);
        if (busy || nx == null) { nextBox.setVisibility(View.GONE); return; }
        String logo = nx.length() > 4 ? nx.optString(4, "") : "", pic = nx.length() > 3 ? nx.optString(3, "") : "", u = !logo.isEmpty() ? logo : pic;
        String title = shortEp(nx.optString(2, ""));   // «المدينة البعيدة · الموسم 3 الحلقة 352» → «المدينة البعيدة ح352»
        int step = onAir ? at : at + 1;   // 0 on air, 1 next, 2+ after it
        String tag = step == 0 ? "الآن" : step == 1 ? "التالي" : "بعده";
        // «الآن · حتى 9:00» — the time it ends (not its start); a later one: its start
        nextTxt.setText(step == 0 ? tag + " · حتى " + clock(nx.optLong(1)) : tag + " · " + clock(nx.optLong(0)));
        nextName.setText(title);
        nextName.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);   // the programme's name under its logo
        nextBox.setVisibility(View.VISIBLE);
        if (u.equals(nextUrl)) return; nextUrl = u;
        if (u.isEmpty()) { nextImg.setImageDrawable(null); nextImg.setVisibility(View.GONE); return; }
        Bitmap c = LOGOS.get(u); if (c != null) { nextImg.setImageBitmap(c); nextImg.setVisibility(View.VISIBLE); return; }
        nextImg.setVisibility(View.GONE);
        final String want = u;
        new Thread(() -> {
            try {
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(want).openConnection();
                con.setConnectTimeout(6000); con.setReadTimeout(8000);
                Bitmap bm = small(con.getInputStream(), 256);
                if (bm != null) { LOGOS.put(want, bm); runOnUiThread(() -> { if (want.equals(nextUrl)) { nextImg.setImageBitmap(bm); nextImg.setVisibility(View.VISIBLE); } }); }
            } catch (Exception ignored) { }
        }, "nextlogo").start();
    }

    // ---- OK: the channel list at the side, like TiviMate's. Three levels: the channels ▶ their groups ▶ the playlists (◀ or
    // OK goes back in); next to the list, the chosen channel's guide by the hour, the whole column. ↑↓ choose, OK plays it
    // (OK again on the playing one closes it), BACK closes (from a group or playlist level: back to the channels); it hides by
    // itself after 10 s untouched
    private FrameLayout listPanel; private android.widget.ListView listLv; private android.widget.BaseAdapter listAd;
    private TextView listHead; private int sel; private boolean listOn;
    private int level, lsel;                                   // 0 channels · 1 groups · 2 playlists, and the cursor there
    private final java.util.List<String> plists = new java.util.ArrayList<>(); private int pi = 0;   // 0 = every playlist
    private final java.util.Map<String, Integer> gCount = new java.util.HashMap<>(), pCount = new java.util.HashMap<>();
    private FrameLayout epgCol; private LinearLayout epgRows; private TextView epgHead;
    private String plOf(int k) { return pls != null && k < pls.length && pls[k] != null ? pls[k] : ""; }
    private boolean inPl(int k) { return pi == 0 || plists.size() <= pi || plists.get(pi).equals(plOf(k)); }
    private final Runnable listHide = () -> listShow(false);
    private void buildList(FrameLayout root) {
        listPanel = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[] { 0xF0101216, 0xD8101216, 0x00101216 });
        listPanel.setBackground(g);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) dp(18), (int) dp(26), (int) dp(30), (int) dp(20));
        listHead = text(15, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xFFFFFFFF);
        listHead.setGravity(Gravity.END); listHead.setPadding(0, 0, (int) dp(10), (int) dp(12));
        box.addView(listHead);
        listLv = new android.widget.ListView(this);
        listLv.setDivider(null); listLv.setDividerHeight(0); listLv.setSelector(new android.graphics.drawable.ColorDrawable(0));
        listLv.setVerticalScrollBarEnabled(false); listLv.setFocusable(false); listLv.setItemsCanFocus(false); listLv.setCacheColorHint(0);
        listAd = new android.widget.BaseAdapter() {
            @Override public int getCount() { return level == 0 ? lview.length : level == 1 ? lists.size() : plists.size(); }
            @Override public Object getItem(int q) { return lview[q]; }
            @Override public long getItemId(int q) { return q; }
            @Override public View getView(int q, View cv, android.view.ViewGroup parent) {
                LinearLayout r = cv instanceof LinearLayout ? (LinearLayout) cv : listRow();
                listBind(r, q); return r;
            }
        };
        listLv.setAdapter(listAd);
        box.addView(listLv, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView tip = text(10.5f, Typeface.DEFAULT, 0x99FFFFFF); tip.setGravity(Gravity.END);
        tip.setText("↑↓ اختر · ▶ المجموعات ثم القوائم · ◀ ارجع · ◀ مطوّلاً بالحرف · OK شغّل · OK مطوّلاً ⭐"); tip.setPadding(0, (int) dp(10), (int) dp(10), 0);
        box.addView(tip);
        listPanel.addView(box, new FrameLayout.LayoutParams(-1, -1));
        listLv.setOnItemClickListener((pa, v, pos, id) -> {
            if (level > 0) { lsel = pos; tapKey(KeyEvent.KEYCODE_DPAD_CENTER); return; }
            if (pos >= lview.length) return;
            if (lview[pos] == cur) { listShow(false); return; }
            sel = pos; i = lview[pos]; listShow(false); play(); fill(); showBar(6000);
        });
        root.addView(listPanel, new FrameLayout.LayoutParams((int) dp(400), -1, Gravity.RIGHT));
        listPanel.setVisibility(View.GONE);
        // 📅 the chosen channel's guide, hour by hour, in the free space next to the list
        epgCol = new FrameLayout(this);
        epgCol.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[] { 0xE6101216, 0xC0101216, 0x00101216 }));
        LinearLayout eb = new LinearLayout(this); eb.setOrientation(LinearLayout.VERTICAL); eb.setPadding((int) dp(40), (int) dp(26), (int) dp(14), (int) dp(20));
        epgHead = text(14, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xB3FFFFFF); epgHead.setGravity(Gravity.END); epgHead.setPadding(0, 0, (int) dp(8), (int) dp(12));
        epgHead.setSingleLine(true); epgHead.setEllipsize(android.text.TextUtils.TruncateAt.END);
        eb.addView(epgHead);
        epgRows = new LinearLayout(this); epgRows.setOrientation(LinearLayout.VERTICAL);
        eb.addView(epgRows, new LinearLayout.LayoutParams(-1, 0, 1f));
        epgCol.addView(eb, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams((int) dp(420), -1, Gravity.RIGHT); ep.rightMargin = (int) dp(400);
        root.addView(epgCol, ep); epgCol.setVisibility(View.GONE);
    }
    private final Runnable epgFill = this::epgFillNow;
    private void epgFillNow() {
        if (!listOn || level != 0 || sel >= lview.length) { epgCol.setVisibility(View.GONE); return; }
        int k = lview[sel]; java.util.List<org.json.JSONArray> ps = progs(k);
        epgHead.setText("📅  " + label(k));
        epgRows.removeAllViews();
        long now = System.currentTimeMillis() / 1000;
        if (ps.isEmpty()) {
            TextView t = text(15, Typeface.DEFAULT, 0x99FFFFFF); t.setGravity(Gravity.END); t.setText("لا يوجد دليل برامج لهذه القناة"); t.setPadding(0, (int) dp(8), (int) dp(8), 0); epgRows.addView(t);
        } else for (int q = 0; q < ps.size() && q < 14; q++) try {
            org.json.JSONArray r = ps.get(q); long st = r.getLong(0), en = r.getLong(1); boolean on = st <= now;
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding((int) dp(10), (int) dp(8), (int) dp(10), (int) dp(8));
            TextView tm = text(13, Typeface.create("sans-serif-medium", Typeface.BOLD), on ? 0xFF34C759 : 0x99FFFFFF); tm.setMinWidth((int) dp(64)); tm.setText(clock(st));
            TextView ti = text(on ? 16 : 15, on ? Typeface.create("sans-serif-medium", Typeface.BOLD) : Typeface.DEFAULT, on ? 0xFFFFFFFF : 0xD9FFFFFF);
            ti.setSingleLine(true); ti.setEllipsize(android.text.TextUtils.TruncateAt.END); ti.setPadding((int) dp(10), 0, 0, 0);
            ti.setText(r.getString(2) + (on ? "  ·  باقي " + inTime(en - now) : ""));
            row.addView(tm); row.addView(ti, new LinearLayout.LayoutParams(0, -2, 1f));
            if (on) { GradientDrawable d = new GradientDrawable(); d.setColor(0x2634C759); d.setCornerRadius(dp(12)); row.setBackground(d); }
            epgRows.addView(row);
        } catch (Throwable ignored) { }
        if (epgCol.getVisibility() != View.VISIBLE) { epgCol.setAlpha(0f); epgCol.setVisibility(View.VISIBLE); epgCol.animate().alpha(1f).setDuration(140); }
    }
    // the list's lists: «الكل», then each group (country / list) in the order met — ▶ moves to the next one
    private static final String USUAL = "🔥 المعتادة";
    private static final String FAVL = "⭐ المفضّلة";
    private java.util.List<String> usualNow = new java.util.ArrayList<>(); private java.util.Set<String> pins = new java.util.HashSet<>(), favs = new java.util.HashSet<>();
    private int lrCode; private boolean lrHeld;   // ◀ / ▶ pressed: a short press acts on release, a held one at once
    private boolean okDown;
    private final java.util.List<String> lists = new java.util.ArrayList<>();
    private int li = 0;                                   // the list shown
    private int[] lview = new int[0];                      // its channels (indexes into urls)
    private boolean letterOn; private int letterAt; private java.util.List<String> letters = new java.util.ArrayList<>();
    private String gOf(int k) { return groups != null && k < groups.length && groups[k] != null ? groups[k] : ""; }
    private String nOf(int k) { return names != null && k < names.length && names[k] != null ? names[k] : "قناة " + (k + 1); }
    /** a country's flag from its two letters (OM → 🇴🇲) */
    static String flag(String cc) {
        if (cc == null || cc.length() != 2 || !cc.matches("[A-Za-z]{2}")) return "";
        String u = cc.toUpperCase(Locale.US);
        return new String(Character.toChars(0x1F1E6 + u.charAt(0) - 'A')) + new String(Character.toChars(0x1F1E6 + u.charAt(1) - 'A'));
    }
    private String fOf(int k) { return ccs != null && k < ccs.length ? flag(ccs[k]) : ""; }
    private void listsBuild() {
        plists.clear(); plists.add("كل القوائم"); pCount.clear();
        for (int k = 0; k < urls.length; k++) { String p = plOf(k); if (p.isEmpty()) continue; if (!plists.contains(p)) plists.add(p); pCount.merge(p, 1, Integer::sum); }
        pCount.put("كل القوائم", urls.length);
        if (pi >= plists.size()) pi = 0;
        lists.clear(); lists.add("الكل"); gCount.clear(); int all = 0;
        for (int k = 0; k < urls.length; k++) { if (!inPl(k)) continue; all++; String g = gOf(k); if (g.isEmpty()) continue; if (!lists.contains(g)) lists.add(g); gCount.merge(g, 1, Integer::sum); }
        gCount.put("الكل", all);
        if (lists.size() == 2) lists.remove(1);   // one group only: «الكل» is it
        usualNow = usual(); pins = pinned(); favs = favSet();
        boolean any = false; for (int k = 0; k < urls.length && !any; k++) any = usualNow.contains(nOf(k));
        if (any && urls.length > 8) lists.add(1, USUAL);
        boolean fv = false; for (int k = 0; k < urls.length && !fv; k++) fv = favs.contains(nOf(k).trim().toLowerCase(Locale.ROOT));
        if (fv && urls.length > 1) lists.add(1, FAVL);
    }
    private void listView() {
        java.util.List<Integer> v = new java.util.ArrayList<>();
        String g = lists.get(li);
        if (USUAL.equals(g)) { for (String nm : usualNow) for (int k = 0; k < urls.length; k++) if (nm.equals(nOf(k)) && inPl(k)) { v.add(k); break; } }
        else if (FAVL.equals(g)) { for (int k = 0; k < urls.length; k++) if (inPl(k) && favs.contains(nOf(k).trim().toLowerCase(Locale.ROOT))) v.add(k); }
        else {
            for (int k = 0; k < urls.length; k++) if (inPl(k) && (li == 0 || g.equals(gOf(k)))) v.add(k);
            // 📅 in each group the channels with a guide come first (their own order kept on both sides)
            if (li > 0 && !"🏠 الرئيسية".equals(g)) {   // a group (not «الكل», not the home's own order)
                java.util.List<Integer> on = new java.util.ArrayList<>(), off = new java.util.ArrayList<>();
                long now = System.currentTimeMillis() / 1000;
                for (int k : v) { org.json.JSONArray a = EPG.get(key0(k)); boolean has = false;
                    try { has = a != null && a.length() > 0 && a.getJSONArray(a.length() - 1).getLong(1) > now; } catch (Throwable ignored) { }
                    (has ? on : off).add(k); }
                v.clear(); v.addAll(on); v.addAll(off);
            }
        }
        lview = new int[v.size()]; for (int k = 0; k < lview.length; k++) lview[k] = v.get(k);
    }
    private int posOf(int ch) { for (int k = 0; k < lview.length; k++) if (lview[k] == ch) return k; return -1; }
    private LinearLayout listRow() {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); r.setSoundEffectsEnabled(false);
        r.setPadding((int) dp(12), (int) dp(9), (int) dp(12), (int) dp(9));
        TextView n = text(13, Typeface.create("sans-serif-medium", Typeface.BOLD), 0x99FFFFFF); n.setMinWidth((int) dp(40));
        TextView t = text(15, Typeface.DEFAULT, 0xFFFFFFFF); t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding((int) dp(8), 0, 0, 0);
        r.addView(n); r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, -2));
        return r;
    }
    private GradientDrawable selBg, curBg;
    private void listBind(LinearLayout r, int q) {
        if (level > 0) {
            java.util.List<String> src = level == 1 ? lists : plists; if (q >= src.size()) return;
            String nm = src.get(q); boolean open = level == 1 ? q == li : q == pi;
            Integer c = (level == 1 ? gCount : pCount).get(nm);
            if (USUAL.equals(nm)) c = usualNow.size(); else if (FAVL.equals(nm)) c = favs.size();
            TextView n = (TextView) r.getChildAt(0), t = (TextView) r.getChildAt(1);
            n.setText(c == null ? "" : String.valueOf(c));
            t.setText((open ? "▶  " : "") + (level == 2 ? "📂  " : "") + nm);
            if (selBg == null) { selBg = new GradientDrawable(); selBg.setColor(0xF2FFFFFF); selBg.setCornerRadius(dp(14)); curBg = new GradientDrawable(); curBg.setColor(0x3334C759); curBg.setCornerRadius(dp(14)); }
            r.setBackground(q == lsel ? selBg.getConstantState().newDrawable() : open ? curBg.getConstantState().newDrawable() : null);
            t.setTextColor(q == lsel ? 0xFF111111 : 0xFFFFFFFF); n.setTextColor(q == lsel ? 0xFF555555 : 0x99FFFFFF);
            return;
        }
        if (q >= lview.length) return;
        int k = lview[q]; String key = nOf(k).trim().toLowerCase(Locale.ROOT), fl = fOf(k);
        String on = nowNext(k)[0].replaceFirst("  \\(باقي.*$", "");
        TextView n = (TextView) r.getChildAt(0), t = (TextView) r.getChildAt(1);
        n.setText(String.valueOf(k + 1));
        t.setText((k == cur ? "▶  " : "") + (favs.contains(key) ? "⭐ " : "") + (pins.contains(key) ? "📌 " : "") + (fl.isEmpty() ? "" : fl + "  ") + nOf(k) + (on.isEmpty() ? "" : "  ·  " + on));
        if (selBg == null) { selBg = new GradientDrawable(); selBg.setColor(0xF2FFFFFF); selBg.setCornerRadius(dp(14)); curBg = new GradientDrawable(); curBg.setColor(0x3334C759); curBg.setCornerRadius(dp(14)); }
        r.setBackground(q == sel ? selBg.getConstantState().newDrawable() : k == cur ? curBg.getConstantState().newDrawable() : null);
        t.setTextColor(q == sel ? 0xFF111111 : 0xFFFFFFFF);
        n.setTextColor(q == sel ? 0xFF555555 : 0x99FFFFFF);
    }
    private void listFill() { listAd.notifyDataSetChanged(); }
    private void listMark() {
        listAd.notifyDataSetChanged();
        String pl = pi > 0 && pi < plists.size() ? "📂 " + plists.get(pi) + "  ›  " : "";
        String head = level == 2 ? "📂 القوائم  ·  ◀ رجوع"
                : level == 1 ? pl + "المجموعات  ·  ▶ القوائم"
                : pl + (lists.size() > 1 ? lists.get(li) : "📺 القنوات") + "  ·  " + (lview.length == 0 ? 0 : sel + 1) + " من " + lview.length;
        if (letterOn && !letters.isEmpty()) head = "🔤 الحرف:  " + letters.get(letterAt) + "   ·  ↑↓ غيّر · OK اختر";
        listHead.setText(head);
        h.removeCallbacks(epgFill); if (level == 0) h.postDelayed(epgFill, 90); else epgCol.setVisibility(View.GONE);
        // the chosen row in the middle, at once (no smooth scroll: the remote never waits)
        final int at = level == 0 ? sel : lsel;
        Runnable mid = () -> listLv.setSelectionFromTop(at, Math.max(0, listLv.getHeight() / 2 - (int) dp(20)));
        if (listLv.getHeight() > 0) mid.run(); else listLv.post(mid);
        h.removeCallbacks(listHide); h.postDelayed(listHide, letterOn ? 20000 : 10000);
    }
    private void listShow(boolean on) {
        listOn = on; letterOn = false; okDown = false; lrCode = 0; level = 0;
        if (!on) { h.removeCallbacks(epgFill); epgCol.animate().cancel(); epgCol.setVisibility(View.GONE); }
        if (on) {
            listsBuild(); if (li >= lists.size()) li = 0;
            listView(); if (posOf(Math.max(0, cur)) < 0) { li = 0; listView(); }
            sel = Math.max(0, posOf(Math.max(0, cur)));
            listFill(); HomeActivity.quiet(listPanel); listPanel.setVisibility(View.VISIBLE); listPanel.setAlpha(0f); listPanel.setTranslationX(dp(40)); listPanel.animate().alpha(1f).translationX(0).setDuration(140); listMark();
        }
        else { h.removeCallbacks(listHide); listPanel.animate().alpha(0f).translationX(dp(60)).setDuration(200).withEndAction(() -> { if (!listOn) listPanel.setVisibility(View.GONE); }); }
    }
    // ---- 📅 the guide on the picture: ◀ ▶ walk a channel's programmes (earlier / later), ↑↓ another channel's guide (what
    // plays goes on playing), OK watches the channel shown, BACK closes; it hides by itself after 10 s untouched
    private LinearLayout guideBox; private ImageView guideArt; private TextView guideHead, guideTitle, guideSub;
    private boolean guideOn; private int guideCh, guideAt;   // the channel shown, its programme (0 = the one on now)
    private final Runnable guideHide = () -> guideShow(false);
    private void buildGuide(FrameLayout root) {
        guideBox = new LinearLayout(this); guideBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable(); d.setColor(0xEB101216); d.setCornerRadius(dp(22)); d.setStroke((int) dp(1), 0x26FFFFFF);
        guideBox.setBackground(d); guideBox.setPadding((int) dp(28), (int) dp(16), (int) dp(28), (int) dp(16));
        guideHead = text(14, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xB3FFFFFF);
        guideTitle = text(24, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xFFFFFFFF);
        guideTitle.setMaxLines(2); guideTitle.setEllipsize(android.text.TextUtils.TruncateAt.END); guideTitle.setPadding(0, (int) dp(8), 0, (int) dp(8));
        guideSub = text(13, Typeface.DEFAULT, 0xA6FFFFFF); guideSub.setLineSpacing(dp(4), 1f);
        // the programme's / series' logo above its name, as it changes with ◀ ▶ and ↑↓
        guideArt = new ImageView(this); guideArt.setAdjustViewBounds(true); guideArt.setScaleType(ImageView.ScaleType.FIT_CENTER); guideArt.setVisibility(View.GONE);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, (int) dp(64)); gap.gravity = Gravity.CENTER_HORIZONTAL; gap.topMargin = (int) dp(4); gap.bottomMargin = (int) dp(4);
        guideHead.setGravity(Gravity.CENTER); guideBox.addView(guideHead, new LinearLayout.LayoutParams(-1, -2)); guideBox.addView(guideArt, gap);
        for (TextView t : new TextView[] { guideTitle, guideSub }) { t.setGravity(Gravity.CENTER); guideBox.addView(t, new LinearLayout.LayoutParams(-1, -2)); }
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams((int) dp(720), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        p.bottomMargin = (int) dp(40);
        root.addView(guideBox, p); guideBox.setVisibility(View.GONE);
    }
    /** channel k's programmes from the one on now: [[start s, stop s, title], …] */
    private java.util.List<org.json.JSONArray> progs(int k) {
        java.util.List<org.json.JSONArray> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = EPG.get(key0(k)); if (a == null) return out;
            long now = System.currentTimeMillis() / 1000;
            for (int q = 0; q < a.length(); q++) { org.json.JSONArray r = a.getJSONArray(q); if (r.getLong(1) > now) out.add(r); }
        } catch (Throwable ignored) { }
        return out;
    }
    private static String inTime(long sec) { long m = Math.max(1, sec / 60); return m < 60 ? m + " د" : (m / 60) + " س" + (m % 60 > 0 ? " و" + (m % 60) + " د" : ""); }
    private void guideFill() {
        java.util.List<org.json.JSONArray> ps = progs(guideCh);
        if (guideAt >= ps.size()) guideAt = Math.max(0, ps.size() - 1);
        String fl = fOf(guideCh);
        guideHead.setText("📅  " + (guideCh == cur ? "▶ " : "") + (fl.isEmpty() ? "" : fl + "  ") + label(guideCh) + "  ·  " + (guideCh + 1));
        String keys = "◀ ▶ البرامج  ·  ↑↓ القنوات  ·  OK " + (guideCh == cur ? "إغلاق" : "شاهد");
        if (ps.isEmpty()) { artInto(guideArt, ""); guideTitle.setText("لا يوجد دليل برامج لهذه القناة"); guideSub.setText(keys); }
        else try {
            org.json.JSONArray r = ps.get(guideAt); long st = r.getLong(0), en = r.getLong(1), now = System.currentTimeMillis() / 1000;
            String when = st <= now ? "🔴 الآن · باقي " + inTime(en - now) : guideAt == 1 ? "⏭ التالي · بعد " + inTime(st - now) : "بعد " + inTime(st - now);
            String art = r.optString(4, ""); if (art.isEmpty() || "null".equals(art)) art = r.optString(3, ""); if ("null".equals(art)) art = "";
            artInto(guideArt, art);
            guideTitle.setText(r.getString(2));
            guideSub.setText(when + "   ·   " + clock(st) + " – " + clock(en) + "   ·   " + (guideAt + 1) + " من " + ps.size() + "\n" + keys);
        } catch (Throwable t) { artInto(guideArt, ""); guideTitle.setText("—"); guideSub.setText(keys); }
        h.removeCallbacks(guideHide); h.postDelayed(guideHide, 10000);
    }
    private void guideShow(boolean on) {
        guideOn = on;
        if (on) { h.removeCallbacks(hide); hide.run(); guideBox.setVisibility(View.VISIBLE); guideFill(); }
        else { h.removeCallbacks(guideHide); guideBox.setVisibility(View.GONE); }
    }
    private boolean guideKey(int code, KeyEvent e) {
        int n = urls.length;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: guideAt++; guideFill(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (guideAt > 0) guideAt--; guideFill(); return true;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: guideCh = (guideCh + 1) % n; guideAt = 0; guideFill(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: guideCh = (guideCh - 1 + n) % n; guideAt = 0; guideFill(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() > 0) return true;
                guideShow(false); if (guideCh != cur) { i = guideCh; play(); } return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_GUIDE: guideShow(false); return true;
        }
        guideShow(false); return false;   // any other key: the guide goes and the key does its usual work
    }
    // ---- ☰ the options (OK held on the picture): ⭐ favourite · 🏠 home · ↔ order the home's channels · ⚽ the matches on now
    // and their channels · 📅 guide · 📺 list · 🔊 sound · 📐 size · ⏮ before · 🔷 picture · 🕐 clock · another player.
    // ↑↓ choose, OK does it, ◀ / BACK one level back; it hides by itself after 15 s untouched
    private FrameLayout menuPanel; private android.widget.ScrollView menuScroll; private LinearLayout menuCol; private TextView menuHead;
    private boolean menuOn, pOk; private int menuLv, menuSel, menuM;
    private final java.util.List<String[]> menuRows = new java.util.ArrayList<>();
    private org.json.JSONArray mts = new org.json.JSONArray();
    private final Runnable menuHide = () -> menuShow(false);
    private void buildMenu(FrameLayout root) {
        menuPanel = new FrameLayout(this);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable(); d.setColor(0xF2101216); d.setCornerRadius(dp(24)); d.setStroke((int) dp(1), 0x26FFFFFF);
        box.setBackground(d); box.setPadding((int) dp(18), (int) dp(18), (int) dp(18), (int) dp(14));
        menuHead = text(16, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xFFFFFFFF);
        menuHead.setGravity(Gravity.CENTER); menuHead.setPadding(0, 0, 0, (int) dp(12)); menuHead.setSingleLine(true); menuHead.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(menuHead);
        menuScroll = new android.widget.ScrollView(this); menuScroll.setVerticalScrollBarEnabled(false); menuScroll.setFocusable(false);
        menuCol = new LinearLayout(this); menuCol.setOrientation(LinearLayout.VERTICAL); menuScroll.addView(menuCol);
        box.addView(menuScroll, new LinearLayout.LayoutParams(-1, -2));
        TextView tip = text(11, Typeface.DEFAULT, 0x99FFFFFF); tip.setGravity(Gravity.CENTER); tip.setPadding(0, (int) dp(10), 0, 0);
        tip.setText("↑↓ اختر  ·  OK أو ◀ ادخل  ·  ▶ أو رجوع للخلف"); box.addView(tip);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams((int) dp(520), -2, Gravity.CENTER);
        menuPanel.addView(box, bp);
        root.addView(menuPanel, new FrameLayout.LayoutParams(-1, -1));
        menuPanel.setVisibility(View.GONE);
    }
    private void menuShow(boolean on) {
        menuOn = on;
        if (on) {
            if (listOn) listShow(false); if (guideOn) guideShow(false);
            h.removeCallbacks(hide); hide.run();
            try { mts = new org.json.JSONArray(MATCHES == null ? "[]" : MATCHES); } catch (Throwable t) { mts = new org.json.JSONArray(); }
            menuLv = 0; menuSel = 0; menuFill(); HomeActivity.quiet(menuPanel); menuPanel.setVisibility(View.VISIBLE);
        } else { h.removeCallbacks(menuHide); menuPanel.setVisibility(View.GONE); }
    }
    private void menuRow(String k, String label) { menuRows.add(new String[] { k, label }); }
    private void menuFill() {
        menuRows.clear();
        int ch = cur >= 0 ? cur : i; String key = nOf(ch).trim().toLowerCase(Locale.ROOT);
        if (menuLv == 0) {
            menuHead.setText("☰  " + label(ch));
            favs = favSet(); pins = pinned();
            menuRow("fav", favs.contains(key) ? "☆  إزالة من المفضّلة" : "⭐  أضف إلى المفضّلة");
            menuRow("home", onHome(key) ? "🗑  إزالة من الرئيسية" : "🏠  أضف إلى الرئيسية");
            menuRow("rec", tsRecOn(key) ? "⏺  إيقاف تسجيل هذي القناة (آخر 20 دقيقة)" : "⏺  سجّل هذي القناة (آخر 20 دقيقة · ◀ ▶ ترجع وتقدّم)");
            menuRow("mpin", mpinHas(key) ? "⚽  إلغاء تثبيتها للمباريات" : "⚽  ثبّت للمباريات");
            if (HomeActivity.alive != null) menuRow("reorder", "↔  رتّب قنوات الرئيسية");
            if (mts.length() > 0) menuRow("matches", "⚽  المباريات الآن  (" + mts.length() + ")");
            menuRow("guide", "📅  دليل البرامج");
            if (urls.length > 1) menuRow("list", "📺  قائمة القنوات");
            if (audioGroups().size() > 1) menuRow("audio", "🔊  صوت آخر  (" + audioGroups().size() + ")");
            menuRow("fit", "📐  حجم الصورة: " + FIT_NAMES[fitMode]);
            if (prevI >= 0 && prevI < urls.length && prevI != i) menuRow("recall", "⏮  القناة السابقة: " + label(prevI));
            menuRow("up4k", "🔷  تحسين الصورة: " + (up4k ? "يعمل" : "مطفأ"));
            menuRow("clock", "🕐  الساعة: " + (hang != null && hang.getVisibility() == View.VISIBLE ? "ظاهرة" : "مخفية"));
            String myNum = chNumOf(ch);
            menuRow("num", myNum.isEmpty() ? "🔢  اختصار لهذه القناة  ·  رقم من ٣ خانات" : "🔢  اختصار هذه القناة: " + myNum + "  ·  غيّره");
            if (!myNum.isEmpty()) menuRow("numdel", "🗑  احذف الاختصار " + myNum);
            if (!"[]".equals(SCORES)) menuRow("sc", scoresOff ? "⚽  النتائج في الزاوية: أظهرها" : "⚽  النتائج في الزاوية: أخفها");
            menuRow("ext", "📺  افتح في مشغّل آخر");
        } else if (menuLv == 1) {
            menuHead.setText("⚽  المباريات الآن");
            for (int k = 0; k < mts.length(); k++) { org.json.JSONObject m = mts.optJSONObject(k); if (m == null) continue;
                org.json.JSONArray c = m.optJSONArray("ch"); int n = c == null ? 0 : c.length();
                menuRow("m" + k, m.optString("t") + (m.optString("lg").isEmpty() ? "" : "\n" + m.optString("lg")) + "  ·  " + (n == 0 ? "ما فيه ناقل في قنواتك" : n + (n == 1 ? " قناة" : " قنوات") + "  ◀")); }
        } else {
            org.json.JSONObject m = mts.optJSONObject(menuM); org.json.JSONArray c = m == null ? null : m.optJSONArray("ch");
            menuHead.setText(m == null ? "⚽" : m.optString("t"));
            if (c != null) for (int k = 0; k < c.length(); k++) { org.json.JSONObject x = c.optJSONObject(k); if (x == null) continue;
                String nm = x.optString("name"), w = x.optString("n");
                menuRow("c" + k, "▶  " + nm + (w.isEmpty() || w.equalsIgnoreCase(nm) ? "" : "   ·  " + w)); }
            if (menuRows.isEmpty()) menuRow("back", "ما فيه قناة ناقلة في قنواتك  ·  ◀ رجوع");
        }
        if (menuSel >= menuRows.size()) menuSel = Math.max(0, menuRows.size() - 1);
        menuCol.removeAllViews();
        for (String[] r : menuRows) {
            TextView t = text(16, Typeface.DEFAULT, 0xFFFFFFFF); t.setText(r[1]); t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); t.setTextDirection(View.TEXT_DIRECTION_RTL);   // Arabic: from the right
            t.setPadding((int) dp(16), (int) dp(11), (int) dp(16), (int) dp(11)); t.setMaxLines(2); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setSoundEffectsEnabled(false);
            menuCol.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        // a long list: no taller than most of the screen
        menuScroll.getLayoutParams().height = menuRows.size() > 9 ? (int) (getResources().getDisplayMetrics().heightPixels * 0.62f) : -2;
        menuScroll.requestLayout();
        menuMark();
    }
    private void menuMark() {
        for (int q = 0; q < menuCol.getChildCount(); q++) {
            TextView t = (TextView) menuCol.getChildAt(q);
            if (q == menuSel) { GradientDrawable d = new GradientDrawable(); d.setColor(0xF2FFFFFF); d.setCornerRadius(dp(14)); t.setBackground(d); t.setTextColor(0xFF111111); }
            else { t.setBackground(null); t.setTextColor(0xFFFFFFFF); }
        }
        View r = menuSel < menuCol.getChildCount() ? menuCol.getChildAt(menuSel) : null;
        if (r != null) menuScroll.post(() -> menuScroll.scrollTo(0, Math.max(0, r.getTop() - menuScroll.getHeight() / 2 + r.getHeight() / 2)));
        h.removeCallbacks(menuHide); h.postDelayed(menuHide, 15000);
    }
    private boolean menuKey(int code, KeyEvent e) {
        int n = menuRows.size();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: if (n > 0) { menuSel = (menuSel - 1 + n) % n; menuMark(); } return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (n > 0) { menuSel = (menuSel + 1) % n; menuMark(); } return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() > 0) return true;   // the long press's own OK, still held
                if (menuSel < n) menuAct(menuRows.get(menuSel)[0]); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:   // ◀ in (Arabic reads that way): the matches, a match's channels
                if (e.getRepeatCount() > 0) return true;
                if (menuSel < n && ((menuLv == 0 && "matches".equals(menuRows.get(menuSel)[0])) || menuLv == 1)) menuAct(menuRows.get(menuSel)[0]);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:   // ▶ back out
                if (e.getRepeatCount() > 0) return true;
                if (menuLv == 2) { menuLv = 1; menuSel = menuM; menuFill(); } else if (menuLv == 1) { menuLv = 0; menuSel = 0; menuFill(); }
                return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                if (menuLv == 2) { menuLv = 1; menuSel = menuM; menuFill(); } else if (menuLv == 1) { menuLv = 0; menuSel = 0; menuFill(); } else menuShow(false);
                return true;
            case KeyEvent.KEYCODE_MENU: menuShow(false); return true;
        }
        return true;
    }
    /** is this channel on the home row now (the home page keeps the list in «homekeys»)? */
    private boolean onHome(String key) {
        try { org.json.JSONArray a = new org.json.JSONArray(Access.set(this).getString("homekeys", "[]")); for (int k = 0; k < a.length(); k++) if (key.equals(a.optString(k))) return true; } catch (Throwable ignored) { }
        return pinned().contains(key);
    }
    private String chJson(int ch) {
        try { return new org.json.JSONObject().put("name", nOf(ch)).put("url", key0(ch)).put("logo", logos != null && ch < logos.length && logos[ch] != null ? logos[ch] : "").put("group", gOf(ch)).toString(); }
        catch (Throwable t) { return "{}"; }
    }
    private void menuAct(String k) {
        int ch = cur >= 0 ? cur : i; HomeActivity hm = HomeActivity.alive;
        if (k.equals("rec")) {
            String key = nOf(ch).trim().toLowerCase(Locale.ROOT); boolean on = !tsRecOn(key); tsRecSet(key, on); menuShow(false);
            if (on) { tsLater(); flash("⏺ بسجّل هذي القناة طول ما تشغّلها · ◀ ▶ ترجع وتقدّم"); } else { tsStop(); flash("⏹ وقف التسجيل وانحذف"); }
            return;
        }
        if (k.startsWith("m") && menuLv == 1) { menuM = Integer.parseInt(k.substring(1)); menuLv = 2; menuSel = 0; menuFill(); return; }
        if (k.startsWith("c") && menuLv == 2) {
            org.json.JSONObject m = mts.optJSONObject(menuM); if (m == null) return;
            // the match's channels the player can open itself (their links came with the list): opened here, the others after them for ▲▼
            try {
                org.json.JSONArray mch = m.optJSONArray("ch"), list = new org.json.JSONArray(); int kk = Integer.parseInt(k.substring(1)), at = -1;
                for (int q = 0; mch != null && q < mch.length(); q++) { org.json.JSONObject x = mch.optJSONObject(q), p = x == null ? null : x.optJSONObject("p"); if (p == null) continue; if (q == kk) at = list.length(); list.put(p); }
                if (at >= 0) { menuShow(false); startActivity(HomeActivity.playerIntent(this, list, at)); return; }
            } catch (Throwable t) { Crash.note(this, "match channel: " + t); }
            if (hm == null) { flash("افتح هالقناة من تاب المباريات"); return; }   // only the home page can open it (its link is found there)
            menuShow(false); flash("⚽ أفتح القناة…");
            hm.run("window.playMatchCh && playMatchCh(" + org.json.JSONObject.quote(m.optString("k")) + "," + k.substring(1) + ")");
            h.postDelayed(this::finish, 1500);   // the new player takes this one's place; if a channel needs the home page's slower work, it shows once this one is gone
            return;
        }
        switch (k) {
            case "back": menuLv = 1; menuSel = menuM; menuFill(); return;
            case "matches": menuLv = 1; menuSel = 0; menuFill(); return;
            case "mpin": {   // ⚽ this channel joins every match's carriers (right after the match's own), and plays when none is found
                String mk = nOf(ch).trim().toLowerCase(Locale.ROOT); boolean on = !mpinHas(mk);
                java.util.Set<String> s2 = new java.util.HashSet<>(mpins());
                if (on) s2.add(mk); else s2.remove(mk); Access.set(this).edit().putStringSet("mpins", s2).apply();
                HomeActivity hm2 = HomeActivity.alive; if (hm2 != null) hm2.run("window.mpinFromPlayer && mpinFromPlayer(" + chJson(ch) + "," + on + ")");
                menuShow(false); flash(on ? "⚽ ثُبّتت للمباريات — تطلع مع ناقلي كل مباراة" : "أُلغي تثبيتها للمباريات"); return;
            }
            case "fav": { boolean on = favToggle(ch); menuShow(false); flash(on ? "⭐ أُضيفت إلى المفضّلة" : "أُزيلت من المفضّلة"); return; }
            case "home": {
                boolean was = onHome(nOf(ch).trim().toLowerCase(Locale.ROOT));
                if (hm != null) hm.run("window.homeFromPlayer && homeFromPlayer(" + chJson(ch) + ")"); else pinToggle(ch);
                menuShow(false); flash(was ? "أُزيلت من الرئيسية" : "🏠 أُضيفت إلى الرئيسية"); return;
            }
            case "reorder": if (hm != null) { hm.run("window.reorderFromPlayer && reorderFromPlayer(" + chJson(ch) + ")"); finish(); } return;
            case "guide": menuShow(false); guideCh = ch; guideAt = 0; guideShow(true); return;
            case "list": menuShow(false); listShow(true); return;
            case "audio": menuShow(false); nextAudio(); return;
            case "fit": fitMode = (fitMode + 1) % 3; fit(); menuFill(); return;
            case "recall": menuShow(false); recall(); return;
            case "up4k":
                if (up4k) up4kOff("🔷 تحسين الصورة: مطفأ", true);
                else {
                    try { player.setVideoEffects(Up4K.effects(this)); up4k = true; Access.set(this).edit().putString("up4k", "on").apply();
                        if (hm != null) hm.run("try{SET.up4k='on'}catch(e){}"); player.stop(); open(); }
                    catch (Throwable t) { up4k = false; }
                }
                menuFill(); return;
            case "clock": {
                if (hang == null) return; boolean show = hang.getVisibility() != View.VISIBLE;
                hang.setVisibility(show ? View.VISIBLE : View.GONE); Access.set(this).edit().putString("pclock", show ? "on" : "off").apply();
                if (hm != null) hm.run("try{SET.pclock='" + (show ? "on" : "off") + "'}catch(e){}");
                menuFill(); return;
            }
            case "ext": menuShow(false); openOtherPlayer(); return;
            case "num": menuShow(false); numOpen(ch); return;
            case "sc": scoresOff = !scoresOff; menuShow(false); scoresFill(); return;
            case "numdel": { String c0 = chNumOf(ch); if (!c0.isEmpty()) { chNumDel(this, c0); flash("🗑 انحذف الاختصار " + c0); } menuShow(false); return; }
        }
    }
    /** a name's first letter, for ◀ (the «ال» of Arabic names and «Al »/«The » skipped) */
    private static String first(String n) {
        String t = n.trim().replaceFirst("^(ال|Al[ -]|El[ -]|The )", "").trim();
        return t.isEmpty() ? "#" : t.substring(0, 1).toUpperCase(Locale.ROOT);
    }
    private void letterJump() {
        String L = letters.get(letterAt);
        for (int q = 0; q < lview.length; q++) if (first(nOf(lview[q])).equals(L)) { sel = q; break; }
        listMark();
    }
    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        try {
            if (listOn && level == 0 && okDown && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                okDown = false;
                if (sel < lview.length) { if (lview[sel] == cur) listShow(false); else { i = lview[sel]; listShow(false); play(); fill(); showBar(6000); } }   // chosen: the list goes, the bar shows the channel
                return true;
            }
            if (pOk && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) { pOk = false; if (!listOn && !menuOn && !guideOn) keyRest(code, e, true); return true; }
            if (lrCode != 0 && code == lrCode) {
                boolean held = lrHeld, right = code == KeyEvent.KEYCODE_DPAD_RIGHT; lrCode = 0; lrHeld = false;
                if (held) return true;
                if (listOn) { if (level == 0 && !right && lists.size() > 1) { li = (li - 1 + lists.size()) % lists.size(); listView(); sel = Math.max(0, posOf(cur)); listFill(); listMark(); } }
                else if (!guideOn && !menuOn && tsRecOn(nOf(i).trim().toLowerCase(Locale.ROOT)) && tsWind(right)) { }   // a channel chosen for recording: ◀ ▶ are its winding first, even while the next-programmes box shows
                else if (!guideOn && nextBox != null && nextBox.getVisibility() == View.VISIBLE) {
                    nextAt = Math.max(0, nextShown + (right ? 1 : -1)); nextUrl = "\u0000"; nextFill(); cd.removeCallbacks(nextBack); cd.postDelayed(nextBack, 8000);
                }
                else if (!guideOn && !menuOn && tsWind(right)) { }   // ◀ ▶ wind the last 20 minutes (when they are being kept)
                return true;
            }
        } catch (Throwable t) { Crash.note(this, "player list: " + t); }
        return super.onKeyUp(code, e);
    }
    private boolean listKey(int code, KeyEvent e) {
        int n = lview.length;
        if (letterOn) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: letterAt = (letterAt - 1 + letters.size()) % letters.size(); letterJump(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (lrHeld && code == KeyEvent.KEYCODE_DPAD_LEFT && e.getRepeatCount() > 0) return true;   // still the hold that opened it
                    letterAt = (letterAt + 1) % letters.size(); letterJump(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                    letterOn = false; listMark(); return true;
            }
            return true;
        }
        if (level > 0) {
            int m = level == 1 ? lists.size() : plists.size();
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: if (m > 0) lsel = (lsel - 1 + m) % m; listMark(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: if (m > 0) lsel = (lsel + 1) % m; listMark(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:   // ▶ from the groups to the playlists
                    if (e.getRepeatCount() > 0 || level == 2) return true;
                    level = 2; lsel = pi; listFill(); listMark(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() > 0) return true;
                    if (level == 2) { pi = lsel; li = 0; listsBuild(); level = 1; lsel = 0; listFill(); listMark(); return true; }   // a playlist: its groups
                    li = Math.min(lsel, lists.size() - 1); level = 0; listView();                                        // a group: its channels
                    sel = Math.max(0, posOf(cur)); listFill(); listMark(); return true;
                case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                    level = level == 2 ? 1 : 0; lsel = level == 1 ? li : 0; listFill(); listMark(); return true;
            }
            return true;
        }
        if (n == 0 && code != KeyEvent.KEYCODE_DPAD_RIGHT && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_BACK) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: sel = (sel - 1 + n) % n; listMark(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: sel = (sel + 1) % n; listMark(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:   // ▶ up to the groups (◀, on release, the group before)
                if (e.getRepeatCount() > 0) return true;
                level = 1; lsel = li; listFill(); listMark(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: {  // ◀ held: by letter
                if (e.getRepeatCount() == 0) { lrCode = code; lrHeld = false; return true; }
                if (!(e.getRepeatCount() == 4 || e.isLongPress()) || lrCode != code || lrHeld || n == 0) return true;   // held: by the repeats, or Android's own long press
                lrHeld = true;
                java.util.TreeSet<String> ls = new java.util.TreeSet<>();
                for (int k : lview) ls.add(first(nOf(k)));
                letters = new java.util.ArrayList<>(ls); if (letters.isEmpty()) return true;
                String now = sel < n ? first(nOf(lview[sel])) : letters.get(0);
                letterAt = Math.max(0, letters.indexOf(now)); letterOn = true; listMark(); return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                // a short OK plays (on release); held: ⭐ in the favourites (or out of them) — one list with the home page and DriveCast
                if (e.getRepeatCount() == 0) { okDown = true; return true; }
                if (okDown && (e.getRepeatCount() >= 6 || e.isLongPress())) {
                    okDown = false; boolean on = favToggle(lview[sel]); int keep = sel; listFill(); sel = keep; listMark();
                    listHead.setText(on ? "⭐ أُضيفت إلى المفضّلة" : "أُزيلت من المفضّلة");
                }
                return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE: listShow(false); return true;
        }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) { listShow(false); return false; }
        return false;
    }

    private TextView text(float sp, Typeface tf, int color) {
        TextView t = new TextView(this); t.setTextSize(sp); t.setTypeface(tf); t.setTextColor(color); t.setIncludeFontPadding(false); return t;
    }

    private LinearLayout row;
    private void chip(String s, int bg, int fg) { chip(s, bg, fg, 12); }
    private void chip(String s, int bg, int fg, float sp) {
        TextView t = text(sp, Typeface.create("sans-serif-medium", Typeface.BOLD), fg);
        t.setText(s); t.setPadding((int) dp(sp > 12 ? 12 : 10), (int) dp(sp > 12 ? 5 : 4), (int) dp(sp > 12 ? 12 : 10), (int) dp(sp > 12 ? 5 : 4));
        t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        GradientDrawable d = new GradientDrawable(); d.setColor(bg); d.setCornerRadius(dp(10)); t.setBackground(d);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMarginEnd((int) dp(6));
        row.addView(t, p);
    }

    /** what is really coming in, as chips */
    private androidx.media3.datasource.DefaultHttpDataSource.Factory http;
    // ---- ▶ YouTube inside the player: YouTube's own embedded player (its IFrame API) over the video, the remote's keys stay the
    // player's (▲▼ channels, OK the list); an embed refused (its owner's choice, errors 101 / 150) or silent for 20 s → the next
    // carrier of a match, else the YouTube app
    private FrameLayout rootF; private android.webkit.WebView yt; private boolean auto; private String ytNow;
    private static String ytIdOf(String u) {
        if (u == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|live/|embed/|shorts/)|youtu\\.be/)([A-Za-z0-9_-]{11})").matcher(u);
        return m.find() ? m.group(1) : null;
    }
    private final Runnable ytSilent = () -> ytFail("silent");
    @android.annotation.SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void ytPlay(String id) {
        try { if (player != null) player.stop(); } catch (Throwable ignored) { }
        if (yt == null) {
            yt = new android.webkit.WebView(this);
            yt.setBackgroundColor(0xFF000000); yt.setFocusable(false); yt.setFocusableInTouchMode(false); yt.setSoundEffectsEnabled(false);
            android.webkit.WebSettings ws = yt.getSettings();
            ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true); ws.setMediaPlaybackRequiresUserGesture(false);
            yt.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface public void ok() { runOnUiThread(() -> { h.removeCallbacks(ytSilent); errors = 0; setNote(null); }); }
                @android.webkit.JavascriptInterface public void err(String c) { runOnUiThread(() -> ytFail(c)); }
            }, "DC");
            rootF.addView(yt, 1, new FrameLayout.LayoutParams(-1, -1));
        }
        ytNow = id; yt.setVisibility(View.VISIBLE);
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>html,body,#p{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head><body><div id=p></div>"
            + "<script src='https://www.youtube.com/iframe_api'></script><script>function onYouTubeIframeAPIReady(){new YT.Player('p',{width:'100%',height:'100%',videoId:'" + id + "',"
            + "playerVars:{autoplay:1,controls:0,rel:0,playsinline:1,modestbranding:1,iv_load_policy:3,fs:0,disablekb:1,cc_load_policy:0},"
            + "events:{onReady:function(e){e.target.unMute();e.target.setVolume(100);e.target.playVideo()},onError:function(e){DC.err(String(e.data))},onStateChange:function(e){if(e.data==1)DC.ok()}}})}</script></body></html>";
        yt.loadDataWithBaseURL("https://mazendevloper.github.io/drivecast-tv/", html, "text/html", "utf-8", null);
        h.removeCallbacks(ytSilent); h.postDelayed(ytSilent, 20000);
    }
    private void ytHide() {
        if (yt == null || yt.getVisibility() != View.VISIBLE) return;
        h.removeCallbacks(ytSilent); ytNow = null;
        try { yt.loadUrl("about:blank"); } catch (Throwable ignored) { }
        yt.setVisibility(View.GONE);
    }
    private void ytFail(String why) {
        String id = ytNow; ytHide();
        if (auto && urls.length > 1) { setNote("بث يوتيوب ما اشتغل — الناقل التالي"); showBar(3000); i = (i + 1) % urls.length; play(); return; }
        if (id != null) { setNote("يوتيوب ما سمح بتشغيله هنا — أفتحه في تطبيق يوتيوب"); try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=" + id))); } catch (Throwable ignored) { } }
    }
    private void open() {
        lastSig = null; try { if (player == null) return;
        String yid = ytIdOf(urls[i]); if (yid != null) { ytPlay(yid); return; } ytHide();
        // 📡 as an IPTV player: the channel's own headers from its list (user agent, Referer, Origin), else its site's, else
        // the IPTV player's (setting «ua»)
        java.util.Map<String, String> hd = new java.util.HashMap<>();
        String ref = Iptv.refererFor(urls[i]), org = Iptv.originFor(urls[i]);
        if (ref != null) hd.put("Referer", ref); if (org != null) hd.put("Origin", org);
        http.setUserAgent(Iptv.uaFor(urls[i])); http.setDefaultRequestProperties(hd);
        // a live channel plays ~20 s behind its edge (where the CDN allows): room in the buffer for the line's hiccups
        vodReport(); reportUrl = urls[i];
        long st0 = starts != null && i < starts.length ? starts[i] : 0; if (starts != null && i < starts.length) starts[i] = 0;
        player.setMediaItem(new MediaItem.Builder().setUri(urls[i]).setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(20_000).setMinPlaybackSpeed(0.98f).setMaxPlaybackSpeed(1.02f).build()).build(), st0 > 0 ? st0 : C.TIME_UNSET); player.prepare(); player.play(); tsLocal = false; tsLater(); } catch (Throwable t) { Crash.note(this, "player open: " + t); setNote("تعذّر تشغيل القناة · ↑↓ قناة ثانية"); } }
    private boolean barDirty;
    private void fill() { try { if (bar != null && bar.getAlpha() < 0.01f) { barDirty = true; return; } barDirty = false; progFill();
        // the chips are rebuilt only when something they show changed (a bar refreshed every few seconds used to rebuild ~15 views each time)
        String sig = player == null ? "" : i + "|" + player.getPlaybackState() + "|" + player.getPlayWhenReady() + "|" + System.identityHashCode(player.getVideoFormat()) + "|" + System.identityHashCode(player.getAudioFormat()) + "|" + decoder + "|" + behindLive() / 5000 + "|" + canWind() + "|" + prevI + "|" + meter.getBitrateEstimate() / 500000 + "|" + (vod() ? player.getCurrentPosition() / 5000 : 0);
        if (sig.equals(lastSig)) return; lastSig = sig; fill0(); } catch (Throwable t) { Crash.note(this, "player bar: " + t); } }
    private void fill0() {
        if (player == null) return;
        chips.removeAllViews(); chips2.removeAllViews(); row = chips;
        boolean ready = player.getPlaybackState() == Player.STATE_READY, paused = !player.getPlayWhenReady();
        long behind = behindLive();
        if (paused) chip("⏸ متوقف", 0xE6F2994A, 0xFF1A0E00);
        else if (!ready) chip("⏳ يتصل…", 0x40FFFFFF, Color.WHITE);
        else if (behind > 15000) chip("⏪ متأخر " + mmss(behind) + "  · ⏯ للمباشر", 0xE6F2994A, 0xFF1A0E00);
        else if (player.isCurrentMediaItemLive() || player.getDuration() == C.TIME_UNSET) chip("🔴 مباشر", 0xE6E5484D, Color.WHITE);
        else chip("▶ " + mmss(player.getCurrentPosition()) + " / " + mmss(player.getDuration()), 0x40FFFFFF, Color.WHITE);
        Format v = player.getVideoFormat(), a = player.getAudioFormat();
        long need = 0;
        if (v != null && v.height > 0) {
            // the quality first, large and in its colour: 4K gold, QHD / FHD green, HD blue, SD grey
            String q = v.height >= 2100 ? "4K UHD" : v.height >= 1400 ? "QHD" : v.height >= 1000 ? "FHD" : v.height >= 700 ? "HD" : "SD";
            int qb = v.height >= 2100 ? 0xFFF5C94C : v.height >= 1000 ? 0xFF3DDC97 : v.height >= 700 ? 0xFF4DA3FF : 0xFF8A96A3;
            chip(q + "  " + v.height + "p", qb, 0xFF07110C, 15);
            String c = codec(v);
            if (!c.isEmpty()) chip("🎞 " + c, 0x2EFFFFFF, Color.WHITE);
            int b = bits(v);
            chip("🎨 " + b + "-bit", b >= 10 ? 0x55B98CFF : 0x2EFFFFFF, Color.WHITE);
            if (v.frameRate > 0) chip(String.format(Locale.US, "⏱ %.0f fps", v.frameRate), v.frameRate >= 49 ? 0x553DDC97 : 0x2EFFFFFF, Color.WHITE);
            String hdr = hdr(v.colorInfo);
            chip(hdr.equals("SDR") ? "☀ SDR" : "✨ " + hdr, hdr.equals("SDR") ? 0x2EFFFFFF : 0xE6F5C94C, hdr.equals("SDR") ? 0xCCFFFFFF : 0xFF1A1405);
            int br = v.averageBitrate > 0 ? v.averageBitrate : v.bitrate > 0 ? v.bitrate : v.peakBitrate;
            if (br > 0) { chip(String.format(Locale.US, "📶 %.1f Mbps", br / 1e6), 0x2EFFFFFF, Color.WHITE); need = br; }
            if (v.width > 0) chip(v.width + "×" + v.height, 0x1AFFFFFF, 0x99FFFFFF);
        }
        row = chips2;
        if (a != null) {
            String m = a.sampleMimeType == null ? "" : a.sampleMimeType.replace("audio/", "").replace("mp4a-latm", "AAC").replace("ac3", "Dolby AC-3").replace("eac3", "Dolby E-AC-3").replace("mpeg", "MP3").toUpperCase(Locale.US);
            String ch = a.channelCount == 6 ? "5.1" : a.channelCount == 8 ? "7.1" : a.channelCount == 2 ? "Stereo" : a.channelCount == 1 ? "Mono" : "";
            String lang = a.language == null || a.language.equals("und") ? "" : " · " + a.language.toUpperCase(Locale.US);
            int nA = audioGroups().size();
            chip(("🔊 " + m + " " + ch + (a.sampleRate > 0 ? " · " + a.sampleRate / 1000 + " kHz" : "") + lang + (nA > 1 ? "  (" + nA + " أصوات)" : "")).trim(), a.channelCount >= 6 ? 0x55B98CFF : 0x2EFFFFFF, Color.WHITE);
        }
        if (!decoder.isEmpty()) {
            boolean sw = decoder.startsWith("OMX.google") || decoder.startsWith("c2.android") || decoder.contains(".sw.");
            chip((sw ? "⚙ فك برمجي · " : "⚡ فك عتادي · ") + decoder.replaceAll("^(c2|OMX)\\.", "").replace(".decoder", ""), sw ? 0x33F2994A : 0x2EFFFFFF, 0xCCFFFFFF);
        }
        hint.setText(vod() ? "◀ ▶ لف ١٠ ث (ضغطات متتابعة تزيد الخطوة)  ·  OK إيقاف / تشغيل  ·  ▲▼ الحلقة السابقة / التالية  ·  OK مطوّلاً ☰ الخيارات" : canWind() ? "▲▼ قناة  ·  ◀ ▶ لف ١٠ ث  ·  ⏪ ⏩ ٣٠ ث  ·  ⏯ إيقاف/تشغيل  ·  OK مطوّلاً ☰ الخيارات"
            : "OK قائمة القنوات  ·  ▲▼ قناة  ·  ◀ ▶ دليل البرامج  ·  ◀ مطوّلاً القناة السابقة" + (prevI >= 0 && prevI < urls.length ? " (" + label(prevI) + ")" : "") + "  ·  ▶ مطوّلاً حجم الصورة  ·  OK مطوّلاً ☰ الخيارات");
        // the colour keys' guide: a coloured dot before each one's action (the Hisense remote's red / green / yellow / blue)
        {
            android.text.SpannableStringBuilder cb = new android.text.SpannableStringBuilder("\n");
            String[][] ck = { { "المباريات الآن", "#FF4D4D" }, { "دليل البرامج", "#3DDC97" }, { "القناة السابقة", "#FFD23F" }, { "ابحث وشغّل", "#4DA3FF" } };
            for (String[] x : ck) {
                int st = cb.length(); cb.append("● ");
                cb.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor(x[1])), st, st + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                cb.append(x[0]).append("     ");
            }
            hint.append(cb);
        }
        // signal: the connection's speed (estimate) against the stream's bitrate
        long have = meter.getBitrateEstimate();
        int bars = !ready ? 1 : need <= 0 ? (have > 8e6 ? 5 : have > 4e6 ? 4 : have > 2e6 ? 3 : 2) : (int) Math.max(1, Math.min(5, Math.round(have / (double) need * 1.6)));
        for (int k = 0; k < 5; k++) ((GradientDrawable) signal[k].getBackground()).setColor(k < bars ? (bars <= 2 ? 0xFFF2994A : 0xFF3DDC97) : 0x40FFFFFF);
    }

    private static String mmss(long ms) { long sec = Math.max(0, ms / 1000); return sec >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60) : String.format(Locale.US, "%d:%02d", sec / 60, sec % 60); }
    private String label(int k) { return names != null && k < names.length && names[k] != null && !names[k].isEmpty() ? names[k] : String.valueOf(k + 1); }
    /** a recording, or a live stream with a window to wind back in (≥ 1 min) */
    private boolean canWind() {
        if (player == null || !player.isCurrentMediaItemSeekable()) return false;
        long d = player.getDuration();
        return d != C.TIME_UNSET && d > 60000;
    }
    /** how far behind the live edge (a live stream wound back or paused), ms */
    private long behindLive() {
        if (player == null || !player.isCurrentMediaItemLive()) return 0;
        long d = player.getDuration(); if (d == C.TIME_UNSET) return 0;
        return Math.max(0, d - player.getCurrentPosition());
    }
    private java.util.List<Tracks.Group> audioGroups() {
        java.util.List<Tracks.Group> out = new java.util.ArrayList<>();
        if (player == null) return out;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) if (g.getType() == C.TRACK_TYPE_AUDIO && g.isSupported()) out.add(g);
        return out;
    }
    private void flash(String s) { setNote(s); fill(); showBar(3500); h.removeCallbacks(clearNote); h.postDelayed(clearNote, 3500); }
    // ⏪ the last 20 minutes of a channel chosen for recording (full edition, HLS channels): Timeshift records it, ◀ ▶ in the player wind through it
    // (taps close together step further: 10 s · 30 s · 1 · 2 · 5 min). Change channel or leave the player: the recording is deleted.
    private Timeshift ts; private boolean tsLocal; private int tsIdx; private long tsLastAt; private String tsUrl;
    private final Handler tsH = new Handler(Looper.getMainLooper());
    private static final int[] TS_STEPS = {10, 30, 60, 120, 300};
    private boolean tsRecOn(String key) { try { return getSharedPreferences("tsrec", MODE_PRIVATE).getStringSet("n", new java.util.HashSet<>()).contains(key); } catch (Throwable t) { return false; } }
    private void tsRecSet(String key, boolean on) { try { android.content.SharedPreferences p = getSharedPreferences("tsrec", MODE_PRIVATE); java.util.Set<String> n = new java.util.HashSet<>(p.getStringSet("n", new java.util.HashSet<>())); if (on) n.add(key); else n.remove(key); p.edit().putStringSet("n", n).apply(); } catch (Throwable t) { } }
    private void tsStop() {
        tsH.removeCallbacksAndMessages(null);
        if (ts != null) { ts.stop(); ts = null; }
        tsLocal = false; tsUrl = null;
    }
    /** a few seconds after a channel opens (not while zapping through) the recording starts */
    private void tsLater() {
        final int at = i; final String u = urls[i];
        if (ts != null && u.equals(tsUrl)) return;
        if (!u.startsWith("http") || ytIdOf(u) != null) return;
        if (!tsRecOn(nOf(at).trim().toLowerCase(Locale.ROOT))) return;   // only the channels chosen for recording, and only while it is playing
        tsH.removeCallbacksAndMessages(null);
        tsH.postDelayed(() -> {
            if (at != i || player == null || (ts != null && u.equals(tsUrl))) return;
            if (ts != null) { ts.stop(); ts = null; }
            tsUrl = u; ts = Timeshift.start(this, u, Iptv.uaFor(u), Iptv.refererFor(u), Iptv.originFor(u));
        }, 6000);
    }
    private void tsOpenLocal(long startMs) {
        tsLocal = true;
        player.setMediaItem(new MediaItem.Builder().setUri(ts.localUrl()).setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()).build(), startMs);
        player.prepare(); player.play();
    }
    private void tsLive() { tsLocal = false; open(); flash("🔴 رجعت للمباشر"); }
    /** ◀ (back) / ▶ (forward) in the player: true when it wound (or told why not), false to leave the key to the guide as before */
    private boolean tsWind(boolean right) {
        if (player == null || urls == null) return false;
        if (ts == null || ts.fail() != null) {   // ◀ ▶ only wind here (never the guide): say why nothing is there
            boolean chosen = tsRecOn(nOf(i).trim().toLowerCase(Locale.ROOT));
            flash(!chosen ? "⏺ هذي القناة مو مسجّلة — من القائمة (OK مطوّل) اختر «سجّل هذي القناة»" : ts != null && ts.fail() != null ? "ما أقدر أسجّل هذي القناة (" + ts.fail() + ")" : "⏳ التسجيل يبدأ بعد لحظات");
            return true;
        }
        long now = System.currentTimeMillis();
        tsIdx = now - tsLastAt < 1500 ? Math.min(TS_STEPS.length - 1, tsIdx + 1) : 0; tsLastAt = now;
        long step = TS_STEPS[tsIdx] * 1000L, win = ts.windowMs();
        if (!tsLocal) {
            if (right) { flash("🔴 أنت على المباشر"); return true; }
            if (win < 20000) { flash("⏳ التسجيل بدأ قبل لحظات — " + mmss(win) + " محفوظة"); return true; }
            tsOpenLocal(Math.max(0, Math.min(win - 15000, win - step)));
            flash("⏪ " + TS_STEPS[tsIdx] + " ث للخلف  · المحفوظ " + mmss(win)); return true;
        }
        long d = player.getDuration(), pos = player.getCurrentPosition(), to = pos + (right ? step : -step);
        if (right && d != C.TIME_UNSET && to >= d - 15000) { tsLive(); return true; }
        player.seekTo(Math.max(0, to));
        flash((right ? "⏩ " : "⏪ ") + TS_STEPS[tsIdx] + " ث" + "  · متأخر " + mmss(d == C.TIME_UNSET ? 0 : Math.max(0, d - Math.max(0, to))) + " من " + mmss(win));
        return true;
    }
    private void wind(long by) {
        long d = player.getDuration(), to = Math.max(0, Math.min(d == C.TIME_UNSET ? Long.MAX_VALUE : d - 2000, player.getCurrentPosition() + by));
        player.seekTo(to);
        flash((by < 0 ? "⏪ " : "⏩ ") + Math.abs(by / 1000) + " ث" + (player.isCurrentMediaItemLive() ? "  · متأخر " + mmss(Math.max(0, d - to)) : "  · " + mmss(to)));
    }
    // ---- «ابحث وشغّل» (blue key): a bar floating at the top; type (the TV's keyboard or the phone remote's) and the closest
    // channels show under it; up/down pick, OK plays, BACK closes
    private LinearLayout sBox, sRes; private android.widget.EditText sIn; private boolean sOn; private int sSel;
    private final java.util.List<Integer> sHits = new java.util.ArrayList<>();
    private void buildSearch(FrameLayout root) {
        sBox = new LinearLayout(this); sBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable(); d.setColor(0xF2101216); d.setCornerRadius(dp(22)); d.setStroke((int) dp(1), 0x40FFFFFF);
        sBox.setBackground(d); sBox.setPadding((int) dp(18), (int) dp(14), (int) dp(18), (int) dp(14));
        sIn = new android.widget.EditText(this); sIn.setSingleLine(true); sIn.setHint("🔎  ابحث وشغّل — اكتب اسم القناة"); sIn.setHintTextColor(0x99FFFFFF);
        sIn.setTextColor(Color.WHITE); sIn.setTextSize(22); sIn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); sIn.setBackground(null);
        sIn.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        sBox.addView(sIn, new LinearLayout.LayoutParams(-1, -2));
        sRes = new LinearLayout(this); sRes.setOrientation(LinearLayout.VERTICAL); sBox.addView(sRes, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams((int) dp(760), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); p.topMargin = (int) dp(36);
        root.addView(sBox, p); sBox.setVisibility(View.GONE);
        sIn.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d2) { }
            public void onTextChanged(CharSequence a, int b, int c, int d2) { }
            public void afterTextChanged(android.text.Editable e) { sSel = 0; searchFill(); }
        });
        sIn.setOnEditorActionListener((v, act, ev) -> { searchGo(); return true; });
        sIn.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN: if (sSel < sHits.size() - 1) sSel++; searchMark(); return true;
                case KeyEvent.KEYCODE_DPAD_UP: if (sSel > 0) sSel--; searchMark(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: searchGo(); return true;
                case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_PROG_BLUE: searchShow(false); return true;
            }
            return false;
        });
    }
    private static String sNorm(String x) {
        if (x == null) return "";
        return x.toLowerCase(Locale.ROOT).replaceAll("[\\[(][^\\])]*[\\])]", " ").replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي').replaceAll("\\s+", " ").trim();
    }
    private void searchFill() {
        sHits.clear(); sRes.removeAllViews();
        String q = sNorm(sIn.getText().toString()); if (q.isEmpty()) return;
        java.util.List<int[]> sc = new java.util.ArrayList<>();
        String[] words = q.split(" ");
        for (int k = 0; k < urls.length; k++) {
            String n = sNorm(nOf(k)); int score = n.startsWith(q) ? 3 : n.contains(q) ? 2 : 0;
            if (score == 0) { boolean all = true; for (String w : words) if (!n.contains(w)) { all = false; break; } if (all) score = 1; }
            if (score > 0) sc.add(new int[]{ score, k });
        }
        java.util.Collections.sort(sc, (a, b) -> b[0] != a[0] ? b[0] - a[0] : a[1] - b[1]);
        for (int x = 0; x < sc.size() && x < 7; x++) {
            int k = sc.get(x)[1]; sHits.add(k);
            TextView t = text(18, Typeface.DEFAULT, 0xE6FFFFFF); t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String fl = fOf(k); t.setText((fl.isEmpty() ? "" : fl + "  ") + nOf(k) + "   · " + (k + 1));
            t.setPadding((int) dp(14), (int) dp(9), (int) dp(14), (int) dp(9));
            sRes.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        if (sHits.isEmpty()) { TextView t = text(16, Typeface.DEFAULT, 0x99FFFFFF); t.setText("ما لقيت قناة بهالاسم"); t.setPadding((int) dp(14), (int) dp(9), 0, (int) dp(9)); sRes.addView(t); }
        searchMark();
    }
    private void searchMark() {
        for (int x = 0; x < sRes.getChildCount() && x < sHits.size(); x++) {
            View v = sRes.getChildAt(x); GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(14));
            g.setColor(x == sSel ? 0x33FFFFFF : 0x00000000); v.setBackground(g);
        }
    }
    private void searchGo() { if (sSel < sHits.size()) { int k = sHits.get(sSel); searchShow(false); if (k != cur) { i = k; play(); } } }
    private void searchShow(boolean on) {
        sOn = on; android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (on) {
            if (menuOn) menuShow(false); if (listOn) listShow(false); if (guideOn) guideShow(false);
            sIn.setText(""); sRes.removeAllViews(); sHits.clear(); sBox.setVisibility(View.VISIBLE); sIn.requestFocus();
            sIn.postDelayed(() -> { if (im != null) im.showSoftInput(sIn, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT); }, 150);
        } else { sBox.setVisibility(View.GONE); if (im != null) im.hideSoftInputFromWindow(sIn.getWindowToken(), 0); sIn.clearFocus(); }
    }
    private void recall() {
        if (prevI < 0 || prevI >= urls.length || prevI == i) { flash("ما فيه قناة سابقة بعد"); return; }
        i = prevI; play();
    }
    /** 🔊 the next sound track (language / commentary), when the channel has more than one */
    private void nextAudio() {
        java.util.List<Tracks.Group> g = audioGroups();
        if (g.size() < 2) { flash("🔊 صوت واحد فقط في هذه القناة"); return; }
        int now = 0; for (int k = 0; k < g.size(); k++) if (g.get(k).isSelected()) now = k;
        audioPick = (now + 1) % g.size();
        Tracks.Group t = g.get(audioPick);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setOverrideForType(new TrackSelectionOverride(t.getMediaTrackGroup(), 0)).build());
        String l = t.getTrackFormat(0).language, lb = t.getTrackFormat(0).label;
        flash("🔊 الصوت " + (audioPick + 1) + " من " + g.size() + (lb != null ? " · " + lb : l != null && !l.equals("und") ? " · " + l.toUpperCase(Locale.US) : ""));
    }
    /** ⏯ pause / go on; a live channel paused a while comes back at the live edge */
    private long pausedAt = 0;
    private void playPause(Boolean want) {
        boolean on = want != null ? want : !player.getPlayWhenReady();
        if (on) {
            boolean jumpLive = player.isCurrentMediaItemLive() && (!canWind() || System.currentTimeMillis() - pausedAt > 5 * 60000);
            if (jumpLive && tsLocal) { tsLive(); return; }   // on the recording: the real live again
            if (jumpLive) player.seekToDefaultPosition();
            player.play(); flash(jumpLive ? "▶ رجوع للمباشر" : "▶ متابعة");
        } else { player.pause(); pausedAt = System.currentTimeMillis(); setNote("⏸ متوقف مؤقتاً · ⏯ أو OK للمتابعة"); fill(); showBar(0); }
    }

    private static String codec(Format f) {
        String m = f.sampleMimeType == null ? "" : f.sampleMimeType, c = f.codecs == null ? "" : f.codecs;
        if (m.contains("hevc") || c.startsWith("hvc1") || c.startsWith("hev1")) return "HEVC · H.265" + (c.matches("^(hvc1|hev1)\\.2\\..*") ? " Main10" : c.isEmpty() ? "" : " Main");
        if (m.contains("avc") || c.startsWith("avc")) {
            String p = c.length() >= 7 ? c.substring(5, 7).toUpperCase(Locale.US) : "";
            String prof = p.equals("42") ? "Baseline" : p.equals("4D") ? "Main" : p.equals("64") ? "High" : p.equals("6E") ? "High10" : "";
            return "AVC · H.264" + (prof.isEmpty() ? "" : " " + prof);
        }
        if (m.contains("av01") || c.startsWith("av01")) return "AV1";
        if (m.contains("vp9") || c.startsWith("vp09")) return "VP9";
        if (m.contains("mpeg2")) return "MPEG-2";
        return m.replace("video/", "").toUpperCase(Locale.US);
    }

    private static int bits(Format f) {
        String c = f.codecs == null ? "" : f.codecs;
        if (f.colorInfo != null && f.colorInfo.lumaBitdepth != Format.NO_VALUE) return f.colorInfo.lumaBitdepth;
        if (c.matches("^(hvc1|hev1)\\.2\\..*") || c.startsWith("avc1.6E") || c.startsWith("avc1.6e")) return 10;
        if (c.startsWith("vp09.02") || c.startsWith("av01.0.") && c.contains(".10.")) return 10;
        return 8;
    }

    private static String hdr(ColorInfo ci) {
        if (ci == null) return "SDR";
        if (ci.colorTransfer == C.COLOR_TRANSFER_ST2084) return "HDR10";
        if (ci.colorTransfer == C.COLOR_TRANSFER_HLG) return "HLG";
        return "SDR";
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            java.util.Calendar c = java.util.Calendar.getInstance();
            int hh = c.get(java.util.Calendar.HOUR) == 0 ? 12 : c.get(java.util.Calendar.HOUR);
            clock.setText(String.format(Locale.US, "%d:%02d", hh, c.get(java.util.Calendar.MINUTE)));
            if (bar.getAlpha() > 0) {
                // a bar that no timer will hide (a «reconnecting…» that ended, a pause that was resumed) never stays: with the picture playing, 2.5 s more
                if (bar.getAlpha() > 0.5f && hideAt == 0 && player != null && player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_READY && !offered && !listOn && !guideOn && !menuOn) showBar(2500);
                if (vod()) { progFill(); if (++tk % 5 == 0) fill(); } else fill();
            }
            h.postDelayed(this, vod() ? 1000 : 5000);
        }
    };

    private void showBar(long ms) {
        bar.animate().cancel(); boolean was = bar.getAlpha() < 0.01f; bar.setAlpha(1f); bar.setTranslationY(0); if (nextBox != null) nextBox.setVisibility(View.GONE); if (sbox != null) sbox.setVisibility(View.GONE);
        if (was && barDirty) fill();
        h.removeCallbacks(hide); hideAt = ms > 0 ? System.currentTimeMillis() + ms : 0;
        if (ms > 0) h.postDelayed(hide, ms);
    }
    private final Runnable hide = () -> bar.animate().alpha(0f).translationY(dp(24)).setDuration(450).withEndAction(() -> { nextNowFirst(); scoresFill(); });

    private boolean retryNote;
    private void setNote(String s) { note.setText(s); note.setVisibility(s == null ? View.GONE : View.VISIBLE); }

    private final Runnable clearNote = () -> { if (!offered && errors == 0) setNote(null); };

    private final Runnable stall = this::retry;

    /** a drop or a stall: reconnect the same channel, a little later each time; after TRIES in a row, offer another player */
    private void retry() {
        net.removeCallbacksAndMessages(null);
        errors++;
        if (errors >= 2 && nextSource()) return;
        if (auto && errors >= 3 && urls.length > 1) { setNote("القناة ما اشتغلت — الناقل التالي"); showBar(3000); i = (i + 1) % urls.length; play(); return; }   // ⚽ a match: the next carrier by itself
        if (errors >= TRIES) { offerOtherPlayer(); return; }
        retryNote = true; setNote("إعادة الاتصال… (" + errors + " من " + TRIES + ")");
        showBar(0);
        net.postDelayed(() -> { open(); }, 1200L * errors);
    }

    private void play() { try { play0(); } catch (Throwable t) { Crash.note(this, "player play: " + t); setNote("تعذّر تشغيل القناة · ↑↓ قناة ثانية"); } }
    private void play0() {
        tally(); since = System.currentTimeMillis(); sinceName = names != null && i < names.length && names[i] != null ? names[i] : "";
        errors = 0; offered = false; decoder = ""; audioPick = -1;
        if (cur >= 0 && cur != i) prevI = cur;
        cur = i;
        try { player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()); } catch (Throwable ignored) { }
        net.removeCallbacksAndMessages(null);
        num.setText(String.valueOf(i + 1));
        String fl = fOf(i);
        name.setText((fl.isEmpty() ? "" : fl + "  ") + (names != null && i < names.length ? names[i] : ""));
        String g = groups != null && i < groups.length ? groups[i] : "";
        group.setText((g == null || g.isEmpty() ? "" : g + " · ") + (i + 1) + " من " + urls.length);
        setNote(null);
        loadLogo(logos != null && i < logos.length ? logos[i] : null);
        fill();
        showBar(5500);
        // zapping: the bar changes at once, the stream opens only once the remote rests (400 ms)
        vodReport();   // an episode left for another: where it stopped
        tsStop();   // another channel: the recording of this one is deleted
        player.stop();
        final int at = i;
        net.postDelayed(() -> { if (at != i) return; open(); }, 250);
    }

    private void loadLogo(String u) {
        logo.setImageDrawable(null);
        if (u == null || u.isEmpty()) { logo.setVisibility(View.GONE); return; }
        logo.setVisibility(View.VISIBLE);
        Bitmap c = LOGOS.get(u);
        if (c != null) { logo.setImageBitmap(c); return; }
        final int at = i;
        new Thread(() -> {
            try {
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                con.setConnectTimeout(6000); con.setReadTimeout(8000);
                Bitmap bm = small(con.getInputStream(), 256);
                if (bm != null) { LOGOS.put(u, bm); runOnUiThread(() -> { if (at == i) logo.setImageBitmap(bm); }); }
            } catch (Exception ignored) { }
        }, "logo").start();
    }

    private void offerOtherPlayer() {
        offered = true;
        setNote("القناة ما اشتغلت بعد " + TRIES + " محاولات · OK لفتحها في مشغّل آخر · ↑↓ قناة ثانية");
        showBar(0);
    }

    private void openOtherPlayer() {
        try {
            Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(urls[i]), "video/*");
            startActivity(Intent.createChooser(v, "شغّل القناة في…"));
        } catch (Exception e) { setNote("ما فيه مشغّل ثاني على التلفزيون · ↑↓ قناة ثانية"); }
    }

    // the channel's number on the remote's digits, like a receiver (1.5 s after the last digit)
    // ---- 🔢 a channel's own 3-digit shortcut (☰ → «اختصار لهذه القناة»): typed anywhere, in the player or on the home page
    private int numFor = -1; private String numBuf = "";
    // the box: three big boxes in the middle of the screen; digits type straight in, ▲▼ change the digit under the cursor and
    // ◀ ▶ move it (for a remote with no digits), OK saves, back cancels
    private LinearLayout numBox; private TextView numDigits, numHint; private int numPos = 0; private char[] numD = { '1', '0', '0' };
    private void numOpen(int ch) {
        numFor = ch; String old = chNumOf(ch);
        numD = (old.length() == 3 ? old : "100").toCharArray(); numPos = 0; numBuf = "";
        if (numBox == null) {
            numBox = new LinearLayout(this); numBox.setOrientation(LinearLayout.VERTICAL); numBox.setGravity(Gravity.CENTER_HORIZONTAL);
            GradientDrawable b = new GradientDrawable(); b.setColor(0xF0101216); b.setCornerRadius(dp(28)); b.setStroke((int) dp(2), 0x40FFFFFF); numBox.setBackground(b);
            numBox.setPadding((int) dp(36), (int) dp(24), (int) dp(36), (int) dp(24));
            TextView t = text(22, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xFFFFFFFF); t.setText("🔢 اختصار لهذه القناة"); t.setGravity(Gravity.CENTER);
            numDigits = text(64, Typeface.create("sans-serif-medium", Typeface.BOLD), 0xFFFFFFFF); numDigits.setGravity(Gravity.CENTER); numDigits.setLetterSpacing(0.3f);
            numDigits.setTextDirection(View.TEXT_DIRECTION_LTR);
            numHint = text(17, Typeface.DEFAULT, 0xCCFFFFFF); numHint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = (int) dp(10);
            numBox.addView(t); numBox.addView(numDigits, lp); numBox.addView(numHint, lp);
            ((FrameLayout) bar.getParent()).addView(numBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        numDraw(null); numBox.setVisibility(View.VISIBLE); numBox.bringToFront();
    }
    private void numDraw(String warn) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int k = 0; k < 3; k++) { int a = sb.length(); sb.append(numD[k]);
            if (k == numPos) { sb.setSpan(new android.text.style.UnderlineSpan(), a, a + 1, 0); sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF34C759), a, a + 1, 0); } }
        numDigits.setText(sb);
        numHint.setText(warn != null ? warn : label(numFor) + "\nاكتب الأرقام، أو ▲▼ للرقم و ◀ ▶ للخانة · OK احفظ · رجوع إلغاء");
    }
    private void numClose() { numFor = -1; if (numBox != null) numBox.setVisibility(View.GONE); }
    private boolean numKey(int code) {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) { numClose(); return true; }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) { numD[numPos] = (char) ('0' + code - KeyEvent.KEYCODE_0); if (numPos < 2) { numPos++; numDraw(null); return true; } return numSave(); }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) { int d = numD[numPos] - '0'; d = (d + (code == KeyEvent.KEYCODE_DPAD_UP ? 1 : 9)) % 10; numD[numPos] = (char) ('0' + d); numDraw(null); return true; }
        // the digits read left to right: ◀ the one before, ▶ the next
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) { numPos = Math.max(0, numPos - 1); numDraw(null); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { numPos = Math.min(2, numPos + 1); numDraw(null); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) return numSave();
        return true;
    }
    private boolean numSave() {
        String c = new String(numD);
        if (c.startsWith("0") || c.equals("999")) { numPos = 0; numDraw("⚠️ " + c + " محجوز — اختر من 100 إلى 998"); return true; }
        org.json.JSONObject o = chNumGet(this, c);
        if (o != null && !urls[numFor].equals(o.optString("url"))) { numDraw("⚠️ " + c + " لـ «" + o.optString("name") + "» — اضغط OK مرة ثانية تبدّله"); if (!c.equals(numBuf)) { numBuf = c; return true; } }
        chNumSet(this, c, nOf(numFor), urls[numFor]); flash("🔢 " + c + " ← " + label(numFor) + "  ·  اكتبه في أي مكان وتنفتح"); numClose();
        return true;
    }
    static void chNumDel(android.content.Context c, String code) {
        try { org.json.JSONObject j = new org.json.JSONObject(Access.set(c).getString("chnum", "{}")); j.remove(code); Access.set(c).edit().putString("chnum", j.toString()).apply(); } catch (Exception ignored) { }
    }
    static void chNumSet(android.content.Context c, String code, String name, String url) {
        try {
            org.json.JSONObject j = new org.json.JSONObject(Access.set(c).getString("chnum", "{}"));
            java.util.Iterator<String> it = j.keys(); java.util.List<String> drop = new java.util.ArrayList<>();
            while (it.hasNext()) { String k = it.next(); if (url.equals(j.optJSONObject(k) == null ? "" : j.optJSONObject(k).optString("url"))) drop.add(k); }
            for (String k : drop) j.remove(k);   // one number per channel
            j.put(code, new org.json.JSONObject().put("name", name).put("url", url));
            Access.set(c).edit().putString("chnum", j.toString()).apply();
        } catch (Exception ignored) { }
    }
    static org.json.JSONObject chNumGet(android.content.Context c, String code) {
        try { return new org.json.JSONObject(Access.set(c).getString("chnum", "{}")).optJSONObject(code); } catch (Exception e) { return null; }
    }
    private String chNumOf(int k) {
        try { org.json.JSONObject j = new org.json.JSONObject(Access.set(this).getString("chnum", "{}")); java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) { String c = it.next(); org.json.JSONObject x = j.optJSONObject(c); if (x != null && urls[k].equals(x.optString("url"))) return c; } } catch (Exception ignored) { }
        return "";
    }
    // ⬆️ 999 over a channel: the newest build fetched and installed by the TV's own debugging shell while this keeps playing;
    // only the install's last seconds restart the app (Android replaces it), and the home comes back by itself
    private void updQuiet() {
        Update.auto(getApplicationContext(), true);
        final long t0 = System.currentTimeMillis();
        h.post(new Runnable() { @Override public void run() {
            String st = Update.stage, nt = Update.note, msg;
            String[] v = nt.split("\\|");
            if ("check".equals(st)) msg = "⬆️ أدوّر على آخر نسخة…";
            else if ("latest".equals(st)) msg = "✅ عندك آخر نسخة" + (v.length == 2 ? " (رقم " + v[0] + ")" : "");
            else if ("found".equals(st)) msg = v.length == 2 ? "⬆️ عندك نسخة رقم " + v[0] + " — وفيه نسخة رقم " + v[1] + " · أبدأ التحديث" : "⬆️ فيه نسخة جديدة · أبدأ التحديث";
            else if ("down".equals(st)) msg = "⬇️ أنزّل النسخة " + (v.length == 2 ? v[1] + " " : "") + Update.pct + "٪";
            else if ("install".equals(st) || "restart".equals(st)) msg = "📦 أثبّت الحين — التطبيق بيسكّر ويرجع بالنسخة الجديدة";
            else if ("noadb".equals(st)) msg = "⚠️ التحديث بدون موافقة يحتاج «تصحيح الشبكة»: من الريموت ← المزيد ← ⚙️ التلفزيون ← 🔧";
            else if ("error".equals(st)) msg = "⚠️ ما تم التحديث: " + nt;
            else msg = null;
            if (msg != null) flash(msg);
            if (!"latest".equals(st) && !"error".equals(st) && !"noadb".equals(st) && !"restart".equals(st) && System.currentTimeMillis() - t0 < 10 * 60_000) h.postDelayed(this, 1500);
        } });
    }
    private final Runnable jump = () -> {
        String d = digits; digits = "";
        org.json.JSONObject own = d.length() == 3 ? chNumGet(this, d) : null;
        if (own != null) {   // 🔢 a channel's own number: here if the list has it, else the home page plays it
            String u = own.optString("url");
            for (int k = 0; k < urls.length; k++) if (u.equals(urls[k])) { i = k; play(); return; }
            HomeActivity hm = HomeActivity.alive; if (hm != null) { finish(); hm.run("window.chNumPlay && chNumPlay(" + own + ")"); } return;
        }
        if (d.equals("999")) { updQuiet(); return; }   // ⬆️ the update in the background; the channel keeps playing
        if (d.startsWith("0")) {   // 🔢 a shortcut (00 settings, 01 a tab, 061 beIN LIVE EVENT 1…): the home page's list
            HomeActivity hm = HomeActivity.alive; if (hm == null) return;
            if (!d.matches("06[1-9]")) finish();
            hm.run("window.shortcutGo && shortcutGo('" + d + "')"); return;
        }
        try { int n = Integer.parseInt(d); if (n >= 1 && n <= urls.length) { i = n - 1; play(); } } catch (Exception ignored) { }
    };

    // 👆 touch (a tablet through RustDesk, a touch screen): the same as the remote's keys — swipe ↑ next channel, ↓ the one before,
    // a tap opens the channel list (its rows are tapped to play), a swipe sideways in the list goes to its groups / playlists and
    // back, a long press is the channel's menu, a double tap pauses; a tap outside an open list closes it
    private android.view.GestureDetector touch;
    private void tapKey(int code) {
        try { long t = android.os.SystemClock.uptimeMillis(); key(code, new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)); onKeyUp(code, new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0)); }
        catch (Throwable x) { Crash.note(this, "player touch: " + x); }
    }
    private boolean inList(android.view.MotionEvent e) {
        if (!listOn || listPanel == null) return false;
        int[] o = new int[2]; listPanel.getLocationOnScreen(o);
        return e.getRawX() >= o[0] && e.getRawX() <= o[0] + listPanel.getWidth();
    }
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (touch == null) {
            touch = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(android.view.MotionEvent ev) { return true; }
                @Override public boolean onSingleTapConfirmed(android.view.MotionEvent ev) {
                    if (inList(ev)) return false;
                    if (menuOn) { tapKey(KeyEvent.KEYCODE_BACK); return true; }
                    if (listOn) { listShow(false); return true; }
                    tapKey(KeyEvent.KEYCODE_DPAD_CENTER); return true;   // the list (as OK does)
                }
                @Override public boolean onDoubleTap(android.view.MotionEvent ev) { if (inList(ev) || listOn || menuOn) return false; tapKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return true; }
                @Override public void onLongPress(android.view.MotionEvent ev) { if (inList(ev) || menuOn) return; if (listOn) listShow(false); menuShow(true); }
                @Override public boolean onFling(android.view.MotionEvent a, android.view.MotionEvent b, float vx, float vy) {
                    if (a == null || b == null) return false;
                    float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                    if (Math.abs(dx) > Math.abs(dy) * 1.3 && Math.abs(dx) > 90) {   // sideways: in the list, up to the groups / playlists (towards its edge) and back in
                        if (listOn) { tapKey(dx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT); return true; }
                        return false;
                    }
                    if (inList(a) || listOn || menuOn || guideOn) return false;
                    if (Math.abs(dy) > 90) { tapKey(dy < 0 ? KeyEvent.KEYCODE_CHANNEL_UP : KeyEvent.KEYCODE_CHANNEL_DOWN); return true; }
                    return false;
                }
            });
        }
        boolean used = touch.onTouchEvent(e);
        if (inList(e) || menuOn) return super.dispatchTouchEvent(e);   // the list and the menu scroll and take taps themselves
        return used || super.dispatchTouchEvent(e);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) { try { boolean w = zzWarned; sleepArm(); if (w && code != KeyEvent.KEYCODE_BACK) { showBar(3000); return true; } return key(code, e); } catch (Throwable t) { Crash.note(this, "player key: " + t); return true; } }
    private boolean key(int code, KeyEvent e) {
        if (numFor >= 0) return numKey(code);   // 🔢 the shortcut box takes every key while it is open
        if (gbox != null && gbox.getVisibility() == View.VISIBLE && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) { h.removeCallbacks(goalHide); goalHide.run(); return true; }
        if (code == KeyEvent.KEYCODE_SEARCH) { HomeActivity.voice(this, 78); return true; }   // 🎙️
        if (listOn && listKey(code, e)) return true;
        if (guideOn && guideKey(code, e)) return true;
        if (menuOn && menuKey(code, e)) return true;
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP) { i = (i + 1) % urls.length; play(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) { i = (i - 1 + urls.length) % urls.length; play(); return true; }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            digits += (code - KeyEvent.KEYCODE_0);
            num.setText(digits + "_"); setNote("🔢 " + digits + "_"); showBar(2500); h.removeCallbacks(clearNote); h.postDelayed(clearNote, 2500);
            h.removeCallbacks(jump); h.postDelayed(jump, digits.length() >= 3 ? 600 : 1300); return true;
        }
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (ok) {   // a short OK on release; held ≈ ½ s: the options
            if (e.getRepeatCount() == 0) { pOk = true; return true; }
            if ((e.getRepeatCount() == 5 || e.isLongPress()) && pOk) { pOk = false; menuShow(true); }   // remotes that send Android's long press rather than repeats too
            return true;
        }
        return keyRest(code, e, false);
    }
    /** a short OK (on its release), or the keys after OK */
    private boolean keyRest(int code, KeyEvent e, boolean ok) {
        if (ok && System.currentTimeMillis() < offerUntil) {
            offerUntil = 0; setNote(null); h.removeCallbacks(goalHide); if (gbox != null) gbox.setVisibility(View.GONE);
            HomeActivity hm = HomeActivity.alive; if (hm != null) hm.run("window.playOffer && playOffer()");
            return true;
        }
        if (ok && offered) { openOtherPlayer(); return true; }
        if (ok && !player.getPlayWhenReady()) { playPause(true); return true; }
        if (ok && vod()) { playPause(null); return true; }   // an episode: OK pauses / plays (held: the options)
        boolean wind = canWind();
        // ◀ ▶ — a stream that can be wound: back / on 10 s (held: 30 s at a time). A plain live channel: ◀ the channel before
        // (like a receiver's «recall»), ▶ the picture's size
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (wind && vod()) { vodWind(right, e); return true; }
            if (wind && !player.isCurrentMediaItemLive()) { wind((right ? 1 : -1) * (e.getRepeatCount() > 2 ? 30000L : 10000L)); return true; }   // a live channel's ◀ ▶ are the recording's (short, on release) and recall / picture size (held)
            if (e.getRepeatCount() == 0) { lrCode = code; lrHeld = false; return true; }   // short: the guide, on release
            if (!(e.getRepeatCount() == 4 || e.isLongPress()) || lrCode != code || lrHeld) return true;
            lrHeld = true;
            if (!right) { recall(); return true; }
            fitMode = (fitMode + 1) % 3; fit();
            flash("حجم الصورة: " + FIT_NAMES[fitMode] + "  ▶ مطوّلاً"); return true;
        }
        // the remote's media keys
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_SPACE: playPause(null); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: playPause(true); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: playPause(false); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: if (wind) wind(30000); else { i = (i + 1) % urls.length; play(); } return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: if (wind) wind(-30000); else { i = (i - 1 + urls.length) % urls.length; play(); } return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT: i = (i + 1) % urls.length; play(); return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: i = (i - 1 + urls.length) % urls.length; play(); return true;
            case KeyEvent.KEYCODE_LAST_CHANNEL: recall(); return true;
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: case KeyEvent.KEYCODE_LANGUAGE_SWITCH: nextAudio(); return true;
            // the colour keys (the Hisense remote over CEC): red the matches on now, green the guide, yellow the channel before, blue search & play
            case KeyEvent.KEYCODE_PROG_RED: case KeyEvent.KEYCODE_PROG_GREEN: case KeyEvent.KEYCODE_PROG_YELLOW: case KeyEvent.KEYCODE_PROG_BLUE: colorKey(code); return true;
            case KeyEvent.KEYCODE_MEDIA_STOP: finish(); return true;
            case KeyEvent.KEYCODE_GUIDE: guideCh = cur >= 0 ? cur : i; guideAt = 0; guideShow(true); return true;
            case KeyEvent.KEYCODE_MENU: fill(); showBar(12000); tickerShow(); return true;
            default: break;
        }
        if (ok && urls.length > 1) { h.removeCallbacks(hide); hide.run(); listShow(true); return true; }   // OK: the list alone
        if (ok || code == KeyEvent.KEYCODE_INFO) { fill(); showBar(6000); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 78 && res == RESULT_OK) { HomeActivity.voiceResult(data); h.postDelayed(this::finish, 1500); }   // the answer is the home page's (its code waits while the player is in front)
    }

    // ---- ⚡ zapping like a receiver, the light way: 3 s after a channel starts playing, the channels ▲ ▼ and ◀ (the one
    // before) go to are «warmed» — their address resolved, the connection opened and their list read (a few KB, no picture
    // decoded), so the next zap skips the slow part. Never a channel on the same server as an Xtream-style account (one
    // connection: asking for another channel there would cut this one).
    private static final java.util.Map<String, Long> WARM = new java.util.HashMap<>();
    private final Runnable warmNext = () -> {
        if (player == null || urls.length < 2) return;
        java.util.LinkedHashSet<Integer> ks = new java.util.LinkedHashSet<>();
        ks.add((i + 1) % urls.length); ks.add((i - 1 + urls.length) % urls.length); if (prevI >= 0 && prevI < urls.length) ks.add(prevI);
        ks.remove(i);
        String host = hostOf(urls[i]); boolean xt = xtream(urls[i]);
        for (int k : ks) {
            final String u = urls[k];
            if (u == null || !u.startsWith("http")) continue;
            if ((xt || xtream(u)) && hostOf(u).equals(host)) continue;
            Long at = WARM.get(u); if (at != null && System.currentTimeMillis() - at < 60_000) continue;
            WARM.put(u, System.currentTimeMillis());
            new Thread(() -> warm(u), "warm").start();
        }
    };
    private static String hostOf(String u) { try { return new java.net.URL(u).getHost(); } catch (Exception e) { return ""; } }
    private static boolean xtream(String u) { return u != null && u.matches("(?i)https?://[^/]+/(live/)?[^/]+/[^/]+/\\d+(\\.(ts|m3u8))?(\\?.*)?$"); }
    private static void warm(String u) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setInstanceFollowRedirects(true);
            Iptv.apply(c, u);
            String ct = String.valueOf(c.getContentType()).toLowerCase(Locale.ROOT);
            if (!(ct.contains("mpegurl") || u.contains(".m3u8"))) { c.disconnect(); return; }   // only a list (never a stream's bytes)
            byte[] b = new byte[65536]; int n = 0, r;
            try (java.io.InputStream in = c.getInputStream()) { while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r; }
            // a master list: its first variant's list too (that server is warmed as well)
            String body = new String(b, 0, n, "UTF-8");
            if (body.contains("#EXT-X-STREAM-INF")) {
                for (String line : body.split("\\r?\\n")) { line = line.trim(); if (!line.isEmpty() && !line.startsWith("#")) {
                    String v = new java.net.URL(c.getURL(), line).toString();
                    java.net.HttpURLConnection c2 = (java.net.HttpURLConnection) new java.net.URL(v).openConnection();
                    c2.setConnectTimeout(5000); c2.setReadTimeout(5000); Iptv.apply(c2, v);
                    try (java.io.InputStream in = c2.getInputStream()) { in.read(new byte[16384]); }
                    break; } }
            }
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onStop() {
        super.onStop();
        vodReport();
        tsStop();
        tally();
        if (live == this) live = null;
        net.removeCallbacksAndMessages(null); h.removeCallbacksAndMessages(null); zz.removeCallbacksAndMessages(null); cd.removeCallbacksAndMessages(null); sk.removeCallbacksAndMessages(null);
        Scan.paused = false;
        try { if (yt != null) { yt.loadUrl("about:blank"); yt.destroy(); yt = null; } } catch (Throwable ignored) { }
        try { if (player != null) { player.release(); } } catch (Throwable ignored) { }
        player = null;
        finish();
    }
}
