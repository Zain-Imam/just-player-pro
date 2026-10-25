package com.brouken.player.engine;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;

import com.brouken.player.net.NetworkSpeed;

// megabytes with nothing buffered: Media3 is rereading up to the seek target
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
            // the end is loaded: near it there is no half second more to wait for
            final long duration = player.getDuration();
            if (duration != androidx.media3.common.C.TIME_UNSET
                    && player.getBufferedPosition() >= duration - 1000) {
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
