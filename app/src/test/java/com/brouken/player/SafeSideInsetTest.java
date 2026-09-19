package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * How much room the controls leave at each end, on every shape of screen.
 *
 * <p>The rule is one number for both ends, and the cases below are the real
 * ones: they are what actual phones report, and between them they are why the
 * rule is a maximum over four numbers rather than a side-by-side inset.
 */
public class SafeSideInsetTest {

    @Test
    public void nothingToAvoidLeavesNoRoom() {
        // A phone held upright, a tablet, a television box. The controls run
        // edge to edge exactly as they always have.
        assertEquals(0, Utils.safeSideInset(0, 0, 0, 0));
    }

    @Test
    public void aCameraOnOneEdgeIsLeftClearAtBothEnds() {
        // A Pixel in landscape: the hole punch lands on one side and there is
        // nothing at all on the other. Insetting only the camera side is what
        // made the seek bar start short and finish flush.
        assertEquals(118, Utils.safeSideInset(118, 0, 118, 0));
        assertEquals(118, Utils.safeSideInset(0, 118, 0, 118));
    }

    @Test
    public void aCameraOnOneEdgeAndANavigationBarOnTheOther() {
        // A Motorola in landscape: a 117px cutout at one end and the gesture
        // bar's 120px at the other. The larger wins, so both ends match.
        assertEquals(120, Utils.safeSideInset(117, 120, 117, 0));
        assertEquals(120, Utils.safeSideInset(120, 117, 0, 117));
    }

    @Test
    public void aNavigationBarAloneStillCounts() {
        // Three-button navigation, which moves to one side in landscape and is
        // reported as a system bar rather than as a cutout. Phones below
        // Android 9 have no cutout to report at all and land here too.
        assertEquals(126, Utils.safeSideInset(0, 126, 0, 0));
        assertEquals(126, Utils.safeSideInset(126, 0, 0, 0));
    }

    @Test
    public void aCutoutLargerThanTheBarItSitsInStillWins() {
        // A waterfall or a tall notch can be reported as more than the system
        // window inset for that side, so the cutout is not simply ignored when
        // the two disagree.
        assertEquals(150, Utils.safeSideInset(40, 0, 150, 0));
    }

    @Test
    public void bothEndsObstructedTakesTheLarger() {
        assertEquals(130, Utils.safeSideInset(90, 130, 90, 130));
    }

    @Test
    public void negativesCannotProduceNegativeRoom() {
        // Nothing should report a negative inset, but padding a view by one
        // would throw, and a crash is a poor answer to a strange number.
        assertEquals(0, Utils.safeSideInset(-5, -9, -2, -1));
    }
}
