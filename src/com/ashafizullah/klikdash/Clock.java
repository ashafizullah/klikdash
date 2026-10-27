package com.ashafizullah.klikdash;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Jam sistem headunit. Headunit tanpa baterai jam menyala pada 1970, lalu Android memasang tanggal build firmware
 * (di NWD K2401: 23-07-2024 11.32) dan baru dibetulkan NTP bila ada internet. Tanpa internet jam itu bertahan
 * sepanjang perjalanan: jadwal sholat ikut salah dan pengingat muncul pada waktu yang keliru (Dzuhur pukul 15.00).
 *
 * Jam dianggap benar ({@link #ok}) bila tidak lebih awal dari saat APK ini dibuat ({@code Built.TIME}), dan pada boot ini
 * sudah pernah diatur (ACTION_TIME_CHANGED, lihat {@link #set}) atau cocok dengan waktu satelit dari fix GPS. Bila
 * tidak cocok dan waktu otomatis menyala, {@link #gps} memasang waktu satelit sebagai jam sistem lewat ADB lokal
 * ({@link Tweaks}; shell punya izin SET_TIME); tanpa ADB, jam dibiarkan dan pengingat sholat ditahan.
 */
final class Clock {
    /** Selisih terbesar jam sistem dengan waktu fix GPS yang masih dianggap jam benar. */
    static final long SKEW_MS = 2 * 60_000L;

    private Clock() { }

    /** Pengaturan › Launcher › Format jam: true = 12 jam (AM/PM), bawaan 24 jam. */
    static boolean twelve(SharedPreferences p) { return p.getBoolean("clock12", false); }

    /** Jam dari menit sejak tengah malam: "14:05", atau "2:05" untuk 12 jam (AM/PM terpisah, lihat {@link #ampm}). */
    static String hm(boolean twelve, int minuteOfDay) {
        int h = minuteOfDay / 60 % 24, m = minuteOfDay % 60;
        return (twelve ? String.valueOf(h % 12 == 0 ? 12 : h % 12) : HomeActivity.two(h)) + ":" + HomeActivity.two(m);
    }

    static String ampm(int minuteOfDay) { return minuteOfDay / 60 % 24 < 12 ? "AM" : "PM"; }

    /** Jam terbukti benar pada boot ini (cache {@link #ok} untuk proses ini). */
    private static volatile boolean ok;
    /** elapsedRealtime percobaan terakhir memasang jam dari GPS; paling sering sekali semenit. */
    private static volatile long setAt = -60_000;

    static boolean ok(Context c) {
        if (!plausible(System.currentTimeMillis())) return false;
        if (ok) return true;
        int boot = bootCount(c);
        return ok = boot >= 0 && prefs(c).getInt("clockOkBoot", -1) == boot;
    }

    /** Jam baru diatur (ACTION_TIME_CHANGED): NTP, pengguna, atau {@link #gps}. Tanggal build firmware tidak dihitung. */
    static void set(Context c) {
        if (!plausible(System.currentTimeMillis())) return;
        ok = true;
        prefs(c).edit().putInt("clockOkBoot", bootCount(c)).apply();
    }

    /** Fix GPS membawa waktu satelit: cocok dengan jam sistem berarti jam benar, tidak cocok berarti jam dibetulkan. */
    static void gps(Context c, Location l) {
        if (!LocationManager.GPS_PROVIDER.equals(l.getProvider())) return; // network/fused: waktunya dari jam sistem
        long now = System.currentTimeMillis();
        long sat = l.getTime() + (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000;
        if (!plausible(sat)) return; // fix tanpa waktu, atau GPS week rollover (20 tahun mundur)
        if (Math.abs(sat - now) <= SKEW_MS) {
            if (!ok) set(c);
            return;
        }
        if (Settings.Global.getInt(c.getContentResolver(), Settings.Global.AUTO_TIME, 1) == 0) return; // jam diatur sendiri
        long t = SystemClock.elapsedRealtime();
        if (t - setAt < 60_000) return;
        setAt = t;
        // IAlarmManager.setTime (transaksi 2) sejak Android 7; Android mengirim ACTION_TIME_CHANGED, lalu home memanggil set.
        Tweaks.shellAsync("service call alarm 2 i64 " + sat);
    }

    /**
     * Fix GPS juga sampai saat aplikasi lain di depan (Maps, Zlink). Android 10 tidak mengirim lokasi ke aplikasi latar
     * tanpa ACCESS_BACKGROUND_LOCATION, dan membatasinya sekali per 10 menit (di headunit) kecuali paket dikecualikan.
     * Keduanya diberikan lewat ADB lokal; tanpa ADB dilewati.
     */
    static void allowBackgroundGps(Context c) {
        String key = "location_background_throttle_package_whitelist", pkg = c.getPackageName();
        String list = Settings.Global.getString(c.getContentResolver(), key);
        StringBuilder cmd = new StringBuilder();
        if (list == null || !java.util.Arrays.asList(list.split(",")).contains(pkg)) {
            cmd.append("settings put global ").append(key).append(' ')
                    .append(list == null || list.isEmpty() ? pkg : list + "," + pkg);
        }
        if (android.os.Build.VERSION.SDK_INT >= 29 && c.checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (cmd.length() > 0) cmd.append("; ");
            cmd.append("pm grant ").append(pkg).append(" android.permission.ACCESS_BACKGROUND_LOCATION");
        }
        if (cmd.length() > 0) Tweaks.shellAsync(cmd.toString());
    }

    /** Tidak lebih awal dari saat APK ini dibuat; tanggal build firmware dan GPS week rollover jauh sebelumnya. */
    private static boolean plausible(long time) {
        return time >= Built.TIME;
    }

    /** Nomor boot (-1 bila firmware tidak mencatatnya): tanda "jam benar" tersimpan hanya berlaku untuk boot yang sama. */
    private static int bootCount(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("klikdash", Context.MODE_PRIVATE);
    }
}
