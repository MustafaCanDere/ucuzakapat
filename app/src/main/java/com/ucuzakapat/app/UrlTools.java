package com.ucuzakapat.app;

import android.net.Uri;

import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paylasilan linkleri gercek urun linkine cevirir.
 *
 * Uygulamalardan "Paylas" ile gelen linkler cogu zaman kisa/izleme linkidir
 * (ty.gl, hb.link, app.adjust, onelink, amzn.eu ...). Bunlar tarayicida acilinca
 * uygulamayi acmaya calisir ya da sitenin "urun bulunamadi" sayfasina duser.
 * Bu sinif yonlendirmeleri takip edip asil urun sayfasini bulur.
 */
public final class UrlTools {
    private UrlTools() {}

    private static final String[] FALLBACK_KEYS = {
            "browser_fallback_url", "$fallback_url", "fallback_url", "af_web_dp", "redirect", "redirect_url",
            "adj_redirect", "deep_link_value", "link", "url", "u", "target", "dest", "destination"};

    /** Metindeki ilk linki bulur. */
    public static String extract(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("https?://[^\\s\"'<>]+").matcher(text);
        if (m.find()) return m.group().replaceAll("[)\\]>,.!;:]+$", "");
        String t = text.trim();
        if (t.contains(".") && !t.contains(" ")) return "https://" + t;
        return null;
    }

    /** intent://...;S.browser_fallback_url=...;end  veya  uygulama-semasi://...?url=... icinden web linkini cikarir. */
    public static String fallbackFromAppLink(String link) {
        try {
            Matcher m = Pattern.compile("S\\.browser_fallback_url=([^;]+)").matcher(link);
            if (m.find()) return clean(URLDecoder.decode(m.group(1), "UTF-8"));
            if (link.startsWith("intent://")) {
                // intent://www.site.com/urun;scheme=https;...  ->  https://www.site.com/urun
                int semi = link.indexOf("#Intent");
                String body = link.substring("intent://".length(), semi > 0 ? semi : link.length());
                Matcher sch = Pattern.compile("scheme=(https?)").matcher(link);
                if (sch.find() && body.contains(".")) return clean(sch.group(1) + "://" + body);
            }
            Uri u = Uri.parse(link.replace("#Intent", "?").replace(";", "&"));
            for (String k : FALLBACK_KEYS) {
                String v = u.getQueryParameter(k);
                if (v != null && v.startsWith("http")) return clean(v);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Izleme parametrelerini atar (utm_*, gclid, adjust...). Urun parametrelerine dokunmaz. */
    public static String clean(String url) {
        try {
            Uri u = Uri.parse(url);
            Uri.Builder b = u.buildUpon().clearQuery();
            for (String k : u.getQueryParameterNames()) {
                String lk = k.toLowerCase(Locale.ROOT);
                if (lk.startsWith("utm_") || lk.equals("gclid") || lk.equals("fbclid") || lk.startsWith("adj_")
                        || lk.startsWith("af_") || lk.equals("wt_int") || lk.equals("_branch_match_id")
                        || lk.equals("ref_") || lk.equals("referrer")) {
                    continue;
                }
                for (String v : u.getQueryParameters(k)) b.appendQueryParameter(k, v);
            }
            return b.build().toString();
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Arka plan thread'inde cagrilmali: yonlendirmeleri takip edip son urun linkini dondurur.
     * Bir sey ters giderse orijinal linki dondurur.
     */
    public static String resolve(String url) {
        String start = clean(url);
        // Dogrudan magaza linki ise dokunma: bot korumasi istegi baska sayfaya yonlendirebilir,
        // o zaman yanlis linki kaydederdik.
        if (!isShortLink(start)) return start;
        try {
            String fb = fallbackFromAppLink(start);
            if (fb != null && !fb.equals(start) && Uri.parse(start).getQueryParameter("url") != null) start = fb;
            String finalUrl = PriceFetcher.resolve(start);
            if (finalUrl == null || !finalUrl.startsWith("http")) return start;
            String fromParam = fallbackFromAppLink(finalUrl);
            // yonlendirme sonunda bir "acilis" sayfasina dustuysek parametredeki linki kullan
            if (fromParam != null && isRedirector(finalUrl)) finalUrl = fromParam;
            return clean(finalUrl);
        } catch (Exception e) {
            return start;
        }
    }

    /** Kisa / paylasim / izleme linki mi (magazanin kendi urun linki degil)? */
    public static boolean isShortLink(String url) {
        String h = Uri.parse(url).getHost();
        if (h == null) return false;
        h = h.toLowerCase(Locale.ROOT);
        if (isRedirector(url)) return true;
        String[] shorts = {"amzn.to", "amzn.eu", "a.co", "bit.ly", "t.co", "tinyurl.com", "hb.link", "hepsiburada.link",
                "n11.link", "app.n11.com", "link.trendyol.com", "click.", "go.", "l.", "share.", "s.click"};
        for (String sh : shorts) if (h.equals(sh) || h.startsWith(sh) || h.endsWith("." + sh)) return true;
        return false;
    }

    private static boolean isRedirector(String url) {
        String h = Uri.parse(url).getHost();
        if (h == null) return false;
        h = h.toLowerCase(Locale.ROOT);
        return h.contains("adjust") || h.contains("onelink") || h.contains("app.link") || h.contains("branch")
                || h.contains("ty.gl") || h.contains("hb.link") || h.contains("bnc.lt") || h.contains("page.link");
    }
}
