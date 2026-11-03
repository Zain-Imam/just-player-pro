package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

// pins in Recent: half of what it shows, never fewer than three
public class PinsTest {

    @Test
    public void halfOfRecent() {
        assertEquals(5, History.maxPins(10));
        assertEquals(15, History.maxPins(30));
        assertEquals(10, History.maxPins(20));
    }

    @Test
    public void neverFewerThanThree() {
        assertEquals(3, History.maxPins(1));
        assertEquals(3, History.maxPins(4));
        assertEquals(3, History.maxPins(0));
    }

    @Test
    public void recentStopsAtThirty() {
        assertEquals(15, History.maxPins(100));
    }

    @Test
    public void halfOfTheTenOnThisDevice() {
        assertEquals(5, Prefs.MAX_PINS_HERE);
    }
}
