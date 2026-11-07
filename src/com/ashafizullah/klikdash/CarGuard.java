package com.ashafizullah.klikdash;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.BG;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.FAINT;
import static com.ashafizullah.klikdash.HomeActivity.HOT;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Penjaga anti-maling (fitur KlikBeam Pro, diatur dari aplikasi KlikBeam di HP). Bekerja tanpa internet: pengaturan
 * terakhir disimpan di prefs ({@link #K_CFG}). Setiap "mulai berkendara" (proses baru setelah boot, atau layar menyala
 * setelah mati lebih dari {@link #SLEEP_MS}; headunit NWD tidur saat ACC off) penjaga bersiaga selama
 * {@code graceMinutes}. Bila dalam waktu itu tidak ada HP yang dikenali tersambung (hotspot WiFi yang SSID-nya cocok,
 * atau perangkat Bluetooth yang namanya cocok), layar penuh menampilkan pesan dan nomor pemilik sampai PIN dimasukkan.
 * PIN dicek di sini: sha256(pinSalt + PIN) dari server, PIN-nya sendiri tidak pernah ada di headunit.
 *
 * Selama layar itu tampil dan ada internet, lokasi terakhir dikirim tiap {@link #ALERT_MS} (server mengirim push ke
 * pemilik). Keadaan per boot ({@link #K_DRIVE}) disimpan, supaya KlikDash yang crash atau dimulai ulang di tengah jalan
 * menampilkannya lagi. Bluetooth di sebagian firmware NWD ditangani modul terpisah dan tidak terlihat oleh Android:
 * semua pemakaian BluetoothAdapter dijaga dan diabaikan bila gagal; hotspot WiFi adalah sinyal utama.
 */
final class CarGuard {
    private static final String K_CFG = "guardCfg", K_DRIVE = "guardDrive";
    private static final long SLEEP_MS = 2 * 60_000L, TICK_MS = 5_000, SYNC_MS = 15 * 60_000L, ALERT_MS = 2 * 60_000L,
            SEEN_MS = 10 * 60_000L, LOCKOUT_MS = 30_000;
    private static final int MAX_TRIES = 5, PIN_MAX = 6;
    /** Keadaan satu kali berkendara (per boot). */
    private static final int ARMED = 1, SHOWING = 2, DISARMED = 3;

    private static CarGuard instance;

    private final Context app;
    private int state;
    /** {@link SystemClock#elapsedRealtime}: batas bersiaga; layar tampil setelahnya bila belum ada HP dikenali. */
    private long deadline;
    private long screenOffAt = -1, nextSync, nextAlert;
    private final Set<String> btConnected = new HashSet<>();
    private final Map<String, Long> reported = new HashMap<>();
    private Overlay overlay;

    private CarGuard(Context c) { app = c.getApplicationContext(); }

    /** Dipanggil saat home dibuat; aman berkali-kali. Proses baru = mulai berkendara, kecuali boot ini sudah tercatat. */
    static void start(Context c) {
        if (instance != null) return;
        instance = new CarGuard(c);
        instance.init();
    }

    private void init() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            app.registerReceiver(rx, f);
        } catch (RuntimeException ignored) { }
        try {
            ((ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(online);
        } catch (RuntimeException ignored) { }
        seedBluetooth();
        restoreOrArm();
        Account.MAIN.postDelayed(tick, TICK_MS);
    }

    // ---------------------------------------------------------------- pengaturan

    /** Pengaturan tersimpan bila penjaga aktif; null bila mati atau belum pernah disinkronkan. */
    static JSONObject config(Context c) {
        String s = Prefs.of(c).getString(K_CFG, null);
        if (s == null) return null;
        try {
            JSONObject j = new JSONObject(s);
            return j.optBoolean("enabled") ? j : null;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** Ambil pengaturan terbaru dari server (thread NET); offline = tetap pakai salinan lama. */
    private void sync() {
        String auth = Account.deviceAuth(app);
        if (auth == null || System.currentTimeMillis() < Built.TIME) return; // belum terhubung: dicoba lagi tiap tick
        nextSync = SystemClock.elapsedRealtime() + SYNC_MS;
        Account.NET.execute(() -> {
            try {
                JSONObject d = Account.call(app, "GET", "/v1/device/guard", auth, null).getJSONObject("data");
                SharedPreferences.Editor e = Prefs.of(app).edit();
                if (d.optBoolean("enabled")) e.putString(K_CFG, d.toString()); else e.remove(K_CFG);
                e.apply();
                Account.MAIN.post(this::configChanged);
            } catch (Account.ApiError e) {
                if ("DEVICE_UNLINKED".equals(e.code)) {
                    Prefs.of(app).edit().remove(K_CFG).apply();
                    Account.MAIN.post(this::configChanged);
                }
            } catch (Exception ignored) { } // offline
            reportSeen();
        });
    }

    /** Dimatikan dari HP saat tampil: tutup. Baru diaktifkan di tengah jalan: tidak langsung bersiaga (tunggu berkendara berikutnya). */
    private void configChanged() {
        if (config(app) == null && state != 0) {
            hide();
            setState(DISARMED);
        }
        Account.changed();
    }

    // ---------------------------------------------------------------- berkendara

    private void restoreOrArm() {
        String boot = HomeActivity.bootId();
        String saved = Prefs.of(app).getString(K_DRIVE, "");
        String[] p = saved.split(":");
        if (boot != null && p.length == 3 && p[0].equals(boot)) {
            state = parse(p[1]);
            deadline = parseLong(p[2]);
            if (state == SHOWING) Account.MAIN.post(() -> show(false));
            return;
        }
        arm(); // boot baru
    }

    /** Mulai berkendara: bersiaga bila penjaga aktif. */
    private void arm() {
        JSONObject cfg = config(app);
        hide();
        if (cfg == null) {
            setState(0);
            return;
        }
        deadline = SystemClock.elapsedRealtime() + Math.max(1, cfg.optInt("graceMinutes", 3)) * 60_000L;
        setState(trustedPresent(cfg) ? DISARMED : ARMED);
    }

    private void setState(int s) {
        state = s;
        String boot = HomeActivity.bootId();
        if (boot != null) Prefs.of(app).edit().putString(K_DRIVE, boot + ":" + s + ":" + deadline).apply();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            JSONObject cfg = config(app);
            if (cfg != null && (state == ARMED || state == SHOWING) && trustedPresent(cfg)) {
                hide(); // pemilik datang: HP-nya tersambung
                setState(DISARMED);
            } else if (cfg != null && state == ARMED && now >= deadline) {
                setState(SHOWING);
                show(false);
            } else if (state == SHOWING && overlay == null) {
                show(false); // izin overlay baru diberikan, atau gagal tampil sebelumnya
            }
            if (state == SHOWING && overlay != null && !overlay.test && now >= nextAlert) alert();
            if (now >= nextSync) sync();
            Account.MAIN.postDelayed(this, TICK_MS);
        }
    };

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                screenOffAt = SystemClock.elapsedRealtime();
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                // Tidur lebih dari 2 menit (ACC off, NWD tidak mati total) = berkendara baru. Layar tetap tampil bila sedang tampil.
                if (screenOffAt >= 0 && SystemClock.elapsedRealtime() - screenOffAt > SLEEP_MS && state != SHOWING) arm();
                screenOffAt = -1;
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a) || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                String name = btName(i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
                if (name == null) return;
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
                    btConnected.add(name);
                    reportSoon();
                } else {
                    btConnected.remove(name);
                }
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(a)) {
                reportSoon();
            }
        }
    };

    private final ConnectivityManager.NetworkCallback online = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) {
            Account.MAIN.postDelayed(() -> {
                sync();
                if (state == SHOWING) nextAlert = 0;
            }, 3000);
        }
    };

    // ---------------------------------------------------------------- HP dikenali

    /** SSID hotspot yang sedang tersambung (tanpa tanda kutip), atau null. Butuh izin lokasi di Android 8.1+. */
    private String ssid() {
        try {
            WifiManager w = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = w == null ? null : w.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) return null;
            String s = info.getSSID();
            if (s == null) return null;
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
            return s.isEmpty() || "<unknown ssid>".equals(s) ? null : s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String btName(Object o) {
        try {
            if (!(o instanceof BluetoothDevice)) return null;
            String n = ((BluetoothDevice) o).getName();
            return n == null || n.trim().isEmpty() ? null : n.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Perangkat Bluetooth yang sudah tersambung saat proses mulai (isConnected tersembunyi; dicoba lewat refleksi). */
    private void seedBluetooth() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) return;
            for (BluetoothDevice d : ad.getBondedDevices()) {
                try {
                    Object on = BluetoothDevice.class.getMethod("isConnected").invoke(d);
                    String name = btName(d);
                    if (Boolean.TRUE.equals(on) && name != null) btConnected.add(name);
                } catch (Exception ignored) { }
            }
        } catch (RuntimeException ignored) { }
    }

    private boolean trustedPresent(JSONObject cfg) {
        JSONArray t = cfg.optJSONArray("trusted");
        if (t == null) return false;
        String wifi = ssid();
        for (int i = 0; i < t.length(); i++) {
            JSONObject p = t.optJSONObject(i);
            if (p == null) continue;
            String name = p.optString("name");
            if ("wifi".equals(p.optString("type")) && name.equals(wifi)) return true;
            if ("bt".equals(p.optString("type")) && btConnected.contains(name)) return true;
        }
        return false;
    }

    private void reportSoon() { Account.MAIN.postDelayed(() -> Account.NET.execute(this::reportSeen), 3000); }

    /** Laporkan hotspot/Bluetooth yang tersambung (thread NET), paling sering sekali per 10 menit per nama. */
    private void reportSeen() {
        String auth = Account.deviceAuth(app);
        if (auth == null || System.currentTimeMillis() < Built.TIME) return;
        long now = SystemClock.elapsedRealtime();
        String wifi = ssid();
        JSONArray bt = new JSONArray();
        boolean any = false;
        try {
            JSONObject body = new JSONObject();
            if (wifi != null && due("wifi:" + wifi, now)) {
                body.put("wifi", wifi);
                any = true;
            }
            for (String n : new HashSet<>(btConnected)) if (bt.length() < 5 && due("bt:" + n, now)) bt.put(n);
            if (bt.length() > 0) {
                body.put("bt", bt);
                any = true;
            }
            if (!any) return;
            Account.call(app, "POST", "/v1/device/seen", auth, body.toString());
            if (body.has("wifi")) reported.put("wifi:" + wifi, now);
            for (int i = 0; i < bt.length(); i++) reported.put("bt:" + bt.getString(i), now);
        } catch (Exception ignored) { } // offline: dicoba lagi saat tersambung berikutnya
    }

    private boolean due(String key, long now) {
        Long last = reported.get(key);
        return last == null || now - last >= SEEN_MS;
    }

    // ---------------------------------------------------------------- peringatan

    /** Kirim peringatan dengan lokasi terakhir; gagal = dicoba lagi di tick berikutnya atau saat online. */
    private void alert() {
        nextAlert = SystemClock.elapsedRealtime() + ALERT_MS;
        String auth = Account.deviceAuth(app);
        if (auth == null || System.currentTimeMillis() < Built.TIME) return;
        double[] loc = lastLocation();
        Account.NET.execute(() -> {
            try {
                JSONObject b = new JSONObject().put("at", iso(System.currentTimeMillis()));
                if (loc != null) b.put("lat", loc[0]).put("lng", loc[1]);
                Account.call(app, "POST", "/v1/device/guard/alert", auth, b.toString());
            } catch (Exception e) {
                Account.MAIN.post(() -> nextAlert = SystemClock.elapsedRealtime() + 30_000); // coba lagi lebih cepat
            }
        });
    }

    private double[] lastLocation() {
        try {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) return new double[]{best.getLatitude(), best.getLongitude()};
        } catch (RuntimeException ignored) { } // izin lokasi dicabut, atau LocationManager belum siap saat boot
        SharedPreferences p = Prefs.of(app);
        try {
            String la = p.getString("lat", null), lo = p.getString("lon", null);
            if (la != null && lo != null) return new double[]{Double.parseDouble(la), Double.parseDouble(lo)};
        } catch (NumberFormatException ignored) { }
        return null;
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    // ---------------------------------------------------------------- layar

    /** Tab Akun dibuka: ambil pengaturan terbaru sekarang (bila online). */
    static void refresh(Context c) {
        if (instance != null) instance.sync();
    }

    /** Uji dari Pengaturan: tampil sekarang dengan tombol TUTUP UJI, tanpa mengubah keadaan berkendara. */
    static void test(Context c) {
        start(c);
        instance.show(true);
    }

    private void show(boolean test) {
        if (overlay != null) {
            if (overlay.test && !test) hide(); else return;
        }
        if (!Settings.canDrawOverlays(app) && !BeamFloat.allowed(app)) return; // dicoba lagi di tick
        JSONObject cfg = config(app);
        Overlay o = new Overlay(test, cfg);
        if (o.add()) {
            overlay = o;
            if (!test) nextAlert = 0;
        }
    }

    private void hide() {
        if (overlay != null) overlay.remove();
        overlay = null;
    }

    private void pinOk() {
        hide();
        setState(DISARMED);
    }

    private static boolean check(JSONObject cfg, String pin) {
        if (cfg == null) return false;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((cfg.optString("pinSalt") + pin).getBytes("UTF-8"));
            StringBuilder s = new StringBuilder();
            for (byte x : d) s.append(String.format(Locale.US, "%02x", x));
            return s.toString().equals(cfg.optString("pinHash"));
        } catch (Exception e) {
            return false;
        }
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Layar penuh di atas semua aplikasi: tidak bisa ditutup dengan kembali atau home, hanya dengan PIN. */
    private final class Overlay {
        final boolean test;
        final JSONObject cfg;
        final Context c;
        final WindowManager wm;
        final FrameLayout root;
        final float dp;
        final StringBuilder pin = new StringBuilder();
        TextView status;
        Dots dots;
        int wrong;
        long lockoutUntil;

        Overlay(boolean test, JSONObject cfg) {
            this.test = test;
            this.cfg = cfg;
            Theme.load(app);
            c = Lang.wrap(app);
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            dp = c.getResources().getDisplayMetrics().density;
            Typeface heavy = Compat.font(c.getAssets(), "display.ttf", "'opsz' 72, 'wght' 800", Typeface.DEFAULT_BOLD);
            Typeface mono = Compat.font(c.getAssets(), "mono.ttf", "'wdth' 100, 'wght' 400", Typeface.MONOSPACE);
            Typeface monoBold = Compat.font(c.getAssets(), "mono.ttf", "'wdth' 100, 'wght' 700", Typeface.MONOSPACE);

            root = new FrameLayout(c) {
                @Override public boolean dispatchKeyEvent(KeyEvent e) {
                    int k = e.getKeyCode();
                    if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) return true; // tidak bisa ditutup
                    return super.dispatchKeyEvent(e);
                }
            };
            root.setBackgroundColor(0xFF000000 | BG);
            root.setFocusableInTouchMode(true);

            float wDp = c.getResources().getDisplayMetrics().widthPixels / dp;
            float hDp = c.getResources().getDisplayMetrics().heightPixels / dp;
            boolean wide = wDp > hDp;
            LinearLayout body = new LinearLayout(c);
            body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            body.setGravity(Gravity.CENTER);
            int pad = px(wide ? 40 : 24);
            body.setPadding(pad, px(24), pad, px(24));

            // Kiri: peringatan, pesan, nomor.
            LinearLayout info = new LinearLayout(c);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView warn = text("⚠", heavy, 54, HOT);
            info.addView(warn);
            TextView title = text(c.getString(R.string.carguard_attention), monoBold, 16, HOT);
            title.setLetterSpacing(0.25f);
            info.addView(title, top(4));
            String msg = cfg != null ? cfg.optString("message") : "";
            if (msg.isEmpty()) msg = c.getString(R.string.carguard_sample_message);
            TextView m = text(msg, heavy, wide ? 30 : 26, TEXT);
            m.setLineSpacing(0, 1.05f);
            info.addView(m, top(14));
            String phone = cfg != null ? cfg.optString("phone") : "";
            if (!phone.isEmpty()) info.addView(text(phone, heavy, wide ? 56 : 44, AMBER), top(16));
            if (test) {
                TextView note = text(c.getString(R.string.carguard_test_note), mono, 12, DIM);
                info.addView(note, top(18));
            }
            ScrollView infoScroll = new ScrollView(c);
            infoScroll.addView(info);
            body.addView(infoScroll, wide ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Kanan: pad PIN.
            LinearLayout padBox = new LinearLayout(c);
            padBox.setOrientation(LinearLayout.VERTICAL);
            padBox.setGravity(Gravity.CENTER_HORIZONTAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(PANEL);
            bg.setStroke(Math.round(dp), LINE);
            bg.setCornerRadius(Theme.corner(dp));
            padBox.setBackground(bg);
            padBox.setPadding(px(22), px(18), px(22), px(18));
            TextView enter = text(c.getString(R.string.carguard_enter_pin), monoBold, 12, TEXT);
            enter.setLetterSpacing(0.2f);
            enter.setGravity(Gravity.CENTER);
            padBox.addView(enter);
            dots = new Dots();
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(PIN_MAX * 30), px(24));
            dl.topMargin = px(12);
            padBox.addView(dots, dl);
            status = text("", mono, 11, DIM);
            padBox.addView(status, top(8));
            GridLayout grid = new GridLayout(c);
            grid.setColumnCount(3);
            String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
            for (String s : labels) {
                Key k = new Key(c, monoBold, s, "OK".equals(s), null);
                if ("⌫".equals(s)) k.setOnClickListener(v -> back());
                else if ("OK".equals(s)) k.setOnClickListener(v -> submit());
                else k.setOnClickListener(v -> digit(s.charAt(0)));
                GridLayout.LayoutParams gl = new GridLayout.LayoutParams();
                gl.width = px(84);
                gl.height = px(52);
                gl.setMargins(px(4), px(4), px(4), px(4));
                grid.addView(k, gl);
            }
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gp.topMargin = px(10);
            padBox.addView(grid, gp);
            if (test) {
                Key close = new Key(c, monoBold, c.getString(R.string.carguard_close_test), false, CarGuard.this::hide);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48));
                cl.topMargin = px(12);
                padBox.addView(close, cl);
            }
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (wide) pl.leftMargin = px(40); else pl.topMargin = px(20);
            body.addView(padBox, pl);

            root.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }

        @SuppressWarnings("deprecation")
        boolean add() {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Compat.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            try {
                wm.addView(root, lp);
                root.requestFocus();
                return true;
            } catch (RuntimeException e) {
                return false; // izin overlay dicabut tepat saat itu
            }
        }

        void remove() {
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) { }
        }

        boolean lockedOut() { return SystemClock.elapsedRealtime() < lockoutUntil; }

        void digit(char ch) {
            if (lockedOut() || pin.length() >= PIN_MAX) return;
            pin.append(ch);
            dots.invalidate();
        }

        void back() {
            if (pin.length() == 0) return;
            pin.setLength(pin.length() - 1);
            dots.invalidate();
        }

        void submit() {
            if (lockedOut() || pin.length() < 4) return;
            String s = pin.toString();
            pin.setLength(0);
            dots.invalidate();
            if (check(cfg != null ? cfg : config(app), s)) {
                if (test) hide(); else pinOk();
                return;
            }
            if (++wrong >= MAX_TRIES) {
                wrong = 0;
                lockoutUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS;
                countdown();
            } else {
                status.setText(c.getString(R.string.carguard_wrong_pin));
                status.setTextColor(HOT);
            }
            dots.error = true;
            dots.invalidate();
            dots.postDelayed(() -> { dots.error = false; dots.invalidate(); }, 600);
        }

        void countdown() {
            long left = lockoutUntil - SystemClock.elapsedRealtime();
            if (left <= 0) {
                status.setText("");
                status.setTextColor(DIM);
                return;
            }
            status.setText(c.getString(R.string.carguard_wait, (int) ((left + 999) / 1000)));
            status.setTextColor(HOT);
            status.postDelayed(this::countdown, 250);
        }

        TextView text(String s, Typeface tf, float size, int color) {
            TextView t = new TextView(c);
            t.setText(s);
            t.setTypeface(tf);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
            t.setTextColor(color);
            t.setIncludeFontPadding(false);
            return t;
        }

        LinearLayout.LayoutParams top(int dpTop) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = px(dpTop);
            return p;
        }

        int px(float v) { return Math.round(v * dp); }

        /** Titik sebanyak angka yang diketik (maks. 6), merah sesaat bila salah. */
        final class Dots extends View {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            boolean error;

            Dots() { super(c); }

            @Override protected void onDraw(Canvas cv) {
                float r = 6 * dp, step = getWidth() / (float) PIN_MAX, cy = getHeight() / 2f;
                for (int i = 0; i < PIN_MAX; i++) {
                    boolean on = i < pin.length();
                    p.setStyle(on || error ? Paint.Style.FILL : Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f * dp);
                    p.setColor(error ? HOT : on ? AMBER : FAINT);
                    cv.drawCircle(step * i + step / 2f, cy, r, p);
                }
            }
        }
    }

    // ---------------------------------------------------------------- Pengaturan

    /** Teks status untuk tab Akun di Pengaturan. */
    static String status(Context c) {
        JSONObject cfg = config(c);
        if (cfg == null) return c.getString(R.string.carguard_status_off);
        StringBuilder names = new StringBuilder();
        JSONArray t = cfg.optJSONArray("trusted");
        if (t != null) for (int i = 0; i < t.length(); i++) {
            JSONObject p = t.optJSONObject(i);
            if (p == null) continue;
            if (names.length() > 0) names.append(", ");
            names.append(p.optString("name")).append(" (")
                    .append(c.getString("bt".equals(p.optString("type")) ? R.string.carguard_bluetooth : R.string.carguard_hotspot)).append(')');
        }
        return c.getString(R.string.carguard_status_on, names.toString(), cfg.optInt("graceMinutes", 3));
    }
}
