package com.ashafizullah.klikdash;

import android.app.Activity;
import android.os.Bundle;

/**
 * Jangkar sesaat untuk {@link SplitScreen} di Android 9–11: dibuka lewat {@code am start --windowingMode 3/4} supaya
 * ada stack split tempat task aplikasi yang sudah berjalan bisa dipindah (Android tidak memindahkan task lama ke split
 * lewat {@code am start}). Transparan, tanpa animasi, dan menutup sendiri begitu tertutup task itu (onStop), atau
 * setelah 5 detik bila tidak ada yang dipindah. Diekspor karena dibuka dari shell ADB.
 */
public final class SplitAnchor extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        overridePendingTransition(0, 0);
        getWindow().getDecorView().postDelayed(this::finish, 5000);
    }

    @Override protected void onStop() {
        super.onStop();
        finish();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
