package com.brouken.player.osd.item;

import android.content.Context;

import com.brouken.player.R;
import com.brouken.player.osd.OsdSettingsAdapter;

/**
 * How far the sound is moved against the picture.
 *
 * Bounded, unlike the subtitle delay. A subtitle that is a minute out is a
 * subtitle for the wrong release and moving it that far is a fair thing to
 * want; sound a minute out is not a thing any file does. What the bound
 * actually protects is the picture: both engines pay for the offset by having
 * the two streams start a seek together and one of them catch up, so the
 * larger the number the longer that takes. Five seconds is past any real
 * fault -- a badly muxed track is out by tenths -- and short enough that
 * arriving at a seek is a blink.
 */
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
