package com.brouken.player.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

// The toast text when OpenSubtitles refuses a download.
// The body is a real reply for an anonymous key that had used its five downloads.
public class OpenSubtitlesRefusalTest {

    private static final String OUT_OF_DOWNLOADS = "{\"requests\":6,\"remaining\":-1,"
            + "\"message\":\"You have downloaded your allowed 5 subtitles for 24h.Your quota will"
            + " be renewed in 11 hours and 45 minutes (2026-10-02 23:59:59 UTC) ts=1790943272 \","
            + "\"reset_time\":\"11 hours and 45 minutes\"}";

    @Test
    public void whenTheyComeBackShortEnoughForAToast() {
        assertEquals("11 h 45 min", Subtitles.refusal(OUT_OF_DOWNLOADS));
        assertEquals("1 h", Subtitles.refusal("{\"reset_time\":\"1 hour\"}"));
        assertEquals("5 min", Subtitles.refusal("{\"reset_time\":\"5 minutes\"}"));
        assertEquals("9 h 58 min", Subtitles.refusal("{\"reset_time\":\"09 hours and 58 minutes\"}"));
    }

    @Test
    public void aRefusalWithNoTimeIsStillARefusal() {
        assertEquals("", Subtitles.refusal("{\"message\":\"Quota exceeded\"}"));
    }

    @Test
    public void somethingElseIsNotARefusal() {
        assertNull(Subtitles.refusal(null));
        assertNull(Subtitles.refusal("not json"));
        assertNull(Subtitles.refusal("{\"errors\":[\"bad\"]}"));
    }
}
