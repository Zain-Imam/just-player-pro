package com.brouken.player;

import android.content.Context;
import android.util.DisplayMetrics;

/*
 * One shape for every panel that slides in from the edge.
 *
 * Four different things open along the side of the player — audio tracks,
 * subtitle tracks, the quick settings and the subtitle settings — and they were
 * each sized by whatever their own contents happened to need. Opening two of
 * them one after the other meant two different boxes in two different places.
 * They all ask here instead.
 */
public final class Panels {

    /** Wide enough for a track name with its codec after it, and no wider. */
    private static final float WIDTH_DP = 400f;

    /** A phone in portrait has less than 400dp to give; leave an edge showing. */
    private static final float MAX_FRACTION = 0.92f;

    private Panels() {
    }

    public static int width(final Context context) {
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.min(Math.round(WIDTH_DP * metrics.density),
                Math.round(metrics.widthPixels * MAX_FRACTION));
    }
}
