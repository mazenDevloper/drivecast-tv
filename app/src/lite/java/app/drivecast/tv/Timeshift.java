package app.drivecast.tv;

import android.content.Context;

/** 🪶 Lite: no 30-minute recording — the full edition has it (src/full). */
final class Timeshift {
    static Timeshift start(Context c, String url, String ua, String ref, String org) { return null; }
    static boolean onDisk(Context c) { return false; }
    String fail() { return "lite"; }
    long windowMs() { return 0; }
    String localUrl() { return ""; }
    void stop() { }
}
