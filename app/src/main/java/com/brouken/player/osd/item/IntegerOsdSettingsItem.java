package com.brouken.player.osd.item;

import com.brouken.player.osd.OsdSettingsAdapter;

public class IntegerOsdSettingsItem extends LeftOrRightOsdSettingsItem {

    private final Listener listener;
    private final OsdSettingsAdapter adapter;
    private final String labelDefault;
    private final boolean addPlusToValue;
    private final int step;

    private int currentValue;

    /*
     * The step grows while you keep going in one direction.
     *
     * Every press moved the value by one step, which for the subtitle delay is
     * 100 ms: ten seconds of delay was a hundred presses on a touchscreen, and
     * a held arrow on a remote was not much better. Nobody adjusts a delay that
     * way -- they give up and watch it out of sync.
     *
     * So a run of presses in the same direction accelerates: the first half
     * second moves at the step it always did, so a single press is still exactly
     * 100 ms and small corrections are unchanged; keep going and it moves five
     * times faster, then ten. Ten seconds is about a second and a half of
     * holding.
     *
     * A run ends when the direction changes or when the presses stop for a
     * moment, so the next deliberate press starts small again. That matters:
     * arriving near the right value at ten times the step and then wanting one
     * more notch is the common case.
     */
    private static final long RUN_BREAK_MS = 400;
    private static final long FASTER_AFTER_MS = 500;
    private static final long FASTEST_AFTER_MS = 1500;
    private static final int FASTER = 5;
    private static final int FASTEST = 10;

    private long runStartedAt;
    private long lastPressAt;
    private int runDirection;

    private int stepFor(final int direction) {
        final long now = android.os.SystemClock.uptimeMillis();
        if (direction != runDirection || now - lastPressAt > RUN_BREAK_MS) {
            runStartedAt = now;
            runDirection = direction;
        }
        lastPressAt = now;

        final long held = now - runStartedAt;
        if (held >= FASTEST_AFTER_MS) {
            return step * FASTEST;
        }
        if (held >= FASTER_AFTER_MS) {
            return step * FASTER;
        }
        return step;
    }

    public IntegerOsdSettingsItem(String title, String labelDefault, boolean addPlusToValue, int value, Listener listener, OsdSettingsAdapter adapter, int step) {
        super(title, null);

        super.listener = createLeftOrRightListener();
        this.listener = listener;
        this.adapter = adapter;
        this.labelDefault = labelDefault;
        this.addPlusToValue = addPlusToValue;
        this.step = step;

        this.currentValue = value;
        summary = getSummaryText(value);
    }

    public IntegerOsdSettingsItem(String title, String labelDefault, boolean addPlusToValue, int value, Listener listener, OsdSettingsAdapter adapter) {
        this(title, labelDefault, addPlusToValue, value, listener, adapter, 1);
    }

    protected String getSummaryText(int value) {
        if (value == 0 && labelDefault != null) {
            return labelDefault;
        } else if (value > 0 && addPlusToValue) {
            return "+" + value;
        } else {
            return String.valueOf(value);
        }
    }

    private void updateCurrentValue(int position, int newValue) {
        currentValue = newValue;
        summary = getSummaryText(newValue);
        adapter.notifyItemChanged(position);
        listener.onSettingChanged(position, newValue);
    }

    private LeftOrRightOsdSettingsItem.Listener createLeftOrRightListener() {
        return new LeftOrRightOsdSettingsItem.Listener() {
            @Override
            public void onSettingLeftClick(int position) {
                updateCurrentValue(position, currentValue - stepFor(-1));
            }

            @Override
            public void onSettingRightClick(int position) {
                updateCurrentValue(position, currentValue + stepFor(1));
            }
        };
    }

    public interface Listener {
        void onSettingChanged(int position, int newValue);
    }

}
