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
 * ElevenLabs. API key hanya disimpan terenkripsi (lihat {@link AiStore}) dan ditampilkan tersamar. Teks tab ini
 * mengikuti bahasa antarmuka; BAHASA ASISTEN memilih bahasa yang didengar dan diucapkan TANYA.
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

        list.addView(a.row(a.getString(R.string.ai_profiles_title),
                a.getString(ps.isEmpty() ? R.string.ai_profiles_empty : R.string.ai_profiles_desc),
                a.key("+ CLAUDE", ps.isEmpty(), () -> edit(AiStore.newProfile(AiStore.CLAUDE), true)),
                a.key(a.narrow ? "+ OPENAI" : "+ OPENAI-COMPATIBLE", false,
                        () -> edit(AiStore.newProfile(AiStore.OPENAI), true))));

        for (AiStore.Profile p : ps) {
            boolean on = act != null && act.id.equals(p.id);
            String host = Uri.parse(p.baseUrl).getHost();
            String desc = (p.claude() ? "Claude" : "OpenAI-compatible") + " · "
                    + (p.model.isEmpty() ? a.getString(R.string.ai_model_unset) : p.model)
                    + " · " + (host != null ? host : p.baseUrl) + " · key " + mask(p.apiKey);
            Key test = a.key(a.getString(R.string.ai_test), false, null);
            test.setOnClickListener(v -> test(p, test));
            list.addView(a.row(p.name.toUpperCase(Lang.locale()), desc,
                    a.key(a.getString(on ? R.string.ai_active : R.string.ai_use), on, () -> {
                        AiStore.setActive(a, p.id);
                        render();
                    }),
                    test,
                    a.key(a.getString(R.string.ai_edit), false, () -> edit(p, false))));
        }

        // AKTIF/MATI = TinyFish; tanpa key TinyFish tidak bisa diaktifkan. Selain TinyFish, profil Claude resmi tetap
        // mencari lewat pencarian Anthropic (lihat AiClient).
        String tf = AiStore.tinyfishKey(a);
        boolean hasTf = !tf.isEmpty(), web = hasTf && AiStore.webSearch(a);
        String anthropic = a.getString(R.string.ai_web_anthropic);
        Key webOn = a.key(a.getString(R.string.ai_active), web, () -> setWebSearch(true));
        webOn.setEnabled(hasTf);
        list.addView(a.row(a.getString(R.string.ai_web_title),
                a.getString(R.string.ai_web_intro) + " " + (!hasTf ? a.getString(R.string.ai_web_no_key, anthropic)
                        : web ? a.getString(R.string.ai_web_on, mask(tf)) : a.getString(R.string.ai_web_off, anthropic)),
                webOn,
                a.key(a.getString(R.string.ai_off), !web, () -> setWebSearch(false)),
                a.key(a.narrow ? "KEY" : "KEY TINYFISH", !hasTf, this::editTinyfishKey)));

        String elKey = AiStore.elKey(a), elVoice = AiStore.elVoice(a), elName = AiStore.elVoiceName(a);

        // Bahasa TANYA, terpisah dari bahasa tampilan. Suara bawaan "Putra" berlogat Indonesia: beri tahu di English.
        boolean en = AiStore.en(a);
        String langDesc = a.getString(R.string.ai_lang_desc);
        if (en && AiStore.EL_VOICE_NAME.equals(elName)) langDesc += " " + a.getString(R.string.ai_lang_voice_hint, elName);
        list.addView(a.row(a.getString(R.string.ai_lang_title), langDesc,
                a.key(a.getString(R.string.lang_id), !en, () -> setLang(Lang.ID)),
                a.key(a.getString(R.string.lang_en), en, () -> setLang(Lang.EN))));

        list.addView(a.row(a.getString(R.string.ai_el_title),
                (elKey.isEmpty() ? a.getString(R.string.ai_el_unset) : a.getString(R.string.ai_el_state, mask(elKey),
                        elVoice.isEmpty() ? a.getString(R.string.ai_voice_unpicked) : elName.isEmpty() ? elVoice : elName,
                        AiStore.elModelName(AiStore.elModel(a))))
                        + " " + a.getString(R.string.ai_el_desc),
                a.key("API KEY", elKey.isEmpty(), this::editElevenKey),
                a.key(a.getString(R.string.ai_voice), false, this::pickVoice),
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
        String ttsDesc = (hasTts ? a.getString(R.string.ai_tts_android, appLabel(ttsEngine))
                : a.getString(R.string.ai_tts_missing)) + " "
                + a.getString(!hasEl ? R.string.ai_tts_need_el : hasTts ? R.string.ai_tts_el : R.string.ai_tts_el_only);
        String ttsTitle = a.getString(R.string.ai_tts_title), listen = a.getString(R.string.ai_listen);
        list.addView(hasTts ? a.row(ttsTitle, ttsDesc, elTts, androidTts, a.key(listen, false, this::speakTest))
                : a.row(ttsTitle, ttsDesc, elTts, androidTts,
                        a.key(a.getString(R.string.ai_install), true, () -> openStore(GOOGLE_TTS)),
                        a.key(listen, false, this::speakTest)));

        boolean elStt = hasEl && (AiStore.elStt(a) || !hasStt);
        Key elSttKey = a.key("ELEVENLABS", elStt, () -> setStt(true));
        elSttKey.setEnabled(hasEl);
        Key androidStt = a.key("ANDROID", !elStt && hasStt, () -> setStt(false));
        androidStt.setEnabled(hasStt);
        String sttDesc = (hasStt ? a.getString(R.string.ai_stt_android, sttService != null
                ? appLabel(sttService.getPackageName()) : a.getString(R.string.ai_stt_builtin))
                : a.getString(R.string.ai_stt_missing)) + " "
                + a.getString(!hasEl ? R.string.ai_stt_need_el : hasStt ? R.string.ai_stt_el : R.string.ai_stt_el_only);
        String sttTitle = a.getString(R.string.ai_stt_title);
        list.addView(hasStt ? a.row(sttTitle, sttDesc, elSttKey, androidStt)
                : a.row(sttTitle, sttDesc, elSttKey, androidStt,
                        a.key(a.getString(R.string.ai_install), true, () -> openStore(GOOGLE_APP))));
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

    String mask(String key) {
        if (key == null || key.isEmpty()) return a.getString(R.string.ai_empty);
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

    void setLang(String lang) {
        AiStore.setLang(a, lang);
        render();
    }

    void speakTest() {
        if (AiStore.elKey(a).isEmpty() && !AiVoice.hasAndroidTts(a)) {
            Notice.info(a, a.getString(R.string.ai_need_el_first));
            return;
        }
        if (voice == null) voice = new AiVoice(a);
        voice.say(AiStore.inLang(a).getString(R.string.as_test_say), null);
    }

    void test(AiStore.Profile p, Key k) {
        if (p.apiKey.isEmpty()) { Notice.info(a, a.getString(R.string.ai_key_missing)); return; }
        k.setLabel(a.getString(R.string.ai_testing));
        k.setEnabled(false);
        new Thread(() -> {
            String msg;
            int kind;
            try {
                AiClient c = new AiClient(a, p);
                String r = c.ask(c.inLang.getString(R.string.as_test_prompt));
                msg = r.isEmpty() ? a.getString(R.string.ai_connected) : r;
                kind = Notice.OK;
            } catch (Exception e) {
                msg = e instanceof AiClient.AiError || e.getMessage() == null ? AiClient.describe(a, e) : e.getMessage();
                kind = Notice.FAIL;
            }
            final String m = msg;
            final int kd = kind;
            a.runOnUiThread(() -> {
                k.setLabel(a.getString(R.string.ai_test));
                k.setEnabled(true);
                Notice.show(a, kd, p.name, m);
            });
        }, "ai-test").start();
    }

    void edit(AiStore.Profile p, boolean isNew) {
        LinearLayout form = a.column();
        int pad = a.px(24);
        form.setPadding(pad, pad, pad, 0);
        EditText name = a.field(a.getString(R.string.ai_field_name_hint), p.name, false);
        EditText url = a.field(p.claude() ? AiStore.CLAUDE_URL : AiStore.OPENAI_URL, p.baseUrl, false);
        EditText model = a.field(p.claude() ? AiStore.CLAUDE_MODEL : a.getString(R.string.ai_field_model_hint), p.model, false);
        EditText key = a.field(isNew || p.apiKey.isEmpty() ? a.getString(R.string.ai_field_key_hint)
                : a.getString(R.string.ai_field_keep, mask(p.apiKey)), "", true);
        a.addField(form, a.getString(R.string.ai_label_name), name);
        a.addField(form, "BASE URL", url);
        a.addField(form, "MODEL", model);
        Key pick = a.key(a.getString(R.string.ai_pick_model), false, null);
        pick.setOnClickListener(v -> pickModel(p, url, key, model));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(WRAP, a.px(48));
        pl.topMargin = a.px(8);
        form.addView(pick, form.indexOfChild(model) + 1, pl);
        a.addField(form, "API KEY", key);
        ScrollView s = a.scroll(form);

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(isNew ? (p.claude() ? R.string.ai_new_claude : R.string.ai_new_openai) : R.string.ai_edit_profile)
                .setView(s)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.common_save, null);
        if (!isNew) b.setNeutralButton(R.string.common_delete, (d, w) -> confirmDelete(p));
        AlertDialog d = SettingsActivity.showDark(b);
        // Tombol Simpan tidak langsung menutup dialog, supaya isian yang salah bisa diperbaiki.
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim(), u = AiClient.trim(url.getText().toString());
            String m = model.getText().toString().trim(), k = key.getText().toString().trim();
            if (u.isEmpty()) u = p.claude() ? AiStore.CLAUDE_URL : AiStore.OPENAI_URL;
            if (!u.startsWith("https://") && !u.startsWith("http://")) { Notice.info(a, a.getString(R.string.ai_url_https)); return; }
            if (m.isEmpty() && !p.claude()) { Notice.info(a, a.getString(R.string.ai_need_model)); return; }
            if (k.isEmpty() && p.apiKey.isEmpty()) { Notice.info(a, a.getString(R.string.ai_need_key)); return; }
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
        if (key.isEmpty() && !p.claude()) { Notice.info(a, a.getString(R.string.ai_need_key_first)); return; }
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
                if (list.isEmpty()) { Notice.info(a, a.getString(R.string.ai_no_models)); return; }
                String[] items = list.toArray(new String[0]);
                int sel = list.indexOf(model.getText().toString().trim());
                SettingsActivity.showDark(new AlertDialog.Builder(a)
                        .setTitle(R.string.ai_choose_model)
                        .setSingleChoiceItems(items, sel, (d, w) -> {
                            model.setText(items[w]);
                            d.dismiss();
                        })
                        .setNegativeButton(R.string.common_close, null));
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
                .setMessage(a.getString(R.string.ai_delete_confirm, p.name))
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.common_delete, (d, w) -> {
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
        EditText key = a.field(cur.isEmpty() ? a.getString(R.string.ai_tf_hint) : a.getString(R.string.ai_field_keep, mask(cur)), "", true);
        a.addField(form, "API KEY", key);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(R.string.ai_tf_title)
                .setView(a.scroll(form))
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.common_save, (d, w) -> {
                    String k = key.getText().toString().trim();
                    if (k.isEmpty()) return;
                    AiStore.saveTinyfishKey(a, k);
                    render();
                });
        if (!cur.isEmpty()) b.setNeutralButton(R.string.ai_delete_key, (d, w) -> {
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
        EditText key = a.field(cur.isEmpty() ? a.getString(R.string.ai_el_hint) : a.getString(R.string.ai_field_keep, mask(cur)), "", true);
        a.addField(form, "API KEY", key);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(R.string.ai_el_key_title)
                .setView(a.scroll(form))
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.common_save, (d, w) -> {
                    String k = key.getText().toString().trim();
                    if (k.isEmpty()) return;
                    AiStore.saveElKey(a, k);
                    // Belum memilih suara: pakai suara Indonesia bawaan (asisten berbahasa Indonesia), supaya langsung
                    // bisa bicara. Asisten English: pengguna memilih sendiri di SUARA.
                    if (AiStore.elVoice(a).isEmpty() && !AiStore.en(a)) {
                        AiStore.saveElVoice(a, AiStore.EL_VOICE, AiStore.EL_VOICE_NAME);
                        addShared(k, AiStore.EL_VOICE_OWNER, AiStore.EL_VOICE, AiStore.EL_VOICE_NAME, null);
                    }
                    render();
                });
        if (!cur.isEmpty()) b.setNeutralButton(R.string.ai_delete_key, (d, w) -> {
            AiStore.saveElKey(a, "");
            render();
        });
        SettingsActivity.showDark(b);
    }

    void pickElModel() {
        String cur = AiStore.elModel(a);
        String[] names = new String[AiStore.EL_MODELS.length], desc = a.getResources().getStringArray(R.array.ai_el_model_desc);
        int sel = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = AiStore.EL_MODELS[i][1] + " — " + desc[i];
            if (AiStore.EL_MODELS[i][0].equals(cur)) sel = i;
        }
        SettingsActivity.showDark(new AlertDialog.Builder(a)
                .setTitle(R.string.ai_el_model_title)
                .setSingleChoiceItems(names, sel, (d, w) -> {
                    AiStore.saveElModel(a, AiStore.EL_MODELS[w][0]);
                    d.dismiss();
                    render();
                })
                .setNegativeButton(R.string.common_close, null));
    }

    /** Satu pilihan suara: dari Voice Library (perlu ditambahkan ke akun) atau yang sudah ada di akun. */
    static final class Voice {
        String id, owner, name, info;
    }

    /** Suara terpopuler dalam bahasa asisten dari Voice Library, lalu suara yang sudah ada di akun. */
    void pickVoice() {
        String key = AiStore.elKey(a);
        if (key.isEmpty()) { Notice.info(a, a.getString(R.string.ai_el_need_key)); return; }
        String lang = AiStore.lang(a);
        String langName = a.getString(Lang.EN.equals(lang) ? R.string.ai_voice_lang_en : R.string.ai_voice_lang_id);
        AlertDialog wait = SettingsActivity.showDark(new AlertDialog.Builder(a).setMessage(R.string.ai_loading_voices));
        new Thread(() -> {
            List<Voice> vs = new java.util.ArrayList<>();
            String err = null;
            java.util.Map<String, String> h = new java.util.HashMap<>();
            h.put("xi-api-key", key);
            try {
                org.json.JSONArray lib = AiClient.get(AiVoice.EL_API + "/shared-voices?language=" + lang + "&page_size=30"
                        + "&sort=usage_character_count_1y", h).optJSONArray("voices");
                for (int i = 0; lib != null && i < lib.length(); i++) {
                    org.json.JSONObject o = lib.getJSONObject(i);
                    if (!o.optBoolean("free_users_allowed", true)) continue; // hanya yang bisa semua paket
                    Voice v = new Voice();
                    v.id = o.optString("voice_id");
                    v.owner = o.optString("public_owner_id");
                    v.name = o.optString("name");
                    v.info = langName + " · " + gender(o.optString("gender")) + " · " + o.optString("use_case").replace('_', ' ');
                    vs.add(v);
                }
            } catch (Exception e) {
                err = AiClient.describe(a, e);
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
                    v.info = a.getString(R.string.ai_voice_mine) + " · " + (l != null ? gender(l.optString("gender")) + " · " + l.optString("accent") : o.optString("category"));
                    vs.add(v);
                }
            } catch (Exception e) {
                if (err == null) err = AiClient.describe(a, e);
            }
            final String e = err;
            a.runOnUiThread(() -> {
                wait.dismiss();
                if (vs.isEmpty()) { Notice.fail(a, a.getString(R.string.ai_voices_failed) + (e != null ? ": " + e : "")); return; }
                showVoices(key, vs);
            });
        }, "el-voices").start();
    }

    String gender(String g) {
        return "male".equals(g) ? a.getString(R.string.ai_male) : "female".equals(g) ? a.getString(R.string.ai_female)
                : g.isEmpty() ? "-" : g;
    }

    void showVoices(String key, List<Voice> vs) {
        String cur = AiStore.elVoice(a);
        String[] items = new String[vs.size()];
        int sel = -1;
        for (int i = 0; i < vs.size(); i++) {
            Voice v = vs.get(i);
            boolean rec = AiStore.EL_VOICE.equals(v.id) && v.owner != null;
            items[i] = (rec ? "★ " : "") + v.name + "\n" + v.info + (rec ? " · " + a.getString(R.string.ai_recommended) : "");
            if (sel < 0 && v.id.equals(cur)) sel = i;
        }
        SettingsActivity.showDark(new AlertDialog.Builder(a)
                .setTitle(R.string.ai_choose_voice)
                .setSingleChoiceItems(items, sel, (d, w) -> {
                    d.dismiss();
                    Voice v = vs.get(w);
                    String nm = v.name.split(" - ")[0].trim();
                    if (v.owner != null) addShared(key, v.owner, v.id, nm, this::speakTest);
                    else { AiStore.saveElVoice(a, v.id, nm); render(); speakTest(); }
                })
                .setNegativeButton(R.string.common_close, null));
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
