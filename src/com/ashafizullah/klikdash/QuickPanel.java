package com.ashafizullah.klikdash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.FAINT;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.MATCH;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;
import static com.ashafizullah.klikdash.HomeActivity.WRAP;

/**
 * Panel cepat di tepi kiri home: dibuka dari menu ☰ di kiri atas panel jam ({@link Icon}) atau digeser
 * dari tepi kiri (seperti launcher bawaan sebagian headunit, mis. Audioteq). Di atasnya PENGATURAN KlikDash, lalu
 * bagian-bagian yang bisa ditambah ({@link #section}): volume media, kecerahan layar, screensaver sekarang. Hanya di home KlikDash, tidak di atas aplikasi lain. Tanpa animasi, seperti
 * home; menutup saat diketuk di luar panel, Kembali, atau setelah {@link #IDLE_MS} tanpa sentuhan.
 *
 * Volume lewat AudioManager (STREAM_MUSIC) dan kecerahan lewat Settings.System: headunit yang volume/lampu layarnya
 * diatur MCU firmware sendiri bisa tidak ikut.
 */
final class QuickPanel extends FrameLayout {
    static final long IDLE_MS = 8000;
    /** Lebar area tepi kiri yang memulai geser, dan jarak geser yang membuka panel (dp). */
    static final int EDGE_DP = 20, SWIPE_DP = 36;

    final HomeActivity h;
    final AudioManager audio;
    final LinearLayout panel;
    final Level volume, brightness;
    final Key mute;
    private final Runnable idleClose = this::close;

    /** Volume diubah dari luar (tombol setir, aplikasi lain): batang ikut. Terdaftar hanya selama panel terbuka. */
    private final BroadcastReceiver volumeRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    QuickPanel(HomeActivity h) {
        super(h);
        this.h = h;
        audio = (AudioManager) h.getSystemService(Context.AUDIO_SERVICE);
        setBackgroundColor(0x99000000); // redupkan home; ketuk di luar panel menutup
        setOnClickListener(v -> close());
        setVisibility(GONE);

        // Bisa digulir: bagiannya akan bertambah, dan layar headunit 720p pendek. fillViewport: bila muat, versi di kaki
        // tetap di dasar panel.
        android.widget.ScrollView scroll = new android.widget.ScrollView(h);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        Theme.fill(scroll);
        scroll.setClickable(true); // sentuhan di panel tidak menutupnya
        panel = new LinearLayout(h);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = h.px(24);
        panel.setPadding(pad, pad, pad, pad);
        scroll.addView(panel, new LayoutParams(MATCH, WRAP));
        addView(scroll, new LayoutParams(Math.min(h.px(420), Math.round(h.wDp * h.dp * .5f)), MATCH, Gravity.START));
        View edge = new View(h); // garis aksen di tepi kanan panel
        edge.setBackgroundColor(LINE);
        LayoutParams el = new LayoutParams(h.px(1), MATCH, Gravity.START);
        el.leftMargin = Math.min(h.px(420), Math.round(h.wDp * h.dp * .5f));
        addView(edge, el);

        LinearLayout head = new LinearLayout(h);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = h.text(h.getString(R.string.quick_title), h.fHeavy, 26, TEXT);
        head.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(key("✕", false, this::close), new LinearLayout.LayoutParams(h.px(48), h.px(44)));
        panel.addView(head);
        Key settings = key("⚙  " + h.getString(R.string.quick_settings), false, () -> {
            close();
            h.openSettings();
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, h.px(48));
        sl.topMargin = h.px(18);
        panel.addView(settings, sl);

        volume = new Level(h, v -> setVolume(v));
        mute = key(h.getString(R.string.quick_mute), false, () -> {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
            refresh();
        });
        section(R.string.quick_volume, row(key("−", false, () -> step(volume, -1)), volume, key("+", false, () -> step(volume, 1))),
                mute);

        brightness = new Level(h, this::setBrightness);
        section(R.string.quick_brightness, row(key("−", false, () -> step(brightness, -1)), brightness,
                key("+", false, () -> step(brightness, 1))));

        section(R.string.quick_screen, key(h.getString(R.string.quick_saver), false, () -> {
            close();
            h.showSaver();
        }));

        // Kaki panel: versi KlikDash (untuk laporan masalah), di dasar panel.
        panel.addView(new View(h), new LinearLayout.LayoutParams(1, 0, 1f));
        String version;
        try {
            version = h.getPackageManager().getPackageInfo(h.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            version = "?";
        }
        TextView foot = h.text("KLIKDASH  " + version, h.fMono, 11, DIM);
        foot.setLetterSpacing(0.25f);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(WRAP, WRAP);
        fl.topMargin = h.px(28); // layar pendek: tidak menempel ke tombol terakhir
        panel.addView(foot, fl);
    }

    /** Satu bagian: label kecil lalu isinya. Fitur baru cukup menambah satu bagian di konstruktor. */
    private void section(int label, View... rows) {
        TextView l = h.text(h.getString(label), h.fMonoBold, 11, AMBER);
        l.setLetterSpacing(0.25f);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(WRAP, WRAP);
        ll.topMargin = h.px(26);
        panel.addView(l, ll);
        for (View r : rows) {
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(r instanceof Key ? WRAP : MATCH, h.px(48));
            rl.topMargin = h.px(10);
            panel.addView(r, rl);
        }
    }

    private LinearLayout row(View minus, View level, View plus) {
        LinearLayout r = new LinearLayout(h);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(minus, new LinearLayout.LayoutParams(h.px(56), MATCH));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, MATCH, 1f);
        ll.leftMargin = ll.rightMargin = h.px(10);
        r.addView(level, ll);
        r.addView(plus, new LinearLayout.LayoutParams(h.px(56), MATCH));
        return r;
    }

    private Key key(String label, boolean on, Runnable click) {
        Key k = new Key(h, h.fMonoBold, label, on, () -> { click.run(); touched(); });
        k.minWidth = h.px(56);
        return k;
    }

    // ---------------------------------------------------------------- buka & tutup

    boolean isOpen() { return getVisibility() == VISIBLE; }

    void open() {
        if (isOpen()) return;
        refresh();
        setVisibility(VISIBLE);
        h.registerReceiver(volumeRx, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        touched();
    }

    void close() {
        if (!isOpen()) return;
        setVisibility(GONE);
        removeCallbacks(idleClose);
        try { h.unregisterReceiver(volumeRx); } catch (IllegalArgumentException ignored) { }
    }

    /** Ada sentuhan di panel: hitung ulang waktu tutup sendiri. */
    void touched() {
        removeCallbacks(idleClose);
        postDelayed(idleClose, IDLE_MS);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touched();
        return super.dispatchTouchEvent(e);
    }

    // ---------------------------------------------------------------- volume & kecerahan

    void refresh() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        volume.set(muted ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC), max);
        mute.setOn(muted);
        int raw = Settings.System.getInt(h.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
        brightness.set(Math.round(toGamma(raw) * BRIGHT_STEPS), BRIGHT_STEPS);
    }

    private void step(Level l, int d) {
        int v = Math.max(0, Math.min(l.max, l.value + d));
        if (l == volume) setVolume(v); else setBrightness(v);
    }

    private void setVolume(int v) {
        if (audio.isStreamMute(AudioManager.STREAM_MUSIC) && v > 0) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        refresh();
    }

    static final int BRIGHT_STEPS = 10;

    // Kurva slider kecerahan Android (SystemUI BrightnessUtils, Android 9+): posisi slider tidak linear terhadap nilai
    // screen_brightness. Dipakai juga di sini supaya batang panel cepat sejajar dengan slider di panel notifikasi.
    private static final float GR = 0.5f, GA = 0.17883277f, GB = 0.28466892f, GC = 0.55991073f;
    private static int minBright = -1, maxBright;

    /** Batas kecerahan yang boleh dipilih pengguna (config_screenBrightnessSetting*), seperti slider sistem. */
    private static void loadRange() {
        if (minBright >= 0) return;
        android.content.res.Resources r = android.content.res.Resources.getSystem();
        int lo = r.getIdentifier("config_screenBrightnessSettingMinimum", "integer", "android");
        int hi = r.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android");
        minBright = lo != 0 ? r.getInteger(lo) : 10;
        maxBright = hi != 0 ? r.getInteger(hi) : 255;
        if (maxBright <= minBright) { minBright = 10; maxBright = 255; }
    }

    /** screen_brightness → posisi slider 0..1. */
    static float toGamma(int raw) {
        loadRange();
        float n = Math.max(0f, Math.min(1f, (raw - minBright) / (float) (maxBright - minBright))) * 12;
        return Math.max(0f, Math.min(1f, n <= 1 ? (float) Math.sqrt(n) * GR : GA * (float) Math.log(n - GB) + GC));
    }

    /** Posisi slider 0..1 → screen_brightness. */
    static int fromGamma(float g) {
        loadRange();
        float n = g <= GR ? (g / GR) * (g / GR) : (float) Math.exp((g - GC) / GA) + GB;
        return Math.round(minBright + (maxBright - minBright) * Math.max(0f, Math.min(1f, n / 12)));
    }

    /**
     * Kecerahan sistem dalam 10 langkah posisi slider Android (langkah 0 = batas bawah sistem, layar tidak gelap total). Izin WRITE_SETTINGS
     * tidak bisa diminta tanpa membuka Pengaturan, jadi tanpa izin itu lewat ADB lokal (seperti penyetelan lain), yang
     * sekaligus memberi izinnya untuk berikutnya. Tanpa ADB: halaman izin "Ubah setelan sistem" dibuka sekali.
     */
    private void setBrightness(int step) {
        int v = fromGamma(step / (float) BRIGHT_STEPS);
        brightness.set(step, BRIGHT_STEPS);
        if (Settings.System.canWrite(h)) {
            Settings.System.putInt(h.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(h.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
        } else {
            Tweaks.shellAsync("settings put system screen_brightness_mode 0; settings put system screen_brightness " + v
                    + "; cmd appops set " + h.getPackageName() + " WRITE_SETTINGS allow", ok -> {
                if (!ok) post(this::askWriteSettings);
            });
        }
    }

    /** Tanpa ADB lokal: minta izin mengubah setelan sistem lewat halaman Android (sekali; kembali dengan Kembali). */
    private void askWriteSettings() {
        if (askedWrite) return;
        askedWrite = true;
        close();
        Notice.info(h, h.getString(R.string.quick_brightness_allow));
        try {
            h.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:" + h.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { // headunit tanpa halaman itu: kecerahan tetap lewat menu headunit
        }
    }

    private boolean askedWrite;

    // ---------------------------------------------------------------- batang level

    interface OnSet { void set(int value); }

    /**
     * Batang bersegmen; ketuk atau seret mendatar untuk memilih nilai (0..max). Geser tegak yang dimulai di atasnya
     * milik panel yang digulir, bukan mengubah nilai.
     */
    static final class Level extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final OnSet onSet;
        final float dp;
        final int slop;
        int value, max = 1;
        private float downX, downY;
        private boolean dragging;

        Level(Context c, OnSet onSet) {
            super(c);
            this.onSet = onSet;
            dp = c.getResources().getDisplayMetrics().density;
            slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }

        private void pick(float x) {
            int v = Math.round(Math.max(0f, Math.min(1f, x / getWidth())) * max);
            if (v == value) return;
            value = v;
            invalidate();
            onSet.set(v);
        }

        void set(int v, int m) {
            value = v;
            max = Math.max(1, m);
            setContentDescription(v + "/" + max);
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(e.getX() - downX) > slop && Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY)) {
                        dragging = true; // mendatar: seret nilai, panel jangan ikut menggulir
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) pick(e.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging || Math.hypot(e.getX() - downX, e.getY() - downY) <= slop) pick(e.getX()); // seret atau ketuk
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL: // panel mengambil alih untuk digulir
                    dragging = false;
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas c) {
            int n = Math.min(max, 20); // banyak langkah (mis. volume 0–30): digambar 20 segmen
            float gap = 3 * dp, w = (getWidth() - gap * (n - 1)) / n, top = getHeight() * .25f, bottom = getHeight() * .75f;
            int lit = Math.round(value * n / (float) max);
            for (int i = 0; i < n; i++) {
                paint.setColor(i < lit ? AMBER : i == lit ? DIM & 0x60FFFFFF : FAINT);
                float x = i * (w + gap);
                c.drawRect(x, top, x + w, bottom, paint);
            }
        }
    }

    // ---------------------------------------------------------------- ikon

    /** Menu ☰: tiga garis rata kiri (sejajar tepi kiri jam), DIM seperti ikon lain di panel jam; aksen saat ditekan. */
    static final class Icon extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float dp;

        Icon(Context c) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            paint.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true);
        }

        @Override protected void onDraw(Canvas c) {
            float cy = getHeight() / 2f, x = 1.5f * dp, w = 20 * dp, gap = 6 * dp;
            paint.setColor(isPressed() ? AMBER : HomeActivity.TEXT);
            paint.setStrokeWidth(2.2f * dp);
            for (int i = -1; i <= 1; i++) c.drawLine(x, cy + i * gap, x + (i == 1 ? w * .65f : w), cy + i * gap, paint);
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
    }
}
