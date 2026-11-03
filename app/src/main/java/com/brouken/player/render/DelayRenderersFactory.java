package com.brouken.player.render;

import android.content.Context;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ForwardingRenderer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.media3.exoplayer.text.TextOutput;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class DelayRenderersFactory extends DefaultRenderersFactory {

    private final AtomicInteger subtitleDelayMs;
    private final AtomicInteger audioDelayMs;
    @Nullable
    private final SideloadedCues sideloaded;

    public DelayRenderersFactory(Context context, AtomicInteger subtitleDelayMs,
                                 AtomicInteger audioDelayMs, @Nullable SideloadedCues sideloaded) {
        super(context);
        this.subtitleDelayMs = subtitleDelayMs;
        this.audioDelayMs = audioDelayMs;
        this.sideloaded = sideloaded;
    }

    // a parse-time shift can't move embedded cues earlier: their times start at 0
    @Override
    protected void buildTextRenderers(@NonNull Context context, @NonNull TextOutput output, @NonNull Looper outputLooper, int extensionRendererMode, @NonNull ArrayList<Renderer> out) {
        ArrayList<Renderer> textRenderers = new ArrayList<>();
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, textRenderers);
        for (Renderer renderer : textRenderers) {
            if (renderer.getTrackType() == C.TRACK_TYPE_TEXT) {
                out.add(new SubtitleDelayRenderer(renderer, subtitleDelayMs, sideloaded));
            } else {
                out.add(renderer);
            }
        }
    }

    // shifts the audio clock; retiming the buffers causes clicks and gaps
    @Override
    protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput,
                                       boolean enableAudioTrackPlaybackParams) {
        return new AudioDelaySink(
                super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams),
                audioDelayMs);
    }

    private static final class SubtitleDelayRenderer extends ForwardingRenderer {

        private final AtomicInteger delayMs;

        private final Renderer renderer;
        private long appliedDelayUs;
        // renderer time minus the stream's own time
        private long offsetUs;
        @Nullable
        private CueReplay replay;

        @Nullable
        private final SideloadedCues sideloaded;

        private SubtitleDelayRenderer(Renderer renderer, AtomicInteger delayMs,
                                      @Nullable SideloadedCues sideloaded) {
            super(renderer);
            this.renderer = renderer;
            this.delayMs = delayMs;
            this.sideloaded = sideloaded;
            this.appliedDelayUs = delayUs();
        }

        private long delayUs() {
            return delayMs.get() * 1000L;
        }

        // only cues parsed while reading the file; old-style decoding and replacing captions
        // (CEA-608) keep the shift alone
        private SampleStream keep(final Format[] formats, final SampleStream stream, final long positionUs) {
            replay = null;
            for (final Format format : formats) {
                if (!MimeTypes.APPLICATION_MEDIA3_CUES.equals(format.sampleMimeType)
                        || format.cueReplacementBehavior != Format.CUE_REPLACEMENT_BEHAVIOR_MERGE) {
                    return stream;
                }
            }
            final String id = formats.length == 0 ? null : formats[0].id;
            replay = new CueReplay(stream, sideloaded == null ? null : () -> sideloaded.cuesFor(id));
            if (delayUs() != 0) {
                replay.activate();
            }
            replay.rewindTo(positionUs - delayUs() - offsetUs);
            return replay;
        }

        @Override
        public void enable(@NonNull RendererConfiguration configuration, @NonNull Format[] formats,
                           @NonNull SampleStream stream, long positionUs, boolean joining,
                           boolean mayRenderStartOfStream, long startPositionUs, long offsetUs,
                           @NonNull MediaSource.MediaPeriodId mediaPeriodId) throws ExoPlaybackException {
            this.offsetUs = offsetUs;
            appliedDelayUs = delayUs();
            super.enable(configuration, formats, keep(formats, stream, positionUs), positionUs - appliedDelayUs,
                    joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId);
        }

        @Override
        public void replaceStream(@NonNull Format[] formats, @NonNull SampleStream stream, long startPositionUs,
                                  long offsetUs, @NonNull MediaSource.MediaPeriodId mediaPeriodId)
                throws ExoPlaybackException {
            this.offsetUs = offsetUs;
            // startPositionUs is already the renderer's own time
            super.replaceStream(formats, keep(formats, stream, startPositionUs), startPositionUs,
                    offsetUs, mediaPeriodId);
        }

        // the player knows only the stream it gave, and matches periods by it
        @Override
        public SampleStream getStream() {
            final SampleStream stream = super.getStream();
            return replay != null && stream == replay ? replay.source : stream;
        }

        // a seek
        @Override
        public void resetPosition(long positionUs, boolean sampleStreamIsResetToKeyFrame)
                throws ExoPlaybackException {
            appliedDelayUs = delayUs();
            if (replay != null) {
                replay.sourceMoved();
                replay.rewindTo(positionUs - appliedDelayUs - offsetUs);
            }
            super.resetPosition(positionUs - appliedDelayUs, sampleStreamIsResetToKeyFrame);
        }

        @Override
        public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
            final long delayUs = delayUs();
            if (delayUs != appliedDelayUs) {
                appliedDelayUs = delayUs;
                // a rewind with nothing to feed again would only wipe the lines already read
                if (replay != null && replay.canReplay()) {
                    // fed again from the first line showing at the new time, earlier or later
                    replay.activate();
                    replay.rewindTo(positionUs - delayUs - offsetUs);
                    super.resetPosition(positionUs - delayUs, true);
                } else {
                    CueRefresh.now(renderer);
                }
            }
            // an earlier position delays the cues, a later one advances them
            super.render(positionUs - delayUs, elapsedRealtimeUs);
        }

        @Override
        public long getDurationToProgressUs(long positionUs, long elapsedRealtimeUs) {
            return super.getDurationToProgressUs(positionUs - delayUs(), elapsedRealtimeUs);
        }

    }

    private static final class AudioDelaySink extends ForwardingAudioSink {

        private final AtomicInteger delayMs;

        private AudioDelaySink(AudioSink sink, AtomicInteger delayMs) {
            super(sink);
            this.delayMs = delayMs;
        }

        @Override
        public long getCurrentPositionUs(boolean sourceEnded) {
            final long positionUs = super.getCurrentPositionUs(sourceEnded);
            if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET) {
                return positionUs;
            }
            return Math.max(0, positionUs + delayMs.get() * 1000L);
        }

    }

}
