package com.ashafizullah.klikdash;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pengingat masuk waktu sholat, hanya notifikasi (tanpa adzan). AlarmManager.RTC (tidak membangunkan headunit yang
 * tidur): alarm yang baru terkirim saat headunit bangun dan sudah lewat lebih dari {@link #LATE_MS} diabaikan. Tiap
 * alarm menjadwalkan waktu berikutnya; home memanggil {@link #schedule} saat dibuka, saat lokasi atau jam berubah.
 * Home tampil: notifikasi KlikDash ({@link Notice}); aplikasi lain di depan: Notice melayang (izin tampil di atas
 * aplikasi lain, diberikan lewat ADB lokal oleh {@link #allowOverlay}); tanpa izin itu: notifikasi sistem.
 */
public class PrayerAlarm extends BroadcastReceiver {
    static final String CHANNEL = "sholat";
    static final long LATE_MS = 10 * 60_000L;

    /** Home yang sedang tampil (di antara onResume dan onPause), untuk notifikasi KlikDash. */
    static WeakReference<Activity> home = new WeakReference<>(null);

    static boolean enabled(SharedPreferences p) { return p.getBoolean("prayerAlert", true); }

    /** Jadwalkan waktu sholat berikutnya (atau batalkan bila pengingat dimatikan). */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent old = PendingIntent.getBroadcast(c, 0, new Intent(c, PrayerAlarm.class), PendingIntent.FLAG_NO_CREATE);
        if (old != null) am.cancel(old);
        SharedPreferences p = prefs(c);
        if (!enabled(p)) return;
        double[] loc = location(p);
        Calendar now = Calendar.getInstance();
        for (int day = 0; day < 2; day++) {
            Calendar d = (Calendar) now.clone();
            d.add(Calendar.DAY_OF_MONTH, day);
            int[] t = PrayerTimes.compute(d, loc[0], loc[1]);
            for (int i = 0; i < t.length; i++) {
                Calendar at = (Calendar) d.clone();
                at.set(Calendar.HOUR_OF_DAY, t[i] / 60);
                at.set(Calendar.MINUTE, t[i] % 60);
                at.set(Calendar.SECOND, 0);
                at.set(Calendar.MILLISECOND, 0);
                if (at.getTimeInMillis() <= now.getTimeInMillis()) continue;
                Intent it = new Intent(c, PrayerAlarm.class).putExtra("i", i).putExtra("at", at.getTimeInMillis());
                am.setExact(AlarmManager.RTC, at.getTimeInMillis(),
                        PendingIntent.getBroadcast(c, 0, it, PendingIntent.FLAG_UPDATE_CURRENT));
                return;
            }
        }
    }

    @Override public void onReceive(Context c, Intent i) {
        long at = i.getLongExtra("at", 0);
        if (enabled(prefs(c)) && Math.abs(System.currentTimeMillis() - at) <= LATE_MS) {
            notify(c, i.getIntExtra("i", 0), at);
        }
        schedule(c);
    }

    /** Tampilkan pengingat untuk waktu sholat {@code i} (indeks {@link PrayerTimes#NAMES}) pada {@code at}. */
    static void notify(Context c, int i, long at) {
        String name = titleCase(PrayerTimes.NAMES[i]);
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(at);
        String time = String.format(Locale.US, "%02d:%02d", t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE));
        String city = prefs(c).getString("city", null);
        String msg = "Masuk waktu " + name + " · " + time + (city != null ? " · " + titleCase(city) : "");
        Activity a = home.get();
        if (a != null && !a.isFinishing()) {
            Notice.show(a, Notice.REMIND, "Waktu sholat", msg);
            return;
        }
        // Aplikasi lain di depan: notifikasi KlikDash melayang di atasnya. Firmware NWD memblokir notifikasi sistem
        // dari aplikasi pihak ketiga ("Not allowed show notification"), jadi notifikasi sistem hanya cadangan.
        Theme.load(c);
        if (Notice.overlay(c, Notice.REMIND, "Waktu sholat", msg)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0);
        Notification.Builder b = Compat.alertNotification(c, CHANNEL, "Pengingat waktu sholat")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Waktu " + name)
                .setContentText(msg)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setWhen(at);
        if (Compat.O) b.setTimeoutAfter(30 * 60_000L);
        nm.notify(2, b.build());
    }

    /** Izin tampil di atas aplikasi lain lewat ADB lokal (seperti Tweaks); diam-diam dilewati bila ADB tidak ada. */
    static void allowOverlay(Context c) {
        if (android.provider.Settings.canDrawOverlays(c) || !enabled(prefs(c))) return;
        Tweaks.shellAsync("cmd appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
    }

    /** Lokasi tersimpan dari GPS, atau tebakan zona waktu seperti di home. */
    static double[] location(SharedPreferences p) {
        String la = p.getString("lat", null), lo = p.getString("lon", null);
        if (la != null && lo != null) return new double[]{Double.parseDouble(la), Double.parseDouble(lo)};
        return PrayerTimes.guess(TimeZone.getDefault().getID());
    }

    static String titleCase(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean up = true;
        for (char ch : s.toCharArray()) {
            b.append(up ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
            up = ch == ' ' || ch == '-';
        }
        return b.toString();
    }

    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("klikdash", Context.MODE_PRIVATE);
    }
}
