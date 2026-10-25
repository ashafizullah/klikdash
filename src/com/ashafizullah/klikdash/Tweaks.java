package com.ashafizullah.klikdash;

import android.content.Context;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javax.crypto.Cipher;

/**
 * Penyetelan sistem yang hilang tiap reboot (matikan ksmd) dan kembali ke home bila Zlink muncul sendiri.
 * Perintahnya butuh hak sistem, jadi dijalankan lewat ADB perangkat itu sendiri (adbd lokal di 127.0.0.1)
 * tanpa mengubah partisi sistem. Hanya bekerja bila ADB aktif di headunit; bila tidak, diam-diam dilewati.
 * Bila adbd meminta autentikasi ({@code ro.adb.secure=1}), KlikDash memakai kunci RSA-nya sendiri; kuncinya diizinkan
 * sekali lewat dialog "Allow USB debugging?" saat pengguna menekan tombol yang butuh ADB (lihat {@link #askAsync}).
 */
final class Tweaks {
    static final String KSM_RUN = "/sys/kernel/mm/ksm/run";
    static final String KSM_OFF = "echo 0 > " + KSM_RUN;
    /** Tekan HOME hanya bila Zlink sedang di depan (dicek di perangkat, lewat shell ADB). */
    static final String HIDE_ZLINK = "dumpsys activity activities | grep mResumedActivity | grep -q com.zjinnova"
            + " && input keyevent KEYCODE_HOME";

    private static final int CNXN = 0x4e584e43, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            WRTE = 0x45545257, CLSE = 0x45534c43, AUTH = 0x48545541;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    /** Hasil {@link #askAsync}. */
    static final int DONE = 0, NO_ADB = 1, NOT_ALLOWED = 2;

    /** adbd meminta autentikasi dan kunci KlikDash belum (atau tidak) diizinkan. */
    static final class NotAllowed extends IOException {
        NotAllowed() { super("adbd meminta autentikasi"); }
    }

    private Tweaks() { }

    /** Folder kunci ADB (penyimpanan DE, bisa dibaca saat direct boot). Tanpa ini, autentikasi dilewati. */
    private static volatile File keyDir;
    private static KeyPair key;

    static void init(Context c) {
        if (keyDir == null) keyDir = c.getApplicationContext().createDeviceProtectedStorageContext().getFilesDir();
    }

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

    /**
     * Untuk tombol yang ditekan pengguna: bila adbd belum mengenal kunci KlikDash, tampilkan dialog izin Android
     * ("Allow USB debugging?") dan tunggu jawabannya. {@code onAsk} dipanggil saat dialog itu muncul, {@code done}
     * menerima {@link #DONE}, {@link #NO_ADB} atau {@link #NOT_ALLOWED} (keduanya di thread latar).
     */
    static void askAsync(String cmd, Runnable onAsk, IntConsumer done) {
        new Thread(() -> {
            int r;
            try {
                exec(cmd, 15000, onAsk);
                r = DONE;
            } catch (NotAllowed e) {
                r = NOT_ALLOWED;
            } catch (IOException e) {
                r = NO_ADB;
            }
            done.accept(r);
        }, "tweaks-ask").start();
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

    static String exec(String cmd, int timeoutMs) throws IOException { return exec(cmd, timeoutMs, null); }

    /** {@code onAsk} bukan null: boleh meminta izin kunci di layar (lihat {@link #askAsync}). */
    private static String exec(String cmd, int timeoutMs, Runnable onAsk) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setSoTimeout(timeoutMs);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            send(out, CNXN, 0x01000000, 4096, "host::klikdash\0");
            connect(s, in, out, onAsk);
            s.setSoTimeout(timeoutMs);

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

    /**
     * Tunggu CNXN dari adbd. Bila diminta autentikasi: tanda tangani token dengan kunci KlikDash; bila kuncinya belum
     * dikenal dan {@code onAsk} ada, kirim kunci publiknya (Android menampilkan dialog izin) dan tunggu hingga 60 detik.
     */
    private static void connect(Socket s, DataInputStream in, OutputStream out, Runnable onAsk) throws IOException {
        boolean signed = false, asked = false;
        while (true) {
            java.io.ByteArrayOutputStream token = new java.io.ByteArrayOutputStream();
            int[] m;
            try {
                m = recv(in, token);
            } catch (IOException e) {
                // Dialog izin ditolak, dibiarkan, atau koneksi diputus adbd selama menunggu.
                if (asked) throw new NotAllowed();
                throw e;
            }
            if (m[0] == CNXN) return;
            if (m[0] != AUTH || m[1] != AUTH_TOKEN) throw new IOException("balasan adbd tidak dikenal");
            KeyPair k = key();
            if (k == null) throw new NotAllowed();
            if (!signed) {
                send(out, AUTH, AUTH_SIGNATURE, 0, sign(k, token.toByteArray()));
                signed = true;
            } else if (onAsk != null && !asked) {
                send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) k.getPublic()));
                s.setSoTimeout(60_000);
                onAsk.run();
                asked = true;
            } else {
                throw new NotAllowed();
            }
        }
    }

    /** Kunci RSA 2048-bit milik KlikDash, dibuat sekali lalu disimpan; null bila {@link #init} belum dipanggil. */
    private static synchronized KeyPair key() {
        if (key != null || keyDir == null) return key;
        File f = new File(keyDir, "adbkey");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (f.exists()) {
                byte[] enc = new byte[(int) f.length()];
                try (DataInputStream i = new DataInputStream(new FileInputStream(f))) {
                    i.readFully(enc);
                }
                RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(enc));
                key = new KeyPair(kf.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent())), priv);
            } else {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(2048);
                KeyPair k = g.generateKeyPair();
                File tmp = new File(keyDir, "adbkey.tmp");
                try (FileOutputStream o = new FileOutputStream(tmp)) {
                    o.write(k.getPrivate().getEncoded());
                }
                if (!tmp.renameTo(f)) throw new IOException("adbkey tidak tersimpan");
                key = k;
            }
        } catch (Exception e) {
            android.util.Log.w("KlikDash", "adbkey", e);
        }
        return key;
    }

    /** PKCS#1 v1.5 atas token 20 byte yang diperlakukan adbd sebagai hash SHA-1 (RSA_verify NID_sha1). */
    private static byte[] sign(KeyPair k, byte[] token) throws IOException {
        byte[] block = new byte[256];
        byte[] sha1 = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        int pad = block.length - sha1.length - token.length;
        block[1] = 1;
        for (int i = 2; i < pad - 1; i++) block[i] = (byte) 0xff;
        System.arraycopy(sha1, 0, block, pad, sha1.length);
        System.arraycopy(token, 0, block, pad + sha1.length, token.length);
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k.getPrivate());
            return c.doFinal(block);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** Format kunci publik adbd (struct RSAPublicKey milik mincrypt, base64) diikuti nama kunci. */
    private static byte[] publicKey(RSAPublicKey k) {
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n.mod(r32).modInverse(r32).negate().mod(r32).intValue());
        putLittleEndian(b, n);
        putLittleEndian(b, BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n));
        b.putInt(k.getPublicExponent().intValue());
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " klikdash@" + android.os.Build.MODEL + "\0")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void putLittleEndian(ByteBuffer b, BigInteger v) {
        byte[] be = v.toByteArray();
        for (int i = 0; i < 256; i++) b.put(i < be.length ? be[be.length - 1 - i] : 0);
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, String data) throws IOException {
        send(out, cmd, arg0, arg1, data.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] d) throws IOException {
        int sum = 0;
        for (byte b : d) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24 + d.length).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(d.length).putInt(sum).putInt(~cmd).put(d);
        out.write(h.array());
        out.flush();
    }

    /** {command, arg0, arg1}; payload WRTE/AUTH ditambahkan ke {@code sink} bila ada, selain itu dibuang. */
    private static int[] recv(DataInputStream in, java.io.ByteArrayOutputStream sink) throws IOException {
        byte[] raw = new byte[24];
        in.readFully(raw);
        ByteBuffer h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = h.getInt(), a0 = h.getInt(), a1 = h.getInt(), len = h.getInt();
        byte[] data = new byte[len];
        in.readFully(data);
        if (sink != null && (cmd == WRTE || cmd == AUTH)) sink.write(data, 0, len);
        return new int[]{cmd, a0, a1};
    }
}
