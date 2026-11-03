package com.ashafizullah.klikdash;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Daftar metrik OBD di Pengaturan: tiap baris punya pegangan ≡ untuk diseret mengubah urutan, dan tombol ON/OFF.
 * Selama menyeret baris lain hanya bergeser (translationY); urutan sebenarnya baru diubah saat dilepas, jadi tinggi
 * baris harus sama.
 */
final class ObdOrderList extends LinearLayout {
    private final SettingsActivity s;
    private final SharedPreferences prefs;
    private final Runnable onChange;
    private final int gap;

    private View dragged;
    private int from, to;
    private float startY, pitch;
    private float[] shift;

    ObdOrderList(SettingsActivity s, SharedPreferences prefs, Runnable onChange) {
        super(s);
        this.s = s;
        this.prefs = prefs;
        this.onChange = onChange;
        gap = s.px(8);
        setOrientation(VERTICAL);
        for (int id : ObdPrefs.order(prefs)) addView(row(id), rowLp());
    }

    private LayoutParams rowLp() {
        LayoutParams lp = new LayoutParams(SettingsActivity.MATCH, s.px(60));
        lp.bottomMargin = gap;
        return lp;
    }

    private View row(int id) {
        LinearLayout r = new LinearLayout(s);
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setTag(id);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setStroke(s.px(1), LINE);
        bg.setCornerRadius(Theme.corner(s.dp));
        r.setBackground(bg);

        TextView handle = s.text("≡", s.fMonoBold, 28, DIM);
        handle.setGravity(Gravity.CENTER);
        handle.setContentDescription(s.getString(ObdPrefs.labelRes(id)));
        handle.setOnTouchListener((v, e) -> touch(r, e));
        r.addView(handle, new LayoutParams(s.px(64), SettingsActivity.MATCH));

        TextView name = s.text(s.getString(ObdPrefs.labelRes(id)), s.fBold, 21, TEXT);
        r.addView(name, new LayoutParams(0, SettingsActivity.WRAP, 1f));

        boolean on = ObdPrefs.isOn(prefs, id);
        Key[] k = new Key[1];
        k[0] = s.key(s.getString(on ? R.string.settings_on : R.string.settings_off).toUpperCase(), on, () -> {
            boolean want = !k[0].on;
            if (!ObdPrefs.toggle(prefs, id, want)) {
                Notice.info(s, s.getString(want ? R.string.settings_obd_max : R.string.settings_obd_min));
                return;
            }
            onChange.run();
        });
        LayoutParams kl = new LayoutParams(s.px(96), s.px(40));
        kl.rightMargin = s.px(12);
        r.addView(k[0], kl);
        return r;
    }

    private boolean touch(View row, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragged = row;
                from = to = indexOfChild(row);
                startY = e.getRawY();
                pitch = row.getHeight() + gap;
                shift = new float[getChildCount()];
                row.setTranslationZ(s.px(8));
                getParent().requestDisallowInterceptTouchEvent(true); // ScrollView jangan ikut menggulir
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragged == null) return false;
                drag(e.getRawY() - startY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragged == null) return false;
                drop();
                return true;
            default:
                return false;
        }
    }

    private void drag(float dy) {
        int n = getChildCount();
        dy = Math.max(-from * pitch, Math.min((n - 1 - from) * pitch, dy));
        dragged.setTranslationY(dy);
        to = Math.max(0, Math.min(n - 1, Math.round(from + dy / pitch)));
        for (int j = 0; j < n; j++) {
            if (j == from) continue;
            float t = 0;
            if (from < to && j > from && j <= to) t = -pitch;
            else if (from > to && j < from && j >= to) t = pitch;
            if (t != shift[j]) {
                shift[j] = t;
                getChildAt(j).animate().translationY(t).setDuration(120).start();
            }
        }
    }

    private void drop() {
        View row = dragged;
        dragged = null;
        for (int j = 0; j < getChildCount(); j++) {
            getChildAt(j).animate().cancel();
            getChildAt(j).setTranslationY(0);
        }
        row.setTranslationZ(0);
        if (to == from) return;
        removeView(row);
        addView(row, to, rowLp());
        int[] order = new int[getChildCount()];
        for (int j = 0; j < order.length; j++) order[j] = (Integer) getChildAt(j).getTag();
        ObdPrefs.setOrder(prefs, order);
        onChange.run();
    }
}
