package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import static com.ashafizullah.klikdash.HomeActivity.BG;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.HOT;
import static com.ashafizullah.klikdash.HomeActivity.MATCH;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Penerima KlikBeam (BETA): mirror layar iPhone lewat WiFi, pengirimnya aplikasi iOS KlikBeam yang belum dirilis.
 * Dibuka dari Pengaturan › Launcher › KLIKBEAM, yang juga menampilkan cara menyambung, kode pairing 6 digit, dan IP
 * headunit. Video di tengah dengan letterbox sesuai rasio CONFIG; selama belum ada gambar tampil panel status.
 * Server hanya hidup selama layar ini terlihat (onStart–onStop), jadi tidak memakan daya di latar. Kode disimpan di
 * prefs "beam".
 */
public final class BeamActivity extends Activity implements BeamServer.Listener, BeamDecoder.Listener {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BeamDecoder decoder;
    private BeamServer server;
    private FrameLayout root;
    private SurfaceView video;
    private View panel;
    private TextView state;
    private boolean streaming;
    int themeBuilt;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override
    protected void onCreate(Bundle b) {
        themeBuilt = Theme.apply(this);
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        decoder = new BeamDecoder(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        video = new SurfaceView(this);
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { decoder.setSurface(h.getSurface()); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) { decoder.setSurface(null); }
        });
        root.addView(video, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        panel = buildPanel();
        root.addView(panel, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        server = new BeamServer(this, pairingCode(this), decoder, this);
        server.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Theme.stale(this, themeBuilt);
    }

    @Override
    protected void onStop() {
        server.stop();
        server = null;
        showPanel(R.string.beam_waiting, null);
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    // ---- BeamServer.Listener / BeamDecoder.Listener (dipanggil dari thread jaringan) ----

    @Override public void onWaiting() { post(R.string.beam_waiting, null); }

    @Override public void onConnected(String from) { post(R.string.beam_connected, null); }

    @Override public void onRejected(String from) {
        if (!streaming) post(R.string.beam_rejected, from);
    }

    @Override public void onEnded() { post(R.string.beam_ended, null); }

    @Override public void onServerError(String message) { post(R.string.beam_error, message); }

    @Override
    public void onConfig(final int width, final int height) {
        ui.post(new Runnable() {
            @Override public void run() {
                fit(width, height);
                streaming = true;
                panel.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onDecoderError(final String message) {
        ui.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(BeamActivity.this, getString(R.string.beam_decoder_error, message), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- Tampilan ----

    private View buildPanel() {
        Typeface heavy = Compat.font(getAssets(), "display.ttf", "'opsz' 72, 'wght' 800", Typeface.DEFAULT_BOLD);
        Typeface mono = Compat.font(getAssets(), "mono.ttf", "'wdth' 100, 'wght' 400", Typeface.MONOSPACE);
        Typeface monoBold = Compat.font(getAssets(), "mono.ttf", "'wdth' 100, 'wght' 700", Typeface.MONOSPACE);

        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER);
        p.setBackgroundColor(BG);
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

        // Cara menyambung, kode, dan alamat ada di Pengaturan › Launcher › KLIKBEAM.
        TextView hint = text(getString(R.string.beam_receiver_hint, codeText(this)), null, 17, TEXT);
        hint.setMaxWidth(dp(560));
        p.addView(hint);
        return p;
    }

    private void post(final int stateRes, final String arg) {
        ui.post(new Runnable() {
            @Override public void run() { showPanel(stateRes, arg); }
        });
    }

    private void showPanel(int stateRes, String arg) {
        streaming = false;
        state.setText(arg == null ? getString(stateRes) : getString(stateRes, arg));
        state.setTextColor(stateRes == R.string.beam_rejected || stateRes == R.string.beam_error ? HOT : DIM);
        panel.setVisibility(View.VISIBLE);
    }

    /** SurfaceView sebesar mungkin dengan rasio video, di tengah layar. */
    private void fit(int w, int h) {
        int rw = root.getWidth(), rh = root.getHeight();
        if (rw == 0 || rh == 0 || w == 0 || h == 0) return;
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

    /** Alamat untuk diketik di iPhone bila headunit tidak ditemukan otomatis, atau null tanpa jaringan. */
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
