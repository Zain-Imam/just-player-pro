package com.brouken.player.net;

import android.os.Handler;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import java.util.Locale;

/**
 * How fast the stream is actually arriving.
 *
 * Not the bandwidth estimate the player uses to choose a rendition -- that is a
 * guess about the connection, it survives long after the downloading stops, and
 * on a full buffer it would go on claiming forty megabits while nothing at all
 * is being fetched. This counts the bytes that actually came in over the last
 * second or so, which is the number a person means by "how fast is it going".
 *
 * A file on the device has no answer to give, and is not asked.
 */
public final class NetworkSpeed implements BandwidthMeter {

    /** How long a sample is worth reporting before it is plainly stale. */
    private static final long WINDOW_MS = 1500;

    private final DefaultBandwidthMeter delegate;

    private long windowStartedAt;
    private long windowBytes;
    private long lastRateBytesPerSecond;

    public NetworkSpeed(final DefaultBandwidthMeter delegate) {
        this.delegate = delegate;
    }

    /** Bytes a second, or -1 when nothing has been received recently. */
    public synchronized long bytesPerSecond() {
        roll(SystemClock.elapsedRealtime());
        return lastRateBytesPerSecond;
    }

    private synchronized void received(final int bytes) {
        final long now = SystemClock.elapsedRealtime();
        if (windowStartedAt == 0) {
            windowStartedAt = now;
        }
        windowBytes += bytes;
        roll(now);
    }

    /**
     * Close a window once it is old enough to mean something.
     *
     * Two windows without a single byte is a stream that has stopped arriving --
     * a full buffer, usually -- and the honest answer then is nothing at all
     * rather than the speed it used to be going at.
     */
    private void roll(final long now) {
        if (windowStartedAt == 0) {
            return;
        }
        final long elapsed = now - windowStartedAt;
        if (elapsed < WINDOW_MS) {
            return;
        }
        lastRateBytesPerSecond = windowBytes * 1000 / elapsed;
        windowBytes = 0;
        windowStartedAt = now;
    }

    /** "3.1 MB/s", "812 KB/s", or nothing when there is nothing to say. */
    @Nullable
    public static String format(final long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return null;
        }
        if (bytesPerSecond >= 1024 * 1024) {
            final double megabytes = bytesPerSecond / (1024d * 1024d);
            return String.format(Locale.getDefault(),
                    megabytes >= 10 ? "%.0f MB/s" : "%.1f MB/s", megabytes);
        }
        if (bytesPerSecond >= 1024) {
            return String.format(Locale.getDefault(), "%d KB/s", bytesPerSecond / 1024);
        }
        return String.format(Locale.getDefault(), "%d B/s", bytesPerSecond);
    }

    // ------------------------------------------------------- BandwidthMeter

    @Override
    public long getBitrateEstimate() {
        return delegate.getBitrateEstimate();
    }

    @Override
    public long getTimeToFirstByteEstimateUs() {
        return delegate.getTimeToFirstByteEstimateUs();
    }

    @NonNull
    @Override
    public TransferListener getTransferListener() {
        final TransferListener inner = delegate.getTransferListener();
        return new TransferListener() {
            @Override
            public void onTransferInitializing(@NonNull DataSource source, @NonNull DataSpec dataSpec, boolean isNetwork) {
                inner.onTransferInitializing(source, dataSpec, isNetwork);
            }

            @Override
            public void onTransferStart(@NonNull DataSource source, @NonNull DataSpec dataSpec, boolean isNetwork) {
                inner.onTransferStart(source, dataSpec, isNetwork);
            }

            @Override
            public void onBytesTransferred(@NonNull DataSource source, @NonNull DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
                if (isNetwork) {
                    received(bytesTransferred);
                }
                inner.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred);
            }

            @Override
            public void onTransferEnd(@NonNull DataSource source, @NonNull DataSpec dataSpec, boolean isNetwork) {
                inner.onTransferEnd(source, dataSpec, isNetwork);
            }
        };
    }

    @Override
    public void addEventListener(@NonNull Handler handler, @NonNull EventListener listener) {
        delegate.addEventListener(handler, listener);
    }

    @Override
    public void removeEventListener(@NonNull EventListener listener) {
        delegate.removeEventListener(listener);
    }
}
