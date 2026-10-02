package com.brouken.player.osd.item;

import com.brouken.player.osd.OsdSettingsAdapter;

public class IntegerOsdSettingsItem extends LeftOrRightOsdSettingsItem {

    private final Listener listener;
    private final OsdSettingsAdapter adapter;
    private final String labelDefault;
    private final boolean addPlusToValue;
    private final int step;

    private int currentValue;

    // a run of presses one way speeds up; a pause or a change of direction resets it
    private static final long RUN_BREAK_MS = 400;
    private static final long FASTER_AFTER_MS = 500;
    private static final long FASTEST_AFTER_MS = 1500;
    private static final int FASTER = 5;
    private static final int FASTEST = 10;

    private long runStartedAt;
    private long lastPressAt;
    private int runDirection;

    // only the delay rows speed up
    protected boolean accelerates() {
        return false;
    }

    private int min = Integer.MIN_VALUE;
    private int max = Integer.MAX_VALUE;

    public IntegerOsdSettingsItem withRange(final int min, final int max) {
        this.min = min;
        this.max = max;
        return this;
    }

    private int stepFor(final int direction) {
        if (!accelerates()) {
            return step;
        }
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

    protected int clamp(int value) {
        return Math.max(min, Math.min(max, value));
    }

    private void updateCurrentValue(int position, int rawValue) {
        final int newValue = clamp(rawValue);
        if (newValue == currentValue) {
            return;
        }
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
