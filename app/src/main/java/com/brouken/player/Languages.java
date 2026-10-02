package com.brouken.player;

import android.content.Context;
import android.view.accessibility.CaptioningManager;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// language preference order, shared by both engines
public final class Languages {

    private Languages() {
    }

    public static String[] audio(final Context context) {
        final List<String> order = new ArrayList<>(
                split(preference(context, "languageAudioPriority")));

        // read directly so both engines' option builders answer the same
        final String chosen = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("languageAudio", Prefs.TRACK_DEVICE);

        if (Prefs.TRACK_DEVICE.equals(chosen)) {
            for (final String language : Utils.getDeviceLanguages()) {
                order.add(language);
            }
        } else if (!Prefs.TRACK_DEFAULT.equals(chosen)) {
            order.add(chosen);
        }

        return unique(order);
    }

    public static String[] subtitle(final Context context) {
        final List<String> order = new ArrayList<>(
                split(preference(context, "languageSubtitlePriority")));

        final CaptioningManager captioning =
                (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);
        final Locale locale = captioning == null ? null : captioning.getLocale();
        if (locale != null) {
            order.add(locale.getLanguage());
        }

        final String search = preference(context, "subtitleLanguage");
        if (search != null && !search.isEmpty()) {
            order.add(search);
        }

        return unique(order);
    }

    // mpv matches the container's tag: usually 3 letters in Matroska, 2 in MP4
    public static String forMpv(final String[] languages) {
        final Set<String> spellings = new LinkedHashSet<>();
        for (final String language : languages) {
            spellings.add(language);
            final String iso3 = toIso3(language);
            if (iso3 != null) {
                spellings.add(iso3);
            }
            final String iso1 = toIso1(language);
            if (iso1 != null) {
                spellings.add(iso1);
            }
        }
        // String.join needs API 26 and the app still runs on 25
        final StringBuilder list = new StringBuilder();
        for (final String spelling : spellings) {
            if (list.length() > 0) {
                list.append(',');
            }
            list.append(spelling);
        }
        return list.toString();
    }

    private static String preference(final Context context, final String key) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(key, "");
    }

    private static List<String> split(final String value) {
        final List<String> entries = new ArrayList<>();
        if (value == null) {
            return entries;
        }
        for (final String entry : value.split("[^A-Za-z]+")) {
            final String trimmed = entry.trim().toLowerCase(Locale.US);
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    private static String[] unique(final List<String> order) {
        return new LinkedHashSet<>(order).toArray(new String[0]);
    }

    private static String toIso3(final String language) {
        try {
            final String iso3 = new Locale(language).getISO3Language();
            return iso3.isEmpty() ? null : iso3;
        } catch (Exception e) {
            return null;
        }
    }

    private static String toIso1(final String language) {
        if (language.length() == 2) {
            return language;
        }
        for (final String code : Locale.getISOLanguages()) {
            if (language.equalsIgnoreCase(toIso3(code))) {
                return code;
            }
        }
        return null;
    }
}
