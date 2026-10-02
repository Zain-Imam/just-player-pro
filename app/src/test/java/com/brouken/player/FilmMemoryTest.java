package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// The pure rules behind remembering a film and naming its subtitles.
public class FilmMemoryTest {

    @Test
    public void aReleaseNameIdentifiesAFilm() {
        assertTrue(FilmKey.identifies("Sintel.2010.1080p.mkv"));
        assertTrue(FilmKey.identifies("holiday clip.mp4"));
    }

    @Test
    public void genericNamesDoNot() {
        // Every HLS stream ends in one of these, and a torrent server in a number.
        assertFalse(FilmKey.identifies("index.m3u8"));
        assertFalse(FilmKey.identifies("master.m3u8"));
        assertFalse(FilmKey.identifies("0"));
        assertFalse(FilmKey.identifies("12345.mkv"));
        assertFalse(FilmKey.identifies("noextension"));
    }

    @Test
    public void rowIdsAreNotNames() {
        assertTrue(SubtitleNames.looksLikeAnId("1000012345"));
        assertTrue(SubtitleNames.looksLikeAnId("msf:1000012345"));
        assertTrue(SubtitleNames.looksLikeAnId("document:42"));
        assertFalse(SubtitleNames.looksLikeAnId("Sintel.en"));
    }

    @Test
    public void onlyTheSubtitleExtensionIsTakenOff() {
        assertEquals("Movie.2010.en", SubtitleNames.withoutExtension("Movie.2010.en.srt"));
        assertEquals("Movie.2010", SubtitleNames.withoutExtension("Movie.2010"));
    }

    @Test
    public void subtitleExtensionsAreRecognised() {
        assertTrue(SubtitleFiles.hasSubtitleExtension("a.SRT"));
        assertTrue(SubtitleFiles.hasSubtitleExtension("a.ass"));
        assertFalse(SubtitleFiles.hasSubtitleExtension("msf:1000012345"));
        assertFalse(SubtitleFiles.hasSubtitleExtension("cacert.pem"));
    }

    @Test
    public void subtitleControlsStayInRange() {
        assertEquals(Prefs.SUBTITLE_SIZE_MIN, Prefs.clampSubtitleSize(-100));
        assertEquals(Prefs.SUBTITLE_SIZE_MAX, Prefs.clampSubtitleSize(100));
        assertEquals(2, Prefs.clampSubtitleSize(2));
        assertEquals(Prefs.SUBTITLE_POSITION_MIN, Prefs.clampSubtitlePosition(-50));
        assertEquals(Prefs.SUBTITLE_POSITION_MAX, Prefs.clampSubtitlePosition(500));
    }

    @Test
    public void mpvSubtitlesCanGoFurtherDownThanMedia3s() {
        // mpv keeps a margin under its text, so its bottom edge is lower.
        assertEquals(-13, Prefs.clampSubtitlePosition(-50, Prefs.SUBTITLE_POSITION_MIN_MPV));
        assertEquals(-10, Prefs.clampSubtitlePosition(-10, Prefs.SUBTITLE_POSITION_MIN_MPV));
        assertEquals(-8, Prefs.clampSubtitlePosition(-10, Prefs.SUBTITLE_POSITION_MIN));
        assertEquals(Prefs.SUBTITLE_POSITION_MAX,
                Prefs.clampSubtitlePosition(500, Prefs.SUBTITLE_POSITION_MIN_MPV));
    }
}
