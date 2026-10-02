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
package com.brouken.player.dtpv.youtube.views;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.util.Consumer;

import com.brouken.player.R;

public final class SecondsView extends ConstraintLayout {

    private long cycleDuration;
    private int seconds;
    private boolean isForward;
    private int icon;
    private boolean animate;

    private final ValueAnimator firstAnimator;
    private final ValueAnimator secondAnimator;
    private final ValueAnimator thirdAnimator;
    private final ValueAnimator fourthAnimator;
    private final ValueAnimator fifthAnimator;

    public SecondsView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        cycleDuration = 750L;
        seconds = 0;
        isForward = true;
        icon = R.drawable.ic_play_triangle;
        animate = false;

        LayoutInflater.from(context).inflate(R.layout.yt_seconds_view, this, true);

        firstAnimator = new CustomValueAnimator(new Runnable() {
            @Override
            public void run() {
                findViewById(R.id.icon_1).setAlpha(0f);
                findViewById(R.id.icon_2).setAlpha(0f);
                findViewById(R.id.icon_3).setAlpha(0f);
            }
        }, new Consumer<Float>() {
            @Override
            public void accept(Float aFloat) {
                findViewById(R.id.icon_1).setAlpha(aFloat);
            }
        }, new Runnable() {
            @Override
            public void run() {
                if (animate)
                    secondAnimator.start();
            }
        });

        secondAnimator = new CustomValueAnimator(new Runnable() {
            @Override
            public void run() {
                findViewById(R.id.icon_1).setAlpha(1f);
                findViewById(R.id.icon_2).setAlpha(0f);
                findViewById(R.id.icon_3).setAlpha(0f);
            }
        }, new Consumer<Float>() {
            @Override
            public void accept(Float aFloat) {
                findViewById(R.id.icon_2).setAlpha(aFloat);
            }
        }, new Runnable() {
            @Override
            public void run() {
                if (animate)
                    thirdAnimator.start();
            }
        });

        thirdAnimator = new CustomValueAnimator(new Runnable() {
            @Override
            public void run() {
                findViewById(R.id.icon_1).setAlpha(1f);
                findViewById(R.id.icon_2).setAlpha(1f);
                findViewById(R.id.icon_3).setAlpha(0f);
            }
        }, new Consumer<Float>() {
            @Override
            public void accept(Float aFloat) {
                findViewById(R.id.icon_1).setAlpha(1f - findViewById(R.id.icon_3).getAlpha());
                findViewById(R.id.icon_3).setAlpha(aFloat);
            }
        }, new Runnable() {
            @Override
            public void run() {
                if (animate)
                    fourthAnimator.start();
            }
        });

        fourthAnimator = new CustomValueAnimator(new Runnable() {
            @Override
            public void run() {
                findViewById(R.id.icon_1).setAlpha(0f);
                findViewById(R.id.icon_2).setAlpha(1f);
                findViewById(R.id.icon_3).setAlpha(1f);
            }
        }, new Consumer<Float>() {
            @Override
            public void accept(Float aFloat) {
                findViewById(R.id.icon_2).setAlpha(1f - aFloat);
            }
        }, new Runnable() {
            @Override
            public void run() {
                if (animate)
                    fifthAnimator.start();
            }
        });

        fifthAnimator = new CustomValueAnimator(new Runnable() {
            @Override
            public void run() {
                findViewById(R.id.icon_1).setAlpha(0f);
                findViewById(R.id.icon_2).setAlpha(0f);
                findViewById(R.id.icon_3).setAlpha(1f);
            }
        }, new Consumer<Float>() {
            @Override
            public void accept(Float aFloat) {
                findViewById(R.id.icon_3).setAlpha(1f - aFloat);
            }
        }, new Runnable() {
            @Override
            public void run() {
                if (animate)
                    firstAnimator.start();
            }
        });
    }

    public final long getCycleDuration() {
        return cycleDuration;
    }

    public final void setCycleDuration(long value) {
        firstAnimator.setDuration(value / (long)5);
        secondAnimator.setDuration(value / (long)5);
        thirdAnimator.setDuration(value / (long)5);
        fourthAnimator.setDuration(value / (long)5);
        fifthAnimator.setDuration(value / (long)5);
        cycleDuration = value;
    }

    public final int getSeconds() {
        return seconds;
    }

    public final void setSeconds(int value) {
        TextView textView = findViewById(R.id.tv_seconds);
        textView.setText(getContext().getResources().getQuantityString(
                R.plurals.quick_seek_x_second, value, value
        ));
        seconds = value;
    }

    public final boolean isForward() {
        return isForward;
    }

    public final void setForward(boolean value) {
        LinearLayout linearLayout = findViewById(R.id.triangle_container);
        linearLayout.setRotation(value ? 0f : 180f);
        isForward = value;
    }

    public final TextView getTextView() {
        return (TextView)findViewById(R.id.tv_seconds);
    }

    public final int getIcon() {
        return icon;
    }

    public final void setIcon(int value) {
        if (value > 0) {
            ((ImageView)findViewById(R.id.icon_1)).setImageResource(value);
            ((ImageView)findViewById(R.id.icon_2)).setImageResource(value);
            ((ImageView)findViewById(R.id.icon_3)).setImageResource(value);
        }
        icon = value;
    }

    public final void start() {
        stop();
        animate = true;
        firstAnimator.start();
    }

    public final void stop() {
        animate = false;
        firstAnimator.cancel();
        secondAnimator.cancel();
        thirdAnimator.cancel();
        fourthAnimator.cancel();
        fifthAnimator.cancel();
        reset();
    }

    private final void reset() {
        findViewById(R.id.icon_1).setAlpha(0f);
        findViewById(R.id.icon_2).setAlpha(0f);
        findViewById(R.id.icon_3).setAlpha(0f);
    }


    private final class CustomValueAnimator extends ValueAnimator {
        public CustomValueAnimator(Runnable start, Consumer<Float> update, Runnable end) {
            setDuration(getCycleDuration() / (long)5);
            setFloatValues(0f, 1f);

            addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    update.accept((Float)animation.getAnimatedValue());
                }
            });

            addListener(new AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    start.run();
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    end.run();
                }

                @Override
                public void onAnimationCancel(Animator animation) {

                }

                @Override
                public void onAnimationRepeat(Animator animation) {

                }
            });
        }
    }
}
