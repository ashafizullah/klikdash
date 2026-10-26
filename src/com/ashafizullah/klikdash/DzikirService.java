package com.ashafizullah.klikdash;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pemutar dzikir pagi & petang yang tetap jalan di latar, untuk didengarkan sambil menyetir. Tiap bacaan diputar
 * sebanyak hitungannya dan tiap putaran yang selesai menambah penghitung di DzikirActivity (disimpan di prefs yang
 * sama). LANJUT: terus ke bacaan berikutnya yang belum selesai; SATU BACAAN: berhenti setelah bacaan ini.
 * Audio dari {@link DzikirAudio}; pola pemutarannya sama dengan {@link QuranService}.
 */
public class DzikirService extends Service
        implements MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener {

    static final String ACTION_PLAY = "com.ashafizullah.klikdash.dzikir.PLAY", ACTION_TOGGLE = "com.ashafizullah.klikdash.dzikir.TOGGLE",
            ACTION_NEXT = "com.ashafizullah.klikdash.dzikir.NEXT", ACTION_PREV = "com.ashafizullah.klikdash.dzikir.PREV",
            ACTION_STOP = "com.ashafizullah.klikdash.dzikir.STOP", ACTION_PAUSE = "com.ashafizullah.klikdash.dzikir.PAUSE",
            ACTION_RESUME = "com.ashafizullah.klikdash.dzikir.RESUME";
    static final String CHANNEL = "dzikir";
    static final int NOTIFICATION_ID = 4; // 1 = murattal, 2 = pengingat sholat, 3 = KlikBeam

    // Status dibaca langsung oleh UI (satu proses, thread utama).
    static String period = DzikirActivity.PAGI;
    /** Bacaan yang diputar (dari {@code total}), putaran yang sudah selesai, dan berapa putaran yang diputar kali ini. */
    static int index, total, rep, reps;
    static boolean playing, active, missing;
    /** SATU BACAAN: berhenti setelah bacaan yang dipilih selesai diputar. */
    static boolean single;
    static final List<Runnable> listeners = new ArrayList<>();

    static void notifyListeners() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    static void play(Context c, String p, int i) {
        Compat.startForegroundService(c, new Intent(c, DzikirService.class).setAction(ACTION_PLAY)
                .putExtra("period", p).putExtra("i", i));
    }

    static void send(Context c, String action) {
        Compat.startForegroundService(c, new Intent(c, DzikirService.class).setAction(action));
    }

    static void loadMode(Context c) { single = DzikirAudio.prefs(c).getBoolean("single", false); }

    static void setSingle(Context c, boolean v) {
        single = v;
        DzikirAudio.prefs(c).edit().putBoolean("single", v).apply();
        notifyListeners();
    }

    List<DzikirActivity.Item> items = new ArrayList<>();
    /** Potongan audio dalam satu putaran (mis. basmalah lalu ayat-ayat surat). */
    int clip;
    MediaPlayer player;
    MediaSession session;
    Compat.Focus focus;
    NotificationManager nm;
    boolean resumeOnGain, ducked;
    final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override public void onCreate() {
        super.onCreate();
        loadMode(this);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        focus = new Compat.Focus(this, AudioManager.AUDIOFOCUS_GAIN, attrs, this);
        session = new MediaSession(this, "klikdash-dzikir");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { userPause(); }
            @Override public void onStop() { userPause(); }
            @Override public void onSkipToNext() { step(1); }
            @Override public void onSkipToPrevious() { step(-1); }
        });
        session.setActive(true);
        active = true;
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        startForeground(NOTIFICATION_ID, notification());
        String a = i != null ? i.getAction() : null;
        if (ACTION_PLAY.equals(a)) {
            String p = i.getStringExtra("period");
            start(p != null ? p : period, i.getIntExtra("i", 0));
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
        } else {
            publish();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        release();
        focus.abandon();
        session.release();
        playing = false;
        active = false;
        notifyListeners();
        super.onDestroy();
    }

    // ------------------------------------------------------------ pemutaran

    /** Bacaan {@code i} dipilih pengguna: putar sisa hitungannya, atau sebanyak hitungan penuh bila sudah selesai. */
    void start(String p, int i) {
        period = p;
        items = DzikirActivity.items(this, p);
        total = items.size();
        if (items.isEmpty()) return;
        boolean granted = focus.request() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        select(Math.max(0, Math.min(i, items.size() - 1)));
        if (!granted) {
            playing = false;
            publish();
            return;
        }
        playCurrent();
    }

    void select(int i) {
        index = i;
        int left = items.get(i).count - DzikirActivity.loadCounts(this, period, items.size())[i];
        reps = left > 0 ? left : items.get(i).count;
        rep = 0;
        clip = 0;
    }

    /**
     * Player lama dibisukan dan baru dilepas sesudah yang baru mulai: jeda tanpa audio aktif membuat MCU headunit
     * NWD berpindah sumber audio dan memotong awal bacaan (lihat {@link QuranService#playCurrent}).
     */
    void playCurrent() {
        MediaPlayer old = player;
        player = null;
        if (old != null) old.setVolume(0f, 0f);
        String[] audio = items.get(index).audio;
        File f = audio.length > 0 ? DzikirAudio.file(this, audio[clip]) : null;
        missing = f == null || !f.exists();
        if (missing) {
            DzikirAudio.forget();
            playing = false;
        } else {
            try {
                player = new MediaPlayer();
                player.setAudioAttributes(attrs);
                player.setDataSource(f.getPath());
                player.setOnCompletionListener(this);
                player.prepare();
                if (ducked) player.setVolume(QuranService.DUCK, QuranService.DUCK);
                player.start();
                playing = true;
            } catch (Exception e) {
                android.util.Log.w("KlikDash", "Putar dzikir " + f + " gagal", e);
                release();
                playing = false;
                missing = true;
            }
        }
        if (old != null) old.release();
        publish();
    }

    @Override public void onCompletion(MediaPlayer mp) {
        if (mp != player) return; // sebagian MediaPlayer melapor selesai dua kali
        if (++clip < items.get(index).audio.length) {
            playCurrent();
            return;
        }
        clip = 0;
        rep++;
        count();
        if (rep < reps) {
            playCurrent();
            return;
        }
        int next = single ? -1 : nextUndone();
        if (next < 0) {
            // Lepas player (bukan sekadar jeda): putar lagi mulai dari bacaan yang tampil.
            release();
            playing = false;
            publish();
        } else {
            select(next);
            playCurrent();
        }
    }

    /** Satu putaran selesai: tambah hitungan bacaan ini (dibaca ulang dari prefs, bisa juga diketuk di layar). */
    void count() {
        int[] c = DzikirActivity.loadCounts(this, period, items.size());
        if (c[index] < items.get(index).count) {
            c[index]++;
            DzikirActivity.saveCounts(this, period, c);
        }
    }

    int nextUndone() {
        int[] c = DzikirActivity.loadCounts(this, period, items.size());
        for (int i = index + 1; i < items.size(); i++) if (c[i] < items.get(i).count) return i;
        return -1;
    }

    void step(int d) {
        if (items.isEmpty()) items = DzikirActivity.items(this, period);
        start(period, index + d);
    }

    void resume() {
        if (player != null && !playing) {
            if (focus.request() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
            player.start();
            playing = true;
            publish();
        } else if (player == null) {
            start(period, index);
        }
    }

    void userPause() {
        resumeOnGain = false;
        pause();
    }

    void pause() {
        if (player != null && playing) player.pause();
        playing = false;
        publish();
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
                ducked = true;
                if (player != null) player.setVolume(QuranService.DUCK, QuranService.DUCK);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                ducked = false;
                if (player != null) player.setVolume(1f, 1f);
                if (resumeOnGain) resume();
                resumeOnGain = false;
                break;
        }
    }

    // ------------------------------------------------------------ status & notifikasi

    String title() {
        Context r = QuranService.res(this);
        String head = r.getString(DzikirActivity.PAGI.equals(period) ? R.string.dzikir_n_pagi : R.string.dzikir_n_petang);
        if (items.isEmpty()) return head;
        return head + String.format(Locale.US, " · %02d/%02d · ", index + 1, items.size()) + items.get(index).title;
    }

    String status() {
        Context r = QuranService.res(this);
        if (missing) return r.getString(R.string.dzikir_n_missing);
        if (items.isEmpty()) return "";
        return r.getString(R.string.dzikir_n_rep, Math.min(rep + 1, reps), reps)
                + (single ? " · " + r.getString(R.string.dzikir_mode_single) : "");
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
                .putString(MediaMetadata.METADATA_KEY_ARTIST, status())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, QuranService.res(this).getString(R.string.app_dzikir))
                .build());
        nm.notify(NOTIFICATION_ID, notification());
        if (!playing) stopForeground(false);
        notifyListeners();
    }

    Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, DzikirActivity.class)
                .putExtra("period", period).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT);
        Context r = QuranService.res(this);
        return Compat.notification(this, CHANNEL, r.getString(R.string.channel_dzikir))
                .setSmallIcon(R.drawable.ic_dzikir_tile)
                .setContentTitle(title())
                .setContentText(status())
                .setContentIntent(open)
                .setOngoing(playing)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, r.getString(R.string.quran_n_prev), pi(ACTION_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(null,
                        r.getString(playing ? R.string.quran_n_pause : R.string.quran_n_play), pi(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(null, r.getString(R.string.quran_n_next), pi(ACTION_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    PendingIntent pi(String action, int code) {
        return PendingIntent.getService(this, code, new Intent(this, DzikirService.class).setAction(action), 0);
    }
}
