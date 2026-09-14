package com.brouken.player.osd.item;

import com.brouken.player.osd.OsdSettingsAdapter;

/**
 * A delay in milliseconds, shown as a signed number of seconds.
 *
 * The subtitles and the sound each have one, they are read the same way -- a
 * sign, a tenth of a second, and minutes once there are any -- so they are
 * written the same way, once, here.
 */
public class DelayOsdSettingsItem extends IntegerOsdSettingsItem {

    public DelayOsdSettingsItem(String title, int value, IntegerOsdSettingsItem.Listener listener, OsdSettingsAdapter adapter) {
        super(title, "", false, value, listener, adapter, 100);
    }

    @Override
    protected String getSummaryText(int value) {
        int absMs = Math.abs(value);
        int totalTenths = (absMs + 50) / 100;
        int minutes = totalTenths / 600;
        int secondsTenths = totalTenths % 600;
        int seconds = secondsTenths / 10;
        int tenths = secondsTenths % 10;

        String secondsPart = seconds + "." + tenths + " s";
        String formatted = minutes > 0 ? minutes + " m " + secondsPart : secondsPart;

        if (value < 0 && totalTenths > 0) {
            return "- " + formatted;
        } else if (value > 0 && totalTenths > 0) {
            return "+ " + formatted;
        } else {
            return formatted;
        }
    }

}
