package app.drivecast.tv;

import android.content.Context;
import java.io.File;

/** 🪶 Lite: no background recording — the full edition has it (src/full). */
final class Recorder {
    static void init(Context c) { }
    static String add(Context c, String json) { return "err:التسجيل في النسخة الكاملة"; }
    static void del(Context c, String id) { }
    static String list(Context c) { return "[]"; }
    static void watch(Context c, String json) { }
    static void goal(Context c, String key, String title) { }
    static File file(Context c, String id, String name) { return null; }
    static String playUrl(Context c, String id) { return ""; }
}
