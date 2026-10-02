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

// exports and restores preferences; folder grants cannot travel and are left out
public final class Backup {

    private static final String MARK = "just-player-pro-backup";
    private static final int FORMAT = 1;

    public enum Part {
        SETTINGS,
        KEYS,
        HISTORY,
        PER_FILE
    }

    private Backup() {
    }

    private static final String[] KEY_PREFIXES = {"apiKey", "subtitleAddon"};
    private static final String[] HISTORY_KEYS = {"urlHistory", "onlineIdentities"};
    private static final String[] PER_FILE_KEYS = {"subtitleDelayMap", "audioDelayMap", "speedMap", "aspectMap"};

    // device-specific: folder grants and the current playback state
    private static final String[] NEVER = {
            "scopeUri", "scopeUris", "mediaUri", "mediaType",
            "subtitleUri", "subtitleUris", "audioTrackId", "subtitleTrackId",
            "firstRun", "subtitleCustomFontName", "subtitleAddonsSeeded",
            // launch headers may carry tokens; the rest points at files on this device
            "launchMemory", "subtitleLabels", "aspectStep", "aspectStepUri"
    };

    public static String export(final Context context, final Set<Part> parts) throws JSONException {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        return write(preferences.getAll(), parts);
    }

    // split from the preferences so the format can be unit tested
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

    public static final class Result {
        public final int applied;
        public final boolean recognised;

        Result(final int applied, final boolean recognised) {
            this.applied = applied;
            this.recognised = recognised;
        }
    }

    // unreadable entries are skipped so a file from a newer version still restores
    public static Result restore(final Context context, final String json) {
        final Map<String, Object> values = read(json);
        if (values == null) {
            return new Result(0, false);
        }
        movePre42MpvSize(values);
        final SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences(context).edit();
        for (final Map.Entry<String, Object> entry : values.entrySet()) {
            put(editor, entry.getKey(), entry.getValue());
        }
        editor.apply();
        return new Result(values.size(), true);
    }

    // an older file holds mpv's size on the old scale; moved the way Prefs moves it
    static void movePre42MpvSize(final Map<String, Object> values) {
        final Object size = values.get(Prefs.PREF_KEY_SUBTITLE_SIZE_MPV);
        if (size instanceof Integer && !values.containsKey(Prefs.PREF_KEY_MPV_SIZE_MOVED)) {
            values.put(Prefs.PREF_KEY_SUBTITLE_SIZE_MPV, Prefs.mpvSizeFrom41((Integer) size));
            values.put(Prefs.PREF_KEY_MPV_SIZE_MOVED, true);
        }
    }

    // null for a file that is not a backup; an empty map for an empty backup
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

    public static String suggestedFileName() {
        final java.text.SimpleDateFormat day =
                new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return "just-player-pro-" + day.format(new java.util.Date()) + ".json";
    }

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
