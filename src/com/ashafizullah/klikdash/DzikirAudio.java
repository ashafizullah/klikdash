package com.ashafizullah.klikdash;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Audio dzikir untuk {@link DzikirService}: rekaman bacaan dari repo KlikDash (audio/dzikir, dibuat dengan
 * tools/build_dzikir_audio.py) dan ayat murattal Alafasy dari everyayah.com, yang disimpan di folder audio Al-Qur'an
 * supaya dipakai bersama. APK tidak membawanya: diunduh sekali (±6 MB) lalu bisa diputar tanpa internet.
 * audio/dzikir/index.json memuat SHA-1 tiap rekaman; rekaman yang diperbaiki di repo terdeteksi sebagai pembaruan
 * dan hanya file itu yang diunduh ulang.
 */
final class DzikirAudio {
    static final String BASE = "https://raw.githubusercontent.com/ashafizullah/klikdash/main/audio/dzikir/";
    static final String ALAFASY = "https://everyayah.com/data/Alafasy_128kbps/";
    static final String QURAN = "alafasy/";

    // Status unduhan, dibaca UI lewat DzikirService.listeners (thread utama).
    static boolean downloading, failed, update;
    static int done, total;
    /** Tawaran pembaruan sudah ditampilkan sekali di proses ini; jangan ditanyakan tiap halaman dibuka. */
    static boolean updateAsked;

    private static final Handler main = new Handler(Looper.getMainLooper());

    private DzikirAudio() { }

    static File dir(Context c) { return new File(c.getExternalFilesDir(null), "dzikir/audio"); }

    /** File untuk satu potongan audio di field "audio" dzikir.json: "alafasy/002255" atau nama rekaman. */
    static File file(Context c, String clip) {
        return clip.startsWith(QURAN) ? new File(QuranService.audioDir(c), clip.substring(QURAN.length()) + ".mp3")
                : new File(dir(c), clip + ".mp3");
    }

    static String url(String clip) {
        return clip.startsWith(QURAN) ? ALAFASY + clip.substring(QURAN.length()) + ".mp3" : BASE + clip + ".mp3";
    }

    /** Semua potongan audio dzikir pagi & petang, tanpa duplikat. */
    static Set<String> clips(Context c) {
        Set<String> s = new LinkedHashSet<>();
        JSONObject d = DzikirActivity.data(c);
        for (String p : new String[]{DzikirActivity.PAGI, DzikirActivity.PETANG}) {
            JSONArray arr = d.optJSONArray(p);
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONArray a = arr.optJSONObject(i).optJSONArray("audio");
                for (int k = 0; a != null && k < a.length(); k++) s.add(a.optString(k));
            }
        }
        return s;
    }

    static boolean ready(Context c) {
        for (String clip : clips(c)) if (!file(c, clip).exists()) return false;
        return true;
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("dzikir", Context.MODE_PRIVATE); }

    /**
     * Unduh audio yang belum ada atau berubah di repo; {@code all}: unduh ulang semua rekaman. Berjalan di thread
     * sendiri; kemajuan lewat {@link #done}/{@link #total} dan DzikirService.listeners.
     */
    static void download(Context ctx, boolean all) {
        if (downloading) return;
        Context c = ctx.getApplicationContext();
        downloading = true;
        failed = false;
        done = total = 0;
        DzikirService.notifyListeners();
        new Thread(() -> {
            boolean ok = true;
            try {
                JSONObject index = new JSONObject(QuranData.fetch(BASE + "index.json")).getJSONObject("files");
                SharedPreferences p = prefs(c);
                List<String> todo = new ArrayList<>();
                for (String clip : clips(c)) {
                    boolean quran = clip.startsWith(QURAN);
                    if (!quran && !index.has(clip)) throw new IOException("Rekaman " + clip + " tidak ada di index.json");
                    String sha = quran ? null : index.getJSONObject(clip).getString("sha1");
                    if (!file(c, clip).exists() || (!quran && (all || !sha.equals(p.getString("sha_" + clip, null))))) {
                        todo.add(clip);
                    }
                }
                main.post(() -> { total = todo.size(); DzikirService.notifyListeners(); });
                for (String clip : todo) {
                    JSONObject meta = clip.startsWith(QURAN) ? null : index.getJSONObject(clip);
                    fetch(c, clip, meta);
                    if (meta != null) p.edit().putString("sha_" + clip, meta.getString("sha1")).apply();
                    main.post(() -> { done++; DzikirService.notifyListeners(); });
                }
            } catch (Exception e) {
                android.util.Log.w("KlikDash", "Unduh audio dzikir gagal", e);
                ok = false;
            }
            final boolean fine = ok;
            main.post(() -> {
                downloading = false;
                failed = !fine;
                if (fine) update = false;
                DzikirService.notifyListeners();
            });
        }, "dzikir-download").start();
    }

    /** Cek di latar apakah ada rekaman yang diperbaiki di repo; hasilnya di {@link #update}. Offline: diam saja. */
    static void checkUpdate(Context ctx) {
        if (downloading) return;
        Context c = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject index = new JSONObject(QuranData.fetch(BASE + "index.json")).getJSONObject("files");
                SharedPreferences p = prefs(c);
                boolean changed = false;
                for (Iterator<String> it = index.keys(); it.hasNext(); ) {
                    String clip = it.next();
                    if (!index.getJSONObject(clip).getString("sha1").equals(p.getString("sha_" + clip, null))) changed = true;
                }
                final boolean u = changed;
                main.post(() -> {
                    if (u != update) { update = u; DzikirService.notifyListeners(); }
                });
            } catch (Exception e) {
                android.util.Log.i("KlikDash", "Cek pembaruan audio dzikir: " + e);
            }
        }, "dzikir-check").start();
    }

    /** Unduh satu file; rekaman diperiksa ukuran & SHA-1-nya. File lama baru diganti setelah unduhan utuh. */
    private static void fetch(Context c, String clip, JSONObject meta) throws Exception {
        File dst = file(c, clip), dir = dst.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(dst.getPath() + ".part" + Thread.currentThread().getId());
        HttpURLConnection con = (HttpURLConnection) new URL(url(clip)).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", "KlikDash");
        try {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode() + " " + clip);
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            long n = 0;
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32768];
                for (int k; (k = in.read(buf)) > 0; n += k) {
                    out.write(buf, 0, k);
                    sha.update(buf, 0, k);
                }
            }
            if (meta != null && (n != meta.getLong("size") || !hex(sha.digest()).equals(meta.getString("sha1")))) {
                throw new IOException("Rekaman " + clip + " tidak utuh");
            }
            if (!tmp.renameTo(dst)) throw new IOException("Gagal menyimpan " + dst);
        } finally {
            con.disconnect();
            tmp.delete();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02x", x));
        return s.toString();
    }
}
