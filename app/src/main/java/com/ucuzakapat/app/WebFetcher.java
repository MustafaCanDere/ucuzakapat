package com.ucuzakapat.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bot korumasi olan siteler duz HTTP istegini reddeder; sayfa once tarayicida
 * JavaScript ile bir "dogrulama" calistirir. Bu sinif sayfayi gercek bir tarayicida
 * (WebView) acar, JavaScript'in bitmesini bekler ve fiyati okur.
 *
 * Fiyat iki yoldan okunur:
 *  1) Sayfanin HTML'i (JSON-LD, meta, siteye ozel kurallar) - PriceFetcher.parse
 *  2) Ekranda gorunen fiyat: sayfada "1.299,90 TL" gibi gorunen, ustu cizili olmayan
 *     ve en buyuk yazi boyutuyla yazilmis fiyat. Site yapisi ne olursa olsun calisir.
 *
 * Uygulama acikken WebView ekrandaki bir kapsayiciya (host) eklenir; bazi bot
 * korumalari ekrana bagli olmayan tarayicida calismiyor.
 *
 * Arka plan thread'inden cagrilmalidir.
 */
public final class WebFetcher {
    private static final long TIMEOUT_MS = 40_000;
    private static final long POLL_MS = 2_000;

    /** Son okunan sayfanin ozeti (hata raporu icin). */
    static volatile JSONObject lastExtract;

    private static WeakReference<ViewGroup> host = new WeakReference<ViewGroup>(null);

    /** Sayfanin tum verisini tek seferde toplayan JavaScript. */
    static final String EXTRACT_JS =
            "(function(){var o={html:document.documentElement.outerHTML,title:document.title,url:location.href,"
            + "price:null,old:null,blocked:false};"
            + "try{var bt=(document.body&&document.body.innerText||'').toLowerCase();"
            + "var tl=(document.title||'').toLowerCase();o.text=bt.replace(/\\s+/g,' ').slice(0,700);"
            + "o.ld=document.querySelectorAll('script[type=\"application/ld+json\"]').length;"
            + "o.blocked=/just a moment|bir dakika|attention required|access denied|erişim engellendi|pardon our|robot|captcha|doğrulama|güvenlik kontrol|security check/.test(tl)"
            + "||!!document.querySelector('#challenge-form,#cf-challenge-running,.cf-turnstile,#px-captcha,#sec-if-cpt-container,iframe[src*=captcha],iframe[src*=challenge]')"
            + "||(bt.length<1500&&/robot|captcha|doğrula|verify you are/.test(bt));"
            + "var re=/^\\s*(?:₺\\s*)?(\\d{1,3}(?:[.\\s]\\d{3})+(?:,\\d{1,2})?|\\d+(?:,\\d{1,2})?)\\s*(?:TL|₺|TRY)?\\s*$/;"
            + "var best=null,bs=0,old=null,os=0;var els=document.querySelectorAll('body *');"
            + "for(var i=0;i<els.length&&i<25000;i++){var e=els[i];if(e.children.length>4)continue;"
            + "var t=(e.innerText||'').replace(/\\s+/g,' ').trim();if(!t||t.length>22)continue;"
            + "var cls=((e.className&&e.className.baseVal!==undefined)?e.className.baseVal:e.className)+' '+e.id;"
            + "if(!/TL|₺/.test(t)&&!/price|fiyat/i.test(cls))continue;if(!re.test(t))continue;"
            + "var r=e.getBoundingClientRect();if(r.width<=0||r.height<=0||r.top>1800||r.top<-50)continue;"
            + "var st=getComputedStyle(e);if(st.visibility=='hidden'||st.display=='none')continue;"
            + "var fs=parseFloat(st.fontSize)||0;"
            + "var strike=(st.textDecorationLine||st.textDecoration||'').indexOf('line-through')>=0||!!e.closest('del,s,strike');"
            + "if(strike){if(fs>os){os=fs;old=t;}continue;}"
            + "if(fs>bs){bs=fs;best=t;}}"
            + "o.price=best;o.old=old;}catch(x){}return JSON.stringify(o);})()";

    private WebFetcher() {}

    /** MainActivity acikken ekrandaki gizli kapsayiciyi bildirir (null = kapali). */
    public static void setHost(ViewGroup g) {
        host = new WeakReference<ViewGroup>(g);
    }

    /** JS'in dondurdugu veriyi sonuca cevirir. Fiyat yoksa price=0 kalir. */
    static PriceFetcher.Result toResult(String fallbackUrl, JSONObject o) {
        String url = o.optString("url", fallbackUrl);
        String html = o.optString("html", "");
        PriceFetcher.Result r = PriceFetcher.parse(url.startsWith("http") ? url : fallbackUrl, html);
        if (r.price <= 0) {
            String vis = o.optString("price", "");
            if (!vis.isEmpty() && !"null".equals(vis)) r.price = PriceFetcher.parseNumber(vis);
            String old = o.optString("old", "");
            if (!old.isEmpty() && !"null".equals(old)) {
                double v = PriceFetcher.parseNumber(old);
                if (v > r.price) r.originalPrice = v;
            }
        }
        if (r.name == null || r.name.isEmpty()) r.name = o.optString("title", null);
        return r;
    }

    /** evaluateJavascript sonucu JSON string olarak gelir: "\"{...}\"" */
    static JSONObject decode(String value) {
        if (value == null || value.equals("null") || value.length() < 2) return null;
        try {
            return new JSONObject(new JSONArray("[" + value + "]").getString(0));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void configure(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // Tarayici kimligini DEGISTIRMIYORUZ: WebView kendini "Android WebView" olarak da
        // bildiriyor (Sec-CH-UA). Kimlik Chrome gibi gosterilirse bu tutarsizlik bot
        // korumalarina yakalanip "urun bulunamadi" / bos sayfa donmesine yol aciyordu.
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
    }

    public static PriceFetcher.Result fetch(Context ctx, final String url) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("WebFetcher ana thread'de çağrılamaz");
        }
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final CountDownLatch done = new CountDownLatch(1);
        final PriceFetcher.Result[] result = new PriceFetcher.Result[1];
        final boolean[] blocked = new boolean[1];
        final boolean[] loaded = new boolean[1];
        final WebView[] holder = new WebView[1];
        lastExtract = null;
        final long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        final Runnable[] pollRef = new Runnable[1];

        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup h = host.get();
                    final WebView wv = new WebView(h != null ? h.getContext() : app);
                    holder[0] = wv;
                    configure(wv);
                    wv.getSettings().setBlockNetworkImage(true);
                    if (h != null) {
                        h.addView(wv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                    } else {
                        wv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY));
                        wv.layout(0, 0, 1080, 2200);
                    }
                    wv.onResume();
                    wv.resumeTimers();

                    pollRef[0] = new Runnable() {
                        @Override
                        public void run() {
                            if (done.getCount() == 0) return;
                            wv.evaluateJavascript(EXTRACT_JS, new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    if (done.getCount() == 0) return;
                                    JSONObject o = decode(value);
                                    if (o != null) {
                                        loaded[0] = true;
                                        lastExtract = o;
                                        blocked[0] = o.optBoolean("blocked");
                                        PriceFetcher.Result r = toResult(url, o);
                                        if (r.price > 0) {
                                            result[0] = r;
                                            done.countDown();
                                            return;
                                        }
                                    }
                                    if (System.currentTimeMillis() > deadline) done.countDown();
                                    else main.postDelayed(pollRef[0], POLL_MS);
                                }
                            });
                        }
                    };

                    wv.setWebViewClient(new WebViewClient() {
                        private boolean started;

                        @Override
                        public void onPageFinished(WebView view, String u) {
                            if (!started) {
                                started = true;
                                main.postDelayed(pollRef[0], 2_500);
                            }
                        }
                    });
                    wv.loadUrl(url);
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (done.getCount() > 0) pollRef[0].run();
                        }
                    }, 12_000);
                } catch (Throwable t) {
                    done.countDown();
                }
            }
        });

        try {
            done.await(TIMEOUT_MS + 10_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        } finally {
            main.post(new Runnable() {
                @Override
                public void run() {
                    WebView wv = holder[0];
                    if (wv != null) {
                        try {
                            ViewGroup p = (ViewGroup) wv.getParent();
                            if (p != null) p.removeView(wv);
                            wv.stopLoading();
                            wv.destroy();
                        } catch (Throwable ignored) {
                        }
                    }
                    try {
                        CookieManager.getInstance().flush();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
        if (result[0] != null) return result[0];
        if (!loaded[0]) throw new IOException("Sayfa açılamadı (internet bağlantısını kontrol et)");
        if (blocked[0]) throw new IOException("Site bot doğrulaması istiyor");
        throw new IOException("Sayfa açıldı ama fiyat bulunamadı");
    }
}
