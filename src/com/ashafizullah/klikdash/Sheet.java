package com.ashafizullah.klikdash;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.SOFT;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Popup bergaya KlikDash, sekeluarga dengan {@link Notice}: panel gelap bergaris tipis dengan strip warna di kiri,
 * label mono, judul tebal, isi (paragraf atau langkah bernomor), QR video tutorial di kanan ({@link HelpVideo}), dan
 * tombol {@link Key} di bawah. Layar sempit (&lt; 640 dp): QR di bawah isi.
 */
final class Sheet {
    private final Activity a;
    private final Dialog d;
    private final float dp;
    private final LinearLayout body, keys;
    private final LinearLayout content;
    private final Typeface fMono, fMonoBold, fHeavy, fBold;
    private Runnable onCancel;
    private boolean done;

    Sheet(Activity a, int accent, String tag, String title, int video) {
        this.a = a;
        dp = a.getResources().getDisplayMetrics().density;
        fMono = Compat.font(a.getAssets(), "mono.ttf", "'wdth' 100, 'wght' 400", Typeface.MONOSPACE);
        fMonoBold = Compat.font(a.getAssets(), "mono.ttf", "'wdth' 100, 'wght' 700", Typeface.MONOSPACE);
        fHeavy = Compat.font(a.getAssets(), "display.ttf", "'opsz' 72, 'wght' 800", Typeface.DEFAULT_BOLD);
        fBold = Compat.font(a.getAssets(), "display.ttf", "'opsz' 36, 'wght' 700", Typeface.DEFAULT);
        boolean narrow = a.getResources().getConfiguration().screenWidthDp < 640;

        LinearLayout box = new LinearLayout(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setStroke(Math.round(dp), LINE);
        float r = Theme.corner(dp);
        bg.setCornerRadius(r);
        box.setBackground(bg);
        box.setPadding(Math.round(dp), Math.round(dp), Math.round(dp), Math.round(dp));
        View strip = new View(a);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(accent);
        float sr = Math.max(0, r - dp);
        sb.setCornerRadii(new float[]{sr, sr, 0, 0, 0, 0, sr, sr});
        strip.setBackground(sb);
        box.addView(strip, new LinearLayout.LayoutParams(px(4), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(32), px(28), px(32), px(24));
        box.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        content = new LinearLayout(a);
        content.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(tag, fMonoBold, 12, accent);
        t.setLetterSpacing(0.25f);
        body.addView(t);
        if (title != null) {
            TextView h = text(title, fHeavy, 30, TEXT);
            h.setLineSpacing(0, 1.05f);
            h.setPadding(0, px(10), 0, px(4));
            body.addView(h);
        }
        content.addView(body, narrow ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (video != HelpVideo.NONE) {
            LinearLayout q = HelpVideo.card(a, video);
            LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (narrow) { ql.topMargin = px(20); ql.gravity = Gravity.CENTER_HORIZONTAL; } else { ql.leftMargin = px(32); ql.gravity = Gravity.TOP; }
            content.addView(q, ql);
        }
        ScrollView scroll = new ScrollView(a); // layar pendek: isi bisa digulir, tombol tetap terlihat
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View rule = new View(a);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(dp)));
        rl.topMargin = px(22);
        rl.bottomMargin = px(18);
        col.addView(rule, rl);
        keys = new LinearLayout(a);
        keys.setGravity(Gravity.END);
        col.addView(keys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(box);
        d.setOnCancelListener(x -> finish(onCancel));
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0));
        int sw = a.getResources().getDisplayMetrics().widthPixels;
        w.setLayout(Math.min(sw - px(narrow ? 24 : 64), px(920)), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Paragraf; {@code color} 0 = warna teks biasa. */
    Sheet text(String s, int color) {
        TextView v = text(s, fBold, 18, color != 0 ? color : SOFT);
        v.setLineSpacing(0, 1.3f);
        v.setPadding(0, px(10), 0, 0);
        body.addView(v);
        return this;
    }

    /** Langkah bernomor: nomor mono beraksen, lalu teksnya. */
    Sheet step(int n, String s) {
        LinearLayout row = new LinearLayout(a);
        row.setPadding(0, px(12), 0, 0);
        TextView num = text(String.format(java.util.Locale.ROOT, "%02d", n), fMonoBold, 14, AMBER);
        num.setPadding(0, px(3), 0, 0);
        row.addView(num, new LinearLayout.LayoutParams(px(40), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = text(s, fBold, 18, TEXT);
        v.setLineSpacing(0, 1.25f);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(row);
        return this;
    }

    /** Tombol; {@code primary} menyala (aksen). Popup ditutup dulu, lalu {@code r} dijalankan. */
    Sheet key(String label, boolean primary, Runnable r) {
        Key k = new Key(a, fMonoBold, label, primary, () -> {
            d.dismiss();
            finish(r);
        });
        k.minWidth = px(120);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(52));
        kl.leftMargin = px(12);
        keys.addView(k, kl);
        return this;
    }

    /** Ditutup dengan tombol kembali (atau Batal yang memanggilnya). */
    Sheet onCancel(Runnable r) {
        onCancel = r;
        return this;
    }

    void show() {
        if (a.isFinishing() || a.isDestroyed()) return;
        d.show();
        Notice.track(d);
    }

    /** Hanya satu jalan keluar yang dijalankan (tombol atau batal). */
    private void finish(Runnable r) {
        if (done) return;
        done = true;
        if (r != null) r.run();
    }

    private TextView text(String s, Typeface tf, float size, int color) {
        TextView v = new TextView(a);
        v.setText(s);
        v.setTypeface(tf);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private int px(float v) { return Math.round(v * dp); }
}
