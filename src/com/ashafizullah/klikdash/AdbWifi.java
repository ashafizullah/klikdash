package com.ashafizullah.klikdash;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.function.IntConsumer;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * ADB lewat Wireless debugging (Android 11+) tanpa komputer: pairing kunci KlikDash dengan kode 6 angka dari
 * Pengaturan › Opsi pengembang › Wireless debugging, lalu koneksi TLS ke adbd perangkat itu sendiri. Dipakai bila
 * adbd tidak mendengar port 5555 (lihat {@link Tweaks#debuggingOn}). Semua koneksi ke 127.0.0.1: port dari mDNS hanya
 * dipakai bila iklannya berasal dari alamat perangkat ini sendiri.
 *
 * Protokol pairing sama dengan adb (system/core/adb/pairing_connection): TLS 1.3, kode + exported keying material
 * sebagai password SPAKE2 ({@link Spake2}), lalu PeerInfo berisi kunci publik terenkripsi AES-128-GCM.
 */
final class AdbWifi {
    static final String PAIRING = "_adb-tls-pairing._tcp", CONNECT = "_adb-tls-connect._tcp";

    private static final String EXPORTED_KEY_LABEL = "adb-label\0";
    private static final int EXPORTED_KEY_SIZE = 64;
    private static final String HKDF_INFO = "adb pairing_auth aes-128-gcm key";
    private static final int HEADER_VERSION = 1, SPAKE2_MSG = 0, PEER_INFO = 1;
    private static final int PEER_INFO_SIZE = 8192, GCM_TAG = 16;

    /** Kode pairing salah (atau dialog pairing sudah ditutup di tengah jalan). */
    static final class WrongCode extends IOException {
        WrongCode() { super("kode pairing salah"); }
    }

    private AdbWifi() { }

    /** Pasangkan kunci KlikDash dengan adbd di {@code port} (port dialog pairing). Jalankan di thread latar. */
    static void pair(int port, String code) throws IOException {
        KeyPair k = Tweaks.key();
        if (k == null) throw new IOException("kunci ADB tidak tersedia");
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            raw.setSoTimeout(15000);
            SSLSocket s = tls(raw, k);
            s.startHandshake();
            byte[] ekm = android.net.ssl.SSLSockets.exportKeyingMaterial(s, EXPORTED_KEY_LABEL, null, EXPORTED_KEY_SIZE);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            ByteArrayOutputStream pw = new ByteArrayOutputStream();
            byte[] c = code.getBytes(StandardCharsets.UTF_8);
            pw.write(c, 0, c.length);
            pw.write(ekm, 0, ekm.length);
            Spake2 spake = new Spake2(pw.toByteArray());
            writePacket(out, SPAKE2_MSG, spake.message());
            byte[] shared = spake.process(readPacket(in, SPAKE2_MSG));
            if (shared == null) throw new IOException("pesan SPAKE2 adbd tidak valid");
            SecretKeySpec aes = new SecretKeySpec(hkdf(shared, HKDF_INFO.getBytes(StandardCharsets.US_ASCII), 16), "AES");

            byte[] info = new byte[PEER_INFO_SIZE]; // type 0 = ADB_RSA_PUB_KEY, lalu kunci publik format adbd
            byte[] pub = Tweaks.publicKey((RSAPublicKey) k.getPublic());
            System.arraycopy(pub, 0, info, 1, Math.min(pub.length, PEER_INFO_SIZE - 2));
            writePacket(out, PEER_INFO, gcm(Cipher.ENCRYPT_MODE, aes, info));
            byte[] theirs;
            try {
                theirs = readPacket(in, PEER_INFO);
            } catch (IOException e) {
                throw new WrongCode(); // adbd memutus koneksi bila PeerInfo kita tidak bisa didekripsi
            }
            try {
                gcm(Cipher.DECRYPT_MODE, aes, theirs);
            } catch (IOException e) {
                throw new WrongCode();
            }
        } catch (javax.net.ssl.SSLException e) {
            throw new IOException(e);
        }
    }

    private static void writePacket(OutputStream out, int type, byte[] payload) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(6 + payload.length); // big-endian, seperti htonl di adb
        b.put((byte) HEADER_VERSION).put((byte) type).putInt(payload.length).put(payload);
        out.write(b.array());
        out.flush();
    }

    private static byte[] readPacket(DataInputStream in, int type) throws IOException {
        int version = in.readUnsignedByte(), t = in.readUnsignedByte(), len = in.readInt();
        if (version != HEADER_VERSION || t != type || len < 0 || len > 2 * PEER_INFO_SIZE)
            throw new IOException("paket pairing tidak dikenal");
        byte[] d = new byte[len];
        in.readFully(d);
        return d;
    }

    /** AES-128-GCM dengan nonce = nomor urut pesan (selalu 0: tiap arah hanya satu pesan). */
    private static byte[] gcm(int mode, SecretKeySpec key, byte[] data) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, key, new GCMParameterSpec(GCM_TAG * 8, new byte[12]));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** HKDF-SHA256 tanpa salt (RFC 5869). */
    private static byte[] hkdf(byte[] ikm, byte[] info, int len) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(info);
            mac.update((byte) 1);
            byte[] okm = new byte[len];
            System.arraycopy(mac.doFinal(), 0, okm, 0, len);
            return okm;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * Socket TLS 1.3 di atas {@code raw} dengan sertifikat dari kunci KlikDash. adbd memakai sertifikat self-signed dan
     * mencocokkan kunci publik klien dengan kunci yang sudah di-pair, jadi sertifikat server tidak diperiksa (koneksinya
     * hanya ke perangkat ini sendiri). Konteks baru tiap koneksi: tanpa sesi TLS yang dipakai ulang.
     */
    static SSLSocket tls(Socket raw, KeyPair k) throws IOException {
        try {
            X509Certificate cert = cert(k);
            X509KeyManager km = new X509KeyManager() {
                @Override public String[] getClientAliases(String type, Principal[] issuers) { return new String[]{"adb"}; }
                @Override public String chooseClientAlias(String[] type, Principal[] issuers, Socket s) { return "adb"; }
                @Override public String[] getServerAliases(String type, Principal[] issuers) { return null; }
                @Override public String chooseServerAlias(String type, Principal[] issuers, Socket s) { return null; }
                @Override public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[]{cert}; }
                @Override public PrivateKey getPrivateKey(String alias) { return k.getPrivate(); }
            };
            X509TrustManager tm = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String auth) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String auth) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(new KeyManager[]{km}, new TrustManager[]{tm}, null);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, "127.0.0.1", raw.getPort(), true);
            s.setUseClientMode(true);
            s.setEnabledProtocols(new String[]{"TLSv1.3"});
            return s;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static X509Certificate cachedCert;

    /** Sertifikat X.509 self-signed minimal (SHA256withRSA) untuk kunci KlikDash; Android tidak punya pembuatnya. */
    private static synchronized X509Certificate cert(KeyPair k) throws Exception {
        if (cachedCert != null && cachedCert.getPublicKey().equals(k.getPublic())) return cachedCert;
        byte[] alg = der(0x30, cat(new byte[]{0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b},
                new byte[]{0x05, 0x00}));
        byte[] name = der(0x30, der(0x31, der(0x30, cat(new byte[]{0x06, 0x03, 0x55, 0x04, 0x03},
                der(0x0c, "KlikDash".getBytes(StandardCharsets.UTF_8))))));
        byte[] validity = der(0x30, cat(der(0x17, "000101000000Z".getBytes(StandardCharsets.US_ASCII)),
                der(0x17, "491231235959Z".getBytes(StandardCharsets.US_ASCII))));
        byte[] tbs = der(0x30, cat(der(0xa0, der(0x02, new byte[]{2})), der(0x02, new byte[]{1}), alg, name, validity, name,
                k.getPublic().getEncoded()));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(k.getPrivate());
        sig.update(tbs);
        byte[] cert = der(0x30, cat(tbs, alg, der(0x03, cat(new byte[]{0}, sig.sign()))));
        cachedCert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
        return cachedCert;
    }

    private static byte[] der(int tag, byte[] content) {
        int n = content.length;
        byte[] len = n < 0x80 ? new byte[]{(byte) n}
                : n < 0x100 ? new byte[]{(byte) 0x81, (byte) n}
                : new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        return cat(new byte[]{(byte) tag}, len, content);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    /**
     * Cari port layanan adbd ({@link #PAIRING} atau {@link #CONNECT}) lewat mDNS. {@code onPort} dipanggil di thread
     * utama tiap kali port berubah: port baru, atau 0 bila layanannya hilang (mis. dialog pairing ditutup).
     */
    static final class Finder {
        private final NsdManager nsd;
        private final String type;
        private final IntConsumer onPort;
        private final Handler main = new Handler(Looper.getMainLooper());
        private NsdManager.DiscoveryListener listener;
        private String found; // nama layanan yang portnya sedang dipakai
        private boolean resolving;

        Finder(Context c, String type, IntConsumer onPort) {
            nsd = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
            this.type = type;
            this.onPort = onPort;
        }

        void start() {
            if (listener != null || nsd == null) return;
            listener = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onStartDiscoveryFailed(String t, int err) { main.post(() -> listener = null); }
                @Override public void onStopDiscoveryFailed(String t, int err) { }
                @Override public void onServiceFound(NsdServiceInfo info) { main.post(() -> resolve(info, 0)); }
                @Override public void onServiceLost(NsdServiceInfo info) {
                    main.post(() -> {
                        if (info.getServiceName().equals(found)) {
                            found = null;
                            onPort.accept(0);
                        }
                    });
                }
            };
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (RuntimeException e) {
                listener = null;
            }
        }

        void stop() {
            if (listener == null) return;
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (RuntimeException ignored) { }
            listener = null;
            main.removeCallbacksAndMessages(null);
        }

        /** NsdManager lama hanya bisa me-resolve satu layanan sekaligus: antre dengan mencoba lagi sebentar. */
        @SuppressWarnings("deprecation")
        private void resolve(NsdServiceInfo info, int attempt) {
            if (listener == null) return;
            if (resolving) {
                if (attempt < 20) main.postDelayed(() -> resolve(info, attempt + 1), 300);
                return;
            }
            resolving = true;
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int err) {
                    main.post(() -> {
                        resolving = false;
                        if (err == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 20)
                            main.postDelayed(() -> resolve(info, attempt + 1), 300);
                    });
                }

                @Override public void onServiceResolved(NsdServiceInfo i) {
                    boolean mine = isLocal(i);
                    main.post(() -> {
                        resolving = false;
                        if (listener == null || !mine) return;
                        found = i.getServiceName();
                        onPort.accept(i.getPort());
                    });
                }
            });
        }

        /** Iklan dari perangkat ini sendiri (bukan perangkat lain di WiFi yang sama). */
        private static boolean isLocal(NsdServiceInfo i) {
            try {
                return i.getHost() != null && NetworkInterface.getByInetAddress(i.getHost()) != null;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
