package com.brouken.player;

import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// another app's launch extras, replayed when the film is reopened from here
// subtitles are kept as copies: the sender may revoke access after the player closes
public final class LaunchMemory {

    static final String PREF_KEY = "launchMemory";
    private static final int MAX_FILMS = 100;

    private LaunchMemory() {
    }

    public static final class Subtitle {
        @NonNull public final Uri uri;
        @Nullable public final String name;
        @Nullable public final String language;
        public final boolean selected;

        public Subtitle(@NonNull Uri uri, @Nullable String name, @Nullable String language,
                        boolean selected) {
            this.uri = uri;
            this.name = name;
            this.language = language;
            this.selected = selected;
        }
    }

    public static final class Record {
        @Nullable public String title;
        @NonNull public final LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        @NonNull public final List<Subtitle> subtitles = new ArrayList<>();

        boolean isEmpty() {
            return (title == null || title.trim().isEmpty()) && headers.isEmpty()
                    && subtitles.isEmpty();
        }
    }

    @Nullable
    public static Record load(@NonNull final SharedPreferences preferences, @Nullable final Uri media) {
        if (media == null) {
            return null;
        }
        final JSONObject entry = all(preferences).optJSONObject(media.toString());
        if (entry == null) {
            return null;
        }
        final Record record = new Record();
        record.title = entry.optString("title", null);
        final JSONObject headers = entry.optJSONObject("headers");
        if (headers != null) {
            final java.util.Iterator<String> names = headers.keys();
            while (names.hasNext()) {
                final String name = names.next();
                record.headers.put(name, headers.optString(name));
            }
        }
        final JSONArray subtitles = entry.optJSONArray("subtitles");
        if (subtitles != null) {
            for (int i = 0; i < subtitles.length(); i++) {
                final JSONObject subtitle = subtitles.optJSONObject(i);
                if (subtitle == null || subtitle.optString("uri", "").isEmpty()) {
                    continue;
                }
                record.subtitles.add(new Subtitle(Uri.parse(subtitle.optString("uri")),
                        subtitle.optString("name", null), subtitle.optString("language", null),
                        subtitle.optBoolean("selected", false)));
            }
        }
        return record;
    }

    public static void save(@NonNull final SharedPreferences preferences, @NonNull final Uri media,
                            @NonNull final Record record) {
        final JSONObject all = all(preferences);
        final String key = media.toString();
        all.remove(key);
        if (!record.isEmpty()) {
            try {
                final JSONObject entry = new JSONObject();
                if (record.title != null) {
                    entry.put("title", record.title);
                }
                final JSONObject headers = new JSONObject();
                for (final Map.Entry<String, String> header : record.headers.entrySet()) {
                    headers.put(header.getKey(), header.getValue());
                }
                entry.put("headers", headers);
                final JSONArray subtitles = new JSONArray();
                for (final Subtitle subtitle : record.subtitles) {
                    final JSONObject one = new JSONObject();
                    one.put("uri", subtitle.uri.toString());
                    if (subtitle.name != null) {
                        one.put("name", subtitle.name);
                    }
                    if (subtitle.language != null) {
                        one.put("language", subtitle.language);
                    }
                    one.put("selected", subtitle.selected);
                    subtitles.put(one);
                }
                entry.put("subtitles", subtitles);
                entry.put("at", System.currentTimeMillis());
                all.put(key, entry);
            } catch (Exception e) {
                return;
            }
        }
        trim(all);
        preferences.edit().putString(PREF_KEY, all.toString()).apply();
    }

    // every subtitle a remembered film refers to, so cache cleanup keeps them
    @NonNull
    public static List<Uri> subtitleFiles(@NonNull final SharedPreferences preferences) {
        final List<Uri> files = new ArrayList<>();
        final JSONObject all = all(preferences);
        final java.util.Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            final JSONObject entry = all.optJSONObject(keys.next());
            final JSONArray subtitles = entry == null ? null : entry.optJSONArray("subtitles");
            if (subtitles == null) {
                continue;
            }
            for (int i = 0; i < subtitles.length(); i++) {
                final JSONObject subtitle = subtitles.optJSONObject(i);
                if (subtitle != null && !subtitle.optString("uri", "").isEmpty()) {
                    files.add(Uri.parse(subtitle.optString("uri")));
                }
            }
        }
        return files;
    }

    @NonNull
    private static JSONObject all(@NonNull final SharedPreferences preferences) {
        try {
            final String stored = preferences.getString(PREF_KEY, null);
            return stored == null ? new JSONObject() : new JSONObject(stored);
        } catch (Exception e) {
            // A corrupt store is no worse than an empty one.
            return new JSONObject();
        }
    }

    private static void trim(@NonNull final JSONObject all) {
        while (all.length() > MAX_FILMS) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            final java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                final JSONObject entry = all.optJSONObject(key);
                final long at = entry == null ? 0 : entry.optLong("at", 0);
                if (at < oldestAt) {
                    oldestAt = at;
                    oldest = key;
                }
            }
            if (oldest == null) {
                return;
            }
            all.remove(oldest);
        }
    }
}
