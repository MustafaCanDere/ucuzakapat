package com.ucuzakapat.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public final class Notifier {
    public static final String CH_DEALS = "indirimler";
    public static final String CH_SERVICE = "takip";
    public static final int ID_SERVICE = 1;

    private static final Locale TR = new Locale("tr", "TR");

    private Notifier() {}

    public static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel deals = new NotificationChannel(CH_DEALS, "Fiyat düşüşleri ve kampanyalar",
                NotificationManager.IMPORTANCE_HIGH);
        deals.setDescription("Takip ettiğin ürün ucuzlayınca veya yeni kampanya çıkınca");
        deals.enableVibration(true);
        nm.createNotificationChannel(deals);
        NotificationChannel svc = new NotificationChannel(CH_SERVICE, "Arka plan takibi",
                NotificationManager.IMPORTANCE_MIN);
        svc.setDescription("Takibin çalıştığını gösteren sessiz bildirim");
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);
    }

    public static String money(double v, String currency) {
        NumberFormat f = NumberFormat.getNumberInstance(TR);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        String cur = currency == null || currency.isEmpty() ? "TL" : currency;
        return f.format(v) + " " + cur;
    }

    public static String pct(double v) {
        NumberFormat f = NumberFormat.getNumberInstance(TR);
        f.setMaximumFractionDigits(1);
        f.setMinimumFractionDigits(0);
        return "%" + f.format(v);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context c, String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
    }

    private static PendingIntent openUrl(Context c, String url, int req) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Fiyat dustu bildirimi. */
    @SuppressWarnings("deprecation")
    public static void priceDrop(Context c, Product p, double oldPrice) {
        double drop = (oldPrice - p.currentPrice) * 100.0 / oldPrice;
        String title = "📉 Ucuzladı: " + shortName(p);
        String line = money(oldPrice, p.currency) + " → " + money(p.currentPrice, p.currency)
                + "  (" + pct(drop) + " indirim)";

        StringBuilder big = new StringBuilder(line);
        if (p.siteDiscountPercent() > 0) {
            big.append("\nSitedeki indirim: ").append(pct(p.siteDiscountPercent()))
                    .append(" (liste fiyatı ").append(money(p.originalPrice, p.currency)).append(")");
        }
        if (p.firstPrice > 0 && p.sinceStartPercent() > 0.05) {
            big.append("\nTakibe başladığından beri: ").append(pct(p.sinceStartPercent())).append(" ucuz");
        }
        if (p.lowestPrice > 0 && p.currentPrice <= p.lowestPrice) {
            big.append("\n🏆 Takip ettiğin en düşük fiyat!");
        }
        if (!p.campaigns.isEmpty()) {
            big.append("\nKampanyalar: ").append(TextUtils.join(" • ", p.campaigns));
        }

        Notification n = builder(c, CH_DEALS)
                .setSmallIcon(R.drawable.ic_stat_price)
                .setContentTitle(title)
                .setContentText(line)
                .setStyle(new Notification.BigTextStyle().bigText(big.toString()))
                .setContentIntent(openUrl(c, p.url, p.id.hashCode()))
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();
        post(c, p.id.hashCode(), n);
    }

    /** Yeni kampanya bildirimi (fiyat degismese de). */
    @SuppressWarnings("deprecation")
    public static void newCampaigns(Context c, Product p, List<String> added) {
        String text = TextUtils.join(" • ", added);
        Notification n = builder(c, CH_DEALS)
                .setSmallIcon(R.drawable.ic_stat_price)
                .setContentTitle("🎁 Yeni kampanya: " + shortName(p))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text
                        + "\nGüncel fiyat: " + money(p.currentPrice, p.currency)))
                .setContentIntent(openUrl(c, p.url, p.id.hashCode() + 1))
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .build();
        post(c, p.id.hashCode() + 1, n);
    }

    public static Notification serviceNotification(Context c, String text) {
        return builder(c, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_price)
                .setContentTitle("Fiyat takibi açık")
                .setContentText(text)
                .setContentIntent(openApp(c))
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private static void post(Context c, int id, Notification n) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(id, n);
        } catch (SecurityException ignored) {
            // bildirim izni verilmemis
        }
    }

    private static String shortName(Product p) {
        String n = p.name == null ? p.url : p.name;
        return n.length() > 60 ? n.substring(0, 57) + "…" : n;
    }
}
