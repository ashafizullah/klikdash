package com.ashafizullah.klikdash;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;

/**
 * Screensaver home: jam besar dan tanggal di atas latar hitam atau foto pilihan pengguna, setelah home tidak disentuh
 * selama saverSec detik (Pengaturan › Launcher; 0 = mati). Hanya di home KlikDash, jadi tidak pernah menutupi aplikasi
 * lain seperti Maps, dan ditahan selama mobil bergerak (lihat HomeActivity.poke). Ketuk sekali untuk kembali; ketukan
 * itu tidak diteruskan ke ubin. Foto disalin dan diperkecil ke penyimpanan DE (saver.jpg): izin baca URI dari pemilih
 * foto bisa hilang setelah reboot, dan home harus bisa membacanya saat direct boot.
 */
final class Screensaver {
    /** Pilihan di Pengaturan, dalam detik; 0 = mati. */
    static final int[] SECONDS = {0, 30, 60, 180, 300, 600};
    static final int BG_BLACK = 0, BG_PHOTO = 1;

    private Screensaver() { }

    /** Detik tanpa sentuhan sebelum screensaver muncul; 0 = mati. Versi 1.9.4 ke bawah menyimpan menit (saverMin). */
    static int seconds(SharedPreferences p) {
        return p.contains("saverSec") ? p.getInt("saverSec", 0) : p.getInt("saverMin", 0) * 60;
    }

    static void setSeconds(SharedPreferences p, int s) { p.edit().putInt("saverSec", s).remove("saverMin").apply(); }

    /**
     * Tombol pilihan: "30 DTK", menit cukup angkanya ("3", seperti sebelum ada pilihan detik, supaya baris tombolnya
     * tidak mendesak penjelasan); {@code full}: "30 DETIK", "3 MENIT" (ringkasan grup).
     */
    static String label(Context c, int s, boolean full) {
        if (s < 60) return c.getString(full ? R.string.settings_saver_sec_long : R.string.settings_saver_sec, s);
        return full ? c.getString(R.string.settings_saver_min_long, s / 60) : String.valueOf(s / 60);
    }

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
     * Isi layar screensaver. Selalu gelap, juga di tema Siang: dipakai saat mobil diam, sering malam hari. Jam dan
     * tanggal tepat di tengah layar, diukur dari bentuk huruf yang tergambar (bukan metrik font) supaya tidak miring.
     * Di bawahnya, bila ada: yang sedang diputar (murattal, dzikir, musik/radio), sama seperti notch home
     * ({@link MediaNotch#current}); home menggambar ulang saat sumbernya berganti.
     */
    static final class Face extends View {
        final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG),
                image = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Matrix fit = new Matrix();
        final TextPaint media = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Rect cb = new Rect(), ab = new Rect(), db = new Rect(), mb = new Rect();
        final float dp;
        SharedPreferences prefs;
        Bitmap bg;
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
            media.setTypeface(mono);
            media.setLetterSpacing(0.2f);
        }

        /** "MURATTAL  ·  AL-KAHFI  ·  10/110", "JEDA  ·  …"; null bila tidak ada yang diputar. */
        String nowPlaying() {
            MediaNotch.Source m = MediaNotch.current();
            if (m == null) return null;
            Context c = getContext();
            return (m.playing() ? m.state(c) : c.getString(R.string.home_notch_paused)) + "  ·  " + m.title(c);
        }

        void start(SharedPreferences p) {
            prefs = p;
            invalidate();
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

        /** Tiap menit: gambar ulang jamnya. */
        void tick() { invalidate(); }

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
            media.setTextSize(small.getTextSize() * 0.9f);
            media.setColor(HomeActivity.AMBER);
            if (shade) {
                clock.setShadowLayer(16 * dp, 0, 0, 0x99000000);
                small.setShadowLayer(8 * dp, 0, 0, 0x99000000);
                media.setShadowLayer(8 * dp, 0, 0, 0x99000000);
            } else {
                clock.clearShadowLayer();
                small.clearShadowLayer();
                media.clearShadowLayer();
            }
            String ap = twelve ? Clock.ampm(min) : "";
            clock.getTextBounds(hm, 0, hm.length(), cb);
            small.getTextBounds(date, 0, date.length(), db);
            float apGap = small.getTextSize() * 0.6f;
            if (ap.isEmpty()) ab.setEmpty(); else small.getTextBounds(ap, 0, ap.length(), ab);
            float rowW = cb.width() + (ap.isEmpty() ? 0 : apGap + ab.width());
            float gap = small.getTextSize() * 1.4f;
            String np = nowPlaying();
            float mGap = 0;
            if (np != null) {
                np = TextUtils.ellipsize(np, media, w * 0.8f, TextUtils.TruncateAt.END).toString(); // judul lagu bisa panjang
                media.getTextBounds(np, 0, np.length(), mb);
                mGap = small.getTextSize() * 1.6f;
            }
            float top = (h - (cb.height() + gap + db.height() + (np == null ? 0 : mGap + mb.height()))) / 2f;

            float x = (w - rowW) / 2f, base = top - cb.top;
            c.drawText(hm, x - cb.left, base, clock);
            if (!ap.isEmpty()) c.drawText(ap, x + cb.width() + apGap - ab.left, base, small);
            c.drawText(date, (w - db.width()) / 2f - db.left, top + cb.height() + gap - db.top, small);
            if (np != null) {
                c.drawText(np, (w - mb.width()) / 2f - mb.left, top + cb.height() + gap + db.height() + mGap - mb.top, media);
            }
        }
    }
}
