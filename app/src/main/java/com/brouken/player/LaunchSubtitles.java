package com.brouken.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

// subtitles in launch extras; apps send Uri or String, as arrays or ArrayLists
public final class LaunchSubtitles {

    public static final String[] FILES = {"subs"};

    public static final String[] ENABLE = {"subs.enable"};

    public static final String[] NAMES = {"subs.name", "subs.titles", "subs.filename"};

    public static final String[] LANGUAGES = {"subs.langs", "subs.languages"};

    private LaunchSubtitles() {
    }

    public static boolean present(final Bundle bundle) {
        if (bundle == null) {
            return false;
        }
        for (final String key : FILES) {
            if (bundle.containsKey(key)) {
                return true;
            }
        }
        for (final String key : ENABLE) {
            if (bundle.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    // keeps every position, null where unusable, so names and languages stay aligned
    @NonNull
    public static List<Uri> urisByPosition(final Bundle bundle, final String... keys) {
        return collect(new ByPosition(), bundle, keys);
    }

    // marker type: add() keeps nulls and repeats in this list
    private static final class ByPosition extends ArrayList<Uri> {
    }

    @NonNull
    public static List<Uri> uris(final Bundle bundle, final String... keys) {
        return collect(new ArrayList<>(), bundle, keys);
    }

    @NonNull
    private static List<Uri> collect(final List<Uri> found, final Bundle bundle,
                                     final String... keys) {
        if (bundle == null) {
            return found;
        }
        for (final String key : keys) {
            if (!bundle.containsKey(key)) {
                continue;
            }
            addParcelableArray(found, bundle, key);
            addParcelableList(found, bundle, key);
            addStrings(found, bundle.getStringArray(key));
            final ArrayList<String> list = stringList(bundle, key);
            if (list != null) {
                addStrings(found, list.toArray(new String[0]));
            }
            // A single one, not an array of one.
            final Object single = value(bundle, key);
            if (single instanceof Uri) {
                add(found, (Uri) single);
            } else if (single instanceof CharSequence) {
                add(found, parse(single.toString()));
            }
            if (!found.isEmpty()) {
                return found;
            }
        }
        return found;
    }

    @NonNull
    public static String[] strings(final Bundle bundle, final String... keys) {
        if (bundle == null) {
            return new String[0];
        }
        for (final String key : keys) {
            final String[] array = bundle.getStringArray(key);
            if (array != null && array.length > 0) {
                return array;
            }
            final ArrayList<String> list = stringList(bundle, key);
            if (list != null && !list.isEmpty()) {
                return list.toArray(new String[0]);
            }
            final Object single = value(bundle, key);
            if (single instanceof CharSequence) {
                return new String[]{single.toString()};
            }
        }
        return new String[0];
    }

    private static void addParcelableArray(final List<Uri> into, final Bundle bundle,
                                           final String key) {
        final Parcelable[] array;
        try {
            array = bundle.getParcelableArray(key);
        } catch (Exception e) {
            return;
        }
        if (array == null) {
            return;
        }
        for (final Parcelable item : array) {
            if (item instanceof Uri) {
                add(into, (Uri) item);
            } else if (item != null) {
                add(into, parse(item.toString()));
            }
        }
    }

    private static void addParcelableList(final List<Uri> into, final Bundle bundle,
                                          final String key) {
        // unchecked list that may hold strings, so each item is type-checked
        final ArrayList<?> list;
        try {
            list = bundle.getParcelableArrayList(key);
        } catch (Exception e) {
            return;
        }
        if (list == null) {
            return;
        }
        for (final Object item : list) {
            if (item instanceof Uri) {
                add(into, (Uri) item);
            } else if (item instanceof Parcelable) {
                add(into, parse(item.toString()));
            }
            // Strings are read once, by the string path.
        }
    }

    // unpacking a key whose class this app lacks throws; treat it as absent
    private static Object value(final Bundle bundle, final String key) {
        try {
            return bundle.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private static ArrayList<String> stringList(final Bundle bundle, final String key) {
        final ArrayList<?> raw;
        try {
            raw = bundle.getStringArrayList(key);
        } catch (Exception e) {
            return null;
        }
        if (raw == null) {
            return null;
        }
        // item by item: a list of Uris can come back under this key too
        final ArrayList<String> strings = new ArrayList<>();
        for (final Object item : raw) {
            if (item instanceof CharSequence) {
                strings.add(item.toString());
            }
        }
        return strings;
    }

    private static void addStrings(final List<Uri> into, final String[] values) {
        if (values == null) {
            return;
        }
        for (final String value : values) {
            add(into, parse(value));
        }
    }

    private static Uri parse(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            final Uri uri = Uri.parse(value.trim());
            // A bare path with no scheme is still a file somebody meant.
            return uri.getScheme() == null ? Uri.fromFile(new java.io.File(value.trim())) : uri;
        } catch (Exception e) {
            return null;
        }
    }

    private static void add(final List<Uri> into, final Uri uri) {
        if (into instanceof ByPosition) {
            into.add(uri);
            return;
        }
        if (uri != null && !into.contains(uri)) {
            into.add(uri);
        }
    }
}
