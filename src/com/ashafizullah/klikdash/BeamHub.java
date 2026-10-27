package com.ashafizullah.klikdash;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Inti penerima KlikBeam, terpisah dari tampilan supaya siaran tetap jalan saat berpindah antara layar penuh
 * ({@link BeamActivity}) dan jendela melayang ({@link BeamFloat}). Memegang server, decoder, dan status; tampilan
 * mendaftar sebagai {@link Listener} lalu membaca status dari sini. Selama hidup, {@link BeamService} menjaga proses
 * tetap di depan (notifikasi). Semua method dan status hanya di thread UI.
 */
final class BeamHub {
    interface Listener {
        /** Status, ukuran video, atau mode melayang berubah. */
        void onBeamChanged();
    }

    /**
     * Lama gambar terakhir ditahan setelah koneksi putus. Aplikasi iOS menyambung ulang sendiri (mis. WiFi hilang
     * sebentar), jadi status "berhenti" baru dipakai bila dalam waktu ini tidak ada CONFIG baru.
     */
    static final long RECONNECT_GRACE_MS = 10_000;
    /** Jendela melayang tanpa gambar menutup sendiri setelah ini, supaya tidak menghalangi Maps/Waze. */
    static final long FLOAT_IDLE_CLOSE_MS = 30_000;

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new ArrayList<>();
    private static Context app;
    private static BeamServer server;
    private static BeamDecoder decoder;
    private static BeamFloat floater;

    /** Teks status (R.string.beam_*) dan argumennya; berlaku selama belum ada gambar. */
    static int stateRes = R.string.beam_waiting;
    static String stateArg;
    /** Ada gambar (termasuk saat menunggu sambung ulang). */
    static boolean streaming;
    static boolean reconnecting;
    static int videoW = 16, videoH = 9;

    private static final Runnable graceOver = () -> {
        reconnecting = false;
        setState(R.string.beam_ended, null);
    };
    private static final Runnable idleClose = () -> { if (!streaming && floater != null) stop(); };

    private BeamHub() { }

    static boolean running() { return server != null; }

    static boolean floating() { return floater != null; }

    /** Nyalakan server bila belum (dari layar penuh). */
    static void start(Context c) {
        if (server != null) return;
        app = c.getApplicationContext();
        stateRes = R.string.beam_waiting;
        stateArg = null;
        streaming = reconnecting = false;
        decoder = new BeamDecoder(message -> ui.post(() ->
                Toast.makeText(app, Lang.wrap(app).getString(R.string.beam_decoder_error, message), Toast.LENGTH_LONG).show()));
        ServerEvents events = new ServerEvents();
        server = new BeamServer(app, BeamActivity.pairingCode(app), decoder, events);
        events.owner = server;
        server.start();
        BeamService.start(app);
    }

    /** Matikan semuanya: server, jendela melayang, notifikasi. Tampilan yang terbuka menutup diri. */
    static void stop() {
        if (server == null) return;
        ui.removeCallbacks(graceOver);
        ui.removeCallbacks(idleClose);
        hideFloat();
        server.stop(); // juga menghentikan decoder
        server = null;
        decoder = null;
        streaming = reconnecting = false;
        BeamService.stop();
        changed();
    }

    static void attach(Surface s) { if (decoder != null) decoder.attach(s); }

    static void detach(Surface s) { if (decoder != null) decoder.detach(s); }

    /** Ke jendela melayang. false bila izin tampil di atas aplikasi lain belum ada (lihat {@link BeamFloat#allowed}). */
    static boolean showFloat() {
        if (server == null) return false;
        if (floater != null) return true;
        if (!BeamFloat.allowed(app)) return false;
        BeamFloat f = new BeamFloat(app);
        if (!f.show()) return false; // tanpa jendela, siaran tidak boleh jalan diam-diam di latar
        floater = f;
        ui.removeCallbacks(idleClose);
        if (!streaming) ui.postDelayed(idleClose, FLOAT_IDLE_CLOSE_MS);
        changed();
        return true;
    }

    static void hideFloat() {
        if (floater == null) return;
        BeamFloat f = floater;
        floater = null;
        f.hide();
        changed();
    }

    static void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    static void removeListener(Listener l) { listeners.remove(l); }

    private static void changed() {
        for (Listener l : new ArrayList<>(listeners)) l.onBeamChanged();
    }

    private static void setState(int res, String arg) {
        streaming = false;
        ui.removeCallbacks(idleClose);
        if (floater != null) ui.postDelayed(idleClose, FLOAT_IDLE_CLOSE_MS);
        stateRes = res;
        stateArg = arg;
        changed();
    }

    /**
     * Dari thread jaringan BeamServer; semua diteruskan ke thread UI lewat {@link #post}, yang membuang kejadian dari
     * server yang sudah dihentikan (mis. onEnded sesudah {@link #stop}).
     */
    private static final class ServerEvents implements BeamServer.Listener {
        BeamServer owner;

        private void post(Runnable r) { ui.post(() -> { if (server == owner) r.run(); }); }

        @Override public void onWaiting() { post(() -> { if (!streaming) setState(R.string.beam_waiting, null); }); }

        @Override public void onConnected(String from) {
            post(() -> { if (!streaming) setState(R.string.beam_connected, null); });
        }

        @Override public void onRejected(String from) {
            post(() -> { if (!streaming) setState(R.string.beam_rejected, from); });
        }

        @Override public void onEnded() {
            post(() -> {
                if (!streaming) { setState(R.string.beam_ended, null); return; }
                reconnecting = true;
                ui.removeCallbacks(graceOver);
                ui.postDelayed(graceOver, RECONNECT_GRACE_MS);
                changed();
            });
        }

        @Override public void onServerError(String message) { post(() -> setState(R.string.beam_error, message)); }

        @Override public void onConfig(int width, int height) {
            post(() -> {
                ui.removeCallbacks(graceOver);
                ui.removeCallbacks(idleClose);
                videoW = width;
                videoH = height;
                streaming = true;
                reconnecting = false;
                changed();
            });
        }
    }
}
