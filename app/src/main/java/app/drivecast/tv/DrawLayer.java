package app.drivecast.tv;

// Adapted from noor-tv (MIT, majedphotos): the phone page that writes or draws on the TV.

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Full-screen transparent layer that draws the strokes sent from the phone, live. */
class DrawLayer extends View {
    private static class Stroke {
        final Path path = new Path();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    }

    private final List<Stroke> strokes = new ArrayList<>();

    DrawLayer(Context c) {
        super(c);
    }

    /**
     * One segment from the phone: "#RRGGBB x,y x,y ..." with x and y from 0 to 1. A segment that starts
     * with "+" continues the last stroke instead of starting a new one.
     */
    void add(String seg) {
        boolean cont = seg.startsWith("+");
        String[] parts = (cont ? seg.substring(1) : seg).trim().split(" ");
        if (parts.length < 2) return;
        Stroke s;
        if (cont && !strokes.isEmpty()) {
            s = strokes.get(strokes.size() - 1);
        } else {
            s = new Stroke();
            try {
                s.paint.setColor(Color.parseColor(parts[0]));
            } catch (Exception e) {
                s.paint.setColor(Color.WHITE);
            }
            s.paint.setStyle(Paint.Style.STROKE);
            s.paint.setStrokeWidth(14);
            s.paint.setStrokeCap(Paint.Cap.ROUND);
            s.paint.setStrokeJoin(Paint.Join.ROUND);
            s.paint.setShadowLayer(8, 0, 0, 0x99000000);
            strokes.add(s);
        }
        float w = getWidth(), h = getHeight();
        for (int i = 1; i < parts.length; i++) {
            String[] xy = parts[i].split(",");
            if (xy.length != 2) continue;
            float x, y;
            try {
                x = Float.parseFloat(xy[0]) * w;
                y = Float.parseFloat(xy[1]) * h;
            } catch (NumberFormatException e) {
                continue;
            }
            if (s.path.isEmpty() || (!cont && i == 1)) s.path.moveTo(x, y);
            else s.path.lineTo(x, y);
        }
        invalidate();
    }

    void clear() {
        strokes.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        for (Stroke s : strokes) c.drawPath(s.path, s.paint);
    }
}
