package com.ashafizullah.klikdash;

import java.util.Calendar;

/**
 * Tanggal Hijriah: Umm al-Qura (ICU bawaan Android), cocok dengan kalender Kemenag untuk Oktober 2026. Koreksi ±2 hari
 * diatur di Pengaturan (atau ketuk tanggalnya di home), untuk jaga-jaga bila hasil rukyat berbeda.
 */
final class Hijri {
    private Hijri() { }

    /** Teks Hijriah hanya berganti bila tanggal atau koreksinya berganti; IslamicCalendar (ICU) mahal dibuat tiap menit. */
    private static String cache;
    private static long cacheKey = -1;

    /** Untuk home yang memperbarui tanggal tiap menit: hasil disimpan selama tanggal dan koreksinya sama. */
    static String today(long now, int offsetDays) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        long key = (c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)) * 100 + offsetDays + 50;
        if (key != cacheKey) {
            cacheKey = key;
            cache = text(now, offsetDays);
        }
        return cache;
    }

    static String text(long now, int offsetDays) {
        android.icu.util.IslamicCalendar c = new android.icu.util.IslamicCalendar();
        c.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        c.setTimeInMillis(now + offsetDays * 86_400_000L);
        // Dipakai juga Pengaturan tanpa home: akhiran tahun dari bahasa, bukan resource.
        return c.get(android.icu.util.Calendar.DAY_OF_MONTH) + " " + Lang.hijriMonths[c.get(android.icu.util.Calendar.MONTH)]
                + " " + c.get(android.icu.util.Calendar.YEAR) + (Lang.en() ? " AH" : " H");
    }
}
