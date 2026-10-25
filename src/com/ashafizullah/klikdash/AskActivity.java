package com.ashafizullah.klikdash;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.BG;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.FAINT;
import static com.ashafizullah.klikdash.HomeActivity.INK;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Ubin TANYA: dengar perintah, jalankan lewat AI + alat mobil (AiTools), bacakan jawabannya.
 * Satu sesi = satu percakapan, jadi pertanyaan lanjutan ("yang kedua saja") tetap nyambung. Teks layar mengikuti
 * bahasa antarmuka; yang didengar dan diucapkan mengikuti bahasa asisten ({@link AiStore#lang}).
 */
public class AskActivity extends Activity {
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final int IDLE = 0, LISTEN = 1, THINK = 2, SPEAK = 3;

    float dp, sp;
    Typeface fHeavy, fBold, fMono, fMonoBold, fSerif;
    AiStore.Profile profile;
    AiClient client;
    AiVoice voice;
    SpeechRecognizer recognizer;
    boolean androidStt;
    /** Uji pengenal suara saja (build debuggable, DEBUGGABLE=1 ./build.sh, --ez stt true): ditampilkan, tidak dikirim ke AI. */
    boolean sttOnly;

    int state = IDLE;
    Mic mic;
    TextView status, heard, answer, who;
    /** Belum ada profil AI (atau API key-nya kosong): mikrofon dan tombol membuka Pengaturan › Asisten AI. */
    boolean needsSetup;
    /** Isi halaman bisa digulir: jawaban panjang tidak terpotong. */
    android.widget.ScrollView scroll;
    /** Ukuran huruf jawaban yang pendek; jawaban panjang dikecilkan (lihat showAnswer). */
    float answerSp;
    Key again;
    boolean alive;

    int themeBuilt;

    @Override protected void onResume() {
        super.onResume();
        Theme.stale(this, themeBuilt);
    }

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        themeBuilt = Theme.apply(this);
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        sp = getResources().getDisplayMetrics().scaledDensity;
        fHeavy = font("display.ttf", "'opsz' 72, 'wght' 800");
        fBold = font("display.ttf", "'opsz' 36, 'wght' 700");
        fMono = font("mono.ttf", "'wdth' 100, 'wght' 400");
        fMonoBold = font("mono.ttf", "'wdth' 100, 'wght' 700");
        fSerif = font("serif.ttf", null);
        voice = new AiVoice(this);
        alive = true;
        // Nyalakan TTS Android sekarang, selagi pengemudi bicara dan AI berpikir, bukan sesudah jawabannya datang.
        if (AiVoice.answersWithAndroid(this)) AiVoice.warm(this);

        float wDp = getResources().getDisplayMetrics().widthPixels / dp;
        float hDp = getResources().getDisplayMetrics().heightPixels / dp;
        boolean portrait = hDp > wDp;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        root.setPadding(px(24), px(24), px(24), px(24));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(getString(R.string.ask_title), fHeavy, wDp < 700 ? 30 : 40, TEXT));
        who = text("", fMono, 12, DIM);
        who.setLetterSpacing(0.2f);
        who.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        wl.leftMargin = px(20);
        head.addView(who, wl);
        head.addView(key(wDp < 900 ? "✕" : getString(R.string.ask_close), false, this::finish), new LinearLayout.LayoutParams(WRAP, px(56)));
        root.addView(head, new LinearLayout.LayoutParams(MATCH, px(64)));
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(MATCH, px(1));
        rl.topMargin = px(16);
        root.addView(rule, rl);

        // Semua ditumpuk di tengah: mikrofon, status, ucapan (transkripsi), jawaban, tombol.
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        mic = new Mic();
        mic.setOnClickListener(v -> tapMic());
        int micSize = px(Math.min(260, Math.min(wDp * 0.5f, (hDp - 140) * 0.42f)));
        body.addView(mic, new LinearLayout.LayoutParams(micSize, micSize));

        int textW = Math.min(px(900), getResources().getDisplayMetrics().widthPixels - px(48));
        status = text("", fMonoBold, 14, AMBER);
        status.setLetterSpacing(0.25f);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(textW, WRAP);
        sl.topMargin = px(20);
        body.addView(status, sl);
        heard = text("", fSerif, 20, DIM);
        heard.setSingleLine(false);
        heard.setMaxLines(2);
        heard.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heard.setLineSpacing(0, 1.2f);
        heard.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(textW, WRAP);
        hl.topMargin = px(12);
        body.addView(heard, hl);
        answerSp = portrait || wDp < 900 ? 24 : 30;
        answer = text("", fBold, answerSp, TEXT);
        answer.setSingleLine(false);
        answer.setLineSpacing(0, 1.15f);
        answer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(textW, WRAP);
        al.topMargin = px(12);
        body.addView(answer, al);
        again = key(getString(R.string.ask_again), true, this::listen);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(WRAP, px(52));
        gl.topMargin = px(16);
        body.addView(again, gl);
        body.setPadding(0, px(8), 0, px(16));
        // Jawaban pendek tetap di tengah layar (fillViewport); yang panjang bisa digulir, tidak terpotong.
        scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(body, new android.widget.ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setContentView(root);

        start();
    }

    /** Cek profil & izin mikrofon, lalu langsung mendengarkan. */
    void start() {
        // Uji pengenal suara tanpa profil AI: adb shell am start -n com.ashafizullah.klikdash/.AskActivity --ez stt true
        boolean dev = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (dev && getIntent().getBooleanExtra("stt", false)) {
            getIntent().removeExtra("stt");
            sttOnly = true;
            again.setVisibility(View.INVISIBLE);
            setState(LISTEN, getString(R.string.ask_listening));
            listenAndroid();
            return;
        }
        profile = AiStore.active(this);
        needsSetup = profile == null || profile.apiKey.isEmpty();
        if (needsSetup) {
            who.setText("");
            setState(IDLE, getString(R.string.ask_no_profile));
            heard.setText(R.string.ask_no_profile_hint);
            again.setLabel(getString(R.string.ask_setup));
            again.setOnClickListener(v -> openAiSettings());
            again.setVisibility(View.VISIBLE);
            return;
        }
        if (client == null || !client.matches(this, profile)) client = new AiClient(this, profile);
        who.setText(info());
        again.setLabel(getString(R.string.ask_again));
        again.setOnClickListener(v -> listen());
        // Uji tanpa mikrofon: adb shell am start -n com.ashafizullah.klikdash/.AskActivity --es q "putar surat Yasin"
        // Hanya di build debuggable (DEBUGGABLE=1 ./build.sh install): TANYA bisa dibuka aplikasi lain, jadi di APK rilis aplikasi lain
        // tidak boleh menitipkan perintah teks untuk dijalankan AI.
        String typed = dev ? getIntent().getStringExtra("q") : null;
        if (typed != null && !typed.trim().isEmpty()) {
            getIntent().removeExtra("q");
            ask(typed.trim());
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        listen();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) listen();
        else setState(IDLE, getString(R.string.ask_mic_denied));
    }

    @Override protected void onRestart() {
        super.onRestart();
        if (needsSetup) { start(); return; } // kembali dari Pengaturan
        // Pengaturan AI mungkin diubah selagi TANYA di latar: perbarui sesi dan baris info, tanpa langsung mendengar.
        if (state == IDLE && profile != null) {
            AiStore.Profile p = AiStore.active(this);
            if (p == null || p.apiKey.isEmpty()) { start(); return; }
            profile = p;
            if (!client.matches(this, p)) client = new AiClient(this, p);
            who.setText(info());
        }
    }

    void openAiSettings() {
        startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_TAB, SettingsActivity.TAB_AI));
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (state == IDLE) start(); // ubin TANYA diketuk lagi
    }

    @Override protected void onDestroy() {
        alive = false;
        if (recognizer != null) recognizer.destroy();
        voice.release();
        super.onDestroy();
    }

    void tapMic() {
        if (needsSetup) { openAiSettings(); return; }
        if (state == LISTEN) {
            if (androidStt && recognizer != null) recognizer.stopListening();
            else voice.finishListening();
            return;
        }
        if (state == SPEAK) voice.stop();
        if (state != THINK) start();
    }

    // ---------------------------------------------------------------- dengar

    /**
     * Baris info di samping judul: model, suara jawaban, pengenal suara, dan pencarian web yang dipakai sesi ini. Tanpa
     * nama profil: nama buatan pengguna (mis. "Claude X") membingungkan di layar ini.
     */
    String info() {
        String elKey = AiStore.elKey(this);
        boolean elTts = AiStore.elTts(this) && !elKey.isEmpty() && !AiStore.elVoice(this).isEmpty();
        boolean elStt = !SpeechRecognizer.isRecognitionAvailable(this) || AiStore.elStt(this) && !elKey.isEmpty();
        String s = (profile.model.isEmpty() ? AiStore.CLAUDE_MODEL : profile.model)
                + "  ·  " + getString(R.string.ask_info_voice) + " " + (elTts ? "ELEVENLABS" : "ANDROID")
                + "  ·  " + getString(R.string.ask_info_listen) + " " + (elStt ? "ELEVENLABS" : "ANDROID");
        if (client.search == AiClient.TINYFISH_SEARCH) s += "  ·  " + getString(R.string.ask_info_search) + " TINYFISH";
        else if (client.search == AiClient.ANTHROPIC_SEARCH) s += "  ·  " + getString(R.string.ask_info_search) + " ANTHROPIC";
        return s.toUpperCase(Lang.locale());
    }

    /** Jawaban panjang: huruf lebih kecil, dan halaman digulir supaya status dan awal jawaban terlihat. */
    void showAnswer(String r) {
        answer.setTextSize(TypedValue.COMPLEX_UNIT_SP, r.length() > 220 ? answerSp * 0.7f
                : r.length() > 120 ? answerSp * 0.82f : answerSp);
        answer.setText(r);
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, status.getTop() - px(8))));
    }

    void listen() {
        if (profile == null) { start(); return; }
        voice.stop();
        heard.setText("");
        answer.setText("");
        scroll.scrollTo(0, 0);
        again.setVisibility(View.INVISIBLE);
        // Pilihan di Pengaturan › Asisten AI; ElevenLabs tanpa API key → tetap Android.
        androidStt = SpeechRecognizer.isRecognitionAvailable(this)
                && (!AiStore.elStt(this) || AiStore.elKey(this).isEmpty());
        voice.hush();
        setState(LISTEN, getString(R.string.ask_listening));
        if (androidStt) listenAndroid(); else listenEleven();
    }

    void listenAndroid() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this, AiVoice.recognizer(this));
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rms) { mic.level(Math.max(0, Math.min(1, (rms + 2) / 12f))); }
                @Override public void onBufferReceived(byte[] buf) { }
                @Override public void onEndOfSpeech() { mic.level(0); }
                @Override public void onEvent(int t, Bundle p) { }

                @Override public void onPartialResults(Bundle r) {
                    String s = first(r);
                    if (!s.isEmpty()) heard.setText("“" + s + "”");
                }

                @Override public void onResults(Bundle r) {
                    String s = first(r);
                    android.util.Log.i("KlikDashAI", "STT Android: " + s.length() + " huruf");
                    if (s.isEmpty()) idle(getString(R.string.ask_not_heard), notHeardHint());
                    else if (sttOnly) idle(getString(R.string.ask_heard), "“" + s + "”");
                    else ask(s);
                }

                @Override public void onError(int e) {
                    mic.level(0);
                    android.util.Log.i("KlikDashAI", "STT Android error " + e);
                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        idle(getString(R.string.ask_not_heard), notHeardHint());
                    } else if (!AiStore.elKey(AskActivity.this).isEmpty()) {
                        androidStt = false; // pengenal Android bermasalah (mis. tanpa Google): pakai ElevenLabs
                        listenEleven();
                    } else {
                        idle(getString(R.string.ask_stt_failed), getString(R.string.ask_stt_error, e));
                    }
                }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, AiStore.speechTag(this))
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recognizer.startListening(i);
    }

    /** Contoh perintah dalam bahasa asisten (yang harus diucapkan), di dalam kalimat berbahasa antarmuka. */
    String notHeardHint() { return getString(R.string.ask_not_heard_hint, AiStore.inLang(this).getString(R.string.as_example)); }

    static String first(Bundle r) {
        ArrayList<String> l = r != null ? r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
        return l == null || l.isEmpty() || l.get(0) == null ? "" : l.get(0).trim();
    }

    void listenEleven() {
        setState(LISTEN, getString(R.string.ask_listening));
        voice.listenEleven(new AiVoice.Heard() {
            @Override public void onText(String text) { ask(text); }
            @Override public void onError(String message) { idle(getString(R.string.ask_not_heard), message); }
            @Override public void onLevel(float l) { mic.level(l); }
            @Override public void onTranscribing() {
                mic.level(0);
                setState(THINK, getString(R.string.ask_recognizing));
            }
        });
        // Saat merekam, tampilkan bahwa transkripsi berjalan setelah hening.
    }

    // ---------------------------------------------------------------- tanya & jawab

    void ask(String text) {
        heard.setText("“" + text + "”");
        answer.setText("");
        again.setVisibility(View.INVISIBLE);
        setState(THINK, getString(R.string.ask_processing));
        AiClient c = client;
        Context talk = c.inLang;
        new Thread(() -> {
            String reply, spoken;
            boolean failed = false;
            try {
                reply = spoken = c.ask(text);
                if (reply.isEmpty()) reply = spoken = talk.getString(R.string.as_done);
            } catch (Exception e) {
                failed = true;
                // Pesan error lengkap dibaca di layar (bahasa antarmuka); yang diucapkan intinya saja (bahasa asisten).
                reply = AiClient.describe(this, e);
                spoken = talk.getString(R.string.as_sorry, AiClient.head(talk, e).split(":")[0]);
                client = new AiClient(this, profile); // riwayat mungkin setengah jadi: mulai baru
            }
            final String r = reply, say = spoken;
            final boolean f = failed;
            runOnUiThread(() -> {
                if (!alive) return;
                showAnswer(r);
                setState(SPEAK, getString(f ? R.string.ask_failed : R.string.ask_answering));
                voice.say(say, () -> {
                    if (alive && state == SPEAK) idle(getString(f ? R.string.ask_failed : R.string.ask_done), null);
                });
            });
        }, "ai-ask").start();
    }

    void idle(String s, String hint) {
        voice.unhush();
        mic.level(0);
        setState(IDLE, s);
        if (hint != null) heard.setText(hint);
        again.setVisibility(View.VISIBLE);
    }

    void setState(int st, String label) {
        state = st;
        status.setText(label);
        mic.invalidate();
    }

    // ---------------------------------------------------------------- helpers

    Key key(String label, boolean on, Runnable click) { return new Key(this, fMonoBold, label, on, click); }

    Typeface font(String file, String axes) { return Compat.font(getAssets(), file, axes, Typeface.DEFAULT); }

    TextView text(String s, Typeface tf, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    int px(float v) { return Math.round(v * dp); }

    /** Lingkaran mikrofon: isi amber mengikuti keras suara saat mendengar; ketuk untuk mulai/berhenti. */
    class Mic extends View {
        float lvl;
        boolean down;
        final android.graphics.Path clip = new android.graphics.Path();
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Mic() {
            super(AskActivity.this);
            setClickable(true);
            setContentDescription(getString(R.string.ask_mic));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(px(3));
            p.setTypeface(fMonoBold);
            p.setTextSize(13 * sp);
            p.setLetterSpacing(0.25f);
            p.setTextAlign(Paint.Align.CENTER);
        }

        void level(float l) {
            if (Math.abs(l - lvl) < 0.04f && l != 0) return;
            lvl = l;
            invalidate();
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (isPressed() != down) { down = isPressed(); invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - px(4);
            fill.setColor(down ? TEXT : PANEL);
            c.drawCircle(cx, cy, r, fill);
            ring.setColor(state == LISTEN ? AMBER : state == IDLE ? LINE : FAINT);
            c.drawCircle(cx, cy, r, ring);
            if (state == LISTEN) {
                fill.setColor(AMBER);
                c.drawCircle(cx, cy, r * (0.28f + 0.6f * lvl), fill);
            }
            // Ikon + keterangan sebagai satu kelompok di tengah lingkaran.
            String hint = needsSetup ? getString(R.string.ask_tap_setup) : state == LISTEN ? getString(R.string.ask_tap_finish)
                    : state == THINK ? "" : getString(R.string.ask_tap_talk);
            float s = r * 0.22f;
            float iy = hint.isEmpty() ? cy : cy - r * 0.12f; // pusat ikon
            fill.setColor(state == LISTEN || down ? INK : state == THINK ? DIM : TEXT);
            c.drawRoundRect(cx - s * 0.55f, iy - s * 1.3f, cx + s * 0.55f, iy + s * 0.4f, s * 0.55f, s * 0.55f, fill);
            ring.setColor(fill.getColor());
            ring.setStrokeWidth(px(3));
            c.drawArc(cx - s, iy - s * 0.9f, cx + s, iy + s * 0.9f, 0, 180, false, ring);
            c.drawLine(cx, iy + s * 0.9f, cx, iy + s * 1.4f, ring);
            if (!hint.isEmpty()) {
                // letterSpacing menambah jarak di ujung kanan; geser setengahnya supaya benar-benar di tengah.
                p.setTextSize(13 * sp);
                p.setTextSize(Math.min(13 * sp, 13 * sp * r * 1.5f / Math.max(1, p.measureText(hint))));
                float extra = p.getLetterSpacing() * p.getTextSize() / 2f, ty = iy + s * 1.4f + px(28);
                p.setColor(state == LISTEN ? AMBER : DIM);
                c.drawText(hint, cx - extra, ty, p);
                // Saat mendengar, isi amber membesar mengikuti suara: bagian teks yang tertutup ditulis gelap.
                if (state == LISTEN) {
                    clip.reset();
                    clip.addCircle(cx, cy, r * (0.28f + 0.6f * lvl), android.graphics.Path.Direction.CW);
                    c.save();
                    c.clipPath(clip);
                    p.setColor(INK);
                    c.drawText(hint, cx - extra, ty, p);
                    c.restore();
                }
            }
        }
    }
}
