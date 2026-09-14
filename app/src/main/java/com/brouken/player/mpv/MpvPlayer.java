package com.brouken.player.mpv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
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
    private static final double SUB_SIZE_DEFAULT = 0.0533;
    private static final double SUB_SIZE_STEP = 0.001;

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

    /** Whether mpv has actually opened the current file. See event(). */
    private boolean fileOpened;

    private boolean released;
    private boolean renderedFirstFrame;

    public static boolean isSupported() {
        return android.os.Build.VERSION.SDK_INT >= 26;
    }

    public MpvPlayer(final Context context, final MpvOptions options) {
        this.context = context.getApplicationContext();
        this.listeners = new ListenerSet<>(Looper.getMainLooper(), Clock.DEFAULT,
                (listener, flags) -> listener.onEvents(this, new Player.Events(flags)));

        mpv = MPVLib.create(this.context);
        options.applyTo(mpv, this.context);
        mpv.init();

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

        mpv.addObserver(this);
        // mpv says why a file would not open; the event carries only an id.
        mpv.addLogObserver(this);
    }

    private void observe(final String property, final int format) {
        if (mpv != null) {
            mpv.observeProperty(property, format);
        }
    }

    // ------------------------------------------------------------- playback

    @Override
    public void setMediaItems(@NonNull List<MediaItem> items, boolean resetPosition) {
        setMediaItems(items, 0, resetPosition ? C.TIME_UNSET : positionMs);
    }

    @Override
    public void setMediaItems(@NonNull List<MediaItem> items, int startIndex, long startPositionMs) {
        mediaItems.clear();
        mediaItems.addAll(items);

        // A single-item timeline: mpv plays one file, and so does this app.
        pendingStartPositionMs = startPositionMs;
        updateTimeline();
    }

    private long pendingStartPositionMs = C.TIME_UNSET;

    @Override
    public void prepare() {
        if (mpv == null || mediaItems.isEmpty()) {
            return;
        }
        error = null;
        fileOpened = false;
        renderedFirstFrame = false;
        setPlaybackState(Player.STATE_BUFFERING);

        // mpv decides once, as the file opens, whether it has a video output at
        // all: told to load before the surface exists it reports "Missing
        // surface pointer", gives up on the video and plays the file as audio
        // over a black screen. The load waits for the surface instead.
        if (surface == null) {
            loadWhenSurfaceReady = true;
            return;
        }
        load();
    }

    /*
     * Sidecar subtitles are added once the file is open, not before.
     *
     * "loadfile" only asks; the file is opened some time afterwards and the
     * track list is built then. A sub-add issued in between is applied to
     * nothing, and the subtitle another app handed over simply never appeared in
     * the picker on this engine — which is most of the reason for accepting one
     * in the first place.
     *
     * They are held here and added when mpv says the file is loaded.
     */
    private final List<MediaItem.SubtitleConfiguration> pendingSubtitles = new ArrayList<>();

    private void addPendingSubtitles() {
        if (mpv == null || pendingSubtitles.isEmpty()) {
            return;
        }
        for (final MediaItem.SubtitleConfiguration subtitle : pendingSubtitles) {
            final String flag =
                    (subtitle.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0 ? "select" : "auto";
            final String title = subtitle.label;
            if (title != null && !title.isEmpty()) {
                mpv.command(new String[]{"sub-add", subtitle.uri.toString(), flag, title});
            } else {
                mpv.command(new String[]{"sub-add", subtitle.uri.toString(), flag});
            }
            if (subtitle.language != null && !subtitle.language.isEmpty()) {
                final Integer count = mpv.getPropertyInt("track-list/count");
                if (count != null && count > 0) {
                    mpv.setPropertyString("track-list/" + (count - 1) + "/lang",
                            subtitle.language);
                }
            }
        }
        pendingSubtitles.clear();
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
        // Nothing has opened yet. If the end of the file arrives before the
        // file does, it never opened at all — see event().
        fileOpened = false;
        synchronized (complaints) {
            complaints.setLength(0);
        }
        mpv.command(new String[]{"loadfile", uri});

        /*
         * Subtitles handed over as sidecar files, the way the rest of the app
         * already supplies them.
         *
         * The one the launching app asked to have on is added with "select"
         * rather than "auto", which is what the other engine does with the same
         * flag: a subtitle sent by Stremio or Nuvio was appearing in the list on
         * both engines and switched on only on one of them. The name and the
         * language go with it, or the picker shows a row called Track 2.
         */
        pendingSubtitles.clear();
        pendingSubtitles.addAll(item.localConfiguration.subtitleConfigurations);

        if (pendingStartPositionMs != C.TIME_UNSET && pendingStartPositionMs > 0) {
            // `start` is a relative-time option, not a number, so it is written
            // as text — a typed write of the wrong type is simply rejected.
            set("start", String.valueOf(pendingStartPositionMs / 1000.0));
            pendingStartPositionMs = C.TIME_UNSET;
        }

        mpv.setPropertyBoolean(PROP_PAUSE, !playWhenReady);
    }

    @Override
    public void setPlayWhenReady(boolean playWhenReady) {
        if (this.playWhenReady == playWhenReady) {
            return;
        }
        this.playWhenReady = playWhenReady;
        if (mpv != null) {
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
        if (mpv == null || positionMs == C.TIME_UNSET) {
            return;
        }
        final long target = Math.max(0, positionMs);
        final long from = this.positionMs;
        this.positionMs = target;
        mpv.command(new String[]{"seek", String.valueOf(target / 1000.0),
                keyframeSeeking ? "absolute+keyframes" : "absolute"});

        final Player.PositionInfo oldPosition = positionInfo(from);
        final Player.PositionInfo newPosition = positionInfo(target);
        listeners.sendEvent(Player.EVENT_POSITION_DISCONTINUITY, listener ->
                listener.onPositionDiscontinuity(oldPosition, newPosition,
                        Player.DISCONTINUITY_REASON_SEEK));
    }

    /*
     * Land where the other engine lands.
     *
     * Media3 is told to snap to the nearest keyframe while the bar is being
     * dragged and while the arrows are seeking, because landing exactly costs a
     * decode of everything since the last keyframe and the drag stops feeling
     * attached to the finger. mpv, asked for an absolute seek, is exact — so the
     * same drag on the same file finished in two different places depending on
     * which engine was playing. It is told the same thing at the same moments.
     */
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

    // --------------------------------------------------------------- events

    @Override
    public void eventProperty(@NonNull String property) {
    }

    @Override
    public void eventProperty(@NonNull String property, long value) {
        handler.post(() -> {
            switch (property) {
                case PROP_WIDTH:
                case PROP_HEIGHT:
                    updateVideoSize();
                    break;
                case PROP_TRACK_LIST_COUNT:
                    updateTracks();
                    break;
                default:
                    break;
            }
        });
    }

    @Override
    public void eventProperty(@NonNull String property, double value) {
        handler.post(() -> {
            switch (property) {
                case PROP_TIME_POS:
                    positionMs = (long) (value * 1000);
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
        handler.post(() -> {
            switch (property) {
                case PROP_PAUSE:
                    // mpv is the authority on whether it is paused; a pause from
                    // its own end (end of file, audio focus) has to reach the UI.
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
                    setPlaybackState(value ? Player.STATE_BUFFERING : Player.STATE_READY);
                    break;
                default:
                    break;
            }
        });
    }

    @Override
    public void eventProperty(@NonNull String property, @NonNull String value) {
        handler.post(() -> {
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
        handler.post(() -> {
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
                fileOpened = true;
                addPendingSubtitles();
                updateTracks();
                updateVideoSize();
                setPlaybackState(Player.STATE_READY);
            } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
                /*
                 * The end of a file that never began is a failure, not an end.
                 *
                 * This used to report STATE_ENDED whatever had happened, and
                 * nothing ever set the error, so getPlayerError() could only
                 * ever return null. A file mpv could not open — an expired
                 * debrid link, a dead host, a 403, a path with no permission —
                 * produced no error, no dialog and no message of any kind. The
                 * player opened, named the file, and sat at 00:00 for as long
                 * as you left it. Every unopenable file on this engine behaved
                 * that way.
                 *
                 * mpv says why in its log, but the binding hands over only the
                 * event id, so the distinction is drawn from order instead: an
                 * end-file before any file-loaded means the file never opened.
                 * That is exactly the case that had nothing to show for it.
                 */
                if (!fileOpened) {
                    reportFailedToOpen();
                } else if (playbackState != Player.STATE_IDLE) {
                    setPlaybackState(Player.STATE_ENDED);
                }
            }
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART) {
                if (!isBuffering) {
                    setPlaybackState(Player.STATE_READY);
                }
                notifyFirstFrame();
            } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG) {
                updateVideoSize();
                notifyFirstFrame();
            }
        });
    }

    /**
     * Everything mpv has complained about since the file was asked for.
     *
     * The event callback carries an id and nothing else, so on its own all this
     * engine could ever report is "something went wrong". mpv does say what
     * went wrong — "HTTP error 403 Forbidden", "Permission denied", "Failed to
     * open" — it says it in its log, so the log is where it is read from.
     *
     * All of them are kept, not one. Taking the last turned a refused link into
     * a connection problem, because mpv says "HTTP error 403 Forbidden" and
     * then "Failed to open" and the summary arrives last. Taking the first was
     * no better: mpv warns "client removed during hook handling" before it has
     * even tried, so the first complaint is often about nothing at all. What
     * matters is whether a reason appears anywhere among them, so they are read
     * together and the most specific one wins.
     *
     * Written from mpv's thread, read from the main one. Capped, because a
     * stream that fails slowly can complain for a long time.
     */
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

    /**
     * Say that the file would not open, in the terms the rest of the app uses.
     *
     * The code chosen here is what decides which sentence the person watching
     * reads, so mpv's own words are matched to one. A refused request and a
     * link that has expired are the same thing to a debrid service, and that is
     * worth saying rather than "something went wrong".
     */
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
        // The decoded video is only described once it is decoding, so the track
        // list is worth re-reading now that it is.
        updateTracks();
        listeners.sendEvent(Player.EVENT_VIDEO_SIZE_CHANGED, listener ->
                listener.onVideoSizeChanged(size));
    }

    // PlayerView covers the video while it believes no video track is selected,
    // so a wrong answer here plays the film as sound over a black screen
    private void updateTracks() {
        if (mpv == null) {
            return;
        }
        final Integer count = mpv.getPropertyInt("track-list/count");
        if (count == null || count <= 0) {
            return;
        }

        // Whether what is on screen is HDR, which is a property of the
        // decoded video rather than of the track list. Media3 reports it in the
        // format and the header line reads it from there, so it goes in the
        // same place here.
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

            // Everything the track list knows about the stream, so a track
            // reads the same in the picker on this engine as on the other:
            // "English - 5.1, E-AC-3, 640 kb/s" rather than just "English".
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
            // A real mime type where the codec name gives one away, so the
            // picker can print "E-AC-3" rather than the raw ffmpeg spelling.
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
    // current-tracks is the playback state; vid/aid/sid are only the setting,
    // and read "auto" while a file is still opening
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

    // A file being played with a video track in it is being played with that
    // video track, whatever mpv has got round to reporting yet
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
        // "no" is a deliberate choice to play nothing of this kind, and is the
        // one case where reporting nothing selected is the honest answer.
        if ("no".equals(mpv.getPropertyString(property))) {
            return;
        }
        first.selected = true;
    }

    // -------------------------------------------------------------- surface

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
        // Not supported: mpv renders into a Surface, and Just Player uses a
        // SurfaceView by default for exactly the zero-copy reason mpv also wants.
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

    // ----------------------------------------------------------- properties

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
        // One item at a time; there is nothing to shuffle.
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
            /*
             * And make mpv act on it now.
             *
             * The property takes the new value immediately -- read it back and
             * it is there -- but the sound already filtered goes on being
             * played at the speed it was filtered at, and mpv only rebuilds the
             * chain when playback next restarts. Changed from the panel, which
             * pauses the film to show itself, that restart may be a dozen
             * seconds away: the viewer picks a speed, presses play, and watches
             * the film carry on at the old one.
             *
             * A seek of zero seconds is a playback restart that does not move.
             * The chain is rebuilt at the position it is already at, and the
             * new speed is what comes out of it.
             */
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
            /*
             * Ask what type the mime is, rather than reading the front of it.
             *
             * This used to test mime.startsWith("text"), which is true of
             * "text/x-ssa" and "text/vtt" and false of the one everybody
             * actually has: SubRip's mime is "application/x-subrip". So
             * choosing an .srt set nothing at all — sid was never written, the
             * picker said "Playing now" against the track you had chosen, and
             * the screen carried on showing whatever it had been showing.
             * Off worked, because that goes through disabledTrackTypes rather
             * than through an override, which is exactly the shape the bug
             * report had: "only clicking off works".
             *
             * The same was true of tx3g and PGS. getTrackType knows all of
             * them, and will know the next one too.
             */
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

        // "No subtitles" arrives as text being disabled rather than as an
        // override, and mpv's way of saying that is sid=no.
        if (!textChosen && parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            mpv.setPropertyString("sid", "no");
        }
        if (!audioChosen && parameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)) {
            mpv.setPropertyString("aid", "no");
        }
    }

    /** The size the person chose, kept so the scale can be worked out again. */
    private int subtitleSizeStep;

    /**
     * Match the other engine's subtitle size, by arithmetic rather than taste.
     *
     * Both engines express size as a share of the window they draw into, and
     * they do not agree on the share. Media3 uses SubtitleView's default text
     * fraction, 0.0533 of the view height. mpv's default sub-font-size of 55 is
     * expressed against a 720-tall window, which is 55/720 = 0.0764 of it. So
     * the same film, the same screen and the same setting give mpv text about
     * 43 per cent larger.
     *
     * That never showed while mpv was drawing into a surface cut to the shape
     * of the film, because the two were measuring different windows. Now that
     * mpv is given the whole screen — which is what lets subtitles sit on the
     * black bars — they measure the same window, and the difference is plain.
     *
     * 0.0533 / 0.0764 brings them level. A constant, deliberately: sizing by
     * how much of the screen the picture happens to occupy made the text change
     * size when the aspect changed, so a crop was noticeably larger than a fit.
     * Subtitles should not resize when you zoom the picture.
     */
    private static final double MEDIA3_PARITY = 0.0533 / (55.0 / 720.0);

    private void applySubtitleScale() {
        if (mpv == null) {
            return;
        }
        final double chosen = 1.0 + subtitleSizeStep * (SUB_SIZE_STEP / SUB_SIZE_DEFAULT);
        set("sub-scale", String.valueOf(Math.max(0.2, chosen * MEDIA3_PARITY)));
    }


    /**
     * Shape the picture, inside mpv, rather than by resizing its canvas.
     *
     * The Android side used to do all of this: an AspectRatioFrameLayout
     * measured the video surface to the shape of the film, and mpv simply
     * filled whatever it was given. Two things follow from that, and both were
     * reported as bugs.
     *
     * The black bars were not mpv's — they were the empty part of the player
     * around a surface that had been shrunk to fit — so mpv had nowhere to put
     * a subtitle except on top of the picture, whatever sub-use-margins said.
     * And changing shape resized the surface under a paused film, which mpv
     * could not redraw into until the next frame arrived, so the picture sat
     * there stretched or clipped until playback resumed.
     *
     * Now the surface covers the whole player and mpv letterboxes inside it.
     * The bars belong to mpv, which can draw subtitles on them; the shape is a
     * property it can change and redraw at once, paused or not.
     *
     * @param keepAspect   false stretches the picture to the window
     * @param panscan      0 letterboxes, 1 crops to fill
     * @param aspectOverride a forced ratio such as 1.777, or 0 for the film's own
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

    /**
     * Draw the picture again, in place, without moving.
     *
     * mpv renders frames as they arrive. Change the shape of the window while a
     * film is paused and there is no next frame to arrive, so what stays on
     * screen is the last one drawn at the old geometry — stretched, offset, or
     * with the edges of the previous shape still showing — until you press play
     * and a fresh frame corrects it. Every other player redraws immediately,
     * and so should this.
     *
     * Restating the surface size makes the video output reconfigure, which
     * covers the common case. A paused film additionally needs a frame to
     * render: an exact relative seek of zero produces one at the position it is
     * already at, which is the cheapest way to say "draw that again".
     */
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
        // The margins have just moved, so the size that was measured against
        // them has to be worked out again.
        applySubtitleScale();
    }

    public void setSubtitleStyle(final int verticalPosition, final int sizeStep,
                                 final String edgeType, final String typeface,
                                 final boolean embeddedStyles) {
        if (mpv == null) {
            return;
        }

        final double position = SUB_POS_DEFAULT - verticalPosition;
        set("sub-pos", String.valueOf(Math.max(0, Math.min(150, position))));

        // Keep subtitles on the picture, never in the black bars.
        //
        // Letting them into the margins sounded right and was not: the surface
        // this engine draws on is already sized to the picture, so anything
        // pushed past its edge is simply clipped away and the text vanishes
        // rather than moving. Media3 is now held to the same rule by measuring
        // the picture and placing the line inside it, so the slider covers the
        // same ground on both.
        /*
         * Subtitles may use the black bars.
         *
         * These were "no", which keeps every subtitle inside the picture. On a
         * letterboxed film in a fit-to-screen shape that puts the text over the
         * bottom of the image while a wide empty band sits unused beneath it —
         * and at some positions pushes it far enough down to be hard to read
         * against the picture at all.
         *
         * "yes" lets mpv lay subtitles out against the window instead of the
         * video, which is the whole screen, bars included. It is what the other
         * engine now does too, so the two agree.
         */
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



    // mpv types volume as a float; writing it as an integer was rejected outright,
    // which is why the boost did nothing under this engine
    public void setVolumePercent(final int percent) {
        if (mpv == null) {
            return;
        }
        set("volume", String.valueOf(percent));
    }

    public void addSubtitle(final android.net.Uri uri) {
        addSubtitle(uri, null);
    }

    /**
     * Add a subtitle and select it, under the name it should be known by.
     *
     * mpv's sub-add takes a title after the flags, and given one it uses that
     * instead of naming the track after the file. Which matters for a
     * downloaded subtitle: the file it was saved to may be called
     * 1321321 while the thing you picked had a release name.
     */
    public void addSubtitle(final android.net.Uri uri, @Nullable final String title) {
        if (mpv == null || uri == null) {
            return;
        }
        if (title == null || title.trim().isEmpty()) {
            mpv.command(new String[]{"sub-add", uri.toString(), "select"});
        } else {
            mpv.command(new String[]{"sub-add", uri.toString(), "select", title.trim()});
        }
    }
    public void setSubtitleDelayMs(final int delayMs) {
        if (mpv == null) {
            return;
        }
        set("sub-delay", String.valueOf(delayMs / 1000.0));
    }

    /**
     * How fast the stream is coming in, in bytes a second.
     *
     * mpv keeps this itself as the rate its cache is filling, which is the same
     * question the other engine answers by counting the bytes its data sources
     * report. Nothing is coming in on a local file, and -1 says so.
     */
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

    /**
     * Move the sound relative to the picture.
     *
     * mpv has the property outright: positive means the sound arrives later,
     * which is the same sense as the delay on the other engine and the same
     * sense as the number the viewer sees.
     */
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

    // Pause when the headphones come out, which ExoPlayer does for itself
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

    // Options are typed, and a write of the wrong type is rejected silently, so
    // everything goes out as text and mpv parses it into whatever it really is
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
        return MediaMetadata.EMPTY;
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
        return positionMs;
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

    // ------------------------------------------------- unsupported playlist

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
