package com.brouken.player.subtitle.parser;

import static org.junit.Assert.assertEquals;

import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SamiParserTest {

    private static final String HEAD = "<SAMI>\n<HEAD><TITLE>Film</TITLE>\n"
            + "<STYLE TYPE=\"text/css\"><!--\nP { margin-left: 8pt; }\n"
            + ".KRCC { Name: Korean; lang: ko-KR; }\n.ENCC { Name: English; lang: en-US; }\n"
            + "--></STYLE></HEAD>\n<BODY>\n";

    private static void assertLine(TextCues.Line line, long startMs, long endMs, String text) {
        assertEquals(startMs * 1000, line.startUs);
        assertEquals(endMs * 1000, line.endUs);
        assertEquals(text, line.text);
    }

    @Test
    public void nbspEndsTheCueAndBrBreaksTheLine() {
        final List<TextCues.Line> lines = SamiParser.parse(HEAD
                + "<SYNC Start=1000><P Class=KRCC>안녕<br>하세요\n"
                + "<SYNC Start=1000><P Class=ENCC>Hello<br>there\n"
                + "<SYNC Start=3500><P Class=KRCC>&nbsp;\n"
                + "<SYNC Start=3500><P Class=ENCC>&nbsp;\n"
                + "<SYNC Start=5000><P Class=KRCC>Tom &amp; Jerry &lt;3&gt;\n"
                + "</BODY></SAMI>");
        assertEquals(2, lines.size());
        assertLine(lines.get(0), 1000, 3500, "안녕\n하세요");
        assertLine(lines.get(1), 5000, 9000, "Tom & Jerry <3>");
    }

    @Test
    public void theFirstLanguageClassIsKept() {
        final List<TextCues.Line> lines = SamiParser.parse(HEAD
                + "<SYNC Start=0><P Class=ENCC>One<P Class=FRCC>Un\n"
                + "<SYNC Start=2000><P Class=ENCC>Two</P><P Class=FRCC>Deux</P>\n"
                + "<SYNC Start=2600><P Class=FRCC>Trois\n"
                + "<SYNC Start=3000><P Class=ENCC>&nbsp\n");
        assertEquals(2, lines.size());
        assertLine(lines.get(0), 0, 2000, "One");
        // the French-only block leaves the English cue up
        assertLine(lines.get(1), 2000, 3000, "Two");
    }

    @Test
    public void aCueRunsToTheNextSyncAndOtherTagsGo() {
        final List<TextCues.Line> lines = SamiParser.parse(
                "<SAMI><BODY><SYNC Start=\"100\"><P>caf&#233; <font color=\"#ffff00\">au</font>\n  lait</P>"
                        + "<SYNC Start=900><P>&#x41;B<BR/>  <i>C</i>");
        assertEquals(2, lines.size());
        assertLine(lines.get(0), 100, 900, "café au lait");
        assertLine(lines.get(1), 900, 4900, "AB\nC");
    }

    @Test
    public void parsesThroughTheMedia3Interface() {
        final byte[] bytes = ("﻿<SAMI><BODY><SYNC Start=1500><P Class=ENCC>Hi"
                + "<SYNC Start=2500><P Class=ENCC>&nbsp;</BODY></SAMI>").getBytes(StandardCharsets.UTF_8);
        final List<CuesWithTiming> out = new ArrayList<>();
        new SamiParser().parse(bytes, SubtitleParser.OutputOptions.allCues(), out::add);
        assertEquals(1, out.size());
        assertEquals(1_500_000, out.get(0).startTimeUs);
        assertEquals(1_000_000, out.get(0).durationUs);
        assertEquals("Hi", String.valueOf(out.get(0).cues.get(0).text));
    }
}
