package com.brouken.player.home;

import java.util.Locale;

public final class Readable {

    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    private Readable() {
    }

    // binary units, one decimal place below 10 and none above
    public static String size(final long bytes) {
        return size(bytes, "B", UNITS);
    }

    // with the units as the app's language writes them (Mo in French)
    public static String size(final android.content.Context context, final long bytes) {
        return size(bytes, context.getString(com.brouken.player.R.string.unit_bytes), new String[]{
                context.getString(com.brouken.player.R.string.unit_kilobytes),
                context.getString(com.brouken.player.R.string.unit_megabytes),
                context.getString(com.brouken.player.R.string.unit_gigabytes),
                context.getString(com.brouken.player.R.string.unit_terabytes)});
    }

    static String size(final long bytes, final String byteUnit, final String[] units) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " " + byteUnit;
        }
        double value = bytes / 1024d;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024d;
            unit++;
        }
        return String.format(Locale.getDefault(), value < 10 ? "%.1f %s" : "%.0f %s",
                value, units[unit]);
    }
}
