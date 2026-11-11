package com.ashafizullah.klikdash;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Server TCP protokol KlikBeam v1 (docs/PROTOCOL.md di repo KlikBeam, yang juga berisi aplikasi pengirim iOS):
 * menerima koneksi dari HP, memeriksa kode pairing, lalu meneruskan CONFIG dan FRAME ke {@link BeamDecoder}.
 * Satu pengirim sekaligus; koneksi baru yang lolos handshake menggantikan yang lama. Juga mendaftarkan layanan
 * Bonjour/NSD supaya HP menemukan headunit. Saat penerima ditutup pengguna, pengirim diberi STOP supaya siaran di HP
 * ikut berhenti (tanpa itu HP menganggapnya putus dan mencoba menyambung ulang hingga 60 detik).
 */
final class BeamServer {
    /** Dipanggil setelah kode pairing cocok; false: tutup tanpa jawaban (pengirim menganggapnya putus, bukan kode salah). */
    interface Gate {
        boolean admit();
    }

    interface Listener {
        void onWaiting();
        void onConnected(String from);
        void onRejected(String from);
        void onConfig(int width, int height);
        void onEnded();
        void onServerError(String message);
    }

    static final int PORT = 47720;
    static final String SERVICE_TYPE = "_klikbeam._tcp";

    private static final String TAG = "KlikBeam";
    private static final int MAGIC = 0x4B424D31; // "KBM1"
    private static final int T_CONFIG = 1, T_FRAME = 2, T_PING = 3;
    /** Penerima → pengirim: penerima ditutup, hentikan siaran (pengirim lama tidak membacanya dan menyambung ulang). */
    private static final int T_STOP = 4;
    private static final int MAX_LEN = 8 << 20;
    private static final int IDLE_TIMEOUT_MS = 6000;

    private final Context context;
    private final int code;
    private final BeamDecoder decoder;
    private final Listener listener;
    private final Gate gate;
    private volatile ServerSocket server;
    private volatile boolean stopped;
    private Thread acceptThread;
    private Socket client;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;

    BeamServer(Context context, int code, BeamDecoder decoder, Listener listener, Gate gate) {
        this.context = context.getApplicationContext();
        this.code = code;
        this.decoder = decoder;
        this.listener = listener;
        this.gate = gate;
    }

    void start() {
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "KlikBeam-accept");
        acceptThread.start();
        register();
    }

    void stop() {
        stopped = true;
        unregister();
        closeQuietly(server);
        final Socket c;
        synchronized (this) {
            c = client;
            client = null; // serve() tidak lagi melapor onEnded: penerima memang ditutup
        }
        // Di thread lain: stop() dipanggil dari tombol (thread UI melarang jaringan). Socket tidak langsung ditutup:
        // close() dengan data masuk yang belum dibaca mengirim RST, dan HP bisa membuang STOP yang belum dibacanya.
        // serve() terus membaca (dan membuang) sampai HP menutup koneksinya; batasnya 2 detik.
        if (c != null) new Thread(() -> {
            sendStop(c);
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            closeQuietly(c);
        }, "KlikBeam-stop").start();
        if (acceptThread != null) acceptThread.interrupt();
        decoder.stop();
    }

    /** [u8 STOP][u32 0], lalu FIN. Cukup sekali: pengirim lama tidak membacanya, yang baru menutup koneksi. */
    private static void sendStop(Socket s) {
        try {
            OutputStream out = s.getOutputStream();
            out.write(new byte[]{T_STOP, 0, 0, 0, 0});
            out.flush();
            s.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            if (stopped) { server.close(); return; } // stop() sebelum bind selesai
        } catch (IOException e) {
            if (stopped) return;
            Log.e(TAG, "bind", e);
            listener.onServerError(e.getMessage());
            return;
        }
        listener.onWaiting();
        while (!server.isClosed()) {
            try {
                final Socket s = server.accept();
                new Thread(new Runnable() {
                    @Override public void run() { serve(s); }
                }, "KlikBeam-client").start();
            } catch (IOException e) {
                if (!server.isClosed()) Log.w(TAG, "accept", e);
            }
        }
    }

    private void serve(Socket s) {
        String from = s.getInetAddress().getHostAddress();
        try {
            s.setTcpNoDelay(true);
            s.setReceiveBufferSize(1 << 20);
            s.setSoTimeout(IDLE_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
            OutputStream out = s.getOutputStream();
            if (blocked()) { // terlalu banyak kode salah: tutup tanpa membaca kodenya
                Log.i(TAG, "dijeda (kode salah berulang): " + from);
                return;
            }
            boolean magic = in.readInt() == MAGIC;
            if (!magic || in.readInt() != code) {
                out.write(0);
                out.flush();
                Log.w(TAG, "ditolak: " + from);
                if (magic) wrongCode();
                listener.onRejected(from);
                return;
            }
            rightCode();
            if (!gate.admit()) {
                Log.i(TAG, "ditunda (penerima baru ditutup): " + from);
                return;
            }
            out.write(1);
            out.flush();
            synchronized (this) {
                closeQuietly(client); // pengirim lama digantikan
                client = s;
            }
            Log.i(TAG, "tersambung: " + from);
            listener.onConnected(from);
            readLoop(s, in);
        } catch (EOFException | SocketTimeoutException e) {
            Log.i(TAG, "putus: " + from + " (" + e.getClass().getSimpleName() + ")");
        } catch (IOException e) {
            Log.i(TAG, "putus: " + from + " (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
            Log.w(TAG, "putus: " + from, e); // pesan rusak dari pengirim tidak boleh mematikan proses
        } finally {
            closeQuietly(s);
            boolean wasCurrent;
            synchronized (this) {
                wasCurrent = client == s;
                if (wasCurrent) client = null;
            }
            if (wasCurrent) {
                decoder.stop();
                listener.onEnded();
            }
        }
    }

    // Kode pairing 6 angka: tanpa jeda, perangkat lain di WiFi yang sama bisa mencoba semuanya dalam hitungan menit.
    // Statis: bertahan saat server dibuat ulang (penerima ditutup lalu siaga lagi).
    private static final int MAX_WRONG = 5;
    private static final long BLOCK_MS = 30_000, BLOCK_MAX_MS = 30 * 60_000L;
    private static int wrong, blockLevel;
    private static long blockedUntil;

    private static synchronized boolean blocked() { return android.os.SystemClock.elapsedRealtime() < blockedUntil; }

    /** Setelah MAX_WRONG kode salah: semua koneksi ditolak 30 detik, lalu berlipat tiap terulang (maks. 30 menit). */
    private static synchronized void wrongCode() {
        if (++wrong < MAX_WRONG) return;
        wrong = 0;
        blockedUntil = android.os.SystemClock.elapsedRealtime() + Math.min(BLOCK_MAX_MS, BLOCK_MS << Math.min(blockLevel, 10));
        blockLevel++;
    }

    private static synchronized void rightCode() {
        wrong = 0;
        blockLevel = 0;
    }

    private void readLoop(Socket s, DataInputStream in) throws IOException {
        byte[] buf = new byte[512 * 1024];
        while (!s.isClosed()) {
            int type = in.readUnsignedByte();
            int len = in.readInt();
            if (len < 0 || len > MAX_LEN) throw new IOException("panjang pesan tidak wajar: " + len);
            if (stopped) { // STOP sudah dikirim: buang sisa frame sampai HP menutup koneksi
                while (len > 0) len -= Math.max(1, in.skipBytes(len)); // skipBytes boleh melewati lebih sedikit
                continue;
            }
            switch (type) {
                case T_CONFIG: {
                    int w = in.readUnsignedShort();
                    int h = in.readUnsignedShort();
                    byte[] sps = new byte[size(in.readInt())];
                    in.readFully(sps);
                    byte[] pps = new byte[size(in.readInt())];
                    in.readFully(pps);
                    Log.i(TAG, "CONFIG " + w + "x" + h);
                    decoder.configure(w, h, sps, pps);
                    listener.onConfig(w, h);
                    break;
                }
                case T_FRAME: {
                    boolean key = (in.readUnsignedByte() & 1) != 0;
                    long pts = in.readLong();
                    int n = size(len - 9);
                    if (n > buf.length) buf = new byte[n + n / 2];
                    in.readFully(buf, 0, n);
                    decoder.feed(buf, n, pts, key);
                    break;
                }
                case T_PING:
                    if (len > 0) in.skipBytes(len);
                    break;
                default:
                    in.skipBytes(len); // tipe dari versi protokol yang lebih baru
            }
        }
    }

    /**
     * Panjang dari pengirim sebelum dipakai mengalokasikan: negatif atau terlalu besar memutus koneksi, bukan
     * melempar NegativeArraySizeException/OutOfMemoryError yang mematikan seluruh proses (termasuk home).
     */
    private static int size(int n) throws IOException {
        if (n < 0 || n > MAX_LEN) throw new IOException("panjang isi tidak wajar: " + n);
        return n;
    }

    private void register() {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("KlikBeam " + Build.MODEL);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(PORT);
        info.setAttribute("v", "1");
        nsdListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo i) { Log.i(TAG, "NSD: " + i.getServiceName()); }
            @Override public void onRegistrationFailed(NsdServiceInfo i, int err) { Log.w(TAG, "NSD gagal: " + err); }
            @Override public void onServiceUnregistered(NsdServiceInfo i) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo i, int err) { }
        };
        try {
            nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (RuntimeException e) {
            // Tanpa Bonjour HP tetap bisa memakai IP yang tampil di layar.
            Log.w(TAG, "NSD", e);
            nsd = null;
        }
    }

    private void unregister() {
        if (nsd == null) return;
        try { nsd.unregisterService(nsdListener); } catch (RuntimeException ignored) { }
        nsd = null;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) { }
    }
}
