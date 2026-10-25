#!/bin/bash
# Build KlikDash tanpa Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Pakai: ./build.sh            (build saja)
#        ./build.sh install    (build + pasang ke headunit, jadikan launcher default)
#        DEBUGGABLE=1 ./build.sh install   (sama, tapi debuggable: untuk uji TANYA lewat adb, lebih lambat)
#        ./build.sh release    (APK rilis publik tanpa teks Al-Qur'an → build/klikdash-<versi>.apk)
set -euo pipefail
cd "$(dirname "$0")"

VERSION_NAME=${VERSION_NAME:-1.7.4}
VERSION_CODE=${VERSION_CODE:-32}

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-35/android.jar
DEVICE=${DEVICE:-192.168.1.17:5555}
MODE=${1:-}

rm -rf build
mkdir -p build/classes build/dex build/gen/com/ashafizullah/klikdash
# Saat build: batas bawah jam yang masuk akal untuk Clock (jam headunit tanpa baterai jam mulai dari tanggal build firmware).
printf 'package com.ashafizullah.klikdash;\n\nfinal class Built {\n    static final long TIME = %s000L;\n\n    private Built() { }\n}\n' \
    "$(date +%s)" > build/gen/com/ashafizullah/klikdash/Built.java

"$BT/aapt2" compile --dir res -o build/res.zip
# Teks Al-Qur'an dan terjemahan English tidak pernah ikut APK rilis (aplikasi mengunduhnya sendiri dari equran.id
# dan alquran.cloud). Build pengembang menyertakan assets/quran.txt dan assets/quran-en.txt bila ada, supaya
# headunit sendiri langsung offline.
mkdir -p build/assets
cp -R assets/. build/assets/
if [ "$MODE" = "release" ] || [ ! -s assets/quran.txt ]; then rm -f build/assets/quran.txt; fi
if [ "$MODE" = "release" ] || [ ! -s assets/quran-en.txt ]; then rm -f build/assets/quran-en.txt; fi

# Bawaan tanpa android:debuggable: Android tidak pernah mengompilasi AOT aplikasi debuggable (dexopt turun ke "quicken")
# dan ART menjalankannya lebih lambat (cold start home ±1,5 dtk vs ±1,1 dtk di headunit K2401).
# DEBUGGABLE=1 ./build.sh install: build pengembangan debuggable, untuk uji TANYA lewat adb (lihat AskActivity).
DEBUG_FLAG=""; [ "$MODE" != "release" ] && [ "${DEBUGGABLE:-0}" = "1" ] && DEBUG_FLAG="--debug-mode"
# PKG=com.ashafizullah.klikdash.demo ./build.sh release: salinan dengan nama paket lain (mis. rekam tutorial "pasang pertama kali"
# tanpa menghapus KlikDash yang terpasang).
[ -n "${PKG:-}" ] && DEBUG_FLAG="$DEBUG_FLAG --rename-manifest-package $PKG"
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml -A build/assets -0 ttf $DEBUG_FLAG \
    --min-sdk-version 24 --target-sdk-version 29 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java build/gen build/res.zip

javac --release 8 -encoding UTF-8 -classpath "$JAR" \
    -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 24 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')

cp build/base.apk build/unaligned.apk
(cd build/dex && zip -q ../unaligned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unaligned.apk build/aligned.apk

# Password keystore disimpan di macOS Keychain (servis "klikdash-keystore"), atau lewat env KLIKDASH_KS_PASS
# (mis. di CI). Diteruskan ke keytool/apksigner lewat environment, tidak muncul di argumen perintah.
KC_SERVICE=klikdash-keystore
export KS_PASS=${KLIKDASH_KS_PASS:-$(security find-generic-password -a klikdash -s "$KC_SERVICE" -w 2>/dev/null || true)}

if [ ! -f klikdash.keystore ]; then
    if [ -n "$KS_PASS" ]; then
        echo "klikdash.keystore tidak ada, tapi password-nya ada di Keychain. Pulihkan keystore dari cadangan;" >&2
        echo "kunci baru akan membuat APK tidak bisa meng-update aplikasi yang sudah terpasang." >&2
        exit 1
    fi
    # Clone baru: buat kunci sendiri dengan password acak yang langsung disimpan ke Keychain.
    KS_PASS=$(openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-32)
    security add-generic-password -a klikdash -s "$KC_SERVICE" -l "KlikDash APK keystore" -w "$KS_PASS" -U
    keytool -genkeypair -keystore klikdash.keystore -storepass:env KS_PASS -keypass:env KS_PASS \
        -alias klikdash -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=KlikDash" >/dev/null 2>&1
    echo "Keystore baru dibuat: klikdash.keystore (password di Keychain: $KC_SERVICE). Cadangkan keduanya." >&2
fi
if [ -z "$KS_PASS" ]; then
    echo "Password keystore tidak ditemukan di Keychain ($KC_SERVICE) maupun env KLIKDASH_KS_PASS." >&2
    exit 1
fi

"$BT/apksigner" sign --ks klikdash.keystore --ks-pass env:KS_PASS --key-pass env:KS_PASS \
    --out build/klikdash.apk build/aligned.apk

if [ "$MODE" = "release" ]; then
    cp build/klikdash.apk "build/klikdash-$VERSION_NAME.apk"
    unzip -l "build/klikdash-$VERSION_NAME.apk" | grep -qE "assets/quran(-en)?\.txt" && { echo "teks Al-Qur'an ikut di APK rilis!" >&2; exit 1; }
    ls -lh "build/klikdash-$VERSION_NAME.apk"
else
    ls -lh build/klikdash.apk
fi

if [ "$MODE" = "install" ]; then
    adb -s "$DEVICE" install -r build/klikdash.apk
    # AOT sekarang juga: dexopt latar menunggu perangkat diam + dicas, headunit hampir tidak pernah begitu (lihat Tweaks.compileAsync).
    adb -s "$DEVICE" shell cmd package compile -m speed -f com.ashafizullah.klikdash || true
    adb -s "$DEVICE" shell pm grant com.ashafizullah.klikdash android.permission.ACCESS_FINE_LOCATION
    adb -s "$DEVICE" shell pm grant com.ashafizullah.klikdash android.permission.ACCESS_COARSE_LOCATION
    adb -s "$DEVICE" shell pm grant com.ashafizullah.klikdash android.permission.ACCESS_BACKGROUND_LOCATION || true # Android 10+
    adb -s "$DEVICE" shell cmd package set-home-activity com.ashafizullah.klikdash/.HomeActivity
    adb -s "$DEVICE" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
fi
