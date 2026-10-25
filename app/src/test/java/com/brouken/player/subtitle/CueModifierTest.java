package com.brouken.player.subtitle;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.text.Cue;

import org.junit.Test;

public class CueModifierTest {

    private static Cue cue(final float line, final int lineType) {
        return new Cue.Builder().setText("line").setLine(line, lineType).build();
    }

    @Test
    public void anUnplacedCueFollowsTheSetting() {
        assertFalse(CueModifier.isSign(new Cue.Builder().setText("srt").build()));
    }

    // WebVTT's default: the last line, counted up from the bottom
    @Test
    public void webVttsDefaultLastLineFollowsTheSetting() {
        assertFalse(CueModifier.isSign(cue(-1, Cue.LINE_TYPE_NUMBER)));
        assertFalse(CueModifier.isSign(cue(-2, Cue.LINE_TYPE_NUMBER)));
    }

    @Test
    public void aLineCountedFromTheTopIsASign() {
        assertTrue(CueModifier.isSign(cue(0, Cue.LINE_TYPE_NUMBER)));
        assertTrue(CueModifier.isSign(cue(2, Cue.LINE_TYPE_NUMBER)));
    }

    @Test
    public void theTopHalfIsASignTheBottomHalfIsNot() {
        assertTrue(CueModifier.isSign(cue(0.1f, Cue.LINE_TYPE_FRACTION)));
        assertFalse(CueModifier.isSign(cue(0.85f, Cue.LINE_TYPE_FRACTION)));
    }
}
