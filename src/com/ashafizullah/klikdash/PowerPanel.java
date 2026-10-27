package com.ashafizullah.klikdash;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.FAINT;
import static com.ashafizullah.klikdash.HomeActivity.INK;
import static com.ashafizullah.klikdash.HomeActivity.LINE;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;
import static com.ashafizullah.klikdash.SettingsActivity.MATCH;
import static com.ashafizullah.klikdash.SettingsActivity.WRAP;

/**
 * Tab Hemat Daya: larang aplikasi pihak ketiga berjalan di latar (appops RUN_ANY_IN_BACKGROUND/RUN_IN_BACKGROUND
 * = ignore). Aplikasinya tidak dihapus dan tetap bisa dibuka. Perintah dijalankan lewat ADB perangkat sendiri.
 */
final class PowerPanel {

    /** Tidak pernah ditampilkan: dibutuhkan mobil atau navigasi tetap jalan di latar. */
    static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "com.zjinnova.zlink", "com.google.android.apps.maps", "com.waze",
            "com.google.android.gms", "com.android.vending"));

    static final class Item {
        String pkg, label;
        Drawable icon;
        boolean restricted, running;
    }

    final SettingsActivity a;
    final View view;
    final List<Item> items = new ArrayList<>();
    final ListView list;
    final Adapter adapter;
    final TextView count, message;
    boolean busy;

    PowerPanel(SettingsActivity a) {
        this.a = a;
        LinearLayout root = a.column();

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        count = a.label("");
        head.addView(count, new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(a.key(a.getString(a.narrow ? R.string.power_restore : R.string.power_restore_all), false, this::restoreAll),
                new LinearLayout.LayoutParams(WRAP, a.px(52)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(MATCH, WRAP);
        hl.topMargin = a.px(20);
        root.addView(head, hl);

        TextView sub = a.paragraph(a.getString(R.string.power_desc));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, WRAP);
        sl.topMargin = a.px(12);
        sl.bottomMargin = a.px(16);
        root.addView(sub, sl);
        root.addView(a.rule(), a.ruleParams(true, 0, 0));

        FrameLayout body = new FrameLayout(a);
        list = new ListView(a);
        list.setDivider(new ColorDrawable(LINE));
        list.setDividerHeight(a.px(1));
        list.setSelector(new ColorDrawable(0));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setVerticalScrollBarEnabled(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> toggle(items.get(pos)));
        body.addView(list, MATCH, MATCH);
        message = a.text("", a.fMonoBold, 14, AMBER);
        message.setLetterSpacing(0.25f);
        message.setGravity(Gravity.CENTER);
        message.setSingleLine(false);
        Theme.fill(message); // menutupi daftar
        body.addView(message, MATCH, MATCH);
        root.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        view = root;

        refresh();
    }

    // ---------------------------------------------------------------- data

    /** Aplikasi pihak ketiga yang bisa dibatasi, beserta status dari appops dan pidof. */
    void refresh() {
        busy = true;
        showMessage(a.getString(R.string.power_checking));
        new Thread(() -> {
            PackageManager pm = a.getPackageManager();
            List<Item> found = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (ai.packageName.equals(a.getPackageName()) || PROTECTED.contains(ai.packageName)) continue;
                if (ai.packageName.contains(".nwd.")) continue;
                Item it = new Item();
                it.pkg = ai.packageName;
                it.label = String.valueOf(ai.loadLabel(pm)).trim();
                it.icon = ai.loadIcon(pm);
                found.add(it);
            }
            // RUN_ANY_IN_BACKGROUND baru ada di Android 9; di bawahnya status blokir dibaca dari RUN_IN_BACKGROUND.
            String op = android.os.Build.VERSION.SDK_INT >= 28 ? "RUN_ANY_IN_BACKGROUND" : "RUN_IN_BACKGROUND";
            StringBuilder cmd = new StringBuilder();
            for (Item it : found) {
                cmd.append("echo \"").append(it.pkg).append("|$(cmd appops get ").append(it.pkg)
                        .append(" ").append(op).append(")|$(pidof ").append(it.pkg).append(")\";");
            }
            String out;
            try {
                out = found.isEmpty() ? "" : Tweaks.exec(cmd.toString());
            } catch (Exception e) {
                a.runOnUiThread(() -> {
                    busy = false;
                    if (!Tweaks.debuggingOn(a)) {
                        showMessage(a.getString(R.string.power_need_adb) + a.getString(R.string.power_help_video));
                        message.setOnClickListener(v -> HelpVideo.fail(a, a.getString(R.string.home_need_adb), HelpVideo.USB));
                    } else if (AdbWifiService.supported()) {
                        // Android 11+: port 5555 bisa dibuka tanpa komputer lewat Wireless debugging.
                        showMessage(a.getString(R.string.power_need_adb_port_wifi));
                        message.setOnClickListener(v -> AdbWifiService.offer(a, Tweaks.NO_ADB, null, true, r -> refresh()));
                    } else {
                        showMessage(a.getString(R.string.power_need_adb_port));
                    }
                });
                return;
            }
            for (String line : out.split("\n")) {
                String[] f = line.trim().split("\\|", -1);
                if (f.length < 3) continue;
                for (Item it : found) {
                    if (!it.pkg.equals(f[0])) continue;
                    it.restricted = f[1].contains("ignore");
                    it.running = !f[2].trim().isEmpty();
                }
            }
            // Yang sedang jalan di latar dan belum dibatasi paling atas, lalu urut nama.
            found.sort((x, y) -> {
                int a = (x.running && !x.restricted) ? 0 : 1, c = (y.running && !y.restricted) ? 0 : 1;
                return a != c ? a - c : x.label.compareToIgnoreCase(y.label);
            });
            a.runOnUiThread(() -> {
                busy = false;
                items.clear();
                items.addAll(found);
                adapter.notifyDataSetChanged();
                updateCount();
                if (items.isEmpty()) showMessage(a.getString(R.string.power_no_apps)); else message.setVisibility(View.GONE);
            });
        }, "power-refresh").start();
    }

    void toggle(Item it) {
        if (busy) return;
        busy = true;
        String p = it.pkg;
        String cmd = it.restricted
                ? "cmd appops set " + p + " RUN_ANY_IN_BACKGROUND allow; cmd appops set " + p + " RUN_IN_BACKGROUND allow"
                : "cmd appops set " + p + " RUN_ANY_IN_BACKGROUND ignore; cmd appops set " + p + " RUN_IN_BACKGROUND ignore; "
                  + "am force-stop " + p;
        run(cmd);
    }

    void restoreAll() {
        if (busy) return;
        StringBuilder cmd = new StringBuilder();
        for (Item it : items) {
            if (!it.restricted) continue;
            cmd.append("cmd appops set ").append(it.pkg).append(" RUN_ANY_IN_BACKGROUND allow; cmd appops set ")
                    .append(it.pkg).append(" RUN_IN_BACKGROUND allow; ");
        }
        if (cmd.length() == 0) return;
        busy = true;
        run(cmd.toString());
    }

    void run(String cmd) {
        new Thread(() -> {
            try {
                Tweaks.exec(cmd);
            } catch (Exception ignored) { }
            a.runOnUiThread(this::refresh);
        }, "power-apply").start();
    }

    void updateCount() {
        int r = 0;
        for (Item it : items) if (it.restricted) r++;
        count.setText(a.getString(R.string.power_count,
                a.getResources().getQuantityString(R.plurals.power_apps, items.size(), items.size()), r));
    }

    void showMessage(String s) {
        message.setOnClickListener(null);
        message.setClickable(false);
        message.setText(s);
        message.setVisibility(View.VISIBLE);
    }

    /** Satu baris aplikasi: ikon, nama, status, dan sakelar di kanan. Ketuk di mana saja untuk mengubah. */
    class Row extends View {
        Item item;
        boolean down;
        final Paint fill = new Paint(), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG), status = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                sw = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final String restricted = a.getString(R.string.power_restricted), restrict = a.getString(R.string.power_restrict),
                stRestricted = a.getString(R.string.power_st_restricted), stRunning = a.getString(R.string.power_st_running),
                stAllowed = a.getString(R.string.power_st_allowed);

        Row() {
            super(a);
            name.setTypeface(a.fBold);
            name.setTextSize(a.sp(24));
            name.setLetterSpacing(0.03f);
            status.setTypeface(a.fMono);
            status.setTextSize(a.sp(10));
            status.setLetterSpacing(0.15f);
            sw.setTypeface(a.fMonoBold);
            sw.setTextSize(a.sp(12));
            sw.setLetterSpacing(0.2f);
            sw.setTextAlign(Paint.Align.CENTER);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(a.px(1));
            stroke.setColor(LINE);
            setLayoutParams(new AbsListView.LayoutParams(MATCH, a.px(84)));
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (isPressed() != down) { down = isPressed(); invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            if (item == null) return;
            int w = getWidth(), h = getHeight(), icon = a.px(48), x = a.px(16);
            if (down) {
                fill.setColor(PANEL);
                c.drawRect(0, 0, w, h, fill);
            }
            item.icon.setBounds(x, (h - icon) / 2, x + icon, (h + icon) / 2);
            item.icon.draw(c);

            int sww = a.narrow ? a.px(130) : a.px(180), swh = a.px(48), swx = w - a.px(16) - sww, swy = (h - swh) / 2;
            fill.setColor(item.restricted ? AMBER : PANEL);
            c.drawRect(swx, swy, swx + sww, swy + swh, fill);
            if (!item.restricted) {
                float s = stroke.getStrokeWidth() / 2f;
                c.drawRect(swx + s, swy + s, swx + sww - s, swy + swh - s, stroke);
            }
            sw.setColor(item.restricted ? INK : TEXT);
            Paint.FontMetrics fm = sw.getFontMetrics();
            c.drawText(item.restricted ? restricted : restrict, swx + sww / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, sw);

            float tx = x + icon + a.px(20), avail = swx - tx - a.px(20);
            name.setColor(TEXT);
            CharSequence n = TextUtils.ellipsize(item.label.toUpperCase(Lang.locale()), name, avail, TextUtils.TruncateAt.END);
            c.drawText(n, 0, n.length(), tx, a.px(40), name);
            String st = item.restricted ? stRestricted : item.running ? stRunning : stAllowed;
            status.setColor(item.running && !item.restricted ? AMBER : item.restricted ? DIM : FAINT);
            CharSequence ss = TextUtils.ellipsize(st + "  ·  " + item.pkg, status, avail, TextUtils.TruncateAt.END);
            c.drawText(ss, 0, ss.length(), tx, a.px(62), status);
        }
    }

    class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            Row r = v instanceof Row ? (Row) v : new Row();
            r.item = items.get(i);
            r.invalidate();
            return r;
        }
    }
}
