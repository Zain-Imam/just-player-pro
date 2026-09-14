package com.brouken.player.osd.audio;

import android.content.Context;

import androidx.annotation.NonNull;

import com.brouken.player.osd.OsdSettingsAdapter;
import com.brouken.player.osd.item.AudioDelayOsdSettingsItem;
import com.brouken.player.osd.item.IntegerOsdSettingsItem;
import com.brouken.player.osd.item.OsdSettingsItem;

/**
 * The sound's own panel, reached from the audio button.
 *
 * The subtitle button has always opened a list of subtitle tracks and, at the
 * end of it, everything else about subtitles -- including their delay. The
 * audio button opened a list of tracks and stopped there, so the one setting
 * that belongs to a soundtrack lived only in the quick panel, which is not
 * where anybody adjusting the sound would look for it.
 */
public class AudioOsdSettingsAdapter extends OsdSettingsAdapter {

    private final Listener listener;

    public AudioOsdSettingsAdapter(@NonNull Context context, @NonNull Listener listener) {
        super(context);
        this.listener = listener;
    }

    public void setInitialValues(final int audioDelayMs) {
        this.items = new OsdSettingsItem[]{createDelayItem(audioDelayMs)};
    }

    private OsdSettingsItem createDelayItem(final int audioDelayMs) {
        final IntegerOsdSettingsItem.Listener itemListener =
                (position, newValue) -> listener.onAudioDelayChange(newValue);
        return new AudioDelayOsdSettingsItem(context, audioDelayMs, itemListener, this);
    }

    public interface Listener {

        /** Move the sound against the picture, in milliseconds. */
        void onAudioDelayChange(int delayMs);

    }
}
