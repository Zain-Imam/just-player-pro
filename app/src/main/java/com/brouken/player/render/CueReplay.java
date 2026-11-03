package com.brouken.player.render;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.extractor.text.CueDecoder;
import androidx.media3.extractor.text.CueEncoder;
import androidx.media3.extractor.text.CuesWithTiming;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Keeps a text track's lines to feed again: TextRenderer drops each one it passes, so a delay moved back
// showed nothing until a seek. Until a delay is set it only passes Media3's stream through, as 5.0.0 did:
// a subtitle file that arrived late once left the kept lines stuck behind playback.
@UnstableApi
final class CueReplay implements SampleStream {

    private static final class Sample {
        final long timeUs;
        // C.TIME_UNSET when the line has no end of its own
        final long endUs;
        final byte[] data;

        Sample(final long timeUs, final long endUs, final byte[] data) {
            this.timeUs = timeUs;
            this.endUs = endUs;
            this.data = data;
        }
    }

    final SampleStream source;
    // the whole file, read apart from Media3, once it is ready; null for embedded tracks
    @Nullable
    private final java.util.function.Supplier<List<CuesWithTiming>> whole;
    private boolean wholeAdded;
    // false until a delay is set: until then every call goes straight to the source
    private boolean active;
    private long rewoundToUs;
    private int servedSinceRewind;
    private final DecoderInputBuffer scratch =
            new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    private final FormatHolder scratchFormat = new FormatHolder();
    private final CueDecoder decoder = new CueDecoder();
    // in time order
    private final List<Sample> samples = new ArrayList<>();
    // read from the source after the renderer was already past their place
    private final ArrayDeque<Sample> late = new ArrayDeque<>();
    private int next;
    private boolean sourceEnded;

    CueReplay(final SampleStream source,
              @Nullable final java.util.function.Supplier<List<CuesWithTiming>> whole) {
        this.source = source;
        this.whole = whole;
    }

    // lines already passed through are gone, except a sideloaded file's, which is read whole
    void activate() {
        active = true;
    }

    // a rewind has lines to feed again only from a file read whole, or once lines are kept
    boolean canReplay() {
        return active || (whole != null && whole.get() != null);
    }

    // the renderer starts again from the first line still showing at this time
    void rewindTo(final long timeUs) {
        rewoundToUs = timeUs;
        if (!active) {
            return;
        }
        servedSinceRewind = 0;
        late.clear();
        next = samples.size();
        for (int i = 0; i < samples.size(); i++) {
            final Sample sample = samples.get(i);
            if (sample.endUs == C.TIME_UNSET ? sample.timeUs >= timeUs : sample.endUs > timeUs) {
                next = i;
                break;
            }
        }
    }

    // a seek moved the source, which may now give lines again or ones not seen yet
    void sourceMoved() {
        sourceEnded = false;
    }

    private void addWhole() {
        if (wholeAdded || whole == null) {
            return;
        }
        final List<CuesWithTiming> all = whole.get();
        if (all == null) {
            return;
        }
        wholeAdded = true;
        final CueEncoder encoder = new CueEncoder();
        for (final CuesWithTiming cues : all) {
            // the same bytes Media3 writes, so its own copies are dropped as repeats
            add(new Sample(cues.startTimeUs, cues.endTimeUs, encoder.encode(cues.cues, cues.durationUs)),
                    false);
        }
        // nothing fed yet: start again from the right line rather than the file's first
        if (servedSinceRewind == 0) {
            rewindTo(rewoundToUs);
        }
    }

    // everything the source has now: a sideloaded file is all there at once
    private void pump() {
        addWhole();
        while (!sourceEnded) {
            scratch.clear();
            final int result = source.readData(scratchFormat, scratch, 0);
            if (result == C.RESULT_FORMAT_READ) {
                continue;
            }
            if (result != C.RESULT_BUFFER_READ) {
                return;
            }
            if (scratch.isEndOfStream()) {
                sourceEnded = true;
                return;
            }
            scratch.flip();
            final ByteBuffer data = scratch.data;
            if (data == null) {
                continue;
            }
            final byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            final CuesWithTiming cues = decoder.decode(scratch.timeUs, bytes, 0, bytes.length);
            add(new Sample(scratch.timeUs, cues.endTimeUs, bytes), true);
        }
    }

    // a line behind the renderer is fed next only when the source just gave it (after a seek);
    // the whole file's are kept for a later rewind
    private void add(final Sample sample, final boolean feedIfBehind) {
        int at = samples.size();
        while (at > 0 && samples.get(at - 1).timeUs > sample.timeUs) {
            at--;
        }
        // after a seek the source gives the same lines again
        for (int i = at - 1; i >= 0 && samples.get(i).timeUs == sample.timeUs; i--) {
            if (Arrays.equals(samples.get(i).data, sample.data)) {
                return;
            }
        }
        samples.add(at, sample);
        if (at < next) {
            next++;
            if (feedIfBehind) {
                late.add(sample);
            }
        }
    }

    @Override
    public boolean isReady() {
        if (!active) {
            return source.isReady();
        }
        return !late.isEmpty() || next < samples.size() || sourceEnded || source.isReady();
    }

    @Override
    public void maybeThrowError() throws java.io.IOException {
        source.maybeThrowError();
    }

    @Override
    public int readData(@NonNull final FormatHolder formatHolder, @NonNull final DecoderInputBuffer buffer,
                        final int readFlags) {
        if (!active) {
            return source.readData(formatHolder, buffer, readFlags);
        }
        pump();
        Sample sample = late.poll();
        if (sample == null && next < samples.size()) {
            sample = samples.get(next++);
        }
        if (sample == null) {
            if (sourceEnded) {
                buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
                return C.RESULT_BUFFER_READ;
            }
            return C.RESULT_NOTHING_READ;
        }
        servedSinceRewind++;
        buffer.timeUs = sample.timeUs;
        buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME);
        buffer.ensureSpaceForWrite(sample.data.length);
        buffer.data.put(sample.data);
        return C.RESULT_BUFFER_READ;
    }

    @Override
    public int skipData(final long positionUs) {
        if (!active) {
            return source.skipData(positionUs);
        }
        int skipped = 0;
        while (next < samples.size() && samples.get(next).timeUs < positionUs) {
            next++;
            skipped++;
        }
        return skipped;
    }
}
