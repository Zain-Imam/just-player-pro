package com.brouken.player.engine;

import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.VideoSize;

// Picture and subtitle handling that differs between Media3 and mpv.
public interface EngineUi {

    void applySubtitleStyle();

    // fraction of the screen height
    void setSubtitleLift(float fractionOfHeight);

    // forcedAspect 0 keeps the video's own ratio
    void applyShape(int resizeMode, float forcedAspect, @Nullable VideoSize known);

    void onEnterPip(@Nullable Format format);

    void onExitPip();

    /** Picture bounds in surface view coordinates; false when not known yet. */
    boolean pictureRect(@NonNull Rect out);

    // true when the engine zooms instead of the view being scaled
    boolean zoomsInEngine();

    float zoom();

    void setZoom(float scale);

    float zoomFit();

    void release();
}
