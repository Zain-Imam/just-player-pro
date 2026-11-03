package com.brouken.player.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// A folder's picture on Home, in the look chosen in Settings. The mosaic fits how many videos there
// are (one to four), so no picture repeats; the folder look shows how long its videos last instead.
public final class FolderPicture extends ViewGroup {

    public static final String CENTRED = "centred";
    public static final String SQUARE = "square";
    public static final String MOSAIC = "mosaic";
    public static final String FOLDER = "folder";

    private final ImageView[] images = new ImageView[4];
    private final Rect[] places = {new Rect(), new Rect(), new Rect(), new Rect()};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float density;
    private String look = CENTRED;
    private int shown = 1;
    private int accent;
    @Nullable
    private String chip;

    public FolderPicture(final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < images.length; i++) {
            final ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(image);
            images[i] = image;
        }
        chipText.setColor(0xFFFFFFFF);
        chipText.setTextSize(11 * context.getResources().getDisplayMetrics().scaledDensity);
        chipText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        setWillNotDraw(false);
    }

    public ImageView[] images() {
        return images;
    }

    public void setChip(@Nullable final String text) {
        chip = text;
        invalidate();
    }

    // how many of the images to fill: only a mosaic shows more than one
    public int show(final String look, final int videos, final int accent) {
        this.look = look;
        this.accent = accent;
        shown = MOSAIC.equals(look) ? Math.max(1, Math.min(4, videos)) : FOLDER.equals(look) ? 0 : 1;
        for (int i = 0; i < images.length; i++) {
            images[i].setVisibility(i < shown ? VISIBLE : GONE);
        }
        requestLayout();
        invalidate();
        return shown;
    }

    private int dp(final float value) {
        return Math.round(value * density);
    }

    private void place(final int w, final int h) {
        final int gap = dp(1);
        final int midX = w / 2;
        final int midY = h / 2;
        switch (shown) {
            case 2:
                places[0].set(0, 0, midX - gap / 2, h);
                places[1].set(midX + gap / 2, 0, w, h);
                break;
            case 3:
                // on a tall card the big one goes on top
                final int big = h * 2 / 3;
                places[0].set(0, 0, w, big - gap / 2);
                places[1].set(0, big + gap / 2, midX - gap / 2, h);
                places[2].set(midX + gap / 2, big + gap / 2, w, h);
                break;
            case 4:
                places[0].set(0, 0, midX - gap / 2, midY - gap / 2);
                places[1].set(midX + gap / 2, 0, w, midY - gap / 2);
                places[2].set(0, midY + gap / 2, midX - gap / 2, h);
                places[3].set(midX + gap / 2, midY + gap / 2, w, h);
                break;
            default:
                places[0].set(0, 0, w, h);
                break;
        }
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        final int h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
        place(w, h);
        for (int i = 0; i < shown; i++) {
            images[i].measure(MeasureSpec.makeMeasureSpec(places[i].width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(places[i].height(), MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(final boolean changed, final int l, final int t, final int r, final int b) {
        place(r - l, b - t);
        for (int i = 0; i < shown; i++) {
            final Rect p = places[i];
            images[i].layout(p.left, p.top, p.right, p.bottom);
        }
    }

    @Override
    protected void dispatchDraw(@NonNull final Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (FOLDER.equals(look)) {
            // a back with its tab, and a front a little lower, as a folder icon is drawn
            final float wide = Math.min(w * 0.8f, h * 0.9f * 1.45f);
            final float tall = wide / 1.45f;
            final float left = (w - wide) / 2f;
            final float top = (h - tall) / 2f;
            final float tab = tall * 0.16f;
            final float round = Math.max(dp(3), tall * 0.08f);
            paint.setColor(accent);
            paint.setAlpha(0x99);
            box.set(left, top, left + wide * 0.42f, top + tab + round);
            canvas.drawRoundRect(box, round, round, paint);
            box.set(left, top + tab, left + wide, top + tall);
            canvas.drawRoundRect(box, round, round, paint);
            paint.setAlpha(0xFF);
            box.set(left, top + tab * 1.9f, left + wide, top + tall);
            canvas.drawRoundRect(box, round, round, paint);
        }
        super.dispatchDraw(canvas);
        if (chip != null && !chip.isEmpty() && h > dp(40)) {
            final Paint.FontMetrics metrics = chipText.getFontMetrics();
            final float pad = dp(5);
            final float margin = dp(6);
            final float right = w - margin;
            final float bottom = h - margin;
            box.set(right - chipText.measureText(chip) - pad * 2, bottom - (metrics.descent - metrics.ascent) - dp(4),
                    right, bottom);
            paint.setColor(0xA6000000);
            canvas.drawRoundRect(box, dp(4), dp(4), paint);
            canvas.drawText(chip, box.left + pad, box.bottom - dp(2) - metrics.descent, chipText);
        }
    }
}
