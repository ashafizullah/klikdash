package com.ashafizullah.klikdash;

import android.annotation.SuppressLint;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.INK;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.MATCH;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;
import static com.ashafizullah.klikdash.HomeActivity.WRAP;

/**
 * Jendela melayang KlikBeam di atas aplikasi lain (izin "tampil di atas aplikasi lain", seperti {@link Notice}):
 * video HP dalam kotak kecil yang bisa digeser ke mana saja. Ketuk sekali untuk tombol (LAYAR PENUH di tengah, ✕ tutup
 * di pojok kanan atas), ketuk dua kali untuk layar penuh, cubit dua jari untuk mengubah ukuran. Sentuhan di luar
 * kotak tetap ke aplikasi di belakangnya. Posisi dan ukuran disimpan di prefs "beam" (floatX, floatY, floatScale).
 */
final class BeamFloat implements BeamHub.Listener {
    /** Batas cubit: lebar kotak sebagai bagian dari lebar layar. */
    private static final float MIN_SCALE = .22f, MAX_SCALE = .9f;
    /** Ukuran lama dari tombol UKURAN (prefs floatSize: kecil, sedang, besar), jadi ukuran awal bila belum dicubit. */
    private static final float[] OLD_SIZES = {.3f, .42f, .56f};
    private static final long CONTROLS_MS = 3500;

    private final Context c;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final WindowManager.LayoutParams lp;
    private final FrameLayout box;
    private final SurfaceView video;
    private final TextView label;
    private final FrameLayout controls;
    private final Point screen = new Point();
    private Surface surface;
    /** Lebar kotak sebagai bagian dari lebar layar. */
    private float scale;

    /**
     * Layar headunit berputar atau berubah ukuran: hitung ulang kotak supaya tetap di dalam layar. Lewat aplikasi,
     * karena view jendela melayang tidak menerima onConfigurationChanged.
     */
    private final ComponentCallbacks resized = new ComponentCallbacks() {
        @Override public void onConfigurationChanged(Configuration cfg) {
            measureScreen();
            layout();
            update();
        }
        @Override public void onLowMemory() { }
    };

    private final Runnable hideControls = new Runnable() {
        @Override public void run() { controls.setVisibility(View.GONE); }
    };

    /** Izin tampil di atas aplikasi lain; dicoba diberikan lewat ADB lokal bila belum (seperti pengingat sholat). */
    static boolean allowed(Context c) {
        if (Settings.canDrawOverlays(c)) return true;
        Tweaks.shellAsync("cmd appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        return false;
    }

    @SuppressWarnings("deprecation")
    BeamFloat(Context app) {
        Theme.load(app);
        c = Lang.wrap(app); // context aplikasi tidak ikut bahasa KlikDash
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        prefs = app.getSharedPreferences("beam", Context.MODE_PRIVATE);
        int old = Math.max(0, Math.min(OLD_SIZES.length - 1, prefs.getInt("floatSize", 1)));
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, prefs.getFloat("floatScale", OLD_SIZES[old])));

        box = new FrameLayout(c);
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(0xFF000000);
        frame.setStroke(dp(2), LINE);
        frame.setCornerRadius(dp(6));
        box.setBackground(frame);
        int pad = dp(2);
        box.setPadding(pad, pad, pad, pad);

        video = new SurfaceView(c);
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                surface = h.getSurface();
                BeamHub.attach(surface);
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                BeamHub.detach(h.getSurface());
                surface = null;
            }
        });
        box.addView(video, new FrameLayout.LayoutParams(MATCH, MATCH));

        Typeface mono = Compat.font(c.getAssets(), "mono.ttf", "'wdth' 100, 'wght' 700", Typeface.MONOSPACE);
        label = new TextView(c);
        label.setTypeface(mono);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setLetterSpacing(.12f);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable chip = new GradientDrawable(); // terbaca di atas gambar terakhir
        chip.setColor(0xCC000000);
        chip.setCornerRadius(Theme.corner(c.getResources().getDisplayMetrics().density, dp(4)));
        label.setBackground(chip);
        box.addView(label, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));

        controls = new FrameLayout(c);
        controls.setBackgroundColor(0xB0000000);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER);
        row.addView(button(R.string.beam_fullscreen, mono, v -> expand()));
        controls.addView(row, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        controls.addView(closeButton(), new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END));
        controls.setVisibility(View.GONE);
        box.addView(controls, new FrameLayout.LayoutParams(MATCH, MATCH));

        lp = new WindowManager.LayoutParams(WRAP, WRAP,
                Compat.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        measureScreen();
        lp.x = prefs.getInt("floatX", Integer.MIN_VALUE);
        lp.y = prefs.getInt("floatY", Integer.MIN_VALUE);
        box.setOnTouchListener(new Drag());
    }

    /** false bila Android menolak menampilkan jendela (mis. izin dicabut tepat saat itu). */
    boolean show() {
        layout();
        if (lp.x == Integer.MIN_VALUE) { // pertama kali: pojok kanan bawah
            lp.x = screen.x - lp.width - dp(24);
            lp.y = screen.y - lp.height - dp(24);
        }
        clamp();
        try {
            wm.addView(box, lp);
        } catch (RuntimeException e) {
            return false; // izin dicabut di tengah jalan
        }
        c.getApplicationContext().registerComponentCallbacks(resized);
        BeamHub.addListener(this);
        onBeamChanged();
        return true;
    }

    void hide() {
        BeamHub.removeListener(this);
        c.getApplicationContext().unregisterComponentCallbacks(resized);
        box.removeCallbacks(hideControls);
        try {
            if (box.isAttachedToWindow()) wm.removeView(box);
        } catch (RuntimeException ignored) { }
    }

    @Override
    public void onBeamChanged() {
        if (BeamHub.reconnecting) {
            showLabel(c.getString(R.string.beam_reconnecting), AMBER);
        } else if (!BeamHub.streaming) {
            showLabel(BeamHub.stateArg == null ? c.getString(BeamHub.stateRes) : c.getString(BeamHub.stateRes, BeamHub.stateArg), DIM);
        } else {
            label.setVisibility(View.GONE);
        }
        if (layout()) update();
    }

    // ---- Ukuran & posisi ----

    @SuppressWarnings("deprecation")
    private void measureScreen() {
        wm.getDefaultDisplay().getSize(screen); // tanpa bilah navigasi, supaya kotak tidak tertutup
    }

    /** Ukuran kotak dari {@link #scale} dan rasio video; true bila berubah. */
    private boolean layout() {
        float vw = BeamHub.videoW, vh = BeamHub.videoH;
        int w = Math.round(screen.x * scale);
        int h = Math.round(w * vh / vw);
        int maxH = Math.round(screen.y * .85f);
        if (h > maxH) { // video portrait: batasi tinggi
            h = maxH;
            w = Math.round(h * vw / vh);
        }
        w += 2 * dp(2);
        h += 2 * dp(2);
        boolean changed = w != lp.width || h != lp.height;
        lp.width = w;
        lp.height = h;
        return changed;
    }

    private void clamp() {
        lp.x = Math.max(0, Math.min(screen.x - lp.width, lp.x));
        lp.y = Math.max(0, Math.min(screen.y - lp.height, lp.y));
    }

    private void update() {
        clamp();
        try {
            if (box.isAttachedToWindow()) wm.updateViewLayout(box, lp);
        } catch (RuntimeException ignored) { }
    }

    private void expand() {
        controls.setVisibility(View.GONE);
        // Jendela ini ditutup BeamActivity setelah Surface layar penuh siap, supaya gambar tidak putus.
        c.startActivity(new Intent(c, BeamActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        box.removeCallbacks(hideControls);
        box.postDelayed(hideControls, CONTROLS_MS);
    }

    private void showLabel(String s, int color) {
        label.setText(s);
        label.setTextColor(color);
        label.setVisibility(View.VISIBLE);
    }

    private TextView button(int res, Typeface tf, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(c.getString(res));
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); // cukup besar untuk diketuk di mobil
        t.setLetterSpacing(.1f);
        t.setTextColor(res == R.string.beam_fullscreen ? INK : TEXT);
        GradientDrawable bg = new GradientDrawable();
        if (res == R.string.beam_fullscreen) Theme.tint(bg, AMBER);
        else bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(Theme.corner(c.getResources().getDisplayMetrics().density, dp(3)));
        t.setBackground(bg);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
        p.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(p);
        return t;
    }

    /** Tutup di pojok kanan atas, terpisah dari LAYAR PENUH dan UKURAN supaya tidak berdesakan di kotak kecil. */
    private TextView closeButton() {
        TextView t = new TextView(c);
        t.setText("✕");
        t.setTypeface(Typeface.DEFAULT_BOLD); // font mono KlikDash tidak punya ✕
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(TEXT);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription(c.getString(R.string.beam_close));
        t.setOnClickListener(v -> BeamHub.close());
        return t;
    }

    private int dp(int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /**
     * Geser kotak; cubit dua jari untuk ukuran (titik tengah kotak tetap di tempat); ketukan diteruskan ke
     * GestureDetector (sekali = tombol, dua kali = layar penuh). Kedua jari harus di dalam kotak: di luarnya sentuhan
     * milik aplikasi di belakang.
     */
    private final class Drag implements View.OnTouchListener {
        /** Sedang dicubit; berlaku sampai semua jari diangkat supaya jari yang tersisa tidak menggeser kotak. */
        private boolean pinching;
        /**
         * Jarak dua jari dan ukuran saat cubit mulai. Dihitung sendiri: ScaleGestureDetector baru bekerja bila jarak jari
         * ±27 mm, padahal kotak kecil hanya selebar itu, jadi melebarkannya dengan dua jari di dalam kotak tidak jalan.
         */
        private float pinchStart, scaleStart;

        private float span(MotionEvent e) { return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); }

        private void pinchTo(float s) {
            s = Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
            if (s == scale) return;
            int cx = lp.x + lp.width / 2, cy = lp.y + lp.height / 2; // titik tengah kotak tetap di tempat
            scale = s;
            if (layout()) {
                lp.x = cx - lp.width / 2;
                lp.y = cy - lp.height / 2;
                update();
            }
        }
        private final int slop = ViewConfiguration.get(c).getScaledTouchSlop();
        private final GestureDetector taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (controls.getVisibility() == View.VISIBLE) hideControls.run();
                else showControls();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                expand();
                return true;
            }
        });
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = pinching = false;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN: // jari kedua: cubit, bukan geser atau ketuk
                    pinchStart = e.getPointerCount() >= 2 ? span(e) : 0;
                    scaleStart = scale;
                    if (!pinching) {
                        pinching = true;
                        dragging = false;
                        MotionEvent cancel = MotionEvent.obtain(e);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        taps.onTouchEvent(cancel);
                        cancel.recycle();
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP: // jari yang tersisa bisa jadi pasangan baru: mulai dari ukuran sekarang
                    pinchStart = 0;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (pinching) {
                        if (e.getPointerCount() >= 2) {
                            if (pinchStart < 1) { pinchStart = span(e); scaleStart = scale; }
                            else pinchTo(scaleStart * span(e) / pinchStart);
                        }
                        break;
                    }
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        MotionEvent cancel = MotionEvent.obtain(e); // batalkan ketukan yang sedang dihitung
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        taps.onTouchEvent(cancel);
                        cancel.recycle();
                    }
                    if (dragging) {
                        lp.x = startX + Math.round(dx);
                        lp.y = startY + Math.round(dy);
                        update();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pinching) prefs.edit().putFloat("floatScale", scale).putInt("floatX", lp.x).putInt("floatY", lp.y).apply();
                    else if (dragging) prefs.edit().putInt("floatX", lp.x).putInt("floatY", lp.y).apply();
                    break;
            }
            if (!dragging && !pinching) taps.onTouchEvent(e);
            return true;
        }
    }
}
