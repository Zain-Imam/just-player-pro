package com.brouken.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PictureSubtitlesTest {

    @Test
    public void supAndIdxAreKnownByName() {
        assertTrue(PictureSubtitles.isPictureName("/storage/Movies/Film.SUP"));
        assertTrue(PictureSubtitles.isPictureName("film.idx"));
        assertFalse(PictureSubtitles.isPictureName("film.sub"));
        assertFalse(PictureSubtitles.isPictureName("film.srt"));
    }

    @Test
    public void aVobSubSubStartsWithAPackHeader() {
        assertTrue(PictureSubtitles.isVobSubData(new byte[]{0x00, 0x00, 0x01, (byte) 0xBA}));
        // MicroDVD and SubViewer .sub files are text
        assertFalse(PictureSubtitles.isVobSubData("{1}{".getBytes()));
        assertFalse(PictureSubtitles.isVobSubData("[INF".getBytes()));
        assertFalse(PictureSubtitles.isVobSubData(new byte[]{0x00, 0x00, 0x01}));
    }
}
