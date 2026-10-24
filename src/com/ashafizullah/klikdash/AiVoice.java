package com.ashafizullah.klikdash;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Suara asisten. Bicara: ElevenLabs bila key & voice terisi (dan dipilih), selain itu TTS Android (id-ID).
 * Dengar (cadangan bila pengenal suara Android tidak ada): rekam AAC, berhenti saat hening, kirim ke ElevenLabs Scribe.
 */
final class AiVoice {
    static final String EL_API = "https://api.elevenlabs.io/v1";

    final Context ctx;
    final Handler main = new Handler(Looper.getMainLooper());
    MediaPlayer player;
    android.media.audiofx.LoudnessEnhancer boost;
    TextToSpeech tts;
    boolean ttsReady;
    String pendingSay;
    Runnable pendingDone;

    AiVoice(Context c) { ctx = c.getApplicationContext(); }

    // Selama mendengar & menjawab: musik/murattal berhenti sementara, lanjut lagi setelahnya.
    Compat.Focus focus;

    void hush() {
        if (focus != null) return;
        focus = new Compat.Focus(ctx, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(), null);
        focus.request();
    }

    void unhush() {
        if (focus == null) return;
        focus.abandon();
        focus = null;
    }

    /** Ada mesin TTS terpasang? Headunit NWD umumnya tidak punya (hanya suara TXZ milik NWD). */
    static boolean hasAndroidTts(Context c) {
        return !c.getPackageManager().queryIntentServices(
                new android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isEmpty();
    }

    // ---------------------------------------------------------------- bicara

    /** {@code done} dipanggil di thread utama setelah selesai (atau gagal). */
    void say(String text, Runnable done) {
        stop();
        if (text == null || text.trim().isEmpty()) { post(done); return; }
        String key = AiStore.elKey(ctx), voice = AiStore.elVoice(ctx);
        if (AiStore.elTts(ctx) && !key.isEmpty() && !voice.isEmpty()) {
            new Thread(() -> {
                try {
                    long t0 = android.os.SystemClock.elapsedRealtime();
                    File f = elevenTts(text, key, voice, AiStore.elModel(ctx));
                    android.util.Log.i("KlikDashAI", "TTS " + voice + " " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
                    main.post(() -> play(f, done));
                } catch (Exception e) {
                    android.util.Log.w("KlikDashAI", "TTS gagal: " + e.getMessage());
                    main.post(() -> androidSay(text, done)); // kuota habis / offline: tetap bersuara
                }
            }, "el-tts").start();
        } else {
            androidSay(text, done);
        }
    }

    File elevenTts(String text, String key, String voice, String model) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(EL_API + "/text-to-speech/" + voice
                + "?output_format=mp3_44100_128").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("xi-api-key", key);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "audio/mpeg");
        JSONObject body = new JSONObject().put("text", text).put("model_id", model);
        if (model.contains("flash") || model.contains("turbo")) body.put("language_code", "id");
        try {
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException(AiClient.errorMessage(code, AiClient.read(c.getErrorStream())));
            File f = new File(ctx.getCacheDir(), "ai-say.mp3");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[16384];
                for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
            }
            return f;
        } finally {
            c.disconnect();
        }
    }

    void play(File f, Runnable done) {
        try {
            player = new MediaPlayer();
            // Jalur & volume yang sama dengan murattal (USAGE_MEDIA), supaya satu tombol volume mengatur keduanya.
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(f.getPath());
            player.setOnCompletionListener(mp -> { stop(); post(done); });
            player.setOnErrorListener((mp, w, x) -> { stop(); post(done); return true; });
            player.prepare();
            // ElevenLabs ±-25 LUFS, murattal ±-17 LUFS: naikkan ±8 dB (penguat Android sudah membatasi puncak).
            try {
                boost = new android.media.audiofx.LoudnessEnhancer(player.getAudioSessionId());
                boost.setTargetGain(800);
                boost.setEnabled(true);
            } catch (Exception ignored) { }
            player.start();
        } catch (Exception e) {
            stop();
            post(done);
        }
    }

    void androidSay(String text, Runnable done) {
        if (tts == null) {
            pendingSay = text;
            pendingDone = done;
            tts = new TextToSpeech(ctx, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) tts.setLanguage(new Locale("id", "ID"));
                String s = pendingSay;
                Runnable d = pendingDone;
                pendingSay = null;
                pendingDone = null;
                if (s != null) speakNow(s, d);
            });
            return;
        }
        speakNow(text, done);
    }

    void speakNow(String text, Runnable done) {
        if (!ttsReady) { post(done); return; }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { post(done); }
            @Override public void onError(String id) { post(done); }
        });
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai");
    }

    void stop() {
        if (boost != null) {
            try { boost.release(); } catch (Exception ignored) { }
            boost = null;
        }
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (tts != null && ttsReady) tts.stop();
    }

    void release() {
        stop();
        unhush();
        if (tts != null) tts.shutdown();
        tts = null;
        cancelRecording();
    }

    void post(Runnable r) { if (r != null) main.post(r); }

    // ---------------------------------------------------------------- dengar (cadangan ElevenLabs)

    interface Heard {
        void onText(String text);
        void onError(String message);
        default void onLevel(float level) { }
        /** Rekaman selesai, sedang dikirim ke ElevenLabs. */
        default void onTranscribing() { }
    }

    MediaRecorder rec;
    File recFile;
    long recStart, lastLoud;
    boolean heardSpeech;

    /** Rekam sampai hening 1,5 detik setelah ada suara (maks. 12 detik), lalu transkripsi. */
    void listenEleven(Heard cb) {
        String key = AiStore.elKey(ctx);
        if (key.isEmpty()) { cb.onError("Pengenal suara Android tidak tersedia. Isi API key ElevenLabs di Pengaturan."); return; }
        try {
            recFile = new File(ctx.getCacheDir(), "ai-heard.m4a");
            rec = new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioChannels(1);
            rec.setAudioSamplingRate(16000);
            rec.setAudioEncodingBitRate(48000);
            rec.setOutputFile(recFile.getPath());
            rec.prepare();
            rec.start();
        } catch (Exception e) {
            cancelRecording();
            cb.onError("Mikrofon tidak bisa dipakai");
            return;
        }
        recStart = lastLoud = System.currentTimeMillis();
        heardSpeech = false;
        stopNow = false;
        noise = 0;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (rec == null) return;
                long now = System.currentTimeMillis();
                int amp = rec.getMaxAmplitude();
                // Lantai bising (mesin, AC, jalan) diukur 0,4 detik pertama; ucapan = jauh di atasnya.
                if (now - recStart < 400) noise = Math.max(noise, amp);
                int loud = Math.max(2500, noise * 2);
                if (amp > loud) { lastLoud = now; heardSpeech = true; }
                cb.onLevel(Math.min(1f, amp / (float) (loud * 3)));
                boolean tapped = stopNow && now - recStart > 500;
                if (tapped) heardSpeech = true; // pengemudi menekan selesai: kirim apa adanya
                boolean quietAfterSpeech = heardSpeech && now - lastLoud > 1200;
                boolean nothing = !heardSpeech && now - recStart > 6000;
                if (tapped || quietAfterSpeech || nothing || now - recStart > 12000) {
                    if (!finishRecording()) { cb.onError("Rekaman gagal"); return; }
                    if (!heardSpeech) { cb.onError("Tidak terdengar suara"); return; }
                    cb.onTranscribing();
                    transcribe(key, cb);
                    return;
                }
                main.postDelayed(this, 100);
            }
        }, 100);
    }

    boolean stopNow;
    int noise;

    /** Ketuk mikrofon saat merekam: selesai sekarang, tanpa menunggu hening. */
    void finishListening() { stopNow = true; }

    boolean finishRecording() {
        if (rec == null) return false;
        boolean ok = true;
        try { rec.stop(); } catch (Exception e) { ok = false; } // stop() melempar bila belum ada data
        rec.release();
        rec = null;
        return ok;
    }

    void cancelRecording() {
        if (rec == null) return;
        try { rec.stop(); } catch (Exception ignored) { }
        rec.release();
        rec = null;
    }

    void transcribe(String key, Heard cb) {
        File f = recFile;
        new Thread(() -> {
            try {
                long t0 = android.os.SystemClock.elapsedRealtime();
                String text = elevenStt(f, key);
                android.util.Log.i("KlikDashAI", "STT " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms: " + text.length() + " huruf");
                main.post(() -> {
                    if (text.isEmpty()) cb.onError("Tidak terdengar jelas"); else cb.onText(text);
                });
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage() != null ? e.getMessage() : "Transkripsi gagal"));
            }
        }, "el-stt").start();
    }

    static String elevenStt(File audio, String key) throws Exception {
        String b = "----klikdash" + System.nanoTime();
        HttpURLConnection c = (HttpURLConnection) new URL(EL_API + "/speech-to-text").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("xi-api-key", key);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, b, "model_id", AiStore.EL_STT_MODEL);
        part(body, b, "language_code", "id");
        part(body, b, "tag_audio_events", "false"); // jangan tulis "(musik)", "(tawa)" sebagai ucapan
        body.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"heard.m4a\"\r\n"
                + "Content-Type: audio/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = new FileInputStream(audio)) {
            byte[] buf = new byte[16384];
            for (int k; (k = in.read(buf)) > 0; ) body.write(buf, 0, k);
        }
        body.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        try {
            try (OutputStream o = c.getOutputStream()) { body.writeTo(o); }
            int code = c.getResponseCode();
            String txt = AiClient.read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new IOException(AiClient.errorMessage(code, txt));
            // Sisa penanda suara latar seperti "[musik]" bukan perintah.
            return new JSONObject(txt).optString("text").replaceAll("[\\[(][^\\])]*[\\])]", "").trim();
        } finally {
            c.disconnect();
        }
    }

    static void part(ByteArrayOutputStream out, String b, String name, String value) throws IOException {
        out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }
}
