package com.brouken.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

// position bar shown while locked; takes no touch or focus so a pocket can't scrub
final class LockedTimeline {

    private final Context context;
    private final ViewGroup parent;

    private LinearLayout root;
    private TextView elapsed;
    private TextView remaining;
    private TextView total;
    private Bar bar;

    LockedTimeline(@NonNull final Context context, @NonNull final ViewGroup parent) {
        this.context = context;
        this.parent = parent;
    }

    boolean isShowing() {
        return root != null && root.getVisibility() == View.VISIBLE;
    }

    void show(final int accent) {
        if (root == null) {
            build();
        }
        bar.accent = accent;
        applyInsets();
        root.setVisibility(View.VISIBLE);
    }

    void hide() {
        if (root != null) {
            root.setVisibility(View.GONE);
        }
    }

    // share of the screen height, for lifting the subtitles clear
    float heightFraction() {
        if (root == null || parent.getHeight() <= 0) {
            return 0f;
        }
        // fade included: text under it is dimmed
        return Math.max(0f, root.getHeight() / (float) parent.getHeight());
    }

    void update(final long positionMs, final long durationMs) {
        if (root == null) {
            return;
        }
        final long position = Math.max(0, positionMs);
        elapsed.setText(Utils.formatMilis(position));
        if (durationMs > 0) {
            remaining.setText("−" + Utils.formatMilis(Math.max(0, durationMs - position)));
            total.setText(" / " + Utils.formatMilis(durationMs));
            remaining.setVisibility(View.VISIBLE);
            total.setVisibility(View.VISIBLE);
            bar.setVisibility(View.VISIBLE);
            bar.setProgress(Math.min(1f, position / (float) durationMs));
        } else {
            // live stream: no duration
            remaining.setVisibility(View.GONE);
            total.setVisibility(View.GONE);
            bar.setVisibility(View.GONE);
        }
    }

    private void build() {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        // Times read left to right in every language, like the seek bar.
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(0, 0, 0, 0), Color.argb(0x8C, 0, 0, 0),
                        Color.argb(0xC8, 0, 0, 0)}));
        root.setClickable(false);
        root.setFocusable(false);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);

        elapsed = text(14, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.WHITE);
        row.addView(elapsed, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final View spacer = new View(context);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        remaining = text(14, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.WHITE);
        row.addView(remaining, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        total = text(12, Typeface.DEFAULT, Color.argb(0xBD, 0xFF, 0xFF, 0xFF));
        row.addView(total, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new Bar(context);
        final LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Utils.dpToPx(14));
        barParams.topMargin = Utils.dpToPx(4);
        root.addView(bar, barParams);

        // the parent is a CoordinatorLayout; FrameLayout params lose the gravity
        final CoordinatorLayout.LayoutParams params = new CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        root.setLayoutParams(params);
        root.setVisibility(View.GONE);
        parent.addView(root);
    }

    private TextView text(final int sp, final Typeface typeface, final int color) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(typeface);
        view.setTextColor(color);
        view.setShadowLayer(Utils.dpToPx(3), 0, 0, Color.BLACK);
        // tabular figures so the times don't jitter
        view.setFontFeatureSettings("tnum");
        view.setIncludeFontPadding(false);
        return view;
    }

    // the larger side inset on both ends keeps the bar centred, like the controls
    private void applyInsets() {
        int left = 0;
        int right = 0;
        int bottom = 0;
        final WindowInsets insets = parent.getRootWindowInsets();
        if (insets != null) {
            int cutoutLeft = 0;
            int cutoutRight = 0;
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                cutoutLeft = insets.getDisplayCutout().getSafeInsetLeft();
                cutoutRight = insets.getDisplayCutout().getSafeInsetRight();
            }
            final int side = Utils.safeSideInset(insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetRight(), cutoutLeft, cutoutRight);
            left = side;
            right = side;
            bottom = insets.getSystemWindowInsetBottom();
        }
        final int gutter = Utils.dpToPx(18);
        root.setPadding(gutter + left, Utils.dpToPx(30), gutter + right, Utils.dpToPx(8) + bottom);
    }

    private static final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float progress;
        int accent = Color.WHITE;

        Bar(final Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
        }

        void setProgress(final float progress) {
            this.progress = progress;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull final Canvas canvas) {
            final float knob = Utils.dpToPx(5);
            final float track = Utils.dpToPx(3);
            final float left = knob;
            final float right = getWidth() - knob;
            final float centre = getHeight() / 2f;
            final float at = left + (right - left) * progress;

            paint.setColor(Color.argb(0x52, 0xFF, 0xFF, 0xFF));
            rect.set(left, centre - track / 2, right, centre + track / 2);
            canvas.drawRoundRect(rect, track, track, paint);

            paint.setColor(accent);
            rect.set(left, centre - track / 2, at, centre + track / 2);
            canvas.drawRoundRect(rect, track, track, paint);

            paint.setColor(Color.WHITE);
            canvas.drawCircle(at, centre, knob, paint);
        }
    }
}
