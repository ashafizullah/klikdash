package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Video tutorial YouTube untuk masalah ADB, ditampilkan sebagai QR code: headunit tidak punya kamera, jadi pengguna
 * memindainya dengan HP dan menonton sambil mengikuti langkahnya di headunit. QR-nya dibuat tools/build_qr.py.
 */
final class HelpVideo {
    static final int NONE = -1, USB = 0, USB_ALLOW = 1, WIRELESS = 2;

    /** Tanpa https:// untuk ditampilkan; QR-nya berisi URL lengkap (lihat tools/build_qr.py). */
    private static final String[] URL = {"youtu.be/eVMA64kG_H8", "youtu.be/eVMA64kG_H8?t=62", "youtu.be/qDb4Irg5wok"};
    private static final int[] QR = {R.drawable.qr_usb, R.drawable.qr_usb_allow, R.drawable.qr_wireless};

    private HelpVideo() { }

    /**
     * Video untuk hasil ADB yang gagal: belum diizinkan, USB debugging mati, atau port 5555 tertutup (videonya hanya
     * untuk Android 11+, yang bisa membukanya lewat Wireless debugging).
     */
    static int forAdb(Context c, int adbResult) {
        if (adbResult == Tweaks.NOT_ALLOWED) return USB_ALLOW;
        if (adbResult != Tweaks.NO_ADB) return NONE;
        if (!Tweaks.debuggingOn(c)) return USB;
        return AdbWifiService.supported() ? WIRELESS : NONE;
    }

    /** Pesan gagal beserta QR videonya; tanpa video: notifikasi gagal biasa. */
    static void fail(Activity a, String msg, int video) {
        if (video == NONE) {
            Notice.fail(a, msg);
            return;
        }
        a.runOnUiThread(() -> new Sheet(a, HomeActivity.HOT, a.getString(R.string.help_fail_tag), null, video)
                .text(msg, TEXT)
                .key(a.getString(R.string.common_close).toUpperCase(Lang.locale()), true, null)
                .show());
    }

    /** Kartu putih berisi QR, ajakan memindai, dan link pendeknya (untuk yang tidak bisa memindai). */
    static LinearLayout card(Context c, int video) {
        float dp = c.getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView qr = new ImageView(c);
        qr.setImageResource(QR[video]);
        int pad = Math.round(10 * dp), size = Math.round(168 * dp);
        qr.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(Theme.corner(dp));
        qr.setBackground(bg);
        col.addView(qr, new LinearLayout.LayoutParams(size, size));
        Typeface mono = Compat.font(c.getAssets(), "mono.ttf", "'wdth' 100, 'wght' 700", Typeface.MONOSPACE);
        TextView scan = new TextView(c);
        scan.setText(R.string.help_scan);
        scan.setTypeface(mono);
        scan.setTextSize(11);
        scan.setLetterSpacing(0.15f);
        scan.setLineSpacing(0, 1.2f);
        scan.setTextColor(AMBER);
        scan.setGravity(Gravity.CENTER);
        scan.setPadding(0, Math.round(12 * dp), 0, 0);
        col.addView(scan, new LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView url = new TextView(c);
        url.setText(URL[video]);
        url.setTypeface(mono);
        url.setTextSize(11);
        url.setTextColor(DIM);
        url.setPadding(0, Math.round(4 * dp), 0, 0);
        col.addView(url);
        return col;
    }
}
