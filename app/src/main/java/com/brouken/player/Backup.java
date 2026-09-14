package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything the player remembers, written out and read back in.
 *
 * For a new phone, a second device, or the day something is cleared by
 * accident. The settings are the easy part; what actually hurts to lose is the
 * keys -- a TMDB key, an OpenSubtitles login, the addons -- because those were
 * typed in one character at a time from another screen.
 *
 * What cannot travel is said plainly rather than exported and quietly broken:
 * the folders the player may read are Android's grants to this installation on
 * this device, and no file can carry them to another. So they are left out, and
 * the person importing is told to grant them again.
 */
public final class Backup {

    /** What the file is, so a file that is not one of ours can be refused. */
    private static final String MARK = "just-player-pro-backup";
    private static final int FORMAT = 1;

    public enum Part {
        /** How the player behaves: engine, subtitles, gestures, everything. */
        SETTINGS,
        /** Keys and logins for the services, and the subtitle addons. */
        KEYS,
        /** What has been played, and the titles it was identified as. */
        HISTORY,
        /** Per-file memory: subtitle and audio delays, and speeds. */
        PER_FILE
    }

    private Backup() {
    }

    /*
     * Three lists rather than one.
     *
     * Keys are worth keeping apart because they are the part someone may not
     * want in a file they hand to somebody else. The per-file memory is worth
     * keeping apart because it is about one person's copies of one person's
     * films and means nothing on another device. History likewise.
     *
     * Everything that is none of these is a setting.
     */
    private static final String[] KEY_PREFIXES = {"apiKey", "subtitleAddon"};
    private static final String[] HISTORY_KEYS = {"urlHistory", "onlineIdentities"};
    private static final String[] PER_FILE_KEYS = {"subtitleDelayMap", "audioDelayMap", "speedMap"};

    /*
     * Never exported, whatever is asked for.
     *
     * The folder grants belong to this installation and cannot be given away.
     * The rest is where this device happened to be up to -- the file it was
     * last playing, where it had got to in it -- which is not a setting anybody
     * wants carried to another device, and would point at files that are not
     * there.
     */
    private static final String[] NEVER = {
            "scopeUri", "scopeUris", "mediaUri", "mediaType",
            "subtitleUri", "subtitleUris", "audioTrackId", "subtitleTrackId",
            "firstRun", "subtitleCustomFontName", "subtitleAddonsSeeded"
    };

    /** The whole of what was asked for, as a JSON document. */
    public static String export(final Context context, final Set<Part> parts) throws JSONException {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        return write(preferences.getAll(), parts);
    }

    /**
     * The document, from a plain map of what is stored.
     *
     * Separated from the preferences themselves so the format can be tested
     * without a device: what goes wrong in a backup is never the reading of a
     * file, it is a value that comes back a different type from the one that
     * went in, and that is testable on any machine.
     */
    public static String write(final Map<String, ?> stored, final Set<Part> parts)
            throws JSONException {
        final JSONObject values = new JSONObject();
        for (final Map.Entry<String, ?> entry : stored.entrySet()) {
            final String key = entry.getKey();
            if (isNever(key) || !parts.contains(partOf(key))) {
                continue;
            }
            final JSONObject typed = typed(entry.getValue());
            if (typed != null) {
                values.put(key, typed);
            }
        }

        final JSONArray asked = new JSONArray();
        for (final Part part : parts) {
            asked.put(part.name().toLowerCase(Locale.ROOT));
        }

        final JSONObject document = new JSONObject();
        document.put("format", MARK);
        document.put("version", FORMAT);
        document.put("app", BuildConfig.VERSION_NAME);
        document.put("exported", System.currentTimeMillis());
        document.put("parts", asked);
        document.put("values", values);
        return document.toString(2);
    }

    /** What an import did, so the person who asked can be told. */
    public static final class Result {
        public final int applied;
        public final boolean recognised;

        Result(final int applied, final boolean recognised) {
            this.applied = applied;
            this.recognised = recognised;
        }
    }

    /**
     * Put back whatever the file happens to hold.
     *
     * No choosing on the way in: a file holds what it holds, and asking again
     * which half of it to use is a question nobody has the information to
     * answer. Anything unreadable is skipped rather than abandoning the rest,
     * so a file from a newer version still restores what this one understands.
     */
    public static Result restore(final Context context, final String json) {
        final Map<String, Object> values = read(json);
        if (values == null) {
            return new Result(0, false);
        }
        final SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences(context).edit();
        for (final Map.Entry<String, Object> entry : values.entrySet()) {
            put(editor, entry.getKey(), entry.getValue());
        }
        editor.apply();
        return new Result(values.size(), true);
    }

    /**
     * What a document holds, as values of the types they were stored as.
     *
     * Null for a file that is not one of ours. An empty map for one of ours
     * that carries nothing -- which is a different thing, and the person who
     * imported it deserves to be told which happened.
     */
    @Nullable
    public static Map<String, Object> read(final String json) {
        final JSONObject document;
        try {
            document = new JSONObject(json);
        } catch (JSONException e) {
            return null;
        }
        if (!MARK.equals(document.optString("format"))) {
            return null;
        }
        final Map<String, Object> values = new java.util.LinkedHashMap<>();
        final JSONObject stored = document.optJSONObject("values");
        if (stored == null) {
            return values;
        }
        final java.util.Iterator<String> keys = stored.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (isNever(key)) {
                continue;
            }
            final JSONObject typed = stored.optJSONObject(key);
            if (typed == null) {
                continue;
            }
            final Object value = value(typed);
            if (value != null) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static Part partOf(final String key) {
        for (final String prefix : KEY_PREFIXES) {
            if (key.startsWith(prefix)) {
                return Part.KEYS;
            }
        }
        for (final String name : HISTORY_KEYS) {
            if (name.equals(key)) {
                return Part.HISTORY;
            }
        }
        for (final String name : PER_FILE_KEYS) {
            if (name.equals(key)) {
                return Part.PER_FILE;
            }
        }
        return Part.SETTINGS;
    }

    private static boolean isNever(final String key) {
        for (final String name : NEVER) {
            if (name.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** A value with its type beside it, because preferences are typed. */
    @Nullable
    private static JSONObject typed(final Object value) {
        try {
            final JSONObject object = new JSONObject();
            if (value instanceof Boolean) {
                object.put("t", "b").put("v", value);
            } else if (value instanceof Integer) {
                object.put("t", "i").put("v", value);
            } else if (value instanceof Long) {
                object.put("t", "l").put("v", value);
            } else if (value instanceof Float) {
                object.put("t", "f").put("v", (double) (Float) value);
            } else if (value instanceof String) {
                object.put("t", "s").put("v", value);
            } else if (value instanceof Set) {
                final JSONArray array = new JSONArray();
                for (final Object item : (Set<?>) value) {
                    array.put(String.valueOf(item));
                }
                object.put("t", "set").put("v", array);
            } else {
                return null;
            }
            return object;
        } catch (JSONException e) {
            return null;
        }
    }

    /** One stored entry, back as the type it was written as. */
    @Nullable
    private static Object value(@NonNull final JSONObject typed) {
        switch (typed.optString("t")) {
            case "b":
                return typed.optBoolean("v");
            case "i":
                return typed.optInt("v");
            case "l":
                return typed.optLong("v");
            case "f":
                return (float) typed.optDouble("v");
            case "s":
                return typed.optString("v");
            case "set": {
                final JSONArray array = typed.optJSONArray("v");
                if (array == null) {
                    return null;
                }
                final Set<String> items = new LinkedHashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    items.add(array.optString(i));
                }
                return items;
            }
            default:
                return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(final SharedPreferences.Editor editor, final String key,
                            final Object value) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Set) {
            editor.putStringSet(key, (Set<String>) value);
        }
    }

    /** A name with the day in it, so two exports do not look alike. */
    public static String suggestedFileName() {
        final java.text.SimpleDateFormat day =
                new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return "just-player-pro-" + day.format(new java.util.Date()) + ".json";
    }

    /** The parts a file actually carries, for telling someone what they imported. */
    public static List<String> partsIn(final String json) {
        final List<String> named = new ArrayList<>();
        try {
            final JSONArray parts = new JSONObject(json).optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    named.add(parts.optString(i));
                }
            }
        } catch (JSONException e) {
            // Nothing to name.
        }
        return named;
    }
}
