package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * "Notch" murattal di home: pil berwarna aksen tema (seperti tombol aktif) yang menggantung dari tepi atas, tampil selama
 * QuranService aktif. Isinya surah · ayat, jeda/putar, dan berikutnya; ketuk teksnya untuk membuka Al-Qur'an.
 * Statis (tanpa animasi), diperbarui lewat {@link #refresh} dari pendengar QuranService di home. Dijeda lebih dari
 * {@link #IDLE_HIDE_MS}: tersembunyi sampai murattal diputar lagi.
 */
final class QuranNotch extends LinearLayout {
    final Activity a;
    final TextView state, title, toggle, next;

    QuranNotch(Activity a, Typeface bold, Typeface mono) {
        super(a);
        this.a = a;
        float dp = a.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        float r = 18 * dp;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(HomeActivity.AMBER); // aksen + teks INK: kontras di semua tema, dan terlihat di atas latar home
        bg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        setBackground(bg);
        setPadding(Math.round(22 * dp), 0, Math.round(8 * dp), 0);

        LinearLayout info = new LinearLayout(a);
        info.setOrientation(HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        state = text("", mono, 10, HomeActivity.INK & 0xB3FFFFFF);
        state.setLetterSpacing(0.2f);
        info.addView(state);
        title = text("", bold, 15, HomeActivity.INK);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = Math.round(12 * dp);
        info.addView(title, tl);
        info.setOnClickListener(v -> a.startActivity(new Intent(a, QuranActivity.class)));
        addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        toggle = button("", bold, () -> QuranService.send(a, QuranService.ACTION_TOGGLE));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Math.round(48 * dp), ViewGroup.LayoutParams.MATCH_PARENT);
        bl.leftMargin = Math.round(14 * dp);
        addView(toggle, bl);
        next = button("⏭", bold, () -> QuranService.send(a, QuranService.ACTION_NEXT));
        addView(next, new LinearLayout.LayoutParams(Math.round(48 * dp), ViewGroup.LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
        // Di konstruktor, bukan di refresh: refresh bisa dipanggil saat QuranService sedang mengiterasi pendengarnya.
        if (!tracking) {
            QuranService.listeners.add(TRACK);
            tracking = true;
        }
    }

    /** Di FrameLayout akar home: tengah atas, setinggi 36dp (tidak menutupi nomor ubin baris pertama). */
    static FrameLayout.LayoutParams params(float dp) {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(36 * dp),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    }

    /** Dijeda selama ini: notch disembunyikan; tampil lagi begitu murattal diputar. */
    static final long IDLE_HIDE_MS = 2 * 60_000L;
    /** Sejak kapan dijeda (elapsedRealtime), 0 = sedang diputar. Statis: tetap berlaku bila home dibangun ulang. */
    private static long pausedSince;
    /** Mencatat waktu jeda walau home tidak tampil (pendengar home dilepas di onPause). Sekali per proses. */
    private static final Runnable TRACK = () -> {
        if (!QuranService.active || QuranService.playing) pausedSince = 0;
        else if (pausedSince == 0) pausedSince = android.os.SystemClock.elapsedRealtime();
    };
    private static boolean tracking;
    private final Runnable hideLater = this::refresh;

    void refresh() {
        removeCallbacks(hideLater);
        TRACK.run();
        if (!QuranService.active) {
            setVisibility(GONE);
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long idle = pausedSince == 0 ? 0 : now - pausedSince;
        if (idle >= IDLE_HIDE_MS) {
            setVisibility(GONE);
            return;
        }
        if (pausedSince != 0) postDelayed(hideLater, IDLE_HIDE_MS - idle);
        int s = QuranService.surah, ay = QuranService.ayah;
        String name = QuranData.loaded() ? QuranData.get(s).latin.toUpperCase(HomeActivity.ID)
                : String.format(Locale.US, "SURAH %d", s);
        state.setText(QuranService.playing ? "MURATTAL" : "JEDA");
        title.setText(QuranService.bismillah ? name + "  ·  BASMALAH"
                : String.format(Locale.US, "%s  ·  %d/%d", name, ay, QuranData.count(s)));
        toggle.setText(QuranService.playing ? "❚❚" : "▶");
        setVisibility(VISIBLE);
    }

    private TextView text(String s, Typeface tf, float size, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView button(String s, Typeface tf, Runnable click) {
        // Font sistem: font display KlikDash tidak punya ▶ dan ⏭, cadangannya tampil kecil dan tidak seragam.
        TextView t = text(s, Typeface.DEFAULT_BOLD, 16, HomeActivity.INK);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> click.run());
        return t;
    }
}
