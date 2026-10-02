package com.brouken.player;

import android.content.Context;
import android.util.DisplayMetrics;

// one size for every panel that slides in from the edge
public final class Panels {

    // fits a track name with its codec
    private static final float WIDTH_DP = 400f;

    // portrait phones have under 400dp; leave an edge showing
    private static final float MAX_FRACTION = 0.92f;

    private Panels() {
    }

    public static int width(final Context context) {
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.min(Math.round(WIDTH_DP * metrics.density),
                Math.round(metrics.widthPixels * MAX_FRACTION));
    }

    // retries each frame until a row exists: rows lay out a frame or two after show,
    // and a focused but empty RecyclerView leaves the D-pad nothing to move to
    public static void focusFirstRow(
            final androidx.recyclerview.widget.RecyclerView list) {
        if (list == null) {
            return;
        }
        list.post(new Runnable() {
            private int framesLeft = 20;

            @Override
            public void run() {
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

                // out of frames: focus the list itself as a fallback
                list.requestFocus();
            }
        });
    }
}
