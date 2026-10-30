package com.ashafizullah.klikdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.BG;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.FAINT;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Pengaturan KlikDash dalam tab: Launcher (dikelompokkan: tampilan, sholat, screensaver, home, sistem), Hemat Daya,
 * Asisten AI, KlikBeam, Akun (riwayat perjalanan, {@link Account}), dan Pembaruan.
 * Tab di kiri pada layar mendatar, di atas pada layar tegak.
 */
public class SettingsActivity extends Activity {

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final String EXTRA_TAB = "tab";
    static final int TAB_LAUNCHER = 0, TAB_POWER = 1, TAB_AI = 2, TAB_BEAM = 3, TAB_ACCOUNT = 4, TAB_UPDATE = 5;
    static final int TAB_COUNT = 6;

    float dp, sp, wDp, hDp, railDp;
    boolean portrait, narrow;
    int screenW, screenH;
    Typeface fHeavy, fBold, fMono, fMonoBold, fSerif;
    SharedPreferences prefs;

    FrameLayout content;
    final Tab[] tabs = new Tab[TAB_COUNT];
    int current = -1;
    PowerPanel power;
    AiPanel ai;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override protected void onCreate(Bundle b) {
        Theme.apply(this);
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        sp = getResources().getDisplayMetrics().scaledDensity;
        fHeavy = font("display.ttf", "'opsz' 72, 'wght' 800");
        fBold = font("display.ttf", "'opsz' 36, 'wght' 700");
        fMono = font("mono.ttf", "'wdth' 100, 'wght' 400");
        fMonoBold = font("mono.ttf", "'wdth' 100, 'wght' 700");
        fSerif = font("serif.ttf", null);
        // Sama dengan HomeActivity: pengaturan launcher di penyimpanan DE (bisa dibaca saat direct boot).
        prefs = Prefs.of(this);
        Tweaks.init(this);
        if (b != null) openGroup = b.getInt("group", -1); // tetap terbuka setelah ganti tema/bahasa
        build(b != null ? b.getInt(EXTRA_TAB, 0) : getIntent().getIntExtra(EXTRA_TAB, TAB_LAUNCHER));
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_TAB, Math.max(current, 0));
        out.putInt("group", openGroup);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (c.screenWidthDp != screenW || c.screenHeightDp != screenH) build(Math.max(current, 0));
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.hasExtra(EXTRA_TAB)) show(i.getIntExtra(EXTRA_TAB, TAB_LAUNCHER));
    }

    @Override protected void onResume() {
        super.onResume();
        prefs = Prefs.of(this); // baca ulang bila file berubah di disk (lihat Prefs)
        // Kembali dari pengaturan sistem atau Play Store: status tampilan utama atau mesin suara mungkin berubah.
        // Tab Launcher tanpa melompat ke atas: baris screensaver di tengah, kembali dari pemilih foto.
        if (current == TAB_LAUNCHER) rebuildKeepScroll();
        else if (current == TAB_AI) show(current, true);
        else if (current == TAB_ACCOUNT) {
            show(current, true);
            Account.refresh(this);
        }
        Account.listeners.add(accountRx);
    }

    @Override protected void onPause() {
        Account.listeners.remove(accountRx);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (ai != null && ai.voice != null) ai.voice.release();
        super.onDestroy();
    }

    void build(int tab) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        wDp = dm.widthPixels / dp;
        hDp = dm.heightPixels / dp;
        portrait = hDp > wDp;
        screenW = getResources().getConfiguration().screenWidthDp;
        screenH = getResources().getConfiguration().screenHeightDp;
        railDp = wDp < 1000 ? 184 : 236;
        narrow = (portrait ? wDp : wDp - railDp - 105) < 640; // lebar area isi

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Theme.fill(root);
        root.setFitsSystemWindows(true);
        root.setPadding(px(24), px(24), px(24), 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(getString(R.string.settings_title), fHeavy, wDp < 700 ? 30 : 40, TEXT));
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(key(wDp < 900 ? "✕" : getString(R.string.settings_close), false, this::finish), new LinearLayout.LayoutParams(WRAP, px(56)));
        root.addView(head, new LinearLayout.LayoutParams(MATCH, px(64)));
        root.addView(rule(), ruleParams(true, 16, 0));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(portrait ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        String[] names = getResources().getStringArray(R.array.settings_tab_names),
                captions = getResources().getStringArray(R.array.settings_tab_captions);
        captions[TAB_UPDATE] = String.format(captions[TAB_UPDATE], Updater.installedVersion(this));
        // Headunit 1024×600: tab 88 dp tidak muat lima-limanya di bawah judul, jadi dipendekkan.
        int tabDp = Math.max(64, Math.min(88, Math.round((hDp - 140) / TAB_COUNT)));
        for (int i = 0; i < TAB_COUNT; i++) {
            final int idx = i;
            tabs[i] = new Tab(names[i], captions[i], () -> show(idx));
            rail.addView(tabs[i], portrait ? new LinearLayout.LayoutParams(0, px(76), 1f)
                    : new LinearLayout.LayoutParams(MATCH, px(tabDp)));
        }
        if (portrait) {
            body.addView(rail, new LinearLayout.LayoutParams(MATCH, WRAP));
            body.addView(rule(), ruleParams(true, 0, 0));
        } else {
            body.addView(rail, new LinearLayout.LayoutParams(px(railDp), MATCH));
            body.addView(rule(), ruleParams(false, 0, 28));
        }
        content = new FrameLayout(this);
        body.addView(content, portrait ? new LinearLayout.LayoutParams(MATCH, 0, 1f)
                : new LinearLayout.LayoutParams(0, MATCH, 1f));
        root.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setContentView(root);
        current = -1;
        show(tab);
    }

    void show(int tab) { show(tab, false); }

    void show(int tab, boolean force) {
        tab = Math.max(0, Math.min(TAB_COUNT - 1, tab));
        if (tab == current && !force) return;
        if (tab == TAB_ACCOUNT && tab != current) Account.refresh(this);
        current = tab;
        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(i == tab);
        content.removeAllViews();
        power = null;
        if (ai != null && ai.voice != null) ai.voice.release();
        ai = null;
        View v;
        if (tab == TAB_POWER) {
            power = new PowerPanel(this);
            v = power.view;
        } else if (tab == TAB_AI) {
            ai = new AiPanel(this);
            v = ai.view;
        } else if (tab == TAB_BEAM) {
            v = beamPanel();
        } else if (tab == TAB_ACCOUNT) {
            v = accountPanel();
        } else if (tab == TAB_UPDATE) {
            v = updatePanel();
        } else {
            v = launcherPanel();
        }
        content.addView(v, MATCH, MATCH);
    }

    // ---------------------------------------------------------------- tab Launcher

    /** Kelompok accordion tab Launcher; hanya satu yang terbuka (openGroup, -1 = semua tertutup). */
    static final int GROUP_LOOK = 0, GROUP_PRAYER = 1, GROUP_SAVER = 2, GROUP_HOME = 3, GROUP_SYSTEM = 4;
    int openGroup = -1;
    final View[] groupViews = new View[5];

    View launcherPanel() {
        LinearLayout list = column();

        boolean def = HomeSwitch.isDefault(this);
        View home = row(getString(R.string.settings_home_title),
                getString(def ? R.string.settings_home_on : R.string.settings_home_off),
                def ? key(getString(R.string.settings_home_restore), false, () -> HomeSwitch.restore(this, prefs))
                        : key(getString(R.string.settings_home_make), true, () -> HomeSwitch.makeDefault(this, prefs,
                        () -> rebuildKeepScroll())));

        // Belum jadi tampilan utama: tombolnya paling atas, di luar kelompok.
        if (!def) list.addView(home);

        List<View> look = new ArrayList<>();
        Key[] langs = new Key[Lang.CODES.length];
        for (int i = 0; i < langs.length; i++) {
            final String code = Lang.CODES[i];
            langs[i] = key(getString(Lang.EN.equals(code) ? R.string.lang_en : R.string.lang_id), code.equals(Lang.code), () -> {
                if (code.equals(Lang.saved(this))) return;
                Lang.save(this, code);
                recreate(); // seperti tema: home dan layar lain menyusul saat tampil lagi (Theme.stale)
            });
        }
        look.add(row(getString(R.string.settings_lang_title), getString(R.string.settings_lang_desc), langs));


        boolean twelve = Clock.twelve(prefs);
        Key[] fmt = new Key[2];
        for (int i = 0; i < 2; i++) {
            final boolean v = i == 1;
            fmt[i] = key(getString(v ? R.string.settings_clock_12 : R.string.settings_clock_24), v == twelve, () -> {
                prefs.edit().putBoolean("clock12", v).apply();
                rebuildKeepScroll();
            });
        }
        look.add(row(getString(R.string.settings_clock_title), getString(R.string.settings_clock_desc), fmt));


        int om = Orientation.mode(prefs);
        int[] omValue = {Orientation.AUTO, Orientation.LANDSCAPE, Orientation.PORTRAIT};
        int[] omLabel = {R.string.settings_orient_auto, R.string.settings_orient_landscape, R.string.settings_orient_portrait};
        Key[] orients = new Key[omValue.length];
        for (int i = 0; i < orients.length; i++) {
            final int v = omValue[i];
            orients[i] = key(getString(omLabel[i]), v == om, () -> {
                prefs.edit().putInt("orientation", v).apply();
                Orientation.apply(this); // home dan layar lain menyusul saat tampil lagi (Theme.stale)
                rebuildKeepScroll();
            });
        }
        look.add(row(getString(R.string.settings_orient_title), getString(R.string.settings_orient_desc), orients));

        // Satu tema, atau tema siang dan malam yang berganti sendiri mengikuti matahari (Theme.saved).
        boolean autoTheme = Theme.auto(this);
        int[] sun = Theme.sun(this);
        boolean tw = Clock.twelve(prefs);
        String rise = Clock.hm(tw, sun[0]), set = Clock.hm(tw, sun[1]);
        look.add(row(getString(R.string.settings_theme_auto_title), getString(R.string.settings_theme_auto_desc, rise, set),
                key(getString(R.string.settings_on), autoTheme, () -> setThemeAuto(true)),
                key(getString(R.string.settings_off), !autoTheme, () -> setThemeAuto(false))));
        if (autoTheme) {
            look.add(themeRow("themeDay", getString(R.string.settings_theme_day_title),
                    getString(R.string.settings_theme_day_desc, rise, set)));
            look.add(themeRow("themeNight", getString(R.string.settings_theme_night_title),
                    getString(R.string.settings_theme_night_desc, set, rise)));
        } else {
            look.add(themeRow("theme", getString(R.string.settings_theme_title),
                    getResources().getStringArray(R.array.theme_desc)[Theme.chosen(this, "theme")]));
        }


        int tc = HomeApps.tileCount(prefs);
        Key[] counts = new Key[HomeApps.TILE_COUNTS.length];
        for (int i = 0; i < counts.length; i++) {
            final int n = HomeApps.TILE_COUNTS[i];
            counts[i] = key(String.valueOf(n), n == tc, () -> {
                prefs.edit().putInt("tiles", n).apply();
                rebuildKeepScroll(); // home dibangun ulang saat tampil lagi
            });
            counts[i].minWidth = px(56);
        }
        look.add(row(getString(R.string.settings_tiles_title), getString(R.string.settings_tiles_desc), counts));


        list.addView(group(GROUP_LOOK, getString(R.string.settings_group_look), dots(
                autoTheme ? getString(R.string.settings_group_theme_auto)
                        : getResources().getStringArray(R.array.theme_names)[Theme.chosen(this, "theme")],
                getString(Lang.EN.equals(Lang.code) ? R.string.lang_en : R.string.lang_id),
                getString(twelve ? R.string.settings_clock_12 : R.string.settings_clock_24),
                om == Orientation.AUTO ? null : getString(omLabel[om]),
                getString(R.string.settings_group_tiles, tc)), look));

        List<View> prayer = new ArrayList<>();
        int pm = prefs.getInt("prayerMethod", PrayerTimes.AUTO);
        int[] pmValue = {PrayerTimes.AUTO, PrayerTimes.KEMENAG, PrayerTimes.JAKIM};
        int[] pmLabel = {R.string.settings_method_auto, R.string.settings_method_kemenag, R.string.settings_method_jakim};
        Key[] methods = new Key[pmValue.length];
        for (int i = 0; i < methods.length; i++) {
            final int v = pmValue[i];
            methods[i] = key(getString(pmLabel[i]), v == pm, () -> {
                prefs.edit().putInt("prayerMethod", v).apply();
                PrayerAlarm.schedule(this); // pengingat ikut jadwal metode baru
                rebuildKeepScroll();
            });
        }
        prayer.add(row(getString(R.string.settings_method_title), getString(R.string.settings_method_desc,
                getString(PrayerTimes.method(prefs) == PrayerTimes.JAKIM ? R.string.settings_method_jakim_name
                        : R.string.settings_method_kemenag_name)), methods));


        int off = prefs.getInt("hijriOffset", 0);
        Key[] shift = new Key[5];
        for (int i = 0; i < 5; i++) {
            final int v = i - 2;
            shift[i] = key(v == 0 ? "0" : v > 0 ? "+" + v : "−" + (-v), v == off, () -> {
                prefs.edit().putInt("hijriOffset", v).apply();
                rebuildKeepScroll();
            });
            shift[i].minWidth = px(56);
        }
        prayer.add(row(getString(R.string.settings_hijri_title), getString(R.string.settings_hijri_desc,
                titleCase(Hijri.text(System.currentTimeMillis(), off))), shift));


        boolean pa = PrayerAlarm.enabled(prefs);
        prayer.add(row(getString(R.string.settings_prayer_title), getString(R.string.settings_prayer_desc),
                key(getString(R.string.settings_on), pa, () -> setPrayerAlert(true)),
                key(getString(R.string.settings_off), !pa, () -> setPrayerAlert(false)),
                key(getString(R.string.settings_prayer_test), false, () -> PrayerAlarm.notify(this, 3, System.currentTimeMillis()))));


        list.addView(group(GROUP_PRAYER, getString(R.string.settings_group_prayer), dots(
                getString(PrayerTimes.method(prefs) == PrayerTimes.JAKIM ? R.string.settings_method_jakim
                        : R.string.settings_method_kemenag),
                getString(R.string.settings_group_alert, getString(pa ? R.string.settings_on : R.string.settings_off))), prayer));

        List<View> saverRows = new ArrayList<>();
        int ss = Screensaver.seconds(prefs);
        // Pilihan waktu lalu COBA: tombol uji ada di baris yang pertama dicari pengguna.
        Key[] mins = new Key[Screensaver.SECONDS.length + 1];
        for (int i = 0; i < Screensaver.SECONDS.length; i++) {
            final int s = Screensaver.SECONDS[i];
            mins[i] = key(s == 0 ? getString(R.string.settings_off) : Screensaver.label(this, s, false), s == ss, () -> {
                Screensaver.setSeconds(prefs, s);
                rebuildKeepScroll();
            });
            if (s > 0) mins[i].minWidth = px(56);
        }
        mins[mins.length - 1] = key(getString(R.string.settings_saver_preview), false, this::previewSaver);
        saverRows.add(row(getString(R.string.settings_saver_title), getString(R.string.settings_saver_desc), mins));
        boolean photo = Screensaver.photo(this, prefs), hasPhoto = Screensaver.file(this).isFile();
        Key black = key(getString(R.string.settings_saver_black), !photo, () -> {
            prefs.edit().putInt("saverBg", Screensaver.BG_BLACK).apply();
            rebuildKeepScroll();
        });
        Key pic = key(getString(R.string.settings_saver_photo), photo, () -> {
            if (!hasPhoto) { pickPhoto(); return; }
            prefs.edit().putInt("saverBg", Screensaver.BG_PHOTO).apply();
            rebuildKeepScroll();
        });
        saverRows.add(row(getString(R.string.settings_saver_bg_title), getString(photo ? R.string.settings_saver_bg_photo_desc
                : R.string.settings_saver_bg_black_desc), hasPhoto ? new View[]{black, pic,
                key(getString(R.string.settings_saver_change), false, this::pickPhoto)}
                : new View[]{black, pic}));


        list.addView(group(GROUP_SAVER, getString(R.string.settings_saver_title), ss == 0 ? getString(R.string.settings_off)
                : getString(R.string.settings_group_saver, Screensaver.label(this, ss, true), getString(photo ? R.string.settings_saver_photo
                : R.string.settings_saver_black)), saverRows));

        List<View> homeRows = new ArrayList<>();
        int wm = Welcome.mode(prefs);
        homeRows.add(row(getString(R.string.settings_welcome_title), getString(R.string.settings_welcome_desc,
                getString(wm == Welcome.MODE_OFF ? R.string.settings_welcome_now_off
                        : wm == Welcome.MODE_CUSTOM ? R.string.settings_welcome_now_custom
                        : R.string.settings_welcome_now_default)),
                key(getString(R.string.settings_welcome_default), wm == Welcome.MODE_DEFAULT,
                        () -> setWelcome(Welcome.MODE_DEFAULT)),
                key(getString(R.string.settings_welcome_custom), wm == Welcome.MODE_CUSTOM, this::editWelcome),
                key(getString(R.string.settings_off), wm == Welcome.MODE_OFF, () -> setWelcome(Welcome.MODE_OFF)),
                key(getString(R.string.settings_welcome_view), false, () -> {
                    Welcome.Content c = Welcome.content(prefs);
                    if (c == null) Notice.info(this, getString(R.string.settings_welcome_disabled));
                    else Welcome.show(this, c);
                })));


        // Kunci layar: PIN 4 angka, gembok di home (ScreenLock). Ubah/hapus minta PIN lama.
        if (ScreenLock.hasPin(prefs)) {
            homeRows.add(row(getString(R.string.settings_lock_title), getString(R.string.settings_lock_desc_on),
                    key(getString(R.string.settings_lock_change), false, () -> ScreenLock.verify(this, prefs,
                            this::newPin, this::pinRemoved)),
                    key(getString(R.string.settings_lock_remove), false, () -> ScreenLock.verify(this, prefs, () -> {
                        ScreenLock.clear(prefs);
                        pinRemoved();
                    }, this::pinRemoved))));
        } else {
            homeRows.add(row(getString(R.string.settings_lock_title), getString(R.string.settings_lock_desc_off),
                    key(getString(R.string.settings_lock_set), false, this::newPin)));
        }


        homeRows.add(row(getString(R.string.settings_tiles_reset_title), getString(R.string.settings_tiles_reset_desc),
                key(getString(R.string.settings_tiles_reset), false, this::resetTiles)));


        int hid = HomeApps.hidden(prefs).size();
        homeRows.add(row(getString(R.string.settings_hidden_title), hid == 0 ? getString(R.string.settings_hidden_none)
                        : getResources().getQuantityString(R.plurals.settings_hidden_desc, hid, hid),
                hid == 0 ? new View[0] : new View[]{key(getString(R.string.settings_hidden_show), false, this::showHidden)}));


        list.addView(group(GROUP_HOME, getString(R.string.settings_group_home), dots(
                getString(R.string.settings_group_welcome, getString(wm == Welcome.MODE_OFF ? R.string.settings_off
                        : wm == Welcome.MODE_CUSTOM ? R.string.settings_welcome_custom : R.string.settings_welcome_default)),
                ScreenLock.hasPin(prefs) ? getString(R.string.settings_group_pin) : null,
                hid > 0 ? getString(R.string.settings_group_hidden, hid) : null), homeRows));

        List<View> sys = new ArrayList<>();
        if (def) sys.add(home);
        sys.add(row(getString(R.string.settings_system_title), getString(R.string.settings_system_desc),
                key(getString(R.string.settings_system_open), false, () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception ignored) { }
                })));

        list.addView(group(GROUP_SYSTEM, getString(R.string.settings_group_system), dots(
                def ? getString(R.string.settings_home_title) : null, getString(R.string.settings_system_title)), sys));
        return scroll(list);
    }

    /** "A · B · C", tanpa bagian yang null. */
    static String dots(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(p);
        }
        return b.toString();
    }

    /** Kepala kelompok: judul dan ringkasan setelan saat ini, lalu isinya bila terbuka. */
    View group(int id, String title, String summary, List<View> rows) {
        boolean open = id == openGroup;
        LinearLayout g = column();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, px(22), 0, px(22));
        StateListDrawable press = new StateListDrawable();
        press.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(PANEL));
        head.setBackground(press);
        LinearLayout t = column();
        TextView h = text(title, fHeavy, 30, TEXT);
        h.setLetterSpacing(0.02f);
        t.addView(h);
        TextView sum = text(summary, fMono, 12, open ? AMBER : DIM);
        sum.setLetterSpacing(0.2f);
        sum.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, WRAP);
        sl.topMargin = px(8);
        t.addView(sum, sl);
        head.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView sign = text(open ? "−" : "+", fMonoBold, 28, open ? AMBER : DIM);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(WRAP, WRAP);
        gl.leftMargin = gl.rightMargin = px(16);
        head.addView(sign, gl);
        head.setContentDescription(title);
        head.setOnClickListener(v -> toggleGroup(id));
        g.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));
        g.addView(rule(), ruleParams(true, 0, 0));
        if (open) {
            LinearLayout body = column();
            body.setPadding(px(24), 0, 0, 0);
            for (View r : rows) body.addView(r);
            g.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        groupViews[id] = g;
        return g;
    }

    /** Buka satu kelompok (yang lain menutup) dan gulir supaya kepalanya di atas. */
    void toggleGroup(int id) {
        openGroup = openGroup == id ? -1 : id;
        show(TAB_LAUNCHER, true);
        View now = content.getChildAt(0), g = openGroup < 0 ? null : groupViews[openGroup];
        if (now instanceof ScrollView && g != null) scrollBeforeDraw(now, g::getTop);
    }

    // ---------------------------------------------------------------- tab Pembaruan

    View updatePanel() {
        LinearLayout list = column();
        String ver = Updater.installedVersion(this);
        long checked = prefs.getLong("updateChecked", 0);
        updateKey = key(getString(R.string.update_check), false, this::checkUpdate);
        list.addView(row(getString(R.string.update_title_row), getString(R.string.update_desc, ver, checked == 0
                        ? getString(R.string.update_never_checked) : getString(R.string.update_last_checked, ago(this, checked))),
                updateKey));
        boolean au = prefs.getBoolean("autoUpdate", false);
        list.addView(row(getString(R.string.update_auto_title), getString(R.string.update_auto_desc),
                key(getString(R.string.settings_on), au, () -> setAutoUpdate(true)),
                key(getString(R.string.settings_off), !au, () -> setAutoUpdate(false))));

        list.addView(row(getString(R.string.settings_about_title), getString(R.string.settings_about_desc, ver)));
        return scroll(list);
    }

    /**
     * "12 JUMADIL AWAL 1448 H" → "12 Jumadil Awal 1448 H", "12 RABI' AL-AWWAL 1448 AH" → "12 Rabi' Al-Awwal 1448 AH".
     * Kata pendek berhuruf besar semua (H, AH) adalah singkatan dan dibiarkan.
     */
    static String titleCase(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (String w : s.split(" ", -1)) {
            if (b.length() > 0) b.append(' ');
            if (w.length() <= 2 && w.equals(w.toUpperCase(Locale.ROOT))) { b.append(w); continue; }
            boolean start = true;
            for (char ch : w.toCharArray()) {
                b.append(start ? ch : Character.toLowerCase(ch));
                start = ch == '-';
            }
        }
        return b.toString();
    }

    /** Pilihan tema untuk "theme", "themeDay", atau "themeNight". */
    View themeRow(String pref, String title, String desc) {
        String[] names = getResources().getStringArray(R.array.theme_names);
        int cur = Theme.chosen(this, pref);
        Key[] keys = new Key[Theme.COUNT];
        for (int i = 0; i < keys.length; i++) {
            final int t = i;
            keys[i] = key(names[i], t == cur, () -> {
                if (t == Theme.chosen(this, pref)) return;
                int was = Theme.current;
                Theme.save(this, pref, t);
                themeChanged(was);
            });
        }
        return row(title, desc, keys);
    }

    void setThemeAuto(boolean on) {
        if (on == Theme.auto(this)) return;
        int was = Theme.current;
        Theme.setAuto(this, on);
        themeChanged(was);
    }

    /** Tema yang berlaku berganti: bangun ulang dengan palet baru; home dan layar lain menyusul lewat Theme.stale. */
    void themeChanged(int was) {
        if (Theme.current != was) recreate();
        else rebuildKeepScroll();
    }

    static final int PICK_PHOTO = 1;

    /** Pemilih foto bawaan; headunit tanpa DocumentsUI biasanya masih punya galeri (ACTION_PICK). */
    void pickPhoto() {
        Intent[] tries = {new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),
                new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)};
        for (Intent i : tries) {
            try {
                startActivityForResult(i, PICK_PHOTO);
                return;
            } catch (android.content.ActivityNotFoundException ignored) { }
        }
        Notice.info(this, getString(R.string.settings_saver_no_picker));
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_PHOTO || res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        new Thread(() -> {
            boolean ok;
            try {
                Screensaver.importPhoto(this, uri);
                ok = true;
            } catch (Exception | OutOfMemoryError e) {
                ok = false;
            }
            final boolean saved = ok;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (!saved) { Notice.fail(this, getString(R.string.settings_saver_failed)); return; }
                prefs.edit().putInt("saverBg", Screensaver.BG_PHOTO).apply();
                if (current == TAB_LAUNCHER) rebuildKeepScroll();
                Notice.ok(this, getString(R.string.settings_saver_saved));
            });
        }, "saver-import").start();
    }

    /** Screensaver layar penuh seperti di home; ketuk untuk menutup. */
    void previewSaver() {
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Screensaver.Face f = new Screensaver.Face(this, fHeavy, fMono);
        f.setOnClickListener(v -> d.dismiss());
        d.setContentView(f);
        d.setOnDismissListener(x -> f.stop());
        d.show();
        // Seperti di home: tanpa bilah status dan navigasi.
        d.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        f.start(prefs);
    }

    Key updateKey;
    boolean updating;

    /** "baru saja", "5 menit lalu", "3 jam lalu", "2 hari lalu" (DateUtils mengikuti bahasa sistem, bukan Lang). */
    static String ago(Context c, long t) {
        int m = (int) Math.max(0, (System.currentTimeMillis() - t) / 60_000);
        android.content.res.Resources r = c.getResources();
        return m < 1 ? c.getString(R.string.update_just_now)
                : m < 60 ? r.getQuantityString(R.plurals.update_minutes_ago, m, m)
                : m < 1440 ? r.getQuantityString(R.plurals.update_hours_ago, m / 60, m / 60)
                : r.getQuantityString(R.plurals.update_days_ago, m / 1440, m / 1440);
    }

    void checkUpdate() {
        if (updating) return;
        updating = true;
        updateKey.setLabel(getString(R.string.update_checking));
        new Thread(() -> {
            Updater.Release r = null;
            String err = null;
            try {
                r = Updater.latest();
            } catch (Exception e) {
                err = e instanceof java.net.UnknownHostException ? getString(R.string.update_no_internet) : AiClient.describe(this, e);
            }
            final Updater.Release rel = r;
            final String e = err;
            runOnUiThread(() -> {
                updating = false;
                if (isFinishing()) return;
                if (e == null) prefs.edit().putLong("updateChecked", System.currentTimeMillis()).apply();
                String cur = Updater.installedVersion(this);
                if (e != null) {
                    updateKey.setLabel(getString(R.string.update_check));
                    Notice.show(this, Notice.FAIL, getString(R.string.update_check_title), e);
                } else if (rel == null || Updater.compare(rel.version, cur) <= 0) {
                    show(TAB_UPDATE, true);
                    Notice.ok(this, getString(R.string.update_latest, cur));
                } else {
                    updateKey.setLabel(getString(R.string.update_check));
                    offerUpdate(this, rel, null);
                }
            });
        }, "update-check").start();
    }

    /**
     * Dialog "KlikDash x tersedia" dengan catatan rilis; dipakai CEK UPDATE dan cek otomatis di home.
     * {@code later}: dijalankan bila pengguna memilih Nanti (boleh null).
     */
    static void offerUpdate(Activity a, Updater.Release rel, Runnable later) {
        // Catatan rilis dalam bahasa antarmuka; pesan AlertDialog sudah bisa digulir bila panjang.
        String notes = Updater.plainNotes(rel.notes, Lang.en());
        showDark(new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.update_available, rel.version))
                .setMessage(a.getString(R.string.update_installed_size, Updater.installedVersion(a),
                        (int) Math.max(1, rel.size / 1024)) + (notes.isEmpty() ? "" : "\n\n" + notes))
                .setNegativeButton(a.getString(R.string.update_later), (d, w) -> { if (later != null) later.run(); })
                .setPositiveButton(a.getString(R.string.update_download_install), (d, w) -> downloadUpdate(a, rel)));
    }

    static void downloadUpdate(Activity a, Updater.Release rel) {
        AlertDialog prog = showDark(new AlertDialog.Builder(a).setTitle(a.getString(R.string.update_downloading, rel.version))
                .setMessage("0%").setCancelable(false));
        new Thread(() -> {
            try {
                java.io.File apk = Updater.download(a, rel, pct -> a.runOnUiThread(() -> prog.setMessage(pct + "%")));
                a.runOnUiThread(() -> {
                    prog.setMessage(a.getString(R.string.update_installing));
                    Updater.install(a, apk, () -> a.runOnUiThread(() -> {
                        prog.dismiss();
                        Notice.info(a, a.getString(R.string.update_allow_install));
                    }));
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> {
                    prog.dismiss();
                    Notice.show(a, Notice.FAIL, a.getString(R.string.update_title),
                            e.getMessage() != null ? e.getMessage() : a.getString(R.string.update_try_later));
                });
            }
        }, "update-download").start();
    }

    void setAutoUpdate(boolean on) {
        prefs.edit().putBoolean("autoUpdate", on).apply();
        show(TAB_UPDATE, true);
    }

    void setPrayerAlert(boolean on) {
        prefs.edit().putBoolean("prayerAlert", on).apply();
        PrayerAlarm.schedule(this);
        PrayerAlarm.allowOverlay(this);
        rebuildKeepScroll();
    }

    void setWelcome(int mode) {
        prefs.edit().putInt("welcome", mode).apply();
        rebuildKeepScroll();
    }

    /** Teks sambutan sendiri: judul, teks Arab, bacaan Latin, arti/pesan; minimal Arab atau pesan. */
    void editWelcome() {
        LinearLayout form = column();
        int pad = px(24);
        form.setPadding(pad, pad, pad, 0);
        EditText title = welcomeField(form, getString(R.string.settings_welcome_f_title),
                getString(R.string.settings_welcome_f_title_hint), "wTitle", false);
        EditText arab = welcomeField(form, getString(R.string.settings_welcome_f_arab), "", "wArab", true);
        arab.setTextDirection(View.TEXT_DIRECTION_RTL);
        EditText latin = welcomeField(form, getString(R.string.settings_welcome_f_latin), "", "wLatin", true);
        EditText text = welcomeField(form, getString(R.string.settings_welcome_f_text),
                getString(R.string.settings_welcome_f_text_hint), "wText", true);
        AlertDialog d = showDark(new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_welcome_dialog))
                .setView(scroll(form))
                .setNegativeButton(getString(R.string.common_cancel), null)
                .setPositiveButton(getString(R.string.common_save), null));
        // Simpan tidak langsung menutup dialog, supaya isian kosong bisa dilengkapi.
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = arab.getText().toString().trim(), t = text.getText().toString().trim();
            if (a.isEmpty() && t.isEmpty()) { Notice.info(this, getString(R.string.settings_welcome_need_text)); return; }
            prefs.edit().putString("wTitle", title.getText().toString().trim()).putString("wArab", a)
                    .putString("wLatin", latin.getText().toString().trim()).putString("wText", t)
                    .putInt("welcome", Welcome.MODE_CUSTOM).apply();
            d.dismiss();
            rebuildKeepScroll();
            Welcome.show(this, Welcome.content(prefs));
        });
    }

    EditText welcomeField(LinearLayout form, String name, String hint, String key, boolean multi) {
        EditText e = field(hint, prefs.getString(key, ""), false);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | (multi ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        e.setTypeface(fSerif);
        if (multi) {
            e.setSingleLine(false);
            e.setMinLines(2);
            e.setMaxLines(4);
            e.setGravity(Gravity.TOP | Gravity.START);
        }
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(MATCH, WRAP);
        ll.topMargin = form.getChildCount() == 0 ? 0 : px(16);
        ll.bottomMargin = px(6);
        form.addView(label(name), ll);
        form.addView(e, new LinearLayout.LayoutParams(MATCH, multi ? WRAP : px(52)));
        return e;
    }

    void resetTiles() {
        showDark(new AlertDialog.Builder(this)
                .setMessage(getString(R.string.settings_tiles_reset_confirm))
                .setNegativeButton(getString(R.string.common_cancel), null)
                .setPositiveButton(getString(R.string.settings_tiles_reset_button), (d, w) -> {
                    SharedPreferences.Editor e = prefs.edit();
                    for (int i = 0; i < HomeApps.SLOTS; i++) e.remove("s" + i).remove("l" + i);
                    e.apply();
                    Notice.ok(this, getString(R.string.settings_tiles_reset_done));
                }));
    }

    /** Aplikasi tersembunyi: ketuk satu untuk memunculkannya lagi di Semua Aplikasi, atau tampilkan semua. */
    void showHidden() {
        android.content.pm.PackageManager pm = getPackageManager();
        java.util.List<String[]> apps = new java.util.ArrayList<>(); // {komponen, nama}
        for (String k : HomeApps.hidden(prefs)) {
            android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(k);
            String name;
            try {
                name = pm.getActivityInfo(cn, 0).loadLabel(pm).toString().trim();
            } catch (Exception e) { // sudah dihapus dari headunit
                name = cn != null ? cn.getPackageName() : k;
            }
            apps.add(new String[]{k, name});
        }
        java.util.Collections.sort(apps, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = apps.get(i)[1];
        showDark(new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_hidden_pick))
                .setItems(names, (d, w) -> {
                    HomeApps.setHidden(prefs, apps.get(w)[0], false);
                    Notice.ok(this, getString(R.string.settings_hidden_shown, names[w]));
                    rebuildKeepScroll();
                })
                .setNeutralButton(getString(R.string.settings_hidden_all), (d, w) -> {
                    prefs.edit().remove("hiddenApps").apply();
                    Notice.ok(this, getString(R.string.settings_hidden_all_done));
                    rebuildKeepScroll();
                })
                .setNegativeButton(getString(R.string.common_close), null));
    }

    // ---------------------------------------------------------------- bagian bersama (dipakai PowerPanel & AiPanel)

    LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    ScrollView scroll(View child) {
        ScrollView s = new ScrollView(this);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setVerticalScrollBarEnabled(false);
        s.setFillViewport(true);
        s.addView(child, new ScrollView.LayoutParams(MATCH, WRAP));
        return s;
    }

    /** Satu pengaturan: judul, penjelasan, lalu tombol di kanan (atau di bawah pada layar sempit). */
    View row(String title, String desc, View... actions) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        r.setGravity(narrow ? Gravity.START : Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(20), 0, px(20));

        LinearLayout t = column();
        TextView h = text(title, fBold, 24, TEXT);
        h.setLetterSpacing(0.03f);
        t.addView(h);
        if (desc != null) {
            TextView d = paragraph(desc);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(MATCH, WRAP);
            dl.topMargin = px(6);
            t.addView(d, dl);
        }
        r.addView(t, narrow ? new LinearLayout.LayoutParams(MATCH, WRAP) : new LinearLayout.LayoutParams(0, WRAP, 1f));

        if (actions.length > 0) {
            LinearLayout a = new LinearLayout(this);
            a.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < actions.length; i++) {
                LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(WRAP, px(52));
                if (i > 0) al.leftMargin = px(8);
                a.addView(actions[i], al);
            }
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(WRAP, WRAP);
            if (narrow) ap.topMargin = px(14); else ap.leftMargin = px(24);
            r.addView(a, ap);
        }

        LinearLayout wrap = column();
        wrap.addView(r, new LinearLayout.LayoutParams(MATCH, WRAP));
        wrap.addView(rule(), ruleParams(true, 0, 0));
        return wrap;
    }

    TextView paragraph(String s) {
        TextView d = text(s, fSerif, 16, HomeActivity.SOFT);
        d.setSingleLine(false);
        d.setLineSpacing(0, 1.25f);
        return d;
    }

    TextView label(String s) {
        TextView t = text(s, fMonoBold, 12, AMBER);
        t.setLetterSpacing(0.25f);
        return t;
    }

    /** Kolom isian gelap untuk dialog (API key, URL, model). {@code secret}: disamarkan seperti kata sandi. */
    EditText field(String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setHintTextColor(FAINT);
        e.setTextColor(TEXT);
        e.setTypeface(fMono);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setSingleLine(true);
        e.setPadding(px(14), px(12), px(14), px(12));
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setStroke(px(1), LINE);
        bg.setCornerRadius(Theme.corner(dp));
        e.setBackground(bg);
        return e;
    }

    /** Label + kolom, ditumpuk; untuk isi dialog. */
    void addField(LinearLayout form, String name, EditText e) {
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(MATCH, WRAP);
        ll.topMargin = form.getChildCount() == 0 ? 0 : px(16);
        ll.bottomMargin = px(6);
        form.addView(label(name), ll);
        form.addView(e, new LinearLayout.LayoutParams(MATCH, px(52)));
    }

    void newPin() {
        ScreenLock.create(this, pin -> {
            ScreenLock.setPin(prefs, pin);
            rebuildKeepScroll();
            Notice.ok(this, getString(R.string.settings_lock_saved));
        });
    }

    void pinRemoved() {
        rebuildKeepScroll();
        Notice.ok(this, getString(R.string.settings_lock_removed));
    }

    // ---------------------------------------------------------------- tab KlikBeam

    /** Tab aplikasi KlikBeam di HP: 0 = Android (uji tertutup Play Store), 1 = iPhone (App Store). */
    int beamApp;

    /** KlikBeam (BETA): cara menyambung, kode pairing, dan alamat; penerimanya BeamActivity. */
    View beamPanel() {
        LinearLayout list = column();
        list.addView(beamPairing()); // paling atas: yang dicari saat menyambung
        list.addView(row(getString(R.string.settings_beam_title), getString(R.string.settings_beam_desc),
                key(getString(R.string.settings_beam_new_code), false, () -> {
                    BeamActivity.newCode(this);
                    show(TAB_BEAM, true);
                }),
                key(getString(R.string.settings_beam_open), false,
                        () -> startActivity(new Intent(this, BeamActivity.class)))));
        boolean auto = BeamHub.autoOpen(this);
        list.addView(row(getString(R.string.settings_beam_auto_title), getString(R.string.settings_beam_auto_desc),
                key(getString(R.string.settings_on), auto, () -> { BeamHub.setAutoOpen(this, true); rebuildKeepScroll(TAB_BEAM); }),
                key(getString(R.string.settings_off), !auto, () -> { BeamHub.setAutoOpen(this, false); rebuildKeepScroll(TAB_BEAM); })));
        list.addView(row(getString(R.string.settings_beam_app_title), getString(R.string.settings_beam_app_desc),
                key(getString(R.string.settings_beam_android), beamApp == 0, () -> { beamApp = 0; show(TAB_BEAM, true); }),
                key(getString(R.string.settings_beam_ios), beamApp == 1, () -> { beamApp = 1; show(TAB_BEAM, true); })));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(MATCH, WRAP);
        al.topMargin = px(20);
        al.bottomMargin = px(28);
        list.addView(beamApp == 0 ? beamAndroid() : beamIos(), al);
        return scroll(list);
    }

    /**
     * Kode pairing dan alamat, besar: pengguna mengetiknya di HP sambil melihat headunit, dan di dalam paragraf
     * keduanya sulit ditemukan. Satu alamat per baris (wlan0, dan ap0 bila headunit jadi hotspot). Di atas kode, QR
     * berisi keduanya: dipindai kamera HP, aplikasi KlikBeam langsung terisi (BeamActivity.pairUrl).
     */
    View beamPairing() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        r.setPadding(0, px(20), 0, px(24));
        LinearLayout code = column();
        android.graphics.drawable.Drawable qr = BeamActivity.pairQr(this);
        if (qr != null) { // di atas kode: dipindai kamera HP, aplikasi KlikBeam langsung terisi
            LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(WRAP, WRAP);
            ql.bottomMargin = px(20);
            code.addView(HelpVideo.qrCard(this, qr, getString(R.string.settings_beam_scan_pair), null), ql);
        }
        code.addView(label(getString(R.string.settings_beam_code_label)));
        TextView c = text(BeamActivity.codeText(this), fHeavy, 64, AMBER);
        c.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(WRAP, WRAP);
        vl.topMargin = px(6);
        code.addView(c, vl);
        r.addView(code, narrow ? new LinearLayout.LayoutParams(MATCH, WRAP) : new LinearLayout.LayoutParams(0, WRAP, 1f));

        LinearLayout addr = column();
        addr.addView(label(getString(R.string.settings_beam_addr_label)));
        String a = BeamActivity.addressText();
        TextView at = a != null ? text(a.replace("   ", "\n"), fMonoBold, 26, TEXT)
                : text(getString(R.string.beam_no_network), fBold, 18, DIM);
        at.setSingleLine(false);
        at.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(WRAP, WRAP);
        tl.topMargin = px(a != null ? 12 : 8);
        addr.addView(at, tl);
        LinearLayout.LayoutParams al = narrow ? new LinearLayout.LayoutParams(MATCH, WRAP)
                : new LinearLayout.LayoutParams(0, WRAP, 1f);
        if (narrow) al.topMargin = px(20); else al.leftMargin = px(28);
        r.addView(addr, al);

        LinearLayout wrap = column();
        wrap.addView(r, new LinearLayout.LayoutParams(MATCH, WRAP));
        wrap.addView(rule(), ruleParams(true, 0, 0));
        return wrap;
    }

    // ---------------------------------------------------------------- tab Akun

    /** Pairing, unggahan atau trip berganti: tab Akun dibangun ulang bila sedang tampil. */
    final Runnable accountRx = () -> {
        if (current == TAB_ACCOUNT && !isFinishing()) rebuildKeepScroll(TAB_ACCOUNT);
    };

    View accountPanel() {
        LinearLayout list = column();
        if (Account.linked(this)) {
            list.addView(row(getString(R.string.account_linked_title), getString(R.string.account_linked_desc, Account.carName(this)),
                    key(getString(R.string.account_unlink), false, this::confirmUnlink)));
            boolean on = Account.tripsOn(this);
            list.addView(row(getString(R.string.account_trips_title), tripsStatus(on),
                    key(getString(R.string.settings_on), on, () -> { Account.setTripsOn(this, true); rebuildKeepScroll(TAB_ACCOUNT); }),
                    key(getString(R.string.settings_off), !on, () -> { Account.setTripsOn(this, false); rebuildKeepScroll(TAB_ACCOUNT); })));
        } else {
            Account.Pairing p = Account.pairing;
            if (p == null) {
                list.addView(row(getString(R.string.account_title), getString(R.string.account_desc),
                        key(getString(R.string.account_link), true, () -> {
                            Account.startPairing(this);
                            show(TAB_ACCOUNT, true);
                        })));
            } else {
                list.addView(accountPairing(p));
            }
        }
        list.addView(row(getString(R.string.account_privacy_title), getString(R.string.account_privacy_desc)));
        return scroll(list);
    }

    String tripsStatus(boolean on) {
        if (!on) return getString(R.string.account_trips_off);
        int n = Account.pending(this);
        String now = TripService.recording() ? getString(R.string.account_trips_recording)
                : n > 0 ? getResources().getQuantityString(R.plurals.account_trips_pending, n, n)
                : getString(R.string.account_trips_sent);
        return getString(R.string.account_trips_desc) + " " + now;
    }

    /** QR dan kode di kiri (seperti pairing KlikBeam), langkah dan status di kanan. */
    View accountPairing(Account.Pairing p) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        r.setPadding(0, px(20), 0, px(24));
        if (p.state == Account.Pairing.SHOWING) {
            LinearLayout code = column();
            QrCode q = QrCode.encode(p.url());
            if (q != null) {
                LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(WRAP, WRAP);
                ql.bottomMargin = px(20);
                code.addView(HelpVideo.qrCard(this, new QrCode.QrDrawable(q), getString(R.string.account_scan), null), ql);
            }
            code.addView(label(getString(R.string.account_code_label)));
            TextView c = text(p.codeText(), fHeavy, 40, AMBER);
            c.setLetterSpacing(0.04f);
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(WRAP, WRAP);
            vl.topMargin = px(6);
            code.addView(c, vl);
            r.addView(code, narrow ? new LinearLayout.LayoutParams(MATCH, WRAP) : new LinearLayout.LayoutParams(WRAP, WRAP));
        }
        LinearLayout side = column();
        side.addView(text(getString(R.string.account_title), fBold, 24, TEXT));
        String msg;
        if (p.state == Account.Pairing.SHOWING) {
            java.util.Calendar e = java.util.Calendar.getInstance();
            e.setTimeInMillis(p.expiresAt);
            msg = getString(R.string.account_pair_steps, Clock.hm(Clock.twelve(prefs),
                    e.get(java.util.Calendar.HOUR_OF_DAY) * 60 + e.get(java.util.Calendar.MINUTE)));
        } else if (p.state == Account.Pairing.FAILED) {
            msg = p.error != null ? p.error : getString(R.string.account_pair_offline);
        } else {
            msg = getString(R.string.account_pair_wait);
        }
        TextView d = paragraph(msg);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(MATCH, WRAP);
        dl.topMargin = px(8);
        side.addView(d, dl);
        LinearLayout keys = new LinearLayout(this);
        if (p.state == Account.Pairing.FAILED) {
            keys.addView(key(getString(R.string.account_retry), true, () -> {
                Account.startPairing(this);
                show(TAB_ACCOUNT, true);
            }), new LinearLayout.LayoutParams(WRAP, px(52)));
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(WRAP, px(52));
        if (keys.getChildCount() > 0) cl.leftMargin = px(8);
        keys.addView(key(getString(R.string.common_cancel).toUpperCase(Lang.locale()), false, () -> {
            Account.cancelPairing();
            show(TAB_ACCOUNT, true);
        }), cl);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(WRAP, WRAP);
        kl.topMargin = px(20);
        side.addView(keys, kl);
        LinearLayout.LayoutParams sl = narrow ? new LinearLayout.LayoutParams(MATCH, WRAP) : new LinearLayout.LayoutParams(0, WRAP, 1f);
        if (p.state == Account.Pairing.SHOWING) { if (narrow) sl.topMargin = px(20); else sl.leftMargin = px(32); }
        r.addView(side, sl);

        LinearLayout wrap = column();
        wrap.addView(r, new LinearLayout.LayoutParams(MATCH, WRAP));
        wrap.addView(rule(), ruleParams(true, 0, 0));
        return wrap;
    }

    void confirmUnlink() {
        Sheet s = new Sheet(this, AMBER, getString(R.string.account_title), getString(R.string.account_unlink_title), HelpVideo.NONE)
                .text(getString(R.string.account_unlink_text), 0);
        s.key(getString(R.string.common_cancel).toUpperCase(Lang.locale()), false, null)
                .key(getString(R.string.account_unlink), true, () -> {
                    Account.unlink(this);
                    rebuildKeepScroll(TAB_ACCOUNT);
                }).show();
    }

    /** Ikut uji tertutup di Play Store: tiga langkah, dua di antaranya dengan QR untuk dibuka di HP. */
    View beamAndroid() {
        LinearLayout box = column();
        box.addView(label(getString(R.string.settings_beam_android_tag)));
        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        String[] text = {getString(R.string.settings_beam_step1), getString(R.string.settings_beam_step2),
                getString(R.string.settings_beam_step3)};
        int[] qr = {R.drawable.qr_beam_group, R.drawable.qr_beam_testing, 0};
        String[] url = {"groups.google.com/g/klikbeam-testers", "play.google.com/apps/testing/com.ashafizullah.klikbeam", null};
        for (int i = 0; i < text.length; i++) {
            LinearLayout col = column();
            TextView num = label(String.format(java.util.Locale.ROOT, "%02d", i + 1));
            num.setTextSize(14);
            col.addView(num);
            TextView t = text(text[i], fBold, 18, TEXT);
            t.setSingleLine(false);
            t.setLineSpacing(0, 1.25f);
            if (!narrow) t.setMinLines(2); // QR langkah 1 dan 2 sejajar
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(MATCH, WRAP);
            tl.topMargin = px(8);
            col.addView(t, tl);
            if (qr[i] != 0) {
                LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(WRAP, WRAP);
                ql.topMargin = px(16);
                col.addView(HelpVideo.qrCard(this, qr[i], getString(R.string.settings_beam_scan), url[i]), ql);
            }
            LinearLayout.LayoutParams cl = narrow ? new LinearLayout.LayoutParams(MATCH, WRAP)
                    : new LinearLayout.LayoutParams(0, WRAP, 1f);
            if (i > 0) { if (narrow) cl.topMargin = px(28); else cl.leftMargin = px(28); }
            steps.addView(col, cl);
        }
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, WRAP);
        sl.topMargin = px(18);
        box.addView(steps, sl);
        return box;
    }

    /** Pasang dari App Store: QR halaman KlikBeam untuk dipindai iPhone. */
    View beamIos() {
        LinearLayout box = column();
        box.addView(label(getString(R.string.settings_beam_ios_tag)));
        TextView t = text(getString(R.string.settings_beam_ios_text), fBold, 18, TEXT);
        t.setSingleLine(false);
        t.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(MATCH, WRAP);
        tl.topMargin = px(10);
        box.addView(t, tl);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(WRAP, WRAP);
        ql.topMargin = px(16);
        box.addView(HelpVideo.qrCard(this, R.drawable.qr_beam_appstore, getString(R.string.settings_beam_scan),
                "apps.apple.com/id/app/klikbeam/id6826742960"), ql);
        return box;
    }

    /** Bangun ulang tab Launcher tanpa melompat ke atas (baris kunci layar ada di bawah). */
    void rebuildKeepScroll() { rebuildKeepScroll(TAB_LAUNCHER); }

    void rebuildKeepScroll(int tab) {
        View old = content.getChildAt(0);
        int y = old instanceof ScrollView ? old.getScrollY() : 0;
        show(tab, true);
        View now = content.getChildAt(0);
        if (now instanceof ScrollView) scrollBeforeDraw(now, () -> y);
    }

    /**
     * Gulir tab yang baru dibangun ke {@code y} sebelum frame pertamanya digambar. Dengan post() frame pertama tergambar
     * di posisi atas lalu melompat (berkedip tiap pilihan diganti); frame itu dilewati, layar tetap menampilkan yang lama.
     * {@code y} dibaca saat itu: tata letak baru sudah diukur (posisi grup accordion).
     */
    static void scrollBeforeDraw(View v, java.util.function.IntSupplier y) {
        // Lepas dari observer tempat ia dipasang: bila tab dibangun ulang lagi sebelum frame ini, v sudah terlepas dan
        // v.getViewTreeObserver() memberi observer lain, sehingga listener tertinggal dan melewatkan setiap frame.
        android.view.ViewTreeObserver o = v.getViewTreeObserver();
        o.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (o.isAlive()) o.removeOnPreDrawListener(this);
                else v.getViewTreeObserver().removeOnPreDrawListener(this);
                v.scrollTo(0, y.getAsInt());
                return false;
            }
        });
    }

    /** Dialog bergaya KlikDash: panel gelap bergaris tipis, tombol amber. Panggil sebagai ganti show(). */
    static AlertDialog showDark(AlertDialog.Builder b) {
        AlertDialog d = b.create();
        d.setCanceledOnTouchOutside(false); // jangan hilang karena tersentuh, isian (API key) bisa ikut hilang
        Context c = d.getContext();
        float density = c.getResources().getDisplayMetrics().density;
        d.setOnShowListener(x -> {
            for (int w : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                android.widget.Button btn = d.getButton(w);
                if (btn != null) btn.setTextColor(w == AlertDialog.BUTTON_POSITIVE ? AMBER : TEXT);
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setStroke(Math.round(density), LINE);
        bg.setCornerRadius(Theme.corner(density));
        d.getWindow().setBackgroundDrawable(bg);
        d.show();
        Notice.track(d);
        return d;
    }

    View rule() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        return v;
    }

    LinearLayout.LayoutParams ruleParams(boolean horizontal, int top, int side) {
        LinearLayout.LayoutParams l = horizontal ? new LinearLayout.LayoutParams(MATCH, px(1))
                : new LinearLayout.LayoutParams(px(1), MATCH);
        l.topMargin = px(top);
        l.leftMargin = l.rightMargin = px(side);
        return l;
    }

    Typeface font(String file, String axes) { return Compat.font(getAssets(), file, axes, Typeface.DEFAULT); }

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

    Key key(String label, boolean on, Runnable click) { return new Key(this, fMonoBold, label, on, click); }

    int px(float v) { return Math.round(v * dp); }

    float sp(float v) { return v * sp; }

    // ---------------------------------------------------------------- views

    /** Tab: nama besar + keterangan kecil; yang aktif diberi garis amber di tepi. */
    class Tab extends View {
        final String name, caption;
        boolean down;
        final Paint bar = new Paint(), n = new Paint(Paint.ANTI_ALIAS_FLAG), cp = new Paint(Paint.ANTI_ALIAS_FLAG),
                fill = new Paint(), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        Tab(String name, String caption, Runnable click) {
            super(SettingsActivity.this);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(px(1));
            this.name = name;
            this.caption = caption;
            setContentDescription(name);
            bar.setColor(AMBER);
            n.setTypeface(fBold);
            n.setTextSize(sp(portrait || railDp < 200 ? 20 : 24));
            n.setLetterSpacing(0.03f);
            cp.setTypeface(fMono);
            cp.setTextSize(sp(10));
            cp.setLetterSpacing(0.2f);
            setClickable(true);
            setOnClickListener(v -> click.run());
        }

        @Override public void setSelected(boolean s) {
            super.setSelected(s);
            invalidate();
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (isPressed() != down) { down = isPressed(); invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            boolean sel = isSelected();
            if (Theme.gloss() && (sel || down)) {
                Theme.panel(c, fill, stroke, w, h, PANEL, true); // tab terpilih: kartu kaca
            } else if (down) {
                bar.setColor(PANEL);
                c.drawRect(0, 0, w, h, bar);
            }
            bar.setColor(AMBER);
            if (sel) {
                if (portrait) c.drawRect(0, h - px(4), w, h, bar);
                else c.drawRect(0, px(14), px(6), h - px(14), bar);
            }
            float x = portrait ? px(4) : px(24);
            n.setColor(sel ? TEXT : DIM);
            cp.setColor(sel ? AMBER : FAINT);
            float avail = w - x - px(8);
            CharSequence nm = android.text.TextUtils.ellipsize(name, new android.text.TextPaint(n), avail,
                    android.text.TextUtils.TruncateAt.END);
            c.drawText(nm, 0, nm.length(), x, h / 2f + px(2), n);
            if (!portrait || w > px(200)) {
                CharSequence cs = android.text.TextUtils.ellipsize(caption, new android.text.TextPaint(cp), avail,
                        android.text.TextUtils.TruncateAt.END);
                c.drawText(cs, 0, cs.length(), x, h / 2f + px(22), cp);
            }
        }
    }
}
