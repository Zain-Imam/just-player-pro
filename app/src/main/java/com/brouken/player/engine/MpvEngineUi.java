package com.brouken.player.engine;

import android.graphics.Rect;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.AspectRatioFrameLayout;

import com.brouken.player.CustomPlayerView;
import com.brouken.player.Prefs;
import com.brouken.player.mpv.MpvPlayer;

// mpv's surface covers the whole player; mpv letterboxes and draws subtitles itself
public final class MpvEngineUi implements EngineUi {

    private final CustomPlayerView playerView;
    private final Prefs prefs;
    private final MpvPlayer mpv;
    private double zoomBeforePip = 1.0;

    public MpvEngineUi(@NonNull CustomPlayerView playerView, @NonNull Prefs prefs,
                       @NonNull MpvPlayer mpv) {
        this.playerView = playerView;
        this.prefs = prefs;
        this.mpv = mpv;
    }

    @Override
    public void applySubtitleStyle() {
        mpv.setSubtitleStyle(
                prefs.subtitleVerticalPosition,
                prefs.subtitleSize,
                prefs.subtitleEdgeType == null ? null : prefs.subtitleEdgeType.name(),
                prefs.subtitleTypeface == null ? null : prefs.subtitleTypeface.name(),
                prefs.subtitleStyleEmbedded);
    }

    @Override
    public void setSubtitleLift(final float fractionOfHeight) {
        mpv.setSubtitleLift(fractionOfHeight);
    }

    // the frame always fills the player; mpv applies the shape
    @Override
    public void applyShape(final int resizeMode, final float forcedAspect,
                           @Nullable final VideoSize known) {
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        final AspectRatioFrameLayout frame =
                playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        if (frame != null) {
            frame.setAspectRatio(0);
        }
        if (forcedAspect > 0) {
            mpv.setAspect(true, 0, forcedAspect);
        } else {
            mpv.setAspect(resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FILL,
                    resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM ? 1.0 : 0.0, 0);
        }
    }

    // no fixed buffer size for PiP: on this full-screen surface it stays stretched
    @Override
    public void onEnterPip(@Nullable final Format format) {
        zoomBeforePip = mpv.getVideoZoom();
        if (zoomBeforePip != 1.0) {
            mpv.setVideoZoom(1.0);
        }
    }

    @Override
    public void onExitPip() {
        final View surface = playerView.getVideoSurfaceView();
        if (surface instanceof SurfaceView) {
            // in case Media3 fixed it earlier in this screen
            ((SurfaceView) surface).getHolder().setSizeFromLayout();
        }
        if (zoomBeforePip != 1.0) {
            mpv.setVideoZoom(zoomBeforePip);
        }
        // after the window is back to full size
        playerView.post(() -> {
            mpv.refreshPicture();
            applySubtitleStyle();
        });
    }

    @Override
    public boolean pictureRect(@NonNull final Rect out) {
        final View surface = playerView.getVideoSurfaceView();
        final int[] margins = mpv.pictureMargins();
        if (surface == null || margins == null || surface.getWidth() <= 0) {
            return false;
        }
        // mpv pixels to view pixels; differ only if the buffer size was fixed
        final float sx = surface.getWidth() / (float) margins[0];
        final float sy = surface.getHeight() / (float) margins[1];
        out.set(Math.round(margins[2] * sx), Math.round(margins[3] * sy),
                Math.round((margins[0] - margins[4]) * sx),
                Math.round((margins[1] - margins[5]) * sy));
        return out.width() > 0 && out.height() > 0;
    }

    @Override
    public boolean zoomsInEngine() {
        return true;
    }

    @Override
    public float zoom() {
        return (float) mpv.getVideoZoom();
    }

    @Override
    public void setZoom(final float scale) {
        mpv.setVideoZoom(scale);
    }

    @Override
    public float zoomFit() {
        return 1f;
    }

    @Override
    public void release() {
        mpv.setPictureListener(null);
    }
}
