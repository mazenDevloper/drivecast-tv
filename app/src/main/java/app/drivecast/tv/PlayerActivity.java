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
    private DefaultBandwidthMeter meter;
    private String[] urls, names, logos, groups;
    private int i;
    private final Handler h = new Handler(Looper.getMainLooper());      // the bar
    private final Handler net = new Handler(Looper.getMainLooper());    // reconnects, the stall watchdog
    static PlayerActivity live;
    // ---- 📺 picture in picture: HOME while a channel plays → it goes on in a corner over the home screen
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
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
    private static final Map<String, Bitmap> LOGOS = new HashMap<>();

    private float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (live != null && live != this) live.finish();   // one player at a time (the one in the corner gives way)
        live = this;
        try { TRIES = Integer.parseInt(Access.set(this).getString("tries", "6")); } catch (Exception ignored) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent in = getIntent();
        urls = in.getStringArrayExtra("urls"); names = in.getStringArrayExtra("names");
        logos = in.getStringArrayExtra("logos"); groups = in.getStringArrayExtra("groups");
        i = in.getIntExtra("i", 0);
        if (urls == null || urls.length == 0) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setUseController(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setShutterBackgroundColor(Color.BLACK);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        buildBar(root); buildList(root); Scan.paused = true;   // the daily scan waits while a channel plays
        setContentView(root);

        meter = new DefaultBandwidthMeter.Builder(this).build();
        http = new androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent(HomeActivity.BROWSER).setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(12000).setReadTimeoutMs(15000);
        player = new ExoPlayer.Builder(this, new androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true)).setBandwidthMeter(meter)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(this, http))).build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) { net.removeCallbacks(stall); net.postDelayed(() -> errors = 0, 20000); fill(); }
                if (s == Player.STATE_BUFFERING) { net.removeCallbacks(stall); net.postDelayed(stall, 12000); }
                if (s == Player.STATE_ENDED) retry();   // a live stream that "ends" has dropped
            }
            @Override public void onPlayerError(PlaybackException e) { retry(); }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) { vw = vs.width * (vs.pixelWidthHeightRatio > 0 ? vs.pixelWidthHeightRatio : 1f); vh = vs.height; fit(); }
        });
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fit());
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime t, String d, long a, long b) { decoder = d; fill(); }
            @Override public void onVideoInputFormatChanged(EventTime t, Format f, DecoderReuseEvaluation r) { fill(); }
            @Override public void onBandwidthEstimate(EventTime t, int ms, long bytes, long bps) { fill(); }
        });
        play();
        tick.run();
        sleepArm();
    }

    // ---- the picture's width: 0 smart, 1 as it is (black sides stay), 2 fill the screen (crops)
    private PlayerView view;
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
        // 🕒 the clock hung at the top, light and see-through, all the time (as in the home's photo view); setting «pclock» = off hides it
        hang = text(30, Typeface.create("sans-serif-medium", Typeface.NORMAL), 0xD9FFFFFF);
        hang.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        tp.setMargins(0, (int) dp(22), (int) dp(34), 0);
        root.addView(hang, tp);
        if ("off".equals(Access.set(this).getString("pclock", "on"))) hang.setVisibility(View.GONE);
        topTick.run();
    }

    // ---- OK: the channel list at the side, like a receiver's. ↑↓ choose (held: fast), ◀ ▶ ten at a time, OK plays it
    // (the list stays, OK again on the playing one closes it), BACK closes; it hides by itself after 10 s untouched
    private FrameLayout listPanel; private android.widget.ScrollView listScroll; private LinearLayout listCol;
    private TextView listHead; private int sel; private boolean listOn;
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
        listScroll = new android.widget.ScrollView(this); listScroll.setVerticalScrollBarEnabled(false); listScroll.setFocusable(false);
        listCol = new LinearLayout(this); listCol.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listCol);
        box.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView tip = text(10.5f, Typeface.DEFAULT, 0x99FFFFFF); tip.setGravity(Gravity.END);
        tip.setText("↑↓ اختر · ◀ ▶ عشر عشر · OK شغّل · رجوع يقفل"); tip.setPadding(0, (int) dp(10), (int) dp(10), 0);
        box.addView(tip);
        listPanel.addView(box, new FrameLayout.LayoutParams(-1, -1));
        root.addView(listPanel, new FrameLayout.LayoutParams((int) dp(400), -1, Gravity.RIGHT));
        listPanel.setVisibility(View.GONE);
    }
    private void listFill() {
        listCol.removeAllViews();
        for (int k = 0; k < urls.length; k++) {
            LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
            r.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            r.setPadding((int) dp(12), (int) dp(9), (int) dp(12), (int) dp(9));
            TextView n = text(13, Typeface.create("sans-serif-medium", Typeface.BOLD), 0x99FFFFFF);
            n.setText(String.valueOf(k + 1)); n.setMinWidth((int) dp(40));
            TextView t = text(15, Typeface.DEFAULT, 0xFFFFFFFF); t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setText((k == cur ? "▶  " : "") + (names != null && k < names.length && names[k] != null ? names[k] : "قناة " + (k + 1)));
            t.setPadding((int) dp(8), 0, 0, 0);
            r.addView(n); r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            listCol.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    private void listMark() {
        for (int k = 0; k < listCol.getChildCount(); k++) {
            View r = listCol.getChildAt(k);
            if (k == sel) { GradientDrawable d = new GradientDrawable(); d.setColor(0xF2FFFFFF); d.setCornerRadius(dp(14)); r.setBackground(d); }
            else if (k == cur) { GradientDrawable d = new GradientDrawable(); d.setColor(0x3334C759); d.setCornerRadius(dp(14)); r.setBackground(d); }
            else r.setBackground(null);
            int fg = k == sel ? 0xFF111111 : 0xFFFFFFFF;
            ((TextView) ((LinearLayout) r).getChildAt(1)).setTextColor(fg);
            ((TextView) ((LinearLayout) r).getChildAt(0)).setTextColor(k == sel ? 0xFF555555 : 0x99FFFFFF);
        }
        String g = groups != null && sel < groups.length && groups[sel] != null ? groups[sel] : "";
        listHead.setText("📺 القنوات · " + (sel + 1) + " من " + urls.length + (g.isEmpty() ? "" : "  ·  " + g));
        View r = listCol.getChildAt(sel);
        if (r != null) listScroll.post(() -> listScroll.smoothScrollTo(0, Math.max(0, r.getTop() - listScroll.getHeight() / 2 + r.getHeight() / 2)));
        h.removeCallbacks(listHide); h.postDelayed(listHide, 10000);
    }
    private void listShow(boolean on) {
        listOn = on;
        if (on) { sel = Math.max(0, cur); listFill(); listPanel.setVisibility(View.VISIBLE); listPanel.setAlpha(0f); listPanel.setTranslationX(dp(60)); listPanel.animate().alpha(1f).translationX(0).setDuration(220); listMark(); }
        else { h.removeCallbacks(listHide); listPanel.animate().alpha(0f).translationX(dp(60)).setDuration(200).withEndAction(() -> { if (!listOn) listPanel.setVisibility(View.GONE); }); }
    }
    private boolean listKey(int code, KeyEvent e) {
        int n = urls.length;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: sel = (sel - 1 + n) % n; listMark(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: sel = (sel + 1) % n; listMark(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: sel = Math.min(n - 1, sel + 10); listMark(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: sel = Math.max(0, sel - 10); listMark(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() > 0) return true;
                if (sel == cur) { listShow(false); return true; }
                i = sel; play(); listFill(); listMark(); return true;
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
    private void open() { try { if (player == null) return;
        // the channel's own site as Referer / Origin where its CDN wants it
        java.util.Map<String, String> hd = new java.util.HashMap<>(); String site = HomeActivity.siteOf(urls[i]);
        if (site != null) { hd.put("Referer", site + "/"); hd.put("Origin", site); }
        http.setDefaultRequestProperties(hd);
        player.setMediaItem(MediaItem.fromUri(urls[i])); player.prepare(); player.play(); } catch (Throwable t) { Crash.note(this, "player open: " + t); setNote("تعذّر تشغيل القناة · ↑↓ قناة ثانية"); } }
    private void fill() { try { fill0(); } catch (Throwable t) { Crash.note(this, "player bar: " + t); } }
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
        hint.setText(canWind() ? "▲▼ قناة  ·  ◀ ▶ لف ١٠ ث  ·  ⏪ ⏩ ٣٠ ث  ·  ⏯ إيقاف/تشغيل  ·  OK مطوّلاً مشغّل آخر"
            : "OK قائمة القنوات  ·  ▲▼ قناة  ·  ◀ القناة السابقة" + (prevI >= 0 && prevI < urls.length ? " (" + label(prevI) + ")" : "") + "  ·  ▶ حجم الصورة  ·  ⏯ إيقاف مؤقت  ·  🔊 صوت آخر");
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
    private void wind(long by) {
        long d = player.getDuration(), to = Math.max(0, Math.min(d == C.TIME_UNSET ? Long.MAX_VALUE : d - 2000, player.getCurrentPosition() + by));
        player.seekTo(to);
        flash((by < 0 ? "⏪ " : "⏩ ") + Math.abs(by / 1000) + " ث" + (player.isCurrentMediaItemLive() ? "  · متأخر " + mmss(Math.max(0, d - to)) : "  · " + mmss(to)));
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

    private final Runnable clearNote = () -> { if (!offered && errors == 0) setNote(null); };

    private final Runnable stall = this::retry;

    /** a drop or a stall: reconnect the same channel, a little later each time; after TRIES in a row, offer another player */
    private void retry() {
        net.removeCallbacksAndMessages(null);
        errors++;
        if (errors >= TRIES) { offerOtherPlayer(); return; }
        setNote("إعادة الاتصال… (" + errors + " من " + TRIES + ")");
        showBar(0);
        net.postDelayed(() -> { open(); }, 1200L * errors);
    }

    private void play() { try { play0(); } catch (Throwable t) { Crash.note(this, "player play: " + t); setNote("تعذّر تشغيل القناة · ↑↓ قناة ثانية"); } }
    private void play0() {
        errors = 0; offered = false; decoder = ""; audioPick = -1;
        if (cur >= 0 && cur != i) prevI = cur;
        cur = i;
        try { player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()); } catch (Throwable ignored) { }
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
        net.postDelayed(() -> { if (at != i) return; open(); }, 400);
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
    public boolean onKeyDown(int code, KeyEvent e) { try { boolean w = zzWarned; sleepArm(); if (w && code != KeyEvent.KEYCODE_BACK) { showBar(3000); return true; } return key(code, e); } catch (Throwable t) { Crash.note(this, "player key: " + t); return true; } }
    private boolean key(int code, KeyEvent e) {
        if (listOn && listKey(code, e)) return true;
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
        if (ok && !player.getPlayWhenReady()) { playPause(true); return true; }
        boolean wind = canWind();
        // ◀ ▶ — a stream that can be wound: back / on 10 s (held: 30 s at a time). A plain live channel: ◀ the channel before
        // (like a receiver's «recall»), ▶ the picture's size
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (wind) { wind((right ? 1 : -1) * (e.getRepeatCount() > 2 ? 30000L : 10000L)); return true; }
            if (e.getRepeatCount() > 0) return true;
            if (!right) { recall(); return true; }
            fitMode = (fitMode + 1) % 3; fit();
            flash("حجم الصورة: " + FIT_NAMES[fitMode] + "  ▶"); return true;
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
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: case KeyEvent.KEYCODE_LANGUAGE_SWITCH: case KeyEvent.KEYCODE_PROG_YELLOW: nextAudio(); return true;
            case KeyEvent.KEYCODE_MEDIA_STOP: finish(); return true;
            case KeyEvent.KEYCODE_GUIDE: case KeyEvent.KEYCODE_MENU: fill(); showBar(12000); return true;
            default: break;
        }
        if (ok && urls.length > 1) { listShow(true); fill(); showBar(4000); return true; }
        if (ok || code == KeyEvent.KEYCODE_INFO) { fill(); showBar(6000); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (live == this) live = null;
        net.removeCallbacksAndMessages(null); h.removeCallbacksAndMessages(null); zz.removeCallbacksAndMessages(null);
        Scan.paused = false;
        try { if (player != null) { player.release(); } } catch (Throwable ignored) { }
        player = null;
        finish();
    }
}
