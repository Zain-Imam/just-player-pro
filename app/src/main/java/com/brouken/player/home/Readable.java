package com.brouken.player.home;

import java.util.Locale;

// Kept apart from the home screen so it can be unit tested.
public final class Readable {

    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    private Readable() {
    }

    /** A size in binary units, one decimal place below 10 and none above. */
    public static String size(final long bytes) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes / 1024d;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024d;
            unit++;
        }
        return String.format(Locale.getDefault(), value < 10 ? "%.1f %s" : "%.0f %s",
                value, UNITS[unit]);
    }
}
