package com.ashafizullah.klikdash;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.List;

/**
 * Musik aplikasi lain (Spotify, YouTube Music, ...) untuk notch home ({@link MediaNotch}), lewat MediaSession mereka.
 * Android hanya memberi daftar sesi media ke aplikasi yang punya akses notifikasi; layanan ini ada hanya untuk itu
 * (notifikasinya sendiri tidak dibaca). Aksesnya diberikan lewat ADB lokal ({@link Tweaks}), seperti izin jendela
 * melayang; tanpa ADB notch tetap jalan untuk murattal dan dzikir. Aplikasi yang tidak memakai MediaSession (mis.
 * sebagian pemutar bawaan headunit) tidak terlihat.
 */
public class MediaListener extends NotificationListenerService {

    static final List<Runnable> listeners = new ArrayList<>();
    /** Sesi yang ditampilkan: yang sedang berbunyi, kalau tidak ada, yang paling baru aktif. */
    static MediaController current;

    private static Context app;
    private static MediaSessionManager msm;
    private static boolean granting;
    private static final MediaSessionManager.OnActiveSessionsChangedListener SESSIONS = MediaListener::watch;
    private static final List<MediaController> watched = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final MediaController.Callback CALLBACK = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { pick(); }
        @Override public void onMetadataChanged(MediaMetadata m) { notifyListeners(); }
        @Override public void onSessionDestroyed() { pick(); }
    };

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        start(this); // akses baru diberikan (atau layanan disambung lagi setelah boot)
    }

    /** Akses dicabut: sistem memutus pemantauan sesi; mulai lagi dari awal saat akses diberikan kembali. */
    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (msm != null) msm.removeOnActiveSessionsChangedListener(SESSIONS);
        msm = null;
        watch(null);
    }

    static void notifyListeners() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    /**
     * Mulai memantau sesi media; dipanggil home. Belum punya akses notifikasi: minta lewat ADB lokal, lalu sistem
     * menyambung layanan ini dan {@link #onListenerConnected} memanggil ini lagi. Hanya sekali sejak dipasang: bila
     * pengguna mencabut aksesnya di Pengaturan Android, KlikDash tidak memintanya lagi.
     */
    static void start(Context c) {
        app = c.getApplicationContext();
        if (msm != null) return;
        MediaSessionManager m = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName me = new ComponentName(app, MediaListener.class);
        try {
            m.addOnActiveSessionsChangedListener(SESSIONS, me, main);
            msm = m;
            watch(m.getActiveSessions(me));
        } catch (SecurityException e) {
            // Penyimpanan DE: home berjalan saat direct boot, sebelum CE terbuka (prefs CE di sini membuat home crash).
            android.content.SharedPreferences p = app.createDeviceProtectedStorageContext()
                    .getSharedPreferences("media", Context.MODE_PRIVATE);
            if (granting || p.getBoolean("granted", false)) return;
            granting = true;
            String comp = me.flattenToString();
            // Android 8.1+: cmd notification; Android 7: tambahkan ke daftar di settings (tanpa menghapus yang lain).
            Tweaks.shellAsync("cmd notification allow_listener " + comp + " 2>/dev/null || { v=$(settings get secure "
                    + "enabled_notification_listeners); case \"$v\" in *" + comp + "*) ;; null|'') settings put secure "
                    + "enabled_notification_listeners " + comp + " ;; *) settings put secure enabled_notification_listeners "
                    + "\"$v:" + comp + "\" ;; esac; }", ok -> {
                if (ok) p.edit().putBoolean("granted", true).apply(); // ADB belum aktif: coba lagi lain kali
            });
        }
    }

    private static void watch(List<MediaController> list) {
        for (MediaController c : watched) c.unregisterCallback(CALLBACK);
        watched.clear();
        if (list != null) {
            for (MediaController c : list) {
                if (app.getPackageName().equals(c.getPackageName())) continue; // murattal & dzikir punya sumber sendiri
                c.registerCallback(CALLBACK, main);
                watched.add(c);
            }
        }
        pick();
    }

    /** Daftar dari sistem sudah urut dari yang paling baru aktif. */
    private static void pick() {
        MediaController best = null;
        for (MediaController c : watched) {
            if (playing(c)) { best = c; break; }
            if (best == null && c.getMetadata() != null) best = c;
        }
        current = best;
        notifyListeners();
    }

    static boolean playing(MediaController c) {
        PlaybackState s = c != null ? c.getPlaybackState() : null;
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private static String namePkg, name;

    /** Nama aplikasi pemutar; disimpan untuk paket terakhir supaya refresh notch tidak bertanya ke PackageManager. */
    static String appName(Context c, String pkg) {
        if (pkg.equals(namePkg)) return name;
        try {
            PackageManager pm = c.getPackageManager();
            name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            name = pkg;
        }
        namePkg = pkg;
        return name;
    }

    static final MediaNotch.Source SOURCE = new MediaNotch.Source() {
        @Override public boolean active() { return current != null && current.getMetadata() != null; }
        @Override public boolean playing() { return MediaListener.playing(current); }

        @Override public String state(Context c) {
            return appName(c, current.getPackageName()).toUpperCase(Lang.locale());
        }

        @Override public String title(Context c) {
            MediaMetadata m = current.getMetadata();
            String t = m.getString(MediaMetadata.METADATA_KEY_TITLE), ar = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (t == null || t.isEmpty()) t = appName(c, current.getPackageName());
            return ar == null || ar.isEmpty() ? t : t + "  ·  " + ar;
        }

        @Override public void toggle(Activity a) {
            if (playing()) current.getTransportControls().pause();
            else current.getTransportControls().play();
        }

        @Override public void next(Activity a) { current.getTransportControls().skipToNext(); }
        @Override public void stop(Activity a) { current.getTransportControls().stop(); }

        @Override public void open(Activity a) {
            PendingIntent pi = current.getSessionActivity();
            try {
                if (pi != null) { pi.send(); return; }
            } catch (PendingIntent.CanceledException ignored) { }
            Intent i = a.getPackageManager().getLaunchIntentForPackage(current.getPackageName());
            if (i != null) a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    };
}
