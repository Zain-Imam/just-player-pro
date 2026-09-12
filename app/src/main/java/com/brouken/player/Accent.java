package com.brouken.player;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.preference.PreferenceManager;

public final class Accent {

    public static final String PREF_KEY = "accentColor";
    public static final String DEFAULT = "orange";

    private Accent() {
    }

    private static final String[] KEYS = {
            "red", "orange", "yellow", "lime", "green", "cyan",
            "blue", "violet", "fuchsia", "pink", "slate",
    };

    private static final int[] OVERLAYS = {
            R.style.Accent_Red, R.style.Accent_Orange, R.style.Accent_Yellow,
            R.style.Accent_Lime, R.style.Accent_Green, R.style.Accent_Cyan,
            R.style.Accent_Blue, R.style.Accent_Violet, R.style.Accent_Fuchsia,
            R.style.Accent_Pink, R.style.Accent_Slate,
    };

    private static final int[] COLORS = {
            R.color.accent_red, R.color.accent_orange, R.color.accent_yellow,
            R.color.accent_lime, R.color.accent_green, R.color.accent_cyan,
            R.color.accent_blue, R.color.accent_violet, R.color.accent_fuchsia,
            R.color.accent_pink, R.color.accent_slate,
    };

    public static String[] keys() {
        return KEYS.clone();
    }

    public static void apply(@NonNull final Activity activity) {
        activity.getTheme().applyStyle(overlayFor(stored(activity)), true);
    }

    @StyleRes
    private static int overlayFor(final String key) {
        return OVERLAYS[indexOf(key)];
    }

    @ColorInt
    public static int color(@NonNull final Context context) {
        final TypedArray array = context.getTheme()
                .obtainStyledAttributes(new int[]{R.attr.accentColor});
        try {
            final int fromTheme = array.getColor(0, 0);
            if (fromTheme != 0) {
                return fromTheme;
            }
        } finally {
            array.recycle();
        }
        // A context with no theme of ours — fall back to the stored choice.
        return context.getResources().getColor(colorResource(context));
    }

    public static int colorResource(@NonNull final Context context) {
        return COLORS[indexOf(stored(context))];
    }

    // Any accent by name, for showing all of them at once rather than whichever
    // one is in use.
    @ColorInt
    public static int colorOf(@NonNull final Context context, @Nullable final String key) {
        return context.getResources().getColor(COLORS[indexOf(key)]);
    }

    public static String stored(@NonNull final Context context) {
        final String value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_KEY, DEFAULT);
        return value == null ? DEFAULT : value;
    }

    private static int indexOf(@Nullable final String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) {
                return i;
            }
        }
        return 1; // orange
    }

    @ColorInt
    public static int translucent(@NonNull final Context context, final int alpha) {
        final int base = color(context);
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base));
    }
}
