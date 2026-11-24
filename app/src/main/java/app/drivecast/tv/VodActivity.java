package app.drivecast.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 🎞️ the media player for episodes and films (not the channels' player): its own progress bar, ◀ ▶ wind 10 s, OK pause / play,
 * ▲ ▼ the episode before / after, BACK leaves. Starts where the episode stopped and tells the page where it stops (vodPos), so
 * «كمّل المشاهدة» and the next episode keep working. Media3's own controls: light and made for the remote.
 */
public class VodActivity extends Activity {
    private ExoPlayer player; private PlayerView view; private TextView title;
    private final List<String> urls = new ArrayList<>(), names = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());

    static Intent of(Context c, String json, int index) {
        return new Intent(c, VodActivity.class).putExtra("items", json).putExtra("index", index).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setControllerShowTimeoutMs(3500); view.setShowNextButton(true); view.setShowPreviousButton(true);
        view.setShowRewindButton(true); view.setShowFastForwardButton(true); view.setKeepScreenOn(true);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        title = new TextView(this); title.setTextColor(Color.WHITE); title.setTextSize(22); title.setShadowLayer(8, 0, 2, 0xE0000000);
        title.setPadding(48, 32, 48, 0); title.setGravity(Gravity.RIGHT);
        root.addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        setContentView(root);

        int index = getIntent().getIntExtra("index", 0); long start = 0;
        List<MediaItem> items = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(getIntent().getStringExtra("items"));
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.getJSONObject(k);
                urls.add(o.optString("url")); names.add(o.optString("name"));
                if (k == index) start = o.optLong("st", 0);
                items.add(new MediaItem.Builder().setUri(o.optString("url"))
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle(o.optString("name")).build()).build());
            }
        } catch (Exception e) { finish(); return; }
        if (items.isEmpty()) { finish(); return; }

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 12) DriveCastTV").setConnectTimeoutMs(15000).setReadTimeoutMs(20000);
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(http))
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build();
        view.setPlayer(player);
        player.setMediaItems(items, Math.max(0, Math.min(index, items.size() - 1)), start > 0 ? start : C.TIME_UNSET);
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(MediaItem m, int why) { report(); showTitle(); }
            @Override public void onPlayerError(PlaybackException e) { title.setText("⚠️ ما اشتغلت هالحلقة — جرّب التالية (▼)"); title.setVisibility(View.VISIBLE); }
        });
        player.prepare(); player.play();
        showTitle();
        h.postDelayed(tick, 15000);
    }

    private final Runnable hideTitle = () -> title.setVisibility(View.GONE);
    private void showTitle() {
        if (player == null) return; int k = player.getCurrentMediaItemIndex();
        title.setText(k >= 0 && k < names.size() ? names.get(k) : ""); title.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideTitle); h.postDelayed(hideTitle, 4000);
    }

    // where it is, every 15 s and on leaving: the page keeps it per series (vodPos)
    private final Runnable tick = new Runnable() { @Override public void run() { report(); h.postDelayed(this, 15000); } };
    private void report() {
        if (player == null) return; int k = player.getCurrentMediaItemIndex(); long dur = player.getDuration();
        if (k < 0 || k >= urls.size() || dur <= 0 || dur == C.TIME_UNSET) return;
        String js = "window.vodPos && vodPos(" + JSONObject.quote(urls.get(k)) + "," + player.getCurrentPosition() + "," + dur + ")";
        HomeActivity a = HomeActivity.alive; if (a != null) a.runOnUiThread(() -> a.run(js));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (player != null && e.getAction() == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible()) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: player.seekBack(); view.showController(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: player.seekForward(); view.showController(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (player.isPlaying()) player.pause(); else player.play(); view.showController(); return true;
                case KeyEvent.KEYCODE_DPAD_UP: if (player.hasPreviousMediaItem()) { player.seekToPreviousMediaItem(); } return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); } return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onPause() { super.onPause(); report(); if (player != null) player.pause(); }
    @Override protected void onResume() { super.onResume(); if (player != null) player.play(); }
    @Override protected void onDestroy() { h.removeCallbacksAndMessages(null); report(); if (player != null) { player.release(); player = null; } super.onDestroy(); }
}
