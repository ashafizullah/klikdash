package com.ashafizullah.klikdash;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Penyetelan sistem yang hilang tiap reboot (matikan ksmd) dan kembali ke home bila Zlink muncul sendiri.
 * Perintahnya butuh hak sistem, jadi dijalankan lewat ADB perangkat itu sendiri (adbd lokal di 127.0.0.1)
 * tanpa mengubah partisi sistem. Hanya bekerja bila ADB aktif di headunit; bila tidak, diam-diam dilewati.
 */
final class Tweaks {
    static final String KSM_RUN = "/sys/kernel/mm/ksm/run";
    static final String KSM_OFF = "echo 0 > " + KSM_RUN;
    /** Tekan HOME hanya bila Zlink sedang di depan (dicek di perangkat, lewat shell ADB). */
    static final String HIDE_ZLINK = "dumpsys activity activities | grep mResumedActivity | grep -q com.zjinnova"
            + " && input keyevent KEYCODE_HOME";

    private static final int CNXN = 0x4e584e43, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            WRTE = 0x45545257, CLSE = 0x45534c43;

    private Tweaks() { }

    /** Sekali jalan di latar; gagal diam-diam (mis. adbd belum siap). */
    static void shellAsync(String cmd) { shellAsync(cmd, null); }

    /** Seperti di atas; {@code done} menerima true bila perintah sampai ke ADB (dipanggil di thread latar). */
    static void shellAsync(String cmd, Consumer<Boolean> done) {
        new Thread(() -> {
            boolean ok;
            try {
                shell(cmd);
                ok = true;
            } catch (IOException e) {
                ok = false;
            }
            if (done != null) done.accept(ok);
        }, "tweaks-cmd").start();
    }

    /** Coba hingga ~2 menit setelah boot (adbd TCP bisa menyala belakangan). */
    static void applyAsync() {
        new Thread(() -> {
            for (int i = 0; i < 12; i++) {
                if ("0".equals(read(KSM_RUN))) return;
                try {
                    shell(KSM_OFF);
                    return;
                } catch (IOException ignored) { }
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "tweaks").start();
    }

    private static volatile boolean compiling;

    /**
     * Kompilasi AOT KlikDash (dexopt "speed"), sekali per pemasangan/pembaruan. Android mengompilasi aplikasi lewat
     * dexopt latar yang menunggu perangkat diam dan sedang dicas; headunit hampir tidak pernah begitu, jadi tanpa ini
     * KlikDash terus diinterpretasi/JIT dan cold start-nya lambat. {@code cmd package compile} ada sejak Android 7.
     * Ditunda 30 detik supaya tidak berebut CPU dengan boot; gagal (ADB tidak aktif): dicoba lagi saat home dibuat ulang.
     */
    static void compileAsync(android.content.Context c, android.content.SharedPreferences p) {
        long installed;
        try {
            installed = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return;
        }
        if (compiling || p.getLong("compiledFor", 0) == installed) return;
        compiling = true;
        String pkg = c.getPackageName();
        new Thread(() -> {
            try {
                Thread.sleep(30_000);
                // Kompilasi bisa makan puluhan detik di headunit; tunggu hasilnya, bukan batas 15 detik bawaan.
                if (exec("cmd package compile -m speed -f " + pkg, 180_000).contains("Success")) {
                    p.edit().putLong("compiledFor", installed).apply();
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                compiling = false;
            }
        }, "dexopt").start();
    }

    private static String read(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String s = r.readLine();
            return s == null ? null : s.trim();
        } catch (IOException e) {
            return null; // SELinux bisa melarang app membaca sysfs; perintahnya idempoten, jadi tetap dijalankan
        }
    }

    private static void shell(String cmd) throws IOException { exec(cmd); }

    /** Jalankan perintah lewat ADB lokal dan kembalikan keluarannya. Jalankan di thread latar. */
    static String exec(String cmd) throws IOException { return exec(cmd, 15000); }

    static String exec(String cmd, int timeoutMs) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setSoTimeout(timeoutMs);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            send(out, CNXN, 0x01000000, 4096, "host::klikdash\0");
            if (recv(in, null)[0] != CNXN) throw new IOException("adbd meminta autentikasi");

            send(out, OPEN, 1, 0, "shell:" + cmd + "\0");
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            while (true) {
                int[] m = recv(in, output);
                if (m[0] == WRTE) {
                    send(out, OKAY, 1, m[1], "");
                } else if (m[0] == CLSE) {
                    send(out, CLSE, 1, m[1], "");
                    return new String(output.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, String data) throws IOException {
        byte[] d = data.getBytes(StandardCharsets.UTF_8);
        int sum = 0;
        for (byte b : d) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24 + d.length).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(d.length).putInt(sum).putInt(~cmd).put(d);
        out.write(h.array());
        out.flush();
    }

    /** {command, arg0, arg1}; payload WRTE ditambahkan ke {@code sink} bila ada, selain itu dibuang. */
    private static int[] recv(DataInputStream in, java.io.ByteArrayOutputStream sink) throws IOException {
        byte[] raw = new byte[24];
        in.readFully(raw);
        ByteBuffer h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = h.getInt(), a0 = h.getInt(), a1 = h.getInt(), len = h.getInt();
        byte[] data = new byte[len];
        in.readFully(data);
        if (sink != null && cmd == WRTE) sink.write(data, 0, len);
        return new int[]{cmd, a0, a1};
    }
}
