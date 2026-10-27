package com.ashafizullah.klikdash;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * SPAKE2 di Ed25519, sama persis dengan SPAKE2 BoringSSL (crypto/curve25519/spake25519.c) yang dipakai adbd untuk
 * pairing Wireless debugging. KlikDash selalu sisi "alice" (klien adb). Aritmetika titik memakai BigInteger: hanya
 * dipakai sekali saat pairing, jadi kecepatan dan waktu konstan tidak penting.
 */
final class Spake2 {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    /** Orde subgrup prima (l). */
    static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.shiftLeft(1).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    /** Titik M dan N BoringSSL (lihat komentar di spake25519.c), dalam encoding Ed25519. */
    private static final BigInteger[] M = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
    private static final BigInteger[] N = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));
    private static final BigInteger[] B = base();

    static final byte[] CLIENT_NAME = "adb pair client\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    static final byte[] SERVER_NAME = "adb pair server\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final byte[] myName, theirName, passwordHash, myMsg;
    private final BigInteger privateKey, passwordScalar;

    Spake2(byte[] password) { this(password, CLIENT_NAME, SERVER_NAME, new SecureRandom()); }

    Spake2(byte[] password, byte[] myName, byte[] theirName, SecureRandom rnd) {
        this.myName = myName;
        this.theirName = theirName;
        byte[] r = new byte[64];
        rnd.nextBytes(r);
        // Kelipatan delapan (kofaktor) supaya komponen orde kecil titik lawan hilang.
        privateKey = le(r).mod(L).shiftLeft(3);
        passwordHash = sha512(password);
        BigInteger w = le(passwordHash).mod(L);
        // "password scalar hack" BoringSSL: tambah kelipatan l sampai kelipatan delapan.
        if (w.testBit(0)) w = w.add(L);
        if (w.testBit(1)) w = w.add(L.shiftLeft(1));
        if (w.testBit(2)) w = w.add(L.shiftLeft(2));
        passwordScalar = w;
        myMsg = encode(add(mul(B, privateKey), mul(M, passwordScalar)));
    }

    byte[] message() { return myMsg.clone(); }

    /** Kunci bersama 64 byte dari pesan lawan; null bila pesannya bukan titik kurva. */
    byte[] process(byte[] theirMsg) {
        BigInteger[] q = theirMsg.length == 32 ? decode(theirMsg) : null;
        if (q == null) return null;
        BigInteger[] peersMask = mul(N, passwordScalar);
        BigInteger[] dh = mul(add(q, neg(peersMask)), privateKey);
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        prefixed(t, myName);
        prefixed(t, theirName);
        prefixed(t, myMsg);
        prefixed(t, theirMsg);
        prefixed(t, encode(dh));
        prefixed(t, passwordHash);
        return sha512(t.toByteArray());
    }

    private static void prefixed(ByteArrayOutputStream t, byte[] d) {
        long n = d.length;
        for (int i = 0; i < 8; i++, n >>= 8) t.write((int) (n & 0xff));
        t.write(d, 0, d.length);
    }

    // Titik dalam koordinat extended (X, Y, Z, T), a = -1. Rumus penjumlahan ini lengkap: berlaku juga untuk doubling.

    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
        BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
        BigInteger c = p[3].multiply(D2).multiply(q[3]).mod(P);
        BigInteger d = p[2].shiftLeft(1).multiply(q[2]).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
        return new BigInteger[]{e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    private static BigInteger[] neg(BigInteger[] p) {
        return new BigInteger[]{P.subtract(p[0]).mod(P), p[1], p[2], P.subtract(p[3]).mod(P)};
    }

    static BigInteger[] mul(BigInteger[] p, BigInteger k) {
        BigInteger[] r = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = add(r, r);
            if (k.testBit(i)) r = add(r, p);
        }
        return r;
    }

    static byte[] encode(BigInteger[] p) {
        BigInteger zi = p[2].modInverse(P);
        BigInteger x = p[0].multiply(zi).mod(P), y = p[1].multiply(zi).mod(P);
        byte[] out = new byte[32];
        byte[] be = y.toByteArray();
        for (int i = 0; i < 32 && i < be.length; i++) out[i] = be[be.length - 1 - i];
        if (x.testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    /** Decode titik Ed25519; null bila bukan titik kurva. */
    static BigInteger[] decode(byte[] s) {
        byte[] c = s.clone();
        boolean sign = (c[31] & 0x80) != 0;
        c[31] &= 0x7f;
        BigInteger y = le(c);
        if (y.compareTo(P) >= 0) return null;
        return point(y, sign);
    }

    private static BigInteger[] point(BigInteger y, boolean sign) {
        BigInteger yy = y.multiply(y).mod(P);
        BigInteger xx = yy.subtract(BigInteger.ONE).multiply(D.multiply(yy).add(BigInteger.ONE).modInverse(P)).mod(P);
        BigInteger x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) x = x.multiply(SQRT_M1).mod(P);
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) return null;
        if (x.testBit(0) != sign) x = P.subtract(x).mod(P);
        return new BigInteger[]{x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    private static BigInteger[] base() {
        return point(BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P), false);
    }

    /** Bilangan little-endian tak bertanda. */
    static BigInteger le(byte[] b) {
        byte[] be = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) be[b.length - i] = b[i];
        return new BigInteger(be);
    }

    static byte[] sha512(byte[] d) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
