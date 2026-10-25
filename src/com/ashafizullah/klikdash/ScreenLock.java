package com.ashafizullah.klikdash;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.BG;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.FAINT;
import static com.ashafizullah.klikdash.HomeActivity.HOT;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Kunci home dengan PIN 4 angka, supaya anak-anak tidak membuka aplikasi atau mengubah pengaturan. Jam dan jadwal
 * sholat tetap tampil; sentuhan selain ikon gembok diabaikan (HomeActivity.dispatchTouchEvent). PIN disimpan sebagai
 * hash SHA-256 bergaram di prefs DE "klikdash" (lockPin, lockSalt), status kunci di "locked": tetap terkunci setelah
 * headunit tidur atau menyala lagi. Lupa PIN: tahan ⌫ 10 detik lalu ketik kode acak yang tampil.
 */
final class ScreenLock {
    static final int LENGTH = 4;
    private static final int MAX_TRIES = 5;
    private static final long LOCKOUT_MS = 30_000, RESET_HOLD_MS = 10_000;
    /** Terlalu banyak salah: jeda sampai waktu ini (uptime), berlaku untuk semua pad. */
    private static long lockoutUntil;
    private static int wrong;

    private ScreenLock() { }

    static boolean hasPin(SharedPreferences p) { return p.getString("lockPin", null) != null; }

    static boolean locked(SharedPreferences p) { return hasPin(p) && p.getBoolean("locked", false); }

    static void setLocked(SharedPreferences p, boolean on) { p.edit().putBoolean("locked", on).apply(); }

    static boolean check(SharedPreferences p, String pin) {
        String h = p.getString("lockPin", null);
        return h != null && h.equals(hash(p.getString("lockSalt", ""), pin));
    }

    static void setPin(SharedPreferences p, String pin) {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        String salt = hex(b);
        p.edit().putString("lockSalt", salt).putString("lockPin", hash(salt, pin)).putBoolean("locked", false).apply();
    }

    static void clear(SharedPreferences p) { p.edit().remove("lockPin").remove("lockSalt").remove("locked").apply(); }

    private static String hash(String salt, String pin) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest((salt + pin).getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.US, "%02x", x));
        return s.toString();
    }

    // ---------------------------------------------------------------- pad PIN

    interface Done { void run(String pin); }

    /** Minta PIN saat ini; {@code ok} dipanggil bila benar. Reset lewat lupa PIN juga membuka kunci. */
    static void verify(Activity a, SharedPreferences p, Runnable ok, Runnable reset) {
        new Pad(a, a.getString(R.string.lock_enter), null, pin -> {
            if (check(p, pin)) { wrong = 0; return true; }
            if (++wrong >= MAX_TRIES) {
                wrong = 0;
                lockoutUntil = SystemClock.uptimeMillis() + LOCKOUT_MS;
            }
            return false;
        }, pin -> ok.run(), () -> {
            clear(p);
            reset.run();
        }).show();
    }

    /** PIN baru, diketik dua kali. */
    static void create(Activity a, Done done) {
        new Pad(a, a.getString(R.string.lock_new), null, pin -> true, first -> {
            Pad confirm = new Pad(a, a.getString(R.string.lock_confirm), null, pin -> pin.equals(first), done, null);
            confirm.wrongRes = R.string.lock_mismatch;
            confirm.show();
        }, null).show();
    }

    interface Check { boolean ok(String pin); }

    /** Pad angka layar penuh bergaya KlikDash. */
    private static final class Pad {
        final Activity a;
        final Dialog d;
        final TextView title, sub;
        final Dots dots;
        final Check check;
        final Done done;
        final Runnable reset;
        final StringBuilder pin = new StringBuilder();
        /** Pesan saat {@link #check} gagal. */
        int wrongRes = R.string.lock_wrong;
        /** Kode acak saat lupa PIN; selama ada, yang diketik dicocokkan dengan kode ini. */
        String resetCode;
        final float dp;

        Pad(Activity a, String heading, String hint, Check check, Done done, Runnable reset) {
            this.a = a;
            this.check = check;
            this.done = done;
            this.reset = reset;
            dp = a.getResources().getDisplayMetrics().density;
            Typeface mono = Compat.font(a.getAssets(), "mono.ttf", "'wdth' 100, 'wght' 700", Typeface.MONOSPACE);
            Typeface display = Compat.font(a.getAssets(), "display.ttf", "'opsz' 36, 'wght' 700", Typeface.DEFAULT_BOLD);

            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(PANEL);
            bg.setStroke(Math.round(dp), LINE);
            card.setBackground(bg);
            int pad = px(28);
            card.setPadding(pad, px(22), pad, px(22));

            title = text(heading, display, 26, TEXT);
            card.addView(title);
            sub = text(hint != null ? hint : "", mono, 11, DIM);
            sub.setLetterSpacing(0.2f);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.topMargin = px(6);
            card.addView(sub, sl);
            dots = new Dots();
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(4 * 34), px(28));
            dl.topMargin = px(14);
            dl.bottomMargin = px(16);
            card.addView(dots, dl);

            GridLayout grid = new GridLayout(a);
            grid.setColumnCount(3);
            String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", a.getString(R.string.common_cancel).toUpperCase(Lang.locale()), "0", "⌫"};
            for (String s : labels) {
                Key k = new Key(a, mono, s, false, null);
                if (s.equals("⌫")) {
                    k.setOnClickListener(v -> back());
                    Runnable hold = this::askReset;
                    k.setOnTouchListener((v, e) -> {
                        if (reset == null) return false;
                        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.postDelayed(hold, RESET_HOLD_MS);
                        else if (e.getActionMasked() == MotionEvent.ACTION_UP
                                || e.getActionMasked() == MotionEvent.ACTION_CANCEL) v.removeCallbacks(hold);
                        return false;
                    });
                } else if (s.length() == 1) {
                    k.setOnClickListener(v -> digit(s.charAt(0)));
                } else {
                    k.setOnClickListener(v -> close());
                }
                GridLayout.LayoutParams gl = new GridLayout.LayoutParams();
                gl.width = px(92);
                gl.height = px(56);
                gl.setMargins(px(4), px(4), px(4), px(4));
                grid.addView(k, gl);
            }
            card.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0xEE000000 | BG & 0xFFFFFF));
            android.widget.FrameLayout root = new android.widget.FrameLayout(a);
            root.addView(card, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            d.setContentView(root);
            d.setCanceledOnTouchOutside(false);
            refreshLockout();
        }

        void show() {
            d.show();
            Notice.track(d);
        }

        void close() {
            if (d.isShowing()) d.dismiss();
        }

        void digit(char c) {
            if ((resetCode == null && lockedOut()) || pin.length() >= LENGTH) return;
            pin.append(c);
            dots.invalidate();
            if (pin.length() < LENGTH) return;
            String s = pin.toString();
            dots.postDelayed(() -> submit(s), 120); // titik keempat sempat terlihat
        }

        void back() {
            if (pin.length() == 0) return;
            pin.setLength(pin.length() - 1);
            dots.invalidate();
        }

        void submit(String s) {
            if (resetCode != null) {
                if (s.equals(resetCode)) {
                    close();
                    reset.run();
                } else {
                    wrongInput(a.getString(R.string.lock_reset_wrong));
                }
                return;
            }
            if (check.ok(s)) {
                close();
                done.run(s);
            } else {
                wrongInput(a.getString(lockedOut() ? R.string.lock_too_many : wrongRes));
                refreshLockout();
            }
        }

        void wrongInput(String msg) {
            pin.setLength(0);
            dots.error = true;
            dots.invalidate();
            sub.setText(msg);
            sub.setTextColor(HOT);
            dots.postDelayed(() -> { dots.error = false; dots.invalidate(); }, 600);
        }

        boolean lockedOut() { return SystemClock.uptimeMillis() < lockoutUntil; }

        /** Hitung mundur selama jeda karena terlalu banyak salah. */
        void refreshLockout() {
            if (!lockedOut()) return;
            Runnable tick = new Runnable() {
                @Override public void run() {
                    long left = lockoutUntil - SystemClock.uptimeMillis();
                    if (left <= 0) {
                        sub.setText("");
                        sub.setTextColor(DIM);
                        return;
                    }
                    sub.setText(a.getString(R.string.lock_wait, (int) ((left + 999) / 1000)));
                    sub.setTextColor(HOT);
                    sub.postDelayed(this, 250);
                }
            };
            tick.run();
        }

        /** Lupa PIN: tampilkan kode acak yang harus diketik. Sulit bagi anak kecil, mudah bagi orang dewasa. */
        void askReset() {
            resetCode = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10_000));
            pin.setLength(0);
            dots.invalidate();
            title.setText(R.string.lock_reset_title);
            sub.setText(a.getString(R.string.lock_reset_hint, resetCode.replace("", " ").trim()));
            sub.setTextColor(AMBER);
        }

        TextView text(String s, Typeface tf, float size, int color) {
            TextView t = new TextView(a);
            t.setText(s);
            t.setTypeface(tf);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
            t.setTextColor(color);
            t.setIncludeFontPadding(false);
            t.setGravity(Gravity.CENTER);
            return t;
        }

        int px(float v) { return Math.round(v * dp); }

        /** Empat titik: terisi sesuai angka yang sudah diketik, merah sesaat bila salah. */
        final class Dots extends View {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            boolean error;

            Dots() { super(a); }

            @Override protected void onDraw(Canvas c) {
                float r = 7 * dp, step = getWidth() / (float) LENGTH, cy = getHeight() / 2f;
                for (int i = 0; i < LENGTH; i++) {
                    boolean on = i < pin.length();
                    p.setStyle(on || error ? Paint.Style.FILL : Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f * dp);
                    p.setColor(error ? HOT : on ? AMBER : FAINT);
                    c.drawCircle(step * i + step / 2f, cy, r, p);
                }
            }
        }
    }

    // ---------------------------------------------------------------- ikon gembok

    /** Ikon gembok di home: terbuka (redup) saat tidak terkunci, tertutup (amber) saat terkunci. */
    static final class Icon extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float dp;
        boolean closed;

        Icon(Activity a) {
            super(a);
            dp = a.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true);
        }

        void set(boolean closed) {
            this.closed = closed;
            setContentDescription(getContext().getString(closed ? R.string.lock_unlock : R.string.lock_lock));
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, s = dp;
            p.setColor(isPressed() ? TEXT : closed ? AMBER : DIM);
            p.setStrokeWidth(2 * s);
            // Badan gembok
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(cx - 8 * s, cy - 1 * s, cx + 8 * s, cy + 11 * s, 2 * s, 2 * s, p);
            // Lengkung: tertutup masuk ke kedua sisi, terbuka terangkat di kanan
            p.setStyle(Paint.Style.STROKE);
            float top = cy - 11 * s, left = cx - 5 * s, right = cx + 5 * s;
            if (closed) {
                c.drawArc(left, top, right, top + 10 * s, 180, 180, false, p);
                c.drawLine(left, top + 5 * s, left, cy - 1 * s, p);
                c.drawLine(right, top + 5 * s, right, cy - 1 * s, p);
            } else {
                c.drawArc(left, top - 3 * s, right, top + 7 * s, 180, 180, false, p);
                c.drawLine(left, top + 2 * s, left, cy - 1 * s, p);
                c.drawLine(right, top + 2 * s, right, top + 5 * s, p);
            }
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
    }
}
