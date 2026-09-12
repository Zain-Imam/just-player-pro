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

    /**
     * Put the focus on the first row, once there is a first row to put it on.
     *
     * Every one of these panels needs this and every one of them had the same
     * bug: a single post, taking whatever it found. What it found was an empty
     * list. The panel is shown and its rows are laid out a frame or two later,
     * so the one attempt asked a list with no children to take focus, which it
     * cannot, and the request was dropped without a word.
     *
     * On a touchscreen nothing looked wrong, because a finger does not need
     * focus. On a remote the panel opened with the focus nowhere at all: every
     * arrow press went to a view that was not there, nothing moved, and Back
     * was the only way out.
     *
     * Asking the list itself instead is not a fix, and was tried. A
     * RecyclerView takes focus the moment it is asked, long before it has any
     * rows, and then there is nothing for the arrows to move between — the
     * panel looks focused and behaves exactly as broken. So this waits for a
     * row, and only settles for the list once it has run out of patience.
     */
    public static void focusFirstRow(
            final androidx.recyclerview.widget.RecyclerView list) {
        if (list == null) {
            return;
        }
        list.post(new Runnable() {
            private int framesLeft = 20;

            @Override
            public void run() {
                // Gone again already — dismissed, or never attached.
                if (!list.isAttachedToWindow()) {
                    return;
                }

                final androidx.recyclerview.widget.RecyclerView.ViewHolder first =
                        list.findViewHolderForAdapterPosition(0);
                final android.view.View row = first != null ? first.itemView
                        : (list.getChildCount() > 0 ? list.getChildAt(0) : null);
                if (row != null && row.requestFocus()) {
                    return;
                }

                if (--framesLeft > 0) {
                    list.post(this);
                    return;
                }

                // Out of frames. The list is better than nothing: Back still
                // closes it, and the rows can still be reached.
                list.requestFocus();
            }
        });
    }
}
