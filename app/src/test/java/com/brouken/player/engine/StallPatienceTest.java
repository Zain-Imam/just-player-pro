package com.brouken.player.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StallPatienceTest {

    @Test
    public void stopsCloseTogetherWaitLongerEachTime() {
        final StallPatience patience = new StallPatience();
        assertEquals(2_000, patience.onStall(0));
        assertEquals(4_000, patience.onStall(10_000));
        assertEquals(8_000, patience.onStall(30_000));
        assertEquals(16_000, patience.onStall(70_000));
        assertEquals(30_000, patience.onStall(150_000));
        assertEquals(30_000, patience.onStall(300_000));
    }

    @Test
    public void threeCalmMinutesStartOver() {
        final StallPatience patience = new StallPatience();
        patience.onStall(0);
        patience.onStall(5_000);
        assertEquals(2_000, patience.onStall(5_000 + StallPatience.CALM_MS + 1));
    }
}
