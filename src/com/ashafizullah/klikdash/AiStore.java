package com.ashafizullah.klikdash;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Profil AI (Claude atau OpenAI-compatible) dan pengaturan suara ElevenLabs.
 * API key dienkripsi dengan kunci AES di Android Keystore, jadi tidak tersimpan sebagai teks biasa.
 */
final class AiStore {
    static final String CLAUDE = "claude", OPENAI = "openai";
    static final String CLAUDE_URL = "https://api.anthropic.com", OPENAI_URL = "https://api.openai.com/v1";
    static final String CLAUDE_MODEL = "claude-opus-5-5";
    static final String EL_MODEL = "eleven_flash_v2_5", EL_STT_MODEL = "scribe_v2";
    /** Suara Indonesia bawaan: "Putra" dari Voice Library ElevenLabs (paling banyak dipakai, boleh untuk akun gratis). */
    static final String EL_VOICE = "RWiGLY9uXI70QL540WNd", EL_VOICE_NAME = "Putra",
            EL_VOICE_OWNER = "c9e3af862b45c80cc29018a0cf11a5feecbba25bbfef3dc811e420a7d82bb47f";
    /** {model_id, nama tampilan}. Semuanya mendukung bahasa Indonesia. */
    static final String[][] EL_MODELS = {
            {"eleven_flash_v2_5", "Flash v2.5 — paling cepat, disarankan di mobil"},
            {"eleven_turbo_v2_5", "Turbo v2.5 — cepat, sedikit lebih natural"},
            {"eleven_multilingual_v2", "Multilingual v2 — paling ekspresif, lebih lambat"},
    };
    /** Cadangan bila daftar model dari API tidak bisa diambil. */
    static final String[] CLAUDE_MODELS = {"claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1"};

    static final class Profile {
        String id, name, type, baseUrl, apiKey, model;

        boolean claude() { return CLAUDE.equals(type); }

        /** API resmi Anthropic: aman memakai effort & fallback penolakan; proxy "Claude-compatible" belum tentu. */
        boolean officialClaude() { return claude() && baseUrl != null && baseUrl.contains("api.anthropic.com"); }
    }

    private AiStore() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("ai", Context.MODE_PRIVATE); }

    static Profile newProfile(String type) {
        Profile p = new Profile();
        p.id = UUID.randomUUID().toString();
        p.type = type;
        p.name = CLAUDE.equals(type) ? "Claude" : "OpenAI-compatible";
        p.baseUrl = CLAUDE.equals(type) ? CLAUDE_URL : OPENAI_URL;
        p.model = CLAUDE.equals(type) ? CLAUDE_MODEL : "";
        p.apiKey = "";
        return p;
    }

    static List<Profile> profiles(Context c) {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("profiles", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Profile p = new Profile();
                p.id = o.optString("id");
                p.name = o.optString("name");
                p.type = o.optString("type", CLAUDE);
                p.baseUrl = o.optString("baseUrl");
                p.model = o.optString("model");
                p.apiKey = decrypt(o.optString("apiKey"));
                out.add(p);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static void saveProfiles(Context c, List<Profile> list) {
        JSONArray a = new JSONArray();
        try {
            for (Profile p : list) {
                a.put(new JSONObject().put("id", p.id).put("name", p.name).put("type", p.type)
                        .put("baseUrl", p.baseUrl).put("model", p.model).put("apiKey", encrypt(p.apiKey)));
            }
        } catch (Exception ignored) { }
        prefs(c).edit().putString("profiles", a.toString()).apply();
    }

    static Profile active(Context c) {
        List<Profile> list = profiles(c);
        String id = prefs(c).getString("active", null);
        for (Profile p : list) if (p.id.equals(id)) return p;
        return list.isEmpty() ? null : list.get(0);
    }

    static void setActive(Context c, String id) { prefs(c).edit().putString("active", id).apply(); }

    // ---------------------------------------------------------------- ElevenLabs

    static String elKey(Context c) { return decrypt(prefs(c).getString("elKey", "")); }

    static String elVoice(Context c) { return prefs(c).getString("elVoice", ""); }

    static String elModel(Context c) { return prefs(c).getString("elModel", EL_MODEL); }

    static String elVoiceName(Context c) { return prefs(c).getString("elVoiceName", ""); }

    static void saveElKey(Context c, String key) { prefs(c).edit().putString("elKey", encrypt(key)).apply(); }

    static void saveElVoice(Context c, String id, String name) {
        prefs(c).edit().putString("elVoice", id).putString("elVoiceName", name).apply();
    }

    static void saveElModel(Context c, String model) { prefs(c).edit().putString("elModel", model).apply(); }

    static String elModelName(String id) {
        for (String[] m : EL_MODELS) if (m[0].equals(id)) return m[1].split(" — ")[0];
        return id;
    }

    /** Pakai suara ElevenLabs untuk jawaban (kalau key & voice terisi); selain itu TTS Android. */
    static boolean elTts(Context c) { return prefs(c).getBoolean("elTts", true); }

    /** Pengenalan suara lewat ElevenLabs Scribe, bukan pengenal Android (bawaan: Android bila ada). */
    static boolean elStt(Context c) { return prefs(c).getBoolean("elStt", false); }

    static void saveEleven(Context c, String key, String voice, String model, boolean tts) {
        prefs(c).edit().putString("elKey", encrypt(key)).putString("elVoice", voice)
                .putString("elModel", model.isEmpty() ? EL_MODEL : model).putBoolean("elTts", tts).apply();
    }

    // ---------------------------------------------------------------- enkripsi (Android Keystore, AES-GCM)

    private static final String ALIAS = "klikdash-ai";

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV(), ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return "v1:" + Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    static String decrypt(String stored) {
        if (stored == null || !stored.startsWith("v1:")) return "";
        try {
            byte[] all = Base64.decode(stored.substring(3), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
