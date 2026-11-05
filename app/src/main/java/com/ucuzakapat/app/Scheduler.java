package com.ucuzakapat.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Bir sonraki kontrolu AlarmManager ile planlar.
 *
 * Neden WorkManager degil? WorkManager'in periyodik islerinde alt sinir 15 dakika.
 * 5 dakikalik aralik icin "exact" alarm + on plan servisi kullaniyoruz.
 * Not: Telefon uzun sure ekrani kapali ve hareketsiz kalirsa (Doze), Android bu
 * alarmlari en fazla ~9-10 dakikada bir calistirir; ekran acildiginda normale doner.
 */
public final class Scheduler {
    private Scheduler() {}

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class);
        return PendingIntent.getBroadcast(c, 42, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void scheduleNext(Context c) {
        long when = System.currentTimeMillis() + Store.intervalMinutes(c) * 60_000L;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent p = pi(c);
        try {
            boolean exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exactOk) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
        }
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi(c));
    }
}
