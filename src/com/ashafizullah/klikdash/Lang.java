package com.ashafizullah.klikdash;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/**
 * Bahasa antarmuka (Indonesia bawaan, atau English), dipilih di Pengaturan dan tidak mengikuti bahasa sistem.
 * Teks ada di res/values (Indonesia) dan res/values-en. Tanpa AndroidX, maka seperti AppCompat sebelum API 33:
 * tiap aktivitas dan layanan memanggil {@link #wrap} di attachBaseContext; receiver dan context aplikasi memakai
 * {@code Lang.wrap(c).getString(...)}. Berganti bahasa menaikkan {@link Theme#version}, sehingga aktivitas lain
 * dibangun ulang lewat {@link Theme#stale} seperti saat tema berganti. Disimpan di prefs DE "klikdash" (key "lang").
 */
final class Lang {
    static final String ID = "id", EN = "en";
    static final String[] CODES = {ID, EN};

    static String code;
    private static Locale locale = new Locale("id", "ID");

    /**
     * Nama yang dipakai banyak kelas, dimuat ulang setiap bahasa berganti (huruf besar, seperti tampilan home). Hari
     * dan bulan sengaja bukan SimpleDateFormat dengan locale: data ICU Indonesia dimuat saat proses start dan menunda
     * home 60–145 ms di headunit (atrace).
     */
    static String[] prayers, hijriMonths, days, months;

    private Lang() { }

    static boolean en() { return EN.equals(code); }

    static Locale locale() { return locale; }

    static String saved(Context c) {
        String s = prefs(c).getString("lang", ID);
        return EN.equals(s) ? EN : ID;
    }

    /** Muat bahasa tersimpan tanpa aktivitas (mis. dari BroadcastReceiver). */
    static void load(Context c) { set(c, saved(c)); }

    static void save(Context c, String code) {
        prefs(c).edit().putString("lang", code).apply();
        set(c, code);
        renameChannels(c);
    }

    /** Context dengan bahasa tersimpan; dipanggil di attachBaseContext dan sebelum getString di luar aktivitas. */
    static Context wrap(Context base) {
        load(base);
        return wrap(base, code);
    }

    /** Context dengan bahasa tertentu, mis. bahasa asisten AI yang terpisah dari bahasa antarmuka. */
    static Context wrap(Context base, String code) {
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocales(new LocaleList(localeOf(code)));
        return base.createConfigurationContext(conf);
    }

    static Locale localeOf(String code) { return EN.equals(code) ? Locale.US : new Locale("id", "ID"); }

    private static void set(Context c, String code) {
        if (code.equals(Lang.code)) return;
        Lang.code = code;
        locale = localeOf(code);
        Resources r = wrap(c, code).getResources();
        prayers = r.getStringArray(R.array.prayer_names);
        hijriMonths = r.getStringArray(R.array.hijri_months);
        days = r.getStringArray(R.array.day_names);
        months = r.getStringArray(R.array.month_names);
        Theme.version++;
    }

    /** Channel yang sudah ada tidak berganti nama sendiri; membuatnya lagi dengan ID sama hanya mengganti namanya. */
    private static void renameChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Context w = wrap(c, code);
        rename(nm, PrayerAlarm.CHANNEL, w.getString(R.string.channel_prayer));
        rename(nm, QuranService.CHANNEL, w.getString(R.string.channel_quran));
        rename(nm, BeamService.CHANNEL, w.getString(R.string.beam_channel));
    }

    private static void rename(NotificationManager nm, String id, String name) {
        NotificationChannel ch = nm.getNotificationChannel(id);
        if (ch == null) return;
        nm.createNotificationChannel(new NotificationChannel(id, name, ch.getImportance()));
    }

    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("klikdash", Context.MODE_PRIVATE);
    }
}
