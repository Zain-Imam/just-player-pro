/*
 * Copyright (c) 2019 Viktor Krez
 *
 * Taken from DoubleTapPlayerView, https://github.com/vkay94/DoubleTapPlayerView,
 * and used under the MIT licence. The full licence text is in
 * licenses/MIT-DoubleTapPlayerView.txt, which is distributed with this software.
 *
 * This file has been modified: translated from the original Kotlin into Java and
 * adapted to this player by the Just Player project.
 */
package com.brouken.player.dtpv;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.view.GestureDetectorCompat;

import com.brouken.player.CustomPlayerView;
import com.brouken.player.R;

public class DoubleTapPlayerView extends CustomPlayerView {

    private final GestureDetectorCompat gestureDetector;
    private final DoubleTapPlayerView.DoubleTapGestureListener gestureListener;

    private PlayerDoubleTapListener controller;

    private final PlayerDoubleTapListener getController() {
        return gestureListener.getControls();
    }

    private final void setController(PlayerDoubleTapListener value) {
        gestureListener.setControls(value);
        controller = value;
    }

    private int controllerRef;

    public DoubleTapPlayerView(Context context) {
        this(context, null);
    }

    public DoubleTapPlayerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DoubleTapPlayerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        controllerRef = -1;

        gestureListener = new DoubleTapGestureListener(this);
        gestureDetector = new GestureDetectorCompat(context, gestureListener);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.DoubleTapPlayerView, 0, 0);
            controllerRef = a != null ? a.getResourceId(R.styleable.DoubleTapPlayerView_dtpv_controller, -1) : -1;
            if (a != null) {
                a.recycle();
            }
        }

        isDoubleTapEnabled = true;
        doubleTapDelay = 700L;
    }

    private boolean isDoubleTapEnabled;

    public final boolean isDoubleTapEnabled() {
        return isDoubleTapEnabled;
    }

    public final void setDoubleTapEnabled(boolean var1) {
        isDoubleTapEnabled = var1;
    }

    // ms after a double tap during which further taps keep seeking
    private long doubleTapDelay;

    public final long getDoubleTapDelay() {
        return gestureListener.getDoubleTapDelay();
    }

    public final void setDoubleTapDelay(long value) {
        gestureListener.setDoubleTapDelay(value);
        doubleTapDelay = value;
    }

    public final DoubleTapPlayerView controller(PlayerDoubleTapListener controller) {
        setController(controller);
        return this;
    }

    public final boolean isInDoubleTapMode() {
        return gestureListener.isDoubleTapping();
    }

    public final void keepInDoubleTapMode() {
        gestureListener.keepInDoubleTapMode();
    }

    public final void cancelInDoubleTapMode() {
        gestureListener.cancelInDoubleTapMode();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (isDoubleTapEnabled) {
            boolean consumed = gestureDetector.onTouchEvent(ev);

            // keeps the controller from flickering during a double tap
            if (!consumed)
                return super.onTouchEvent(ev);

            return true;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (controllerRef != -1) {
            try {
                View view = ((View)getParent()).findViewById(this.controllerRef);
                if (view instanceof PlayerDoubleTapListener) {
                    controller((PlayerDoubleTapListener)view);
                }
            } catch (Exception e) {
                e.printStackTrace();
                Log.e("DoubleTapPlayerView","controllerRef is either invalid or not PlayerDoubleTapListener: ${e.message}");
            }
        }
    }

    private static final class DoubleTapGestureListener extends GestureDetector.SimpleOnGestureListener {
        private final Handler mHandler;
        private final Runnable mRunnable;

        private PlayerDoubleTapListener controls;
        private boolean isDoubleTapping;
        private long doubleTapDelay;

        public final boolean isDoubleTapping() {
            return isDoubleTapping;
        }

        public final void setDoubleTapping(boolean var1) {
            isDoubleTapping = var1;
        }

        public final long getDoubleTapDelay() {
            return doubleTapDelay;
        }

        public final void setDoubleTapDelay(long var1) {
            doubleTapDelay = var1;
        }

        private final CustomPlayerView rootView;

        public final PlayerDoubleTapListener getControls() {
            return controls;
        }

        public final void setControls(PlayerDoubleTapListener var1) {
            controls = var1;
        }

        private static final String TAG = ".DTGListener";
        private static boolean DEBUG = false;

        public final void keepInDoubleTapMode() {
            isDoubleTapping = true;
            mHandler.removeCallbacks(mRunnable);
            mHandler.postDelayed(mRunnable, doubleTapDelay);
        }

        public final void cancelInDoubleTapMode() {
            mHandler.removeCallbacks(mRunnable);
            isDoubleTapping = false;
            if (controls != null)
                controls.onDoubleTapFinished();
        }

        @Override
        public boolean onDown(MotionEvent e) {
            if (isDoubleTapping) {
                if (controls != null)
                    controls.onDoubleTapProgressDown(e.getX(), e.getY());
                return true;
            }
            return super.onDown(e);
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (isDoubleTapping) {
                if (DEBUG)
                    Log.d(TAG, "onSingleTapUp: isDoubleTapping = true");
                if (controls != null)
                    controls.onDoubleTapProgressUp(e.getX(), e.getY());
                return true;
            }
            return super.onSingleTapUp(e);
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            // also fires after a third tap; true keeps the controller from toggling
            if (isDoubleTapping)
                return true;
            if (DEBUG)
                Log.d(TAG, "onSingleTapConfirmed: isDoubleTap = false");
            return rootView.tap();
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            // First tap (ACTION_DOWN) of both taps
            if (DEBUG)
                Log.d(TAG, "onDoubleTap");
            if (!isDoubleTapping) {
                isDoubleTapping = true;
                keepInDoubleTapMode();
                if (controls != null)
                    controls.onDoubleTapStarted(e.getX(), e.getY());
            }
            return true;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            // Second tap (ACTION_UP) of both taps
            if (e.getActionMasked() == MotionEvent.ACTION_UP && isDoubleTapping) {
                if (DEBUG)
                    Log.d(TAG,"onDoubleTapEvent, ACTION_UP");
                if (controls != null)
                    controls.onDoubleTapProgressUp(e.getX(), e.getY());
                return true;
            }
            return super.onDoubleTapEvent(e);
        }

        public DoubleTapGestureListener(CustomPlayerView rootView) {
            super();
            this.rootView = rootView;
            mHandler = new Handler();
            mRunnable = new Runnable() {
                @Override
                public void run() {
                    if (DEBUG)
                        Log.d(TAG, "Runnable called");
                    setDoubleTapping(false);
                    DoubleTapGestureListener.this.setDoubleTapping(false);
                    if (getControls() != null)
                        getControls().onDoubleTapFinished();
                }
            };
            doubleTapDelay = 650L;
        }
    }
}
