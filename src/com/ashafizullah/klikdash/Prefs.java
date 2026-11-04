package com.ashafizullah.klikdash;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.Set;

/**
 * Pengaturan utama KlikDash: "klikdash" di penyimpanan DE (bisa dibaca saat direct boot). Selalu buka lewat sini.
 *
 * Headunit dengan fast boot memulihkan isi RAM lama saat ACC on, termasuk salinan pengaturan di memori proses ini.
 * Menulis apa saja dari salinan lama itu (mis. {@link Clock#set} saat jam disinkronkan begitu ACC on) menimpa seluruh
 * file dengan isi lama: tema, ubin, dan setelan lain kembali seperti saat salinan itu dibuat. MODE_MULTI_PROCESS
 * membaca ulang file bila berubah di disk setiap kali getSharedPreferences dipanggil, dan edit() menunggu bacaan itu.
 *
 * Cadangan: di sebagian headunit (8227L, laporan PR #3) file ini terbaca kosong saat boot, lalu tulisan pertama sesudahnya
 * (homeRotation, clockOkBoot, lat/lon) menyimpan isi kosong itu dan semua pengaturan pengguna hilang. Maka isinya
 * dicerminkan ke "klikdash_backup" (beberapa detik setelah berubah), dan kunci {@link #MARK} menandai isi yang sehat.
 * Dibuka tanpa MARK padahal cadangan punya: kunci yang tidak ada diambil dari cadangan sebelum siapa pun menulis.
 */
final class Prefs {
    private Prefs() { }

    private static final String NAME = "klikdash", BACKUP = "klikdash_backup";
    /** Ada di pengaturan yang sehat dan di cadangan; tidak pernah dihapus. */
    static final String MARK = "prefsGuard";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Pengaturan yang dicerminkan (satu instance per proses, kecuali setelah moveSharedPreferencesFrom). */
    private static SharedPreferences watched;
    private static Context de;
    private static boolean unreadableReported;
    private static final Runnable SNAPSHOT = Prefs::snapshot;
    // Disimpan di field: SharedPreferences hanya memegang listener lewat referensi lemah.
    private static final SharedPreferences.OnSharedPreferenceChangeListener ON_CHANGE = (p, key) -> {
        MAIN.removeCallbacks(SNAPSHOT);
        MAIN.postDelayed(SNAPSHOT, 3000);
    };

    @SuppressWarnings("deprecation")
    static SharedPreferences of(Context c) {
        SharedPreferences p = c.createDeviceProtectedStorageContext().getSharedPreferences(NAME, Context.MODE_MULTI_PROCESS);
        if (p != watched || !p.contains(MARK)) guard(c, p);
        return p;
    }

    private static synchronized void guard(Context c, SharedPreferences p) {
        if (de == null) de = c.getApplicationContext().createDeviceProtectedStorageContext();
        String report = p.contains(MARK) ? null : heal(p);
        if (p != watched) {
            p.registerOnSharedPreferenceChangeListener(ON_CHANGE);
            watched = p;
            MAIN.postDelayed(SNAPSHOT, 3000); // cadangan pertama, juga untuk versi sebelum ada cadangan
        }
        if (report != null && p.getBoolean(ErrorLog.PREF, true)) ErrorLog.report(c, report, "Prefs.heal");
    }

    /** Pengaturan tanpa MARK: pulihkan dari cadangan, atau tandai sehat bila belum ada cadangan. Hasil: isi laporan. */
    private static String heal(SharedPreferences p) {
        SharedPreferences b = de.getSharedPreferences(BACKUP, Context.MODE_PRIVATE);
        if (b.contains(MARK)) {
            Map<String, ?> cur = p.getAll();
            SharedPreferences.Editor e = p.edit();
            int n = 0;
            // Hanya kunci yang tidak ada: yang sudah ditulis sejak boot (rotasi, jam, lokasi) lebih baru dari cadangan.
            for (Map.Entry<String, ?> en : b.getAll().entrySet()) {
                if (!cur.containsKey(en.getKey()) && put(e, en.getKey(), en.getValue())) n++;
            }
            e.commit();
            return "prefs restored from backup: " + n + " keys restored, " + cur.size() + " kept " + cur.keySet();
        }
        long size = new java.io.File(de.getDataDir(), "shared_prefs/" + BACKUP + ".xml").length();
        if (size > 100) {
            // Cadangan berisi di disk tapi terbaca tanpa MARK: sedang tidak terbaca. Jangan tandai sehat, supaya
            // snapshot tidak menimpanya dengan isi kosong ini. Dicoba lagi tiap dibuka; dilaporkan sekali per proses.
            if (unreadableReported) return null;
            unreadableReported = true;
            return "prefs and backup unreadable: backup " + size + " bytes, prefs " + p.getAll().keySet();
        }
        p.edit().putBoolean(MARK, true).commit(); // baru dipasang, atau versi sebelum ada cadangan
        return null;
    }

    /** Cerminkan pengaturan yang sehat ke cadangan (di thread utama, beberapa detik setelah berubah). */
    private static void snapshot() {
        SharedPreferences p = watched;
        if (p == null || de == null || !p.contains(MARK)) return;
        SharedPreferences.Editor e = de.getSharedPreferences(BACKUP, Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, ?> en : p.getAll().entrySet()) put(e, en.getKey(), en.getValue());
        e.apply();
    }

    @SuppressWarnings("unchecked")
    private static boolean put(SharedPreferences.Editor e, String k, Object v) {
        if (v instanceof String) e.putString(k, (String) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof Long) e.putLong(k, (Long) v);
        else if (v instanceof Float) e.putFloat(k, (Float) v);
        else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Set) e.putStringSet(k, (Set<String>) v);
        else return false;
        return true;
    }
}
