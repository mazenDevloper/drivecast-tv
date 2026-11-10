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
    private DefaultBandwidthMeter meter;
    private String[] urls, names, logos, groups;
    private int i;
    private final Handler h = new Handler(Looper.getMainLooper());      // the bar
    private final Handler net = new Handler(Looper.getMainLooper());    // reconnects, the stall watchdog
    private int TRIES = 6, errors;
    private boolean offered;
    private String decoder = "", digits = "";

    private LinearLayout bar;
    private TextView num, name, group, note, clock;
    private ImageView logo;
    private LinearLayout chips, chips2;
    private View[] signal = new View[5];
    private static final Map<String, Bitmap> LOGOS = new HashMap<>();

    private float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { TRIES = Integer.parseInt(Access.set(this).getString("tries", "6")); } catch (Exception ignored) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent in = getIntent();
        urls = in.getStringArrayExtra("urls"); names = in.getStringArrayExtra("names");
        logos = in.getStringArrayExtra("logos"); groups = in.getStringArrayExtra("groups");
        i = in.getIntExtra("i", 0);
        if (urls == null || urls.length == 0) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        PlayerView view = new PlayerView(this);
        view.setUseController(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setShutterBackgroundColor(Color.BLACK);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        buildBar(root);
        setContentView(root);

        meter = new DefaultBandwidthMeter.Builder(this).build();
        player = new ExoPlayer.Builder(this).setBandwidthMeter(meter).build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) { net.removeCallbacks(stall); net.postDelayed(() -> errors = 0, 20000); fill(); }
                if (s == Player.STATE_BUFFERING) { net.removeCallbacks(stall); net.postDelayed(stall, 12000); }
                if (s == Player.STATE_ENDED) retry();   // a live stream that "ends" has dropped
            }
            @Override public void onPlayerError(PlaybackException e) { retry(); }
        });
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime t, String d, long a, long b) { decoder = d; fill(); }
            @Override public void onVideoInputFormatChanged(EventTime t, Format f, DecoderReuseEvaluation r) { fill(); }
            @Override public void onBandwidthEstimate(EventTime t, int ms, long bytes, long bps) { fill(); }
        });
        play();
        tick.run();
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
        names_.addView(name); names_.addView(group);
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
        note = text(13, med, 0xFFFFD27A); note.setVisibility(View.GONE);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2); np.topMargin = (int) dp(8);
        bar.addView(note, np);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins((int) dp(40), 0, (int) dp(40), (int) dp(26));
        root.addView(bar, lp);
    }

    private TextView text(float sp, Typeface tf, int color) {
        TextView t = new TextView(this); t.setTextSize(sp); t.setTypeface(tf); t.setTextColor(color); t.setIncludeFontPadding(false); return t;
    }

    private LinearLayout row;
    private void chip(String s, int bg, int fg) {
        TextView t = text(12, Typeface.create("sans-serif-medium", Typeface.BOLD), fg);
        t.setText(s); t.setPadding((int) dp(10), (int) dp(4), (int) dp(10), (int) dp(4));
        t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        GradientDrawable d = new GradientDrawable(); d.setColor(bg); d.setCornerRadius(dp(10)); t.setBackground(d);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMarginEnd((int) dp(6));
        row.addView(t, p);
    }

    /** what is really coming in, as chips */
    private void fill() {
        if (player == null) return;
        chips.removeAllViews(); chips2.removeAllViews(); row = chips;
        boolean ready = player.getPlaybackState() == Player.STATE_READY;
        chip(ready ? "● مباشر" : "… يتصل", ready ? 0xE6E5484D : 0x40FFFFFF, Color.WHITE);
        Format v = player.getVideoFormat(), a = player.getAudioFormat();
        long need = 0;
        if (v != null && v.height > 0) {
            int hh = Math.min(v.height, v.width > 0 ? v.width * 9 / 16 + 8 : v.height);
            String q = v.height >= 2100 ? "4K UHD" : v.height >= 1400 ? "QHD" : v.height >= 1000 ? "FHD" : v.height >= 700 ? "HD" : "SD";
            chip(q + "  " + v.width + "×" + v.height, 0xF2FFFFFF, 0xFF0B1014);
            String c = codec(v);
            if (!c.isEmpty()) chip(c, 0x2EFFFFFF, Color.WHITE);
            chip(bits(v) + "-bit", 0x2EFFFFFF, Color.WHITE);
            if (v.frameRate > 0) chip(String.format(Locale.US, "%.0f fps", v.frameRate), 0x2EFFFFFF, Color.WHITE);
            String hdr = hdr(v.colorInfo);
            chip(hdr, hdr.equals("SDR") ? 0x33FFFFFF : 0xE6F5C94C, hdr.equals("SDR") ? Color.WHITE : 0xFF1A1405);
            int br = v.averageBitrate > 0 ? v.averageBitrate : v.bitrate > 0 ? v.bitrate : v.peakBitrate;
            if (br > 0) { chip(String.format(Locale.US, "%.1f Mbps", br / 1e6), 0x2EFFFFFF, Color.WHITE); need = br; }
        }
        row = chips2;
        if (a != null) {
            String m = a.sampleMimeType == null ? "" : a.sampleMimeType.replace("audio/", "").replace("mp4a-latm", "AAC").replace("ac3", "Dolby AC-3").replace("eac3", "Dolby E-AC-3").replace("mpeg", "MP3").toUpperCase(Locale.US);
            String ch = a.channelCount == 6 ? "5.1" : a.channelCount == 8 ? "7.1" : a.channelCount == 2 ? "2.0" : a.channelCount == 1 ? "1.0" : "";
            chip(("♪ " + m + " " + ch + (a.sampleRate > 0 ? " · " + a.sampleRate / 1000 + " kHz" : "")).trim(), 0x2EFFFFFF, Color.WHITE);
        }
        if (!decoder.isEmpty()) {
            boolean sw = decoder.startsWith("OMX.google") || decoder.startsWith("c2.android") || decoder.contains(".sw.");
            chip((sw ? "فك برمجي · " : "فك عتادي · ") + decoder.replaceAll("^(c2|OMX)\\.", "").replace(".decoder", ""), 0x2EFFFFFF, 0xCCFFFFFF);
        }
        // signal: the connection's speed (estimate) against the stream's bitrate
        long have = meter.getBitrateEstimate();
        int bars = !ready ? 1 : need <= 0 ? (have > 8e6 ? 5 : have > 4e6 ? 4 : have > 2e6 ? 3 : 2) : (int) Math.max(1, Math.min(5, Math.round(have / (double) need * 1.6)));
        for (int k = 0; k < 5; k++) ((GradientDrawable) signal[k].getBackground()).setColor(k < bars ? (bars <= 2 ? 0xFFF2994A : 0xFF3DDC97) : 0x40FFFFFF);
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
            if (bar.getAlpha() > 0) fill();
            h.postDelayed(this, 5000);
        }
    };

    private void showBar(long ms) {
        bar.animate().cancel(); bar.setAlpha(1f); bar.setTranslationY(0);
        h.removeCallbacks(hide);
        if (ms > 0) h.postDelayed(hide, ms);
    }
    private final Runnable hide = () -> bar.animate().alpha(0f).translationY(dp(24)).setDuration(450);

    private void setNote(String s) { note.setText(s); note.setVisibility(s == null ? View.GONE : View.VISIBLE); }

    private final Runnable stall = this::retry;

    /** a drop or a stall: reconnect the same channel, a little later each time; after TRIES in a row, offer another player */
    private void retry() {
        net.removeCallbacksAndMessages(null);
        errors++;
        if (errors >= TRIES) { offerOtherPlayer(); return; }
        setNote("إعادة الاتصال… (" + errors + " من " + TRIES + ")");
        showBar(0);
        net.postDelayed(() -> { player.setMediaItem(MediaItem.fromUri(urls[i])); player.prepare(); player.play(); }, 1200L * errors);
    }

    private void play() {
        errors = 0; offered = false; decoder = "";
        net.removeCallbacksAndMessages(null);
        num.setText(String.valueOf(i + 1));
        name.setText(names != null && i < names.length ? names[i] : "");
        String g = groups != null && i < groups.length ? groups[i] : "";
        group.setText((g == null || g.isEmpty() ? "" : g + " · ") + (i + 1) + " من " + urls.length);
        setNote(null);
        loadLogo(logos != null && i < logos.length ? logos[i] : null);
        fill();
        showBar(5500);
        // zapping: the bar changes at once, the stream opens only once the remote rests (400 ms)
        player.stop();
        final int at = i;
        net.postDelayed(() -> { if (at != i) return; player.setMediaItem(MediaItem.fromUri(urls[i])); player.prepare(); player.play(); }, 400);
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
                Bitmap bm = BitmapFactory.decodeStream(con.getInputStream());
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
    private final Runnable jump = () -> {
        try { int n = Integer.parseInt(digits); if (n >= 1 && n <= urls.length) { i = n - 1; play(); } } catch (Exception ignored) { }
        digits = "";
    };

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP) { i = (i + 1) % urls.length; play(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) { i = (i - 1 + urls.length) % urls.length; play(); return true; }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            digits += (code - KeyEvent.KEYCODE_0);
            num.setText(digits + "_"); showBar(2500);
            h.removeCallbacks(jump); h.postDelayed(jump, 1500); return true;
        }
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (ok && e.getRepeatCount() == 8) { openOtherPlayer(); return true; }   // OK held ≈ ½ s: another player
        if (ok && e.getRepeatCount() > 0) return true;
        if (ok && offered) { openOtherPlayer(); return true; }
        if (ok || code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) { fill(); showBar(6000); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onStop() {
        super.onStop();
        net.removeCallbacksAndMessages(null); h.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        finish();
    }
}
