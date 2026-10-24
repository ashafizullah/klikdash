package com.ashafizullah.klikdash;

import android.app.AlertDialog;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.List;

import static com.ashafizullah.klikdash.SettingsActivity.MATCH;
import static com.ashafizullah.klikdash.SettingsActivity.WRAP;

/**
 * Tab Asisten AI: profil penyedia (Claude atau OpenAI-compatible, bisa lebih dari satu, satu aktif) dan suara
 * ElevenLabs. API key hanya disimpan terenkripsi (lihat {@link AiStore}) dan ditampilkan tersamar.
 */
final class AiPanel {
    final SettingsActivity a;
    final ScrollView view;
    final LinearLayout list;
    AiVoice voice;

    AiPanel(SettingsActivity a) {
        this.a = a;
        list = a.column();
        view = a.scroll(list);
        render();
    }

    void render() {
        list.removeAllViews();
        List<AiStore.Profile> ps = AiStore.profiles(a);
        AiStore.Profile act = AiStore.active(a);

        list.addView(a.row("PROFIL AI",
                ps.isEmpty() ? "Belum ada profil. Tambahkan API key Claude (console.anthropic.com) atau layanan "
                        + "OpenAI-compatible seperti OpenAI, OpenRouter, Groq, atau server lokal."
                        : "Ketuk PAKAI untuk memilih profil yang dipakai pintasan TANYA. API key disimpan terenkripsi di "
                        + "headunit ini dan hanya dikirim ke alamat profilnya.",
                a.key("+ CLAUDE", ps.isEmpty(), () -> edit(AiStore.newProfile(AiStore.CLAUDE), true)),
                a.key(a.narrow ? "+ OPENAI" : "+ OPENAI-COMPATIBLE", false,
                        () -> edit(AiStore.newProfile(AiStore.OPENAI), true))));

        for (AiStore.Profile p : ps) {
            boolean on = act != null && act.id.equals(p.id);
            String host = Uri.parse(p.baseUrl).getHost();
            String desc = (p.claude() ? "Claude" : "OpenAI-compatible") + " · " + (p.model.isEmpty() ? "model belum diisi" : p.model)
                    + " · " + (host != null ? host : p.baseUrl) + " · key " + mask(p.apiKey);
            Key test = a.key("UJI", false, null);
            test.setOnClickListener(v -> test(p, test));
            list.addView(a.row(p.name.toUpperCase(SettingsActivity.ID), desc,
                    a.key(on ? "AKTIF" : "PAKAI", on, () -> {
                        AiStore.setActive(a, p.id);
                        render();
                    }),
                    test,
                    a.key("UBAH", false, () -> edit(p, false))));
        }

        // AKTIF/MATI = TinyFish; tanpa key TinyFish tidak bisa diaktifkan. Selain TinyFish, profil Claude resmi tetap
        // mencari lewat pencarian Anthropic (lihat AiClient).
        String tf = AiStore.tinyfishKey(a);
        boolean hasTf = !tf.isEmpty(), web = hasTf && AiStore.webSearch(a);
        String anthropic = "profil Claude resmi mencari lewat pencarian Anthropic, ditagih ke akun Claude ($10 per 1.000 "
                + "pencarian); profil lain tanpa pencarian.";
        Key webOn = a.key("AKTIF", web, () -> setWebSearch(true));
        webOn.setEnabled(hasTf);
        list.addView(a.row("PENCARIAN WEB",
                "TANYA mencari info terkini (berita, harga, cuaca, jadwal) bila perlu. " + (!hasTf
                        ? "Isi API key TinyFish (gratis, buat di agent.tinyfish.ai) supaya semua profil mencari lewat "
                        + "TinyFish. Tanpa TinyFish, " + anthropic
                        : web ? "Semua profil mencari lewat TinyFish (gratis), key " + mask(tf) + "."
                        : "TinyFish dimatikan: " + anthropic),
                webOn,
                a.key("MATI", !web, () -> setWebSearch(false)),
                a.key(a.narrow ? "KEY" : "KEY TINYFISH", !hasTf, this::editTinyfishKey)));

        String elKey = AiStore.elKey(a), elVoice = AiStore.elVoice(a), elName = AiStore.elVoiceName(a);
        list.addView(a.row("SUARA ELEVENLABS",
                (elKey.isEmpty() ? "Belum diatur. " : "Key " + mask(elKey) + " · suara "
                        + (elVoice.isEmpty() ? "belum dipilih" : elName.isEmpty() ? elVoice : elName) + " · "
                        + AiStore.elModelName(AiStore.elModel(a)) + ". ")
                        + "Dipakai untuk suara jawaban dan untuk mendengar perintah bila headunit tidak punya pengenal "
                        + "suara Android.",
                a.key("API KEY", elKey.isEmpty(), this::editElevenKey),
                a.key("SUARA", false, this::pickVoice),
                a.key("MODEL", false, this::pickElModel)));

        // Tanpa API key ElevenLabs: tombol ELEVENLABS nonaktif dan Android yang terpilih (sesuai yang dipakai TANYA).
        // Aplikasi suara Android belum terpasang: tombol ANDROID nonaktif, dan PASANG membuka Play Store.
        boolean hasEl = !elKey.isEmpty();
        String ttsEngine = AiVoice.androidEngine(a);
        android.content.ComponentName sttService = AiVoice.recognizer(a);
        boolean hasTts = ttsEngine != null, hasStt = android.speech.SpeechRecognizer.isRecognitionAvailable(a);
        boolean el = hasEl && (AiStore.elTts(a) || !hasTts);
        Key elTts = a.key("ELEVENLABS", el, () -> setTts(true));
        elTts.setEnabled(hasEl);
        Key androidTts = a.key("ANDROID", !el && hasTts, () -> setTts(false));
        androidTts.setEnabled(hasTts);
        String ttsDesc = hasTts ? "Suara Android memakai " + appLabel(ttsEngine) + ". "
                : "Suara Android butuh aplikasi Speech Services by Google (gratis, bisa offline), yang belum terpasang "
                + "di headunit ini: ketuk PASANG untuk memasangnya dari Play Store. ";
        ttsDesc += !hasEl ? "Isi API key ElevenLabs di atas untuk bisa memilih suara ElevenLabs."
                : "ElevenLabs butuh internet dan memakai kuota akun ElevenLabs" + (hasTts ? "; bila gagal, otomatis "
                + "memakai suara Android." : ".");
        list.addView(hasTts ? a.row("SUARA JAWABAN", ttsDesc, elTts, androidTts, a.key("DENGAR", false, this::speakTest))
                : a.row("SUARA JAWABAN", ttsDesc, elTts, androidTts,
                        a.key("PASANG", true, () -> openStore(GOOGLE_TTS)), a.key("DENGAR", false, this::speakTest)));

        boolean elStt = hasEl && (AiStore.elStt(a) || !hasStt);
        Key elSttKey = a.key("ELEVENLABS", elStt, () -> setStt(true));
        elSttKey.setEnabled(hasEl);
        Key androidStt = a.key("ANDROID", !elStt && hasStt, () -> setStt(false));
        androidStt.setEnabled(hasStt);
        String sttDesc = hasStt ? "Pengenal suara Android memakai "
                + (sttService != null ? appLabel(sttService.getPackageName()) : "layanan bawaan headunit") + ". "
                : "Pengenal suara Android butuh aplikasi Google (gratis), yang belum terpasang di headunit ini: ketuk "
                + "PASANG untuk memasangnya dari Play Store. ";
        sttDesc += !hasEl ? "Isi API key ElevenLabs di atas untuk bisa memilih ElevenLabs Scribe."
                : "ElevenLabs Scribe mentranskripsi rekaman ucapan Anda dan memakai kuota akun ElevenLabs"
                + (hasStt ? "; bila pengenal Android gagal, rekaman dikirim ke ElevenLabs." : ".");
        list.addView(hasStt ? a.row("PENGENALAN SUARA", sttDesc, elSttKey, androidStt)
                : a.row("PENGENALAN SUARA", sttDesc, elSttKey, androidStt,
                        a.key("PASANG", true, () -> openStore(GOOGLE_APP))));
    }

    /** Aplikasi suara Android dari Google di Play Store: mesin TTS dan pengenal suara (aplikasi Google). */
    static final String GOOGLE_TTS = "com.google.android.tts", GOOGLE_APP = "com.google.android.googlequicksearchbox";

    /** Nama aplikasi untuk keterangan (mis. "Speech Services by Google"), atau nama paketnya bila tidak terbaca. */
    String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = a.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    static String mask(String key) {
        if (key == null || key.isEmpty()) return "kosong";
        return "••••" + (key.length() > 8 ? key.substring(key.length() - 4) : "");
    }

    /**
     * Belum ada mesin atau pengenal suara Android: buka aplikasinya di Play Store (Speech Recognition & Synthesis untuk
     * suara, aplikasi Google untuk pengenal suara).
     */
    void openStore(String id) {
        try {
            a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + id)));
        } catch (android.content.ActivityNotFoundException e) {
            try {
                a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + id)));
            } catch (android.content.ActivityNotFoundException ignored) { } // tanpa toko dan browser: notifikasi saja
        }
    }

    void setStt(boolean eleven) {
        AiStore.prefs(a).edit().putBoolean("elStt", eleven).apply();
        render();
    }

    void setTts(boolean eleven) {
        AiStore.prefs(a).edit().putBoolean("elTts", eleven).apply();
        render();
    }

    void speakTest() {
        if (AiStore.elKey(a).isEmpty() && !AiVoice.hasAndroidTts(a)) {
            Notice.info(a, "Isi API key & voice ID ElevenLabs dulu");
            return;
        }
        if (voice == null) voice = new AiVoice(a);
        voice.say("Halo, saya asisten KlikDash. Mau ke mana kita hari ini?", null);
    }

    void test(AiStore.Profile p, Key k) {
        if (p.apiKey.isEmpty()) { Notice.info(a, "API key belum diisi"); return; }
        k.setLabel("MENGUJI…");
        k.setEnabled(false);
        new Thread(() -> {
            String msg;
            int kind;
            try {
                String r = new AiClient(a, p).ask("Tes koneksi. Jawab satu kalimat singkat bahwa kamu siap, tanpa memakai alat.");
                msg = r.isEmpty() ? "Tersambung" : r;
                kind = Notice.OK;
            } catch (Exception e) {
                msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                kind = Notice.FAIL;
            }
            final String m = msg;
            final int kd = kind;
            a.runOnUiThread(() -> {
                k.setLabel("UJI");
                k.setEnabled(true);
                Notice.show(a, kd, p.name, m);
            });
        }, "ai-test").start();
    }

    void edit(AiStore.Profile p, boolean isNew) {
        LinearLayout form = a.column();
        int pad = a.px(24);
        form.setPadding(pad, pad, pad, 0);
        EditText name = a.field("Nama profil", p.name, false);
        EditText url = a.field(p.claude() ? AiStore.CLAUDE_URL : AiStore.OPENAI_URL, p.baseUrl, false);
        EditText model = a.field(p.claude() ? AiStore.CLAUDE_MODEL : "mis. gpt-5.1 atau openai/gpt-5.1", p.model, false);
        EditText key = a.field(isNew || p.apiKey.isEmpty() ? "Tempel API key" : "Kosongkan = tetap " + mask(p.apiKey), "", true);
        a.addField(form, "NAMA", name);
        a.addField(form, "BASE URL", url);
        a.addField(form, "MODEL", model);
        Key pick = a.key("PILIH MODEL", false, null);
        pick.setOnClickListener(v -> pickModel(p, url, key, model));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(WRAP, a.px(48));
        pl.topMargin = a.px(8);
        form.addView(pick, form.indexOfChild(model) + 1, pl);
        a.addField(form, "API KEY", key);
        ScrollView s = a.scroll(form);

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(isNew ? (p.claude() ? "Profil Claude baru" : "Profil OpenAI-compatible baru") : "Ubah profil")
                .setView(s)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", null);
        if (!isNew) b.setNeutralButton("Hapus", (d, w) -> confirmDelete(p));
        AlertDialog d = SettingsActivity.showDark(b);
        // Tombol Simpan tidak langsung menutup dialog, supaya isian yang salah bisa diperbaiki.
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim(), u = AiClient.trim(url.getText().toString());
            String m = model.getText().toString().trim(), k = key.getText().toString().trim();
            if (u.isEmpty()) u = p.claude() ? AiStore.CLAUDE_URL : AiStore.OPENAI_URL;
            if (!u.startsWith("https://") && !u.startsWith("http://")) { Notice.info(a, "Base URL harus diawali https://"); return; }
            if (m.isEmpty() && !p.claude()) { Notice.info(a, "Isi nama model"); return; }
            if (k.isEmpty() && p.apiKey.isEmpty()) { Notice.info(a, "Isi API key"); return; }
            p.name = n.isEmpty() ? (p.claude() ? "Claude" : "OpenAI-compatible") : n;
            p.baseUrl = u;
            p.model = m;
            if (!k.isEmpty()) p.apiKey = k;
            save(p);
            d.dismiss();
            render();
        });
    }

    /** Ambil daftar model dari penyedia memakai key yang diketik (atau yang tersimpan), lalu isi kolom MODEL. */
    void pickModel(AiStore.Profile p, EditText url, EditText keyField, EditText model) {
        String k = keyField.getText().toString().trim();
        if (k.isEmpty()) k = p.apiKey;
        String u = AiClient.trim(url.getText().toString());
        if (u.isEmpty()) u = p.claude() ? AiStore.CLAUDE_URL : AiStore.OPENAI_URL;
        final String key = k, base = u;
        if (key.isEmpty() && !p.claude()) { Notice.info(a, "Isi API key dulu"); return; }
        new Thread(() -> {
            List<String> ids;
            try {
                ids = key.isEmpty() ? new java.util.ArrayList<>() : AiClient.models(p.type, base, key);
            } catch (Exception e) {
                ids = new java.util.ArrayList<>();
            }
            if (ids.isEmpty() && p.claude()) ids = java.util.Arrays.asList(AiStore.CLAUDE_MODELS);
            final List<String> list = ids;
            a.runOnUiThread(() -> {
                if (list.isEmpty()) { Notice.info(a, "Daftar model tidak tersedia; ketik nama model"); return; }
                String[] items = list.toArray(new String[0]);
                int sel = list.indexOf(model.getText().toString().trim());
                SettingsActivity.showDark(new AlertDialog.Builder(a)
                        .setTitle("Pilih model")
                        .setSingleChoiceItems(items, sel, (d, w) -> {
                            model.setText(items[w]);
                            d.dismiss();
                        })
                        .setNegativeButton("Tutup", null));
            });
        }, "ai-models").start();
    }

    void save(AiStore.Profile p) {
        List<AiStore.Profile> ps = AiStore.profiles(a);
        boolean found = false;
        for (int i = 0; i < ps.size(); i++) {
            if (ps.get(i).id.equals(p.id)) { ps.set(i, p); found = true; }
        }
        if (!found) ps.add(p);
        AiStore.saveProfiles(a, ps);
        if (ps.size() == 1 || !found && AiStore.active(a) == null) AiStore.setActive(a, p.id);
    }

    void confirmDelete(AiStore.Profile p) {
        SettingsActivity.showDark(new AlertDialog.Builder(a)
                .setMessage("Hapus profil " + p.name + " beserta API key-nya dari headunit ini?")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (d, w) -> {
                    List<AiStore.Profile> ps = AiStore.profiles(a);
                    for (int i = ps.size() - 1; i >= 0; i--) if (ps.get(i).id.equals(p.id)) ps.remove(i);
                    AiStore.saveProfiles(a, ps);
                    render();
                }));
    }

    void setWebSearch(boolean on) {
        AiStore.setWebSearch(a, on);
        render();
    }

    void editTinyfishKey() {
        LinearLayout form = a.column();
        int pad = a.px(24);
        form.setPadding(pad, pad, pad, 0);
        String cur = AiStore.tinyfishKey(a);
        EditText key = a.field(cur.isEmpty() ? "Tempel API key TinyFish" : "Kosongkan = tetap " + mask(cur), "", true);
        a.addField(form, "API KEY", key);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("API key TinyFish")
                .setView(a.scroll(form))
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d, w) -> {
                    String k = key.getText().toString().trim();
                    if (k.isEmpty()) return;
                    AiStore.saveTinyfishKey(a, k);
                    render();
                });
        if (!cur.isEmpty()) b.setNeutralButton("Hapus key", (d, w) -> {
            AiStore.saveTinyfishKey(a, "");
            render();
        });
        SettingsActivity.showDark(b);
    }

    void editElevenKey() {
        LinearLayout form = a.column();
        int pad = a.px(24);
        form.setPadding(pad, pad, pad, 0);
        String cur = AiStore.elKey(a);
        EditText key = a.field(cur.isEmpty() ? "Tempel API key ElevenLabs" : "Kosongkan = tetap " + mask(cur), "", true);
        a.addField(form, "API KEY", key);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("API key ElevenLabs")
                .setView(a.scroll(form))
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d, w) -> {
                    String k = key.getText().toString().trim();
                    if (k.isEmpty()) return;
                    AiStore.saveElKey(a, k);
                    // Belum memilih suara: pakai suara Indonesia bawaan, supaya langsung bisa bicara.
                    if (AiStore.elVoice(a).isEmpty()) {
                        AiStore.saveElVoice(a, AiStore.EL_VOICE, AiStore.EL_VOICE_NAME);
                        addShared(k, AiStore.EL_VOICE_OWNER, AiStore.EL_VOICE, AiStore.EL_VOICE_NAME, null);
                    }
                    render();
                });
        if (!cur.isEmpty()) b.setNeutralButton("Hapus key", (d, w) -> {
            AiStore.saveElKey(a, "");
            render();
        });
        SettingsActivity.showDark(b);
    }

    void pickElModel() {
        String cur = AiStore.elModel(a);
        String[] names = new String[AiStore.EL_MODELS.length];
        int sel = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = AiStore.EL_MODELS[i][1];
            if (AiStore.EL_MODELS[i][0].equals(cur)) sel = i;
        }
        SettingsActivity.showDark(new AlertDialog.Builder(a)
                .setTitle("Model suara ElevenLabs")
                .setSingleChoiceItems(names, sel, (d, w) -> {
                    AiStore.saveElModel(a, AiStore.EL_MODELS[w][0]);
                    d.dismiss();
                    render();
                })
                .setNegativeButton("Tutup", null));
    }

    /** Satu pilihan suara: dari Voice Library (perlu ditambahkan ke akun) atau yang sudah ada di akun. */
    static final class Voice {
        String id, owner, name, info;
    }

    /** Suara berbahasa Indonesia terpopuler dari Voice Library, lalu suara yang sudah ada di akun. */
    void pickVoice() {
        String key = AiStore.elKey(a);
        if (key.isEmpty()) { Notice.info(a, "Isi API key ElevenLabs dulu"); return; }
        AlertDialog wait = SettingsActivity.showDark(new AlertDialog.Builder(a).setMessage("Mengambil daftar suara…"));
        new Thread(() -> {
            List<Voice> vs = new java.util.ArrayList<>();
            String err = null;
            java.util.Map<String, String> h = new java.util.HashMap<>();
            h.put("xi-api-key", key);
            try {
                org.json.JSONArray lib = AiClient.get(AiVoice.EL_API + "/shared-voices?language=id&page_size=30"
                        + "&sort=usage_character_count_1y", h).optJSONArray("voices");
                for (int i = 0; lib != null && i < lib.length(); i++) {
                    org.json.JSONObject o = lib.getJSONObject(i);
                    if (!o.optBoolean("free_users_allowed", true)) continue; // hanya yang bisa semua paket
                    Voice v = new Voice();
                    v.id = o.optString("voice_id");
                    v.owner = o.optString("public_owner_id");
                    v.name = o.optString("name");
                    v.info = "Indonesia · " + gender(o.optString("gender")) + " · " + o.optString("use_case").replace('_', ' ');
                    vs.add(v);
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            try {
                org.json.JSONArray mine = AiClient.get(AiVoice.EL_API.replace("/v1", "/v2") + "/voices?page_size=100", h)
                        .optJSONArray("voices");
                for (int i = 0; mine != null && i < mine.length(); i++) {
                    org.json.JSONObject o = mine.getJSONObject(i);
                    Voice v = new Voice();
                    v.id = o.optString("voice_id");
                    v.name = o.optString("name");
                    org.json.JSONObject l = o.optJSONObject("labels");
                    v.info = "Di akun · " + (l != null ? gender(l.optString("gender")) + " · " + l.optString("accent") : o.optString("category"));
                    vs.add(v);
                }
            } catch (Exception e) {
                if (err == null) err = e.getMessage();
            }
            final String e = err;
            a.runOnUiThread(() -> {
                wait.dismiss();
                if (vs.isEmpty()) { Notice.fail(a, "Daftar suara tidak bisa diambil" + (e != null ? ": " + e : "")); return; }
                showVoices(key, vs);
            });
        }, "el-voices").start();
    }

    static String gender(String g) {
        return "male".equals(g) ? "pria" : "female".equals(g) ? "wanita" : g.isEmpty() ? "-" : g;
    }

    void showVoices(String key, List<Voice> vs) {
        String cur = AiStore.elVoice(a);
        String[] items = new String[vs.size()];
        int sel = -1;
        for (int i = 0; i < vs.size(); i++) {
            Voice v = vs.get(i);
            boolean rec = AiStore.EL_VOICE.equals(v.id) && v.owner != null;
            items[i] = (rec ? "★ " : "") + v.name + "\n" + v.info + (rec ? " · disarankan" : "");
            if (sel < 0 && v.id.equals(cur)) sel = i;
        }
        SettingsActivity.showDark(new AlertDialog.Builder(a)
                .setTitle("Pilih suara")
                .setSingleChoiceItems(items, sel, (d, w) -> {
                    d.dismiss();
                    Voice v = vs.get(w);
                    String nm = v.name.split(" - ")[0].trim();
                    if (v.owner != null) addShared(key, v.owner, v.id, nm, this::speakTest);
                    else { AiStore.saveElVoice(a, v.id, nm); render(); speakTest(); }
                })
                .setNegativeButton("Tutup", null));
    }

    /** Suara Voice Library harus ditambahkan ke akun dulu; id hasilnya yang dipakai untuk TTS. */
    void addShared(String key, String owner, String id, String name, Runnable then) {
        AiStore.saveElVoice(a, id, name);
        new Thread(() -> {
            String use = id;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(AiVoice.EL_API
                        + "/voices/add/" + owner + "/" + id).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("xi-api-key", key);
                c.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream o = c.getOutputStream()) {
                    o.write(new org.json.JSONObject().put("new_name", name + " (KlikDash)").toString()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                String txt = AiClient.read(code >= 400 ? c.getErrorStream() : c.getInputStream());
                // Sudah pernah ditambahkan juga mengembalikan error; id aslinya tetap bisa dipakai.
                if (code < 400) use = new org.json.JSONObject(txt).optString("voice_id", id);
                c.disconnect();
            } catch (Exception ignored) { }
            final String u = use;
            a.runOnUiThread(() -> {
                AiStore.saveElVoice(a, u, name);
                render();
                if (then != null) then.run();
            });
        }, "el-add").start();
    }
}
