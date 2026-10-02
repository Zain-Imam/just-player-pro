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

import com.brouken.player.osd.audio.AudioOsdSettingsAdapter;
import com.brouken.player.osd.player.PlayerOsdSettingsAdapter;
import com.brouken.player.osd.subtitle.SubtitleEdgeType;
import com.brouken.player.osd.subtitle.SubtitleOsdSettingsAdapter;
import com.brouken.player.osd.subtitle.SubtitleTypeface;

public class OsdSettingsController {

    private final PlayerActivity playerActivity;
    private final Prefs prefs;

    private final SubtitleOsdSettingsAdapter subtitleAdapter;
    private final PopupWindow osdSettingsWindow;

    private final AudioOsdSettingsAdapter audioAdapter;
    private final PopupWindow audioSettingsWindow;

    private final PlayerOsdSettingsAdapter playerAdapter;
    private final PopupWindow playerSettingsWindow;

    @SuppressLint("InflateParams")
    public OsdSettingsController(PlayerActivity playerActivity) {
        this.playerActivity = playerActivity;
        this.prefs = playerActivity.mPrefs;

        Context context = playerActivity.playerView.getContext();

        subtitleAdapter = new SubtitleOsdSettingsAdapter(context, createSubtitleSettingsListener());

        subtitleAdapter.setPositionMin(prefs.subtitlePositionMin());
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
        playerAdapter.setInitialValues(prefs.speedForUri(prefs.mediaUri), prefs.playbackEngine, true, true,
                prefs.adaptiveBuffering, 0, false, 0,
                prefs.getAudioDelayForUri(prefs.mediaUri),
                prefs.getSubtitleDelayForUri(prefs.mediaUri));

        audioAdapter = new AudioOsdSettingsAdapter(context,
                delayMs -> playerActivity.updateAudioDelay(delayMs));
        audioAdapter.setInitialValues(prefs.getAudioDelayForUri(prefs.mediaUri));

        View audioPanelView = LayoutInflater.from(context).inflate(R.layout.osd_settings, null);
        RecyclerView audioList = audioPanelView.findViewById(android.R.id.list);
        audioList.setAdapter(audioAdapter);
        audioList.setLayoutManager(new LinearLayoutManager(context));
        audioSettingsWindow =
                new PopupWindow(audioPanelView, com.brouken.player.Panels.width(context), FrameLayout.LayoutParams.MATCH_PARENT, true);

        View playerPanelView = LayoutInflater.from(context).inflate(R.layout.osd_settings, null);
        RecyclerView playerList = playerPanelView.findViewById(android.R.id.list);
        playerList.setAdapter(playerAdapter);
        playerList.setLayoutManager(new LinearLayoutManager(context));
        playerSettingsWindow =
                new PopupWindow(playerPanelView, com.brouken.player.Panels.width(context), FrameLayout.LayoutParams.MATCH_PARENT, true);

        osdSettingsWindow.setAnimationStyle(R.style.PanelAnimation);
        audioSettingsWindow.setAnimationStyle(R.style.PanelAnimation);
        playerSettingsWindow.setAnimationStyle(R.style.PanelAnimation);

        // lets the info card come back once the panel closes
        osdSettingsWindow.setOnDismissListener(playerActivity::hideOverlayCardForNow);
        audioSettingsWindow.setOnDismissListener(playerActivity::hideOverlayCardForNow);
        playerSettingsWindow.setOnDismissListener(playerActivity::hideOverlayCardForNow);

        if (Util.SDK_INT < 23) {
            // Work around issue where tapping outside of the menu area or pressing the back button
            // doesn't dismiss the menu as expected. See: https://github.com/google/ExoPlayer/issues/8272.
            osdSettingsWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            audioSettingsWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            playerSettingsWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    public void showPlayerSettings() {
        playerActivity.hideOverlayCardForNow();
        playerAdapter.setInitialValues(
                prefs.speedForUri(prefs.mediaUri),
                prefs.playbackEngine,
                preferences().getBoolean("overlayOnPause", false),
                preferences().getBoolean("skipSegments", true),
                prefs.adaptiveBuffering,
                playerActivity.sleepMinutesLeft(),
                playerActivity.sleepAtEndOfFile(),
                playerActivity.videoTrackCount(),
                prefs.getAudioDelayForUri(prefs.mediaUri),
                prefs.getSubtitleDelayForUri(prefs.mediaUri)
        );
        playerAdapter.notifyDataSetChanged();

        TextView titleTextView = playerSettingsWindow.getContentView().findViewById(android.R.id.text1);
        titleTextView.setText(R.string.osd_player_title);
        playerSettingsWindow.showAtLocation(playerActivity.playerView, Gravity.END | Gravity.TOP, 0, 0);
        focusFirstRow(playerSettingsWindow);

        playerActivity.playerView.postDelayed(playerActivity.playerView::hideController, 100);
    }

    // re-read each time: the delay is kept per file
    public void showAudioSettings() {
        playerActivity.hideOverlayCardForNow();
        audioAdapter.setInitialValues(prefs.getAudioDelayForUri(prefs.mediaUri));
        audioAdapter.notifyDataSetChanged();

        TextView titleTextView = audioSettingsWindow.getContentView().findViewById(android.R.id.text1);
        titleTextView.setText(R.string.osd_audio_title);
        audioSettingsWindow.showAtLocation(playerActivity.playerView, Gravity.END | Gravity.TOP, 0, 0);
        focusFirstRow(audioSettingsWindow);

        playerActivity.playerView.postDelayed(playerActivity.playerView::hideController, 100);
    }

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
                // the engine is chosen when the player is built
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
                // buffering is set on the load control at build time
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
            public void onSearchAgain() {
                playerSettingsWindow.dismiss();
                playerActivity.identifyAgain();
            }

            @Override
            public void onCopyLink() {
                playerSettingsWindow.dismiss();
                playerActivity.copyCurrentLink();
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
            public void onAudioDelayChange(int delayMs) {
                playerActivity.updateAudioDelay(delayMs);
            }

            @Override
            public void onSubtitleDelayChange(int delayMs) {
                playerActivity.updateSubtitleDelay(delayMs);
                subtitleAdapter.setSubtitleDelay(delayMs);
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

    // call on every open and engine change: the values depend on the engine
    public void refreshSubtitleValues() {
        subtitleAdapter.setPositionMin(prefs.subtitlePositionMin());
        subtitleAdapter.setInitialValues(
                prefs.subtitleVerticalPosition,
                prefs.getSubtitleDelayForUri(prefs.mediaUri),
                prefs.subtitleSize,
                prefs.subtitleEdgeType,
                prefs.subtitleTypeface,
                prefs.subtitleStyleEmbedded
        );
        subtitleAdapter.notifyDataSetChanged();
    }

    public void showSubtitleSettings() {
        // the card would show through the panel
        playerActivity.hideOverlayCardForNow();
        refreshSubtitleValues();
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
            // restyle directly; waiting for the prefs callback fails on TVs
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
