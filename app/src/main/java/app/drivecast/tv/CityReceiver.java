package app.drivecast.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Setup sets the city over adb, without touching the remote:
 * adb shell am broadcast -n app.drivecast.tv/.CityReceiver --es lat 17.01 --es lon 54.09 --ei method 8 --es name "صلالة"
 */
public class CityReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            City.set(c, Double.parseDouble(i.getStringExtra("lat")), Double.parseDouble(i.getStringExtra("lon")),
                    i.getIntExtra("method", BuildConfig.METHOD), i.getStringExtra("name"));
            Adhan.schedule(c);
            setResultData("ok " + City.name(c));
        } catch (Exception e) {
            setResultData("bad: " + e.getMessage());
        }
    }
}
