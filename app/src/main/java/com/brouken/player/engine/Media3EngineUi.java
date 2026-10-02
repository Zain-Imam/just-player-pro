package com.brouken.player.engine;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.SurfaceView;
import android.view.View;
import android.view.accessibility.CaptioningManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import com.brouken.player.CustomPlayerView;
import com.brouken.player.Prefs;
import com.brouken.player.SubtitleUtils;
import com.brouken.player.subtitle.CueModifier;

public final class Media3EngineUi implements EngineUi {

    private final Context context;
    private final CustomPlayerView playerView;
    private final Prefs prefs;
    private final Player player;

    private float subtitleLift;
    private float zoomBeforePip = 1f;

    public Media3EngineUi(@NonNull Context context, @NonNull CustomPlayerView playerView,
                          @NonNull Prefs prefs, @NonNull Player player) {
        this.context = context;
        this.playerView = playerView;
        this.prefs = prefs;
        this.player = player;
        // the cue modifier outlives the player
        playerView.cueModifier.setLift(0f);
    }

    @Override
    public void applySubtitleStyle() {
        final SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView == null) {
            return;
        }
        final CaptioningManager captioningManager =
                (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);
        final CaptioningManager.CaptionStyle userStyle = captioningManager.getUserStyle();
        final CaptionStyleCompat userStyleCompat = CaptionStyleCompat.createFromCaptionStyle(userStyle);
        final int edgeColor = userStyle.hasEdgeColor() ? userStyleCompat.edgeColor : Color.BLACK;
        final Typeface customTypeface = SubtitleUtils.loadCustomSubtitleTypeface(context, prefs);
        final Typeface typeface = SubtitleUtils.getSubtitleTypeface(prefs.subtitleTypeface,
                userStyleCompat, customTypeface);
        final CaptionStyleCompat captionStyle = new CaptionStyleCompat(
                userStyle.hasForegroundColor() ? userStyleCompat.foregroundColor : Color.WHITE,
                userStyle.hasBackgroundColor() ? userStyleCompat.backgroundColor : Color.TRANSPARENT,
                userStyle.hasWindowColor() ? userStyleCompat.windowColor : Color.TRANSPARENT,
                SubtitleUtils.getSubtitleEdgeType(prefs.subtitleEdgeType, userStyle),
                edgeColor,
                typeface);

        subtitleView.setStyle(captionStyle);
        subtitleView.setApplyEmbeddedStyles(prefs.subtitleStyleEmbedded);
        applyBottomPadding(subtitleView);
        SubtitleUtils.updateFractionalTextSize(subtitleView, captioningManager, prefs);

        final CueModifier cueModifier = playerView.cueModifier;
        cueModifier.setSubtitleTypeface(prefs.subtitleTypeface, typeface);
        cueModifier.setSubtitleEdgeType(prefs.subtitleEdgeType);
        cueModifier.setShadowColor(edgeColor);
        cueModifier.setVerticalPosition(prefs.subtitleVerticalPosition);
        refreshCues(subtitleView);
    }

    private void applyBottomPadding(final SubtitleView subtitleView) {
        subtitleView.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
                + prefs.subtitleVerticalPosition * 0.01f + subtitleLift);
    }

    private void refreshCues(final SubtitleView subtitleView) {
        subtitleView.post(() -> {
            playerView.cueModifier.setPictureArea(0f, 1f);
            if (player.isCommandAvailable(Player.COMMAND_GET_TEXT)) {
                subtitleView.setCues(playerView.cueModifier.modifyCues(player.getCurrentCues().cues));
            }
        });
    }

    // cues with an explicit line ignore the padding, so lift them in the modifier too
    @Override
    public void setSubtitleLift(final float fractionOfHeight) {
        subtitleLift = Math.max(0f, fractionOfHeight);
        playerView.cueModifier.setLift(subtitleLift);
        final SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView != null) {
            applyBottomPadding(subtitleView);
            refreshCues(subtitleView);
        }
    }

    @Override
    public void applyShape(final int resizeMode, final float forcedAspect,
                           @Nullable final VideoSize known) {
        final AspectRatioFrameLayout frame =
                playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        if (forcedAspect > 0) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            if (frame != null) {
                frame.setAspectRatio(forcedAspect);
            }
            return;
        }
        // a forced ratio stays on the frame until reset
        if (frame != null) {
            frame.setAspectRatio(known == null || known.height == 0 ? 0
                    : known.width * known.pixelWidthHeightRatio / known.height);
        }
        playerView.setResizeMode(resizeMode);
    }

    // fixed buffer size avoids a flicker entering PiP; released in onExitPip
    @Override
    public void onEnterPip(@Nullable final Format format) {
        zoomBeforePip = zoom();
        playerView.setScale(1f);
        final SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView != null) {
            subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 2);
        }
        final View surface = playerView.getVideoSurfaceView();
        if (format != null && format.width > 0 && format.height > 0 && surface instanceof SurfaceView) {
            ((SurfaceView) surface).getHolder().setFixedSize(format.width, format.height);
        }
    }

    @Override
    public void onExitPip() {
        final View surface = playerView.getVideoSurfaceView();
        if (surface instanceof SurfaceView) {
            ((SurfaceView) surface).getHolder().setSizeFromLayout();
        }
        final SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView != null) {
            final CaptioningManager captioningManager =
                    (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);
            SubtitleUtils.updateFractionalTextSize(subtitleView, captioningManager, prefs);
        }
        if (playerView.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            playerView.setScale(zoomBeforePip);
        }
    }

    @Override
    public boolean pictureRect(@NonNull final Rect out) {
        final View surface = playerView.getVideoSurfaceView();
        if (surface == null || surface.getWidth() <= 0 || surface.getHeight() <= 0) {
            return false;
        }
        out.set(0, 0, surface.getWidth(), surface.getHeight());
        return true;
    }

    @Override
    public boolean zoomsInEngine() {
        return false;
    }

    @Override
    public float zoom() {
        final View surface = playerView.getVideoSurfaceView();
        return surface == null ? 1f : surface.getScaleX();
    }

    @Override
    public void setZoom(final float scale) {
        playerView.setScale(scale);
    }

    @Override
    public float zoomFit() {
        return playerView.getScaleFit();
    }

    @Override
    public void release() {
    }
}
