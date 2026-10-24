package com.ashafizullah.klikdash;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selisih Android 7 (API 24–25) dengan Android 8+ (API 26). Semua pemanggilan API 26 lewat sini, selalu di balik
 * {@link #O}, supaya kelas lain tetap jalan di headunit Android 7.
 */
final class Compat {
    static final boolean O = Build.VERSION.SDK_INT >= 26;

    private Compat() { }

    private static final Pattern AXIS = Pattern.compile("'(\\w{4})'\\s*(\\d+)");

    /**
     * Font dari assets dengan sumbu variabel {@code axes} (mis. {@code "'opsz' 36, 'wght' 700"}). Android 7 belum bisa
     * mengatur sumbu, dan Android 8.x mengaturnya tanpa menyesuaikan lebar huruf (spasi jadi berantakan), jadi sebelum
     * Android 9 dipakai instans statis {@code static/display-opsz36-wght700.ttf} buatan tools/build_static_fonts.py;
     * bila tidak ada, instans bawaan file (ditebalkan bila wght ≥ 600).
     */
    static Typeface font(AssetManager am, String file, String axes, Typeface fallback) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                Typeface.Builder b = new Typeface.Builder(am, file);
                if (axes != null) b.setFontVariationSettings(axes);
                Typeface t = b.build();
                if (t != null) return t;
            } else if (axes == null) {
                return Typeface.createFromAsset(am, file);
            } else {
                java.util.TreeMap<String, Integer> v = new java.util.TreeMap<>();
                for (Matcher m = AXIS.matcher(axes); m.find(); ) v.put(m.group(1), Integer.parseInt(m.group(2)));
                StringBuilder name = new StringBuilder("static/").append(file, 0, file.length() - 4);
                for (java.util.Map.Entry<String, Integer> e : v.entrySet()) name.append('-').append(e.getKey()).append(e.getValue());
                try {
                    return Typeface.createFromAsset(am, name.append(".ttf").toString());
                } catch (RuntimeException missing) {
                    Typeface t = Typeface.createFromAsset(am, file);
                    Integer w = v.get("wght");
                    return w != null && w >= 600 ? Typeface.create(t, Typeface.BOLD) : t;
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    /** Android 8+ melarang startService dari latar untuk layanan yang akan foreground; Android 7 belum mengenalnya. */
    static void startForegroundService(Context c, Intent i) {
        if (O) c.startForegroundService(i);
        else c.startService(i);
    }

    /** Notification.Builder dengan channel (dibuat bila perlu); Android 7 belum punya channel. */
    static Notification.Builder notification(Context c, String channel, String name) {
        if (!O) return new Notification.Builder(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(channel) == null)
            nm.createNotificationChannel(new NotificationChannel(channel, name, NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(c, channel);
    }

    /**
     * Notifikasi penting tanpa suara (mis. pengingat sholat): channel IMPORTANCE_HIGH supaya muncul di atas aplikasi
     * lain, tanpa bunyi dan getar. Android 7 tanpa channel: prioritas tinggi, tampil di panel notifikasi.
     */
    @SuppressWarnings("deprecation")
    static Notification.Builder alertNotification(Context c, String channel, String name) {
        if (!O) return new Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(channel) == null) {
            NotificationChannel ch = new NotificationChannel(channel, name, NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(c, channel);
    }

    /** Izin "pasang aplikasi tak dikenal" per aplikasi baru ada di Android 8; di Android 7 penginstal sistem yang bertanya. */
    static boolean canRequestPackageInstalls(Activity a) {
        return !O || a.getPackageManager().canRequestPackageInstalls();
    }

    /** Audio focus: AudioFocusRequest di Android 8+, API lama berbasis listener di Android 7. */
    static final class Focus {
        final AudioManager am;
        final int gain;
        final AudioManager.OnAudioFocusChangeListener listener;
        final Object request; // AudioFocusRequest; bertipe Object supaya kelas ini tetap termuat di Android 7

        Focus(Context c, int gain, AudioAttributes attrs, AudioManager.OnAudioFocusChangeListener listener) {
            am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            this.gain = gain;
            this.listener = listener != null ? listener : change -> { };
            request = O ? new AudioFocusRequest.Builder(gain).setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(this.listener).build() : null;
        }

        int request() {
            return O ? am.requestAudioFocus((AudioFocusRequest) request)
                    : am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, gain);
        }

        void abandon() {
            if (O) am.abandonAudioFocusRequest((AudioFocusRequest) request);
            else am.abandonAudioFocus(listener);
        }
    }
}
