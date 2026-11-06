package com.ucuzakapat.app;

import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tum urunlerin fiyatini kontrol eder, degisiklik varsa bildirim atar. */
public final class Checker {
    public static final String ACTION_UPDATED = "com.ucuzakapat.app.UPDATED";
    private static final Object RUN_LOCK = new Object();

    private Checker() {}

    /** Arka plan thread'inde cagrilmali. */
    public static void checkAll(Context ctx) {
        Context c = ctx.getApplicationContext();
        synchronized (RUN_LOCK) {
            List<Product> list = Store.load(c);
            for (Product stale : list) {
                // Kontrol suresince kullanici urunu silmis/guncellemis olabilir: en guncel halini al
                Product p = Store.get(c, stale.id);
                if (p == null) continue;
                checkOne(c, p);
                Store.update(c, p);
                c.sendBroadcast(new Intent(ACTION_UPDATED).setPackage(c.getPackageName()));
            }
            Store.setLastRun(c, System.currentTimeMillis());
            Updater.backgroundCheck(c);
            c.sendBroadcast(new Intent(ACTION_UPDATED).setPackage(c.getPackageName()));
        }
    }

    public static void checkOne(Context c, Product p) {
        long now = System.currentTimeMillis();
        PriceFetcher.Result r;
        try {
            r = PriceFetcher.fetch(c, p.url);
        } catch (Exception e) {
            p.lastCheck = now;
            p.lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return;
        }
        apply(c, p, r);
    }

    /** Okunan sonucu urune isler, gerekiyorsa bildirim atar (uygulama ici tarayici da kullanir). */
    public static void apply(Context c, Product p, PriceFetcher.Result r) {
        long now = System.currentTimeMillis();
        p.lastCheck = now;
        p.lastError = null;
        if (r.name != null && !r.name.isEmpty()) p.name = r.name;
        if (r.currency != null) p.currency = r.currency;
        p.originalPrice = r.originalPrice;

        boolean firstTime = p.currentPrice <= 0;
        double old = p.currentPrice;

        // yeni kampanyalari bul
        List<String> added = new ArrayList<String>();
        if (!firstTime) {
            for (String s : r.campaigns) {
                if (!containsIgnoreCase(p.seenCampaigns, s)) added.add(s);
            }
        }
        p.campaigns = r.campaigns;
        for (String s : r.campaigns) {
            if (!containsIgnoreCase(p.seenCampaigns, s)) p.seenCampaigns.add(s);
        }
        while (p.seenCampaigns.size() > 100) p.seenCampaigns.remove(0);

        if (firstTime) {
            p.currentPrice = r.price;
            p.firstPrice = r.price;
            p.lowestPrice = r.price;
            p.lastChange = now;
            return;
        }

        // 1 kurustan kucuk oynamalari yok say
        if (Math.abs(r.price - old) >= 0.01) {
            p.previousPrice = old;
            p.currentPrice = r.price;
            p.lastChange = now;
            if (r.price < old) {
                Notifier.priceDrop(c, p, old);
            }
            if (p.lowestPrice <= 0 || r.price < p.lowestPrice) p.lowestPrice = r.price;
            if (r.price < old) added.clear(); // indirim bildirimi kampanyalari zaten gosteriyor
        }
        if (!added.isEmpty()) Notifier.newCampaigns(c, p, added);
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        Locale tr = new Locale("tr");
        String k = s.toLowerCase(tr);
        for (String x : list) if (x.toLowerCase(tr).equals(k)) return true;
        return false;
    }
}
