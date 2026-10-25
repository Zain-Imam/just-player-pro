package com.brouken.player;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.ColorUtils;
import androidx.preference.PreferenceManager;

// AppCompat screens only; the player has its own dark theme, a TV is always dark
public final class AppTheme {

    static final String PREF_KEY = "appTheme";

    private AppTheme() {
    }

    static void start(final Application app) {
        apply(app);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(@NonNull final Activity activity) {
                // the player sets its own bars
                if (activity instanceof AppCompatActivity) {
                    barIcons(activity);
                }
            }

            @Override
            public void onActivityCreated(@NonNull final Activity activity, @Nullable final Bundle state) {
            }

            @Override
            public void onActivityResumed(@NonNull final Activity activity) {
                if (activity instanceof AppCompatActivity) {
                    catchUp(activity);
                }
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

    static void apply(final Context context) {
        final String value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_KEY, "dark");
        AppCompatDelegate.setDefaultNightMode(mode(value, Utils.isTvBox(context)));
    }

    private static long caughtUpAtMs;

    // a screen may come up in the wrong theme; rebuilt, but not twice within seconds
    private static void catchUp(final Activity activity) {
        final int mode = mode(PreferenceManager.getDefaultSharedPreferences(activity)
                .getString(PREF_KEY, "dark"), Utils.isTvBox(activity));
        if (mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                || isNight(activity) == (mode == AppCompatDelegate.MODE_NIGHT_YES)) {
            return;
        }
        final long now = android.os.SystemClock.elapsedRealtime();
        if (now - caughtUpAtMs < 5_000) {
            return;
        }
        caughtUpAtMs = now;
        android.util.Log.i(Utils.TAG, "Theme out of step on " + activity.getClass().getSimpleName()
                + ", rebuilt");
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            // recreates the open screens itself
            AppCompatDelegate.setDefaultNightMode(mode);
        } else {
            activity.recreate();
        }
    }

    static int mode(@Nullable final String value, final boolean tv) {
        if (tv) {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
        if ("light".equals(value)) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        }
        if ("phone".equals(value)) {
            return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        return AppCompatDelegate.MODE_NIGHT_YES;
    }

    // the title pickers draw white text, so they stay dark over a light page
    public static Context panels(final Activity activity) {
        // the player's own theme is dark already, even on a light phone
        if (!(activity instanceof AppCompatActivity) || isNight(activity)) {
            return activity;
        }
        return Accent.themed(new ContextThemeWrapper(activity, android.R.style.Theme_Material),
                Accent.stored(activity));
    }

    public static boolean isNight(final Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    // dark icons on a light bar, or on the page behind a see-through one
    @SuppressWarnings("deprecation")
    static void barIcons(final Activity activity) {
        final Window window = activity.getWindow();
        final boolean night = isNight(activity);
        final boolean lightStatus = isLight(window.getStatusBarColor(), night);
        boolean lightNavigation = isLight(window.getNavigationBarColor(), night);
        // too old for dark navigation icons: a dark bar instead
        if (lightNavigation && Build.VERSION.SDK_INT < 27) {
            window.setNavigationBarColor(Color.BLACK);
            lightNavigation = false;
        }
        final View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags = lightStatus ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 27) {
            flags = lightNavigation ? flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    private static boolean isLight(final int bar, final boolean night) {
        if (Color.alpha(bar) < 0xFF) {
            return !night;
        }
        return ColorUtils.calculateLuminance(bar) > 0.5;
    }
}
