package com.brouken.player.render;

import androidx.media3.common.C;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.text.TextRenderer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

// TextRenderer redraws only after the next cue change past the last position it saw, so a longer delay
// left the old line up until the old timing caught up. Clearing the line and that position fixes it.
final class CueRefresh {

    private static Field lastPosition;
    private static Method clearOutput;
    private static boolean unavailable;

    private CueRefresh() {
    }

    static void now(final Renderer renderer) {
        // past its last line the renderer no longer draws, and a disable would then throw
        if (!(renderer instanceof TextRenderer) || renderer.isEnded() || !find()) {
            return;
        }
        try {
            // clearOutput reads the last position, so it goes first
            if (lastPosition.getLong(renderer) != C.TIME_UNSET) {
                clearOutput.invoke(renderer);
            }
            lastPosition.setLong(renderer, C.TIME_UNSET);
        } catch (Exception e) {
            unavailable = true;
        }
    }

    private static boolean find() {
        if (unavailable) {
            return false;
        }
        if (lastPosition == null) {
            try {
                lastPosition = TextRenderer.class.getDeclaredField("lastRendererPositionUs");
                lastPosition.setAccessible(true);
                clearOutput = TextRenderer.class.getDeclaredMethod("clearOutput");
                clearOutput.setAccessible(true);
            } catch (Exception e) {
                // another Media3 version: the delay still works, just as late as before
                unavailable = true;
                return false;
            }
        }
        return true;
    }
}
