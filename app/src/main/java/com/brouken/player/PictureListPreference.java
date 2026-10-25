package com.brouken.player;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;

// shown as picture cards by SettingsActivity.showPictureChoice
public class PictureListPreference extends ListPreference {

    private final int[] pictures;
    @Nullable
    private final CharSequence[] descriptions;

    public PictureListPreference(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.PictureListPreference);
        final int picturesId = a.getResourceId(R.styleable.PictureListPreference_choicePictures, 0);
        final int descriptionsId = a.getResourceId(R.styleable.PictureListPreference_choiceDescriptions, 0);
        a.recycle();
        if (picturesId != 0) {
            final TypedArray list = context.getResources().obtainTypedArray(picturesId);
            pictures = new int[list.length()];
            for (int i = 0; i < pictures.length; i++) {
                pictures[i] = list.getResourceId(i, 0);
            }
            list.recycle();
        } else {
            pictures = new int[0];
        }
        descriptions = descriptionsId != 0 ? context.getResources().getTextArray(descriptionsId) : null;
    }

    int pictureAt(final int index) {
        return index < pictures.length ? pictures[index] : 0;
    }

    @Nullable
    CharSequence descriptionAt(final int index) {
        return descriptions != null && index < descriptions.length ? descriptions[index] : null;
    }
}
