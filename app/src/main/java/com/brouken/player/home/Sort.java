package com.brouken.player.home;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.brouken.player.R;

import java.text.Collator;
import java.util.Collections;
import java.util.List;

/**
 * What order the two lists come in, and which way round, remembered between
 * sessions.
 *
 * <p>Names are compared with a {@link Collator} rather than by character, so
 * "Ätherwelle" files beside "Atlas" instead of after "Zulu", and case never
 * decides anything. Every other order falls back to the name when two entries
 * tie, so a list never reshuffles itself between two equal items.
 *
 * <p>The direction is "reversed" rather than "ascending", because ascending
 * means nothing consistent here: sorting by name starts at A, and sorting by
 * size starts at the biggest, and both of those are what somebody asking for
 * that order wants first. Each order names its own two directions — "A to Z"
 * and "Z to A", "Largest first" and "Smallest first" — which is the only way
 * the words stay true whichever column is chosen.
 */
public final class Sort {

    private static final String PREF_KEY_FOLDERS = "homeFolderSort";
    private static final String PREF_KEY_VIDEOS = "homeVideoSort";
    private static final String PREF_KEY_FOLDERS_REVERSED = "homeFolderSortReversed";
    private static final String PREF_KEY_VIDEOS_REVERSED = "homeVideoSortReversed";

    private static final Collator COLLATOR = collator();

    private static Collator collator() {
        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.SECONDARY);
        return collator;
    }

    public enum Folders {
        NAME(R.string.home_sort_name, R.string.home_order_az, R.string.home_order_za),
        COUNT(R.string.home_sort_count, R.string.home_order_most, R.string.home_order_fewest),
        SIZE(R.string.home_sort_size, R.string.home_order_largest, R.string.home_order_smallest),
        RECENT(R.string.home_sort_date, R.string.home_order_newest, R.string.home_order_oldest);

        public final int label;
        /** What this order puts first, and what reversing it puts first. */
        public final int first;
        public final int reversedFirst;

        Folders(final int label, final int first, final int reversedFirst) {
            this.label = label;
            this.first = first;
            this.reversedFirst = reversedFirst;
        }
    }

    public enum Videos {
        NAME(R.string.home_sort_name, R.string.home_order_az, R.string.home_order_za),
        RECENT(R.string.home_sort_date, R.string.home_order_newest, R.string.home_order_oldest),
        SIZE(R.string.home_sort_size, R.string.home_order_largest, R.string.home_order_smallest),
        DURATION(R.string.home_sort_duration, R.string.home_order_longest,
                R.string.home_order_shortest);

        public final int label;
        public final int first;
        public final int reversedFirst;

        Videos(final int label, final int first, final int reversedFirst) {
            this.label = label;
            this.first = first;
            this.reversedFirst = reversedFirst;
        }
    }

    private Sort() {
    }

    // ------------------------------------------------------------ storage

    @NonNull
    public static Folders folders(@NonNull final SharedPreferences preferences) {
        return read(preferences, PREF_KEY_FOLDERS, Folders.class, Folders.NAME);
    }

    public static void setFolders(@NonNull final SharedPreferences preferences,
                                  @NonNull final Folders order) {
        preferences.edit().putString(PREF_KEY_FOLDERS, order.name()).apply();
    }

    @NonNull
    public static Videos videos(@NonNull final SharedPreferences preferences) {
        return read(preferences, PREF_KEY_VIDEOS, Videos.class, Videos.NAME);
    }

    public static void setVideos(@NonNull final SharedPreferences preferences,
                                 @NonNull final Videos order) {
        preferences.edit().putString(PREF_KEY_VIDEOS, order.name()).apply();
    }

    /*
     * The direction is kept apart from the column, so changing one does not
     * throw away the other: somebody who likes the biggest first and switches
     * from size to length means the longest first, not the shortest.
     */
    public static boolean foldersReversed(@NonNull final SharedPreferences preferences) {
        return preferences.getBoolean(PREF_KEY_FOLDERS_REVERSED, false);
    }

    public static void setFoldersReversed(@NonNull final SharedPreferences preferences,
                                          final boolean reversed) {
        preferences.edit().putBoolean(PREF_KEY_FOLDERS_REVERSED, reversed).apply();
    }

    public static boolean videosReversed(@NonNull final SharedPreferences preferences) {
        return preferences.getBoolean(PREF_KEY_VIDEOS_REVERSED, false);
    }

    public static void setVideosReversed(@NonNull final SharedPreferences preferences,
                                         final boolean reversed) {
        preferences.edit().putBoolean(PREF_KEY_VIDEOS_REVERSED, reversed).apply();
    }

    private static <T extends Enum<T>> T read(final SharedPreferences preferences, final String key,
                                              final Class<T> type, final T fallback) {
        final String stored = preferences.getString(key, null);
        if (stored == null || stored.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, stored);
        } catch (IllegalArgumentException error) {
            // An order removed in a later version, or a hand-edited file.
            return fallback;
        }
    }

    // ------------------------------------------------------------ applying

    public static void apply(@NonNull final List<Library.Folder> folders,
                             @NonNull final Folders order) {
        apply(folders, order, false);
    }

    public static void apply(@NonNull final List<Library.Folder> folders,
                             @NonNull final Folders order, final boolean reversed) {
        final int way = reversed ? -1 : 1;
        Collections.sort(folders, (left, right) -> {
            final int result;
            switch (order) {
                case COUNT:
                    result = Integer.compare(right.count, left.count);
                    break;
                case SIZE:
                    result = Long.compare(right.size, left.size);
                    break;
                case RECENT:
                    result = Long.compare(right.modified, left.modified);
                    break;
                case NAME:
                default:
                    result = 0;
                    break;
            }
            // The name breaks a tie in the direction asked for too, so a
            // reversed list is the same list upside down and not a different
            // one with the ties left where they were.
            return way * (result != 0 ? result : COLLATOR.compare(left.name, right.name));
        });
    }

    public static void apply(@NonNull final List<Library.Video> videos,
                             @NonNull final Videos order) {
        apply(videos, order, false);
    }

    public static void apply(@NonNull final List<Library.Video> videos,
                             @NonNull final Videos order, final boolean reversed) {
        final int way = reversed ? -1 : 1;
        Collections.sort(videos, (left, right) -> {
            final int result;
            switch (order) {
                case RECENT:
                    result = Long.compare(right.modified, left.modified);
                    break;
                case SIZE:
                    result = Long.compare(right.size, left.size);
                    break;
                case DURATION:
                    result = Long.compare(right.duration, left.duration);
                    break;
                case NAME:
                default:
                    result = 0;
                    break;
            }
            return way * (result != 0 ? result : COLLATOR.compare(left.name, right.name));
        });
    }
}
