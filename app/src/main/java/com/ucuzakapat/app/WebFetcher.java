package com.ucuzakapat.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bot korumasi olan siteler (Hepsiburada vb.) duz HTTP istegini reddeder; sayfa once
 * tarayicida JavaScript ile bir "dogrulama" calistirir. Bu sinif sayfayi ekranda
 * gorunmeyen gercek bir tarayicida (WebView) acar, JavaScript'in bitmesini bekler ve
 * olusan HTML'den fiyati okur.
 *
 * Arka plan thread'inden cagrilmalidir (ana thread'de cagrilirsa kilitlenir).
 */
public final class WebFetcher {
    private static final long TIMEOUT_MS = 35_000;
    private static final long POLL_MS = 1_500;

    private WebFetcher() {}

    public static PriceFetcher.Result fetch(Context ctx, final String url) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("WebFetcher ana thread'de cagrilamaz");
        }
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final CountDownLatch done = new CountDownLatch(1);
        final PriceFetcher.Result[] result = new PriceFetcher.Result[1];
        final String[] lastHtml = new String[1];
        final WebView[] holder = new WebView[1];
        final long deadline = System.currentTimeMillis() + TIMEOUT_MS;

        main.post(new Runnable() {
            @SuppressLint("SetJavaScriptEnabled")
            @Override
            public void run() {
                try {
                    final WebView wv = new WebView(app);
                    holder[0] = wv;
                    WebSettings s = wv.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setDatabaseEnabled(true);
                    s.setLoadsImagesAutomatically(false);
                    s.setBlockNetworkImage(true);
                    s.setMediaPlaybackRequiresUserGesture(true);
                    // "wv" isareti bazi bot korumalarinda engellenir; normal Chrome gibi gorun
                    String ua = s.getUserAgentString();
                    if (ua != null) s.setUserAgentString(ua.replace("; wv)", ")").replace("Version/4.0 ", ""));
                    CookieManager cm = CookieManager.getInstance();
                    cm.setAcceptCookie(true);
                    cm.setAcceptThirdPartyCookies(wv, true);
                    // Ekranda olmasa da sayfa "telefon boyutunda" cizilsin
                    wv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY));
                    wv.layout(0, 0, 1080, 2200);

                    final Runnable poll = new Runnable() {
                        @Override
                        public void run() {
                            if (done.getCount() == 0) return;
                            wv.evaluateJavascript("document.documentElement.outerHTML",
                                    new ValueCallback<String>() {
                                        @Override
                                        public void onReceiveValue(String value) {
                                            if (done.getCount() == 0) return;
                                            String html = decode(value);
                                            if (html != null) {
                                                lastHtml[0] = html;
                                                PriceFetcher.Result r = PriceFetcher.parse(wv.getUrl() == null ? url : wv.getUrl(), html);
                                                if (r.price > 0) {
                                                    result[0] = r;
                                                    done.countDown();
                                                    return;
                                                }
                                            }
                                            if (System.currentTimeMillis() > deadline) {
                                                done.countDown();
                                            } else {
                                                main.postDelayed(this_poll[0], POLL_MS);
                                            }
                                        }
                                    });
                        }
                    };
                    this_poll[0] = poll;

                    wv.setWebViewClient(new WebViewClient() {
                        private boolean started;

                        @Override
                        public void onPageFinished(WebView view, String u) {
                            if (!started) {
                                started = true;
                                main.postDelayed(poll, 2_000);
                            }
                        }
                    });
                    wv.loadUrl(url);
                    // Sayfa hic "bitti" demezse de yoklamaya basla
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (done.getCount() > 0) poll.run();
                        }
                    }, 12_000);
                } catch (Throwable t) {
                    done.countDown();
                }
            }

            private final Runnable[] this_poll = new Runnable[1];
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
                            wv.stopLoading();
                            wv.destroy();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        }
        if (result[0] != null) return result[0];
        throw new IOException(lastHtml[0] == null
                ? "Sayfa açılamadı"
                : "Fiyat sayfada bulunamadı (site bot korumasına takılmış olabilir)");
    }

    /** evaluateJavascript sonucu JSON string olarak gelir: "\"<html>...\"" */
    private static String decode(String value) {
        if (value == null || value.equals("null") || value.length() < 2) return null;
        try {
            return new JSONArray("[" + value + "]").getString(0);
        } catch (Exception e) {
            return null;
        }
    }
}
