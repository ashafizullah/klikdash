package com.ashafizullah.klikdash;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Random;

/**
 * Screensaver home: jam besar dan tanggal di atas latar hitam atau foto pilihan pengguna, setelah home tidak disentuh
 * selama saverMin menit (Pengaturan › Launcher; 0 = mati). Hanya di home KlikDash, jadi tidak pernah menutupi aplikasi
 * lain seperti Maps, dan ditahan selama mobil bergerak (lihat HomeActivity.poke). Ketuk sekali untuk kembali; ketukan
 * itu tidak diteruskan ke ubin. Foto disalin dan diperkecil ke penyimpanan DE (saver.jpg): izin baca URI dari pemilih
 * foto bisa hilang setelah reboot, dan home harus bisa membacanya saat direct boot.
 */
final class Screensaver {
    /** Pilihan di Pengaturan, dalam menit; 0 = mati. */
    static final int[] MINUTES = {0, 1, 3, 5, 10};
    static final int BG_BLACK = 0, BG_PHOTO = 1;

    private Screensaver() { }

    static int minutes(SharedPreferences p) { return p.getInt("saverMin", 0); }

    /** Latar foto dipilih dan filenya ada. */
    static boolean photo(Context c, SharedPreferences p) {
        return p.getInt("saverBg", BG_BLACK) == BG_PHOTO && file(c).isFile();
    }

    static File file(Context c) { return new File(c.createDeviceProtectedStorageContext().getFilesDir(), "saver.jpg"); }

    /**
     * Salin foto dari {@code uri} sebagai JPEG seukuran layar (dipotong di tengah, diputar sesuai EXIF). Foto kamera
     * HP 12–50 MP tidak muat di memori headunit, jadi didekode dengan inSampleSize dulu. Di thread latar.
     */
    static void importPhoto(Context c, Uri uri) throws IOException {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        android.content.ContentResolver cr = c.getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("not an image");
        int deg = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            switch (new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: deg = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: deg = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: deg = 270; break;
            }
        } catch (Exception ignored) { } // bukan JPEG atau tanpa EXIF
        boolean turn = deg % 180 != 0;
        int pw = turn ? o.outHeight : o.outWidth, ph = turn ? o.outWidth : o.outHeight;
        float cover = Math.max(sw / (float) pw, sh / (float) ph); // skala supaya foto menutupi layar
        int sample = 1;
        while (cover * sample * 2 <= 1) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap src;
        try (InputStream in = cr.openInputStream(uri)) { src = BitmapFactory.decodeStream(in, null, o); }
        if (src == null) throw new IOException("not an image");

        Bitmap out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        int rw = turn ? src.getHeight() : src.getWidth(), rh = turn ? src.getWidth() : src.getHeight();
        float s = Math.max(sw / (float) rw, sh / (float) rh);
        Matrix m = new Matrix();
        m.postTranslate(-src.getWidth() / 2f, -src.getHeight() / 2f);
        m.postRotate(deg);
        m.postScale(s, s);
        m.postTranslate(sw / 2f, sh / 2f);
        new Canvas(out).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();

        File f = file(c), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            out.compress(Bitmap.CompressFormat.JPEG, 90, fo);
        } finally {
            out.recycle();
        }
        if (!tmp.renameTo(f)) throw new IOException("cannot save");
    }

    /**
     * Isi layar screensaver. Selalu gelap, juga di tema Siang: dipakai saat mobil diam, sering malam hari. Posisi jam
     * bergeser sedikit tiap menit ({@link #tick}) supaya layar tidak berbayang.
     */
    static final class Face extends View {
        final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG),
                image = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Matrix fit = new Matrix();
        final Random rnd = new Random();
        final float dp;
        SharedPreferences prefs;
        Bitmap bg;
        /** Pergeseran jam, sebagian dari lebar/tinggi layar. */
        float dx, dy;
        /** Menaikkan nilai ini membatalkan pemuatan foto yang belum selesai (stop, atau start lagi). */
        int loadGen;

        Face(Context c, Typeface heavy, Typeface mono) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            clock.setTypeface(heavy);
            clock.setColor(0xFFF4F1EA);
            clock.setLetterSpacing(-0.01f);
            small.setTypeface(mono);
            small.setColor(0xCCF4F1EA);
            small.setLetterSpacing(0.3f);
        }

        void start(SharedPreferences p) {
            prefs = p;
            tick();
            int gen = ++loadGen;
            if (!photo(getContext(), p)) return;
            File f = file(getContext());
            new Thread(() -> {
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                post(() -> {
                    if (gen != loadGen) { if (b != null) b.recycle(); return; }
                    bg = b;
                    invalidate();
                });
            }, "saver-photo").start();
        }

        /** Lepas foto dari memori saat tidak tampil (±3,7 MB di layar 1280×720). */
        void stop() {
            loadGen++;
            if (bg != null) { bg.recycle(); bg = null; }
        }

        void tick() {
            dx = (rnd.nextFloat() * 2 - 1) * 0.05f;
            dy = (rnd.nextFloat() * 2 - 1) * 0.06f;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(0xFF000000);
            boolean shade = bg != null;
            if (shade) {
                float s = Math.max(w / (float) bg.getWidth(), h / (float) bg.getHeight());
                fit.setScale(s, s);
                fit.postTranslate((w - bg.getWidth() * s) / 2f, (h - bg.getHeight() * s) / 2f);
                c.drawBitmap(bg, fit, image);
                c.drawColor(0x66000000); // redupkan supaya jam terbaca di foto terang
            }
            if (prefs == null) return;

            Calendar now = Calendar.getInstance();
            int min = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            boolean twelve = Clock.twelve(prefs);
            String hm = Clock.hm(twelve, min);
            String date = Lang.days[now.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY] + " · " + getContext().getString(
                    R.string.home_date, now.get(Calendar.DAY_OF_MONTH), Lang.months[now.get(Calendar.MONTH)], now.get(Calendar.YEAR));

            // Jam selebar ±55% layar ("00:00" pada huruf ini), tapi tidak lebih dari 40% tinggi.
            clock.setTextSize(100);
            float size = Math.min(h * 0.40f, 100 * w * 0.55f / clock.measureText("00:00"));
            clock.setTextSize(size);
            small.setTextSize(Math.max(14 * dp, size * 0.11f));
            if (shade) {
                clock.setShadowLayer(16 * dp, 0, 0, 0x99000000);
                small.setShadowLayer(8 * dp, 0, 0, 0x99000000);
            } else {
                clock.clearShadowLayer();
                small.clearShadowLayer();
            }
            Paint.FontMetrics fm = clock.getFontMetrics();
            float capH = -fm.ascent * 0.72f; // tinggi angka, kira-kira
            float gap = small.getTextSize() * 1.6f;
            float block = capH + gap + small.getTextSize();
            float cx = w / 2f + dx * w, top = (h - block) / 2f + dy * h;

            float tw = clock.measureText(hm);
            String ap = twelve ? Clock.ampm(min) : "";
            float aw = ap.isEmpty() ? 0 : small.measureText(ap) + small.getTextSize() * 0.6f;
            float x = cx - (tw + aw) / 2f, base = top + capH;
            c.drawText(hm, x, base, clock);
            if (!ap.isEmpty()) c.drawText(ap, x + tw + small.getTextSize() * 0.6f, base, small);
            c.drawText(date, cx - small.measureText(date) / 2f, base + gap + small.getTextSize() * 0.8f, small);
        }
    }
}
