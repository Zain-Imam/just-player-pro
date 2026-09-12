package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ReleaseNameTest {

    @Test
    public void titlesComeOutOfTheCorpus() {
        for (final String[] each : ReleaseNameCorpus.CASES) {
            assertEquals(each[0], each[1], ReleaseName.parse(each[0]).cleanTitle);
        }
    }

    @Test
    public void nothingThrows() {
        for (final String nasty : ReleaseNameCorpus.NASTY) {
            assertNotNull(nasty, ReleaseName.parse(nasty));
        }
        assertNotNull(ReleaseName.parse(null));
    }

    @Test
    public void seasonsAndEpisodesAreFound() {
        final ReleaseName.Info bad = ReleaseName.parse("Breaking.Bad.S05E16.Felina.720p.mkv");
        assertEquals(Integer.valueOf(5), bad.season);
        assertEquals(Integer.valueOf(16), bad.episode);
        assertEquals("Felina", bad.episodeTitle);

        final ReleaseName.Info onePiece = ReleaseName.parse("One.Piece.EP1089.1080p.mkv");
        assertEquals(Integer.valueOf(1089), onePiece.episode);

        final ReleaseName.Info titan =
                ReleaseName.parse("Attack on Titan Season 3 Episode 12 1080p.mkv");
        assertEquals(Integer.valueOf(3), titan.season);
        assertEquals(Integer.valueOf(12), titan.episode);
    }

    @Test
    public void aYearIsAYearAndADateStampIsNot() {
        assertEquals("2008", ReleaseName.parse("The.Dark.Knight.2008.1080p.mkv").year);
        assertNull(ReleaseName.parse("VID-20230515-WA0001.mp4").year);
    }

    /*
     * A film is searched for by name, with the year alongside rather than
     * inside the words.
     *
     * "The Runner 2026" finds nothing at TMDB because it is looking for those
     * words in a title. "The Runner", with 2026 as the year, finds it.
     */
    @Test
    public void theSearchQueryIsTheTitleWithoutTheYear() {
        final ReleaseName.Info info = ReleaseName.parse("The.Runner.2026.1080p.WEB-DL.mkv");
        assertEquals("The Runner", info.cleanTitle);
        assertEquals("2026", info.year);
        assertEquals("The Runner", info.searchQuery());
    }
}
