package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.BG;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.FAINT;
import static com.ashafizullah.klikdash.HomeActivity.INK;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Dzikir pagi & petang dengan penghitung berpanduan. Data dari assets/dzikir.json, dibangun dari KlikBand
 * (tools/build_dzikir.mjs): urutan & jumlah dari Almanhaj, Latin mengikuti Hisnul Muslim, ayat dari Tanzil.
 */
public class DzikirActivity extends Activity {

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final int TRANSLATION = 0xFFD9D3C5, ACTIVE_BG = 0xFF1A1710;
    static final String PAGI = "pagi", PETANG = "petang";

    static final class Item {
        String title, arabic, latin, meaning, note;
        int count;
    }

    float dp, sp;
    Typeface fHeavy, fBold, fMono, fMonoBold, fArab, fSerif;
    SharedPreferences prefs;
    JSONObject data;

    String period;
    final List<Item> items = new ArrayList<>();
    int[] counts = new int[0];
    int cur;

    ListView list;
    ItemAdapter adapter;
    TextView step, arab, latin, meaning, note, title;
    ScrollView scroll;
    Chip cPagi, cPetang;
    Counter counter;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        sp = getResources().getDisplayMetrics().scaledDensity;
        prefs = getSharedPreferences("dzikir", MODE_PRIVATE);
        fHeavy = font("display.ttf", "'opsz' 72, 'wght' 800");
        fBold = font("display.ttf", "'opsz' 36, 'wght' 700");
        fMono = font("mono.ttf", "'wdth' 100, 'wght' 400");
        fMonoBold = font("mono.ttf", "'wdth' 100, 'wght' 700");
        fArab = font("arabic.ttf", null);
        fSerif = font("serif.ttf", null);
        try {
            data = new JSONObject(readAsset("dzikir.json"));
        } catch (Exception e) {
            data = new JSONObject();
        }
        setContentView(buildUi());
        String p = getIntent().getStringExtra("period");
        open(p != null ? p : currentPeriod(this));
    }

    // ---------------------------------------------------------------- data

    /** Pagi sebelum Ashar, petang sejak Ashar (lokasi dari pengaturan home; tanpa lokasi: Jakarta). */
    static String currentPeriod(android.content.Context c) {
        SharedPreferences hp = c.createDeviceProtectedStorageContext().getSharedPreferences("klikdash", MODE_PRIVATE);
        double lat = -6.2088, lon = 106.8456;
        try {
            String la = hp.getString("lat", null), lo = hp.getString("lon", null);
            if (la != null && lo != null) { lat = Double.parseDouble(la); lon = Double.parseDouble(lo); }
        } catch (NumberFormatException ignored) { }
        Calendar now = Calendar.getInstance();
        int ashar = PrayerTimes.compute(now, lat, lon)[2];
        int min = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return min < ashar ? PAGI : PETANG;
    }

    void open(String p) {
        period = p;
        items.clear();
        JSONArray arr = data.optJSONArray(p);
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            Item it = new Item();
            it.arabic = o.optString("arabic");
            it.latin = o.optString("latin");
            it.title = o.optString("title", it.latin);
            it.meaning = o.optString("meaning");
            it.note = o.isNull("note") ? null : o.optString("note");
            it.count = Math.max(1, o.optInt("count", 1));
            items.add(it);
        }
        counts = loadCounts();
        cur = firstUndone();
        cPagi.setOn(PAGI.equals(p));
        cPetang.setOn(PETANG.equals(p));
        title.setText(PAGI.equals(p) ? "DZIKIR PAGI" : "DZIKIR PETANG");
        adapter.notifyDataSetChanged();
        show(cur);
    }

    /** Progres disimpan per hari dan per waktu, jadi keluar-masuk tidak mengulang hitungan. */
    String key() {
        return "c_" + period + "_" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    int[] loadCounts() {
        int[] c = new int[items.size()];
        String[] f = prefs.getString(key(), "").split(",");
        for (int i = 0; i < c.length && i < f.length; i++) {
            try { c[i] = Integer.parseInt(f[i]); } catch (NumberFormatException ignored) { }
        }
        return c;
    }

    void saveCounts() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < counts.length; i++) s.append(i > 0 ? "," : "").append(counts[i]);
        prefs.edit().putString(key(), s.toString()).apply();
    }

    boolean done(int i) { return counts[i] >= items.get(i).count; }

    int firstUndone() {
        for (int i = 0; i < items.size(); i++) if (!done(i)) return i;
        return Math.max(0, items.size() - 1);
    }

    boolean allDone() {
        for (int i = 0; i < items.size(); i++) if (!done(i)) return false;
        return !items.isEmpty();
    }

    // ---------------------------------------------------------------- perilaku

    void show(int i) {
        if (items.isEmpty()) return;
        cur = Math.max(0, Math.min(i, items.size() - 1));
        Item it = items.get(cur);
        step.setText(String.format(Locale.US, "BACAAN %02d / %02d  ·  %s  ·  %d×", cur + 1, items.size(),
                it.title.toUpperCase(new Locale("id", "ID")), it.count));
        arab.setText(it.arabic);
        latin.setText(it.latin);
        meaning.setText(it.meaning);
        note.setText(it.note != null ? it.note : "");
        note.setVisibility(it.note != null ? View.VISIBLE : View.GONE);
        scroll.scrollTo(0, 0);
        counter.invalidate();
        adapter.notifyDataSetChanged();
        int first = list.getFirstVisiblePosition(), last = list.getLastVisiblePosition();
        if (cur < first || cur > last) list.setSelectionFromTop(cur, px(8));
    }

    /** Ketuk = satu hitungan; begitu jumlahnya terpenuhi, pindah ke bacaan berikutnya yang belum selesai. */
    void count() {
        if (items.isEmpty()) return;
        if (allDone()) { // ulangi dari awal
            counts = new int[items.size()];
            saveCounts();
            show(0);
            return;
        }
        if (done(cur)) { show(firstUndone()); return; }
        counts[cur]++;
        saveCounts();
        counter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (done(cur)) {
            counter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            final int next = firstUndone();
            counter.invalidate();
            adapter.notifyDataSetChanged();
            if (!allDone()) counter.postDelayed(() -> show(next), 450); // beri jeda singkat agar angka penuh terlihat
        } else {
            counter.invalidate();
            adapter.notifyDataSetChanged();
        }
    }

    // ---------------------------------------------------------------- layout


    // Ukuran layar dalam dp: daftar di kiri (landscape) atau di atas (portrait).
    float wDp, hDp;
    boolean portrait, narrow;
    int screenW, screenH;

    void measureScreen() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        wDp = dm.widthPixels / dm.density;
        hDp = dm.heightPixels / dm.density;
        portrait = hDp > wDp;
        narrow = (wDp - (portrait ? 0 : listDp())) < 720;
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
        row.setBackgroundColor(BG);
        row.setFitsSystemWindows(true);

        // Kiri: judul, pilihan pagi/petang, daftar bacaan
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(px(24), px(20), px(24), px(14));
        title = text("DZIKIR", fHeavy, 34, TEXT);
        head.addView(title);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        cPagi = new Chip("PAGI", () -> open(PAGI));
        cPetang = new Chip("PETANG", () -> open(PETANG));
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0, px(44), 1f);
        c1.topMargin = px(12);
        chips.addView(cPagi, c1);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0, px(44), 1f);
        c2.topMargin = px(12);
        c2.leftMargin = px(10);
        chips.addView(cPetang, c2);
        head.addView(chips);
        left.addView(head);
        left.addView(rule(), new LinearLayout.LayoutParams(MATCH, px(1)));
        list = new ListView(this);
        list.setDivider(new ColorDrawable(LINE));
        list.setDividerHeight(px(1));
        list.setSelector(new ColorDrawable(0));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setVerticalScrollBarEnabled(false);
        adapter = new ItemAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> show(pos));
        left.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        row.addView(left, portrait ? new LinearLayout.LayoutParams(MATCH, px(hDp * 0.34f))
                : new LinearLayout.LayoutParams(px(listDp()), MATCH));
        row.addView(rule(), portrait ? new LinearLayout.LayoutParams(MATCH, px(1)) : new LinearLayout.LayoutParams(px(1), MATCH));

        // Kanan: teks bacaan dan penghitung
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(28), px(20), px(28), px(20));
        step = text("", fMonoBold, 11, AMBER);
        step.setLetterSpacing(0.2f);
        body.addView(step);
        arab = new TextView(this);
        arab.setTypeface(fArab);
        arab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        arab.setTextColor(TEXT);
        arab.setGravity(Gravity.RIGHT); // RIGHT, bukan END: dengan arah RTL, END berarti kiri
        arab.setTextDirection(View.TEXT_DIRECTION_RTL);
        arab.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(MATCH, WRAP);
        al.topMargin = px(10);
        body.addView(arab, al);
        latin = serif(15, AMBER & 0xCCFFFFFF);
        body.addView(latin, margin(14));
        meaning = serif(17, TRANSLATION);
        body.addView(meaning, margin(10));
        note = text("", fMono, 11, DIM);
        note.setLetterSpacing(0.15f);
        body.addView(note, margin(10));
        scroll.addView(body);
        right.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(PANEL);
        bar.setPadding(px(20), px(12), px(20), px(12));
        bar.addView(new Chip(narrow ? "‹" : "‹  SEBELUMNYA", () -> show(cur - 1)),
                new LinearLayout.LayoutParams(narrow ? px(72) : WRAP, px(72)));
        counter = new Counter();
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, px(72), 1f);
        kl.leftMargin = px(12);
        kl.rightMargin = px(12);
        bar.addView(counter, kl);
        bar.addView(new Chip(narrow ? "›" : "BERIKUTNYA  ›", () -> show(cur + 1)),
                new LinearLayout.LayoutParams(narrow ? px(72) : WRAP, px(72)));
        right.addView(bar, new LinearLayout.LayoutParams(MATCH, px(96)));
        row.addView(right, portrait ? new LinearLayout.LayoutParams(MATCH, 0, 1f) : new LinearLayout.LayoutParams(0, MATCH, 1f));
        return row;
    }

    // ---------------------------------------------------------------- helpers

    String readAsset(String name) throws java.io.IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    Typeface font(String file, String axes) { return Compat.font(getAssets(), file, axes, Typeface.DEFAULT); }

    TextView text(String s, Typeface tf, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    TextView serif(float size, int color) {
        TextView t = text("", fSerif, size, color);
        t.setLineSpacing(0, 1.3f);
        return t;
    }

    LinearLayout.LayoutParams margin(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = px(top);
        return lp;
    }

    View rule() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        return v;
    }

    int px(float v) { return Math.round(v * dp); }

    float sp(float v) { return v * sp; }

    // ---------------------------------------------------------------- views

    class Chip extends View {
        final String label;
        boolean on, down;
        final Paint fill = new Paint(), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Chip(String label, Runnable click) {
            super(DzikirActivity.this);
            this.label = label;
            setContentDescription(label); // pembaca layar & pengujian otomatis
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(px(1));
            stroke.setColor(LINE);
            p.setTypeface(fMonoBold);
            p.setTextSize(sp(12));
            p.setLetterSpacing(0.2f);
            p.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
            setOnClickListener(v -> click.run());
        }

        void setOn(boolean v) { on = v; invalidate(); }

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
            fill.setColor(down ? TEXT : on ? AMBER : BG);
            c.drawRect(0, 0, w, h, fill);
            if (!lit) {
                float s = stroke.getStrokeWidth() / 2f;
                c.drawRect(s, s, w - s, h - s, stroke);
            }
            p.setColor(lit ? INK : TEXT);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(label, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, p);
        }
    }

    /** Tombol hitung besar: "2 / 3" dan petunjuk, dengan pita progres di bawah; terang saat ditekan. */
    class Counter extends View {
        boolean down;
        final Paint fill = new Paint(), big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);

        Counter() {
            super(DzikirActivity.this);
            big.setTypeface(fHeavy);
            big.setTextSize(sp(34));
            big.setTextAlign(Paint.Align.CENTER);
            small.setTypeface(fMonoBold);
            small.setTextSize(sp(10));
            small.setLetterSpacing(0.25f);
            small.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
            setOnClickListener(v -> count());
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (isPressed() != down) { down = isPressed(); invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            if (items.isEmpty()) return;
            int w = getWidth(), h = getHeight();
            Item it = items.get(cur);
            boolean finished = allDone(), full = done(cur);
            fill.setColor(down ? TEXT : AMBER);
            c.drawRect(0, 0, w, h, fill);
            // Progres bacaan ini sebagai pita gelap di bawah tombol.
            fill.setColor(0x33000000);
            c.drawRect(0, h - px(6), w * Math.min(1f, counts[cur] / (float) it.count), h, fill);
            big.setColor(INK);
            small.setColor(INK);
            String n = finished ? "SELESAI" : Math.min(counts[cur], it.count) + " / " + it.count;
            String hint = finished ? "ALHAMDULILLAH  ·  KETUK UNTUK MENGULANG"
                    : full ? "SELESAI  ·  KETUK UNTUK LANJUT" : "KETUK UNTUK MENGHITUNG";
            if (small.measureText(hint) > w - px(16)) hint = finished ? "ALHAMDULILLAH" : full ? "LANJUT" : "KETUK";
            c.drawText(n, w / 2f, px(40), big);
            c.drawText(hint, w / 2f, px(60), small);
        }
    }

    class Row extends View {
        int i;
        boolean down;
        final Paint fill = new Paint();
        final TextPaint num = new TextPaint(Paint.ANTI_ALIAS_FLAG), name = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                meta = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        Row() {
            super(DzikirActivity.this);
            num.setTypeface(fMono);
            num.setTextSize(sp(11));
            name.setTypeface(fBold);
            name.setTextSize(sp(19));
            name.setLetterSpacing(0.02f);
            meta.setTypeface(fMono);
            meta.setTextSize(sp(9));
            meta.setLetterSpacing(0.15f);
            setLayoutParams(new AbsListView.LayoutParams(MATCH, px(66)));
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (isPressed() != down) { down = isPressed(); invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            if (i >= items.size()) return;
            Item it = items.get(i);
            int w = getWidth(), h = getHeight();
            boolean sel = i == cur, ok = done(i);
            if (down || sel) {
                fill.setColor(down ? PANEL : ACTIVE_BG);
                c.drawRect(0, 0, w, h, fill);
            }
            if (sel) {
                fill.setColor(AMBER);
                c.drawRect(0, 0, px(4), h, fill);
            }
            num.setColor(ok ? AMBER : sel ? TEXT : DIM);
            c.drawText(ok ? "✓" : String.format(Locale.US, "%02d", i + 1), px(22), px(38), num);
            float x = px(62), avail = w - x - px(18);
            name.setColor(ok ? DIM : TEXT);
            CharSequence n = TextUtils.ellipsize(it.title, name, avail, TextUtils.TruncateAt.END);
            c.drawText(n, 0, n.length(), x, px(32), name);
            meta.setColor(ok ? AMBER : FAINT);
            String m = ok ? "SELESAI" : Math.min(counts[i], it.count) + " / " + it.count + "×";
            c.drawText(m, x, px(52), meta);
        }
    }

    class ItemAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            Row r = v instanceof Row ? (Row) v : new Row();
            r.i = i;
            r.invalidate();
            return r;
        }
    }
}
