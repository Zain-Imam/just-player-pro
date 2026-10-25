package com.brouken.player;

import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class History {

    private static final String PREF_KEY = "urlHistory";

    private static final String KEY_URI = "uri";
    private static final String KEY_NAME = "name";
    private static final String KEY_TYPE = "type";
    private static final String KEY_TIME = "time";

    // set once a frame renders; entries without it count as played
    private static final String KEY_PLAYED = "played";

    // worst first; a name is replaced only by a better kind, a file name never
    static final int NAME_RAW = 0;
    static final int NAME_LAUNCHER = 1;
    static final int NAME_FILE = 2;
    private static final String KEY_NAME_KIND = "nameKind";

    private static final String KEY_POSTER = "poster";
    private static final String KEY_TITLE = "title";
    // held at the top of Recent, outside its count, never trimmed
    private static final String KEY_PINNED = "pinned";
    static final int MAX_PINS = 3;

    private static final int MAX_ENTRIES = 100;

    private static final String[] NETWORK_SCHEMES = {
            "http", "https",
            "rtmp", "rtmps", "rtsp",
            "mms", "srt", "udp", "tcp",
            "ftp", "ftps",
            "smb", "dav", "davs", "webdav", "webdavs",
    };

    private History() {
    }

    static final class Entry {
        final Uri uri;
        final String name;
        @Nullable
        final String type;
        final long time;
        final boolean played;
        final int nameKind;
        @Nullable
        final String poster;
        @Nullable
        final String title;
        final boolean pinned;

        Entry(Uri uri, String name, @Nullable String type, long time, boolean played,
              int nameKind, @Nullable String poster, @Nullable String title) {
            this(uri, name, type, time, played, nameKind, poster, title, false);
        }

        Entry(Uri uri, String name, @Nullable String type, long time, boolean played,
              int nameKind, @Nullable String poster, @Nullable String title, boolean pinned) {
            this.uri = uri;
            this.name = name;
            this.type = type;
            this.time = time;
            this.played = played;
            this.nameKind = nameKind;
            this.poster = poster;
            this.title = title;
            this.pinned = pinned;
        }

        Entry withPlayed() {
            return new Entry(uri, name, type, time, true, nameKind, poster, title, pinned);
        }

        Entry withName(final String newName, final int kind) {
            return new Entry(uri, newName, type, time, played, kind, poster, title, pinned);
        }

        Entry withIdentified(@Nullable final String path, @Nullable final String heading) {
            return new Entry(uri, name, type, time, played, nameKind, path, heading, pinned);
        }

        Entry withPinned(final boolean pin) {
            return new Entry(uri, name, type, time, played, nameKind, poster, title, pin);
        }
    }

    static int rawKind(@NonNull final Uri uri) {
        return FilmKey.identifies(displayName(uri)) ? NAME_FILE : NAME_RAW;
    }

    static boolean isFileName(@Nullable final String name) {
        return name != null && FilmKey.identifies(name);
    }

    // -1 when there is no entry
    static int nameKindFor(final SharedPreferences preferences, @Nullable final Uri uri) {
        if (uri == null) {
            return -1;
        }
        final String key = uri.toString();
        for (final Entry entry : load(preferences)) {
            if (key.equals(entry.uri.toString())) {
                return entry.nameKind;
            }
        }
        return -1;
    }

    static boolean isNetworkUri(@Nullable final Uri uri) {
        if (uri == null) {
            return false;
        }
        final String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        final String lower = scheme.toLowerCase(Locale.ROOT);
        for (final String candidate : NETWORK_SCHEMES) {
            if (candidate.equals(lower)) {
                return true;
            }
        }
        return false;
    }

    static String displayName(@NonNull final Uri uri) {
        String segment = uri.getLastPathSegment();
        if (segment != null) {
            segment = Uri.decode(segment).trim();
            if (!segment.isEmpty()) {
                return segment;
            }
        }
        final String host = uri.getHost();
        if (host != null && !host.isEmpty()) {
            return host;
        }
        return uri.toString();
    }

    static void record(final SharedPreferences preferences, @Nullable final Uri uri,
                       @Nullable final String type) {
        if (!isNetworkUri(uri)) {
            return;
        }

        final List<Entry> entries = load(preferences);
        final String key = uri.toString();
        final String place = withoutQuery(uri);

        // keep the best earlier name and poster; the newest wins a tie
        String name = displayName(uri);
        int kind = rawKind(uri);
        String poster = null;
        String title = null;
        // ever played, so an expired link today does not hide yesterday's film
        boolean played = false;
        boolean pinned = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            final Entry existing = entries.get(i);
            if (key.equals(existing.uri.toString()) || place.equals(withoutQuery(existing.uri))) {
                if (existing.nameKind > kind
                        || (existing.nameKind == kind && kind > NAME_RAW)) {
                    name = existing.name;
                    kind = existing.nameKind;
                }
                if (existing.poster != null || existing.title != null) {
                    poster = existing.poster;
                    title = existing.title;
                }
                played |= existing.played;
                pinned |= existing.pinned;
                entries.remove(i);
            }
        }

        entries.add(0, new Entry(uri, name, type, System.currentTimeMillis(), played, kind, poster, title, pinned));
        trim(entries);

        save(preferences, entries);
    }

    // the oldest go first, but never a pinned one
    private static void trim(final List<Entry> entries) {
        for (int i = entries.size() - 1; i >= 0 && entries.size() > MAX_ENTRIES; i--) {
            if (!entries.get(i).pinned) {
                entries.remove(i);
            }
        }
    }

    // false when a fourth pin is asked for
    static boolean setPinned(final SharedPreferences preferences, @NonNull final Uri uri,
                             final boolean pin) {
        final List<Entry> entries = load(preferences);
        if (pin) {
            int pins = 0;
            for (final Entry entry : entries) {
                if (entry.pinned && !samePlace(entry.uri, uri)) {
                    pins++;
                }
            }
            if (pins >= MAX_PINS) {
                return false;
            }
        }
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            if (samePlace(entries.get(i).uri, uri) && entries.get(i).pinned != pin) {
                entries.set(i, entries.get(i).withPinned(pin));
                changed = true;
            }
        }
        if (changed) {
            save(preferences, entries);
        }
        return true;
    }

    @Nullable
    static Entry find(final SharedPreferences preferences, @Nullable final Uri uri) {
        if (uri == null) {
            return null;
        }
        for (final Entry entry : load(preferences)) {
            if (samePlace(entry.uri, uri)) {
                return entry;
            }
        }
        return null;
    }

    static void restore(final SharedPreferences preferences, @NonNull final Entry entry,
                        final int index) {
        final List<Entry> entries = load(preferences);
        for (final Entry existing : entries) {
            if (samePlace(existing.uri, entry.uri)) {
                return;
            }
        }
        entries.add(Math.max(0, Math.min(index, entries.size())), entry);
        save(preferences, entries);
    }

    // the same film, its link's token aside
    private static boolean samePlace(@NonNull final Uri a, @NonNull final Uri b) {
        return a.toString().equals(b.toString()) || withoutQuery(a).equals(withoutQuery(b));
    }




    @Nullable
    static String nameFor(final SharedPreferences preferences, @Nullable final Uri uri) {
        if (uri == null) {
            return null;
        }
        final String place = withoutQuery(uri);
        final java.util.List<Entry> entries = load(preferences);
        for (final Entry entry : entries) {
            if (place.equals(withoutQuery(entry.uri)) && resolved(entry)) {
                return entry.name;
            }
        }
        // a regenerated link shares only the file name with the stored one
        final String file = lastSegment(uri);
        if (file != null) {
            for (final Entry entry : entries) {
                if (file.equals(lastSegment(entry.uri)) && resolved(entry)) {
                    return entry.name;
                }
            }
        }
        return null;
    }

    private static boolean resolved(final Entry entry) {
        return entry.name != null && entry.nameKind > NAME_RAW;
    }

    @Nullable
    private static String lastSegment(@Nullable final Uri uri) {
        if (uri == null) {
            return null;
        }
        final String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            return null;
        }
        final int slash = path.lastIndexOf('/');
        final String name = slash < 0 ? path : path.substring(slash + 1);
        return name.isEmpty() ? null : name;
    }
    private static String withoutQuery(@NonNull final Uri uri) {
        final String scheme = uri.getScheme();
        final String authority = uri.getAuthority();
        if (scheme == null || authority == null) {
            return uri.toString();
        }
        return scheme + "://" + authority + (uri.getPath() == null ? "" : uri.getPath());
    }

    static void rename(final SharedPreferences preferences, @Nullable final Uri uri,
                       @Nullable final String name, final int kind) {
        if (uri == null || name == null || name.trim().isEmpty()) {
            return;
        }
        final String trimmed = name.trim();
        final List<Entry> entries = load(preferences);
        final String key = uri.toString();
        boolean changed = false;

        for (int i = 0; i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            if (!key.equals(entry.uri.toString())
                    || entry.nameKind == NAME_FILE
                    || kind < entry.nameKind
                    || (kind == entry.nameKind && trimmed.equals(entry.name))) {
                continue;
            }
            entries.set(i, entry.withName(trimmed, kind));
            changed = true;
        }

        if (changed) {
            save(preferences, entries);
        }
    }
    // its link with a new token too; returns where it was, or -1
    static int remove(final SharedPreferences preferences, @NonNull final Uri uri) {
        final List<Entry> entries = load(preferences);
        int at = -1;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (samePlace(entries.get(i).uri, uri)) {
                entries.remove(i);
                at = i;
            }
        }
        if (at >= 0) {
            save(preferences, entries);
        }
        return at;
    }

    static void setIdentified(final SharedPreferences preferences, @Nullable final Uri uri,
                              @Nullable final String posterPath, @Nullable final String heading) {
        final String poster = posterPath == null || posterPath.isEmpty() ? null : posterPath;
        final String title = heading == null || heading.trim().isEmpty() ? null : heading.trim();
        if (uri == null || (poster == null && title == null)) {
            return;
        }
        final List<Entry> entries = load(preferences);
        final String place = withoutQuery(uri);
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            if (place.equals(withoutQuery(entry.uri))
                    && (!java.util.Objects.equals(poster, entry.poster)
                    || !java.util.Objects.equals(title, entry.title))) {
                entries.set(i, entry.withIdentified(poster, title));
                changed = true;
            }
        }
        if (changed) {
            save(preferences, entries);
        }
    }

    static void clear(final SharedPreferences preferences) {
        preferences.edit().remove(PREF_KEY).apply();
    }

    // called once a frame renders: a link that buffers then fails never played
    static void markPlayed(final SharedPreferences preferences, @Nullable final Uri uri) {
        if (!isNetworkUri(uri)) {
            return;
        }
        final List<Entry> entries = load(preferences);
        final String key = uri.toString();
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            if (!entry.played && key.equals(entry.uri.toString())) {
                entries.set(i, entry.withPlayed());
                changed = true;
            }
        }
        if (changed) {
            save(preferences, entries);
        }
    }

    @NonNull
    static List<Entry> load(final SharedPreferences preferences) {
        final List<Entry> entries = new ArrayList<>();
        final String stored = preferences.getString(PREF_KEY, null);
        if (stored == null || stored.isEmpty()) {
            return entries;
        }

        try {
            final JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length(); i++) {
                final JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                final String uriString = object.optString(KEY_URI, null);
                if (uriString == null || uriString.isEmpty()) {
                    continue;
                }
                final Uri uri = Uri.parse(uriString);
                String name = object.optString(KEY_NAME, null);
                if (name == null || name.isEmpty()) {
                    name = displayName(uri);
                }
                final String type = object.has(KEY_TYPE) && !object.isNull(KEY_TYPE)
                        ? object.optString(KEY_TYPE, null) : null;
                // no stored kind: a name unlike the link's is taken as a launcher's
                final int kind = object.has(KEY_NAME_KIND)
                        ? object.optInt(KEY_NAME_KIND, NAME_RAW)
                        : name.equals(displayName(uri)) ? rawKind(uri) : NAME_LAUNCHER;
                final String poster = object.has(KEY_POSTER) && !object.isNull(KEY_POSTER)
                        ? object.optString(KEY_POSTER, null) : null;
                final String title = object.has(KEY_TITLE) && !object.isNull(KEY_TITLE)
                        ? object.optString(KEY_TITLE, null) : null;
                entries.add(new Entry(uri, name, type, object.optLong(KEY_TIME, 0L),
                        object.optBoolean(KEY_PLAYED, true), kind, poster, title,
                        object.optBoolean(KEY_PINNED, false)));
            }
        } catch (JSONException e) {
            Utils.log("Discarding unreadable history: " + e);
            return new ArrayList<>();
        }

        return entries;
    }

    private static void save(final SharedPreferences preferences, final List<Entry> entries) {
        final JSONArray array = new JSONArray();
        try {
            for (final Entry entry : entries) {
                final JSONObject object = new JSONObject();
                object.put(KEY_URI, entry.uri.toString());
                object.put(KEY_NAME, entry.name);
                if (entry.type != null) {
                    object.put(KEY_TYPE, entry.type);
                }
                object.put(KEY_TIME, entry.time);
                object.put(KEY_PLAYED, entry.played);
                object.put(KEY_NAME_KIND, entry.nameKind);
                if (entry.pinned) {
                    object.put(KEY_PINNED, true);
                }
                if (entry.poster != null) {
                    object.put(KEY_POSTER, entry.poster);
                }
                if (entry.title != null) {
                    object.put(KEY_TITLE, entry.title);
                }
                array.put(object);
            }
        } catch (JSONException e) {
            Utils.log("Could not write history: " + e);
            return;
        }
        preferences.edit().putString(PREF_KEY, array.toString()).apply();
    }
}
