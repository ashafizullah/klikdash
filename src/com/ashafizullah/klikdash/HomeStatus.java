package com.ashafizullah.klikdash;

import android.widget.TextView;

import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.HOT;
import static com.ashafizullah.klikdash.HomeActivity.WARN;
import static com.ashafizullah.klikdash.HomeActivity.show;

/** Kondisi headunit di baris status home: suhu CPU, RAM, dan penyimpanan terpakai; diperbarui tiap 5 detik selama home tampil. */
final class HomeStatus {
    final HomeActivity h;
    TextView temp, ram, disk;
    /** Panel kiri sempit (headunit 800/1024 px): label pendek supaya baris status tidak terpotong. */
    String diskLabel;

    /** Berkas suhu CPU; dicari sekali per proses (memindai /sys/class/thermal). */
    static String cpuZone;
    /** Hitungan tick sejak home tampil; penyimpanan jarang berubah, jadi dibaca tiap 12 tick (1 menit). */
    int ticks;
    final Runnable tick = new Runnable() {
        @Override public void run() {
            updateTemp();
            updateMemory(ticks++ % 12 == 0);
            temp.postDelayed(this, 5000);
        }
    };

    HomeStatus(HomeActivity h) { this.h = h; }

    void start() {
        temp.removeCallbacks(tick);
        ticks = 0;
        tick.run();
    }

    void stop() { temp.removeCallbacks(tick); }

    static String findCpuZone() {
        java.io.File[] zones = new java.io.File("/sys/class/thermal").listFiles();
        if (zones != null) {
            for (java.io.File z : zones) {
                String type = readLine(new java.io.File(z, "type").getPath());
                if (type != null && type.contains("cpu")) return z.getPath() + "/temp";
            }
        }
        return "/sys/class/thermal/thermal_zone0/temp";
    }

    static String readLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            String s = r.readLine();
            return s == null ? null : s.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** RAM dan (bila {@code withDisk}) penyimpanan internal yang terpakai, dalam persen; peringatan di atas 85% dan 90%. */
    void updateMemory(boolean withDisk) {
        long total = 0, avail = -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            for (String l; (l = r.readLine()) != null && (total == 0 || avail < 0); ) {
                if (l.startsWith("MemTotal:")) total = kb(l);
                else if (l.startsWith("MemAvailable:")) avail = kb(l);
            }
        } catch (Exception ignored) { }
        if (total > 0 && avail >= 0) {
            int p = (int) Math.round(100.0 * (total - avail) / total);
            show(ram, h.getString(R.string.home_ram, p), p >= 85 ? WARN : DIM);
        } else {
            show(ram, "", DIM);
        }
        if (!withDisk) return;
        android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
        long size = fs.getTotalBytes();
        int p = size > 0 ? (int) Math.round(100.0 * (size - fs.getAvailableBytes()) / size) : 0;
        show(disk, diskLabel + " " + p + "%", p >= 90 ? WARN : DIM);
    }

    /** "MemTotal:        4194304 kB" → 4194304 */
    static long kb(String line) {
        long v = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch >= '0' && ch <= '9') v = v * 10 + (ch - '0');
        }
        return v;
    }

    void updateTemp() {
        if (cpuZone == null) cpuZone = findCpuZone();
        String s = readLine(cpuZone);
        if (s == null) { show(temp, ""); return; }
        int c;
        try {
            c = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            show(temp, "");
            return;
        }
        if (c > 1000) c = Math.round(c / 1000f); // sensor melaporkan mili-derajat
        show(temp, h.getString(c >= 85 ? R.string.home_cpu_hot : R.string.home_cpu, c), c >= 85 ? HOT : c >= 70 ? WARN : DIM);
    }
}
