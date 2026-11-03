package com.brouken.player;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.Bundle;

// The notification's tap: the player may live in the task of the app that opened it, which a
// notification cannot reach directly, so this brings that task forward and closes
public class ShowPlayerActivity extends Activity {

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (PlaybackService.playerTask != -1) {
            try {
                ((ActivityManager) getSystemService(ACTIVITY_SERVICE))
                        .moveTaskToFront(PlaybackService.playerTask, 0);
            } catch (RuntimeException e) {
                Utils.log("Could not bring the player back: " + e);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
