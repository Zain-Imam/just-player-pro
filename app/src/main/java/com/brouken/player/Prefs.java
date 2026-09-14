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
    /*
     * The folder the player was last given, and every folder it has been given.
     *
     * There was only ever one. Granting a second silently replaced the first,
     * so a library split across two cards or two drives could never work: the
     * half you granted second was the only half the player could look in for
     * the next episode or a subtitle sitting beside the film.
     *
     * scopeUri is kept, and kept meaning what it always meant -- the most
     * recent grant -- so nothing that reads it has to change. The list is the
     * new thing, and the single one migrates into it on first load.
     */
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
    public String fileAccess = "auto";
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
    private final LinkedHashMap<String, Integer> subtitleDelayMap = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> audioDelayMap = new LinkedHashMap<>();

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
    }

    public void loadUserPreferences() {
        autoPiP = mSharedPreferences.getBoolean(PREF_KEY_AUTO_PIP, autoPiP);
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
        subtitleSize = mSharedPreferences.getInt(PREF_KEY_SUBTITLE_SIZE, subtitleSize);
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

        // Recorded here rather than at the call sites: every media change goes
        // through this method, so the history cannot quietly miss one.
        History.record(mSharedPreferences, mediaUri, mediaType);

        if (persistentMode) {
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
            // A corrupt list is not worth a crash; the selected one still works.
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
            // Bounded: a dozen subtitles on one file is already unusual, and
            // every one of them is a track the player has to open.
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
    }
    public void updatePosition(final long position) {
        if (mediaUri == null)
            return;

        while (positions.size() > 100)
            positions.remove(positions.keySet().toArray()[0]);

        if (persistentMode) {
            positions.put(mediaUri.toString(), position);
            savePositions();
        } else {
            nonPersitentPosition = position;
        }
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
        if (!persistentMode) {
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
            // Newest first: the folder just granted is the likeliest place to
            // find whatever is being played.
            scopeUris.remove(uri);
            scopeUris.add(0, uri);
            saveScopes();
        }
    }

    /** Stop looking in a folder. The system grant is released by the caller. */
    public void removeScope(final Uri uri) {
        if (uri == null) {
            return;
        }
        scopeUris.remove(uri);
        saveScopes();
        if (uri.equals(scopeUri)) {
            // Whatever is left becomes the one that answers for the old single
            // setting, so nothing reading scopeUri sees a folder that has gone.
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
                // A list that will not parse is no worse than no list; the
                // single folder below still gets the player working.
            }
        }
        // Anyone upgrading has one folder and no list.
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
        this.subtitleVerticalPosition = subtitleVerticalPosition;
        final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putInt(getSubtitleVerticalPositionKey(currentVideoHeight), subtitleVerticalPosition);
        // Also as the general answer, for video heights not seen yet.
        sharedPreferencesEditor.putInt(PREF_KEY_SUBTITLE_VERTICAL_POSITION + engineSuffix(), subtitleVerticalPosition);
        sharedPreferencesEditor.apply();
    }

    public boolean refreshSubtitleVerticalPositionForVideoHeight(int videoHeight) {
        this.currentVideoHeight = videoHeight;
        int position = getSubtitleVerticalPositionForVideoHeight(videoHeight);
        if (subtitleVerticalPosition != position) {
            subtitleVerticalPosition = position;
            return true;
        } else {
            return false;
        }
    }

    private int getSubtitleVerticalPositionForVideoHeight(int videoHeight) {
        final int fallback = mSharedPreferences.getInt(PREF_KEY_SUBTITLE_VERTICAL_POSITION + engineSuffix(), 0);
        String key = getSubtitleVerticalPositionKey(videoHeight);
        return mSharedPreferences.getInt(key, fallback);
    }

    private String getSubtitleVerticalPositionKey(int videoHeight) {
        final String base = PREF_KEY_SUBTITLE_VERTICAL_POSITION + engineSuffix();
        if (videoHeight > 0) {
            return base + "_" + videoHeight;
        } else {
            return base;
        }
    }

    // The two engines draw subtitles at different sizes and sit them in
    // different places, so a position that reads well under one is wrong under
    // the other. Each keeps its own. Media3 keeps the unsuffixed keys so
    // anything already set carries over.
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
        subtitleSize = mSharedPreferences.getInt(subtitleSizeKey(), 0);
        subtitleVerticalPosition = getSubtitleVerticalPositionForVideoHeight(currentVideoHeight);
        return true;
    }

    public void updateSubtitleSize(final int subtitleSize) {
        this.subtitleSize = subtitleSize;
        final SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putInt(subtitleSizeKey(), subtitleSize);
        sharedPreferencesEditor.apply();
    }

    public void updateSubtitleDelay(final int subtitleDelayMs) {
        if (mediaUri != null) {
            updateDelayForUri(subtitleDelayMap, PREF_KEY_SUBTITLE_DELAY_MAP, mediaUri, subtitleDelayMs);
        }
    }

    public int getSubtitleDelayForUri(@Nullable Uri uri) {
        return getDelayForUri(subtitleDelayMap, uri);
    }

    /*
     * The audio delay is remembered exactly as the subtitle delay is: by file
     * name, twenty-five files deep, in its own list.
     *
     * A film that needs its sound moved needs it moved every time it is opened
     * -- the fault is in the file, not in the sitting -- and a viewer who has
     * found the right number should never have to find it twice.
     */
    public void updateAudioDelay(final int audioDelayMs) {
        if (mediaUri != null) {
            updateDelayForUri(audioDelayMap, PREF_KEY_AUDIO_DELAY_MAP, mediaUri, audioDelayMs);
        }
    }

    public int getAudioDelayForUri(@Nullable Uri uri) {
        return getDelayForUri(audioDelayMap, uri);
    }

    private int getDelayForUri(final LinkedHashMap<String, Integer> map, @Nullable Uri uri) {
        String key = getDelayKeyFromUri(uri);
        if (key == null) {
            return 0;
        }
        Integer delay = map.get(key);
        return delay != null ? delay : 0;
    }

    private void updateDelayForUri(final LinkedHashMap<String, Integer> map, final String prefKey,
                                   @NonNull Uri uri, int delayMs) {
        String key = getDelayKeyFromUri(uri);
        if (key == null) {
            return;
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
    }
}
