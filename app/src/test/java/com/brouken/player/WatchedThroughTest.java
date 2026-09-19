package com.brouken.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When a film counts as finished.
 *
 * <p>The answer decides what is written down as the place to pick it up from.
 * Getting it wrong in one direction loses somebody's place in a film they were
 * halfway through; getting it wrong in the other leaves a finished film sitting
 * on its last frame, which shows a still and a play button instead of a film --
 * and with "play the next file automatically" turned on, walks the whole folder
 * at speed, because every already-watched file ends the instant it loads.
 */
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
        // A live stream, or a file the engine has not measured yet. Whatever
        // the position is, it is kept as it is.
        assertFalse(Prefs.watchedThrough(HOUR, 0));
        assertFalse(Prefs.watchedThrough(HOUR, -1));
    }

    @Test
    public void aClipShorterThanTheAllowanceIsFinishedOnlyAtItsEnd() {
        // A two second clip is shorter than the three second allowance, so the
        // allowance must not swallow the whole of it: position zero is somebody
        // who has not started, whatever the arithmetic says.
        assertFalse(Prefs.watchedThrough(0, 2000));
        assertTrue(Prefs.watchedThrough(2000, 2000));
    }
}
