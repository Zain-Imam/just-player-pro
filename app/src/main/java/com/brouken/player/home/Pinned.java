package com.brouken.player.home;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.brouken.player.Utils;

import org.json.JSONArray;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The folders kept at the top of the home screen.
 *
 * <p>Stored as one preference so it travels with an export like everything
 * else — {@code Backup} files an unrecognised key under settings, which is
 * where a list of favourite folders belongs.
 *
 * <p>A pin is held against the folder's path, so it survives a reboot, a
 * rescan and an upgrade. A pinned folder that is no longer there is not an
 * error: the card may simply be out. It stays pinned and reappears with the
 * card, rather than being quietly forgotten the one time it was unplugged.
 */
public final class Pinned {

    private static final String PREF_KEY = "homePinnedFolders";

    private Pinned() {
    }

    @NonNull
    public static Set<String> load(@NonNull final SharedPreferences preferences) {
        final Set<String> pinned = new LinkedHashSet<>();
        final String stored = preferences.getString(PREF_KEY, null);
        if (stored == null || stored.isEmpty()) {
            return pinned;
        }
        try {
            final JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length(); i++) {
                final String id = array.optString(i, null);
                if (id != null && !id.isEmpty()) {
                    pinned.add(id);
                }
            }
        } catch (Exception error) {
            // A list of favourites is a convenience. Starting without it beats
            // refusing to open the home screen.
            Utils.log("Discarding unreadable pins: " + error);
        }
        return pinned;
    }

    public static boolean isPinned(@NonNull final SharedPreferences preferences,
                                   @Nullable final String folderId) {
        return folderId != null && load(preferences).contains(folderId);
    }

    /** Pins or unpins, and answers with what the folder now is. */
    public static boolean toggle(@NonNull final SharedPreferences preferences,
                                 @NonNull final String folderId) {
        final Set<String> pinned = load(preferences);
        final boolean nowPinned;
        if (pinned.contains(folderId)) {
            pinned.remove(folderId);
            nowPinned = false;
        } else {
            pinned.add(folderId);
            nowPinned = true;
        }
        save(preferences, pinned);
        return nowPinned;
    }

    private static void save(@NonNull final SharedPreferences preferences,
                             @NonNull final Set<String> pinned) {
        final JSONArray array = new JSONArray();
        for (final String id : pinned) {
            array.put(id);
        }
        preferences.edit().putString(PREF_KEY, array.toString()).apply();
    }
}
