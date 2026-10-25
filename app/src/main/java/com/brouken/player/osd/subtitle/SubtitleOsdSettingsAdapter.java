package com.brouken.player.osd.subtitle;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.brouken.player.R;
import com.brouken.player.osd.OsdSettingsAdapter;
import com.brouken.player.osd.item.BooleanOsdSettingsItem;
import com.brouken.player.osd.item.ChoiceOsdSettingsItem;
import com.brouken.player.osd.item.IntegerOsdSettingsItem;
import com.brouken.player.osd.item.OsdSettingsItem;
import com.brouken.player.osd.item.SimpleOsdSettingsItem;
import com.brouken.player.osd.item.SubtitleDelayOsdSettingsItem;

import java.util.ArrayList;

public class SubtitleOsdSettingsAdapter extends OsdSettingsAdapter {

    private final Listener listener;

    public SubtitleOsdSettingsAdapter(@NonNull Context context, @NonNull Listener listener) {
        super(context);
        this.listener = listener;
    }

    public void setInitialValues(int subtitlePosition, int subtitleDelay, int size, SubtitleEdgeType edgeType, SubtitleTypeface typeface, int colorIndex, boolean embeddedStyles) {
        this.items = createSubtitleSettingsArray(subtitlePosition, subtitleDelay, size, edgeType, typeface, colorIndex, embeddedStyles);
    }

    // rows in the order of createSubtitleSettingsArray
    private static final int POSITION_ROW = 0;
    private static final int DELAY_ROW = 1;

    public void setSubtitlePosition(int subtitlePosition) {
        this.items[POSITION_ROW] = createPositionItem(subtitlePosition);
        notifyItemChanged(POSITION_ROW);
    }

    public void setSubtitleDelay(int subtitleDelay) {
        this.items[DELAY_ROW] = createDelayItem(subtitleDelay);
        notifyItemChanged(DELAY_ROW);
    }

    private OsdSettingsItem[] createSubtitleSettingsArray(int subtitlePosition, int subtitleDelay, int size, SubtitleEdgeType edgeType, SubtitleTypeface typeface, int colorIndex, boolean embeddedStyles) {
        return new OsdSettingsItem[]{
                createPositionItem(subtitlePosition),
                createDelayItem(subtitleDelay),
                createSizeItem(size),
                createEdgeTypeItem(edgeType),
                createTypefaceItem(typeface),
                createColorItem(colorIndex),
                createEmbeddedStylesItem(embeddedStyles),
                createCaptioningPreferenceItem(),
                createOnlineSearchItem(),
                createChangeTitleItem()
        };
    }

    private static Drawable getDrawable(Context context, @DrawableRes int id) {
        return ResourcesCompat.getDrawable(context.getResources(), id, context.getTheme());
    }

    // depends on the engine; set before setInitialValues
    private int positionMin = com.brouken.player.Prefs.SUBTITLE_POSITION_MIN;

    public void setPositionMin(int positionMin) {
        this.positionMin = positionMin;
    }

    private OsdSettingsItem createPositionItem(int subtitlePosition) {
        String title = context.getString(R.string.osd_subtitle_position_title);
        String labelDefault = context.getString(R.string.osd_item_integer_default);

        IntegerOsdSettingsItem.Listener itemListener = (position, newValue) -> listener.onSubtitlePositionChange(newValue);
        return new IntegerOsdSettingsItem(title, labelDefault, true, subtitlePosition, itemListener, this)
                .withRange(positionMin, com.brouken.player.Prefs.SUBTITLE_POSITION_MAX);
    }

    private OsdSettingsItem createDelayItem(int subtitleDelay) {
        IntegerOsdSettingsItem.Listener itemListener = (position, newValue) -> listener.onSubtitleDelayChange(newValue);
        return new SubtitleDelayOsdSettingsItem(context, subtitleDelay, itemListener, this);
    }

    private OsdSettingsItem createSizeItem(int size) {
        String title = context.getString(R.string.osd_subtitle_size_title);
        String labelDefault = context.getString(R.string.osd_item_integer_default);

        IntegerOsdSettingsItem.Listener itemListener = (position, newValue) -> listener.onSubtitleSizeChange(newValue);
        return new IntegerOsdSettingsItem(title, labelDefault, true, size, itemListener, this)
                .withRange(com.brouken.player.Prefs.SUBTITLE_SIZE_MIN,
                        com.brouken.player.Prefs.SUBTITLE_SIZE_MAX);
    }

    private OsdSettingsItem createEdgeTypeItem(SubtitleEdgeType edgeType) {
        String title = context.getString(R.string.osd_subtitle_edge_type_title);

        ChoiceOsdSettingsItem.Element[] elements = new ChoiceOsdSettingsItem.Element[SubtitleEdgeType.values().length];
        for (int i = 0; i < elements.length; i++) {
            switch (SubtitleEdgeType.values()[i]) {
                case Default:
                    elements[i] = new ChoiceOsdSettingsItem.Element(context.getString(R.string.osd_subtitle_edge_type_default));
                    break;
                case None:
                    elements[i] = new ChoiceOsdSettingsItem.Element(context.getString(R.string.osd_subtitle_edge_type_none));
                    break;
                case Outline:
                    elements[i] = new ChoiceOsdSettingsItem.Element(context.getString(R.string.osd_subtitle_edge_type_outline));
                    break;
                case DropShadow:
                    elements[i] = new ChoiceOsdSettingsItem.Element(context.getString(R.string.osd_subtitle_edge_type_drop_shadow));
                    break;
                case OutlineShadow:
                    elements[i] = new ChoiceOsdSettingsItem.Element(context.getString(R.string.osd_subtitle_edge_type_outline_shadow));
                    break;
                case Raised:
                    elements[i] = new ChoiceOsdSettingsItem.Element(context.getString(R.string.osd_subtitle_edge_type_raised));
                    break;
                case Depressed:
                    elements[i] = new ChoiceOsdSettingsItem.Element(context.getString(R.string.osd_subtitle_edge_type_depressed));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown SubtitleEdgeType: " + SubtitleEdgeType.values()[i]);
            }
        }

        ChoiceOsdSettingsItem.Listener itemListener = (position, newValueIndex) -> listener.onSubtitleEdgeTypeChange(SubtitleEdgeType.values()[newValueIndex]);
        return new ChoiceOsdSettingsItem(title, elements, edgeType.ordinal(), itemListener, this);
    }

    private OsdSettingsItem createTypefaceItem(SubtitleTypeface typeface) {
        String title = context.getString(R.string.osd_subtitle_typeface_title);

        ArrayList<ChoiceOsdSettingsItem.Element> elementList = new ArrayList<>(SubtitleTypeface.values().length);
        for (int i = 0; i < SubtitleTypeface.values().length; i++) {
            switch (SubtitleTypeface.values()[i]) {
                case Regular:
                    elementList.add(new ChoiceOsdSettingsItem.Element(context.getString(R.string.osd_subtitle_typeface_regular)));
                    break;
                case Medium:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        elementList.add(new ChoiceOsdSettingsItem.Element(context.getString(R.string.osd_subtitle_typeface_medium)));
                    }
                    break;
                case Bold:
                    elementList.add(new ChoiceOsdSettingsItem.Element(context.getString(R.string.osd_subtitle_typeface_bold)));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown SubtitleTypeface: " + SubtitleEdgeType.values()[i]);
            }
        }

        ChoiceOsdSettingsItem.Element[] elements = new ChoiceOsdSettingsItem.Element[elementList.size()];
        elementList.toArray(elements);
        ChoiceOsdSettingsItem.Listener itemListener = (position, newValueIndex) -> listener.onSubtitleTypefaceChange(SubtitleTypeface.values()[newValueIndex]);
        return new ChoiceOsdSettingsItem(title, elements, typeface.ordinal(), itemListener, this);
    }

    private OsdSettingsItem createColorItem(int colorIndex) {
        String title = context.getString(R.string.subtitle_color_title);
        String[] names = context.getResources().getStringArray(R.array.subtitle_color_entries);

        ChoiceOsdSettingsItem.Element[] elements = new ChoiceOsdSettingsItem.Element[names.length];
        for (int i = 0; i < names.length; i++) {
            elements[i] = new ChoiceOsdSettingsItem.Element(names[i],
                    com.brouken.player.SubtitleColors.argb(com.brouken.player.SubtitleColors.keyAt(i)));
        }

        ChoiceOsdSettingsItem.Listener itemListener = (position, newValueIndex) -> listener.onSubtitleColorChange(newValueIndex);
        return new ChoiceOsdSettingsItem(title, elements, Math.max(0, Math.min(colorIndex, elements.length - 1)), itemListener, this);
    }

    private OsdSettingsItem createEmbeddedStylesItem(boolean embeddedStyles) {
        String title = context.getString(R.string.osd_subtitle_embedded_styles_title);
        String labelTrue = context.getString(R.string.osd_item_boolean_true);
        String labelFalse = context.getString(R.string.osd_item_boolean_false);

        BooleanOsdSettingsItem.Listener itemListener = (position, newValue) -> SubtitleOsdSettingsAdapter.this.listener.onSubtitleEmbeddedStylesChange(newValue);
        return new BooleanOsdSettingsItem(title, labelTrue, labelFalse, embeddedStyles, itemListener, this);
    }

    private OsdSettingsItem createOnlineSearchItem() {
        String title = context.getString(R.string.online_search_subtitles);
        Drawable icon = getDrawable(context, R.drawable.ic_search_24dp);
        SimpleOsdSettingsItem.Listener itemListener = position -> listener.onSearchOnlineSubtitles();
        return new SimpleOsdSettingsItem(title, icon, itemListener);
    }

    private OsdSettingsItem createChangeTitleItem() {
        String title = context.getString(R.string.online_change_title);
        Drawable icon = getDrawable(context, R.drawable.ic_manage_search_24dp);
        SimpleOsdSettingsItem.Listener itemListener = position -> listener.onChangeTitle();
        return new SimpleOsdSettingsItem(title, icon, itemListener);
    }

    private OsdSettingsItem createCaptioningPreferenceItem() {
        String title = context.getString(R.string.osd_subtitle_caption_preferences_title);
        @SuppressLint("PrivateResource")
        Drawable icon = getDrawable(context, androidx.media3.ui.R.drawable.exo_styled_controls_settings);
        SimpleOsdSettingsItem.Listener itemListener = position -> listener.onOpenCaptionPreferences();
        return new SimpleOsdSettingsItem(title, icon, itemListener);
    }

    public interface Listener {

        void onSubtitlePositionChange(int position);

        void onSubtitleDelayChange(int delay);

        void onSubtitleSizeChange(int size);

        void onSubtitleEdgeTypeChange(SubtitleEdgeType edgeType);

        void onSubtitleTypefaceChange(SubtitleTypeface typeface);

        void onSubtitleColorChange(int colorIndex);

        void onSubtitleEmbeddedStylesChange(boolean embeddedStyles);

        void onOpenCaptionPreferences();

        void onSearchOnlineSubtitles();

        void onChangeTitle();

    }

}
