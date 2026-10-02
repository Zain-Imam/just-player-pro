package com.brouken.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// When a film counts as finished, so it is not resumed on its last frame.
public class WatchedThroughTest {

    private static final long HOUR = 60 * 60 * 1000L;

    @Test
    public void theLastFrameIsFinished() {
        assertTrue(Prefs.watchedThrough(HOUR, HOUR));
    }

    @Test
    public void aSecondShortOfTheEndIsStillFinished() {
        // Engines disagree with containers about where the end is, by a little.
        assertTrue(Prefs.watchedThrough(HOUR - 1000, HOUR));
        assertTrue(Prefs.watchedThrough(HOUR - 2999, HOUR));
    }

    @Test
    public void tenSecondsShortOfTheEndIsNot() {
        assertFalse(Prefs.watchedThrough(HOUR - 10_000, HOUR));
    }

    @Test
    public void theMiddleIsNot() {
        assertFalse(Prefs.watchedThrough(HOUR / 2, HOUR));
    }

    @Test
    public void theBeginningIsNot() {
        assertFalse(Prefs.watchedThrough(0, HOUR));
    }

    @Test
    public void anUnknownLengthDecidesNothing() {
        // a live stream, or a file the engine has not measured yet
        assertFalse(Prefs.watchedThrough(HOUR, 0));
        assertFalse(Prefs.watchedThrough(HOUR, -1));
    }

    @Test
    public void aClipShorterThanTheAllowanceIsFinishedOnlyAtItsEnd() {
        // the three second allowance must not cover a whole two second clip
        assertFalse(Prefs.watchedThrough(0, 2000));
        assertTrue(Prefs.watchedThrough(2000, 2000));
    }
}
