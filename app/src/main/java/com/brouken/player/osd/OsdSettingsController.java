package com.brouken.player.osd;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.media3.common.util.Util;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.brouken.player.PlayerActivity;
import com.brouken.player.Prefs;
import com.brouken.player.R;
import androidx.preference.PreferenceManager;
import android.content.SharedPreferences;

import com.brouken.player.osd.player.PlayerOsdSettingsAdapter;
import com.brouken.player.osd.subtitle.SubtitleEdgeType;
import com.brouken.player.osd.subtitle.SubtitleOsdSettingsAdapter;
import com.brouken.player.osd.subtitle.SubtitleTypeface;

public class OsdSettingsController {

    private final PlayerActivity playerActivity;
    private final Prefs prefs;

    private final SubtitleOsdSettingsAdapter subtitleAdapter;
    private final PopupWindow osdSettingsWindow;

    private final PlayerOsdSettingsAdapter playerAdapter;
    private final PopupWindow playerSettingsWindow;

    @SuppressLint("InflateParams")
    public OsdSettingsController(PlayerActivity playerActivity) {
        this.playerActivity = playerActivity;
        this.prefs = playerActivity.mPrefs;

        Context context = playerActivity.playerView.getContext();

        subtitleAdapter = new SubtitleOsdSettingsAdapter(context, createSubtitleSettingsListener());

        subtitleAdapter.setInitialValues(
                prefs.subtitleVerticalPosition,
                prefs.getSubtitleDelayForUri(prefs.mediaUri),
                prefs.subtitleSize,
                prefs.subtitleEdgeType,
                prefs.subtitleTypeface,
                prefs.subtitleStyleEmbedded
        );

        View settingsView = LayoutInflater.from(context).inflate(R.layout.osd_settings, null);
        RecyclerView recyclerView = settingsView.findViewById(android.R.id.list);
        recyclerView.setAdapter(subtitleAdapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        osdSettingsWindow =
                new PopupWindow(settingsView, com.brouken.player.Panels.width(context), FrameLayout.LayoutParams.MATCH_PARENT, true);
        playerAdapter = new PlayerOsdSettingsAdapter(context, createPlayerSettingsListener());
        playerAdapter.setInitialValues(prefs.speed, prefs.playbackEngine, true, true,
                prefs.adaptiveBuffering, 0, false, 0);

        View playerPanelView = LayoutInflater.from(context).inflate(R.layout.osd_settings, null);
        RecyclerView playerList = playerPanelView.findViewById(android.R.id.list);
        playerList.setAdapter(playerAdapter);
        playerList.setLayoutManager(new LinearLayoutManager(context));
        playerSettingsWindow =
                new PopupWindow(playerPanelView, com.brouken.player.Panels.width(context), FrameLayout.LayoutParams.MATCH_PARENT, true);

        // The same edge, the same width, the same slide in as the track lists:
        // opening one after the other should not move the panel about.
        osdSettingsWindow.setAnimationStyle(R.style.PanelAnimation);
        playerSettingsWindow.setAnimationStyle(R.style.PanelAnimation);

        if (Util.SDK_INT < 23) {
            // Work around issue where tapping outside of the menu area or pressing the back button
            // doesn't dismiss the menu as expected. See: https://github.com/google/ExoPlayer/issues/8272.
            osdSettingsWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            playerSettingsWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    public void showPlayerSettings() {
        // Same for the quick panel: one thing on screen at a time.
        playerActivity.hideOverlayCard();
        playerAdapter.setInitialValues(
                prefs.speed,
                prefs.playbackEngine,
                preferences().getBoolean("overlayOnPause", false),
                preferences().getBoolean("skipSegments", true),
                prefs.adaptiveBuffering,
                playerActivity.sleepMinutesLeft(),
                playerActivity.sleepAtEndOfFile(),
                playerActivity.videoTrackCount()
        );
        playerAdapter.notifyDataSetChanged();

        TextView titleTextView = playerSettingsWindow.getContentView().findViewById(android.R.id.text1);
        titleTextView.setText(R.string.osd_player_title);
        playerSettingsWindow.showAtLocation(playerActivity.playerView, Gravity.END | Gravity.TOP, 0, 0);
        focusFirstRow(playerSettingsWindow);

        // Same reason as the subtitle panel: without the delay the controller
        // comes straight back when the panel is opened from a remote.
        playerActivity.playerView.postDelayed(playerActivity.playerView::hideController, 100);
    }

    /**
     * Both panels, and the track pickers, need the focus to land on a row that
     * does not exist yet. Panels holds the one copy of how, and why asking once
     * is not enough.
     */
    private void focusFirstRow(final PopupWindow window) {
        com.brouken.player.Panels.focusFirstRow(
                window.getContentView().findViewById(android.R.id.list));
    }

    private SharedPreferences preferences() {
        return PreferenceManager.getDefaultSharedPreferences(playerActivity);
    }

    private PlayerOsdSettingsAdapter.Listener createPlayerSettingsListener() {
        return new PlayerOsdSettingsAdapter.Listener() {
            @Override
            public void onSpeedChange(float speed) {
                playerActivity.setSpeed(speed);
            }

            @Override
            public void onEngineChange(String engine) {
                preferences().edit().putString("playbackEngine", engine).apply();
                prefs.loadUserPreferences();
                playerSettingsWindow.dismiss();
                // The engine is chosen when the player is built, so the file has
                // to be reopened — at the position it is already at.
                playerActivity.rebuildPlayer();
            }

            @Override
            public void onOverlayChange(boolean enabled) {
                preferences().edit().putBoolean("overlayOnPause", enabled).apply();
                if (!enabled) {
                    playerActivity.hideOverlayCard();
                }
            }

            @Override
            public void onSkipChange(boolean enabled) {
                preferences().edit().putBoolean("skipSegments", enabled).apply();
                playerActivity.updateSkipEnabled(enabled);
            }

            @Override
            public void onAdaptiveBufferingChange(boolean enabled) {
                preferences().edit().putBoolean("adaptiveBuffering", enabled).apply();
                prefs.loadUserPreferences();
                // Buffering is configured on the load control the player is
                // built with, so this one also needs the player rebuilt.
                playerSettingsWindow.dismiss();
                playerActivity.rebuildPlayer();
            }

            @Override
            public void onSleepChange(int minutes) {
                playerActivity.setSleepTimer(minutes);
                playerSettingsWindow.dismiss();
            }

            @Override
            public void onShowInfoCard() {
                playerSettingsWindow.dismiss();
                playerActivity.showOverlayCardNow();
            }

            @Override
            public void onOpenVideoTracks() {
                playerSettingsWindow.dismiss();
                playerActivity.showVideoMenu();
            }

            @Override
            public void onOpenAudioTracks() {
                playerSettingsWindow.dismiss();
                playerActivity.showAudioMenu();
            }

            @Override
            public void onOpenSubtitleSettings() {
                playerSettingsWindow.dismiss();
                showSubtitleSettings();
            }

            @Override
            public void onOpenAllSettings() {
                playerSettingsWindow.dismiss();
                playerActivity.openSettingsScreen();
            }
        };
    }

    public void showSubtitleSettings() {
        // Nothing may sit under a panel: the card would show through it.
        playerActivity.hideOverlayCard();
        TextView titleTextView = osdSettingsWindow.getContentView().findViewById(android.R.id.text1);
        titleTextView.setText(R.string.osd_subtitle_title);
        osdSettingsWindow.showAtLocation(playerActivity.playerView, Gravity.END | Gravity.TOP, 0, 0);
        focusFirstRow(osdSettingsWindow);

        // Without delaying hide, controller's UI reappears when
        // using physical button on a remote to open settings
        playerActivity.playerView.postDelayed(playerActivity.playerView::hideController, 100);
    }

    public void updateSubtitlePosition() {
        subtitleAdapter.setSubtitlePosition(prefs.subtitleVerticalPosition);
    }

    private SubtitleOsdSettingsAdapter.Listener createSubtitleSettingsListener() {
        return new SubtitleOsdSettingsAdapter.Listener() {
            /*
             * Applied here, not left to the preference listener.
             *
             * Writing the value and waiting for the shared-preferences callback
             * to notice worked on a phone and did nothing on a television: the
             * arrows moved the number and the subtitles stayed where they were.
             * Asking the player to restyle itself is immediate and does not
             * depend on a callback arriving.
             */
            @Override
            public void onSubtitlePositionChange(int position) {
                prefs.updateSubtitleVerticalPosition(position);
                playerActivity.updateSubtitleStyle(playerActivity);
            }

            @Override
            public void onSubtitleDelayChange(int delay) {
                playerActivity.updateSubtitleDelay(delay);
            }

            @Override
            public void onSubtitleSizeChange(int size) {
                prefs.updateSubtitleSize(size);
                playerActivity.updateSubtitleStyle(playerActivity);
            }

            @Override
            public void onSubtitleEdgeTypeChange(SubtitleEdgeType edgeType) {
                prefs.updateSubtitleEdgeType(edgeType);
                playerActivity.updateSubtitleStyle(playerActivity);
            }

            @Override
            public void onSubtitleTypefaceChange(SubtitleTypeface typeface) {
                prefs.updateSubtitleTypeface(typeface);
                playerActivity.updateSubtitleStyle(playerActivity);
            }

            @Override
            public void onSubtitleEmbeddedStylesChange(boolean embeddedStyles) {
                prefs.updateSubtitleStyleEmbedded(embeddedStyles);
            }

            @Override
            public void onSearchOnlineSubtitles() {
                osdSettingsWindow.dismiss();
                playerActivity.searchOnlineSubtitles();
            }

            @Override
            public void onChangeTitle() {
                osdSettingsWindow.dismiss();
                playerActivity.reIdentifyOnline();
            }

            @Override
            public void onOpenCaptionPreferences() {
                osdSettingsWindow.dismiss();
                playerActivity.enableRotation();
                Intent intent = new Intent(Settings.ACTION_CAPTIONING_SETTINGS);
                playerActivity.safelyStartActivityForResult(intent, PlayerActivity.REQUEST_SYSTEM_CAPTIONS);
            }
        };
    }

}
