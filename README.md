# Ucuza Kapat – Fiyat Takip & İndirim Bildirimi (Android)

Ürün linklerini eklersin; uygulama fiyatı **her 5 dakikada bir** kontrol eder, ürün ucuzlayınca
**indirim oranı** ve sayfada bulunan **kampanyalarla** (Sepette %X, 3 Al 2 Öde, kupon, kargo bedava, taksit…)
birlikte bildirim gönderir. Yeni bir kampanya çıkarsa fiyat değişmese de ayrıca haber verir.

## Kurulum (APK)
**İndir:** [apk/UcuzaKapat.apk](https://github.com/MustafaCanDere/ucuzakapat/raw/main/apk/UcuzaKapat.apk)

1. `UcuzaKapat.apk` dosyasını telefona at (WhatsApp/Drive/USB).
2. Dosyaya dokun → "Bilinmeyen kaynaklardan yüklemeye izin ver" → Yükle.
   (Play Protect "tanınmayan uygulama" uyarısı verirse "Yine de yükle" de; uygulama Play Store'da değil, o yüzden.)
3. Açınca **bildirim izni** ver. İlk ürünü ekleyince **pil kısıtlamasını kaldır** penceresi çıkar, "İzin ver" de.
   Bu, telefonun takibi arka planda öldürmemesi için önemli (Xiaomi/Redmi'de ayrıca:
   Ayarlar → Uygulamalar → Ucuza Kapat → Otomatik başlatma: AÇIK, Pil tasarrufu: Kısıtlama yok).

## Güncelleme
1.2 ve sonrasında APK'yı elle indirmene gerek yok: uygulama açılışta ve arka planda 12 saatte bir
GitHub'daki `version.json`'a bakar, yeni sürüm varsa "Güncelle" der, indirip kurar (Android bir kez
"bu kaynaktan yüklemeye izin ver" ister). Menüden "Güncellemeleri kontrol et" ile elle de bakabilirsin.

Yeni sürüm yayınlamak: `build.sh` başındaki `VERSION_CODE`'u artır, derle (aynı keystore ile!),
`apk/UcuzaKapat.apk` ve `version.json`'u commit'leyip push et.

## Kullanım
- Linki üstteki kutuya yapıştır → **Ekle**. Ya da Trendyol/Hepsiburada/Amazon uygulamasında ürünü aç →
  **Paylaş → Ucuza Kapat**.
- Karta dokun: ürünü sitede açar. Uzun bas: şimdi kontrol et / takipten çıkar.
- Sağ üst menü: Şimdi kontrol et, Kontrol aralığı (5/10/15/30/60 dk), Pil kısıtlamasını kaldır, Takibi durdur.

Kartta gördüklerin: güncel fiyat, sitedeki indirim oranı (üstü çizili fiyata göre), önceki fiyata göre değişim,
takibe başladığından beri değişim, gördüğü en düşük fiyat, kampanyalar.

## Desteklenen siteler
- Özel kurallar: **Trendyol, Hepsiburada, Amazon.com.tr, n11**
- Diğer siteler: sayfada schema.org ürün verisi (JSON-LD) veya fiyat meta etiketi varsa çoğu e-ticaret sitesi
  (MediaMarkt, Teknosa, Vatan, Boyner, Koton, LC Waikiki vb. genelde çalışır).
- Bazı siteler bot koruması kullanır; o zaman kartta "⚠ bot korumasına takıldı" yazar ve bir sonraki turda tekrar dener.

## 5 dakika hakkında
Android'in standart periyodik iş sistemi (WorkManager) en az 15 dakikada bir çalışır. 5 dakika için uygulama
**ön plan servisi + tam zamanlı alarm** kullanır (bildirim çubuğunda sessiz "Fiyat takibi açık" bildirimi bu yüzden durur).
Telefon uzun süre ekranı kapalı ve hareketsiz kalırsa Android (Doze modu) alarmları en fazla ~9-10 dakikada bir
çalıştırır; ekran açılınca 5 dakikaya döner. Bu Android'in kendi sınırıdır.

## Kaynaktan derleme
- **Android Studio:** klasörü aç, Run. (Harici kütüphane yok, sadece Android SDK 34.)
- **Komut satırı (Linux):** `build.sh` – aapt2, javac, dx, zipalign, apksigner ile Gradle'sız derler.
