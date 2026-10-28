# KlikDash untuk developer

[English](DEVELOPMENT.md) | [Bahasa Indonesia](DEVELOPMENT.id.md) · kembali ke [README](../README.id.md)

## Struktur

```
klikdash/
├── AndroidManifest.xml
├── build.sh                  build + pasang ke headunit
├── klikdash.keystore         kunci tanda tangan APK (jangan hilang, lihat Catatan)
├── src/com/ashafizullah/klikdash/   dikelompokkan per fitur; tiap kelas diawali komentar penjelasan
│   ├── Home*.java, Hijri, Welcome, ScreenLock, Screensaver   launcher: ubin, laci, panel jam, tanggal Hijriah, sambutan, kunci PIN, screensaver
│   ├── Prayer*.java, Clock      jadwal sholat (parameter Kemenag RI), pengingat sholat, jam dari GPS
│   ├── Quran*.java              mushaf, pemutar murattal di latar
│   ├── MediaNotch, MediaListener   notch pemutar di home: murattal, dzikir, musik aplikasi lain (MediaSession)
│   ├── Dzikir*.java             dzikir pagi & petang: penghitung terpandu, pemutar audio di latar, unduhan audio
│   ├── SettingsActivity, PowerPanel   Pengaturan: launcher, hemat daya
│   ├── Ask*, Ai*.java           TANYA AI: suara, perintah mobil, profil & kunci API terenkripsi, tab pengaturan
│   ├── Beam*.java               KlikBeam (beta): mirror layar HP lewat WiFi
│   ├── Theme, Lang, Key, Notice   palet tema, bahasa antarmuka, tombol, notifikasi
│   └── Updater, UpdatedReceiver, HomeSwitch, Tweaks, Compat   update, tampilan utama, ADB lokal, Android 7–8
├── res/                      tema, ikon vektor, sertifikat root ISRG untuk Android 7.0 (network_security.xml)
├── assets/
│   ├── dzikir.json           dzikir pagi & petang, dibangun dari KlikBand (tools/build_dzikir.mjs)
│   ├── quran.txt             6.236 ayat (Arab, Latin, terjemahan), 3,1 MB — TIDAK ada di repo, dibangun sendiri
│   ├── display.ttf           Big Shoulders (jam, label)
│   ├── mono.ttf              Martian Mono (angka kecil)
│   ├── arabic.ttf            Amiri Quran
│   ├── serif.ttf             Literata (terjemahan; di-instance & subset)
│   └── licenses/             teks SIL OFL tiap font (ikut terbawa di APK)
├── audio/dzikir/             rekaman dzikir (MP3) + index.json, diunduh aplikasi — TIDAK ikut APK
├── tools/build_quran.py      bangun quran.txt dari JSON equran.id
├── tools/build_dzikir_audio.py  potong & samakan level rekaman mentah ke audio/dzikir/ (butuh ffmpeg, numpy)
├── tools/build_static_fonts.py  bangun assets/static/ (butuh fonttools)
├── design/                   header README (header.html → header.png / header.id.png) + screenshot asli
└── LICENSE                   MIT (kode); font tetap OFL

../quran-data/                tidak ikut APK
├── text/                     JSON per surah dari equran.id
├── zips/  audio/             murattal Alafasy 128 kbps (everyayah.com), 6.236 file mp3
└── intro/                    file SSS000.mp3 (tidak dipakai)
```

## Kebutuhan

- macOS dengan JDK 17 dan Android SDK (`build-tools/36.0.0`, `platforms/android-35`)
  di `~/Library/Android/sdk` atau `$ANDROID_HOME`.
- `adb`. Headunit dihubungkan lewat **ADB WiFi** (`192.168.1.17:5555`), karena port USB headunit
  berperan sebagai host.

## Build & pasang

Pertama kali, bangun dulu `assets/quran.txt` (lihat [Teks Al-Qur'an](#teks-al-quran)); `build.sh` berhenti
dengan petunjuk bila file itu belum ada.

```bash
adb connect 192.168.1.17:5555
./build.sh              # build saja → build/klikdash.apk
./build.sh release      # APK rilis publik tanpa teks Al-Qur'an → build/klikdash-<versi>.apk
./build.sh install      # build, pasang, beri izin lokasi, jadikan home
DEVICE=1.2.3.4:5555 ./build.sh install   # kalau IP headunit berubah
DEBUGGABLE=1 ./build.sh install          # build debuggable, perlu untuk uji TANYA AI lewat adb (lihat AskActivity)
```

Selalu pasang lewat `./build.sh install`. Kalau memakai `adb install` biasa, preferensi home bisa hilang
sampai `set-home-activity` dijalankan ulang.

## Audio murattal

Aplikasi mengunduh audionya sendiri (lihat Fitur). Untuk mengisi ke-114 surah sekaligus (1,6 GB) tanpa
memakai koneksi headunit, unduh di komputer lalu kirim lewat ADB. Lokasi filenya:
`/sdcard/Android/data/com.ashafizullah.klikdash/files/quran/audio/SSSAAA.mp3`.

```bash
# unduh (sekali)
cd ../quran-data
seq -f "%03g" 1 114 | xargs -P 4 -I{} curl -fsS -C - -o zips/{}.zip \
  https://everyayah.com/data/Alafasy_128kbps/zips/{}.zip
for z in zips/*.zip; do unzip -qq -o -j "$z" '*.mp3' -d audio; done
mkdir -p intro && mv audio/*000.mp3 intro/

# kirim ke headunit (~20 menit lewat WiFi)
adb push audio /sdcard/Android/data/com.ashafizullah.klikdash/files/quran/
```

Basmalah sebelum ayat 1 (kecuali Al-Fatihah dan At-Taubah) memakai `001001.mp3`.

> ⚠️ Folder itu milik aplikasi. **Uninstall KlikDash akan menghapus audionya.** Update lewat
> `./build.sh install` aman.

## Teks Al-Qur'an

Teks mushaf dan terjemahan Kemenag **tidak disertakan di repo ini maupun di APK rilis**. Peredaran mushaf di
Indonesia diatur PMA 44/2016 (pentashihan LPMQ). Aplikasi mengunduh teksnya dari equran.id saat pertama
dipakai dan membersihkannya dengan cara yang sama seperti `tools/build_quran.py` (hasilnya identik byte per
byte), yaitu menyesuaikan beberapa tanda agar bisa ditampilkan font Amiri. Untuk build pengembang sendiri,
teksnya bisa disertakan (`./build.sh` menyertakan `assets/quran.txt` bila ada; `./build.sh release` tidak pernah):

```bash
mkdir -p ../quran-data/text
for i in $(seq 1 114); do curl -fsS -o ../quran-data/text/$i.json https://equran.id/api/v2/surat/$i; done
python3 -I tools/build_quran.py ../quran-data/text assets/quran.txt
```

Skrip ini juga membersihkan teks:
- Menghapus penanda khas mushaf Kemenag yang tidak ada di font Amiri dan tidak dibaca:
  U+08D6 (tanda ruku'), U+06D4 (pemisah juz), dan U+06D5 (tumpuan waqaf).
- Menempelkan tanda waqaf ke kata sebelumnya.
- Membuang karakter kontrol.

Terjemahan Inggris (Saheeh International, dari alquran.cloud) diunduh terpisah ke `quran-en.txt` saat tampilan
berbahasa Inggris. Untuk menyertakannya di build pengembang (tidak pernah di rilis):

```bash
python3 -I tools/build_quran.py --en assets/quran-en.txt
```

## Terjemahan antarmuka

Tampilan berbahasa Indonesia (`res/values/strings_*.xml`, bawaan) dan Inggris (`res/values-en/strings_*.xml`),
dipilih di **⚙ › Launcher › TAMPILAN › BAHASA**, tidak mengikuti bahasa sistem (`Lang.java`).
- Setiap teks UI baru masuk ke **kedua** folder dengan nama yang sama. Pakai argumen posisional (`%1$s`) dan
  `<plurals>` untuk bilangan. Jangan membandingkan atau menyimpan teks tampilan: simpan kunci terpisah.
- Aktivitas dan `QuranService` membungkus context di `attachBaseContext`. Receiver dan context aplikasi
  memanggil `Lang.wrap(c)` sebelum `getString`.
- Asisten suara punya bahasa sendiri (`AiStore`, **⚙ › Asisten AI**). Prompt dan ucapannya ada di
  `strings_assistant.xml` dan dibaca dengan `Lang.wrap(c, AiStore.lang(c))`.

## Cara kerja khusus firmware

- **Launcher default.** Firmware NWD mengabaikan pilihan home Android dan memakai
  `persist.nwd.launcher.default` (`PackageManagerService.chooseBestActivity`).
- **Direct boot.** Saat boot, penyimpanan terenkripsi (FBE) masih terkunci beberapa detik, dan hanya
  launcher yang `directBootAware` yang bisa dipilih. Karena itu `HomeActivity` dibuat directBootAware dan
  pengaturannya disimpan di penyimpanan DE. Daftar aplikasi dimuat ulang setelah `ACTION_USER_UNLOCKED`.
- **Perintah sistem.** Mematikan ksmd dan kembali ke home butuh hak sistem. `Tweaks.java` menjalankannya
  lewat ADB perangkat itu sendiri (`127.0.0.1`), tanpa mengubah partisi sistem, sehingga ADB harus aktif di
  headunit. Kalau ADB mati, kedua penyetelan ini dilewati, sedangkan fitur lain tetap jalan.
- **Headunit tidur, bukan mati.** Saat ACC off headunit hanya tidur, jadi jendela "baru menyala" untuk
  Zlink juga dimulai dari `SCREEN_ON`, `com.nwd.ACTION_AUTO_SLEEP_WAKE_STATE`, dan
  `com.nwd.android.ACTION_SYSTEM_AUTO_WAKEUP`.
- **Zlink dan WiFi.** Saat menyala, layanan Zlink menyalakan hotspot untuk CarPlay nirkabel, dan chip WiFi
  tidak bisa menjalankan hotspot dan WiFi klien bersamaan. Selama CarPlay nirkabel aktif, **WiFi akan
  mati** dan headunit tidak bisa diakses dari Mac. Untuk mengakses ADB, nyalakan WiFi secara manual.

## Catatan

- **Nama kota tidak terdeteksi otomatis**, karena geocoder di perangkat ini tidak mengembalikan hasil.
  Nama kota bisa diisi manual dan tetap dipakai selama posisi masih dalam radius 15 km:
  ```bash
  adb shell am start -n com.ashafizullah.klikdash/.HomeActivity --es lat -6.1754 --es lon 106.8272 --es city "Jakarta"
  ```
- **`klikdash.keystore`** (alias `klikdash`) wajib dicadangkan di tempat aman, misalnya lampiran password
  manager. File ini tidak ikut di repo. Password-nya ada di **macOS Keychain** (servis `klikdash-keystore`):
  `security find-generic-password -a klikdash -s klikdash-keystore -w`. Untuk CI, isi env `KLIKDASH_KS_PASS`.
  APK yang ditandatangani kunci lain tidak bisa meng-update KlikDash; harus uninstall dulu, dan uninstall
  ikut menghapus audio murattal. Clone baru tanpa keystore akan dibuatkan kunci baru oleh `build.sh`.

## Header README

`design/header.png` (EN) dan `design/header.id.png` (ID) dirender dari `design/header.html` memakai
screenshot asli headunit:

```bash
cd design
for l in en id; do
  out=header.png; [ $l = id ] && out=header.id.png
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars \
    --window-size=1280,640 --force-device-scale-factor=2 --virtual-time-budget=5000 \
    --screenshot="$PWD/$out" "file://$PWD/header.html?lang=$l"
done
```
