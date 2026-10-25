package com.brouken.player.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NameReadingTest {

    @Test
    public void everyNameReadsAsEpisodeInfoReadsIt() {
        final List<String> failures = new ArrayList<>();
        for (final String[] each : NameReadingCorpus.CASES) {
            final List<String> problems = NameReadingCheck.problems(each[0], each[1]);
            if (!problems.isEmpty()) {
                failures.add(each[0] + " -> " + problems);
            }
        }
        assertEquals(String.join("\n", failures), 0, failures.size());
    }

    @Test
    public void nothingThrows() {
        for (final String nasty : NameReadingCorpus.NASTY) {
            NameReading.usable(NameReading.reading(nasty, null, "file name", false));
        }
    }

    @Test
    public void aShowComesFromItsFolders() {
        final NameReading.Reading rd = NameReading.reading("01 - Pilot.mkv",
                Arrays.asList("TV", "Lost", "Season 1"), "file name", false);
        assertEquals("Lost", rd.p.title);
        assertEquals(Integer.valueOf(1), rd.p.season);
        assertEquals(Integer.valueOf(1), rd.p.episode);
        assertTrue(NameReading.usable(rd));
    }

    @Test
    public void anExtrasFolderMarksAnExtra() {
        assertTrue(NameReading.reading("Inception.2010.mkv", Arrays.asList("Inception", "Featurettes"),
                "file name", false).p.extra);
    }

    // only real names may beat a launcher's title
    @Test
    public void aServerPlaceholderIsNoTitle() {
        for (final String placeholder : new String[]{"download", "Download", "video", "file.mp4",
                "stream", "d41d8cd98f00b204e9800998ecf8427e.mkv"}) {
            assertFalse(placeholder, NameReading.titleOk(NameReading.parse(placeholder)));
        }
        for (final String real : new String[]{"Batman.2005.1080p.BluRay.x264.mkv", "1917.2019.mkv",
                "Inception (2010).mp4", "The.Office.S02E03.720p.mkv"}) {
            assertTrue(real, NameReading.titleOk(NameReading.parse(real)));
        }
    }

    // the shown title starts the search; a file name only adds to the same title
    @Test
    public void theShownTitleStartsTheSearch() {
        assertEquals("Sintel", Readings.searchName("download", "Sintel"));
        assertEquals("Sintel", Readings.searchName("Batman.2005.1080p.BluRay.x264.mkv", "Sintel"));
        assertEquals("Breaking.Bad.S02E03.720p.mkv",
                Readings.searchName("Breaking.Bad.S02E03.720p.mkv", "Breaking Bad"));
        assertEquals("Inception.2010.mkv", Readings.searchName("Inception.2010.mkv", null));
        assertEquals("Inception.2010.mkv", Readings.searchName("Inception.2010.mkv", " "));
        // a launcher's placeholder is no title either
        assertEquals("Inception.2010.mkv", Readings.searchName("Inception.2010.mkv", "Video"));
    }

    @Test
    public void titlesMatchWithoutAccentsArticlesOrPunctuation() {
        assertTrue(NameReading.titleMatches("Amelie", Arrays.asList("Amélie")));
        assertTrue(NameReading.titleMatches("Office", Arrays.asList("The Office")));
        assertTrue(NameReading.titleMatches("Fast and Furious", Arrays.asList("Fast & Furious")));
        assertTrue(NameReading.titleMatches("Oceans Eleven", Arrays.asList("Ocean's Eleven")));
        assertFalse(NameReading.titleMatches("Bikini Inception", Arrays.asList("Inception")));
    }
}
