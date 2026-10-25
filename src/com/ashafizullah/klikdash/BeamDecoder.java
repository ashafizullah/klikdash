package com.ashafizullah.klikdash;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Decoder H.264 hardware ({@link MediaCodec}) yang menggambar langsung ke Surface. Input diisi dari thread koneksi
 * ({@link #feed}), output dirender oleh thread sendiri secepatnya (tanpa menunggu pts) supaya delay serendah mungkin.
 * CONFIG baru (resolusi berubah) → codec dibuat ulang, lalu frame dibuang sampai keyframe berikutnya.
 */
final class BeamDecoder {
    interface Listener {
        void onDecoderError(String message);
    }

    private static final String TAG = "KlikBeam";
    private static final String MIME = "video/avc";
    private static final byte[] START = {0, 0, 0, 1};

    private final Listener listener;
    private Surface surface;
    private MediaCodec codec;
    private Thread drain;
    /** CONFIG terakhir, dipakai lagi bila Surface baru siap setelah CONFIG datang. */
    private int width, height;
    private byte[] sps, pps;
    private boolean needKey = true;

    // Statistik untuk logcat
    private int frames, dropped;
    private long bytes, statsAt;

    BeamDecoder(Listener listener) {
        this.listener = listener;
    }

    /**
     * Gambar ke Surface ini (layar penuh atau jendela melayang). Codec yang sedang jalan dipindah dengan
     * setOutputSurface tanpa dibuat ulang, jadi tidak perlu menunggu keyframe; bila decoder headunit menolak, codec
     * dibuat ulang.
     */
    synchronized void attach(Surface s) {
        if (s == surface) return;
        surface = s;
        if (codec != null) {
            try {
                codec.setOutputSurface(s);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "setOutputSurface gagal, codec dibuat ulang", e);
                release();
            }
        }
        if (sps != null) start();
    }

    /** Surface ini hilang. Diabaikan bila decoder sudah pindah ke Surface lain (lihat {@link #attach}). */
    synchronized void detach(Surface s) {
        if (s != surface) return;
        surface = null;
        release();
    }

    synchronized void configure(int w, int h, byte[] sps, byte[] pps) {
        width = w;
        height = h;
        this.sps = sps;
        this.pps = pps;
        release();
        if (surface != null) start();
    }

    /** Satu access unit Annex-B. Dibuang bila codec belum siap, input penuh, atau masih menunggu keyframe. */
    synchronized void feed(byte[] data, int len, long ptsUs, boolean key) {
        stats(len);
        if (codec == null || (needKey && !key)) { dropped++; return; }
        try {
            int i = codec.dequeueInputBuffer(20_000);
            if (i < 0) { dropped++; needKey = true; return; }
            ByteBuffer in = codec.getInputBuffer(i);
            if (in == null || in.capacity() < len) { codec.queueInputBuffer(i, 0, 0, ptsUs, 0); dropped++; return; }
            in.clear();
            in.put(data, 0, len);
            codec.queueInputBuffer(i, 0, len, ptsUs, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            needKey = false;
            frames++;
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    synchronized void stop() {
        release();
        sps = pps = null;
    }

    private void start() {
        try {
            MediaFormat f = MediaFormat.createVideoFormat(MIME, width, height);
            f.setByteBuffer("csd-0", withStart(sps));
            f.setByteBuffer("csd-1", withStart(pps));
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height, 256 * 1024));
            f.setInteger(MediaFormat.KEY_PRIORITY, 0); // 0 = realtime
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            final MediaCodec c = MediaCodec.createDecoderByType(MIME);
            c.configure(f, surface, null, 0);
            c.start();
            codec = c;
            needKey = true;
            drain = new Thread(new Runnable() {
                @Override public void run() { drainLoop(c); }
            }, "KlikBeam-drain");
            drain.start();
            Log.i(TAG, "decoder " + c.getName() + " " + width + "x" + height);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void drainLoop(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                int i = c.dequeueOutputBuffer(info, 50_000);
                if (i >= 0) c.releaseOutputBuffer(i, true);
            } catch (IllegalStateException e) {
                return; // codec dilepas dari thread lain
            }
        }
    }

    /** stop() dulu supaya thread drain keluar dari dequeueOutputBuffer, baru release(). */
    private void release() {
        if (codec == null) return;
        drain.interrupt();
        try { codec.stop(); } catch (RuntimeException ignored) { }
        try { drain.join(500); } catch (InterruptedException ignored) { }
        codec.release();
        codec = null;
        drain = null;
    }

    private void fail(Exception e) {
        Log.e(TAG, "decoder", e);
        release();
        listener.onDecoderError(String.valueOf(e.getMessage()));
    }

    private void stats(int len) {
        bytes += len;
        long now = System.currentTimeMillis();
        if (statsAt == 0) statsAt = now;
        if (now - statsAt < 5000) return;
        float s = (now - statsAt) / 1000f;
        Log.i(TAG, String.format("%.1f fps, %d kbps, %d dibuang", frames / s, (int) (bytes * 8 / s / 1000), dropped));
        frames = dropped = 0;
        bytes = 0;
        statsAt = now;
    }

    private static ByteBuffer withStart(byte[] nal) {
        ByteBuffer b = ByteBuffer.allocate(START.length + nal.length);
        b.put(START).put(nal).flip();
        return b;
    }
}
