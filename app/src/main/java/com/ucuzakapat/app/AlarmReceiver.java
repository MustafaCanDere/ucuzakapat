package com.ucuzakapat.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarm calinca kontrolu baslatir. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        if (Store.load(app).isEmpty()) return;
        try {
            Intent i = new Intent(app, TrackerService.class).setAction(TrackerService.ACTION_CHECK);
            if (android.os.Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Exception e) {
            // Servis baslatilamazsa (bazi telefonlarda arka plan kisitlamasi) kontrolu
            // JobScheduler'a ver; orada 10 dakikaya kadar calisabilir.
            CheckJob.schedule(app);
            Scheduler.scheduleNext(app);
        }
    }
}
