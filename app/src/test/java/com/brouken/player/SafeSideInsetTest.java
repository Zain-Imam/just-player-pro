package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

// Both ends get the same side inset: the largest of the four reported values.
public class SafeSideInsetTest {

    @Test
    public void nothingToAvoidLeavesNoRoom() {
        assertEquals(0, Utils.safeSideInset(0, 0, 0, 0));
    }

    @Test
    public void aCameraOnOneEdgeIsLeftClearAtBothEnds() {
        // Pixel in landscape: hole punch on one side, nothing on the other
        assertEquals(118, Utils.safeSideInset(118, 0, 118, 0));
        assertEquals(118, Utils.safeSideInset(0, 118, 0, 118));
    }

    @Test
    public void aCameraOnOneEdgeAndANavigationBarOnTheOther() {
        // Motorola landscape: 117px cutout one end, 120px gesture bar the other
        assertEquals(120, Utils.safeSideInset(117, 120, 117, 0));
        assertEquals(120, Utils.safeSideInset(120, 117, 0, 117));
    }

    @Test
    public void aNavigationBarAloneStillCounts() {
        // three-button nav is a system bar; below Android 9 there is no cutout
        assertEquals(126, Utils.safeSideInset(0, 126, 0, 0));
        assertEquals(126, Utils.safeSideInset(126, 0, 0, 0));
    }

    @Test
    public void aCutoutLargerThanTheBarItSitsInStillWins() {
        // a waterfall or tall notch can exceed the system window inset
        assertEquals(150, Utils.safeSideInset(40, 0, 150, 0));
    }

    @Test
    public void bothEndsObstructedTakesTheLarger() {
        assertEquals(130, Utils.safeSideInset(90, 130, 90, 130));
    }

    @Test
    public void negativesCannotProduceNegativeRoom() {
        // padding a view by a negative value would throw
        assertEquals(0, Utils.safeSideInset(-5, -9, -2, -1));
    }
}
