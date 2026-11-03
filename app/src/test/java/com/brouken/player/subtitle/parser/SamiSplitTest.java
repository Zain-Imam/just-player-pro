package com.brouken.player.subtitle.parser;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// shaped like TubeMate's files: languages one after another, a Greek class with no lines, and
// YouTube's overlapping times (each line starts before the last one's &nbsp;)
public class SamiSplitTest {

    private static final String FILE = "<sami>\n<head>\n<Title>vivo</Title>\n<style type='text/css'><!--\n"
            + ".el { Name:Greek(auto); lang:el; SAMIType:CC; }\n"
            + ".en { Name:English (auto); lang:en; SAMIType:CC; }\n"
            + ".fr-FR { Name:French; lang:fr-FR; SAMIType:CC; }\n"
            + "--></style>\n</head>\n<body>\n"
            + "<SYNC Start=0><P class='en'>Hi there. This is the upcoming Vivo V80\n"
            + "<SYNC Start=5040><P class='en'>&nbsp;\n"
            + "<SYNC Start=3840><P class='en'>and it&#39;s known for its advanced\n"
            + "<SYNC Start=7360><P class='en'>&nbsp;\n"
            + "<SYNC Start=0><P class='fr-FR'>Salut\n"
            + "<SYNC Start=4000><P class='fr-FR'>&nbsp;\n"
            + "</body>\n</sami>";

    @Test
    public void languagesAreTheClassesWithLines() {
        final Map<String, String> languages = SamiParser.languages(FILE);
        assertEquals(new ArrayList<>(List.of("en", "fr-FR")), new ArrayList<>(languages.keySet()));
        assertEquals("fr-FR", languages.get("fr-FR"));
    }

    @Test
    public void eachLanguageReadsOnlyItsOwnLines() {
        final List<TextCues.Line> french = SamiParser.parse(FILE, "fr-FR");
        assertEquals(1, french.size());
        assertEquals("Salut", french.get(0).text);
    }

    @Test
    public void oneLineAtATime() {
        final List<TextCues.Line> lines = SamiSplit.oneAtATime(SamiParser.parse(FILE, "en"));
        assertEquals(2, lines.size());
        assertEquals(0, lines.get(0).startUs);
        assertEquals(3_840_000, lines.get(0).endUs);
        assertEquals("and it's known for its advanced", lines.get(1).text);
        assertEquals(7_360_000, lines.get(1).endUs);
    }

    @Test
    public void writesSrt() {
        final String srt = SamiSplit.srt(SamiSplit.oneAtATime(SamiParser.parse(FILE, "en")));
        assertEquals("1\n00:00:00,000 --> 00:00:03,840\nHi there. This is the upcoming Vivo V80\n\n"
                + "2\n00:00:03,840 --> 00:00:07,360\nand it's known for its advanced\n\n", srt);
    }
}
