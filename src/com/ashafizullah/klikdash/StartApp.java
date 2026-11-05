package com.ashafizullah.klikdash;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aplikasi yang dibuka sendiri saat headunit menyala (boot atau ACC on), dipilih di ⚙ › HOME › BUKA SAAT MENYALA.
 * Opsional, bawaan tidak ada. Disimpan di prefs DE "klikdash" sebagai komponen ({@link #PREF}).
 *
 * Sambutan tampil di home: aplikasinya dibuka setelah sambutan ditutup (diketuk atau 30 detik). Bila selama menunggu
 * pengguna sudah membuka aplikasi lain, tidak dibuka. Tidak dibuka saat mobil berjalan: layar yang dimatikan lewat tombol
 * sambil menyetir lalu dinyalakan lagi juga terhitung "bangun", dan aplikasi ini tidak boleh menutupi Maps di jalan. Saat direct boot (penyimpanan CE belum terbuka) aplikasi biasa
 * belum bisa dijalankan: ditunggu sampai terbuka.
 */
final class StartApp {
    private StartApp() { }

    static final String PREF = "startApp";
    /** Sama dengan sambutan: layar dimatikan-nyalakan sebentar tidak membuka aplikasinya lagi. */
    private static final long GAP_MS = Welcome.GAP_MS;
    /** Paling lama menunggu penyimpanan CE terbuka setelah boot. */
    private static final long UNLOCK_WAIT_MS = 5 * 60_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Home yang tampil sekarang; berganti bila home dibangun ulang selama menunggu (lihat {@link #attach}). */
    private static WeakReference<HomeActivity> home = new WeakReference<>(null);
    private static long lastRun, welcomeUntil, unlockUntil;
    private static boolean pending, waited;

    static ComponentName get(SharedPreferences p) {
        String s = p.getString(PREF, null);
        return s != null ? ComponentName.unflattenFromString(s) : null;
    }

    static void set(SharedPreferences p, ComponentName cn) {
        if (cn == null) p.edit().remove(PREF).apply();
        else p.edit().putString(PREF, cn.flattenToString()).apply();
    }

    /** Nama aplikasi, atau null bila sudah tidak terpasang. */
    static String label(Context c, ComponentName cn) {
        int own = HomeApps.ownLabel(cn.getClassName());
        if (own != 0 && c.getPackageName().equals(cn.getPackageName())) return c.getString(own);
        PackageManager pm = c.getPackageManager();
        try {
            return String.valueOf(pm.getActivityInfo(cn, 0).loadLabel(pm)).trim();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Pilihan di Pengaturan: aplikasi di Semua Aplikasi (tanpa home dan Pengaturan KlikDash), urut nama. */
    static List<String[]> choices(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>(); // {komponen, nama}
        java.util.Set<String> seen = new java.util.HashSet<>();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            String cls = r.activityInfo.name;
            if (HomeActivity.class.getName().equals(cls) || SettingsActivity.class.getName().equals(cls)) continue;
            ComponentName cn = new ComponentName(r.activityInfo.packageName, cls);
            int own = HomeApps.ownLabel(cls);
            String name = own != 0 ? c.getString(own) : String.valueOf(r.loadLabel(pm)).trim();
            if (!seen.add(HomeApps.dupKey(cn, name))) continue;
            out.add(new String[]{cn.flattenToString(), name});
        }
        Collections.sort(out, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        return out;
    }

    /** Headunit baru menyala atau bangun (ACC on), setelah sambutan dijadwalkan. */
    static void schedule(HomeActivity h) {
        long now = SystemClock.elapsedRealtime();
        if (get(h.prefs) == null || (lastRun != 0 && now - lastRun < GAP_MS)) return;
        lastRun = now;
        home = new WeakReference<>(h);
        pending = true;
        waited = false;
        welcomeUntil = now + Welcome.SHOW_MS + 5_000;
        unlockUntil = now + UNLOCK_WAIT_MS;
        MAIN.removeCallbacks(TRY);
        MAIN.postDelayed(TRY, 1500); // home selesai tampil dulu
    }

    /** Home tampil (onResume): home yang dibangun ulang selama menunggu mengambil alih. */
    static void attach(HomeActivity h) {
        if (pending) home = new WeakReference<>(h);
    }

    /**
     * Mobil berjalan: kecepatan dari home (hanya selama home tampil), atau fix GPS terakhir di sistem bila masih baru,
     * mis. saat Maps di depan. Saat ACC on fix terakhir dari sebelum tidur: basi, dianggap diam.
     */
    static boolean moving(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (HomeLocation.lastKmh > 0 && now - HomeLocation.lastKmhAt < 10_000) return true;
        try {
            Location l = c.getSystemService(LocationManager.class).getLastKnownLocation(LocationManager.GPS_PROVIDER);
            return l != null && l.hasSpeed() && l.getSpeed() * 3.6f >= 3
                    && now - l.getElapsedRealtimeNanos() / 1_000_000L < 10_000;
        } catch (RuntimeException e) { // tanpa izin lokasi, atau tanpa GPS
            return false;
        }
    }

    private static final Runnable TRY = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            HomeActivity h = home.get();
            if (h == null || h.isFinishing() || h.isDestroyed()) { // sedang dibangun ulang
                if (now < welcomeUntil) MAIN.postDelayed(this, 500);
                else pending = false;
                return;
            }
            if (!h.getSystemService(UserManager.class).isUserUnlocked()) {
                if (now < unlockUntil) MAIN.postDelayed(this, 1000);
                else pending = false;
                return;
            }
            boolean welcome = h.getWindow().getDecorView().findViewWithTag(Welcome.class) != null;
            if (welcome && h.resumed && now < welcomeUntil) {
                waited = true;
                MAIN.postDelayed(this, 500);
                return;
            }
            pending = false;
            // Setelah menunggu sambutan: hanya bila home masih di depan (pengguna belum membuka aplikasi lain).
            if (waited && !h.resumed) return;
            if (moving(h)) return;
            ComponentName cn = get(h.prefs);
            if (cn == null || label(h, cn) == null) return; // dimatikan, atau aplikasinya sudah dihapus
            h.apps.launch(cn);
        }
    };
}
