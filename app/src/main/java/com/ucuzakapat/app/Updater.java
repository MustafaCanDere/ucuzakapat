package com.ucuzakapat.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Uygulama ici guncelleme.
 *
 * GitHub deposundaki version.json dosyasi okunur. Icindeki versionCode yuklu
 * surumden buyukse APK indirilir ve Android'in PackageInstaller'i ile kurulur
 * (Android "Guncelle" onayini sorar, liste ve ayarlar korunur).
 */
public final class Updater {
    public static final String VERSION_URL =
            "https://raw.githubusercontent.com/MustafaCanDere/ucuzakapat/main/version.json";
    private static final String ACTION_INSTALL_STATUS = "com.ucuzakapat.app.INSTALL_STATUS";
    private static final int NOTIF_ID = 77;
    private static final long BG_CHECK_EVERY = 12 * 60 * 60 * 1000L;

    public static final class Info {
        public int versionCode;
        public String versionName;
        public String apkUrl;
        public String notes;
    }

    private Updater() {}

    public static int installedVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Arka plan thread'inde cagrilir. Yeni surum yoksa null. */
    public static Info fetchNewer(Context c) throws IOException {
        String json = new String(get(VERSION_URL + "?t=" + System.currentTimeMillis(), 256 * 1024), "UTF-8");
        try {
            JSONObject o = new JSONObject(json);
            Info i = new Info();
            i.versionCode = o.getInt("versionCode");
            i.versionName = o.optString("versionName", String.valueOf(i.versionCode));
            i.apkUrl = o.getString("apkUrl");
            i.notes = o.optString("notes", "");
            return i.versionCode > installedVersionCode(c) ? i : null;
        } catch (Exception e) {
            throw new IOException("Sürüm bilgisi okunamadı");
        }
    }

    // ------------------------------------------------------------ arka plan

    /** Fiyat kontrolu sirasinda arada bir cagrilir; yeni surum varsa bildirim atar. */
    public static void backgroundCheck(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("updater", Context.MODE_PRIVATE);
        long last = sp.getLong("lastCheck", 0);
        if (System.currentTimeMillis() - last < BG_CHECK_EVERY) return;
        sp.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
        try {
            Info i = fetchNewer(c);
            if (i == null || sp.getInt("notified", 0) == i.versionCode) return;
            sp.edit().putInt("notified", i.versionCode).apply();
            Intent open = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_UPDATE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 7, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            @SuppressWarnings("deprecation")
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, Notifier.CH_DEALS) : new Notification.Builder(c);
            Notification n = b.setSmallIcon(R.drawable.ic_stat_price)
                    .setContentTitle("Ucuza Kapat güncellemesi: " + i.versionName)
                    .setContentText(i.notes.isEmpty() ? "Güncellemek için dokun" : i.notes)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, n);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ arayuz

    /** Uygulama acilinca / menuden. silent=true ise "guncel" mesaji gosterilmez. */
    public static void checkInteractive(final Activity a, final boolean silent) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                Info info = null;
                String err = null;
                try {
                    info = fetchNewer(a);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final Info fi = info;
                final String fe = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) return;
                        if (fi != null) offer(a, fi);
                        else if (!silent) {
                            android.widget.Toast.makeText(a, fe != null ? "Güncelleme kontrol edilemedi: " + fe
                                    : "En güncel sürümü kullanıyorsun 👍", android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private static void offer(final Activity a, final Info i) {
        String msg = "Yeni sürüm: " + i.versionName;
        if (i.notes != null && !i.notes.isEmpty()) msg += "\n\n" + i.notes;
        new AlertDialog.Builder(a)
                .setTitle("Güncelleme var")
                .setMessage(msg)
                .setPositiveButton("Güncelle", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        startUpdate(a, i);
                    }
                })
                .setNegativeButton("Sonra", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private static void startUpdate(final Activity a, final Info i) {
        // Android 8+: "bilinmeyen uygulamalari yukle" izni bu uygulama icin acik olmali
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle("İzin gerekiyor")
                    .setMessage("Güncellemeyi kurabilmem için bir sonraki ekranda \"Bu kaynaktan izin ver\" "
                            + "seçeneğini aç, sonra geri dönüp tekrar Güncelle'ye bas.")
                    .setPositiveButton("Ayarları aç", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        }
                    })
                    .setNegativeButton("Vazgeç", null)
                    .show();
            return;
        }
        final ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage("Güncelleme indiriliyor…");
        pd.setCancelable(false);
        pd.show();
        final Handler ui = new Handler(Looper.getMainLooper());
        final Context app = a.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    byte[] apk = get(i.apkUrl, 100 * 1024 * 1024);
                    install(app, apk);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fe = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Exception ignored) {
                        }
                        if (fe != null) {
                            android.widget.Toast.makeText(app, "Güncelleme başarısız: " + fe,
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private static void install(Context c, byte[] apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream out = s.openWrite("base.apk", 0, apk.length);
            out.write(apk);
            s.fsync(out);
            out.close();
            Intent cb = new Intent(c, InstallReceiver.class).setAction(ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent p = PendingIntent.getBroadcast(c, id, cb, flags);
            s.commit(p.getIntentSender());
        } catch (IOException e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }

    /** Kurulum durumunu alir; kullanici onayi gerekiyorsa Android'in onay ekranini acar. */
    public static class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                android.widget.Toast.makeText(c, "Güncelleme kurulamadı" + (m != null ? ": " + m : ""),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------ indirme

    private static byte[] get(String startUrl, int maxBytes) throws IOException {
        String url = startUrl;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(20000);
            con.setReadTimeout(60000);
            con.setRequestProperty("User-Agent", "UcuzaKapat-Updater");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) throw new IOException("Yönlendirme hatası");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                con.disconnect();
                throw new IOException("HTTP " + code);
            }
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > maxBytes) throw new IOException("Dosya çok büyük");
                }
            } finally {
                in.close();
                con.disconnect();
            }
            return bos.toByteArray();
        }
        throw new IOException("Çok fazla yönlendirme");
    }
}
