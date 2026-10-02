package com.brouken.player;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// background threads that log uncaught exceptions instead of killing the process
public final class Background {

    private Background() {
    }

    public static ExecutorService single(final String name) {
        return pool(name, 1);
    }

    // keep pools small: frame decoding is heavy and must not starve playback
    public static ExecutorService pool(final String name, final int threads) {
        return Executors.newFixedThreadPool(Math.max(1, threads), runnable -> {
            final Thread thread = new Thread(runnable, name);
            // never compete with playback
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.setUncaughtExceptionHandler((t, error) ->
                    Utils.log(name + " gave up: " + error));
            return thread;
        });
    }

    public static Runnable safely(final Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (Throwable error) {
                // Throwable: an Error (bad regex, failed class init) kills the process too
                Utils.log("Background work failed: " + error);
            }
        };
    }
}
