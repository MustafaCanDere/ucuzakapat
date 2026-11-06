package com.ucuzakapat.app;

/**
 * "Siteye gir, urun sec" listesi. Fiyat okuma tum sitelerde ayni yollarla calisir
 * (siteye ozel kural, schema.org urun verisi, meta etiketleri, ekranda gorunen fiyat);
 * bu liste sadece hizli giris icin. Listede olmayan bir sitenin linki de eklenebilir.
 */
final class Sites {
    private Sites() {}

    static final String[] NAMES = {
            "Trendyol", "Hepsiburada", "Amazon", "n11", "Çiçeksepeti", "Pazarama", "PttAVM", "İdefix",
            "Teknosa", "MediaMarkt", "Vatan Bilgisayar", "İtopya", "İnceHesap", "Apple Store TR",
            "Koçtaş", "IKEA", "Decathlon", "Karaca", "English Home", "Madame Coco", "Evidea",
            "Boyner", "Beymen", "LC Waikiki", "Koton", "DeFacto", "Mavi", "FLO", "Modanisa",
            "Gratis", "Watsons", "Rossmann", "D&R", "Kitapyurdu", "Migros", "A101", "Şok", "CarrefourSA",
    };

    static final String[] URLS = {
            "https://www.trendyol.com", "https://www.hepsiburada.com", "https://www.amazon.com.tr",
            "https://www.n11.com", "https://www.ciceksepeti.com", "https://www.pazarama.com",
            "https://www.pttavm.com", "https://www.idefix.com", "https://www.teknosa.com",
            "https://www.mediamarkt.com.tr", "https://www.vatanbilgisayar.com", "https://www.itopya.com",
            "https://www.incehesap.com", "https://www.apple.com/tr/shop", "https://www.koctas.com.tr",
            "https://www.ikea.com.tr", "https://www.decathlon.com.tr", "https://www.karaca.com",
            "https://www.englishhome.com", "https://www.madamecoco.com", "https://www.evidea.com",
            "https://www.boyner.com.tr", "https://www.beymen.com", "https://www.lcw.com",
            "https://www.koton.com", "https://www.defacto.com.tr", "https://www.mavi.com",
            "https://www.flo.com.tr", "https://www.modanisa.com", "https://www.gratis.com",
            "https://www.watsons.com.tr", "https://www.rossmann.com.tr", "https://www.dr.com.tr",
            "https://www.kitapyurdu.com", "https://www.migros.com.tr", "https://www.a101.com.tr",
            "https://www.sokmarket.com.tr", "https://www.carrefoursa.com",
    };
}
