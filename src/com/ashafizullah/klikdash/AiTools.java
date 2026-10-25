package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
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
 * Setiap alat mengembalikan teks singkat untuk dibaca model, bukan untuk pengemudi. Semua teks untuk model memakai
 * bahasa asisten ({@link AiStore#inLang}, R.string.as_*), bukan bahasa antarmuka.
 */
final class AiTools {
    /**
     * Satu alat: nama, deskripsi (R.string.as_tool_*), dan JSON schema parameternya. Keterangan parameter yang perlu
     * diterjemahkan ditulis %1$s, %2$s, … di schema dan diisi dari {@code args} (R.string.as_param_*).
     */
    static final class Tool {
        final String name, schema;
        final int desc;
        final int[] args;

        Tool(String name, int desc, String schema, int... args) {
            this.name = name;
            this.desc = desc;
            this.schema = schema;
            this.args = args;
        }

        String desc(Context t) { return t.getString(desc); }

        JSONObject schema(Context t) throws Exception {
            Object[] v = new Object[args.length];
            for (int i = 0; i < args.length; i++) v[i] = JSONObject.quote(t.getString(args[i]));
            return new JSONObject(String.format(Locale.US, schema, v));
        }
    }

    static final Tool[] TOOLS = {
            new Tool("open_app", R.string.as_tool_open_app,
                    "{\"type\":\"object\",\"properties\":{\"app\":{\"type\":\"string\",\"description\":%1$s}},"
                            + "\"required\":[\"app\"],\"additionalProperties\":false}", R.string.as_param_app),
            new Tool("navigate", R.string.as_tool_navigate,
                    "{\"type\":\"object\",\"properties\":{\"destination\":{\"type\":\"string\"},"
                            + "\"app\":{\"type\":\"string\",\"enum\":[\"maps\",\"waze\"],\"description\":\"Default maps\"}},"
                            + "\"required\":[\"destination\"],\"additionalProperties\":false}"),
            new Tool("play_quran", R.string.as_tool_play_quran,
                    "{\"type\":\"object\",\"properties\":{\"surah\":{\"type\":\"string\"},"
                            + "\"ayah\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"Default 1\"}},"
                            + "\"required\":[\"surah\"],\"additionalProperties\":false}"),
            new Tool("media_control", R.string.as_tool_media_control,
                    "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\","
                            + "\"enum\":[\"play\",\"pause\",\"next\",\"previous\"]}},\"required\":[\"action\"],\"additionalProperties\":false}"),
            new Tool("set_volume", R.string.as_tool_set_volume,
                    "{\"type\":\"object\",\"properties\":{\"level\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100},"
                            + "\"change\":{\"type\":\"string\",\"enum\":[\"up\",\"down\"]}},\"additionalProperties\":false}"),
            new Tool("prayer_times", R.string.as_tool_prayer_times,
                    "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
            // "pagi"/"petang" adalah kunci internal dzikir (lihat DzikirActivity), sama di semua bahasa.
            new Tool("open_dzikir", R.string.as_tool_open_dzikir,
                    "{\"type\":\"object\",\"properties\":{\"period\":{\"type\":\"string\",\"enum\":[\"pagi\",\"petang\"],"
                            + "\"description\":%1$s}},\"additionalProperties\":false}", R.string.as_param_period),
            new Tool("restrict_background", R.string.as_tool_restrict_background,
                    "{\"type\":\"object\",\"properties\":{\"app\":{\"type\":\"string\"},\"restrict\":{\"type\":\"boolean\"}},"
                            + "\"required\":[\"app\",\"restrict\"],\"additionalProperties\":false}"),
            new Tool("car_status", R.string.as_tool_car_status,
                    "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
            new Tool("go_home", R.string.as_tool_go_home,
                    "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
    };

    /** Pencarian web lewat TinyFish, untuk semua profil bila key-nya diisi (lihat AiClient). */
    static final Tool WEB_SEARCH = new Tool("web_search", R.string.as_tool_web_search,
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":%1$s}},"
                    + "\"required\":[\"query\"],\"additionalProperties\":false}", R.string.as_param_query);

    /** {@code t}: context berbahasa asisten ({@link AiStore#inLang}). */
    static JSONArray claudeTools(Context t, boolean webSearch) throws Exception {
        JSONArray a = new JSONArray();
        for (Tool x : TOOLS) a.put(claudeTool(t, x));
        if (webSearch) a.put(claudeTool(t, WEB_SEARCH));
        return a;
    }

    static JSONObject claudeTool(Context t, Tool x) throws Exception {
        return new JSONObject().put("name", x.name).put("description", x.desc(t)).put("input_schema", x.schema(t));
    }

    static JSONArray openaiTools(Context t, boolean webSearch) throws Exception {
        JSONArray a = new JSONArray();
        for (Tool x : TOOLS) a.put(openaiTool(t, x));
        if (webSearch) a.put(openaiTool(t, WEB_SEARCH));
        return a;
    }

    static JSONObject openaiTool(Context t, Tool x) throws Exception {
        return new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", x.name).put("description", x.desc(t)).put("parameters", x.schema(t)));
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
    static String systemPrompt(Activity a, boolean webSearch) {
        Context t = AiStore.inLang(a);
        StringBuilder names = new StringBuilder();
        for (App x : apps(a)) names.append(names.length() > 0 ? ", " : "").append(x.label);
        String now = new SimpleDateFormat(t.getString(R.string.as_now_pattern), locale(a)).format(new Date());
        return t.getString(R.string.as_system, now, names) + (webSearch ? " " + t.getString(R.string.as_system_web) : "");
    }

    /** Locale bahasa asisten, untuk tanggal dan huruf kecil di teks untuk model. */
    static Locale locale(Context c) { return Lang.localeOf(AiStore.lang(c)); }

    // ---------------------------------------------------------------- pencarian web (TinyFish)

    /** TinyFish Search API (gratis, 30 pencarian/menit): 5 hasil teratas sebagai teks ringkas untuk model. */
    static String webSearch(Activity a, String query) throws Exception {
        Context t = AiStore.inLang(a);
        if (query.trim().isEmpty()) return t.getString(R.string.as_search_empty);
        String key = AiStore.tinyfishKey(a);
        if (key.isEmpty()) return t.getString(R.string.as_search_unset);
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://api.search.tinyfish.ai?query="
                + java.net.URLEncoder.encode(query.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("X-API-Key", key);
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            int code = c.getResponseCode();
            String txt = AiClient.read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            android.util.Log.i("KlikDashAI", "TinyFish " + code + " " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
            if (code == 401 || code == 403) return t.getString(R.string.as_search_key);
            if (code >= 400) return t.getString(R.string.as_search_http, code);
            org.json.JSONArray rs = new JSONObject(txt).optJSONArray("results");
            if (rs == null || rs.length() == 0) return t.getString(R.string.as_search_none, query);
            StringBuilder s = new StringBuilder(t.getString(R.string.as_search_results, query));
            for (int i = 0; i < Math.min(5, rs.length()); i++) {
                JSONObject r = rs.getJSONObject(i);
                s.append("\n").append(i + 1).append(". ").append(r.optString("title"));
                String site = r.optString("site_name");
                if (!site.isEmpty()) s.append(" (").append(site).append(")");
                s.append(": ").append(r.optString("snippet")).append(" ").append(r.optString("url"));
            }
            return s.toString();
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- eksekusi

    /** Jalankan satu alat. Dipanggil dari thread latar; aksi UI dijalankan di thread utama. */
    static String run(Activity a, String name, JSONObject in) {
        Context t = AiStore.inLang(a);
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
                case "web_search": return webSearch(a, in.optString("query"));
                case "go_home":
                    ui(a, () -> a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                    return t.getString(R.string.as_res_home);
                default: return t.getString(R.string.as_res_unknown_tool, name);
            }
        } catch (Exception e) {
            return t.getString(R.string.as_res_failed, AiClient.describe(t, e));
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
        Context t = AiStore.inLang(a);
        if (x == null) return t.getString(R.string.as_res_app_not_found, name);
        ui(a, () -> a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(x.cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        return t.getString(R.string.as_res_opening, x.label);
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
            if (i.resolveActivity(a.getPackageManager()) == null) return AiStore.inLang(a).getString(R.string.as_res_no_nav);
        }
        final Intent go = i;
        ui(a, () -> a.startActivity(go));
        return AiStore.inLang(a).getString(R.string.as_res_navigating, dest);
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
        Context t = AiStore.inLang(a);
        if (s < 1 || s > QuranData.SURAH_COUNT) return t.getString(R.string.as_res_surah_not_found, surah);
        final int fs = s, fa = Math.max(1, Math.min(ayah, QuranData.count(s)));
        ui(a, () -> QuranService.play(a, fs, fa));
        String nm = QuranData.loaded() ? QuranData.get(fs).latin : "surah " + fs;
        return t.getString(R.string.as_res_playing, nm, fa);
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
        String c = a.createDeviceProtectedStorageContext().getSharedPreferences("klikdash", Activity.MODE_PRIVATE)
                .getString("city", null);
        return c != null ? c : AiStore.inLang(a).getString(R.string.as_res_gps);
    }

    static String prayerTimes(Activity a) {
        double[] l = location(a);
        Calendar now = Calendar.getInstance();
        int[] t = PrayerTimes.compute(now, l[0], l[1]);
        int min = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        Context c = AiStore.inLang(a);
        Locale loc = locale(a);
        String[] names = c.getResources().getStringArray(R.array.prayer_names);
        StringBuilder s = new StringBuilder(c.getString(R.string.as_res_schedule, city(a))).append(' ');
        int next = -1;
        for (int i = 0; i < t.length; i++) {
            s.append(names[i].toLowerCase(loc)).append(String.format(Locale.US, " %02d:%02d", t[i] / 60, t[i] % 60))
                    .append(i < t.length - 1 ? ", " : ". ");
            if (next < 0 && t[i] > min) next = i;
        }
        if (next >= 0) {
            int left = t[next] - min;
            android.content.res.Resources r = c.getResources();
            String wait = (left / 60 > 0 ? r.getQuantityString(R.plurals.as_hours, left / 60, left / 60) + " " : "")
                    + r.getQuantityString(R.plurals.as_minutes, left % 60, left % 60);
            s.append(c.getString(R.string.as_res_next, names[next].toLowerCase(loc), wait));
        } else {
            s.append(c.getString(R.string.as_res_next_tomorrow, names[0].toLowerCase(loc)));
        }
        return s.toString();
    }

    static String openDzikir(Activity a, String period) {
        String p = "pagi".equals(period) || "petang".equals(period) ? period : DzikirActivity.currentPeriod(a);
        ui(a, () -> a.startActivity(new Intent(a, DzikirActivity.class).putExtra("period", p)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        return AiStore.inLang(a).getString("pagi".equals(p) ? R.string.as_res_dzikir_pagi : R.string.as_res_dzikir_petang);
    }

    static String restrict(Activity a, String name, boolean on) throws Exception {
        App x = findApp(a, name);
        Context t = AiStore.inLang(a);
        if (x == null) return t.getString(R.string.as_res_app_not_found, name);
        String p = x.cn.getPackageName();
        if (PowerPanel.PROTECTED.contains(p)) return t.getString(R.string.as_res_protected, x.label);
        String mode = on ? "ignore" : "allow";
        Tweaks.exec("cmd appops set " + p + " RUN_ANY_IN_BACKGROUND " + mode + "; cmd appops set " + p
                + " RUN_IN_BACKGROUND " + mode + (on ? "; am force-stop " + p : ""));
        return t.getString(on ? R.string.as_res_restricted : R.string.as_res_allowed, x.label);
    }

    static String status(Activity a) {
        Date now = new Date();
        Context x = AiStore.inLang(a);
        Locale loc = locale(a);
        StringBuilder s = new StringBuilder(new SimpleDateFormat(x.getString(R.string.as_status_pattern), loc).format(now));
        android.icu.util.IslamicCalendar c = new android.icu.util.IslamicCalendar();
        c.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        c.setTimeInMillis(now.getTime());
        s.append(". ").append(x.getString(R.string.as_res_hijri, c.get(android.icu.util.Calendar.DAY_OF_MONTH),
                x.getResources().getStringArray(R.array.hijri_months)[c.get(android.icu.util.Calendar.MONTH)].toLowerCase(loc),
                c.get(android.icu.util.Calendar.YEAR)));
        if (SystemClock.elapsedRealtime() - HomeLocation.lastKmhAt < 10_000)
            s.append(". ").append(x.getString(R.string.as_res_speed, HomeLocation.lastKmh));
        String t = HomeStatus.readLine(HomeStatus.findCpuZone());
        if (t != null) {
            try {
                int v = Integer.parseInt(t);
                s.append(". ").append(x.getString(R.string.as_res_cpu, v > 1000 ? Math.round(v / 1000f) : v));
            } catch (NumberFormatException ignored) { }
        }
        return s.append(". ").append(x.getString(R.string.as_res_location, city(a))).toString();
    }
}
