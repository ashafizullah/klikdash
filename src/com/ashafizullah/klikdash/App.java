package com.ashafizullah.klikdash;

import android.app.Application;

/** Proses KlikDash: pasang penangkap crash sebelum aktivitas atau layanan mana pun jalan ({@link ErrorLog}). */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ErrorLog.install(this);
        ErrorLog.flush(this);
    }
}
