package com.ashafizullah.klikdash;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Pencatat perjalanan dari fix GPS, tanpa Android (diuji di JVM, lihat tools/test/trip-recorder.sh).
 *
 * Trip mulai saat kecepatan ≥ 10 km/j dan selesai setelah 5 menit diam, atau saat fix berhenti lebih dari 5 menit
 * (mesin dimatikan: headunit tidur, lalu fix berikutnya datang saat mesin hidup lagi). Trip di bawah 300 m dibuang,
 * tapi posisinya tetap jadi lokasi parkir. Titik rute disimpan per 15 m ke {@code current.txt} supaya trip tidak hilang
 * bila proses mati di tengah jalan; trip selesai masuk antrean {@code queue/} sebagai JSON siap unggah
 * ({@code POST /v1/device/trips}) dan posisi akhirnya ke {@code parked.json} ({@code PUT /v1/device/parked}).
 */
final class TripRecorder {
    static final float START_KMH = 10, MOVING_KMH = 3;
    static final long STOP_MS = 5 * 60_000, GAP_MS = 5 * 60_000;
    static final int MIN_TRIP_M = 300;
    static final float MAX_ACCURACY_M = 50, POINT_SPACING_M = 15, SIMPLIFY_M = 8;
    static final int MAX_QUEUE = 500;
    /** Lonjakan GPS (fix melompat) tidak dihitung sebagai kecepatan maksimum. */
    static final float MAX_PLAUSIBLE_KMH = 250;

    private final File dir, current, queue, parked;

    // Trip berjalan (active = true)
    private boolean active;
    private String clientId;
    private long startedAt, lastMoveAt;
    private double moveLat, moveLng; // fix bergerak terakhir: titik akhir trip yang selesai karena diam
    private double distanceM;
    private long movingMs;
    private float maxKmh;
    private final List<double[]> points = new ArrayList<>(); // {lat, lng, waktu}
    // Fix terakhir (juga saat diam): titik awal trip berikutnya dan dasar jarak/selisih waktu.
    private double lastLat, lastLng;
    private long lastT;
    private boolean hasLast;

    TripRecorder(File dir) {
        this.dir = dir;
        current = new File(dir, "current.txt");
        queue = new File(dir, "queue");
        parked = new File(dir, "parked.json");
        //noinspection ResultOfMethodCallIgnored
        queue.mkdirs();
        restore();
    }

    File queueDir() { return queue; }

    File parkedFile() { return parked; }

    boolean active() { return active; }

    /** Satu fix GPS. {@code speedMs} &lt; 0: tidak ada kecepatan dari GPS (dihitung dari jarak). */
    synchronized void feed(long t, double lat, double lng, float speedMs, float accuracyM) {
        if (accuracyM > MAX_ACCURACY_M || t <= lastT) return;
        if (active && t - lastT > GAP_MS) close(lastT, lastLat, lastLng); // mesin mati di antara dua fix
        float step = hasLast ? (float) distance(lastLat, lastLng, lat, lng) : 0;
        long dt = hasLast ? t - lastT : 0;
        float kmh = speedMs >= 0 ? speedMs * 3.6f : dt > 0 && dt <= 10_000 ? step / dt * 3600 : 0;
        // Fix yang diulang dengan posisi sama tapi kecepatan lama (GPS menahan fix terakhir): diam. Bergerak 3 km/j
        // saja sudah pindah 0,8 m per detik.
        if (hasLast && dt <= 2000 && step < 0.5f) kmh = 0;

        if (!active) {
            if (hasLast && kmh >= START_KMH) begin(t, lat, lng); // fix pertama saja belum cukup: kecepatannya bisa basi
        } else {
            if (kmh >= MOVING_KMH) {
                lastMoveAt = t;
                moveLat = lat;
                moveLng = lng;
                if (dt <= 10_000) {
                    distanceM += step;
                    movingMs += dt;
                }
                if (kmh <= MAX_PLAUSIBLE_KMH && accuracyM <= 20 && kmh > maxKmh) maxKmh = kmh;
            }
            double[] p = points.get(points.size() - 1);
            if (kmh >= MOVING_KMH && distance(p[0], p[1], lat, lng) >= POINT_SPACING_M) addPoint(lat, lng, t);
            if (t - lastMoveAt >= STOP_MS) close(lastMoveAt, moveLat, moveLng);
        }
        lastLat = lat;
        lastLng = lng;
        lastT = t;
        hasLast = true;
    }

    /** Dipanggil berkala: GPS hilang (parkir basement) lebih dari 5 menit juga menutup trip. */
    synchronized void tick(long now) {
        if (active && now - lastT > GAP_MS) close(lastT, lastLat, lastLng);
    }

    /** Trip pertama mulai di fix diam terakhir (posisi parkir sebelumnya), bila dekat. */
    private void begin(long t, double lat, double lng) {
        active = true;
        clientId = UUID.randomUUID().toString();
        distanceM = 0;
        movingMs = 0;
        maxKmh = 0;
        points.clear();
        boolean fromLast = hasLast && t - lastT <= 60_000 && distance(lastLat, lastLng, lat, lng) < 200;
        startedAt = fromLast ? lastT : t;
        lastMoveAt = t;
        moveLat = lat;
        moveLng = lng;
        current.delete();
        if (fromLast) addPoint(lastLat, lastLng, lastT);
        addPoint(lat, lng, t);
    }

    private void addPoint(double lat, double lng, long t) {
        points.add(new double[]{lat, lng, t});
        try (Writer w = new OutputStreamWriter(new FileOutputStream(current, true), StandardCharsets.UTF_8)) {
            if (points.size() == 1) w.write("trip " + clientId + " " + startedAt + "\n");
            w.write(lat + " " + lng + " " + t + " " + Math.round(distanceM) + " " + movingMs + " " + maxKmh + "\n");
        } catch (IOException ignored) { }
    }

    /** Trip selesai di posisi dan waktu itu: titik sesudahnya (diam) dibuang, posisi akhirnya jadi titik terakhir. */
    private void close(long end, double lat, double lng) {
        active = false;
        while (points.size() > 1 && points.get(points.size() - 1)[2] > end) points.remove(points.size() - 1);
        if (points.get(points.size() - 1)[2] < end) points.add(new double[]{lat, lng, end});
        writeParked(lat, lng, end);
        if (distanceM >= MIN_TRIP_M && points.size() >= 2) {
            // JSON ditulis sendiri (org.json tidak ada di JVM uji). Polyline berisi karakter 63..126: hanya garis miring terbalik perlu di-escape.
            String json = String.format(Locale.ROOT,
                    "{\"clientId\":\"%s\",\"startedAt\":\"%s\",\"endedAt\":\"%s\",\"distanceM\":%d,\"movingS\":%d,\"maxKmh\":%.1f,\"polyline\":\"%s\"}",
                    clientId, iso(startedAt), iso(Math.max(end, startedAt)), Math.round(distanceM), movingMs / 1000, maxKmh,
                    Polyline.encode(Polyline.simplify(points, SIMPLIFY_M)).replace("\\", "\\\\"));
            write(new File(queue, String.format(Locale.ROOT, "%013d-%s.json", startedAt, clientId)), json);
            trimQueue();
        }
        points.clear();
        current.delete();
    }

    private void writeParked(double lat, double lng, long t) {
        write(parked, String.format(Locale.ROOT, "{\"lat\":%.6f,\"lng\":%.6f,\"at\":\"%s\"}", lat, lng, iso(t)));
    }

    /** Antrean penuh (headunit lama tidak online): yang paling lama dibuang. */
    private void trimQueue() {
        File[] f = queue.listFiles();
        if (f == null || f.length <= MAX_QUEUE) return;
        java.util.Arrays.sort(f);
        for (int i = 0; i < f.length - MAX_QUEUE; i++) f[i].delete();
    }

    /** Proses dimulai ulang: lanjutkan trip dari current.txt (ditutup oleh fix atau tick berikutnya bila sudah lama). */
    private void restore() {
        if (!current.isFile()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(current))) {
            String first = r.readLine();
            String[] head = first != null ? first.split(" ") : new String[0];
            if (head.length < 3) throw new IOException("kepala rusak");
            clientId = head[1];
            startedAt = Long.parseLong(head[2]);
            for (String line; (line = r.readLine()) != null; ) {
                String[] p = line.split(" ");
                if (p.length < 6) break; // baris terakhir terpotong
                double lat = Double.parseDouble(p[0]), lng = Double.parseDouble(p[1]);
                long t = Long.parseLong(p[2]);
                points.add(new double[]{lat, lng, t});
                distanceM = Long.parseLong(p[3]);
                movingMs = Long.parseLong(p[4]);
                maxKmh = Float.parseFloat(p[5]);
                lastLat = lat;
                lastLng = lng;
                lastT = lastMoveAt = t;
                moveLat = lat;
                moveLng = lng;
                hasLast = true;
            }
            active = !points.isEmpty();
        } catch (IOException | RuntimeException e) {
            points.clear();
            active = false;
        }
        if (!active) current.delete();
    }

    static void write(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(s);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    static String iso(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }

    /** Jarak permukaan bumi (haversine), meter. */
    static double distance(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371008.8, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Google encoded polyline (presisi 5) dan penyederhanaan Douglas–Peucker. */
    static final class Polyline {
        private Polyline() { }

        static String encode(List<double[]> pts) {
            StringBuilder b = new StringBuilder();
            long pLat = 0, pLng = 0;
            for (double[] p : pts) {
                long lat = Math.round(p[0] * 1e5), lng = Math.round(p[1] * 1e5);
                put(b, lat - pLat);
                put(b, lng - pLng);
                pLat = lat;
                pLng = lng;
            }
            return b.toString();
        }

        private static void put(StringBuilder b, long v) {
            long x = v < 0 ? ~(v << 1) : v << 1;
            while (x >= 0x20) {
                b.append((char) ((0x20 | (x & 0x1f)) + 63));
                x >>= 5;
            }
            b.append((char) (x + 63));
        }

        static List<double[]> simplify(List<double[]> pts, double tolM) {
            if (pts.size() < 3) return new ArrayList<>(pts);
            boolean[] keep = new boolean[pts.size()];
            keep[0] = keep[pts.size() - 1] = true;
            java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
            stack.push(new int[]{0, pts.size() - 1});
            while (!stack.isEmpty()) {
                int[] s = stack.pop();
                double worst = 0;
                int at = -1;
                for (int i = s[0] + 1; i < s[1]; i++) {
                    double d = offset(pts.get(i), pts.get(s[0]), pts.get(s[1]));
                    if (d > worst) { worst = d; at = i; }
                }
                if (at >= 0 && worst > tolM) {
                    keep[at] = true;
                    stack.push(new int[]{s[0], at});
                    stack.push(new int[]{at, s[1]});
                }
            }
            List<double[]> out = new ArrayList<>();
            for (int i = 0; i < pts.size(); i++) if (keep[i]) out.add(pts.get(i));
            return out;
        }

        /** Jarak titik ke ruas a–b dalam meter (proyeksi datar lokal; cukup untuk ruas pendek). */
        private static double offset(double[] p, double[] a, double[] b) {
            double k = Math.cos(Math.toRadians(a[0])) * 111320, m = 110540;
            double px = (p[1] - a[1]) * k, py = (p[0] - a[0]) * m, bx = (b[1] - a[1]) * k, by = (b[0] - a[0]) * m;
            double len = bx * bx + by * by;
            double u = len == 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / len));
            double dx = px - u * bx, dy = py - u * by;
            return Math.sqrt(dx * dx + dy * dy);
        }
    }
}
