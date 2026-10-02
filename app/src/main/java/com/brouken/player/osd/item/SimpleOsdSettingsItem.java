package com.brouken.player.osd.item;

import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

public class SimpleOsdSettingsItem implements OsdSettingsItem {

    public static final int VIEW_TYPE = 1;

    public final String title;
    public final Drawable icon;
    public final Listener listener;

    // optional second button at the end of the row
    @Nullable
    public final Drawable trailingIcon;
    @Nullable
    public final String trailingDescription;
    @Nullable
    public final Listener trailingListener;

    public SimpleOsdSettingsItem(String title, Drawable icon, Listener listener) {
        this(title, icon, listener, null, null, null);
    }

    public SimpleOsdSettingsItem(String title, Drawable icon, Listener listener,
                                 @Nullable Drawable trailingIcon,
                                 @Nullable String trailingDescription,
                                 @Nullable Listener trailingListener) {
        this.title = title;
        this.icon = icon;
        this.listener = listener;
        this.trailingIcon = trailingIcon;
        this.trailingDescription = trailingDescription;
        this.trailingListener = trailingListener;
    }

    @Override
    public int getViewType() {
        return VIEW_TYPE;
    }

    public interface Listener {
        void onSettingClicked(int position);
    }

}
