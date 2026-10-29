package com.ashafizullah.klikdash;

import android.os.SystemClock;

/** Cek update otomatis saat headunit menyala, bila ⚙ › CEK UPDATE OTOMATIS dinyalakan (bawaan mati). */
final class HomeUpdate {
    /** Paling sering sekali per 6 jam (termasuk CEK UPDATE manual): ACC on-off berkali-kali cukup satu cek. */
    static final long EVERY = 6 * 3600_000L;
    /**
     * Rilis baru yang belum ditawarkan: menunggu home tampil, tidak terkunci, dan mobil diam. Statis, supaya tetap
     * ada bila home dibangun ulang (ganti tema, tombol tampilan utama) sebelum sempat ditawarkan.
     */
    static volatile Updater.Release pending;

    final HomeActivity h;
    int tries;

    HomeUpdate(HomeActivity h) { this.h = h; }

    /** Saat headunit menyala (boot atau ACC on). */
    void schedule() {
        h.home.removeCallbacks(check);
        if (!h.prefs.getBoolean("autoUpdate", false)) return;
        long since = System.currentTimeMillis() - h.prefs.getLong("updateChecked", 0);
        if (since >= 0 && since < EVERY) return; // negatif: jam headunit mundur (belum sinkron), cek saja
        tries = 0;
        h.home.postDelayed(check, 20_000); // WiFi/hotspot HP biasanya baru tersambung beberapa detik setelah menyala
    }

    /** Home tampil lagi: tawarkan rilis yang tertunda. */
    void resume() {
        if (pending != null) h.home.post(offer);
    }

    /** Home dihancurkan; home yang baru (recreate) menjadwalkan sendiri. */
    void cancel() {
        h.home.removeCallbacks(check);
        h.home.removeCallbacks(offer);
    }

    /** Satu cek ke GitHub; tanpa internet dicoba lagi tiap 30 detik, sampai ±5 menit setelah menyala. */
    final Runnable check = new Runnable() {
        @Override public void run() {
            Runnable retry = this;
            new Thread(() -> {
                Updater.Release r;
                try {
                    r = Updater.latest();
                } catch (Exception e) {
                    if (++tries < 10) h.home.postDelayed(retry, 30_000);
                    return;
                }
                h.runOnUiThread(() -> {
                    Prefs.of(h).edit().putLong("updateChecked", System.currentTimeMillis()).apply();
                    if (r == null || Updater.compare(r.version, Updater.installedVersion(h)) <= 0) return;
                    if (r.version.equals(h.prefs.getString("updateSkipped", null))) return; // sudah dijawab Nanti
                    pending = r;
                    offer.run();
                });
            }, "update-auto").start();
        }
    };

    /** Dialog yang sama dengan CEK UPDATE, hanya saat mobil diam; Nanti = versi ini tidak ditawarkan lagi. */
    final Runnable offer = new Runnable() {
        @Override public void run() {
            h.home.removeCallbacks(this);
            Updater.Release r = pending;
            if (r != null && r.version.equals(h.prefs.getString("updateSkipped", null))) { // dijawab Nanti di home lain
                pending = null;
                return;
            }
            if (r == null || !h.resumed || h.isFinishing()) return; // onResume mencoba lagi
            boolean moving = HomeLocation.lastKmh > 0 && SystemClock.elapsedRealtime() - HomeLocation.lastKmhAt < 10_000;
            boolean welcome = h.getWindow().getDecorView().findViewWithTag(Welcome.class) != null;
            if (moving || h.locked || welcome) {
                h.home.postDelayed(this, 15_000);
                return;
            }
            pending = null;
            SettingsActivity.offerUpdate(h, r, () -> Prefs.of(h).edit().putString("updateSkipped", r.version).apply());
        }
    };
}
