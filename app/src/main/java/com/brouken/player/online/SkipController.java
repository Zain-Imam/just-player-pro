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

        /**
         * Whether the film is actually running.
         *
         * The button is an offer to skip forward, which only makes sense while
         * something is moving. On a paused film it is one more thing sitting
         * over the picture you paused to look at.
         */
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

    /*
     * The file's own markers first, the internet second.
     *
     * A file that names its own chapters — "Intro", "Opening", "Credits" — has
     * told you exactly where they are, at the right timings for the cut you
     * actually have. A community database has guessed, for some other release,
     * and has to be matched by identifying the film at all. When the file
     * knows, the file wins.
     *
     * Which means chapters must not depend on identification. They used to:
     * this returned immediately without an IMDb id, so a file full of perfectly
     * good chapter marks offered nothing unless it had also been looked up
     * online — and anything unidentifiable never offered skipping at all.
     * Identity is optional now, and only the online half needs it.
     */
    public void load(@Nullable final Identity identity) {
        stop();
        segments = null;
        showing = null;
        skipped = null;
        hideButton();

        final double duration = host.durationSeconds();

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
        if (imdb == null) {
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

    /*
     * Where the button ought to be, worked out afresh every half second.
     *
     * This used to be written as a set of transitions — remember what was
     * showing, act only when it changes — and it got two things wrong.
     *
     * A segment that had been skipped was struck off the list for good, so
     * seeking back into the intro offered nothing: the one moment you are most
     * likely to want the button is right after you have gone back to see what
     * you skipped. There is no need for that exclusion at all, because after a
     * skip the position is past the end of the segment anyway; all it has to do
     * is stay quiet while the undo offer is still on screen.
     *
     * And because the decision was edge-triggered, anything that hid the button
     * for another reason — a pause, a rebuild, the card — left "showing" still
     * pointing at the segment, so nothing ever put it back.
     *
     * It is now a plain question asked repeatedly: should the button be up, and
     * is it? Every path in and out of a segment, in either direction, produces
     * the right answer without needing to be enumerated.
     */
    private void update() {
        if (segments == null) {
            return;
        }
        final double position = host.positionSeconds();
        if (position < 0) {
            return;
        }

        // The undo offer lasts a few seconds of playback and then goes.
        if (undoAt >= 0 && position > undoUntil) {
            undoAt = -1;
            hideButton();
        }
        if (undoAt >= 0) {
            // Undo is up; leave it alone.
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

        // Already offering this one, and still on screen: nothing to do.
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
