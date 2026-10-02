package com.brouken.player.home;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.brouken.player.R;

import java.text.Collator;
import java.util.Collections;
import java.util.List;

// Saved sort orders for the folder and file lists. Names compare with a Collator
// and break ties; unreversed puts A, the largest or the newest first.
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

    // direction is stored apart from the column, so it survives a column change
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
            // ties reverse too, so a reversed list is this one upside down
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
