package com.brouken.player;

import android.app.Application;

/*
 * One rule, applied to the whole app: a background thread may not take the
 * player down with it.
 *
 * An uncaught exception on any thread kills the process, and from the sofa that
 * looks like the film vanishing back to the launcher mid-scene with nothing
 * said. Almost none of what this app does off the main thread is worth that:
 * asking a website for a title, parsing a file name, reading a subtitle, looking
 * for the next file in a folder. If one of those fails, the right outcome is
 * that it did not happen.
 *
 * The main thread is left alone. A failure there is in the middle of drawing or
 * of handling a press, and carrying on from it would leave something on screen
 * that cannot be trusted.
 */
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
