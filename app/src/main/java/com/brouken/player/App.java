package com.brouken.player;

import android.app.Application;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

// background crashes are logged; the main thread keeps the default handler
public final class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppLanguage.start(this);
        ImageCache.init(this);
        Showcase.start(this);
        AppTheme.start(this);

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

    @Override
    public void onConfigurationChanged(@NonNull final Configuration config) {
        super.onConfigurationChanged(config);
        AppLanguage.keep(this);
    }
}
