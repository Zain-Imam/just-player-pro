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

// counts bytes actually received; the bandwidth estimate stays high on a full buffer
public final class NetworkSpeed implements BandwidthMeter {

    private static final long WINDOW_MS = 1500;

    private final DefaultBandwidthMeter delegate;

    private long windowStartedAt;
    private long windowBytes;
    private long lastRateBytesPerSecond;
    private long totalBytes;

    public NetworkSpeed(final DefaultBandwidthMeter delegate) {
        this.delegate = delegate;
    }

    public synchronized long bytesPerSecond() {
        roll(SystemClock.elapsedRealtime());
        return lastRateBytesPerSecond;
    }

    public synchronized long totalBytes() {
        return totalBytes;
    }

    private synchronized void received(final int bytes) {
        totalBytes += bytes;
        final long now = SystemClock.elapsedRealtime();
        if (windowStartedAt == 0) {
            windowStartedAt = now;
        }
        windowBytes += bytes;
        roll(now);
    }

    // an empty window reports 0, usually a full buffer
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

    // null when there is no speed to show
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
