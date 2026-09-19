package com.brouken.player;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 * Work off the main thread that cannot take the app down with it.
 *
 * An uncaught exception on a background thread kills the whole process, and
 * there is nothing on screen to say why: the film simply vanishes back to the
 * launcher mid-scene. That is the worst thing this app can do, and none of the
 * work these threads are given — asking a website for a title, parsing a file
 * name, reading a subtitle — is worth it.
 *
 * A thread made here carries its own handler for whatever it fails to catch, so
 * a failure is one piece of work that did not happen rather than a player that
 * disappeared. The pool simply makes another thread and carries on.
 */
public final class Background {

    private Background() {
    }

    public static ExecutorService single(final String name) {
        return pool(name, 1);
    }

    /**
     * The same, with more than one thread.
     *
     * <p>For work that is independent piece by piece and slow enough that doing
     * it one at a time shows: decoding a frame out of each file in a folder
     * takes about a second apiece, and in a single queue the fifth row sat on a
     * grey rectangle for five seconds while the device had cores to spare.
     *
     * <p>Kept small on purpose. Decoding is the heaviest thing this application
     * does outside playback, and a wide pool on a television box would take the
     * cores the film is using.
     */
    public static ExecutorService pool(final String name, final int threads) {
        return Executors.newFixedThreadPool(Math.max(1, threads), runnable -> {
            final Thread thread = new Thread(runnable, name);
            // Below the player's own work: a picture for a list nobody has
            // scrolled to yet must never compete with the film.
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.setUncaughtExceptionHandler((t, error) ->
                    Utils.log(name + " gave up: " + error));
            return thread;
        });
    }

    /** Runs the work, swallowing anything it throws. */
    public static Runnable safely(final Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (Throwable error) {
                // Deliberately Throwable: a pattern that will not compile or a
                // class that will not initialise arrives as an Error, not an
                // Exception, and takes the process with it just the same.
                Utils.log("Background work failed: " + error);
            }
        };
    }
}
