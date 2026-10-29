package com.ashafizullah.klikdash;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Pengaturan utama KlikDash: "klikdash" di penyimpanan DE (bisa dibaca saat direct boot). Selalu buka lewat sini.
 *
 * Headunit dengan fast boot memulihkan isi RAM lama saat ACC on, termasuk salinan pengaturan di memori proses ini.
 * Menulis apa saja dari salinan lama itu (mis. {@link Clock#set} saat jam disinkronkan begitu ACC on) menimpa seluruh
 * file dengan isi lama: tema, ubin, dan setelan lain kembali seperti saat salinan itu dibuat. MODE_MULTI_PROCESS
 * membaca ulang file bila berubah di disk setiap kali getSharedPreferences dipanggil, dan edit() menunggu bacaan itu.
 */
final class Prefs {
    private Prefs() { }

    @SuppressWarnings("deprecation")
    static SharedPreferences of(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("klikdash", Context.MODE_MULTI_PROCESS);
    }
}
