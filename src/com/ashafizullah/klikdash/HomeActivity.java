package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** KlikDash: launcher ringan untuk headunit 1280x720. Tanpa animasi, tanpa library. */
public class HomeActivity extends Activity {

    // Palet tema aktif, diisi Theme.apply (nilai di sini = tema AMBER). AMBER = warna aksen tema; INK = teks di atas
    // aksen; SOFT = teks sekunder yang panjang (terjemahan); ACTIVE = latar baris yang sedang dibaca; WARN = peringatan
    // (suhu CPU, kecepatan), tetap amber di tema mana pun.
    static int BG = 0xFF0B0B0A, PANEL = 0xFF151513, LINE = 0xFF2B2A26, TEXT = 0xFFECE6D8,
            DIM = 0xFF8A8478, FAINT = 0xFF4A4740, AMBER = 0xFFFFB000, INK = 0xFF0B0B0A,
            SOFT = 0xFFD9D3C5, ACTIVE = 0xFF1A1710, WARN = 0xFFFFB000;

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    /** Slot bawaan: komponen + label. Bisa diganti dengan tekan lama. */
    static final String[][] DEFAULTS = {
            {"com.google.android.apps.maps/com.google.android.maps.MapsActivity", "MAPS"},
            {"com.waze/.FreeMapAppActivity", "WAZE"},
            {"com.zjinnova.zlink/com.zjinnova.android.zlink.features.main.MainActivity", "ZLINK"},
            {"com.google.android.youtube/.app.honeycomb.Shell$HomeActivity", "YOUTUBE"},
            {"com.nwd.android.phone/.home_horizontalActivity", "TELEPON"},
            {"com.nwd.android.video.ui/.home_horizontalActivity", "VIDEO"},
            {"com.ashafizullah.klikdash/.DzikirActivity", "DZIKIR"},
            {"com.ashafizullah.klikdash/.QuranActivity", "AL-QUR'AN"},
            {"com.nwd.carkit360camera/.home_horizontalActivity", "KAMERA 360"},
            {"com.android.car.setting/.home_horizontalActivity", "SISTEM"},
            {"com.ashafizullah.klikdash/.AskActivity", "TANYA"}, // asisten AI suara; Radio tetap ada di Semua
    };
    static final int SLOTS = DEFAULTS.length;
    /**
     * Pilihan jumlah ubin home (slot + ubin "Semua"), diatur di Pengaturan. Lebih sedikit = ubin, ikon, dan tulisan
     * lebih besar. Slot yang tidak tampil tetap tersimpan dan muncul lagi bila jumlahnya ditambah.
     */
    static final int[] TILE_COUNTS = {12, 9, 6, 4};

    static int tileCount(SharedPreferences p) {
        int n = p.getInt("tiles", SLOTS + 1);
        for (int c : TILE_COUNTS) if (c == n) return n;
        return SLOTS + 1;
    }

    /** Grid home: slot + ubin "Semua"; jumlah kolom dipilih dari ukuran layar (lihat measureScreen). */
    int cols = 4, rows = 3;
    /** Jumlah slot yang tampil di home (ubin "Semua" sesudahnya); tetap sampai home dibangun ulang. */
    int shown = SLOTS;

    // Ukuran layar dalam dp; tata letak dihitung dari sini, bukan ditulis mati untuk 1280×720.
    float wDp, hDp;
    boolean portrait;
    int screenW, screenH; // Configuration.screenWidthDp/HeightDp saat dibangun, untuk deteksi rotasi

    static final Locale ID = new Locale("id", "ID");

    static class App {
        ComponentName cn;
        String label;
        Drawable icon;
    }

    float dp, sp;
    Typeface fHeavy, fBold, fMed, fMono, fMonoBold;
    ColorFilter mono;
    SharedPreferences prefs;

    final ComponentName[] slotCn = new ComponentName[SLOTS];
    final String[] slotLabel = new String[SLOTS];
    final Tile[] tiles = new Tile[SLOTS + 1];
    final List<App> apps = new ArrayList<>();
    final Map<String, App> appByKey = new HashMap<>();
    final Map<String, App> appByPkg = new HashMap<>();

    final SimpleDateFormat fmtClock = new SimpleDateFormat("HH:mm", Locale.US);
    final SimpleDateFormat fmtDay = new SimpleDateFormat("EEEE", ID);
    final SimpleDateFormat fmtDate = new SimpleDateFormat("d MMMM yyyy", ID);

    // Lokasi & jadwal sholat. Tanpa GPS, dipakai kota perkiraan dari zona waktu.
    double lat, lon;
    String city;
    boolean locApprox;
    int[] pray, prayTomorrow;
    int prayDayKey = -1;
    LocationManager lm;

    View home;
    LinearLayout drawer;
    TextView clock, day, date, hijri, speed, drawerTitle, drawerCount, temp, ram, disk;

    // Suhu CPU headunit dari sensor thermal; dibaca tiap 5 detik selama home tampil.
    static int HOT = 0xFFFF5A3C;
    final String cpuZone = findCpuZone();
    final Runnable tempTick = new Runnable() {
        @Override public void run() {
            updateTemp();
            updateMemory();
            temp.postDelayed(this, 5000);
        }
    };

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

    /** Panel kiri sempit (headunit 800/1024 px): label pendek supaya baris status tidak terpotong. */
    String diskLabel = "PENYIMPANAN";

    /** RAM dan penyimpanan internal yang terpakai, dalam persen; peringatan di atas 85% dan 90%. */
    void updateMemory() {
        long total = 0, avail = -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            for (String l; (l = r.readLine()) != null && (total == 0 || avail < 0); ) {
                if (l.startsWith("MemTotal:")) total = kb(l);
                else if (l.startsWith("MemAvailable:")) avail = kb(l);
            }
        } catch (Exception ignored) { }
        if (total > 0 && avail >= 0) {
            int p = (int) Math.round(100.0 * (total - avail) / total);
            ram.setText("RAM " + p + "%");
            ram.setTextColor(p >= 85 ? WARN : DIM);
        } else {
            ram.setText("");
        }
        android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
        long size = fs.getTotalBytes();
        int p = size > 0 ? (int) Math.round(100.0 * (size - fs.getAvailableBytes()) / size) : 0;
        disk.setText(diskLabel + " " + p + "%");
        disk.setTextColor(p >= 90 ? WARN : DIM);
    }

    /** "MemTotal:        4194304 kB" → 4194304 */
    static long kb(String line) {
        try {
            return Long.parseLong(line.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    void updateTemp() {
        String s = readLine(cpuZone);
        if (s == null) { temp.setText(""); return; }
        int c;
        try {
            c = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            temp.setText("");
            return;
        }
        if (c > 1000) c = Math.round(c / 1000f); // sensor melaporkan mili-derajat
        temp.setText(c >= 85 ? "CPU " + c + "°C · PANAS" : "CPU " + c + "°C");
        temp.setTextColor(c >= 85 ? HOT : c >= 70 ? WARN : DIM);
    }
    Gauge gauge;
    PrayerView prayerView;
    GridView grid;
    AppAdapter adapter;
    int pickSlot = -1;
    int drawerCols = 7;

    final BroadcastReceiver timeRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateTime();
            if (!Intent.ACTION_TIME_TICK.equals(i.getAction())) PrayerAlarm.schedule(c); // jam/zona waktu diubah
        }
    };

    final LocationListener locRx = new LocationListener() {
        @Override public void onLocationChanged(Location l) { onLocation(l); }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            loadApps();
            adapter.notifyDataSetChanged();
            for (Tile t : tiles) if (t != null) t.invalidate();
        }
    };

    int themeBuilt;

    @Override protected void onCreate(Bundle b) {
        themeBuilt = Theme.apply(this);
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        sp = getResources().getDisplayMetrics().scaledDensity;
        // Direct boot: saat boot penyimpanan biasa (CE) masih terkunci beberapa detik, dan firmware hanya bisa
        // memilih launcher yang directBootAware. Maka pengaturan disimpan di penyimpanan DE.
        prefs = openPrefs();
        // Baru menyala: rotasi sekarang adalah bawaan firmware, catat untuk keepOrientation.
        if (SystemClock.elapsedRealtime() < 5 * 60_000L) {
            prefs.edit().putInt("homeRotation", getWindowManager().getDefaultDisplay().getRotation()).apply();
        }

        fHeavy = font("display.ttf", "'opsz' 72, 'wght' 800");
        fBold = font("display.ttf", "'opsz' 36, 'wght' 700");
        fMed = font("display.ttf", "'opsz' 36, 'wght' 500");
        fMono = font("mono.ttf", "'wdth' 100, 'wght' 400");
        fMonoBold = font("mono.ttf", "'wdth' 100, 'wght' 700");

        // Ikon dibuat monokrom dalam warna tema; warna asli muncul saat ditekan.
        mono = Theme.iconFilter();

        loadSlots();
        loadApps();
        loadLocation();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        measureScreen();
        home = buildHome();
        drawer = buildDrawer();
        drawer.setVisibility(View.GONE);
        root.addView(home, MATCH, MATCH);
        notch = new QuranNotch(this, fBold, fMonoBold);
        root.addView(notch, QuranNotch.params(dp)); // di bawah laci aplikasi, yang menutupinya saat dibuka
        root.addView(drawer, MATCH, MATCH);
        setContentView(root);

        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeRx, tf);

        IntentFilter pf = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(pkgRx, pf);
        IntentFilter wf = new IntentFilter(Intent.ACTION_SCREEN_ON);
        wf.addAction("com.nwd.ACTION_AUTO_SLEEP_WAKE_STATE");
        wf.addAction("com.nwd.android.ACTION_SYSTEM_AUTO_WAKEUP");
        registerReceiver(wakeRx, wf);
        if (!getSystemService(UserManager.class).isUserUnlocked()) {
            registerReceiver(unlockRx, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        }

        applyLocationExtras(getIntent());
        updateTime();
        startLocation();
        Tweaks.applyAsync();
        PrayerAlarm.allowOverlay(this);
        // Baru menyala (bukan sekadar dibangun ulang, mis. ganti tema atau pembaruan): sambutan.
        if (SystemClock.elapsedRealtime() < 5 * 60_000L) root.post(() -> Welcome.maybeShow(this, prefs));
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startLocation();
    }

    QuranNotch notch;
    final Runnable onQuran = () -> {
        for (Tile t : tiles) if (t != null) t.invalidate();
        notch.refresh();
    };

    @Override protected void onResume() {
        super.onResume();
        if (Theme.stale(this, themeBuilt)) return;
        if (tileCount(openPrefs()) != shown + 1) { recreate(); return; } // jumlah ubin diganti di Pengaturan
        keepOrientation();
        QuranService.listeners.add(onQuran);
        PrayerAlarm.home = new java.lang.ref.WeakReference<>(this);
        PrayerAlarm.schedule(this);
        reloadPrefs();
        onQuran.run();
        updateTime();
        prayerView.startTicking();
        temp.removeCallbacks(tempTick);
        tempTick.run();
        refreshDefaultHome();
        startSpeed();
    }

    // ---------------------------------------------------------------- tanggal Hijriah

    static final String[] HIJRI_MONTHS = {"MUHARRAM", "SAFAR", "RABIUL AWAL", "RABIUL AKHIR", "JUMADIL AWAL",
            "JUMADIL AKHIR", "RAJAB", "SYA'BAN", "RAMADHAN", "SYAWAL", "DZULQA'DAH", "DZULHIJJAH"};

    /**
     * Umm al-Qura (ICU bawaan Android), cocok dengan kalender Kemenag untuk Oktober 2026. Koreksi ±2 hari diatur
     * di Pengaturan (atau ketuk tanggalnya), untuk jaga-jaga bila hasil rukyat berbeda.
     */
    String hijriText(long now) { return hijriText(now, prefs.getInt("hijriOffset", 0)); }

    static String hijriText(long now, int offsetDays) {
        android.icu.util.IslamicCalendar c = new android.icu.util.IslamicCalendar();
        c.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        c.setTimeInMillis(now + offsetDays * 86_400_000L);
        return c.get(android.icu.util.Calendar.DAY_OF_MONTH) + " " + HIJRI_MONTHS[c.get(android.icu.util.Calendar.MONTH)]
                + " " + c.get(android.icu.util.Calendar.YEAR) + " H";
    }

    /** Sebelum Ashar = dzikir pagi, sejak Ashar = dzikir petang. */
    boolean dzikirPagi() {
        Calendar c = Calendar.getInstance();
        int min = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return pray == null || min < pray[2];
    }

    // ---------------------------------------------------------------- kecepatan (GPS)

    /** Kecepatan dari GPS; headunit ini tidak menyediakan kecepatan dari CAN (can_config_speed=0). */
    long speedAt;
    /** Kecepatan terakhir untuk asisten AI (alat car_status); basi bila lastKmhAt lebih dari 10 detik lalu. */
    static volatile int lastKmh;
    static volatile long lastKmhAt;

    final LocationListener speedRx = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            if (!l.hasSpeed()) return;
            speedAt = SystemClock.elapsedRealtime();
            int kmh = Math.round(l.getSpeed() * 3.6f);
            if (kmh < 3) kmh = 0; // diam: abaikan goyangan GPS
            lastKmh = kmh;
            lastKmhAt = speedAt;
            speed.setText(String.valueOf(kmh));
            speed.setTextColor(kmh > 100 ? WARN : TEXT); // di atas batas tol
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    /** Tanpa pembaruan 5 detik (GPS hilang sinyal): tampilkan "--" alih-alih angka basi. */
    final Runnable speedStale = new Runnable() {
        @Override public void run() {
            if (SystemClock.elapsedRealtime() - speedAt > 5000) {
                speed.setText("--");
                speed.setTextColor(TEXT);
            }
            speed.postDelayed(this, 1000);
        }
    };

    /** Hanya selama home tampil: GPS 1 detik (di NWD GPS memang sudah selalu aktif) + passive. */
    void startSpeed() {
        speed.removeCallbacks(speedStale);
        speed.post(speedStale);
        if (lm == null || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, speedRx);
        } catch (Exception ignored) { }
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000, 0, speedRx);
        } catch (Exception ignored) { }
    }

    void stopSpeed() {
        speed.removeCallbacks(speedStale);
        if (lm != null) lm.removeUpdates(speedRx);
    }

    // ---------------------------------------------------------------- tampilan utama (launcher default)

    /** Firmware NWD memilih launcher lewat property ini dan mengabaikan pilihan home Android. */
    Button makeDefault;

    void openSettings() {
        userLaunch = true;
        startActivity(new Intent(this, SettingsActivity.class));
    }

    void refreshDefaultHome() {
        makeDefault.setVisibility(HomeSwitch.isDefault(this) ? View.GONE : View.VISIBLE);
    }

    @Override protected void onPause() {
        stopSpeed();
        prayerView.stopTicking();
        temp.removeCallbacks(tempTick);
        QuranService.listeners.remove(onQuran);
        if (PrayerAlarm.home.get() == this) PrayerAlarm.home.clear();
        super.onPause();
    }

    /** true bila aplikasi di depan dibuka lewat KlikDash (ubin/daftar), bukan muncul sendiri. */
    boolean userLaunch;
    /** Awal jendela "baru menyala": boot (0) atau saat bangun dari tidur (ACC on). */
    long wakeAt;

    /** Headunit umumnya tidur saat ACC off, bukan mati; bangun = ACC on. */
    final BroadcastReceiver wakeRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            wakeAt = SystemClock.elapsedRealtime();
            reloadPrefs();
            onQuran.run();
            updateTime();
            Welcome.maybeShow(HomeActivity.this, prefs);
        }
    };

    @Override protected void onStop() {
        super.onStop();
        // Zlink membuka tampilannya sendiri saat CarPlay nirkabel tersambung setelah headunit menyala. Dalam
        // 3 menit pertama, kembalikan ke home; sesi CarPlay tetap jalan di latar dan bisa dibuka dari ubin Zlink.
        if (!userLaunch && SystemClock.elapsedRealtime() - wakeAt < 180_000) {
            home.postDelayed(() -> Tweaks.shellAsync(Tweaks.HIDE_ZLINK), 1500);
        }
        userLaunch = false;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyLocationExtras(intent);
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
    }

    @Override public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
    }

    @Override protected void onDestroy() {
        if (lm != null) lm.removeUpdates(locRx);
        unregisterReceiver(timeRx);
        unregisterReceiver(pkgRx);
        unregisterReceiver(wakeRx);
        try { unregisterReceiver(unlockRx); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- data

    private static Integer nwdRotation;

    /**
     * Home kembali ke rotasi bawaan firmware. Aplikasi seperti Maps bisa memutar layar ke potret, dan headunit tanpa
     * sensor putar membiarkannya begitu setelah aplikasi itu ditutup. Firmware NWD mengabaikan setRequestedOrientation
     * dari home, jadi rotasinya dikembalikan lewat pengaturan sistem (ADB lokal, seperti Tweaks). Rotasi bawaan dicatat
     * saat headunit baru menyala; NWD: ro.boot.nwd.rotation. Belum diketahui: dibiarkan (mis. headunit tegak).
     */
    void keepOrientation() {
        if (nwdRotation == null) { // getprop menjalankan proses: sekali saja
            int r = -1;
            if (HomeSwitch.isNwd()) {
                try { r = Integer.parseInt(HomeSwitch.getprop("ro.boot.nwd.rotation")); } catch (NumberFormatException ignored) { }
            }
            nwdRotation = r;
        }
        int want = prefs.getInt("homeRotation", nwdRotation);
        if (want < 0 || want > 3 || getWindowManager().getDefaultDisplay().getRotation() == want) return;
        boolean free = android.provider.Settings.System.getInt(getContentResolver(),
                android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        // Kunci sebentar ke rotasi itu, lalu kembalikan putar otomatis bila tadinya aktif (rotasi tetap di sana).
        Tweaks.shellAsync("settings put system accelerometer_rotation 0; settings put system user_rotation " + want
                + (free ? "; settings put system accelerometer_rotation 1" : ""));
    }

    /**
     * Prefs di penyimpanan DE; pindahkan sekali dari CE (versi lama) begitu CE sudah terbuka.
     * MODE_MULTI_PROCESS: baca ulang dari disk bila filenya berubah. Headunit dengan fast boot memulihkan isi RAM
     * lama saat ACC on; tanpa ini susunan ubin lama di memori tampil lagi, lalu tertulis menimpa yang tersimpan.
     */
    @SuppressWarnings("deprecation")
    SharedPreferences openPrefs() {
        Context de = createDeviceProtectedStorageContext();
        if (getSystemService(UserManager.class).isUserUnlocked()) de.moveSharedPreferencesFrom(this, "klikdash");
        return de.getSharedPreferences("klikdash", MODE_MULTI_PROCESS);
    }

    /** Ambil ulang ubin dan lokasi dari disk (lihat openPrefs), juga perubahan dari Pengaturan. */
    void reloadPrefs() {
        prefs = openPrefs();
        loadSlots();
        loadLocation();
        prayDayKey = -1;
    }

    /** Setelah CE terbuka: aplikasi non-directBoot (mis. Maps, Al-Qur'an) baru terlihat oleh PackageManager. */
    final BroadcastReceiver unlockRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            prefs = openPrefs();
            loadSlots();
            loadLocation();
            prayDayKey = -1;
            loadApps();
            adapter.notifyDataSetChanged();
            for (Tile t : tiles) if (t != null) t.invalidate();
            updateTime();
        }
    };

    void loadSlots() {
        for (int i = 0; i < SLOTS; i++) {
            String s = prefs.getString("s" + i, null);
            if (s == null) {
                slotCn[i] = DEFAULTS[i][0] != null ? ComponentName.unflattenFromString(DEFAULTS[i][0]) : null;
                slotLabel[i] = DEFAULTS[i][1];
            } else {
                slotCn[i] = s.isEmpty() ? null : ComponentName.unflattenFromString(s); // "" = slot kosong
                slotLabel[i] = prefs.getString("l" + i, null);
                // v1.3: ubin pengaturan mobil berganti nama jadi SISTEM (Pengaturan kini milik KlikDash).
                if ("PENGATURAN".equals(slotLabel[i]) && slotCn[i] != null
                        && "com.android.car.setting".equals(slotCn[i].getPackageName())) {
                    slotLabel[i] = "SISTEM";
                    prefs.edit().putString("l" + i, "SISTEM").apply();
                }
            }
        }
    }

    /** {@code label} null: pakai nama aplikasi. */
    void saveSlot(int i, ComponentName cn, String label) {
        slotCn[i] = cn;
        slotLabel[i] = label;
        SharedPreferences.Editor e = prefs.edit().putString("s" + i, cn != null ? cn.flattenToString() : "");
        if (label != null) e.putString("l" + i, label); else e.remove("l" + i);
        e.apply();
    }

    void swapSlots(int a, int b) {
        ComponentName ca = slotCn[a];
        String la = slotLabel[a];
        saveSlot(a, slotCn[b], slotLabel[b]);
        saveSlot(b, ca, la);
        tiles[a].invalidate();
        tiles[b].invalidate();
    }

    void loadApps() {
        apps.clear();
        appByKey.clear();
        appByPkg.clear();
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            if (HomeActivity.class.getName().equals(r.activityInfo.name)) continue;
            App a = new App();
            a.cn = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            a.label = String.valueOf(r.loadLabel(pm)).trim();
            a.icon = DzikirActivity.class.getName().equals(r.activityInfo.name)
                    ? getDrawable(R.drawable.ic_dzikir_tile).mutate() : r.loadIcon(pm).mutate();
            apps.add(a);
            appByKey.put(a.cn.flattenToString(), a);
            if (!appByPkg.containsKey(a.cn.getPackageName())) appByPkg.put(a.cn.getPackageName(), a);
        }
        Collections.sort(apps, (x, y) -> x.label.compareToIgnoreCase(y.label));
    }

    App appFor(int slot) {
        ComponentName cn = slotCn[slot];
        if (cn == null) return null;
        App a = appByKey.get(cn.flattenToString());
        return a != null ? a : appByPkg.get(cn.getPackageName());
    }

    void launch(ComponentName cn) {
        userLaunch = true;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Intent li = getPackageManager().getLaunchIntentForPackage(cn.getPackageName());
            try {
                if (li != null) { startActivity(li); return; }
            } catch (Exception ignored) { }
            Notice.fail(this, "Tidak bisa membuka aplikasi");
        }
    }

    // ---------------------------------------------------------------- lokasi & sholat

    void loadLocation() {
        String la = prefs.getString("lat", null), lo = prefs.getString("lon", null);
        if (la != null && lo != null) {
            lat = Double.parseDouble(la);
            lon = Double.parseDouble(lo);
            city = prefs.getString("city", null);
            locApprox = false;
            return;
        }
        // Belum pernah dapat lokasi: tebak ibu kota zona waktu.
        String tz = TimeZone.getDefault().getID();
        double[] g = PrayerTimes.guess(tz);
        lat = g[0];
        lon = g[1];
        city = PrayerTimes.guessCity(tz);
        locApprox = true;
    }

    void setLocation(double la, double lo, String name) {
        lat = la;
        lon = lo;
        city = name;
        locApprox = false;
        SharedPreferences.Editor e = prefs.edit().putString("lat", String.valueOf(la)).putString("lon", String.valueOf(lo));
        if (name != null) e.putString("city", name); else e.remove("city");
        e.apply();
        prayDayKey = -1;
        updateTime();
        PrayerAlarm.schedule(this);
    }

    /** Set lokasi manual lewat adb: am start -n com.ashafizullah.klikdash/.HomeActivity --es lat -6.1754 --es lon 106.8272 --es city Jakarta */
    void applyLocationExtras(Intent i) {
        Bundle x = i != null ? i.getExtras() : null;
        if (x == null || !x.containsKey("lat") || !x.containsKey("lon")) return;
        try {
            double la = Double.parseDouble(String.valueOf(x.get("lat")));
            double lo = Double.parseDouble(String.valueOf(x.get("lon")));
            String name = x.getString("city");
            setLocation(la, lo, name != null ? name.toUpperCase(ID) : null);
        } catch (NumberFormatException ignored) { }
    }

    void startLocation() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            return;
        }
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        for (String p : lm.getProviders(false)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Exception ignored) { }
        }
        if (best != null) onLocation(best);
        // GPS headunit sudah selalu aktif (dipakai layanan NWD); passive cukup menumpang, tanpa beban tambahan.
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 60_000, 1000, locRx);
        } catch (Exception ignored) { }
    }

    void onLocation(Location l) {
        float[] d = new float[1];
        Location.distanceBetween(lat, lon, l.getLatitude(), l.getLongitude(), d);
        if (!locApprox && d[0] < 3000) return; // pindah < 3 km: jadwal praktis sama
        final double la = l.getLatitude(), lo = l.getLongitude();
        // Masih dalam 15 km dari lokasi tersimpan: nama kota lama tetap dipakai (geocoder tidak selalu ada).
        setLocation(la, lo, !locApprox && d[0] < 15000 ? city : null);
        if (!Geocoder.isPresent()) return;
        new Thread(() -> {
            try {
                List<Address> r = new Geocoder(this, ID).getFromLocation(la, lo, 1);
                if (r == null || r.isEmpty()) return;
                Address a = r.get(0);
                String n = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea();
                if (n == null) return;
                final String name = n.replaceFirst("^(Kecamatan|Kota|Kabupaten|Kab\\.)\\s+", "").toUpperCase(ID);
                runOnUiThread(() -> {
                    if (lat == la && lon == lo) setLocation(la, lo, name);
                });
            } catch (Exception ignored) { }
        }, "geocode").start();
    }

    void computePrayer(Calendar now) {
        int key = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR);
        if (key == prayDayKey) return;
        prayDayKey = key;
        pray = PrayerTimes.compute(now, lat, lon);
        Calendar t = (Calendar) now.clone();
        t.add(Calendar.DAY_OF_MONTH, 1);
        prayTomorrow = PrayerTimes.compute(t, lat, lon);
    }

    /** Indeks waktu sholat berikutnya, atau -1 jika berikutnya Subuh besok. */
    int nextPrayer(int minuteOfDay) {
        for (int i = 0; i < pray.length; i++) if (pray[i] > minuteOfDay) return i;
        return -1;
    }

    void updateTime() {
        if (clock == null) return;
        computePrayer(Calendar.getInstance());
        if (prayerView != null) prayerView.invalidate();
        TimeZone tz = TimeZone.getDefault();
        fmtClock.setTimeZone(tz);
        fmtDay.setTimeZone(tz);
        fmtDate.setTimeZone(tz);
        Date now = new Date();
        clock.setText(fmtClock.format(now));
        day.setText(fmtDay.format(now).toUpperCase(ID));
        date.setText(fmtDate.format(now).toUpperCase(ID));
        hijri.setText(hijriText(now.getTime()));
        gauge.invalidate();
    }

    // ---------------------------------------------------------------- layout

    void measureScreen() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        wDp = dm.widthPixels / dm.density;
        hDp = dm.heightPixels / dm.density;
        portrait = hDp > wDp;
        screenW = getResources().getConfiguration().screenWidthDp;
        screenH = getResources().getConfiguration().screenHeightDp;
    }

    /** Ukuran layar berubah (rotasi, layar lain): bangun ulang tata letak dari awal. */
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (c.screenWidthDp != screenW || c.screenHeightDp != screenH) recreate();
    }

    static final float SPEED_RATIO = 0.43f; // ukuran angka kecepatan terhadap jam

    View buildHome() {
        // Landscape: panel jam di kiri (±34% lebar). Portrait: panel jam di atas, ubin di bawah.
        float leftDp = portrait ? wDp - 48 : Math.max(260, Math.min(480, wDp * 0.34f));
        float panelH = portrait ? hDp * 0.42f : hDp;
        float gridW = portrait ? wDp - 48 : wDp - 48 - leftDp - 57;
        int n = tileCount(prefs);
        shown = n - 1;
        cols = n == 4 ? 2 : n == 12 && !portrait && gridW >= 600 ? 4 : 3;
        rows = (n + cols - 1) / cols;
        boolean shortPanel = panelH < 560; // tidak cukup tinggi untuk penggaris 24 jam
        // Jam sebesar mungkin: jam dan kecepatan 3 digit (ukurannya 0,43 × jam) muat berdampingan di lebar panel, dan
        // sebanding tinggi layar. Lebar diukur dengan digit terlebar (angka font ini tidak sama lebar) pada ukuran sp.
        Paint pm = new Paint();
        pm.setTypeface(fHeavy);
        pm.setTextSize(100 * sp);
        float digit = 0;
        for (char d = '0'; d <= '9'; d++) digit = Math.max(digit, pm.measureText(String.valueOf(d)));
        float per100 = (4 * digit + pm.measureText(":") + 3 * digit * SPEED_RATIO) / dp;
        float clockSp = Math.max(56, Math.min(150, Math.min((leftDp - 24) / per100 * 100, panelH * 0.21f)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int pad = px(24);
        row.setPadding(pad, pad, pad, pad);
        row.setBackground(new CropMarks());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        // Model dari sistem (mis. "K2401/K2401P/k2401M" → K2401), bukan ditulis mati.
        String model = android.os.Build.MODEL.split("[/ ]")[0].toUpperCase(Locale.US);
        TextView tag = text("KLIKDASH  ·  " + model, fMono, 12, DIM);
        tag.setLetterSpacing(0.25f);
        top.addView(tag, new LinearLayout.LayoutParams(0, WRAP, 1f));
        // Pengaturan KlikDash (tampilan, Hemat Daya, asisten AI). Kotak sentuh 48dp walau ikonnya kecil.
        TextView gear = text("⚙", fMonoBold, 18, DIM);
        gear.setGravity(Gravity.CENTER);
        gear.setContentDescription("Pengaturan KlikDash");
        gear.setOnClickListener(v -> openSettings());
        top.addView(gear, new LinearLayout.LayoutParams(px(48), px(48)));
        top.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(top);

        // Kondisi headunit: suhu CPU, RAM, dan penyimpanan terpakai (diperbarui tiap 5 detik, lihat tempTick).
        diskLabel = leftDp < 380 ? "DISK" : "PENYIMPANAN";
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        temp = text("", fMonoBold, 11, DIM);
        ram = text("", fMonoBold, 11, DIM);
        disk = text("", fMonoBold, 11, DIM);
        for (TextView t : new TextView[]{temp, ram, disk}) {
            t.setLetterSpacing(0.15f);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(WRAP, WRAP);
            if (t != temp) sl.leftMargin = px(18);
            stats.addView(t, sl);
        }
        left.addView(stats);

        // Muncul hanya bila KlikDash belum jadi tampilan utama (lihat refreshDefaultHome).
        makeDefault = new Button("JADIKAN TAMPILAN UTAMA", true);
        makeDefault.setOnClickListener(v -> HomeSwitch.makeDefault(this, prefs, this::refreshDefaultHome));
        makeDefault.setVisibility(View.GONE);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(MATCH, px(48));
        ml.topMargin = px(14);
        left.addView(makeDefault, ml);
        left.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        // Jam di kiri, kecepatan (GPS) di kanan pada garis dasar yang sama, seperti panel instrumen.
        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text("00:00", fHeavy, clockSp, TEXT);
        clock.setLetterSpacing(-0.01f);
        clockRow.addView(clock);
        clockRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout speedBox = new LinearLayout(this);
        speedBox.setOrientation(LinearLayout.VERTICAL);
        speedBox.setGravity(Gravity.END);
        speed = text("--", fHeavy, clockSp * SPEED_RATIO, TEXT);
        // WRAP, bukan bawaan MATCH_PARENT: hanya lebar WRAP yang diukur ulang saat angkanya berubah. Dengan MATCH_PARENT
        // lebarnya tetap selebar "0" atau "--" saat pertama tampil, sehingga angka 2–3 digit terpotong di kanan.
        speedBox.addView(speed, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView unit = text("KM/H", fMono, 10, DIM);
        unit.setLetterSpacing(0.25f);
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(WRAP, WRAP);
        ul.topMargin = px(4);
        ul.bottomMargin = px(10);
        speedBox.addView(unit, ul);
        clockRow.addView(speedBox);
        left.addView(clockRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        View bar = new View(this);
        bar.setBackgroundColor(AMBER);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(px(84), px(6));
        bl.topMargin = px(14);
        bl.bottomMargin = px(22);
        left.addView(bar, bl);

        day = text("", fMonoBold, 18, AMBER);
        day.setLetterSpacing(0.3f);
        left.addView(day);

        date = text("", fMed, shortPanel ? 28 : 36, TEXT);
        date.setLetterSpacing(0.03f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(WRAP, WRAP);
        dl.topMargin = px(6);
        left.addView(date, dl);

        hijri = text("", fMono, 13, DIM);
        hijri.setLetterSpacing(0.2f);
        hijri.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(WRAP, WRAP);
        hl.topMargin = px(8);
        left.addView(hijri, hl);

        left.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        prayerView = new PrayerView();
        left.addView(prayerView, new LinearLayout.LayoutParams(MATCH, px(104)));
        gauge = new Gauge();
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(MATCH, px(56));
        gl.topMargin = px(18);
        left.addView(gauge, gl);
        if (shortPanel) gauge.setVisibility(View.GONE);

        row.addView(left, portrait ? new LinearLayout.LayoutParams(MATCH, px(panelH - 24))
                : new LinearLayout.LayoutParams(px(leftDp), MATCH));

        View div = new View(this);
        div.setBackgroundColor(LINE);
        LinearLayout.LayoutParams vl = portrait ? new LinearLayout.LayoutParams(MATCH, px(1))
                : new LinearLayout.LayoutParams(px(1), MATCH);
        if (portrait) { vl.topMargin = px(20); vl.bottomMargin = px(20); }
        else { vl.leftMargin = px(28); vl.rightMargin = px(28); }
        row.addView(div, vl);

        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        int gap = px(12);
        for (int r = 0; r < rows; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx > shown) break;
                if (idx == shown) idx = SLOTS; // ubin "Semua" selalu terakhir
                Tile t = new Tile(idx);
                tiles[idx] = t;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH, 1f);
                if (c > 0) lp.leftMargin = gap;
                line.addView(t, lp);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(MATCH, 0, 1f);
            if (r > 0) rl.topMargin = gap;
            g.addView(line, rl);
        }
        row.addView(g, portrait ? new LinearLayout.LayoutParams(MATCH, 0, 1f) : new LinearLayout.LayoutParams(0, MATCH, 1f));
        return row;
    }

    LinearLayout buildDrawer() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setBackgroundColor(BG);
        int pad = px(24);
        d.setPadding(pad, pad, pad, 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        boolean narrow = wDp < 1000;
        drawerTitle = text("SEMUA APLIKASI", fHeavy, narrow ? 30 : 40, TEXT);
        head.addView(drawerTitle);
        drawerCount = text("", fMono, 13, AMBER);
        drawerCount.setLetterSpacing(0.2f);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(WRAP, WRAP);
        cl.leftMargin = px(20);
        head.addView(drawerCount, cl);
        if (narrow) drawerCount.setVisibility(View.GONE);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        Button settings = new Button(wDp < 900 ? "⚙" : "⚙  PENGATURAN", false);
        settings.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(WRAP, px(56));
        stl.rightMargin = px(12);
        head.addView(settings, stl);

        Button close = new Button(wDp < 900 ? "✕" : "TUTUP  ✕", false);
        close.setOnClickListener(v -> closeDrawer());
        head.addView(close, new LinearLayout.LayoutParams(WRAP, px(56)));
        d.addView(head, new LinearLayout.LayoutParams(MATCH, px(64)));

        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(MATCH, px(1));
        rl.topMargin = px(16);
        d.addView(rule, rl);

        grid = new GridView(this);
        drawerCols = Math.max(3, Math.min(9, (int) ((wDp - 48) / 170)));
        grid.setNumColumns(drawerCols);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(px(12));
        grid.setVerticalSpacing(px(12));
        grid.setSelector(new ColorDrawable(0));
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setVerticalScrollBarEnabled(false);
        grid.setPadding(0, px(16), 0, px(24));
        grid.setClipToPadding(false);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> onPick(apps.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", apps.get(pos).cn.getPackageName(), null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        });
        d.addView(grid, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        return d;
    }

    void openDrawer(int slot) {
        pickSlot = slot;
        drawerTitle.setText(slot >= 0
                ? String.format(Locale.US, "PILIH UNTUK SLOT %02d", slot + 1)
                : "SEMUA APLIKASI");
        drawerCount.setText(apps.size() + " APLIKASI");
        grid.setSelection(0);
        home.setVisibility(View.GONE);
        drawer.setVisibility(View.VISIBLE);
    }

    void closeDrawer() {
        pickSlot = -1;
        drawer.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
    }

    void onPick(App a) {
        if (pickSlot >= 0) {
            saveSlot(pickSlot, a.cn, null);
            tiles[pickSlot].invalidate();
        } else {
            launch(a.cn);
        }
        closeDrawer();
    }

    // ---------------------------------------------------------------- helpers

    Typeface font(String file, String axes) { return Compat.font(getAssets(), file, axes, Typeface.DEFAULT_BOLD); }

    TextView text(String s, Typeface tf, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    int px(float v) { return Math.round(v * dp); }

    float sp(float v) { return v * sp; }

    void drawIcon(Canvas c, Drawable d, int left, int top, int size, boolean color) {
        d.setBounds(left, top, left + size, top + size);
        d.setColorFilter(color ? null : mono);
        d.setAlpha(color ? 255 : 225);
        d.draw(c);
    }

    // ---------------------------------------------------------------- views

    /** Panel datar yang berubah amber saat ditekan. Semua digambar sendiri: satu View per tombol. */
    abstract class Panel extends View {
        final Paint fill = new Paint();
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean down;

        Panel() {
            super(HomeActivity.this);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(px(1));
            stroke.setColor(LINE);
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (isPressed() != down) {
                down = isPressed();
                invalidate();
            }
        }

        void drawFrame(Canvas c) {
            int w = getWidth(), h = getHeight();
            fill.setColor(down ? AMBER : PANEL);
            c.drawRect(0, 0, w, h, fill);
            if (!down) {
                float s = stroke.getStrokeWidth() / 2f;
                c.drawRect(s, s, w - s, h - s, stroke);
            }
        }
    }

    class Tile extends Panel {
        final int idx;
        final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint dot = new Paint();

        Tile(int idx) {
            this.idx = idx;
            label.setTypeface(fBold);
            label.setTextSize(sp(30));
            label.setLetterSpacing(0.04f);
            small.setTypeface(fMono);
            small.setTextSize(sp(11));
            small.setLetterSpacing(0.15f);
            setClickable(true);
            setOnClickListener(v -> {
                if (idx == SLOTS) { openDrawer(-1); return; }
                App a = appFor(idx);
                if (a == null) openDrawer(idx); else launch(a.cn);
            });
            if (idx < SLOTS) {
                // Tekan lama: geser ke ubin lain = tukar posisi; lepas di tempat = pilih aplikasi.
                setOnLongClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    v.startDragAndDrop(null, new View.DragShadowBuilder(v), idx, 0);
                    dragging = true;
                    invalidate();
                    return true;
                });
                setOnDragListener((v, e) -> {
                    if (!(e.getLocalState() instanceof Integer)) return false;
                    int from = (Integer) e.getLocalState();
                    switch (e.getAction()) {
                        case DragEvent.ACTION_DRAG_ENTERED:
                            target = idx != from;
                            invalidate();
                            break;
                        case DragEvent.ACTION_DRAG_EXITED:
                            target = false;
                            invalidate();
                            break;
                        case DragEvent.ACTION_DROP:
                            target = false;
                            if (idx == from) openDrawer(idx); else swapSlots(from, idx);
                            break;
                        case DragEvent.ACTION_DRAG_ENDED:
                            target = false;
                            dragging = false;
                            invalidate();
                            break;
                    }
                    return true;
                });
            }
        }

        boolean dragging, target;

        /** Ubin asal saat diseret: diredupkan. Ubin tujuan: bingkai amber tebal. */
        void drawDragState(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (dragging) {
                fill.setColor(0xD9000000 | BG & 0xFFFFFF);
                c.drawRect(0, 0, w, h, fill);
            }
            if (target) {
                fill.setColor(AMBER);
                int t = px(3);
                c.drawRect(0, 0, w, t, fill);
                c.drawRect(0, h - t, w, h, fill);
                c.drawRect(0, 0, t, h, fill);
                c.drawRect(w - t, 0, w, h, fill);
            }
        }

        @Override protected void onDraw(Canvas c) {
            drawFrame(c);
            int w = getWidth(), h = getHeight();
            // Ubin besar (sedikit ubin, layar besar) memperbesar ikon dan tulisan, sampai 1,8×.
            float k = Math.max(0.55f, Math.min(1.8f, Math.min(w / (float) px(180), h / (float) px(190))));
            int m = Math.round(px(16) * k);
            small.setTextSize(sp(11) * Math.max(0.85f, Math.min(1.4f, k)));
            boolean all = idx == SLOTS;
            App a = all ? null : appFor(idx);

            small.setColor(down ? INK : (idx < cols ? AMBER : DIM));
            c.drawText(String.format(Locale.US, "%02d", (all ? shown : idx) + 1), m, m + small.getTextSize(), small);

            int icon = Math.round(px(60) * k);
            String name, sub = null;
            if (all) {
                int cell = Math.round(px(14) * k), g = Math.round(px(7) * k), x0 = w - m - (cell * 3 + g * 2);
                dot.setColor(down ? INK : TEXT);
                for (int i = 0; i < 9; i++) {
                    int x = x0 + (i % 3) * (cell + g), y = m + (i / 3) * (cell + g);
                    if (i == 4 && !down) dot.setColor(AMBER);
                    c.drawRect(x, y, x + cell, y + cell, dot);
                    if (i == 4) dot.setColor(down ? INK : TEXT);
                }
                name = "SEMUA";
                sub = "APLIKASI  →";
            } else if (a != null) {
                drawIcon(c, a.icon, w - m - icon, m, icon, down);
                name = slotLabel[idx] != null ? slotLabel[idx] : a.label.toUpperCase(ID);
                if (DzikirActivity.class.getName().equals(a.cn.getClassName())) {
                    sub = dzikirPagi() ? "PAGI" : "PETANG";
                }
                if (AskActivity.class.getName().equals(a.cn.getClassName())) sub = "ASISTEN AI";
                if (QuranActivity.class.getName().equals(a.cn.getClassName()) && QuranService.playing) {
                    sub = String.format(Locale.US, "▶ %d : %d", QuranService.surah, QuranService.ayah);
                }
            } else {
                name = slotLabel[idx] != null ? slotLabel[idx] : "KOSONG";
                sub = "KETUK UNTUK ISI";
            }

            float base = h - m;
            if (sub != null) {
                small.setColor(down ? INK : DIM);
                CharSequence ss = TextUtils.ellipsize(sub, small, w - m * 2, TextUtils.TruncateAt.END);
                c.drawText(ss, 0, ss.length(), m, base, small);
                base -= small.getTextSize() + px(10) * k;
            }
            label.setColor(down ? INK : (a == null && !all ? FAINT : TEXT));
            // Label panjang dikecilkan dulu (sampai 60%), baru dipotong kalau masih tidak muat.
            float avail = w - m * 2, size = sp(30) * k;
            label.setTextSize(size);
            float tw = label.measureText(name);
            if (tw > avail) label.setTextSize(Math.max(size * 0.6f, size * avail / tw * 0.97f));
            CharSequence s = TextUtils.ellipsize(name, label, avail, TextUtils.TruncateAt.END);
            c.drawText(s, 0, s.length(), m, base, label);
            drawDragState(c);
        }
    }

    class Button extends Panel {
        final String caption;
        /** accent: tombol amber (ajakan), selain itu panel biasa. */
        final boolean accent;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Button(String caption, boolean accent) {
            this.caption = caption;
            setContentDescription(caption); // pembaca layar & pengujian otomatis
            this.accent = accent;
            p.setTypeface(fMonoBold);
            p.setTextSize(sp(13));
            p.setLetterSpacing(0.2f);
            p.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            if (MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY) { super.onMeasure(wSpec, hSpec); return; }
            setMeasuredDimension(resolveSize(Math.round(p.measureText(caption)) + px(44), wSpec),
                    resolveSize(px(48), hSpec));
        }

        @Override protected void onDraw(Canvas c) {
            if (accent) {
                fill.setColor(down ? TEXT : AMBER);
                c.drawRect(0, 0, getWidth(), getHeight(), fill);
            } else {
                drawFrame(c);
            }
            p.setColor(down || accent ? INK : TEXT);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(caption, getWidth() / 2f, getHeight() / 2f - (fm.ascent + fm.descent) / 2f, p);
        }
    }

    class Cell extends Panel {
        App app;
        final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        Cell() {
            label.setTypeface(fBold);
            label.setTextSize(sp(20));
            label.setLetterSpacing(0.04f);
            label.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            drawFrame(c);
            if (app == null) return;
            int w = getWidth(), h = getHeight(), icon = Math.min(px(64), Math.round(h * 0.42f));
            drawIcon(c, app.icon, (w - icon) / 2, Math.round(h * 0.15f), icon, down);
            label.setColor(down ? INK : TEXT);
            CharSequence s = TextUtils.ellipsize(app.label.toUpperCase(ID), label, w - px(20), TextUtils.TruncateAt.END);
            c.drawText(s, 0, s.length(), w / 2f, h - px(20), label);
        }
    }

    class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            Cell c = v instanceof Cell ? (Cell) v : new Cell();
            if (v == null) {
                float colDp = (wDp - 48 - 12 * (drawerCols - 1)) / drawerCols;
                c.setLayoutParams(new AbsListView.LayoutParams(MATCH, px(Math.max(110, Math.min(160, colDp * 0.85f)))));
            }
            c.app = apps.get(i);
            c.invalidate();
            return c;
        }
    }

    /** Penggaris 24 jam dengan jarum di waktu sekarang. Digambar ulang sekali per menit. */
    class Gauge extends View {
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path needle = new Path();

        Gauge() {
            super(HomeActivity.this);
            num.setTypeface(fMono);
            num.setTextSize(sp(10));
            num.setLetterSpacing(0.1f);
        }

        @Override protected void onDraw(Canvas c) {
            Calendar cal = Calendar.getInstance();
            float frac = (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) / 1440f;
            float w = getWidth() - px(2), x0 = px(1), base = px(26), now = x0 + w * frac;

            line.setStrokeWidth(px(1));
            for (int hr = 0; hr <= 24; hr++) {
                float x = x0 + w * hr / 24f;
                boolean major = hr % 6 == 0;
                line.setColor(x <= now ? AMBER : FAINT);
                c.drawLine(x, base - (major ? px(14) : px(7)), x, base, line);
            }
            line.setColor(FAINT);
            c.drawLine(x0, base, x0 + w, base, line);
            line.setColor(AMBER);
            line.setStrokeWidth(px(3));
            c.drawLine(x0, base, now, base, line);

            needle.rewind();
            needle.moveTo(now - px(6), base - px(24));
            needle.lineTo(now + px(6), base - px(24));
            needle.lineTo(now, base - px(16));
            needle.close();
            line.setStyle(Paint.Style.FILL);
            c.drawPath(needle, line);
            line.setStyle(Paint.Style.STROKE);

            // Penanda waktu sholat: belah ketupat kecil di bawah garis, yang berikutnya amber.
            if (pray != null) {
                int next = nextPrayer(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE));
                float r = px(3.5f), cy = base + px(6);
                line.setStyle(Paint.Style.FILL);
                for (int i = 0; i < pray.length; i++) {
                    float x = x0 + w * pray[i] / 1440f;
                    line.setColor(i == next ? AMBER : TEXT);
                    needle.rewind();
                    needle.moveTo(x, cy - r);
                    needle.lineTo(x + r, cy);
                    needle.lineTo(x, cy + r);
                    needle.lineTo(x - r, cy);
                    needle.close();
                    c.drawPath(needle, line);
                }
                line.setStyle(Paint.Style.STROKE);
            }

            num.setColor(DIM);
            float y = base + px(20);
            for (int hr = 0; hr <= 24; hr += 6) {
                String s = String.format(Locale.US, "%02d", hr);
                num.setTextAlign(hr == 0 ? Paint.Align.LEFT : hr == 24 ? Paint.Align.RIGHT : Paint.Align.CENTER);
                c.drawText(s, x0 + w * hr / 24f, y, num);
            }
        }
    }

    /** Lima waktu sholat + hitung mundur ke waktu berikutnya. Digambar ulang sekali per menit. */
    class PrayerView extends View {
        final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint time = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint info = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint rule = new Paint();

        /** Hitung mundur berdetik: hanya view kecil ini yang digambar ulang tiap detik, selama home tampil. */
        final Runnable tick = new Runnable() {
            @Override public void run() {
                invalidate();
                postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
            }
        };

        void startTicking() {
            removeCallbacks(tick);
            tick.run();
        }

        void stopTicking() { removeCallbacks(tick); }

        PrayerView() {
            super(HomeActivity.this);
            name.setTypeface(fMono);
            name.setTextSize(sp(10));
            name.setLetterSpacing(0.15f);
            time.setTypeface(fBold);
            time.setTextSize(sp(30));
            time.setLetterSpacing(0.02f);
            info.setTypeface(fMono);
            info.setTextSize(sp(11));
            info.setLetterSpacing(0.12f);
            rule.setColor(LINE);
        }

        @Override protected void onDraw(Canvas c) {
            if (pray == null) return;
            int w = getWidth();
            Calendar cal = Calendar.getInstance();
            int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            int next = nextPrayer(now);

            c.drawRect(0, 0, w, px(1), rule);
            float col = w / 5f;
            // Panel sempit: kecilkan huruf supaya "MAGHRIB" dan "17:36" tidak bertumpuk dengan kolom sebelah.
            name.setTextSize(Math.min(sp(10), col / 7.2f));
            time.setTextSize(Math.min(sp(30), col * 0.4f));
            for (int i = 0; i < pray.length; i++) {
                float x = i * col;
                int color = i == next ? AMBER : pray[i] <= now ? DIM : TEXT;
                if (i == next) c.drawRect(x, px(1), x + px(28), px(4), paintOf(AMBER));
                name.setColor(i == next ? AMBER : DIM);
                c.drawText(PrayerTimes.NAMES[i], x, px(28), name);
                time.setColor(color);
                c.drawText(String.format(Locale.US, "%02d:%02d", pray[i] / 60, pray[i] % 60), x, px(64), time);
            }

            int target = next >= 0 ? pray[next] : 1440 + prayTomorrow[0];
            int left = (target - now) * 60 - cal.get(Calendar.SECOND); // detik tersisa
            String wait = String.format(Locale.US, "%02d:%02d:%02d", left / 3600, left / 60 % 60, left % 60);
            String msg = "▸ " + PrayerTimes.NAMES[next >= 0 ? next : 0] + " DALAM " + wait;
            info.setColor(AMBER);
            info.setTextAlign(Paint.Align.LEFT);
            c.drawText(msg, 0, px(96), info);

            String place = (city != null ? city : String.format(Locale.US, "%.2f, %.2f", lat, lon))
                    + (locApprox ? " · PERKIRAAN" : "");
            float avail = w - info.measureText(msg) - px(16);
            info.setColor(DIM);
            info.setTextAlign(Paint.Align.RIGHT);
            CharSequence p = TextUtils.ellipsize(place, info, avail, TextUtils.TruncateAt.END);
            c.drawText(p, 0, p.length(), w, px(96), info);
        }
    }

    final Paint solid = new Paint();

    Paint paintOf(int color) {
        solid.setColor(color);
        return solid;
    }

    /** Tanda potong tipis di empat sudut layar, gaya panel instrumen. */
    class CropMarks extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        CropMarks() {
            p.setColor(FAINT);
            p.setStrokeWidth(px(1.5f));
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            int i = px(9), l = px(14);
            float L = b.left + i, T = b.top + i, R = b.right - i, B = b.bottom - i;
            c.drawLine(L, T, L + l, T, p); c.drawLine(L, T, L, T + l, p);
            c.drawLine(R, T, R - l, T, p); c.drawLine(R, T, R, T + l, p);
            c.drawLine(L, B, L + l, B, p); c.drawLine(L, B, L, B - l, p);
            c.drawLine(R, B, R - l, B, p); c.drawLine(R, B, R, B - l, p);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
