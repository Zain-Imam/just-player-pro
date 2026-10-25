package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

// Off lets the film follow the drag; the others show a preview and seek on release
final class SeekPreview {

    static final String PREF_KEY = "seekPreview";
    static final String OFF = "off";
    static final String LOCAL = "local";
    static final String ALL = "all";

    // the boolean key this setting took over from
    private static final String OLD_KEY = "scrubThumbnails";

    private SeekPreview() {
    }

    static String mode(final Context context) {
        final String mode = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_KEY, ALL);
        return OFF.equals(mode) || LOCAL.equals(mode) ? mode : ALL;
    }

    static boolean on(final Context context) {
        return !OFF.equals(mode(context));
    }

    static void migrate(final SharedPreferences prefs) {
        if (prefs.contains(PREF_KEY) || !prefs.contains(OLD_KEY)) {
            return;
        }
        prefs.edit()
                .putString(PREF_KEY, prefs.getBoolean(OLD_KEY, true) ? ALL : OFF)
                .apply();
    }

    // whether frames can come for this source; the time shows either way
    static boolean framesFor(final Context context, @Nullable final Uri uri) {
        if (uri == null) {
            return false;
        }
        final String mode = mode(context);
        if (OFF.equals(mode)) {
            return false;
        }
        if (!Utils.isSupportedNetworkUri(uri)) {
            return true;
        }
        if (!ALL.equals(mode)) {
            return false;
        }
        // adaptive and live streams have no single file to read a keyframe from
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.startsWith("http")) {
            return false;
        }
        final String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
        return !path.endsWith(".m3u8") && !path.endsWith(".mpd") && !path.endsWith(".ism")
                && !path.contains(".ism/");
    }
}
