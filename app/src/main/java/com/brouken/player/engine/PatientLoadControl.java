package com.brouken.player.engine;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;

// a full buffer always plays, or a too-high bitrate would wait for ever
public final class PatientLoadControl implements LoadControl {

    private final LoadControl inner;
    private final StallPatience patience = new StallPatience();
    private long stallSeenAtMs = C.TIME_UNSET;
    private long goalUs;

    public PatientLoadControl(@NonNull final LoadControl inner) {
        this.inner = inner;
    }

    @Override
    public boolean shouldStartPlayback(@NonNull final Parameters parameters) {
        if (!inner.shouldStartPlayback(parameters)) {
            return false;
        }
        if (!parameters.rebuffering || parameters.lastRebufferRealtimeMs == C.TIME_UNSET) {
            return true;
        }
        if (parameters.lastRebufferRealtimeMs != stallSeenAtMs) {
            stallSeenAtMs = parameters.lastRebufferRealtimeMs;
            goalUs = patience.onStall(stallSeenAtMs) * 1000;
            android.util.Log.i(com.brouken.player.Utils.TAG, "Buffering stopped play: on again with "
                    + goalUs / 1_000_000 + " s held");
        }
        return parameters.bufferedDurationUs >= goalUs || !inner.shouldContinueLoading(parameters);
    }

    @Override
    public void onPrepared(@NonNull final PlayerId playerId) {
        inner.onPrepared(playerId);
    }

    @Override
    public void onTracksSelected(@NonNull final Parameters parameters, @NonNull final TrackGroupArray trackGroups,
                                 @NonNull final ExoTrackSelection[] trackSelections) {
        inner.onTracksSelected(parameters, trackGroups, trackSelections);
    }

    @Override
    public void onStopped(@NonNull final PlayerId playerId) {
        inner.onStopped(playerId);
    }

    @Override
    public void onReleased(@NonNull final PlayerId playerId) {
        inner.onReleased(playerId);
    }

    @NonNull
    @Override
    public Allocator getAllocator(@NonNull final PlayerId playerId) {
        return inner.getAllocator(playerId);
    }

    @Override
    public long getBackBufferDurationUs(@NonNull final PlayerId playerId) {
        return inner.getBackBufferDurationUs(playerId);
    }

    @Override
    public boolean retainBackBufferFromKeyframe(@NonNull final PlayerId playerId) {
        return inner.retainBackBufferFromKeyframe(playerId);
    }

    @Override
    public boolean shouldContinueLoading(@NonNull final Parameters parameters) {
        return inner.shouldContinueLoading(parameters);
    }

    @Override
    public boolean shouldContinuePreloading(@NonNull final PlayerId playerId, @NonNull final Timeline timeline,
                                            @NonNull final MediaSource.MediaPeriodId mediaPeriodId,
                                            final long bufferedDurationUs) {
        return inner.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs);
    }
}
