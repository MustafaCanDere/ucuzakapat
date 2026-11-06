package com.ucuzakapat.app;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * "Hata raporu": bir urun icin tum okuma yollarini tek tek dener ve sonucu duz metin
 * olarak dondurur. Kullanici bunu paylasarak gelistiriciye gonderir.
 * Kisisel veri icermez (sadece urun sayfasinin ozeti).
 */
public final class Diagnostics {
    private Diagnostics() {}

    /** Arka plan thread'inde cagrilmali (40-60 sn surebilir). */
    public static String run(Context c, String url) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ucuza Kapat hata raporu\n");
        sb.append("Sürüm: ").append(Updater.installedVersionCode(c))
                .append(" • Android ").append(Build.VERSION.RELEASE).append(" • ").append(Build.MANUFACTURER)
                .append(' ').append(Build.MODEL).append('\n');
        sb.append("Link: ").append(url).append('\n');
        sb.append("UA: ").append(PriceFetcher.userAgent(c)).append("\n\n");

        // 1) Trendyol API
        if (url.contains("trendyol") || url.contains("ty.gl")) {
            try {
                PriceFetcher.Result r = PriceFetcher.trendyolApi(url);
                sb.append("[Trendyol API] ").append(r == null ? "sonuç yok" : "fiyat=" + r.price).append('\n');
            } catch (Throwable t) {
                sb.append("[Trendyol API] hata: ").append(t).append('\n');
            }
        }

        // 2) Duz HTTP
        try {
            HttpURLConnection con = PriceFetcher.open(url);
            con.setInstanceFollowRedirects(true);
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String body = "";
            if (in != null) {
                if ("gzip".equalsIgnoreCase(con.getContentEncoding())) in = new GZIPInputStream(in);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && bos.size() < 4_000_000) bos.write(buf, 0, n);
                in.close();
                body = new String(bos.toByteArray(), "UTF-8");
            }
            sb.append("[HTTP] kod=").append(code).append(" son adres=").append(con.getURL())
                    .append(" boyut=").append(body.length())
                    .append(" bot=").append(PriceFetcher.looksBlocked(body))
                    .append(" başlık=").append(title(body)).append('\n');
            PriceFetcher.Result r = PriceFetcher.parse(url, body);
            sb.append("   okunan fiyat=").append(r.price).append(" liste=").append(r.originalPrice)
                    .append(" ld+json=").append(count(body, "application/ld+json"))
                    .append(" kampanya=").append(r.campaigns).append('\n');
            sb.append("   fiyat geçen yerler: ").append(priceSnippets(body)).append('\n');
            con.disconnect();
        } catch (Throwable t) {
            sb.append("[HTTP] hata: ").append(t).append('\n');
        }

        // 3) Gizli tarayici
        try {
            PriceFetcher.Result r = WebFetcher.fetch(c, url);
            sb.append("[Tarayıcı] fiyat=").append(r.price).append(" liste=").append(r.originalPrice)
                    .append(" ad=").append(r.name).append('\n');
        } catch (Throwable t) {
            sb.append("[Tarayıcı] hata: ").append(t.getMessage()).append('\n');
        }
        JSONObject o = WebFetcher.lastExtract;
        if (o != null) {
            sb.append("   adres=").append(o.optString("url")).append('\n');
            sb.append("   başlık=").append(o.optString("title")).append('\n');
            sb.append("   bot=").append(o.optBoolean("blocked")).append(" ld+json=").append(o.optInt("ld"))
                    .append(" ekrandaki fiyat=").append(o.optString("price")).append(" çizili=")
                    .append(o.optString("old")).append(" html=").append(o.optString("html").length()).append('\n');
            sb.append("   fiyat geçen yerler: ").append(priceSnippets(o.optString("html"))).append('\n');
            sb.append("   sayfa metni: ").append(o.optString("text")).append('\n');
        } else {
            sb.append("   (tarayıcı sayfa içeriği alamadı)\n");
        }
        return sb.toString();
    }

    private static String title(String html) {
        Matcher m = Pattern.compile("<title[^>]*>([^<]{0,150})", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group(1).trim() : "-";
    }

    private static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    /** HTML'de "price" gecen ilk birkac yerin kisa kesitleri (yapiyi gormek icin). */
    private static String priceSnippets(String html) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("(?i)price|fiyat").matcher(html);
        int found = 0, lastEnd = -1;
        while (m.find() && found < 8) {
            if (m.start() < lastEnd) continue;
            int a = Math.max(0, m.start() - 40), b = Math.min(html.length(), m.end() + 80);
            sb.append("\n      …").append(html.substring(a, b).replaceAll("\\s+", " ")).append('…');
            lastEnd = b;
            found++;
        }
        return found == 0 ? "yok" : sb.toString();
    }
}
