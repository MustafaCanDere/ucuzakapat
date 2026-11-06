package com.ucuzakapat.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Bir urun sayfasini indirip fiyat, liste fiyati, isim ve kampanyalari cikarir.
 *
 * Sira:
 *  1) Siteye ozel kurallar (Trendyol, Hepsiburada, Amazon, n11)
 *  2) Sayfadaki schema.org JSON-LD "Product" verisi (cogu e-ticaret sitesinde var)
 *  3) meta etiketleri (product:price:amount, og:price:amount, itemprop=price)
 */
public final class PriceFetcher {

    public static final class Result {
        public String name;
        public double price;
        public double originalPrice;
        public String currency = "TL";
        public List<String> campaigns = new ArrayList<String>();
    }

    private static final String FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
    private static volatile String UA = FALLBACK_UA;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private PriceFetcher() {}

    /**
     * Telefonun gercek Chrome surumunu kullan: hem bot korumalari eski/sahte surumu
     * yakalamasin, hem de uygulama ici tarayicida alinan "dogrulandi" cerezleri
     * (ayni tarayici kimligiyle) duz isteklerde de gecerli olsun.
     */
    public static String userAgent(android.content.Context ctx) {
        try {
            String ua = android.webkit.WebSettings.getDefaultUserAgent(ctx);
            if (ua != null && ua.length() > 20) {
                UA = ua.replace("; wv)", ")").replace("Version/4.0 ", "");
            }
        } catch (Throwable ignored) {
        }
        return UA;
    }

    /** Bu siteler duz HTTP istegini neredeyse her zaman engeller; dogrudan tarayiciyla ac. */
    private static boolean browserFirst(String url) {
        String h = hostOf(url);
        return h.contains("hepsiburada") || h.contains("mediamarkt") || h.contains("teknosa")
                || h.contains("vatanbilgisayar") || h.contains("ciceksepeti") || h.contains("boyner");
    }

    /**
     * Sirayla: (Trendyol ise) Trendyol'un kendi urun API'si, duz HTTP, gizli tarayici.
     * Hepsi basarisiz olursa en anlamli hata mesaji dondurulur.
     */
    public static Result fetch(android.content.Context ctx, String url) throws IOException {
        userAgent(ctx);
        String firstError = null;
        if (hostOf(url).contains("trendyol") || hostOf(url).contains("ty.gl")) {
            try {
                Result r = trendyolApi(url);
                if (r != null && r.price > 0) return r;
            } catch (Exception ignored) {
            }
        }
        if (!browserFirst(url)) {
            try {
                return fetch(url);
            } catch (IOException e) {
                firstError = e.getMessage();
            }
        }
        try {
            return WebFetcher.fetch(ctx, url);
        } catch (IOException e) {
            // Hepsiburada: urun sayfasi engellenirse, ayni urunu arama sayfasindan oku
            // (arama sonuclari sunucuda hazirlanir ve koruma daha gevsek)
            String sku = hepsiburadaSku(url);
            if (sku != null) {
                try {
                    Result r = hepsiburadaSearch(ctx, sku);
                    if (r != null && r.price > 0) return r;
                } catch (Exception ignored) {
                }
            }
            String m = e.getMessage();
            if (firstError != null && m != null && m.startsWith("Sayfa açılamadı")) m = firstError;
            throw new IOException(m + " — ürüne dokunup uygulama içinde bir kez aç, sonra tekrar dene");
        }
    }

    public static Result fetch(String url) throws IOException {
        String html = download(url);
        Result r = parse(url, html);
        if (r.price <= 0) {
            if (looksBlocked(html)) {
                throw new IOException("Site bot korumasına takıldı");
            }
            throw new IOException("Sayfa açıldı ama fiyat bulunamadı");
        }
        return r;
    }

    // ---- Hepsiburada arama sayfasi yedegi
    private static final Pattern HB_SKU = Pattern.compile("-p[m]?-([A-Za-z0-9]{8,20})");

    /** ornek: .../kulaklik-p-HBCV00004X8Y9Z  ->  HBCV00004X8Y9Z */
    static String hepsiburadaSku(String url) {
        if (!hostOf(url).contains("hepsiburada")) return null;
        String path = url;
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        Matcher m = HB_SKU.matcher(path);
        String last = null;
        while (m.find()) last = m.group(1);
        return last == null ? null : last.toUpperCase(Locale.ROOT);
    }

    static Result hepsiburadaSearch(android.content.Context ctx, String sku) throws Exception {
        String searchUrl = "https://www.hepsiburada.com/ara?q=" + sku;
        // 1) duz HTTP
        try {
            String html = download(searchUrl);
            Result r = priceNearSku(html, sku);
            if (r != null) return r;
        } catch (IOException ignored) {
        }
        // 2) tarayici: arama sayfasinda tek urun cikar, ekrandaki fiyat o urunundur
        Result r = WebFetcher.fetch(ctx, searchUrl);
        if (r != null && r.price > 0) {
            r.name = null; // arama sayfasinin basligi urun adi degil; mevcut adi koru
            return r;
        }
        return null;
    }

    private static final Pattern TL_PRICE = Pattern.compile("(\\d{1,3}(?:\\.\\d{3})*,\\d{2})\\s*(?:TL|₺)");
    private static final Pattern JSON_PRICE = Pattern.compile(
            "\"(?:finalPrice|price|currentPrice|salePrice|value)\"\\s*:\\s*\"?(\\d+(?:[.,]\\d+)?)");

    /** HTML'de SKU'nun gectigi yerin hemen yakinindaki fiyati bulur. */
    static Result priceNearSku(String html, String sku) {
        String h = html;
        String up = h.toUpperCase(Locale.ROOT);
        int idx = 0;
        while ((idx = up.indexOf(sku, idx)) >= 0) {
            int a = Math.max(0, idx - 1500), b = Math.min(h.length(), idx + 5000);
            String win = h.substring(a, b);
            Matcher m = TL_PRICE.matcher(win);
            double best = 0;
            if (m.find()) best = parseNumber(m.group(1));
            if (best <= 0) {
                Matcher j = JSON_PRICE.matcher(win);
                while (j.find()) {
                    double v = parseNumber(j.group(1));
                    if (v > 1) {
                        best = v;
                        break;
                    }
                }
            }
            if (best > 0) {
                Result r = new Result();
                r.price = best;
                findCampaigns(win, r);
                return r;
            }
            idx += sku.length();
        }
        return null;
    }

    // ---- Trendyol urun API'si (sayfa yerine JSON; bot korumasina daha az takilir)
    private static final Pattern TY_ID = Pattern.compile("-p-(\\d+)");

    static Result trendyolApi(String url) throws Exception {
        Matcher m = TY_ID.matcher(url);
        if (!m.find()) {
            // ty.gl kisa linki: once gercek adrese yonlen
            String finalUrl = resolve(url);
            m = TY_ID.matcher(finalUrl);
            if (!m.find()) return null;
        }
        String id = m.group(1);
        String[] endpoints = {
                "https://apigw.trendyol.com/discovery-web-productgw-service/api/productDetail/" + id
                        + "?storefrontId=1&culture=tr-TR&linearVariants=true",
                "https://public.trendyol.com/discovery-web-productgw-service/api/productDetail/" + id
                        + "?storefrontId=1&culture=tr-TR&linearVariants=true",
        };
        for (String ep : endpoints) {
            String json;
            try {
                json = download(ep);
            } catch (IOException e) {
                continue;
            }
            Result r = new Result();
            try {
                JSONObject root = new JSONObject(json);
                JSONObject res = root.optJSONObject("result");
                if (res == null) res = root;
                r.name = res.optString("name", null);
                JSONObject price = res.optJSONObject("price");
                if (price != null) {
                    double disc = val(price, "discountedPrice");
                    double sell = val(price, "sellingPrice");
                    double orig = val(price, "originalPrice");
                    r.price = disc > 0 ? disc : sell;
                    double list = Math.max(orig, sell);
                    if (list > r.price) r.originalPrice = list;
                }
                JSONArray promos = res.optJSONArray("promotions");
                if (promos != null) {
                    for (int i = 0; i < promos.length(); i++) {
                        JSONObject pr = promos.optJSONObject(i);
                        if (pr == null) continue;
                        String t = pr.optString("text", pr.optString("name", pr.optString("shortName", "")));
                        addCampaign(r, t);
                    }
                }
            } catch (Exception e) {
                r = parse(url, json);
            }
            if (r.price > 0) {
                findCampaigns(json, r);
                return r;
            }
        }
        return null;
    }

    private static double val(JSONObject price, String key) {
        Object o = price.opt(key);
        if (o instanceof JSONObject) return ((JSONObject) o).optDouble("value", 0);
        if (o instanceof Number) return ((Number) o).doubleValue();
        return 0;
    }

    /** Yonlendirmeleri takip edip son adresi dondurur (kisa linkler icin). */
    static String resolve(String startUrl) throws IOException {
        String url = startUrl;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection con = open(url);
            con.setRequestMethod("GET");
            int code = con.getResponseCode();
            String loc = con.getHeaderField("Location");
            con.disconnect();
            if (code >= 300 && code < 400 && loc != null) {
                url = new URL(new URL(url), loc).toString();
            } else {
                return url;
            }
        }
        return url;
    }

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(false);
        con.setConnectTimeout(20000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", UA);
        con.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8");
        con.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.6");
        con.setRequestProperty("Accept-Encoding", "gzip");
        // Uygulama ici tarayicida alinmis cerezleri (bot dogrulamasi dahil) gonder
        try {
            String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) con.setRequestProperty("Cookie", cookies);
        } catch (Throwable ignored) {
        }
        return con;
    }

    // ---------------------------------------------------------------- indirme

    static String download(String startUrl) throws IOException {
        String url = startUrl;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection con = open(url);
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) throw new IOException("Yonlendirme hatasi");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            if ("gzip".equalsIgnoreCase(con.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            try {
                while ((n = in.read(buf)) > 0 && bos.size() < MAX_BYTES) bos.write(buf, 0, n);
            } finally {
                in.close();
                con.disconnect();
            }
            String body = new String(bos.toByteArray(), "UTF-8");
            if (code >= 400) {
                // Bazi siteler 403 ile birlikte yine de sayfayi dondurur; fiyat varsa kullan
                Result probe = parse(url, body);
                if (probe.price > 0) return body;
                throw new IOException("Site erişimi reddetti (HTTP " + code + ")");
            }
            return body;
        }
        throw new IOException("Cok fazla yonlendirme");
    }

    /**
     * Sadece gercek bot/dogrulama sayfalarini yakalar. Normal sayfalarda da "captcha",
     * "robot" gibi kelimeler gecer (giris formu, robot supurge...), onlara bakmiyoruz.
     */
    static boolean looksBlocked(String html) {
        String h = html.toLowerCase(new Locale("tr"));
        String title = "";
        Matcher m = Pattern.compile("<title[^>]*>([^<]*)</title>").matcher(h);
        if (m.find()) title = m.group(1);
        String[] titles = {"just a moment", "bir dakika", "attention required", "access denied", "erişim engellendi",
                "pardon our interruption", "robot", "captcha", "doğrulama", "security check", "güvenlik kontrol",
                "are you a human", "request rejected", "forbidden"};
        for (String t : titles) if (title.contains(t)) return true;
        return h.contains("cf-challenge") || h.contains("challenge-platform") || h.contains("_incapsula_")
                || h.contains("px-captcha") || h.contains("/_sec/cp_challenge") || h.contains("sec-if-cpt-container")
                || (h.length() < 3000 && (h.contains("captcha") || h.contains("robot")));
    }

    // ---------------------------------------------------------------- ayrıştırma

    static Result parse(String url, String html) {
        Result r = new Result();
        String host = hostOf(url);

        if (host.contains("trendyol")) parseTrendyol(html, r);
        else if (host.contains("hepsiburada")) parseHepsiburada(html, r);
        else if (host.contains("amazon.")) parseAmazon(html, r);
        else if (host.contains("n11.com")) parseN11(html, r);

        parseJsonLd(html, r);
        parseMeta(html, r);

        if (r.name == null) r.name = metaContent(html, "og:title");
        if (r.name == null) {
            Matcher m = Pattern.compile("<title[^>]*>([^<]{3,300})</title>", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) r.name = m.group(1);
        }
        if (r.name != null) r.name = cleanText(r.name);

        if (r.originalPrice > 0 && r.originalPrice <= r.price) r.originalPrice = 0;
        findCampaigns(html, r);
        return r;
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    // ---- Trendyol: sayfa icinde JSON state var
    private static void parseTrendyol(String html, Result r) {
        double discounted = jsonValue(html, "discountedPrice");
        double selling = jsonValue(html, "sellingPrice");
        double original = jsonValue(html, "originalPrice");
        double price = discounted > 0 ? discounted : selling;
        if (price > 0) r.price = price;
        double list = Math.max(original, selling);
        if (list > r.price) r.originalPrice = list;
        // Kampanya / promosyon isimleri
        addAllMatches(html, "\"promotions\"\\s*:\\s*\\[(.*?)\\]", "\"(?:name|shortName|text)\"\\s*:\\s*\"([^\"]{3,80})\"", r);
        addAllMatches(html, "\"campaign\"\\s*:\\s*\\{(.*?)\\}", "\"name\"\\s*:\\s*\"([^\"]{3,80})\"", r);
    }

    // ---- Hepsiburada
    private static void parseHepsiburada(String html, Result r) {
        double price = firstNumber(html,
                "\"finalPriceOnSale\"\\s*:\\s*\"?([\\d.,]+)",
                "\"price\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*([\\d.]+)",
                "\"currentPrice\"\\s*:\\s*\"?([\\d.,]+)",
                "data-test-id=\"(?:price-current-price|default-price|checkout-price)\"[^>]*>([^<]+)<");
        if (price > 0) r.price = price;
        double orig = firstNumber(html,
                "\"originalPrice\"\\s*:\\s*\"?([\\d.,]+)",
                "\"oldPrice\"\\s*:\\s*\"?([\\d.,]+)",
                "data-test-id=\"(?:price-prev-price|prev-price)\"[^>]*>([^<]+)<");
        if (orig > 0) r.originalPrice = orig;
        addAllMatches(html, "\"campaigns?\"\\s*:\\s*\\[(.*?)\\]", "\"(?:name|title|text)\"\\s*:\\s*\"([^\"]{3,80})\"", r);
    }

    // ---- Amazon
    private static void parseAmazon(String html, Result r) {
        Matcher m = Pattern.compile(
                "id=\"(?:corePrice_feature_div|corePriceDisplay_desktop_feature_div|apex_desktop|corePriceDisplay_mobile_feature_div)\"[\\s\\S]{0,3000}?class=\"a-offscreen\">([^<]+)<")
                .matcher(html);
        if (m.find()) r.price = parseNumber(m.group(1));
        if (r.price <= 0) {
            Matcher w = Pattern.compile("class=\"a-price-whole\">([\\d.,]+)").matcher(html);
            if (w.find()) {
                String whole = w.group(1).replaceAll("[.,]$", "");
                String frac = "00";
                Matcher f = Pattern.compile("class=\"a-price-fraction\">(\\d+)").matcher(html.substring(w.end()));
                if (f.find()) frac = f.group(1);
                r.price = parseNumber(whole + "," + frac);
            }
        }
        if (r.price <= 0) r.price = firstNumber(html, "\"priceAmount\"\\s*:\\s*([\\d.]+)");
        Matcher lp = Pattern.compile(
                "(?:basisPrice|a-text-price)[\\s\\S]{0,400}?class=\"a-offscreen\">([^<]+)<").matcher(html);
        if (lp.find()) {
            double v = parseNumber(lp.group(1));
            if (v > r.price) r.originalPrice = v;
        }
        Matcher t = Pattern.compile("id=\"productTitle\"[^>]*>\\s*([^<]+)<").matcher(html);
        if (t.find()) r.name = t.group(1);
        Matcher cp = Pattern.compile("(?i)(%\\s?\\d+[^<]{0,40}kupon[^<]{0,40}|kupon[^<]{0,20}uygula[^<]{0,40})").matcher(html);
        while (cp.find() && r.campaigns.size() < 3) addCampaign(r, cp.group(1));
    }

    // ---- n11
    private static void parseN11(String html, Result r) {
        double price = firstNumber(html,
                "\"displayPrice\"\\s*:\\s*\"?([\\d.,]+)",
                "class=\"newPrice\"[^>]*>\\s*<ins[^>]*content=\"([\\d.]+)\"");
        if (price > 0) r.price = price;
        double orig = firstNumber(html, "class=\"oldPrice\"[^>]*>\\s*<del>([^<]+)<");
        if (orig > 0) r.originalPrice = orig;
    }

    // ---- schema.org JSON-LD
    private static void parseJsonLd(String html, Result r) {
        Matcher m = Pattern.compile(
                "<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            String json = m.group(1).trim();
            try {
                Object root = json.startsWith("[") ? new JSONArray(json) : new JSONObject(json);
                walkJsonLd(root, r);
            } catch (Exception ignored) {
            }
        }
    }

    private static void walkJsonLd(Object node, Result r) {
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) walkJsonLd(a.opt(i), r);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        if (o.has("@graph")) walkJsonLd(o.opt("@graph"), r);
        String type = String.valueOf(o.opt("@type"));
        if (type.contains("Product")) {
            if (r.name == null && o.optString("name", "").length() > 0) r.name = o.optString("name");
            Object offers = o.opt("offers");
            readOffers(offers, r);
        }
    }

    private static void readOffers(Object offers, Result r) {
        if (offers instanceof JSONArray) {
            JSONArray a = (JSONArray) offers;
            for (int i = 0; i < a.length(); i++) readOffers(a.opt(i), r);
            return;
        }
        if (!(offers instanceof JSONObject)) return;
        JSONObject of = (JSONObject) offers;
        if (of.has("offers")) readOffers(of.opt("offers"), r);
        double p = parseNumber(of.optString("price", ""));
        if (p <= 0) p = parseNumber(of.optString("lowPrice", ""));
        if (p <= 0) {
            JSONObject spec = of.optJSONObject("priceSpecification");
            if (spec != null) p = parseNumber(spec.optString("price", ""));
        }
        if (p > 0 && r.price <= 0) r.price = p;
        String cur = of.optString("priceCurrency", "");
        if (cur.length() > 0) r.currency = "TRY".equalsIgnoreCase(cur) ? "TL" : cur;
    }

    // ---- meta etiketleri
    private static void parseMeta(String html, Result r) {
        if (r.price > 0) return;
        String[] keys = {"product:price:amount", "og:price:amount", "twitter:data1"};
        for (String k : keys) {
            String v = metaContent(html, k);
            if (v != null) {
                double p = parseNumber(v);
                if (p > 0) {
                    r.price = p;
                    return;
                }
            }
        }
        Matcher m = Pattern.compile("itemprop=[\"']price[\"'][^>]*content=[\"']([^\"']+)[\"']").matcher(html);
        if (m.find()) r.price = parseNumber(m.group(1));
        if (r.price <= 0) {
            m = Pattern.compile("content=[\"']([\\d.,]+)[\"'][^>]*itemprop=[\"']price[\"']").matcher(html);
            if (m.find()) r.price = parseNumber(m.group(1));
        }
    }

    private static String metaContent(String html, String key) {
        String k = Pattern.quote(key);
        Matcher m = Pattern.compile("<meta[^>]+(?:property|name)=[\"']" + k + "[\"'][^>]*content=[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return m.group(1);
        m = Pattern.compile("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']" + k + "[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return m.group(1);
        return null;
    }

    // ---------------------------------------------------------------- kampanyalar

    private static final Pattern[] CAMPAIGN_PATTERNS = {
            Pattern.compile("(?i)\\b(\\d+\\s*Al\\s*\\d+\\s*[ÖöOo]de)"),
            Pattern.compile("(?i)(Sepette\\s*%\\s*\\d+[^<\"\\\\]{0,25})"),
            Pattern.compile("(?i)(Sepette\\s*\\d+[.,]?\\d*\\s*TL\\s*[İiI]ndirim)"),
            Pattern.compile("(?i)(\\d+\\.\\s*[üu]r[üu]n[^<\"\\\\]{0,10}%\\s*\\d+[^<\"\\\\]{0,15})"),
            Pattern.compile("(?i)(\\d+\\s*TL\\s*ve\\s*[üu]zeri[^<\"\\\\]{0,40})"),
            Pattern.compile("(?i)(Pe[şs]in\\s*[Ff]iyat[ıi]na\\s*\\d+\\s*[Tt]aksit)"),
            Pattern.compile("(?i)(%\\s*\\d+\\s*[İiI]ndirim(?:\\s*Kuponu)?)"),
            Pattern.compile("(?i)(\\d+\\s*TL\\s*[İiI]ndirim\\s*Kuponu)"),
            Pattern.compile("(?i)(Kargo\\s*Bedava|[ÜüUu]cretsiz\\s*Kargo)"),
            Pattern.compile("(?i)(Fla[şs]\\s*[Üü]r[üu]n|S[ıi]n[ıi]rl[ıi]\\s*S[üu]re)"),
    };

    private static void findCampaigns(String html, Result r) {
        String text = unescape(html);
        for (Pattern p : CAMPAIGN_PATTERNS) {
            Matcher m = p.matcher(text);
            int hits = 0;
            while (m.find() && hits < 2) {
                addCampaign(r, m.group(1));
                hits++;
            }
            if (r.campaigns.size() >= 8) break;
        }
        // tekrarlari temizle (buyuk/kucuk harf duyarsiz)
        Set<String> seen = new LinkedHashSet<String>();
        List<String> out = new ArrayList<String>();
        for (String c : r.campaigns) {
            String key = c.toLowerCase(new Locale("tr")).replaceAll("\\s+", " ");
            if (seen.add(key)) out.add(c);
        }
        // "Sepette %10 Indirim" varsa ayrica "%10 Indirim" gosterme
        List<String> keys = new ArrayList<String>(seen);
        for (int i = out.size() - 1; i >= 0; i--) {
            String k = keys.get(i);
            for (int j = 0; j < keys.size(); j++) {
                if (j != i && keys.get(j).length() > k.length() && keys.get(j).contains(k)) {
                    out.remove(i);
                    keys.remove(i);
                    break;
                }
            }
        }
        r.campaigns = out.size() > 8 ? out.subList(0, 8) : out;
    }

    private static void addAllMatches(String html, String outer, String inner, Result r) {
        Matcher m = Pattern.compile(outer).matcher(html);
        int n = 0;
        while (m.find() && n++ < 5) {
            String body = m.group(1);
            if (body.length() > 4000) body = body.substring(0, 4000);
            Matcher i = Pattern.compile(inner).matcher(body);
            while (i.find()) addCampaign(r, i.group(1));
        }
    }

    private static void addCampaign(Result r, String raw) {
        if (raw == null) return;
        String s = cleanText(unescape(raw));
        if (s.length() < 3 || s.length() > 90) return;
        if (s.contains("{") || s.contains("http")) return;
        r.campaigns.add(s);
    }

    // ---------------------------------------------------------------- yardimcilar

    /** "value": 123.45 seklindeki ilk sayiyi bulur:  "discountedPrice":{"text":"..","value":123} */
    private static double jsonValue(String html, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\{[^{}]{0,300}?\"value\"\\s*:\\s*([\\d.]+)").matcher(html);
        if (m.find()) return parseNumber(m.group(1));
        m = Pattern.compile("\"" + key + "\"\\s*:\\s*([\\d.]+)").matcher(html);
        if (m.find()) return parseNumber(m.group(1));
        return 0;
    }

    private static double firstNumber(String html, String... regexes) {
        for (String re : regexes) {
            Matcher m = Pattern.compile(re).matcher(html);
            if (m.find()) {
                double v = parseNumber(m.group(1));
                if (v > 0) return v;
            }
        }
        return 0;
    }

    /**
     * "1.299,90 TL", "1299.90", "1,299.90", "₺1.299" gibi bicimleri sayiya cevirir.
     */
    static double parseNumber(String s) {
        if (s == null) return 0;
        String t = s.replaceAll("[^0-9.,]", "");
        if (t.isEmpty()) return 0;
        int lastDot = t.lastIndexOf('.');
        int lastComma = t.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            if (lastComma > lastDot) t = t.replace(".", "").replace(',', '.');
            else t = t.replace(",", "");
        } else if (lastComma >= 0) {
            int decimals = t.length() - lastComma - 1;
            if (decimals == 3) t = t.replace(",", "");      // 1,299 -> binlik ayirici
            else t = t.replace(',', '.');                   // 1299,90 -> ondalik
        } else if (lastDot >= 0) {
            int decimals = t.length() - lastDot - 1;
            boolean multi = t.indexOf('.') != lastDot;
            if (multi || decimals == 3) t = t.replace(".", "");
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u') {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String cleanText(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&#x27;", "'")
                .replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }
}
