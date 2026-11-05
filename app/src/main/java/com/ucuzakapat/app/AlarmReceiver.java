package com.ucuzakapat.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

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
            // Servis baslatilamazsa kontrolu dogrudan burada yap
            final PendingResult pr = goAsync();
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UcuzaKapat:alarm");
            wl.acquire(4 * 60_000L);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Checker.checkAll(app);
                    } finally {
                        Scheduler.scheduleNext(app);
                        if (wl.isHeld()) wl.release();
                        pr.finish();
                    }
                }
            }).start();
        }
    }
}
