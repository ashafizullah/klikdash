package com.ashafizullah.klikdash;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

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
 * terakhir disimpan di prefs ({@link #K_CFG}). Setiap "mulai berkendara" penjaga bersiaga selama {@code graceMinutes};
 * bila dalam waktu itu tidak ada HP yang dikenali tersambung (hotspot WiFi yang SSID-nya cocok, atau perangkat Bluetooth
 * yang alamat MAC-nya cocok), layar penuh menampilkan pesan dan nomor pemilik sampai PIN dimasukkan. PIN dicek di sini
 * dengan hash dari server ({@link #pinMatches}); PIN-nya sendiri tidak pernah ada di headunit.
 *
 * <p>Mulai berkendara = salah satu dari:
 * <ul>
 *   <li>boot baru ({@code /proc/sys/kernel/random/boot_id} berbeda dari yang tersimpan);</li>
 *   <li>siaran bangun NWD ({@code com.nwd.ACTION_AUTO_SLEEP_WAKE_STATE}, {@code com.nwd.android.ACTION_SYSTEM_AUTO_WAKEUP});</li>
 *   <li>jeda lebih dari {@link #GAP_MS} sejak tick terakhir (elapsedRealtime ikut berjalan saat CPU tidur): headunit
 *   tidur saat ACC off, proses mati selama tidur, atau citra RAM fast boot dipulihkan;</li>
 *   <li>SCREEN_ON setelah layar mati lebih dari {@link #GAP_MS}, tetapi hanya di headunit yang belum pernah mengirim
 *   siaran bangun NWD ({@link #K_NWD}). Di NWD layar bisa dimatikan sambil berkendara, dan itu tidak boleh membatalkan
 *   PIN yang sudah dimasukkan; di headunit lain SCREEN_ON adalah satu-satunya tanda ACC on yang tersedia, jadi di sana
 *   layar yang dimatikan lebih dari 2 menit sambil berkendara memang dihitung berkendara baru.</li>
 * </ul>
 * Mulai berkendara selalu bersiaga (ARMED). HP dikenali tidak dinilai selama {@link #SETTLE_MS} pertama (WiFi/BT dari
 * sebelum tidur masih tercatat sesaat); sesudahnya tick yang menurunkan ke DISARMED. Layar yang sedang tampil tetap
 * tampil saat berkendara baru dimulai.
 *
 * <p>Selama SHOWING dan ada internet, lokasi terakhir dikirim tiap {@link #ALERT_MS}, juga bila layar tidak bisa tampil
 * karena izin overlay belum ada. Keadaan per boot disimpan di prefs DE terpisah "carguard" (paling sering tiap
 * {@link #PERSIST_MS}), bukan prefs utama yang dicerminkan ke cadangan.
 */
final class CarGuard {
    private static final String K_CFG = "guardCfg", STATE_FILE = "carguard", K_DRIVE = "drive", K_NWD = "nwdWake",
            K_LOCK = "lock";
    private static final long GAP_MS = 2 * 60_000L, SETTLE_MS = 20_000, TICK_MS = 5_000, IDLE_TICK_MS = 60_000,
            SYNC_MS = 15 * 60_000L, SYNC_MIN_MS = 60_000, ALERT_MS = 2 * 60_000L, SEEN_MS = 10 * 60_000L,
            PERSIST_MS = 30_000, ALLOW_MS = 10 * 60_000L, LOCKOUT_MS = 30_000, LOCKOUT_MAX_MS = 30 * 60_000L;
    private static final int MAX_TRIES = 5, PIN_MAX = 6, NAME_MAX = 64;
    /** Keadaan satu kali berkendara. */
    private static final int IDLE = 0, ARMED = 1, SHOWING = 2, DISARMED = 3;
    private static final String[] NWD_WAKE = {"com.nwd.ACTION_AUTO_SLEEP_WAKE_STATE", "com.nwd.android.ACTION_SYSTEM_AUTO_WAKEUP"};

    private static CarGuard instance;
    /** Pengecekan PIN (PBKDF2 ±0,5–1 dtk di headunit) di luar thread utama. */
    private static final ExecutorService CHECK = Executors.newSingleThreadExecutor(r -> new Thread(r, "carguard-pin"));

    private final Context app;
    private final SharedPreferences store;
    private int state;
    /** elapsedRealtime: batas bersiaga, akhir masa tenang, tick terakhir. */
    private long deadline, settleUntil, lastTick, lastPersist = -PERSIST_MS;
    private long screenOffAt = -1, nextSync, lastSyncTry = -SYNC_MIN_MS, nextAlert, lastAllowTry = -ALLOW_MS,
            lastNwdWake = -GAP_MS - 1;
    /** Salah PIN berturut-turut, berapa kali sudah dikunci (jeda berlipat), dan akhir jeda (elapsedRealtime). */
    private int wrong, lockLevel;
    private long lockoutUntil;
    /** Bluetooth yang tersambung: alamat MAC → nama. Dibaca juga di thread NET. */
    private final Map<String, String> btConnected = new ConcurrentHashMap<>();
    private final Map<String, Long> reported = new ConcurrentHashMap<>();
    private Overlay overlay;
    private boolean wasLinked = true;
    /** Pengaturan yang sudah diurai, dipakai ulang sampai teks di prefs berubah. */
    private String cfgRaw;
    private JSONObject cfg;

    private CarGuard(Context c) {
        app = c.getApplicationContext();
        store = states(app);
    }

    private static SharedPreferences states(Context c) {
        return c.getApplicationContext().createDeviceProtectedStorageContext().getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE);
    }

    /** Dipanggil saat home tampil; aman berkali-kali. */
    static void start(Context c) {
        if (instance != null) return;
        instance = new CarGuard(c);
        instance.init();
    }

    private void init() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        for (String a : NWD_WAKE) f.addAction(a);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            app.registerReceiver(rx, f);
        } catch (RuntimeException ignored) { }
        try {
            ((ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(online);
        } catch (RuntimeException ignored) { }
        // Layar diputar atau ukurannya berubah: tata letak (lebar/tinggi, ukuran tombol) dihitung ulang.
        app.registerComponentCallbacks(new android.content.ComponentCallbacks() {
            @Override public void onConfigurationChanged(android.content.res.Configuration cfg) {
                Account.MAIN.post(() -> {
                    if (overlay == null) return;
                    boolean test = overlay.test;
                    hide();
                    show(test);
                });
            }
            @Override public void onLowMemory() { }
        });
        restore();
        Account.MAIN.postDelayed(tick, TICK_MS);
    }

    // ---------------------------------------------------------------- pengaturan

    /** Pengaturan tersimpan bila penjaga aktif; null bila mati atau belum pernah disinkronkan. */
    static JSONObject config(Context c) {
        CarGuard g = instance;
        return g != null ? g.cfg() : parse(Prefs.of(c).getString(K_CFG, null));
    }

    private JSONObject cfg() {
        String raw = Prefs.of(app).getString(K_CFG, null);
        if (raw == null ? cfgRaw != null : !raw.equals(cfgRaw)) {
            cfgRaw = raw;
            cfg = parse(raw);
        }
        return cfg;
    }

    private static JSONObject parse(String s) {
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
        if (auth == null || System.currentTimeMillis() < Built.TIME) return; // belum terhubung / jam salah: dicoba lagi nanti
        long now = SystemClock.elapsedRealtime();
        nextSync = now + SYNC_MS;
        lastSyncTry = now;
        Account.NET.execute(() -> {
            try {
                JSONObject d = Account.call(app, "GET", "/v1/device/guard", auth, null).getJSONObject("data");
                if (!auth.equals(Account.deviceAuth(app))) return; // dilepas dari akun selama menunggu
                SharedPreferences.Editor e = Prefs.of(app).edit();
                if (d.optBoolean("enabled")) e.putString(K_CFG, d.toString()); else e.remove(K_CFG);
                e.apply();
                Account.MAIN.post(this::configChanged);
            } catch (Exception ignored) { } // offline; DEVICE_UNLINKED → Account.forget → forget()
            reportSeen();
        });
    }

    /**
     * Dimatikan dari HP: tutup. Pesan, nomor, atau PIN diubah saat layar tampil: bangun ulang dengan isi baru. Baru
     * diaktifkan di tengah jalan: tidak langsung bersiaga (tunggu berkendara berikutnya).
     */
    private void configChanged() {
        JSONObject c = cfg();
        if (c == null && state != IDLE) {
            hide();
            setState(DISARMED);
        } else if (c != null && overlay != null && !overlay.test && !c.optString("updatedAt").equals(overlay.stamp)) {
            hide();
            show(false);
        }
        Account.changed();
    }

    /** Headunit dilepas dari akun ({@link Account#forget}): buang pengaturan dan keadaan, tutup layar. */
    static void forget(Context c) {
        Prefs.of(c).edit().remove(K_CFG).apply();
        states(c).edit().remove(K_DRIVE).remove(K_LOCK).apply();
        CarGuard g = instance;
        if (g != null) Account.MAIN.post(() -> {
            g.hide();
            g.state = IDLE;
            g.wrong = 0;
            g.lockLevel = 0;
            g.lockoutUntil = 0;
            g.nextSync = 0; // terhubung lagi (akun lain): pengaturan baru langsung diambil
        });
    }

    // ---------------------------------------------------------------- berkendara

    /** Proses baru: lanjutkan keadaan boot ini, atau mulai berkendara (boot baru, atau jeda panjang sejak tick terakhir). */
    private void restore() {
        String boot = HomeActivity.bootId();
        long now = SystemClock.elapsedRealtime();
        lastTick = now;
        try {
            JSONObject l = new JSONObject(store.getString(K_LOCK, "{}"));
            lockLevel = l.optInt("level");
            wrong = l.optInt("wrong");
            long left = l.optLong("left");
            if (left > 0) lockoutUntil = now + left; // jeda yang belum habis berlanjut, juga setelah boot ulang
        } catch (org.json.JSONException ignored) { }
        try {
            JSONObject d = new JSONObject(store.getString(K_DRIVE, "{}"));
            long tickAt = d.optLong("tick");
            if (boot != null && boot.equals(d.optString("boot")) && tickAt > 0 && tickAt <= now && now - tickAt <= GAP_MS) {
                state = d.optInt("state");
                deadline = d.optLong("deadline");
                settleUntil = d.optLong("settle");
                if (state == SHOWING && cfg() == null) setState(DISARMED); // tanpa pengaturan tidak bisa dibuka: jangan tampil
                else if (state == SHOWING) Account.MAIN.post(() -> show(false));
                return;
            }
            if (boot != null && boot.equals(d.optString("boot")) && d.optInt("state") == SHOWING) state = SHOWING; // tetap tampil
        } catch (org.json.JSONException ignored) { }
        driveStart();
    }

    /** Mulai berkendara: selalu bersiaga dulu; HP dikenali dinilai tick setelah masa tenang. */
    private void driveStart() {
        long now = SystemClock.elapsedRealtime();
        JSONObject c = cfg();
        seedBluetooth();
        settleUntil = now + SETTLE_MS;
        if (c == null) {
            hide();
            setState(IDLE);
        } else if (state == SHOWING) {
            setState(SHOWING); // layar sedang tampil: tetap tampil, tanpa masa tunggu baru
            if (overlay == null) Account.MAIN.post(() -> show(false));
        } else {
            hide();
            deadline = now + Math.max(1, c.optInt("graceMinutes", 3)) * 60_000L;
            setState(ARMED);
        }
    }

    private void setState(int s) {
        if (s == SHOWING && state != SHOWING) nextAlert = 0;
        state = s;
        persist(true);
    }

    /** Simpan keadaan berkendara dan kunci PIN; dari tick paling sering tiap PERSIST_MS. */
    private void persist(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastPersist < PERSIST_MS) return;
        lastPersist = now;
        try {
            String boot = HomeActivity.bootId();
            store.edit()
                    .putString(K_DRIVE, new JSONObject().put("boot", boot == null ? "" : boot).put("state", state)
                            .put("deadline", deadline).put("settle", settleUntil).put("tick", now).toString())
                    .putString(K_LOCK, new JSONObject().put("level", lockLevel).put("wrong", wrong)
                            .put("left", Math.max(0, lockoutUntil - now)).toString())
                    .apply();
        } catch (org.json.JSONException ignored) { }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            boolean slept = now - lastTick > GAP_MS; // CPU tidur (ACC off) atau proses beku: berkendara baru
            lastTick = now;
            if (slept) driveStart();
            JSONObject c = cfg();
            boolean linked = Account.linked(app);
            if (linked && !wasLinked) nextSync = 0; // baru dipasangkan: ambil pengaturan sekarang
            wasLinked = linked;
            boolean active = c != null && linked;
            if (active && (state == ARMED || state == SHOWING) && now >= settleUntil && trustedPresent(c)) {
                hide(); // pemilik datang: HP-nya tersambung
                setState(DISARMED);
            } else if (active && state == ARMED && now >= deadline) {
                setState(SHOWING);
                show(false);
            } else if (state == SHOWING && (overlay == null || overlay.test)) {
                show(false); // izin overlay baru diberikan, atau gagal tampil sebelumnya
            }
            if (state == SHOWING && now >= nextAlert) alert(); // juga tanpa izin overlay: pemilik tetap diberi tahu
            if (now >= nextSync) sync();
            persist(false);
            boolean busy = active || state == SHOWING || overlay != null;
            Account.MAIN.postDelayed(this, busy ? TICK_MS : IDLE_TICK_MS);
        }
    };

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            long now = SystemClock.elapsedRealtime();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                screenOffAt = now;
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                boolean longOff = screenOffAt >= 0 && now - screenOffAt > GAP_MS;
                screenOffAt = -1;
                if (longOff && !store.getBoolean(K_NWD, false)) driveStart(); // lihat catatan kelas
            } else if (NWD_WAKE[0].equals(a) || NWD_WAKE[1].equals(a)) {
                if (!store.getBoolean(K_NWD, false)) store.edit().putBoolean(K_NWD, true).apply();
                if (now - lastNwdWake > GAP_MS) driveStart(); // dua siaran untuk satu kali bangun: yang kedua diabaikan
                lastNwdWake = now;
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a) || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                Object d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String addr = btAddr(d);
                if (addr == null) return;
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
                    String name = btName(d);
                    btConnected.put(addr, name == null ? "" : name);
                    reportSoon();
                } else {
                    btConnected.remove(addr);
                }
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(a)) {
                reportSoon();
            }
        }
    };

    private final ConnectivityManager.NetworkCallback online = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) {
            Account.MAIN.postDelayed(() -> {
                if (SystemClock.elapsedRealtime() - lastSyncTry >= SYNC_MIN_MS) sync(); // jaringan naik-turun: paling sering 1×/menit
                if (state == SHOWING) nextAlert = 0;
            }, 3000);
        }
    };

    // ---------------------------------------------------------------- HP dikenali

    /** SSID hotspot yang sedang tersambung (tanpa tanda kutip), atau null. Butuh izin lokasi di Android 8.1+. */
    private static String ssid(Context app) {
        try {
            WifiManager w = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = w == null ? null : w.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) return null;
            String s = info.getSSID();
            if (s == null) return null;
            s = s.trim();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1).trim();
            return s.isEmpty() || "<unknown ssid>".equals(s) ? null : s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Hotspot tidak bisa dikenali: izin lokasi (atau lokasi latar di Android 10+) belum ada, WiFi tersambung tetapi SSID
     * tidak terbaca (mis. lokasi dimatikan), atau headunit sendiri sedang menjadi hotspot. Hanya untuk peringatan.
     */
    private static boolean ssidBlind(Context app) {
        if (app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return true;
        if (Build.VERSION.SDK_INT >= 29 && app.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return true;
        try {
            WifiManager w = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (w == null) return false;
            try {
                if (Boolean.TRUE.equals(WifiManager.class.getMethod("isWifiApEnabled").invoke(w))) return true;
            } catch (Exception ignored) { } // tersembunyi; firmware tanpa metode itu
            WifiInfo info = w.getConnectionInfo();
            return info != null && info.getNetworkId() != -1 && ssid(app) == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String btName(Object o) {
        try {
            if (!(o instanceof BluetoothDevice)) return null;
            String n = ((BluetoothDevice) o).getName();
            if (n == null || n.trim().isEmpty()) return null;
            n = n.trim();
            return n.length() > NAME_MAX ? n.substring(0, NAME_MAX) : n;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String btAddr(Object o) {
        try {
            if (!(o instanceof BluetoothDevice)) return null;
            String a = ((BluetoothDevice) o).getAddress();
            return a == null || a.isEmpty() ? null : a.toUpperCase(Locale.US);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Perangkat Bluetooth yang tersambung sekarang (isConnected tersembunyi; dicoba lewat refleksi). */
    private void seedBluetooth() {
        btConnected.clear();
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) return;
            for (BluetoothDevice d : ad.getBondedDevices()) {
                try {
                    Object on = BluetoothDevice.class.getMethod("isConnected").invoke(d);
                    String addr = btAddr(d), name = btName(d);
                    if (Boolean.TRUE.equals(on) && addr != null) btConnected.put(addr, name == null ? "" : name);
                } catch (Exception ignored) { }
            }
        } catch (RuntimeException ignored) { }
    }

    /** Bluetooth dicocokkan dengan alamat MAC bila entri dikenali punya; nama hanya untuk entri lama tanpa alamat. */
    private boolean trustedPresent(JSONObject cfg) {
        JSONArray t = cfg.optJSONArray("trusted");
        if (t == null) return false;
        String wifi = ssid(app);
        for (int i = 0; i < t.length(); i++) {
            JSONObject p = t.optJSONObject(i);
            if (p == null) continue;
            String name = p.optString("name").trim();
            if ("wifi".equals(p.optString("type"))) {
                if (!name.isEmpty() && name.equals(wifi)) return true;
            } else if ("bt".equals(p.optString("type"))) {
                String addr = p.optString("addr").trim().toUpperCase(Locale.US);
                if (!addr.isEmpty() ? btConnected.containsKey(addr) : !name.isEmpty() && btConnected.containsValue(name)) return true;
            }
        }
        return false;
    }

    private void reportSoon() { Account.MAIN.postDelayed(() -> Account.NET.execute(this::reportSeen), 3000); }

    /** Laporkan hotspot/Bluetooth yang tersambung (thread NET), paling sering sekali per 10 menit per perangkat. */
    private void reportSeen() {
        String auth = Account.deviceAuth(app);
        if (auth == null || System.currentTimeMillis() < Built.TIME) return;
        long now = SystemClock.elapsedRealtime();
        String wifi = ssid(app);
        try {
            JSONObject body = new JSONObject();
            JSONArray bt = new JSONArray();
            if (wifi != null && due("wifi:" + wifi, now)) body.put("wifi", wifi);
            for (Map.Entry<String, String> e : btConnected.entrySet()) {
                if (bt.length() >= 5 || !due("bt:" + e.getKey(), now)) continue;
                String name = e.getValue().isEmpty() ? e.getKey() : e.getValue();
                bt.put(new JSONObject().put("name", name.length() > NAME_MAX ? name.substring(0, NAME_MAX) : name).put("addr", e.getKey()));
            }
            if (bt.length() > 0) body.put("bt", bt);
            if (body.length() == 0) return;
            Account.call(app, "POST", "/v1/device/seen", auth, body.toString());
            if (body.has("wifi")) reported.put("wifi:" + wifi, now);
            for (int i = 0; i < bt.length(); i++) reported.put("bt:" + bt.getJSONObject(i).getString("addr"), now);
        } catch (Exception ignored) { } // offline: dicoba lagi saat tersambung berikutnya
    }

    private boolean due(String key, long now) {
        Long last = reported.get(key);
        return last == null || now - last >= SEEN_MS;
    }

    // ---------------------------------------------------------------- peringatan

    /** Kirim peringatan dengan lokasi terakhir; gagal = dicoba lagi lebih cepat, atau saat online. */
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
                Account.MAIN.post(() -> nextAlert = Math.min(nextAlert, SystemClock.elapsedRealtime() + 30_000));
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

    // ---------------------------------------------------------------- PIN

    /**
     * PIN cocok dengan hash dari server. {@code pinAlgo} "pbkdf2-sha1-<iterasi>": PBKDF2-HMAC-SHA1(PIN, salt sebagai
     * byte UTF-8, 32 byte), hex huruf kecil. Tanpa pinAlgo (server lama): sha256(salt + PIN). Lambat; bukan di thread utama.
     */
    static boolean pinMatches(JSONObject cfg, String pin) {
        if (cfg == null || pin.isEmpty()) return false;
        String salt = cfg.optString("pinSalt"), want = cfg.optString("pinHash"), algo = cfg.optString("pinAlgo");
        try {
            byte[] got;
            if (algo.startsWith("pbkdf2-sha1-")) {
                int iterations = Integer.parseInt(algo.substring("pbkdf2-sha1-".length()));
                PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), iterations, 256);
                got = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
            } else if (algo.isEmpty()) {
                got = MessageDigest.getInstance("SHA-256").digest((salt + pin).getBytes(StandardCharsets.UTF_8));
            } else {
                return false; // algoritme baru yang belum dikenal versi ini
            }
            StringBuilder s = new StringBuilder();
            for (byte x : got) s.append(String.format(Locale.US, "%02x", x));
            return MessageDigest.isEqual(s.toString().getBytes(StandardCharsets.US_ASCII), want.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean lockedOut() { return SystemClock.elapsedRealtime() < lockoutUntil; }

    /** PIN salah: setelah MAX_TRIES dikunci 30 dtk, lalu berlipat tiap kali (60, 120 … maks. 30 menit). */
    private void wrongPin() {
        if (++wrong >= MAX_TRIES) {
            wrong = 0;
            lockoutUntil = SystemClock.elapsedRealtime() + Math.min(LOCKOUT_MAX_MS, LOCKOUT_MS << Math.min(lockLevel, 10));
            lockLevel++;
        }
        persist(true);
    }

    private void pinOk() {
        wrong = 0;
        lockLevel = 0;
        lockoutUntil = 0;
        hide();
        setState(DISARMED);
    }

    // ---------------------------------------------------------------- layar

    /** Izin tampil di atas aplikasi lain. */
    static boolean overlayAllowed(Context c) { return Settings.canDrawOverlays(c); }

    /** Layar izin overlay Android untuk KlikDash; firmware tanpa layar itu: coba lewat ADB lokal. */
    static void openOverlaySettings(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            BeamFloat.allowed(c);
        }
    }

    /** Tab Akun dibuka: ambil pengaturan terbaru sekarang (bila online). */
    static void refresh(Context c) {
        if (instance != null) instance.sync();
    }

    /**
     * Uji dari Pengaturan: tampil sekarang dengan tombol TUTUP UJI, tanpa mengubah keadaan berkendara.
     * @return false bila izin overlay belum ada (layar izin dibuka)
     */
    static boolean test(Context c) {
        start(c);
        if (!overlayAllowed(c)) {
            openOverlaySettings(c);
            return false;
        }
        instance.show(true);
        return true;
    }

    private void show(boolean test) {
        if (overlay != null) {
            if (overlay.test && !test) hide(); else return;
        }
        if (!overlayAllowed(app)) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastAllowTry >= ALLOW_MS) { // coba lewat ADB lokal, tidak tiap tick
                lastAllowTry = now;
                BeamFloat.allowed(app);
            }
            return;
        }
        Overlay o = new Overlay(test, cfg());
        if (o.add()) overlay = o;
    }

    private void hide() {
        if (overlay != null) overlay.remove();
        overlay = null;
    }

    /** Layar penuh di atas semua aplikasi: tidak bisa ditutup dengan kembali atau home, hanya dengan PIN. */
    private final class Overlay {
        final boolean test;
        /** updatedAt pengaturan yang ditampilkan; berubah = dibangun ulang ({@link #configChanged}). */
        final String stamp;
        final Context c;
        final WindowManager wm;
        final FrameLayout root;
        final float dp;
        final StringBuilder pin = new StringBuilder();
        final TextView status;
        final Dots dots;
        final Key ok;
        boolean checking;

        Overlay(boolean test, JSONObject cfg) {
            this.test = test;
            stamp = cfg != null ? cfg.optString("updatedAt") : "";
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
            // Tinggi tombol menyesuaikan layar supaya pad selalu muat (sisanya: judul, titik, status, padding, TUTUP UJI).
            float avail = wide ? hDp - 56 : hDp * 0.55f;
            float keyH = Math.max(34, Math.min(52, (avail - (test ? 176 : 118)) / 4f - 8));

            LinearLayout body = new LinearLayout(c);
            body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            body.setGravity(Gravity.CENTER);
            int pad = px(wide ? 40 : 20);
            body.setPadding(pad, px(wide ? 24 : 32), pad, px(24));

            // Info: peringatan, pesan, nomor. Bobot 1 dengan gulir sendiri, jadi pad diukur lebih dulu.
            LinearLayout info = new LinearLayout(c);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.addView(text("⚠", heavy, wide ? 54 : 40, HOT));
            TextView title = text(c.getString(R.string.carguard_attention), monoBold, 16, HOT);
            title.setLetterSpacing(0.25f);
            info.addView(title, top(4));
            String msg = cfg != null ? cfg.optString("message") : "";
            if (msg.isEmpty()) msg = c.getString(R.string.carguard_sample_message);
            TextView m = text(msg, heavy, wide ? 30 : 22, TEXT);
            m.setLineSpacing(0, 1.05f);
            info.addView(m, top(wide ? 14 : 10));
            String phone = cfg != null ? cfg.optString("phone") : "";
            if (!phone.isEmpty()) info.addView(text(phone, heavy, wide ? 56 : 36, AMBER), top(wide ? 16 : 10));
            if (test) info.addView(text(c.getString(R.string.carguard_test_note), mono, 12, DIM), top(14));
            ScrollView infoScroll = new ScrollView(c);
            infoScroll.setFillViewport(true);
            infoScroll.addView(info, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            body.addView(infoScroll, wide ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // Pad PIN.
            LinearLayout padBox = new LinearLayout(c);
            padBox.setOrientation(LinearLayout.VERTICAL);
            padBox.setGravity(Gravity.CENTER_HORIZONTAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(PANEL);
            bg.setStroke(Math.round(dp), LINE);
            bg.setCornerRadius(Theme.corner(dp));
            padBox.setBackground(bg);
            padBox.setPadding(px(20), px(14), px(20), px(14));
            TextView enter = text(c.getString(R.string.carguard_enter_pin), monoBold, 12, TEXT);
            enter.setLetterSpacing(0.2f);
            enter.setGravity(Gravity.CENTER);
            padBox.addView(enter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dots = new Dots();
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(PIN_MAX * 30), px(22));
            dl.topMargin = px(10);
            padBox.addView(dots, dl);
            status = text("", mono, 11, DIM);
            status.setGravity(Gravity.CENTER);
            padBox.addView(status, top(6));
            GridLayout grid = new GridLayout(c);
            grid.setColumnCount(3);
            String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
            Key okKey = null;
            for (String s : labels) {
                Key k = new Key(c, monoBold, s, "OK".equals(s), null);
                if ("⌫".equals(s)) {
                    k.setOnClickListener(v -> back());
                } else if ("OK".equals(s)) {
                    k.setOnClickListener(v -> submit());
                    okKey = k;
                } else {
                    k.setOnClickListener(v -> digit(s.charAt(0)));
                }
                GridLayout.LayoutParams gl = new GridLayout.LayoutParams();
                gl.width = px(84);
                gl.height = px(keyH);
                gl.setMargins(px(4), px(4), px(4), px(4));
                grid.addView(k, gl);
            }
            ok = okKey;
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gp.topMargin = px(8);
            padBox.addView(grid, gp);
            if (test) {
                Key close = new Key(c, monoBold, c.getString(R.string.carguard_close_test), false, CarGuard.this::hide);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44));
                cl.topMargin = px(10);
                padBox.addView(close, cl);
            }
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pl.gravity = Gravity.CENTER;
            if (wide) pl.leftMargin = px(32); else pl.topMargin = px(16);
            body.addView(padBox, pl);

            root.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (!test && lockedOut()) countdown();
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

        boolean blocked() { return checking || (!test && lockedOut()); }

        void digit(char ch) {
            if (blocked() || pin.length() >= PIN_MAX) return;
            pin.append(ch);
            dots.invalidate();
        }

        void back() {
            if (checking || pin.length() == 0) return;
            pin.setLength(pin.length() - 1);
            dots.invalidate();
        }

        /** Dicek dengan pengaturan terbaru (PIN bisa baru diganti dari HP), di thread lain karena PBKDF2 lambat. */
        void submit() {
            if (blocked() || pin.length() < 4) return;
            String s = pin.toString();
            JSONObject latest = cfg();
            checking = true;
            ok.setLabel("…");
            CHECK.execute(() -> {
                boolean good = pinMatches(latest, s);
                Account.MAIN.post(() -> done(good));
            });
        }

        void done(boolean good) {
            checking = false;
            ok.setLabel("OK");
            pin.setLength(0);
            dots.invalidate();
            if (overlay != this) return; // ditutup selama dicek
            if (good) {
                if (test) hide(); else pinOk();
                return;
            }
            if (!test) wrongPin();
            if (!test && lockedOut()) {
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

    /** Teks status untuk tab Akun: jumlah HP dikenali (bukan namanya), dan peringatan bila penjaga tidak bisa bekerja penuh. */
    static String status(Context c) {
        JSONObject cfg = config(c);
        if (cfg == null) return c.getString(R.string.carguard_status_off);
        JSONArray t = cfg.optJSONArray("trusted");
        int n = t == null ? 0 : t.length();
        boolean wifiTrusted = false;
        for (int i = 0; i < n; i++) {
            JSONObject p = t.optJSONObject(i);
            if (p != null && "wifi".equals(p.optString("type"))) wifiTrusted = true;
        }
        StringBuilder s = new StringBuilder(c.getString(R.string.carguard_status_on,
                c.getResources().getQuantityString(R.plurals.carguard_trusted_count, n, n), cfg.optInt("graceMinutes", 3)));
        if (!overlayAllowed(c)) s.append("\n").append(c.getString(R.string.carguard_warn_overlay));
        if (wifiTrusted && ssidBlind(c)) s.append("\n").append(c.getString(R.string.carguard_warn_ssid));
        return s.toString();
    }
}
