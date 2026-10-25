package com.brouken.player;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

// debug builds only, switched on over adb for clean screenshots
public final class Showcase {

    // home lists only the folders under this path
    static final String PREF_FOLDERS = "debugShowcaseFolders";
    static final String PREF_HIDE_BARS = "debugShowcaseHideBars";
    // runs the app's TV code on a phone
    static final String PREF_TV = "debugShowcaseTv";

    private Showcase() {
    }

    static boolean on(@NonNull final Context context, @NonNull final String key) {
        return BuildConfig.DEBUG
                && PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key, false);
    }

    @Nullable
    public static String folders(@NonNull final Context context) {
        if (!BuildConfig.DEBUG) {
            return null;
        }
        final String path = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_FOLDERS, null);
        return path == null || path.isEmpty() ? null : path;
    }

    static void start(@NonNull final Application app) {
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 30) {
            return;
        }
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityPreCreated(@NonNull final Activity activity, @Nullable final Bundle state) {
                // a real TV gives the player's theme a white highlight (values-television)
                if (on(activity, PREF_TV) && usesPlayerTheme(activity)) {
                    activity.getTheme().applyStyle(R.style.ShowcaseTv, true);
                }
            }

            @Override
            public void onActivityCreated(@NonNull final Activity activity, @Nullable final Bundle state) {
                // a dialog closing hands the focus back and the bars return with it
                activity.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(focused -> {
                    if (focused && on(activity, PREF_HIDE_BARS)) {
                        hideBars(activity.getWindow());
                    }
                });
            }

            @Override
            public void onActivityResumed(@NonNull final Activity activity) {
                if (on(activity, PREF_HIDE_BARS)) {
                    hideBars(activity.getWindow());
                }
            }

            @Override
            public void onActivityStarted(@NonNull final Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull final Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull final Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull final Activity activity, @NonNull final Bundle state) {
            }

            @Override
            public void onActivityDestroyed(@NonNull final Activity activity) {
            }
        });
    }

    // also for a sheet or dialog: their own windows bring the bars back
    public static void hideBars(@Nullable final Window window) {
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 30 || window == null
                || !on(window.getContext(), PREF_HIDE_BARS)) {
            return;
        }
        final WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
        }
    }

    private static boolean usesPlayerTheme(@NonNull final Activity activity) {
        try {
            final ActivityInfo info = activity.getPackageManager().getActivityInfo(activity.getComponentName(), 0);
            return info.getThemeResource() == R.style.Theme_Player;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
