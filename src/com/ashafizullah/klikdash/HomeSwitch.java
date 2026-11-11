package com.ashafizullah.klikdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

/**
 * Menjadikan KlikDash tampilan utama, atau kembali ke launcher bawaan. Firmware NWD mengabaikan pilihan
 * "Aplikasi home" Android dan memakai property {@link #NWD_PROP}, yang diatur lewat ADB perangkat sendiri
 * (bila adbd meminta autentikasi, Android sekali menanyakan izin untuk kunci KlikDash).
 */
final class HomeSwitch {
    static final String NWD_PROP = "persist.nwd.launcher.default";

    private HomeSwitch() { }

    static String getprop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String s = r.readLine();
                return s == null ? "" : s.trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** getprop menjalankan proses: cukup sekali per proses, firmware tidak berubah selama berjalan. */
    private static volatile Boolean nwd;

    static boolean isNwd() {
        if (nwd == null) nwd = "true".equals(getprop("ro.nwd.start.launcher.by.fw")) || !getprop(NWD_PROP).isEmpty();
        return nwd;
    }

    static boolean isDefault(Context c) {
        if (isNwd()) return c.getPackageName().equals(getprop(NWD_PROP));
        ResolveInfo r = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
    }

    /**
     * Port ADB 5555 tetap terbuka setelah headunit dinyalakan ulang (adbd membaca property ini saat mulai). Tanpa itu,
     * bila home KlikDash gagal tampil, headunit tertahan di logo boot dan tidak bisa dipulihkan dari jaringan: tidak
     * oleh {@link Guard} (lewat ADB lokal), tidak juga dari laptop. Baris terakhir keluarannya 5555 bila berhasil.
     */
    private static final String KEEP_PORT = "setprop persist.adb.tcp.port 5555; getprop persist.adb.tcp.port";

    /**
     * NWD: atur property lewat ADB perangkat sendiri (tanpa komputer). Lainnya: buka pengaturan "Aplikasi home".
     * Di NWD dicek dulu bahwa port ADB 5555 terbuka dan tetap terbuka setelah boot; bila tidak, pengguna diperingatkan.
     * Port 5555 tertutup (Android 11+): dibuka dulu lewat Wireless debugging, lalu dicek dengan cara yang sama.
     */
    static void makeDefault(Activity a, SharedPreferences prefs, Runnable done) {
        if (!isNwd()) {
            openHomeSettings(a);
            return;
        }
        check(a, prefs, done, true);
    }

    /** {@code mayOpen}: port 5555 yang tertutup boleh dicoba dibuka lewat Wireless debugging (sekali saja). */
    private static void check(Activity a, SharedPreferences prefs, Runnable done, boolean mayOpen) {
        new Thread(() -> {
            int r = Tweaks.DONE;
            boolean kept = false;
            try {
                String out = Tweaks.ask(KEEP_PORT, () -> a.runOnUiThread(() -> Notice.info(a, a.getString(R.string.home_adb_allow))));
                String[] lines = out.trim().split("\n");
                kept = "5555".equals(lines[lines.length - 1].trim());
            } catch (Tweaks.NotAllowed e) {
                r = Tweaks.NOT_ALLOWED;
            } catch (java.io.IOException e) {
                r = Tweaks.NO_ADB; // port 5555 tertutup sekarang
            }
            int res = r;
            boolean ok = kept;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (res == Tweaks.NOT_ALLOWED) HelpVideo.fail(a, a.getString(failure(a, res)), HelpVideo.forAdb(a, res));
                else if (ok) apply(a, prefs, done);
                else if (res == Tweaks.NO_ADB) {
                    // Buka port 5555 lewat Wireless debugging (tanpa komputer), lalu cek ulang lewat port itu: tanpa
                    // port 5555 Guard tidak bisa memulihkan launcher bawaan, jadi home tidak diganti sebelum dicek.
                    if (!mayOpen || !AdbWifiService.offer(a, res, null, true, w -> {
                        if (w == Tweaks.DONE) check(a, prefs, done, false);
                        // Dibatalkan/gagal: video Wireless debugging, dan cara lewat komputer di pesannya.
                        else HelpVideo.fail(a, a.getString(R.string.home_need_adb_port), HelpVideo.WIRELESS);
                    })) HelpVideo.fail(a, a.getString(failure(a, res)), HelpVideo.forAdb(a, res));
                } else new AlertDialog.Builder(a)
                        .setTitle(R.string.home_port_not_kept_title)
                        .setMessage(R.string.home_port_not_kept_message)
                        .setNegativeButton(R.string.common_cancel, null)
                        .setPositiveButton(R.string.home_port_not_kept_confirm, (d, w) -> apply(a, prefs, done))
                        .show();
            });
        }, "home-check").start();
    }

    private static void apply(Activity a, SharedPreferences prefs, Runnable done) {
        String cur = getprop(NWD_PROP), me = a.getPackageName();
        if (!cur.isEmpty() && !cur.equals(me)) prefs.edit().putString("nwdPrev", cur).apply();
        // Hanya dipanggil setelah check() berhasil menghubungi ADB di port 5555.
        String cmd = "setprop " + NWD_PROP + " " + me + "; cmd package set-home-activity " + me + "/.HomeActivity";
        Tweaks.askAsync(cmd,
                () -> Notice.info(a, a.getString(R.string.home_adb_allow)),
                r -> a.runOnUiThread(() -> {
                    if (done != null) done.run();
                    if (isDefault(a)) Notice.ok(a, a.getString(R.string.home_default_ok));
                    else HelpVideo.fail(a, a.getString(failure(a, r)), HelpVideo.forAdb(a, r));
                }));
    }

    /**
     * Pesan bila ADB gagal: USB debugging mati, port 5555 tertutup meski USB debugging menyala, kunci KlikDash belum
     * diizinkan, atau perintahnya ditolak firmware.
     */
    static int failure(Context c, int adbResult) {
        if (adbResult == Tweaks.NOT_ALLOWED) return R.string.home_adb_denied;
        if (adbResult == Tweaks.NO_ADB) {
            if (!Tweaks.debuggingOn(c)) return R.string.home_need_adb;
            return AdbWifiService.supported() ? R.string.home_need_adb_port : R.string.home_need_adb_port_laptop;
        }
        return R.string.home_setprop_failed;
    }

    static void restore(Activity a, SharedPreferences prefs) {
        if (!isNwd()) {
            openHomeSettings(a);
            return;
        }
        new AlertDialog.Builder(a)
                .setMessage(R.string.home_restore_message)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.home_restore_confirm, (dlg, w) -> Tweaks.askAsync(restoreCmd(a, prefs),
                        () -> Notice.info(a, a.getString(R.string.home_adb_allow)),
                        r -> {
                            if (r != Tweaks.DONE) HelpVideo.fail(a, a.getString(failure(a, r)), HelpVideo.forAdb(a, r));
                        }))
                .show();
    }

    /** Perintah ADB kembali ke launcher bawaan NWD; {@code prefs} null: launcher bawaan yang umum (lihat Guard). */
    static String restoreCmd(Context c, SharedPreferences prefs) {
        String prev = prefs != null ? prefs.getString("nwdPrev", "com.android.launcher") : "com.android.launcher";
        ComponentName stock = stockHome(c, prev);
        return "setprop " + NWD_PROP + " " + prev
                + (stock != null ? "; cmd package set-home-activity " + stock.flattenToShortString() : "")
                + "; input keyevent KEYCODE_HOME";
    }

    /** Activity HOME bawaan: milik paket {@code pkg}, atau launcher lain selain KlikDash. */
    static ComponentName stockHome(Context c, String pkg) {
        ComponentName other = null;
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            ComponentName cn = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            if (cn.getPackageName().equals(pkg)) return cn;
            if (other == null && !cn.getPackageName().equals(c.getPackageName())) other = cn;
        }
        return other;
    }

    static void openHomeSettings(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
