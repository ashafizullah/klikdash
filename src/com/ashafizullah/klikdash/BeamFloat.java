package com.ashafizullah.klikdash;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
 * video HP dalam kotak kecil yang bisa digeser ke mana saja. Ketuk sekali untuk tombol (LAYAR PENUH, UKURAN,
 * TUTUP), ketuk dua kali untuk layar penuh. Sentuhan di luar kotak tetap ke aplikasi di belakangnya. Posisi dan
 * ukuran disimpan di prefs "beam" (floatX, floatY, floatSize).
 */
final class BeamFloat implements BeamHub.Listener {
    /** Lebar kotak sebagai bagian dari lebar layar: kecil, sedang, besar. */
    private static final float[] SIZES = {.3f, .42f, .56f};
    private static final long CONTROLS_MS = 3500;

    private final Context c;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final WindowManager.LayoutParams lp;
    private final FrameLayout box;
    private final SurfaceView video;
    private final TextView label;
    private final LinearLayout controls;
    private final Point screen = new Point();
    private Surface surface;
    private int size;

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
        size = Math.max(0, Math.min(SIZES.length - 1, prefs.getInt("floatSize", 1)));

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

        controls = new LinearLayout(c);
        controls.setGravity(Gravity.CENTER);
        controls.setBackgroundColor(0xB0000000);
        controls.addView(button(R.string.beam_fullscreen, mono, v -> expand()));
        controls.addView(button(R.string.beam_size, mono, v -> cycleSize()));
        controls.addView(button(R.string.beam_close, mono, v -> BeamHub.stop()));
        controls.setVisibility(View.GONE);
        box.addView(controls, new FrameLayout.LayoutParams(MATCH, MATCH));

        lp = new WindowManager.LayoutParams(WRAP, WRAP,
                Compat.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.getDefaultDisplay().getSize(screen); // tanpa bilah navigasi, supaya kotak tidak tertutup
        lp.x = prefs.getInt("floatX", Integer.MIN_VALUE);
        lp.y = prefs.getInt("floatY", Integer.MIN_VALUE);
        box.setOnTouchListener(new Drag());
    }

    void show() {
        layout();
        if (lp.x == Integer.MIN_VALUE) { // pertama kali: pojok kanan bawah
            lp.x = screen.x - lp.width - dp(24);
            lp.y = screen.y - lp.height - dp(24);
        }
        clamp();
        try {
            wm.addView(box, lp);
        } catch (RuntimeException e) {
            return; // izin dicabut di tengah jalan
        }
        BeamHub.addListener(this);
        onBeamChanged();
    }

    void hide() {
        BeamHub.removeListener(this);
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

    /** Ukuran kotak dari pilihan ukuran dan rasio video; true bila berubah. */
    private boolean layout() {
        float vw = BeamHub.videoW, vh = BeamHub.videoH;
        int w = Math.round(screen.x * SIZES[size]);
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

    private void cycleSize() {
        size = (size + 1) % SIZES.length;
        prefs.edit().putInt("floatSize", size).apply();
        layout();
        update();
        showControls();
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

    private int dp(int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Geser kotak; ketukan diteruskan ke GestureDetector (sekali = tombol, dua kali = layar penuh). */
    private final class Drag implements View.OnTouchListener {
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
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
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
                    if (dragging) prefs.edit().putInt("floatX", lp.x).putInt("floatY", lp.y).apply();
                    break;
            }
            if (!dragging) taps.onTouchEvent(e);
            return true;
        }
    }
}
