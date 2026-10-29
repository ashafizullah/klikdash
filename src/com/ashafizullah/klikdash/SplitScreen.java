package com.ashafizullah.klikdash;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.WindowInsets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layar terbagi: dua aplikasi berdampingan (mis. YouTube kiri, Maps kanan), dipilih dari panel cepat. Aplikasi biasa
 * tidak boleh memindahkan jendela aplikasi lain, jadi semuanya lewat perintah {@code am} di ADB lokal ({@link Tweaks});
 * tanpa ADB fiturnya tidak jalan. Caranya beda per versi Android (diuji di emulator 7, 10, dan 16):
 * <ul>
 * <li>Android 7–8 ({@link #DOCK_N}): split screen bawaan; task kiri dipindah ke docked stack (id tetap 3), task kanan ke
 *     stack layar penuh (id 1) yang jadi sisi kanan.</li>
 * <li>Android 9–11 ({@link #DOCK_P}): split screen bawaan, tapi id stack tidak tetap dan {@code am start --windowingMode 3}
 *     tidak memindahkan task yang sudah berjalan. Bila aplikasi kiri sudah berjalan, {@link SplitAnchor} dibuka dulu
 *     sebagai sisi kiri, lalu task-nya dipindah ke stack jangkar itu.</li>
 * <li>Android 12+ ({@link #FREEFORM}): split bawaan dipegang SystemUI dan tidak bisa diminta lewat {@code am}; dua
 *     jendela freeform ditaruh berdampingan ({@code am task resize}). Sebagian firmware baru mengizinkan freeform
 *     setelah dinyalakan ulang.</li>
 * </ul>
 * Pembatas di tengah ({@link SplitBar}) digeser untuk mengubah ukuran (ke tepi = tutup sisi itu) dan diketuk untuk tombol
 * tutup kiri/kanan dan tukar. Selama layar terbagi aktif, {@code am stack list} dibaca tiap {@link #POLL_MS} untuk
 * letak pembatas dan untuk tahu bila layar terbagi sudah berakhir (mis. aplikasinya ditutup dengan Kembali).
 * {@code am} di Android 7+ memanggil {@code cmd activity}, tanpa memulai VM baru, jadi cukup ringan untuk itu.
 */
final class SplitScreen {
    static final int DOCK_N = 1, DOCK_P = 2, FREEFORM = 3;
    static final int MODE = Build.VERSION.SDK_INT >= 31 ? FREEFORM : Build.VERSION.SDK_INT >= 28 ? DOCK_P : DOCK_N;
    /** Batas ukuran sisi pertama; pembatas dilepas lebih ke tepi dari {@link #CLOSE_EDGE} menutup sisi itu. */
    static final float MIN_RATIO = .25f, MAX_RATIO = .75f, CLOSE_EDGE = .12f;
    private static final long POLL_MS = 3000;
    private static final int DOCKED_N = 3, FULLSCREEN_N = 1;

    private SplitScreen() { }

    // ---------------------------------------------------------------- isi am stack list

    /** Satu stack (Android 11 ke bawah) atau root task (12+). Urutan daftar: paling depan dulu. */
    static final class Stack {
        int id;
        String mode = "", type = "";
        final Rect bounds = new Rect();
        /** Dari bawah ke atas: task terakhir yang tampil. */
        final List<Task> tasks = new ArrayList<>();

        Task top() { return tasks.isEmpty() ? null : tasks.get(tasks.size() - 1); }
    }

    static final class Task {
        int id;
        /** Komponen dasar task, mis. com.google.android.youtube/com.google...WatchWhileActivity. */
        String cn, pkg;
        Stack stack;
    }

    private static final Pattern STACK = Pattern.compile("^(?:Stack|RootTask) id=(\\d+) bounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");
    private static final Pattern TASK = Pattern.compile("^\\s+taskId=(\\d+): (([^/\\s]+)/\\S+) ");
    private static final Pattern MODE_RE = Pattern.compile("mWindowingMode=([a-z-]+)");
    private static final Pattern TYPE_RE = Pattern.compile("mActivityType=([a-z]+)");

    static List<Stack> parse(String out) {
        List<Stack> list = new ArrayList<>();
        Stack cur = null;
        for (String line : out.split("\n")) {
            Matcher m = STACK.matcher(line);
            if (m.find()) {
                cur = new Stack();
                cur.id = Integer.parseInt(m.group(1));
                cur.bounds.set(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                        Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
                list.add(cur);
                continue;
            }
            if (cur == null) continue;
            m = TASK.matcher(line);
            if (m.find()) {
                Task t = new Task();
                t.id = Integer.parseInt(m.group(1));
                t.cn = m.group(2);
                t.pkg = m.group(3);
                t.stack = cur;
                cur.tasks.add(t);
                continue;
            }
            if (line.contains("winConfig=") && cur.mode.isEmpty()) { // baris configuration stack (Android 9+)
                m = MODE_RE.matcher(line);
                if (m.find()) cur.mode = m.group(1);
                m = TYPE_RE.matcher(line);
                if (m.find()) cur.type = m.group(1);
            }
        }
        return list;
    }

    /** Task aplikasi {@code pkg} yang paling depan, atau null bila belum berjalan. */
    static Task find(List<Stack> stacks, String pkg) {
        for (Stack s : stacks) {
            for (int i = s.tasks.size() - 1; i >= 0; i--) if (s.tasks.get(i).pkg.equals(pkg)) return s.tasks.get(i);
        }
        return null;
    }

    static Task findId(List<Stack> stacks, int id) {
        for (Stack s : stacks) for (Task t : s.tasks) if (t.id == id) return t;
        return null;
    }

    /** Sisi pertama (kiri/atas) split bawaan: docked stack. */
    static Stack docked(List<Stack> stacks) {
        for (Stack s : stacks) {
            if (MODE == DOCK_N ? s.id == DOCKED_N : s.mode.equals("split-screen-primary")) return s.tasks.isEmpty() ? null : s;
        }
        return null;
    }

    /** Sisi kedua split bawaan yang sedang tampil (stack sekunder paling depan). */
    static Stack secondary(List<Stack> stacks) {
        for (Stack s : stacks) {
            if (s.tasks.isEmpty()) continue;
            if (MODE == DOCK_N ? s.id == FULLSCREEN_N : s.mode.equals("split-screen-secondary")) return s;
        }
        return null;
    }

    private static boolean home(Stack s) {
        return s.type.equals("home") || s.type.equals("recents") || (MODE == DOCK_N && s.id == 0);
    }

    // ---------------------------------------------------------------- keadaan

    /** Hasil satu pembacaan: dua sisi yang tampil, atau null bila layar terbagi sudah berakhir. */
    static final class Pair {
        Task first, second;
        final Rect a = new Rect(), b = new Rect();
        /** Kedua sisi di depan (tidak tertutup home atau aplikasi layar penuh). */
        boolean front;

        /** Atas-bawah (layar portrait), bukan kiri-kanan. */
        boolean stacked() { return a.bottom <= b.top + 2; }
    }

    private static Context app;
    private static Handler worker, main;
    private static SplitBar bar;
    /** Layar terbagi yang dibuka KlikDash masih berlangsung. */
    static volatile boolean active;
    /** Home KlikDash di depan: pembatas disembunyikan. */
    private static volatile boolean homeFront;
    /** Freeform: task dan komponen tiap sisi (untuk dibuka layar penuh saat sisi lain ditutup). */
    private static int firstTask = -1, secondTask = -1;
    private static String firstCn, secondCn;
    /** Area layar untuk jendela freeform: layar tanpa bilah status/navigasi. */
    private static final Rect area = new Rect();
    private static java.lang.ref.WeakReference<Activity> owner = new java.lang.ref.WeakReference<>(null);

    private static void init(Context c) {
        if (worker != null) return;
        app = c.getApplicationContext();
        HandlerThread t = new HandlerThread("split");
        t.start();
        worker = new Handler(t.getLooper());
        main = new Handler(Looper.getMainLooper());
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("split", Context.MODE_PRIVATE); }

    /** Home tampil (onResume) atau ditinggalkan (onPause). */
    static void home(boolean front) {
        homeFront = front;
        if (bar != null && front) bar.hide();
        if (!front && active) pollSoon();
    }

    // ---------------------------------------------------------------- buka

    /**
     * Buka {@code left} dan {@code right} berdampingan. Perintah pertama boleh memunculkan dialog izin ADB; tanpa ADB,
     * pesan yang sama dengan fitur ADB lain.
     */
    static void open(Activity a, ComponentName left, ComponentName right) {
        init(a);
        owner = new java.lang.ref.WeakReference<>(a);
        measure(a);
        // Sekali saja tidak apa-apa diulang: aplikasi yang menolak split tetap bisa dibagi, izin pembatas di atas aplikasi lain.
        String prep = "settings put global force_resizable_activities 1; cmd appops set " + a.getPackageName()
                + " SYSTEM_ALERT_WINDOW allow" + (MODE == FREEFORM ? "; settings put global enable_freeform_support 1" : "");
        Tweaks.askAsync(prep, () -> Notice.info(a, a.getString(R.string.home_adb_allow)), r -> {
            if (r != Tweaks.DONE) {
                a.runOnUiThread(() -> HelpVideo.fail(a, a.getString(HomeSwitch.failure(a, r)), HelpVideo.forAdb(a, r)));
                return;
            }
            worker.post(() -> {
                int err;
                try {
                    err = MODE == FREEFORM ? openFreeform(left, right) : MODE == DOCK_P ? openDockP(left, right) : openDockN(left, right);
                } catch (IOException e) {
                    err = R.string.split_unsupported;
                }
                if (err != 0) {
                    fail(err);
                    return;
                }
                active = true;
                hintOnce();
                settle(); // pembatas langsung tampil
            });
        });
    }

    /** Layar tanpa bilah sistem, untuk ukuran jendela freeform. */
    @SuppressWarnings("deprecation")
    private static void measure(Activity a) {
        Point p = new Point();
        a.getWindowManager().getDefaultDisplay().getRealSize(p);
        area.set(0, 0, p.x, p.y);
        WindowInsets in = a.getWindow().getDecorView().getRootWindowInsets();
        if (in != null) {
            area.left += in.getStableInsetLeft();
            area.top += in.getStableInsetTop();
            area.right -= in.getStableInsetRight();
            area.bottom -= in.getStableInsetBottom();
        }
    }

    private static int openDockN(ComponentName l, ComponentName r) throws IOException {
        List<Stack> s = list();
        Task tl = find(s, l.getPackageName());
        sh(tl != null ? "am stack movetask " + tl.id + " " + DOCKED_N + " true" : "am start --stack " + DOCKED_N + " " + intent(l));
        // Android 7 membuka layar Terbaru di sisi kanan sesaat setelah split dimulai; aplikasi kanan dibuka sesudahnya
        // supaya tidak tertutup olehnya.
        pause(600);
        s = list();
        if (docked(s) == null) return R.string.split_unsupported;
        Task tr = find(s, r.getPackageName());
        if (tr == null) sh("am start --stack " + FULLSCREEN_N + " " + intent(r));
        else if (tr.stack.id == FULLSCREEN_N) sh("am start " + intent(r)); // movetask ke stack yang sama tidak berbuat apa-apa
        else sh("am stack movetask " + tr.id + " " + FULLSCREEN_N + " true");
        return 0;
    }

    /**
     * Perintah {@code am} selesai setelah keadaan task berubah, jadi tiap langkah langsung disusul langkah berikutnya
     * tanpa jeda; daftar stack dibaca dalam koneksi ADB yang sama ({@link #then}).
     */
    private static int openDockP(ComponentName l, ComponentName r) throws IOException {
        List<Stack> s = list();
        Stack p = docked(s);
        Task tl = find(s, l.getPackageName());
        if (tl == null) {
            s = then("am start --windowingMode 3 " + intent(l));
        } else if (p == null) {
            p = anchor(3);
            if (p == null) return R.string.split_unsupported;
            s = then("am stack move-task " + tl.id + " " + p.id + " true");
        } else if (p.top() != tl) {
            s = then("am stack move-task " + tl.id + " " + p.id + " true");
        }
        p = docked(s);
        if (p == null) return R.string.split_unsupported;
        Task tr = find(s, r.getPackageName());
        if (tr == null) {
            sh("am start --windowingMode 4 " + intent(r));
        } else if (tr.stack == p) { // mis. dulu di kiri: pindah ke stack sekunder
            Stack sec = secondary(s);
            if (sec == null) sec = anchor(4);
            if (sec != null) sh("am stack move-task " + tr.id + " " + sec.id + " true");
        } else {
            sh("am start " + intent(r)); // sudah di sisi kanan: bawa ke depan
        }
        return 0;
    }

    /**
     * Buka {@link SplitAnchor} di sisi {@code windowingMode} (3 = kiri, 4 = kanan) dan kembalikan stack-nya, supaya
     * task yang sudah berjalan bisa dipindah ke sana. Jangkarnya menutup sendiri begitu tertutup task itu.
     */
    private static Stack anchor(int windowingMode) throws IOException {
        String cn = app.getPackageName() + "/" + SplitAnchor.class.getName();
        List<Stack> s = then("am start --windowingMode " + windowingMode + " -n " + cn);
        for (int i = 0; i < 20; i++) {
            for (Stack st : s) for (Task t : st.tasks) if (t.cn.equals(cn)) return st;
            pause(100);
            s = list();
        }
        return null;
    }

    /** Freeform: tiap jendela langsung diberi ukurannya begitu dibuka, supaya tidak tampil dulu di ukuran bawaan. */
    private static int openFreeform(ComponentName l, ComponentName r) throws IOException {
        float ratio = prefs(app).getFloat("ratio", .5f);
        boolean side = area.width() >= area.height();
        Task tl = find(then("am start --windowingMode 5 " + intent(l)), l.getPackageName());
        if (tl == null) return R.string.split_unsupported;
        if (!tl.stack.mode.equals("freeform")) return R.string.split_need_reboot;
        sh("am task resize " + tl.id + " " + bounds(ratio, side, true));
        Task tr = find(then("am start --windowingMode 5 " + intent(r)), r.getPackageName());
        if (tr == null) return R.string.split_unsupported;
        if (!tr.stack.mode.equals("freeform")) return R.string.split_need_reboot;
        sh("am task resize " + tr.id + " " + bounds(ratio, side, false));
        firstTask = tl.id;
        secondTask = tr.id;
        firstCn = l.flattenToString();
        secondCn = r.flattenToString();
        return 0;
    }

    /**
     * Freeform: aplikasi pasangan layar terbagi dibuka lagi dari home (ubin atau laci). Task-nya masih freeform, jadi
     * tanpa ini ia muncul sendirian setengah layar di atas home; dibuka bersama pasangannya di tempat semula. true bila
     * ditangani di sini.
     */
    static boolean reopen(ComponentName cn) {
        if (!active || MODE != FREEFORM || firstCn == null) return false;
        String pkg = cn.getPackageName();
        ComponentName f = ComponentName.unflattenFromString(firstCn), s = ComponentName.unflattenFromString(secondCn);
        if (!pkg.equals(f.getPackageName()) && !pkg.equals(s.getPackageName())) return false;
        worker.post(() -> {
            try {
                sh("am start --windowingMode 5 " + intent(f) + "; am start --windowingMode 5 " + intent(s));
            } catch (IOException ignored) { }
            pollSoon();
        });
        return true;
    }

    // ---------------------------------------------------------------- dari pembatas

    /** Pembatas dilepas di {@code ratio} (bagian sisi pertama). */
    static void resize(float ratio) {
        if (!active) return;
        worker.post(() -> {
            Pair p = read();
            if (p == null) return;
            if (ratio < CLOSE_EDGE || ratio > 1 - CLOSE_EDGE) {
                close(p, ratio > .5f);
                return;
            }
            float r = Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
            prefs(app).edit().putFloat("ratio", r).apply();
            try {
                if (MODE == FREEFORM) {
                    resizeFreeform(r, !p.stacked());
                } else {
                    Rect all = new Rect(p.a);
                    all.union(p.b);
                    Rect d = new Rect(all);
                    if (p.stacked()) d.bottom = all.top + Math.round(all.height() * r);
                    else d.right = all.left + Math.round(all.width() * r);
                    String b = d.left + " " + d.top + " " + d.right + " " + d.bottom;
                    sh("am stack resize-docked-stack " + b + " " + b);
                }
            } catch (IOException ignored) { }
            settle();
        });
    }

    private static void resizeFreeform(float r, boolean sideBySide) throws IOException {
        sh("am task resize " + firstTask + " " + bounds(r, sideBySide, true) + "; am task resize " + secondTask + " "
                + bounds(r, sideBySide, false));
    }

    /** Batas jendela freeform sisi pertama atau kedua, "kiri atas kanan bawah". */
    private static String bounds(float r, boolean sideBySide, boolean first) {
        Rect b = new Rect(area);
        int cut = sideBySide ? area.left + Math.round(area.width() * r) : area.top + Math.round(area.height() * r);
        if (sideBySide) { if (first) b.right = cut; else b.left = cut; }
        else { if (first) b.bottom = cut; else b.top = cut; }
        return b.left + " " + b.top + " " + b.right + " " + b.bottom;
    }

    /** Tombol ✕: tutup sisi pertama ({@code keepFirst} false) atau kedua. */
    static void close(boolean keepFirst) {
        if (!active) return;
        worker.post(() -> {
            Pair p = read();
            if (p != null) close(p, keepFirst);
        });
    }

    /**
     * Split bawaan: semua task sisi pertama dipindah ke stack sisi kedua, di atas (sisi pertama tetap) atau di bawahnya;
     * stack pertama kosong sehingga Android mengakhiri split, seperti pembatas sistem digeser ke tepi. Sisi yang ditutup
     * tetap berjalan di belakang. Freeform: task yang ditutup dihapus, yang tersisa dibuka layar penuh.
     */
    private static void close(Pair p, boolean keepFirst) {
        active = false;
        main.post(() -> { if (bar != null) bar.hide(); });
        try {
            if (MODE == FREEFORM) {
                Task gone = keepFirst ? p.second : p.first;
                String keep = keepFirst ? firstCn : secondCn;
                sh("am stack remove " + gone.stack.id + "; am start --windowingMode 1 " + intent(ComponentName.unflattenFromString(keep)));
                return;
            }
            int target = MODE == DOCK_N ? FULLSCREEN_N : p.second.stack.id;
            String verb = MODE == DOCK_N ? "am stack movetask " : "am stack move-task ";
            StringBuilder cmd = new StringBuilder();
            for (Task t : p.first.stack.tasks) { // mis. jangkar yang belum tertutup
                if (t != p.first) cmd.append(verb).append(t.id).append(' ').append(target).append(" false; ");
            }
            cmd.append(verb).append(p.first.id).append(' ').append(target).append(keepFirst ? " true" : " false");
            sh(cmd.toString());
        } catch (IOException ignored) { }
    }

    /**
     * Tombol TUTUP SEMUA: akhiri layar terbagi dan kembali ke home. Split bawaan: seperti menutup sisi kiri (keduanya
     * tetap berjalan di belakang, seperti tombol Home); freeform: kedua task dihapus.
     */
    static void closeAll() {
        if (!active) return;
        worker.post(() -> {
            Pair p = read();
            if (p == null) return;
            try {
                if (MODE == FREEFORM) {
                    active = false;
                    main.post(() -> { if (bar != null) bar.hide(); });
                    sh("am stack remove " + p.first.stack.id + "; am stack remove " + p.second.stack.id);
                } else {
                    close(p, false);
                }
                sh("am start -a android.intent.action.MAIN -c android.intent.category.HOME"); // input keyevent memulai VM, lambat
            } catch (IOException ignored) { }
        });
    }

    /** Tombol ⇄: tukar sisi. */
    static void swap() {
        if (!active) return;
        worker.post(() -> {
            Pair p = read();
            if (p == null) return;
            try {
                if (MODE == FREEFORM) {
                    int t = firstTask; firstTask = secondTask; secondTask = t;
                    String c = firstCn; firstCn = secondCn; secondCn = c;
                    resizeFreeform(prefs(app).getFloat("ratio", .5f), !p.stacked());
                } else if (MODE == DOCK_N) {
                    // Android 7 menampilkan layar Terbaru di sisi kanan saat docked stack berganti isi: bawa task kanan
                    // yang baru ke depan.
                    sh("am stack movetask " + p.second.id + " " + DOCKED_N + " true; am stack movetask " + p.first.id + " "
                            + FULLSCREEN_N + " true; am start " + intent(ComponentName.unflattenFromString(p.first.cn)));
                } else {
                    // Sisi kanan bisa kosong setelah tasknya dipindah ke kiri: jangkar dibuka dulu sebagai stack kanan baru.
                    Stack a = anchor(4);
                    if (a == null) return;
                    sh("am stack move-task " + p.second.id + " " + p.first.stack.id + " true; am stack move-task "
                            + p.first.id + " " + a.id + " true");
                }
            } catch (IOException ignored) { }
            settle();
        });
    }

    // ---------------------------------------------------------------- pemantauan

    private static final Runnable poll = SplitScreen::pollNow;

    /**
     * Setelah membuka, menukar, atau mengubah ukuran: pembatas langsung tampil, lalu dibaca lagi sebentar kemudian karena
     * Android masih menyelesaikan ukuran sisi kedua.
     */
    private static void settle() {
        pollNow();
        if (!active) return;
        worker.removeCallbacks(poll);
        worker.postDelayed(poll, 500);
    }

    private static void pollSoon() {
        worker.removeCallbacks(poll);
        worker.postDelayed(poll, 150);
    }

    /** Di thread split: baca keadaan, tampilkan atau sembunyikan pembatas, jadwalkan pembacaan berikutnya. */
    private static void pollNow() {
        worker.removeCallbacks(poll);
        if (!active) return;
        Pair p;
        try {
            p = readOrThrow();
        } catch (IOException e) {
            worker.postDelayed(poll, POLL_MS); // ADB sesaat tidak bisa dihubungi
            return;
        }
        if (p == null) { // berakhir di luar KlikDash (Kembali, pembatas sistem, aplikasi ditutup)
            active = false;
            main.post(() -> { if (bar != null) bar.hide(); });
            return;
        }
        boolean show = p.front && !homeFront;
        main.post(() -> {
            if (!show) {
                if (bar != null) bar.hide();
                return;
            }
            if (bar == null) bar = new SplitBar(app);
            bar.show(p);
        });
        worker.postDelayed(poll, show ? POLL_MS : 3 * POLL_MS); // tersembunyi (home di depan): lebih jarang
    }

    private static Pair read() {
        try {
            return readOrThrow();
        } catch (IOException e) {
            return null;
        }
    }

    private static Pair readOrThrow() throws IOException {
        List<Stack> s = list();
        Pair p = new Pair();
        if (MODE == FREEFORM) {
            p.first = findId(s, firstTask);
            p.second = findId(s, secondTask);
            if (p.first == null || p.second == null) {
                Task left = p.first != null ? p.first : p.second;
                String cn = p.first != null ? firstCn : secondCn;
                // Satu sisi ditutup di aplikasinya sendiri: sisanya jangan tinggal setengah layar.
                if (left != null && left.stack.mode.equals("freeform")) {
                    sh("am start --windowingMode 1 " + intent(ComponentName.unflattenFromString(cn)));
                }
                return null;
            }
            if (!p.first.stack.mode.equals("freeform") || !p.second.stack.mode.equals("freeform")) return null;
            p.a.set(p.first.stack.bounds);
            p.b.set(p.second.stack.bounds);
            int i1 = s.indexOf(p.first.stack), i2 = s.indexOf(p.second.stack);
            p.front = true;
            for (int i = 0; i < Math.max(i1, i2); i++) if (!s.get(i).mode.equals("freeform") && !s.get(i).tasks.isEmpty()) p.front = false;
            return p;
        }
        Stack d = docked(s), sec = secondary(s);
        if (d == null) return null;
        if (sec == null) { // sisi kedua kosong (mis. layar Terbaru): tidak ada yang bisa ditutup atau ditukar
            p.front = false;
            p.first = p.second = d.top();
            return p;
        }
        p.first = d.top();
        p.second = sec.top();
        p.a.set(d.bounds);
        p.b.set(sec.bounds);
        // Home di depan saat split: Android 7 menaruhnya di sisi kedua, Android 10 di atas semua (sisi kiri diperkecil di
        // tepi). Keduanya: stack home masuk daftar sebelum stack sekunder. (Android 7 tidak mengurutkan docked stack.)
        p.front = true;
        for (int i = 0; i < s.indexOf(sec); i++) if (home(s.get(i))) p.front = false;
        return p;
    }

    // ---------------------------------------------------------------- pembantu

    private static List<Stack> list() throws IOException { return parse(sh("am stack list")); }

    /** Jalankan {@code cmd} lalu baca daftar stack, dalam satu koneksi ADB. */
    private static List<Stack> then(String cmd) throws IOException { return parse(sh(cmd + "; am stack list")); }

    private static String sh(String cmd) throws IOException { return Tweaks.exec(cmd); }

    /** Seperti ketukan ikon launcher: membuka task yang sudah ada apa adanya. Komponen dikutip ($ di nama kelas). */
    private static String intent(ComponentName cn) {
        return "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n '" + cn.flattenToString() + "'";
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) { }
    }

    private static void fail(int res) {
        main.post(() -> {
            Activity a = owner.get();
            String msg = Lang.wrap(app).getString(res);
            if (!Notice.overlay(app, Notice.FAIL, null, msg) && a != null) Notice.fail(a, msg);
        });
    }

    /** Tiga kali pertama: cara menutup dan mengubah ukuran. */
    private static void hintOnce() {
        SharedPreferences p = prefs(app);
        int n = p.getInt("hints", 0);
        if (n >= 3) return;
        p.edit().putInt("hints", n + 1).apply();
        main.postDelayed(() -> Notice.overlay(app, Notice.INFO, null, Lang.wrap(app).getString(R.string.split_hint)), 1200);
    }
}
