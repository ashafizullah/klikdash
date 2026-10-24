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
import android.widget.Toast;

/**
 * Menjadikan KlikDash tampilan utama, atau kembali ke launcher bawaan. Firmware NWD mengabaikan pilihan
 * "Aplikasi home" Android dan memakai property {@link #NWD_PROP}, yang diatur lewat ADB perangkat sendiri.
 */
final class HomeSwitch {
    static final String NWD_PROP = "persist.nwd.launcher.default";
    static final String NEED_ADB = "Belum berhasil. Aktifkan USB debugging di Opsi pengembang, lalu coba lagi.";

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

    static boolean isNwd() {
        return "true".equals(getprop("ro.nwd.start.launcher.by.fw")) || !getprop(NWD_PROP).isEmpty();
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
        Tweaks.shellAsync("setprop " + NWD_PROP + " " + me + "; cmd package set-home-activity " + me + "/.HomeActivity",
                ok -> a.runOnUiThread(() -> {
                    if (done != null) done.run();
                    Toast.makeText(a, isDefault(a) ? "KlikDash sekarang tampilan utama" : NEED_ADB, Toast.LENGTH_LONG).show();
                }));
    }

    static void restore(Activity a, SharedPreferences prefs) {
        if (!isNwd()) {
            openHomeSettings(a);
            return;
        }
        new AlertDialog.Builder(a)
                .setMessage("Kembali ke launcher bawaan? KlikDash tetap terpasang dan bisa dijadikan tampilan utama lagi "
                        + "dari tombol di home-nya.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Kembali", (dlg, w) -> {
                    String prev = prefs.getString("nwdPrev", "com.android.launcher");
                    ComponentName stock = stockHome(a, prev);
                    Tweaks.shellAsync("setprop " + NWD_PROP + " " + prev
                                    + (stock != null ? "; cmd package set-home-activity " + stock.flattenToShortString() : "")
                                    + "; input keyevent KEYCODE_HOME",
                            ok -> {
                                if (!ok) a.runOnUiThread(() -> Toast.makeText(a, NEED_ADB, Toast.LENGTH_LONG).show());
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
