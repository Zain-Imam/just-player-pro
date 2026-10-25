package com.brouken.player.subtitle.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.media3.common.Format;
import androidx.media3.common.util.Consumer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SubViewerParserTest {

    private static final String SAMPLE = "[INFORMATION]\r\n[TITLE]Film\r\n[AUTHOR]Someone\r\n"
            + "[SOURCE]\r\n[PRG]\r\n[FILEPATH]\r\n[DELAY]0\r\n[CD TRACK]0\r\n[COMMENT]\r\n"
            + "[END INFORMATION]\r\n[SUBTITLE]\r\n[COLF]&HFFFFFF,[STYLE]bd,[SIZE]18,[FONT]Arial\r\n"
            + "00:00:01.50,00:00:04.00\r\nFirst line[br]second line\r\n\r\n"
            + "00:01:02.05,00:01:03.10\r\nOnly one\r\nand a second[BR]and third\r\n";

    @Test
    public void readsTimesAndLineBreaks() {
        final List<TextCues.Line> lines = SubViewerParser.parse(SAMPLE);
        assertEquals(2, lines.size());
        assertEquals(1_500_000, lines.get(0).startUs);
        assertEquals(4_000_000, lines.get(0).endUs);
        assertEquals("First line\nsecond line", lines.get(0).text);
        assertEquals(62_050_000, lines.get(1).startUs);
        assertEquals(63_100_000, lines.get(1).endUs);
        assertEquals("Only one\nand a second\nand third", lines.get(1).text);
    }

    @Test
    public void toldApartFromTheOtherTextFormats() {
        assertTrue(SubViewerParser.looksLikeSubViewer(SAMPLE));
        assertTrue(SubViewerParser.looksLikeSubViewer("\n00:00:01.00,00:00:02.00\nHi\n"));
        assertFalse(SubViewerParser.looksLikeSubViewer("1\n00:00:01,000 --> 00:00:02,000\nHi\n"));
        assertFalse(SubViewerParser.looksLikeSubViewer("{10}{20}Hi\n"));
        assertFalse(SubViewerParser.looksLikeSubViewer("[10][20]Hi\n"));
    }

    @Test
    public void anythingElseGoesToTheFallback() {
        final List<String> calls = new ArrayList<>();
        final SubtitleParser fallback = new SubtitleParser() {
            @Override
            public void parse(@NonNull byte[] data, @NonNull OutputOptions outputOptions, @NonNull Consumer<CuesWithTiming> output) {
                parse(data, 0, data.length, outputOptions, output);
            }

            @Override
            public void parse(@NonNull byte[] data, int offset, int length, @NonNull OutputOptions outputOptions, @NonNull Consumer<CuesWithTiming> output) {
                calls.add(new String(data, offset, length, StandardCharsets.UTF_8));
            }

            @Override
            public void reset() {
            }

            @Override
            public int getCueReplacementBehavior() {
                return Format.CUE_REPLACEMENT_BEHAVIOR_MERGE;
            }
        };
        final SubViewerParser parser = new SubViewerParser(fallback);
        final List<CuesWithTiming> out = new ArrayList<>();

        final String srt = "1\n00:00:01,000 --> 00:00:02,000\nHi\n";
        parser.parse(srt.getBytes(StandardCharsets.UTF_8), SubtitleParser.OutputOptions.allCues(), out::add);
        assertEquals(1, calls.size());
        assertEquals(srt, calls.get(0));
        assertEquals(0, out.size());

        parser.parse(SAMPLE.getBytes(StandardCharsets.UTF_8), SubtitleParser.OutputOptions.allCues(), out::add);
        assertEquals(1, calls.size());
        assertEquals(2, out.size());
        assertEquals(2_500_000, out.get(0).durationUs);
        assertEquals("First line\nsecond line", String.valueOf(out.get(0).cues.get(0).text));
    }
}
