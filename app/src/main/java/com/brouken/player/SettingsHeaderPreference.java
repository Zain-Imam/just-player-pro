package com.brouken.player;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

// with two panes, the category open on the right is marked in the accent
public class SettingsHeaderPreference extends Preference {

    private boolean open;

    public SettingsHeaderPreference(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    void setOpen(final boolean open) {
        if (this.open != open) {
            this.open = open;
            notifyChanged();
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final Context context = getContext();
        final int accent = Accent.color(context);
        final View row = holder.itemView;
        // the press and focus highlight stays on top of the mark
        final Drawable touch = themeDrawable(context, android.R.attr.selectableItemBackground);
        if (open) {
            final float density = context.getResources().getDisplayMetrics().density;
            final GradientDrawable fill = new GradientDrawable();
            fill.setColor(ColorUtils.setAlphaComponent(accent, 0x2E));
            fill.setCornerRadius(12 * density);
            final int inset = Math.round(6 * density);
            final LayerDrawable marked = new LayerDrawable(touch == null
                    ? new Drawable[]{fill} : new Drawable[]{fill, touch});
            marked.setLayerInset(0, inset, Math.round(2 * density), inset, Math.round(2 * density));
            row.setBackground(marked);
        } else {
            row.setBackground(touch);
        }
        final View title = holder.findViewById(android.R.id.title);
        if (title instanceof TextView) {
            final TextView text = (TextView) title;
            if (open) {
                text.setTextColor(accent);
            } else {
                text.setTextColor(themeColour(context, android.R.attr.textColorPrimary));
            }
        }
    }

    private static Drawable themeDrawable(final Context context, final int attr) {
        final TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getDrawable(0);
        } finally {
            a.recycle();
        }
    }

    private static int themeColour(final Context context, final int attr) {
        final TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, 0xFF000000);
        } finally {
            a.recycle();
        }
    }
}
