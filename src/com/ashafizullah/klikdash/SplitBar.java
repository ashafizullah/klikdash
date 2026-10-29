package com.ashafizullah.klikdash;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.util.HashMap;
import java.util.Map;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.WRAP;

/**
 * Pembatas layar terbagi di atas kedua aplikasi (izin tampil di atas aplikasi lain, seperti {@link BeamFloat}): garis
 * tipis selebar layar dengan pegangan di tengahnya, menutupi pembatas sistem bila ada. Geser untuk mengubah ukuran
 * (dilepas di dekat tepi = tutup sisi itu); ketuk untuk tombol ✕ tiap aplikasi dan ⇄ tukar. Ukurannya baru diterapkan
 * saat dilepas: tiap perubahan adalah perintah ADB, terlalu lambat untuk mengikuti jari.
 */
final class SplitBar {
    private static final long MENU_MS = 5000;

    private final Context c;
    private final WindowManager wm;
    private final float dp;
    private final Strip strip;
    private final WindowManager.LayoutParams lp, menuLp;
    private final LinearLayout menu;
    private final Key closeFirst, swap, closeSecond;
    private final Map<String, String> labels = new HashMap<>();
    private final Runnable hideMenu = this::hideMenu;
    /** Gabungan kedua sisi saat terakhir dibaca: batas geser dan dasar perbandingan ukuran. */
    private final Rect all = new Rect();
    private boolean stacked, dragging;

    SplitBar(Context app) {
        Theme.load(app);
        c = Lang.wrap(app); // context aplikasi tidak ikut bahasa KlikDash
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        dp = c.getResources().getDisplayMetrics().density;

        strip = new Strip();
        lp = params(WRAP, WRAP);
        strip.setOnTouchListener(new Drag());

        Typeface mono = Compat.font(c.getAssets(), "mono.ttf", "'wdth' 100, 'wght' 700", Typeface.MONOSPACE);
        menu = new LinearLayout(c);
        menu.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setStroke(Math.round(dp), LINE);
        bg.setCornerRadius(Theme.corner(dp));
        menu.setBackground(bg);
        int pad = px(8);
        menu.setPadding(pad, pad, pad, pad);
        closeFirst = key(mono, "", () -> SplitScreen.close(false));
        swap = key(mono, c.getString(R.string.split_swap), SplitScreen::swap);
        closeSecond = key(mono, "", () -> SplitScreen.close(true));
        menuLp = params(WRAP, WRAP);
        menuLp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        menu.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) hideMenu();
            return false;
        });
    }

    @SuppressWarnings("deprecation")
    private static WindowManager.LayoutParams params(int w, int h) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                Compat.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.windowAnimations = 0;
        return p;
    }

    private Key key(Typeface font, String label, Runnable click) {
        Key k = new Key(c, font, label, false, () -> {
            hideMenu();
            click.run();
        });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(WRAP, px(48));
        l.setMargins(px(4), 0, px(4), 0);
        menu.addView(k, l);
        return k;
    }

    // ---------------------------------------------------------------- tampil

    /** Letakkan pembatas di antara kedua sisi; dipanggil tiap pembacaan {@link SplitScreen}. */
    void show(SplitScreen.Pair p) {
        if (!Settings.canDrawOverlays(c)) return;
        closeFirst.setLabel("✕  " + label(p.first.pkg));
        closeSecond.setLabel("✕  " + label(p.second.pkg));
        if (dragging) return; // jangan melawan jari
        all.set(p.a);
        all.union(p.b);
        stacked = p.stacked();
        int t = px(28);
        if (stacked) {
            lp.width = all.width();
            lp.height = t;
            lp.x = all.left;
            lp.y = (p.a.bottom + p.b.top) / 2 - t / 2;
        } else {
            lp.width = t;
            lp.height = all.height();
            lp.x = (p.a.right + p.b.left) / 2 - t / 2;
            lp.y = all.top;
        }
        strip.invalidate();
        try {
            if (strip.isAttachedToWindow()) wm.updateViewLayout(strip, lp);
            else wm.addView(strip, lp);
        } catch (RuntimeException ignored) { } // izin dicabut di tengah jalan
        if (menu.isAttachedToWindow()) placeMenu();
    }

    void hide() {
        hideMenu();
        try {
            if (strip.isAttachedToWindow()) wm.removeView(strip);
        } catch (RuntimeException ignored) { }
    }

    private void toggleMenu() {
        if (menu.isAttachedToWindow()) {
            hideMenu();
            return;
        }
        try {
            placeMenu();
            wm.addView(menu, menuLp);
        } catch (RuntimeException ignored) { }
        menu.removeCallbacks(hideMenu);
        menu.postDelayed(hideMenu, MENU_MS);
    }

    /** Di tengah pegangan. */
    private void placeMenu() {
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int cx = lp.x + lp.width / 2, cy = lp.y + lp.height / 2;
        menuLp.x = Math.max(all.left, Math.min(all.right - menu.getMeasuredWidth(), cx - menu.getMeasuredWidth() / 2));
        menuLp.y = Math.max(all.top, Math.min(all.bottom - menu.getMeasuredHeight(), cy - menu.getMeasuredHeight() / 2));
        if (menu.isAttachedToWindow()) wm.updateViewLayout(menu, menuLp);
    }

    private void hideMenu() {
        menu.removeCallbacks(hideMenu);
        try {
            if (menu.isAttachedToWindow()) wm.removeView(menu);
        } catch (RuntimeException ignored) { }
    }

    private String label(String pkg) {
        String l = labels.get(pkg);
        if (l != null) return l;
        try {
            PackageManager pm = c.getPackageManager();
            l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().toUpperCase(Lang.locale());
        } catch (PackageManager.NameNotFoundException e) {
            l = pkg;
        }
        if (l.length() > 14) l = l.substring(0, 13) + "…";
        labels.put(pkg, l);
        return l;
    }

    private int px(int v) { return Math.round(v * dp); }

    // ---------------------------------------------------------------- pegangan

    /** Garis gelap sepanjang pembatas dan pegangan aksen di tengah. */
    private final class Strip extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        Strip() { super(c); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, line = 4 * dp;
            paint.setColor(0xFF000000);
            if (stacked) cv.drawRect(0, cy - line / 2, w, cy + line / 2, paint);
            else cv.drawRect(cx - line / 2, 0, cx + line / 2, h, paint);
            float lo = 22 * dp, sh = 34 * dp; // pegangan: 22 x 68 dp
            if (stacked) r.set(cx - sh, cy - lo / 2, cx + sh, cy + lo / 2);
            else r.set(cx - lo / 2, cy - sh, cx + lo / 2, cy + sh);
            paint.setColor(dragging ? AMBER : PANEL);
            cv.drawRoundRect(r, lo / 2, lo / 2, paint);
            paint.setColor(dragging ? PANEL : AMBER);
            for (int i = -1; i <= 1; i++) cv.drawCircle(stacked ? cx + i * 8 * dp : cx, stacked ? cy : cy + i * 8 * dp, 2.2f * dp, paint);
        }
    }

    private final class Drag implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(c).getScaledTouchSlop();
        private float down;
        private int start;

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouch(View v, MotionEvent e) {
            float pos = stacked ? e.getRawY() : e.getRawX();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = pos;
                    start = stacked ? lp.y : lp.x;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(pos - down) > slop) {
                        dragging = true;
                        hideMenu();
                    }
                    if (dragging) {
                        int half = (stacked ? lp.height : lp.width) / 2;
                        int lo = (stacked ? all.top : all.left) - half, hi = (stacked ? all.bottom : all.right) - half;
                        int at = Math.max(lo, Math.min(hi, start + Math.round(pos - down)));
                        if (stacked) lp.y = at; else lp.x = at;
                        strip.invalidate();
                        try { wm.updateViewLayout(strip, lp); } catch (RuntimeException ignored) { }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        float centre = stacked ? lp.y + lp.height / 2f : lp.x + lp.width / 2f;
                        float ratio = stacked ? (centre - all.top) / all.height() : (centre - all.left) / all.width();
                        dragging = false;
                        strip.invalidate();
                        SplitScreen.resize(ratio);
                    } else {
                        toggleMenu();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    strip.invalidate();
                    return true;
            }
            return false;
        }
    }
}
