package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.brouken.player.online.SkipSegments;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Skipping from the file's own chapter marks.
 *
 * A file that names its chapters has said exactly where its intro and credits
 * are, at the timings of the cut you actually have — which is better than a
 * community database guessing about some other release. That is why chapters
 * are consulted first.
 *
 * None of this can be checked on the device without a file whose chapters are
 * named "Intro" and "Credits", and no public sample has those. The matching is
 * plain Java, so it is checked here instead, where a chapter list can simply be
 * written down.
 */
public class SkipFromChaptersTest {

    private static List<SkipSegments.ChapterMark> chapters(final Object... titleAndStart) {
        final List<SkipSegments.ChapterMark> marks = new ArrayList<>();
        for (int i = 0; i < titleAndStart.length; i += 2) {
            marks.add(new SkipSegments.ChapterMark(
                    (String) titleAndStart[i],
                    ((Number) titleAndStart[i + 1]).doubleValue()));
        }
        return marks;
    }

    @Test
    public void namedChaptersBecomeSegments() {
        final List<SkipSegments.Segment> found = SkipSegments.fromChapters(
                chapters(
                        "Intro", 0,
                        "Part A", 90,
                        "Credits", 1320),
                1400);

        assertEquals("an intro and a set of credits", 2, found.size());

        final SkipSegments.Segment intro = found.get(0);
        assertEquals(SkipSegments.Kind.INTRO, intro.kind);
        assertEquals(0, intro.start, 0.001);
        // A chapter runs until the next one starts.
        assertEquals(90, intro.end, 0.001);

        final SkipSegments.Segment credits = found.get(1);
        assertEquals(SkipSegments.Kind.CREDITS, credits.kind);
        assertEquals(1320, credits.start, 0.001);
        // The last one runs to the end of the film.
        assertEquals(1400, credits.end, 0.001);
    }

    @Test
    public void theNamesPeopleActuallyUse() {
        // Every spelling kindOfChapter accepts, so that widening it later does
        // not quietly narrow it.
        final String[] intros = {"OP", "Opening", "intro", "Avant", "Title", "Titles",
                "Opening credits", "Main title", "Main titles"};
        for (final String name : intros) {
            final List<SkipSegments.Segment> found =
                    SkipSegments.fromChapters(chapters(name, 0, "Show", 60), 600);
            assertEquals(name + " should be an intro", 1, found.size());
            assertEquals(name, SkipSegments.Kind.INTRO, found.get(0).kind);
        }

        final String[] endings = {"ED", "Ending", "Outro", "Credits",
                "End credits", "Closing credits", "Endcard"};
        for (final String name : endings) {
            final List<SkipSegments.Segment> found =
                    SkipSegments.fromChapters(chapters("Show", 0, name, 500), 600);
            assertEquals(name + " should be credits", 1, found.size());
            assertEquals(name, SkipSegments.Kind.CREDITS, found.get(0).kind);
        }

        final List<SkipSegments.Segment> recap =
                SkipSegments.fromChapters(chapters("Previously on Something", 0, "Show", 40), 600);
        assertEquals(1, recap.size());
        assertEquals(SkipSegments.Kind.RECAP, recap.get(0).kind);
    }

    @Test
    public void ordinaryChaptersOfferNothing() {
        // A film chaptered "Chapter 1..12" has not said where anything is, and
        // must not produce a skip button over an arbitrary twelfth of it.
        final List<SkipSegments.Segment> found = SkipSegments.fromChapters(
                chapters("Chapter 1", 0, "Chapter 2", 600, "Chapter 3", 1200), 1800);
        assertTrue("nothing recognisable", found.isEmpty());
    }

    @Test
    public void noChaptersAtAll() {
        assertTrue(SkipSegments.fromChapters(null, 600).isEmpty());
        assertTrue(SkipSegments.fromChapters(new ArrayList<>(), 600).isEmpty());
        // One chapter is not a division of anything.
        assertTrue(SkipSegments.fromChapters(chapters("Intro", 0), 600).isEmpty());
    }
}
