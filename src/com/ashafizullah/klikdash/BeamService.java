package com.ashafizullah.klikdash;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Layanan foreground selama {@link BeamHub} hidup, supaya Android tidak mematikan proses saat KlikBeam tampil
 * melayang di atas aplikasi lain. Notifikasinya membuka layar penuh atau menghentikan siaran. Tidak memegang apa pun
 * selain notifikasi; server dan jendela melayang ada di BeamHub.
 */
public final class BeamService extends Service {
    static final String CHANNEL = "beam";
    private static final String ACTION_STOP = "com.ashafizullah.klikdash.beam.STOP";
    private static final int NOTIFICATION_ID = 3; // 1 = murattal, 2 = pengingat sholat

    private static BeamService instance;

    static void start(Context c) { Compat.startForegroundService(c, new Intent(c, BeamService.class)); }

    /**
     * Bukan stopService: bila layanan belum sempat memanggil startForeground (dibuka lalu langsung ditutup), Android 8+
     * menganggapnya galat. Layanan yang belum hidup menghentikan dirinya sendiri di onStartCommand.
     */
    static void stop() {
        if (instance == null) return;
        instance.stopForeground(true);
        instance.stopSelf();
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification()); // Android 8+: wajib segera setelah startForegroundService
        if (intent != null && ACTION_STOP.equals(intent.getAction()) || !BeamHub.running()) {
            BeamHub.stop();
            stop();
        }
        return START_NOT_STICKY; // tanpa BeamHub (proses mati) tidak ada yang perlu dilanjutkan
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, BeamActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BeamService.class).setAction(ACTION_STOP), 0);
        return Compat.notification(this, CHANNEL, getString(R.string.beam_channel))
                .setSmallIcon(R.drawable.ic_beam)
                .setContentTitle("KlikBeam")
                .setContentText(getString(R.string.beam_notification))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, getString(R.string.beam_close), stop).build())
                .build();
    }
}
