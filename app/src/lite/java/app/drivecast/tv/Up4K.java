package app.drivecast.tv;

import android.content.Context;
import androidx.media3.common.Effect;
import java.util.List;

/** 🪶 Lite: no picture enhancement (the full edition's GPU upscaling needs Media3's effect library, left out of Lite).
 *  The player asks for it inside a try and keeps the plain picture when this says no. */
final class Up4K {
    static int screenLines(Context c) { return 1080; }
    static List<Effect> effects(Context c) { throw new UnsupportedOperationException("Lite has no picture enhancement"); }
}
