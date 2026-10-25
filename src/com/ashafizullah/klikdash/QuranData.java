package com.ashafizullah.klikdash;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Teks Al-Qur'an offline (sumber: equran.id — mushaf standar Kemenag, transliterasi & terjemahan Kemenag).
 * APK rilis tidak membawa teks: saat pertama dibuka, teks diunduh sekali ke filesDir/quran.txt
 * ({@link #download}). Build pengembang boleh menyertakan assets/quran.txt sebagai cadangan.
 * Format baris, dipisah tab:
 *   S  nomor  namaLatin  namaArab  arti  tempatTurun  jumlahAyat
 *   A  surah  ayat  arab  latin  indonesia
 * Terjemahan English (Saheeh International dari alquran.cloud) ada di file terpisah filesDir/quran-en.txt, diunduh
 * hanya bila bahasa antarmuka English ({@link #downloadEn}):
 *   S  nomor  arti  tempatTurun
 *   A  surah  ayat  english
 */
final class QuranData {
    static final int SURAH_COUNT = 114, AYAH_TOTAL = 6236;

    /** Jumlah ayat per surah; dipakai service tanpa perlu memuat teks. */
    static final int[] COUNTS = {7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111,
            110, 98, 135, 112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88, 75, 85, 54, 53,
            89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24, 13, 14, 11, 11, 18, 12, 12, 30, 52,
            52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36, 25, 22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8,
            19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6};

    static final String BISMILLAH = "بِسْمِ اللّٰهِ الرَّحْمٰنِ الرَّحِيْمِ";
    static final String BISMILLAH_ID = "Dengan nama Allah Yang Maha Pengasih lagi Maha Penyayang.";
    static final String BISMILLAH_EN = "In the name of Allah, the Entirely Merciful, the Especially Merciful.";

    static final class Surah {
        int no, count, first;
        String latin, arab, arti, tempat;
        /** Dari quran-en.txt; null bila terjemahan English belum dimuat. */
        String artiEn, tempatEn;
    }

    static volatile Surah[] surah;
    static String[] arab, latin, indo;
    /** Terjemahan English; null bila belum diunduh atau belum dimuat ({@link #loadEn}). */
    static volatile String[] en;

    private QuranData() { }

    static int count(int s) { return COUNTS[s - 1]; }

    /** Surah selain Al-Fatihah dan At-Taubah diawali basmalah yang bukan bagian dari ayat 1. */
    static boolean hasBismillah(int s) { return s != 1 && s != 9; }

    static boolean loaded() { return surah != null; }

    static synchronized void load(Context c) throws IOException {
        if (surah != null) return;
        Surah[] ss = new Surah[SURAH_COUNT];
        String[] ar = new String[AYAH_TOTAL], la = new String[AYAH_TOTAL], id = new String[AYAH_TOTAL];
        int k = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(openText(c), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f[0].equals("S")) {
                    Surah s = new Surah();
                    s.no = Integer.parseInt(f[1]);
                    s.latin = f[2];
                    s.arab = f[3];
                    s.arti = f[4];
                    s.tempat = f[5];
                    s.count = Integer.parseInt(f[6]);
                    s.first = k;
                    ss[s.no - 1] = s;
                } else {
                    ar[k] = f[3];
                    la[k] = f[4];
                    id[k] = f[5];
                    k++;
                }
            }
        }
        arab = ar;
        latin = la;
        indo = id;
        surah = ss;
    }

    // ---------------------------------------------------------------- unduh teks

    static final String API = "https://equran.id/api/v2/surat/";

    interface Progress { void update(int done, int total); }

    static File textFile(Context c) { return new File(c.getFilesDir(), "quran.txt"); }

    /** Teks sudah ada: hasil unduhan, atau tertanam di APK (build pengembang). */
    static boolean available(Context c) {
        if (textFile(c).length() > 0) return true;
        try (InputStream in = c.getAssets().open("quran.txt")) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static InputStream openText(Context c) throws IOException {
        File f = textFile(c);
        return f.length() > 0 ? new FileInputStream(f) : c.getAssets().open("quran.txt");
    }

    /**
     * Unduh 114 surah dari equran.id dan tulis quran.txt dengan pembersihan yang sama seperti
     * tools/build_quran.py. Ditulis ke file sementara dulu, jadi unduhan yang gagal tidak merusak apa pun.
     * Jalankan di thread latar.
     */
    static void download(Context c, Progress p) throws Exception {
        File dst = textFile(c), tmp = new File(dst.getPath() + ".part");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (int n = 1; n <= SURAH_COUNT; n++) {
                JSONObject d = new JSONObject(fetch(API + n)).getJSONObject("data");
                JSONArray ayat = d.getJSONArray("ayat");
                if (ayat.length() != COUNTS[n - 1]) throw new IOException(c.getString(R.string.quran_err_count, n));
                w.write("S\t" + d.getInt("nomor") + "\t" + clean(d.getString("namaLatin")) + "\t"
                        + clean(d.getString("nama")) + "\t" + clean(d.getString("arti")) + "\t"
                        + clean(d.getString("tempatTurun")) + "\t" + d.getInt("jumlahAyat") + "\n");
                for (int i = 0; i < ayat.length(); i++) {
                    JSONObject a = ayat.getJSONObject(i);
                    w.write("A\t" + n + "\t" + a.getInt("nomorAyat") + "\t" + arab(a.getString("teksArab")) + "\t"
                            + clean(a.getString("teksLatin")) + "\t" + clean(a.getString("teksIndonesia")) + "\n");
                }
                p.update(n, SURAH_COUNT);
            }
        }
        if (!tmp.renameTo(dst)) throw new IOException(c.getString(R.string.quran_err_save));
    }

    // ---------------------------------------------------------------- terjemahan English

    static final String API_EN = "https://api.alquran.cloud/v1/surah/%d/en.sahih";

    static File enFile(Context c) { return new File(c.getFilesDir(), "quran-en.txt"); }

    /** Terjemahan English sudah ada: hasil unduhan, atau tertanam di APK (build pengembang). */
    static boolean availableEn(Context c) {
        if (enFile(c).length() > 0) return true;
        try (InputStream in = c.getAssets().open("quran-en.txt")) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Muat terjemahan English ke {@link #en} dan arti/tempat English per surah. Butuh {@link #load} lebih dulu. */
    static synchronized void loadEn(Context c) throws IOException {
        if (en != null) return;
        load(c);
        String[] e = new String[AYAH_TOTAL];
        File f = enFile(c);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                f.length() > 0 ? new FileInputStream(f) : c.getAssets().open("quran-en.txt"), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p[0].equals("S")) {
                    Surah s = surah[Integer.parseInt(p[1]) - 1];
                    s.artiEn = p[2];
                    s.tempatEn = p[3];
                } else {
                    e[index(Integer.parseInt(p[1]), Integer.parseInt(p[2]))] = p[3];
                }
            }
        }
        en = e;
    }

    /** Seperti {@link #download}, untuk terjemahan Saheeh International dari alquran.cloud (±1 MB). */
    static void downloadEn(Context c, Progress p) throws Exception {
        File dst = enFile(c), tmp = new File(dst.getPath() + ".part");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (int n = 1; n <= SURAH_COUNT; n++) {
                JSONObject d = new JSONObject(fetch(String.format(java.util.Locale.US, API_EN, n))).getJSONObject("data");
                JSONArray ayat = d.getJSONArray("ayahs");
                if (ayat.length() != COUNTS[n - 1]) throw new IOException(c.getString(R.string.quran_err_count, n));
                w.write("S\t" + n + "\t" + clean(d.getString("englishNameTranslation")) + "\t"
                        + clean(d.getString("revelationType")) + "\n");
                for (int i = 0; i < ayat.length(); i++) {
                    JSONObject a = ayat.getJSONObject(i);
                    w.write("A\t" + n + "\t" + a.getInt("numberInSurah") + "\t" + clean(a.getString("text")) + "\n");
                }
                p.update(n, SURAH_COUNT);
            }
        }
        if (!tmp.renameTo(dst)) throw new IOException(c.getString(R.string.quran_err_save));
    }

    static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "KlikDash");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** Sama dengan clean() di tools/build_quran.py. */
    static String clean(String s) {
        s = s.replace('\t', ' ').replaceAll("[\\x00-\\x08\\x0b-\\x1f\\x7f-\\x9f]", "");
        return strip(s.replaceAll(WS + "+", " "));
    }

    /**
     * Sama dengan arab() di tools/build_quran.py: hapus penanda khas mushaf Kemenag yang tidak ada di font
     * Amiri dan tidak dibaca (U+08D6 ruku', U+06D4 pemisah juz, U+06D5 tumpuan waqaf), lalu tempelkan tanda
     * waqaf (U+06D6..U+06DC) ke kata sebelumnya.
     */
    static String arab(String s) {
        s = clean(s).replaceAll("[\u08D6\u06D4\u06D5]", "");
        s = s.replaceAll(WS + "+([\u06D6-\u06DC])", "$1");
        return strip(s.replaceAll(WS + "+", " "));
    }

    /**
     * Spasi menurut str.isspace() Python, ditulis eksplisit: \\s di Java desktop hanya ASCII, sedangkan regex
     * ICU di Android tidak mendukung flag (?U). Dengan daftar ini hasilnya sama di keduanya dan di Python.
     */
    private static final String WS =
            "[\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]";

    /** Seperti str.strip() Python, bukan String.trim(). */
    private static String strip(String s) { return s.replaceAll("^" + WS + "+|" + WS + "+$", ""); }

    static Surah get(int s) { return surah[s - 1]; }

    static int index(int s, int a) { return surah[s - 1].first + a - 1; }
}
