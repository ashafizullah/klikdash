package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/**
 * KlikDash: launcher ringan untuk headunit 1280x720. Tanpa animasi, tanpa library. Di sini siklus hidup, panel jam,
 * dan kunci layar; bagian lain di {@link HomeApps} (ubin, daftar aplikasi), {@link HomeDrawer} (laci),
 * {@link HomeLocation} (lokasi, GPS, jadwal sholat), {@link HomeStatus} (suhu, RAM, penyimpanan),
 * {@link HomeUpdate} (cek update otomatis), dan {@link HomeViews} (ubin dan panel yang digambar sendiri).
 */
public class HomeActivity extends Activity {

    // Palet tema aktif, diisi Theme.apply (nilai di sini = tema AMBER). AMBER = warna aksen tema; INK = teks di atas
    // aksen; SOFT = teks sekunder yang panjang (terjemahan); ACTIVE = latar baris yang sedang dibaca; WARN = peringatan
    // (suhu CPU, kecepatan), tetap amber di tema mana pun.
    static int BG = 0xFF0B0B0A, PANEL = 0xFF151513, LINE = 0xFF2B2A26, TEXT = 0xFFECE6D8,
            DIM = 0xFF8A8478, FAINT = 0xFF4A4740, AMBER = 0xFFFFB000, INK = 0xFF0B0B0A,
            SOFT = 0xFFD9D3C5, ACTIVE = 0xFF1A1710, WARN = 0xFFFFB000;

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** Suhu CPU panas (lihat HomeStatus). */
    static int HOT = 0xFFFF5A3C;

    /** Grid home: slot + ubin "Semua"; jumlah kolom dipilih dari ukuran layar (lihat measureScreen). */
    int cols = 4, rows = 3;
    /** Jumlah slot yang tampil di home (ubin "Semua" sesudahnya); tetap sampai home dibangun ulang. */
    int shown = HomeApps.SLOTS;

    // Ukuran layar dalam dp; tata letak dihitung dari sini, bukan ditulis mati untuk 1280×720.
    float wDp, hDp;
    boolean portrait;
    int screenW, screenH; // Configuration.screenWidthDp/HeightDp saat dibangun, untuk deteksi rotasi

    static final Locale ID = new Locale("id", "ID");

    float dp, sp;
    Typeface fHeavy, fBold, fMed, fMono, fMonoBold;
    ColorFilter mono;
    SharedPreferences prefs;

    // Bagian home; dibuat di onCreate (butuh resource dan prefs).
    HomeApps apps;
    HomeLocation loc;
    HomeDrawer drawer;
    HomeStatus status;
    HomeUpdate update;

    final HomeViews.Tile[] tiles = new HomeViews.Tile[HomeApps.SLOTS + 1];

    static String two(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    View home;
    TextView clock, day, date, hijri, speed;

    /** Teks yang digambar tiap frame (ubin, panel sholat): diambil sekali saat home dibangun, bukan getString tiap onDraw. */
    String tAll, tApps, tMorning, tEvening, tAssistant, tEmpty, tTapToFill, tNextIn, tApprox;
    /**
     * setText hanya bila teksnya berubah. TextView selebar WRAP_CONTENT meminta layout ulang seluruh home di tiap
     * setText, walau teksnya sama; kecepatan dan status headunit diperbarui tiap 1–5 detik.
     */
    static void show(TextView t, String s, int color) {
        if (!TextUtils.equals(t.getText(), s)) t.setText(s);
        if (t.getCurrentTextColor() != color) t.setTextColor(color);
    }

    static void show(TextView t, String s) { show(t, s, t.getCurrentTextColor()); }

    HomeViews.Gauge gauge;
    HomeViews.PrayerView prayerView;

    final BroadcastReceiver timeRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateTime();
            if (Intent.ACTION_TIME_CHANGED.equals(i.getAction())) {
                Clock.set(c);
                loc.watchClock(); // mis. diatur ke tanggal yang jelas salah
            }
            if (!Intent.ACTION_TIME_TICK.equals(i.getAction())) PrayerAlarm.schedule(c); // jam/zona waktu diubah
        }
    };

    final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            home.removeCallbacks(apps.reload);
            home.postDelayed(apps.reload, 500);
        }
    };

    int themeBuilt;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        themeBuilt = Theme.apply(this);
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        sp = getResources().getDisplayMetrics().scaledDensity;
        // Direct boot: saat boot penyimpanan biasa (CE) masih terkunci beberapa detik, dan firmware hanya bisa
        // memilih launcher yang directBootAware. Maka pengaturan disimpan di penyimpanan DE.
        prefs = openPrefs();
        apps = new HomeApps(this);
        loc = new HomeLocation(this);
        drawer = new HomeDrawer(this);
        status = new HomeStatus(this);
        update = new HomeUpdate(this);
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

        tAll = getString(R.string.home_tile_all);
        tApps = getString(R.string.home_tile_apps);
        tMorning = getString(R.string.home_tile_morning);
        tEvening = getString(R.string.home_tile_evening);
        tAssistant = getString(R.string.home_tile_assistant);
        tEmpty = getString(R.string.home_tile_empty);
        tTapToFill = getString(R.string.home_tile_tap_to_fill);
        tNextIn = getString(R.string.home_prayer_next_in);
        tApprox = getString(R.string.home_location_approx);

        apps.loadSlots();
        loc.load();

        // Tanpa latar sendiri: latar jendela (Theme.apply) sudah BG, dua kali mengisi layar penuh hanya membebani GPU.
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        measureScreen();
        home = buildHome();
        LinearLayout drawerView = drawer.build();
        drawerView.setVisibility(View.GONE);
        root.addView(home, MATCH, MATCH);
        notch = new MediaNotch(this, fBold, fMonoBold);
        root.addView(notch, MediaNotch.params(dp)); // di bawah laci aplikasi, yang menutupinya saat dibuka
        root.addView(drawerView, MATCH, MATCH);
        setContentView(root);
        // Keyboard hanya untuk kolom cari di laci: tidak muncul sendiri, dan laci menyusut di atasnya.
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        // Daftar aplikasi sesudah frame pertama: pemuat ikon memegang kunci ResourcesManager yang juga ditunggu main
        // thread saat start (30–55 ms, atrace). Label ubin tampil dulu, ikon menyusul.
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                root.post(apps::load); // berjalan sesudah frame ini selesai
                return true;
            }
        });

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

        refreshLock();
        loc.applyExtras(getIntent());
        updateTime();
        loc.start();
        Tweaks.init(this);
        Tweaks.applyAsync();
        Tweaks.compileAsync(this, prefs);
        PrayerAlarm.allowOverlay(this);
        Clock.allowBackgroundGps(this);
        home.postDelayed(warmVoice, 20_000); // sesudah boot selesai berebut CPU
        // Baru menyala (bukan sekadar dibangun ulang, mis. ganti tema atau pembaruan): sambutan.
        if (SystemClock.elapsedRealtime() < 5 * 60_000L || welcomeAgain) {
            root.post(() -> { if (!rebuilding) Welcome.maybeShow(this, prefs); }); // home ini sudah diganti: biar yang baru
        }
        welcomeAgain = false;
        if (SystemClock.elapsedRealtime() < 5 * 60_000L) update.schedule();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) loc.start();
    }

    MediaNotch notch;
    /** Musik aplikasi lain berganti lagu atau status: hanya notch. */
    final Runnable onMedia = () -> notch.refresh();
    /** Murattal berganti ayat atau status: hanya ubin Al-Qur'an ("▶ surah : ayat") dan notch yang berubah. */
    final Runnable onQuran = () -> {
        for (HomeViews.Tile t : tiles) if (t != null && t.quran()) t.invalidate();
        notch.refresh();
    };

    /**
     * Dzikir berganti bacaan/putaran atau status: notch selalu, ubin Dzikir ("▶ PAGI/PETANG") hanya bila yang
     * ditampilkannya berubah (pemutar memberi kabar tiap putaran, tiap ±5 detik saat tasbih 100×).
     */
    String dzikirShown = "";
    final Runnable onDzikir = () -> {
        notch.refresh();
        String now = DzikirService.playing + DzikirService.period;
        if (now.equals(dzikirShown)) return;
        dzikirShown = now;
        for (HomeViews.Tile t : tiles) if (t != null) t.invalidate();
    };

    /** Semua ubin dan notch: saat home tampil lagi (dzikir pagi/petang, murattal bisa berubah selama di latar). */
    void refreshTiles() {
        invalidateTiles();
        notch.refresh();
    }

    void invalidateTiles() {
        for (HomeViews.Tile t : tiles) if (t != null) t.invalidate();
    }

    @Override protected void onResume() {
        super.onResume();
        if (Theme.stale(this, themeBuilt)) return;
        reloadPrefs();
        if (HomeApps.tileCount(prefs) != shown + 1) { recreate(); return; } // jumlah ubin diganti di Pengaturan
        refreshLock(); // PIN bisa diatur atau dihapus di Pengaturan
        keepOrientation();
        QuranService.listeners.add(onQuran);
        DzikirService.listeners.add(onDzikir);
        MediaListener.listeners.add(onMedia);
        MediaListener.start(this);
        PrayerAlarm.home = new java.lang.ref.WeakReference<>(this);
        PrayerAlarm.schedule(this);
        apps.untrim();
        refreshTiles();
        updateTime();
        prayerView.startTicking();
        status.start();
        refreshDefaultHome();
        loc.startSpeed();
        resumed = true;
        update.resume();
    }

    // ---------------------------------------------------------------- tampilan utama (launcher default)

    /** Firmware NWD memilih launcher lewat property ini dan mengabaikan pilihan home Android. */
    HomeViews.Button makeDefault;

    // ---------------------------------------------------------------- kunci layar (ScreenLock)

    ScreenLock.Icon lockIcon;
    TextView gear;
    boolean locked;
    /** Gestur yang dimulai di luar gembok saat terkunci: seluruhnya diabaikan sampai jari diangkat. */
    boolean swallow;
    long lockHintAt;

    void refreshLock() {
        locked = ScreenLock.locked(prefs);
        lockIcon.setVisibility(ScreenLock.hasPin(prefs) ? View.VISIBLE : View.GONE);
        lockIcon.set(locked);
        gear.setVisibility(locked ? View.INVISIBLE : View.VISIBLE);
    }

    void toggleLock() {
        if (!locked) {
            ScreenLock.setLocked(prefs, true);
            if (drawer.isOpen()) drawer.close();
            refreshLock();
            Notice.info(this, getString(R.string.lock_locked));
            return;
        }
        ScreenLock.verify(this, prefs, () -> {
            ScreenLock.setLocked(prefs, false);
            refreshLock();
            Notice.ok(this, getString(R.string.lock_unlocked));
        }, () -> {
            refreshLock();
            Notice.ok(this, getString(R.string.lock_reset_done));
        });
    }

    /** Terkunci: hanya gembok (dan sambutan, yang menutup sendiri) yang menerima sentuhan. */
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (!locked) return super.dispatchTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int[] at = new int[2];
            lockIcon.getLocationOnScreen(at);
            boolean onLock = e.getRawX() >= at[0] && e.getRawX() < at[0] + lockIcon.getWidth()
                    && e.getRawY() >= at[1] && e.getRawY() < at[1] + lockIcon.getHeight();
            boolean welcome = findViewById(android.R.id.content).findViewWithTag(Welcome.class) != null;
            swallow = !onLock && !welcome;
            if (swallow && SystemClock.uptimeMillis() - lockHintAt > 4000) {
                lockHintAt = SystemClock.uptimeMillis();
                Notice.info(this, getString(R.string.lock_locked));
            }
        }
        if (swallow) return true;
        return super.dispatchTouchEvent(e);
    }

    /** Tombol arah/OK (remote, keyboard) juga diabaikan saat terkunci; tombol media tetap jalan. */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (locked) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_TAB: case KeyEvent.KEYCODE_SEARCH: case KeyEvent.KEYCODE_MENU:
                    return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    void openSettings() {
        userLaunch = true;
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /** Di thread latar: isDefault menjalankan getprop (proses baru), terlalu lambat untuk onResume di headunit. */
    /** Hasil terakhir {@link HomeSwitch#isDefault} di proses ini; null = belum dicek. */
    static volatile Boolean knownDefault;
    /** buildHome menyisakan tempat untuk tombol JADIKAN TAMPILAN UTAMA. */
    boolean reservedDefault;
    /** Sambutan sedang tampil saat home dibangun ulang (lihat refreshDefaultHome): tampilkan lagi di home baru. */
    static boolean welcomeAgain;
    /** recreate() sudah diminta: sambutan yang dijadwalkan home ini dilewati, home yang baru menampilkannya. */
    boolean rebuilding;

    void refreshDefaultHome() {
        new Thread(() -> {
            boolean def = HomeSwitch.isDefault(this);
            knownDefault = def;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                // Tombolnya muncul padahal tempatnya belum disisakan: bangun ulang, supaya jadwal sholat dan penggaris
                // 24 jam tidak terdesak keluar layar. Sebaliknya (tombol hilang) cukup disembunyikan.
                if (!def && !reservedDefault) {
                    rebuilding = true;
                    if (getWindow().getDecorView().findViewWithTag(Welcome.class) != null) {
                        Welcome.again();
                        welcomeAgain = true;
                    }
                    recreate();
                    return;
                }
                makeDefault.setVisibility(def ? View.GONE : View.VISIBLE);
            });
        }, "home-default").start();
    }

    /** Di antara onResume dan onPause. */
    boolean resumed;

    @Override protected void onPause() {
        resumed = false;
        loc.stopSpeed();
        prayerView.stopTicking();
        status.stop();
        QuranService.listeners.remove(onQuran);
        DzikirService.listeners.remove(onDzikir);
        MediaListener.listeners.remove(onMedia);
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
            refreshTiles();
            updateTime();
            Welcome.maybeShow(HomeActivity.this, prefs);
            home.removeCallbacks(warmVoice);
            home.postDelayed(warmVoice, 10_000);
            update.schedule();
        }
    };

    /**
     * TTS Android disiapkan di latar bila TANYA menjawab dengan suara Android: start dingin Google TTS ±18 detik
     * (lihat AiVoice.warm), jadi pertanyaan pertama setelah headunit menyala langsung dijawab. ±70 MB RAM.
     */
    final Runnable warmVoice = () -> {
        if (AiVoice.answersWithAndroid(this)) AiVoice.warm(this);
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
        loc.applyExtras(intent);
        if (drawer.isOpen()) drawer.close();
    }

    @Override public void onBackPressed() {
        if (drawer.isOpen()) drawer.close();
    }

    /** RAM sistem menipis saat home di latar: lepas ikon laci (lihat HomeApps.trim). */
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND) apps.trim();
    }

    @Override protected void onDestroy() {
        update.cancel();
        loc.stop();
        unregisterReceiver(timeRx);
        unregisterReceiver(pkgRx);
        unregisterReceiver(wakeRx);
        try { unregisterReceiver(unlockRx); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- prefs dan rotasi

    private static volatile Integer nwdRotation;

    /**
     * Home kembali ke rotasi bawaan firmware. Aplikasi seperti Maps bisa memutar layar ke potret, dan headunit tanpa
     * sensor putar membiarkannya begitu setelah aplikasi itu ditutup. Firmware NWD mengabaikan setRequestedOrientation
     * dari home, jadi rotasinya dikembalikan lewat pengaturan sistem (ADB lokal, seperti Tweaks). Rotasi bawaan dicatat
     * saat headunit baru menyala; NWD: ro.boot.nwd.rotation. Belum diketahui: dibiarkan (mis. headunit tegak).
     */
    void keepOrientation() {
        // Di thread latar: getprop pertama menjalankan 2–3 proses, ±50–70 ms di onResume saat cold start (atrace).
        SharedPreferences p = prefs;
        new Thread(() -> {
            if (nwdRotation == null) { // getprop menjalankan proses: sekali saja
                int r = -1;
                if (HomeSwitch.isNwd()) {
                    try { r = Integer.parseInt(HomeSwitch.getprop("ro.boot.nwd.rotation")); } catch (NumberFormatException ignored) { }
                }
                nwdRotation = r;
            }
            int want = p.getInt("homeRotation", nwdRotation);
            if (want < 0 || want > 3 || getWindowManager().getDefaultDisplay().getRotation() == want) return;
            boolean free = android.provider.Settings.System.getInt(getContentResolver(),
                    android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
            // Kunci sebentar ke rotasi itu, lalu kembalikan putar otomatis bila tadinya aktif (rotasi tetap di sana).
            Tweaks.shellAsync("settings put system accelerometer_rotation 0; settings put system user_rotation " + want
                    + (free ? "; settings put system accelerometer_rotation 1" : ""));
        }, "orientation").start();
    }

    /**
     * Prefs di penyimpanan DE; pindahkan sekali dari CE (versi lama) begitu CE sudah terbuka.
     * MODE_MULTI_PROCESS: baca ulang dari disk bila filenya berubah. Headunit dengan fast boot memulihkan isi RAM
     * lama saat ACC on; tanpa ini susunan ubin lama di memori tampil lagi, lalu tertulis menimpa yang tersimpan.
     */
    @SuppressWarnings("deprecation")
    SharedPreferences openPrefs() {
        Context de = createDeviceProtectedStorageContext();
        // Pindah sekali per proses: moveSharedPreferencesFrom membaca isi folder di disk tiap dipanggil.
        if (!prefsMoved && getSystemService(UserManager.class).isUserUnlocked()) {
            de.moveSharedPreferencesFrom(this, "klikdash");
            prefsMoved = true;
        }
        return de.getSharedPreferences("klikdash", MODE_MULTI_PROCESS);
    }

    private static boolean prefsMoved;

    /** Ambil ulang ubin dan lokasi dari disk (lihat openPrefs), juga perubahan dari Pengaturan. */
    void reloadPrefs() {
        prefs = openPrefs();
        apps.loadSlots();
        loc.reload();
    }

    /** Setelah CE terbuka: aplikasi non-directBoot (mis. Maps, Al-Qur'an) baru terlihat oleh PackageManager. */
    final BroadcastReceiver unlockRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            reloadPrefs();
            apps.load();
            updateTime();
        }
    };

    int dzikirState = -1;

    void updateTime() {
        if (clock == null) return;
        Calendar now = Calendar.getInstance(); // zona waktu saat ini, juga sesudah diganti
        loc.computePrayer(now);
        if (prayerView != null) prayerView.invalidate();
        show(clock, two(now.get(Calendar.HOUR_OF_DAY)) + ":" + two(now.get(Calendar.MINUTE)));
        show(day, Lang.days[now.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY]);
        show(date, getString(R.string.home_date, now.get(Calendar.DAY_OF_MONTH), Lang.months[now.get(Calendar.MONTH)],
                now.get(Calendar.YEAR)));
        show(hijri, Hijri.today(now.getTimeInMillis(), prefs.getInt("hijriOffset", 0)));
        gauge.invalidate();
        int dz = loc.dzikirPagi() ? 1 : 0; // ubin Dzikir berganti PAGI → PETANG saat Ashar, walau home terus tampil
        if (dz != dzikirState) {
            dzikirState = dz;
            invalidateTiles();
        }
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
        int n = HomeApps.tileCount(prefs);
        shown = n - 1;
        // Lebih sedikit ubin (9, 6, 4): panel jam lebih besar. Jam, kecepatan, tanggal, dan jadwal sholat membesar
        // sejauh tinggi layar masih cukup bersama penggaris 24 jam; batas itu sudah tercapai di 9 ubin, jadi 6 dan 4
        // memakai panel jam yang sama dan lebar sisanya untuk ubin yang lebih besar.
        int big = n == 12 ? 0 : 1;
        float k = 1 + 0.06f * big; // skala tulisan panel jam selain jam dan kecepatan
        float panelH = portrait ? hDp * (0.42f + 0.06f * big) : hDp;
        boolean shortPanel = panelH < 560; // tidak cukup tinggi untuk penggaris 24 jam

        // Jam sebesar mungkin: jam dan kecepatan 3 digit (ukurannya 0,43 × jam) muat berdampingan di lebar panel, dan
        // sebanding tinggi layar. Lebar diukur dengan digit terlebar (angka font ini tidak sama lebar) pada ukuran sp.
        Paint pm = new Paint();
        pm.setTypeface(fHeavy);
        pm.setTextSize(100 * sp);
        float digit = 0;
        for (char d = '0'; d <= '9'; d++) digit = Math.max(digit, pm.measureText(String.valueOf(d)));
        float per100 = (4 * digit + pm.measureText(":") + 3 * digit * SPEED_RATIO) / dp;
        // Jam tidak boleh mendesak isi panel lain keluar layar: tinggi baris jam (tanpa padding font) dibatasi sisa
        // tinggi panel sesudah baris atas, tanggal, jadwal sholat, dan penggaris.
        Paint.FontMetrics fmc = pm.getFontMetrics();
        float clockLine = (fmc.descent - fmc.ascent) / 100 / dp; // tinggi baris jam, dp per sp
        // Tema kilap: panel jam jadi kartu kaca dengan jarak dalam `inset` di tiap sisi; jarak ke ubin menyempit.
        // `edge`: jarak tepi layar ke panel jam dan ubin.
        int inset = Theme.gloss() ? 16 : 0, sep = Theme.gloss() ? 13 : 57, edge = 24;
        float spDp = sp / dp, availH = (portrait ? panelH - 24 : screenH - 2 * edge) - 2 * inset;
        // Tombol JADIKAN TAMPILAN UTAMA (48 + jarak 14) ikut dihitung bila tampil; lihat refreshDefaultHome.
        reservedDefault = knownDefault != null && !knownDefault;
        float fixed = 48 + 14 * spDp + 42 + 1.3f * (18 + (panelH < 560 ? 28 : 36) + 13) * k * spDp + 14 + 104 * k
                + (shortPanel ? 0 : 74) + (reservedDefault ? 62 : 0);
        float clockFit = (availH - fixed - 8) / clockLine;
        // Layar pendek (mis. 1280×720 dengan density 180 dan huruf sistem besar): jam sudah di ukuran terkecil (56) dan
        // isi panel tetap terlalu tinggi, sehingga di tema kilap bingkai kartu memotong penggaris dan hitung mundur
        // sholat. Rapatkan tepi layar dan bingkai kartu supaya ruangnya setidaknya sama dengan tema datar; di portrait
        // panel jam meminjam jarak garis pemisah (20 di tema datar, 6 di tema kilap) dari ubin.
        int grow = 0;
        if (Theme.gloss() && clockFit < 56) {
            int was = inset;
            inset = 10;
            if (portrait) grow = 2 * (20 - 6); else edge = 12;
            availH += 2 * (was - inset) + (portrait ? grow : 2 * (24 - edge));
            clockFit = (availH - fixed - 8) / clockLine;
        }

        // Landscape: panel jam di kiri, 34% lebar; dengan ubin lebih sedikit selebar yang dibutuhkan jam (sampai 55%).
        // Portrait: panel jam di atas, ubin di bawah.
        float leftDp = portrait ? wDp - 48 : big == 0 ? Math.max(260, Math.min(480, wDp * 0.34f))
                : Math.max(260, Math.min(wDp * 0.55f,
                        Math.max(wDp * 0.34f, Math.min(150 + 40 * big, clockFit) * per100 / 100 + 64 + 2 * inset)));
        float clockSp = Math.min(150 + 40 * big, (leftDp - 24 - 2 * inset) / per100 * 100);
        if (big == 0) clockSp = Math.min(clockSp, panelH * 0.21f);
        clockSp = Math.max(56, Math.min(clockSp, clockFit));

        float gridW = portrait ? wDp - 48 : wDp - 2 * edge - leftDp - sep;
        cols = n == 4 ? 2 : n == 12 && !portrait && gridW >= 600 ? 4 : 3;
        if (n == 6) { // 3×2 atau 2×3: mana yang ubinnya paling mendekati persegi
            float gridH = portrait ? screenH - panelH - grow - (Theme.gloss() ? 13 : 41) : screenH - 2 * edge;
            double c2 = Math.log((gridW - 12) / 2f / ((gridH - 24) / 3f)), c3 = Math.log((gridW - 24) / 3f / ((gridH - 12) / 2f));
            cols = Math.abs(c2) < Math.abs(c3) ? 2 : 3;
        }
        rows = (n + cols - 1) / cols;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int pad = px(edge);
        row.setPadding(pad, pad, pad, pad);
        if (!Theme.gloss()) row.setBackground(new HomeViews.CropMarks(this));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        if (inset > 0) {
            left.setBackground(Theme.card());
            left.setPadding(px(inset), px(inset), px(inset), px(inset));
        }

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        // Model dari sistem (mis. "K2401/K2401P/k2401M" → K2401), bukan ditulis mati.
        String model = android.os.Build.MODEL.split("[/ ]")[0].toUpperCase(Locale.US);
        TextView tag = text("KLIKDASH  ·  " + model, fMono, 12, DIM);
        tag.setLetterSpacing(0.25f);
        top.addView(tag, new LinearLayout.LayoutParams(0, WRAP, 1f));
        // Pengaturan KlikDash (tampilan, Hemat Daya, asisten AI). Kotak sentuh 48dp walau ikonnya kecil.
        // Kunci layar (PIN di Pengaturan): gembok muncul hanya bila PIN sudah diatur, lihat refreshLock.
        lockIcon = new ScreenLock.Icon(this);
        lockIcon.setOnClickListener(v -> toggleLock());
        top.addView(lockIcon, new LinearLayout.LayoutParams(px(48), px(48)));
        gear = text("⚙", fMonoBold, 18, DIM);
        gear.setGravity(Gravity.CENTER);
        gear.setContentDescription(getString(R.string.app_settings));
        gear.setOnClickListener(v -> openSettings());
        top.addView(gear, new LinearLayout.LayoutParams(px(48), px(48)));
        top.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(top);

        // Kondisi headunit: suhu CPU, RAM, dan penyimpanan terpakai (diperbarui tiap 5 detik, lihat HomeStatus).
        status.diskLabel = getString(leftDp < 380 ? R.string.home_disk_short : R.string.home_disk);
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        status.temp = text("", fMonoBold, 11, DIM);
        status.ram = text("", fMonoBold, 11, DIM);
        status.disk = text("", fMonoBold, 11, DIM);
        for (TextView t : new TextView[]{status.temp, status.ram, status.disk}) {
            t.setLetterSpacing(0.15f);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(WRAP, WRAP);
            if (t != status.temp) sl.leftMargin = px(18);
            stats.addView(t, sl);
        }
        left.addView(stats);

        // Muncul hanya bila KlikDash belum jadi tampilan utama (lihat refreshDefaultHome).
        makeDefault = new HomeViews.Button(this, getString(R.string.home_make_default), true);
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
        TextView unit = text(getString(R.string.home_speed_unit), fMono, 10 * k, DIM);
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

        day = text("", fMonoBold, 18 * k, AMBER);
        day.setLetterSpacing(0.3f);
        left.addView(day);

        date = text("", fMed, (panelH < 560 ? 28 : 36) * k, TEXT);
        date.setLetterSpacing(0.03f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(WRAP, WRAP);
        dl.topMargin = px(6);
        left.addView(date, dl);

        hijri = text("", fMono, 13 * k, DIM);
        hijri.setLetterSpacing(0.2f);
        hijri.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(WRAP, WRAP);
        hl.topMargin = px(8);
        left.addView(hijri, hl);

        left.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        prayerView = new HomeViews.PrayerView(this, k);
        left.addView(prayerView, new LinearLayout.LayoutParams(MATCH, px(104 * k)));
        gauge = new HomeViews.Gauge(this);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(MATCH, px(56));
        gl.topMargin = px(18);
        left.addView(gauge, gl);
        if (shortPanel) gauge.setVisibility(View.GONE);

        row.addView(left, portrait ? new LinearLayout.LayoutParams(MATCH, px(panelH - 24 + grow))
                : new LinearLayout.LayoutParams(px(leftDp), MATCH));

        View div = new View(this);
        div.setBackgroundColor(LINE);
        LinearLayout.LayoutParams vl = portrait ? new LinearLayout.LayoutParams(MATCH, px(1))
                : new LinearLayout.LayoutParams(px(1), MATCH);
        int half = (sep - 1) / 2; // garis 1dp di tengah jarak panel jam dan ubin
        if (portrait) { vl.topMargin = px(Theme.gloss() ? 6 : 20); vl.bottomMargin = vl.topMargin; }
        else { vl.leftMargin = px(half); vl.rightMargin = px(sep - 1 - half); }
        if (Theme.gloss()) div.setVisibility(View.INVISIBLE); // tepi kartu kaca sudah memisahkan
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
                if (idx == shown) idx = HomeApps.SLOTS; // ubin "Semua" selalu terakhir
                HomeViews.Tile t = new HomeViews.Tile(this, idx);
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

    final Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    final Rect iconRect = new Rect();

    void drawIcon(Canvas c, Bitmap b, int left, int top, int size, boolean color) {
        iconRect.set(left, top, left + size, top + size);
        iconPaint.setColorFilter(color ? null : mono);
        iconPaint.setAlpha(color ? 255 : 225);
        c.drawBitmap(b, null, iconRect, iconPaint);
    }

    void drawIcon(Canvas c, Drawable d, int left, int top, int size, boolean color) {
        d.setBounds(left, top, left + size, top + size);
        d.setColorFilter(color ? null : mono);
        d.setAlpha(color ? 255 : 225);
        d.draw(c);
    }

}
