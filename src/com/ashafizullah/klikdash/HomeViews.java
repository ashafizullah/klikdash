package com.ashafizullah.klikdash;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.HapticFeedbackConstants;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.BG;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.FAINT;
import static com.ashafizullah.klikdash.HomeActivity.INK;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;
import static com.ashafizullah.klikdash.HomeApps.SLOTS;

/** View home yang digambar sendiri: satu View per ubin atau tombol, tanpa layout bertingkat. */
final class HomeViews {
    private HomeViews() { }

    /** Panel datar yang berubah amber saat ditekan. */
    abstract static class Panel extends View {
        final HomeActivity h;
        final Paint fill = new Paint();
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean down;

        Panel(HomeActivity h) {
            super(h);
            this.h = h;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(h.px(1));
            stroke.setColor(LINE);
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (isPressed() != down) {
                down = isPressed();
                invalidate();
            }
        }

        void drawFrame(Canvas c) {
            Theme.panel(c, fill, stroke, getWidth(), getHeight(), down ? AMBER : PANEL, !down);
        }
    }

    static class Tile extends Panel {
        final int idx;
        final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint dot = new Paint();

        Tile(HomeActivity h, int idx) {
            super(h);
            this.idx = idx;
            label.setTypeface(h.fBold);
            label.setTextSize(h.sp(30));
            label.setLetterSpacing(0.04f);
            small.setTypeface(h.fMono);
            small.setTextSize(h.sp(11));
            small.setLetterSpacing(0.15f);
            setClickable(true);
            HomeApps apps = h.apps;
            setOnClickListener(v -> {
                if (idx == SLOTS) { h.drawer.open(-1); return; }
                HomeApps.App a = apps.appFor(idx);
                if (a != null) apps.launch(a.cn);
                else if (!apps.ready && apps.slotCn[idx] != null) apps.launch(apps.slotCn[idx]); // daftar aplikasi belum termuat
                else h.drawer.open(idx);
            });
            if (idx < SLOTS) {
                // Tekan lama: geser ke ubin lain = tukar posisi; lepas di tempat = pilih aplikasi.
                setOnLongClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    v.startDragAndDrop(null, new View.DragShadowBuilder(v), idx, 0);
                    dragging = true;
                    invalidate();
                    return true;
                });
                setOnDragListener((v, e) -> {
                    if (!(e.getLocalState() instanceof Integer)) return false;
                    int from = (Integer) e.getLocalState();
                    switch (e.getAction()) {
                        case DragEvent.ACTION_DRAG_ENTERED:
                            target = idx != from;
                            invalidate();
                            break;
                        case DragEvent.ACTION_DRAG_EXITED:
                            target = false;
                            invalidate();
                            break;
                        case DragEvent.ACTION_DROP:
                            target = false;
                            if (idx == from) h.drawer.open(idx); else apps.swapSlots(from, idx);
                            break;
                        case DragEvent.ACTION_DRAG_ENDED:
                            target = false;
                            dragging = false;
                            invalidate();
                            break;
                    }
                    return true;
                });
            }
        }

        boolean dragging, target;

        boolean quran() {
            HomeApps.App a = idx < SLOTS ? h.apps.appFor(idx) : null;
            return a != null && QuranActivity.class.getName().equals(a.cn.getClassName());
        }

        /** Ubin asal saat diseret: diredupkan. Ubin tujuan: bingkai amber tebal. */
        void drawDragState(Canvas c) {
            int w = getWidth(), ht = getHeight();
            if (dragging) {
                fill.setColor(0xD9000000 | BG & 0xFFFFFF);
                c.drawRect(0, 0, w, ht, fill);
            }
            if (target) Theme.ring(c, fill, w, ht, AMBER, h.px(3));
        }

        @Override protected void onDraw(Canvas c) {
            drawFrame(c);
            HomeApps apps = h.apps;
            int w = getWidth(), ht = getHeight();
            // Ubin besar (sedikit ubin, layar besar) memperbesar ikon dan tulisan, sampai 1,8×.
            float k = Math.max(0.55f, Math.min(1.8f, Math.min(w / (float) h.px(180), ht / (float) h.px(190))));
            int m = Math.round(h.px(16) * k);
            small.setTextSize(h.sp(11) * Math.max(0.85f, Math.min(1.4f, k)));
            boolean all = idx == SLOTS;
            HomeApps.App a = all ? null : apps.appFor(idx);
            boolean loading = !all && a == null && !apps.ready && apps.slotCn[idx] != null;

            small.setColor(down ? INK : (idx < h.cols ? AMBER : DIM));
            c.drawText(String.format(Locale.US, "%02d", (all ? h.shown : idx) + 1), m, m + small.getTextSize(), small);

            int icon = Math.round(h.px(60) * k);
            String name, sub = null;
            if (all) {
                int cell = Math.round(h.px(14) * k), g = Math.round(h.px(7) * k), x0 = w - m - (cell * 3 + g * 2);
                dot.setColor(down ? INK : TEXT);
                for (int i = 0; i < 9; i++) {
                    int x = x0 + (i % 3) * (cell + g), y = m + (i / 3) * (cell + g);
                    if (i == 4 && !down) dot.setColor(AMBER);
                    c.drawRect(x, y, x + cell, y + cell, dot);
                    if (i == 4) dot.setColor(down ? INK : TEXT);
                }
                name = h.tAll;
                sub = h.tApps;
            } else if (a != null) {
                h.drawIcon(c, apps.iconOf(a), w - m - icon, m, icon, down);
                name = apps.slotLabel[idx] != null ? apps.labelText(apps.slotLabel[idx]) : a.upper;
                if (DzikirActivity.class.getName().equals(a.cn.getClassName())) {
                    sub = DzikirService.playing ? "▶ " + (DzikirActivity.PAGI.equals(DzikirService.period) ? h.tMorning : h.tEvening)
                            : h.loc.dzikirPagi() ? h.tMorning : h.tEvening;
                }
                if (AskActivity.class.getName().equals(a.cn.getClassName())) sub = h.tAssistant;
                if (QuranActivity.class.getName().equals(a.cn.getClassName()) && QuranService.playing) {
                    sub = String.format(Locale.US, "▶ %d : %d", QuranService.surah, QuranService.ayah);
                }
            } else if (loading) {
                name = apps.slotLabel[idx] != null ? apps.labelText(apps.slotLabel[idx]) : ""; // daftar aplikasi belum termuat, ikon menyusul
            } else {
                name = apps.slotLabel[idx] != null ? apps.labelText(apps.slotLabel[idx]) : h.tEmpty;
                sub = h.tTapToFill;
            }

            float base = ht - m;
            if (sub != null) {
                small.setColor(down ? INK : DIM);
                CharSequence ss = TextUtils.ellipsize(sub, small, w - m * 2, TextUtils.TruncateAt.END);
                c.drawText(ss, 0, ss.length(), m, base, small);
                base -= small.getTextSize() + h.px(10) * k;
            }
            label.setColor(down ? INK : (a == null && !all && !loading ? FAINT : TEXT));
            // Label panjang dikecilkan dulu (sampai 60%), baru dipotong kalau masih tidak muat.
            float avail = w - m * 2, size = h.sp(30) * k;
            label.setTextSize(size);
            float tw = label.measureText(name);
            if (tw > avail) label.setTextSize(Math.max(size * 0.6f, size * avail / tw * 0.97f));
            CharSequence s = TextUtils.ellipsize(name, label, avail, TextUtils.TruncateAt.END);
            c.drawText(s, 0, s.length(), m, base, label);
            drawDragState(c);
        }
    }

    static class Button extends Panel {
        final String caption;
        /** accent: tombol amber (ajakan), selain itu panel biasa. */
        final boolean accent;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Button(HomeActivity h, String caption, boolean accent) {
            super(h);
            this.caption = caption;
            setContentDescription(caption); // pembaca layar & pengujian otomatis
            this.accent = accent;
            p.setTypeface(h.fMonoBold);
            p.setTextSize(h.sp(13));
            p.setLetterSpacing(0.2f);
            p.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            if (MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY) { super.onMeasure(wSpec, hSpec); return; }
            setMeasuredDimension(resolveSize(Math.round(p.measureText(caption)) + h.px(44), wSpec),
                    resolveSize(h.px(48), hSpec));
        }

        @Override protected void onDraw(Canvas c) {
            if (accent) Theme.panel(c, fill, stroke, getWidth(), getHeight(), down ? TEXT : AMBER, false);
            else drawFrame(c);
            p.setColor(down || accent ? INK : TEXT);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(caption, getWidth() / 2f, getHeight() / 2f - (fm.ascent + fm.descent) / 2f, p);
        }
    }

    /** Penggaris 24 jam dengan jarum di waktu sekarang. Digambar ulang sekali per menit. */
    static class Gauge extends View {
        final HomeActivity h;
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path needle = new Path();

        Gauge(HomeActivity h) {
            super(h);
            this.h = h;
            num.setTypeface(h.fMono);
            num.setTextSize(h.sp(10));
            num.setLetterSpacing(0.1f);
        }

        @Override protected void onDraw(Canvas c) {
            Calendar cal = Calendar.getInstance();
            float frac = (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) / 1440f;
            float w = getWidth() - h.px(2), x0 = h.px(1), base = h.px(26), now = x0 + w * frac;

            line.setStrokeWidth(h.px(1));
            for (int hr = 0; hr <= 24; hr++) {
                float x = x0 + w * hr / 24f;
                boolean major = hr % 6 == 0;
                line.setColor(x <= now ? AMBER : FAINT);
                c.drawLine(x, base - (major ? h.px(14) : h.px(7)), x, base, line);
            }
            line.setColor(FAINT);
            c.drawLine(x0, base, x0 + w, base, line);
            line.setColor(AMBER);
            line.setStrokeWidth(h.px(3));
            c.drawLine(x0, base, now, base, line);

            needle.rewind();
            needle.moveTo(now - h.px(6), base - h.px(24));
            needle.lineTo(now + h.px(6), base - h.px(24));
            needle.lineTo(now, base - h.px(16));
            needle.close();
            line.setStyle(Paint.Style.FILL);
            c.drawPath(needle, line);
            line.setStyle(Paint.Style.STROKE);

            // Penanda waktu sholat: belah ketupat kecil di bawah garis, yang berikutnya amber.
            int[] pray = h.loc.pray;
            if (pray != null) {
                int next = h.loc.nextPrayer(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE));
                float r = h.px(3.5f), cy = base + h.px(6);
                line.setStyle(Paint.Style.FILL);
                for (int i = 0; i < pray.length; i++) {
                    float x = x0 + w * pray[i] / 1440f;
                    line.setColor(i == next ? AMBER : TEXT);
                    needle.rewind();
                    needle.moveTo(x, cy - r);
                    needle.lineTo(x + r, cy);
                    needle.lineTo(x, cy + r);
                    needle.lineTo(x - r, cy);
                    needle.close();
                    c.drawPath(needle, line);
                }
                line.setStyle(Paint.Style.STROKE);
            }

            num.setColor(DIM);
            float y = base + h.px(20);
            for (int hr = 0; hr <= 24; hr += 6) {
                String s = String.format(Locale.US, "%02d", hr);
                num.setTextAlign(hr == 0 ? Paint.Align.LEFT : hr == 24 ? Paint.Align.RIGHT : Paint.Align.CENTER);
                c.drawText(s, x0 + w * hr / 24f, y, num);
            }
        }
    }

    /** Lima waktu sholat + hitung mundur ke waktu berikutnya. Digambar ulang sekali per menit. */
    static class PrayerView extends View {
        final HomeActivity h;
        final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint time = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint info = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint rule = new Paint();
        final Paint solid = new Paint();

        /** Hitung mundur berdetik: hanya view kecil ini yang digambar ulang tiap detik, selama home tampil. */
        final Runnable tick = new Runnable() {
            @Override public void run() {
                invalidate();
                postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
            }
        };

        void startTicking() {
            removeCallbacks(tick);
            tick.run();
        }

        void stopTicking() { removeCallbacks(tick); }

        /** Skala ukuran: 1 dengan 12 ubin, lebih besar bila ubinnya lebih sedikit (lihat buildHome). */
        final float k;

        PrayerView(HomeActivity h, float k) {
            super(h);
            this.h = h;
            this.k = k;
            name.setTypeface(h.fMono);
            name.setTextSize(h.sp(10 * k));
            name.setLetterSpacing(0.15f);
            time.setTypeface(h.fBold);
            time.setTextSize(h.sp(30 * k));
            time.setLetterSpacing(0.02f);
            info.setTypeface(h.fMono);
            info.setTextSize(h.sp(11 * k));
            info.setLetterSpacing(0.12f);
            rule.setColor(LINE);
        }

        @Override protected void onDraw(Canvas c) {
            HomeLocation loc = h.loc;
            int[] pray = loc.pray;
            if (pray == null) return;
            int w = getWidth();
            Calendar cal = Calendar.getInstance();
            int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            int next = loc.nextPrayer(now);

            c.drawRect(0, 0, w, h.px(1), rule);
            float col = w / 5f;
            // Panel sempit: kecilkan huruf supaya "MAGHRIB" dan "17:36" tidak bertumpuk dengan kolom sebelah.
            name.setTextSize(Math.min(h.sp(10 * k), col / 7.2f));
            time.setTextSize(Math.min(h.sp(30 * k), col * 0.4f));
            for (int i = 0; i < pray.length; i++) {
                float x = i * col;
                int color = i == next ? AMBER : pray[i] <= now ? DIM : TEXT;
                if (i == next) {
                    solid.setColor(AMBER);
                    c.drawRect(x, h.px(1), x + h.px(28 * k), h.px(4), solid);
                }
                name.setColor(i == next ? AMBER : DIM);
                c.drawText(Lang.prayers[i], x, h.px(28 * k), name);
                time.setColor(color);
                c.drawText(loc.prayText[i], x, h.px(64 * k), time);
            }

            int target = next >= 0 ? pray[next] : 1440 + loc.prayTomorrow[0];
            int left = (target - now) * 60 - cal.get(Calendar.SECOND); // detik tersisa
            String wait = String.format(Locale.US, "%02d:%02d:%02d", left / 3600, left / 60 % 60, left % 60);
            String msg = String.format(h.tNextIn, Lang.prayers[next >= 0 ? next : 0], wait);
            info.setColor(AMBER);
            info.setTextAlign(Paint.Align.LEFT);
            c.drawText(msg, 0, h.px(96 * k), info);

            String place = (loc.city != null ? loc.city : String.format(Locale.US, "%.2f, %.2f", loc.lat, loc.lon))
                    + (loc.locApprox ? h.tApprox : "");
            float avail = w - info.measureText(msg) - h.px(16);
            info.setColor(DIM);
            info.setTextAlign(Paint.Align.RIGHT);
            CharSequence p = TextUtils.ellipsize(place, info, avail, TextUtils.TruncateAt.END);
            c.drawText(p, 0, p.length(), w, h.px(96 * k), info);
        }
    }

    /** Tanda potong tipis di empat sudut layar, gaya panel instrumen. */
    static class CropMarks extends Drawable {
        final HomeActivity h;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        CropMarks(HomeActivity h) {
            this.h = h;
            p.setColor(FAINT);
            p.setStrokeWidth(h.px(1.5f));
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            int i = h.px(9), l = h.px(14);
            float L = b.left + i, T = b.top + i, R = b.right - i, B = b.bottom - i;
            c.drawLine(L, T, L + l, T, p); c.drawLine(L, T, L, T + l, p);
            c.drawLine(R, T, R - l, T, p); c.drawLine(R, T, R, T + l, p);
            c.drawLine(L, B, L + l, B, p); c.drawLine(L, B, L, B - l, p);
            c.drawLine(R, B, R - l, B, p); c.drawLine(R, B, R, B - l, p);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
