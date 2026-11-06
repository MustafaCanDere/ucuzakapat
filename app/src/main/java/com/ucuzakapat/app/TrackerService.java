package com.ucuzakapat.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On plan servisi: uygulamanin arka planda oldurulmesini zorlastirir (ozellikle
 * Xiaomi/Huawei/Samsung gibi agresif pil yoneticisi olan telefonlarda) ve
 * kontrolu calistirir. Her kontrolden sonra bir sonraki alarm kurulur.
 */
public class TrackerService extends Service {
    public static final String ACTION_CHECK = "com.ucuzakapat.app.CHECK";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    public static void start(Context c, boolean checkNow) {
        Intent i = new Intent(c, TrackerService.class);
        if (checkNow) i.setAction(ACTION_CHECK);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            // Arka plandan servis baslatilamadiysa en azindan alarmi kur
            Scheduler.scheduleNext(c);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, TrackerService.class));
        Scheduler.cancel(c);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notifier.createChannels(this);
        goForeground(statusText());
    }

    private void goForeground(String text) {
        Notification n = Notifier.serviceNotification(this, text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(Notifier.ID_SERVICE, n);
        }
    }

    private String statusText() {
        int count = Store.load(this).size();
        long last = Store.lastRun(this);
        String t = last == 0 ? "henüz yok"
                : new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(last));
        return count + " ürün • her " + Store.intervalMinutes(this) + " dk • son kontrol: " + t;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(statusText());
        boolean checkNow = intent == null || ACTION_CHECK.equals(intent.getAction());
        if (checkNow) {
            runCheck(getApplicationContext());
        } else {
            Scheduler.scheduleNext(this);
        }
        return START_STICKY;
    }

    private void runCheck(final Context app) {
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UcuzaKapat:check");
        wl.acquire(15 * 60_000L);
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Checker.checkAll(app);
                } finally {
                    Scheduler.scheduleNext(app);
                    try {
                        NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
                        nm.notify(Notifier.ID_SERVICE, Notifier.serviceNotification(app, statusText()));
                    } catch (Exception ignored) {
                    }
                    if (wl.isHeld()) wl.release();
                }
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
