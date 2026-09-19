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

    /*
     * Whether anything ever came out of it.
     *
     * An entry is written the moment a URL is opened, which is before anyone
     * knows whether it will play — so a dead link, an expired debrid URL or a
     * typo lands in the list beside the things that worked. This is set once
     * the player has actually rendered a frame, and the list can then be asked
     * to show only what played.
     *
     * Absent on entries written by earlier versions, which are given the
     * benefit of the doubt: they were listed before this existed, and silently
     * hiding somebody's history on upgrade is worse than listing a dud.
     */
    private static final String KEY_PLAYED = "played";

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
        /** Whether this one ever actually played. See KEY_PLAYED. */
        final boolean played;

        Entry(Uri uri, String name, @Nullable String type, long time) {
            this(uri, name, type, time, true);
        }

        Entry(Uri uri, String name, @Nullable String type, long time, boolean played) {
            this.uri = uri;
            this.name = name;
            this.type = type;
            this.time = time;
            this.played = played;
        }
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

        for (int i = entries.size() - 1; i >= 0; i--) {
            final Uri existing = entries.get(i).uri;
            if (key.equals(existing.toString()) || place.equals(withoutQuery(existing))) {
                entries.remove(i);
            }
        }

        // Not played yet — it has only been opened. markPlayed says otherwise.
        entries.add(0, new Entry(uri, displayName(uri), type,
                System.currentTimeMillis(), false));

        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }

        save(preferences, entries);
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
        // A regenerated link is a different host and a different path, so the
        // one thing it still shares with the entry that was stored is the file
        // name on the end of it. Without this the prompt fell back to showing
        // the identifier out of the URL, which is what it was trying to avoid.
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
        return entry.name != null && !entry.name.equals(displayName(entry.uri));
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

    /**
     * Give an entry a name, but never in place of a better one.
     *
     * <p>Identifying a film answers with the name of the work -- "Silo" -- and
     * that is the right thing to show on the card and the wrong thing to offer
     * as the last video, where "Silo" could be any of thirty episodes. It is
     * still far better than the identifier out of the link, so it is written
     * only where nothing has named the entry yet: a launcher's title and a name
     * resolved from the server are both the file itself, and both win.
     */
    static void fillInName(final SharedPreferences preferences, @Nullable final Uri uri,
                           @Nullable final String name) {
        if (uri == null || name == null || name.trim().isEmpty()) {
            return;
        }
        final List<Entry> entries = load(preferences);
        final String key = uri.toString();
        boolean changed = false;

        for (int i = 0; i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            if (!key.equals(entry.uri.toString()) || resolved(entry)) {
                continue;
            }
            entries.set(i, new Entry(entry.uri, name.trim(), entry.type, entry.time,
                    entry.played));
            changed = true;
        }

        if (changed) {
            save(preferences, entries);
        }
    }
    static void rename(final SharedPreferences preferences, @Nullable final Uri uri,
                       @Nullable final String name) {
        if (uri == null || name == null || name.trim().isEmpty()) {
            return;
        }
        final List<Entry> entries = load(preferences);
        final String key = uri.toString();
        boolean changed = false;

        for (int i = 0; i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            if (!key.equals(entry.uri.toString()) || name.equals(entry.name)) {
                continue;
            }
            entries.set(i, new Entry(entry.uri, name.trim(), entry.type, entry.time));
            changed = true;
        }

        if (changed) {
            save(preferences, entries);
        }
    }
    static void remove(final SharedPreferences preferences, @NonNull final Uri uri) {
        final List<Entry> entries = load(preferences);
        final String key = uri.toString();
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (key.equals(entries.get(i).uri.toString())) {
                entries.remove(i);
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

    /**
     * Say that this one played, so it survives the "only what played" filter.
     *
     * Called when a frame has actually been rendered rather than when playback
     * is merely requested: a link that connects, buffers and then fails has not
     * played, and should not be remembered as though it had.
     */
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
                entries.set(i, new Entry(entry.uri, entry.name, entry.type, entry.time, true));
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
                entries.add(new Entry(uri, name, type, object.optLong(KEY_TIME, 0L),
                        object.optBoolean(KEY_PLAYED, true)));
            }
        } catch (JSONException e) {
            // A corrupt list is not worth failing a launch over — the feature is
            // a convenience, and starting empty is the recoverable outcome.
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
                array.put(object);
            }
        } catch (JSONException e) {
            Utils.log("Could not write history: " + e);
            return;
        }
        preferences.edit().putString(PREF_KEY, array.toString()).apply();
    }
}
