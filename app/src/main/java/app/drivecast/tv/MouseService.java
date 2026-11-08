package app.drivecast.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * The phone page's touchpad: a pointer drawn over every app, moved by the phone; a tap, long press or scroll is a
 * gesture at the pointer (Android's accessibility gestures, no root). Back / home / recent apps are global actions.
 * Turned on once in Settings → Accessibility (or by the setup over adb). It reads nothing on the screen.
 */
public class MouseService extends AccessibilityService {
    static volatile MouseService on;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private View cursor;
    private float x, y;
    private int w, h;
    private final Runnable hide = () -> { if (cursor != null) cursor.animate().alpha(0f).setDuration(400).start(); };

    @Override
    protected void onServiceConnected() {
        on = this;
        wm = getSystemService(WindowManager.class);
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        w = m.widthPixels; h = m.heightPixels; x = w / 2f; y = h / 2f;
        int size = Math.max(36, w / 40);
        cursor = new Pointer(this);
        lp = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        place();
        try { wm.addView(cursor, lp); } catch (Exception ignored) { cursor = null; }
        if (cursor != null) cursor.setAlpha(0f);
    }

    private void place() { lp.x = Math.round(x); lp.y = Math.round(y); }

    private void showCursor() {
        if (cursor == null) return;
        cursor.animate().cancel(); cursor.setAlpha(1f);
        main.removeCallbacks(hide); main.postDelayed(hide, 6000);
    }

    /** one line from the phone: "m dx dy", "t", "l", "s steps", "b", "h", "r" */
    void command(String line) {
        String[] p = line.trim().split("\\s+");
        if (p.length == 0) return;
        switch (p[0]) {
            case "m":
                if (p.length < 3) return;
                try {
                    x = Math.max(0, Math.min(w - 2, x + Float.parseFloat(p[1]) * w / 1920f));
                    y = Math.max(0, Math.min(h - 2, y + Float.parseFloat(p[2]) * h / 1080f));
                } catch (NumberFormatException e) { return; }
                place();
                if (cursor != null) try { wm.updateViewLayout(cursor, lp); } catch (Exception ignored) { }
                showCursor();
                break;
            case "t": tap(60); break;
            case "l": tap(700); break;
            case "s":
                try { scroll(Integer.parseInt(p[1])); } catch (Exception ignored) { }
                break;
            case "b": performGlobalAction(GLOBAL_ACTION_BACK); break;
            case "h": performGlobalAction(GLOBAL_ACTION_HOME); break;
            case "r": performGlobalAction(GLOBAL_ACTION_RECENTS); break;
        }
    }

    private void tap(long ms) {
        showCursor();
        Path path = new Path();
        path.moveTo(x + 2, y + 2);   // the arrow's tip
        try {
            dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, ms)).build(), null, null);
        } catch (Exception ignored) { }
    }

    /** steps > 0 scrolls the page down (the finger goes up), < 0 up */
    private void scroll(int steps) {
        showCursor();
        float y0 = Math.max(h * .25f, Math.min(h * .75f, y)), dy = Math.max(-h * .45f, Math.min(h * .45f, steps * h / 18f));
        Path path = new Path();
        path.moveTo(x, y0);
        path.lineTo(x, y0 - dy);
        try {
            dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, 220)).build(), null, null);
        } catch (Exception ignored) { }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        on = null;
        if (cursor != null) try { wm.removeView(cursor); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /** a white arrow with a dark edge, readable on any picture */
    static class Pointer extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        Pointer(Context c) {
            super(c);
            fill.setColor(Color.WHITE);
            edge.setColor(0xCC000000); edge.setStyle(Paint.Style.STROKE); edge.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas c) {
            float s = getWidth();
            edge.setStrokeWidth(s / 14f);
            Path a = new Path();
            a.moveTo(s * .08f, s * .06f); a.lineTo(s * .08f, s * .82f); a.lineTo(s * .3f, s * .62f);
            a.lineTo(s * .46f, s * .94f); a.lineTo(s * .6f, s * .87f); a.lineTo(s * .44f, s * .57f); a.lineTo(s * .74f, s * .55f); a.close();
            c.drawPath(a, fill); c.drawPath(a, edge);
        }
    }
}
