package com.ashafizullah.klikdash;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ELM327 OBD-II lewat Bluetooth SPP; satu koneksi untuk seluruh proses, dijalankan oleh {@link ObdService} (bukan
 * oleh layar mana pun, jadi tetap hidup saat Maps di depan). Hanya PID yang dipilih di {@link ObdPrefs} yang dibaca.
 *
 * Satu thread kerja per "generasi": {@link #stop} menaikkan {@code gen} dan menutup socket, jadi thread lama keluar
 * dari I/O yang menggantung dan tidak pernah bersaing dengan thread baru (ELM327 hanya melayani satu klien).
 * Adapter yang menjawab tetapi mobil yang diam (kontak mati) bukan putus: socket dipertahankan, ECU dicoba lagi.
 */
final class ObdLink {
    static final ObdLink I = new ObdLink();

    static final int OFF = 0, NO_ADAPTER = 1, CONNECTING = 2, INIT = 3, ECU_WAIT = 4, LIVE = 5, RETRY = 6;
    private static final String[] STATE_NAMES = {"off", "no adapter", "connecting", "init", "waiting for ECU", "live", "retry"};

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String[] MODES = {"insecure", "secure", "port 1"};
    private static final String TAG = "klikdash-obd";
    private static final int MAX_LOG = 120;
    /** Nilai yang tidak diperbarui selama ini ditampilkan sebagai "--". */
    private static final long STALE_MS = 5000;
    private static final int MAX_SILENT = 3, MAX_DEAD_CYCLES = 5;

    interface Listener {
        /** {@code values} sejajar {@code metricIds}; {@link Float#NaN} bila tidak ada data. */
        default void onValues(int[] metricIds, float[] values) { }
        /** Adapter tersambung dan menjawab (mobilnya belum tentu). */
        default void onLink(boolean connected) { }
        /** Status atau log berubah. */
        default void onLog() { }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>(); // hanya utas utama
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final float[] last = new float[ObdPrefs.COUNT];
    private final long[] lastAt = new long[ObdPrefs.COUNT];

    private Context app;
    private Thread worker;
    private boolean on;
    private volatile int gen;
    private volatile BluetoothSocket sock;
    private volatile int[] metricIds = ObdPrefs.enabled(null);
    private volatile boolean linked;
    private volatile int state = OFF;
    private volatile String detail = "";
    private volatile int[] shownIds;
    private volatile float[] shownValues;

    // Hanya dipakai thread kerja yang sedang hidup.
    /** Protokol yang terakhir menjawab (ATDPN); null = cari otomatis. */
    private String proto;
    private int mode, fails, silent;
    private String lastEcuLog = "";

    private ObdLink() { }

    void init(Context c) {
        if (app == null) app = c.getApplicationContext();
    }

    void configure(int[] ids) {
        metricIds = ids == null || ids.length == 0 ? ObdPrefs.enabled(null) : ids.clone();
    }

    int state() { return state; }

    String detail() { return detail; }

    boolean linked() { return linked; }

    boolean running() {
        synchronized (this) { return on; }
    }

    static int stateRes(int s) {
        switch (s) {
            case NO_ADAPTER: return R.string.obd_state_no_adapter;
            case CONNECTING: return R.string.obd_state_connecting;
            case INIT: return R.string.obd_state_init;
            case ECU_WAIT: return R.string.obd_state_ecu_wait;
            case LIVE: return R.string.obd_state_live;
            case RETRY: return R.string.obd_state_retry;
            default: return R.string.obd_state_off;
        }
    }

    // ---------------------------------------------------------------- siklus hidup

    synchronized void start() {
        if (on) return;
        on = true;
        launch();
    }

    synchronized void stop() {
        if (!on) return;
        on = false;
        gen++;
        closeQuiet(sock);
        if (worker != null) worker.interrupt();
        state = OFF;
        detail = "";
        log("stopped");
        setLink(false);
    }

    /** Putuskan lalu sambung ulang dari awal (tombol SAMBUNGKAN, ganti adapter). */
    synchronized void restart() {
        stop();
        start();
    }

    private void launch() {
        final int g = ++gen;
        final Thread prev = worker;
        Thread t = new Thread(() -> loop(g, prev), "obd");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    private boolean alive(int g) { return g == gen; }

    // ---------------------------------------------------------------- pendengar dan log

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        l.onLink(linked);
        int[] ids = shownIds;
        float[] v = shownValues;
        if (ids != null && v != null) l.onValues(ids, v);
        l.onLog();
    }

    void removeListener(Listener l) { listeners.remove(l); }

    /** Terbaru di atas, supaya tidak perlu menggulir. */
    String logText(int max) {
        StringBuilder b = new StringBuilder();
        synchronized (lines) {
            int n = 0;
            for (Iterator<String> it = lines.descendingIterator(); it.hasNext() && n++ < max; ) {
                if (b.length() > 0) b.append('\n');
                b.append(it.next());
            }
        }
        return b.toString();
    }

    void clearLog() {
        synchronized (lines) { lines.clear(); }
        main.post(this::fireLog);
    }

    void log(String msg) {
        String line;
        synchronized (lines) {
            line = clock.format(new Date()) + "  " + msg;
            lines.addLast(line);
            while (lines.size() > MAX_LOG) lines.removeFirst();
        }
        Log.d(TAG, msg);
        main.post(this::fireLog);
    }

    private void fireLog() {
        for (Listener l : new ArrayList<>(listeners)) l.onLog();
    }

    private void set(int g, int s, String d) {
        if (!alive(g)) return;
        boolean changed = state != s || !d.equals(detail);
        state = s;
        detail = d;
        if (changed) log(STATE_NAMES[s] + (d.isEmpty() ? "" : ": " + d));
    }

    private synchronized void setLink(boolean up) {
        if (linked == up) return;
        linked = up;
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onLink(up);
        });
    }

    private void postValues(int[] ids, float[] values) {
        shownIds = ids;
        shownValues = values;
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onValues(ids, values);
        });
    }

    // ---------------------------------------------------------------- adapter

    static boolean looksLikeObd(String name) {
        if (name == null) return false;
        String u = name.toUpperCase(Locale.US);
        return u.contains("OBD") || u.contains("ELM") || u.contains("VLINK") || u.contains("V-LINK")
                || u.contains("VGATE") || u.contains("ICAR") || u.contains("KONNWEI") || u.contains("VEEPEAK");
    }

    /** Perangkat yang sudah dipasangkan, urut nama; kosong bila Bluetooth mati. */
    static List<BluetoothDevice> bonded() {
        List<BluetoothDevice> out = new ArrayList<>();
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) return out;
        out.addAll(a.getBondedDevices());
        out.sort((x, y) -> String.valueOf(x.getName()).compareToIgnoreCase(String.valueOf(y.getName())));
        return out;
    }

    static String name(BluetoothDevice d) {
        String n = d.getName();
        return (n == null ? "?" : n) + " (" + d.getAddress() + ")";
    }

    /** Adapter pilihan pengguna, atau yang namanya mirip adapter OBD; null (dan {@link #detail}) bila tidak ada. */
    private BluetoothDevice pickDevice(int g) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) {
            set(g, NO_ADAPTER, "Bluetooth off");
            return null;
        }
        String mac = app == null ? "" : ObdPrefs.mac(Prefs.of(app));
        BluetoothDevice best = null;
        for (BluetoothDevice d : a.getBondedDevices()) {
            if (!mac.isEmpty()) {
                if (mac.equalsIgnoreCase(d.getAddress())) return d;
            } else if (best == null && looksLikeObd(d.getName())) {
                best = d;
            }
        }
        if (best == null) set(g, NO_ADAPTER, mac.isEmpty() ? "no paired OBD adapter" : "chosen adapter is not paired");
        return best;
    }

    // ---------------------------------------------------------------- thread kerja

    private void loop(int g, Thread prev) {
        if (prev != null) {
            try { prev.join(8000); } catch (InterruptedException ignored) { }
        }
        log("start");
        fails = 0;
        while (alive(g)) {
            BluetoothDevice dev = pickDevice(g);
            if (dev == null) {
                setLink(false);
                pause(g, 4000);
                continue;
            }
            BluetoothSocket s = null;
            try {
                s = connect(g, dev);
                session(g, s);
            } catch (IOException e) {
                if (alive(g)) log("link lost: " + e.getMessage());
            } catch (RuntimeException e) {
                log("error: " + e);
                Log.w(TAG, e);
            } finally {
                closeQuiet(s);
                if (sock == s) sock = null;
            }
            if (!alive(g)) break;
            setLink(false);
            fails++;
            long wait = Math.min(2000L * fails, 10000L);
            set(g, RETRY, "retry in " + wait / 1000 + "s");
            pause(g, wait);
        }
    }

    private BluetoothSocket connect(int g, BluetoothDevice dev) throws IOException {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a != null && a.isDiscovering()) a.cancelDiscovery();
        set(g, CONNECTING, name(dev));
        IOException first = null;
        for (int n = 0; n < MODES.length && alive(g); n++) {
            int m = (mode + n) % MODES.length;
            BluetoothSocket s = null;
            try {
                s = open(dev, m);
                sock = s;
                if (!alive(g)) throw new IOException("stopped");
                log("connect via " + MODES[m] + " socket");
                s.connect();
                mode = m;
                log("connected");
                return s;
            } catch (IOException e) {
                closeQuiet(s);
                if (first == null) first = e;
                if (alive(g)) log(MODES[m] + " socket failed: " + e.getMessage());
                pause(g, 600);
            }
        }
        throw first != null ? first : new IOException("stopped");
    }

    private static BluetoothSocket open(BluetoothDevice dev, int m) throws IOException {
        if (m == 0) return dev.createInsecureRfcommSocketToServiceRecord(SPP);
        if (m == 1) return dev.createRfcommSocketToServiceRecord(SPP);
        try {
            Method rf = dev.getClass().getMethod("createRfcommSocket", int.class);
            return (BluetoothSocket) rf.invoke(dev, 1);
        } catch (ReflectiveOperationException e) {
            throw new IOException("createRfcommSocket unavailable");
        }
    }

    /** Dari adapter yang baru tersambung sampai putus: inisialisasi ELM327, lalu baca PID selama mobil menjawab. */
    private void session(int g, BluetoothSocket s) throws IOException {
        set(g, INIT, "");
        silent = 0;
        pause(g, 300);
        log("ATZ → " + clip(ask(g, s, "ATZ", 5000)));
        for (String c : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATST96", "ATSP" + (proto != null ? proto : "0")}) {
            String r = ask(g, s, c, 2500);
            log(c + " → " + clip(r));
            if (r == null) throw new IOException("no reply to " + c);
        }
        fails = 0;
        setLink(true);
        log("adapter ready (protocol " + (proto != null ? proto : "auto") + ")");

        boolean ecu = false;
        int cycle = 0, dead = 0;
        int[] miss = new int[ObdPrefs.COUNT];
        while (alive(g)) {
            if (!ecu) {
                set(g, ECU_WAIT, "");
                String r = ask(g, s, "0100", proto == null ? 15000 : 4000);
                if (mode01(r, 0x00) != null) {
                    ecu = true;
                    dead = 0;
                    Arrays.fill(miss, 0);
                    lastEcuLog = "";
                    String p = clip(ask(g, s, "ATDPN", 2000)).replaceAll("[^0-9A-C]", "");
                    if (p.length() == 2 && p.charAt(0) == 'A') p = p.substring(1); // "A6" = otomatis, hasilnya 6
                    if (p.length() == 1) proto = p;
                    log("ECU answered, protocol " + proto);
                    set(g, LIVE, "");
                } else {
                    String why = clip(r);
                    if (!why.equals(lastEcuLog)) {
                        lastEcuLog = why;
                        log("ECU: " + why);
                    }
                    if (proto != null) { // protokol tersimpan tidak cocok lagi (mobil lain?): cari ulang
                        proto = null;
                        ask(g, s, "ATSP0", 2500);
                    }
                    postValues(metricIds, nan(metricIds.length));
                    pause(g, 3000);
                    continue;
                }
            }

            int[] ids = metricIds;
            int asked = 0, got = 0;
            for (int id : ids) {
                if (!alive(g)) return;
                if (miss[id] >= 3 && cycle % 10 != 0) continue; // PID yang tidak didukung ECU: jarang dicoba lagi
                asked++;
                float v = query(g, s, id, miss[id] < 2);
                if (Float.isNaN(v)) {
                    if (++miss[id] == 3) log("metric " + id + " has no data, polling it less often");
                } else {
                    if (miss[id] >= 3) log("metric " + id + " has data again");
                    miss[id] = 0;
                    last[id] = v;
                    lastAt[id] = SystemClock.elapsedRealtime();
                    got++;
                }
            }
            cycle++;
            long now = SystemClock.elapsedRealtime();
            float[] out = new float[ids.length];
            for (int i = 0; i < ids.length; i++) out[i] = now - lastAt[ids[i]] < STALE_MS ? last[ids[i]] : Float.NaN;
            postValues(ids, out);
            if (asked > 0 && got == 0) {
                if (++dead >= MAX_DEAD_CYCLES) {
                    log("ECU stopped answering");
                    ecu = false;
                }
            } else if (got > 0) {
                dead = 0;
            }
            pause(g, 80);
        }
    }

    /** {@code verbose}: catat jawaban mentah bila tidak ada data, untuk dua kegagalan pertama sebuah metrik. */
    private float query(int g, BluetoothSocket s, int id, boolean verbose) throws IOException {
        String cmd = id == ObdPrefs.VOLTAGE ? "ATRV" : String.format(Locale.US, "01%02X", ObdPrefs.pid(id));
        String r = ask(g, s, cmd, id == ObdPrefs.VOLTAGE ? 1500 : 2500);
        // Tegangan dibaca dari adapter (ATRV): PID 0x42 jarang didukung ECU.
        float v = id == ObdPrefs.VOLTAGE ? volts(r) : decode(id, mode01(r, ObdPrefs.pid(id)));
        if (Float.isNaN(v) && verbose && r != null) log(cmd + " → " + clip(r));
        return v;
    }

    private static float[] nan(int n) {
        float[] a = new float[n];
        Arrays.fill(a, Float.NaN);
        return a;
    }

    private static float decode(int id, int[] b) {
        if (b == null) return Float.NaN;
        switch (id) {
            case ObdPrefs.RPM:
                return b.length < 2 ? Float.NaN : (b[0] * 256 + b[1]) / 4f;
            case ObdPrefs.SPEED:
                return b[0];
            case ObdPrefs.COOLANT:
                return b[0] - 40f;
            case ObdPrefs.FUEL:
            case ObdPrefs.LOAD:
            case ObdPrefs.THROTTLE:
                return b[0] * 100f / 255f;
            case ObdPrefs.INTAKE:
            case ObdPrefs.OIL:
                return b[0] - 40f;
            case ObdPrefs.MAP:
                return b[0];
            case ObdPrefs.MAF:
                return b.length < 2 ? Float.NaN : (b[0] * 256 + b[1]) / 100f;
            default:
                return Float.NaN;
        }
    }

    // ---------------------------------------------------------------- protokol

    /**
     * Kirim satu perintah dan baca sampai prompt '>'. null bila adapter tidak menjawab dalam {@code timeoutMs}; setelah
     * itu satu karakter dikirim untuk membatalkan perintah yang masih berjalan (mis. pencarian protokol), supaya
     * jawaban perintah berikutnya tidak bergeser. Tiga kali diam beruntun dianggap putus.
     */
    private String ask(int g, BluetoothSocket s, String cmd, int timeoutMs) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        while (in.available() > 0) in.read();
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String r = readPrompt(g, in, timeoutMs);
        if (r != null) {
            silent = 0;
            return r;
        }
        out.write('\r');
        out.flush();
        if (readPrompt(g, in, 1500) != null) {
            // Adapter menjawab pembatalan: hanya perintah ini yang tak dijawab (ECU lambat, PID tak didukung).
            silent = 0;
            log(cmd + ": no answer in " + timeoutMs + " ms (adapter alive)");
            return null;
        }
        log(cmd + ": no answer, adapter silent");
        if (++silent >= MAX_SILENT) throw new IOException("adapter not responding");
        return null;
    }

    private String readPrompt(int g, InputStream in, int timeoutMs) throws IOException {
        StringBuilder sb = new StringBuilder();
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        while (alive(g) && SystemClock.elapsedRealtime() < end) {
            if (in.available() > 0) {
                int b = in.read();
                if (b < 0) throw new IOException("adapter closed the link");
                if (b == '>') return sb.toString();
                sb.append((char) b);
            } else {
                SystemClock.sleep(8);
            }
        }
        if (!alive(g)) throw new IOException("stopped");
        return null;
    }

    /** Byte data dari jawaban mode 01 untuk {@code pid} ("41 0C 1A F8" → {0x1A, 0xF8}); null bila bukan jawaban data. */
    private static int[] mode01(String resp, int pid) {
        if (resp == null) return null;
        String mark = String.format(Locale.US, "41%02X", pid);
        for (String line : resp.split("[\r\n]+")) {
            String hex = line.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
            if (!hex.startsWith(mark) || hex.length() < mark.length() + 2) continue;
            int n = Math.min((hex.length() - mark.length()) / 2, 4);
            int[] b = new int[n];
            for (int i = 0; i < n; i++) b[i] = Integer.parseInt(hex.substring(mark.length() + 2 * i, mark.length() + 2 * i + 2), 16);
            return b;
        }
        return null;
    }

    /** "12.6V" dari ATRV. */
    private static float volts(String resp) {
        if (resp == null) return Float.NaN;
        Matcher m = Pattern.compile("(\\d{1,2}(?:\\.\\d+)?)\\s*V", Pattern.CASE_INSENSITIVE)
                .matcher(resp);
        return m.find() ? Float.parseFloat(m.group(1)) : Float.NaN;
    }

    /** Satu baris ringkas untuk log. */
    private static String clip(String r) {
        if (r == null) return "(no answer)";
        String s = r.replaceAll("[\r\n]+", " ").trim();
        if (s.isEmpty()) return "(empty)";
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static void closeQuiet(BluetoothSocket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }

    /** Tidur yang dibangunkan {@link #stop}; pemanggil mengecek {@code alive} sesudahnya. */
    private void pause(int g, long ms) {
        if (!alive(g)) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
