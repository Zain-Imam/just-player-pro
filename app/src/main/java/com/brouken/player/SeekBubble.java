package com.brouken.player;

import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

final class SeekBubble {

    // a frame quicker than this, as from a local file, comes without a spinner first
    private static final long LOADING_AFTER_MS = 250;

    private final View host;
    private final CustomDefaultTimeBar timeBar;
    private final boolean television;

    private final View root;
    private final View frame;
    private final ImageView image;
    private final ProgressBar loading;
    private final TextView time;
    private final TextView delta;

    private float aspect = 16f / 9f;
    private long shownAt;
    private boolean awaiting;
    private final Runnable showLoading = this::showLoading;

    SeekBubble(final View host, final CustomDefaultTimeBar timeBar, final boolean television) {
        this.host = host;
        this.timeBar = timeBar;
        this.television = television;
        root = host.findViewById(R.id.seek_bubble);
        frame = host.findViewById(R.id.seek_bubble_frame);
        image = host.findViewById(R.id.seek_bubble_image);
        loading = host.findViewById(R.id.seek_bubble_loading);
        time = host.findViewById(R.id.seek_bubble_time);
        delta = host.findViewById(R.id.seek_bubble_delta);
        frame.setClipToOutline(true);
    }

    void show(final long positionMs, final long fromMs) {
        shownAt = positionMs;
        time.setText(Utils.formatMilis(positionMs));
        delta.setText(Utils.formatMilisSign(positionMs - fromMs));
        if (root.getVisibility() != View.VISIBLE) {
            Utils.log("Seek preview: bubble up");
        }
        root.setVisibility(View.VISIBLE);
        place(positionMs);
    }

    // timed from the first unanswered ask, so a steady drag still shows the spinner
    void awaitFrame() {
        if (awaiting) {
            return;
        }
        awaiting = true;
        host.postDelayed(showLoading, LOADING_AFTER_MS);
    }

    void noFrame() {
        stopAwaiting();
        if (image.getDrawable() == null) {
            frame.setVisibility(View.GONE);
        }
    }

    void frame(@Nullable final Bitmap bitmap, final long positionMs) {
        if (bitmap == null) {
            return;
        }
        stopAwaiting();
        aspect = bitmap.getWidth() / (float) Math.max(1, bitmap.getHeight());
        if (image.getDrawable() == null) {
            Utils.log("Seek preview: frame shown");
        }
        image.setImageBitmap(bitmap);
        frame.setVisibility(View.VISIBLE);
        place(positionMs);
    }

    void timeOnly() {
        stopAwaiting();
        frame.setVisibility(View.GONE);
        image.setImageDrawable(null);
    }

    void hide() {
        stopAwaiting();
        root.setVisibility(View.GONE);
        frame.setVisibility(View.GONE);
        image.setImageDrawable(null);
    }

    private void showLoading() {
        frame.setVisibility(View.VISIBLE);
        loading.setVisibility(View.VISIBLE);
        place(shownAt);
    }

    private void stopAwaiting() {
        awaiting = false;
        host.removeCallbacks(showLoading);
        loading.setVisibility(View.GONE);
    }

    private void place(final long positionMs) {
        final int width = host.getWidth();
        if (width <= 0) {
            return;
        }
        int frameWidth = television ? Utils.dpToPx(240)
                : Math.max(Utils.dpToPx(144), Math.min(Utils.dpToPx(240), width / 4));
        int frameHeight = Math.round(frameWidth / aspect);
        final int maxHeight = Utils.dpToPx(television ? 160 : 135);
        if (frameHeight > maxHeight) {
            frameHeight = maxHeight;
            frameWidth = Math.round(frameHeight * aspect);
        }
        final ViewGroup.LayoutParams params = frame.getLayoutParams();
        if (params.width != frameWidth || params.height != frameHeight) {
            params.width = frameWidth;
            params.height = frameHeight;
            frame.setLayoutParams(params);
        }

        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(host.getHeight(), View.MeasureSpec.AT_MOST));
        final int bubbleWidth = root.getMeasuredWidth();
        final int bubbleHeight = root.getMeasuredHeight();

        final int[] bar = new int[2];
        final int[] at = new int[2];
        timeBar.getLocationInWindow(bar);
        host.getLocationInWindow(at);
        final float knobX = bar[0] - at[0] + timeBar.knobX(positionMs);
        final float barTop = bar[1] - at[1] + timeBar.barTop();

        final int edge = Utils.dpToPx(8);
        final float x = Math.max(edge, Math.min(width - bubbleWidth - edge, knobX - bubbleWidth / 2f));
        root.setTranslationX(x);
        root.setTranslationY(Math.max(edge, barTop - Utils.dpToPx(8) - bubbleHeight));
    }
}
