package com.brouken.player.mpv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.BasePlayer;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import com.brouken.player.BuildConfig;

import com.google.common.collect.ImmutableList;

import dev.jdtech.mpv.MPVLib;

import java.util.ArrayList;
import java.util.List;

@UnstableApi
public final class MpvPlayer extends BasePlayer
        implements MPVLib.EventObserver, MPVLib.LogObserver {

    private static final String TAG = "MpvPlayer";

    private static final String PROP_TIME_POS = "time-pos";
    private static final String PROP_DURATION = "duration";
    private static final String PROP_PAUSE = "pause";
    private static final String PROP_EOF = "eof-reached";
    private static final String PROP_CACHE_BUFFERING = "paused-for-cache";
    private static final String PROP_WIDTH = "width";
    private static final String PROP_HEIGHT = "height";
    private static final String PROP_TRACK_LIST_COUNT = "track-list/count";
    private static final String PROP_DEMUXER_CACHE_TIME = "demuxer-cache-time";
    private static final double SUB_POS_DEFAULT = 92;

    private static final String PROP_VID = "vid";
    private static final String PROP_AID = "aid";
    private static final String PROP_SID = "sid";

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ListenerSet<Player.Listener> listeners;

    @Nullable
    private MPVLib mpv;
    private BroadcastReceiver becomingNoisyReceiver;
    private boolean loadWhenSurfaceReady;
    @Nullable
    private Surface surface;
    @Nullable
    private SurfaceHolder surfaceHolder;

    private final List<MediaItem> mediaItems = new ArrayList<>();

    private int playbackState = Player.STATE_IDLE;
    private boolean playWhenReady = true;
    private boolean isBuffering;
    private int repeatMode = Player.REPEAT_MODE_OFF;
    private float volume = 1f;
    private PlaybackParameters playbackParameters = PlaybackParameters.DEFAULT;
    private TrackSelectionParameters trackSelectionParameters = TrackSelectionParameters.DEFAULT;
    private VideoSize videoSize = VideoSize.UNKNOWN;
    private Tracks tracks = Tracks.EMPTY;

    private long positionMs;
    private long durationMs = C.TIME_UNSET;
    private long bufferedMs;

    @Nullable
    private PlaybackException error;

    private boolean fileOpened;

    private boolean released;

    // mpv events come on mpv's thread; drop any that reach main after release
    private void onMain(final Runnable task) {
        handler.post(() -> {
            if (!released) {
                task.run();
            }
        });
    }

    private boolean renderedFirstFrame;

    private final boolean network;

    // until PLAYBACK_RESTART the position is the seek target, as in Media3;
    // older time-pos reports must not overwrite it
    private boolean seekPending;
    private long seekTargetMs = C.TIME_UNSET;
    private long seekIssuedAtNs;
    private boolean seekTargetUncached;
    private int seekRetries;

    public static boolean isSupported() {
        return android.os.Build.VERSION.SDK_INT >= 26;
    }

    public MpvPlayer(final Context context, final MpvOptions options) {
        this.context = context.getApplicationContext();
        this.listeners = new ListenerSet<>(Looper.getMainLooper(), Clock.DEFAULT,
                (listener, flags) -> listener.onEvents(this, new Player.Events(flags)));
        this.network = options.isNetwork();

        mpv = MPVLib.create(this.context);
        options.applyTo(mpv, this.context);
        mpv.init();
        subtitleParity = measureSubtitleParity();

        observe(PROP_TIME_POS, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe(PROP_DURATION, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe(PROP_DEMUXER_CACHE_TIME, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe(PROP_PAUSE, MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        observe(PROP_EOF, MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        observe(PROP_CACHE_BUFFERING, MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        observe(PROP_WIDTH, MPVLib.MpvFormat.MPV_FORMAT_INT64);
        observe(PROP_HEIGHT, MPVLib.MpvFormat.MPV_FORMAT_INT64);
        observe(PROP_TRACK_LIST_COUNT, MPVLib.MpvFormat.MPV_FORMAT_INT64);
        observe(PROP_VID, MPVLib.MpvFormat.MPV_FORMAT_STRING);
        observe(PROP_AID, MPVLib.MpvFormat.MPV_FORMAT_STRING);
        observe(PROP_SID, MPVLib.MpvFormat.MPV_FORMAT_STRING);
        // picture position inside the surface, see pictureMargins()
        for (final String edge : PICTURE_EDGES) {
            observe("osd-dimensions/" + edge, MPVLib.MpvFormat.MPV_FORMAT_INT64);
        }

        mpv.addObserver(this);
        // load errors only carry a code; the reason is in the log
        mpv.addLogObserver(this);
    }

    private void observe(final String property, final int format) {
        if (mpv != null) {
            mpv.observeProperty(property, format);
        }
    }

    // playback

    @Override
    public void setMediaItems(@NonNull List<MediaItem> items, boolean resetPosition) {
        setMediaItems(items, 0, resetPosition ? C.TIME_UNSET : positionMs);
    }

    @Override
    public void setMediaItems(@NonNull List<MediaItem> items, int startIndex, long startPositionMs) {
        // same file with extra subtitles: add them to the open file, no reload
        if (fileOpened && !items.isEmpty() && !mediaItems.isEmpty()
                && sameSource(mediaItems.get(0), items.get(0))) {
            mediaItems.clear();
            mediaItems.addAll(items);
            final MediaItem.LocalConfiguration local = items.get(0).localConfiguration;
            if (local != null) {
                for (final MediaItem.SubtitleConfiguration subtitle : local.subtitleConfigurations) {
                    if (!addedSubtitles.contains(subtitle.uri.toString())) {
                        addSubtitleConfiguration(subtitle);
                    }
                }
            }
            announceMediaMetadata();
            return;
        }

        mediaItems.clear();
        mediaItems.addAll(items);

        pendingStartPositionMs = startPositionMs;
        openingAtMs = startPositionMs != C.TIME_UNSET && startPositionMs > 0
                ? startPositionMs : C.TIME_UNSET;
        updateTimeline();
        announceMediaMetadata();
    }

    private static boolean sameSource(final MediaItem a, final MediaItem b) {
        return a.localConfiguration != null && b.localConfiguration != null
                && a.localConfiguration.uri.equals(b.localConfiguration.uri);
    }

    private final java.util.Set<String> addedSubtitles = new java.util.HashSet<>();

    private long pendingStartPositionMs = C.TIME_UNSET;

    // reported as the position until mpv reports a time, as Media3 does
    private long openingAtMs = C.TIME_UNSET;

    @Override
    public void prepare() {
        if (mpv == null || mediaItems.isEmpty()) {
            return;
        }
        error = null;
        fileOpened = false;
        renderedFirstFrame = false;
        setPlaybackState(Player.STATE_BUFFERING);

        // loading before the surface exists makes mpv play audio only
        if (surface == null) {
            loadWhenSurfaceReady = true;
            return;
        }
        load();
    }

    // sub-add before FILE_LOADED is lost, so sidecar subtitles wait here
    private final List<MediaItem.SubtitleConfiguration> pendingSubtitles = new ArrayList<>();

    private void addPendingSubtitles() {
        if (mpv == null || pendingSubtitles.isEmpty()) {
            return;
        }
        for (final MediaItem.SubtitleConfiguration subtitle : pendingSubtitles) {
            addSubtitleConfiguration(subtitle);
        }
        pendingSubtitles.clear();
    }

    // language must go with sub-add; track-list is read-only afterwards
    private void addSubtitleConfiguration(final MediaItem.SubtitleConfiguration subtitle) {
        final String flag =
                (subtitle.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0 ? "select" : "auto";
        subAdd(subtitle.uri, subtitle.label, subtitle.language, flag);
    }

    private void load() {
        loadWhenSurfaceReady = false;
        if (mpv == null || mediaItems.isEmpty()) {
            return;
        }
        final MediaItem item = mediaItems.get(0);
        if (item.localConfiguration == null) {
            return;
        }

        final String uri = item.localConfiguration.uri.toString();
        fileOpened = false;
        addedSubtitles.clear();
        clearSeek();
        synchronized (complaints) {
            complaints.setLength(0);
        }

        // per-file start option; the global `start` property would stick
        final long startMs = pendingStartPositionMs;
        pendingStartPositionMs = C.TIME_UNSET;
        if (startMs != C.TIME_UNSET && startMs > 0) {
            mpv.command(new String[]{"loadfile", uri, "replace", "-1",
                    "start=" + (startMs / 1000.0)});
        } else {
            mpv.command(new String[]{"loadfile", uri});
        }

        pendingSubtitles.clear();
        pendingSubtitles.addAll(item.localConfiguration.subtitleConfigurations);

        mpv.setPropertyBoolean(PROP_PAUSE, !playWhenReady);
    }

    @Override
    public void setPlayWhenReady(boolean playWhenReady) {
        if (this.playWhenReady == playWhenReady) {
            return;
        }
        this.playWhenReady = playWhenReady;
        // while holding, the pause state is applied on landing (see hold)
        if (mpv != null && !holding) {
            mpv.setPropertyBoolean(PROP_PAUSE, !playWhenReady);
        }
        listeners.queueEvent(Player.EVENT_PLAY_WHEN_READY_CHANGED, listener ->
                listener.onPlayWhenReadyChanged(playWhenReady,
                        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST));
        updateIsPlaying();
        listeners.flushEvents();
    }

    @Override
    public boolean getPlayWhenReady() {
        return playWhenReady;
    }

    @Override
    public void stop() {
        if (mpv != null) {
            mpv.command(new String[]{"stop"});
        }
        setPlaybackState(Player.STATE_IDLE);
    }

    @Override
    public void release() {
        if (released) {
            return;
        }
        released = true;
        handler.removeCallbacksAndMessages(null);
        seekAfterOpenMs = C.TIME_UNSET;
        pictureListener = null;
        setHandleAudioBecomingNoisy(false);
        if (mpv != null) {
            mpv.removeObserver(this);
            detachSurface();
            mpv.destroy();
            mpv = null;
        }
        listeners.release();
    }

    @Override
    protected void seekTo(int mediaItemIndex, long positionMs, int seekCommand,
                          boolean isRepeatingCurrentItem) {
        if (mpv == null) {
            return;
        }
        // C.TIME_UNSET means the default position, the start
        final long target = positionMs == C.TIME_UNSET ? 0 : Math.max(0, positionMs);
        final long from = this.positionMs;

        // during an uncached seek, a failed reconnect can append the first target's
        // data to the cached range; drop the cache unless this target is held
        final boolean held = network && isCached(target);
        if (network && seekPending && seekTargetUncached && !held
                && Math.abs(target - seekTargetMs) > 1_000) {
            Log.i(TAG, "seek during an uncached seek: dropping the cache first");
            mpv.command(new String[]{"drop-buffers"});
        }
        if (target != seekTargetMs) {
            seekRetries = 0;
        }
        endHold();
        seekTargetUncached = network && !held;
        sendSeek(target, keyframeSeeking ? "absolute+keyframes" : "absolute");

        final Player.PositionInfo oldPosition = positionInfo(from);
        final Player.PositionInfo newPosition = positionInfo(target);
        listeners.sendEvent(Player.EVENT_POSITION_DISCONTINUITY, listener ->
                listener.onPositionDiscontinuity(oldPosition, newPosition,
                        Player.DISCONTINUITY_REASON_SEEK));
    }

    // buffering until mpv lands, as Media3 does; the timeline drag relies on it
    private void sendSeek(final long targetMs, final String mode) {
        if (mpv == null) {
            return;
        }
        positionMs = targetMs;
        // a keyframe seek may land either side of the target
        guard.anchor(targetMs, LANDING_TOLERANCE_MS);
        if (!fileOpened) {
            // mpv refuses seeks before the file is open; sent on FILE_LOADED
            seekAfterOpenMs = targetMs;
            openingAtMs = targetMs;
            return;
        }
        seekPending = true;
        seekTargetMs = targetMs;
        seekIssuedAtNs = System.nanoTime();
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "seek " + targetMs + " " + mode);
        }
        mpv.command(new String[]{"seek", String.valueOf(targetMs / 1000.0), mode});
        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
            setPlaybackState(Player.STATE_BUFFERING);
        }
        handler.removeCallbacks(seekWatchdog);
        handler.postDelayed(seekWatchdog, SEEK_WATCHDOG_MS);
    }

    private long seekAfterOpenMs = C.TIME_UNSET;

    // commands return nothing, so a refused seek never restarts playback;
    // poll mpv's "seeking" instead
    private static final long SEEK_WATCHDOG_MS = 1_000;
    private final Runnable seekWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!seekPending || mpv == null) {
                return;
            }
            final Boolean seeking = mpv.getPropertyBoolean("seeking");
            if (seeking != null && !seeking && !isBuffering) {
                if (landedWrong()) {
                    return;
                }
                seekPending = false;
                final Double now = mpv.getPropertyDouble(PROP_TIME_POS);
                if (now != null) {
                    positionMs = (long) (now * 1000);
                }
                landed();
                if (playbackState == Player.STATE_BUFFERING) {
                    setPlaybackState(Player.STATE_READY);
                }
                return;
            }
            handler.postDelayed(this, SEEK_WATCHDOG_MS);
        }
    };

    private void clearSeek() {
        seekPending = false;
        seekTargetMs = C.TIME_UNSET;
        seekTargetUncached = false;
        seekRetries = 0;
        seekAfterOpenMs = C.TIME_UNSET;
        handler.removeCallbacks(seekWatchdog);
        endHold();
        guard.reset();
    }

    // from demuxer-cache-state seekable-ranges; unreadable counts as not cached
    private boolean isCached(final long positionMs) {
        if (mpv == null) {
            return false;
        }
        try {
            final String state = mpv.getPropertyString("demuxer-cache-state");
            if (state == null || state.isEmpty()) {
                return false;
            }
            final org.json.JSONArray ranges =
                    new org.json.JSONObject(state).optJSONArray("seekable-ranges");
            if (ranges == null) {
                return false;
            }
            final double seconds = positionMs / 1000.0;
            for (int i = 0; i < ranges.length(); i++) {
                final org.json.JSONObject range = ranges.optJSONObject(i);
                if (range != null && seconds >= range.optDouble("start", Double.NaN)
                        && seconds <= range.optDouble("end", Double.NaN)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read the cache state: " + e);
        }
        return false;
    }

    // a failed reconnect lands the seek elsewhere; generous for keyframe seeks
    private static final long LANDING_TOLERANCE_MS = 15_000;

    private boolean landedWrong() {
        if (!network || mpv == null || seekTargetMs == C.TIME_UNSET) {
            return false;
        }
        final Double landed = mpv.getPropertyDouble(PROP_TIME_POS);
        if (landed == null) {
            return false;
        }
        final long landedMs = (long) (landed * 1000);
        if (Math.abs(landedMs - seekTargetMs) <= LANDING_TOLERANCE_MS) {
            return false;
        }
        seekRetries++;
        final long delay = seekRetries == 1 ? 0
                : Math.min(MAX_RETRY_DELAY_MS, 1_000L << Math.min(seekRetries - 2, 3));
        Log.w(TAG, "seek to " + seekTargetMs + " landed at " + landedMs + " (try "
                + seekRetries + "): held there, asking again in " + delay + "ms");
        hold();
        positionMs = seekTargetMs;
        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
            setPlaybackState(Player.STATE_BUFFERING);
        }
        handler.removeCallbacks(landingRetry);
        handler.postDelayed(landingRetry, delay);
        return true;
    }

    // after a wrong landing, stay paused and retry with backoff until it lands
    private static final long MAX_RETRY_DELAY_MS = 8_000;
    private boolean holding;
    private final Runnable landingRetry = () -> {
        if (!holding || mpv == null || seekTargetMs == C.TIME_UNSET) {
            return;
        }
        mpv.command(new String[]{"drop-buffers"});
        seekTargetUncached = true;
        sendSeek(seekTargetMs, "absolute+exact");
    };

    // pauses mpv without changing playWhenReady
    private void hold() {
        if (!holding && mpv != null) {
            holding = true;
            mpv.setPropertyBoolean(PROP_PAUSE, true);
        }
    }

    private void endHold() {
        handler.removeCallbacks(landingRetry);
        if (holding) {
            holding = false;
            if (mpv != null) {
                mpv.setPropertyBoolean(PROP_PAUSE, !playWhenReady);
            }
        }
    }

    private void landed() {
        seekRetries = 0;
        endHold();
        guard.anchor(positionMs, SeamGuard.JUMP_MS);
    }

    // Detects an unrequested forward jump (a badly joined cache), drops the cache and
    // refetches. A repeat jump from the same place is in the file itself and allowed.
    private final SeamGuard guard = new SeamGuard();

    private final class SeamGuard {
        static final long JUMP_MS = 8_000;
        private long lastPositionMs = -1;
        private long lastAtMs;
        private long slackMs = JUMP_MS;
        private long lastSeamFromMs = -1;

        void reset() {
            lastPositionMs = -1;
        }

        void anchor(final long positionMs, final long slack) {
            lastPositionMs = positionMs;
            lastAtMs = android.os.SystemClock.elapsedRealtime();
            slackMs = slack;
        }

        // true when a jump was handled and the new position should be ignored
        boolean check(final long newPositionMs) {
            final long now = android.os.SystemClock.elapsedRealtime();
            final long previous = lastPositionMs;
            final long previousAt = lastAtMs;
            final long slack = slackMs;
            lastPositionMs = newPositionMs;
            lastAtMs = now;
            slackMs = JUMP_MS;
            if (!network || previous < 0) {
                return false;
            }
            final long elapsed = now - previousAt;
            final long expected = previous
                    + (long) (elapsed * Math.max(1f, playbackParameters.speed));
            if (newPositionMs - expected < slack) {
                return false;
            }
            if (lastSeamFromMs >= 0 && Math.abs(previous - lastSeamFromMs) < 3_000) {
                Log.w(TAG, "the file itself jumps from " + previous + " to " + newPositionMs);
                return false;
            }
            lastSeamFromMs = previous;
            Log.w(TAG, "unrequested jump from " + previous + " to " + newPositionMs
                    + ": the cache was joined wrong; refetching from " + previous);
            mpv.command(new String[]{"drop-buffers"});
            seekRetries = 0;
            seekTargetUncached = true;
            sendSeek(previous, "absolute+exact");
            return true;
        }
    }

    // set by SeekPrecision, mirrors Media3's SeekParameters
    private boolean keyframeSeeking;

    public void setKeyframeSeeking(final boolean keyframes) {
        keyframeSeeking = keyframes;
    }

    private Player.PositionInfo positionInfo(final long atMs) {
        return new Player.PositionInfo(
                /* windowUid= */ null,
                /* mediaItemIndex= */ 0,
                getCurrentMediaItem(),
                /* periodUid= */ null,
                /* periodIndex= */ 0,
                /* positionMs= */ atMs,
                /* contentPositionMs= */ atMs,
                /* adGroupIndex= */ C.INDEX_UNSET,
                /* adIndexInAdGroup= */ C.INDEX_UNSET);
    }

    // events

    @Override
    public void eventProperty(@NonNull String property) {
    }

    @Override
    public void eventProperty(@NonNull String property, long value) {
        onMain(() -> {
            switch (property) {
                case PROP_WIDTH:
                case PROP_HEIGHT:
                    updateVideoSize();
                    break;
                case PROP_TRACK_LIST_COUNT:
                    updateTracks();
                    break;
                default:
                    if (property.startsWith("osd-dimensions/")) {
                        final Runnable listener = pictureListener;
                        if (listener != null) {
                            listener.run();
                        }
                    }
                    break;
            }
        });
    }

    @Override
    public void eventProperty(@NonNull String property, double value) {
        onMain(() -> {
            switch (property) {
                case PROP_TIME_POS:
                    if (seekPending) {
                        break;
                    }
                    final long reported = (long) (value * 1000);
                    if (guard.check(reported)) {
                        break;
                    }
                    positionMs = reported;
                    if (fileOpened) {
                        openingAtMs = C.TIME_UNSET;
                    }
                    break;
                case PROP_DURATION:
                    final long newDuration = value > 0 ? (long) (value * 1000) : C.TIME_UNSET;
                    if (newDuration != durationMs) {
                        durationMs = newDuration;
                        updateTimeline();
                    }
                    break;
                case PROP_DEMUXER_CACHE_TIME:
                    bufferedMs = value > 0 ? (long) (value * 1000) : positionMs;
                    break;
                default:
                    break;
            }
        });
    }

    @Override
    public void eventProperty(@NonNull String property, boolean value) {
        onMain(() -> {
            switch (property) {
                case PROP_PAUSE:
                    // mpv can pause itself (end of file, audio focus); ignore our own hold
                    if (holding) {
                        break;
                    }
                    if (playWhenReady == value) {
                        playWhenReady = !value;
                        listeners.sendEvent(Player.EVENT_PLAY_WHEN_READY_CHANGED, listener ->
                                listener.onPlayWhenReadyChanged(playWhenReady,
                                        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE));
                        updateIsPlaying();
                    }
                    break;
                case PROP_EOF:
                    if (value) {
                        setPlaybackState(Player.STATE_ENDED);
                    }
                    break;
                case PROP_CACHE_BUFFERING:
                    isBuffering = value;
                    setPlaybackState(value || seekPending
                            ? Player.STATE_BUFFERING : Player.STATE_READY);
                    break;
                default:
                    break;
            }
        });
    }

    @Override
    public void eventProperty(@NonNull String property, @NonNull String value) {
        onMain(() -> {
            switch (property) {
                case PROP_VID:
                case PROP_AID:
                case PROP_SID:
                    updateTracks();
                    break;
                default:
                    break;
            }
        });
    }

    @Override
    public void event(int eventId) {
        // taken before posting: a restart from before the latest seek is an older seek's
        final long arrivedAtNs = System.nanoTime();
        onMain(() -> {
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART
                    && seekPending && arrivedAtNs >= seekIssuedAtNs) {
                if (landedWrong()) {
                    return;
                }
                seekPending = false;
                final Double landedAt = mpv == null ? null : mpv.getPropertyDouble(PROP_TIME_POS);
                if (landedAt != null) {
                    positionMs = (long) (landedAt * 1000);
                    openingAtMs = C.TIME_UNSET;
                }
                landed();
            }
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
                fileOpened = true;
                addPendingSubtitles();
                updateTracks();
                updateVideoSize();
                setPlaybackState(Player.STATE_READY);
                if (seekAfterOpenMs != C.TIME_UNSET) {
                    final long target = seekAfterOpenMs;
                    seekAfterOpenMs = C.TIME_UNSET;
                    sendSeek(target, keyframeSeeking ? "absolute+keyframes" : "absolute");
                }
            } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
                // END_FILE before FILE_LOADED: the file failed to open
                if (!fileOpened) {
                    reportFailedToOpen();
                } else if (playbackState != Player.STATE_IDLE) {
                    setPlaybackState(Player.STATE_ENDED);
                }
            }
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART) {
                if (!isBuffering && !seekPending) {
                    setPlaybackState(Player.STATE_READY);
                }
                notifyFirstFrame();
            } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG) {
                updateVideoSize();
                notifyFirstFrame();
            }
        });
    }

    // Warnings and errors from mpv's log since loadfile; all are kept because the
    // specific reason is often neither first nor last. Written on mpv's thread.
    private static final int COMPLAINTS_LIMIT = 4000;
    private final StringBuilder complaints = new StringBuilder();

    @Override
    public void logMessage(final String prefix, final int level, final String text) {
        if (text == null || level > MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) {
            return;
        }
        synchronized (complaints) {
            if (complaints.length() < COMPLAINTS_LIMIT) {
                complaints.append(text.trim()).append('\n');
            }
        }
    }

    private String complaints() {
        synchronized (complaints) {
            return complaints.toString();
        }
    }

    // maps mpv's log text to a PlaybackException code for the error message
    private void reportFailedToOpen() {
        if (error != null) {
            return;
        }
        final String all = complaints();
        final String said = all.toLowerCase();
        final int code;
        if (said.contains("404") || said.contains("not found")) {
            code = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND;
        } else if (said.contains("403") || said.contains("401")
                || said.contains("http error")) {
            code = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS;
        } else if (said.contains("permission denied")) {
            code = PlaybackException.ERROR_CODE_IO_NO_PERMISSION;
        } else if (said.contains("failed to open") || said.contains("connection")
                || said.contains("timed out") || said.contains("resolve")) {
            code = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED;
        } else if (said.contains("no video or audio streams")
                || said.contains("unrecognized file format")) {
            code = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
        } else {
            code = PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
        }

        error = new PlaybackException(
                all.isEmpty() ? "mpv could not open the file" : all.trim(),
                null,
                code);
        setPlaybackState(Player.STATE_IDLE);
        listeners.sendEvent(Player.EVENT_PLAYER_ERROR,
                listener -> listener.onPlayerError(error));
        listeners.sendEvent(Player.EVENT_PLAYER_ERROR,
                listener -> listener.onPlayerErrorChanged(error));
    }

    private void notifyFirstFrame() {
        if (renderedFirstFrame) {
            return;
        }
        renderedFirstFrame = true;
        listeners.sendEvent(Player.EVENT_RENDERED_FIRST_FRAME, Player.Listener::onRenderedFirstFrame);
    }

    private void setPlaybackState(final int state) {
        if (playbackState == state) {
            return;
        }
        playbackState = state;
        listeners.queueEvent(Player.EVENT_PLAYBACK_STATE_CHANGED, listener ->
                listener.onPlaybackStateChanged(state));
        updateIsPlaying();
        listeners.flushEvents();
    }

    private void updateIsPlaying() {
        final boolean playing = isPlaying();
        listeners.queueEvent(Player.EVENT_IS_PLAYING_CHANGED, listener ->
                listener.onIsPlayingChanged(playing));
    }

    private void updateTimeline() {
        listeners.sendEvent(Player.EVENT_TIMELINE_CHANGED, listener ->
                listener.onTimelineChanged(getCurrentTimeline(),
                        Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE));
    }

    private void updateVideoSize() {
        if (mpv == null) {
            return;
        }
        final Integer width = mpv.getPropertyInt(PROP_WIDTH);
        final Integer height = mpv.getPropertyInt(PROP_HEIGHT);
        if (width == null || height == null || width <= 0 || height <= 0) {
            return;
        }
        final VideoSize size = new VideoSize(width, height);
        if (size.equals(videoSize)) {
            return;
        }
        videoSize = size;
        // video params are only known once decoding starts
        updateTracks();
        listeners.sendEvent(Player.EVENT_VIDEO_SIZE_CHANGED, listener ->
                listener.onVideoSizeChanged(size));
    }

    // PlayerView hides the video when it thinks no video track is selected
    private void updateTracks() {
        if (mpv == null) {
            return;
        }
        final Integer count = mpv.getPropertyInt("track-list/count");
        if (count == null || count <= 0) {
            return;
        }

        // HDR comes from the decoded video, reported in the Format as Media3 does
        final String gamma = mpv.getPropertyString("video-params/gamma");

        final Integer currentVideo = currentTrackId("video", "vid");
        final Integer currentAudio = currentTrackId("audio", "aid");
        final Integer currentSub = currentTrackId("sub", "sid");

        final List<TrackInfo> found = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final String type = mpv.getPropertyString("track-list/" + i + "/type");
            if (type == null || (!type.equals("video") && !type.equals("audio")
                    && !type.equals("sub"))) {
                continue;
            }
            final TrackInfo info = new TrackInfo();
            info.index = i;
            info.type = type;
            info.title = mpv.getPropertyString("track-list/" + i + "/title");
            info.language = mpv.getPropertyString("track-list/" + i + "/lang");
            info.id = mpv.getPropertyInt("track-list/" + i + "/id");

            info.codec = mpv.getPropertyString("track-list/" + i + "/codec");
            info.channels = mpv.getPropertyInt("track-list/" + i + "/demux-channel-count");
            info.sampleRate = mpv.getPropertyInt("track-list/" + i + "/demux-samplerate");
            info.fps = mpv.getPropertyDouble("track-list/" + i + "/demux-fps");
            info.width = mpv.getPropertyInt("track-list/" + i + "/demux-w");
            info.height = mpv.getPropertyInt("track-list/" + i + "/demux-h");
            info.isDefault = isTrue(mpv.getPropertyBoolean("track-list/" + i + "/default"));
            info.forced = isTrue(mpv.getPropertyBoolean("track-list/" + i + "/forced"));
            info.hearingImpaired =
                    isTrue(mpv.getPropertyBoolean("track-list/" + i + "/hearing-impaired"));
            info.visualImpaired =
                    isTrue(mpv.getPropertyBoolean("track-list/" + i + "/visual-impaired"));

            final Integer current = type.equals("video") ? currentVideo
                    : type.equals("audio") ? currentAudio : currentSub;
            if (current != null && info.id != null) {
                info.selected = current.equals(info.id);
            } else {
                final Boolean flagged =
                        mpv.getPropertyBoolean("track-list/" + i + "/selected");
                info.selected = flagged != null && flagged;
            }
            found.add(info);
        }

        ensureOneSelected(found, "video", "vid");
        ensureOneSelected(found, "audio", "aid");

        final ImmutableList.Builder<Tracks.Group> groups = ImmutableList.builder();
        for (final TrackInfo info : found) {
            String mime = MpvCodecs.mimeFor(info.type, info.codec);
            if (mime == null) {
                switch (info.type) {
                    case "video":
                        mime = androidx.media3.common.MimeTypes.BASE_TYPE_VIDEO + "/unknown";
                        break;
                    case "audio":
                        mime = androidx.media3.common.MimeTypes.BASE_TYPE_AUDIO + "/unknown";
                        break;
                    default:
                        mime = androidx.media3.common.MimeTypes.BASE_TYPE_TEXT + "/unknown";
                        break;
                }
            }

            int selectionFlags = 0;
            if (info.isDefault) {
                selectionFlags |= C.SELECTION_FLAG_DEFAULT;
            }
            if (info.forced) {
                selectionFlags |= C.SELECTION_FLAG_FORCED;
            }

            int roleFlags = 0;
            if (info.hearingImpaired) {
                roleFlags |= C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND;
            }
            if (info.visualImpaired) {
                roleFlags |= C.ROLE_FLAG_DESCRIBES_VIDEO;
            }

            final Format.Builder builder = new Format.Builder()
                    .setId(info.id == null ? String.valueOf(info.index) : String.valueOf(info.id))
                    .setSampleMimeType(mime)
                    .setLanguage(info.language)
                    .setLabel(info.title)
                    .setSelectionFlags(selectionFlags)
                    .setRoleFlags(roleFlags);
            if (info.codec != null && !info.codec.isEmpty()) {
                builder.setCodecs(info.codec);
            }
            if (info.channels != null && info.channels > 0) {
                builder.setChannelCount(info.channels);
            }
            if (info.sampleRate != null && info.sampleRate > 0) {
                builder.setSampleRate(info.sampleRate);
            }
            if (info.width != null && info.width > 0 && info.height != null && info.height > 0) {
                builder.setWidth(info.width).setHeight(info.height);
            }
            if (info.fps != null && info.fps > 0) {
                builder.setFrameRate(info.fps.floatValue());
            }
            if ("video".equals(info.type) && gamma != null) {
                final int transfer = "pq".equals(gamma) ? C.COLOR_TRANSFER_ST2084
                        : "hlg".equals(gamma) ? C.COLOR_TRANSFER_HLG
                        : Format.NO_VALUE;
                if (transfer != Format.NO_VALUE) {
                    builder.setColorInfo(new androidx.media3.common.ColorInfo.Builder()
                            .setColorTransfer(transfer)
                            .build());
                }
            }
            final Format format = builder.build();
            final TrackGroup group = new TrackGroup(String.valueOf(info.index), format);
            groups.add(new Tracks.Group(group, false, new int[]{C.FORMAT_HANDLED},
                    new boolean[]{info.selected}));
        }

        final Tracks built = new Tracks(groups.build());
        if (built.equals(tracks)) {
            return;
        }
        tracks = built;
        listeners.sendEvent(Player.EVENT_TRACKS_CHANGED, listener ->
                listener.onTracksChanged(built));
    }

    private static final class TrackInfo {
        int index;
        String type;
        String title;
        String language;
        String codec;
        Integer id;
        Integer channels;
        Integer sampleRate;
        Double fps;
        Integer width;
        Integer height;
        boolean isDefault;
        boolean forced;
        boolean hearingImpaired;
        boolean visualImpaired;
        boolean selected;
    }

    private static boolean isTrue(final Boolean value) {
        return value != null && value;
    }

    @Nullable
    // vid/aid/sid read "auto" while the file is opening; current-tracks is what plays
    private Integer currentTrackId(final String type, final String property) {
        if (mpv == null) {
            return null;
        }
        final Integer current = mpv.getPropertyInt("current-tracks/" + type + "/id");
        if (current != null) {
            return current;
        }
        return mpv.getPropertyInt(property);
    }

    // mpv may not report the playing track as selected yet
    private void ensureOneSelected(final List<TrackInfo> found, final String type,
                                   final String property) {
        if (mpv == null) {
            return;
        }
        TrackInfo first = null;
        for (final TrackInfo info : found) {
            if (!info.type.equals(type)) {
                continue;
            }
            if (info.selected) {
                return;
            }
            if (first == null) {
                first = info;
            }
        }
        if (first == null) {
            return;
        }
        // "no" means the track type was turned off
        if ("no".equals(mpv.getPropertyString(property))) {
            return;
        }
        first.selected = true;
    }

    // surface

    @Override
    public void setVideoSurface(@Nullable Surface surface) {
        detachSurface();
        this.surface = surface;
        if (surface != null && mpv != null) {
            mpv.attachSurface(surface);
            mpv.setOptionString("force-window", "yes");
            mpv.setOptionString("vo", "gpu");
            if (loadWhenSurfaceReady) {
                load();
            }
        }
    }

    @Override
    public void setVideoSurfaceHolder(@Nullable SurfaceHolder holder) {
        if (holder == null) {
            clearVideoSurface();
            return;
        }
        surfaceHolder = holder;
        holder.addCallback(surfaceCallback);
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            setVideoSurface(holder.getSurface());
        }
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            setVideoSurface(holder.getSurface());
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            if (mpv != null) {
                mpv.setPropertyString("android-surface-size", width + "x" + height);
            }
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            detachSurface();
        }
    };

    private void detachSurface() {
        if (surface != null && mpv != null) {
            mpv.setOptionString("vo", "null");
            mpv.setOptionString("force-window", "no");
            mpv.detachSurface();
        }
        surface = null;
    }

    @Override
    public void setVideoSurfaceView(@Nullable SurfaceView surfaceView) {
        setVideoSurfaceHolder(surfaceView == null ? null : surfaceView.getHolder());
    }

    @Override
    public void clearVideoSurfaceView(@Nullable SurfaceView surfaceView) {
        clearVideoSurface();
    }

    @Override
    public void setVideoTextureView(@Nullable TextureView textureView) {
        // not supported, mpv needs a SurfaceView
        clearVideoSurface();
    }

    @Override
    public void clearVideoTextureView(@Nullable TextureView textureView) {
        clearVideoSurface();
    }

    @Override
    public void clearVideoSurface() {
        if (surfaceHolder != null) {
            surfaceHolder.removeCallback(surfaceCallback);
            surfaceHolder = null;
        }
        detachSurface();
    }

    @Override
    public void clearVideoSurface(@Nullable Surface surface) {
        if (surface == this.surface) {
            clearVideoSurface();
        }
    }

    @Override
    public void clearVideoSurfaceHolder(@Nullable SurfaceHolder holder) {
        if (holder == surfaceHolder) {
            clearVideoSurface();
        }
    }

    // properties

    @NonNull
    @Override
    public Looper getApplicationLooper() {
        return Looper.getMainLooper();
    }

    @Override
    public void addListener(@NonNull Player.Listener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(@NonNull Player.Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public int getPlaybackState() {
        return playbackState;
    }

    @Override
    public int getPlaybackSuppressionReason() {
        return Player.PLAYBACK_SUPPRESSION_REASON_NONE;
    }

    @Nullable
    @Override
    public PlaybackException getPlayerError() {
        return error;
    }

    @Override
    public void setRepeatMode(int repeatMode) {
        this.repeatMode = repeatMode;
        if (mpv != null) {
            mpv.setOptionString("loop-file",
                    repeatMode == Player.REPEAT_MODE_OFF ? "no" : "inf");
        }
        listeners.sendEvent(Player.EVENT_REPEAT_MODE_CHANGED, listener ->
                listener.onRepeatModeChanged(repeatMode));
    }

    @Override
    public int getRepeatMode() {
        return repeatMode;
    }

    @Override
    public void setShuffleModeEnabled(boolean shuffleModeEnabled) {
        // single item, nothing to shuffle
    }

    @Override
    public boolean getShuffleModeEnabled() {
        return false;
    }

    @Override
    public boolean isLoading() {
        return isBuffering;
    }

    @Override
    public long getSeekBackIncrement() {
        return C.DEFAULT_SEEK_BACK_INCREMENT_MS;
    }

    @Override
    public long getSeekForwardIncrement() {
        return C.DEFAULT_SEEK_FORWARD_INCREMENT_MS;
    }

    @Override
    public long getMaxSeekToPreviousPosition() {
        return C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS;
    }

    @Override
    public void setPlaybackParameters(@NonNull PlaybackParameters parameters) {
        final float previousSpeed = playbackParameters.speed;
        playbackParameters = parameters;
        if (mpv != null) {
            set("speed", String.valueOf(parameters.speed));
            // mpv applies a new speed only after a playback restart; a 0s seek forces one
            if (fileOpened && Math.abs(previousSpeed - parameters.speed) > 0.001f) {
                mpv.command(new String[]{"seek", "0", "relative+exact"});
            }
        }
        listeners.sendEvent(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, listener ->
                listener.onPlaybackParametersChanged(parameters));
    }

    @NonNull
    @Override
    public PlaybackParameters getPlaybackParameters() {
        return playbackParameters;
    }

    @NonNull
    @Override
    public Tracks getCurrentTracks() {
        return tracks;
    }

    @NonNull
    @Override
    public TrackSelectionParameters getTrackSelectionParameters() {
        return trackSelectionParameters;
    }


    @Override
    public void setTrackSelectionParameters(@NonNull TrackSelectionParameters parameters) {
        trackSelectionParameters = parameters;
        if (mpv == null) {
            return;
        }

        boolean audioChosen = false;
        boolean textChosen = false;

        for (final TrackSelectionOverride override : parameters.overrides.values()) {
            final TrackGroup group = override.mediaTrackGroup;
            if (group.length == 0 || override.trackIndices.isEmpty()) {
                continue;
            }
            final Format format = group.getFormat(0);
            final String id = format.id;
            if (id == null) {
                continue;
            }
            // not a "text" prefix check: SubRip, tx3g and PGS are application/*
            final String mime = format.sampleMimeType == null ? "" : format.sampleMimeType;
            final int trackType = androidx.media3.common.MimeTypes.getTrackType(mime);
            if (trackType == C.TRACK_TYPE_AUDIO) {
                mpv.setPropertyString("aid", id);
                audioChosen = true;
            } else if (trackType == C.TRACK_TYPE_TEXT) {
                mpv.setPropertyString("sid", id);
                textChosen = true;
            } else if (trackType == C.TRACK_TYPE_VIDEO) {
                mpv.setPropertyString("vid", id);
            }
        }

        // "off" comes as a disabled track type, not an override
        if (!textChosen && parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            mpv.setPropertyString("sid", "no");
        }
        if (!audioChosen && parameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)) {
            mpv.setPropertyString("aid", "no");
        }
    }

    private int subtitleSizeStep;

    // sub-scale matching Media3's text size, from mpv's actual sub-font-size;
    // constant so subtitles don't resize with the picture's aspect or zoom
    private final double subtitleParity;

    private double measureSubtitleParity() {
        Double fontSize = null;
        try {
            fontSize = mpv == null ? null : mpv.getPropertyDouble("sub-font-size");
        } catch (Exception e) {
            Log.w(TAG, "could not read sub-font-size: " + e);
        }
        final double size = fontSize == null || fontSize <= 0
                ? MpvSubtitleScale.DEFAULT_FONT_SIZE : fontSize;
        return MpvSubtitleScale.parity(size);
    }

    private void applySubtitleScale() {
        if (mpv == null) {
            return;
        }
        set("sub-scale", String.valueOf(MpvSubtitleScale.scale(subtitleSizeStep, subtitleParity)));
    }


    /**
     * @param keepAspect     false stretches the picture to the window
     * @param panscan        0 letterboxes, 1 crops to fill
     * @param aspectOverride a forced ratio such as 1.777, or 0 for the video's own
     */
    public void setAspect(final boolean keepAspect, final double panscan,
                          final double aspectOverride) {
        if (mpv == null) {
            return;
        }
        set("keepaspect", keepAspect ? "yes" : "no");
        set("panscan", String.valueOf(panscan));
        set("video-aspect-override", aspectOverride > 0
                ? String.valueOf(aspectOverride) : "-1");
        refreshPicture();
    }

    // mpv only redraws on a new frame; when paused, a 0s exact seek produces one
    public void refreshPicture() {
        if (mpv == null) {
            return;
        }
        if (surfaceHolder != null) {
            final android.graphics.Rect frame = surfaceHolder.getSurfaceFrame();
            if (frame != null && frame.width() > 0 && frame.height() > 0) {
                mpv.setPropertyString("android-surface-size",
                        frame.width() + "x" + frame.height());
            }
        }
        if (playbackState == Player.STATE_READY && !playWhenReady) {
            mpv.command(new String[]{"seek", "0", "relative+exact"});
        }
        applySubtitleScale();
    }

    public void setSubtitleStyle(final int verticalPosition, final int sizeStep,
                                 final String edgeType, final String typeface,
                                 final boolean embeddedStyles) {
        if (mpv == null) {
            return;
        }

        subtitleVerticalPosition = verticalPosition;
        applySubtitlePosition();

        // let subtitles use the black bars
        set("sub-use-margins", "yes");
        set("sub-ass-force-margins", "yes");

        subtitleSizeStep = sizeStep;
        applySubtitleScale();

        switch (edgeType == null ? "" : edgeType) {
            case "None":
                set("sub-border-style", "outline-and-shadow");
                set("sub-border-size", "0");
                set("sub-shadow-offset", "0");
                break;
            case "DropShadow":
                set("sub-border-style", "outline-and-shadow");
                set("sub-border-size", "0");
                set("sub-shadow-offset", "3");
                break;
            case "OutlineShadow":
            case "Raised":
            case "Depressed":
                set("sub-border-style", "outline-and-shadow");
                set("sub-border-size", "3");
                set("sub-shadow-offset", "3");
                break;
            case "Outline":
            case "Default":
            default:
                set("sub-border-style", "outline-and-shadow");
                set("sub-border-size", "3");
                set("sub-shadow-offset", "0");
                break;
        }

        final boolean bold = "Bold".equals(typeface);
        set("sub-bold", bold ? "yes" : "no");
        set("sub-font", "Medium".equals(typeface) ? "sans-serif-medium" : "sans-serif");

        set("sub-ass-override", embeddedStyles ? "scale" : "force");
    }



    private int subtitleVerticalPosition;
    // percent of the screen height
    private double subtitleLiftPercent;

    private void applySubtitlePosition() {
        final double position = SUB_POS_DEFAULT - subtitleVerticalPosition - subtitleLiftPercent;
        set("sub-pos", String.valueOf(Math.max(0, Math.min(150, position))));
    }

    public void setSubtitleLift(final float fractionOfHeight) {
        subtitleLiftPercent = Math.max(0, fractionOfHeight) * 100.0;
        applySubtitlePosition();
    }

    // osd-dimensions: mpv letterboxes inside the full-screen surface
    private static final String[] PICTURE_EDGES = {"w", "h", "ml", "mt", "mr", "mb"};

    /** {w, h, left, top, right, bottom} in mpv's pixels, or null before a picture. */
    @Nullable
    public int[] pictureMargins() {
        if (mpv == null) {
            return null;
        }
        final int[] out = new int[PICTURE_EDGES.length];
        for (int i = 0; i < PICTURE_EDGES.length; i++) {
            final Integer value = mpv.getPropertyInt("osd-dimensions/" + PICTURE_EDGES[i]);
            if (value == null) {
                return null;
            }
            out[i] = value;
        }
        return out[0] > 0 && out[1] > 0 ? out : null;
    }

    @Nullable
    private Runnable pictureListener;

    // called when the picture moves inside the surface
    public void setPictureListener(@Nullable final Runnable listener) {
        this.pictureListener = listener;
    }

    // 1 is fit; mpv's video-zoom is log2 of the scale and leaves subtitles alone
    private double videoZoom = 1.0;

    public void setVideoZoom(final double scale) {
        videoZoom = Math.max(1.0, scale);
        set("video-zoom", String.valueOf(Math.log(videoZoom) / Math.log(2)));
    }

    public double getVideoZoom() {
        return videoZoom;
    }

    // volume is a float property; integer writes are rejected
    public void setVolumePercent(final int percent) {
        if (mpv == null) {
            return;
        }
        set("volume", String.valueOf(percent));
    }

    public void addSubtitle(final android.net.Uri uri) {
        addSubtitle(uri, null);
    }

    // title replaces the track name mpv would take from the file name
    public void addSubtitle(final android.net.Uri uri, @Nullable final String title) {
        addSubtitle(uri, title, null);
    }

    public void addSubtitle(final android.net.Uri uri, @Nullable final String title,
                            @Nullable final String language) {
        if (mpv == null || uri == null) {
            return;
        }
        subAdd(uri, title, language, "select");
    }

    // sub-add returns nothing; a failure shows as an unchanged track count
    private void subAdd(final android.net.Uri uri, @Nullable final String title,
                        @Nullable final String language, final String flag) {
        if (mpv == null) {
            return;
        }
        addedSubtitles.add(uri.toString());
        final Integer before = mpv.getPropertyInt("track-list/count");
        final String name = title == null ? "" : title.trim();
        final String lang = language == null ? "" : language.trim();
        if (name.isEmpty() && lang.isEmpty()) {
            mpv.command(new String[]{"sub-add", uri.toString(), flag});
        } else if (lang.isEmpty()) {
            mpv.command(new String[]{"sub-add", uri.toString(), flag, name});
        } else {
            // lang is positional after title, so a title is required
            mpv.command(new String[]{"sub-add", uri.toString(), flag,
                    name.isEmpty() ? lang : name, lang});
        }
        final Integer after = mpv.getPropertyInt("track-list/count");
        if (before != null && after != null && after <= before) {
            final Runnable listener = subtitleFailureListener;
            if (listener != null) {
                handler.post(listener);
            }
        }
    }

    @Nullable
    private Runnable subtitleFailureListener;

    public void setSubtitleFailureListener(@Nullable final Runnable listener) {
        this.subtitleFailureListener = listener;
    }
    public void setSubtitleDelayMs(final int delayMs) {
        if (mpv == null) {
            return;
        }
        set("sub-delay", String.valueOf(delayMs / 1000.0));
    }

    // mpv's cache-speed; -1 when nothing is coming in (local files)
    public long cacheSpeedBytesPerSecond() {
        if (mpv == null) {
            return -1;
        }
        final Double speed = mpv.getPropertyDouble("cache-speed");
        if (speed == null || speed <= 0) {
            return -1;
        }
        return (long) (double) speed;
    }

    // positive delays the audio, same sign as Media3
    public void setAudioDelayMs(final int delayMs) {
        if (mpv == null) {
            return;
        }
        set("audio-delay", String.valueOf(delayMs / 1000.0));
    }

    @Nullable
    public java.util.List<String[]> chapterList() {
        if (mpv == null) {
            return null;
        }
        final Integer count = mpv.getPropertyInt("chapter-list/count");
        if (count == null || count <= 0) {
            return null;
        }
        final java.util.List<String[]> chapters = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            final String title = mpv.getPropertyString("chapter-list/" + i + "/title");
            final Double time = mpv.getPropertyDouble("chapter-list/" + i + "/time");
            if (time == null) {
                continue;
            }
            chapters.add(new String[]{title == null ? "" : title, String.valueOf(time)});
        }
        return chapters;
    }

    // pause on headphone unplug, which ExoPlayer does itself
    public void setHandleAudioBecomingNoisy(final boolean handle) {
        if (handle == (becomingNoisyReceiver != null)) {
            return;
        }
        if (!handle) {
            context.unregisterReceiver(becomingNoisyReceiver);
            becomingNoisyReceiver = null;
            return;
        }
        becomingNoisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handler.post(() -> setPlayWhenReady(false));
            }
        };
        context.registerReceiver(becomingNoisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    // typed writes of the wrong type fail silently; strings are parsed by mpv
    private void set(final String property, final String value) {
        if (mpv == null) {
            return;
        }
        mpv.setPropertyString(property, value);
        if (BuildConfig.DEBUG) {
            final String readBack = mpv.getPropertyString(property);
            if (readBack == null || !readBack.equals(value)) {
                android.util.Log.d("JustPlayer", "mpv " + property + " = " + value
                        + " -> " + readBack);
            }
        }
    }

    public void selectTrack(final String type, final int id) {
        if (mpv != null) {
            set(type.equals("sub") ? "sid" : "aid", String.valueOf(id));
            updateTracks();
        }
    }

    @NonNull
    @Override
    public MediaMetadata getMediaMetadata() {
        return mediaItems.isEmpty() ? MediaMetadata.EMPTY : mediaItems.get(0).mediaMetadata;
    }

    private void announceMediaMetadata() {
        final MediaMetadata metadata = getMediaMetadata();
        listeners.sendEvent(Player.EVENT_MEDIA_METADATA_CHANGED,
                listener -> listener.onMediaMetadataChanged(metadata));
    }

    @NonNull
    @Override
    public MediaMetadata getPlaylistMetadata() {
        return MediaMetadata.EMPTY;
    }

    @Override
    public void setPlaylistMetadata(@NonNull MediaMetadata mediaMetadata) {
    }

    @NonNull
    @Override
    public Timeline getCurrentTimeline() {
        if (mediaItems.isEmpty()) {
            return Timeline.EMPTY;
        }
        return new SingleItemTimeline(mediaItems.get(0),
                durationMs == C.TIME_UNSET ? C.TIME_UNSET : C.msToUs(durationMs));
    }

    @Override
    public int getCurrentPeriodIndex() {
        return 0;
    }

    @Override
    public int getCurrentMediaItemIndex() {
        return 0;
    }

    @Override
    public long getDuration() {
        return durationMs;
    }

    @Override
    public long getCurrentPosition() {
        return openingAtMs != C.TIME_UNSET ? openingAtMs : positionMs;
    }

    @Override
    public long getBufferedPosition() {
        return Math.max(bufferedMs, positionMs);
    }

    @Override
    public long getTotalBufferedDuration() {
        return Math.max(0, getBufferedPosition() - positionMs);
    }

    @Override
    public boolean isPlayingAd() {
        return false;
    }

    @Override
    public int getCurrentAdGroupIndex() {
        return C.INDEX_UNSET;
    }

    @Override
    public int getCurrentAdIndexInAdGroup() {
        return C.INDEX_UNSET;
    }

    @Override
    public long getContentPosition() {
        return positionMs;
    }

    @Override
    public long getContentBufferedPosition() {
        return getBufferedPosition();
    }

    @NonNull
    @Override
    public AudioAttributes getAudioAttributes() {
        return AudioAttributes.DEFAULT;
    }

    @Override
    public void setVolume(float volume) {
        this.volume = volume;
        if (mpv != null) {
            set("volume", String.valueOf(volume * 100));
        }
        listeners.sendEvent(Player.EVENT_VOLUME_CHANGED, listener ->
                listener.onVolumeChanged(volume));
    }

    @Override
    public float getVolume() {
        return volume;
    }

    @NonNull
    @Override
    public VideoSize getVideoSize() {
        return videoSize;
    }

    @NonNull
    @Override
    public Size getSurfaceSize() {
        return Size.UNKNOWN;
    }

    @NonNull
    @Override
    public CueGroup getCurrentCues() {
        return CueGroup.EMPTY_TIME_ZERO;
    }

    @NonNull
    @Override
    public DeviceInfo getDeviceInfo() {
        return DeviceInfo.UNKNOWN;
    }

    @Override
    public int getDeviceVolume() {
        return 0;
    }

    @Override
    public boolean isDeviceMuted() {
        return false;
    }

    @Override
    public void setDeviceVolume(int volume, int flags) {
    }

    @Override
    public void increaseDeviceVolume(int flags) {
    }

    @Override
    public void decreaseDeviceVolume(int flags) {
    }

    @Override
    public void setDeviceMuted(boolean muted, int flags) {
    }

    @Override
    public void setDeviceMuted(boolean muted) {
    }

    @Override
    public void setDeviceVolume(int volume) {
    }

    @Override
    public void increaseDeviceVolume() {
    }

    @Override
    public void decreaseDeviceVolume() {
    }

    @Override
    public void mute() {
    }

    @Override
    public void unmute() {
    }

    @Override
    public void setAudioAttributes(@NonNull AudioAttributes audioAttributes, boolean handleAudioFocus) {
    }

    @NonNull
    @Override
    public Player.Commands getAvailableCommands() {
        return new Player.Commands.Builder()
                .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_PREPARE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_BACK,
                        Player.COMMAND_SEEK_FORWARD,
                        Player.COMMAND_SET_SPEED_AND_PITCH,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_TRACKS,
                        Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                        Player.COMMAND_GET_TEXT,
                        Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                        Player.COMMAND_SET_VIDEO_SURFACE,
                        Player.COMMAND_SET_VOLUME,
                        Player.COMMAND_GET_VOLUME,
                        Player.COMMAND_RELEASE)
                .build();
    }

    // unsupported playlist

    @Override
    public void addMediaItems(int index, @NonNull List<MediaItem> mediaItems) {
    }

    @Override
    public void moveMediaItems(int fromIndex, int toIndex, int newIndex) {
    }

    @Override
    public void removeMediaItems(int fromIndex, int toIndex) {
    }

    @Override
    public void replaceMediaItems(int fromIndex, int toIndex, @NonNull List<MediaItem> mediaItems) {
    }

    private static final class SingleItemTimeline extends Timeline {

        private final MediaItem mediaItem;
        private final long durationUs;

        SingleItemTimeline(MediaItem mediaItem, long durationUs) {
            this.mediaItem = mediaItem;
            this.durationUs = durationUs;
        }

        @Override
        public int getWindowCount() {
            return 1;
        }

        @NonNull
        @Override
        public Window getWindow(int windowIndex, @NonNull Window window, long defaultPositionProjectionUs) {
            window.set(Window.SINGLE_WINDOW_UID, mediaItem, null, C.TIME_UNSET, C.TIME_UNSET,
                    C.TIME_UNSET, true, durationUs != C.TIME_UNSET, null, 0, durationUs, 0, 0, 0);
            return window;
        }

        @Override
        public int getPeriodCount() {
            return 1;
        }

        @NonNull
        @Override
        public Period getPeriod(int periodIndex, @NonNull Period period, boolean setIds) {
            period.set(null, null, 0, durationUs, 0);
            return period;
        }

        @Override
        public int getIndexOfPeriod(@NonNull Object uid) {
            return 0;
        }

        @NonNull
        @Override
        public Object getUidOfPeriod(int periodIndex) {
            return 0;
        }
    }
}
