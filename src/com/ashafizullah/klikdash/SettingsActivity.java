package com.ashafizullah.klikdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.BG;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.FAINT;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;

/**
 * Pengaturan KlikDash dalam tab: Launcher (tampilan utama, Hijriah, ubin), Hemat Daya, Asisten AI, dan KlikBeam.
 * Tab di kiri pada layar mendatar, di atas pada layar tegak.
 */
public class SettingsActivity extends Activity {

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final String EXTRA_TAB = "tab";
    static final int TAB_LAUNCHER = 0, TAB_POWER = 1, TAB_AI = 2, TAB_BEAM = 3;
    static final int TAB_COUNT = 4;

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
        prefs = createDeviceProtectedStorageContext().getSharedPreferences("klikdash", MODE_PRIVATE);
        Tweaks.init(this);
        build(b != null ? b.getInt(EXTRA_TAB, 0) : getIntent().getIntExtra(EXTRA_TAB, TAB_LAUNCHER));
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_TAB, Math.max(current, 0));
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
        // Kembali dari pengaturan sistem atau Play Store: status tampilan utama atau mesin suara mungkin berubah.
        if (current == TAB_LAUNCHER || current == TAB_AI) show(current, true);
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
        for (int i = 0; i < TAB_COUNT; i++) {
            final int idx = i;
            tabs[i] = new Tab(names[i], captions[i], () -> show(idx));
            rail.addView(tabs[i], portrait ? new LinearLayout.LayoutParams(0, px(76), 1f)
                    : new LinearLayout.LayoutParams(MATCH, px(88)));
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
        } else {
            v = launcherPanel();
        }
        content.addView(v, MATCH, MATCH);
    }

    // ---------------------------------------------------------------- tab Launcher

    View launcherPanel() {
        LinearLayout list = column();

        boolean def = HomeSwitch.isDefault(this);
        list.addView(row(getString(R.string.settings_home_title),
                getString(def ? R.string.settings_home_on : R.string.settings_home_off),
                def ? key(getString(R.string.settings_home_restore), false, () -> HomeSwitch.restore(this, prefs))
                        : key(getString(R.string.settings_home_make), true, () -> HomeSwitch.makeDefault(this, prefs,
                        () -> show(TAB_LAUNCHER, true)))));

        boolean twelve = Clock.twelve(prefs);
        Key[] fmt = new Key[2];
        for (int i = 0; i < 2; i++) {
            final boolean v = i == 1;
            fmt[i] = key(getString(v ? R.string.settings_clock_12 : R.string.settings_clock_24), v == twelve, () -> {
                prefs.edit().putBoolean("clock12", v).apply();
                show(TAB_LAUNCHER, true);
            });
        }
        list.addView(row(getString(R.string.settings_clock_title), getString(R.string.settings_clock_desc), fmt));

        int off = prefs.getInt("hijriOffset", 0);
        Key[] shift = new Key[5];
        for (int i = 0; i < 5; i++) {
            final int v = i - 2;
            shift[i] = key(v == 0 ? "0" : v > 0 ? "+" + v : "−" + (-v), v == off, () -> {
                prefs.edit().putInt("hijriOffset", v).apply();
                show(TAB_LAUNCHER, true);
            });
            shift[i].minWidth = px(56);
        }
        list.addView(row(getString(R.string.settings_hijri_title), getString(R.string.settings_hijri_desc,
                titleCase(Hijri.text(System.currentTimeMillis(), off))), shift));

        Key[] langs = new Key[Lang.CODES.length];
        for (int i = 0; i < langs.length; i++) {
            final String code = Lang.CODES[i];
            langs[i] = key(getString(Lang.EN.equals(code) ? R.string.lang_en : R.string.lang_id), code.equals(Lang.code), () -> {
                if (code.equals(Lang.saved(this))) return;
                Lang.save(this, code);
                recreate(); // seperti tema: home dan layar lain menyusul saat tampil lagi (Theme.stale)
            });
        }
        list.addView(row(getString(R.string.settings_lang_title), getString(R.string.settings_lang_desc), langs));

        String[] themeNames = getResources().getStringArray(R.array.theme_names);
        Key[] themes = new Key[Theme.COUNT];
        int cur = Theme.saved(this);
        for (int i = 0; i < themes.length; i++) {
            final int t = i;
            themes[i] = key(themeNames[i], t == cur, () -> {
                if (t == Theme.saved(this)) return;
                Theme.save(this, t);
                recreate(); // home dan layar lain menyusul saat tampil lagi (Theme.stale)
            });
        }
        list.addView(row(getString(R.string.settings_theme_title),
                getResources().getStringArray(R.array.theme_desc)[cur], themes));

        int tc = HomeApps.tileCount(prefs);
        Key[] counts = new Key[HomeApps.TILE_COUNTS.length];
        for (int i = 0; i < counts.length; i++) {
            final int n = HomeApps.TILE_COUNTS[i];
            counts[i] = key(String.valueOf(n), n == tc, () -> {
                prefs.edit().putInt("tiles", n).apply();
                show(TAB_LAUNCHER, true); // home dibangun ulang saat tampil lagi
            });
            counts[i].minWidth = px(56);
        }
        list.addView(row(getString(R.string.settings_tiles_title), getString(R.string.settings_tiles_desc), counts));

        int wm = Welcome.mode(prefs);
        list.addView(row(getString(R.string.settings_welcome_title), getString(R.string.settings_welcome_desc,
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

        boolean pa = PrayerAlarm.enabled(prefs);
        list.addView(row(getString(R.string.settings_prayer_title), getString(R.string.settings_prayer_desc),
                key(getString(R.string.settings_on), pa, () -> setPrayerAlert(true)),
                key(getString(R.string.settings_off), !pa, () -> setPrayerAlert(false)),
                key(getString(R.string.settings_prayer_test), false, () -> PrayerAlarm.notify(this, 3, System.currentTimeMillis()))));

        // Kunci layar: PIN 4 angka, gembok di home (ScreenLock). Ubah/hapus minta PIN lama.
        if (ScreenLock.hasPin(prefs)) {
            list.addView(row(getString(R.string.settings_lock_title), getString(R.string.settings_lock_desc_on),
                    key(getString(R.string.settings_lock_change), false, () -> ScreenLock.verify(this, prefs,
                            this::newPin, this::pinRemoved)),
                    key(getString(R.string.settings_lock_remove), false, () -> ScreenLock.verify(this, prefs, () -> {
                        ScreenLock.clear(prefs);
                        pinRemoved();
                    }, this::pinRemoved))));
        } else {
            list.addView(row(getString(R.string.settings_lock_title), getString(R.string.settings_lock_desc_off),
                    key(getString(R.string.settings_lock_set), false, this::newPin)));
        }

        list.addView(row(getString(R.string.settings_tiles_reset_title), getString(R.string.settings_tiles_reset_desc),
                key(getString(R.string.settings_tiles_reset), false, this::resetTiles)));

        list.addView(row(getString(R.string.settings_system_title), getString(R.string.settings_system_desc),
                key(getString(R.string.settings_system_open), false, () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception ignored) { }
                })));

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
                    show(TAB_LAUNCHER, true);
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
        show(TAB_LAUNCHER, true);
    }

    void setPrayerAlert(boolean on) {
        prefs.edit().putBoolean("prayerAlert", on).apply();
        PrayerAlarm.schedule(this);
        PrayerAlarm.allowOverlay(this);
        show(TAB_LAUNCHER, true);
    }

    void setWelcome(int mode) {
        prefs.edit().putInt("welcome", mode).apply();
        show(TAB_LAUNCHER, true);
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
            show(TAB_LAUNCHER, true);
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

    /** KlikBeam (BETA): cara menyambung, kode pairing, dan alamat; penerimanya BeamActivity. */
    View beamPanel() {
        LinearLayout list = column();
        String beamAddr = BeamActivity.addressText();
        list.addView(row(getString(R.string.settings_beam_title), getString(R.string.settings_beam_desc,
                        BeamActivity.codeText(this), beamAddr != null ? beamAddr : getString(R.string.beam_no_network)),
                key(getString(R.string.settings_beam_new_code), false, () -> {
                    BeamActivity.newCode(this);
                    show(TAB_BEAM, true);
                }),
                key(getString(R.string.settings_beam_open), false,
                        () -> startActivity(new Intent(this, BeamActivity.class)))));
        return scroll(list);
    }

    /** Bangun ulang tab Launcher tanpa melompat ke atas (baris kunci layar ada di bawah). */
    void rebuildKeepScroll() {
        View old = content.getChildAt(0);
        int y = old instanceof ScrollView ? old.getScrollY() : 0;
        show(TAB_LAUNCHER, true);
        View now = content.getChildAt(0);
        if (now instanceof ScrollView) now.post(() -> now.scrollTo(0, y));
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
