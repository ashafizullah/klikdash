package com.ashafizullah.klikdash;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.INK;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.MATCH;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;
import static com.ashafizullah.klikdash.HomeActivity.WRAP;

/** Laci "Semua aplikasi": cari, buka aplikasi, atau pilih aplikasi untuk satu ubin. */
final class HomeDrawer {
    final HomeActivity h;
    LinearLayout view;
    TextView title, count, noApps;
    android.widget.EditText search;
    GridView grid;
    AppAdapter adapter;
    int cols = 7;
    /** Ubin yang sedang dipilihkan aplikasinya; -1 = laci dibuka untuk membuka aplikasi. */
    int pickSlot = -1;
    /** Aplikasi yang tampil di laci: semua, atau yang cocok dengan pencarian (lihat filter). */
    final List<HomeApps.App> shown = new ArrayList<>();

    HomeDrawer(HomeActivity h) { this.h = h; }

    boolean isOpen() { return view.getVisibility() == View.VISIBLE; }

    LinearLayout build() {
        LinearLayout d = new LinearLayout(h);
        d.setOrientation(LinearLayout.VERTICAL);
        Theme.fill(d);
        int pad = h.px(24);
        d.setPadding(pad, pad, pad, 0);

        LinearLayout head = new LinearLayout(h);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        boolean narrow = h.wDp < 1000;
        title = h.text(h.getString(R.string.home_all_apps), h.fHeavy, narrow ? 30 : 40, TEXT);
        head.addView(title);
        count = h.text("", h.fMono, 13, AMBER);
        count.setLetterSpacing(0.2f);
        // Selebar teks terpanjang, supaya kolom cari tidak bergeser saat hitungannya berubah ("36 APLIKASI" ↔ "4 DARI 36").
        count.setMinWidth(Math.round(count.getPaint().measureText(
                h.getResources().getQuantityString(R.plurals.home_app_count, 100, 100))));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(WRAP, WRAP);
        cl.leftMargin = h.px(20);
        head.addView(count, cl);
        if (narrow) count.setVisibility(View.GONE);

        // Cari aplikasi: menyaring sambil mengetik. Keyboard hanya muncul bila kolomnya diketuk.
        search = new android.widget.EditText(h);
        search.setHint(h.getString(narrow ? R.string.home_search_short : R.string.home_search));
        search.setSingleLine(true);
        search.setTypeface(h.fMonoBold);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setLetterSpacing(0.1f);
        search.setTextColor(TEXT);
        search.setHintTextColor(DIM);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(h.px(16), 0, h.px(16), 0);
        android.graphics.drawable.GradientDrawable sbg = new android.graphics.drawable.GradientDrawable();
        sbg.setColor(PANEL);
        sbg.setStroke(h.px(1), LINE);
        sbg.setCornerRadius(Theme.corner(h.dp));
        search.setBackground(sbg);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // Layar mendatar: tanpa layar ketik penuh, supaya hasil saringan tetap terlihat di atas keyboard.
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { filter(); }
        });
        // Tombol cari di keyboard: tutup keyboard; tinggal satu hasil = langsung buka/pilih.
        search.setOnEditorActionListener((v, action, e) -> {
            hideKeyboard();
            if (shown.size() == 1) onPick(shown.get(0));
            return true;
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, h.px(56), 1f);
        sl.leftMargin = h.px(24);
        sl.rightMargin = h.px(12);
        head.addView(search, sl);

        HomeViews.Button settings = new HomeViews.Button(h, h.wDp < 900 ? "⚙" : h.getString(R.string.home_settings_button), false);
        settings.setOnClickListener(v -> h.openSettings());
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(WRAP, h.px(56));
        stl.rightMargin = h.px(12);
        head.addView(settings, stl);

        HomeViews.Button close = new HomeViews.Button(h, h.wDp < 900 ? "✕" : h.getString(R.string.home_close_button), false);
        close.setOnClickListener(v -> close());
        head.addView(close, new LinearLayout.LayoutParams(WRAP, h.px(56)));
        d.addView(head, new LinearLayout.LayoutParams(MATCH, h.px(64)));

        View rule = new View(h);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(MATCH, h.px(1));
        rl.topMargin = h.px(16);
        d.addView(rule, rl);

        grid = new GridView(h);
        cols = Math.max(3, Math.min(9, (int) ((h.wDp - 48) / 170)));
        grid.setNumColumns(cols);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(h.px(12));
        grid.setVerticalSpacing(h.px(12));
        grid.setSelector(new ColorDrawable(0));
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setVerticalScrollBarEnabled(false);
        grid.setPadding(0, h.px(16), 0, h.px(24));
        grid.setClipToPadding(false);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> onPick(shown.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            h.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", shown.get(pos).cn.getPackageName(), null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        });
        d.addView(grid, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        noApps = h.text("", h.fMono, 14, DIM);
        noApps.setLetterSpacing(0.15f);
        noApps.setGravity(Gravity.CENTER);
        d.addView(noApps, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        grid.setEmptyView(noApps);
        // Menahan fokus saat laci dibuka/ditutup, supaya kolom cari tidak terfokus (dan keyboard tidak muncul) sendiri.
        d.setFocusableInTouchMode(true);
        view = d;
        return d;
    }

    /** Saring laci dengan teks pencarian (nama aplikasi yang tampil), tanpa memuat ulang ikon. */
    void filter() {
        List<HomeApps.App> apps = h.apps.all;
        String q = search != null ? search.getText().toString().trim().toLowerCase(Lang.locale()) : "";
        shown.clear();
        for (HomeApps.App a : apps) {
            if (q.isEmpty() || a.label.toLowerCase(Lang.locale()).contains(q)) shown.add(a);
        }
        adapter.notifyDataSetChanged();
        count.setText(q.isEmpty() ? h.getResources().getQuantityString(R.plurals.home_app_count, apps.size(), apps.size())
                : h.getString(R.string.home_app_filtered, shown.size(), apps.size()));
        noApps.setText(!h.apps.ready ? h.getString(R.string.home_apps_loading) : q.isEmpty() ? h.getString(R.string.home_no_apps)
                : h.getString(R.string.home_no_apps_for, search.getText().toString().trim().toUpperCase(Lang.locale())));
    }

    void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm = h.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        view.requestFocus();
    }

    void open(int slot) {
        pickSlot = slot;
        title.setText(slot >= 0
                ? h.getString(R.string.home_pick_for_slot, slot + 1)
                : h.getString(R.string.home_all_apps));
        search.setText(""); // juga menyaring ulang
        filter();
        grid.setSelection(0);
        h.home.setVisibility(View.GONE);
        view.setVisibility(View.VISIBLE);
        view.requestFocus();
    }

    void close() {
        pickSlot = -1;
        hideKeyboard();
        search.setText("");
        view.setVisibility(View.GONE);
        h.home.setVisibility(View.VISIBLE);
    }

    void onPick(HomeApps.App a) {
        if (pickSlot >= 0) {
            h.apps.saveSlot(pickSlot, a.cn, null);
            h.tiles[pickSlot].invalidate();
        } else {
            h.apps.launch(a.cn);
        }
        close();
    }

    class Cell extends HomeViews.Panel {
        HomeApps.App app;
        final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        Cell() {
            super(HomeDrawer.this.h); // h di sini field Panel, belum terisi sebelum super()
            label.setTypeface(h.fBold);
            label.setTextSize(h.sp(20));
            label.setLetterSpacing(0.04f);
            label.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            drawFrame(c);
            if (app == null) return;
            int w = getWidth(), ht = getHeight(), icon = Math.min(h.px(64), Math.round(ht * 0.42f));
            if (app.bmp != null) h.drawIcon(c, app.bmp, (w - icon) / 2, Math.round(ht * 0.15f), icon, down);
            label.setColor(down ? INK : TEXT);
            CharSequence s = TextUtils.ellipsize(app.upper, label, w - h.px(20), TextUtils.TruncateAt.END);
            c.drawText(s, 0, s.length(), w / 2f, ht - h.px(20), label);
        }
    }

    class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            Cell c = v instanceof Cell ? (Cell) v : new Cell();
            if (v == null) {
                float colDp = (h.wDp - 48 - 12 * (cols - 1)) / cols;
                c.setLayoutParams(new AbsListView.LayoutParams(MATCH, h.px(Math.max(110, Math.min(160, colDp * 0.85f)))));
            }
            c.app = shown.get(i);
            c.invalidate();
            return c;
        }
    }
}
