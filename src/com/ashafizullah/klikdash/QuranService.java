package com.ashafizullah.klikdash;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pemutar murattal per ayat (Mishary Rashid Alafasy) yang tetap jalan di latar.
 * File audio: <external files>/quran/audio/SSSAAA.mp3 (format everyayah.com).
 */
public class QuranService extends Service
        implements MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener {

    static final String ACTION_PLAY = "com.ashafizullah.klikdash.quran.PLAY", ACTION_TOGGLE = "com.ashafizullah.klikdash.quran.TOGGLE",
            ACTION_NEXT = "com.ashafizullah.klikdash.quran.NEXT", ACTION_PREV = "com.ashafizullah.klikdash.quran.PREV",
            ACTION_STOP = "com.ashafizullah.klikdash.quran.STOP", ACTION_PAUSE = "com.ashafizullah.klikdash.quran.PAUSE",
            ACTION_RESUME = "com.ashafizullah.klikdash.quran.RESUME", ACTION_DOWNLOAD = "com.ashafizullah.klikdash.quran.DOWNLOAD";

    /** LANJUT: terus ke surah berikutnya; SURAH/AYAT: ulangi. */
    static final int MODE_CONTINUE = 0, MODE_REPEAT_SURAH = 1, MODE_REPEAT_AYAH = 2;
    static final String RECITER = "Mishary Rashid Alafasy";
    /** Audio per ayat; APK tidak membawanya, diunduh saat diputar lalu disimpan untuk offline. */
    static final String AUDIO_URL = "https://everyayah.com/data/Alafasy_128kbps/%03d%03d.mp3";

    // Status dibaca langsung oleh UI (satu proses, thread utama).
    static int surah = 1, ayah = 1, mode = MODE_CONTINUE;
    /** ULANG AYAT/SURAH: berapa kali tiap ayat/surah diputar sebelum lanjut; 0 = terus diulang. */
    static int repeat;
    /** Berhenti setelah surah ini selesai diputar (0 = tidak, lanjut sampai An-Nas). */
    static int stopAfter;
    /** Sudah berapa kali ayat dan surah yang sedang diputar selesai; diulang dari nol tiap pengguna memilih. */
    static int ayahPlays, surahPlays;
    static boolean playing, active, bismillah, missing;
    /** buffering: menunggu file ayat yang sedang diputar selesai diunduh. dlSurah 0 = tidak ada unduhan. */
    static boolean buffering;
    static int dlSurah, dlDone, dlTotal;
    static final List<Runnable> listeners = new ArrayList<>();

    static File audioDir(Context c) { return new File(c.getExternalFilesDir(null), "quran/audio"); }

    static File audioFile(Context c, int s, int a) {
        return new File(audioDir(c), String.format(Locale.US, "%03d%03d.mp3", s, a));
    }

    /** Jumlah file ayat yang sudah tersimpan per surah (indeks 1..114). */
    static int[] downloaded(Context c) {
        int[] n = new int[QuranData.SURAH_COUNT + 1];
        String[] names = audioDir(c).list();
        if (names == null) return n;
        for (String f : names) {
            if (f.length() != 10 || !f.endsWith(".mp3")) continue;
            try {
                int s = Integer.parseInt(f.substring(0, 3)), a = Integer.parseInt(f.substring(3, 6));
                if (s >= 1 && s <= QuranData.SURAH_COUNT && a >= 1) n[s]++;
            } catch (NumberFormatException ignored) { }
        }
        return n;
    }

    /** Unduh seluruh audio surah {@code s} untuk dipakai offline. */
    static void download(Context c, int s) {
        Compat.startForegroundService(c, new Intent(c, QuranService.class).setAction(ACTION_DOWNLOAD).putExtra("s", s));
    }

    static void send(Context c, String action) {
        Compat.startForegroundService(c, new Intent(c, QuranService.class).setAction(action));
    }

    static void play(Context c, int s, int a) {
        Compat.startForegroundService(c, new Intent(c, QuranService.class).setAction(ACTION_PLAY)
                .putExtra("s", s).putExtra("a", a));
    }

    static void loadState(Context c) {
        SharedPreferences p = c.getSharedPreferences("quran", MODE_PRIVATE);
        surah = p.getInt("s", 1);
        ayah = p.getInt("a", 1);
        mode = p.getInt("mode", MODE_CONTINUE);
        repeat = p.getInt("repeat", 0);
        stopAfter = p.getInt("stopAfter", 0);
    }

    /** Mode, jumlah ulangan (0 = terus), dan surah terakhir (0 = tidak ada); berlaku mulai ayat yang sedang diputar. */
    static void setMode(Context c, int m, int times, int lastSurah) {
        mode = m;
        repeat = times;
        stopAfter = lastSurah;
        ayahPlays = surahPlays = 0;
        c.getSharedPreferences("quran", MODE_PRIVATE).edit().putInt("mode", m).putInt("repeat", times)
                .putInt("stopAfter", lastSurah).apply();
        notifyListeners();
    }

    /** Untuk tombol mode, notch, dan notifikasi: "LANJUT", "LANJUT → AN-NAS", "ULANG AYAT 3×", "ULANG SURAH ∞". */
    static String modeLabel() {
        if (mode == MODE_CONTINUE) {
            return stopAfter > 0 ? "LANJUT → " + surahName(stopAfter).toUpperCase(Locale.US) : "LANJUT";
        }
        return (mode == MODE_REPEAT_AYAH ? "ULANG AYAT " : "ULANG SURAH ") + (repeat == 0 ? "∞" : repeat + "×");
    }

    static String surahName(int s) {
        return QuranData.loaded() ? QuranData.get(s).latin : "Surah " + s;
    }

    static void notifyListeners() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    MediaPlayer player;
    MediaSession session;
    AudioManager am;
    Compat.Focus focus;
    NotificationManager nm;
    boolean resumeOnGain;
    final Handler main = new Handler(Looper.getMainLooper());
    /** Unduhan surah berurutan di satu thread; file yang sedang ditunggu pemutar diunduh terpisah. */
    final ExecutorService downloader = Executors.newSingleThreadExecutor();
    final Set<Integer> queued = new HashSet<>();
    File waitingFor;
    final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    @Override public void onCreate() {
        super.onCreate();
        loadState(this);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        focus = new Compat.Focus(this, AudioManager.AUDIOFOCUS_GAIN, attrs, this);

        session = new MediaSession(this, "klikdash-quran");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { userPause(); }
            @Override public void onStop() { userPause(); }
            @Override public void onSkipToNext() { step(1); }
            @Override public void onSkipToPrevious() { step(-1); }
        });
        session.setActive(true);
        active = true;

        // Nama surah untuk notifikasi; teks dimuat di latar.
        new Thread(() -> {
            try { QuranData.load(this); } catch (Exception ignored) { }
            main.post(this::publish);
        }, "quran-load").start();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        startForeground(1, notification());
        String a = i != null ? i.getAction() : null;
        if (ACTION_PLAY.equals(a) || ACTION_NEXT.equals(a) || ACTION_PREV.equals(a)) {
            ayahPlays = surahPlays = 0; // pilihan pengguna: hitungan ulang mulai lagi
            android.util.Log.i("KlikDashQuran", "DBG reset oleh " + a);
        }
        if (ACTION_PLAY.equals(a)) {
            start(i.getIntExtra("s", surah), i.getIntExtra("a", 1), true);
        } else if (ACTION_TOGGLE.equals(a)) {
            if (playing || resumeOnGain) userPause(); else resume();
        } else if (ACTION_PAUSE.equals(a)) {
            userPause();
        } else if (ACTION_RESUME.equals(a)) {
            resumeOnGain = true; // bila asisten sedang memegang audio, lanjut begitu ia selesai bicara
            resume();
        } else if (ACTION_NEXT.equals(a)) {
            step(1);
        } else if (ACTION_PREV.equals(a)) {
            step(-1);
        } else if (ACTION_STOP.equals(a)) {
            stopAll();
        } else if (ACTION_DOWNLOAD.equals(a)) {
            queueSurah(i.getIntExtra("s", surah), 1);
            publish();
        } else {
            publish();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        downloader.shutdownNow();
        release();
        focus.abandon();
        session.release();
        playing = false;
        active = false;
        notifyListeners();
        super.onDestroy();
    }

    // ------------------------------------------------------------ pemutaran

    /** Mulai dari ayat {@code a}; basmalah diputar dulu bila ayat 1 dan surah memilikinya. */
    void start(int s, int a, boolean withBismillah) {
        surah = s;
        ayah = Math.max(1, Math.min(a, QuranData.count(s)));
        bismillah = withBismillah && ayah == 1 && QuranData.hasBismillah(s);
        getSharedPreferences("quran", MODE_PRIVATE).edit().putInt("s", surah).putInt("a", ayah).apply();
        if (focus.request() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            playing = false;
            publish();
            return;
        }
        playCurrent();
    }

    void playCurrent() {
        final File f = bismillah ? audioFile(this, 1, 1) : audioFile(this, surah, ayah);
        release();
        missing = false;
        if (!f.exists()) {
            // Unduh ayat ini dulu (thread sendiri, tidak antre), sisa surah menyusul di latar.
            playing = false;
            buffering = true;
            waitingFor = f;
            final int s = bismillah ? 1 : surah, a = bismillah ? 1 : ayah;
            new Thread(() -> {
                boolean ok = fetchAudio(s, a);
                main.post(() -> onFetched(f, ok));
            }, "quran-fetch").start();
            queueSurah(surah, ayah);
            publish();
            return;
        }
        buffering = false;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(attrs);
            player.setDataSource(f.getPath());
            player.setOnCompletionListener(this);
            player.prepare();
            player.start();
            playing = true;
            android.util.Log.i("KlikDashQuran", "putar " + surah + ":" + (bismillah ? "basmalah" : ayah)
                    + " (" + modeLabel() + ")");
        } catch (Exception e) {
            release();
            playing = false;
        }
        publish();
    }

    @Override public void onCompletion(MediaPlayer mp) {
        // Sebagian MediaPlayer melapor selesai dua kali untuk satu file; yang kedua harus diabaikan, kalau tidak
        // hitungan ulang (ULANG SURAH 2×) terhitung lagi dan surah diputar sekali lebih banyak.
        if (mp != player) return;
        android.util.Log.i("KlikDashQuran", "DBG selesai " + surah + ":" + ayah + " b=" + bismillah + " ayahPlays=" + ayahPlays + " surahPlays=" + surahPlays + " mode=" + mode + " repeat=" + repeat + " stop=" + stopAfter);
        if (bismillah) {
            bismillah = false;
            playCurrent();
            return;
        }
        if (mode == MODE_REPEAT_AYAH && (repeat == 0 || ++ayahPlays < repeat)) {
            playCurrent();
            return;
        }
        ayahPlays = 0;
        if (ayah < QuranData.count(surah)) {
            start(surah, ayah + 1, false);
            return;
        }
        // Akhir surah.
        if (mode == MODE_REPEAT_SURAH && (repeat == 0 || ++surahPlays < repeat)) {
            start(surah, 1, true);
            return;
        }
        surahPlays = 0;
        if (surah == stopAfter || surah >= QuranData.SURAH_COUNT) {
            // Lepas player (bukan sekadar jeda): putar lagi mulai ulang ayat ini, dan player lama tidak bisa
            // melapor selesai lagi.
            release();
            playing = false;
            publish();
        } else {
            start(surah + 1, 1, true);
        }
    }

    void step(int d) {
        int s = surah, a = ayah + d;
        if (a < 1) {
            if (s > 1) { s--; a = QuranData.count(s); } else a = 1;
        } else if (a > QuranData.count(s)) {
            if (s < QuranData.SURAH_COUNT) { s++; a = 1; } else a = QuranData.count(s);
        }
        start(s, a, a == 1);
    }

    void resume() {
        if (player != null && !playing) {
            if (focus.request() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
            player.start();
            playing = true;
            publish();
        } else if (player == null) {
            start(surah, ayah, ayah == 1);
        }
    }

    /** Dijeda pengguna (atau asisten atas permintaannya): jangan lanjut sendiri saat audio fokus kembali. */
    void userPause() {
        resumeOnGain = false;
        pause();
    }

    void pause() {
        if (player != null && playing) player.pause();
        playing = false;
        waitingFor = null; // batalkan putar-setelah-unduh
        buffering = false;
        publish();
    }

    // ------------------------------------------------------------ unduh audio

    void onFetched(File f, boolean ok) {
        if (!f.equals(waitingFor)) return; // sudah dijeda atau pindah ayat
        waitingFor = null;
        buffering = false;
        if (ok) {
            playCurrent();
        } else {
            missing = true; // offline atau server gagal
            publish();
        }
    }

    /** Antrekan unduhan semua ayat surah {@code s}, dimulai dari ayat {@code from} lalu memutar ke awal. */
    void queueSurah(int s, int from) {
        if (!queued.add(s)) return;
        downloader.execute(() -> {
            int total = QuranData.count(s), done = 0;
            for (int k = 0; k < total; k++) {
                int a = (from - 1 + k) % total + 1;
                if (audioFile(this, s, a).exists() || fetchAudio(s, a)) done++;
                final int d = done;
                main.post(() -> {
                    dlSurah = s;
                    dlDone = d;
                    dlTotal = total;
                    if (missing && audioFile(this, surah, ayah).exists()) missing = false; // ayat ini sudah tiba
                    publish();
                });
                if (Thread.currentThread().isInterrupted()) return;
            }
            main.post(() -> { queued.remove(s); dlSurah = 0; publish(); });
        });
    }

    /** Unduh satu file ayat ke penyimpanan; true bila tersedia. Aman dipanggil dari beberapa thread. */
    boolean fetchAudio(int s, int a) {
        File dst = audioFile(this, s, a);
        if (dst.exists()) return true;
        File dir = dst.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(dst.getPath() + ".part" + Thread.currentThread().getId());
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(String.format(Locale.US, AUDIO_URL, s, a)).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "KlikDash");
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32768];
                for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
            }
            return tmp.renameTo(dst) || dst.exists();
        } catch (Exception e) {
            android.util.Log.w("KlikDash", "Unduh audio " + s + ":" + a + " gagal", e);
            return false;
        } finally {
            if (c != null) c.disconnect();
            tmp.delete();
        }
    }

    void stopAll() {
        release();
        playing = false;
        focus.abandon();
        stopForeground(true);
        stopSelf();
    }

    void release() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnGain = false;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                resumeOnGain = playing;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (player != null) player.setVolume(0.3f, 0.3f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (player != null) player.setVolume(1f, 1f);
                if (resumeOnGain) resume();
                resumeOnGain = false;
                break;
        }
    }

    // ------------------------------------------------------------ status & notifikasi

    String title() {
        String name = QuranData.loaded() ? QuranData.get(surah).latin : "Surah " + surah;
        return bismillah ? name + " · Basmalah" : name + " · Ayat " + ayah;
    }

    String status() {
        if (missing) return "Audio belum ada — sambungkan internet untuk mengunduh";
        if (buffering) return "Mengunduh audio…";
        if (dlSurah > 0) {
            String name = QuranData.loaded() ? QuranData.get(dlSurah).latin : "Surah " + dlSurah;
            return "Mengunduh " + name + " " + dlDone + "/" + dlTotal;
        }
        return mode == MODE_CONTINUE && stopAfter == 0 ? RECITER : RECITER + " · " + modeLabel();
    }

    void publish() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, 0, 1f)
                .build());
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, RECITER)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Al-Qur'an")
                .build());
        nm.notify(1, notification());
        if (!playing) stopForeground(false);
        notifyListeners();
    }

    Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, QuranActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0);
        return Compat.notification(this, "quran", "Murattal")
                .setSmallIcon(R.drawable.ic_quran)
                .setContentTitle(title())
                .setContentText(status())
                .setContentIntent(open)
                .setOngoing(playing)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Sebelumnya", pi(ACTION_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(null, playing ? "Jeda" : "Putar", pi(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(null, "Berikutnya", pi(ACTION_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    PendingIntent pi(String action, int code) {
        return PendingIntent.getService(this, code, new Intent(this, QuranService.class).setAction(action), 0);
    }
}
