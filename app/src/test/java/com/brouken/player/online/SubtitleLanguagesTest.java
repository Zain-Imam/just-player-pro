package com.brouken.player.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SubtitleLanguagesTest {

    @Test
    public void eachSourceGetsItsOwnCode() {
        assertEquals("pt-br", SubtitleLanguages.forOpenSubtitles("pt-br"));
        assertEquals("BR_PT", SubtitleLanguages.forSubdl("pt-br"));
        assertEquals("pt", SubtitleLanguages.forWyzie("pt-br"));
        assertEquals("zh-tw", SubtitleLanguages.forOpenSubtitles("zh-tw"));
        assertEquals("ZH_BG", SubtitleLanguages.forSubdl("zh-tw"));
        assertEquals("fa", SubtitleLanguages.forOpenSubtitles("fa"));
        assertEquals("UK", SubtitleLanguages.forSubdl("uk"));
        assertEquals("az-az", SubtitleLanguages.forOpenSubtitles("az-az"));
        assertEquals("az", SubtitleLanguages.forWyzie("az-az"));
    }

    @Test
    public void aSourceWithoutTheLanguageIsSkipped() {
        assertNull(SubtitleLanguages.forOpenSubtitles("fr-ca"));
        assertNull(SubtitleLanguages.forSubdl("ze"));
        assertNull(SubtitleLanguages.forWyzie("ea"));
        assertNull(SubtitleLanguages.forOpenSubtitles("sr-cyrl"));
    }

    // OpenSubtitles never accepted bare pt or zh
    @Test
    public void anOldChoiceAsksForBothKinds() {
        assertEquals("pt-br,pt-pt", SubtitleLanguages.forOpenSubtitles("pt"));
        assertEquals("BR_PT,PT", SubtitleLanguages.forSubdl("pt"));
        assertEquals("zh-cn,zh-tw", SubtitleLanguages.forOpenSubtitles("zh"));
        assertEquals("en", SubtitleLanguages.forOpenSubtitles("en"));
    }

    @Test
    public void anUnknownTwoLetterCodeGoesAsTyped() {
        assertEquals("xx", SubtitleLanguages.forOpenSubtitles("xx"));
        assertEquals("XX", SubtitleLanguages.forSubdl("xx"));
        assertNull(SubtitleLanguages.forWyzie("xyz"));
    }

    @Test
    public void resultsMatchInAnySpelling() {
        assertTrue(SubtitleLanguages.same("english", "en"));
        assertTrue(SubtitleLanguages.same("eng", "en"));
        assertTrue(SubtitleLanguages.same("fre", "fr"));
        assertTrue(SubtitleLanguages.same("fra", "fr"));
        assertTrue(SubtitleLanguages.same("pob", "pt-br"));
        assertTrue(SubtitleLanguages.same("BR_PT", "pt-br"));
        assertTrue(SubtitleLanguages.same("per", "fa"));
        // a plain Portuguese result suits either kind; the other kind does not
        assertTrue(SubtitleLanguages.same("pt", "pt-br"));
        assertTrue(SubtitleLanguages.same("pt-pt", "pt"));
        assertFalse(SubtitleLanguages.same("pt-pt", "pt-br"));
        assertFalse(SubtitleLanguages.same("es", "fr"));
    }

    @Test
    public void tracksArePickedByARealTag() {
        assertEquals("pt-BR", SubtitleLanguages.trackTag("pt-br"));
        assertEquals("es-ES", SubtitleLanguages.trackTag("sp"));
        assertEquals("en", SubtitleLanguages.trackTag("en"));
    }

    @Test
    public void namesComeFromTheLanguageItself() {
        assertTrue(SubtitleLanguages.name("pt-br").startsWith("Portuguese"));
        assertTrue(SubtitleLanguages.name("ze").contains("+"));
        assertEquals(SubtitleLanguages.codes().size(), new java.util.HashSet<>(SubtitleLanguages.codes()).size());
        assertTrue(SubtitleLanguages.codes().size() >= 115);
    }
}
