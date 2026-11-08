package app.drivecast.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;

/**
 * IPTV on the TV: a full-screen player for a list of channels. Up / down on the remote = the next / previous
 * channel, OK = the channel's name, Back = home. A stream that drops or stalls is reconnected quietly, up to 6 times
 * in a row; only then is another player app offered (OK opens it: VLC, MX…).
 */
public class PlayerActivity extends Activity {
    private VideoView video;
    private TextView label;
    private String[] urls, names;
    private int i;
    private final Handler h = new Handler(Looper.getMainLooper());      // the name label
    private final Handler net = new Handler(Looper.getMainLooper());    // reconnects and the stall watchdog
    private static final int TRIES = 6;
    private int errors;
    private boolean offered;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        urls = getIntent().getStringArrayExtra("urls");
        names = getIntent().getStringArrayExtra("names");
        i = getIntent().getIntExtra("i", 0);
        if (urls == null || urls.length == 0) { finish(); return; }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(26);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(40, 20, 40, 20);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC0A0E13);
        bg.setCornerRadius(48);
        label.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = 48;
        root.addView(label, lp);
        setContentView(root);
        video.setOnPreparedListener(mp -> {
            mp.setLooping(false); video.start();
            net.removeCallbacksAndMessages(null);
            net.postDelayed(() -> errors = 0, 20000);   // 20 s of picture: the channel is fine again
            mp.setOnInfoListener((m, what, extra) -> {
                if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START) net.postDelayed(stall, 12000);
                if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END) net.removeCallbacks(stall);
                return false;
            });
        });
        video.setOnErrorListener((mp, what, extra) -> { retry(); return true; });
        video.setOnCompletionListener(mp -> retry());   // a live stream that "ends" has dropped
        play();
    }

    private final Runnable stall = this::retry;

    /** a drop or a stall: reconnect the same channel, a little later each time; after 6 in a row, offer another player */
    private void retry() {
        net.removeCallbacksAndMessages(null);
        errors++;
        if (errors >= TRIES) { offerOtherPlayer(); return; }
        label.setText("إعادة الاتصال… (" + errors + " من " + TRIES + ")");
        label.setAlpha(1f);
        net.postDelayed(() -> { video.stopPlayback(); video.setVideoURI(Uri.parse(urls[i])); }, 1200L * errors);
    }

    private void play() {
        errors = 0; offered = false;
        net.removeCallbacksAndMessages(null);
        label.setText((i + 1) + " · " + (names != null && i < names.length ? names[i] : ""));
        label.setAlpha(1f);
        h.removeCallbacksAndMessages(null);
        h.postDelayed(() -> label.animate().alpha(0f).setDuration(400), 3500);
        video.setVideoURI(Uri.parse(urls[i]));
    }

    private void offerOtherPlayer() {
        offered = true;
        h.removeCallbacksAndMessages(null);
        label.setText("القناة ما اشتغلت بعد ٦ محاولات · OK لفتحها في مشغّل آخر · ↑↓ قناة ثانية");
        label.setAlpha(1f);
    }

    private void openOtherPlayer() {
        try {
            Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(urls[i]), "video/*");
            startActivity(Intent.createChooser(v, "شغّل القناة في…"));
        } catch (Exception e) {
            label.setText("ما فيه مشغّل ثاني على التلفزيون · ↑↓ قناة ثانية");
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP) { i = (i + 1) % urls.length; play(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) { i = (i - 1 + urls.length) % urls.length; play(); return true; }
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (ok && e.getRepeatCount() == 8) { openOtherPlayer(); return true; }   // OK held down ≈ ½ s: another player
        if (ok && e.getRepeatCount() > 0) return true;
        if (ok && offered) { openOtherPlayer(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) { label.setAlpha(1f); h.postDelayed(() -> label.animate().alpha(0f), 3000); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onStop() {
        super.onStop();
        net.removeCallbacksAndMessages(null); h.removeCallbacksAndMessages(null);
        if (video != null) video.stopPlayback();
        finish();
    }
}
