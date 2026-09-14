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

/**
 * The two delays the viewer can set, applied to the Media3 renderers.
 *
 * Both are the same problem — something is out by a fraction of a second and
 * the viewer wants to say by how much — and both are fixed here rather than in
 * the file, so nothing has to be re-parsed or re-opened when the number moves.
 */
public final class DelayRenderersFactory extends DefaultRenderersFactory {

    private final AtomicInteger subtitleDelayMs;
    private final AtomicInteger audioDelayMs;

    public DelayRenderersFactory(Context context, AtomicInteger subtitleDelayMs,
                                 AtomicInteger audioDelayMs) {
        super(context);
        this.subtitleDelayMs = subtitleDelayMs;
        this.audioDelayMs = audioDelayMs;
    }

    /**
     * Applies the user subtitle delay at render time by shifting the playback position seen by the
     * text renderers. Shifting cue timestamps at parse time cannot move embedded subtitles earlier:
     * their parsed timestamps are relative to the container sample (start == 0), so a negative
     * shift gets clamped and only shortens the cue duration. Render-time shifting works in both
     * directions, for embedded, external and image-based subtitles alike.
     */
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

    /**
     * The audio delay, by way of the clock rather than the sound.
     *
     * Media3 has no audio delay of its own, and the sound itself must not be
     * touched: relabelling buffers on their way to the track is how you get
     * clicks, dropped samples and a sink that spends the film recovering from a
     * discontinuity it was never told about.
     *
     * What moves instead is the position the audio renderer reports. That
     * number is the master clock — the video renderer shows the frame the clock
     * asks for — so reporting a moment later than the sound actually is puts
     * the picture ahead of the sound by exactly that much, which is what
     * "delay the audio" means. Reporting earlier holds the picture back.
     * Nothing else in the audio path changes, so the worst a wrong number can
     * do is shift the picture; the sound itself cannot break.
     *
     * The cost is honest and unavoidable in either direction: the picture and
     * the sound start a seek together, so after a seek one of them has to catch
     * up — a few frames dropped, or a held frame, for as long as the delay.
     * mpv avoids it by seeking the two streams to different places, which a
     * Media3 media source cannot do.
     */
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
            // Cues show when position >= cue start: presenting an earlier position delays
            // subtitles, a later one advances them.
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
                // No clock yet. Adding to it would make one up.
                return positionUs;
            }
            return Math.max(0, positionUs + delayMs.get() * 1000L);
        }

    }

}
