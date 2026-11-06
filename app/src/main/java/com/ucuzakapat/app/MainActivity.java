package com.ucuzakapat.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    public static final String ACTION_UPDATE = "com.ucuzakapat.app.OPEN_UPDATE";
    private static final int ORANGE = 0xFFF27A1A;
    private static final int GREEN = 0xFF1B8E3E;
    private static final int RED = 0xFFC62828;
    private static final int GREY = 0xFF6B6B6B;
    private static final int[] INTERVALS = {5, 10, 15, 30, 60};

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Product> items = new ArrayList<Product>();

    private EditText urlInput;
    private TextView status;
    private Adapter adapter;
    private android.widget.FrameLayout webHost;

    private final BroadcastReceiver updated = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reload();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifier.createChannels(this);
        setContentView(buildUi());
        askNotificationPermission();
        handleShare(getIntent());
        reload();
        if (!Store.load(this).isEmpty()) TrackerService.start(this, false);
        Updater.checkInteractive(this, true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
        if (intent != null && ACTION_UPDATE.equals(intent.getAction())) Updater.checkInteractive(this, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Checker.ACTION_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updated, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updated, f);
        reload();
        WebFetcher.setHost(webHost);
    }

    @Override
    protected void onPause() {
        super.onPause();
        WebFetcher.setHost(null);
        try {
            unregisterReceiver(updated);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ arayuz

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F5F5);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(16), dp(12), dp(16), dp(8));
        top.setBackgroundColor(Color.WHITE);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        urlInput = new EditText(this);
        urlInput.setHint("Ürün linkini yapıştır (Trendyol, Hepsiburada, Amazon…)");
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        urlInput.setTextSize(14);
        row.addView(urlInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button add = new Button(this);
        add.setText("Ekle");
        add.setTextColor(Color.WHITE);
        add.setBackground(rounded(ORANGE, 8));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addUrl(urlInput.getText().toString());
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.leftMargin = dp(8);
        row.addView(add, lp);
        top.addView(row);

        status = new TextView(this);
        status.setTextColor(GREY);
        status.setTextSize(12);
        status.setPadding(0, dp(6), 0, 0);
        top.addView(status);
        root.addView(top);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setPadding(dp(8), dp(4), dp(8), dp(16));
        list.setClipToPadding(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        TextView empty = new TextView(this);
        empty.setText("Henüz ürün yok.\n\nÜrün linkini yukarıya yapıştır ya da Trendyol/Hepsiburada/Amazon "
                + "uygulamasında ürünü açıp “Paylaş → Ucuza Kapat” de. Menüdeki “Siteye gir, ürün seç” ile uygulama içinden de ekleyebilirsin.\n\nUygulama fiyatı her 5 dakikada bir "
                + "kontrol eder; ucuzlayınca indirim oranı ve kampanyalarla birlikte bildirim gönderir.");
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(GREY);
        empty.setPadding(dp(32), dp(48), dp(32), 0);
        root.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        list.setEmptyView(empty);
        // Arkada, gorunmeyen tarayici alani: uygulama acikken kontroller burada yapilir
        // (bazi bot korumalari ekranda olmayan tarayicida calismiyor)
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        webHost = new android.widget.FrameLayout(this);
        frame.addView(webHost, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(root, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Şimdi kontrol et").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, 2, 1, "Kontrol aralığı");
        menu.add(0, 3, 2, "Pil kısıtlamasını kaldır");
        menu.add(0, 4, 3, "Takibi durdur");
        menu.add(0, 5, 4, "Güncellemeleri kontrol et");
        menu.add(0, 6, 1, "Siteye gir, ürün seç");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                if (Store.load(this).isEmpty()) {
                    toast("Önce bir ürün ekle");
                } else {
                    toast("Kontrol ediliyor…");
                    TrackerService.start(this, true);
                }
                return true;
            case 2:
                pickInterval();
                return true;
            case 3:
                askIgnoreBatteryOptimizations(true);
                return true;
            case 4:
                TrackerService.stop(this);
                toast("Takip durduruldu. Uygulamayı açınca yeniden başlar.");
                return true;
            case 6:
                pickSite();
                return true;
            case 5:
                toast("Kontrol ediliyor…");
                Updater.checkInteractive(this, false);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void pickSite() {
        final String[] names = {"Trendyol", "Hepsiburada", "Amazon", "n11", "MediaMarkt", "Teknosa"};
        final String[] urls = {"https://www.trendyol.com", "https://www.hepsiburada.com", "https://www.amazon.com.tr",
                "https://www.n11.com", "https://www.mediamarkt.com.tr", "https://www.teknosa.com"};
        new AlertDialog.Builder(this)
                .setTitle("Hangi site?")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        BrowserActivity.open(MainActivity.this, urls[which]);
                    }
                })
                .show();
    }

    private void offerBrowser(final Product p) {
        new AlertDialog.Builder(this)
                .setTitle("Fiyat okunamadı")
                .setMessage(p.lastError + "\n\nÜrünü uygulama içinde açayım mı? Site doğrulama isterse geç, "
                        + "fiyat görününce \"Güncelle\"ye bas. Sonraki kontroller de bu doğrulamayı kullanır.")
                .setPositiveButton("Aç", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        BrowserActivity.open(MainActivity.this, p.url);
                    }
                })
                .setNegativeButton("Sonra", null)
                .show();
    }

    private void pickInterval() {
        String[] labels = new String[INTERVALS.length];
        int current = Store.intervalMinutes(this);
        int sel = 0;
        for (int i = 0; i < INTERVALS.length; i++) {
            labels[i] = INTERVALS[i] + " dakika";
            if (INTERVALS[i] == current) sel = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Ne sıklıkla kontrol edilsin?")
                .setSingleChoiceItems(labels, sel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Store.setIntervalMinutes(MainActivity.this, INTERVALS[which]);
                        Scheduler.scheduleNext(MainActivity.this);
                        d.dismiss();
                        reload();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ islemler

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null) addUrl(text);
        intent.setAction(null);
    }

    private static String extractUrl(String text) {
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (m.find()) return m.group().replaceAll("[)\\]>,.]+$", "");
        String t = text.trim();
        if (t.contains(".") && !t.contains(" ")) return "https://" + t;
        return null;
    }

    private void addUrl(String text) {
        final String url = extractUrl(text == null ? "" : text);
        if (url == null) {
            toast("Geçerli bir link bulunamadı");
            return;
        }
        final Product p = Store.add(this, url);
        if (p == null) {
            toast("Bu ürün zaten listede");
            return;
        }
        urlInput.setText("");
        reload();
        toast("Eklendi, fiyat okunuyor…");
        askIgnoreBatteryOptimizations(false);
        exec.execute(new Runnable() {
            @Override
            public void run() {
                Checker.checkOne(getApplicationContext(), p);
                Store.update(getApplicationContext(), p);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        reload();
                        if (p.lastError != null && !isFinishing()) offerBrowser(p);
                        TrackerService.start(MainActivity.this, false);
                    }
                });
            }
        });
    }

    private void reload() {
        items.clear();
        items.addAll(Store.load(this));
        if (adapter != null) adapter.notifyDataSetChanged();
        if (status != null) {
            long last = Store.lastRun(this);
            String s = items.size() + " ürün takipte • her " + Store.intervalMinutes(this) + " dakikada bir";
            if (last > 0) {
                s += " • son tur " + DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS);
            }
            status.setText(s);
        }
    }

    private void confirmDelete(final Product p) {
        new AlertDialog.Builder(this)
                .setTitle(p.name != null ? p.name : p.url)
                .setItems(new String[]{"Uygulamada aç", "Şimdi kontrol et", "Takipten çıkar", "Tarayıcıda aç"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) BrowserActivity.open(MainActivity.this, p.url);
                                else if (which == 1) checkSingle(p);
                                else if (which == 3) openUrl(p.url);
                                else {
                                    Store.remove(MainActivity.this, p.id);
                                    reload();
                                    if (Store.load(MainActivity.this).isEmpty()) TrackerService.stop(MainActivity.this);
                                }
                            }
                        })
                .show();
    }

    private void checkSingle(final Product p) {
        toast("Kontrol ediliyor…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                Checker.checkOne(getApplicationContext(), p);
                Store.update(getApplicationContext(), p);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        reload();
                        if (p.lastError != null && !isFinishing()) offerBrowser(p);
                    }
                });
            }
        });
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Link açılamadı");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void askIgnoreBatteryOptimizations(boolean force) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            if (force) toast("Pil kısıtlaması zaten kaldırılmış 👍");
            return;
        }
        if (!force && getPreferences(MODE_PRIVATE).getBoolean("askedBattery", false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("askedBattery", true).apply();
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ liste

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            final Product p = items.get(pos);
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackground(rounded(Color.WHITE, 12));
            elevate(card);

            TextView name = new TextView(MainActivity.this);
            name.setText(p.name != null ? p.name : p.url);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(0xFF222222);
            name.setTextSize(15);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(name);

            TextView price = new TextView(MainActivity.this);
            price.setTextSize(22);
            price.setTypeface(Typeface.DEFAULT_BOLD);
            price.setPadding(0, dp(4), 0, 0);
            if (p.currentPrice > 0) {
                price.setText(Notifier.money(p.currentPrice, p.currency));
                price.setTextColor(p.previousPrice > p.currentPrice ? GREEN
                        : (p.previousPrice > 0 && p.previousPrice < p.currentPrice ? RED : ORANGE));
            } else {
                price.setText(p.lastError == null ? "Fiyat okunuyor…" : "Fiyat okunamadı");
                price.setTextColor(GREY);
            }
            card.addView(price);

            List<String> lines = new ArrayList<String>();
            if (p.siteDiscountPercent() > 0) {
                lines.add("Sitede " + Notifier.pct(p.siteDiscountPercent()) + " indirimli (liste fiyatı "
                        + Notifier.money(p.originalPrice, p.currency) + ")");
            }
            if (p.previousPrice > 0 && p.currentPrice > 0 && p.previousPrice != p.currentPrice) {
                double ch = (p.previousPrice - p.currentPrice) * 100.0 / p.previousPrice;
                lines.add((ch > 0 ? "▼ " : "▲ ") + Notifier.pct(Math.abs(ch)) + " (önceki "
                        + Notifier.money(p.previousPrice, p.currency) + ")");
            }
            if (p.firstPrice > 0 && p.currentPrice > 0 && Math.abs(p.sinceStartPercent()) >= 0.05) {
                double s = p.sinceStartPercent();
                lines.add("Takipten beri " + (s > 0 ? Notifier.pct(s) + " ucuzladı" : Notifier.pct(-s) + " pahalandı")
                        + " • en düşük " + Notifier.money(p.lowestPrice, p.currency));
            }
            for (String l : lines) {
                TextView t = new TextView(MainActivity.this);
                t.setText(l);
                t.setTextSize(13);
                t.setTextColor(0xFF444444);
                card.addView(t);
            }

            if (!p.campaigns.isEmpty()) {
                TextView camp = new TextView(MainActivity.this);
                camp.setText("🎁 " + TextUtils.join(" • ", p.campaigns));
                camp.setTextSize(13);
                camp.setTextColor(0xFF8A4B00);
                camp.setBackground(rounded(0xFFFFF1E3, 6));
                camp.setPadding(dp(8), dp(4), dp(8), dp(4));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.topMargin = dp(6);
                card.addView(camp, clp);
            }

            TextView foot = new TextView(MainActivity.this);
            foot.setTextSize(11);
            foot.setPadding(0, dp(6), 0, 0);
            String host = Uri.parse(p.url).getHost();
            String f = (host == null ? "" : host.replace("www.", "")) + " • ";
            if (p.lastCheck > 0) {
                f += "kontrol: " + DateUtils.getRelativeTimeSpanString(p.lastCheck, System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS);
            } else {
                f += "henüz kontrol edilmedi";
            }
            if (p.lastError != null) {
                f += "\n⚠ " + p.lastError;
                foot.setTextColor(RED);
            } else {
                foot.setTextColor(GREY);
            }
            foot.setText(f);
            card.addView(foot);

            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    BrowserActivity.open(MainActivity.this, p.url);
                }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDelete(p);
                    return true;
                }
            });

            LinearLayout wrap = new LinearLayout(MainActivity.this);
            wrap.setPadding(dp(4), dp(6), dp(4), dp(6));
            wrap.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return wrap;
        }
    }

    private void elevate(View v) {
        if (Build.VERSION.SDK_INT >= 21) v.setElevation(dp(2));
    }
}
