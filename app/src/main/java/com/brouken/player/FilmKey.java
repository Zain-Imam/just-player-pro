package com.brouken.player;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

// launch URLs carry a fresh token each time, so the URI is the last resort
public final class FilmKey {

    private static final String[] GENERIC = {
            "index", "master", "playlist", "manifest", "video", "stream", "media",
            "file", "download", "play", "watch", "movie", "output", "chunklist"};

    private FilmKey() {
    }

    @Nullable
    public static String of(@NonNull final Context context, @Nullable final Uri uri,
                            @Nullable final String title) {
        if (uri == null) {
            return null;
        }
        final String name = Utils.getFileName(context, uri, true);
        if (name != null && identifies(name)) {
            return "f:" + name.trim().toLowerCase(Locale.ROOT);
        }
        if (title != null && !title.trim().isEmpty()) {
            return "t:" + title.trim().toLowerCase(Locale.ROOT);
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        final String path = uri.getEncodedPath() == null ? "" : uri.getEncodedPath();
        return "u:" + scheme + "://" + (uri.getEncodedAuthority() == null ? ""
                : uri.getEncodedAuthority()) + path;
    }

    static boolean identifies(@NonNull final String name) {
        final String trimmed = name.trim();
        final int dot = trimmed.lastIndexOf('.');
        if (dot <= 0 || dot == trimmed.length() - 1) {
            return false;
        }
        final String base = trimmed.substring(0, dot).toLowerCase(Locale.ROOT);
        if (base.length() < 3 || SubtitleNames.looksLikeAnId(base)) {
            return false;
        }
        for (final String generic : GENERIC) {
            if (base.equals(generic)) {
                return false;
            }
        }
        return true;
    }
}
