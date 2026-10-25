package com.ashafizullah.klikdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.INK;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Tombol teks bergaya KlikDash; aksen bila {@code on}. Dipakai Pengaturan, TANYA, Al-Qur'an, dan Dzikir; warna saat
 * mati dan ukuran huruf bisa diatur ({@link #off}, {@link #textSp}).
 */
final class Key extends View {
    String label;
    boolean on, down;
    int minWidth;
    /** Latar dan teks saat tidak menyala dan tidak ditekan. */
    int offFill = PANEL, offText = TEXT;
    final float dp;
    final Paint fill = new Paint(), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), p = new Paint(Paint.ANTI_ALIAS_FLAG);

    Key(Context c, Typeface font, String label, boolean on, Runnable click) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        this.label = label;
        setContentDescription(label); // pembaca layar & pengujian otomatis
        this.on = on;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.round(dp));
        stroke.setColor(LINE);
        p.setTypeface(font);
        p.setTextSize(13 * c.getResources().getDisplayMetrics().scaledDensity);
        p.setLetterSpacing(0.2f);
        p.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
        if (click != null) setOnClickListener(v -> click.run());
    }

    Key off(int fill, int text) {
        offFill = fill;
        offText = text;
        invalidate();
        return this;
    }

    Key textSp(float size) {
        p.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
        requestLayout();
        return this;
    }

    void setOn(boolean v) {
        if (v != on) { on = v; invalidate(); }
    }

    void setLabel(String s) {
        label = s;
        setContentDescription(s);
        requestLayout();
        invalidate();
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (isPressed() != down) { down = isPressed(); invalidate(); }
    }

    /** Lebar mengikuti teks bila tidak ditentukan pasti (WRAP), supaya muat di layar sempit. */
    @Override protected void onMeasure(int wSpec, int hSpec) {
        if (MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY) { super.onMeasure(wSpec, hSpec); return; }
        setMeasuredDimension(resolveSize(Math.max(minWidth, Math.round(p.measureText(label) + 36 * dp)), wSpec),
                resolveSize(Math.round(48 * dp), hSpec));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        Theme.panel(c, fill, stroke, w, h, down ? TEXT : on ? AMBER : offFill, !down && !on);
        p.setColor(down || on ? INK : offText);
        p.setAlpha(isEnabled() ? 255 : 110);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(label, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, p);
    }
}
