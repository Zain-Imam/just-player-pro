package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.brouken.player.online.SkipController;
import com.brouken.player.online.SkipSegments;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/*
 * The undo offer, on a real device, on the real handler.
 *
 * This cannot be reached by driving the player itself: the skip button only
 * appears over a file that has chapter marks or a film the databases know, and
 * there is no such file to hand. The timing is the whole of what changed, and
 * timing is exactly what a device runs differently from a laptop -- so the
 * controller is built here with the same Activity, the same layout and the same
 * main-thread Handler it has in the player, and only the film is a stand-in.
 *
 * SettingsActivity is borrowed as somewhere to put the button. Any of this
 * app's own activities would do; that one needs no media to open.
 */
@RunWith(AndroidJUnit4.class)
public class SkipUndoDeviceTest {

    /** A film that is not playing, whose position the test moves by hand. */
    private static final class Stub implements SkipController.Host {
        volatile double position;
        volatile boolean playing = true;
        volatile double seekedTo = -1;

        @Override
        public double positionSeconds() {
            return position;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public double durationSeconds() {
            return 600;
        }

        @Override
        public void seekToSeconds(final double seconds) {
            seekedTo = seconds;
            position = seconds;
        }

        @Nullable
        @Override
        public List<SkipSegments.ChapterMark> chapters() {
            final List<SkipSegments.ChapterMark> marks = new ArrayList<>();
            marks.add(new SkipSegments.ChapterMark("Intro", 0));
            marks.add(new SkipSegments.ChapterMark("Chapter One", 60));
            return marks;
        }
    }

    @Test
    public void theUndoOfferGoesAfterThreeSeconds() throws Exception {
        final Stub film = new Stub();
        final AtomicReference<SkipController> held = new AtomicReference<>();
        final AtomicReference<ViewGroup> rootHeld = new AtomicReference<>();

        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(SettingsActivity.class)) {

            scenario.onActivity(activity -> {
                final ViewGroup root = activity.findViewById(android.R.id.content);
                rootHeld.set(root);
                final SkipController controller = new SkipController(activity, root, film);
                held.set(controller);
                controller.load(null);
            });

            // The file's own chapters, so no lookup and no network.
            final Button skip = waitForButton(rootHeld, 5000);
            assertNotNull("the skip button never appeared for a named intro", skip);
            assertEquals("Skip intro", textOf(scenario, skip));

            click(scenario, skip);

            assertEquals("skipping should land just past the intro",
                    60.5, film.seekedTo, 0.01);
            assertEquals("the same button becomes the undo offer",
                    "Undo skip", textOf(scenario, skip));
            assertTrue("undo should be on screen straight after a skip",
                    visible(scenario, skip));

            /*
             * Paused, which is the case the old version could never get out of.
             *
             * The offer used to expire on playback position, so a film that was
             * not moving never reached the end of the window and the button sat
             * there for as long as it was left. Three seconds of real time is
             * three seconds whatever the film is doing.
             */
            film.playing = false;

            Thread.sleep(2000);
            assertTrue("undo should still be offered two seconds in",
                    visible(scenario, skip));

            Thread.sleep(1600);
            assertTrue("undo should be gone three seconds after the skip",
                    !visible(scenario, skip));

            /*
             * And going back into the intro offers it again.
             *
             * A segment that has been skipped is not struck off: returning to
             * it is the most likely moment to want the button, and it is also
             * how anybody checks what they just missed.
             */
            film.position = 10;
            film.playing = true;
            final Button again = waitForButton(rootHeld, 3000);
            assertNotNull("the skip offer should return on seeking back in", again);
            assertEquals("Skip intro", textOf(scenario, again));

            scenario.onActivity(activity -> held.get().stop());
        }
    }

    // ------------------------------------------------------------- plumbing

    private static Button waitForButton(final AtomicReference<ViewGroup> root, final long ms)
            throws Exception {
        final long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            final Button found = onMainThread(() -> {
                final ViewGroup parent = root.get();
                for (int i = 0; i < parent.getChildCount(); i++) {
                    final View child = parent.getChildAt(i);
                    if (child instanceof Button && child.getVisibility() == View.VISIBLE) {
                        return (Button) child;
                    }
                }
                return null;
            });
            if (found != null) {
                return found;
            }
            Thread.sleep(100);
        }
        return null;
    }

    private static void click(final ActivityScenario<SettingsActivity> scenario, final Button b) {
        scenario.onActivity(activity -> b.performClick());
    }

    private static String textOf(final ActivityScenario<SettingsActivity> scenario, final Button b)
            throws Exception {
        return onMainThread(() -> b.getText().toString());
    }

    private static boolean visible(final ActivityScenario<SettingsActivity> scenario,
                                   final Button b) throws Exception {
        return onMainThread(() -> b.getVisibility() == View.VISIBLE);
    }

    private interface OnMain<T> {
        T get();
    }

    private static <T> T onMainThread(final OnMain<T> work) throws Exception {
        final AtomicReference<T> answer = new AtomicReference<>();
        final Object lock = new Object();
        final boolean[] done = {false};
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            answer.set(work.get());
            synchronized (lock) {
                done[0] = true;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            final long deadline = System.currentTimeMillis() + 5000;
            while (!done[0] && System.currentTimeMillis() < deadline) {
                lock.wait(100);
            }
        }
        return answer.get();
    }
}
