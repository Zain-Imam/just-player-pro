package com.brouken.player;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// its own thread: before Android 14 PixelCopy waits for the render thread
final class GlassCapture {

    // small is plenty for a heavy blur
    private static final int WIDTH = 160;

    interface Done {
        // refused when the picture is protected, so no copy of this film will work
        void onFrame(@Nullable Bitmap bitmap, @NonNull RectF onScreen, boolean refused);
    }

    private final HandlerThread thread = new HandlerThread("jpp-glass", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;
    private boolean released;
    // worker thread only
    @Nullable
    private int[] last;

    GlassCapture() {
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    // main thread; picture: the film's area in the surface (mpv draws bars), or null
    void request(@Nullable final View surface, @Nullable final Rect picture, @NonNull final Done done) {
        if (busy || released || surface == null || surface.getWidth() <= 0 || surface.getHeight() <= 0
                || surface.getVisibility() != View.VISIBLE) {
            return;
        }
        final Rect area = new Rect(0, 0, surface.getWidth(), surface.getHeight());
        if (picture != null && !area.intersect(picture)) {
            return;
        }
        final int[] at = new int[2];
        surface.getLocationOnScreen(at);
        final float sx = surface.getScaleX();
        final float sy = surface.getScaleY();
        final RectF onScreen = new RectF(at[0] + area.left * sx, at[1] + area.top * sy,
                at[0] + area.right * sx, at[1] + area.bottom * sy);
        // the whole surface: copying only a part needs Android 8
        final float scale = WIDTH / (float) area.width();
        final int wholeWidth = Math.max(1, Math.round(surface.getWidth() * scale));
        final int wholeHeight = Math.max(1, Math.round(surface.getHeight() * scale));

        if (surface instanceof TextureView) {
            final Bitmap whole = ((TextureView) surface).getBitmap(wholeWidth, wholeHeight);
            if (whole == null) {
                return;
            }
            busy = true;
            worker.post(() -> finish(whole, area, scale, onScreen, false, done));
            return;
        }
        if (!(surface instanceof SurfaceView)) {
            return;
        }
        final Surface target = ((SurfaceView) surface).getHolder().getSurface();
        if (target == null || !target.isValid()) {
            return;
        }
        busy = true;
        worker.post(() -> {
            final Bitmap whole = Bitmap.createBitmap(wholeWidth, wholeHeight, Bitmap.Config.ARGB_8888);
            try {
                PixelCopy.request(target, whole, result -> finish(
                        result == PixelCopy.SUCCESS ? whole : null, area, scale, onScreen,
                        result == PixelCopy.ERROR_SOURCE_INVALID, done), worker);
            } catch (IllegalArgumentException e) {
                // the surface went between the check and the copy
                finish(null, area, scale, onScreen, false, done);
            }
        });
    }

    // on the worker
    private void finish(@Nullable final Bitmap whole, final Rect area, final float scale,
                        final RectF onScreen, final boolean refused, final Done done) {
        final Bitmap blurred = whole == null ? null : blend(Frost.blurredLive(crop(whole, area, scale)));
        main.post(() -> {
            busy = false;
            if (!released) {
                done.onFrame(blurred, onScreen, refused);
            }
        });
    }

    private static Bitmap crop(final Bitmap whole, final Rect area, final float scale) {
        final int w = whole.getWidth();
        final int h = whole.getHeight();
        final int left = Math.max(0, Math.min(w - 1, Math.round(area.left * scale)));
        final int top = Math.max(0, Math.min(h - 1, Math.round(area.top * scale)));
        final int right = Math.max(left + 1, Math.min(w, Math.round(area.right * scale)));
        final int bottom = Math.max(top + 1, Math.min(h, Math.round(area.bottom * scale)));
        if (left == 0 && top == 0 && right == w && bottom == h) {
            return whole;
        }
        return Bitmap.createBitmap(whole, left, top, right - left, bottom - top);
    }

    // half over the last copy, so a cut or a strobe never flashes through the glass
    private Bitmap blend(final Bitmap fresh) {
        final int w = fresh.getWidth();
        final int h = fresh.getHeight();
        final int[] pixels = new int[w * h];
        fresh.getPixels(pixels, 0, w, 0, 0, w, h);
        if (last != null && last.length == pixels.length) {
            for (int i = 0; i < pixels.length; i++) {
                final int a = pixels[i];
                final int b = last[i];
                pixels[i] = 0xFF000000
                        | ((((a >> 16) & 0xFF) + ((b >> 16) & 0xFF)) >> 1) << 16
                        | ((((a >> 8) & 0xFF) + ((b >> 8) & 0xFF)) >> 1) << 8
                        | (((a & 0xFF) + (b & 0xFF)) >> 1);
            }
            fresh.setPixels(pixels, 0, w, 0, 0, w, h);
        }
        last = pixels;
        return fresh;
    }

    // a new film starts from its own first frame
    void forget() {
        worker.post(() -> last = null);
    }

    void release() {
        released = true;
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }
}
