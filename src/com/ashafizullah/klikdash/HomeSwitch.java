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
        Tweaks.askAsync("setprop " + NWD_PROP + " " + me + "; cmd package set-home-activity " + me + "/.HomeActivity",
                () -> Notice.info(a, a.getString(R.string.home_adb_allow)),
                r -> a.runOnUiThread(() -> {
                    if (done != null) done.run();
                    if (isDefault(a)) Notice.ok(a, a.getString(R.string.home_default_ok));
                    else Notice.fail(a, a.getString(failure(r)));
                }));
    }

    /** Pesan bila ADB gagal: tidak bisa dihubungi, kunci KlikDash belum diizinkan, atau perintahnya ditolak firmware. */
    static int failure(int adbResult) {
        if (adbResult == Tweaks.NOT_ALLOWED) return R.string.home_adb_denied;
        if (adbResult == Tweaks.NO_ADB) return R.string.home_need_adb;
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
                .setPositiveButton(R.string.home_restore_confirm, (dlg, w) -> {
                    String prev = prefs.getString("nwdPrev", "com.android.launcher");
                    ComponentName stock = stockHome(a, prev);
                    Tweaks.askAsync("setprop " + NWD_PROP + " " + prev
                                    + (stock != null ? "; cmd package set-home-activity " + stock.flattenToShortString() : "")
                                    + "; input keyevent KEYCODE_HOME",
                            () -> Notice.info(a, a.getString(R.string.home_adb_allow)),
                            r -> {
                                if (r != Tweaks.DONE) Notice.fail(a, a.getString(failure(r)));
                            });
                })
                .show();
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
