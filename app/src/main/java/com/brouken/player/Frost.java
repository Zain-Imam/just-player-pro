package com.brouken.player;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

// a SurfaceView can't be blurred, so surfaces draw a small copied frame instead
final class Frost {

    static final int BAND_TOP = 0;
    static final int BAND_BOTTOM = 1;
    static final int PANEL = 2;
    static final int ROUND = 3;
    static final int CARD = 4;
    // the strip under the status bar: part of the top band, so no line of its own
    static final int BAND_FILL = 5;

    static final long COPY_EVERY_MS = 200;

    private static final int TINT_RGB = 0x101216;
    // with nothing to frost: 74% dark, a panel 84%, as AOSP advises without blur
    private static final int PLAIN = 0xBD0E0F12;
    private static final int PLAIN_PANEL = 0xD60E0F12;

    // the blur for the live copy: a few hundred pixels across is plenty
    private static final int BLUR_RADIUS = 3;
    private static final int BLUR_PASSES = 2;

    interface Host {
        // a frosted surface was drawn, so copies are wanted again
        void wake();
    }

    @Nullable
    private Bitmap art;
    private final RectF artOnScreen = new RectF();
    private boolean live;
    private float tintAlpha = 0.42f;
    private long lastDrawn;
    @Nullable
    private Host host;

    private final List<WeakReference<FrostDrawable>> drawables = new ArrayList<>();

    void setHost(@Nullable final Host host) {
        this.host = host;
    }

    boolean isLive() {
        return live;
    }

    boolean hasArt() {
        return art != null;
    }

    long lastDrawn() {
        return lastDrawn;
    }

    // whether the picture may be copied for this film
    void setLive(final boolean live) {
        this.live = live;
        if (!live) {
            art = null;
        }
        invalidateAll();
    }

    void clear() {
        art = null;
        invalidateAll();
    }

    void setArt(@NonNull final Bitmap blurred, @NonNull final RectF onScreen) {
        art = blurred;
        artOnScreen.set(onScreen);
        // a bright picture needs more of the tint for white text to read
        tintAlpha = 0.42f + Math.max(0f, Math.min(0.16f, (brightness(blurred) - 0.55f) * 0.5f));
        invalidateAll();
    }

    private void drawn() {
        lastDrawn = SystemClock.uptimeMillis();
        if (live && host != null) {
            host.wake();
        }
    }

    FrostDrawable drawable(final int kind) {
        final FrostDrawable drawable = new FrostDrawable(this, kind);
        drawables.add(new WeakReference<>(drawable));
        return drawable;
    }

    private void invalidateAll() {
        for (int i = drawables.size() - 1; i >= 0; i--) {
            final FrostDrawable drawable = drawables.get(i).get();
            if (drawable == null) {
                drawables.remove(i);
            } else {
                drawable.invalidateSelf();
            }
        }
    }

    // a copied frame: richer colours as glass gives them, then a soft blur
    @NonNull
    static Bitmap blurredLive(@NonNull final Bitmap frame) {
        return soften(frame, frame.getWidth(), 1.5f, 1f, BLUR_RADIUS, BLUR_PASSES);
    }

    private static Bitmap soften(final Bitmap picture, final int width, final float saturation,
                                 final float brightness, final int radius, final int passes) {
        final int height = Math.max(1, Math.round(width * picture.getHeight() / (float) picture.getWidth()));
        final Bitmap small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final ColorMatrix colours = new ColorMatrix();
        colours.setSaturation(saturation);
        final ColorMatrix dimmer = new ColorMatrix();
        dimmer.setScale(brightness, brightness, brightness, 1f);
        colours.postConcat(dimmer);
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(colours));
        new Canvas(small).drawBitmap(picture, null, new Rect(0, 0, width, height), paint);

        final int[] pixels = new int[width * height];
        final int[] spare = new int[pixels.length];
        small.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int pass = 0; pass < passes; pass++) {
            blurRows(pixels, spare, width, height, radius);
            blurColumns(spare, pixels, width, height, radius);
        }
        small.setPixels(pixels, 0, width, 0, 0, width, height);
        return small;
    }

    private static void blurRows(final int[] in, final int[] out, final int w, final int h, final int r) {
        final int span = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            final int row = y * w;
            int red = 0, green = 0, blue = 0;
            for (int i = -r; i <= r; i++) {
                final int c = in[row + Math.max(0, Math.min(w - 1, i))];
                red += (c >> 16) & 0xFF;
                green += (c >> 8) & 0xFF;
                blue += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = 0xFF000000 | (red / span) << 16 | (green / span) << 8 | blue / span;
                final int in1 = in[row + Math.min(w - 1, x + r + 1)];
                final int out1 = in[row + Math.max(0, x - r)];
                red += ((in1 >> 16) & 0xFF) - ((out1 >> 16) & 0xFF);
                green += ((in1 >> 8) & 0xFF) - ((out1 >> 8) & 0xFF);
                blue += (in1 & 0xFF) - (out1 & 0xFF);
            }
        }
    }

    private static void blurColumns(final int[] in, final int[] out, final int w, final int h, final int r) {
        final int span = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int red = 0, green = 0, blue = 0;
            for (int i = -r; i <= r; i++) {
                final int c = in[Math.max(0, Math.min(h - 1, i)) * w + x];
                red += (c >> 16) & 0xFF;
                green += (c >> 8) & 0xFF;
                blue += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = 0xFF000000 | (red / span) << 16 | (green / span) << 8 | blue / span;
                final int in1 = in[Math.min(h - 1, y + r + 1) * w + x];
                final int out1 = in[Math.max(0, y - r) * w + x];
                red += ((in1 >> 16) & 0xFF) - ((out1 >> 16) & 0xFF);
                green += ((in1 >> 8) & 0xFF) - ((out1 >> 8) & 0xFF);
                blue += (in1 & 0xFF) - (out1 & 0xFF);
            }
        }
    }

    // 0 for black, 1 for white; the brightest quarter counts, as text may sit there
    private static float brightness(final Bitmap bitmap) {
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        final int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        final float[] levels = new float[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            final int c = pixels[i];
            levels[i] = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
        }
        java.util.Arrays.sort(levels);
        float sum = 0f;
        final int from = levels.length * 3 / 4;
        for (int i = from; i < levels.length; i++) {
            sum += levels[i];
        }
        return levels.length == from ? 0f : sum / (levels.length - from);
    }

    static final class FrostDrawable extends Drawable {

        private final Frost frost;
        private final int kind;

        private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final Path edge = new Path();
        private final RectF box = new RectF();
        private final RectF behind = new RectF();
        private final RectF artBounds = new RectF();
        private final Matrix matrix = new Matrix();
        private final int[] where = new int[2];
        @Nullable
        private Bitmap shaderArt;
        @Nullable
        private android.content.res.ColorStateList tint;
        private int alpha = 255;
        private float corner;

        private FrostDrawable(final Frost frost, final int kind) {
            this.frost = frost;
            this.kind = kind;
            edgePaint.setStyle(Paint.Style.STROKE);
        }

        // the view this draws for, through any state list or layer holding it
        @Nullable
        private View host() {
            Object callback = getCallback();
            while (callback instanceof Drawable) {
                callback = ((Drawable) callback).getCallback();
            }
            return callback instanceof View ? (View) callback : null;
        }

        @Override
        public void draw(@NonNull final Canvas canvas) {
            if (getBounds().isEmpty()) {
                return;
            }
            final Bitmap art = frost.live ? frost.art : null;
            final View host = art == null ? null : host();
            int fill;
            if (art != null && host != null) {
                host.getLocationOnScreen(where);
                behind.set(frost.artOnScreen);
                behind.offset(-where[0], -where[1]);
                artBounds.set(0, 0, art.getWidth(), art.getHeight());
                matrix.setRectToRect(artBounds, behind, Matrix.ScaleToFit.FILL);
                // CLAMP carries the edges over the black bars so a surface never goes half black
                if (shaderArt != art) {
                    shaderArt = art;
                    artPaint.setShader(new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                }
                artPaint.getShader().setLocalMatrix(matrix);
                artPaint.setAlpha(alpha);
                canvas.drawPath(shape, artPaint);
                // panels and the card are mostly words, so their glass is thicker
                final float tint = frost.tintAlpha + (kind == PANEL || kind == CARD ? 0.14f : 0f);
                fill = ((int) (tint * 255) << 24) | TINT_RGB;
            } else if (frost.live) {
                // the first copy is still on its way: dark enough not to show the sharp picture
                fill = 0x99000000 | TINT_RGB;
            } else {
                fill = kind == PANEL ? PLAIN_PANEL : PLAIN;
            }
            if (tint != null) {
                // a warning colour, as the volume boost turns its message red
                fill = (0xC0 << 24) | (tint.getDefaultColor() & 0xFFFFFF);
            }
            fillPaint.setColor(faded(fill));
            canvas.drawPath(shape, fillPaint);
            drawEdge(canvas);
            frost.drawn();
        }

        private void drawEdge(final Canvas canvas) {
            final Rect b = getBounds();
            final float stroke = Math.max(1f, Utils.dpToPx(1));
            edgePaint.setStrokeWidth(stroke);
            edgePaint.setColor(faded(kind == ROUND || kind == CARD ? 0x2EFFFFFF : 0x1FFFFFFF));
            switch (kind) {
                case BAND_TOP:
                    canvas.drawLine(b.left, b.bottom - stroke / 2f, b.right, b.bottom - stroke / 2f, edgePaint);
                    break;
                case BAND_BOTTOM:
                    canvas.drawLine(b.left, b.top + stroke / 2f, b.right, b.top + stroke / 2f, edgePaint);
                    break;
                case BAND_FILL:
                    break;
                case PANEL:
                    // only the side facing the film; the others are the screen's edges
                    canvas.save();
                    if (rtl()) {
                        canvas.clipRect(b.right - corner - stroke, b.top, b.right, b.bottom);
                    } else {
                        canvas.clipRect(b.left, b.top, b.left + corner + stroke, b.bottom);
                    }
                    canvas.drawPath(edge, edgePaint);
                    canvas.restore();
                    break;
                default:
                    canvas.drawPath(edge, edgePaint);
                    break;
            }
        }

        private boolean rtl() {
            return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        }

        private int faded(final int colour) {
            return Color.argb(Color.alpha(colour) * alpha / 255,
                    Color.red(colour), Color.green(colour), Color.blue(colour));
        }

        @Override
        protected void onBoundsChange(@NonNull final Rect bounds) {
            super.onBoundsChange(bounds);
            box.set(bounds);
            switch (kind) {
                case PANEL:
                    corner = Utils.dpToPx(22);
                    break;
                case ROUND:
                    corner = Math.min(box.width(), box.height()) / 2f;
                    break;
                case CARD:
                    corner = Utils.dpToPx(14);
                    break;
                default:
                    corner = 0;
                    break;
            }
            shape.reset();
            shape.addRoundRect(box, radii(corner), Path.Direction.CW);
            final float stroke = Math.max(1f, Utils.dpToPx(1));
            final RectF inner = new RectF(box);
            inner.inset(stroke / 2f, stroke / 2f);
            edge.reset();
            edge.addRoundRect(inner, radii(Math.max(0f, corner - stroke / 2f)), Path.Direction.CW);
        }

        private float[] radii(final float r) {
            if (kind != PANEL) {
                return new float[]{r, r, r, r, r, r, r, r};
            }
            // a side panel sits flush with the screen's edge
            return rtl()
                    ? new float[]{0, 0, r, r, r, r, 0, 0}
                    : new float[]{r, r, 0, 0, 0, 0, r, r};
        }

        // a dialog clips its window to this, so it must never be empty
        @Override
        public void getOutline(@NonNull final Outline outline) {
            if (kind == ROUND || kind == CARD) {
                outline.setRoundRect(getBounds(), corner);
            } else {
                outline.setRect(getBounds());
            }
        }

        @Override
        public void setAlpha(final int alpha) {
            if (this.alpha != alpha) {
                this.alpha = alpha;
                invalidateSelf();
            }
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void setColorFilter(@Nullable final ColorFilter colorFilter) {
            // a tint is the one colour change, see setTintList
        }

        @Override
        public void setTintList(@Nullable final android.content.res.ColorStateList tint) {
            this.tint = tint;
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
