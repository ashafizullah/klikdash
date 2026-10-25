package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;

/**
 * Tema launcher: mengisi palet di {@link HomeActivity} (BG, PANEL, …, AMBER = warna aksen). Tiap aktivitas memanggil
 * {@link #apply} sebelum super.onCreate, lalu {@link #stale} di onResume untuk membangun ulang tampilannya bila tema
 * diganti saat ia di latar. Disimpan di prefs DE "klikdash" (bisa dibaca saat direct boot).
 */
final class Theme {
    /** Jumlah tema; nama dan penjelasannya di resource (theme_names, theme_desc). */
    static final int COUNT = 3;

    //                        BG          PANEL       LINE        TEXT        DIM         FAINT       AKSEN       INK
    //                        HOT         SOFT        ACTIVE      WARN
    private static final int[][] PALETTES = {
            {0xFF0B0B0A, 0xFF151513, 0xFF2B2A26, 0xFFECE6D8, 0xFF8A8478, 0xFF4A4740, 0xFFFFB000, 0xFF0B0B0A,
                    0xFFFF5A3C, 0xFFD9D3C5, 0xFF1A1710, 0xFFFFB000},
            {0xFF08100D, 0xFF0F1814, 0xFF223129, 0xFFE2EFE7, 0xFF7D9488, 0xFF3B4C43, 0xFF2FD48A, 0xFF06100B,
                    0xFFFF5A3C, 0xFFCCDCD3, 0xFF0E2219, 0xFFFFB000},
            // Terang: aksen amber tua dan INK putih, supaya teks di atas aksen dan aksen di atas krem tetap kontras.
            {0xFFF3EEE3, 0xFFFBF8F1, 0xFFDCD4C3, 0xFF1A1712, 0xFF6E6656, 0xFFB5AC9A, 0xFF9A5B00, 0xFFFFFFFF,
                    0xFFC8371B, 0xFF3A342A, 0xFFF4E4C4, 0xFF9A5B00},
    };
    /** Pengali R, G, B ikon aplikasi yang dibuat monokrom di home (warna asli muncul saat ditekan). */
    private static final float[][] ICON_TINT = {{1f, .94f, .82f}, {.84f, 1f, .9f}, {.6f, .55f, .46f}}; // terang: lebih gelap, supaya ikon putih tetap terlihat

    static int current = -1;
    /** Naik setiap tema berganti; aktivitas menyimpan nilai saat dibangun untuk {@link #stale}. */
    static int version;

    private Theme() { }

    static boolean light() { return current == 2; }

    static int saved(Context c) {
        int t = prefs(c).getInt("theme", 0);
        return t >= 0 && t < COUNT ? t : 0;
    }

    static void save(Context c, int t) {
        prefs(c).edit().putInt("theme", t).apply();
        set(t);
    }

    /** Palet saja, tanpa aktivitas (mis. notifikasi melayang dari BroadcastReceiver). */
    static void load(Context c) { set(saved(c)); }

    /** Sebelum super.onCreate: pasang palet dan gaya jendela (gelap/terang, untuk dialog dan widget bawaan). */
    static int apply(Activity a) {
        set(saved(a));
        a.setTheme(light() ? R.style.KlikDash_Light : R.style.KlikDash);
        Window w = a.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(HomeActivity.BG));
        w.setStatusBarColor(HomeActivity.BG);
        w.setNavigationBarColor(HomeActivity.BG);
        int f = w.getDecorView().getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (light()) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if (light()) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(f);
        return version;
    }

    /** Di onResume: tema berganti sejak aktivitas dibangun → recreate(). */
    static boolean stale(Activity a, int builtVersion) {
        if (builtVersion == version) return false;
        a.recreate();
        return true;
    }

    /** Ikon aplikasi monokrom dalam warna tema. */
    static ColorMatrixColorFilter iconFilter() {
        float[] t = ICON_TINT[Math.max(0, current)];
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        cm.postConcat(new ColorMatrix(new float[]{
                t[0], 0, 0, 0, 0,
                0, t[1], 0, 0, 0,
                0, 0, t[2], 0, 0,
                0, 0, 0, 1f, 0}));
        return new ColorMatrixColorFilter(cm);
    }

    private static void set(int t) {
        if (t == current) return;
        current = t;
        version++;
        int[] p = PALETTES[t];
        HomeActivity.BG = p[0];
        HomeActivity.PANEL = p[1];
        HomeActivity.LINE = p[2];
        HomeActivity.TEXT = p[3];
        HomeActivity.DIM = p[4];
        HomeActivity.FAINT = p[5];
        HomeActivity.AMBER = p[6];
        HomeActivity.INK = p[7];
        HomeActivity.HOT = p[8];
        HomeActivity.SOFT = p[9];
        HomeActivity.ACTIVE = p[10];
        HomeActivity.WARN = p[11];
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("klikdash", Context.MODE_PRIVATE);
    }
}
