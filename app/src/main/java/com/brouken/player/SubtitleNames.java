package com.brouken.player;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

// one label for a sidecar subtitle, given to both engines
public final class SubtitleNames {

    private SubtitleNames() {
    }

    // the given name, then the file name without extension, then the language name
    @NonNull
    public static String label(@NonNull final Context context, @Nullable final Uri uri,
                               @Nullable final String given, @Nullable final String language) {
        if (given != null && !given.trim().isEmpty()) {
            return given.trim();
        }
        final String file = uri == null ? null : withoutExtension(Utils.getFileName(context, uri, true));
        if (file != null && !file.isEmpty() && !looksLikeAnId(file)) {
            return file;
        }
        final String languageName = languageName(language);
        if (languageName != null) {
            return languageName;
        }
        if (file != null && !file.isEmpty()) {
            return file;
        }
        return context.getString(R.string.subtitle_menu_track, 1);
    }

    @Nullable
    static String withoutExtension(@Nullable final String name) {
        if (name == null) {
            return null;
        }
        final String trimmed = name.trim();
        if (SubtitleFiles.hasSubtitleExtension(trimmed)) {
            return trimmed.substring(0, trimmed.lastIndexOf('.'));
        }
        return trimmed;
    }

    // a row id, e.g. "1234", "msf:1000012345", "document:42"
    static boolean looksLikeAnId(@NonNull final String name) {
        return name.matches("(?i)([a-z]+:)?[0-9]+");
    }

    @Nullable
    static String languageName(@Nullable final String language) {
        if (language == null || language.trim().isEmpty()) {
            return null;
        }
        final String code = language.trim();
        final String name = new Locale(code).getDisplayLanguage();
        return name.isEmpty() || name.equalsIgnoreCase(code) ? null : name;
    }
}
