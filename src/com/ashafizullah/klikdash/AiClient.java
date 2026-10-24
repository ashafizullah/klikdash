package com.ashafizullah.klikdash;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loop asisten: kirim pertanyaan + alat, jalankan alat yang diminta model, ulangi sampai ada jawaban teks.
 * Dua format: Claude (Messages API, anthropic-version 2026-10-21) dan OpenAI-compatible (Chat Completions).
 * HTTP mentah tanpa SDK, seperti bagian lain KlikDash (APK kecil, tanpa dependensi).
 */
final class AiClient {
    static final int MAX_STEPS = 6;

    /** Riwayat percakapan sesi ini, dalam format penyedia profil aktif. */
    final JSONArray messages = new JSONArray();
    final AiStore.Profile profile;
    final Activity activity;
    /** Dibuat sekali per sesi: prompt sistem yang berubah di tengah percakapan membuat blok thinking tidak berlaku. */
    String system;

    /**
     * Pencarian web sesi ini. TinyFish aktif (key diisi, tidak dimatikan di Pengaturan): TinyFish untuk semua profil
     * (alat web_search yang dijalankan KlikDash). Selain itu: web search server Anthropic untuk profil Claude resmi,
     * profil lain tanpa pencarian.
     */
    static final int NO_SEARCH = 0, ANTHROPIC_SEARCH = 1, TINYFISH_SEARCH = 2;
    final int search;
    /** Web search Anthropic ditolak (dimatikan admin organisasi, atau model tidak mendukung): sesi ini tanpa pencarian. */
    boolean searchRejected;

    AiClient(Activity a, AiStore.Profile p) {
        activity = a;
        profile = p;
        search = searchMode(a, p);
    }

    static int searchMode(Activity a, AiStore.Profile p) {
        return AiStore.webSearch(a) && AiStore.hasTinyfishKey(a) ? TINYFISH_SEARCH
                : p.officialClaude() ? ANTHROPIC_SEARCH : NO_SEARCH;
    }

    /** Sesi ini masih sesuai pengaturan sekarang (profil, key, model, alamat, pencarian)? Bila tidak: sesi baru. */
    boolean matches(Activity a, AiStore.Profile p) {
        return profile.id.equals(p.id) && profile.apiKey.equals(p.apiKey) && profile.model.equals(p.model)
                && profile.baseUrl.equals(p.baseUrl) && search == searchMode(a, p);
    }

    /** Jalankan di thread latar. Mengembalikan teks jawaban untuk dibacakan. */
    String ask(String userText) throws Exception {
        if (system == null) system = AiTools.systemPrompt(activity, search != NO_SEARCH);
        return profile.claude() ? askClaude(userText) : askOpenAi(userText);
    }

    /**
     * Web search server Anthropic (versi dasar, dipanggil langsung oleh model; didukung semua model yang punya web
     * search). Paling banyak 2 pencarian per pertanyaan: cukup untuk pertanyaan pengemudi, biaya $10 per 1.000.
     * Lokasi perkiraan (kota tersimpan, zona waktu) supaya "cuaca hari ini" atau "berita lokal" relevan.
     */
    JSONObject anthropicSearchTool() throws Exception {
        JSONObject loc = new JSONObject().put("type", "approximate").put("timezone", java.util.TimeZone.getDefault().getID());
        String city = activity.createDeviceProtectedStorageContext()
                .getSharedPreferences("klikdash", Activity.MODE_PRIVATE).getString("city", null);
        if (city != null && !city.isEmpty()) loc.put("city", PrayerAlarm.titleCase(city));
        if (loc.getString("timezone").matches("Asia/(Jakarta|Pontianak|Makassar|Jayapura)")) loc.put("country", "ID");
        return new JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 2)
                .put("user_location", loc);
    }

    // ---------------------------------------------------------------- Claude

    String askClaude(String userText) throws Exception {
        messages.put(new JSONObject().put("role", "user").put("content", userText));
        String base = trim(profile.baseUrl.isEmpty() ? AiStore.CLAUDE_URL : profile.baseUrl);
        String url = base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
        for (int step = 0; step < MAX_STEPS; step++) {
            JSONArray tools = AiTools.claudeTools(search == TINYFISH_SEARCH);
            boolean withSearch = search == ANTHROPIC_SEARCH && !searchRejected;
            if (withSearch) tools.put(anthropicSearchTool());
            JSONObject body = new JSONObject()
                    .put("model", profile.model.isEmpty() ? AiStore.CLAUDE_MODEL : profile.model)
                    .put("max_tokens", 4096)
                    .put("system", system)
                    .put("tools", tools)
                    .put("messages", messages);
            Map<String, String> headers = new java.util.HashMap<>();
            headers.put("x-api-key", profile.apiKey);
            headers.put("anthropic-version", "2026-10-21");
            if (profile.officialClaude()) {
                // Perintah mobil itu sederhana: effort rendah = jawaban cepat. Fallback otomatis bila model menolak.
                body.put("output_config", new JSONObject().put("effort", "low"));
                // Haiku: perintah mobil cukup tanpa "berpikir" dulu, jawaban jauh lebih cepat (boleh di effort low).
                if (body.getString("model").contains("haiku")) body.put("thinking", new JSONObject().put("type", "disabled"));
                if (!body.getString("model").contains("haiku")) { // Haiku tidak punya fallback server-side
                    body.put("fallbacks", "default");
                    headers.put("anthropic-beta", "server-side-fallback-2026-07-01");
                }
            }
            JSONObject res;
            try {
                res = post(url, headers, body);
            } catch (IOException e) {
                // Web search dimatikan admin organisasi di Console, atau model ini tidak mendukungnya: tetap menjawab.
                String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.US);
                if (!withSearch || !m.contains("http 400") || !m.contains("web")) throw e;
                android.util.Log.w("KlikDashAI", "web search ditolak, lanjut tanpa: " + e.getMessage());
                searchRejected = true;
                step--;
                continue;
            }
            JSONArray content = res.optJSONArray("content");
            if (content == null) content = new JSONArray();
            // Simpan seluruh content (termasuk blok thinking/fallback) apa adanya untuk giliran berikutnya.
            messages.put(new JSONObject().put("role", "assistant").put("content", content));
            String stop = res.optString("stop_reason");
            if ("refusal".equals(stop)) return "Maaf, permintaan itu tidak bisa saya proses.";
            // Pencarian panjang dijeda server: kirim ulang pesan asisten yang sudah tersimpan apa adanya untuk lanjut.
            if ("pause_turn".equals(stop)) continue;
            if (!"tool_use".equals(stop)) return text(content);

            JSONArray results = new JSONArray();
            for (int i = 0; i < content.length(); i++) {
                JSONObject b = content.getJSONObject(i);
                if (!"tool_use".equals(b.optString("type"))) continue;
                String out = AiTools.run(activity, b.optString("name"), b.optJSONObject("input") != null
                        ? b.getJSONObject("input") : new JSONObject());
                results.put(new JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id"))
                        .put("content", out));
            }
            // Semua hasil alat dalam satu pesan user.
            messages.put(new JSONObject().put("role", "user").put("content", results));
        }
        return "Maaf, terlalu banyak langkah. Coba ulangi dengan lebih singkat.";
    }

    /**
     * Teks jawaban untuk dibacakan. Dengan web search, model sering menulis pengantar ("Saya carikan dulu…") sebelum
     * mencari: yang dibacakan hanya teks sesudah blok pencarian terakhir.
     */
    static String text(JSONArray content) {
        int from = 0;
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            String t = b != null ? b.optString("type") : "";
            if ("server_tool_use".equals(t) || "web_search_tool_result".equals(t)) from = i + 1;
        }
        StringBuilder s = new StringBuilder();
        for (int i = from; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b != null && "text".equals(b.optString("type"))) s.append(b.optString("text"));
        }
        return s.toString().trim();
    }

    // ---------------------------------------------------------------- OpenAI-compatible

    String askOpenAi(String userText) throws Exception {
        if (messages.length() == 0) {
            messages.put(new JSONObject().put("role", "system").put("content", system));
        }
        messages.put(new JSONObject().put("role", "user").put("content", userText));
        String url = trim(profile.baseUrl.isEmpty() ? AiStore.OPENAI_URL : profile.baseUrl) + "/chat/completions";
        for (int step = 0; step < MAX_STEPS; step++) {
            JSONObject body = new JSONObject()
                    .put("model", profile.model)
                    .put("messages", messages)
                    .put("tools", AiTools.openaiTools(search == TINYFISH_SEARCH))
                    .put("tool_choice", "auto");
            Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Authorization", "Bearer " + profile.apiKey);
            JSONObject res = post(url, headers, body);
            JSONArray choices = res.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new IOException("Jawaban kosong dari server");
            JSONObject msg = choices.getJSONObject(0).getJSONObject("message");
            messages.put(msg);
            JSONArray calls = msg.optJSONArray("tool_calls");
            if (calls == null || calls.length() == 0) return msg.optString("content", "").trim();
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.getJSONObject(i), fn = call.getJSONObject("function");
                JSONObject args;
                try {
                    args = new JSONObject(fn.optString("arguments", "{}"));
                } catch (Exception e) {
                    args = new JSONObject();
                }
                String out = AiTools.run(activity, fn.optString("name"), args);
                messages.put(new JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("content", out));
            }
        }
        return "Maaf, terlalu banyak langkah. Coba ulangi dengan lebih singkat.";
    }

    // ---------------------------------------------------------------- HTTP

    static String trim(String s) {
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Jaringan mobil (hotspot HP, WiFi lemah) kadang macet di satu koneksi. Jawaban AI biasanya ±1 detik, jadi
     * bila 20 detik tak ada balasan, buka koneksi baru sekali lagi daripada menunggu lebih lama.
     */
    static JSONObject post(String url, Map<String, String> headers, JSONObject body) throws Exception {
        try {
            return post(url, headers, body, 20000);
        } catch (java.net.SocketTimeoutException e) {
            android.util.Log.w("KlikDashAI", "timeout, coba lagi: " + url);
            try {
                return post(url, headers, body, 25000);
            } catch (java.net.SocketTimeoutException e2) {
                throw new IOException("Server AI terlalu lama menjawab, coba lagi");
            }
        }
    }

    static JSONObject post(String url, Map<String, String> headers, JSONObject body, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(readMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            long head = android.os.SystemClock.elapsedRealtime() - t0;
            String txt = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            android.util.Log.i("KlikDashAI", "POST " + new URL(url).getHost() + " " + code + " header " + head + " ms, total "
                    + (android.os.SystemClock.elapsedRealtime() - t0) + " ms, " + txt.length() + " B, req-id "
                    + c.getHeaderField("request-id"));
            if (code >= 400) throw new IOException(errorMessage(code, txt));
            return new JSONObject(txt);
        } finally {
            c.disconnect();
        }
    }

    static JSONObject get(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        try {
            int code = c.getResponseCode();
            String txt = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new IOException(errorMessage(code, txt));
            return new JSONObject(txt);
        } finally {
            c.disconnect();
        }
    }

    /** Daftar id model dari penyedia (GET /v1/models), untuk dipilih di pengaturan. */
    static java.util.List<String> models(String type, String baseUrl, String apiKey) throws Exception {
        Map<String, String> h = new java.util.HashMap<>();
        String base = trim(baseUrl);
        String url;
        if (AiStore.CLAUDE.equals(type)) {
            h.put("x-api-key", apiKey);
            h.put("anthropic-version", "2026-10-21");
            url = (base.endsWith("/v1") ? base : base + "/v1") + "/models?limit=100";
        } else {
            h.put("Authorization", "Bearer " + apiKey);
            url = base + "/models";
        }
        JSONArray data = get(url, h).optJSONArray("data");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (data != null) for (int i = 0; i < data.length(); i++) {
            String id = data.getJSONObject(i).optString("id");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    /** Pesan error yang bisa dibacakan: 401 = API key salah, 429 = kuota/limit, dst. */
    static String errorMessage(int code, String body) {
        String m = "";
        try {
            JSONObject j = new JSONObject(body), e = j.optJSONObject("error");
            if (e != null) m = e.optString("message");
            else if (j.optJSONObject("detail") != null) m = j.getJSONObject("detail").optString("message"); // ElevenLabs
        } catch (Exception ignored) { }
        String head = code == 401 || code == 403 ? "API key ditolak" : code == 429 ? "Batas pemakaian tercapai"
                : code >= 500 ? "Server AI sedang bermasalah" : "Permintaan ditolak (HTTP " + code + ")";
        return m.isEmpty() ? head : head + ": " + m;
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream i = in) {
            byte[] buf = new byte[16384];
            for (int k; (k = i.read(buf)) > 0; ) out.write(buf, 0, k);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
