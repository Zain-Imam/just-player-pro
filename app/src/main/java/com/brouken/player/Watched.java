package com.brouken.player;

import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.brouken.player.online.Identity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

// This app's own watched marks, by film and by the video's place, so Recent can tick it without knowing
// the film. Simkl's list is never copied here, so unwatching there or signing out takes its ticks away.
public final class Watched {

    private static final String PREF_KEY = "watchedFilms";
    private static final int MAX = 2000;

    public static final String BY_APP = "app";

    private Watched() {
    }

    public static final class State {
        public final long at;
        // the Simkl list a film sat on when this app marked it (plantowatch, dropped), so Unwatch
        // puts it back: Simkl takes an unwatched film off every list
        @Nullable
        public final String simklList;

        State(final long at, @Nullable final String simklList) {
            this.at = at;
            this.simklList = simklList;
        }
    }

    @Nullable
    public static String filmKey(@Nullable final Identity identity) {
        if (identity == null || identity.tmdbId <= 0) {
            return null;
        }
        if (identity.isSeries) {
            return identity.season == null || identity.episode == null ? null
                    : "tv:" + identity.tmdbId + ":" + identity.season + ":" + identity.episode;
        }
        return "movie:" + identity.tmdbId;
    }

    @NonNull
    public static String placeKey(@NonNull final Uri uri) {
        return "place:" + History.withoutQuery(uri);
    }

    @Nullable
    public static State state(@NonNull final SharedPreferences prefs, @Nullable final Uri uri,
                              @Nullable final Identity identity) {
        final JSONObject all = load(prefs);
        final String film = filmKey(identity);
        JSONObject found = film == null ? null : all.optJSONObject(film);
        if (found == null && uri != null) {
            found = all.optJSONObject(placeKey(uri));
        }
        return found == null ? null : stateOf(found);
    }

    // the films marked here, so every copy of one gets the tick
    @NonNull
    public static java.util.Set<String> films(@NonNull final SharedPreferences prefs) {
        return filmsIn(load(prefs));
    }

    static java.util.Set<String> filmsIn(final JSONObject all) {
        final java.util.Set<String> films = new java.util.HashSet<>();
        final Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (!key.startsWith("place:")) {
                films.add(key);
            }
        }
        return films;
    }

    // every watched place at once, for a screen of ticks
    @NonNull
    public static java.util.Set<String> places(@NonNull final SharedPreferences prefs) {
        final java.util.Set<String> places = new java.util.HashSet<>();
        final Iterator<String> keys = load(prefs).keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (key.startsWith("place:")) {
                places.add(key);
            }
        }
        return places;
    }

    public static void mark(@NonNull final SharedPreferences prefs, @Nullable final Uri uri,
                            @Nullable final Identity identity, @Nullable final String simklList) {
        final JSONObject all = load(prefs);
        final JSONObject entry = new JSONObject();
        try {
            entry.put("by", BY_APP);
            entry.put("at", System.currentTimeMillis());
            if (simklList != null) {
                entry.put("simklList", simklList);
            }
            final String film = filmKey(identity);
            if (film != null) {
                putFresh(all, film, entry);
            }
            if (uri != null) {
                putFresh(all, placeKey(uri), entry);
            }
        } catch (Exception ignored) {
            return;
        }
        save(prefs, all);
    }

    public static void forget(@NonNull final SharedPreferences prefs, @Nullable final Uri uri,
                              @Nullable final Identity identity) {
        final JSONObject all = load(prefs);
        final String film = filmKey(identity);
        if (film != null) {
            all.remove(film);
        }
        if (uri != null) {
            all.remove(placeKey(uri));
        }
        save(prefs, all);
    }

    // films this app marked, for sending once to a service connected later
    @NonNull
    public static JSONArray markedByApp(@NonNull final SharedPreferences prefs) {
        final JSONArray films = new JSONArray();
        final JSONObject all = load(prefs);
        final Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final JSONObject entry = all.optJSONObject(key);
            if (!key.startsWith("place:") && entry != null && BY_APP.equals(entry.optString("by"))) {
                films.put(key);
            }
        }
        return films;
    }

    private static State stateOf(final JSONObject entry) {
        final String list = entry.isNull("simklList") ? "" : entry.optString("simklList", "");
        return new State(entry.optLong("at"), list.isEmpty() ? null : list);
    }

    private static JSONObject load(final SharedPreferences prefs) {
        try {
            return ownMarks(new JSONObject(prefs.getString(PREF_KEY, "{}")));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // test builds once kept Simkl's word here too; only this app's own marks are read
    static JSONObject ownMarks(final JSONObject all) {
        final java.util.List<String> others = new java.util.ArrayList<>();
        final Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final JSONObject entry = all.optJSONObject(key);
            // no owner, or JSON null (which Android's optString reads as "null"): this app's
            if (entry == null || (!entry.isNull("by") && !BY_APP.equals(entry.optString("by")))) {
                others.add(key);
            }
        }
        for (final String key : others) {
            all.remove(key);
        }
        return all;
    }

    private static void save(final SharedPreferences prefs, final JSONObject all) {
        while (all.length() > MAX) {
            final Iterator<String> keys = all.keys();
            if (!keys.hasNext()) {
                break;
            }
            all.remove(keys.next());
        }
        prefs.edit().putString(PREF_KEY, all.toString()).apply();
    }

    // to the end, so the cap drops the oldest
    private static void putFresh(final JSONObject all, final String key, final JSONObject value) throws Exception {
        all.remove(key);
        all.put(key, value);
    }
}
