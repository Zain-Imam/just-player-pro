package com.brouken.player.osd.item;

import android.content.Context;

import com.brouken.player.R;
import com.brouken.player.osd.OsdSettingsAdapter;

// value in ms, shown as signed seconds in the app's language
public class DelayOsdSettingsItem extends IntegerOsdSettingsItem {

    private final String secondsUnit;
    private final String minutesUnit;

    public DelayOsdSettingsItem(Context context, String title, int value, IntegerOsdSettingsItem.Listener listener, OsdSettingsAdapter adapter) {
        super(title, "", false, value, listener, adapter, 100);
        secondsUnit = context.getString(R.string.unit_seconds_short);
        minutesUnit = context.getString(R.string.unit_minutes_short);
        // the parent wrote the first one before the units were set
        summary = getSummaryText(value);
    }

    @Override
    protected boolean accelerates() {
        return true;
    }

    @Override
    protected String getSummaryText(int value) {
        int absMs = Math.abs(value);
        int totalTenths = (absMs + 50) / 100;
        int minutes = totalTenths / 600;
        int secondsTenths = totalTenths % 600;
        int seconds = secondsTenths / 10;
        int tenths = secondsTenths % 10;

        final char point = java.text.DecimalFormatSymbols.getInstance().getDecimalSeparator();
        String secondsPart = seconds + "" + point + tenths + " " + secondsUnit;
        String formatted = minutes > 0 ? minutes + " " + minutesUnit + " " + secondsPart : secondsPart;

        if (value < 0 && totalTenths > 0) {
            return "- " + formatted;
        } else if (value > 0 && totalTenths > 0) {
            return "+ " + formatted;
        } else {
            return formatted;
        }
    }

}
