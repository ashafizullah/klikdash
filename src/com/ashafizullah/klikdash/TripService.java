package com.ashafizullah.klikdash;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.IBinder;

/**
 * Riwayat perjalanan untuk akun KlikBeam ({@link Account}): fix GPS tiap detik ke {@link TripRecorder}, lalu unggah
 * antreannya setiap ada internet (biasanya hotspot HP). Layanan foreground bertipe lokasi: tanpa itu Android 8+ hanya
 * memberi fix ke aplikasi di latar beberapa kali per jam (lihat {@link Clock}), padahal yang di depan biasanya Maps.
 * Hidup hanya selama headunit terhubung ke akun dan riwayat perjalanan nyala ({@link #sync}).
 */
public final class TripService extends Service {
    static final String CHANNEL = "trips";
    private static final int NOTIFICATION_ID = 5; // 1 = murattal, 2 = pengingat sholat, 3 = KlikBeam, 4 = ADB/dzikir
    private static final long TICK_MS = 60_000, RETRY_MIN_MS = 30_000, RETRY_MAX_MS = 15 * 60_000;

    private static TripService instance;

    private TripRecorder recorder;
    private LocationManager lm;
    private ConnectivityManager cm;
    private long retryMs = RETRY_MIN_MS, nextTry;
    private boolean flushing;
    /** Waktu GPS fix terakhir dan jam monotonik saat itu: jam sistem headunit bisa salah sebelum {@link Clock} membetulkannya. */
    private long fixTime, fixElapsed;

    /** Nyalakan atau matikan sesuai akun dan setelan; aman dipanggil berkali-kali (home onResume, Pengaturan). */
    static void sync(Context c) {
        boolean want = Account.linked(c) && Account.tripsOn(c)
                && c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (want && instance == null) Compat.startForegroundService(c, new Intent(c, TripService.class));
        else if (!want && instance != null) {
            instance.stopForeground(true);
            instance.stopSelf();
        }
    }

    static boolean running() { return instance != null; }

    static boolean recording() { return instance != null && instance.recorder.active(); }

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        recorder = new TripRecorder(Account.dir(this));
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gps);
        } catch (SecurityException | IllegalArgumentException e) {
            android.util.Log.w("KlikDash", "trip: GPS tidak tersedia", e);
        }
        try {
            cm.registerDefaultNetworkCallback(online);
        } catch (RuntimeException ignored) { }
        Account.MAIN.postDelayed(tick, 5000);
    }

    @Override public void onDestroy() {
        instance = null;
        Account.MAIN.removeCallbacks(tick);
        lm.removeUpdates(gps);
        try {
            cm.unregisterNetworkCallback(online);
        } catch (RuntimeException ignored) { }
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification()); // Android 8+: wajib segera setelah startForegroundService
        if (!Account.linked(this) || !Account.tripsOn(this)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY; // proses dimatikan sistem: Android menyalakannya lagi dan trip dilanjutkan dari berkas
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private final LocationListener gps = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            fixTime = l.getTime();
            fixElapsed = android.os.SystemClock.elapsedRealtime();
            boolean was = recorder.active();
            recorder.feed(l.getTime(), l.getLatitude(), l.getLongitude(), l.hasSpeed() ? l.getSpeed() : -1,
                    l.hasAccuracy() ? l.getAccuracy() : 30);
            if (was != recorder.active()) {
                Account.changed();
                if (was) flushSoon(); // trip selesai
            }
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    private final ConnectivityManager.NetworkCallback online = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { Account.MAIN.postDelayed(TripService.this::flushSoon, 3000); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean was = recorder.active();
            if (fixElapsed > 0) recorder.tick(fixTime + android.os.SystemClock.elapsedRealtime() - fixElapsed);
            if (was != recorder.active()) Account.changed();
            if (System.currentTimeMillis() >= nextTry) flush();
            Account.MAIN.postDelayed(this, TICK_MS);
        }
    };

    private void flushSoon() {
        retryMs = RETRY_MIN_MS;
        nextTry = 0;
        flush();
    }

    /** Satu unggahan sekaligus; gagal: coba lagi dengan jeda yang makin panjang (30 detik sampai 15 menit). */
    private void flush() {
        if (flushing || !hasWork()) return;
        flushing = true;
        Context c = getApplicationContext();
        Account.NET.execute(() -> {
            boolean done = Account.flush(c);
            Account.MAIN.post(() -> {
                flushing = false;
                if (done) {
                    retryMs = RETRY_MIN_MS;
                    nextTry = 0;
                } else {
                    nextTry = System.currentTimeMillis() + retryMs;
                    retryMs = Math.min(RETRY_MAX_MS, retryMs * 2);
                }
            });
        });
    }

    private boolean hasWork() {
        String[] q = recorder.queueDir().list((d, n) -> n.endsWith(".json"));
        return recorder.parkedFile().isFile() || (q != null && q.length > 0);
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 5, new Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_TAB, SettingsActivity.TAB_ACCOUNT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT);
        return Compat.notification(this, CHANNEL, getString(R.string.account_channel))
                .setSmallIcon(R.drawable.ic_beam)
                .setContentTitle(getString(R.string.account_notification_title))
                .setContentText(getString(R.string.account_notification_text, Account.carName(this)))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}
