package app.drivecast.tv;

import android.content.Context;
import android.content.SharedPreferences;

/** The city of the prayer times: set by Claude during setup (adb broadcast) or from the home screen's settings. */
final class City {
    private static SharedPreferences p(Context c) { return c.getSharedPreferences("city", Context.MODE_PRIVATE); }
    static double lat(Context c) { return Double.parseDouble(p(c).getString("lat", BuildConfig.LAT)); }
    static double lon(Context c) { return Double.parseDouble(p(c).getString("lon", BuildConfig.LON)); }
    static int method(Context c) { return p(c).getInt("method", BuildConfig.METHOD); }
    static String name(Context c) { return p(c).getString("name", BuildConfig.CITY); }
    /** Salalah uses the user's own table (DriveCast's prayer-times bin) instead of Aladhan */
    static boolean mine(Context c) { return Math.abs(lat(c) - 17.02) < 0.2 && Math.abs(lon(c) - 54.09) < 0.2; }
    /** a key for caches that depend on the city */
    static String key(Context c) { return lat(c) + "," + lon(c) + "," + method(c); }
    static void set(Context c, double lat, double lon, int method, String name) {
        p(c).edit().putString("lat", String.valueOf(lat)).putString("lon", String.valueOf(lon)).putInt("method", method)
                .putString("name", name == null ? "" : name).apply();
    }
}
