package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.view.Display;

/**
 * Orientasi layar KlikDash, dipilih di Pengaturan (key "orientation"). OTOMATIS mengikuti rotasi sistem, supaya
 * headunit tegak juga didukung. Sebagian headunit memakai panel yang aslinya tegak dan tampak mendatar hanya karena
 * launcher bawaannya mengunci landscape; begitu KlikDash jadi home, layarnya ikut tegak. Di sana pengguna memilih
 * MENDATAR. Dipasang di semua aktivitas lewat {@link Theme#apply} dan {@link Theme#stale}. Firmware NWD mengabaikan
 * permintaan orientasi dari home: HomeActivity.keepOrientation memutarnya lewat pengaturan sistem (ADB lokal).
 */
final class Orientation {
    static final int AUTO = 0, LANDSCAPE = 1, PORTRAIT = 2;

    private Orientation() { }

    static int mode(SharedPreferences p) {
        int m = p.getInt("orientation", AUTO);
        return m == LANDSCAPE || m == PORTRAIT ? m : AUTO;
    }

    static void apply(Activity a) {
        int m = mode(Prefs.of(a));
        // Bukan SENSOR_*: arah mendatar persis seperti launcher bawaan yang mengunci landscape. Firmware panel tegak
        // yang dipasang miring menentukan arah itu (90° atau 270°, config_reverseDefaultRotation); tanpa sensor putar,
        // SENSOR_LANDSCAPE bisa memakai rotasi terakhir yang terbalik.
        int want = m == LANDSCAPE ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : m == PORTRAIT ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (a.getRequestedOrientation() != want) a.setRequestedOrientation(want);
    }

    /**
     * Rotasi sistem (Surface.ROTATION_*) untuk orientasi pilihan, atau -1 bila OTOMATIS atau layar sudah begitu.
     * prefer: rotasi bawaan firmware (mis. ro.boot.nwd.rotation), dipakai bila orientasinya cocok.
     */
    static int rotation(int mode, Display d, int prefer) {
        if (mode == AUTO) return -1;
        Point size = new Point();
        d.getRealSize(size);
        boolean land = size.x > size.y, wantLand = mode == LANDSCAPE;
        if (land == wantLand) return -1;
        boolean naturalLand = (d.getRotation() % 2 == 0) == land;
        if (prefer >= 0 && prefer <= 3 && ((prefer % 2 == 0) == naturalLand) == wantLand) return prefer;
        return naturalLand == wantLand ? 0 : reverseDefault() ? 3 : 1;
    }

    /** Seperti WindowManager: true → mendatar dari panel tegak (dan sebaliknya) adalah 270°, bukan 90°. */
    private static boolean reverseDefault() {
        android.content.res.Resources r = android.content.res.Resources.getSystem();
        int id = r.getIdentifier("config_reverseDefaultRotation", "bool", "android");
        return id != 0 && r.getBoolean(id);
    }
}
