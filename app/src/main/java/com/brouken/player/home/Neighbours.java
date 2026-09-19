package com.brouken.player.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.List;

/**
 * What sits either side of the film that is playing.
 *
 * <p>Answered from the folder it was opened from, in the order that folder was
 * being shown in — the same sort, the same direction. Somebody who sorted a
 * folder oldest-first and pressed play on the top one means the second-oldest
 * by "next", and alphabetical order would be a different film entirely.
 *
 * <p>Read fresh rather than carried in the intent. A folder of five hundred
 * clips is several hundred kilobytes of addresses, which an intent will not
 * take, and a list carried across would go stale the moment a file was deleted.
 * One media store query answers it and cannot be wrong.
 */
public final class Neighbours {

    /** What is before and after, either of which may be nothing. */
    public static final class Either {
        @Nullable
        public final Uri previous;
        @Nullable
        public final Uri next;

        Either(@Nullable final Uri previous, @Nullable final Uri next) {
            this.previous = previous;
            this.next = next;
        }

        public boolean any() {
            return previous != null || next != null;
        }
    }

    private static final Either NOTHING = new Either(null, null);

    private Neighbours() {
    }

    /** Nothing either side, for a film that belongs to no list. */
    public static Either none() {
        return NOTHING;
    }

    /**
     * The films either side of this one in this folder.
     *
     * <p>Runs a media store query, so never on the main thread.
     */
    @NonNull
    public static Either of(@NonNull final Context context, @Nullable final String folderId,
                            @Nullable final Uri current) {
        if (folderId == null || current == null) {
            return NOTHING;
        }
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        final List<Library.Video> videos =
                Library.inFolder(Library.videos(context), folderId);
        if (videos.size() < 2) {
            return NOTHING;
        }
        Sort.apply(videos, Sort.videos(preferences), Sort.videosReversed(preferences));

        int at = -1;
        for (int i = 0; i < videos.size(); i++) {
            if (videos.get(i).uri.equals(current)) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            // The film is not in the folder it claimed to come from: deleted
            // while playing, or handed over by something else with a folder
            // attached. Offering a guess would be worse than offering nothing.
            return NOTHING;
        }
        return new Either(
                at > 0 ? videos.get(at - 1).uri : null,
                at < videos.size() - 1 ? videos.get(at + 1).uri : null);
    }
}
