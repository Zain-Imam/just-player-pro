package com.brouken.player.osd.item;

import android.content.Context;

import com.brouken.player.R;
import com.brouken.player.osd.OsdSettingsAdapter;

public final class SubtitleDelayOsdSettingsItem extends DelayOsdSettingsItem {

    public SubtitleDelayOsdSettingsItem(Context context, int value, IntegerOsdSettingsItem.Listener listener, OsdSettingsAdapter adapter) {
        super(context.getString(R.string.osd_subtitle_delay_title), value, listener, adapter);
    }

}
