package com.brouken.player.home;

import java.util.Locale;

/**
 * Numbers as a person would say them.
 *
 * <p>Its own class, away from the screen that shows it, because this is the one
 * part of the home screen that can be checked without a device — and a folder
 * that says "1.0 GB" where it means "1,024 MB" is exactly the kind of thing
 * nobody notices in a screenshot.
 */
public final class Readable {

    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    private Readable() {
    }

    /**
     * A size, to one decimal place below ten and to none above it.
     *
     * <p>1.4 GB rather than 1.43 GB, and 870 MB rather than 870.2 MB: the
     * second digit never changed a decision anybody made about a video file,
     * and it makes a column of sizes harder to scan.
     */
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
