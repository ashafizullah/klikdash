package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.Context;
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
 * "Notch" pemutar di home: pil berwarna aksen tema (seperti tombol aktif) yang menggantung dari tepi atas, tampil selama
 * ada yang diputar: murattal ({@link QuranService}), dzikir ({@link DzikirService}), atau musik aplikasi lain
 * ({@link MediaListener}). Isinya apa yang diputar, jeda/putar, dan berikutnya; ketuk teksnya untuk membuka
 * halaman/aplikasinya, tekan lama untuk menghentikannya.
 * Beberapa aktif sekaligus: yang sedang berbunyi; kalau semua dijeda, yang terakhir dijeda.
 * Statis (tanpa animasi), diperbarui lewat {@link #refresh} dari pendengar ketiga sumber di home. Dijeda lebih dari
 * {@link #IDLE_HIDE_MS}: tersembunyi sampai diputar lagi.
 */
final class MediaNotch extends LinearLayout {

    /** Satu sumber yang bisa ditampilkan notch. */
    interface Source {
        /** Ada yang bisa ditampilkan (layanan hidup / sesi media ada). */
        boolean active();
        boolean playing();
        /** Label kecil di kiri saat berbunyi (mis. MURATTAL, DZIKIR, SPOTIFY). */
        String state(Context c);
        String title(Context c);
        void toggle(Activity a);
        void next(Activity a);
        void stop(Activity a);
        void open(Activity a);
    }

    static final Source QURAN = new Source() {
        @Override public boolean active() { return QuranService.active; }
        @Override public boolean playing() { return QuranService.playing; }

        @Override public String state(Context c) {
            // Mode ulang/berhenti terlihat juga dari home, supaya "kok surah ini diulang terus" tidak terjadi diam-diam.
            boolean plain = QuranService.mode == QuranService.MODE_CONTINUE && QuranService.stopAfter == 0;
            return plain ? c.getString(R.string.home_notch_playing) : QuranService.modeLabel();
        }

        @Override public String title(Context c) {
            int s = QuranService.surah, ay = QuranService.ayah;
            String name = QuranData.loaded() ? QuranData.get(s).latin.toUpperCase(Lang.locale())
                    : c.getString(R.string.home_notch_surah, s);
            return QuranService.bismillah ? c.getString(R.string.home_notch_basmalah, name)
                    : String.format(Locale.US, "%s  ·  %d/%d", name, ay, QuranData.count(s));
        }

        @Override public void toggle(Activity a) { QuranService.send(a, QuranService.ACTION_TOGGLE); }
        @Override public void next(Activity a) { QuranService.send(a, QuranService.ACTION_NEXT); }
        @Override public void stop(Activity a) { QuranService.send(a, QuranService.ACTION_STOP); }
        @Override public void open(Activity a) { a.startActivity(new Intent(a, QuranActivity.class)); }
    };

    static final Source DZIKIR = new Source() {
        @Override public boolean active() { return DzikirService.active; }
        @Override public boolean playing() { return DzikirService.playing; }
        @Override public String state(Context c) { return c.getString(R.string.home_notch_dzikir); }

        @Override public String title(Context c) {
            String p = c.getString(DzikirActivity.PAGI.equals(DzikirService.period) ? R.string.dzikir_pagi : R.string.dzikir_petang);
            return String.format(Locale.US, "%s  ·  %02d/%02d  ·  %d/%d×", p, DzikirService.index + 1, DzikirService.total,
                    Math.min(DzikirService.rep + 1, DzikirService.reps), DzikirService.reps);
        }

        @Override public void toggle(Activity a) { DzikirService.send(a, DzikirService.ACTION_TOGGLE); }
        @Override public void next(Activity a) { DzikirService.send(a, DzikirService.ACTION_NEXT); }
        @Override public void stop(Activity a) { DzikirService.send(a, DzikirService.ACTION_STOP); }

        @Override public void open(Activity a) {
            a.startActivity(new Intent(a, DzikirActivity.class).putExtra("period", DzikirService.period));
        }
    };

    /** Urutan saat beberapa sama-sama berbunyi (jarang: audio focus biasanya menjeda yang lain). */
    static final Source[] SOURCES = {DZIKIR, QURAN, MediaListener.SOURCE};

    final Activity a;
    final TextView state, title, toggle, next;
    /** Sumber yang sedang ditampilkan; tombol-tombol notch mengikutinya. */
    Source shown;

    MediaNotch(Activity a, Typeface bold, Typeface mono) {
        super(a);
        this.a = a;
        float dp = a.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        float r = 18 * dp;
        GradientDrawable bg = new GradientDrawable();
        Theme.tint(bg, HomeActivity.AMBER); // aksen + teks INK: kontras di semua tema, dan terlihat di atas latar home
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
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setMaxWidth(Math.round(420 * dp)); // judul lagu bisa panjang
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = Math.round(12 * dp);
        info.addView(title, tl);
        info.setOnClickListener(v -> { if (shown != null) shown.open(a); });
        // Tekan lama di mana saja pada notch: hentikan yang diputar (notch ikut hilang).
        View.OnLongClickListener stop = v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (shown != null) shown.stop(a);
            return true;
        };
        info.setOnLongClickListener(stop);
        setOnLongClickListener(stop);
        addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        toggle = button("", bold, () -> { if (shown != null) shown.toggle(a); });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Math.round(48 * dp), ViewGroup.LayoutParams.MATCH_PARENT);
        bl.leftMargin = Math.round(14 * dp);
        addView(toggle, bl);
        next = button("⏭", bold, () -> { if (shown != null) shown.next(a); });
        addView(next, new LinearLayout.LayoutParams(Math.round(48 * dp), ViewGroup.LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
        // Di konstruktor, bukan di refresh: refresh bisa dipanggil saat layanan sedang mengiterasi pendengarnya.
        if (!tracking) {
            QuranService.listeners.add(TRACK);
            DzikirService.listeners.add(TRACK);
            MediaListener.listeners.add(TRACK);
            tracking = true;
        }
    }

    /** Di FrameLayout akar home: tengah atas, setinggi 36dp (tidak menutupi nomor ubin baris pertama). */
    static FrameLayout.LayoutParams params(float dp) {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(36 * dp),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    }

    /** Dijeda selama ini: notch disembunyikan; tampil lagi begitu diputar. */
    static final long IDLE_HIDE_MS = 2 * 60_000L;
    /**
     * Sejak kapan tiap sumber dijeda (elapsedRealtime, urutan {@link #SOURCES}), 0 = sedang diputar atau tidak aktif.
     * Statis: tetap berlaku bila home dibangun ulang.
     */
    private static final long[] pausedSince = new long[SOURCES.length];
    /** Mencatat waktu jeda walau home tidak tampil (pendengar home dilepas di onPause). Sekali per proses. */
    private static final Runnable TRACK = () -> {
        long now = android.os.SystemClock.elapsedRealtime();
        for (int i = 0; i < SOURCES.length; i++) {
            if (!SOURCES[i].active() || SOURCES[i].playing()) pausedSince[i] = 0;
            else if (pausedSince[i] == 0) pausedSince[i] = now;
        }
    };
    private static boolean tracking;
    private final Runnable hideLater = this::refresh;

    void refresh() {
        removeCallbacks(hideLater);
        TRACK.run();
        long now = android.os.SystemClock.elapsedRealtime();
        int pick = -1;
        for (int i = 0; i < SOURCES.length; i++) {
            Source s = SOURCES[i];
            if (!s.active() || (pausedSince[i] != 0 && now - pausedSince[i] >= IDLE_HIDE_MS)) continue;
            if (s.playing()) { pick = i; break; }
            if (pick < 0 || pausedSince[i] > pausedSince[pick]) pick = i; // yang terakhir dijeda
        }
        if (pick < 0) {
            shown = null;
            setVisibility(GONE);
            return;
        }
        shown = SOURCES[pick];
        if (pausedSince[pick] != 0) postDelayed(hideLater, IDLE_HIDE_MS - (now - pausedSince[pick]));
        Context c = getContext();
        set(state, shown.playing() ? shown.state(c) : c.getString(R.string.home_notch_paused));
        set(title, shown.title(c));
        set(toggle, shown.playing() ? "❚❚" : "▶");
        setVisibility(VISIBLE);
    }

    /**
     * Ganti teks hanya bila berubah. Sumber memberi kabar tiap potongan audio (dzikir: tiap beberapa detik) dan
     * setText pada notch selebar isinya selalu meminta tata letak ulang seluruh home, walau teksnya sama.
     */
    private static void set(TextView t, String s) {
        if (!s.contentEquals(t.getText())) t.setText(s);
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
