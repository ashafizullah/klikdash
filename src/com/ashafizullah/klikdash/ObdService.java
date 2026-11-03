package com.ashafizullah.klikdash;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;

/**
 * Layanan foreground yang memegang koneksi {@link ObdLink}, supaya adapter OBD tidak putus setiap kali home
 * di-pause (Maps, Waze, layar terbagi) dan data tetap mengalir saat home kembali tampil. Berjalan selama
 * {@link ObdPrefs#auto} menyala; tombol di Pengaturan dan di notifikasinya mematikan atau menyalakannya.
 */
public final class ObdService extends Service implements ObdLink.Listener {
    static final String CHANNEL = "obd";
    private static final String ACTION_STOP = "com.ashafizullah.klikdash.obd.STOP",
            ACTION_RECONNECT = "com.ashafizullah.klikdash.obd.RECONNECT";
    private static final int NOTIFICATION_ID = 6; // 1 = murattal, 2 = pengingat sholat, 3 = KlikBeam, 4 = ADB/dzikir, 5 = perjalanan

    private NotificationManager nm;
    private int shown = -1;

    /** Dipanggil saat home tampil: pakai metrik terbaru dan nyalakan layanan bila diizinkan. */
    static void sync(Context c) {
        SharedPreferences p = Prefs.of(c);
        ObdLink.I.init(c);
        ObdLink.I.configure(ObdPrefs.enabled(p));
        if (ObdPrefs.auto(p)) Compat.startForegroundService(c, new Intent(c, ObdService.class));
    }

    /** Sambungkan dari awal dan ingat pilihan itu. */
    static void connect(Context c) {
        ObdPrefs.setAuto(Prefs.of(c), true);
        Compat.startForegroundService(c, new Intent(c, ObdService.class).setAction(ACTION_RECONNECT));
    }

    /**
     * Putuskan dan jangan sambung lagi sampai diminta. Lewat onStartCommand, bukan stopService: layanan yang baru
     * dimulai harus sempat memanggil startForeground (Android 8+).
     */
    static void disconnect(Context c) {
        ObdPrefs.setAuto(Prefs.of(c), false);
        ObdLink.I.stop(); // langsung, tanpa menunggu layanan: socket ditutup dan thread kerja keluar
        Compat.startForegroundService(c, new Intent(c, ObdService.class).setAction(ACTION_STOP));
    }

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        ObdLink.I.addListener(this);
    }

    @Override public void onDestroy() {
        ObdLink.I.removeListener(this);
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        startForeground(NOTIFICATION_ID, notification()); // Android 8+: wajib segera setelah startForegroundService
        String a = i == null ? null : i.getAction();
        SharedPreferences p = Prefs.of(this);
        if (ACTION_STOP.equals(a) || !ObdPrefs.auto(p)) {
            ObdPrefs.setAuto(p, false);
            ObdLink.I.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        ObdLink.I.init(this);
        ObdLink.I.configure(ObdPrefs.enabled(p));
        if (ACTION_RECONNECT.equals(a)) ObdLink.I.restart();
        else ObdLink.I.start();
        return START_STICKY;
    }

    @Override public void onLog() {
        int s = ObdLink.I.state();
        if (s == shown) return;
        shown = s;
        nm.notify(NOTIFICATION_ID, notification());
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_TAB, SettingsActivity.TAB_OBD)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ObdService.class).setAction(ACTION_STOP), 0);
        return Compat.notification(this, CHANNEL, getString(R.string.obd_channel))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("OBD")
                .setContentText(getString(ObdLink.stateRes(ObdLink.I.state())))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, getString(R.string.obd_close), stop).build())
                .build();
    }
}
