package com.ashafizullah.klikdash;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Locale;

import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.HOT;
import static com.ashafizullah.klikdash.HomeActivity.MATCH;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;
import static com.ashafizullah.klikdash.HomeActivity.WARN;
import static com.ashafizullah.klikdash.HomeActivity.WRAP;
import static com.ashafizullah.klikdash.HomeActivity.show;

/**
 * Kartu OBD di bawah kartu jam; isi kartu mengikuti {@link ObdPrefs}. Hanya menampilkan: koneksinya milik
 * {@link ObdService}, jadi home yang di-pause tidak memutus adapter.
 */
final class HomeObd implements ObdLink.Listener {
    final HomeActivity h;
    TextView overlay;
    View scrim;
    FrameLayout block;
    int[] metricIds = ObdPrefs.enabled(null);
    TextView[] vals;
    View[] cells;

    /** Tinggi kartu (dp): label di atas dan angka di bawah dengan jarak di antaranya. */
    static final int HEIGHT = 76;

    HomeObd(HomeActivity h) { this.h = h; }

    /** Satu baris kartu (2–4), sama lebar. */
    View buildLeftPanel(int gap) {
        metricIds = ObdPrefs.enabled(h.prefs);
        int n = metricIds.length;
        vals = new TextView[n];
        cells = new View[n];
        LinearLayout cards = new LinearLayout(h);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < n; i++) {
            vals[i] = value("--");
            cells[i] = cell(h.getString(ObdPrefs.unitRes(metricIds[i])), vals[i]);
            cards.addView(cells[i], cellLp(i, gap));
        }

        // Tirai saat tidak ada data: tembus pandang dan bersudut bulat, supaya angka terakhir masih samar terlihat.
        scrim = new View(h);
        GradientDrawable veil = new GradientDrawable();
        veil.setColor(0x80000000 | (HomeActivity.BG & 0xFFFFFF));
        veil.setCornerRadius(Math.max(Theme.corner(h.getResources().getDisplayMetrics().density), h.px(8)));
        scrim.setBackground(veil);
        scrim.setVisibility(View.GONE);

        overlay = h.text("", h.fMonoBold, 12, TEXT);
        overlay.setGravity(Gravity.CENTER);
        overlay.setLetterSpacing(0.08f);
        overlay.setVisibility(View.GONE);

        block = new FrameLayout(h);
        block.addView(cards, new FrameLayout.LayoutParams(MATCH, MATCH));
        block.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));
        block.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));
        return block;
    }

    private LinearLayout.LayoutParams cellLp(int idx, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH, 1f);
        if (idx > 0) lp.leftMargin = gap;
        return lp;
    }

    private View cell(String unit, TextView val) {
        return new HomeViews.Panel(h) {
            @Override protected void onDraw(Canvas c) {
                drawFrame(c);
                int ht = getHeight();
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setTypeface(h.fMono);
                p.setTextSize(h.sp(10));
                p.setColor(DIM);
                p.setLetterSpacing(0.12f);
                c.drawText(unit, h.px(10), h.px(14) - p.ascent(), p);
                p.setTypeface(h.fHeavy);
                p.setTextSize(h.sp(26));
                p.setColor(val.getCurrentTextColor());
                c.drawText(val.getText().toString(), h.px(10), ht - h.px(10) - p.descent(), p);
            }
        };
    }

    private TextView value(String s) {
        TextView t = h.text(s, h.fHeavy, 26, DIM);
        t.setVisibility(View.GONE);
        return t;
    }

    void start() {
        ObdService.sync(h);
        ObdLink.I.addListener(this);
    }

    void stop() { ObdLink.I.removeListener(this); }

    void refresh() {
        if (cells == null) return;
        for (View v : cells) if (v != null) v.invalidate();
    }

    @Override public void onValues(int[] ids, float[] values) {
        if (vals == null || !Arrays.equals(ids, metricIds)) return;
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            float v = i < values.length ? values[i] : Float.NaN;
            if (Float.isNaN(v)) show(vals[i], "--", DIM);
            else show(vals[i], format(id, v), color(id, v));
        }
        refresh();
    }

    static String format(int id, float v) {
        switch (id) {
            case ObdPrefs.VOLTAGE:
            case ObdPrefs.MAF:
                return String.format(Locale.US, "%.1f", v);
            default:
                return String.valueOf(Math.round(v));
        }
    }

    /** Putih saat normal; kuning/merah hanya untuk nilai yang melewati batas. */
    static int color(int id, float v) {
        if (id == ObdPrefs.RPM) return v > 5500 ? WARN : TEXT;
        if (id == ObdPrefs.SPEED) return v > 100 ? WARN : TEXT;
        if (id == ObdPrefs.COOLANT) return HomeObd.coolColor(v);
        return TEXT;
    }

    static int coolColor(float c) {
        if (c >= HomeObd.COOL_DANGER) return HOT;
        if (c >= HomeObd.COOL_PANAS) return WARN;
        return TEXT;
    }

    static final int COOL_PANAS = 95;
    static final int COOL_DANGER = 110;

    @Override public void onLink(boolean connected) {
        if (scrim == null) return;
        if (connected) {
            scrim.setVisibility(View.GONE);
            overlay.setVisibility(View.GONE);
        } else {
            onLog();
            scrim.setVisibility(View.VISIBLE);
            overlay.setVisibility(View.VISIBLE);
            if (vals != null) {
                for (TextView t : vals) show(t, "--", DIM);
            }
            refresh();
        }
    }

    /** Alasan kartu tertutup (mencari adapter, mencoba lagi, dimatikan) mengikuti status koneksi. */
    @Override public void onLog() {
        if (overlay != null && !ObdLink.I.linked()) {
            show(overlay, h.getString(ObdLink.stateRes(ObdLink.I.state())), TEXT);
        }
    }
}
