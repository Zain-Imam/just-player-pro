package com.brouken.player;

import android.app.Application;

// background thread failures are logged instead of killing the process;
// the main thread keeps the default handler
public final class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        final Thread.UncaughtExceptionHandler existing =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (thread == getMainLooper().getThread() || existing == null) {
                if (existing != null) {
                    existing.uncaughtException(thread, error);
                }
                return;
            }
            Utils.log("Background thread " + thread.getName() + " failed: " + error);
            if (BuildConfig.DEBUG) {
                error.printStackTrace();
            }
        });
    }
}
