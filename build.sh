#!/bin/bash
# Android Gradle Plugin olmadan, komut satiri araclariyla APK derler.
set -euo pipefail
# Yeni surum cikarirken sadece bu iki satiri degistir (versionCode her seferinde artmali)
VERSION_CODE=6
VERSION_NAME=1.5
NOTES="Hepsiburada: ürün sayfası engellenirse fiyat aynı ürünün arama sonucundan okunuyor. Hata raporu da duruyor."
ANDROID_JAR=${ANDROID_JAR:-$HOME/dl/android34.jar}
OUT=build
rm -rf $OUT && mkdir -p $OUT/{res,gen,classes,dex}
SRC=app/src/main
aapt2 compile --dir $SRC/res -o $OUT/res/res.zip
sed 's|<manifest |<manifest package="com.ucuzakapat.app" |' $SRC/AndroidManifest.xml > $OUT/AndroidManifest.xml
aapt2 link -I "$ANDROID_JAR" --manifest $OUT/AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 34 --version-code $VERSION_CODE --version-name $VERSION_NAME \
  -o $OUT/unsigned.apk --java $OUT/gen $OUT/res/res.zip
javac --release 8 -nowarn -encoding UTF-8 -cp "$ANDROID_JAR" -d $OUT/classes \
  $(find $SRC/java $OUT/gen -name '*.java') 2>&1 | grep -v -E 'JAVA_TOOL_OPTIONS|options? .*deprecated|warning: \[options\]|^[0-9]+ warning' || true
test -f $OUT/classes/com/ucuzakapat/app/MainActivity.class
dalvik-exchange --dex --min-sdk-version=24 --output=$OUT/dex/classes.dex $OUT/classes
cp $OUT/unsigned.apk $OUT/tmp.apk
(cd $OUT/dex && zip -q ../tmp.apk classes.dex)
zipalign -f -p 4 $OUT/tmp.apk $OUT/aligned.apk
KS=${KEYSTORE:-ucuzakapat.keystore}
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass ucuzakapat -keypass ucuzakapat -alias ucuzakapat \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Ucuza Kapat, C=TR" 2>&1 | grep -v JAVA_TOOL
fi
apksigner sign --ks "$KS" --ks-pass pass:ucuzakapat --ks-key-alias ucuzakapat --out UcuzaKapat.apk $OUT/aligned.apk
apksigner verify --print-certs UcuzaKapat.apk | head -3
ls -la UcuzaKapat.apk

# Uygulama ici guncelleme bu dosyayi okur (GitHub'a apk/UcuzaKapat.apk ile birlikte yukle)
mkdir -p apk && cp UcuzaKapat.apk apk/UcuzaKapat.apk
python3 -c "import json,sys;print(json.dumps({'versionCode':int(sys.argv[1]),'versionName':sys.argv[2],'apkUrl':'https://raw.githubusercontent.com/MustafaCanDere/ucuzakapat/main/apk/UcuzaKapat.apk','notes':sys.argv[3]},ensure_ascii=False,indent=2))" "$VERSION_CODE" "$VERSION_NAME" "$NOTES" > version.json
cat version.json
