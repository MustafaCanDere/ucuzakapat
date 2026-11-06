package com.ucuzakapat.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Takip edilen bir urun. */
public class Product {
    public String id;
    public String url;
    public String name;
    public String currency = "TL";

    public double currentPrice;     // son okunan fiyat (0 = henuz yok)
    public double originalPrice;    // sitenin gosterdigi ustu cizili / liste fiyati (0 = yok)
    public double firstPrice;       // takibe baslandigindaki fiyat
    public double lowestPrice;      // gorulen en dusuk fiyat
    public double previousPrice;    // bir onceki farkli fiyat

    public List<String> campaigns = new ArrayList<String>();
    public List<String> seenCampaigns = new ArrayList<String>(); // bugune kadar gorulen tum kampanyalar

    public long addedAt;
    public long lastCheck;
    public long lastChange;
    public String lastError;        // null = son kontrol basarili

    // Yeni fiyat bir kez gorulunce hemen kabul edilmez; tekrar gorulurse kabul edilir.
    // (Sayfadaki baska bir fiyatin bir kez yanlislikla okunmasi bos bildirim atmasin.)
    public double pendingPrice;
    public int pendingCount;

    /** Sitenin kendi indirim orani (liste fiyatina gore), yoksa 0. */
    public double siteDiscountPercent() {
        if (originalPrice > 0 && currentPrice > 0 && originalPrice > currentPrice) {
            return (originalPrice - currentPrice) * 100.0 / originalPrice;
        }
        return 0;
    }

    /** Takibe baslandigindan beri degisim (+ = ucuzlama). */
    public double sinceStartPercent() {
        if (firstPrice > 0 && currentPrice > 0) {
            return (firstPrice - currentPrice) * 100.0 / firstPrice;
        }
        return 0;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("url", url);
        o.put("name", name);
        o.put("currency", currency);
        o.put("currentPrice", currentPrice);
        o.put("originalPrice", originalPrice);
        o.put("firstPrice", firstPrice);
        o.put("lowestPrice", lowestPrice);
        o.put("previousPrice", previousPrice);
        JSONArray c = new JSONArray();
        for (String s : campaigns) c.put(s);
        o.put("campaigns", c);
        JSONArray sc = new JSONArray();
        for (String s : seenCampaigns) sc.put(s);
        o.put("seenCampaigns", sc);
        o.put("addedAt", addedAt);
        o.put("lastCheck", lastCheck);
        o.put("lastChange", lastChange);
        if (lastError != null) o.put("lastError", lastError);
        o.put("pendingPrice", pendingPrice);
        o.put("pendingCount", pendingCount);
        return o;
    }

    public static Product fromJson(JSONObject o) {
        Product p = new Product();
        p.id = o.optString("id");
        p.url = o.optString("url");
        p.name = o.optString("name", null);
        p.currency = o.optString("currency", "TL");
        p.currentPrice = o.optDouble("currentPrice", 0);
        p.originalPrice = o.optDouble("originalPrice", 0);
        p.firstPrice = o.optDouble("firstPrice", 0);
        p.lowestPrice = o.optDouble("lowestPrice", 0);
        p.previousPrice = o.optDouble("previousPrice", 0);
        JSONArray c = o.optJSONArray("campaigns");
        if (c != null) for (int i = 0; i < c.length(); i++) p.campaigns.add(c.optString(i));
        JSONArray sc = o.optJSONArray("seenCampaigns");
        if (sc != null) for (int i = 0; i < sc.length(); i++) p.seenCampaigns.add(sc.optString(i));
        p.addedAt = o.optLong("addedAt");
        p.lastCheck = o.optLong("lastCheck");
        p.lastChange = o.optLong("lastChange");
        p.lastError = o.has("lastError") ? o.optString("lastError") : null;
        p.pendingPrice = o.optDouble("pendingPrice", 0);
        p.pendingCount = o.optInt("pendingCount", 0);
        return p;
    }
}
