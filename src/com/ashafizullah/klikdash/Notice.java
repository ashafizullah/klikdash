package com.ashafizullah.klikdash;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.HOT;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Notifikasi singkat bergaya KlikDash, pengganti Toast dan dialog info ber-tombol OK: panel gelap bergaris tipis
 * dengan strip warna di kiri (amber = berhasil, merah = gagal, abu = info), di bawah tengah layar, hilang sendiri
 * atau saat diketuk. Tampil di atas dialog yang sedang terbuka (lihat {@link #track}); yang baru menggantikan yang lama.
 * Boleh dipanggil dari thread mana pun.
 */
final class Notice {
    static final int INFO = 0, OK = 1, FAIL = 2, REMIND = 3;

    private Notice() { }

    private static WeakReference<PopupWindow> current = new WeakReference<>(null);
    private static final List<WeakReference<Dialog>> dialogs = new ArrayList<>();
    private static Typeface fTag, fMsg;

    static void info(Activity a, String msg) { show(a, INFO, null, msg); }

    static void ok(Activity a, String msg) { show(a, OK, null, msg); }

    static void fail(Activity a, String msg) { show(a, FAIL, null, msg); }

    /** {@code title}: konteks singkat di samping jenisnya, mis. nama profil AI ("BERHASIL · CLAUDE"). */
    static void show(Activity a, int kind, String title, String msg) {
        a.runOnUiThread(() -> showNow(a, kind, title, msg));
    }

    /** Dialog yang sedang terbuka; notifikasi muncul di atasnya, bukan tertutup di belakangnya. */
    static void track(Dialog d) {
        dialogs.add(new WeakReference<>(d));
    }

    private static void showNow(Activity a, int kind, String title, String msg) {
        if (a.isFinishing() || a.isDestroyed()) return;
        View parent = topWindow(a);
        if (parent.getWindowToken() == null) {
            parent.post(() -> showNow(a, kind, title, msg)); // jendela belum terpasang (onCreate)
            return;
        }
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout box = build(a, kind, title, msg);

        PopupWindow old = current.get();
        if (old != null) dismiss(old);
        PopupWindow p = new PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        p.setBackgroundDrawable(new ColorDrawable(0)); // tanpa ini Android 7 tidak meneruskan ketukan ke isi
        p.setTouchable(true);
        p.setAnimationStyle(android.R.style.Animation_Toast);
        p.setElevation(16 * dp); // bayangan, supaya terpisah dari isi layar yang sama gelapnya
        box.setOnClickListener(v -> dismiss(p));
        // Selalu di bawah tengah layar aktivitas. Posisi popup dihitung dari jendela induknya, yang bisa berupa dialog
        // kecil di tengah, jadi koordinatnya digeser dari dialog ke layar dan boleh keluar dari batas dialog.
        View screen = a.getWindow().getDecorView();
        int[] s = new int[2], t = new int[2];
        screen.getLocationOnScreen(s);
        parent.getLocationOnScreen(t);
        box.measure(View.MeasureSpec.makeMeasureSpec(screen.getWidth(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screen.getHeight(), View.MeasureSpec.AT_MOST));
        int x = s[0] - t[0] + (screen.getWidth() - box.getMeasuredWidth()) / 2;
        int y = s[1] - t[1] + screen.getHeight() - box.getMeasuredHeight() - Math.round(32 * dp);
        p.setClippingEnabled(false);
        try {
            p.showAtLocation(parent, Gravity.TOP | Gravity.START, x, y);
        } catch (RuntimeException e) {
            return; // jendela induk baru saja ditutup
        }
        current = new WeakReference<>(p);
        box.postDelayed(() -> dismiss(p), duration(kind, msg));
        box.announceForAccessibility(msg);
    }

    /** Isi notifikasi: panel bergaris tipis, strip warna jenisnya, label, lalu pesan. */
    private static LinearLayout build(Context c, int kind, String title, String msg) {
        float dp = c.getResources().getDisplayMetrics().density;
        if (fTag == null) {
            fTag = Compat.font(c.getAssets(), "mono.ttf", "'wdth' 100, 'wght' 700", Typeface.MONOSPACE);
            fMsg = Compat.font(c.getAssets(), "display.ttf", "'opsz' 36, 'wght' 600", Typeface.DEFAULT);
        }
        int color = kind == OK || kind == REMIND ? AMBER : kind == FAIL ? HOT : DIM;

        LinearLayout box = new LinearLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setStroke(Math.round(dp), LINE);
        box.setBackground(bg);
        box.setPadding(Math.round(dp), Math.round(dp), Math.round(dp), Math.round(dp));

        View strip = new View(c);
        strip.setBackgroundColor(color);
        box.addView(strip, new LinearLayout.LayoutParams(Math.round(4 * dp), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int ph = Math.round(22 * dp), pv = Math.round(16 * dp);
        col.setPadding(ph, pv, Math.round(24 * dp), pv);
        TextView tag = new TextView(c);
        String kindText = kind == OK ? "BERHASIL" : kind == FAIL ? "GAGAL" : kind == REMIND ? "PENGINGAT" : "INFO";
        tag.setText(title != null && !title.isEmpty() ? kindText + "  ·  " + title.toUpperCase(HomeActivity.ID) : kindText);
        tag.setTypeface(fTag);
        tag.setTextSize(12);
        tag.setLetterSpacing(0.15f);
        tag.setTextColor(color);
        tag.setSingleLine(true);
        col.addView(tag);
        TextView body = new TextView(c);
        body.setText(msg);
        body.setTypeface(fMsg);
        body.setTextSize(22);
        body.setTextColor(TEXT);
        body.setLineSpacing(0, 1.15f);
        body.setMaxLines(5);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.setMaxWidth(Math.round(560 * dp));
        body.setPadding(0, Math.round(6 * dp), 0, 0);
        col.addView(body);
        box.addView(col, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.setContentDescription(tag.getText() + ": " + msg);
        return box;
    }

    /** Cukup lama untuk dibaca sekilas sambil menyetir: 3 detik + 50 ms per huruf, paling lama 9 detik; pengingat 15 detik. */
    private static long duration(int kind, String msg) {
        return kind == REMIND ? 15_000 : Math.max(3000, Math.min(9000, 2500 + 50L * msg.length()));
    }

    /**
     * Di atas aplikasi lain (izin "tampil di atas aplikasi lain"), mis. pengingat sholat saat Maps di depan. false bila
     * izinnya belum ada; pemanggil memakai cara lain.
     */
    @SuppressWarnings("deprecation")
    static boolean overlay(Context c, int kind, String title, String msg) {
        if (!android.provider.Settings.canDrawOverlays(c)) return false;
        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        LinearLayout box = build(app, kind, title, msg);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Compat.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(32 * app.getResources().getDisplayMetrics().density);
        lp.windowAnimations = android.R.style.Animation_Toast;
        Runnable remove = () -> {
            try {
                if (box.isAttachedToWindow()) wm.removeView(box);
            } catch (RuntimeException ignored) { }
        };
        box.setOnClickListener(v -> remove.run());
        try {
            wm.addView(box, lp);
        } catch (RuntimeException e) {
            return false;
        }
        box.postDelayed(remove, duration(kind, msg));
        return true;
    }

    /** Jendela teratas milik {@code a}: dialog terbuka yang terakhir di-{@link #track}, atau jendela aktivitasnya. */
    private static View topWindow(Activity a) {
        for (int i = dialogs.size() - 1; i >= 0; i--) {
            Dialog d = dialogs.get(i).get();
            if (d == null || !d.isShowing()) {
                dialogs.remove(i); // dialog KlikDash tidak dibuka ulang setelah ditutup
                continue;
            }
            if (owner(d.getContext()) == a && d.getWindow() != null) return d.getWindow().getDecorView();
        }
        return a.getWindow().getDecorView();
    }

    private static Activity owner(Context c) {
        while (c instanceof ContextWrapper && !(c instanceof Activity)) c = ((ContextWrapper) c).getBaseContext();
        return c instanceof Activity ? (Activity) c : null;
    }

    private static void dismiss(PopupWindow p) {
        if (!p.isShowing()) return;
        try {
            p.dismiss();
        } catch (RuntimeException ignored) { } // jendela induk sudah hilang lebih dulu
    }
}
