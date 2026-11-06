package com.ucuzakapat.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/**
 * Uygulama ici tarayici. Iki isi var:
 *  1) Kullanici siteye kendisi girer; bot dogrulamasi varsa normal sekilde gecer.
 *     Alinan cerezler arka plandaki kontrollerde de kullanilir.
 *  2) Acik olan sayfanin fiyatini okur, "Takip et" ile listeye ekler (veya mevcut
 *     urunu gunceller).
 */
public class BrowserActivity extends Activity {
    public static final String EXTRA_URL = "url";

    private static final int ORANGE = 0xFFF27A1A;

    private WebView web;
    private TextView info;
    private Button follow;
    private ProgressBar progress;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PriceFetcher.Result lastResult;
    private String lastUrl;
    private int readGeneration;

    public static void open(Context c, String url) {
        c.startActivity(new Intent(c, BrowserActivity.class).putExtra(EXTRA_URL, url));
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) url = "https://www.trendyol.com";
        setTitle("Ürünü bul, Takip et'e bas");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        web = new WebView(this);
        WebFetcher.configure(web);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) return false;
                // Site uygulamayi acmaya calisiyor (intent://, trendyol:// ...): web karsiligini ac
                String fb = UrlTools.fallbackFromAppLink(u);
                if (fb != null) view.loadUrl(fb);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                scheduleRead(1500);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String u, boolean reload) {
                // Trendyol gibi siteler sayfa yenilemeden adres degistirir
                scheduleRead(2000);
            }
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(8));
        info = new TextView(this);
        info.setTextSize(13);
        info.setTextColor(0xFF333333);
        info.setText("Bir ürün aç; fiyatı burada görünecek.");
        bar.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        follow = new Button(this);
        follow.setText("⭐ Takip et");
        follow.setTextSize(16);
        follow.setAllCaps(false);
        follow.setPadding(dp(18), 0, dp(18), 0);
        follow.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ORANGE);
        g.setCornerRadius(dp(8));
        follow.setBackground(g);
        follow.setEnabled(false);
        follow.setAlpha(0.4f);
        follow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                followCurrent();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50));
        lp.leftMargin = dp(8);
        bar.addView(follow, lp);
        root.addView(bar);

        setContentView(root);
        web.loadUrl(url);
    }

    private void scheduleRead(long delay) {
        final int gen = ++readGeneration;
        // Sayfa yavas yuklenirse birkac kez dene
        for (int i = 0; i < 4; i++) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (web == null || gen != readGeneration) return;
                    String cu = web.getUrl();
                    if (lastResult == null || cu == null || !cu.equals(lastUrl)) read();
                }
            }, delay + i * 2500L);
        }
    }

    private void read() {
        if (web == null) return;
        web.evaluateJavascript(WebFetcher.EXTRACT_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                JSONObject o = WebFetcher.decode(value);
                if (o == null) return;
                String url = o.optString("url", web.getUrl());
                PriceFetcher.Result r = WebFetcher.toResult(url, o);
                if (r.price > 0) {
                    lastResult = r;
                    lastUrl = url;
                    StringBuilder sb = new StringBuilder();
                    sb.append("Fiyat: ").append(Notifier.money(r.price, r.currency));
                    if (r.originalPrice > r.price) {
                        double pct = (r.originalPrice - r.price) * 100 / r.originalPrice;
                        sb.append("  (").append(Notifier.pct(pct)).append(" indirimli)");
                    }
                    if (!r.campaigns.isEmpty()) sb.append("\n🎁 ").append(r.campaigns.get(0));
                    info.setText(sb.toString());
                    Product existing = findByUrl(url);
                    follow.setEnabled(true);
                    follow.setAlpha(1f);
                    if (existing != null) markFollowed();
                    else {
                        follow.setText("⭐ Takip et");
                        follow.setBackground(btnBg(ORANGE));
                    }
                } else {
                    lastResult = null;
                    follow.setEnabled(false);
                    follow.setAlpha(0.4f);
                    info.setText(o.optBoolean("blocked")
                            ? "Site doğrulama istiyor; ekrandaki adımı tamamla."
                            : "Bu sayfada fiyat bulamadım. Bir ürün sayfası aç.");
                }
            }
        });
    }

    private void markFollowed() {
        follow.setText("✓ Takipte");
        follow.setBackground(btnBg(0xFF1B8E3E));
    }

    private GradientDrawable btnBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        return g;
    }

    private Product findByUrl(String url) {
        List<Product> list = Store.load(this);
        String key = stripQuery(url);
        for (Product p : list) if (stripQuery(p.url).equals(key)) return p;
        String orig = getIntent().getStringExtra(EXTRA_URL);
        if (orig != null) for (Product p : list) if (p.url.equals(orig)) return p;
        return null;
    }

    private static String stripQuery(String u) {
        int i = u.indexOf('?');
        return i > 0 ? u.substring(0, i) : u;
    }

    private void followCurrent() {
        if (lastResult == null || lastUrl == null) return;
        Product p = findByUrl(lastUrl);
        if (p != null && !p.url.equals(lastUrl) && lastUrl.startsWith("http")) {
            // Kayitli link bozuk/kisa link olabilir: dogru sayfanin linkini kaydet
            p.url = UrlTools.clean(lastUrl);
        }
        if (p == null) {
            p = Store.add(this, lastUrl);
            if (p == null) {
                Toast.makeText(this, "Bu ürün zaten listede", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Checker.apply(getApplicationContext(), p, lastResult, true);
        Store.update(getApplicationContext(), p);
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        sendBroadcast(new Intent(Checker.ACTION_UPDATED).setPackage(getPackageName()));
        TrackerService.start(this, false);
        Toast.makeText(this, "Takip listene eklendi: " + Notifier.money(lastResult.price, lastResult.currency)
                + "\nUcuzlayınca bildirim gelecek.", Toast.LENGTH_LONG).show();
        markFollowed();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
