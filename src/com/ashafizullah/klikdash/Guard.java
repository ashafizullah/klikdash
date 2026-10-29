package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Pengaman home dari crash berulang. Firmware NWD memilih launcher lewat property (lihat {@link HomeSwitch}), bukan
 * pilihan home Android: bila KlikDash crash tiap kali dibuka, Android tidak bisa pindah ke launcher lain dan headunit
 * tertahan di logo boot. Crash dalam menit pertama proses dihitung di penyimpanan DE (bisa ditulis saat direct boot):
 * <ul>
 *   <li>{@link #SAFE_AFTER}× → home dibuka dalam mode aman: tampilan polos tanpa tema, font, ubin, atau layanan, yang
 *   selalu bisa digambar (logo boot hilang begitu home tergambar);</li>
 *   <li>{@link #RESTORE_AFTER}× (mode aman pun crash, atau crash sebelum home dibuat) → di NWD launcher bawaan
 *   dikembalikan lewat ADB lokal, seperti tombol LAUNCHER BAWAAN.</li>
 * </ul>
 * Hitungan kembali 0 setelah home normal berjalan semenit, atau saat pengguna menekan COBA LAGI.
 */
final class Guard {
    static final int SAFE_AFTER = 2, RESTORE_AFTER = 3;
    /** Crash sesudah ini (sejak proses mulai) bukan crash saat membuka home. */
    private static final long WINDOW = 60_000;

    private Guard() { }

    private static boolean installed;

    private static SharedPreferences store(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("guard", Context.MODE_PRIVATE);
    }

    /** Sekali per proses, sebelum kode home lain jalan (attachBaseContext). */
    static synchronized void install(Context base) {
        if (installed) return;
        installed = true;
        Context app = base.getApplicationContext() != null ? base.getApplicationContext() : base;
        Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                crashed(app, e);
            } catch (Throwable ignored) {
            }
            if (next != null) next.uncaughtException(t, e);
            else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    private static void crashed(Context c, Throwable e) {
        if (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() > WINDOW) return;
        SharedPreferences s = store(c);
        int n = s.getInt("crashes", 0) + 1;
        // commit, bukan apply: proses ini segera mati.
        s.edit().putInt("crashes", n).putString("last", describe(e)).commit();
        if (n >= RESTORE_AFTER && HomeSwitch.isNwd()) restoreStock(c);
    }

    /**
     * Di thread lain (thread utama melarang jaringan, termasuk soket ke adbd lokal), ditunggu sebentar sebelum proses
     * mati. adbd bisa belum siap di awal boot: gagal diam-diam, crash berikutnya mencoba lagi.
     */
    private static void restoreStock(Context c) {
        Tweaks.init(c);
        Thread t = new Thread(() -> {
            try {
                Tweaks.exec(restoreCmd(c), 5000);
            } catch (Throwable ignored) {
            }
        }, "guard-restore");
        t.start();
        try {
            t.join(6000);
        } catch (InterruptedException ignored) {
        }
    }

    /** Prefs yang rusak bisa jadi penyebab crash-nya: tanpa prefs, kembali ke launcher bawaan NWD yang umum. */
    private static String restoreCmd(Context c) {
        try {
            return HomeSwitch.restoreCmd(c, Prefs.of(c));
        } catch (Throwable e) {
            return HomeSwitch.restoreCmd(c, null);
        }
    }

    /** Penyebab terdalam dan baris pertama kode KlikDash, untuk foto layar yang dikirim pengguna. */
    private static String describe(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        StringBuilder b = new StringBuilder(r.toString());
        StackTraceElement at = ownFrame(r);
        if (at == null) at = ownFrame(e);
        if (at != null) b.append("\n  at ").append(at);
        return b.length() > 600 ? b.substring(0, 600) : b.toString();
    }

    private static StackTraceElement ownFrame(Throwable e) {
        for (StackTraceElement f : e.getStackTrace()) {
            if (f.getClassName().startsWith(Guard.class.getPackage().getName())) return f;
        }
        return null;
    }

    /** Home perlu dibuka dalam mode aman. Penyimpanan tidak terbaca: anggap tidak. */
    static boolean safe(Context c) {
        try {
            return store(c).getInt("crashes", 0) >= SAFE_AFTER;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Bahasa KlikDash bila masih terbaca; prefs yang rusak bisa jadi penyebab crash-nya, maka cadangannya bahasa sistem. */
    static Context lang(Context base) {
        try {
            return Lang.wrap(base);
        } catch (Throwable e) {
            return base;
        }
    }

    /** Home normal sudah berjalan semenit tanpa crash. */
    static void ok(Context c) {
        try {
            SharedPreferences s = store(c);
            if (s.getInt("crashes", 0) != 0) s.edit().putInt("crashes", 0).remove("last").apply();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- mode aman

    /** Hanya widget bawaan Android dan warna tetap: tidak ada yang bisa ikut crash (Theme, font, prefs utama). */
    static View view(Activity a) {
        float dp = a.getResources().getDisplayMetrics().density;
        int pad = Math.round(32 * dp);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(HomeActivity.BG);

        box.addView(text(a, a.getString(R.string.guard_title), 24, HomeActivity.TEXT));
        TextView msg = text(a, a.getString(R.string.guard_message), 16, HomeActivity.DIM);
        msg.setPadding(0, Math.round(12 * dp), 0, 0);
        box.addView(msg);
        String last = "";
        try {
            last = store(a).getString("last", "");
        } catch (Throwable ignored) {
        }
        if (!last.isEmpty()) {
            TextView err = text(a, last, 12, HomeActivity.DIM);
            err.setTypeface(android.graphics.Typeface.MONOSPACE);
            err.setPadding(0, Math.round(16 * dp), 0, 0);
            err.setTextIsSelectable(true);
            box.addView(err);
        }

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Math.round(24 * dp), 0, 0);
        Button retry = new Button(a);
        retry.setText(R.string.guard_retry);
        // Proses baru, bukan recreate: crash berikutnya masih di menit pertama proses, jadi tetap terhitung. Android
        // menyalakan ulang home sendiri.
        retry.setOnClickListener(v -> {
            try {
                store(a).edit().putInt("crashes", 0).remove("last").commit();
            } catch (Throwable ignored) {
            }
            Process.killProcess(Process.myPid());
        });
        row.addView(retry);
        Button stock = new Button(a);
        stock.setText(R.string.guard_stock);
        stock.setOnClickListener(v -> toStock(a));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = Math.round(16 * dp);
        row.addView(stock, sl);
        box.addView(row);
        return box;
    }

    /** NWD: kembalikan property launcher lewat ADB lokal. Lainnya: pengaturan "Aplikasi home" Android. */
    private static void toStock(Activity a) {
        if (!HomeSwitch.isNwd()) {
            HomeSwitch.openHomeSettings(a);
            return;
        }
        Tweaks.init(a);
        Tweaks.askAsync(restoreCmd(a),
                () -> a.runOnUiThread(() -> Toast.makeText(a, R.string.home_adb_allow, Toast.LENGTH_LONG).show()),
                r -> {
                    if (r != Tweaks.DONE) a.runOnUiThread(() ->
                            Toast.makeText(a, HomeSwitch.failure(a, r), Toast.LENGTH_LONG).show());
                });
    }

    private static TextView text(Context c, String s, float size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }
}
