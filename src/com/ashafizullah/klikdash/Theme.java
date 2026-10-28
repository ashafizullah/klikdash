package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;

/**
 * Tema launcher: mengisi palet di {@link HomeActivity} (BG, PANEL, …, AMBER = warna aksen). Tiap aktivitas memanggil
 * {@link #apply} sebelum super.onCreate, lalu {@link #stale} di onResume untuk membangun ulang tampilannya bila tema
 * diganti saat ia di latar. Disimpan di prefs DE "klikdash" (bisa dibaca saat direct boot): "theme" (pilihan biasa), atau
 * bila "themeAuto" menyala, "themeDay" dari matahari terbit sampai terbenam dan "themeNight" sesudahnya, dihitung dari
 * lokasi terakhir home (lat/lon). Home memeriksanya tiap menit; aktivitas lain menyusul di onResume.
 */
final class Theme {
    /** Jumlah tema; nama dan penjelasannya di resource (theme_names, theme_desc). */
    static final int COUNT = 4;
    static final int AMBER = 0, DAY = 2;

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
            // Kilap: kaca bening di atas latar hitam bergradasi halus (lihat backdrop dan panel); PANEL tetap pekat untuk
            // dialog dan notifikasi yang melayang di atas aplikasi lain. Aksen biru es dengan INK hitam.
            {0xFF07080B, 0xFF16181D, 0x38FFFFFF, 0xFFF2F4F8, 0xFF9CA4B3, 0xFF5C6372, 0xFF7FE3FF, 0xFF06080B,
                    0xFFFF6B5A, 0xFFD9DEE7, 0x1FFFFFFF, 0xFFFFC14D},
    };
    /** Pengali R, G, B ikon aplikasi yang dibuat monokrom di home (warna asli muncul saat ditekan). */
    private static final float[][] ICON_TINT = {{1f, .94f, .82f}, {.84f, 1f, .9f}, {.6f, .55f, .46f}, null}; // terang: lebih gelap, supaya ikon putih tetap terlihat; kilap: warna asli

    static int current = -1;
    /** Naik setiap tema berganti; aktivitas menyimpan nilai saat dibangun untuk {@link #stale}. */
    static int version;

    private Theme() { }

    static boolean light() { return current == 2; }

    /** Tema kilap: panel kaca bersudut bulat di atas latar gradasi, bukan kotak datar. */
    static boolean gloss() { return current == 3; }

    /** Tema yang berlaku sekarang: pilihan biasa, atau tema siang/malam bila otomatis. */
    static int saved(Context c) {
        android.content.SharedPreferences p = prefs(c);
        if (!p.getBoolean("themeAuto", false)) return valid(p.getInt("theme", AMBER), AMBER);
        return daytime(p) ? valid(p.getInt("themeDay", DAY), DAY) : valid(p.getInt("themeNight", AMBER), AMBER);
    }

    private static int valid(int t, int def) { return t >= 0 && t < COUNT ? t : def; }

    static boolean auto(Context c) { return prefs(c).getBoolean("themeAuto", false); }

    /** Pilihan tersimpan untuk "theme", "themeDay", atau "themeNight" (lihat {@link #saved} untuk bawaannya). */
    static int chosen(Context c, String key) {
        int def = key.equals("themeDay") ? DAY : AMBER;
        return valid(prefs(c).getInt(key, def), def);
    }

    /** Simpan pilihan untuk {@code key} lalu pasang tema yang berlaku sekarang. */
    static void save(Context c, String key, int t) {
        prefs(c).edit().putInt(key, t).apply();
        refresh(c);
    }

    /**
     * Nyalakan/matikan tema otomatis. Pertama kali menyala: tema malam = tema gelap yang sedang dipakai (selain itu
     * AMBER), tema siang = SIANG.
     */
    static void setAuto(Context c, boolean on) {
        android.content.SharedPreferences p = prefs(c);
        android.content.SharedPreferences.Editor e = p.edit().putBoolean("themeAuto", on);
        if (on && !p.contains("themeNight")) {
            int t = valid(p.getInt("theme", AMBER), AMBER);
            e.putInt("themeNight", t == DAY ? AMBER : t);
        }
        e.apply();
        refresh(c);
    }

    /** Pasang tema yang berlaku sekarang (mis. matahari baru terbenam); {@link #version} naik bila berganti. */
    static void refresh(Context c) { set(saved(c)); }

    /** Menit matahari terbit dan terbenam hari ini di lokasi terakhir home (tanpa GPS: kota dari zona waktu). */
    static int[] sun(Context c) { return sun(prefs(c), java.util.Calendar.getInstance()); }

    private static int[] sun(android.content.SharedPreferences p, java.util.Calendar now) {
        double[] at = PrayerTimes.guess(java.util.TimeZone.getDefault().getID());
        try {
            String la = p.getString("lat", null), lo = p.getString("lon", null);
            if (la != null && lo != null) at = new double[]{Double.parseDouble(la), Double.parseDouble(lo)};
        } catch (NumberFormatException ignored) { }
        return PrayerTimes.sun(now, at[0], at[1]);
    }

    private static boolean daytime(android.content.SharedPreferences p) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int m = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        int[] s = sun(p, now);
        // Lokasi jauh dari zona waktunya: terbenam bisa jatuh sesudah tengah malam (lebih kecil dari terbit).
        return s[0] <= s[1] ? m >= s[0] && m < s[1] : m >= s[0] || m < s[1];
    }

    /** Palet saja, tanpa aktivitas (mis. notifikasi melayang dari BroadcastReceiver). */
    static void load(Context c) { set(saved(c)); }

    /** Sebelum super.onCreate: pasang palet dan gaya jendela (gelap/terang, untuk dialog dan widget bawaan). */
    static int apply(Activity a) {
        set(saved(a));
        a.setTheme(light() ? R.style.KlikDash_Light : R.style.KlikDash);
        Window w = a.getWindow();
        w.setBackgroundDrawable(gloss() ? backdrop() : new ColorDrawable(HomeActivity.BG));
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

    /** Di onResume: tema berganti sejak aktivitas dibangun (juga tema otomatis yang sudah waktunya) → recreate(). */
    static boolean stale(Activity a, int builtVersion) {
        refresh(a);
        if (builtVersion == version) return false;
        a.recreate();
        return true;
    }

    /** Ikon aplikasi monokrom dalam warna tema; null = warna asli (tema kilap). */
    static ColorMatrixColorFilter iconFilter() {
        float[] t = ICON_TINT[Math.max(0, current)];
        if (t == null) return null;
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        cm.postConcat(new ColorMatrix(new float[]{
                t[0], 0, 0, 0, 0,
                0, t[1], 0, 0, 0,
                0, 0, t[2], 0, 0,
                0, 0, 0, 1f, 0}));
        return new ColorMatrixColorFilter(cm);
    }

    // ---------------------------------------------------------------- panel

    private static final float DP = Resources.getSystem().getDisplayMetrics().density;
    /** Gradasi latar tema kilap, dari kiri atas (= BG, menyatu dengan status bar) ke kanan bawah: hitam, tidak silau. */
    private static final int[] BACKDROP = {0xFF07080B, 0xFF14171D, 0xFF06070A};
    // Hanya dipakai di thread UI saat menggambar.
    private static final RectF BOX = new RectF();
    private static final Path CLIP = new Path();
    private static final Paint SHEEN = new Paint(Paint.ANTI_ALIAS_FLAG);

    static Drawable backdrop() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, BACKDROP);
    }

    /** Latar layar penuh (root, laci aplikasi): BG, atau gradasi di tema kilap. */
    static void fill(View v) {
        if (gloss()) v.setBackground(backdrop());
        else v.setBackgroundColor(HomeActivity.BG);
    }

    /** Sudut bulat GradientDrawable (kartu, dialog, kolom isian): 0 di tema datar. */
    static float corner(float dp) { return corner(dp, 0); }

    /** Seperti di atas, dengan sudut {@code flat} (px) di tema datar. */
    static float corner(float dp, float flat) { return gloss() ? 14 * dp : flat; }

    private static float radius(int w, int h) { return Math.min(14 * DP, Math.min(w, h) / 4f); }

    /**
     * Latar tombol/ubin seukuran view. Tema datar: kotak {@code color}, bergaris LINE bila {@code outline}. Tema kilap:
     * kotak bulat dengan pantulan putih di separuh atas; panel biasa (PANEL/BG) jadi kaca bening yang menampakkan
     * latar di belakangnya, warna lain (aksen, ditekan) jadi tombol pekat yang mengilap. {@code fill} dan
     * {@code stroke} milik view (stroke bergaya STROKE); shader dan warna garisnya dikembalikan setelahnya.
     */
    static void panel(Canvas c, Paint fill, Paint stroke, int w, int h, int color, boolean outline) {
        if (!gloss()) {
            fill.setColor(color);
            c.drawRect(0, 0, w, h, fill);
            if (outline) {
                float s = stroke.getStrokeWidth() / 2f;
                c.drawRect(s, s, w - s, h - s, stroke);
            }
            return;
        }
        boolean glass = color == HomeActivity.PANEL || color == HomeActivity.BG;
        float r = radius(w, h);
        BOX.set(0, 0, w, h);
        fill.setAntiAlias(true);
        fill.setColor(0xFF000000); // alfa paint ikut mengalikan shader: sisa warna transparan (pita, redup) jangan terbawa
        fill.setShader(glass
                ? new LinearGradient(0, 0, w * .35f, h, 0x26FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP)
                : new LinearGradient(0, 0, 0, h, mix(color, 0xFFFFFFFF, .3f), mix(color, 0xFF000000, .1f),
                        Shader.TileMode.CLAMP));
        c.drawRoundRect(BOX, r, r, fill);
        fill.setShader(null);
        SHEEN.setShader(new LinearGradient(0, 0, 0, h / 2f, glass ? 0x24FFFFFF : 0x73FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        c.save();
        c.clipRect(0, 0, w, h / 2f);
        c.drawRoundRect(BOX, r, r, SHEEN);
        c.restore();
        int was = stroke.getColor();
        float s = stroke.getStrokeWidth() / 2f;
        stroke.setColor(glass ? 0x40FFFFFF : mix(color, 0xFFFFFFFF, .45f));
        BOX.set(s, s, w - s, h - s);
        c.drawRoundRect(BOX, r - s, r - s, stroke);
        stroke.setColor(was);
    }

    /** Latar kartu kaca seukuran view (panel jam di home), digambar seperti {@link #panel} dengan PANEL. */
    static Drawable card() {
        return new Drawable() {
            final Paint fill = new Paint(), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            { stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(Math.round(DP)); }

            @Override public void draw(Canvas c) {
                android.graphics.Rect b = getBounds();
                c.save();
                c.translate(b.left, b.top);
                panel(c, fill, stroke, b.width(), b.height(), HomeActivity.PANEL, true);
                c.restore();
            }

            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    /** Isi GradientDrawable berwarna pekat (notch, strip): di tema kilap bergradasi mengilap dari atas. */
    static void tint(GradientDrawable g, int color) {
        if (gloss()) g.setColors(new int[]{mix(color, 0xFFFFFFFF, .3f), mix(color, 0xFF000000, .1f)});
        else g.setColor(color);
    }

    /** Bingkai tebal (ubin tujuan saat diseret), mengikuti bentuk {@link #panel}. */
    static void ring(Canvas c, Paint fill, int w, int h, int color, int t) {
        fill.setColor(color);
        if (!gloss()) {
            c.drawRect(0, 0, w, t, fill);
            c.drawRect(0, h - t, w, h, fill);
            c.drawRect(0, 0, t, h, fill);
            c.drawRect(w - t, 0, w, h, fill);
            return;
        }
        Paint.Style was = fill.getStyle();
        float r = radius(w, h);
        fill.setAntiAlias(true);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(t);
        BOX.set(t / 2f, t / 2f, w - t / 2f, h - t / 2f);
        c.drawRoundRect(BOX, r - t / 2f, r - t / 2f, fill);
        fill.setStyle(was);
    }

    /** Potong gambar berikutnya ke bentuk {@link #panel} (mis. pita progres); pasangkan dengan c.restore(). */
    static void clip(Canvas c, int w, int h) {
        c.save();
        if (!gloss()) return;
        float r = radius(w, h);
        CLIP.rewind();
        CLIP.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
        c.clipPath(CLIP);
    }

    /** Campur dua warna ARGB; f = 0 → a, f = 1 → b. */
    static int mix(int a, int b, float f) {
        int r = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int x = a >>> sh & 0xFF, y = b >>> sh & 0xFF;
            r |= Math.round(x + (y - x) * f) << sh;
        }
        return r;
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
