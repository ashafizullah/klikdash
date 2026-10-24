package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Locale;

import static com.ashafizullah.klikdash.HomeActivity.ACTIVE;
import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.BG;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.FAINT;
import static com.ashafizullah.klikdash.HomeActivity.INK;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.SOFT;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/** Al-Qur'an offline: daftar surah di kiri, mushaf + terjemahan di kanan, pemutar di bawah. */
public class QuranActivity extends Activity {

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final Locale ID = new Locale("id", "ID");

    float dp, sp;
    Typeface fHeavy, fBold, fMono, fMonoBold, fArab, fSerif;
    SharedPreferences prefs;

    int surah = 1;            // surah yang sedang ditampilkan
    int followed = -1;        // indeks global ayat terakhir yang diikuti scroll
    int followedSurah = -1;   // surah pemutar yang terakhir diikuti tampilan
    boolean showLatin, showIndo;

    ListView surahList, ayahList;
    SurahAdapter surahAdapter;
    AyahAdapter ayahAdapter;
    TextView hNo, hTitle, hMeta, hArab, npTitle, npSub, loading;
    Chip cLatin, cIndo, cMode, cDl;
    /** Jumlah file audio tersimpan per surah (indeks 1..114), diperbarui tiap status pemutar berubah. */
    int[] have = new int[QuranData.SURAH_COUNT + 1];
    Key kPrev, kPlay, kNext;

    final Runnable onService = this::refreshPlayer;

    int themeBuilt;

    @Override protected void onCreate(Bundle b) {
        themeBuilt = Theme.apply(this);
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        sp = getResources().getDisplayMetrics().scaledDensity;
        prefs = getSharedPreferences("quran", MODE_PRIVATE);
        showLatin = prefs.getBoolean("latin", false);
        showIndo = prefs.getBoolean("indo", true);
        QuranService.loadState(this);

        fHeavy = font("display.ttf", "'opsz' 72, 'wght' 800");
        fBold = font("display.ttf", "'opsz' 36, 'wght' 700");
        fMono = font("mono.ttf", "'wdth' 100, 'wght' 400");
        fMonoBold = font("mono.ttf", "'wdth' 100, 'wght' 700");
        fArab = font("arabic.ttf", null);
        fSerif = font("serif.ttf", null);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        root.addView(buildUi(), MATCH, MATCH);
        root.addView(buildOverlay(), MATCH, MATCH);
        setContentView(root);

        if (QuranData.available(this)) loadText(); else askDownload();
    }

    // Layar pembuka: memuat teks, atau menawarkan unduh sekali bila APK tidak membawa teks.
    LinearLayout overlay;
    TextView loadSub;
    Chip loadBtn;

    View buildOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(BG);
        overlay.setClickable(true);
        loading = text("", fMonoBold, 14, AMBER);
        loading.setLetterSpacing(0.3f);
        overlay.addView(loading, new LinearLayout.LayoutParams(WRAP, WRAP));
        loadSub = text("", fSerif, 17, SOFT);
        loadSub.setSingleLine(false);
        loadSub.setGravity(Gravity.CENTER);
        loadSub.setMaxWidth(px(640));
        loadSub.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(WRAP, WRAP);
        sl.topMargin = px(16);
        overlay.addView(loadSub, sl);
        loadBtn = new Chip("UNDUH TEKS", this::downloadText);
        loadBtn.setOn(true);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(px(220), px(56));
        bl.topMargin = px(28);
        overlay.addView(loadBtn, bl);
        return overlay;
    }

    void loadText() {
        overlay.setVisibility(View.VISIBLE);
        loading.setText("MEMUAT MUSHAF…");
        loadSub.setVisibility(View.GONE);
        loadBtn.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                QuranData.load(this);
                runOnUiThread(this::onLoaded);
            } catch (Exception e) {
                runOnUiThread(() -> loading.setText("GAGAL MEMUAT MUSHAF"));
            }
        }, "quran-load").start();
    }

    void askDownload() {
        loading.setText("TEKS AL-QUR'AN BELUM ADA");
        loadSub.setText("Unduh sekali (±6 MB) dari equran.id: mushaf standar, Latin, dan terjemahan Kemenag. "
                + "Setelah itu bisa dibaca offline. Audio murattal diunduh per surah saat diputar.");
        loadSub.setVisibility(View.VISIBLE);
        loadBtn.setLabel("UNDUH TEKS");
        loadBtn.setVisibility(View.VISIBLE);
    }

    void downloadText() {
        loadBtn.setVisibility(View.GONE);
        loadSub.setVisibility(View.GONE);
        loading.setText("MENGUNDUH MUSHAF  0 / 114");
        new Thread(() -> {
            try {
                QuranData.download(this, (d, t) -> runOnUiThread(() ->
                        loading.setText(String.format(Locale.US, "MENGUNDUH MUSHAF  %d / %d", d, t))));
                runOnUiThread(this::loadText);
            } catch (Exception e) {
                android.util.Log.w("KlikDash", "Unduh teks Al-Qur'an gagal", e);
                runOnUiThread(() -> {
                    loading.setText("GAGAL MENGUNDUH");
                    loadSub.setText("Periksa koneksi internet headunit, lalu coba lagi.");
                    loadSub.setVisibility(View.VISIBLE);
                    loadBtn.setLabel("COBA LAGI");
                    loadBtn.setVisibility(View.VISIBLE);
                });
            }
        }, "quran-download").start();
    }

    void onLoaded() {
        overlay.setVisibility(View.GONE);
        surahAdapter.notifyDataSetChanged();
        open(QuranService.surah, QuranService.ayah);
        surahList.setSelectionFromTop(Math.max(0, surah - 3), 0);
        refreshPlayer();
    }

    @Override protected void onResume() {
        super.onResume();
        if (Theme.stale(this, themeBuilt)) return;
        QuranService.listeners.add(onService);
        followed = -1;
        followedSurah = -1;
        if (QuranData.loaded()) refreshPlayer();
    }

    @Override protected void onPause() {
        QuranService.listeners.remove(onService);
        super.onPause();
    }

    // ---------------------------------------------------------------- layout


    // Ukuran layar dalam dp: daftar di kiri (landscape) atau di atas (portrait); lebar dihitung, bukan ditulis mati.
    float wDp, hDp;
    boolean portrait, narrow;
    int screenW, screenH;

    void measureScreen() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        wDp = dm.widthPixels / dm.density;
        hDp = dm.heightPixels / dm.density;
        portrait = hDp > wDp;
        float listDp = portrait ? 0 : Math.max(220, Math.min(380, wDp * 0.27f));
        narrow = (wDp - listDp) < 720;
        screenW = getResources().getConfiguration().screenWidthDp;
        screenH = getResources().getConfiguration().screenHeightDp;
    }

    float listDp() { return Math.max(220, Math.min(380, wDp * 0.27f)); }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (c.screenWidthDp != screenW || c.screenHeightDp != screenH) recreate();
    }

    View buildUi() {
        measureScreen();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        // Kiri: daftar surah
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(px(24), px(20), px(24), px(16));
        head.addView(text("AL-QUR'AN", fHeavy, 34, TEXT));
        TextView sub = text("114 SURAH  ·  OFFLINE", fMono, 10, AMBER);
        sub.setLetterSpacing(0.25f);
        sub.setPadding(0, px(6), 0, 0);
        head.addView(sub);
        left.addView(head);
        left.addView(rule(), new LinearLayout.LayoutParams(MATCH, px(1)));

        surahList = list();
        surahAdapter = new SurahAdapter();
        surahList.setAdapter(surahAdapter);
        surahList.setOnItemClickListener((p, v, pos, id) -> pickSurah(pos + 1));
        left.addView(surahList, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        row.addView(left, portrait ? new LinearLayout.LayoutParams(MATCH, px(hDp * 0.34f))
                : new LinearLayout.LayoutParams(px(listDp()), MATCH));
        row.addView(rule(), portrait ? new LinearLayout.LayoutParams(MATCH, px(1)) : new LinearLayout.LayoutParams(px(1), MATCH));

        // Kanan: kepala surah, ayat, pemutar
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(px(28), px(14), px(24), px(14));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        hNo = text("", fMono, 10, AMBER);
        hNo.setLetterSpacing(0.25f);
        titles.addView(hNo);
        hTitle = text("", fHeavy, 34, TEXT);
        hTitle.setPadding(0, px(4), 0, px(4));
        titles.addView(hTitle);
        hMeta = text("", fMono, 10, DIM);
        hMeta.setLetterSpacing(0.15f);
        titles.addView(hMeta);
        hdr.addView(titles, new LinearLayout.LayoutParams(0, WRAP, 1f));
        hArab = text("", fArab, 34, TEXT);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(WRAP, WRAP);
        al.rightMargin = px(20);
        hdr.addView(hArab, al);
        if (narrow) hArab.setVisibility(View.GONE); // nama Arab tetap ada di daftar surah
        cDl = new Chip("UNDUH", () -> {
            if (have[surah] < QuranData.count(surah)) QuranService.download(this, surah);
        });
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(WRAP, px(48));
        dl.rightMargin = px(10);
        hdr.addView(cDl, dl);
        cLatin = new Chip("LATIN", () -> {
            showLatin = !showLatin;
            prefs.edit().putBoolean("latin", showLatin).apply();
            cLatin.setOn(showLatin);
            ayahAdapter.notifyDataSetChanged();
        });
        cLatin.setOn(showLatin);
        hdr.addView(cLatin, new LinearLayout.LayoutParams(WRAP, px(48)));
        cIndo = new Chip("TERJEMAH", () -> {
            showIndo = !showIndo;
            prefs.edit().putBoolean("indo", showIndo).apply();
            cIndo.setOn(showIndo);
            ayahAdapter.notifyDataSetChanged();
        });
        cIndo.setOn(showIndo);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(WRAP, px(48));
        il.leftMargin = px(10);
        hdr.addView(cIndo, il);
        right.addView(hdr);
        right.addView(rule(), new LinearLayout.LayoutParams(MATCH, px(1)));

        ayahList = list();
        ayahAdapter = new AyahAdapter();
        ayahList.setAdapter(ayahAdapter);
        ayahList.setOnItemClickListener((p, v, pos, id) -> QuranService.play(this, surah, ayahAt(pos)));
        right.addView(ayahList, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        right.addView(buildPlayer(), new LinearLayout.LayoutParams(MATCH, px(92)));
        row.addView(right, portrait ? new LinearLayout.LayoutParams(MATCH, 0, 1f) : new LinearLayout.LayoutParams(0, MATCH, 1f));
        return row;
    }

    View buildPlayer() {
        LinearLayout bar = new LinearLayout(this) {
            final Paint p = new Paint();
            @Override protected void dispatchDraw(Canvas c) {
                p.setColor(PANEL);
                c.drawRect(0, 0, getWidth(), getHeight(), p);
                p.setColor(LINE);
                c.drawRect(0, 0, getWidth(), px(1), p);
                super.dispatchDraw(c);
            }
        };
        bar.setWillNotDraw(false);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(28), px(12), px(16), px(12));

        // Mode di kiri, jauh dari ⏭: sambil menyetir, ketukan ⏭ dulu sering meleset ke sini dan mengubah mode.
        cMode = new Chip("", this::showModeDialog);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(WRAP, px(64));
        ml.rightMargin = px(24);
        bar.addView(cMode, ml);

        LinearLayout np = new LinearLayout(this);
        np.setOrientation(LinearLayout.VERTICAL);
        npTitle = text("", fBold, 24, TEXT);
        np.addView(npTitle);
        npSub = text("", fMono, 10, DIM);
        npSub.setLetterSpacing(0.15f);
        npSub.setPadding(0, px(6), 0, 0);
        np.addView(npSub);
        bar.addView(np, new LinearLayout.LayoutParams(0, WRAP, 1f));

        kPrev = new Key(Key.PREV, () -> QuranService.send(this, QuranService.ACTION_PREV));
        kPlay = new Key(Key.PLAY, this::togglePlay);
        kNext = new Key(Key.NEXT, () -> QuranService.send(this, QuranService.ACTION_NEXT));
        int kw = narrow ? 60 : 76;
        bar.addView(kPrev, new LinearLayout.LayoutParams(px(kw), px(64)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(px(narrow ? 84 : 104), px(64));
        pl.leftMargin = px(8);
        pl.rightMargin = px(8);
        bar.addView(kPlay, pl);
        bar.addView(kNext, new LinearLayout.LayoutParams(px(kw), px(64)));
        return bar;
    }

    // ---------------------------------------------------------------- perilaku

    void open(int s, int a) {
        surah = s;
        QuranData.Surah q = QuranData.get(s);
        hNo.setText(String.format(Locale.US, "SURAH %03d", s));
        hTitle.setText(q.latin.toUpperCase(ID));
        hMeta.setText((q.arti + "  ·  " + q.count + " AYAT  ·  " + q.tempat).toUpperCase(ID));
        hArab.setText(q.arab);
        ayahAdapter.notifyDataSetChanged();
        surahAdapter.notifyDataSetChanged();
        followed = -1;
        refreshPlayer();
        // Sesudah refreshPlayer: invalidateViews() di sana membatalkan lompatan scroll. Bila refreshPlayer sudah
        // pindah ke surah yang sedang diputar, biarkan posisinya.
        if (surah == s) ayahList.setSelectionFromTop(a <= 1 ? 0 : a - 1 + offset(), 0);
    }

    /** Saat murattal diputar, tap surah langsung memutarnya (satu ketukan sambil menyetir); selain itu hanya membuka. */
    void pickSurah(int s) {
        if (QuranService.playing && s != QuranService.surah) QuranService.play(this, s, 1);
        open(s, 1);
    }

    /** Surah yang tampil = surah pemutar: jeda/lanjut. Surah lain: putar dari awal. */
    void togglePlay() {
        if (QuranService.surah == surah) {
            QuranService.send(this, QuranService.ACTION_TOGGLE);
        } else {
            QuranService.play(this, surah, 1);
        }
    }

    /** Tombol UNDUH untuk surah yang tampil: UNDUH → persen saat berjalan → OFFLINE bila lengkap. */
    void refreshDownload() {
        have = QuranService.downloaded(this);
        int need = QuranData.count(surah);
        if (have[surah] >= need) {
            cDl.setLabel("OFFLINE");
            cDl.setOn(true);
        } else {
            cDl.setLabel(QuranService.dlSurah == surah
                    ? (QuranService.dlDone * 100 / Math.max(1, QuranService.dlTotal)) + "%"
                    : "UNDUH");
            cDl.setOn(false);
        }
    }

    /** Dipanggil saat status pemutar berubah: perbarui bar, sorot ayat, ikuti bacaan. */
    void refreshPlayer() {
        if (!QuranData.loaded()) return;
        int s = QuranService.surah, a = QuranService.ayah;
        QuranData.Surah q = QuranData.get(s);
        boolean audible = QuranService.active;
        npTitle.setText(audible
                ? q.latin.toUpperCase(ID) + (QuranService.bismillah ? "  ·  BASMALAH" : "  ·  " + a)
                : "TERAKHIR: " + q.latin.toUpperCase(ID) + "  ·  " + a);
        if (QuranService.missing) {
            npSub.setText("AUDIO BELUM ADA  ·  SAMBUNGKAN INTERNET UNTUK MENGUNDUH");
            npSub.setTextColor(AMBER);
        } else if (QuranService.buffering) {
            npSub.setText("MENGUNDUH AUDIO…");
            npSub.setTextColor(AMBER);
        } else {
            npSub.setText(String.format(Locale.US, "%s  ·  AYAT %d / %d",
                    QuranService.RECITER.toUpperCase(ID), a, q.count));
            npSub.setTextColor(DIM);
        }
        kPlay.setPlaying(QuranService.playing);
        cMode.setLabel(QuranService.modeLabel());
        cMode.setOn(QuranService.mode != QuranService.MODE_CONTINUE || QuranService.stopAfter > 0);
        refreshDownload();

        // Harus SEBELUM setSelectionFromTop: invalidateViews() menyimpan posisi scroll saat ini
        // (rememberSyncState) dan akan membatalkan lompatan ke ayat yang sedang dibaca.
        ayahList.invalidateViews();
        surahList.invalidateViews();

        if (QuranService.playing) {
            // Ikuti pemutar hanya saat ia pindah surah (mode LANJUT, ⏮/⏭), supaya surah yang baru dibuka tidak
            // langsung ditimpa surah yang masih diputar.
            if (s != followedSurah) {
                followedSurah = s;
                if (s != surah) open(s, a);
            }
            if (s != surah) return;
            int g = QuranData.index(s, a);
            if (g != followed) {
                followed = g;
                int pos = QuranService.bismillah ? 0 : a - 1 + offset();
                int first = ayahList.getFirstVisiblePosition(), last = ayahList.getLastVisiblePosition();
                // Lompat hanya jika ayat yang dibaca tidak lagi terlihat utuh (tanpa animasi scroll).
                View v = ayahList.getChildAt(pos - first);
                boolean fully = v != null && v.getTop() >= 0 && v.getBottom() <= ayahList.getHeight();
                if (pos < first || pos > last || !fully) ayahList.setSelectionFromTop(pos, px(8));
            }
        }
    }

    int offset() { return QuranData.hasBismillah(surah) ? 1 : 0; }

    /** Nomor ayat untuk posisi daftar (baris basmalah dihitung sebagai ayat 1). */
    int ayahAt(int pos) { return Math.max(1, pos + 1 - offset()); }

    // ---------------------------------------------------------------- mode pemutaran

    static final int[] TIMES = {2, 3, 5, 7, 0}; // 0 = terus

    /** Pilihan pemutaran: mode, berapa kali diulang, dan surah terakhir. Berlaku langsung, juga di tengah bacaan. */
    void showModeDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(24), px(4), px(24), px(8));
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            box.removeAllViews();
            int m = QuranService.mode, r = QuranService.repeat, st = QuranService.stopAfter;
            box.addView(caption("SETELAH SELESAI"));
            String[] names = {"LANJUT", "ULANG AYAT", "ULANG SURAH"};
            int[] modes = {QuranService.MODE_CONTINUE, QuranService.MODE_REPEAT_AYAH, QuranService.MODE_REPEAT_SURAH};
            Chip[] mc = new Chip[3];
            for (int i = 0; i < 3; i++) {
                final int v = modes[i];
                mc[i] = new Chip(names[i], () -> { QuranService.setMode(this, v, r, st); fill[0].run(); });
                mc[i].setOn(m == v);
            }
            box.addView(chipRow(mc));
            if (m != QuranService.MODE_CONTINUE) {
                box.addView(caption("BERAPA KALI"));
                Chip[] tc = new Chip[TIMES.length];
                for (int i = 0; i < TIMES.length; i++) {
                    final int v = TIMES[i];
                    tc[i] = new Chip(v == 0 ? "TERUS" : v + "×", () -> { QuranService.setMode(this, m, v, st); fill[0].run(); });
                    tc[i].setOn(r == v);
                }
                box.addView(chipRow(tc));
            }
            box.addView(caption("BERHENTI SETELAH"));
            int here = QuranService.active ? QuranService.surah : surah;
            Chip none = new Chip("TIDAK", () -> { QuranService.setMode(this, m, r, 0); fill[0].run(); });
            none.setOn(st == 0);
            Chip thisOne = new Chip("SURAH INI", () -> { QuranService.setMode(this, m, r, here); fill[0].run(); });
            thisOne.setOn(st != 0 && st == here);
            Chip pick = new Chip(st != 0 && st != here ? "SAMPAI " + QuranService.surahName(st).toUpperCase(ID)
                    : "PILIH SURAH…", () -> pickStopSurah(fill[0]));
            pick.setOn(st != 0 && st != here);
            box.addView(chipRow(new Chip[]{none, thisOne, pick}));
            box.addView(hint(modeHint()));
        };
        fill[0].run();
        SettingsActivity.showDark(new android.app.AlertDialog.Builder(this)
                .setTitle("Pemutaran murattal")
                .setView(box)
                .setPositiveButton("Selesai", null));
    }

    /** Penjelasan pilihan saat ini, dalam satu kalimat. */
    String modeHint() {
        int m = QuranService.mode, r = QuranService.repeat, st = QuranService.stopAfter;
        String end = st > 0 ? "berhenti setelah " + QuranService.surahName(st) + "." : "terus sampai An-Nas.";
        if (m == QuranService.MODE_REPEAT_AYAH) {
            return r == 0 ? "Ayat yang sama diulang terus, sampai Anda mengubahnya."
                    : "Tiap ayat diputar " + r + " kali, lalu lanjut ke ayat berikutnya; " + end;
        }
        if (m == QuranService.MODE_REPEAT_SURAH) {
            return r == 0 ? "Surah yang sama diulang terus, sampai Anda mengubahnya."
                    : "Tiap surah diputar " + r + " kali, lalu lanjut ke surah berikutnya; " + end;
        }
        return "Otomatis lanjut ke ayat dan surah berikutnya, " + end;
    }

    void pickStopSurah(Runnable then) {
        String[] names = new String[QuranData.SURAH_COUNT];
        for (int i = 0; i < names.length; i++) {
            names[i] = String.format(Locale.US, "%03d  %s", i + 1, QuranService.surahName(i + 1));
        }
        SettingsActivity.showDark(new android.app.AlertDialog.Builder(this)
                .setTitle("Berhenti setelah surah")
                .setItems(names, (d, w) -> {
                    QuranService.setMode(this, QuranService.mode, QuranService.repeat, w + 1);
                    then.run();
                })
                .setNegativeButton("Batal", null));
    }

    TextView caption(String s) {
        TextView t = text(s, fMonoBold, 11, DIM);
        t.setLetterSpacing(0.2f);
        t.setPadding(0, px(20), 0, px(10));
        return t;
    }

    TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(fSerif);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(SOFT);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, px(18), 0, 0);
        return t;
    }

    LinearLayout chipRow(Chip[] chips) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < chips.length; i++) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(WRAP, px(48));
            if (i > 0) l.leftMargin = px(8);
            row.addView(chips[i], l);
        }
        return row;
    }

    // ---------------------------------------------------------------- helpers

    Typeface font(String file, String axes) { return Compat.font(getAssets(), file, axes, Typeface.DEFAULT); }

    TextView text(String s, Typeface tf, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    View rule() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        return v;
    }

    ListView list() {
        ListView l = new ListView(this);
        l.setDivider(new ColorDrawable(LINE));
        l.setDividerHeight(px(1));
        l.setSelector(new ColorDrawable(0));
        l.setOverScrollMode(View.OVER_SCROLL_NEVER);
        l.setVerticalScrollBarEnabled(false);
        return l;
    }

    int px(float v) { return Math.round(v * dp); }

    float sp(float v) { return v * sp; }

    // ---------------------------------------------------------------- views

    /** Baris surah, digambar sendiri. */
    class SurahRow extends View {
        int no;
        final TextPaint num = new TextPaint(Paint.ANTI_ALIAS_FLAG), name = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                meta = new TextPaint(Paint.ANTI_ALIAS_FLAG), arab = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint();
        boolean down;

        SurahRow() {
            super(QuranActivity.this);
            num.setTypeface(fMono);
            num.setTextSize(sp(11));
            num.setLetterSpacing(0.1f);
            name.setTypeface(fBold);
            name.setTextSize(sp(22));
            name.setLetterSpacing(0.03f);
            meta.setTypeface(fMono);
            meta.setTextSize(sp(9));
            meta.setLetterSpacing(0.12f);
            arab.setTypeface(fArab);
            arab.setTextSize(sp(22));
            arab.setTextAlign(Paint.Align.RIGHT);
            setLayoutParams(new AbsListView.LayoutParams(MATCH, px(72)));
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (isPressed() != down) { down = isPressed(); invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            if (!QuranData.loaded()) return;
            QuranData.Surah q = QuranData.get(no);
            int w = getWidth(), h = getHeight();
            boolean sel = no == surah, live = QuranService.active && QuranService.surah == no;
            if (down) {
                fill.setColor(AMBER);
                c.drawRect(0, 0, w, h, fill);
            } else if (sel) {
                fill.setColor(PANEL);
                c.drawRect(0, 0, w, h, fill);
                fill.setColor(AMBER);
                c.drawRect(0, 0, px(4), h, fill);
            }
            int ink = down ? INK : TEXT;
            num.setColor(down ? INK : sel ? AMBER : DIM);
            c.drawText(String.format(Locale.US, "%03d", no), px(24), px(42), num);
            float x = px(70);
            // Baris sempit: nama Arab disembunyikan supaya nama Latin tidak terpotong (tetap ada di kepala surah).
            float aw = 0;
            if (w >= px(280)) {
                arab.setColor(down ? INK : sel ? TEXT : DIM);
                aw = arab.measureText(q.arab);
                c.drawText(q.arab, w - px(20), px(44), arab);
            }
            name.setColor(ink);
            float avail = w - x - aw - px(36);
            CharSequence n = TextUtils.ellipsize(q.latin.toUpperCase(ID), name, avail, TextUtils.TruncateAt.END);
            c.drawText(n, 0, n.length(), x, px(36), name);
            meta.setColor(down ? INK : live ? AMBER : DIM);
            String m = (live ? "▶ " : "") + (q.arti + " · " + q.count + " AYAT").toUpperCase(ID)
                    + (have[no] >= q.count ? " · OFFLINE" : "");
            CharSequence mm = TextUtils.ellipsize(m, meta, avail, TextUtils.TruncateAt.END);
            c.drawText(mm, 0, mm.length(), x, px(56), meta);
        }
    }

    class SurahAdapter extends BaseAdapter {
        @Override public int getCount() { return QuranData.loaded() ? QuranData.SURAH_COUNT : 0; }
        @Override public Object getItem(int i) { return i + 1; }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            SurahRow r = v instanceof SurahRow ? (SurahRow) v : new SurahRow();
            r.no = i + 1;
            r.invalidate();
            return r;
        }
    }

    /** Satu ayat: nomor, teks Arab (kanan), Latin & terjemahan. Ayat yang dibaca diberi garis amber. */
    class AyahRow extends LinearLayout {
        final TextView num, live, arab, latin, indo;
        final Paint bar = new Paint();
        boolean active;

        AyahRow() {
            super(QuranActivity.this);
            setOrientation(VERTICAL);
            setPadding(px(28), px(18), px(28), px(22));
            setWillNotDraw(false);
            LinearLayout top = new LinearLayout(QuranActivity.this);
            top.setOrientation(HORIZONTAL);
            num = text("", fMonoBold, 11, AMBER);
            num.setLetterSpacing(0.15f);
            top.addView(num, new LayoutParams(0, WRAP, 1f));
            live = text("▶ DIBACA", fMonoBold, 10, AMBER);
            live.setLetterSpacing(0.2f);
            top.addView(live);
            addView(top);

            arab = new TextView(QuranActivity.this);
            arab.setTypeface(fArab);
            arab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            arab.setTextColor(TEXT);
            // RIGHT, bukan END: dengan arah teks RTL, END justru berarti kiri.
            arab.setGravity(Gravity.RIGHT);
            arab.setTextDirection(TEXT_DIRECTION_RTL);
            arab.setLineSpacing(0, 1.25f);
            LayoutParams lp = new LayoutParams(MATCH, WRAP);
            lp.topMargin = px(6);
            addView(arab, lp);

            latin = new TextView(QuranActivity.this);
            latin.setTypeface(fSerif);
            latin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            latin.setTextColor(AMBER & 0xCCFFFFFF);
            latin.setLineSpacing(0, 1.2f);
            LayoutParams ll = new LayoutParams(MATCH, WRAP);
            ll.topMargin = px(12);
            addView(latin, ll);

            indo = new TextView(QuranActivity.this);
            indo.setTypeface(fSerif);
            indo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            indo.setTextColor(SOFT);
            indo.setLineSpacing(0, 1.3f);
            LayoutParams il = new LayoutParams(MATCH, WRAP);
            il.topMargin = px(10);
            addView(indo, il);
            setLayoutParams(new AbsListView.LayoutParams(MATCH, WRAP));
        }

        void bind(String n, String ar, String la, String id, boolean act, boolean center) {
            num.setText(n);
            arab.setText(ar);
            arab.setGravity(center ? Gravity.CENTER_HORIZONTAL : Gravity.RIGHT);
            latin.setText(la);
            latin.setVisibility(showLatin && la != null ? VISIBLE : GONE);
            indo.setText(id);
            indo.setVisibility(showIndo ? VISIBLE : GONE);
            indo.setGravity(center ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            active = act;
            live.setVisibility(act ? VISIBLE : GONE);
            setBackgroundColor(act ? ACTIVE : 0);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (active) {
                bar.setColor(AMBER);
                c.drawRect(0, 0, px(4), getHeight(), bar);
            }
        }
    }

    class AyahAdapter extends BaseAdapter {
        @Override public int getCount() {
            return QuranData.loaded() ? QuranData.count(surah) + offset() : 0;
        }
        @Override public Object getItem(int i) { return i; }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View v, ViewGroup parent) {
            AyahRow r = v instanceof AyahRow ? (AyahRow) v : new AyahRow();
            boolean live = QuranService.active && QuranService.surah == surah;
            if (pos < offset()) {
                r.bind("BASMALAH", QuranData.BISMILLAH, "Bismillāhir-raḥmānir-raḥīm(i).", QuranData.BISMILLAH_ID,
                        live && QuranService.bismillah, true);
            } else {
                int a = pos + 1 - offset(), g = QuranData.index(surah, a);
                r.bind(String.format(Locale.US, "%d : %d", surah, a), QuranData.arab[g], QuranData.latin[g],
                        QuranData.indo[g], live && !QuranService.bismillah && QuranService.ayah == a, false);
            }
            return r;
        }
    }

    /** Tombol teks yang bisa menyala (amber) atau mati. */
    class Chip extends View {
        String label;
        boolean on, down;
        final Paint fill = new Paint(), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Chip(String label, Runnable click) {
            super(QuranActivity.this);
            this.label = label;
            setContentDescription(label); // pembaca layar & pengujian otomatis
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(px(1));
            p.setTypeface(fMonoBold);
            p.setTextSize(sp(11));
            p.setLetterSpacing(0.2f);
            p.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
            setOnClickListener(v -> click.run());
        }

        void setOn(boolean v) { on = v; invalidate(); }

        void setLabel(String s) {
            label = s;
            setContentDescription(s);
            requestLayout();
            invalidate();
        }

        /** Lebar mengikuti teks bila tidak ditentukan pasti (WRAP), supaya muat di layar sempit. */
        @Override protected void onMeasure(int wSpec, int hSpec) {
            if (MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY) { super.onMeasure(wSpec, hSpec); return; }
            setMeasuredDimension(resolveSize(Math.round(p.measureText(label)) + px(36), wSpec),
                    resolveSize(px(48), hSpec));
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (isPressed() != down) { down = isPressed(); invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            boolean lit = down || on;
            fill.setColor(down ? TEXT : on ? AMBER : PANEL);
            c.drawRect(0, 0, w, h, fill);
            if (!lit) {
                stroke.setColor(LINE);
                float s = stroke.getStrokeWidth() / 2f;
                c.drawRect(s, s, w - s, h - s, stroke);
            }
            p.setColor(lit ? INK : DIM);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(label, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, p);
        }
    }

    /** Tombol pemutar dengan ikon yang digambar (tanpa font ikon). */
    class Key extends View {
        static final int PREV = 0, PLAY = 1, NEXT = 2;
        final int kind;
        boolean playing, down;
        final Paint fill = new Paint(), ink = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();

        Key(int kind, Runnable click) {
            super(QuranActivity.this);
            this.kind = kind;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(px(1));
            stroke.setColor(LINE);
            setClickable(true);
            setOnClickListener(v -> click.run());
        }

        void setPlaying(boolean p) {
            if (p != playing) { playing = p; invalidate(); }
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (isPressed() != down) { down = isPressed(); invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            boolean main = kind == PLAY;
            fill.setColor(down ? TEXT : main ? AMBER : BG);
            c.drawRect(0, 0, w, h, fill);
            if (!down && !main) {
                float s = stroke.getStrokeWidth() / 2f;
                c.drawRect(s, s, w - s, h - s, stroke);
            }
            ink.setColor(down || main ? INK : TEXT);
            float cx = w / 2f, cy = h / 2f, r = px(main ? 13 : 10);
            path.rewind();
            if (main && playing) {
                c.drawRect(cx - r * 0.8f, cy - r, cx - r * 0.25f, cy + r, ink);
                c.drawRect(cx + r * 0.25f, cy - r, cx + r * 0.8f, cy + r, ink);
                return;
            }
            if (main) {
                path.moveTo(cx - r * 0.7f, cy - r);
                path.lineTo(cx + r, cy);
                path.lineTo(cx - r * 0.7f, cy + r);
            } else {
                float d = kind == NEXT ? 1 : -1;
                path.moveTo(cx - d * r * 0.9f, cy - r);
                path.lineTo(cx + d * r * 0.5f, cy);
                path.lineTo(cx - d * r * 0.9f, cy + r);
                path.close();
                float bx = cx + d * r * 0.55f;
                c.drawRect(Math.min(bx, bx + d * px(3)), cy - r, Math.max(bx, bx + d * px(3)), cy + r, ink);
            }
            path.close();
            c.drawPath(path, ink);
        }
    }
}
