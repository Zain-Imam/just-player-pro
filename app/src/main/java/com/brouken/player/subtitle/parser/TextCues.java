package com.brouken.player.subtitle.parser;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.Consumer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TextCues {

    static final class Line {
        final long startUs;
        final long endUs;
        final String text;

        Line(long startUs, long endUs, String text) {
            this.startUs = startUs;
            this.endUs = Math.max(endUs, startUs);
            this.text = text;
        }
    }

    private TextCues() {
    }

    // the app hands over UTF-8 (Utils.convertToUTF); a BOM is dropped
    @NonNull
    static String decode(byte[] data, int offset, int length) {
        String text = new String(data, offset, length, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        return text;
    }

    // same order rule as Media3's own parsers: cues before the start time go last
    static void output(List<Line> lines, SubtitleParser.OutputOptions outputOptions,
                       Consumer<CuesWithTiming> output) {
        final List<CuesWithTiming> before =
                outputOptions.startTimeUs != C.TIME_UNSET && outputOptions.outputAllCues
                        ? new ArrayList<>()
                        : null;
        for (Line line : lines) {
            final Cue cue = new Cue.Builder().setText(line.text).build();
            final CuesWithTiming cues = new CuesWithTiming(Collections.singletonList(cue),
                    line.startUs, line.endUs - line.startUs);
            if (outputOptions.startTimeUs == C.TIME_UNSET || line.endUs >= outputOptions.startTimeUs) {
                output.accept(cues);
            } else if (before != null) {
                before.add(cues);
            }
        }
        if (before != null) {
            for (CuesWithTiming cues : before) {
                output.accept(cues);
            }
        }
    }
}
