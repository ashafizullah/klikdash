package com.ashafizullah.klikdash;

import java.util.Calendar;

/**
 * Jadwal sholat dihitung di perangkat (tanpa internet). Dua metode ({@link #method}):
 * - Kemenag RI: Subuh saat matahari 20° di bawah ufuk, Isya 18°, ihtiyath +2 menit.
 * - JAKIM (Malaysia): Subuh 18°, Isyak 18°, ihtiyath +1 menit (Zuhur +2). Dicocokkan dengan jadwal resmi e-solat 2026
 *   enam zona (TRG01, WLY01, JHR02, SBH07, SWK08, KDH01) di koordinat kotanya: ±1 menit pada 92–93% hari. Sisanya karena
 *   JAKIM menghitung per zona dari titik rujukan, KlikDash dari lokasi GPS.
 * Keduanya: Ashar madzhab Syafi'i (panjang bayangan = tinggi benda + bayangan saat zawal), Maghrib saat tinggi matahari
 * -0,833° (piringan atas di ufuk, termasuk refraksi), dibulatkan ke atas.
 *
 * Posisi matahari: rumus "Approximate Solar Coordinates" dari U.S. Naval Observatory.
 * Hari Julian: Meeus, Astronomical Algorithms, bab 7.
 * Tiap waktu dicari bertahap: tebak jamnya (UT), hitung posisi matahari pada jam itu, perbaiki jamnya, ulangi.
 */
final class PrayerTimes {
    static final int AUTO = -1, KEMENAG = 0, JAKIM = 1;

    /** Per metode: tinggi matahari Subuh dan Isya, lalu ihtiyath (menit) Subuh, Dzuhur, Ashar, Maghrib, Isya. */
    private static final double[][] ALT = {{Math.toRadians(-20), Math.toRadians(-18)}, {Math.toRadians(-18), Math.toRadians(-18)}};
    private static final int[][] IHTIYATH = {{2, 2, 2, 2, 2}, {1, 2, 1, 1, 1}};
    private static final double SUNSET_ALT = Math.toRadians(-0.833);
    private static final double ASHAR_SHADOW = 1; // Syafi'i; Hanafi = 2
    private static final int PASSES = 3;

    // Urutan hasil compute().
    private static final int SUBUH = 0, DZUHUR = 1, ASHAR = 2, MAGHRIB = 3, ISYA = 4;

    private PrayerTimes() { }

    /**
     * Metode pilihan Pengaturan (pref "prayerMethod"); bawaan otomatis: JAKIM bila zona waktu headunit Malaysia,
     * selain itu Kemenag.
     */
    static int method(android.content.SharedPreferences p) {
        int m = p.getInt("prayerMethod", AUTO);
        return m != AUTO ? m : autoMethod(java.util.TimeZone.getDefault().getID());
    }

    static int autoMethod(String tz) {
        return tz.equals("Asia/Kuala_Lumpur") || tz.equals("Asia/Kuching") ? JAKIM : KEMENAG;
    }

    /** Menit sejak tengah malam (waktu lokal) untuk 5 waktu sholat pada hari {@code day}. */
    static int[] compute(Calendar day, double lat, double lon, int method) {
        double jd0 = julianDayAt0hUT(day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1,
                day.get(Calendar.DAY_OF_MONTH));
        double zone = day.getTimeZone().getOffset(day.getTimeInMillis()) / 3600000.0;
        double phi = Math.toRadians(lat);

        int[] minutes = new int[5];
        for (int prayer = SUBUH; prayer <= ISYA; prayer++) {
            double local = utHourOf(prayer, jd0, phi, lon, ALT[method]) + zone;
            local -= 24 * Math.floor(local / 24);
            minutes[prayer] = (int) Math.ceil(local * 60) + IHTIYATH[method][prayer]; // dibulatkan ke atas
        }
        return minutes;
    }

    /** Menit sejak tengah malam saat matahari terbit dan terbenam pada hari {@code day}, tanpa ihtiyath: {terbit, terbenam}. */
    static int[] sun(Calendar day, double lat, double lon) {
        double jd0 = julianDayAt0hUT(day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1,
                day.get(Calendar.DAY_OF_MONTH));
        double zone = day.getTimeZone().getOffset(day.getTimeInMillis()) / 3600000.0;
        double phi = Math.toRadians(lat);
        int[] minutes = new int[2];
        for (int i = 0; i < 2; i++) {
            double ut = 12 - lon / 15;
            for (int pass = 0; pass < PASSES; pass++) {
                double[] sun = solarCoordinates(jd0 + ut / 24);
                double transit = 12 - lon / 15 - sun[1], h = hoursFromTransit(SUNSET_ALT, phi, sun[0]);
                ut = i == 0 ? transit - h : transit + h;
            }
            double local = ut + zone;
            local -= 24 * Math.floor(local / 24);
            minutes[i] = (int) Math.round(local * 60);
        }
        return minutes;
    }

    /** Belum pernah dapat lokasi GPS: ibu kota zona waktu, {lat, lon}. Nama lewat {@link #guessCity}. */
    static double[] guess(String tz) {
        if (tz.equals("Asia/Kuala_Lumpur")) return new double[]{3.1390, 101.6869};
        if (tz.equals("Asia/Kuching")) return new double[]{1.5535, 110.3593};
        if (tz.equals("Asia/Makassar")) return new double[]{-5.1477, 119.4327};
        if (tz.equals("Asia/Jayapura")) return new double[]{-2.5337, 140.7181};
        if (tz.equals("Asia/Pontianak")) return new double[]{-0.0263, 109.3425};
        return new double[]{-6.2088, 106.8456};
    }

    static String guessCity(String tz) {
        if (tz.equals("Asia/Kuala_Lumpur")) return "KUALA LUMPUR";
        if (tz.equals("Asia/Kuching")) return "KUCHING";
        if (tz.equals("Asia/Makassar")) return "MAKASSAR";
        if (tz.equals("Asia/Jayapura")) return "JAYAPURA";
        if (tz.equals("Asia/Pontianak")) return "PONTIANAK";
        return "JAKARTA";
    }

    /** Jam UT (boleh di luar 0..24) saat waktu {@code prayer} tiba, untuk hari Julian {@code jd0} pukul 0 UT. */
    private static double utHourOf(int prayer, double jd0, double phi, double lon, double[] alt) {
        double ut = 12 - lon / 15; // tebakan awal: tengah hari rata-rata
        for (int pass = 0; pass < PASSES; pass++) {
            double[] sun = solarCoordinates(jd0 + ut / 24);
            double decl = sun[0];
            double transit = 12 - lon / 15 - sun[1];
            if (prayer == DZUHUR) {
                ut = transit;
                continue;
            }
            double fromTransit = hoursFromTransit(altitudeFor(prayer, phi, decl, alt), phi, decl);
            ut = prayer == SUBUH ? transit - fromTransit : transit + fromTransit;
        }
        return ut;
    }

    /** Tinggi matahari (radian) yang menandai waktu {@code prayer}. */
    private static double altitudeFor(int prayer, double phi, double decl, double[] alt) {
        switch (prayer) {
            case SUBUH: return alt[0];
            case ISYA: return alt[1];
            case MAGHRIB: return SUNSET_ALT;
            default: // ASHAR: bayangan = ASHAR_SHADOW x tinggi benda + bayangan saat matahari tertinggi
                return Math.atan(1 / (ASHAR_SHADOW + Math.tan(Math.abs(phi - decl))));
        }
    }

    /** Selisih jam dari transit sampai matahari setinggi {@code alt} (semua sudut dalam radian). */
    private static double hoursFromTransit(double alt, double phi, double decl) {
        double cosH = (Math.sin(alt) - Math.sin(phi) * Math.sin(decl)) / (Math.cos(phi) * Math.cos(decl));
        cosH = Math.max(-1, Math.min(1, cosH)); // lintang tinggi: matahari tidak pernah setinggi itu
        return Math.toDegrees(Math.acos(cosH)) / 15;
    }

    /**
     * USNO Approximate Solar Coordinates. Hasil: {deklinasi (radian), equation of time (jam, -12..12)}.
     * Galat sekitar 0,01° untuk tahun 1800-2200, jauh di bawah satu menit waktu sholat.
     */
    private static double[] solarCoordinates(double jd) {
        double n = jd - 2451545.0;                                   // hari sejak J2000.0
        double meanAnomaly = Math.toRadians((357.529 + 0.98560028 * n) % 360);
        double meanLongitude = (280.459 + 0.98564736 * n) % 360;     // derajat
        double eclLongitude = Math.toRadians(meanLongitude
                + 1.915 * Math.sin(meanAnomaly) + 0.020 * Math.sin(2 * meanAnomaly));
        double obliquity = Math.toRadians(23.439 - 0.00000036 * n);

        double decl = Math.asin(Math.sin(obliquity) * Math.sin(eclLongitude));
        double rightAscension = Math.toDegrees(Math.atan2(
                Math.cos(obliquity) * Math.sin(eclLongitude), Math.cos(eclLongitude))) / 15;
        double eqTime = meanLongitude / 15 - rightAscension;
        eqTime -= 24 * Math.floor((eqTime + 12) / 24);               // ke rentang -12..12
        return new double[]{decl, eqTime};
    }

    /** Hari Julian pada pukul 0 UT tanggal Gregorian y-m-d (Meeus, Astronomical Algorithms, rumus 7.1). */
    private static double julianDayAt0hUT(int y, int m, int d) {
        if (m < 3) {
            y--;
            m += 12;
        }
        int century = y / 100;
        int gregorian = 2 - century + century / 4;
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + gregorian - 1524.5;
    }
}
