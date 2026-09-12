package com.brouken.player;

import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.Player;

// Stops the film after a while, for falling asleep to. The last half minute
// fades the sound down rather than cutting it, so drifting off is not
// interrupted by silence arriving all at once.
public final class SleepTimer {

    public interface Host {
        void onSleepFinished();
    }

    private static final long FADE_MS = 30_000;
    private static final long TICK_MS = 1_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Host host;

    private long endsAt;
    private boolean atEndOfFile;
    private float volumeBeforeFade = 1f;
    private boolean running;

    public SleepTimer(final Host host) {
        this.host = host;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isAtEndOfFile() {
        return atEndOfFile;
    }

    /** Minutes from now, or zero to stop the timer. */
    public void setMinutes(final int minutes, final Player player) {
        cancel(player);
        if (minutes <= 0) {
            return;
        }
        running = true;
        atEndOfFile = false;
        volumeBeforeFade = player == null ? 1f : player.getVolume();
        endsAt = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L;
        handler.postDelayed(this.tick, TICK_MS);
    }

    /** Stop when the file that is playing now finishes. */
    public void setEndOfFile(final Player player) {
        cancel(player);
        running = true;
        atEndOfFile = true;
    }

    public void cancel(final Player player) {
        handler.removeCallbacks(tick);
        if (running && !atEndOfFile && player != null) {
            player.setVolume(volumeBeforeFade);
        }
        running = false;
        atEndOfFile = false;
    }

    /** How long is left, in minutes, rounded up. Zero when not counting down. */
    public int minutesLeft() {
        if (!running || atEndOfFile) {
            return 0;
        }
        final long left = endsAt - android.os.SystemClock.elapsedRealtime();
        return left <= 0 ? 0 : (int) ((left + 59_999) / 60_000);
    }

    /** Called when playback reaches the end, for the end-of-file setting. */
    public void onPlaybackEnded(final Player player) {
        if (running && atEndOfFile) {
            cancel(player);
            host.onSleepFinished();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            final Player player = PlayerActivity.player;
            final long left = endsAt - android.os.SystemClock.elapsedRealtime();

            if (left <= 0) {
                if (player != null) {
                    player.setVolume(volumeBeforeFade);
                }
                running = false;
                host.onSleepFinished();
                return;
            }
            if (player != null && left < FADE_MS) {
                player.setVolume(volumeBeforeFade * left / (float) FADE_MS);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };
}
