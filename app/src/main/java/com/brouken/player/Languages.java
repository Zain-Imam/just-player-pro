package com.brouken.player;

import android.content.Context;
import android.view.accessibility.CaptioningManager;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/*
 * Which language wins, asked once and answered the same way by both engines.
 *
 * A file with English, Japanese and Spanish audio has no idea which of them is
 * wanted, and the one the muxer happened to mark as default is a coin toss. A
 * single preferred language is not much better on a collection where some files
 * have the wanted language and some do not: what is actually wanted is an order
 * of preference, with a fallback further down it.
 *
 * This also closes a gap between the engines. mpv was told which subtitle
 * language to prefer and Media3 was not, so the same file opened with subtitles
 * on one engine and without them on the other.
 */
public final class Languages {

    private Languages() {
    }

    /** Audio languages in order of preference, best first, possibly empty. */
    public static String[] audio(final Context context) {
        final List<String> order = new ArrayList<>(
                split(preference(context, "languageAudioPriority")));

        // Read here rather than taken from Prefs: the other engine builds its
        // options without one, and both must answer identically.
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

    /** Subtitle languages in order of preference, best first, possibly empty. */
    public static String[] subtitle(final Context context) {
        final List<String> order = new ArrayList<>(
                split(preference(context, "languageSubtitlePriority")));

        // The system's own captioning language, which is what someone who set
        // one up expects every player to honour.
        final CaptioningManager captioning =
                (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);
        final Locale locale = captioning == null ? null : captioning.getLocale();
        if (locale != null) {
            order.add(locale.getLanguage());
        }

        // The language already chosen for searching for subtitles online: no
        // reason to prefer one language in the file and another out of it.
        final String search = preference(context, "subtitleLanguage");
        if (search != null && !search.isEmpty()) {
            order.add(search);
        }

        return unique(order);
    }

    /*
     * mpv matches what the container says, which for Matroska is usually the
     * three-letter tag and for MP4 is usually the two. Both spellings are given
     * so neither file type falls through.
     */
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
        // Built by hand rather than with String.join, which arrived in API 26
        // and this app still runs on 25.
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
