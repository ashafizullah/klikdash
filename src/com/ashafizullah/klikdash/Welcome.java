package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.BG;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.SOFT;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Sambutan saat headunit menyala atau bangun (ACC on): kartu di tengah home berisi doa keluar rumah (bawaan) atau
 * teks pilihan pengguna, hilang saat diketuk atau sendiri setelah 30 detik (dengan hitung mundur). Diatur di Pengaturan › Launcher; disimpan
 * di prefs DE "klikdash" (welcome = MODE_*, wTitle/wArab/wLatin/wText untuk teks sendiri).
 */
final class Welcome {
    static final int MODE_DEFAULT = 0, MODE_CUSTOM = 1, MODE_OFF = 2;

    static final String DEF_ARAB = "بِسْمِ اللَّهِ تَوَكَّلْتُ عَلَى اللَّهِ لاَ حَوْلَ وَلاَ قُوَّةَ إِلاَّ بِاللَّهِ";
    static final String DEF_LATIN = "Bismillaahi tawakkaltu ‘alalloh, laa hawla wa laa quwwata illaa billaah.";
    // Judul dan arti doa bawaan ada di resource (welcome_default_title/text), diisi saat ditampilkan (lihat show).

    /** Jeda minimum antar-sambutan, supaya layar yang dimatikan-nyalakan sebentar tidak memunculkannya lagi. */
    static final long GAP_MS = 10 * 60_000L;
    /** Lama tampil sebelum tertutup sendiri. */
    static final long SHOW_MS = 30_000L;
    private static long lastShown;

    private Welcome() { }

    static final class Content {
        String title, arab, latin, text;
        /** Doa bawaan: judul dan arti diambil dari resource sesuai bahasa saat ditampilkan. */
        boolean def;
    }

    static int mode(SharedPreferences p) { return p.getInt("welcome", MODE_DEFAULT); }

    /** Isi sambutan sesuai pengaturan, atau null bila dimatikan (atau teks sendiri masih kosong). */
    static Content content(SharedPreferences p) {
        int m = mode(p);
        if (m == MODE_OFF) return null;
        Content c = new Content();
        if (m == MODE_CUSTOM) {
            c.title = p.getString("wTitle", "");
            c.arab = p.getString("wArab", "");
            c.latin = p.getString("wLatin", "");
            c.text = p.getString("wText", "");
            if (c.arab.isEmpty() && c.text.isEmpty()) return null;
        } else {
            c.def = true;
            c.arab = DEF_ARAB;
            c.latin = DEF_LATIN;
        }
        return c;
    }

    /** Home: tampilkan bila diaktifkan dan belum tampil dalam {@link #GAP_MS} terakhir. */
    static void maybeShow(Activity a, SharedPreferences p) {
        long now = SystemClock.elapsedRealtime();
        if (lastShown != 0 && now - lastShown < GAP_MS) return;
        Content c = content(p);
        if (c == null) return;
        lastShown = now;
        show(a, c);
    }

    static void show(Activity a, Content c) {
        ViewGroup root = a.findViewById(android.R.id.content);
        View old = root.findViewWithTag(Welcome.class);
        if (c.def) {
            c.title = a.getString(R.string.welcome_default_title);
            c.text = a.getString(R.string.welcome_default_text);
        }
        if (old != null) root.removeView(old);
        float dp = a.getResources().getDisplayMetrics().density;
        Typeface mono = Compat.font(a.getAssets(), "mono.ttf", "'wdth' 100, 'wght' 700", Typeface.MONOSPACE);
        Typeface arabic = Compat.font(a.getAssets(), "arabic.ttf", null, Typeface.SERIF);
        Typeface serif = Compat.font(a.getAssets(), "serif.ttf", null, Typeface.SERIF);

        FrameLayout scrim = new FrameLayout(a);
        scrim.setTag(Welcome.class);
        scrim.setBackgroundColor(0xE6000000 | BG & 0xFFFFFF);
        scrim.setClickable(true); // ketukan tidak tembus ke ubin di bawahnya

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setStroke(Math.round(dp), LINE);
        card.setBackground(bg);
        int pad = Math.round(32 * dp);
        card.setPadding(pad, Math.round(28 * dp), pad, Math.round(24 * dp));

        View bar = new View(a);
        bar.setBackgroundColor(AMBER);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Math.round(56 * dp), Math.round(4 * dp));
        bl.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(bar, bl);
        String head = a.getString(R.string.welcome_head);
        TextView tag = text(a, c.title.isEmpty() ? head : a.getString(R.string.welcome_head_title, head,
                        c.title.toUpperCase(Lang.locale())),
                mono, 12, AMBER);
        tag.setLetterSpacing(0.2f);
        tag.setGravity(Gravity.CENTER);
        card.addView(tag, margins(dp, 14));
        if (!c.arab.isEmpty()) {
            TextView arab = text(a, c.arab, arabic, 34, TEXT);
            arab.setGravity(Gravity.CENTER);
            arab.setTextDirection(View.TEXT_DIRECTION_RTL);
            arab.setLineSpacing(0, 1.3f);
            card.addView(arab, margins(dp, 18));
        }
        if (!c.latin.isEmpty()) {
            TextView latin = text(a, c.latin, serif, 18, AMBER);
            latin.setGravity(Gravity.CENTER);
            card.addView(latin, margins(dp, 16));
        }
        if (!c.text.isEmpty()) {
            TextView arti = text(a, c.text, serif, 20, SOFT);
            arti.setGravity(Gravity.CENTER);
            arti.setLineSpacing(0, 1.2f);
            card.addView(arti, margins(dp, 12));
        }
        // Hitung mundur sampai tertutup sendiri: pita amber yang menyusut dan sisa detiknya.
        FrameLayout track = new FrameLayout(a);
        track.setBackgroundColor(LINE);
        View fill = new View(a);
        fill.setBackgroundColor(AMBER);
        fill.setPivotX(0);
        track.addView(fill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tl = margins(dp, 24);
        tl.height = Math.round(3 * dp);
        card.addView(track, tl);
        TextView hint = text(a, "", mono, 11, DIM);
        hint.setLetterSpacing(0.2f);
        hint.setGravity(Gravity.CENTER);
        card.addView(hint, margins(dp, 12));

        int maxW = Math.round(Math.min(760 * dp, a.getResources().getDisplayMetrics().widthPixels - 48 * dp));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(card, cl);
        long until = SystemClock.uptimeMillis() + SHOW_MS;
        Runnable tick = new Runnable() {
            @Override public void run() {
                long left = Math.max(0, until - SystemClock.uptimeMillis());
                int sec = (int) Math.max(1, (left + 999) / 1000);
                hint.setText(a.getResources().getQuantityString(R.plurals.welcome_closing_in, sec, sec));
                // Digerakkan sendiri, bukan animate(): headunit sering mematikan skala animasi sistem (0×).
                fill.setScaleX(left / (float) SHOW_MS);
                if (left > 0) scrim.postDelayed(this, 100);
            }
        };
        Runnable close = () -> {
            scrim.removeCallbacks(tick);
            scrim.animate().alpha(0f).setDuration(200).withEndAction(() -> root.removeView(scrim)).start();
        };
        scrim.setOnClickListener(v -> close.run());
        card.setOnClickListener(v -> close.run());
        scrim.setAlpha(0f);
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.animate().alpha(1f).setDuration(250).start();
        tick.run();
        scrim.postDelayed(close, SHOW_MS);
    }

    private static TextView text(Context c, String s, Typeface tf, float size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        return t;
    }

    private static LinearLayout.LayoutParams margins(float dp, int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = Math.round(top * dp);
        return l;
    }
}
