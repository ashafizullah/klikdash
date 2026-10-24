package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Perintah mobil yang boleh dijalankan AI (function calling). Satu daftar untuk Claude dan OpenAI-compatible.
 * Setiap alat mengembalikan teks singkat untuk dibaca model, bukan untuk pengemudi.
 */
final class AiTools {
    static final Locale ID = new Locale("id", "ID");

    /** {nama, deskripsi, JSON schema parameter}. */
    static final String[][] TOOLS = {
            {"open_app", "Buka aplikasi yang terpasang di headunit. Pakai nama persis dari daftar aplikasi di instruksi sistem.",
                    "{\"type\":\"object\",\"properties\":{\"app\":{\"type\":\"string\",\"description\":\"Nama aplikasi\"}},"
                            + "\"required\":[\"app\"],\"additionalProperties\":false}"},
            {"navigate", "Mulai navigasi ke tujuan (nama tempat atau alamat), misalnya 'masjid terdekat' atau 'Alun-alun Kendal'.",
                    "{\"type\":\"object\",\"properties\":{\"destination\":{\"type\":\"string\"},"
                            + "\"app\":{\"type\":\"string\",\"enum\":[\"maps\",\"waze\"],\"description\":\"Default maps\"}},"
                            + "\"required\":[\"destination\"],\"additionalProperties\":false}"},
            {"play_quran", "Putar murattal Al-Qur'an (Mishary Alafasy). Surah boleh nomor (1-114) atau nama Latin, misalnya 'Yasin'.",
                    "{\"type\":\"object\",\"properties\":{\"surah\":{\"type\":\"string\"},"
                            + "\"ayah\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"Default 1\"}},"
                            + "\"required\":[\"surah\"],\"additionalProperties\":false}"},
            {"media_control", "Kendalikan audio yang sedang diputar (murattal, musik, radio).",
                    "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\","
                            + "\"enum\":[\"play\",\"pause\",\"next\",\"previous\"]}},\"required\":[\"action\"],\"additionalProperties\":false}"},
            {"set_volume", "Atur volume media. Isi level 0-100, atau change naik/turun.",
                    "{\"type\":\"object\",\"properties\":{\"level\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100},"
                            + "\"change\":{\"type\":\"string\",\"enum\":[\"up\",\"down\"]}},\"additionalProperties\":false}"},
            {"prayer_times", "Jadwal sholat hari ini di lokasi mobil, dan waktu sholat berikutnya.",
                    "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"},
            {"open_dzikir", "Buka dzikir pagi atau petang.",
                    "{\"type\":\"object\",\"properties\":{\"period\":{\"type\":\"string\",\"enum\":[\"pagi\",\"petang\"],"
                            + "\"description\":\"Kosongkan untuk mengikuti waktu sekarang\"}},\"additionalProperties\":false}"},
            {"restrict_background", "Larang (restrict=true) atau izinkan (false) aplikasi berjalan di latar, untuk menghemat daya.",
                    "{\"type\":\"object\",\"properties\":{\"app\":{\"type\":\"string\"},\"restrict\":{\"type\":\"boolean\"}},"
                            + "\"required\":[\"app\",\"restrict\"],\"additionalProperties\":false}"},
            {"car_status", "Status headunit: jam, tanggal Masehi & Hijriah, kecepatan, suhu CPU, dan lokasi.",
                    "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"},
            {"go_home", "Kembali ke layar home KlikDash.",
                    "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"},
    };

    static JSONArray claudeTools() throws Exception {
        JSONArray a = new JSONArray();
        for (String[] t : TOOLS) {
            a.put(new JSONObject().put("name", t[0]).put("description", t[1]).put("input_schema", new JSONObject(t[2])));
        }
        return a;
    }

    static JSONArray openaiTools() throws Exception {
        JSONArray a = new JSONArray();
        for (String[] t : TOOLS) {
            a.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                    .put("name", t[0]).put("description", t[1]).put("parameters", new JSONObject(t[2]))));
        }
        return a;
    }

    // ---------------------------------------------------------------- konteks untuk instruksi sistem

    static final class App {
        ComponentName cn;
        String label;
    }

    static List<App> apps(Activity a) {
        List<App> out = new ArrayList<>();
        PackageManager pm = a.getPackageManager();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (HomeActivity.class.getName().equals(r.activityInfo.name)) continue;
            App x = new App();
            x.cn = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            x.label = String.valueOf(r.loadLabel(pm)).trim();
            out.add(x);
        }
        return out;
    }

    /** Instruksi sistem: singkat, untuk pengemudi, dengan daftar aplikasi dan waktu sekarang. */
    static String systemPrompt(Activity a) {
        StringBuilder names = new StringBuilder();
        for (App x : apps(a)) names.append(names.length() > 0 ? ", " : "").append(x.label);
        String now = new SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", ID).format(new Date());
        return "Kamu asisten suara KlikDash di headunit mobil. Pengemudi sedang menyetir dan mendengarkan jawabanmu "
                + "lewat speaker, jadi jawab dalam bahasa Indonesia, sangat singkat (paling banyak dua kalimat), tanpa "
                + "markdown, daftar, atau emoji, dan jangan minta pengemudi melihat atau menyentuh layar. Untuk perintah "
                + "mobil, panggil alat yang sesuai lalu konfirmasi singkat apa yang sudah dilakukan. Kalau permintaan "
                + "tidak jelas, tanya balik dengan satu pertanyaan pendek. Sekarang " + now + ". Aplikasi terpasang: "
                + names + ".";
    }

    // ---------------------------------------------------------------- eksekusi

    /** Jalankan satu alat. Dipanggil dari thread latar; aksi UI dijalankan di thread utama. */
    static String run(Activity a, String name, JSONObject in) {
        try {
            switch (name) {
                case "open_app": return openApp(a, in.optString("app"));
                case "navigate": return navigate(a, in.optString("destination"), in.optString("app", "maps"));
                case "play_quran": return playQuran(a, in.optString("surah"), in.optInt("ayah", 1));
                case "media_control": return media(a, in.optString("action"));
                case "set_volume": return volume(a, in);
                case "prayer_times": return prayerTimes(a);
                case "open_dzikir": return openDzikir(a, in.optString("period", ""));
                case "restrict_background": return restrict(a, in.optString("app"), in.optBoolean("restrict", true));
                case "car_status": return status(a);
                case "go_home":
                    ui(a, () -> a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                    return "Kembali ke home.";
                default: return "Alat tidak dikenal: " + name;
            }
        } catch (Exception e) {
            return "Gagal: " + e.getMessage();
        }
    }

    static void ui(Activity a, Runnable r) { a.runOnUiThread(r); }

    /** Huruf kecil tanpa aksen dan tanda baca, untuk mencocokkan nama ("Al-Fātiḥah" ≈ "al fatihah"). */
    static String norm(String s) {
        s = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static App findApp(Activity a, String name) {
        String q = norm(name);
        if (q.isEmpty()) return null;
        App best = null;
        for (App x : apps(a)) {
            String l = norm(x.label);
            if (l.equals(q)) return x;
            if (best == null && (l.contains(q) || q.contains(l))) best = x;
        }
        return best;
    }

    static String openApp(Activity a, String name) {
        App x = findApp(a, name);
        if (x == null) return "Aplikasi '" + name + "' tidak ditemukan.";
        ui(a, () -> a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(x.cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        return "Membuka " + x.label + ".";
    }

    static String navigate(Activity a, String dest, String app) {
        Intent i;
        if ("waze".equals(app)) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=" + Uri.encode(dest) + "&navigate=yes"));
            i.setPackage("com.waze");
        } else {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)));
            i.setPackage("com.google.android.apps.maps");
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(a.getPackageManager()) == null) {
            i.setPackage(null); // aplikasi navigasi lain apa pun
            if (i.resolveActivity(a.getPackageManager()) == null) return "Tidak ada aplikasi navigasi.";
        }
        final Intent go = i;
        ui(a, () -> a.startActivity(go));
        return "Navigasi ke " + dest + " dimulai.";
    }

    static String playQuran(Activity a, String surah, int ayah) throws Exception {
        int s = -1;
        try {
            s = Integer.parseInt(surah.trim());
        } catch (NumberFormatException e) {
            if (QuranData.available(a)) {
                QuranData.load(a);
                String q = norm(surah.replaceFirst("(?i)^(surah|surat|qs\\.?)\\s+", ""));
                for (int i = 1; i <= QuranData.SURAH_COUNT && s < 0; i++) if (norm(QuranData.get(i).latin).equals(q)) s = i;
                for (int i = 1; i <= QuranData.SURAH_COUNT && s < 0; i++) if (norm(QuranData.get(i).latin).contains(q)) s = i;
            }
        }
        if (s < 1 || s > QuranData.SURAH_COUNT) return "Surah '" + surah + "' tidak ditemukan; minta nomor surahnya.";
        final int fs = s, fa = Math.max(1, Math.min(ayah, QuranData.count(s)));
        ui(a, () -> QuranService.play(a, fs, fa));
        String nm = QuranData.loaded() ? QuranData.get(fs).latin : "surah " + fs;
        return "Memutar " + nm + " ayat " + fa + ".";
    }

    static String media(Activity a, String action) {
        // Murattal KlikDash: perintah langsung ke layanannya. Tombol media biasa tidak membatalkan "lanjut otomatis"
        // setelah asisten selesai bicara, sehingga murattal bisa jalan lagi padahal diminta berhenti.
        if (QuranService.active) {
            String act = "pause".equals(action) ? QuranService.ACTION_PAUSE : "next".equals(action) ? QuranService.ACTION_NEXT
                    : "previous".equals(action) ? QuranService.ACTION_PREV : QuranService.ACTION_RESUME;
            Compat.startForegroundService(a, new Intent(a, QuranService.class).setAction(act));
            return "Murattal: " + action + ".";
        }
        int code = "pause".equals(action) ? KeyEvent.KEYCODE_MEDIA_PAUSE : "next".equals(action) ? KeyEvent.KEYCODE_MEDIA_NEXT
                : "previous".equals(action) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_PLAY;
        AudioManager am = (AudioManager) a.getSystemService(Activity.AUDIO_SERVICE);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        return "Media: " + action + ".";
    }

    static String volume(Activity a, JSONObject in) {
        AudioManager am = (AudioManager) a.getSystemService(Activity.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int v = cur;
        if (in.has("level")) v = Math.round(in.optInt("level") / 100f * max);
        else if ("up".equals(in.optString("change"))) v = cur + Math.max(1, max / 10);
        else if ("down".equals(in.optString("change"))) v = cur - Math.max(1, max / 10);
        v = Math.max(0, Math.min(max, v));
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI);
        return "Volume " + Math.round(v * 100f / max) + "%.";
    }

    static double[] location(Activity a) {
        SharedPreferences hp = a.createDeviceProtectedStorageContext().getSharedPreferences("klikdash", Activity.MODE_PRIVATE);
        try {
            return new double[]{Double.parseDouble(hp.getString("lat", "-6.2088")), Double.parseDouble(hp.getString("lon", "106.8456"))};
        } catch (NumberFormatException e) {
            return new double[]{-6.2088, 106.8456};
        }
    }

    static String city(Activity a) {
        return a.createDeviceProtectedStorageContext().getSharedPreferences("klikdash", Activity.MODE_PRIVATE)
                .getString("city", "lokasi GPS");
    }

    static String prayerTimes(Activity a) {
        double[] l = location(a);
        Calendar now = Calendar.getInstance();
        int[] t = PrayerTimes.compute(now, l[0], l[1]);
        int min = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        StringBuilder s = new StringBuilder("Jadwal di " + city(a) + ": ");
        int next = -1;
        for (int i = 0; i < t.length; i++) {
            s.append(PrayerTimes.NAMES[i].toLowerCase(ID)).append(String.format(Locale.US, " %02d:%02d", t[i] / 60, t[i] % 60))
                    .append(i < t.length - 1 ? ", " : ". ");
            if (next < 0 && t[i] > min) next = i;
        }
        if (next >= 0) {
            int left = t[next] - min;
            s.append("Berikutnya ").append(PrayerTimes.NAMES[next].toLowerCase(ID)).append(" dalam ")
                    .append(left / 60 > 0 ? left / 60 + " jam " : "").append(left % 60).append(" menit.");
        } else {
            s.append("Berikutnya subuh besok.");
        }
        return s.toString();
    }

    static String openDzikir(Activity a, String period) {
        String p = "pagi".equals(period) || "petang".equals(period) ? period : DzikirActivity.currentPeriod(a);
        ui(a, () -> a.startActivity(new Intent(a, DzikirActivity.class).putExtra("period", p)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        return "Membuka dzikir " + p + ".";
    }

    static String restrict(Activity a, String name, boolean on) throws Exception {
        App x = findApp(a, name);
        if (x == null) return "Aplikasi '" + name + "' tidak ditemukan.";
        String p = x.cn.getPackageName();
        if (PowerPanel.PROTECTED.contains(p)) return x.label + " dilindungi agar navigasi/CarPlay tetap jalan.";
        String mode = on ? "ignore" : "allow";
        Tweaks.exec("cmd appops set " + p + " RUN_ANY_IN_BACKGROUND " + mode + "; cmd appops set " + p
                + " RUN_IN_BACKGROUND " + mode + (on ? "; am force-stop " + p : ""));
        return x.label + (on ? " dilarang jalan di latar." : " boleh jalan di latar lagi.");
    }

    static String status(Activity a) {
        Date now = new Date();
        StringBuilder s = new StringBuilder(new SimpleDateFormat("EEEE d MMMM yyyy, 'pukul' HH:mm", ID).format(now));
        android.icu.util.IslamicCalendar c = new android.icu.util.IslamicCalendar();
        c.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        c.setTimeInMillis(now.getTime());
        s.append(". Hijriah ").append(c.get(android.icu.util.Calendar.DAY_OF_MONTH)).append(' ')
                .append(HomeActivity.HIJRI_MONTHS[c.get(android.icu.util.Calendar.MONTH)].toLowerCase(ID)).append(' ')
                .append(c.get(android.icu.util.Calendar.YEAR)).append(" H");
        if (SystemClock.elapsedRealtime() - HomeActivity.lastKmhAt < 10_000) s.append(". Kecepatan ").append(HomeActivity.lastKmh).append(" km/jam");
        String t = HomeActivity.readLine(HomeActivity.findCpuZone());
        if (t != null) {
            try {
                int v = Integer.parseInt(t);
                s.append(". Suhu CPU ").append(v > 1000 ? Math.round(v / 1000f) : v).append("°C");
            } catch (NumberFormatException ignored) { }
        }
        return s.append(". Lokasi ").append(city(a)).append('.').toString();
    }
}
