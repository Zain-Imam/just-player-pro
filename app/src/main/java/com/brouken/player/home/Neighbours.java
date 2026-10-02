package com.brouken.player.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.List;

// Previous and next file in the folder a video was opened from, in the order
// the home screen shows it. Queried fresh: a file list is too big for an intent.
public final class Neighbours {

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

    public static Either none() {
        return NOTHING;
    }

    // runs a media store query: not on the main thread
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
            // not in that folder, e.g. deleted while playing
            return NOTHING;
        }
        return new Either(
                at > 0 ? videos.get(at - 1).uri : null,
                at < videos.size() - 1 ? videos.get(at + 1).uri : null);
    }
}
