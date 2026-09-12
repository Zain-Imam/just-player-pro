package com.brouken.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/*
 * Subtitles handed over by whatever app started the player.
 *
 * There is one convention here and about five spellings of it. The keys are
 * agreed — "subs" for the files, "subs.enable" for the one to turn on,
 * "subs.name" for what to call them — but what goes in them is not: some apps
 * put an array of Uri in the bundle, some an ArrayList of Uri, some an array of
 * plain strings, some an ArrayList of strings. Reading only the first of those,
 * which is what this did, means a subtitle sent by one app arrives and a
 * subtitle sent by the next is silently dropped and the film plays without it.
 *
 * Every shape is read, and the alternative spellings of the name and language
 * keys with them, so that a subtitle offered is a subtitle used.
 */
public final class LaunchSubtitles {

    /** Where the files themselves come in. */
    public static final String[] FILES = {"subs"};

    /** Which of them to turn on. */
    public static final String[] ENABLE = {"subs.enable"};

    /** What to call them in the picker. */
    public static final String[] NAMES = {"subs.name", "subs.titles", "subs.filename"};

    /** Which language each one is. */
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

    @NonNull
    public static List<Uri> uris(final Bundle bundle, final String... keys) {
        final List<Uri> found = new ArrayList<>();
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

    // ---------------------------------------------------------------- shapes

    private static void addParcelableArray(final List<Uri> into, final Bundle bundle,
                                           final String key) {
        final Parcelable[] array;
        try {
            array = bundle.getParcelableArray(key);
        } catch (Exception e) {
            // A bundle carrying something that is not Parcelable under this key.
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
        final ArrayList<Parcelable> list;
        try {
            list = bundle.getParcelableArrayList(key);
        } catch (Exception e) {
            return;
        }
        if (list == null) {
            return;
        }
        for (final Parcelable item : list) {
            if (item instanceof Uri) {
                add(into, (Uri) item);
            } else if (item != null) {
                add(into, parse(item.toString()));
            }
        }
    }

    /*
     * Reading one value out of a bundle without trusting it.
     *
     * A bundle arrives as bytes and is only unpacked when something asks for a
     * key. Asking for one whose class this app does not have throws, and a
     * player that crashes because another app put something unexpected in an
     * extra is worse than a player that ignores it.
     */
    private static Object value(final Bundle bundle, final String key) {
        try {
            return bundle.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private static ArrayList<String> stringList(final Bundle bundle, final String key) {
        try {
            return bundle.getStringArrayList(key);
        } catch (Exception e) {
            return null;
        }
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
        if (uri != null && !into.contains(uri)) {
            into.add(uri);
        }
    }
}
