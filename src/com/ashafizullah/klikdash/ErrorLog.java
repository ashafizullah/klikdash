package com.ashafizullah.klikdash;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Laporan crash ke server KlikBeam (/v1/errors, dilihat di /admin/errors). Crash ditulis ke file saat aplikasi tertutup,
 * lalu dikirim berikutnya saat headunit online dan jamnya sudah benar. Isinya hanya pesan, jejak kode, bagian aplikasi,
 * versi, dan model headunit; token headunit ikut bila terhubung ke akun KlikBeam. Bisa dimatikan di ⚙ › PEMBARUAN.
 */
final class ErrorLog {
    private ErrorLog() { }

    static final String PREF = "crashReports";
    private static final String FILE = "errors.jsonl";
    private static final int MAX_QUEUED = 20;
    /** Kirim paling sering tiap 10 menit (dipanggil tiap home tampil). */
    private static final long FLUSH_EVERY_MS = 10 * 60_000L;
    private static boolean installed;
    private static long lastFlush = -FLUSH_EVERY_MS;

    static boolean enabled(Context c) { return Prefs.of(c).getBoolean(PREF, true); }

    static void setEnabled(Context c, boolean on) {
        Prefs.of(c).edit().putBoolean(PREF, on).apply();
        if (!on) synchronized (ErrorLog.class) { write(c, new ArrayList<>()); }
    }

    /** Pasang penangkap crash (sekali per proses, dari {@link App}). */
    static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        Context c = context.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            // Langsung ke file: proses akan berhenti, tidak sempat kirim ke jaringan.
            try { if (enabled(c)) queue(c, entry(c, e, "uncaught:" + t.getName())); } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** Kejadian yang bukan crash (mis. pengaturan dipulihkan dari cadangan), dikirim bersama laporan crash. */
    static void report(Context c, String message, String where) {
        try {
            queue(c, new JSONObject()
                    .put("kind", "error")
                    .put("message", message.length() > 1000 ? message.substring(0, 1000) : message)
                    .put("context", where)
                    .put("appVersion", version(c))
                    .put("osVersion", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
                    .put("device", (Build.MANUFACTURER + " " + Build.MODEL).trim())
                    .put("package", c.getPackageName()) // server membuang laporan salinan berpaket lain
                    .put("at", System.currentTimeMillis()));
        } catch (Throwable ignored) { }
    }

    /** Kirim yang tertunda di latar; gagal (offline, jam belum benar) = coba lagi nanti. */
    static void flush(Context context) {
        Context c = context.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        if (now - lastFlush < FLUSH_EVERY_MS || System.currentTimeMillis() < Built.TIME || !enabled(c)) return;
        lastFlush = now;
        Account.NET.execute(() -> send(c));
    }

    private static JSONObject entry(Context c, Throwable e, String where) throws Exception {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String stack = sw.toString();
        String msg = e.getClass().getName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        return new JSONObject()
                .put("kind", "crash")
                .put("message", msg.length() > 1000 ? msg.substring(0, 1000) : msg)
                .put("stack", stack.length() > 16_000 ? stack.substring(0, 16_000) : stack)
                .put("context", where)
                .put("appVersion", version(c))
                .put("osVersion", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
                .put("device", (Build.MANUFACTURER + " " + Build.MODEL).trim())
                .put("package", c.getPackageName())
                .put("at", System.currentTimeMillis());
    }

    private static String version(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            @SuppressWarnings("deprecation") long code = Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
            return p.versionName + " (" + code + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    private static synchronized void queue(Context c, JSONObject item) throws Exception {
        List<String> lines = read(c);
        lines.add(item.toString());
        while (lines.size() > MAX_QUEUED) lines.remove(0);
        write(c, lines);
    }

    private static void send(Context c) {
        List<String> lines;
        synchronized (ErrorLog.class) { lines = read(c); }
        if (lines.isEmpty()) return;
        try {
            JSONArray items = new JSONArray();
            for (String l : lines) items.put(new JSONObject(l));
            Account.call(c, "POST", "/v1/errors", Account.deviceAuth(c),
                    new JSONObject().put("platform", "klikdash").put("items", items).toString());
            synchronized (ErrorLog.class) {
                List<String> now = read(c);
                write(c, now.subList(Math.min(lines.size(), now.size()), now.size()));
            }
        } catch (Account.ApiError e) {
            // Ditolak server (isi tidak valid): buang, supaya tidak dikirim ulang terus.
            if (e.rejected()) synchronized (ErrorLog.class) { write(c, new ArrayList<>()); }
        } catch (Exception ignored) { }
    }

    /** File di penyimpanan DE, sama dengan {@link Prefs}: bisa ditulis saat direct boot. */
    private static File file(Context c) { return new File(c.createDeviceProtectedStorageContext().getFilesDir(), FILE); }

    private static List<String> read(Context c) {
        File f = file(c);
        List<String> out = new ArrayList<>();
        if (!f.exists()) return out;
        try {
            for (String l : AiClient.read(new FileInputStream(f)).split("\n")) if (!l.isEmpty()) out.add(l);
        } catch (Exception ignored) { }
        return out;
    }

    private static void write(Context c, List<String> lines) {
        File f = file(c);
        if (lines.isEmpty()) { f.delete(); return; }
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }
}
