package com.brouken.player.online;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.Nullable;

import com.brouken.player.R;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SkipController {

    private static final long POLL_MS = 500;

    public interface Host {
        double positionSeconds();

        double durationSeconds();

        void seekToSeconds(double seconds);

        @Nullable
        java.util.List<SkipSegments.ChapterMark> chapters();
    }

    private final Activity activity;
    private final ViewGroup parent;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Nullable
    private Button button;
    @Nullable
    private List<SkipSegments.Segment> segments;
    @Nullable
    private SkipSegments.Segment showing;
    @Nullable
    private SkipSegments.Segment skipped;

    /** Where the film was before the last skip, while undo is still offered. */
    private double undoAt = -1;
    private static final double UNDO_WINDOW_SECONDS = 8;
    private double undoUntil = -1;

    private boolean running;
    private int restingBottomMargin;

    public SkipController(final Activity activity, final ViewGroup parent, final Host host) {
        this.activity = activity;
        this.parent = parent;
        this.host = host;
    }

    public void load(final Identity identity) {
        stop();
        segments = null;
        showing = null;
        skipped = null;
        hideButton();

        final String imdb = identity.isSeries ? identity.parentImdbId : identity.imdbId;
        if (imdb == null) {
            return;
        }
        final double duration = host.durationSeconds();

        final List<SkipSegments.Segment> fromChapters =
                SkipSegments.fromChapters(host.chapters(), duration);
        if (!fromChapters.isEmpty()) {
            segments = fromChapters;
            start();
            return;
        }

        worker.execute(() -> {
            final List<SkipSegments.Segment> found =
                    SkipSegments.lookup(imdb, identity.season, identity.episode, duration);
            main.post(() -> {
                segments = found;
                android.util.Log.d("JustPlayer", "Skip segments: " + describe(found));
                if (!found.isEmpty()) {
                    start();
                }
            });
        });
    }

    private static String describe(final List<SkipSegments.Segment> segments) {
        if (segments.isEmpty()) {
            return "none";
        }
        final StringBuilder sb = new StringBuilder();
        for (final SkipSegments.Segment s : segments) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.kind).append(" ").append((long) s.start).append("-").append((long) s.end)
                    .append("s x").append(s.sources);
        }
        return sb.toString();
    }

    public void start() {
        if (running || segments == null || segments.isEmpty()) {
            return;
        }
        running = true;
        main.post(tick);
    }

    public void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            update();
            main.postDelayed(this, POLL_MS);
        }
    };

    private void update() {
        if (segments == null) {
            return;
        }
        final double position = host.positionSeconds();
        if (position < 0) {
            return;
        }

        SkipSegments.Segment inside = null;
        for (final SkipSegments.Segment segment : segments) {
            if (segment == skipped) {
                continue;
            }
            if (segment.contains(position)) {
                inside = segment;
                break;
            }
        }

        // The undo offer lasts a few seconds of playback and then goes.
        if (undoAt >= 0 && position > undoUntil) {
            undoAt = -1;
            hideButton();
        }

        if (inside == null) {
            if (showing != null) {
                showing = null;
                if (undoAt < 0) {
                    hideButton();
                }
            }
            return;
        }

        if (inside == showing) {
            return;
        }
        showing = inside;
        showButton(inside);
    }

    public interface KeepOut {
        @Nullable
        View view();
    }

    @Nullable
    private KeepOut keepOut;

    public void avoid(@Nullable final KeepOut keepOut) {
        this.keepOut = keepOut;
    }

    public void reposition() {
        if (button != null && button.getVisibility() == View.VISIBLE) {
            button.post(this::clearOfCard);
        }
    }

    private void clearOfCard() {
        if (button == null || !(button.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        final ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) button.getLayoutParams();

        final View avoid = keepOut == null ? null : keepOut.view();
        int margin = restingBottomMargin;
        if (avoid != null && avoid.getVisibility() == View.VISIBLE
                && avoid.getHeight() > 0 && button.getHeight() > 0 && parent.getHeight() > 0) {
            final int[] cardAt = new int[2];
            final int[] parentAt = new int[2];
            avoid.getLocationInWindow(cardAt);
            parent.getLocationInWindow(parentAt);

            final int cardBottom = cardAt[1] - parentAt[1] + avoid.getHeight();
            final int gap = Math.round(24 * activity.getResources().getDisplayMetrics().density);
            final int minimum = Math.round(16 * activity.getResources().getDisplayMetrics().density);
            final int needed = parent.getHeight() - cardBottom - gap - button.getHeight();
            margin = Math.max(minimum, Math.min(margin, needed));
        }

        if (params.bottomMargin != margin) {
            params.bottomMargin = margin;
            button.setLayoutParams(params);
        }
    }

    private void showButton(final SkipSegments.Segment segment) {
        if (button == null) {
            button = (Button) activity.getLayoutInflater()
                    .inflate(R.layout.online_skip_button, parent, false);
            if (button.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                restingBottomMargin = ((ViewGroup.MarginLayoutParams)
                        button.getLayoutParams()).bottomMargin;
            }
            parent.addView(button);
            button.setOnClickListener(v -> {
                if (undoAt >= 0) {
                    // The same button undoes the skip it just made, for a few
                    // seconds afterwards -- a marker that was wrong, or cut a
                    // scene short, otherwise leaves nowhere to go but the
                    // scrubber.
                    final double back = undoAt;
                    undoAt = -1;
                    skipped = null;
                    host.seekToSeconds(back);
                    hideButton();
                    return;
                }
                final SkipSegments.Segment current = showing;
                if (current != null) {
                    skipped = current;
                    undoAt = host.positionSeconds();
                    host.seekToSeconds(current.end + 0.5);
                    showing = null;
                    showUndo();
                }
            });
        }

        button.setText(labelFor(segment.kind));
        button.setVisibility(View.VISIBLE);
        // A remote reaches nothing it has not been pointed at, and this button
        // comes and goes on its own while the controls are usually hidden -- so
        // it takes focus for as long as it is up, and gives it back when it
        // goes. A touchscreen is in touch mode and quietly refuses the request,
        // which is the right answer there and needs no test for it.
        button.post(button::requestFocus);
        // After layout: the button has no height until it has been measured,
        // and the card it is dodging may be mid-appearance.
        button.post(this::clearOfCard);
    }

    private void showUndo() {
        if (button == null) {
            return;
        }
        undoUntil = host.positionSeconds() + UNDO_WINDOW_SECONDS;
        button.setText(R.string.skip_undo);
        button.setVisibility(View.VISIBLE);
        button.post(button::requestFocus);
        button.post(this::clearOfCard);
    }

    private void hideButton() {
        if (button != null) {
            button.setVisibility(View.GONE);
        }
    }

    private int labelFor(final SkipSegments.Kind kind) {
        switch (kind) {
            case RECAP:
                return R.string.online_skip_recap;
            case CREDITS:
                return R.string.online_skip_credits;
            case INTRO:
            default:
                return R.string.online_skip_intro;
        }
    }

    public void release() {
        stop();
        if (button != null) {
            parent.removeView(button);
            button = null;
        }
    }
}
