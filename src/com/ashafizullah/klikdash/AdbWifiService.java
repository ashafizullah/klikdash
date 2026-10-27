package com.ashafizullah.klikdash;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Membuka ADB tanpa komputer lewat Wireless debugging (Android 11+, lihat {@link AdbWifi}). Pengguna membuka dialog
 * "Pair device with pairing code" di Opsi pengembang, lalu mengetik kodenya di notifikasi ini (balas langsung):
 * dialog pairing tertutup bila Pengaturan ditinggalkan, jadi kodenya tidak bisa diketik di layar KlikDash.
 *
 * Setelah pairing, perintah pemanggil dijalankan sekali lewat koneksi TLS, lalu Wireless debugging dimatikan lagi.
 * Port 5555 hanya dibuka bila diminta ({@code openPort}, mis. untuk Hemat Daya) dan hanya sampai headunit dinyalakan
 * ulang: adbd di port itu bisa dihubungi perangkat lain di jaringan yang sama.
 */
public final class AdbWifiService extends Service {
    static final String CHANNEL = "adbwifi";
    private static final int NOTIFICATION_ID = 4; // 1 = murattal, 2 = pengingat sholat, 3 = KlikBeam
    private static final String ACTION_CODE = "com.ashafizullah.klikdash.adbwifi.CODE";
    private static final String ACTION_STOP = "com.ashafizullah.klikdash.adbwifi.STOP";
    private static final String EXTRA_CMD = "cmd", EXTRA_OPEN_PORT = "openPort", EXTRA_SETTINGS = "settings", KEY_CODE = "code";
    private static final long WAIT_PORT_MS = 10_000;

    /** Hasil untuk pemanggil, di thread utama: {@link Tweaks#DONE} atau {@link Tweaks#NO_ADB} (gagal/dibatalkan). */
    private static IntConsumer callback;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AdbWifi.Finder pairing, connect;
    private volatile int pairingPort, connectPort;
    private String cmd, settingsPkg;
    private boolean openPort, busy;
    private int triedPort; // port Wireless debugging yang sudah dicoba tanpa pairing (port baru tiap dinyalakan)
    private String status;

    static boolean supported() { return android.os.Build.VERSION.SDK_INT >= 30; }

    /**
     * Bila ADB lokal gagal karena port 5555 tertutup padahal USB debugging menyala (Android 11+), tawarkan membukanya
     * lewat Wireless debugging. True bila tawarannya ditampilkan; {@code done} dipanggil setelah dicoba (atau dibatalkan).
     */
    static boolean offer(Activity a, int adbResult, String cmd, boolean openPort, IntConsumer done) {
        if (adbResult != Tweaks.NO_ADB || !supported() || !Tweaks.debuggingOn(a) || a.isFinishing()) return false;
        String msg = a.getString(R.string.adbwifi_offer_message);
        if (openPort) {
            msg += a.getString(R.string.adbwifi_offer_port_note);
            // ro.adb.secure=0: adbd di port 5555 tidak meminta izin kunci sama sekali.
            if ("0".equals(HomeSwitch.getprop("ro.adb.secure"))) msg += a.getString(R.string.adbwifi_offer_insecure_note);
        }
        Notice.track(new android.app.AlertDialog.Builder(a)
                .setTitle(R.string.adbwifi_offer_title)
                .setMessage(msg)
                .setNegativeButton(R.string.common_cancel, (d, w) -> done.accept(Tweaks.NO_ADB))
                .setOnCancelListener(d -> done.accept(Tweaks.NO_ADB))
                .setPositiveButton(R.string.adbwifi_offer_start, (d, w) -> start(a, cmd, openPort, done))
                .show());
        return true;
    }

    /**
     * Mulai: buka Opsi pengembang dan tunggu kode pairing. {@code cmd} (boleh null) dijalankan sekali setelah ADB
     * terhubung; sesudahnya aplikasi Pengaturan ditutup sehingga layar KlikDash pemanggil tampil lagi (activity
     * KlikDash tidak di-export, jadi tidak bisa dibuka lewat am start dari shell).
     */
    static void start(Activity a, String cmd, boolean openPort, IntConsumer done) {
        callback = done;
        String pkg = settingsPackage(a);
        Compat.startForegroundService(a, new Intent(a, AdbWifiService.class)
                .putExtra(EXTRA_CMD, cmd)
                .putExtra(EXTRA_OPEN_PORT, openPort)
                .putExtra(EXTRA_SETTINGS, pkg));
        openSettings(a, pkg);
    }

    /**
     * Paket Pengaturan sistem yang menampilkan Opsi pengembang, atau null. Hanya aplikasi sistem: aplikasi lain yang
     * mendaftarkan intent yang sama tidak boleh menggantikan layar tempat kode pairing tampil, dan namanya dipakai di
     * perintah shell (am force-stop).
     */
    private static String settingsPackage(Context c) {
        android.content.pm.ResolveInfo r = c.getPackageManager().resolveActivity(
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), 0);
        if (r == null || r.activityInfo == null) return null;
        String pkg = r.activityInfo.packageName;
        boolean system = (r.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        return system && !"android".equals(pkg) && pkg.matches("[A-Za-z0-9_.]+") ? pkg : null;
    }

    /** Opsi pengembang, dengan sorotan pada Wireless debugging (argumen fragmen Pengaturan AOSP). */
    static void openSettings(Context c, String pkg) {
        try {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .setPackage(pkg)
                    .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (status == null) status = getString(R.string.adbwifi_waiting);
        startForeground(NOTIFICATION_ID, notification()); // Android 8+: wajib segera setelah startForegroundService
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            finish(Tweaks.NO_ADB);
        } else if (ACTION_CODE.equals(action)) {
            Bundle r = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = r != null ? r.getCharSequence(KEY_CODE) : intent.getStringExtra(KEY_CODE);
            if (code != null) submit(code.toString());
            else update(status);
        } else if (intent != null && intent.hasExtra(EXTRA_OPEN_PORT)) {
            // Dimulai lagi saat masih menunggu (mis. dari layar lain): pakai permintaan terbaru.
            if (!busy) {
                cmd = intent.getStringExtra(EXTRA_CMD);
                openPort = intent.getBooleanExtra(EXTRA_OPEN_PORT, false);
                settingsPkg = intent.getStringExtra(EXTRA_SETTINGS);
            }
            if (pairing != null) return START_NOT_STICKY;
            pairing = new AdbWifi.Finder(this, AdbWifi.PAIRING, p -> {
                pairingPort = p;
                if (!busy) update(getString(p > 0 ? R.string.adbwifi_found : R.string.adbwifi_waiting));
            });
            connect = new AdbWifi.Finder(this, AdbWifi.CONNECT, p -> {
                connectPort = p;
                if (p > 0 && !busy && p != triedPort) tryPaired(p);
            });
            pairing.start();
            connect.start();
        } else if (callback == null && pairing == null) {
            finish(Tweaks.NO_ADB); // dimulai ulang sistem tanpa pemanggil: tidak ada yang perlu dilanjutkan
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (pairing != null) pairing.stop();
        if (connect != null) connect.stop();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Masukan pengguna: "KODE", atau "PORT KODE" / "IP:PORT KODE" bila mDNS tidak menemukan dialog pairing. */
    private void submit(String input) {
        if (busy) return;
        List<String> nums = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+").matcher(input.replaceAll("\\d+\\.\\d+\\.\\d+\\.\\d+", " "));
        while (m.find()) nums.add(m.group());
        String code = null;
        int port = 0;
        for (String n : nums) {
            if (n.length() == 6 && code == null) code = n;
            else if (n.length() <= 5) port = Integer.parseInt(n);
        }
        if (code == null) {
            update(getString(R.string.adbwifi_bad_code));
            return;
        }
        busy = true;
        update(getString(R.string.adbwifi_pairing));
        final String c = code;
        final int manualPort = port;
        new Thread(() -> run(c, manualPort), "adbwifi").start();
    }

    /**
     * Kunci KlikDash sudah pernah di-pair (Android mengingatnya hingga 7 hari bawaan): Wireless debugging cukup
     * dinyalakan, tanpa kode. Gagal: tunggu kode seperti biasa.
     */
    private void tryPaired(int port) {
        triedPort = port;
        busy = true;
        new Thread(() -> {
            try {
                Tweaks.execTls(port, "shell:true", 5000);
            } catch (IOException e) {
                android.util.Log.i("KlikDash", "adbwifi: belum di-pair (" + e + ")");
                retry(status);
                return;
            }
            main.post(() -> update(getString(R.string.adbwifi_connecting)));
            connected();
        }, "adbwifi").start();
    }

    /** Di thread latar: pairing, sambungkan, jalankan perintah, lalu tutup lagi Wireless debugging. */
    private void run(String code, int manualPort) {
        int port = manualPort > 0 ? manualPort : waitFor(true);
        if (port <= 0) {
            retry(getString(R.string.adbwifi_no_dialog));
            return;
        }
        try {
            AdbWifi.pair(port, code);
        } catch (AdbWifi.WrongCode e) {
            retry(getString(R.string.adbwifi_wrong_code));
            return;
        } catch (IOException e) {
            android.util.Log.w("KlikDash", "adbwifi pair", e);
            retry(getString(R.string.adbwifi_pair_failed));
            return;
        }
        main.post(() -> update(getString(R.string.adbwifi_connecting)));
        connected();
    }

    /** Di thread latar, kunci sudah di-pair: jalankan perintah pemanggil lewat port Wireless debugging. */
    private void connected() {
        int cp = waitFor(false);
        try {
            if (cp <= 0) throw new IOException("port Wireless debugging tidak ditemukan");
            String back = settingsPkg != null ? "am force-stop " + settingsPkg : null;
            String first = cmd != null && back != null ? cmd + "; " + back : cmd != null ? cmd : back;
            if (first != null) Tweaks.execTls(cp, "shell:" + first, 30_000);
            if (openPort) {
                // Hanya sampai headunit dinyalakan ulang: persist.adb.tcp.port tidak diatur, supaya port 5555 tidak
                // terbuka permanen di setiap WiFi yang nanti disambungkan.
                quiet(cp, "tcpip:5555"); // adbd mulai ulang; koneksinya ikut putus
                // Tunggu adbd siap di 5555, lalu matikan Wireless debugging lewat port itu (bila kuncinya diterima).
                for (int i = 0; i < 20; i++) {
                    try {
                        Tweaks.exec("settings put global adb_wifi_enabled 0", 5000);
                        break;
                    } catch (Tweaks.NotAllowed e) {
                        break;
                    } catch (IOException e) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                }
            } else {
                quiet(cp, "shell:settings put global adb_wifi_enabled 0");
            }
        } catch (IOException e) {
            android.util.Log.w("KlikDash", "adbwifi connect", e);
            main.post(() -> fail(getString(R.string.adbwifi_connect_failed)));
            return;
        }
        main.post(() -> finish(Tweaks.DONE));
    }

    private static void quiet(int port, String service) {
        try {
            Tweaks.execTls(port, service, 10_000);
        } catch (IOException ignored) { }
    }

    /**
     * Tunggu port dari mDNS (dialog pairing, atau Wireless debugging sesudah pairing). Port Wireless debugging harus
     * benar-benar menerima koneksi: mDNS bisa masih menyimpan iklan lama dari saat sebelumnya dinyalakan.
     */
    private int waitFor(boolean pairingDialog) {
        long until = System.currentTimeMillis() + WAIT_PORT_MS;
        while (System.currentTimeMillis() < until) {
            int p = pairingDialog ? pairingPort : connectPort;
            if (p > 0 && (pairingDialog || listening(p))) return p;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Port lokal menerima koneksi. Dipakai untuk port connect saja: menyentuh port pairing membuat adbd menganggapnya
     * satu percobaan pairing.
     */
    private static boolean listening(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Gagal tapi bisa dicoba lagi: kode baru diketik di notifikasi yang sama. */
    private void retry(String msg) {
        main.post(() -> {
            busy = false;
            update(msg);
        });
    }

    /** Gagal total: notifikasi tetap tampil (bisa disapu) dengan pesannya, pemanggil diberi tahu. */
    private void fail(String msg) {
        IntConsumer cb = callback;
        callback = null;
        stopForeground(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, base().setContentText(msg).setStyle(new Notification.BigTextStyle().bigText(msg))
                .setOngoing(false).setAutoCancel(true).build());
        stopSelf();
        if (cb != null) cb.accept(Tweaks.NO_ADB);
    }

    private void finish(int result) {
        IntConsumer cb = callback;
        callback = null;
        stopForeground(true);
        stopSelf();
        if (cb != null) cb.accept(result);
    }

    private void update(String msg) {
        status = msg;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
    }

    private Notification.Builder base() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).setPackage(settingsPkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0);
        return Compat.alertNotification(this, CHANNEL, getString(R.string.adbwifi_channel))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.adbwifi_title))
                .setContentIntent(open)
                .setShowWhen(false);
    }

    private Notification notification() {
        Notification.Builder b = base()
                .setContentText(status)
                .setStyle(new Notification.BigTextStyle().bigText(status))
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (!busy) {
            // Mutable: sistem mengisi hasil RemoteInput ke intent-nya (targetSdk 29: bawaan PendingIntent memang mutable).
            PendingIntent reply = PendingIntent.getService(this, 1,
                    new Intent(this, AdbWifiService.class).setAction(ACTION_CODE), PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, getString(R.string.adbwifi_enter), reply)
                    .addRemoteInput(new RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.adbwifi_code_hint)).build())
                    .build());
        }
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, AdbWifiService.class).setAction(ACTION_STOP), 0);
        b.addAction(new Notification.Action.Builder(null, getString(R.string.common_cancel), stop).build());
        return b.build();
    }
}
