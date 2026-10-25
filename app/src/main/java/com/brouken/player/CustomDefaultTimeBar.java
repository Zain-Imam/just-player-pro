package com.brouken.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.ui.DefaultTimeBar;

import java.lang.reflect.Field;

// only touches near the line count; the stock 48dp target is the whole bottom bar
class CustomDefaultTimeBar extends DefaultTimeBar {

    private static final int TAP_SLOP_DP = 6;
    private static final int TOUCH_ZONE_DP = 10;

    Rect scrubberBar;
    private Rect seekBounds;
    private Rect progressBar;
    private Rect bufferedBar;

    private boolean scrubbing;
    private boolean holdingBuffered;
    private long heldBufferedPosition;

    private boolean onBar;
    private float startX;
    private long startTime;

    // the stock bar keeps these private
    private long durationMs;
    private long positionMs;

    // presses closer together than this are one gesture
    private static final long KEY_SESSION_MS = 2_000;
    private long keyTargetMs = -1;
    private long keyLastAt;

    // local files draw fully buffered; the player only reports its read-ahead
    private boolean wholeFileHere;

    boolean isWholeFileHere() {
        return wholeFileHere;
    }

    void setWholeFileHere(final boolean here) {
        wholeFileHere = here;
        if (here && durationMs > 0) {
            super.setBufferedPosition(durationMs);
        }
    }

    @Override
    public void setDuration(final long duration) {
        durationMs = duration;
        super.setDuration(duration);
        if (wholeFileHere && duration > 0) {
            super.setBufferedPosition(duration);
        }
    }

    @Override
    public void setPosition(final long position) {
        positionMs = position;
        super.setPosition(position);
    }

    private boolean keyScrubbing;
    private int keyRepeats;
    private float keyX;

    // remote: 1s steps that grow while the key is held
    private static final long[] KEY_STEP_MS = {1_000, 5_000, 15_000};
    private static final int[] KEY_STEP_AFTER = {10, 30};

    @Override
    public boolean onKeyDown(final int keyCode, final android.view.KeyEvent event) {
        final boolean back = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT;
        final boolean forward = keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;

        if (!back && !forward) {
            // OK on a preview jumps there now rather than after the pause
            if (keyScrubbing && previewing && isConfirmKey(keyCode)) {
                removeCallbacks(commitKeyScrub);
                Utils.log("Seek preview: jump by OK");
                commitKeyScrub.run();
                return true;
            }
            // the activity ignores OK while the controls are up, so play/pause here
            if (!keyScrubbing && isConfirmKey(keyCode)) {
                final androidx.media3.common.Player player = PlayerActivity.player;
                if (player == null) {
                    return false;
                }
                if (player.isPlaying()) {
                    player.pause();
                } else {
                    player.play();
                }
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        if (!isEnabled() || durationMs <= 0 || progressBar == null || progressBar.width() <= 0) {
            return super.onKeyDown(keyCode, event);
        }

        removeCallbacks(commitKeyScrub);
        final long now = SystemClock.uptimeMillis();
        if (!keyScrubbing) {
            keyScrubbing = true;
            keyRepeats = 0;
            // continue from the last target; the player lags behind quick presses
            if (keyTargetMs < 0 || now - keyLastAt > KEY_SESSION_MS) {
                keyTargetMs = positionMs;
            }
            dispatchToSuper(MotionEvent.ACTION_DOWN, xFor(keyTargetMs));
        }
        keyLastAt = now;

        final long step = KEY_STEP_MS[keyRepeats < KEY_STEP_AFTER[0] ? 0
                : keyRepeats < KEY_STEP_AFTER[1] ? 1 : 2];
        keyRepeats++;

        keyTargetMs = Math.max(0, Math.min(durationMs,
                keyTargetMs + (forward ? step : -step)));
        keyX = xFor(keyTargetMs);
        dispatchToSuper(MotionEvent.ACTION_MOVE, keyX);
        return true;
    }

    private float xFor(final long ms) {
        if (progressBar == null || durationMs <= 0) {
            return 0;
        }
        return progressBar.left + (float) ms / durationMs * progressBar.width();
    }

    // knob position for a time, and the bar's top, in this view's coordinates
    float knobX(final long ms) {
        return xFor(Math.max(0, Math.min(durationMs, ms)));
    }

    int barTop() {
        if (progressBar == null) {
            return 0;
        }
        return progressBar.top - (track == null ? 0 : track.reachAbove());
    }

    // a player style's own drawing of the line; Classic keeps the stock one
    interface Track {
        // how far it draws above the line, so the preview clears it
        int reachAbove();

        void draw(@NonNull Canvas canvas, @NonNull Rect bar, int bufferedRight, int playedRight, boolean grown);
    }

    @Nullable
    private Track track;

    void setTrack(@Nullable final Track track) {
        this.track = track;
        invalidate();
    }

    @Override
    public void onDraw(final Canvas canvas) {
        if (track == null || progressBar == null || bufferedBar == null || scrubberBar == null) {
            super.onDraw(canvas);
            return;
        }
        // the stock bar keeps these up to date, scrubbing included
        track.draw(canvas, progressBar, bufferedBar.right, scrubberBar.right,
                scrubbing || keyScrubbing || isFocused());
    }

    // commits after the last press so taps add up; a preview waits for OK
    private static final long KEY_COMMIT_DELAY_MS = 350;
    private boolean previewing;

    void setPreviewing(final boolean previewing) {
        this.previewing = previewing;
    }

    private final Runnable commitKeyScrub = () -> {
        if (keyScrubbing) {
            Utils.log("Seek preview: keys done");
            keyScrubbing = false;
            dispatchToSuper(MotionEvent.ACTION_UP, keyX);
        }
    };

    // Back or a focus move during a preview cancels without seeking
    boolean cancelKeyScrub() {
        if (!keyScrubbing || !previewing) {
            return false;
        }
        removeCallbacks(commitKeyScrub);
        keyScrubbing = false;
        keyTargetMs = -1;
        Utils.log("Seek preview: dropped");
        dispatchToSuper(MotionEvent.ACTION_CANCEL, keyX);
        return true;
    }

    @Override
    public boolean onKeyUp(final int keyCode, final android.view.KeyEvent event) {
        if (keyScrubbing && (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
            removeCallbacks(commitKeyScrub);
            if (!previewing) {
                postDelayed(commitKeyScrub, KEY_COMMIT_DELAY_MS);
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // cancel first: the stock bar treats losing focus as the end of a drag and seeks
    @Override
    protected void onFocusChanged(final boolean gainFocus, final int direction,
                                  @Nullable final Rect previouslyFocusedRect) {
        if (!gainFocus) {
            cancelKeyScrub();
        }
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    }

    private static boolean isConfirmKey(final int keyCode) {
        return keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == android.view.KeyEvent.KEYCODE_ENTER
                || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

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
        bufferedBar = rect("bufferedBar");
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

    // keeps the buffered band during a drag; Media3 drops it on seeks outside it
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
        if (wholeFileHere && durationMs > 0) {
            super.setBufferedPosition(durationMs);
            return;
        }
        if (holdingBuffered) {
            heldBufferedPosition = Math.max(heldBufferedPosition, bufferedPosition);
            super.setBufferedPosition(heldBufferedPosition);
            return;
        }
        super.setBufferedPosition(bufferedPosition);
    }

    private boolean isOnBar(final float y) {
        if (progressBar == null || progressBar.height() <= 0) {
            // not laid out yet: accept the touch like the stock bar
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
        // the stock code ignores presses outside seekBounds
        final float clampedX = seekBounds != null && seekBounds.width() > 0
                ? Math.max(seekBounds.left, Math.min(seekBounds.right, x))
                : x;
        final long now = SystemClock.uptimeMillis();
        final MotionEvent event = MotionEvent.obtain(now, now, action, clampedX, y, 0);
        super.onTouchEvent(event);
        event.recycle();
    }
}
