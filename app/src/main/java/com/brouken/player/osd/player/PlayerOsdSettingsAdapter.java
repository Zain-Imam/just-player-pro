package com.brouken.player.osd.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.brouken.player.R;
import com.brouken.player.mpv.MpvPlayer;
import com.brouken.player.osd.OsdSettingsAdapter;
import com.brouken.player.osd.item.AudioDelayOsdSettingsItem;
import com.brouken.player.osd.item.BooleanOsdSettingsItem;
import com.brouken.player.osd.item.ChoiceOsdSettingsItem;
import com.brouken.player.osd.item.IntegerOsdSettingsItem;
import com.brouken.player.osd.item.OsdSettingsItem;
import com.brouken.player.osd.item.SimpleOsdSettingsItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlayerOsdSettingsAdapter extends OsdSettingsAdapter {

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

    private final Listener listener;

    private String[] engineValues = new String[0];

    public PlayerOsdSettingsAdapter(@NonNull Context context, @NonNull Listener listener) {
        super(context);
        this.listener = listener;
    }

    public void setInitialValues(final float speed, final String engine,
                                 final boolean overlayOnPause, final boolean skipSegments,
                                 final boolean adaptiveBuffering,
                                 final int sleepMinutes, final boolean sleepAtEnd,
                                 final int videoTracks, final int audioDelayMs) {
        /*
         * The rows you change with the arrows first, the rows you press second.
         *
         * Not a tidiness: the two kinds do not look alike -- one carries a
         * value between two arrows, the other a single icon and a title -- and
         * a row of the first kind dropped among the second reads as a mistake
         * and puts the arrows in a place the eye is not looking for them.
         */
        final List<OsdSettingsItem> items = new ArrayList<>();
        items.add(createSpeedItem(speed));
        items.add(createAudioDelayItem(audioDelayMs));
        items.add(createEngineItem(engine));
        items.add(createOverlayItem(overlayOnPause));
        items.add(createSkipItem(skipSegments));
        items.add(createBufferingItem(adaptiveBuffering));
        items.add(createSleepItem(sleepMinutes, sleepAtEnd));
        if (videoTracks > 1) {
            // Nothing to choose between on a file with one video track, and a row
            // that opens a list of one is a row worth not having.
            items.add(createVideoTrackItem());
        }
        items.add(createInfoCardItem());
        items.add(createAudioTrackItem());
        items.add(createSubtitleSettingsItem());
        items.add(createCopyLinkItem());
        items.add(createAllSettingsItem());
        this.items = items.toArray(new OsdSettingsItem[0]);
    }


    // Off, a set number of minutes, or when the file finishes.
    static final int[] SLEEP_MINUTES = {0, 15, 30, 45, 60, 90, -1};

    private OsdSettingsItem createSleepItem(final int currentMinutes, final boolean endOfFile) {
        final ChoiceOsdSettingsItem.Element[] elements =
                new ChoiceOsdSettingsItem.Element[SLEEP_MINUTES.length];
        int selected = 0;
        for (int i = 0; i < SLEEP_MINUTES.length; i++) {
            final int minutes = SLEEP_MINUTES[i];
            elements[i] = new ChoiceOsdSettingsItem.Element(
                    minutes == 0 ? context.getString(R.string.osd_sleep_off)
                            : minutes < 0 ? context.getString(R.string.osd_sleep_end)
                            : context.getString(R.string.osd_sleep_minutes, minutes));
            if (minutes < 0 && endOfFile) {
                selected = i;
            } else if (minutes > 0 && !endOfFile && currentMinutes > 0
                    && minutes >= currentMinutes) {
                selected = Math.min(selected == 0 ? i : selected, i);
            }
        }
        return new ChoiceOsdSettingsItem(context.getString(R.string.osd_sleep_title),
                elements, selected,
                (position, index) -> listener.onSleepChange(SLEEP_MINUTES[index]), this);
    }
    private OsdSettingsItem createSpeedItem(final float speed) {
        final ChoiceOsdSettingsItem.Element[] elements =
                new ChoiceOsdSettingsItem.Element[SPEEDS.length];
        int selected = 2;
        for (int i = 0; i < SPEEDS.length; i++) {
            elements[i] = new ChoiceOsdSettingsItem.Element(SPEEDS[i] == 1f
                    ? context.getString(R.string.osd_player_speed_normal)
                    : String.format(Locale.getDefault(), "%.2f×", SPEEDS[i])
                            .replace(".00", "").replace("0×", "×"));
            // Nearest match, so a speed set by gesture still shows up here.
            if (Math.abs(SPEEDS[i] - speed) < Math.abs(SPEEDS[selected] - speed)) {
                selected = i;
            }
        }

        final ChoiceOsdSettingsItem.Listener itemListener =
                (position, newValueIndex) -> listener.onSpeedChange(SPEEDS[newValueIndex]);
        return new ChoiceOsdSettingsItem(context.getString(R.string.osd_player_speed_title),
                elements, selected, itemListener, this);
    }

    private OsdSettingsItem createEngineItem(final String engine) {
        final String[] entries = context.getResources().getStringArray(R.array.playback_engine_entries);
        final String[] values = context.getResources().getStringArray(R.array.playback_engine_values);

        final List<ChoiceOsdSettingsItem.Element> elements = new ArrayList<>();
        final List<String> kept = new ArrayList<>();
        for (int i = 0; i < values.length && i < entries.length; i++) {
            if (!MpvPlayer.isSupported() && !"media3".equals(values[i])) {
                continue;
            }
            elements.add(new ChoiceOsdSettingsItem.Element(entries[i]));
            kept.add(values[i]);
        }
        engineValues = kept.toArray(new String[0]);

        int selected = 0;
        for (int i = 0; i < engineValues.length; i++) {
            if (engineValues[i].equals(engine)) {
                selected = i;
                break;
            }
        }

        final ChoiceOsdSettingsItem.Listener itemListener =
                (position, newValueIndex) -> listener.onEngineChange(engineValues[newValueIndex]);
        return new ChoiceOsdSettingsItem(context.getString(R.string.pref_playback_engine),
                elements.toArray(new ChoiceOsdSettingsItem.Element[0]), selected, itemListener, this);
    }

    private OsdSettingsItem createOverlayItem(final boolean enabled) {
        final BooleanOsdSettingsItem.Listener itemListener =
                (position, newValue) -> listener.onOverlayChange(newValue);
        return booleanItem(R.string.pref_overlay, enabled, itemListener);
    }

    private OsdSettingsItem createSkipItem(final boolean enabled) {
        final BooleanOsdSettingsItem.Listener itemListener =
                (position, newValue) -> listener.onSkipChange(newValue);
        return booleanItem(R.string.pref_skip, enabled, itemListener);
    }

    private OsdSettingsItem createBufferingItem(final boolean enabled) {
        final BooleanOsdSettingsItem.Listener itemListener =
                (position, newValue) -> listener.onAdaptiveBufferingChange(newValue);
        return booleanItem(R.string.pref_adaptive_buffering, enabled, itemListener);
    }

    private OsdSettingsItem booleanItem(final int titleRes, final boolean value,
                                        final BooleanOsdSettingsItem.Listener itemListener) {
        return new BooleanOsdSettingsItem(context.getString(titleRes),
                context.getString(R.string.osd_item_boolean_true),
                context.getString(R.string.osd_item_boolean_false),
                value, itemListener, this);
    }

    private OsdSettingsItem createVideoTrackItem() {
        final Drawable icon = getDrawable(R.drawable.ic_hd_24dp);
        return new SimpleOsdSettingsItem(context.getString(R.string.video_menu_title), icon,
                position -> listener.onOpenVideoTracks());
    }

    /*
     * Show the card, and — on the end of the same row — look it up again.
     *
     * Identification reads the file name and guesses, and when the guess is
     * wrong the card is confidently wrong with it. The button on the end is
     * the way to say so: it asks what this actually is, starting from the name
     * it guessed, and then offers the posters it found.
     *
     * Deliberately not the subtitle search, which is a different question with
     * a different answer and already has its own row.
     */
    private OsdSettingsItem createInfoCardItem() {
        @SuppressLint("PrivateResource")
        final Drawable icon = getDrawable(R.drawable.ic_info_card_24dp);
        return new SimpleOsdSettingsItem(context.getString(R.string.osd_info_card), icon,
                position -> listener.onShowInfoCard(),
                getDrawable(R.drawable.ic_search_24dp),
                context.getString(R.string.osd_info_card_search_again),
                position -> listener.onSearchAgain());
    }

    /*
     * The address of what is playing, on the clipboard.
     *
     * A URL for a stream, a path for a file. Reaching it any other way means
     * going back to whatever opened the player, which for a link handed over by
     * another app is often nowhere at all.
     */
    private OsdSettingsItem createCopyLinkItem() {
        final Drawable icon = getDrawable(R.drawable.ic_content_copy_24dp);
        return new SimpleOsdSettingsItem(context.getString(R.string.copy_link), icon,
                position -> listener.onCopyLink());
    }

    private OsdSettingsItem createAudioTrackItem() {
        @SuppressLint("PrivateResource")
        // Our own audio mark rather than the library play circle, which is a
        // dark glyph and looked like a hole beside the white icons around it.
        final Drawable icon = getDrawable(R.drawable.ic_audio_track_24dp);
        return new SimpleOsdSettingsItem(context.getString(R.string.audio_menu_title), icon,
                position -> listener.onOpenAudioTracks());
    }

    /*
     * Under the speed, with the other rows that carry a number between two
     * arrows. Arrows rather than a list of set amounts: the right number is
     * whatever makes the lips fit, and it is found by moving until they do.
     */
    private OsdSettingsItem createAudioDelayItem(final int delayMs) {
        final IntegerOsdSettingsItem.Listener itemListener =
                (position, newValue) -> listener.onAudioDelayChange(newValue);
        return new AudioDelayOsdSettingsItem(context, delayMs, itemListener, this);
    }

    private OsdSettingsItem createSubtitleSettingsItem() {
        @SuppressLint("PrivateResource")
        final Drawable icon = getDrawable(androidx.media3.ui.R.drawable.exo_styled_controls_subtitle_on);
        return new SimpleOsdSettingsItem(context.getString(R.string.osd_subtitle_title), icon,
                position -> listener.onOpenSubtitleSettings());
    }

    private OsdSettingsItem createAllSettingsItem() {
        @SuppressLint("PrivateResource")
        final Drawable icon = getDrawable(androidx.media3.ui.R.drawable.exo_styled_controls_settings);
        return new SimpleOsdSettingsItem(context.getString(R.string.osd_player_all_settings), icon,
                position -> listener.onOpenAllSettings());
    }

    private Drawable getDrawable(@DrawableRes final int id) {
        return ResourcesCompat.getDrawable(context.getResources(), id, context.getTheme());
    }

    public interface Listener {

        void onSpeedChange(float speed);

        void onEngineChange(String engine);

        void onOverlayChange(boolean enabled);

        void onSkipChange(boolean enabled);

        void onAdaptiveBufferingChange(boolean enabled);

        void onSleepChange(int minutes);

        void onShowInfoCard();

        /** Look the film up again, because what it found was wrong. */
        void onSearchAgain();

        /** Put the address of what is playing on the clipboard. */
        void onCopyLink();

        void onOpenVideoTracks();

        void onOpenAudioTracks();

        /** Move the sound against the picture, in milliseconds. */
        void onAudioDelayChange(int delayMs);

        void onOpenSubtitleSettings();

        void onOpenAllSettings();

    }
}
