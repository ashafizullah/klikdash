package com.ashafizullah.klikdash;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Isi ubin home (slot tersimpan di prefs) dan daftar aplikasi untuk ubin dan laci. */
final class HomeApps {
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
     * Teks tampilan label bawaan (indeks sama dengan DEFAULTS; 0 = sama di semua bahasa). Prefs l0..l10 tetap menyimpan
     * label Indonesia sebagai kunci (ikut berpindah saat ubin ditukar), dan baru diterjemahkan saat digambar.
     */
    static final int[] DEFAULT_TEXT = {0, 0, 0, 0, R.string.home_tile_phone, 0, R.string.home_tile_dzikir,
            R.string.home_tile_quran, R.string.home_tile_camera, R.string.home_tile_system, R.string.home_tile_ask};
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

    static class App {
        ComponentName cn;
        String label, upper; // upper: label huruf besar, untuk ubin dan laci
        /** Ikon asli untuk ubin; null bila aplikasi tidak di ubin (dimuat lagi saat perlu, lihat iconOf). */
        Drawable icon;
        /** Ikon laci, sudah digambar seukuran sel (lihat query); null setelah onTrimMemory. */
        Bitmap bmp;
    }

    final HomeActivity h;
    final ComponentName[] slotCn = new ComponentName[SLOTS];
    final String[] slotLabel = new String[SLOTS];
    final List<App> all = new ArrayList<>();
    final Map<String, App> byKey = new HashMap<>();
    final Map<String, App> byPkg = new HashMap<>();
    /** Label tersimpan (kunci Indonesia, lihat DEFAULT_TEXT) → teks dalam bahasa antarmuka. */
    final Map<String, String> defaultText = new HashMap<>();
    /** false sampai daftar aplikasi pertama selesai dimuat: ubin belum boleh dianggap kosong. */
    boolean ready;
    /** Pemuatan terakhir; hasil pemuatan yang lebih lama dibuang. */
    int gen;
    /** Ikon laci dilepas onTrimMemory selama di latar; dimuat lagi di onResume. */
    boolean trimmed;

    HomeApps(HomeActivity h) {
        this.h = h;
        for (int i = 0; i < SLOTS; i++) if (DEFAULT_TEXT[i] != 0) defaultText.put(DEFAULTS[i][1], h.getString(DEFAULT_TEXT[i]));
    }

    void loadSlots() {
        SharedPreferences prefs = h.prefs;
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

    /** Label tersimpan untuk ditampilkan: label bawaan diterjemahkan (lihat DEFAULT_TEXT), selain itu apa adanya. */
    String labelText(String label) {
        String t = defaultText.get(label);
        return t != null ? t : label;
    }

    /** {@code label} null: pakai nama aplikasi. */
    void saveSlot(int i, ComponentName cn, String label) {
        slotCn[i] = cn;
        slotLabel[i] = label;
        SharedPreferences.Editor e = h.prefs.edit().putString("s" + i, cn != null ? cn.flattenToString() : "");
        if (label != null) e.putString("l" + i, label); else e.remove("l" + i);
        e.apply();
    }

    void swapSlots(int a, int b) {
        ComponentName ca = slotCn[a];
        String la = slotLabel[a];
        saveSlot(a, slotCn[b], slotLabel[b]);
        saveSlot(b, ca, la);
        h.tiles[a].invalidate();
        h.tiles[b].invalidate();
    }

    /**
     * Daftar aplikasi dimuat di thread latar: label dan ikon semua aplikasi terlalu lambat untuk main thread saat
     * headunit baru menyala. Perubahan paket datang beruntun (PACKAGE_CHANGED), jadi ditunda sebentar dan digabung.
     */
    void load() {
        final int g = ++gen;
        final java.util.Set<String> keep = slotPackages();
        final int iconPx = h.px(64); // ikon laci terbesar, lihat HomeDrawer.Cell
        new Thread(() -> {
            List<App> list = query(keep, iconPx);
            h.runOnUiThread(() -> {
                if (g != gen || h.isDestroyed()) return;
                all.clear();
                byKey.clear();
                byPkg.clear();
                for (App a : list) {
                    all.add(a);
                    byKey.put(a.cn.flattenToString(), a);
                    if (!byPkg.containsKey(a.cn.getPackageName())) byPkg.put(a.cn.getPackageName(), a);
                }
                ready = true;
                h.drawer.filter();
                h.invalidateTiles();
            });
        }, "apps").start();
    }

    final Runnable reload = this::load;

    /** Paket aplikasi di ubin home: ikon aslinya disimpan, yang lain cukup bitmap laci. */
    java.util.Set<String> slotPackages() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (ComponentName cn : slotCn) if (cn != null) s.add(cn.getPackageName());
        return s;
    }

    /**
     * Di thread latar. Ikon laci digambar sekali ke bitmap seukuran sel: ikon asli sering beresolusi besar atau ikon
     * adaptif (dua lapis + topeng), mahal digambar ulang tiap sel muncul saat laci digulir, dan boros RAM.
     */
    List<App> query(java.util.Set<String> keep, int iconPx) {
        List<App> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        PackageManager pm = h.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            if (HomeActivity.class.getName().equals(r.activityInfo.name)) continue;
            App a = new App();
            a.cn = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            int own = ownLabel(r.activityInfo.name);
            a.label = own != 0 ? h.getString(own) : String.valueOf(r.loadLabel(pm)).trim();
            if (!seen.add(dupKey(a.cn, a.label))) continue;
            a.upper = a.label.toUpperCase(Lang.locale());
            Drawable d = DzikirActivity.class.getName().equals(r.activityInfo.name)
                    ? h.getDrawable(R.drawable.ic_dzikir_tile).mutate() : r.loadIcon(pm).mutate();
            a.bmp = render(d, iconPx);
            if (keep.contains(a.cn.getPackageName())) a.icon = d;
            list.add(a);
        }
        Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return list;
    }

    /**
     * Firmware headunit (mis. FYT/UIS) sering memberi satu paket beberapa aktivitas LAUNCHER berlabel sama (kamera
     * belakang/depan/DVR, radio); launcher bawaannya menyaringnya. Cukup tampilkan yang pertama, sama dengan
     * getLaunchIntentForPackage.
     */
    static String dupKey(ComponentName cn, String label) {
        return cn.getPackageName() + '\n' + label.toLowerCase(Locale.ROOT);
    }

    /**
     * Aplikasi yang disembunyikan pengguna dari Semua Aplikasi (komponen, flattenToString), mis. sisa entri ganda
     * yang tidak tertangkap {@link #dupKey}. Ubin home tidak terpengaruh. Dimunculkan lagi di Pengaturan.
     */
    static java.util.Set<String> hidden(SharedPreferences p) {
        return new java.util.HashSet<>(p.getStringSet("hiddenApps", Collections.<String>emptySet()));
    }

    static void setHidden(SharedPreferences p, String key, boolean hide) {
        java.util.Set<String> s = hidden(p); // salinan: set dari getStringSet tidak boleh diubah
        if (hide ? s.add(key) : s.remove(key)) p.edit().putStringSet("hiddenApps", s).apply();
    }

    /**
     * Label aktivitas KlikDash sendiri dari resource: loadLabel mengikuti bahasa sistem, bukan bahasa yang dipilih di
     * Pengaturan. 0 untuk aplikasi lain.
     */
    static int ownLabel(String activity) {
        if (QuranActivity.class.getName().equals(activity)) return R.string.app_quran;
        if (DzikirActivity.class.getName().equals(activity)) return R.string.app_dzikir;
        if (AskActivity.class.getName().equals(activity)) return R.string.app_ask;
        if (BeamActivity.class.getName().equals(activity)) return R.string.app_beam;
        if (SettingsActivity.class.getName().equals(activity)) return R.string.app_settings;
        return 0;
    }

    static Bitmap render(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(b));
        return b;
    }

    /** Ikon asli untuk ubin; dimuat bila belum ada (aplikasi baru dipasang di ubin, atau setelah onTrimMemory). */
    Drawable iconOf(App a) {
        if (a.icon == null) {
            try {
                a.icon = DzikirActivity.class.getName().equals(a.cn.getClassName())
                        ? h.getDrawable(R.drawable.ic_dzikir_tile).mutate() : h.getPackageManager().getActivityIcon(a.cn).mutate();
            } catch (PackageManager.NameNotFoundException e) {
                a.icon = new ColorDrawable(0);
            }
        }
        return a.icon;
    }

    /**
     * RAM sistem menipis saat home di latar (mis. Maps/Waze di depan): lepas ikon laci, dibuat ulang di onResume.
     * Launcher yang lebih kecil lebih jarang dibunuh, jadi kembali ke home tidak perlu cold start. Ikon ubin tetap.
     */
    void trim() {
        if (all.isEmpty()) return;
        java.util.Set<String> keep = slotPackages();
        for (App a : all) {
            a.bmp = null;
            if (!keep.contains(a.cn.getPackageName())) a.icon = null;
        }
        trimmed = true;
    }

    /** Home tampil lagi: muat ulang ikon laci yang dilepas {@link #trim}. */
    void untrim() {
        if (!trimmed) return;
        trimmed = false;
        load();
    }

    App appFor(int slot) {
        ComponentName cn = slotCn[slot];
        if (cn == null) return null;
        App a = byKey.get(cn.flattenToString());
        return a != null ? a : byPkg.get(cn.getPackageName());
    }

    void launch(ComponentName cn) {
        h.userLaunch = true;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            h.startActivity(i);
        } catch (Exception e) {
            Intent li = h.getPackageManager().getLaunchIntentForPackage(cn.getPackageName());
            try {
                if (li != null) { h.startActivity(li); return; }
            } catch (Exception ignored) { }
            Notice.fail(h, h.getString(R.string.home_cannot_open));
        }
    }
}
