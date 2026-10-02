package com.brouken.player;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.ui.AspectRatioFrameLayout;

import com.brouken.player.osd.subtitle.SubtitleEdgeType;
import com.brouken.player.osd.subtitle.SubtitleTypeface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class Prefs {

    private static final String PREF_KEY_MEDIA_URI = "mediaUri";
    private static final String PREF_KEY_MEDIA_TYPE = "mediaType";
    private static final String PREF_KEY_BRIGHTNESS = "brightness";
    private static final String PREF_KEY_FIRST_RUN = "firstRun";
    private static final String PREF_KEY_SUBTITLE_URI = "subtitleUri";
    private static final String PREF_KEY_SUBTITLE_URIS = "subtitleUris";
    private static final int MAX_SUBTITLES = 8;

    private static final String PREF_KEY_AUDIO_TRACK_ID = "audioTrackId";
    private static final String PREF_KEY_SUBTITLE_TRACK_ID = "subtitleTrackId";
    private static final String PREF_KEY_RESIZE_MODE = "resizeMode";
    private static final String PREF_KEY_ORIENTATION = "orientation";
    private static final String PREF_KEY_SCALE = "scale";
    private static final String PREF_KEY_SCOPE_URI = "scopeUri";
    private static final String PREF_KEY_SCOPE_URIS = "scopeUris";
    private static final String PREF_KEY_ASK_SCOPE = "askScope";
    private static final String PREF_KEY_AUTO_PIP = "autoPiP";
    private static final String PREF_KEY_AUTO_NEXT = "autoPlayNext";
    private static final String PREF_KEY_BACKGROUND_AUDIO = "backgroundAudio";
    private static final String PREF_KEY_ASK_RESUME = "askResume";
    private static final String PREF_KEY_PLAYBACK_ENGINE = "playbackEngine";
    private static final String PREF_KEY_ADAPTIVE_BUFFERING = "adaptiveBuffering";
    private static final String PREF_KEY_DOUBLE_TAP_SEEK = "doubleTapSeekSeconds";
    private static final String PREF_KEY_TUNNELING = "tunneling";
    private static final String PREF_KEY_SKIP_SILENCE = "skipSilence";
    private static final String PREF_KEY_VOLUME_BOOST = "volumeBoost";
    private static final String PREF_KEY_KEEP_SCREEN_ON = "keepScreenOn";
    private static final String PREF_KEY_FRAMERATE_MATCHING = "frameRateMatching";
    private static final String PREF_KEY_REPEAT_TOGGLE = "repeatToggle";
    private static final String PREF_KEY_SPEED = "speed";
    private static final String PREF_KEY_FILE_ACCESS = "fileAccess";
    private static final String PREF_KEY_DECODER_PRIORITY = "decoderPriority";
    private static final String PREF_KEY_MAP_DV7 = "mapDV7ToHevc";
    private static final String PREF_KEY_LANGUAGE_AUDIO = "languageAudio";
    static final String PREF_KEY_SUBTITLE_STYLE_EMBEDDED = "subtitleStyleEmbedded";
    static final String PREF_KEY_SUBTITLE_TYPEFACE = "subtitleTypeface";
    static final String PREF_KEY_SUBTITLE_VERTICAL_POSITION = "subtitleVerticalPosition";
    static final String PREF_KEY_SUBTITLE_SIZE = "subtitleSize";
    static final String PREF_KEY_SUBTITLE_EDGE_TYPE = "subtitleEdgeType";
    static final String PREF_KEY_SUBTITLE_CUSTOM_FONT_ENABLED = "subtitleCustomFontEnabled";
    static final String PREF_KEY_SUBTITLE_CUSTOM_FONT_NAME = "subtitleCustomFontName";
    private static final String PREF_KEY_SUBTITLE_DELAY_MAP = "subtitleDelayMap";
    private static final String PREF_KEY_AUDIO_DELAY_MAP = "audioDelayMap";
    private static final String PREF_KEY_SPEED_MAP = "speedMap";

    static final String SUBTITLE_CUSTOM_FONT_DIR = "fonts";
    static final String SUBTITLE_CUSTOM_FONT_FILE_NAME = "custom_subtitle_font";

    private static final int MAX_SUBTITLE_DELAY_ENTRIES = 25;

    public static final String TRACK_DEFAULT = "default";
    public static final String TRACK_DEVICE = "device";

    final Context mContext;
    final SharedPreferences mSharedPreferences;

    public Uri mediaUri;
    public Uri subtitleUri;
    public final java.util.List<Uri> subtitleUris = new java.util.ArrayList<>();
    // scopeUri is the latest grant; scopeUris holds every granted folder
    public Uri scopeUri;
    public final java.util.List<Uri> scopeUris = new java.util.ArrayList<>();
    public String mediaType;
    private int currentVideoHeight = 0;
    public int resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
    public Utils.Orientation orientation = Utils.Orientation.LANDSCAPE;
    public float scale = 1.f;
    public float speed = 1.f;

    public String subtitleTrackId;
    public String audioTrackId;

    public int brightness = -1;
    public boolean firstRun = true;
    public boolean askScope = true;
    public boolean autoPiP = false;
    public boolean autoPlayNext = false;
    public boolean backgroundAudio = false;
    public boolean askResume = true;
    public boolean adaptiveBuffering = true;
    public String playbackEngine = "auto";
    public int doubleTapSeekSeconds = 10;

    public boolean tunneling = false;
    public boolean skipSilence = false;
    public boolean volumeBoost = false;
    public boolean keepScreenOn = false;
    public boolean frameRateMatching = false;
    public boolean repeatToggle = false;
    public String fileAccess = "home";
    public int decoderPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
    public boolean mapDV7ToHevc = false;
    public String languageAudio = TRACK_DEVICE;
    public boolean subtitleStyleEmbedded = true;
    public int subtitleVerticalPosition = 0;
    public int subtitleSize = 0;
    private String subtitleEngine = "media3";
    public SubtitleEdgeType subtitleEdgeType = SubtitleEdgeType.Default;
    public SubtitleTypeface subtitleTypeface = SubtitleTypeface.Regular;
    public boolean subtitleCustomFontEnabled;
    public String subtitleCustomFontName;

    private LinkedHashMap positions;

    // slack for trailing black frames and engines that report the end early
    private static final long FINISHED_MS = 3_000;
    private final LinkedHashMap<String, Integer> subtitleDelayMap = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> audioDelayMap = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> speedMap = new LinkedHashMap<>();

    public boolean persistentMode = true;
    public long nonPersitentPosition = -1L;

    public Prefs(Context context) {
        mContext = context;
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        loadSavedPreferences();
        loadPositions();
    }

    private static <T extends Enum<T>> T valueOfEnum(@NonNull Class<T> clazz, @Nullable String name, @NonNull T defaultValue) {
        if (name == null) return defaultValue;
        try {
            return Enum.valueOf(clazz, name);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    private void loadSavedPreferences() {
        if (mSharedPreferences.contains(PREF_KEY_MEDIA_URI))
            mediaUri = Uri.parse(mSharedPreferences.getString(PREF_KEY_MEDIA_URI, null));
        if (mSharedPreferences.contains(PREF_KEY_MEDIA_TYPE))
            mediaType = mSharedPreferences.getString(PREF_KEY_MEDIA_TYPE, null);
        brightness = mSharedPreferences.getInt(PREF_KEY_BRIGHTNESS, brightness);
        firstRun = mSharedPreferences.getBoolean(PREF_KEY_FIRST_RUN, firstRun);
        if (mSharedPreferences.contains(PREF_KEY_SUBTITLE_URI))
            subtitleUri = Uri.parse(mSharedPreferences.getString(PREF_KEY_SUBTITLE_URI, null));
        loadSubtitleUris();
        if (mSharedPreferences.contains(PREF_KEY_AUDIO_TRACK_ID))
            audioTrackId = mSharedPreferences.getString(PREF_KEY_AUDIO_TRACK_ID, audioTrackId);
        if (mSharedPreferences.contains(PREF_KEY_SUBTITLE_TRACK_ID))
            subtitleTrackId = mSharedPreferences.getString(PREF_KEY_SUBTITLE_TRACK_ID, subtitleTrackId);
        if (mSharedPreferences.contains(PREF_KEY_RESIZE_MODE))
            resizeMode = mSharedPreferences.getInt(PREF_KEY_RESIZE_MODE, resizeMode);
        orientation = Utils.Orientation.fromValue(
                mSharedPreferences.getInt(PREF_KEY_ORIENTATION, orientation.value));
        scale = mSharedPreferences.getFloat(PREF_KEY_SCALE, scale);
        if (mSharedPreferences.contains(PREF_KEY_SCOPE_URI))
            scopeUri = Uri.parse(mSharedPreferences.getString(PREF_KEY_SCOPE_URI, null));
        loadScopes();
        askScope = mSharedPreferences.getBoolean(PREF_KEY_ASK_SCOPE, askScope);
        speed = mSharedPreferences.getFloat(PREF_KEY_SPEED, speed);
        loadUserPreferences();
        loadDelays(subtitleDelayMap, PREF_KEY_SUBTITLE_DELAY_MAP);
        loadDelays(audioDelayMap, PREF_KEY_AUDIO_DELAY_MAP);
        loadDelays(speedMap, PREF_KEY_SPEED_MAP);
        loadDelays(aspectMap, PREF_KEY_ASPECT_MAP);
        migrateAspectStep();
        migrateMpvSubtitleSize();
        loadSubtitleLabels();
    }

    static final String PREF_KEY_MPV_SIZE_MOVED = "subtitleSizeMpvMoved";
    static final String PREF_KEY_SUBTITLE_SIZE_MPV = PREF_KEY_SUBTITLE_SIZE + "_mpv";

    // an mpv size from the old scale, as the step that draws the same size now
    static int mpvSizeFrom41(final int chosen) {
        return chosen == 0 ? 0
                : clampSubtitleSize(com.brouken.player.mpv.MpvSubtitleScale.stepDrawnLike41(chosen));
    }

    // runs once: converts a chosen mpv size to the corrected scale
    private void migrateMpvSubtitleSize() {
        if (mSharedPreferences.getBoolean(PREF_KEY_MPV_SIZE_MOVED, false)) {
            return;
        }
        final SharedPreferences.Editor editor = mSharedPreferences.edit()
                .putBoolean(PREF_KEY_MPV_SIZE_MOVED, true);
        final int chosen = mSharedPreferences.getInt(PREF_KEY_SUBTITLE_SIZE_MPV, 0);
        if (chosen != 0) {
            final int moved = mpvSizeFrom41(chosen);
            editor.putInt(PREF_KEY_SUBTITLE_SIZE_MPV, moved);
            if ("mpv".equals(subtitleEngine)) {
                subtitleSize = moved;
            }
        }
        editor.apply();
    }

    public void loadUserPreferences() {
        autoPiP = mSharedPreferences.getBoolean(PREF_KEY_AUTO_PIP, autoPiP);
        autoPlayNext = mSharedPreferences.getBoolean(PREF_KEY_AUTO_NEXT, autoPlayNext);
        backgroundAudio = mSharedPreferences.getBoolean(PREF_KEY_BACKGROUND_AUDIO, backgroundAudio);
        askResume = mSharedPreferences.getBoolean(PREF_KEY_ASK_RESUME, askResume);
        adaptiveBuffering = mSharedPreferences.getBoolean(PREF_KEY_ADAPTIVE_BUFFERING, adaptiveBuffering);
        playbackEngine = mSharedPreferences.getString(PREF_KEY_PLAYBACK_ENGINE, playbackEngine);
        doubleTapSeekSeconds = mSharedPreferences.getInt(PREF_KEY_DOUBLE_TAP_SEEK, doubleTapSeekSeconds);
        tunneling = mSharedPreferences.getBoolean(PREF_KEY_TUNNELING, tunneling);
        skipSilence = mSharedPreferences.getBoolean(PREF_KEY_SKIP_SILENCE, skipSilence);
        volumeBoost = mSharedPreferences.getBoolean(PREF_KEY_VOLUME_BOOST, volumeBoost);
        keepScreenOn = mSharedPreferences.getBoolean(PREF_KEY_KEEP_SCREEN_ON, keepScreenOn);
        frameRateMatching = mSharedPreferences.getBoolean(PREF_KEY_FRAMERATE_MATCHING, frameRateMatching);
        repeatToggle = mSharedPreferences.getBoolean(PREF_KEY_REPEAT_TOGGLE, repeatToggle);
        fileAccess = mSharedPreferences.getString(PREF_KEY_FILE_ACCESS, fileAccess);
        decoderPriority = Integer.parseInt(mSharedPreferences.getString(PREF_KEY_DECODER_PRIORITY, String.valueOf(decoderPriority)));
        mapDV7ToHevc = mSharedPreferences.getBoolean(PREF_KEY_MAP_DV7, mapDV7ToHevc);
        languageAudio = mSharedPreferences.getString(PREF_KEY_LANGUAGE_AUDIO, languageAudio);
        subtitleStyleEmbedded = mSharedPreferences.getBoolean(PREF_KEY_SUBTITLE_STYLE_EMBEDDED, subtitleStyleEmbedded);
        subtitleVerticalPosition = getSubtitleVerticalPositionForVideoHeight(currentVideoHeight);
        subtitleSize = clampSubtitleSize(mSharedPreferences.getInt(subtitleSizeKey(), 0));
        subtitleEdgeType = valueOfEnum(SubtitleEdgeType.class, mSharedPreferences.getString(PREF_KEY_SUBTITLE_EDGE_TYPE, null), subtitleEdgeType);
        subtitleTypeface = valueOfEnum(SubtitleTypeface.class, mSharedPreferences.getString(PREF_KEY_SUBTITLE_TYPEFACE, null), subtitleTypeface);
        subtitleCustomFontEnabled = mSharedPreferences.getBoolean(PREF_KEY_SUBTITLE_CUSTOM_FONT_ENABLED, subtitleCustomFontEnabled);
        subtitleCustomFontName = mSharedPreferences.getString(PREF_KEY_SUBTITLE_CUSTOM_FONT_NAME, subtitleCustomFontName);
    }

    public void updateMedia(final Context context, final Uri uri, final String type) {
        mediaUri = uri;
        mediaType = type;
        updateSubtitle(null);
        updateMeta(null, null, AspectRatioFrameLayout.RESIZE_MODE_FIT, 1.f, 1.f);
        currentVideoHeight = 0;
        subtitleVerticalPosition = getSubtitleVerticalPositionForVideoHeight(currentVideoHeight);

        if (mediaType != null && mediaType.endsWith("/*")) {
            mediaType = null;
        }

        if (mediaType == null) {
            if (ContentResolver.SCHEME_CONTENT.equals(mediaUri.getScheme())) {
                mediaType = context.getContentResolver().getType(mediaUri);
            }
        }

        // every media change passes through here
        History.record(mSharedPreferences, mediaUri, mediaType);

        // saved even in non-persistent mode; only the position belongs to the launcher
        final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        if (mediaUri == null)
            sharedPreferencesEditor.remove(PREF_KEY_MEDIA_URI);
        else
            sharedPreferencesEditor.putString(PREF_KEY_MEDIA_URI, mediaUri.toString());
        if (mediaType == null)
            sharedPreferencesEditor.remove(PREF_KEY_MEDIA_TYPE);
        else
            sharedPreferencesEditor.putString(PREF_KEY_MEDIA_TYPE, mediaType);
        sharedPreferencesEditor.apply();
    }

    private void loadSubtitleUris() {
        subtitleUris.clear();
        final String stored = mSharedPreferences.getString(PREF_KEY_SUBTITLE_URIS, null);
        if (stored == null) {
            if (subtitleUri != null) {
                subtitleUris.add(subtitleUri);
            }
            return;
        }
        try {
            final JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length(); i++) {
                final String value = array.optString(i, "");
                if (!value.isEmpty()) {
                    subtitleUris.add(Uri.parse(value));
                }
            }
        } catch (JSONException e) {
            // corrupt list: fall back to the selected subtitle
            if (subtitleUri != null) {
                subtitleUris.add(subtitleUri);
            }
        }
    }
    public void updateSubtitle(final Uri uri) {
        subtitleUri = uri;
        subtitleTrackId = null;

        if (uri == null) {
            subtitleUris.clear();
        } else {
            subtitleUris.remove(uri);
            subtitleUris.add(uri);
            while (subtitleUris.size() > MAX_SUBTITLES) {
                subtitleUris.remove(0);
            }
        }

        if (persistentMode) {
            final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
            if (uri == null) {
                sharedPreferencesEditor.remove(PREF_KEY_SUBTITLE_URI);
                sharedPreferencesEditor.remove(PREF_KEY_SUBTITLE_URIS);
            } else {
                sharedPreferencesEditor.putString(PREF_KEY_SUBTITLE_URI, uri.toString());
                final JSONArray array = new JSONArray();
                for (final Uri each : subtitleUris) {
                    array.put(each.toString());
                }
                sharedPreferencesEditor.putString(PREF_KEY_SUBTITLE_URIS, array.toString());
            }
            sharedPreferencesEditor.remove(PREF_KEY_SUBTITLE_TRACK_ID);
            sharedPreferencesEditor.apply();
        }
        if (uri == null) {
            subtitleLabels.clear();
        }
        if (persistentMode) {
            saveSubtitleLabels();
        }
    }
    public void updatePosition(final long position) {
        updatePosition(position, 0L);
    }

    // a film watched to the end is saved as 0; a duration of 0 means unknown
    public void updatePosition(final long position, final long duration) {
        if (mediaUri == null)
            return;

        while (positions.size() > 100)
            positions.remove(positions.keySet().toArray()[0]);

        // also kept in the player's own list so a launcher's film can be resumed here
        positions.put(mediaUri.toString(), watchedThrough(position, duration) ? 0L : position);
        savePositions();
        if (!persistentMode) {
            nonPersitentPosition = position;
        }
    }

    // capped at half the duration so a very short clip is not counted as finished
    static boolean watchedThrough(final long position, final long duration) {
        if (duration <= 0) {
            return false;
        }
        return position >= duration - Math.min(FINISHED_MS, duration / 2);
    }

    public void updateBrightness(final int brightness) {
        if (brightness >= -1) {
            this.brightness = brightness;
            final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
            sharedPreferencesEditor.putInt(PREF_KEY_BRIGHTNESS, brightness);
            sharedPreferencesEditor.apply();
        }
    }

    public void markFirstRun() {
        this.firstRun = false;
        final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(PREF_KEY_FIRST_RUN, false);
        sharedPreferencesEditor.apply();
    }

    public void markScopeAsked() {
        this.askScope = false;
        final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(PREF_KEY_ASK_SCOPE, false);
        sharedPreferencesEditor.apply();
    }

    private void savePositions() {
        try {
            FileOutputStream fos = mContext.openFileOutput("positions", Context.MODE_PRIVATE);
            ObjectOutputStream os = new ObjectOutputStream(fos);
            os.writeObject(positions);
            os.close();
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadPositions() {
        try {
            FileInputStream fis = mContext.openFileInput("positions");
            ObjectInputStream is = new ObjectInputStream(fis);
            positions = (LinkedHashMap) is.readObject();
            is.close();
            fis.close();
        } catch (Exception e) {
            e.printStackTrace();
            positions = new LinkedHashMap(10);
        }
    }

    public long getPosition() {
        if (!persistentMode && nonPersitentPosition >= 0) {
            return nonPersitentPosition;
        }

        Object val = positions.get(mediaUri.toString());
        if (val != null)
            return (long) val;

        // Return position for uri from limited scope (loaded after using Next action)
        if (ContentResolver.SCHEME_CONTENT.equals(mediaUri.getScheme())) {
            final String searchPath = SubtitleUtils.getTrailPathFromUri(mediaUri);
            if (searchPath == null || searchPath.length() < 1)
                return 0L;
            final Set<String> keySet = positions.keySet();
            final Object[] keys = keySet.toArray();
            for (int i = keys.length; i > 0; i--) {
                final String key = (String) keys[i - 1];
                final Uri uri = Uri.parse(key);
                if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                    final String keyPath = SubtitleUtils.getTrailPathFromUri(uri);
                    if (searchPath.equals(keyPath)) {
                        return (long) positions.get(key);
                    }
                }
            }
        }

        return 0L;
    }

    public void updateOrientation() {
        final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putInt(PREF_KEY_ORIENTATION, orientation.value);
        sharedPreferencesEditor.apply();
    }

    public void updateMeta(final String audioTrackId, final String subtitleTrackId, final int resizeMode, final float scale, final float speed) {
        this.audioTrackId = audioTrackId;
        this.subtitleTrackId = subtitleTrackId;
        this.resizeMode = resizeMode;
        this.scale = scale;
        this.speed = speed;
        if (persistentMode) {
            final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
            if (audioTrackId == null)
                sharedPreferencesEditor.remove(PREF_KEY_AUDIO_TRACK_ID);
            else
                sharedPreferencesEditor.putString(PREF_KEY_AUDIO_TRACK_ID, audioTrackId);
            if (subtitleTrackId == null)
                sharedPreferencesEditor.remove(PREF_KEY_SUBTITLE_TRACK_ID);
            else
                sharedPreferencesEditor.putString(PREF_KEY_SUBTITLE_TRACK_ID, subtitleTrackId);
            sharedPreferencesEditor.putInt(PREF_KEY_RESIZE_MODE, resizeMode);
            sharedPreferencesEditor.putFloat(PREF_KEY_SCALE, scale);
            sharedPreferencesEditor.putFloat(PREF_KEY_SPEED, speed);
            sharedPreferencesEditor.apply();
        }
    }

    public void updateScope(final Uri uri) {
        scopeUri = uri;
        final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        if (uri == null)
            sharedPreferencesEditor.remove(PREF_KEY_SCOPE_URI);
        else
            sharedPreferencesEditor.putString(PREF_KEY_SCOPE_URI, uri.toString());
        sharedPreferencesEditor.apply();

        if (uri != null) {
            scopeUris.remove(uri);
            scopeUris.add(0, uri);
            saveScopes();
        }
    }

    // the caller releases the system permission grant
    public void removeScope(final Uri uri) {
        if (uri == null) {
            return;
        }
        scopeUris.remove(uri);
        saveScopes();
        if (uri.equals(scopeUri)) {
            // keep scopeUri pointing at a folder that is still granted
            updateScope(scopeUris.isEmpty() ? null : scopeUris.get(0));
        }
    }

    private void loadScopes() {
        scopeUris.clear();
        final String saved = mSharedPreferences.getString(PREF_KEY_SCOPE_URIS, null);
        if (saved != null && !saved.isEmpty()) {
            try {
                final org.json.JSONArray all = new org.json.JSONArray(saved);
                for (int i = 0; i < all.length(); i++) {
                    final String each = all.optString(i, null);
                    if (each != null && !each.isEmpty()) {
                        final Uri uri = Uri.parse(each);
                        if (!scopeUris.contains(uri)) {
                            scopeUris.add(uri);
                        }
                    }
                }
            } catch (org.json.JSONException e) {
                // unreadable list: the single folder below still works
            }
        }
        // migrate the old single folder into the list
        if (scopeUri != null && !scopeUris.contains(scopeUri)) {
            scopeUris.add(0, scopeUri);
            saveScopes();
        }
    }

    private void saveScopes() {
        final org.json.JSONArray all = new org.json.JSONArray();
        for (final Uri uri : scopeUris) {
            all.put(uri.toString());
        }
        mSharedPreferences.edit().putString(PREF_KEY_SCOPE_URIS, all.toString()).apply();
    }

    public void updateSubtitleVerticalPosition(final int subtitleVerticalPosition) {
        this.subtitleVerticalPosition = clampSubtitlePosition(subtitleVerticalPosition,
                subtitlePositionMin());
        mSharedPreferences.edit()
                .putInt(PREF_KEY_SUBTITLE_VERTICAL_POSITION + engineSuffix(),
                        this.subtitleVerticalPosition)
                .apply();
    }

    // position does not depend on height; kept for existing callers
    public boolean refreshSubtitleVerticalPositionForVideoHeight(int videoHeight) {
        this.currentVideoHeight = videoHeight;
        return false;
    }

    private int getSubtitleVerticalPositionForVideoHeight(int videoHeight) {
        return clampSubtitlePosition(mSharedPreferences.getInt(
                PREF_KEY_SUBTITLE_VERTICAL_POSITION + engineSuffix(), 0), subtitlePositionMin());
    }

    public static final int SUBTITLE_SIZE_MIN = -30;
    public static final int SUBTITLE_SIZE_MAX = 50;
    public static final int SUBTITLE_POSITION_MIN = -8;
    public static final int SUBTITLE_POSITION_MAX = 80;
    // mpv keeps its own 22/720 bottom margin; -13 puts its text on the screen edge
    public static final int SUBTITLE_POSITION_MIN_MPV = -13;

    public static int clampSubtitleSize(final int size) {
        return Math.max(SUBTITLE_SIZE_MIN, Math.min(SUBTITLE_SIZE_MAX, size));
    }

    public static int clampSubtitlePosition(final int position) {
        return clampSubtitlePosition(position, SUBTITLE_POSITION_MIN);
    }

    public static int clampSubtitlePosition(final int position, final int min) {
        return Math.max(min, Math.min(SUBTITLE_POSITION_MAX, position));
    }

    public int subtitlePositionMin() {
        return "mpv".equals(subtitleEngine) ? SUBTITLE_POSITION_MIN_MPV : SUBTITLE_POSITION_MIN;
    }

    // each engine keeps its own size and position; Media3 uses the unsuffixed keys
    private String engineSuffix() {
        return "mpv".equals(subtitleEngine) ? "_mpv" : "";
    }

    private String subtitleSizeKey() {
        return PREF_KEY_SUBTITLE_SIZE + engineSuffix();
    }

    public boolean setSubtitleEngine(final String engine) {
        final String normalized = "mpv".equals(engine) ? "mpv" : "media3";
        if (normalized.equals(subtitleEngine)) {
            return false;
        }
        subtitleEngine = normalized;
        subtitleSize = clampSubtitleSize(mSharedPreferences.getInt(subtitleSizeKey(), 0));
        subtitleVerticalPosition = getSubtitleVerticalPositionForVideoHeight(currentVideoHeight);
        return true;
    }

    public void updateSubtitleSize(final int subtitleSize) {
        this.subtitleSize = clampSubtitleSize(subtitleSize);
        final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putInt(subtitleSizeKey(), this.subtitleSize);
        sharedPreferencesEditor.apply();
    }

    // only used to tell films apart where the address cannot (see FilmKey)
    @Nullable
    public String mediaTitle;

    public void setMediaTitle(@Nullable final String title) {
        mediaTitle = title == null || title.trim().isEmpty() ? null : title.trim();
    }

    @Nullable
    public String filmKey() {
        return FilmKey.of(mContext, mediaUri, mediaTitle);
    }

    @Nullable
    private String filmKeyFor(@Nullable final Uri uri) {
        if (uri == null) {
            return null;
        }
        return FilmKey.of(mContext, uri, uri.equals(mediaUri) ? mediaTitle : null);
    }

    private static final String PREF_KEY_ASPECT_MAP = "aspectMap";
    private static final int MAX_ASPECT_ENTRIES = 200;
    private final LinkedHashMap<String, Integer> aspectMap = new LinkedHashMap<>();

    public int aspectStep(final int count) {
        final String key = filmKey();
        final Integer stored = key == null ? null : aspectMap.get(key);
        return stored != null && stored >= 0 && stored < count ? stored : 0;
    }

    public void updateAspectStep(final int step) {
        final String key = filmKey();
        if (key == null) {
            return;
        }
        aspectMap.remove(key);
        if (step != 0) {
            aspectMap.put(key, step);
        }
        while (aspectMap.size() > MAX_ASPECT_ENTRIES) {
            aspectMap.remove(aspectMap.keySet().iterator().next());
        }
        saveDelays(aspectMap, PREF_KEY_ASPECT_MAP);
    }

    // moves the old single-slot aspect setting into the per-film map
    private void migrateAspectStep() {
        final String uri = mSharedPreferences.getString("aspectStepUri", null);
        final int step = mSharedPreferences.getInt("aspectStep", 0);
        if (mSharedPreferences.contains("aspectStepUri") || mSharedPreferences.contains("aspectStep")) {
            if (uri != null && step != 0) {
                final String key = FilmKey.of(mContext, Uri.parse(uri), null);
                if (key != null && !aspectMap.containsKey(key)) {
                    aspectMap.put(key, step);
                    saveDelays(aspectMap, PREF_KEY_ASPECT_MAP);
                }
            }
            mSharedPreferences.edit().remove("aspectStepUri").remove("aspectStep").apply();
        }
    }

    private static final String PREF_KEY_SUBTITLE_LABELS = "subtitleLabels";

    private final Map<String, String> subtitleLabels = new LinkedHashMap<>();

    @Nullable
    public String subtitleLabel(@Nullable final Uri uri) {
        return uri == null ? null : subtitleLabels.get(uri.toString());
    }

    public void putSubtitleLabel(@Nullable final Uri uri, @Nullable final String label) {
        if (uri == null || label == null || label.trim().isEmpty()) {
            return;
        }
        subtitleLabels.put(uri.toString(), label.trim());
        saveSubtitleLabels();
    }

    private void loadSubtitleLabels() {
        subtitleLabels.clear();
        try {
            final String stored = mSharedPreferences.getString(PREF_KEY_SUBTITLE_LABELS, null);
            if (stored == null) {
                return;
            }
            final JSONObject object = new JSONObject(stored);
            final java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                subtitleLabels.put(key, object.optString(key));
            }
        } catch (JSONException e) {
            Log.w(Utils.TAG, e);
        }
    }

    private void saveSubtitleLabels() {
        final JSONObject object = new JSONObject();
        try {
            for (final Uri uri : subtitleUris) {
                final String label = subtitleLabels.get(uri.toString());
                if (label != null) {
                    object.put(uri.toString(), label);
                }
            }
        } catch (JSONException e) {
            return;
        }
        mSharedPreferences.edit().putString(PREF_KEY_SUBTITLE_LABELS, object.toString()).apply();
    }

    public void updateSubtitleDelay(final int subtitleDelayMs) {
        if (mediaUri != null) {
            updateDelayForUri(subtitleDelayMap, PREF_KEY_SUBTITLE_DELAY_MAP, mediaUri, subtitleDelayMs);
        }
    }

    public int getSubtitleDelayForUri(@Nullable Uri uri) {
        return getDelayForUri(subtitleDelayMap, uri);
    }

    public void updateAudioDelay(final int audioDelayMs) {
        if (mediaUri != null) {
            updateDelayForUri(audioDelayMap, PREF_KEY_AUDIO_DELAY_MAP, mediaUri, audioDelayMs);
        }
    }

    public int getAudioDelayForUri(@Nullable Uri uri) {
        return getDelayForUri(audioDelayMap, uri);
    }

    // stored in hundredths because the map holds integers
    public void updateSpeedForUri(final float speed) {
        if (mediaUri != null) {
            updateDelayForUri(speedMap, PREF_KEY_SPEED_MAP, mediaUri, Math.round(speed * 100));
        }
    }

    public float speedForUri(@Nullable Uri uri) {
        final int hundredths = getDelayForUri(speedMap, uri);
        if (hundredths <= 0) {
            return speed;
        }
        return hundredths / 100f;
    }

    public boolean hasSpeedForUri(@Nullable Uri uri) {
        return getDelayForUri(speedMap, uri) > 0;
    }

    // keyed by FilmKey, falling back to the old file-name key
    private int getDelayForUri(final LinkedHashMap<String, Integer> map, @Nullable Uri uri) {
        final String key = filmKeyFor(uri);
        Integer delay = key == null ? null : map.get(key);
        if (delay == null) {
            final String legacy = getDelayKeyFromUri(uri);
            delay = legacy == null ? null : map.get(legacy);
        }
        return delay != null ? delay : 0;
    }

    private void updateDelayForUri(final LinkedHashMap<String, Integer> map, final String prefKey,
                                   @NonNull Uri uri, int delayMs) {
        final String key = filmKeyFor(uri);
        if (key == null) {
            return;
        }
        final String legacy = getDelayKeyFromUri(uri);
        if (legacy != null) {
            map.remove(legacy);
        }
        map.remove(key);
        map.put(key, delayMs);
        while (map.size() > MAX_SUBTITLE_DELAY_ENTRIES) {
            String oldestKey = map.keySet().iterator().next();
            map.remove(oldestKey);
        }
        saveDelays(map, prefKey);
    }

    private void loadDelays(final LinkedHashMap<String, Integer> map, final String prefKey) {
        map.clear();
        String raw = mSharedPreferences.getString(prefKey, null);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String storedKey = entry.optString("name", null);
                if (storedKey == null || storedKey.isEmpty()) {
                    storedKey = entry.optString("uri", null);
                }
                String key = normalizeDelayKey(storedKey);
                if (key == null || key.isEmpty()) {
                    continue;
                }
                int delayMs = entry.optInt("delay", 0);
                map.remove(key);
                map.put(key, delayMs);
                if (map.size() >= MAX_SUBTITLE_DELAY_ENTRIES) {
                    break;
                }
            }
        } catch (JSONException e) {
            Log.w(Utils.TAG, e);
        }
    }

    private void saveDelays(final LinkedHashMap<String, Integer> map, final String prefKey) {
        JSONArray array = new JSONArray();
        try {
            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                JSONObject object = new JSONObject();
                object.put("name", entry.getKey());
                object.put("delay", entry.getValue());
                array.put(object);
            }
        } catch (JSONException e) {
            Log.w(Utils.TAG, e);
            return;
        }
        final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putString(prefKey, array.toString());
        sharedPreferencesEditor.apply();
    }

    @Nullable
    private String getDelayKeyFromUri(@Nullable Uri uri) {
        if (uri == null) {
            return null;
        }
        String fileName = Utils.getFileName(mContext, uri, true);
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        return fileName;
    }

    @Nullable
    private String normalizeDelayKey(@Nullable String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) return null;

        // Already a film key; never re-read as an address.
        if (rawKey.startsWith("f:") || rawKey.startsWith("t:") || rawKey.startsWith("u:")) {
            return rawKey;
        }

        boolean looksLikeUri = rawKey.contains("://")
                || rawKey.startsWith("file:")
                || rawKey.startsWith("content:");

        if (looksLikeUri) {
            String normalized = getDelayKeyFromUri(Uri.parse(rawKey));
            if (normalized != null) {
                return normalized;
            }
        }

        return rawKey;
    }

    public void updateSubtitleEdgeType(final SubtitleEdgeType subtitleEdgeType) {
        this.subtitleEdgeType = subtitleEdgeType;
        final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putString(PREF_KEY_SUBTITLE_EDGE_TYPE, subtitleEdgeType.name());
        sharedPreferencesEditor.apply();
    }

    public void updateSubtitleTypeface(final SubtitleTypeface subtitleTypeface) {
        this.subtitleTypeface = subtitleTypeface;
        final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putString(PREF_KEY_SUBTITLE_TYPEFACE, subtitleTypeface.name());
        sharedPreferencesEditor.apply();
    }

    public void updateSubtitleStyleEmbedded(final boolean subtitleStyleEmbedded) {
        this.subtitleStyleEmbedded = subtitleStyleEmbedded;
        final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(PREF_KEY_SUBTITLE_STYLE_EMBEDDED, subtitleStyleEmbedded);
        sharedPreferencesEditor.apply();
    }

    public void setPersistent(boolean persistentMode) {
        this.persistentMode = persistentMode;
        if (persistentMode) {
            // a launcher's position only applies to the film it came with
            nonPersitentPosition = -1L;
        }
    }
}
