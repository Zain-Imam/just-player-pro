package com.brouken.player;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.Util;

import com.brouken.player.online.NameReading;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// kept as what the track is, since each engine numbers tracks its own way
final class TrackMemory {

    private static final String PREF_KEY = "trackMemory";
    private static final int MAX_ENTRIES = 300;

    private static final Pattern FORCED = Pattern.compile("(?i)\\bforced\\b");

    private TrackMemory() {
    }

    static final class Pick {
        boolean off;
        @Nullable
        String language;
        @Nullable
        String label;
        boolean forced;
        boolean sdh;
        // among tracks of the same language and kind
        int ordinal;
        // among all tracks of the type, and how many there were
        int index;
        int count;

        JSONObject toJson() throws JSONException {
            final JSONObject o = new JSONObject();
            o.put("off", off);
            o.put("lang", language == null ? "" : language);
            o.put("label", label == null ? "" : label);
            o.put("forced", forced);
            o.put("sdh", sdh);
            o.put("ordinal", ordinal);
            o.put("index", index);
            o.put("count", count);
            return o;
        }

        static Pick fromJson(final JSONObject o) {
            final Pick p = new Pick();
            p.off = o.optBoolean("off");
            p.language = emptyToNull(o.optString("lang"));
            p.label = emptyToNull(o.optString("label"));
            p.forced = o.optBoolean("forced");
            p.sdh = o.optBoolean("sdh");
            p.ordinal = o.optInt("ordinal");
            p.index = o.optInt("index", -1);
            p.count = o.optInt("count");
            return p;
        }
    }

    static final class Found {
        final Tracks.Group group;
        final int track;

        Found(final Tracks.Group group, final int track) {
            this.group = group;
            this.track = track;
        }
    }

    static Pick off() {
        final Pick p = new Pick();
        p.off = true;
        return p;
    }

    @Nullable
    static Pick of(@NonNull final Tracks tracks, final int type,
                   @NonNull final Tracks.Group chosen, final int chosenTrack) {
        final List<Found> all = list(tracks, type);
        Pick pick = null;
        for (int i = 0; i < all.size(); i++) {
            final Found f = all.get(i);
            if (f.group.getMediaTrackGroup() == chosen.getMediaTrackGroup() && f.track == chosenTrack) {
                pick = describe(f);
                pick.index = i;
                break;
            }
        }
        if (pick == null) {
            return null;
        }
        pick.count = all.size();
        int ordinal = 0;
        for (int i = 0; i < pick.index; i++) {
            if (sameKind(describe(all.get(i)), pick)) {
                ordinal++;
            }
        }
        pick.ordinal = ordinal;
        return pick;
    }

    // a show's pick, made on another episode, matches by language and kind only
    @Nullable
    static Found find(@NonNull final Tracks tracks, final int type, @NonNull final Pick pick,
                      final boolean sameFilm) {
        if (pick.off) {
            return null;
        }
        final List<Found> all = list(tracks, type);
        if (all.isEmpty()) {
            return null;
        }
        if (sameFilm) {
            final Found byLabel = byLabel(all, pick);
            if (byLabel != null) {
                return byLabel;
            }
        }
        final List<Found> kind = new ArrayList<>();
        for (final Found f : all) {
            if (sameKind(describe(f), pick)) {
                kind.add(f);
            }
        }
        if (!kind.isEmpty()) {
            return kind.get(Math.min(pick.ordinal, kind.size() - 1));
        }
        if (sameFilm && pick.count == all.size() && pick.index >= 0 && pick.index < all.size()) {
            return all.get(pick.index);
        }
        return null;
    }

    // by label alone, while more tracks are still on the way
    @Nullable
    static Found findExact(@NonNull final Tracks tracks, final int type, @NonNull final Pick pick) {
        return pick.off ? null : byLabel(list(tracks, type), pick);
    }

    @Nullable
    private static Found byLabel(final List<Found> all, final Pick pick) {
        if (pick.label == null) {
            return null;
        }
        Found found = null;
        for (int i = 0; i < all.size(); i++) {
            final Pick candidate = describe(all.get(i));
            if (pick.label.equals(candidate.label) && sameLanguage(pick, candidate)) {
                if (found == null || i == pick.index) {
                    found = all.get(i);
                }
            }
        }
        return found;
    }

    private static List<Found> list(final Tracks tracks, final int type) {
        final List<Found> all = new ArrayList<>();
        for (final Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != type) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                all.add(new Found(group, i));
            }
        }
        return all;
    }

    private static Pick describe(final Found f) {
        final Format format = f.group.getTrackFormat(f.track);
        final Pick p = new Pick();
        p.language = format.language == null ? null
                : Util.normalizeLanguageCode(format.language).toLowerCase(Locale.ROOT);
        if ("und".equals(p.language)) {
            p.language = null;
        }
        p.label = format.label == null || format.label.trim().isEmpty() ? null : format.label.trim();
        final String label = p.label == null ? "" : p.label;
        p.forced = (format.selectionFlags & C.SELECTION_FLAG_FORCED) != 0
                || FORCED.matcher(label).find();
        p.sdh = TrackNames.hardOfHearing(format);
        return p;
    }

    private static boolean sameLanguage(final Pick a, final Pick b) {
        return a.language == null ? b.language == null : a.language.equals(b.language);
    }

    private static boolean sameKind(final Pick a, final Pick b) {
        return sameLanguage(a, b) && a.forced == b.forced && a.sdh == b.sdh;
    }

    static void save(@NonNull final SharedPreferences prefs, final int type,
                     @Nullable final String filmKey, @Nullable final String showKey,
                     @NonNull final Pick pick) {
        final JSONObject root = read(prefs);
        try {
            if (filmKey != null) {
                put(root, "films", entry(type, filmKey), pick.toJson());
            }
            if (showKey != null) {
                put(root, "shows", entry(type, showKey), pick.toJson());
            }
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(PREF_KEY, root.toString()).apply();
    }

    @Nullable
    static Pick forFilm(@NonNull final SharedPreferences prefs, final int type,
                        @Nullable final String filmKey) {
        return get(read(prefs), "films", filmKey == null ? null : entry(type, filmKey));
    }

    @Nullable
    static Pick forShow(@NonNull final SharedPreferences prefs, final int type,
                        @Nullable final String showKey) {
        return get(read(prefs), "shows", showKey == null ? null : entry(type, showKey));
    }

    // "s:" and the show's name when the name reads as an episode
    @Nullable
    static String showKey(@Nullable final String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        final NameReading.Parsed p = NameReading.parse(name);
        if (p == null || (p.episode == null && p.season == null)
                || p.title == null || p.title.trim().isEmpty()) {
            return null;
        }
        return "s:" + p.title.trim().toLowerCase(Locale.ROOT);
    }

    private static String entry(final int type, final String key) {
        return (type == C.TRACK_TYPE_AUDIO ? "a|" : "t|") + key;
    }

    private static JSONObject read(final SharedPreferences prefs) {
        try {
            return new JSONObject(prefs.getString(PREF_KEY, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    @Nullable
    private static Pick get(final JSONObject root, final String table, @Nullable final String key) {
        if (key == null) {
            return null;
        }
        final JSONObject map = root.optJSONObject(table);
        final JSONObject o = map == null ? null : map.optJSONObject(key);
        return o == null ? null : Pick.fromJson(o);
    }

    // the newest last; the oldest go beyond MAX_ENTRIES
    private static void put(final JSONObject root, final String table, final String key,
                            final JSONObject value) throws JSONException {
        JSONObject map = root.optJSONObject(table);
        if (map == null) {
            map = new JSONObject();
        }
        map.remove(key);
        map.put(key, value);
        while (map.length() > MAX_ENTRIES) {
            final Iterator<String> keys = map.keys();
            map.remove(keys.next());
        }
        root.put(table, map);
    }

    @Nullable
    private static String emptyToNull(@Nullable final String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
