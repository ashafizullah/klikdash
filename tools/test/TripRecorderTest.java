package com.ashafizullah.klikdash;

import java.io.File;
import java.nio.file.Files;

/**
 * Uji TripRecorder di JVM (tanpa emulator):
 *   tools/test/trip-recorder.sh
 * Mensimulasikan fix GPS 1 detik: diam, jalan, berhenti, mesin mati, proses mati di tengah trip.
 */
public final class TripRecorderTest {
    static int fails;
    static long T0 = 1_780_000_000_000L; // 2026

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "GAGAL ") + what);
        if (!ok) fails++;
    }

    /** Jalan lurus ke utara {@code meters} dengan {@code kmh}, satu fix per detik; mengembalikan waktu dan posisi akhir. */
    static double[] drive(TripRecorder r, long t, double lat, double lng, double meters, float kmh) {
        double perS = kmh / 3.6, dLat = perS / 111_195.0;
        int n = (int) Math.round(meters / perS);
        for (int i = 1; i <= n; i++) r.feed(t + i * 1000L, lat + dLat * i, lng, kmh / 3.6f, 5);
        return new double[]{t + n * 1000L, lat + dLat * n, lng};
    }

    static double[] idle(TripRecorder r, long t, double lat, double lng, long ms) {
        for (long s = 1000; s <= ms; s += 1000) r.feed(t + s, lat + (s % 3 - 1) * 2e-6, lng, 0.2f, 8); // goyang GPS ±0,2 m
        return new double[]{t + ms, lat, lng};
    }

    static File[] queue(TripRecorder r) {
        File[] f = r.queueDir().listFiles((d, n) -> n.endsWith(".json"));
        java.util.Arrays.sort(f);
        return f;
    }

    static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), "UTF-8"); }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("trips").toFile();
        TripRecorder r = new TripRecorder(dir);
        double lat = -6.1754, lng = 106.8272;

        double[] s = idle(r, T0, lat, lng, 60_000);
        check(!r.active(), "diam: belum ada trip");
        s = drive(r, (long) s[0], s[1], s[2], 3000, 40);
        check(r.active(), "jalan 40 km/j: trip berjalan");
        s = idle(r, (long) s[0], s[1], s[2], 6 * 60_000);
        check(!r.active(), "diam 6 menit: trip selesai");
        File[] q = queue(r);
        check(q.length == 1, "1 trip di antrean");
        String j = read(q[0]);
        System.out.println("      " + j.substring(0, Math.min(200, j.length())) + "…");
        int dist = Integer.parseInt(j.replaceAll(".*\"distanceM\":(\\d+).*", "$1"));
        check(Math.abs(dist - 3000) < 60, "jarak ≈ 3000 m (" + dist + ")");
        check(j.contains("\"maxKmh\":40.0"), "kecepatan maks 40");
        long moving = Long.parseLong(j.replaceAll(".*\"movingS\":(\\d+).*", "$1"));
        check(Math.abs(moving - 270) <= 2, "waktu bergerak ≈ 270 dtk (" + moving + ")");
        check(j.contains("\"endedAt\":\"" + TripRecorder.iso((long) s[0] - 6 * 60_000) + "\""), "selesai saat berhenti, bukan setelah 5 menit diam");
        check(read(r.parkedFile()).contains(String.format(java.util.Locale.ROOT, "\"lat\":%.6f", s[1])), "lokasi parkir = titik akhir");
        // Jalan lurus: penyederhanaan menyisakan sedikit titik.
        String poly = j.replaceAll(".*\"polyline\":\"([^\"]*)\".*", "$1");
        check(poly.length() < 40, "rute lurus disederhanakan (" + poly.length() + " karakter)");

        // Trip pendek: dibuang, tapi parkir ikut pindah.
        s = drive(r, (long) s[0], s[1], s[2], 150, 15);
        s = idle(r, (long) s[0], s[1], s[2], 6 * 60_000);
        check(queue(r).length == 1, "trip 150 m tidak diantre");
        check(read(r.parkedFile()).contains(String.format(java.util.Locale.ROOT, "\"lat\":%.6f", s[1])), "parkir ikut pindah setelah trip pendek");

        // Mesin mati di tengah jalan: fix berhenti 2 jam, lalu jalan lagi.
        s = drive(r, (long) s[0], s[1], s[2], 1000, 30);
        long off = (long) s[0];
        s = drive(r, off + 2 * 3600_000L, s[1], s[2], 1000, 30);
        check(queue(r).length == 2, "jeda 2 jam menutup trip sebelumnya");
        check(read(queue(r)[1]).contains("\"endedAt\":\"" + TripRecorder.iso(off) + "\""), "trip ditutup pada fix terakhir sebelum mesin mati");
        check(r.active(), "trip baru berjalan setelah mesin hidup");

        // Proses mati di tengah trip: dilanjutkan dari current.txt.
        TripRecorder r2 = new TripRecorder(dir);
        check(r2.active(), "trip dipulihkan setelah proses mati");
        s = drive(r2, (long) s[0], s[1], s[2], 1000, 30);
        s = idle(r2, (long) s[0], s[1], s[2], 6 * 60_000);
        File[] q3 = queue(r2);
        check(q3.length == 3, "trip pulihan masuk antrean");
        int d3 = Integer.parseInt(read(q3[2]).replaceAll(".*\"distanceM\":(\\d+).*", "$1"));
        check(Math.abs(d3 - 2000) < 60, "jarak sebelum + sesudah pulih ≈ 2000 m (" + d3 + ")");

        // GPS hilang (basement) lebih dari 5 menit: tick menutup trip.
        s = drive(r2, (long) s[0], s[1], s[2], 800, 30);
        r2.tick((long) s[0] + 6 * 60_000);
        check(!r2.active() && queue(r2).length == 4, "tick menutup trip saat GPS hilang");

        // Fix buruk diabaikan.
        r2.feed((long) s[0] + 7 * 60_000, 0, 0, 50, 500);
        check(!r2.active(), "fix akurasi 500 m diabaikan");

        // GPS mengulang fix terakhir dengan kecepatan basi: trip tetap selesai 5 menit setelah posisi berhenti berubah.
        s = drive(r2, (long) s[0] + 8 * 60_000, s[1], s[2], 1000, 30);
        for (int i = 1; i <= 6 * 60; i++) r2.feed((long) s[0] + i * 1000L, s[1], s[2], 30 / 3.6f, 5);
        check(!r2.active() && queue(r2).length == 5, "fix berulang dengan kecepatan basi menutup trip");

        System.out.println("antrean: " + r2.queueDir());
        System.exit(fails == 0 ? 0 : 1);
    }
}
