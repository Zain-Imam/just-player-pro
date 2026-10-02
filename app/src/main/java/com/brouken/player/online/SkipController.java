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

        boolean isPlaying();

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

    // position before the last skip while undo is offered, otherwise -1
    private double undoAt = -1;

    // wall-clock time, so the undo offer also expires while paused
    private static final long UNDO_WINDOW_MS = 3_000;
    private final Runnable undoExpired = () -> {
        undoAt = -1;
        hideButton();
    };

    private boolean running;
    private int restingBottomMargin;

    public SkipController(final Activity activity, final ViewGroup parent, final Host host) {
        this.activity = activity;
        this.parent = parent;
        this.host = host;
    }

    // the file's own chapter marks win; only the online lookup needs an identity
    public void load(@Nullable final Identity identity) {
        stop();
        segments = null;
        showing = null;
        skipped = null;
        hideButton();
        loadWhenLengthKnown(identity, ++generation, 0);
    }

    // only the latest load's answer is used; an earlier lookup can return late
    private int generation;

    // wait up to 20 s for the duration: segments that run to the end need it
    private static final long LENGTH_WAIT_MS = 500;
    private static final int LENGTH_WAIT_TRIES = 40;

    private void loadWhenLengthKnown(@Nullable final Identity identity, final int mine,
                                     final int tries) {
        if (mine != generation) {
            return;
        }
        final double duration = host.durationSeconds();
        if (duration <= 0 && tries < LENGTH_WAIT_TRIES) {
            main.postDelayed(() -> loadWhenLengthKnown(identity, mine, tries + 1), LENGTH_WAIT_MS);
            return;
        }

        final List<SkipSegments.Segment> fromChapters =
                SkipSegments.fromChapters(host.chapters(), duration);
        if (!fromChapters.isEmpty()) {
            segments = fromChapters;
            start();
            return;
        }

        if (identity == null) {
            return;
        }
        final String imdb = identity.isSeries ? identity.parentImdbId : identity.imdbId;
        if (imdb == null || worker.isShutdown()) {
            return;
        }

        worker.execute(() -> {
            final List<SkipSegments.Segment> found =
                    SkipSegments.lookup(imdb, identity.season, identity.episode, duration);
            main.post(() -> {
                if (mine != generation) {
                    return;
                }
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

    // checked before the player handles a key itself while the controls are hidden
    public boolean buttonHasFocus() {
        return button != null && button.getVisibility() == View.VISIBLE && button.hasFocus();
    }

    public void stop() {
        running = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(undoExpired);
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

    // recomputed every tick, so a button hidden for any other reason comes back
    private void update() {
        if (segments == null) {
            return;
        }
        final double position = host.positionSeconds();
        if (position < 0) {
            return;
        }

        // the undo offer is removed by its own timer
        if (undoAt >= 0) {
            return;
        }

        SkipSegments.Segment inside = null;
        if (host.isPlaying()) {
            for (final SkipSegments.Segment segment : segments) {
                if (segment.contains(position)) {
                    inside = segment;
                    break;
                }
            }
        }

        if (inside == null) {
            showing = null;
            hideButton();
            return;
        }

        if (inside == showing && button != null && button.getVisibility() == View.VISIBLE) {
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
                    // for a few seconds after a skip the same button undoes it
                    final double back = undoAt;
                    undoAt = -1;
                    main.removeCallbacks(undoExpired);
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
        // focus for remotes; ignored in touch mode
        button.post(button::requestFocus);
        // after layout: the button has no height until it is measured
        button.post(this::clearOfCard);
    }

    // called when the controls hide, since they take focus while shown
    public void reclaimFocus() {
        if (button != null && button.getVisibility() == View.VISIBLE && !button.isInTouchMode()) {
            button.post(button::requestFocus);
        }
    }

    private void showUndo() {
        if (button == null) {
            return;
        }
        main.removeCallbacks(undoExpired);
        main.postDelayed(undoExpired, UNDO_WINDOW_MS);
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
        // drop any lookup still in flight
        generation++;
        worker.shutdownNow();
        if (button != null) {
            parent.removeView(button);
            button = null;
        }
    }
}
