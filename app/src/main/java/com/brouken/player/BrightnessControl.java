package com.brouken.player;

import android.app.Activity;
import android.provider.Settings;
import android.view.WindowManager;

class BrightnessControl {

    private final Activity activity;

    // 0 to 100 like the volume; -1 leaves it to the system
    public int currentBrightnessLevel = -1;

    public BrightnessControl(Activity activity) {
        this.activity = activity;
    }

    public float getScreenBrightness() {
        return activity.getWindow().getAttributes().screenBrightness;
    }

    public void setScreenBrightness(final float brightness) {
        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        lp.screenBrightness = brightness;
        activity.getWindow().setAttributes(lp);
    }

    public void changeBrightness(final CustomPlayerView playerView, final boolean increase,
                                 final boolean canSetAuto, final int steps) {
        // from the system's own level: starting at 0 made the first swipe up go dark
        final int from = currentBrightnessLevel < 0 ? systemLevel() : currentBrightnessLevel;
        final int to = from + (increase ? steps : -steps);
        if (to < 0 && canSetAuto) {
            setLevel(-1);
        } else {
            setLevel(Math.max(0, Math.min(100, to)));
        }
        playerView.setHighlight(false);
        show(playerView);
    }

    void setLevel(final int level) {
        currentBrightnessLevel = level;
        setScreenBrightness(level < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                : levelToBrightness(level));
    }

    void show(final CustomPlayerView playerView) {
        if (currentBrightnessLevel < 0) {
            playerView.setIconBrightnessAuto();
            playerView.setCustomErrorMessage("");
        } else {
            playerView.setIconBrightness();
            playerView.setCustomErrorMessage(" " + currentBrightnessLevel);
        }
    }

    // straight, as VLC does; the old curve kept the middle much darker than its number
    static float levelToBrightness(final int level) {
        return Math.max(0.01f, level / 100f);
    }

    // 1.x saved 0 to 30 on a curve; the same brightness on the new scale
    static int fromOldLevel(final int oldLevel) {
        final double d = 0.064 + 0.936 / 30d * oldLevel;
        return (int) Math.round(d * d * 100);
    }

    private int systemLevel() {
        try {
            final int value = Settings.System.getInt(activity.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS);
            return Math.max(0, Math.min(100, Math.round(value * 100f / 255f)));
        } catch (Settings.SettingNotFoundException e) {
            return 50;
        }
    }
}
