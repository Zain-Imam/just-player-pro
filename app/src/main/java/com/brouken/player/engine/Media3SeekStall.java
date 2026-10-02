package com.brouken.player.engine;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;

import com.brouken.player.net.NetworkSpeed;

// After a failed range request Media3 can retry from its old read position and read
// through to the seek target. Megabytes arriving with nothing buffered means that.
public final class Media3SeekStall {

    public interface Host {
        void onSeekStalled();
    }

    private static final long CHECK_MS = 3_000;
    private static final long STALLED_AFTER_MS = 15_000;
    private static final long GIVE_UP_AFTER_MS = 120_000;
    private static final long READING_ELSEWHERE_BYTES = 4L * 1024 * 1024;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Player player;
    private final NetworkSpeed meter;
    private final Host host;

    private long startedAt = -1;
    private long bytesAtStart;

    public Media3SeekStall(@NonNull final Player player, @NonNull final NetworkSpeed meter,
                           @NonNull final Host host) {
        this.player = player;
        this.meter = meter;
        this.host = host;
    }

    public void onSeek() {
        handler.removeCallbacks(check);
        startedAt = SystemClock.elapsedRealtime();
        bytesAtStart = meter.totalBytes();
        handler.postDelayed(check, CHECK_MS);
    }

    public void stop() {
        handler.removeCallbacks(check);
        startedAt = -1;
    }

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            if (startedAt < 0) {
                return;
            }
            if (player.getPlaybackState() != Player.STATE_BUFFERING) {
                stop();
                return;
            }
            // data for the target arrived: just a slow server
            if (player.getBufferedPosition() > player.getCurrentPosition() + 500) {
                stop();
                return;
            }
            final long waited = SystemClock.elapsedRealtime() - startedAt;
            final long arrived = meter.totalBytes() - bytesAtStart;
            if (waited >= STALLED_AFTER_MS && arrived >= READING_ELSEWHERE_BYTES) {
                stop();
                host.onSeekStalled();
                return;
            }
            if (waited >= GIVE_UP_AFTER_MS) {
                stop();
                return;
            }
            handler.postDelayed(this, CHECK_MS);
        }
    };
}
