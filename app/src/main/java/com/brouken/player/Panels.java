package com.brouken.player;

import android.content.Context;
import android.util.DisplayMetrics;

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

    public static void focusFirstRow(
            final androidx.recyclerview.widget.RecyclerView list) {
        focusRow(list, 0);
    }

    // rows lay out a frame or two after show, so retry each frame until the row exists
    public static void focusRow(
            final androidx.recyclerview.widget.RecyclerView list, final int position) {
        if (list == null) {
            return;
        }
        list.post(new Runnable() {
            private int framesLeft = 20;
            private boolean scrolled;

            @Override
            public void run() {
                if (!list.isAttachedToWindow()) {
                    return;
                }

                final androidx.recyclerview.widget.RecyclerView.ViewHolder wanted =
                        list.findViewHolderForAdapterPosition(position);
                final android.view.View row = wanted != null ? wanted.itemView
                        : (position == 0 && list.getChildCount() > 0 ? list.getChildAt(0) : null);
                if (row != null && row.requestFocus()) {
                    return;
                }
                // laid out but off screen: scroll to it, once
                if (!scrolled && position > 0 && list.getChildCount() > 0) {
                    scrolled = true;
                    list.scrollToPosition(position);
                }

                if (--framesLeft > 0) {
                    list.post(this);
                    return;
                }

                list.requestFocus();
            }
        });
    }
}
