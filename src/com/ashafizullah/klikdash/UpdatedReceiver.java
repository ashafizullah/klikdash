package com.ashafizullah.klikdash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Setelah KlikDash diperbarui (dari Pengaturan › Pembaruan), buka home lagi. Tanpa ini firmware NWD kadang
 * menampilkan aplikasi lain (mis. Zlink). Android 10 melarang membuka activity dari latar, jadi lewat ADB lokal.
 */
public class UpdatedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        PendingResult pr = goAsync();
        Tweaks.shellAsync("am start -n " + c.getPackageName() + "/.HomeActivity", ok -> pr.finish());
    }
}
