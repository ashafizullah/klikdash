package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.HOT;
import static com.ashafizullah.klikdash.HomeActivity.INK;
import static com.ashafizullah.klikdash.HomeActivity.MATCH;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;
import static com.ashafizullah.klikdash.HomeActivity.WRAP;

/**
 * Penerima KlikBeam (BETA) layar penuh: mirror layar HP lewat WiFi, pengirimnya aplikasi KlikBeam (Android di Play Store
 * uji tertutup; iOS belum rilis). Dibuka dari Pengaturan › KLIKBEAM, yang juga menampilkan cara menyambung, kode pairing 6 digit,
 * dan IP headunit. Video di tengah dengan letterbox sesuai rasio CONFIG; selama belum ada gambar tampil panel status.
 *
 * Server dan decoder ada di {@link BeamHub}, jadi siaran tidak putus saat berpindah ke jendela melayang
 * ({@link BeamFloat}): ketuk video lalu KECILKAN, tekan Kembali, atau pindah ke aplikasi lain selagi ada gambar.
 * Tanpa gambar, keluar dari layar ini mematikan server seperti sebelumnya (tidak memakan daya di latar).
 * Kode disimpan di prefs "beam".
 */
public final class BeamActivity extends Activity implements BeamHub.Listener {
    private static final long CONTROLS_MS = 3500;
    /** Jeda sebelum layar ini ditutup setelah jendela melayang dibuat, supaya decoder sempat pindah Surface. */
    private static final long HANDOFF_MS = 400;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private SurfaceView video;
    private View panel;
    private LinearLayout controls;
    private TextView state, badge;
    /** KECILKAN sedang berjalan: onStop tidak perlu mematikan atau membuat jendela melayang lagi. */
    private boolean minimizing;
    int themeBuilt;

    private final Runnable hideControls = () -> controls.setVisibility(View.GONE);

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override
    protected void onCreate(Bundle b) {
        themeBuilt = Theme.apply(this);
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        video = new SurfaceView(this);
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                BeamHub.attach(h.getSurface());
                BeamHub.hideFloat(); // gambar sudah di layar penuh; jendela melayang tidak diperlukan
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) { BeamHub.detach(h.getSurface()); }
        });
        root.addView(video, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        // Ukuran layar berubah setelah mode imersif aktif (bilah sistem disembunyikan): sesuaikan video lagi.
        root.addOnLayoutChangeListener((v, l, t, r, btm, ol, ot, or, ob) -> {
            if ((r - l != or - ol || btm - t != ob - ot) && BeamHub.streaming) fit(BeamHub.videoW, BeamHub.videoH);
        });
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP && BeamHub.streaming) toggleControls();
            return true;
        });
        panel = buildPanel();
        root.addView(panel, new FrameLayout.LayoutParams(MATCH, MATCH));
        badge = buildBadge();
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blp.topMargin = dp(24);
        root.addView(badge, blp);
        controls = buildControls();
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(20), dp(20), 0);
        root.addView(controls, clp);
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        minimizing = false;
        BeamHub.start(this);
        BeamHub.addListener(this);
        onBeamChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Theme.stale(this, themeBuilt);
    }

    @Override
    protected void onStop() {
        BeamHub.removeListener(this);
        ui.removeCallbacks(hideControls);
        controls.setVisibility(View.GONE);
        // Pindah ke aplikasi lain selagi ada gambar: lanjut melayang. Tanpa gambar (atau tanpa izin): matikan.
        if (!minimizing && !isChangingConfigurations() && !(BeamHub.streaming && BeamHub.showFloat())) BeamHub.stop();
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (BeamHub.streaming) {
            minimize();
        } else {
            BeamHub.stop();
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    @Override
    public void onBeamChanged() {
        if (!BeamHub.running()) { // dihentikan dari notifikasi atau jendela melayang
            if (!isFinishing()) finish();
            return;
        }
        badge.setVisibility(BeamHub.reconnecting ? View.VISIBLE : View.GONE);
        if (BeamHub.streaming) {
            fit(BeamHub.videoW, BeamHub.videoH);
            panel.setVisibility(View.GONE);
        } else {
            int res = BeamHub.stateRes;
            state.setText(BeamHub.stateArg == null ? getString(res) : getString(res, BeamHub.stateArg));
            state.setTextColor(res == R.string.beam_rejected || res == R.string.beam_error ? HOT : DIM);
            panel.setVisibility(View.VISIBLE);
            controls.setVisibility(View.GONE);
        }
    }

    /** Ke jendela melayang; layar ini ditutup sesudah decoder pindah ke Surface jendela itu. */
    private void minimize() {
        if (!BeamHub.showFloat()) {
            Toast.makeText(this, R.string.beam_no_overlay, Toast.LENGTH_LONG).show();
            return;
        }
        minimizing = true;
        controls.setVisibility(View.GONE);
        ui.postDelayed(this::finish, HANDOFF_MS);
    }

    private void toggleControls() {
        ui.removeCallbacks(hideControls);
        if (controls.getVisibility() == View.VISIBLE) {
            controls.setVisibility(View.GONE);
        } else {
            controls.setVisibility(View.VISIBLE);
            ui.postDelayed(hideControls, CONTROLS_MS);
        }
    }

    // ---- Tampilan ----

    private View buildPanel() {
        Typeface heavy = Compat.font(getAssets(), "display.ttf", "'opsz' 72, 'wght' 800", Typeface.DEFAULT_BOLD);
        Typeface mono = Compat.font(getAssets(), "mono.ttf", "'wdth' 100, 'wght' 400", Typeface.MONOSPACE);
        Typeface monoBold = Compat.font(getAssets(), "mono.ttf", "'wdth' 100, 'wght' 700", Typeface.MONOSPACE);

        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER);
        Theme.fill(p);
        int pad = dp(32);
        p.setPadding(pad, pad, pad, pad);

        p.addView(text("KLIKBEAM", heavy, 44, AMBER));
        TextView beta = text(getString(R.string.beam_beta), monoBold, 12, HOT);
        beta.setLetterSpacing(.2f);
        p.addView(beta);
        state = text("", mono, 13, DIM);
        state.setLetterSpacing(.12f);
        state.setPadding(0, dp(18), 0, dp(24));
        p.addView(state);

        // Cara menyambung, kode, dan alamat ada di Pengaturan › KLIKBEAM.
        TextView hint = text(getString(R.string.beam_receiver_hint, codeText(this)), null, 17, TEXT);
        hint.setMaxWidth(dp(560));
        p.addView(hint);
        return p;
    }

    private TextView buildBadge() {
        Typeface monoBold = Compat.font(getAssets(), "mono.ttf", "'wdth' 100, 'wght' 700", Typeface.MONOSPACE);
        TextView t = text(getString(R.string.beam_reconnecting), monoBold, 13, AMBER);
        t.setLetterSpacing(.15f);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC000000);
        bg.setCornerRadius(Theme.corner(getResources().getDisplayMetrics().density, dp(4)));
        t.setBackground(bg);
        t.setVisibility(View.GONE);
        return t;
    }

    /** KECILKAN (ke jendela melayang) dan TUTUP; muncul saat video diketuk. */
    private LinearLayout buildControls() {
        Typeface monoBold = Compat.font(getAssets(), "mono.ttf", "'wdth' 100, 'wght' 700", Typeface.MONOSPACE);
        LinearLayout row = new LinearLayout(this);
        row.addView(controlButton(R.string.beam_minimize, monoBold, true, v -> minimize()));
        row.addView(controlButton(R.string.beam_close, monoBold, false, v -> {
            BeamHub.stop();
            finish();
        }));
        row.setVisibility(View.GONE);
        return row;
    }

    private TextView controlButton(int res, Typeface tf, boolean primary, View.OnClickListener l) {
        TextView t = text(getString(res), tf, 14, primary ? INK : TEXT);
        t.setLetterSpacing(.12f);
        t.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable bg = new GradientDrawable();
        if (primary) Theme.tint(bg, AMBER);
        else bg.setColor(0xCC000000);
        bg.setCornerRadius(Theme.corner(getResources().getDisplayMetrics().density, dp(4)));
        if (!primary) bg.setStroke(dp(1), 0x66FFFFFF);
        t.setBackground(bg);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
        p.setMarginStart(dp(10));
        t.setLayoutParams(p);
        return t;
    }

    /** SurfaceView sebesar mungkin dengan rasio video, di tengah layar. */
    private void fit(int w, int h) {
        int rw = root.getWidth(), rh = root.getHeight();
        if (rw == 0 || rh == 0) {
            root.post(() -> fit(w, h)); // belum diukur (baru dibuka dari jendela melayang)
            return;
        }
        if (w == 0 || h == 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) video.getLayoutParams();
        if ((long) w * rh > (long) h * rw) {
            lp.width = rw;
            lp.height = (int) ((long) rw * h / w);
        } else {
            lp.height = rh;
            lp.width = (int) ((long) rh * w / h);
        }
        video.setLayoutParams(lp);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private TextView text(String s, Typeface tf, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        if (tf != null) t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER); // lebar penuh di panel; teks di tengah
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- Kode pairing & alamat ----

    static int pairingCode(Context c) {
        SharedPreferences p = c.getSharedPreferences("beam", MODE_PRIVATE);
        int code = p.getInt("code", 0);
        if (code < 100000) {
            code = 100000 + new SecureRandom().nextInt(900000);
            p.edit().putInt("code", code).apply();
        }
        return code;
    }

    /** Kode baru; dipakai server berikutnya (saat penerima dibuka lagi). */
    static void newCode(Context c) { c.getSharedPreferences("beam", MODE_PRIVATE).edit().remove("code").apply(); }

    /** "123 456" */
    static String codeText(Context c) {
        String s = String.valueOf(pairingCode(c));
        return s.substring(0, 3) + " " + s.substring(3);
    }

    /** Alamat untuk diketik di HP bila headunit tidak ditemukan otomatis, atau null tanpa jaringan. */
    static String addressText() {
        List<String> ips = addresses();
        return ips.isEmpty() ? null : join(ips, ":" + BeamServer.PORT + "   ") + ":" + BeamServer.PORT;
    }

    /** IPv4 di semua antarmuka aktif (wlan0, ap0 bila headunit jadi hotspot), tanpa loopback. */
    private static List<String> addresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (SocketException | NullPointerException ignored) {
            // getNetworkInterfaces() bisa null tanpa jaringan
        }
        return out;
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }
}
