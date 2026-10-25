package com.brouken.player.online;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.brouken.player.Accent;
import com.brouken.player.R;

public final class MatchPrompt {

    private final Activity activity;
    private final ViewGroup attachTo;
    private final Runnable onOpen;
    private final Runnable onDismiss;

    @Nullable
    private View root;

    public MatchPrompt(final Activity activity, final ViewGroup attachTo,
                       final Runnable onOpen, final Runnable onDismiss) {
        this.activity = activity;
        this.attachTo = attachTo;
        this.onOpen = onOpen;
        this.onDismiss = onDismiss;
    }

    // left and top in the parent's coordinates
    public void show(final int left, final int top) {
        if (root == null) {
            root = activity.getLayoutInflater().inflate(R.layout.match_prompt, attachTo, false);
            final com.brouken.player.PlayerStyle style = com.brouken.player.PlayerStyle.of(activity);
            if (style != null) {
                style.dressMatchPrompt(root);
            } else {
                final float density = activity.getResources().getDisplayMetrics().density;
                final GradientDrawable pill = new GradientDrawable();
                pill.setColor(0xEB282828);
                pill.setCornerRadius(1000 * density);
                pill.setStroke(Math.round(1.5f * density), Accent.color(activity));
                root.setBackground(pill);
                final android.widget.TextView open = root.findViewById(R.id.match_prompt_open);
                open.setCompoundDrawableTintList(
                        android.content.res.ColorStateList.valueOf(Accent.color(activity)));
            }
            root.findViewById(R.id.match_prompt_open).setOnClickListener(v -> {
                hide();
                onOpen.run();
            });
            root.findViewById(R.id.match_prompt_close).setOnClickListener(v -> {
                hide();
                onDismiss.run();
            });
            // margins are left/top, so keep LTR on right-to-left devices
            root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            attachTo.addView(root);
        }
        final ViewGroup.LayoutParams params = root.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            final ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            margins.leftMargin = left;
            margins.topMargin = top;
            root.setLayoutParams(margins);
        }
        root.setVisibility(View.VISIBLE);
    }

    public void hide() {
        if (root != null) {
            root.setVisibility(View.GONE);
        }
    }

    public boolean isShowing() {
        return root != null && root.getVisibility() == View.VISIBLE;
    }
}
