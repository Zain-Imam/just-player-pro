package com.brouken.player.mpv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// mpv subtitle size: the corrected scale, and moving sizes saved on the old one.
public class MpvSubtitleScaleTest {

    // the old sub-scale formula, copied verbatim
    private static double scale41(final int step) {
        final double chosen = 1.0 + step * (0.001 / 0.0533);
        return Math.max(0.2, chosen * (0.0533 / (55.0 / 720.0)));
    }

    @Test
    public void theDefaultIsNowMedia3sSize() {
        // at font size 38 parity alone is about 1.01; 248/205 is the glyph match
        final double parity = MpvSubtitleScale.parity(MpvSubtitleScale.DEFAULT_FONT_SIZE);
        assertEquals(0.0533 / (38.0 / 720.0) * (248.0 / 205.0), parity, 1e-9);
        assertEquals(parity, MpvSubtitleScale.scale(0, parity), 1e-9);
    }

    @Test
    public void aMovedSizeDrawsWhatItDrewBefore() {
        final double parity = MpvSubtitleScale.parity(MpvSubtitleScale.DEFAULT_FONT_SIZE);
        final double oneStep = parity * (0.001 / 0.0533);
        for (int old = -10; old <= 50; old++) {
            final int moved = MpvSubtitleScale.stepDrawnLike41(old);
            // Within half a step either way: the setting moves in whole steps.
            assertEquals("step " + old, scale41(old), MpvSubtitleScale.scale(moved, parity),
                    oneStep / 2 + 1e-9);
        }
    }

    @Test
    public void aSizeRaisedToMakeUpForSmallTextComesDown() {
        // +30 on the old scale drew at about 1.09, about -6 on the new one
        final int moved = MpvSubtitleScale.stepDrawnLike41(30);
        assertTrue("moved to " + moved, moved < 0 && moved > -10);
    }
}
