package com.ashafizullah.klikdash;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Akun KlikBeam di headunit (opsional): menghubungkan mobil ke akun lewat QR, lalu {@link TripService} mengirim
 * riwayat perjalanan dan lokasi parkir. Headunit tidak pernah login; ia memegang token perangkat dari pairing
 * ({@code Authorization: Device …}). API: klikbeam/server/README.md.
 *
 * Alamat server bisa diganti untuk uji (emulator ke server di Mac):
 * {@code am start -n com.ashafizullah.klikdash/.HomeActivity --es api http://10.0.2.2:3110}
 * ({@code --es api ""} kembali ke bawaan).
 */
final class Account {
    static final String API = "https://klikbeam.ashafizullah.com";
    static final String LINK_PAGE = "klikbeam.ashafizullah.com/link";

    private static final String K_TOKEN = "acctToken", K_CAR = "acctCar", K_API = "acctApi", K_TRIPS = "acctTrips";

    /** Satu antrean jaringan untuk pairing dan unggah: urut, tidak pernah dua sekaligus. */
    static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> new Thread(r, "klikbeam-api"));
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Pengaturan tab AKUN mendengarkan perubahan pairing dan antrean. */
    static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private Account() { }

    static boolean linked(Context c) { return Prefs.of(c).getString(K_TOKEN, null) != null; }

    static String carName(Context c) { return Prefs.of(c).getString(K_CAR, ""); }

    /** Riwayat perjalanan dicatat (bawaan nyala setelah terhubung; bisa dimatikan tanpa memutus akun). */
    static boolean tripsOn(Context c) { return Prefs.of(c).getBoolean(K_TRIPS, true); }

    static void setTripsOn(Context c, boolean on) {
        Prefs.of(c).edit().putBoolean(K_TRIPS, on).apply();
        TripService.sync(c);
    }

    static String api(Context c) {
        String a = Prefs.of(c).getString(K_API, null);
        return a != null && !a.isEmpty() ? a : API;
    }

    static void applyExtras(Context c, Intent i) {
        // Hanya build debuggable (DEBUGGABLE=1 ./build.sh): HomeActivity bisa dibuka aplikasi lain, jadi di APK rilis
        // aplikasi lain bisa mengalihkan token perangkat dan perjalanan ke server mereka.
        if ((c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        if (i == null || !i.hasExtra("api")) return;
        String a = i.getStringExtra("api");
        SharedPreferences.Editor e = Prefs.of(c).edit();
        if (a == null || a.trim().isEmpty()) e.remove(K_API); else e.putString(K_API, a.trim().replaceAll("/+$", ""));
        e.apply();
    }

    static File dir(Context c) { return new File(c.createDeviceProtectedStorageContext().getFilesDir(), "trips"); }

    static void changed() { MAIN.post(() -> { for (Runnable r : listeners) r.run(); }); }

    // ---------------------------------------------------------------- HTTP

    static final class ApiError extends Exception {
        final int status;
        final String code;

        ApiError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        /** Data ditolak server (bukan masalah jaringan): jangan dikirim ulang. */
        boolean rejected() { return status == 400 || status == 413 || status == 422; }
    }

    /** Panggilan JSON ke API; {@code auth} lengkap dengan skema ("Device …", "Pair …") atau null. Thread latar. */
    static JSONObject call(Context c, String method, String path, String auth, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(api(c) + path).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(20_000);
        h.setRequestMethod(method);
        h.setRequestProperty("Accept", "application/json");
        if (auth != null) h.setRequestProperty("Authorization", auth);
        try {
            if (body != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = h.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = h.getResponseCode();
            String txt = AiClient.read(code >= 400 ? h.getErrorStream() : h.getInputStream());
            JSONObject j;
            try {
                j = new JSONObject(txt);
            } catch (org.json.JSONException e) {
                throw new ApiError(code, "HTTP_" + code, "HTTP " + code);
            }
            if (code >= 400 || !j.optBoolean("ok")) {
                JSONObject err = j.optJSONObject("error");
                ApiError e = new ApiError(code, err != null ? err.optString("code") : "HTTP_" + code,
                        err != null ? err.optString("message") : "HTTP " + code);
                if ("DEVICE_UNLINKED".equals(e.code) && auth != null && auth.startsWith("Device ")) forget(c);
                throw e;
            }
            return j;
        } finally {
            h.disconnect();
        }
    }

    static String deviceAuth(Context c) {
        String t = Prefs.of(c).getString(K_TOKEN, null);
        return t != null ? "Device " + t : null;
    }

    // ---------------------------------------------------------------- pairing

    /** Pairing yang sedang tampil di tab AKUN: satu sekaligus, bertahan walau Pengaturan ditutup sampai kodenya habis. */
    static final class Pairing {
        static final int WAITING = 0, SHOWING = 1, FAILED = 2;

        volatile int state = WAITING;
        volatile String code, error;
        volatile long expiresAt;
        private String poll;
        private volatile boolean cancelled;

        String url() { return "https://" + LINK_PAGE + "#c=" + code; }

        /** "ABCDE-FGHIJ", untuk diketik bila kamera HP tidak bisa membaca QR. */
        String codeText() { return code == null ? "" : code.substring(0, 5) + "-" + code.substring(5); }
    }

    static volatile Pairing pairing;

    static Pairing startPairing(Context app) {
        Context c = app.getApplicationContext();
        cancelPairing();
        Pairing p = new Pairing();
        pairing = p;
        NET.execute(() -> {
            try {
                String model = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
                JSONObject d = call(c, "POST", "/v1/pair/start", null, new JSONObject().put("model", model.trim()).toString())
                        .getJSONObject("data");
                p.code = d.getString("code");
                p.poll = d.getString("pollToken");
                p.expiresAt = System.currentTimeMillis() + 10 * 60_000;
                p.state = Pairing.SHOWING;
                changed();
                MAIN.postDelayed(() -> poll(c, p), 2000);
            } catch (Exception e) {
                fail(p, e);
            }
        });
        changed();
        return p;
    }

    static void cancelPairing() {
        Pairing p = pairing;
        if (p != null) p.cancelled = true;
        pairing = null;
    }

    private static void fail(Pairing p, Exception e) {
        p.state = Pairing.FAILED;
        p.error = e instanceof ApiError ? e.getMessage() : null; // null: tidak ada internet / server tidak terjangkau
        changed();
    }

    /** Tiap 2 detik selama kode berlaku; jaringan putus sesaat tidak menghentikannya. */
    private static void poll(Context c, Pairing p) {
        if (p.cancelled || pairing != p) return;
        if (System.currentTimeMillis() > p.expiresAt + 30_000) {
            fail(p, new ApiError(410, "PAIR_EXPIRED", c.getString(R.string.account_expired)));
            return;
        }
        NET.execute(() -> {
            if (p.cancelled) return;
            try {
                JSONObject d = call(c, "GET", "/v1/pair/status", "Pair " + p.poll, null).getJSONObject("data");
                if ("linked".equals(d.optString("status"))) {
                    Prefs.of(c).edit()
                            .putString(K_TOKEN, d.getString("deviceToken"))
                            .putString(K_CAR, d.getJSONObject("device").optString("name"))
                            .putBoolean(K_TRIPS, true)
                            .apply();
                    pairing = null;
                    changed();
                    MAIN.post(() -> TripService.sync(c));
                    return;
                }
            } catch (ApiError e) {
                if (e.status == 410) { fail(p, e); return; }
            } catch (Exception ignored) { } // jaringan: coba lagi
            MAIN.postDelayed(() -> poll(c, p), 2000);
        });
    }

    /**
     * Saat tab AKUN dibuka: nama mobil diganti dari HP ikut tampil, dan mobil yang dihapus dari HP (atau akun yang
     * dihapus) langsung terlepas di sini ({@code DEVICE_UNLINKED} → {@link #forget}) tanpa menunggu unggahan berikutnya.
     */
    static void refresh(Context app) {
        Context c = app.getApplicationContext();
        String auth = deviceAuth(c);
        if (auth == null) return;
        NET.execute(() -> {
            try {
                String name = call(c, "GET", "/v1/device", auth, null).getJSONObject("data").optString("name");
                if (!name.isEmpty() && !name.equals(carName(c)) && auth.equals(deviceAuth(c))) {
                    Prefs.of(c).edit().putString(K_CAR, name).apply();
                    changed();
                }
            } catch (Exception ignored) { } // offline; DEVICE_UNLINKED sudah ditangani call()
        });
    }

    // ---------------------------------------------------------------- putus

    /** "Putuskan" di headunit: beri tahu server bila bisa, lalu lupakan token dan buang antrean. */
    static void unlink(Context app) {
        Context c = app.getApplicationContext();
        String auth = deviceAuth(c);
        forget(c);
        if (auth != null) NET.execute(() -> {
            try {
                call(c, "DELETE", "/v1/device", auth, null);
            } catch (Exception ignored) { } // offline: mobil tetap tampil di akun sampai dihapus dari KlikBeam
        });
    }

    /** Token tidak berlaku lagi (diputus dari HP atau akun dihapus): kembali ke keadaan tanpa akun. */
    static void forget(Context c) {
        Prefs.of(c).edit().remove(K_TOKEN).remove(K_CAR).remove(K_TRIPS).apply();
        deleteTree(dir(c));
        MAIN.post(() -> TripService.sync(c));
        changed();
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---------------------------------------------------------------- unggah

    static volatile long lastUpload;
    static volatile boolean uploadFailed;

    static int pending(Context c) {
        String[] f = new File(dir(c), "queue").list((d, n) -> n.endsWith(".json"));
        return f == null ? 0 : f.length;
    }

    /**
     * Kirim lokasi parkir dan antrean trip, yang lama dulu. Berhenti di galat jaringan atau server (dicoba lagi oleh
     * {@link TripService}); trip yang ditolak server dibuang supaya tidak menyumbat antrean. Thread {@link #NET}.
     * @return true bila antrean kosong setelahnya
     */
    static boolean flush(Context c) {
        File dir = dir(c);
        try {
            File parked = new File(dir, "parked.json");
            if (parked.isFile()) {
                String auth = deviceAuth(c);
                if (auth == null) return true;
                call(c, "PUT", "/v1/device/parked", auth, readText(parked));
                parked.delete();
            }
            File[] q = new File(dir, "queue").listFiles((d, n) -> n.endsWith(".json"));
            if (q != null) {
                java.util.Arrays.sort(q);
                for (File f : q) {
                    String auth = deviceAuth(c);
                    if (auth == null) return true;
                    try {
                        call(c, "POST", "/v1/device/trips", auth, readText(f));
                    } catch (ApiError e) {
                        if (!e.rejected()) throw e;
                        android.util.Log.w("KlikDash", "trip ditolak server: " + e.code + " " + e.getMessage());
                    }
                    f.delete();
                    lastUpload = System.currentTimeMillis();
                }
            }
            uploadFailed = false;
            return true;
        } catch (Exception e) {
            uploadFailed = true;
            return false;
        } finally {
            changed();
        }
    }

    private static String readText(File f) throws java.io.IOException {
        return AiClient.read(new java.io.FileInputStream(f)); // java.nio.file baru ada di Android 8
    }
}
