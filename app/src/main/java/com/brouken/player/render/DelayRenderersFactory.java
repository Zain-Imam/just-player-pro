package com.brouken.player.render;

import android.content.Context;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ForwardingRenderer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.media3.exoplayer.text.TextOutput;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class DelayRenderersFactory extends DefaultRenderersFactory {

    private final AtomicInteger subtitleDelayMs;
    private final AtomicInteger audioDelayMs;

    public DelayRenderersFactory(Context context, AtomicInteger subtitleDelayMs,
                                 AtomicInteger audioDelayMs) {
        super(context);
        this.subtitleDelayMs = subtitleDelayMs;
        this.audioDelayMs = audioDelayMs;
    }

    // shifts the position the text renderers see; a parse-time shift can't move
    // embedded cues earlier because their timestamps start at 0
    @Override
    protected void buildTextRenderers(@NonNull Context context, @NonNull TextOutput output, @NonNull Looper outputLooper, int extensionRendererMode, @NonNull ArrayList<Renderer> out) {
        ArrayList<Renderer> textRenderers = new ArrayList<>();
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, textRenderers);
        for (Renderer renderer : textRenderers) {
            if (renderer.getTrackType() == C.TRACK_TYPE_TEXT) {
                out.add(new SubtitleDelayRenderer(renderer, subtitleDelayMs));
            } else {
                out.add(renderer);
            }
        }
    }

    // shifts the audio clock that drives the video; retiming the buffers causes
    // clicks and discontinuities
    @Override
    protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput,
                                       boolean enableAudioTrackPlaybackParams) {
        return new AudioDelaySink(
                super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams),
                audioDelayMs);
    }

    private static final class SubtitleDelayRenderer extends ForwardingRenderer {

        private final AtomicInteger delayMs;

        private SubtitleDelayRenderer(Renderer renderer, AtomicInteger delayMs) {
            super(renderer);
            this.delayMs = delayMs;
        }

        private long delayUs() {
            return delayMs.get() * 1000L;
        }

        @Override
        public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
            // an earlier position delays the cues, a later one advances them
            super.render(positionUs - delayUs(), elapsedRealtimeUs);
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
                // no clock yet
                return positionUs;
            }
            return Math.max(0, positionUs + delayMs.get() * 1000L);
        }

    }

}
