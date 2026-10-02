package com.brouken.player.osd.item;

import android.content.Context;

import com.brouken.player.R;
import com.brouken.player.osd.OsdSettingsAdapter;

// bounded: after a seek one stream has to catch up by the whole offset
public final class AudioDelayOsdSettingsItem extends DelayOsdSettingsItem {

    private static final int LIMIT_MS = 5000;

    public AudioDelayOsdSettingsItem(Context context, int value, IntegerOsdSettingsItem.Listener listener, OsdSettingsAdapter adapter) {
        super(context.getString(R.string.osd_audio_delay_title), value, listener, adapter);
    }

    @Override
    protected int clamp(int value) {
        return Math.max(-LIMIT_MS, Math.min(LIMIT_MS, value));
    }

}
