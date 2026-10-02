package com.brouken.player.osd.audio;

import android.content.Context;

import androidx.annotation.NonNull;

import com.brouken.player.osd.OsdSettingsAdapter;
import com.brouken.player.osd.item.AudioDelayOsdSettingsItem;
import com.brouken.player.osd.item.IntegerOsdSettingsItem;
import com.brouken.player.osd.item.OsdSettingsItem;

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

        void onAudioDelayChange(int delayMs);

    }
}
