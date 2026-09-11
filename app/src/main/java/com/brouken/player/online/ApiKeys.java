package com.brouken.player.online;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

public final class ApiKeys {

    public static final String PREF_TMDB = "apiKeyTmdb";
    public static final String PREF_OPENSUBTITLES = "apiKeyOpenSubtitles";
    public static final String PREF_OPENSUBTITLES_USER = "apiKeyOpenSubtitlesUser";
    public static final String PREF_OPENSUBTITLES_PASSWORD = "apiKeyOpenSubtitlesPassword";
    public static final String PREF_SUBDL = "apiKeySubdl";
    public static final String PREF_WYZIE = "apiKeyWyzie";

    private ApiKeys() {
    }

    private static SharedPreferences preferences(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    @Nullable
    public static String get(final Context context, final String key) {
        final String value = preferences(context).getString(key, null);
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static boolean has(final Context context, final String key) {
        return get(context, key) != null;
    }

    public static boolean hasTmdb(final Context context) {
        return has(context, PREF_TMDB);
    }

    public static boolean hasAnySubtitleSource(final Context context) {
        return has(context, PREF_OPENSUBTITLES)
                || has(context, PREF_SUBDL)
                || has(context, PREF_WYZIE);
    }
}
