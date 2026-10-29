package com.ashafizullah.klikdash;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Pembuat QR code di aplikasi, untuk isi yang berubah (alamat dan kode pairing KlikBeam); QR video tutorial yang tetap
 * dibuat sekali oleh tools/build_qr.py. Hanya mode byte, tingkat koreksi M, versi 1–10 (hingga 213 byte), mengikuti
 * ISO/IEC 18004: pola tetap, data + Reed-Solomon yang diselang-seling per blok, lalu mask dengan penalti terkecil.
 */
final class QrCode {
    /** Codeword koreksi per blok dan jumlah blok, tingkat M, indeks = versi. */
    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    final int size;
    /** Mask terpilih (0–7); untuk uji terhadap pembuat QR lain. */
    int mask;
    private final boolean[][] dark, function;

    private QrCode(int version) {
        size = version * 4 + 17;
        dark = new boolean[size][size];
        function = new boolean[size][size];
    }

    boolean dark(int x, int y) { return dark[y][x]; }

    /** null bila teks terlalu panjang untuk versi 10. */
    static QrCode encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int v = 1; v <= 10; v++) {
            int capacity = dataCodewords(v);
            int bits = 4 + (v < 10 ? 8 : 16) + bytes.length * 8;
            if (bits > capacity * 8) continue;
            QrCode q = new QrCode(v);
            q.drawFunctionPatterns(v);
            q.drawCodewords(addEcc(v, data(v, bytes, capacity)));
            int best = 0, bestPenalty = Integer.MAX_VALUE;
            for (int m = 0; m < 8; m++) { // mask dengan penalti terkecil: paling mudah dipindai
                q.applyMask(m);
                q.drawFormat(m);
                int p = q.penalty();
                if (p < bestPenalty) { best = m; bestPenalty = p; }
                q.applyMask(m); // XOR: dibatalkan lagi
            }
            q.applyMask(best);
            q.drawFormat(best);
            q.mask = best;
            return q;
        }
        return null;
    }

    // ---------------------------------------------------------------- data

    private static int rawModules(int v) {
        int r = (16 * v + 128) * v + 64;
        if (v >= 2) {
            int n = v / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (v >= 7) r -= 36;
        }
        return r;
    }

    private static int dataCodewords(int v) { return rawModules(v) / 8 - ECC_PER_BLOCK[v] * BLOCKS[v]; }

    /** Mode byte, panjang, isi, terminator, lalu bantalan 0xEC/0x11 sampai penuh. */
    private static byte[] data(int v, byte[] bytes, int capacity) {
        BitBuffer b = new BitBuffer();
        b.put(0b0100, 4);
        b.put(bytes.length, v < 10 ? 8 : 16);
        for (byte x : bytes) b.put(x & 0xFF, 8);
        b.put(0, Math.min(4, capacity * 8 - b.length));
        b.put(0, (8 - b.length % 8) % 8);
        for (int pad = 0xEC; b.length < capacity * 8; pad ^= 0xEC ^ 0x11) b.put(pad, 8);
        return b.bytes();
    }

    private static byte[] addEcc(int v, byte[] data) {
        int blocks = BLOCKS[v], ecc = ECC_PER_BLOCK[v], raw = rawModules(v) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] div = rsDivisor(ecc);
        byte[][] bl = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            byte[] dat = Arrays.copyOfRange(data, k, k + shortLen - ecc + (i < shortBlocks ? 0 : 1));
            k += dat.length;
            byte[] block = Arrays.copyOf(dat, shortLen + 1);
            byte[] rem = rsRemainder(dat, div);
            System.arraycopy(rem, 0, block, block.length - ecc, ecc);
            bl[i] = block;
        }
        byte[] out = new byte[raw];
        for (int i = 0, k = 0; i < bl[0].length; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - ecc || j >= shortBlocks) out[k++] = bl[j][i]; // blok pendek tanpa byte ke-(shortLen-ecc)
            }
        }
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = (byte) gfMul(r[j] & 0xFF, root);
                if (j + 1 < degree) r[j] ^= r[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) gfMul(div[i] & 0xFF, f);
        }
        return r;
    }

    /** Perkalian di GF(2^8) dengan polinom 0x11D. */
    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---------------------------------------------------------------- tata letak

    private void set(int x, int y, boolean d) {
        dark[y][x] = d;
        function[y][x] = true;
    }

    private void drawFunctionPatterns(int v) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        if (v >= 2) {
            int n = v / 7 + 2, step = (v * 8 + n * 3 + 5) / (n * 4 - 4) * 2;
            int[] pos = new int[n];
            pos[0] = 6;
            for (int i = n - 1, p = size - 7; i >= 1; i--, p -= step) pos[i] = p;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0) continue; // di finder
                    for (int dy = -2; dy <= 2; dy++)
                        for (int dx = -2; dx <= 2; dx++)
                            set(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
            }
        }
        drawFormat(0); // tempatnya dicadangkan; isi sebenarnya setelah mask dipilih
        if (v >= 7) {
            int rem = v;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = v << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean d = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(a, b, d);
                set(b, a, d);
            }
        }
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy, d = Math.max(Math.abs(dx), Math.abs(dy));
                if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d != 2 && d != 4);
            }
        }
    }

    /** Tingkat M (bit 00) dan mask, BCH(15,5), dua salinan. */
    private void drawFormat(int mask) {
        int data = mask, rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    /** Zig-zag dua kolom dari kanan bawah, melewati kolom timing (x = 6). */
    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        dark[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (function[y][x]) continue;
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0;
                }
                if (inv) dark[y][x] = !dark[y][x];
            }
        }
    }

    /** Empat aturan penalti: deret ≥5 sewarna, kotak 2×2, pola mirip finder, dan keseimbangan gelap/terang. */
    private int penalty() {
        int p = 0, darkCount = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean prev = pass == 0 ? dark[a][b - 1] : dark[b - 1][a], cur = pass == 0 ? dark[a][b] : dark[b][a];
                    if (cur == prev) {
                        run++;
                    } else {
                        if (run >= 5) p += run - 2;
                        run = 1;
                    }
                }
                if (run >= 5) p += run - 2;
                for (int b = 0; b + 10 < size; b++) { // 1011101 dengan 4 terang di salah satu sisi
                    boolean f1 = true, f2 = true;
                    for (int k = 0; k < 11; k++) {
                        boolean d = pass == 0 ? dark[a][b + k] : dark[b + k][a];
                        if (d != FINDER_A[k]) f1 = false;
                        if (d != FINDER_B[k]) f2 = false;
                    }
                    if (f1) p += 40;
                    if (f2) p += 40;
                }
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (dark[y][x]) darkCount++;
                if (x + 1 < size && y + 1 < size) {
                    boolean c = dark[y][x];
                    if (c == dark[y][x + 1] && c == dark[y + 1][x] && c == dark[y + 1][x + 1]) p += 3;
                }
            }
        }
        int total = size * size;
        p += Math.abs(darkCount * 20 - total * 10) / total * 10;
        return p;
    }

    private static final boolean[] FINDER_A = {true, false, true, true, true, false, true, false, false, false, false};
    private static final boolean[] FINDER_B = {false, false, false, false, true, false, true, true, true, false, true};

    private static final class BitBuffer {
        byte[] buf = new byte[256];
        int length;

        void put(int value, int n) {
            for (int i = n - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) buf[length >>> 3] |= (byte) (0x80 >>> (length & 7));
                length++;
            }
        }

        byte[] bytes() { return Arrays.copyOf(buf, length / 8); }
    }

    /** Modul hitam di atas putih dengan tepi 2 modul (kartu QR menambah tepi putihnya sendiri), setajam piksel. */
    static final class QrDrawable extends Drawable {
        private final QrCode q;
        private final Paint paint = new Paint();

        QrDrawable(QrCode q) { this.q = q; }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            int n = q.size + 4;
            float cell = Math.min(b.width(), b.height()) / (float) n;
            float ox = b.left + (b.width() - cell * n) / 2f, oy = b.top + (b.height() - cell * n) / 2f;
            paint.setColor(0xFFFFFFFF);
            c.drawRect(ox, oy, ox + cell * n, oy + cell * n, paint);
            paint.setColor(0xFF000000);
            for (int y = 0; y < q.size; y++) {
                for (int x = 0; x < q.size; x++) {
                    if (!q.dark(x, y)) continue;
                    float l = ox + (x + 2) * cell, t = oy + (y + 2) * cell;
                    c.drawRect((float) Math.floor(l), (float) Math.floor(t), (float) Math.ceil(l + cell), (float) Math.ceil(t + cell), paint);
                }
            }
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
