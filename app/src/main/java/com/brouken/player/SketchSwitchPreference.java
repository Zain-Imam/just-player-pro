package com.brouken.player;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

public class SketchSwitchPreference extends SwitchPreferenceCompat {

    private final int sketch;

    public SketchSwitchPreference(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SketchSwitchPreference);
        sketch = a.getResourceId(R.styleable.SketchSwitchPreference_switchSketch, 0);
        a.recycle();
        setLayoutResource(R.layout.pref_sketch_switch);
    }

    @Override
    public void onBindViewHolder(@NonNull final PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View view = holder.findViewById(R.id.pref_sketch);
        if (view instanceof ImageView) {
            ((ImageView) view).setImageResource(sketch);
            view.setVisibility(sketch == 0 ? View.GONE : View.VISIBLE);
            // greyed with the row when the setting cannot apply
            view.setAlpha(isEnabled() ? 1f : 0.4f);
        }
    }
}
