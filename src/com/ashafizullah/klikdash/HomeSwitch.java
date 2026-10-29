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

    /** NWD: atur property lewat ADB perangkat sendiri (tanpa komputer). Lainnya: buka pengaturan "Aplikasi home". */
    static void makeDefault(Activity a, SharedPreferences prefs, Runnable done) {
        if (!isNwd()) {
            openHomeSettings(a);
            return;
        }
        String cur = getprop(NWD_PROP), me = a.getPackageName();
        if (!cur.isEmpty() && !cur.equals(me)) prefs.edit().putString("nwdPrev", cur).apply();
        String cmd = "setprop " + NWD_PROP + " " + me + "; cmd package set-home-activity " + me + "/.HomeActivity";
        Tweaks.askAsync(cmd,
                () -> Notice.info(a, a.getString(R.string.home_adb_allow)),
                r -> a.runOnUiThread(() -> {
                    if (done != null) done.run();
                    if (isDefault(a)) Notice.ok(a, a.getString(R.string.home_default_ok));
                    // Port 5555 tertutup (Android 11+): coba lewat Wireless debugging, tanpa komputer.
                    else if (!AdbWifiService.offer(a, r, cmd, false, w -> {
                        if (done != null) done.run();
                        if (isDefault(a)) Notice.ok(a, a.getString(R.string.home_default_ok));
                        else if (w == Tweaks.DONE) Notice.fail(a, a.getString(R.string.home_setprop_failed));
                        // Dibatalkan/gagal: video Wireless debugging, dan cara lewat komputer di pesannya.
                        else HelpVideo.fail(a, a.getString(R.string.home_need_adb_port), HelpVideo.WIRELESS);
                    })) HelpVideo.fail(a, a.getString(failure(a, r)), HelpVideo.forAdb(a, r));
                }));
    }

    /**
     * Pesan bila ADB gagal: USB debugging mati, port 5555 tertutup meski USB debugging menyala, kunci KlikDash belum
     * diizinkan, atau perintahnya ditolak firmware.
     */
    static int failure(Context c, int adbResult) {
        if (adbResult == Tweaks.NOT_ALLOWED) return R.string.home_adb_denied;
        if (adbResult == Tweaks.NO_ADB) return Tweaks.debuggingOn(c) ? R.string.home_need_adb_port : R.string.home_need_adb;
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
