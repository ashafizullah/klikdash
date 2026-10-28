package com.ashafizullah.klikdash;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.ID;
import static com.ashafizullah.klikdash.HomeActivity.WARN;
import static com.ashafizullah.klikdash.HomeActivity.show;

/**
 * Lokasi dan GPS untuk home: jadwal sholat hari ini (tanpa GPS, kota perkiraan dari zona waktu), kecepatan, dan
 * pemeriksaan jam sistem ({@link Clock}).
 */
final class HomeLocation {
    final HomeActivity h;

    double lat, lon;
    String city;
    boolean locApprox;
    int[] pray, prayTomorrow;
    /** "04:31" dst., dihitung sekali per hari untuk PrayerView yang digambar ulang tiap detik. */
    String[] prayText;
    int prayDayKey = -1;
    LocationManager lm;

    HomeLocation(HomeActivity h) { this.h = h; }

    /** Tiap fix GPS sampai jam terbukti benar ({@link Clock}), juga saat aplikasi lain di depan; lalu berhenti. */
    final LocationListener clockRx = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            Clock.gps(h, l);
            if (Clock.ok(h) && lm != null) lm.removeUpdates(this);
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    final LocationListener locRx = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            Clock.gps(h, l);
            onLocation(l);
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    void load() {
        SharedPreferences prefs = h.prefs;
        String la = prefs.getString("lat", null), lo = prefs.getString("lon", null);
        if (la != null && lo != null) {
            lat = Double.parseDouble(la);
            lon = Double.parseDouble(lo);
            city = prefs.getString("city", null);
            locApprox = false;
            return;
        }
        // Belum pernah dapat lokasi: tebak ibu kota zona waktu.
        String tz = TimeZone.getDefault().getID();
        double[] g = PrayerTimes.guess(tz);
        lat = g[0];
        lon = g[1];
        city = PrayerTimes.guessCity(tz);
        locApprox = true;
    }

    /** Muat ulang dari prefs (lihat HomeActivity.openPrefs); jadwal dihitung lagi. */
    void reload() {
        load();
        prayDayKey = -1;
    }

    void set(double la, double lo, String name) {
        lat = la;
        lon = lo;
        city = name;
        locApprox = false;
        SharedPreferences.Editor e = h.prefs.edit().putString("lat", String.valueOf(la)).putString("lon", String.valueOf(lo));
        if (name != null) e.putString("city", name); else e.remove("city");
        e.apply();
        prayDayKey = -1;
        h.updateTime();
        PrayerAlarm.schedule(h);
    }

    /** Set lokasi manual lewat adb: am start -n com.ashafizullah.klikdash/.HomeActivity --es lat -6.1754 --es lon 106.8272 --es city Jakarta */
    void applyExtras(Intent i) {
        Bundle x = i != null ? i.getExtras() : null;
        if (x == null || !x.containsKey("lat") || !x.containsKey("lon")) return;
        try {
            double la = Double.parseDouble(String.valueOf(x.get("lat")));
            double lo = Double.parseDouble(String.valueOf(x.get("lon")));
            String name = x.getString("city");
            set(la, lo, name != null ? name.toUpperCase(ID) : null);
        } catch (NumberFormatException ignored) { }
    }

    void start() {
        if (h.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            h.requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            return;
        }
        lm = (LocationManager) h.getSystemService(android.content.Context.LOCATION_SERVICE);
        Location best = null;
        for (String p : lm.getProviders(false)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Exception ignored) { }
        }
        if (best != null) onLocation(best);
        // GPS headunit sudah selalu aktif (dipakai layanan NWD); passive cukup menumpang, tanpa beban tambahan.
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 60_000, 1000, locRx);
        } catch (Exception ignored) { }
        watchClock();
    }

    void stop() {
        if (lm == null) return;
        lm.removeUpdates(locRx);
        lm.removeUpdates(clockRx);
    }

    /** Selama jam belum terbukti benar: periksa (dan betulkan) jam tiap fix GPS lewat {@link #clockRx}. */
    void watchClock() {
        if (lm == null || Clock.ok(h)) return;
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000, 0, clockRx); // terdaftar lagi: diganti
        } catch (Exception ignored) { }
    }

    void onLocation(Location l) {
        float[] d = new float[1];
        Location.distanceBetween(lat, lon, l.getLatitude(), l.getLongitude(), d);
        if (!locApprox && d[0] < 3000) return; // pindah < 3 km: jadwal praktis sama
        final double la = l.getLatitude(), lo = l.getLongitude();
        // Masih dalam 15 km dari lokasi tersimpan: nama kota lama tetap dipakai (geocoder tidak selalu ada).
        set(la, lo, !locApprox && d[0] < 15000 ? city : null);
        if (!Geocoder.isPresent()) return;
        new Thread(() -> {
            try {
                List<Address> r = new Geocoder(h, ID).getFromLocation(la, lo, 1);
                if (r == null || r.isEmpty()) return;
                Address a = r.get(0);
                String n = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea();
                if (n == null) return;
                final String name = n.replaceFirst("^(Kecamatan|Kota|Kabupaten|Kab\\.)\\s+", "").toUpperCase(ID);
                h.runOnUiThread(() -> {
                    if (lat == la && lon == lo) set(la, lo, name);
                });
            } catch (Exception ignored) { }
        }, "geocode").start();
    }

    void computePrayer(Calendar now) {
        boolean twelve = Clock.twelve(h.prefs);
        int method = PrayerTimes.method(h.prefs);
        // Ganti format jam atau metode: hitung dan tulis ulang.
        int key = ((now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR)) * 2 + (twelve ? 1 : 0)) * 2 + method;
        if (key == prayDayKey) return;
        prayDayKey = key;
        pray = PrayerTimes.compute(now, lat, lon, method);
        prayText = new String[pray.length];
        for (int i = 0; i < pray.length; i++) prayText[i] = Clock.hm(twelve, pray[i]);
        Calendar t = (Calendar) now.clone();
        t.add(Calendar.DAY_OF_MONTH, 1);
        prayTomorrow = PrayerTimes.compute(t, lat, lon, method);
    }

    /** Indeks waktu sholat berikutnya, atau -1 jika berikutnya Subuh besok. */
    int nextPrayer(int minuteOfDay) {
        for (int i = 0; i < pray.length; i++) if (pray[i] > minuteOfDay) return i;
        return -1;
    }

    /** Sebelum Ashar = dzikir pagi, sejak Ashar = dzikir petang. */
    boolean dzikirPagi() {
        Calendar c = Calendar.getInstance();
        int min = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return pray == null || min < pray[2];
    }

    // ---------------------------------------------------------------- kecepatan (GPS)

    /** Kecepatan dari GPS; headunit ini tidak menyediakan kecepatan dari CAN (can_config_speed=0). */
    long speedAt;
    /** Kecepatan terakhir untuk asisten AI (alat car_status); basi bila lastKmhAt lebih dari 10 detik lalu. */
    static volatile int lastKmh;
    static volatile long lastKmhAt;

    /** Waktu fix terakhir: GPS dan passive sama-sama mengirim fix GPS yang sama, cukup diproses sekali. */
    long speedFixTime;

    final LocationListener speedRx = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            Clock.gps(h, l);
            if (!l.hasSpeed() || l.getTime() == speedFixTime) return;
            speedFixTime = l.getTime();
            speedAt = SystemClock.elapsedRealtime();
            int kmh = Math.round(l.getSpeed() * 3.6f);
            if (kmh < 3) kmh = 0; // diam: abaikan goyangan GPS
            lastKmh = kmh;
            lastKmhAt = speedAt;
            show(h.speed, String.valueOf(kmh), kmh > 100 ? WARN : DIM); // di atas batas tol
            if (kmh > 0) h.poke(); // bergerak: tanpa screensaver, hitung ulang menit diam
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    /** Tanpa pembaruan 5 detik (GPS hilang sinyal): tampilkan "--" alih-alih angka basi. */
    final Runnable speedStale = new Runnable() {
        @Override public void run() {
            if (SystemClock.elapsedRealtime() - speedAt > 5000) show(h.speed, "--", DIM);
            h.speed.postDelayed(this, 1000);
        }
    };

    /** Hanya selama home tampil: GPS 1 detik (di NWD GPS memang sudah selalu aktif) + passive. */
    void startSpeed() {
        h.speed.removeCallbacks(speedStale);
        h.speed.post(speedStale);
        if (lm == null || h.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, speedRx);
        } catch (Exception ignored) { }
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000, 0, speedRx);
        } catch (Exception ignored) { }
    }

    void stopSpeed() {
        h.speed.removeCallbacks(speedStale);
        if (lm != null) lm.removeUpdates(speedRx);
    }
}
