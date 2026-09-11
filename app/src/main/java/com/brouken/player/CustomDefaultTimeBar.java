package com.brouken.player;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.media3.ui.DefaultTimeBar;

import java.lang.reflect.Field;

// Only answers to touches on the painted line: the stock bar accepts a press
// anywhere in its 48dp target, which is the height of the whole bottom bar
class CustomDefaultTimeBar extends DefaultTimeBar {

    private static final int TAP_SLOP_DP = 6;
    private static final int TOUCH_ZONE_DP = 10;

    Rect scrubberBar;
    private Rect seekBounds;
    private Rect progressBar;

    private boolean scrubbing;
    private boolean holdingBuffered;
    private long heldBufferedPosition;

    private boolean onBar;
    private float startX;
    private long startTime;

    public CustomDefaultTimeBar(Context context) {
        this(context, null);
    }

    public CustomDefaultTimeBar(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomDefaultTimeBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, attrs);
    }

    public CustomDefaultTimeBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr, @Nullable AttributeSet timebarAttrs) {
        this(context, attrs, defStyleAttr, timebarAttrs, 0);
    }

    public CustomDefaultTimeBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr, @Nullable AttributeSet timebarAttrs, int defStyleRes) {
        super(context, attrs, defStyleAttr, timebarAttrs, defStyleRes);
        scrubberBar = rect("scrubberBar");
        seekBounds = rect("seekBounds");
        progressBar = rect("progressBar");
    }

    @Nullable
    private Rect rect(final String name) {
        try {
            final Field field = DefaultTimeBar.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Rect) field.get(this);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return super.onTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scrubbing = false;
                startX = event.getX();
                startTime = SystemClock.uptimeMillis();
                onBar = isOnBar(event.getY());
                return true;

            case MotionEvent.ACTION_MOVE:
                if (scrubbing) {
                    return super.onTouchEvent(event);
                }
                if (!onBar) {
                    return true;
                }
                if (Math.abs(event.getX() - startX) <= Utils.dpToPx(TAP_SLOP_DP)) {
                    return true;
                }
                scrubbing = true;
                dispatchToSuper(MotionEvent.ACTION_DOWN, event.getX());
                return super.onTouchEvent(event);

            case MotionEvent.ACTION_UP:
                if (scrubbing) {
                    scrubbing = false;
                    return super.onTouchEvent(event);
                }
                if (onBar && isTap(event)) {
                    dispatchToSuper(MotionEvent.ACTION_DOWN, event.getX());
                    dispatchToSuper(MotionEvent.ACTION_UP, event.getX());
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (scrubbing) {
                    scrubbing = false;
                    return super.onTouchEvent(event);
                }
                return true;

            default:
                return scrubbing ? super.onTouchEvent(event) : true;
        }
    }

    // Media3 drops its buffer on a seek outside what it holds, so the buffered
    // band vanished for the length of a drag; mpv keeps its cache and did not
    void holdBufferedPosition(final long seed) {
        holdingBuffered = true;
        heldBufferedPosition = seed;
    }

    void releaseBufferedPosition() {
        holdingBuffered = false;
        heldBufferedPosition = 0;
    }

    @Override
    public void setBufferedPosition(final long bufferedPosition) {
        if (holdingBuffered) {
            heldBufferedPosition = Math.max(heldBufferedPosition, bufferedPosition);
            super.setBufferedPosition(heldBufferedPosition);
            return;
        }
        super.setBufferedPosition(bufferedPosition);
    }

    private boolean isOnBar(final float y) {
        if (progressBar == null || progressBar.height() <= 0) {
            // Before the first layout there is nothing to measure against; fall
            // back to the stock behaviour rather than ignoring the touch.
            return true;
        }
        final float distance = Math.abs(y - progressBar.centerY());
        return distance <= progressBar.height() / 2f + Utils.dpToPx(TOUCH_ZONE_DP);
    }

    private boolean isTap(final MotionEvent event) {
        return Math.abs(event.getX() - startX) <= Utils.dpToPx(TAP_SLOP_DP)
                && SystemClock.uptimeMillis() - startTime <= ViewConfiguration.getLongPressTimeout();
    }

    private void dispatchToSuper(final int action, final float x) {
        final float y = seekBounds != null && seekBounds.height() > 0
                ? seekBounds.centerY()
                : getHeight() / 2f;
        // Clamped into the bar so a tap right at either end still reaches the
        // stock code, which ignores a press outside its own rectangle.
        final float clampedX = seekBounds != null && seekBounds.width() > 0
                ? Math.max(seekBounds.left, Math.min(seekBounds.right, x))
                : x;
        final long now = SystemClock.uptimeMillis();
        final MotionEvent event = MotionEvent.obtain(now, now, action, clampedX, y, 0);
        super.onTouchEvent(event);
        event.recycle();
    }
}
