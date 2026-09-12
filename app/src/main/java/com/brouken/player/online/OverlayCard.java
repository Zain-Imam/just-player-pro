package com.brouken.player.online;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.brouken.player.R;

import java.util.Locale;

public final class OverlayCard {

    private final Activity activity;
    private final ViewGroup attachTo;
    private final View bounds;

    @Nullable
    private View root;
    @Nullable
    private ImageView poster;
    @Nullable
    private TextView heading;
    @Nullable
    private TextView meta;
    @Nullable
    private TextView overview;

    public OverlayCard(final Activity activity, final ViewGroup attachTo, final View bounds) {
        this.activity = activity;
        this.attachTo = attachTo;
        this.bounds = bounds;
    }

    private void inflateIfNeeded() {
        if (root != null) {
            return;
        }
        root = activity.getLayoutInflater().inflate(R.layout.online_overlay, attachTo, false);

        poster = root.findViewById(R.id.overlay_poster);
        heading = root.findViewById(R.id.overlay_heading);
        meta = root.findViewById(R.id.overlay_meta);
        overview = root.findViewById(R.id.overlay_overview);

        // The margins below are left/top, so they must not be re-read as
        // start/end on a right-to-left device.
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        attachTo.addView(root);

        // Rotation, an aspect-ratio change and a resize all reach us the same
        // way: the frame we are copying gets laid out at a new size.
        bounds.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> syncToVideo());
    }

    private void syncToVideo() {
        if (root == null || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            return;
        }
        final ViewGroup.LayoutParams params = root.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        final ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;

        final int[] videoAt = new int[2];
        final int[] parentAt = new int[2];
        bounds.getLocationInWindow(videoAt);
        attachTo.getLocationInWindow(parentAt);

        final int rawLeft = videoAt[0] - parentAt[0];
        final int rawTop = videoAt[1] - parentAt[1];

        final int left = Math.max(0, rawLeft);
        final int top = Math.max(0, rawTop);
        final int right = Math.min(attachTo.getWidth(), rawLeft + bounds.getWidth());
        final int bottom = Math.min(attachTo.getHeight(), rawTop + bounds.getHeight());

        final int width = right - left;
        final int height = bottom - top;
        if (width <= 0 || height <= 0) {
            return;
        }

        if (margins.width == width && margins.height == height
                && margins.leftMargin == left && margins.topMargin == top) {
            return;
        }

        margins.width = width;
        margins.height = height;
        margins.setMargins(left, top, 0, 0);
        root.setLayoutParams(margins);
    }

    public void show(final Identity identity) {
        inflateIfNeeded();
        if (root == null) {
            return;
        }

        heading.setText(identity.heading());
        meta.setText(metaLine(identity));
        meta.setVisibility(metaLine(identity).isEmpty() ? View.GONE : View.VISIBLE);

        final String synopsis = identity.overview;
        overview.setText(synopsis == null ? "" : synopsis);
        overview.setVisibility(synopsis == null || synopsis.isEmpty() ? View.GONE : View.VISIBLE);

        Posters.load(poster, Posters.url(identity.posterPath), R.drawable.online_poster_placeholder);

        syncToVideo();
        root.setVisibility(View.VISIBLE);
        // The frame can still be mid-layout on the first pause after opening a
        // file, in which case the measurements above were of nothing yet.
        root.post(this::syncToVideo);
    }

    /*
     * Measure again, because the picture just changed shape.
     *
     * A layout listener catches a rotation or a window resize, but stepping
     * through the scaling modes can leave the frame the same size while the
     * picture inside it is not, and then the card keeps the width it had.
     */
    public void refresh() {
        if (root == null || root.getVisibility() != View.VISIBLE) {
            return;
        }
        syncToVideo();
        root.post(this::syncToVideo);
    }

    public void hide() {
        if (root != null) {
            root.setVisibility(View.GONE);
        }
    }

    @Nullable
    public View box() {
        return isShowing() ? (root == null ? null : root.findViewById(R.id.online_overlay)) : null;
    }

    public boolean isShowing() {
        return root != null && root.getVisibility() == View.VISIBLE;
    }

    private String metaLine(final Identity identity) {
        final StringBuilder sb = new StringBuilder();
        if (identity.airDate != null && !identity.airDate.isEmpty()) {
            sb.append(identity.airDate);
        }
        if (identity.rating > 0) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(String.format(Locale.ROOT, "★ %.1f", identity.rating));
        }
        return sb.toString();
    }
}
