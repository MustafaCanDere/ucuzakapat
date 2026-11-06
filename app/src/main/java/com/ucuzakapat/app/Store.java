package com.ucuzakapat.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Urun listesini ve ayarlari telefonda saklar. */
public final class Store {
    private static final String PREFS = "ucuzakapat";
    private static final String KEY_PRODUCTS = "products";
    private static final String KEY_INTERVAL = "intervalMin";
    private static final String KEY_LAST_RUN = "lastRun";
    private static final Object LOCK = new Object();

    private Store() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Product> load(Context c) {
        synchronized (LOCK) {
            List<Product> list = new ArrayList<Product>();
            try {
                JSONArray arr = new JSONArray(prefs(c).getString(KEY_PRODUCTS, "[]"));
                for (int i = 0; i < arr.length(); i++) list.add(Product.fromJson(arr.getJSONObject(i)));
            } catch (Exception ignored) {
            }
            return list;
        }
    }

    public static void save(Context c, List<Product> list) {
        synchronized (LOCK) {
            try {
                JSONArray arr = new JSONArray();
                for (Product p : list) arr.put(p.toJson());
                prefs(c).edit().putString(KEY_PRODUCTS, arr.toString()).apply();
            } catch (Exception ignored) {
            }
        }
    }

    /** Tek bir urunu gunceller (kontrol sirasinda kullanici urun silmis olabilir). */
    public static void update(Context c, Product updated) {
        synchronized (LOCK) {
            List<Product> list = load(c);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(updated.id)) {
                    list.set(i, updated);
                    save(c, list);
                    return;
                }
            }
        }
    }

    public static Product get(Context c, String id) {
        for (Product p : load(c)) if (p.id.equals(id)) return p;
        return null;
    }

    public static Product add(Context c, String url) {
        synchronized (LOCK) {
            List<Product> list = load(c);
            for (Product p : list) if (p.url.equals(url)) return null; // zaten var
            Product p = new Product();
            p.id = UUID.randomUUID().toString();
            p.url = url;
            p.addedAt = System.currentTimeMillis();
            list.add(0, p);
            save(c, list);
            return p;
        }
    }

    public static void remove(Context c, String id) {
        synchronized (LOCK) {
            List<Product> list = load(c);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(id)) {
                    list.remove(i);
                    break;
                }
            }
            save(c, list);
        }
    }

    public static int intervalMinutes(Context c) {
        return prefs(c).getInt(KEY_INTERVAL, 5);
    }

    public static void setIntervalMinutes(Context c, int m) {
        prefs(c).edit().putInt(KEY_INTERVAL, m).apply();
    }

    public static long lastRun(Context c) {
        return prefs(c).getLong(KEY_LAST_RUN, 0);
    }

    public static void setLastRun(Context c, long t) {
        prefs(c).edit().putLong(KEY_LAST_RUN, t).apply();
    }
}
