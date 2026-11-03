package com.ashafizullah.klikdash;

import android.content.SharedPreferences;

import java.util.Arrays;

/**
 * Metrik OBD: yang menyala tampil di home menurut urutan di Pengaturan (daftar yang bisa diseret). {@code obdOrder}
 * menyimpan urutan semua metrik, {@code obdHome} yang menyala.
 */
final class ObdPrefs {
    static final int RPM = 0, SPEED = 1, COOLANT = 2, VOLTAGE = 3, FUEL = 4, LOAD = 5, THROTTLE = 6, INTAKE = 7,
            MAP = 8, MAF = 9, OIL = 10;
    private static final int[] ALL = {RPM, SPEED, COOLANT, VOLTAGE, FUEL, LOAD, THROTTLE, INTAKE, MAP, MAF, OIL};
    static final int COUNT = ALL.length;
    /** Batas yang tampil di home: satu baris kartu. */
    static final int MIN = 2, MAX = 4;
    private static final String KEY = "obdHome", ORDER = "obdOrder", SHOW = "obdShow", AUTO = "obdAuto", MAC = "obdAdapter";
    private static final int[] DEFAULT = {RPM, SPEED, COOLANT};

    private ObdPrefs() { }

    /** Semua metrik menurut urutan pilihan pengguna; yang belum pernah diurutkan ditaruh di belakang. */
    static int[] order(SharedPreferences p) {
        int[] out = new int[COUNT];
        int n = 0;
        for (int id : parse(p == null ? null : p.getString(ORDER, null))) if (!contains(out, n, id)) out[n++] = id;
        for (int id : ALL) if (!contains(out, n, id)) out[n++] = id;
        return out;
    }

    static void setOrder(SharedPreferences p, int[] order) { p.edit().putString(ORDER, join(order)).apply(); }

    /** Metrik yang menyala, menurut urutan. */
    static int[] enabled(SharedPreferences p) {
        int[] on = p == null ? DEFAULT : parse(p.getString(KEY, null));
        if (on.length < MIN) on = DEFAULT;
        int[] ord = order(p);
        int[] out = new int[ord.length];
        int n = 0;
        for (int id : ord) if (contains(on, on.length, id) && n < MAX) out[n++] = id;
        return Arrays.copyOf(out, n);
    }

    /** Berubah bila tata letak home harus dibangun ulang: tampil/sembunyi, metrik yang menyala, urutannya. */
    static String signature(SharedPreferences p) { return (show(p) ? "1:" : "0:") + join(enabled(p)); }

    static boolean isOn(SharedPreferences p, int id) { return contains(enabled(p), enabled(p).length, id); }

    /** Nyalakan/matikan metrik; gagal bila melanggar min/max. */
    static boolean toggle(SharedPreferences p, int id, boolean on) {
        int[] cur = enabled(p);
        boolean has = contains(cur, cur.length, id);
        if (on == has) return true;
        if (on ? cur.length >= MAX : cur.length <= MIN) return false;
        int[] next = new int[on ? cur.length + 1 : cur.length - 1];
        int n = 0;
        for (int x : cur) if (x != id) next[n++] = x;
        if (on) next[n] = id;
        p.edit().putString(KEY, join(next)).commit();
        return true;
    }

    /** Kartu OBD di home tampil atau disembunyikan; koneksinya tidak ikut berhenti. */
    static boolean show(SharedPreferences p) { return p == null || p.getBoolean(SHOW, true); }

    static void setShow(SharedPreferences p, boolean on) { p.edit().putBoolean(SHOW, on).commit(); }

    /** Sambung otomatis selama KlikDash hidup; mati hanya bila pengguna memutuskannya. */
    static boolean auto(SharedPreferences p) { return p == null || p.getBoolean(AUTO, true); }

    static void setAuto(SharedPreferences p, boolean on) { p.edit().putBoolean(AUTO, on).apply(); }

    /** Alamat adapter pilihan pengguna; kosong = cari yang namanya mirip adapter OBD. */
    static String mac(SharedPreferences p) { return p == null ? "" : p.getString(MAC, ""); }

    static void setMac(SharedPreferences p, String mac) { p.edit().putString(MAC, mac).apply(); }

    static int pid(int id) {
        switch (id) {
            case RPM: return 0x0C;
            case SPEED: return 0x0D;
            case COOLANT: return 0x05;
            case VOLTAGE: return 0x42;
            case FUEL: return 0x2F;
            case LOAD: return 0x04;
            case THROTTLE: return 0x11;
            case INTAKE: return 0x0F;
            case MAP: return 0x0B;
            case MAF: return 0x10;
            case OIL: return 0x5C;
            default: return -1;
        }
    }

    static int labelRes(int id) {
        switch (id) {
            case SPEED: return R.string.settings_obd_speed;
            case COOLANT: return R.string.settings_obd_coolant;
            case VOLTAGE: return R.string.settings_obd_voltage;
            case FUEL: return R.string.settings_obd_fuel;
            case LOAD: return R.string.settings_obd_load;
            case THROTTLE: return R.string.settings_obd_throttle;
            case INTAKE: return R.string.settings_obd_intake;
            case MAP: return R.string.settings_obd_map;
            case MAF: return R.string.settings_obd_maf;
            case OIL: return R.string.settings_obd_oil;
            default: return R.string.settings_obd_rpm;
        }
    }

    static int unitRes(int id) {
        switch (id) {
            case SPEED: return R.string.home_obd_unit_speed;
            case COOLANT: return R.string.home_obd_unit_coolant;
            case VOLTAGE: return R.string.home_obd_unit_voltage;
            case FUEL: return R.string.home_obd_unit_fuel;
            case LOAD: return R.string.home_obd_unit_load;
            case THROTTLE: return R.string.home_obd_unit_throttle;
            case INTAKE: return R.string.home_obd_unit_intake;
            case MAP: return R.string.home_obd_unit_map;
            case MAF: return R.string.home_obd_unit_maf;
            case OIL: return R.string.home_obd_unit_oil;
            default: return R.string.home_obd_unit_rpm;
        }
    }

    /** Id yang valid dan unik dari "3,0,2"; sisanya dibuang. */
    private static int[] parse(String s) {
        if (s == null || s.isEmpty()) return new int[0];
        int[] out = new int[COUNT];
        int n = 0;
        for (String part : s.split(",")) {
            try {
                int id = Integer.parseInt(part.trim());
                if (id >= 0 && id < COUNT && !contains(out, n, id)) out[n++] = id;
            } catch (NumberFormatException ignored) { }
        }
        return Arrays.copyOf(out, n);
    }

    private static String join(int[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) b.append(',');
            b.append(a[i]);
        }
        return b.toString();
    }

    private static boolean contains(int[] a, int n, int v) {
        for (int i = 0; i < n; i++) if (a[i] == v) return true;
        return false;
    }
}
