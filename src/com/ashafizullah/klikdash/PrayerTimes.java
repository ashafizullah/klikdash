package com.ashafizullah.klikdash;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Hitung jadwal sholat secara lokal (tanpa internet), metode Kemenag RI:
 * Subuh 20°, Isya 18°, Ashar madzhab Syafi'i, ihtiyath +2 menit.
 * Algoritma posisi matahari mengikuti praytimes.org.
 */
final class PrayerTimes {
    static final String[] NAMES = {"SUBUH", "DZUHUR", "ASHAR", "MAGHRIB", "ISYA"};
    static final int IHTIYATH = 2;

    private PrayerTimes() { }

    /** Menit sejak tengah malam (waktu lokal) untuk 5 waktu sholat pada hari {@code day}. */
    static int[] compute(Calendar day, double lat, double lon) {
        int y = day.get(Calendar.YEAR), m = day.get(Calendar.MONTH) + 1, d = day.get(Calendar.DAY_OF_MONTH);
        TimeZone tz = day.getTimeZone();
        double tzHours = tz.getOffset(day.getTimeInMillis()) / 3600000.0;
        double jd = julian(y, m, d) - lon / (15 * 24.0);

        double[] t = {
                sunAngleTime(jd, lat, 20, 5 / 24.0, true),     // subuh
                midDay(jd, 12 / 24.0),                          // dzuhur
                asrTime(jd, lat, 1, 13 / 24.0),                 // ashar
                sunAngleTime(jd, lat, 0.833, 18 / 24.0, false), // maghrib
                sunAngleTime(jd, lat, 18, 18 / 24.0, false),    // isya
        };
        int[] out = new int[t.length];
        for (int i = 0; i < t.length; i++) {
            double h = t[i] + tzHours - lon / 15.0;
            out[i] = (int) Math.ceil(fixHour(h) * 60) + IHTIYATH; // dibulatkan ke atas seperti Kemenag
        }
        return out;
    }

    private static double julian(int y, int m, int d) {
        if (m <= 2) { y -= 1; m += 12; }
        double a = Math.floor(y / 100.0);
        double b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    /** {declination, equationOfTime} untuk julian date {@code jd}. */
    private static double[] sun(double jd) {
        double dd = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * dd);
        double q = fixAngle(280.459 + 0.98564736 * dd);
        double l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g));
        double e = 23.439 - 0.00000036 * dd;
        double ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15;
        double eqt = q / 15 - fixHour(ra);
        double decl = darcsin(dsin(e) * dsin(l));
        return new double[]{decl, eqt};
    }

    private static double midDay(double jd, double t) {
        return fixHour(12 - sun(jd + t)[1]);
    }

    private static double sunAngleTime(double jd, double lat, double angle, double t, boolean beforeNoon) {
        double decl = sun(jd + t)[0];
        double noon = midDay(jd, t);
        double v = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15.0;
        return noon + (beforeNoon ? -v : v);
    }

    private static double asrTime(double jd, double lat, double factor, double t) {
        double decl = sun(jd + t)[0];
        double angle = -darccot(factor + dtan(Math.abs(lat - decl)));
        return sunAngleTime(jd, lat, angle, t, false);
    }

    private static double dsin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double dcos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double dtan(double d) { return Math.tan(Math.toRadians(d)); }
    private static double darcsin(double x) { return Math.toDegrees(Math.asin(x)); }
    private static double darccos(double x) { return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, x)))); }
    private static double darctan2(double y, double x) { return Math.toDegrees(Math.atan2(y, x)); }
    private static double darccot(double x) { return Math.toDegrees(Math.atan(1 / x)); }
    private static double fixAngle(double a) { a = a - 360 * Math.floor(a / 360); return a < 0 ? a + 360 : a; }
    private static double fixHour(double a) { a = a - 24 * Math.floor(a / 24); return a < 0 ? a + 24 : a; }
}
