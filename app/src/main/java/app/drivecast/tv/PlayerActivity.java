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
 * channel, OK = the channel's name, Back = home. A channel the player cannot open is offered to any other
 * player app on the TV (VLC, MX...).
 */
public class PlayerActivity extends Activity {
    private VideoView video;
    private TextView label;
    private String[] urls, names;
    private int i;
    private final Handler h = new Handler(Looper.getMainLooper());

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
        video.setOnPreparedListener(mp -> { mp.setLooping(false); video.start(); });
        video.setOnErrorListener((mp, what, extra) -> { offerOtherPlayer(); return true; });
        play();
    }

    private void play() {
        label.setText((i + 1) + " · " + (names != null && i < names.length ? names[i] : ""));
        label.setAlpha(1f);
        h.removeCallbacksAndMessages(null);
        h.postDelayed(() -> label.animate().alpha(0f).setDuration(400), 3500);
        video.setVideoURI(Uri.parse(urls[i]));
    }

    private void offerOtherPlayer() {
        label.setText("هذه القناة لا تعمل هنا… أجرّب مشغّلاً آخر");
        label.setAlpha(1f);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(urls[i]), "video/*"));
        } catch (Exception e) {
            label.setText("هذه القناة لا تعمل الآن ↑↓ لقناة أخرى");
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP) { i = (i + 1) % urls.length; play(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) { i = (i - 1 + urls.length) % urls.length; play(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) { label.setAlpha(1f); h.postDelayed(() -> label.animate().alpha(0f), 3000); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (video != null) video.stopPlayback();
        finish();
    }
}
