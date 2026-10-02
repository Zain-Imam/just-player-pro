package com.brouken.player;

import static android.content.pm.PackageManager.FEATURE_EXPANDED_PICTURE_IN_PICTURE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.CaptioningManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerControlView;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import androidx.media3.ui.TimeBar;

import com.brouken.player.dtpv.DoubleTapPlayerView;
import com.brouken.player.dtpv.youtube.YouTubeOverlay;
import com.brouken.player.osd.OsdSettingsController;
import com.brouken.player.subtitle.CueModifier;
import com.brouken.player.subtitle.parser.EnhancedSubtitleParserFactory;
import com.brouken.player.render.DelayRenderersFactory;
import com.brouken.player.online.ApiKeys;
import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetView;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import kotlin.Unit;

public class PlayerActivity extends Activity {

    // lets a newer player screen close this one; weak so it cannot leak the player
    private static java.lang.ref.WeakReference<PlayerActivity> currentInstance;

    private PlayerListener playerListener;
    private BroadcastReceiver mReceiver;
    private AudioManager mAudioManager;
    private MediaSession mediaSession;
    // one session per id per app: a screen replacing the player must release this
    @Nullable
    private static MediaSession sharedSession;
    private DefaultTrackSelector trackSelector;
    public static LoudnessEnhancer loudnessEnhancer;

    public CustomPlayerView playerView;
    public static Player player;

    // the player this screen built; a newer screen may already own the shared one
    @Nullable
    private Player ownPlayer;

    private boolean ownsPlayer() {
        return player != null && player == ownPlayer;
    }

    @Nullable
    private com.brouken.player.engine.EngineUi engineUi;

    @Nullable
    public com.brouken.player.engine.EngineUi engineUi() {
        return ownsPlayer() ? engineUi : null;
    }
    private YouTubeOverlay youTubeOverlay;
    private OsdSettingsController osdSettingsController;
    private com.brouken.player.online.OnlineController onlineController;
    private com.brouken.player.online.OverlayCard overlayCard;
    private com.brouken.player.online.SkipController skipController;
    private Uri skipLoadedFor;
    private boolean mpvFallbackActive;

    private Object mPictureInPictureParamsBuilder;

    public Prefs mPrefs;
    public BrightnessControl mBrightnessControl;
    public static boolean haveMedia;
    private boolean videoLoading;
    public static boolean controllerVisible;
    public static boolean controllerVisibleFully;
    public static Snackbar snackbar;
    private ExoPlaybackException errorToShow;
    public static int boostLevel = 0;

    // swipe volume 0-100, finer than the device's steps; -1 until read
    public static int volumeFinePercent = -1;

    // gain 0-1 within the device step at or above the target; see applyEngineVolume
    public static float fineVolume = 1f;
    private boolean isScaling = false;
    private boolean isScaleStarting = false;
    private float scaleFactor = 1.0f;

    private static final int REQUEST_CHOOSER_VIDEO = 1;
    private static final int REQUEST_CHOOSER_SUBTITLE = 2;
    private static final int REQUEST_CHOOSER_SCOPE_DIR = 10;
    private static final int REQUEST_CHOOSER_VIDEO_MEDIASTORE = 20;
    private static final int REQUEST_CHOOSER_SUBTITLE_MEDIASTORE = 21;
    private static final int REQUEST_SETTINGS = 100;
    public static final int REQUEST_SYSTEM_CAPTIONS = 200;
    // ms; set from the preference by loadControllerTimeout
    public static int CONTROLLER_TIMEOUT = 3500;

    // seconds, as the preference stores it
    private static final int CONTROLLER_TIMEOUT_DEFAULT = 6;

    // Media3's staged hide after the timeout, measured on device; taken off the front
    private static final int CONTROLS_FADE_MS = 2280;

    static void loadControllerTimeout(final android.content.Context context) {
        final int seconds = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context)
                .getInt("controlsTimeoutSeconds", CONTROLLER_TIMEOUT_DEFAULT);
        CONTROLLER_TIMEOUT = Math.max(300, Math.max(1, seconds) * 1000 - CONTROLS_FADE_MS);
    }
    private static final String ACTION_MEDIA_CONTROL = "media_control";
    private static final String EXTRA_CONTROL_TYPE = "control_type";
    private static final int REQUEST_PLAY = 1;
    private static final int REQUEST_PAUSE = 2;
    private static final int CONTROL_TYPE_PLAY = 1;
    private static final int CONTROL_TYPE_PAUSE = 2;

    private CoordinatorLayout coordinatorLayout;
    private TextView titleView;
    private TextView metaView;
    private LinearLayout titleBar;
    private ImageButton buttonOpen;
    private ImageButton buttonBack;
    private ImageButton buttonLock;
    private ImageButton buttonPlayPause;
    private ImageButton buttonAudioTrack;
    private ImageButton buttonPiP;
    private ImageButton buttonAspectRatio;
    private ImageButton buttonRotation;
    private ImageButton exoSettings;
    private ImageButton exoPlayPause;
    private ImageButton subtitleButton;
    private TextView exoDuration;
    private ViewGroup centerControls;
    private LinearLayout cardControls;
    private boolean showRemainingTime;
    
    private String pendingSubtitleLabel;

    // saved release name for a downloaded subtitle, else the shared naming rule
    @Nullable
    private String subtitleLabelFor(final Uri uri) {
        if (uri == null) {
            return null;
        }
        final String known = mPrefs.subtitleLabel(uri);
        if (known != null && !known.trim().isEmpty()) {
            return known.trim();
        }
        return SubtitleNames.label(this, uri, null, null);
    }
    private String appliedAccent;
    // the button strip is built once; a changed setting means rebuilding the screen
    private boolean appliedRepeatToggle;
    // the spinner and its label together
    private View loadingProgressBar;
    private PlayerControlView controlView;
    private CustomDefaultTimeBar timeBar;

    private boolean restoreOrientationLock;
    private Uri deferredResumeUri;
    private String deferredResumeType;
    private boolean pendingResumeAsk;
    private boolean restorePlayState;
    private boolean restorePlayStateAllowed;
    private boolean play;
    // start the next film loaded; not set when rebuilding around the same one
    private boolean playOnLoad;
    private boolean isScrubbing;
    private boolean scrubbingNoticeable;
    private long scrubbingStart;
    public boolean frameRendered;
    private boolean alive;
    private final AtomicInteger subtitleDelayMs = new AtomicInteger();
    private boolean keptPlayingInBackground;
    private boolean capabilityAsked;
    @Nullable
    private Thumbnails thumbnails;
    private ImageView thumbnailView;
    @Nullable
    private com.brouken.player.net.NetworkSpeed networkSpeed;
    private final AtomicInteger audioDelayMs = new AtomicInteger();
    private final Runnable audioDelayApplyRunnable = this::applyAudioDelay;
    public static boolean focusPlay = false;
    private Uri nextUri;
    // set by the home screen; null for a film handed over by another app
    private String folderOfCurrent;
    private com.brouken.player.home.Neighbours.Either neighbours =
            com.brouken.player.home.Neighbours.none();
    private Thread neighboursThread;
    private static boolean isTvBox;
    public static boolean locked = false;
    private Thread nextUriThread;
    public Thread frameRateSwitchThread;

    public static boolean restoreControllerTimeout = false;
    public static boolean shortControllerTimeout = false;

    final Rational rationalLimitWide = new Rational(239, 100);
    final Rational rationalLimitTall = new Rational(100, 239);

    static final String API_POSITION = "position";
    static final String API_DURATION = "duration";
    static final String API_RETURN_RESULT = "return_result";
    static final String API_SUBS = "subs";
    static final String API_SUBS_ENABLE = "subs.enable";
    static final String API_SUBS_NAME = "subs.name";
    static final String API_TITLE = "title";
    static final String API_END_BY = "end_by";
    // further extras a launching app can add
    static final String API_HEADERS = "headers";
    static final String API_IMDB = "imdb_id";
    static final String API_TMDB = "tmdb_id";

    private final HashMap<String, String> apiHeaders = new HashMap<>();
    boolean apiAccess;
    boolean apiAccessPartial;
    String apiTitle;
    List<MediaItem.SubtitleConfiguration> apiSubs = new ArrayList<>();
    boolean intentReturnResult;
    boolean playbackFinished;

    DisplayManager displayManager;
    DisplayManager.DisplayListener displayListener;
    SubtitleFinder subtitleFinder;

    Runnable barsHider = () -> {
        if (playerView != null && !controllerVisible) {
            Utils.toggleSystemUi(PlayerActivity.this, playerView, false);
        }
    };

    final Object onBackInvokedCallback = createOnBackInvokedCallback();

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Rotate ASAP, before super/inflating to avoid glitches with activity launch animation
        mPrefs = new Prefs(this);
        // before setContentView: the player view reads it while being inflated
        loadControllerTimeout(this);
        Utils.setOrientation(this, mPrefs.orientation);
        com.brouken.player.online.SubtitleAddons.seedDefault(this);

        super.onCreate(savedInstanceState);

        // honour "Start on" here too; before setContentView so nothing is drawn
        if (savedInstanceState == null && wantsHomeInstead(getIntent())) {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            return;
        }

        Accent.apply(this);
        appliedAccent = Accent.stored(this);
        appliedRepeatToggle = mPrefs.repeatToggle;
        if (Build.VERSION.SDK_INT == 28 && Build.MANUFACTURER.equalsIgnoreCase("xiaomi") &&
                (Build.DEVICE.equalsIgnoreCase("oneday") || Build.DEVICE.equalsIgnoreCase("once"))) {
            setContentView(R.layout.activity_player_textureview);
        } else {
            setContentView(R.layout.activity_player);
        }

        if (Build.VERSION.SDK_INT >= 31) {
            Window window = getWindow();
            if (window != null) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController windowInsetsController = window.getInsetsController();
                if (windowInsetsController != null) {
                    // On Android 12 BEHAVIOR_DEFAULT allows system gestures without visible system bars
                    windowInsetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
                }
            }
        }

        // singleTop allows a second instance; close the previous so two films never play
        final PlayerActivity previous = currentInstance == null ? null : currentInstance.get();
        if (previous != null && previous != this && !previous.isFinishing()) {
            previous.finish();
        }
        currentInstance = new java.lang.ref.WeakReference<>(this);

        isTvBox = Utils.isTvBox(this);

        if (isTvBox) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }

        mPrefs.mSharedPreferences.registerOnSharedPreferenceChangeListener(preferenceListener);

        final Intent launchIntent = getIntent();
        final String action = launchIntent.getAction();
        final String type = launchIntent.getType();

        if ("com.brouken.player.action.SHORTCUT_VIDEOS".equals(action)) {
            openFile(Utils.getMoviesFolderUri());
        } else if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
            String text = launchIntent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                final Uri parsedUri = Uri.parse(text);
                if (parsedUri.isAbsolute()) {
                    mPrefs.updateMedia(this, parsedUri, null);
                    focusPlay = true;
                }
            }
        } else if (launchIntent.getData() != null) {
            openFromLaunch(launchIntent);
        }

        if (mPrefs.askResume
                && launchIntent.getData() == null
                && !Intent.ACTION_SEND.equals(action)
                && !"com.brouken.player.action.SHORTCUT_VIDEOS".equals(action)
                && mPrefs.mediaUri != null) {
            deferredResumeUri = mPrefs.mediaUri;
            deferredResumeType = mPrefs.mediaType;
            mPrefs.mediaUri = null;
            mPrefs.mediaType = null;
            pendingResumeAsk = true;
        }

        coordinatorLayout = findViewById(R.id.coordinatorLayout);
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        playerView = findViewById(R.id.video_view);
        exoPlayPause = findViewById(R.id.exo_play_pause);
        exoDuration = findViewById(R.id.exo_duration);
        centerControls = findViewById(R.id.exo_center_controls);
        loadingProgressBar = findViewById(R.id.loading);

        playerView.setShowNextButton(false);
        playerView.setShowPreviousButton(false);
        playerView.setShowFastForwardButton(false);
        playerView.setShowRewindButton(false);

        playerView.setRepeatToggleModes(Player.REPEAT_MODE_ONE);

        playerView.setControllerHideOnTouch(false);
        playerView.setControllerAutoShow(true);

        ((DoubleTapPlayerView) playerView).setDoubleTapEnabled(false);

        thumbnailView = playerView.findViewById(R.id.thumbnail_preview);
        timeBar = playerView.findViewById(R.id.exo_progress);
        timeBar.addListener(new TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(TimeBar timeBar, long position) {
                if (player == null) {
                    return;
                }
                restorePlayState = player.isPlaying();
                if (restorePlayState) {
                    player.pause();
                }
                scrubbingNoticeable = false;
                isScrubbing = true;
                frameRendered = true;
                playerView.setControllerShowTimeoutMs(-1);
                scrubbingStart = player.getCurrentPosition();
                PlayerActivity.this.timeBar.holdBufferedPosition(player.getBufferedPosition());
                seekToKeyframes(SeekParameters.CLOSEST_SYNC);
                startThumbnails();
                reportScrubbing(position);
            }

            @Override
            public void onScrubMove(TimeBar timeBar, long position) {
                reportScrubbing(position);
            }

            @Override
            public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
                playerView.setCustomErrorMessage(null);
                hideThumbnail();
                isScrubbing = false;
                PlayerActivity.this.timeBar.releaseBufferedPosition();
                // the control view listens first, so the drag's seek is already sent
                com.brouken.player.engine.SeekPrecision.exact(player);
                if (restorePlayState) {
                    restorePlayState = false;
                    playerView.setControllerShowTimeoutMs(PlayerActivity.CONTROLLER_TIMEOUT);
                    if (player != null) {
                        player.setPlayWhenReady(true);
                    }
                }
            }
        });

        buttonPlayPause = new ImageButton(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonPlayPause.setContentDescription(getString(R.string.exo_controls_play_description));
        buttonPlayPause.setOnClickListener(view -> {
            if (player == null) {
                return;
            }
            if (player.isPlaying()) {
                player.pause();
            } else {
                // a bare play() at the end does nothing on Media3; this restarts it
                androidx.media3.common.util.Util.handlePlayButtonAction(player);
            }
            updateButtonPlayPause();
            resetHideCallbacks();
        });
        updateButtonPlayPause();

        buttonOpen = new ImageButton(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonOpen.setImageResource(R.drawable.ic_folder_open_24dp);
        buttonOpen.setId(View.generateViewId());
        buttonOpen.setContentDescription(getString(R.string.button_open));

        buttonOpen.setOnClickListener(view -> {
            hideOverlayCard();
            OpenMenu.show(this);
        });

        buttonOpen.setOnLongClickListener(view -> {
            final Runnable loadFile = this::openSubtitleFilePicker;
            if (onlineController != null && onlineController.isConfigured()) {
                OpenMenu.showSubtitleSources(this, loadFile, this::searchOnlineSubtitles,
                        this::reIdentifyOnline,
                        onlineController.remembered(mPrefs.mediaUri) != null);
            } else {
                loadFile.run();
            }
            return true;
        });

        if (Utils.isPiPSupported(this)) {
            // TODO: Android 12 improvements:
            // https://developer.android.com/about/versions/12/features/pip-improvements
            mPictureInPictureParamsBuilder = new PictureInPictureParams.Builder();
            boolean success = updatePictureInPictureActions(R.drawable.ic_play_arrow_24dp, R.string.exo_controls_play_description, CONTROL_TYPE_PLAY, REQUEST_PLAY);

            if (success) {
                buttonPiP = new ImageButton(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
                buttonPiP.setContentDescription(getString(R.string.button_pip));
                buttonPiP.setImageResource(R.drawable.ic_picture_in_picture_alt_24dp);

                buttonPiP.setOnClickListener(view -> enterPiP());
            }
        }

        updateClock();

        buttonAspectRatio = new ImageButton(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonAspectRatio.setId(Integer.MAX_VALUE - 100);
        buttonAspectRatio.setContentDescription(getString(R.string.button_crop));
        updatebuttonAspectRatioIcon();
        buttonAspectRatio.setOnClickListener(view -> {
            if (engineUi() != null) {
                engineUi().setZoom(1f);
            } else {
                playerView.setScale(1.f);
            }
            aspectStep = (aspectStep + 1) % (3 + FORCED_ASPECTS.length);
            applyAspectStep(true);
            updatebuttonAspectRatioIcon();
            resetHideCallbacks();
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            buttonAspectRatio.setOnLongClickListener(v -> {
                scaleStart();
                updatebuttonAspectRatioIcon();
                return true;
            });
        }
        buttonRotation = new ImageButton(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonRotation.setContentDescription(getString(R.string.button_rotate));
        updateButtonRotation();
        buttonRotation.setOnClickListener(view -> {
            mPrefs.orientation = Utils.getNextOrientation(mPrefs.orientation);
            Utils.setOrientation(PlayerActivity.this, mPrefs.orientation);
            updateButtonRotation();
            Utils.showText(playerView, getString(mPrefs.orientation.description), 2500);
            resetHideCallbacks();
        });

        buttonLock = new ImageButton(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonLock.setContentDescription(getString(R.string.button_lock));
        buttonLock.setImageResource(R.drawable.ic_lock_open_24dp);
        buttonLock.setOnClickListener(view -> {
            hideOverlayCard();
            locked = !locked;
            ((CustomPlayerView) playerView).setIconLock(locked);
            updateButtonLock();
            if (locked) {
                playerView.hideController();
            } else {
                resetHideCallbacks();
            }
        });

        final int titleViewPaddingHorizontal = Utils.dpToPx(14);
        final int titleViewPaddingVertical = getResources().getDimensionPixelOffset(R.dimen.exo_styled_bottom_bar_time_padding);
        FrameLayout centerView = playerView.findViewById(R.id.exo_controls_background);
        titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setBackgroundResource(R.color.ui_controls_background);
        titleBar.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleBar.setPadding(titleViewPaddingHorizontal, titleViewPaddingVertical, titleViewPaddingHorizontal, titleViewPaddingVertical);
        titleBar.setVisibility(View.GONE);

        // leaves directly; the Back path would only hide the controls first
        buttonBack = new ImageButton(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonBack.setImageResource(R.drawable.ic_arrow_back_24dp);
        buttonBack.setId(View.generateViewId());
        buttonBack.setContentDescription(getString(R.string.button_back));
        buttonBack.setOnClickListener(view -> leavePlayer());
        titleBar.addView(buttonBack);

        final LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleColumn.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        titleColumn.addView(titleView);

        metaView = new TextView(this);
        metaView.setTextColor(0xB3FFFFFF);
        metaView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        metaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        metaView.setMaxLines(1);
        metaView.setEllipsize(TextUtils.TruncateAt.END);
        metaView.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        metaView.setVisibility(View.GONE);
        titleColumn.addView(metaView);

        titleBar.addView(titleColumn);

        centerView.addView(titleBar);

        titleView.setOnLongClickListener(view -> {
            // Prevent FileUriExposedException
            if (mPrefs.mediaUri != null && ContentResolver.SCHEME_FILE.equals(mPrefs.mediaUri.getScheme())) {
                return false;
            }

            final Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.putExtra(Intent.EXTRA_STREAM, mPrefs.mediaUri);
            if (mPrefs.mediaType == null)
                shareIntent.setType("video/*");
            else
                shareIntent.setType(mPrefs.mediaType);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // Start without intent chooser to allow any target to be set as default
            startActivity(shareIntent);

            return true;
        });

        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        controlView = playerView.findViewById(R.id.exo_controller);
        controlView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            if (windowInsets != null) {
                if (Build.VERSION.SDK_INT >= 31) {
                    boolean visibleBars = windowInsets.isVisible(WindowInsets.Type.statusBars());
                    if (visibleBars && !controllerVisible) {
                        playerView.postDelayed(barsHider, 2500);
                    } else {
                        playerView.removeCallbacks(barsHider);
                    }
                }

                // both ends get the larger side inset so the rows stay symmetric
                final int cutoutLeft;
                final int cutoutRight;
                if (Build.VERSION.SDK_INT >= 28 && windowInsets.getDisplayCutout() != null) {
                    cutoutLeft = windowInsets.getDisplayCutout().getSafeInsetLeft();
                    cutoutRight = windowInsets.getDisplayCutout().getSafeInsetRight();
                } else {
                    cutoutLeft = 0;
                    cutoutRight = 0;
                }

                final int side = Utils.safeSideInset(
                        windowInsets.getSystemWindowInsetLeft(),
                        windowInsets.getSystemWindowInsetRight(),
                        cutoutLeft, cutoutRight);

                int bottomBarPaddingBottom = 0;
                int progressBarMarginBottom = 0;

                if (Build.VERSION.SDK_INT >= 35) {
                    final View exoTop = findViewById(R.id.exo_top);
                    exoTop.getLayoutParams().height = windowInsets.getSystemWindowInsetTop();
                    Utils.setViewMargins(exoTop, 0, 0, 0, 0);

                    final FrameLayout exoBottomBar = findViewById(R.id.exo_bottom_bar);
                    ViewGroup.LayoutParams params = exoBottomBar.getLayoutParams();
                    params.height = getResources().getDimensionPixelSize(R.dimen.exo_styled_bottom_bar_height) + windowInsets.getSystemWindowInsetBottom();
                    exoBottomBar.setLayoutParams(params);

                    // no side spacers, so the bottom bar lines up with the seek bar
                    findViewById(R.id.exo_left).getLayoutParams().width = 0;
                    findViewById(R.id.exo_right).getLayoutParams().width = 0;

                    bottomBarPaddingBottom = windowInsets.getSystemWindowInsetBottom();
                    progressBarMarginBottom = windowInsets.getSystemWindowInsetBottom();
                } else {
                    view.setPadding(0, windowInsets.getSystemWindowInsetTop(), 0, windowInsets.getSystemWindowInsetBottom());
                }

                Utils.setViewParams(titleBar, side + titleViewPaddingHorizontal, titleViewPaddingVertical, side + titleViewPaddingHorizontal, titleViewPaddingVertical,
                        0, windowInsets.getSystemWindowInsetTop(), 0, 0);

                Utils.setViewParams(findViewById(R.id.exo_bottom_bar), side, 0, side, bottomBarPaddingBottom,
                        0, 0, 0, 0);

                Utils.setViewParams(findViewById(R.id.exo_progress), side, 0, side, 0,
                        0, 0, 0, getResources().getDimensionPixelSize(R.dimen.exo_styled_progress_margin_bottom) + progressBarMarginBottom);

                Utils.setViewMargins(findViewById(R.id.exo_error_message), 0, windowInsets.getSystemWindowInsetTop() / 2, 0, getResources().getDimensionPixelSize(R.dimen.exo_error_message_margin_bottom) + windowInsets.getSystemWindowInsetBottom() / 2);

                windowInsets.consumeSystemWindowInsets();
            }
            return windowInsets;
        });

        osdSettingsController = new OsdSettingsController(this);
        onlineController = new com.brouken.player.online.OnlineController(this,
                new com.brouken.player.online.OnlineController.Host() {
                    @Override
                    public Uri mediaUri() {
                        return mPrefs.mediaUri;
                    }

                    @Override
                    public String mediaName() {
                        // the launching app's title beats a name taken from the URL
                        if (apiTitle != null && !apiTitle.trim().isEmpty()) {
                            return apiTitle.trim();
                        }
                        return mPrefs.mediaUri == null
                                ? null
                                : Utils.getFileName(PlayerActivity.this, mPrefs.mediaUri, true);
                    }

                    @Override
                    public void loadSubtitle(Uri uri, String label) {
                        attachSubtitle(uri, label);
                    }
                });

        timeBar.setAdMarkerColor(Color.argb(0x00, 0xFF, 0xFF, 0xFF));
        timeBar.setPlayedAdMarkerColor(Color.argb(0x98, 0xFF, 0xFF, 0xFF));

        try {
            CustomDefaultTrackNameProvider customDefaultTrackNameProvider = new CustomDefaultTrackNameProvider(getResources());
            final Field field = PlayerControlView.class.getDeclaredField("trackNameProvider");
            field.setAccessible(true);
            field.set(controlView, customDefaultTrackNameProvider);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }

        findViewById(R.id.delete).setOnClickListener(view -> askDeleteMedia());

        findViewById(R.id.next).setOnClickListener(view -> {
            if (neighbours.next != null) {
                playInFolder(neighbours.next);
            } else if (!isTvBox && mPrefs.askScope) {
                askForScope(false, true);
            } else {
                skipToNext();
            }
        });

        final View previousButton = findViewById(R.id.prev);
        previousButton.setContentDescription(getString(R.string.button_previous));
        findViewById(R.id.next).setContentDescription(getString(R.string.button_next));
        previousButton.setOnClickListener(view -> {
            if (neighbours.previous != null) {
                playInFolder(neighbours.previous);
            }
        });

        exoPlayPause.setOnClickListener(view -> dispatchPlayPause());

        // Prevent double tap actions in controller
        findViewById(R.id.exo_bottom_bar).setOnTouchListener((v, event) -> true);

        playerListener = new PlayerListener();

        mBrightnessControl = new BrightnessControl(this);
        if (mPrefs.brightness >= 0) {
            mBrightnessControl.currentBrightnessLevel = mPrefs.brightness;
            mBrightnessControl.setScreenBrightness(mBrightnessControl.levelToBrightness(mBrightnessControl.currentBrightnessLevel));
        }
        playerView.setBrightnessControl(mBrightnessControl);

        final LinearLayout exoBasicControls = playerView.findViewById(R.id.exo_basic_controls);
        final ImageButton exoSubtitle = exoBasicControls.findViewById(R.id.exo_subtitle);
        exoBasicControls.removeView(exoSubtitle);

        buttonAudioTrack = exoBasicControls.findViewById(R.id.audio_track);
        exoBasicControls.removeView(buttonAudioTrack);
        buttonAudioTrack.setOnClickListener(view -> showAudioMenu());

        exoSettings = exoBasicControls.findViewById(R.id.exo_settings);
        exoBasicControls.removeView(exoSettings);
        final ImageButton exoRepeat = exoBasicControls.findViewById(R.id.exo_repeat_toggle);
        exoBasicControls.removeView(exoRepeat);

        exoSettings.setOnClickListener(view -> osdSettingsController.showPlayerSettings());

        exoSettings.setOnLongClickListener(view -> {
            openSettingsScreen();
            return true;
        });

        exoSubtitle.setOnClickListener(v -> showSubtitleMenu());
        subtitleButton = exoSubtitle;

        exoSubtitle.setOnLongClickListener(v -> {
            osdSettingsController.showSubtitleSettings();
            return true;
        });

        updateButtons(false);

        final HorizontalScrollView horizontalScrollView = (HorizontalScrollView) getLayoutInflater().inflate(R.layout.controls, null);
        final LinearLayout controls = horizontalScrollView.findViewById(R.id.controls);

        final LinearLayout exoTime = playerView.findViewById(R.id.exo_time);
        if (exoTime != null) {
            exoTime.setGravity(Gravity.CENTER_VERTICAL);
            exoTime.addView(buttonPlayPause, 0);
        } else {
            controls.addView(buttonPlayPause);
        }

        if (exoTime != null) {
            cardControls = new LinearLayout(this);
            cardControls.setOrientation(LinearLayout.HORIZONTAL);
            cardControls.setGravity(Gravity.CENTER_VERTICAL);
            cardControls.setVisibility(View.GONE);
            exoTime.addView(cardControls, 1);
        }

        final View timeText = playerView.findViewById(R.id.time_text);
        if (timeText != null) {
            timeText.setClickable(true);
            timeText.setFocusable(true);
            final android.util.TypedValue highlight = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, highlight, true);
            timeText.setBackgroundResource(highlight.resourceId);
            timeText.setOnClickListener(view -> {
                showRemainingTime = !showRemainingTime;
                startDurationTicker();
                updateDurationText();
                resetHideCallbacks();
            });
        }
        for (final String key : ControlOrder.order(this)) {
            if (ControlOrder.hidden(this).contains(key)) {
                continue;
            }
            switch (key) {
                case ControlOrder.OPEN:     controls.addView(buttonOpen); break;
                case ControlOrder.SUBTITLE: controls.addView(exoSubtitle); break;
                case ControlOrder.AUDIO:    controls.addView(buttonAudioTrack); break;
                case ControlOrder.ASPECT:   controls.addView(buttonAspectRatio); break;
                case ControlOrder.SETTINGS: controls.addView(exoSettings); break;
                case ControlOrder.LOCK:     controls.addView(buttonLock); break;
                case ControlOrder.ROTATE:
                    if (!isTvBox) {
                        controls.addView(buttonRotation);
                    }
                    break;
                case ControlOrder.PIP:
                    if (Utils.isPiPSupported(this) && buttonPiP != null) {
                        controls.addView(buttonPiP);
                    }
                    break;
                case ControlOrder.REPEAT:
                    if (mPrefs.repeatToggle) {
                        controls.addView(exoRepeat);
                    }
                    break;
                default:
                    break;
            }
        }

        horizontalScrollView.setHorizontalFadingEdgeEnabled(true);
        horizontalScrollView.setFadingEdgeLength(Utils.dpToPx(24));
        horizontalScrollView.setFillViewport(true);

        exoBasicControls.addView(horizontalScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        controls.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        if (Build.VERSION.SDK_INT > 23) {
            horizontalScrollView.setOnScrollChangeListener((view, i, i1, i2, i3) -> resetHideCallbacks());
        }

        playerView.setControllerVisibilityListener(new PlayerView.ControllerVisibilityListener() {
            @Override
            public void onVisibilityChanged(int visibility) {
                controllerVisible = visibility == View.VISIBLE;
                if (BuildConfig.DEBUG) {
                    Utils.log("Controls " + (controllerVisible ? "shown" : "hidden")
                            + " (timeout " + CONTROLLER_TIMEOUT + "ms)");
                }

                controllerVisibleFully = playerView.isControllerFullyVisible();

                if (overlayCard != null && overlayCard.isShowing()) {
                    setCardControlsVisible(true);
                }
                keepSubtitleButtonEnabled();
                if (controllerVisible) {
                    startDurationTicker();
                    updateMetaLine();
                    startMetaTicking();
                } else {
                    playerView.removeCallbacks(durationTicker);
                    stopMetaTicking();
                    if (skipController != null) {
                        skipController.reclaimFocus();
                    }
                }


                if (PlayerActivity.restoreControllerTimeout) {
                    restoreControllerTimeout = false;
                    if (player == null || !player.isPlaying()) {
                        playerView.setControllerShowTimeoutMs(-1);
                    } else {
                        playerView.setControllerShowTimeoutMs(PlayerActivity.CONTROLLER_TIMEOUT);
                    }
                }

                // https://developer.android.com/training/system-ui/immersive
                Utils.toggleSystemUi(PlayerActivity.this, playerView, visibility == View.VISIBLE);
                if (visibility == View.VISIBLE) {
                    // Because when using dpad controls, focus resets to first item in bottom controls bar.
                    // the centre button is hidden unless the info card is up
                    final View focusTarget =
                            buttonPlayPause != null && buttonPlayPause.getVisibility() == View.VISIBLE
                                    ? buttonPlayPause
                                    : (exoPlayPause != null && exoPlayPause.isEnabled()
                                            ? exoPlayPause : buttonPlayPause);
                    if (focusTarget != null) {
                        focusTarget.requestFocus();
                    }
                }

                if (controllerVisible && playerView.isControllerFullyVisible()) {
                    if (errorToShow != null) {
                        showError(errorToShow);
                        errorToShow = null;
                    }
                }
            }
        });

        youTubeOverlay = findViewById(R.id.youtube_overlay);
        youTubeOverlay.seekSeconds(mPrefs.doubleTapSeekSeconds);

        youTubeOverlay.performListener(new YouTubeOverlay.PerformListener() {
            @Override
            public void onAnimationStart() {
                youTubeOverlay.setAlpha(1.0f);
                youTubeOverlay.setVisibility(View.VISIBLE);
                hideOverlayCardForNow();
            }

            @Override
            public void onAnimationEnd() {
                youTubeOverlay.animate()
                        .alpha(0.0f)
                        .setDuration(300)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                youTubeOverlay.setVisibility(View.GONE);
                                youTubeOverlay.setAlpha(1.0f);
                            }
                        });
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (useMediaStore()) {
                Utils.scanMediaStorage(this);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_SYSTEM_NAVIGATION_OBSERVER,
                    () -> restorePlayStateAllowed = false
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::onBackPressed
            );
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        alive = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerBackHandling(true);
        }
        updateSubtitleStyle(this);
        if (Build.VERSION.SDK_INT >= 31) {
            playerView.removeCallbacks(barsHider);
            Utils.toggleSystemUi(this, playerView, true);
        }
        if (keptPlayingInBackground && player != null) {
            // still playing; rebuilding would restart it from the saved position
            keptPlayingInBackground = false;
        } else if (keptPlayerForSettings && player != null) {
            // decided in onResume: the settings result may arrive after onStart
        } else {
            keptPlayingInBackground = false;
            keptPlayerForSettings = false;
            initializePlayer();
        }
        updateButtonRotation();

        // after the player exists, so the dialog is not over a blank window
        if (pendingResumeAsk) {
            pendingResumeAsk = false;
            askResumeLastVideo();
        }
    }

    private void askResumeLastVideo() {
        if (deferredResumeUri == null) {
            return;
        }
        final Uri uri = deferredResumeUri;
        final String type = deferredResumeType;
        deferredResumeUri = null;
        deferredResumeType = null;

        Utils.showFocused(new AlertDialog.Builder(this)
                .setTitle(R.string.resume_title)
                .setMessage(rememberedName(uri))
                .setNegativeButton(R.string.resume_decline, null)
                .setPositiveButton(R.string.resume_accept, (dialog, which) -> playMedia(uri, type))
                .create(), AlertDialog.BUTTON_POSITIVE);
    }

    private String rememberedName(final Uri uri) {
        final String fromHistory = History.nameFor(
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this), uri);
        if (fromHistory != null && !fromHistory.isEmpty()) {
            return fromHistory;
        }
        return Utils.getFileName(this, uri, true);
    }

    @Override
    public void onResume() {
        super.onResume();
        restorePlayStateAllowed = true;
        // again here: on Android 12+ a TV can restyle between onStart and onResume
        updateSubtitleStyle(this);

        // the settings result is delivered just before onResume
        if (keptPlayerForSettings && player != null) {
            keptPlayerForSettings = false;
            if (settingsWantARebuild) {
                settingsWantARebuild = false;
                resumeAfterSettings = false;
                rebuildPlayer();
            } else if (resumeAfterSettings) {
                resumeAfterSettings = false;
                player.play();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePlayer();
    }

    @Override
    public void onStop() {
        super.onStop();
        alive = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerBackHandling(false);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            playerView.removeCallbacks(barsHider);
        }
        playerView.setCustomErrorMessage(null);

        // background audio; the picture stops by itself once the window is gone
        if (keepPlayingInBackground()) {
            keptPlayingInBackground = true;
            savePlayer();
            return;
        }
        keptPlayingInBackground = false;

        // keep the player, paused, across a trip to our own settings
        if (keptPlayerForSettings && player != null) {
            resumeAfterSettings = player.isPlaying();
            if (resumeAfterSettings) {
                player.pause();
            }
            savePlayer();
            return;
        }

        releasePlayer(false);
    }

    private boolean keepPlayingInBackground() {
        return mPrefs != null && mPrefs.backgroundAudio
                && ownsPlayer() && player.isPlaying()
                && haveMedia && !isFinishing();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // only this screen's own player; a newer screen may already have one playing
        if (ownsPlayer()) {
            keptPlayingInBackground = false;
            releasePlayer(false);
        } else if (mediaSession != null) {
            // This screen's own session, never the newer screen's.
            mediaSession.release();
            if (sharedSession == mediaSession) {
                sharedSession = null;
            }
            mediaSession = null;
        }
        hideLockedTimeline();
        stopPosterWatch();
        mPrefs.mSharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        // from Android 13 Back arrives here rather than as a key event
        if (hintTookBack()) {
            return;
        }
        restorePlayStateAllowed = false;
        super.onBackPressed();
    }

    private void leavePlayer() {
        if (locked) {
            return;
        }
        restorePlayStateAllowed = false;
        finish();
    }

    @Override
    public void finish() {
        if (intentReturnResult) {
            Intent intent = new Intent("com.mxtech.intent.result.VIEW");
            intent.putExtra(API_END_BY, playbackFinished ? "playback_completion" : "user");
            if (!playbackFinished) {
                // This film's player, not a newer screen's.
                if (ownsPlayer()) {
                    long duration = player.getDuration();
                    if (duration != C.TIME_UNSET) {
                        intent.putExtra(API_DURATION, (int) player.getDuration());
                    }
                    if (player.isCurrentMediaItemSeekable()) {
                        if (mPrefs.persistentMode) {
                            intent.putExtra(API_POSITION, (int) mPrefs.nonPersitentPosition);
                        } else {
                            intent.putExtra(API_POSITION, (int) player.getCurrentPosition());
                        }
                    }
                }
            }
            setResult(Activity.RESULT_OK, intent);
        }

        super.finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent != null) {
            final String action = intent.getAction();
            final String type = intent.getType();
            final Uri uri = intent.getData();

            if (Intent.ACTION_VIEW.equals(action) && uri != null) {
                openFromLaunch(intent);
                initializePlayer();
            } else if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
                String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (text != null) {
                    final Uri parsedUri = Uri.parse(text);
                    if (parsedUri.isAbsolute()) {
                        resetApiAccess();
                        mPrefs.updateMedia(this, parsedUri, null);
                        focusPlay = true;
                        initializePlayer();
                    }
                }
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (player == null)
                    break;
                // Play from the end of a film starts it again, as the buttons do.
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                    player.pause();
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                    androidx.media3.common.util.Util.handlePlayButtonAction(player);
                } else if (player.isPlaying()) {
                    player.pause();
                } else {
                    androidx.media3.common.util.Util.handlePlayButtonAction(player);
                }
                return true;
            // with no neighbour the key is left to the system
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (neighbours.next != null) {
                    playInFolder(neighbours.next);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (neighbours.previous != null) {
                    playInFolder(neighbours.previous);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (adjustPlayerVolume(keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
                    return true;
                }
                Utils.adjustVolume(this, mAudioManager, playerView, keyCode == KeyEvent.KEYCODE_VOLUME_UP, event.getRepeatCount() == 0, true);
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (player == null)
                    break;
                if (!controllerVisibleFully) {
                    if (player.isPlaying()) {
                        player.pause();
                    } else {
                        androidx.media3.common.util.Util.handlePlayButtonAction(player);
                    }
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (!controllerVisibleFully || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                    if (player == null)
                        break;
                    playerView.removeCallbacks(playerView.textClearRunnable);
                    long pos = player.getCurrentPosition();
                    if (playerView.keySeekStart == -1) {
                        playerView.keySeekStart = pos;
                    }
                    long seekTo = pos - mPrefs.doubleTapSeekSeconds * 1000L;
                    if (seekTo < 0)
                        seekTo = 0;
                    seekToKeyframes(SeekParameters.PREVIOUS_SYNC);
                    player.seekTo(seekTo);
                    final String message = Utils.formatMilisSign(seekTo - playerView.keySeekStart) + "\n" + Utils.formatMilis(seekTo);
                    playerView.setCustomErrorMessage(message);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (!controllerVisibleFully || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                    if (player == null)
                        break;
                    playerView.removeCallbacks(playerView.textClearRunnable);
                    long pos = player.getCurrentPosition();
                    if (playerView.keySeekStart == -1) {
                        playerView.keySeekStart = pos;
                    }
                    long seekTo = pos + mPrefs.doubleTapSeekSeconds * 1000L;
                    long seekMax = player.getDuration();
                    if (seekMax != C.TIME_UNSET && seekTo > seekMax)
                        seekTo = seekMax;
                    seekToKeyframes(SeekParameters.NEXT_SYNC);
                    player.seekTo(seekTo);
                    final String message = Utils.formatMilisSign(seekTo - playerView.keySeekStart) + "\n" + Utils.formatMilis(seekTo);
                    playerView.setCustomErrorMessage(message);
                    return true;
                }
                break;
            //noinspection "GestureBackNavigation"
            case KeyEvent.KEYCODE_BACK:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    return super.onKeyDown(keyCode, event);
                } else {
                    if (controllerVisible && player != null /*&& player.isPlaying()*/) {
                        playerView.hideController();
                        return true;
                    } else {
                        onBackPressed();
                    }
                }
                break;
            case KeyEvent.KEYCODE_UNKNOWN:
                return super.onKeyDown(keyCode, event);
            default:
                if (!controllerVisibleFully) {
                    playerView.showController();
                    return true;
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                playerView.postDelayed(playerView.textClearRunnable, CustomPlayerView.MESSAGE_TIMEOUT_KEY);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (!isScrubbing) {
                    playerView.postDelayed(playerView.textClearRunnable, 1000);
                }
                break;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // locked: holding OK unlocks, any other key shows the padlock
        if (locked) {
            final int lockedKey = event.getKeyCode();
            // volume keys still work while locked
            if (lockedKey == KeyEvent.KEYCODE_VOLUME_UP
                    || lockedKey == KeyEvent.KEYCODE_VOLUME_DOWN
                    || lockedKey == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return super.dispatchKeyEvent(event);
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return true;
            }

            // a held key repeats from the first tick; a synthetic long press sets the flag
            final boolean held = event.isLongPress() || event.getRepeatCount() >= 1;
            final boolean confirm = lockedKey == KeyEvent.KEYCODE_DPAD_CENTER
                    || lockedKey == KeyEvent.KEYCODE_ENTER
                    || lockedKey == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || lockedKey == KeyEvent.KEYCODE_BUTTON_A;

            if (confirm && held) {
                locked = false;
                ((CustomPlayerView) playerView).setIconLock(false);
                updateButtonLock();
                Utils.showText(playerView, getString(R.string.unlocked));
                resetHideCallbacks();
            } else {
                ((CustomPlayerView) playerView).setIconLock(true);
                Utils.showText(playerView, getString(R.string.locked_hint));
            }
            return true;
        }

        if (hintTakesKey(event)) {
            return true;
        }

        if (isScaling) {
            final int keyCode = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        scale(true);
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        scale(false);
                        break;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        break;
                    default:
                        if (isScaleStarting) {
                            isScaleStarting = false;
                        } else {
                            scaleEnd();
                        }
                }
            }
            return true;
        }

        if (!controllerVisibleFully) {
            // with the controls hidden, OK still goes to a focused skip button
            if (isConfirmKey(event.getKeyCode())
                    && skipController != null && skipController.buttonHasFocus()) {
                return super.dispatchKeyEvent(event);
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                onKeyDown(event.getKeyCode(), event);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                onKeyUp(event.getKeyCode(), event);
            }
            return true;
        } else {
            return super.dispatchKeyEvent(event);
        }
    }

    // remote keys for a hint pointer: OK presses it, Back dismisses it
    private boolean hintTakesKey(final KeyEvent event) {
        if (currentHint == null || !currentHint.isVisible()) {
            return false;
        }
        final int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return false;
        }
        // act on key down: eating the down half stops the system invoking Back
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        if (event.getRepeatCount() > 0) {
            return true;
        }
        if (isConfirmKey(keyCode)) {
            if (currentHintIsTheKeyOne) {
                openSettingsAfterHints = true;
            } else {
                openFileAfterHints = true;
            }
            currentHint.dismiss(true);
        } else if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            currentHint.dismiss(false);
        }
        return true;
    }

    private boolean hintTookBack() {
        if (currentHint == null || !currentHint.isVisible()) {
            return false;
        }
        currentHint.dismiss(false);
        return true;
    }

    // Android 13+: Back is not a key event; an overlay callback takes it for a pointer
    @Nullable
    private Object hintBackWatch;

    private void watchBackForHint(final boolean on) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (on) {
            if (hintBackWatch == null) {
                final android.window.OnBackInvokedCallback callback = this::hintTookBack;
                hintBackWatch = callback;
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            }
        } else if (hintBackWatch != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) hintBackWatch);
            hintBackWatch = null;
        }
    }

    private boolean wantsHomeInstead(final Intent intent) {
        if (intent == null || intent.getData() != null) {
            return false;
        }
        final String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)
                || "com.brouken.player.action.SHORTCUT_VIDEOS".equals(action)) {
            return false;
        }
        if (intent.getBooleanExtra(HomeActivity.EXTRA_FROM_HOME, false)) {
            return false;
        }
        return HomeActivity.START_ON_HOME.equals(
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                        .getString("startOn", HomeActivity.START_ON_HOME));
    }

    private static boolean isConfirmKey(final int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (0 != (event.getSource() & InputDevice.SOURCE_CLASS_POINTER)) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_SCROLL:
                    final float value = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    Utils.adjustVolume(this, mAudioManager, playerView, value > 0.0f, Math.abs(value) > 1.0f, true);
                    return true;
            }
        } else if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
                event.getAction() == MotionEvent.ACTION_MOVE) {
            // TODO: This somehow works, but it would use better filtering
            float value = event.getAxisValue(MotionEvent.AXIS_RZ);
            for (int i = 0; i < event.getHistorySize(); i++) {
                float historical = event.getHistoricalAxisValue(MotionEvent.AXIS_RZ, i);
                if (Math.abs(historical) > value) {
                    value = historical;
                }
            }
            if (Math.abs(value) == 1.0f) {
                Utils.adjustVolume(this, mAudioManager, playerView, value < 0, true, true);
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);

        if (isInPictureInPictureMode) {
            // On Android TV it is required to hide controller in this PIP change callback
            playerView.hideController();
            hideLockedTimeline();
            if (!pipPrepared && engineUi() != null) {
                // Entered without going through enterPiP(): the system did it.
                engineUi().onEnterPip(videoFormat());
            }
            pipPrepared = false;
            inPip = true;
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !ACTION_MEDIA_CONTROL.equals(intent.getAction()) || player == null) {
                        return;
                    }

                    switch (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                        case CONTROL_TYPE_PLAY:
                            androidx.media3.common.util.Util.handlePlayButtonAction(player);
                            break;
                        case CONTROL_TYPE_PAUSE:
                            player.pause();
                            break;
                    }
                }
            };
            ContextCompat.registerReceiver(this, mReceiver, new IntentFilter(ACTION_MEDIA_CONTROL), ContextCompat.RECEIVER_EXPORTED);
        } else {
            inPip = false;
            if (engineUi() != null) {
                engineUi().onExitPip();
            } else {
                releaseFixedSurfaceSize();
            }
            if (mReceiver != null) {
                unregisterReceiver(mReceiver);
                mReceiver = null;
            }
            playerView.setControllerAutoShow(true);
            if (player != null) {
                if (player.isPlaying())
                    Utils.toggleSystemUi(this, playerView, false);
                else
                    playerView.showController();
            }
        }
    }

    // set by enterPiP() until the system confirms the switch
    private boolean pipPrepared;
    private boolean inPip;

    private void releaseFixedSurfaceSize() {
        final View surface = playerView.getVideoSurfaceView();
        if (surface instanceof SurfaceView) {
            ((SurfaceView) surface).getHolder().setSizeFromLayout();
        }
    }

    void resetApiAccess() {
        apiAccess = false;
        apiAccessPartial = false;
        apiTitle = null;
        apiSubs.clear();
        apiHeaders.clear();
        apiImdbId = null;
        apiTmdbId = null;
        launchMemoryChecked = false;
        launchStateFor = null;
        mPrefs.setPersistent(true);
        mPrefs.setMediaTitle(null);
    }

    // the film the launch extras belong to; opening another film drops them
    @Nullable
    private Uri launchStateFor;

    private void claimLaunchState() {
        if (launchStateFor != null && !launchStateFor.equals(mPrefs.mediaUri)) {
            resetApiAccess();
        }
        launchStateFor = mPrefs.mediaUri;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
            if (restoreOrientationLock) {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                restoreOrientationLock = false;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // a subtitle picked for a kept film is attached without reopening the film
        final boolean subtitleForKeptPlayer = (requestCode == REQUEST_CHOOSER_SUBTITLE
                || requestCode == REQUEST_CHOOSER_SUBTITLE_MEDIASTORE)
                && keptPlayerForSettings && player != null;

        if (resultCode == RESULT_OK && alive && !subtitleForKeptPlayer) {
            releasePlayer();
        }

        if (requestCode == REQUEST_CHOOSER_VIDEO || requestCode == REQUEST_CHOOSER_VIDEO_MEDIASTORE) {
            if (resultCode == RESULT_OK) {
                resetApiAccess();
                restorePlayState = false;

                final Uri uri = data.getData();

                if (requestCode == REQUEST_CHOOSER_VIDEO) {
                    boolean uriAlreadyTaken = false;

                    // https://commonsware.com/blog/2020/06/13/count-your-saf-uri-permission-grants.html
                    final ContentResolver contentResolver = getContentResolver();
                    for (UriPermission persistedUri : contentResolver.getPersistedUriPermissions()) {
                        if (persistedUri.getUri().equals(mPrefs.scopeUri)) {
                            continue;
                        } else if (persistedUri.getUri().equals(uri)) {
                            uriAlreadyTaken = true;
                        } else {
                            try {
                                contentResolver.releasePersistableUriPermission(persistedUri.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (SecurityException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    if (!uriAlreadyTaken && uri != null) {
                        try {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException e) {
                            e.printStackTrace();
                        }
                    }
                }

                mPrefs.setPersistent(true);
                mPrefs.updateMedia(this, uri, data.getType());

                if (requestCode == REQUEST_CHOOSER_VIDEO) {
                    searchSubtitles();
                }
            }
        } else if (requestCode == REQUEST_CHOOSER_SUBTITLE || requestCode == REQUEST_CHOOSER_SUBTITLE_MEDIASTORE) {
            if (resultCode == RESULT_OK) {
                Uri uri = data.getData();

                if (requestCode == REQUEST_CHOOSER_SUBTITLE) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException e) {
                        e.printStackTrace();
                    }
                }

                if (subtitleForKeptPlayer) {
                    attachSubtitle(uri);
                } else {
                    handleSubtitles(uri);
                }
            }
        } else if (requestCode == REQUEST_CHOOSER_SCOPE_DIR) {
            if (resultCode == RESULT_OK) {
                final Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    mPrefs.updateScope(uri);
                    mPrefs.markScopeAsked();
                    searchSubtitles();
                } catch (SecurityException e) {
                    e.printStackTrace();
                }
            }
        } else if (requestCode == REQUEST_SETTINGS) {
            mPrefs.loadUserPreferences();
            applyImmediatePreferences();
            settingsWantARebuild = keptPlayerForSettings && settingsNeedTheFileReopened();
            Utils.log("Back from settings: kept=" + keptPlayerForSettings
                    + " rebuild=" + settingsWantARebuild);

            // accent and button strip are fixed when the screen is built
            if (!Accent.stored(this).equals(appliedAccent)
                    || mPrefs.repeatToggle != appliedRepeatToggle) {
                recreate();
                return;
            }

            // A URL picked from the history screen comes back as the result data.
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                setMedia(data.getData(), data.getType());
                // the kept player holds the wrong file now
                settingsWantARebuild = true;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }

        // Init here because onStart won't follow when app was only paused when file chooser was shown
        // (for example pop-up file chooser on tablets)
        if (resultCode == RESULT_OK && alive && !subtitleForKeptPlayer) {
            initializePlayer();
        }
    }

    @Nullable
    Uri handleSubtitles(Uri uri) {
        // keep copies this film or a remembered one still uses
        SubtitleUtils.clearCache(this, subtitleFilesInUse());
        // Convert subtitles to UTF-8 if necessary
        uri = Utils.convertToUTF(this, uri);
        // null while a remote subtitle downloads; it arrives later
        if (uri != null) {
            mPrefs.updateSubtitle(uri);
        }
        return uri;
    }

    private List<Uri> subtitleFilesInUse() {
        final List<Uri> inUse = new ArrayList<>(mPrefs.subtitleUris);
        for (final MediaItem.SubtitleConfiguration subtitle : apiSubs) {
            inUse.add(subtitle.uri);
        }
        inUse.addAll(LaunchMemory.subtitleFiles(mPrefs.mSharedPreferences));
        return inUse;
    }

    // a drawn frame proves this engine can play the file
    private boolean pictureSeen;

    // last real size: a rebuilt media item reports zero until the source is read
    private androidx.media3.common.VideoSize lastVideoSize;

    // a failed sidecar's load error has trackType -1, so it is matched by URI
    private final java.util.Set<String> sidecarSubtitleUris = new java.util.HashSet<>();

    private boolean subtitleFailureReported;


    private boolean tryMpvFallback() {
        if (pictureSeen) {
            return false;
        }
        if (BuildConfig.DEBUG) {
            Utils.log("mpv fallback check: engine=" + mPrefs.playbackEngine
                    + " active=" + mpvFallbackActive
                    + " supported=" + com.brouken.player.mpv.MpvPlayer.isSupported());
        }
        if (!"auto".equals(mPrefs.playbackEngine)
                || mpvFallbackActive
                || !com.brouken.player.mpv.MpvPlayer.isSupported()
                || mPrefs.mediaUri == null) {
            return false;
        }

        mpvFallbackActive = true;
        if (BuildConfig.DEBUG) Utils.log("Falling back to mpv for this file");
        Utils.showText(playerView, getString(R.string.engine_fallback_mpv), 2500);

        // the engine is chosen when the player is built
        captureTrackSelection();
        releasePlayer();
        initializePlayer();
        return true;
    }

    private boolean useMpvEngine() {
        if (!com.brouken.player.mpv.MpvPlayer.isSupported()) {
            return false;
        }
        return "mpv".equals(mPrefs.playbackEngine)
                || ("auto".equals(mPrefs.playbackEngine) && mpvFallbackActive);
    }

    // engines number tracks differently, so carry language and index instead
    private String carryAudioLanguage;
    private int carryAudioIndex = -1;
    private String carryTextLanguage;
    private int carryTextIndex = -1;
    private boolean carryTracks;

    private void captureTrackSelection() {
        if (player == null) {
            return;
        }
        carryAudioLanguage = null;
        carryAudioIndex = -1;
        carryTextLanguage = null;
        carryTextIndex = -1;
        carryTracks = true;

        int audio = 0;
        int text = 0;
        for (final Tracks.Group group : player.getCurrentTracks().getGroups()) {
            for (int i = 0; i < group.length; i++) {
                if (group.getType() == C.TRACK_TYPE_AUDIO) {
                    if (group.isTrackSelected(i)) {
                        carryAudioLanguage = group.getTrackFormat(i).language;
                        carryAudioIndex = audio;
                    }
                    audio++;
                } else if (group.getType() == C.TRACK_TYPE_TEXT) {
                    if (group.isTrackSelected(i)) {
                        carryTextLanguage = group.getTrackFormat(i).language;
                        carryTextIndex = text;
                    }
                    text++;
                }
            }
        }
    }

    private void applyCarriedTracks(final Tracks tracks) {
        if (!carryTracks || player == null) {
            return;
        }
        carryTracks = false;
        applyCarriedTrack(tracks, C.TRACK_TYPE_AUDIO, carryAudioLanguage, carryAudioIndex);
        applyCarriedTrack(tracks, C.TRACK_TYPE_TEXT, carryTextLanguage, carryTextIndex);
    }

    private void applyCarriedTrack(final Tracks tracks, final int type,
                                   @Nullable final String language, final int wantedIndex) {
        if (wantedIndex < 0) {
            return;
        }
        Tracks.Group byIndex = null;
        int byIndexTrack = -1;
        int seen = 0;
        for (final Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != type) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                final String candidate = group.getTrackFormat(i).language;
                if (language != null && language.equals(candidate)) {
                    select(group, i, type);
                    return;
                }
                if (seen == wantedIndex) {
                    byIndex = group;
                    byIndexTrack = i;
                }
                seen++;
            }
        }
        if (byIndex != null) {
            select(byIndex, byIndexTrack, type);
        }
    }

    private void select(final Tracks.Group group, final int index, final int type) {
        final java.util.List<Integer> selection = new ArrayList<>();
        selection.add(index);
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(new TrackSelectionOverride(
                                group.getMediaTrackGroup(), selection))
                        .build());
    }

    @Nullable
    static ExoPlayer exo() {
        return player instanceof ExoPlayer ? (ExoPlayer) player : null;
    }

    @Nullable
    static Format videoFormat() {
        final ExoPlayer exo = exo();
        if (exo != null) {
            return exo.getVideoFormat();
        }
        final Player current = player;
        if (current == null) {
            return null;
        }
        final androidx.media3.common.VideoSize size = current.getVideoSize();
        if (size.width <= 0 || size.height <= 0) {
            return null;
        }
        return new Format.Builder()
                .setWidth(size.width)
                .setHeight(size.height)
                .setRotationDegrees(size.unappliedRotationDegrees)
                .setPixelWidthHeightRatio(size.pixelWidthHeightRatio)
                .build();
    }

    static int audioSessionId() {
        final ExoPlayer exo = exo();
        return exo == null ? 0 : exo.getAudioSessionId();
    }

    public void initializePlayer() {
        boolean isNetworkUri = Utils.isSupportedNetworkUri(mPrefs.mediaUri);
        haveMedia = mPrefs.mediaUri != null;
        if (haveMedia) {
            claimLaunchState();
            restoreLaunchMemory();
        }

        Utils.log("Building the player");

        if (timeBar != null) {
            timeBar.setWholeFileHere(haveMedia && !isNetworkUri);
        }

        if (player != null) {
            if (sharedSession != null) {
                sharedSession.release();
                sharedSession = null;
            }
            player.removeListener(playerListener);
            player.clearMediaItems();
            player.release();
            player = null;
        }
        if (engineUi != null) {
            engineUi.release();
            engineUi = null;
        }
        ownPlayer = null;

        trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true));
        if (mPrefs.tunneling) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setTunnelingEnabled(true)
            );
        }
        final String[] audioLanguages = Languages.audio(this);
        if (audioLanguages.length > 0) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setPreferredAudioLanguages(audioLanguages)
            );
        }

        final CaptioningManager captioningManager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
        if (!captioningManager.isEnabled()) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            );
        }

        final String[] textLanguages = Languages.subtitle(this);
        if (textLanguages.length > 0) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setPreferredTextLanguages(textLanguages)
            );
        }

        int subtitleDelay = mPrefs.getSubtitleDelayForUri(mPrefs.mediaUri);
        subtitleDelayMs.set(subtitleDelay);
        audioDelayMs.set(mPrefs.getAudioDelayForUri(mPrefs.mediaUri));

        EnhancedSubtitleParserFactory enhancedSubtitleParserFactory = new EnhancedSubtitleParserFactory(0);
        SubtitleParser.Factory subtitleParserFactory = enhancedSubtitleParserFactory;

        // https://github.com/google/ExoPlayer/issues/8571
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
                .setSubtitleParserFactory(subtitleParserFactory);

        @SuppressLint("WrongConstant") RenderersFactory renderersFactory = new DelayRenderersFactory(this, subtitleDelayMs, audioDelayMs)
                .setExtensionRendererMode(mPrefs.decoderPriority)
                .setMapDV7ToHevc(mPrefs.mapDV7ToHevc);

        DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory(this, extractorsFactory)
                        .setSubtitleParserFactory(subtitleParserFactory);

        // the default meter wrapped with a counter for the stream speed readout
        networkSpeed = new com.brouken.player.net.NetworkSpeed(
                new androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(this).build());

        ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(this, renderersFactory)
                .setTrackSelector(trackSelector)
                .setBandwidthMeter(networkSpeed)
                .setMediaSourceFactory(mediaSourceFactory);

        if (haveMedia && isNetworkUri) {
            if (mPrefs.mediaUri.getScheme().toLowerCase().startsWith("http")) {
                HashMap<String, String> headers = new HashMap<>();
                String userInfo = mPrefs.mediaUri.getUserInfo();
                if (userInfo != null && userInfo.length() > 0 && userInfo.contains(":")) {
                    headers.put("Authorization", "Basic " + Base64.encodeToString(userInfo.getBytes(), Base64.NO_WRAP));
                }
                headers.putAll(apiHeaders);
                if (!headers.isEmpty()) {
                    DefaultHttpDataSource.Factory defaultHttpDataSourceFactory = new DefaultHttpDataSource.Factory();
                    // the data source writes its own User-Agent over these, so set it directly
                    for (final java.util.Map.Entry<String, String> header : headers.entrySet()) {
                        if ("User-Agent".equalsIgnoreCase(header.getKey())) {
                            defaultHttpDataSourceFactory.setUserAgent(header.getValue());
                        }
                    }
                    defaultHttpDataSourceFactory.setDefaultRequestProperties(headers);
                    DefaultMediaSourceFactory networkMediaSourceFactory =
                            new DefaultMediaSourceFactory(defaultHttpDataSourceFactory, extractorsFactory)
                                    .setSubtitleParserFactory(subtitleParserFactory);
                    playerBuilder.setMediaSourceFactory(networkMediaSourceFactory);
                }
            }
        }


        if (mPrefs.adaptiveBuffering) {
            playerBuilder.setLoadControl(BufferProfile.create(this, mPrefs.mediaUri));
        }
        final boolean mpv = useMpvEngine();
        // subtitle size and position are stored per engine
        mPrefs.setSubtitleEngine(mpv ? "mpv" : "media3");
        if (mpv) {
            final com.brouken.player.mpv.MpvPlayer mpvPlayer =
                    new com.brouken.player.mpv.MpvPlayer(this,
                            new com.brouken.player.mpv.MpvOptions(mPrefs.mediaUri)
                                    .withHeaders(apiHeaders));
            // mpv has no load error; it reports a track list that did not grow
            mpvPlayer.setSubtitleFailureListener(() -> {
                if (subtitleFailureReported) {
                    return;
                }
                subtitleFailureReported = true;
                Utils.log("Subtitle would not load");
                Utils.showText(playerView, getString(R.string.subtitle_would_not_load), 3500);
            });
            player = mpvPlayer;
            engineUi = new com.brouken.player.engine.MpvEngineUi(playerView, mPrefs, mpvPlayer);
            // overlays follow the picture as it moves inside mpv's surface
            mpvPlayer.setPictureListener(this::remeasureOverPicture);
        } else {
            player = playerBuilder.build();
            engineUi = new com.brouken.player.engine.Media3EngineUi(this, playerView, mPrefs, player);
        }
        ownPlayer = player;
        // Media3 streams only; mpv watches its own seeks
        if (seekStall != null) {
            seekStall.stop();
        }
        seekStall = player instanceof ExoPlayer && isNetworkUri && networkSpeed != null
                ? new com.brouken.player.engine.Media3SeekStall(player, networkSpeed,
                        this::onMedia3SeekStalled)
                : null;
        if (osdSettingsController != null) {
            osdSettingsController.refreshSubtitleValues();
        }

        // a failed subtitle still reports as selected; only the load error shows it
        final ExoPlayer errorSource = exo();
        if (errorSource != null) {
            errorSource.addAnalyticsListener(
                    new androidx.media3.exoplayer.analytics.AnalyticsListener() {
                        @Override
                        public void onLoadError(
                                @NonNull EventTime eventTime,
                                @NonNull androidx.media3.exoplayer.source.LoadEventInfo loadEventInfo,
                                @NonNull androidx.media3.exoplayer.source.MediaLoadData mediaLoadData,
                                @NonNull java.io.IOException error,
                                boolean wasCanceled) {
                            if (subtitleFailureReported) {
                                return;
                            }
                            final boolean ours = mediaLoadData.trackType == C.TRACK_TYPE_TEXT
                                    || (loadEventInfo.uri != null
                                        && sidecarSubtitleUris.contains(
                                                loadEventInfo.uri.toString()));
                            if (!ours) {
                                return;
                            }
                            subtitleFailureReported = true;
                            Utils.log("Subtitle would not load");
                            Utils.showText(playerView,
                                    getString(R.string.subtitle_would_not_load), 3500);
                        }
                    });
        }

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        player.setAudioAttributes(audioAttributes, true);

        final ExoPlayer frameRateSource = exo();
        if (frameRateSource != null) {
            UtilsKt.calculateFrameRateOnTheFly(frameRateSource, frameRate -> {
                if (enhancedSubtitleParserFactory.setFallbackFrameRate(frameRate)) {
                    restartPlayback();
                }
                return Unit.INSTANCE;
            });
        }

        if (mPrefs.skipSilence) {
            if (exo() != null) {
                exo().setSkipSilenceEnabled(true);
            }
        }

        youTubeOverlay.player(player);
        playerView.setPlayer(player);

        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }

        if (player.canAdvertiseSession()) {
            try {
                mediaSession = new MediaSession.Builder(this, player).build();
                sharedSession = mediaSession;
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }

        playerView.setControllerShowTimeoutMs(-1);

        locked = false;

        if (haveMedia) {

            aspectStep = mPrefs.aspectStep(3 + FORCED_ASPECTS.length);
            applyAspectStep(false);

            if (playerView.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                playerView.setScale(mPrefs.scale);
            } else {
                playerView.setScale(1.f);
            }
            updatebuttonAspectRatioIcon();

            MediaItem.Builder mediaItemBuilder = new MediaItem.Builder()
                    .setUri(mPrefs.mediaUri)
                    .setMimeType(mPrefs.mediaType);
            String title;
            if (apiTitle != null) {
                title = apiTitle;
            } else {
                title = Utils.getFileName(PlayerActivity.this, mPrefs.mediaUri, false);
            }
            if (title != null) {
                final MediaMetadata mediaMetadata = new MediaMetadata.Builder()
                        .setTitle(title)
                        .setDisplayTitle(title)
                        .build();
                mediaItemBuilder.setMediaMetadata(mediaMetadata);
            }
            final List<MediaItem.SubtitleConfiguration> subtitles = allSubtitleConfigurations();
            if (!subtitles.isEmpty()) {
                mediaItemBuilder.setSubtitleConfigurations(subtitles);
                rememberSubtitleAddresses(subtitles);
            }
            player.setMediaItem(mediaItemBuilder.build(), mPrefs.getPosition());

            try {
                if (loudnessEnhancer != null) {
                    loudnessEnhancer.release();
                }
                loudnessEnhancer = new LoudnessEnhancer(audioSessionId());
            } catch (Exception e) {
                e.printStackTrace();
            }

            notifyAudioSessionUpdate(true);

            videoLoading = true;
            pictureSeen = false;
            stopNoPictureWatch();

            updateLoading(true);

            if (mPrefs.getPosition() == 0L || apiAccess || apiAccessPartial || playOnLoad) {
                play = true;
            }
            playOnLoad = false;

            if (apiTitle != null) {
                titleView.setText(apiTitle);
            } else {
                titleView.setText(Utils.getFileName(this, mPrefs.mediaUri, false));
                resolveTitleFromServer(mPrefs.mediaUri);
            }
            autoIdentify(mPrefs.mediaUri);
            titleBar.setVisibility(View.VISIBLE);

            updateButtons(true);

            ((DoubleTapPlayerView) playerView).setDoubleTapEnabled(true);

            if (!apiAccess) {
                if (nextUriThread != null) {
                    nextUriThread.interrupt();
                }
                nextUri = null;
                nextUriThread = new Thread(() -> {
                    Uri uri = findNext();
                    if (!Thread.currentThread().isInterrupted()) {
                        nextUri = uri;
                    }
                });
                nextUriThread.start();
            }

            if (exo() != null) {
                exo().setHandleAudioBecomingNoisy(!isTvBox);
            } else if (player instanceof com.brouken.player.mpv.MpvPlayer) {
                ((com.brouken.player.mpv.MpvPlayer) player).setHandleAudioBecomingNoisy(!isTvBox);
            }
        } else {
            playerView.showController();
        }

        player.addListener(playerListener);
        player.prepare();

        updateSubtitleStyle(this);

        if (restorePlayState) {
            restorePlayState = false;
            playerView.showController();
            playerView.setControllerShowTimeoutMs(PlayerActivity.CONTROLLER_TIMEOUT);
            player.setPlayWhenReady(true);
        }
    }

    private void savePlayer() {
        if (ownsPlayer()) {
            mPrefs.updateBrightness(mBrightnessControl.currentBrightnessLevel);
            mPrefs.updateOrientation();

            if (haveMedia) {
                // Prevent overwriting temporarily inaccessible media position
                if (player.isCurrentMediaItemSeekable()) {
                    mPrefs.updatePosition(player.getCurrentPosition(),
                            player.getDuration() == C.TIME_UNSET ? 0L : player.getDuration());
                }
                mPrefs.updateMeta(getSelectedTrack(C.TRACK_TYPE_AUDIO),
                        getSelectedTrack(C.TRACK_TYPE_TEXT),
                        playerView.getResizeMode(),
                        playerView.getVideoSurfaceView().getScaleX(),
                        player.getPlaybackParameters().speed);
            }
        }
    }

    public void releasePlayer() {
        releasePlayer(true);
    }

    public void releasePlayer(boolean save) {
        // the skip poller runs on a Handler and would outlive the player
        if (skipController != null) {
            skipController.stop();
        }

        if (save) {
            savePlayer();
        }

        // also lowers Media3's raised cues again
        hideLockedTimeline();
        stopNoPictureWatch();
        if (seekStall != null) {
            seekStall.stop();
            seekStall = null;
        }

        if (ownsPlayer()) {
            notifyAudioSessionUpdate(false);
            if (engineUi != null) {
                engineUi.release();
                engineUi = null;
            }

            if (mediaSession != null) {
                mediaSession.release();
                if (sharedSession == mediaSession) {
                    sharedSession = null;
                }
                mediaSession = null;
            }

            if (player.isPlaying() && restorePlayStateAllowed) {
                restorePlayState = true;
            }
            player.removeListener(playerListener);
            player.clearMediaItems();
            player.release();
            player = null;
            ownPlayer = null;
        } else if (mediaSession != null && ownPlayer != null) {
            // a newer screen owns the player; release only this screen's session
            mediaSession.release();
            mediaSession = null;
            ownPlayer = null;
        }
        titleBar.setVisibility(View.GONE);
        updateButtons(false);
    }

    private void rememberSubtitleAddresses(
            final java.util.List<MediaItem.SubtitleConfiguration> subtitles) {
        if (subtitles == null) {
            return;
        }
        for (final MediaItem.SubtitleConfiguration subtitle : subtitles) {
            if (subtitle.uri != null) {
                sidecarSubtitleUris.add(subtitle.uri.toString());
            }
        }
    }

    private class PlayerListener implements Player.Listener {
        @Override
        public void onAudioSessionIdChanged(int audioSessionId) {
            try {
                if (loudnessEnhancer != null) {
                    loudnessEnhancer.release();
                }
                loudnessEnhancer = new LoudnessEnhancer(audioSessionId);
                applyVolumeBoost();
            } catch (Exception e) {
                e.printStackTrace();
            }
            notifyAudioSessionUpdate(true);
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {

            updateButtonPlayPause();
            updateOverlayCard(isPlaying);
            if (isPlaying) {
                ensureSkipSegments();
                watchForNoPicture();
            }
            applyKeepScreenOn(isPlaying);

            if (Utils.isPiPSupported(PlayerActivity.this)) {
                if (isPlaying) {
                    updatePictureInPictureActions(R.drawable.ic_pause_24dp, R.string.exo_controls_pause_description, CONTROL_TYPE_PAUSE, REQUEST_PAUSE);
                } else {
                    updatePictureInPictureActions(R.drawable.ic_play_arrow_24dp, R.string.exo_controls_play_description, CONTROL_TYPE_PLAY, REQUEST_PLAY);
                }
            }

            if (!isScrubbing) {
                if (isPlaying) {
                    if (shortControllerTimeout) {
                        playerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT / 3);
                        shortControllerTimeout = false;
                        restoreControllerTimeout = true;
                    } else {
                        playerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT);
                    }
                } else {
                    playerView.setControllerShowTimeoutMs(-1);
                }
            }

            // unlock on pause or end; isPlaying also drops briefly while buffering
            if (!isPlaying && (player == null || !player.getPlayWhenReady()
                    || player.getPlaybackState() == Player.STATE_ENDED
                    || player.getPlaybackState() == Player.STATE_IDLE)) {
                PlayerActivity.locked = false;
            }
        }

        // if a seek fails to load, the position before it is the one to keep
        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                            @NonNull Player.PositionInfo newPosition,
                                            int reason) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK
                    || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                if (!seekUnconfirmed) {
                    positionBeforeSeek = oldPosition.positionMs;
                    seekUnconfirmed = true;
                }
                if (seekStall != null) {
                    seekStall.onSeek();
                }
            }
        }

        @SuppressLint("SourceLockedOrientationActivity")
        @Override
        public void onPlaybackStateChanged(int state) {
            // delayed so the brief rebuffer after a seek does not flash the spinner
            coordinatorLayout.removeCallbacks(showLoading);
            if (state == Player.STATE_BUFFERING) {
                coordinatorLayout.postDelayed(showLoading, 300);
            } else {
                updateLoading(false);
            }

            boolean isNearEnd = false;
            final long duration = player.getDuration();
            if (duration != C.TIME_UNSET) {
                final long position = player.getCurrentPosition();
                if (position + 4000 >= duration) {
                    isNearEnd = true;
                }
            }
            setEndControlsVisible(haveMedia && (state == Player.STATE_ENDED || isNearEnd));

            if (state == Player.STATE_READY) {
                frameRendered = true;
                seekUnconfirmed = false;

                if (videoLoading) {
                    videoLoading = false;

                    applyVideoShape();

                    if (duration != C.TIME_UNSET && duration > TimeUnit.MINUTES.toMillis(20)) {
                        timeBar.setKeyTimeIncrement(TimeUnit.MINUTES.toMillis(1));
                    } else {
                        timeBar.setKeyCountIncrement(20);
                    }

                    boolean switched = false;
                    if (mPrefs.frameRateMatching) {
                        if (play) {
                            if (displayManager == null) {
                                displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                            }
                            if (displayListener == null) {
                                displayListener = new DisplayManager.DisplayListener() {
                                    @Override
                                    public void onDisplayAdded(int displayId) {

                                    }

                                    @Override
                                    public void onDisplayRemoved(int displayId) {

                                    }

                                    @Override
                                    public void onDisplayChanged(int displayId) {
                                        if (play) {
                                            play = false;
                                            displayManager.unregisterDisplayListener(this);
                                            if (player != null) {
                                                player.play();
                                            }
                                            if (playerView != null) {
                                                playerView.hideController();
                                            }
                                        }
                                    }
                                };
                            }
                            displayManager.registerDisplayListener(displayListener, null);
                        }
                        switched = Utils.switchFrameRate(PlayerActivity.this, mPrefs.mediaUri, play);
                    }
                    if (!switched) {
                        if (displayManager != null) {
                            displayManager.unregisterDisplayListener(displayListener);
                        }
                        if (play) {
                            play = false;
                            player.play();
                            playerView.hideController();
                        }
                    }

                    updateLoading(false);

                    // a saved speed is set even at 1x, to undo the previous film's
                    final float speed = mPrefs.speedForUri(mPrefs.mediaUri);
                    if (speed <= 0.99f || speed >= 1.01f
                            || mPrefs.hasSpeedForUri(mPrefs.mediaUri)) {
                        player.setPlaybackSpeed(speed);
                    }
                    restoreDelays();
                    maybeWarnAboutCapability();
                    if (!apiAccess) {
                        setSelectedTracks(mPrefs.subtitleTrackId, mPrefs.audioTrackId);
                    }
                }
            } else if (state == Player.STATE_ENDED) {
                playbackFinished = true;
                // read before onPlaybackEnded, which cancels it
                final boolean sleepWantedThisEnding =
                        sleepTimer != null && sleepTimer.willStopAtEndOfFile();
                if (sleepTimer != null) {
                    sleepTimer.onPlaybackEnded(player);
                }
                if (apiAccess) {
                    finish();
                    return;
                }
                if (mPrefs.autoPlayNext && neighbours.next != null && !sleepWantedThisEnding) {
                    playInFolder(neighbours.next);
                }
            }
        }


        @Override
        public void onRenderedFirstFrame() {
            pictureSeen = true;
            stopNoPictureWatch();
            Utils.log("First frame on "
                    + (player instanceof com.brouken.player.mpv.MpvPlayer ? "mpv" : "media3"));
            // only a drawn frame counts as played, keeping dead links out of history
            History.markPlayed(mPrefs.mSharedPreferences, mPrefs.mediaUri);
        }

        @Override
        public void onVideoSizeChanged(@NonNull androidx.media3.common.VideoSize videoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                lastVideoSize = videoSize;
            }
            applyVideoShape();
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            selectPendingSubtitle(tracks);
            applyCarriedTracks(tracks);
            keepSubtitleButtonEnabled();
            updateMetaLine();
            logEngineState("tracks");
            final boolean unplayable = hasUnplayableVideo(tracks);
            if (BuildConfig.DEBUG) {
                Utils.log("Tracks: " + tracks.getGroups().size() + " groups, unplayable video: " + unplayable);
            }
            if (unplayable) {
                tryMpvFallback();
            }
        }

        private boolean hasUnplayableVideo(final Tracks tracks) {
            if (tracks.getGroups().isEmpty()) {
                return false; // nothing known yet
            }

            boolean sawVideo = false;
            for (final Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != C.TRACK_TYPE_VIDEO) {
                    continue;
                }
                sawVideo = true;
                for (int i = 0; i < group.length; i++) {
                    if (group.isTrackSupported(i)) {
                        return false;
                    }
                }
            }
            if (sawVideo) {
                return true;
            }

            return looksLikeVideo();
        }

        private boolean looksLikeVideo() {
            if (mPrefs.mediaType != null && mPrefs.mediaType.startsWith("video/")) {
                return true;
            }
            final String name = mPrefs.mediaUri == null
                    ? null
                    : Utils.getFileName(PlayerActivity.this, mPrefs.mediaUri, true);
            return name != null && name.matches(
                    "(?i).*\\.(mkv|mp4|m4v|avi|mov|wmv|asf|flv|ts|m2ts|mpg|mpeg|vob|webm|ogv|rm|rmvb|3gp|divx)$");
        }
        @Override
        public void onPlayerError(PlaybackException error) {
            if (tryMpvFallback()) {
                return;
            }
            updateLoading(false);
            if (offerOtherEngine()) {
                return;
            }
            if (error instanceof ExoPlaybackException) {
                final ExoPlaybackException exoPlaybackException = (ExoPlaybackException) error;
                if (exoPlaybackException.type == ExoPlaybackException.TYPE_SOURCE) {
                    recoverFromSourceError(exoPlaybackException);
                    return;
                }
                if (controllerVisible && controllerVisibleFully) {
                    showError(exoPlaybackException);
                } else {
                    errorToShow = exoPlaybackException;
                }
                return;
            }
            // everything mpv raises ends up here
            PlaybackError.show(PlayerActivity.this, error,
                    useMpvEngine() ? "mpv" : "media3",
                    mPrefs.mediaUri == null ? null : mPrefs.mediaUri.toString());
        }
    }

    private boolean seekUnconfirmed;
    private long positionBeforeSeek;
    @Nullable
    private com.brouken.player.engine.Media3SeekStall seekStall;

    // Media3 reports no error when a seek loads everything but its target
    private void onMedia3SeekStalled() {
        if (player == null || !ownsPlayer() || !(player instanceof ExoPlayer)) {
            return;
        }
        Utils.log("Seek stalled: the stream is loading, but not the part asked for");
        recoverFromSourceError(ExoPlaybackException.createForSource(
                new java.io.IOException("The stream kept loading, but not the part asked for"),
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED));
    }

    private long sourceRetryAtMs;
    private static final long SOURCE_RETRY_INTERVAL_MS = 60_000;

    // saves the last place actually played and rebuilds once;
    // a second failure within a minute is shown
    private void recoverFromSourceError(final ExoPlaybackException error) {
        final boolean wasPlaying = player != null && player.getPlayWhenReady();
        if (player != null && ownsPlayer()) {
            final long safe = seekUnconfirmed ? positionBeforeSeek : player.getCurrentPosition();
            if (safe > 0 && player.isCurrentMediaItemSeekable()) {
                mPrefs.updatePosition(safe,
                        player.getDuration() == C.TIME_UNSET ? 0L : player.getDuration());
            }
        }
        seekUnconfirmed = false;
        releasePlayer(false);

        final long now = android.os.SystemClock.elapsedRealtime();
        if (haveMedia && alive && now - sourceRetryAtMs > SOURCE_RETRY_INTERVAL_MS) {
            sourceRetryAtMs = now;
            Utils.log("Source error, rebuilding once: " + error.getMessage());
            // posted, so it runs outside the failed player's listener dispatch
            coordinatorLayout.post(() -> {
                if (alive && player == null && haveMedia) {
                    playOnLoad = wasPlaying;
                    initializePlayer();
                }
            });
            return;
        }
        if (controllerVisible && controllerVisibleFully) {
            showError(error);
        } else {
            errorToShow = error;
            playerView.showController();
        }
    }

    private boolean offerOtherEngine() {
        return offerOtherEngine(R.string.engine_failed_title, R.string.engine_failed_message);
    }

    private boolean offerOtherEngine(final int title, final int message) {
        // after a picture was shown, a failure is the stream's fault
        if (pictureSeen) {
            return false;
        }
        if (!haveMedia || "auto".equals(mPrefs.playbackEngine)
                || !com.brouken.player.mpv.MpvPlayer.isSupported()) {
            return false;
        }
        final boolean onMpv = "mpv".equals(mPrefs.playbackEngine);
        final String other = onMpv ? "media3" : "mpv";

        Utils.showFocused(new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(getString(message,
                        getString(onMpv ? R.string.pref_engine_mpv : R.string.pref_engine_media3),
                        getString(onMpv ? R.string.pref_engine_media3 : R.string.pref_engine_mpv)))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.engine_failed_auto, (dialog, which) -> {
                    switchEngine("auto");
                })
                .setPositiveButton(getString(R.string.engine_failed_try,
                        getString(onMpv ? R.string.pref_engine_media3 : R.string.pref_engine_mpv)),
                        (dialog, which) -> switchEngine(other))
                .create(), AlertDialog.BUTTON_POSITIVE);
        return true;
    }

    private void switchEngine(final String engine) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putString("playbackEngine", engine).apply();
        mPrefs.loadUserPreferences();
        rebuildPlayer();
    }

    public void enableRotation() {
        try {
            if (Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION) == 0) {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
                restoreOrientationLock = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // not on TV boxes below Android 11: their media store is often nearly empty
    boolean useHomeBrowser() {
        if ("home".equals(mPrefs.fileAccess)) {
            return true;
        }
        if (!"auto".equals(mPrefs.fileAccess)) {
            return false;
        }
        return !(isTvBox && Build.VERSION.SDK_INT < 30);
    }

    boolean useMediaStore() {
        final int targetSdkVersion = getApplicationContext().getApplicationInfo().targetSdkVersion;
        return (isTvBox && Build.VERSION.SDK_INT >= 30 && targetSdkVersion >= 30 && mPrefs.fileAccess.equals("auto")) || mPrefs.fileAccess.equals("mediastore");
    }

    // always plays, including from a saved position: the engines' defaults differ
    void playMedia(final Uri uri, final String type) {
        if (uri == null) {
            return;
        }
        playOnLoad = true;
        releasePlayer();
        setMedia(uri, type);
        initializePlayer();
    }

    private void ensureSkipSegments() {
        if (onlineController == null || mPrefs.mediaUri == null
                || !onlineController.skipEnabled()) {
            return;
        }
        if (BuildConfig.DEBUG) {
            Utils.log("Skip check: remembered="
                    + (onlineController.remembered(mPrefs.mediaUri) != null)
                    + " alreadyLoaded=" + mPrefs.mediaUri.equals(skipLoadedFor));
        }
        if (mPrefs.mediaUri.equals(skipLoadedFor)) {
            // releasing the player stopped the skip timer; restart it
            if (skipController != null) {
                skipController.start();
            }
            return;
        }

        // may be null: a file with its own chapter marks needs no lookup
        final com.brouken.player.online.Identity identity =
                onlineController.remembered(mPrefs.mediaUri);

        skipLoadedFor = mPrefs.mediaUri;

        if (skipController == null) {
            skipController = new com.brouken.player.online.SkipController(this, coordinatorLayout,
                    new com.brouken.player.online.SkipController.Host() {
                        @Override
                        public boolean isPlaying() {
                            return player != null && player.isPlaying();
                        }

                        @Override
                        public double positionSeconds() {
                            return player == null ? -1 : player.getCurrentPosition() / 1000.0;
                        }

                        @Override
                        public double durationSeconds() {
                            if (player == null || player.getDuration() == C.TIME_UNSET) {
                                return 0;
                            }
                            return player.getDuration() / 1000.0;
                        }

                        @Override
                        public void seekToSeconds(double seconds) {
                            if (player != null) {
                                com.brouken.player.engine.SeekPrecision.exact(player);
                                player.seekTo((long) (seconds * 1000));
                            }
                        }

                        @Override
                        public java.util.List<com.brouken.player.online.SkipSegments.ChapterMark> chapters() {
                            if (!(player instanceof com.brouken.player.mpv.MpvPlayer)) {
                                final java.util.List<com.brouken.player.online.SkipSegments.ChapterMark> own =
                                        MatroskaChapters.read(PlayerActivity.this, mPrefs.mediaUri);
                                return own.isEmpty() ? null : own;
                            }
                            final java.util.List<String[]> raw =
                                    ((com.brouken.player.mpv.MpvPlayer) player).chapterList();
                            if (raw == null) {
                                return null;
                            }
                            final java.util.List<com.brouken.player.online.SkipSegments.ChapterMark> marks =
                                    new java.util.ArrayList<>();
                            for (final String[] row : raw) {
                                try {
                                    marks.add(new com.brouken.player.online.SkipSegments.ChapterMark(
                                            row[0], Double.parseDouble(row[1])));
                                } catch (NumberFormatException e) {
                                    // skip a chapter without a usable time
                                }
                            }
                            return marks;
                        }
                    });
        }
        // the card is built lazily, so it is looked up each time
        skipController.avoid(() -> overlayCard == null ? null : overlayCard.box());
        skipController.load(identity);
    }


    // before a pushed-aside card returns; long enough not to flicker back
    private static final long CARD_RETURN_MS = 3_000L;

    private final Runnable overlayShower = () -> {
        if (onlineController == null || mPrefs.mediaUri == null) {
            return;
        }
        if (player == null || player.isPlaying()) {
            return;
        }
        // a file being opened looks paused until its first frame
        if (!pictureSeen || player.getPlaybackState() == Player.STATE_BUFFERING) {
            return;
        }
        final com.brouken.player.online.Identity identity =
                onlineController.rememberedForCard(mPrefs.mediaUri);
        if (identity == null) {
            return;
        }
        if (overlayCard == null) {
            final android.view.View bounds = overlayBounds();
            overlayCard = new com.brouken.player.online.OverlayCard(
                    this, coordinatorLayout, bounds,
                    // on mpv the surface is the whole player, so ask the engine
                    rect -> bounds == playerView.getVideoSurfaceView()
                            && engineUi() != null && engineUi().pictureRect(rect));
        }
        overlayCard.show(identity);
        watchForPoster(identity);
        if (skipController != null) {
            skipController.reposition();
        }

        setCardControlsVisible(true);
    };

    public void showOverlayCardNow() {
        if (onlineController == null || mPrefs.mediaUri == null) {
            return;
        }
        if (onlineController.rememberedForCard(mPrefs.mediaUri) != null) {
            coordinatorLayout.removeCallbacks(overlayShower);
            overlayShower.run();
            return;
        }
        Utils.showText(playerView, getString(R.string.online_identifying));
        final Uri uri = mPrefs.mediaUri;
        onlineController.identifySilently(uri, identity -> {
            if (!uri.equals(mPrefs.mediaUri)) {
                return;
            }
            skipLoadedFor = null;
            ensureSkipSegments();
            coordinatorLayout.removeCallbacks(overlayShower);
            overlayShower.run();
        }, () -> {
            // nothing matched: offer the search box
            if (uri.equals(mPrefs.mediaUri)) {
                reIdentifyOnline();
            }
        });
    }

    private void updateOverlayCard(final boolean isPlaying) {
        if (onlineController == null) {
            return;
        }

        coordinatorLayout.removeCallbacks(overlayShower);

        if (isPlaying || !onlineController.overlayEnabled()) {
            hideOverlayCard();
            return;
        }

        final long delayMs = onlineController.overlayDelaySeconds() * 1000L;
        if (delayMs <= 0) {
            overlayShower.run();
        } else {
            coordinatorLayout.postDelayed(overlayShower, delayMs);
        }
    }

    // the surface, since the frame keeps its layout size in some scaling modes
    private android.view.View overlayBounds() {
        final android.view.View surface = playerView.getVideoSurfaceView();
        if (surface != null) {
            return surface;
        }
        final android.view.View frame =
                playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        return frame != null ? frame : coordinatorLayout;
    }

    public void hideOverlayCardForNow() {
        hideOverlayCard();
        scheduleOverlayReturn();
    }

    @Nullable
    private android.app.AlertDialog cardReturnsWhenClosed(
            @Nullable final android.app.AlertDialog dialog) {
        if (dialog != null) {
            dialog.setOnDismissListener(d -> hideOverlayCardForNow());
        }
        return dialog;
    }

    private void scheduleOverlayReturn() {
        coordinatorLayout.removeCallbacks(overlayShower);
        if (onlineController == null || !onlineController.overlayEnabled()) {
            return;
        }
        if (player == null || player.isPlaying()) {
            return;
        }
        coordinatorLayout.postDelayed(overlayShower, CARD_RETURN_MS);
    }

    public void hideOverlayCard() {
        coordinatorLayout.removeCallbacks(overlayShower);
        if (overlayCard != null) {
            overlayCard.hide();
        }
        if (skipController != null) {
            skipController.reposition();
        }
        setCardControlsVisible(false);
    }


    public void identifyAgain() {
        if (onlineController == null) {
            return;
        }
        hideOverlayCard();
        final Uri uri = mPrefs.mediaUri;
        onlineController.forgetCardTitle(uri);
        // with separate titles, subtitles and skip markers keep the film's own
        if (onlineController.titlesAreLinked()) {
            onlineController.forget(uri);
            skipLoadedFor = null;
        }
        onlineController.identify(this, true, identity -> {
            if (uri == null || !uri.equals(mPrefs.mediaUri)) {
                return;
            }
            // saved so the next automatic guess does not undo it
            onlineController.rememberForCard(uri, identity);
            History.setPoster(androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(this), uri, identity.posterPath);
            ensureSkipSegments();
            updateMetaLine();
            showOverlayCardNow();
        });
    }

    public void copyCurrentLink() {
        Clipboard.copy(this, mPrefs.mediaUri, getTitleForCopy());
    }

    private CharSequence getTitleForCopy() {
        final CharSequence shown = titleView == null ? null : titleView.getText();
        return shown != null && shown.length() > 0 ? shown : getString(R.string.copy_link);
    }

    public void reIdentifyOnline() {
        hideOverlayCard();
        if (onlineController != null) {
            onlineController.forget(mPrefs.mediaUri);
            skipLoadedFor = null;
            onlineController.searchSubtitles(this, true);
        }
    }

    public void searchOnlineSubtitles() {
        hideOverlayCardForNow();
        if (onlineController != null) {
            onlineController.searchSubtitles(this, false);
        }
    }

    // also used by onNewIntent, so it must forget everything about the previous film
    private void openFromLaunch(final Intent intent) {
        playOnLoad = true;

        forgetPreviousFilm();

        // read before the media is set, which clears it
        final String folder = intent.getStringExtra(HomeActivity.EXTRA_FOLDER);

        resetApiAccess();
        final Uri uri = intent.getData();
        if (SubtitleUtils.isSubtitle(uri, intent.getType())) {
            handleSubtitles(uri);
        } else {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                apiAccess = bundle.containsKey(API_POSITION) || bundle.containsKey(API_RETURN_RESULT)
                        || LaunchSubtitles.present(bundle);
                if (apiAccess) {
                    mPrefs.setPersistent(false);
                } else if (bundle.containsKey(API_TITLE)) {
                    apiAccessPartial = true;
                }
                apiTitle = bundle.getString(API_TITLE);
                readApiHeaders(bundle);
            }

            mPrefs.updateMedia(this, uri, intent.getType());
            mPrefs.setMediaTitle(apiTitle);
            // after updateMedia, which creates the history entry this renames
            if (apiTitle != null && !apiTitle.trim().isEmpty()) {
                // a stand-in until the file's own name is known; see History
                History.rename(androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(this), uri, apiTitle, History.NAME_LAUNCHER);
                rememberFileName(uri);
            }
            // after updateMedia, which clears the previous film's folder
            useFolder(folder);

            if (bundle != null) {
                // names and languages match the files by position, so keep the order
                final List<Uri> given =
                        LaunchSubtitles.urisByPosition(bundle, LaunchSubtitles.FILES);
                final List<Uri> toEnable =
                        LaunchSubtitles.uris(bundle, LaunchSubtitles.ENABLE);
                final String[] subsName =
                        LaunchSubtitles.strings(bundle, LaunchSubtitles.NAMES);
                final String[] subsLanguage =
                        LaunchSubtitles.strings(bundle, LaunchSubtitles.LANGUAGES);

                // unreadable or repeated entries are skipped along with their name and language
                final List<Integer> positions = new ArrayList<>();
                final List<Uri> wanted = new ArrayList<>();
                for (int i = 0; i < given.size(); i++) {
                    final Uri candidate = given.get(i);
                    if (candidate != null && !wanted.contains(candidate)) {
                        positions.add(i);
                        wanted.add(candidate);
                    }
                }
                final List<Uri> subs = new SubtitleConverter().convertSubtitles(this, wanted);

                for (int k = 0; k < subs.size(); k++) {
                    Uri sub = subs.get(k);
                    if (sub == null) {
                        continue;
                    }
                    final int i = positions.get(k);
                    final Uri original = wanted.get(k);
                    final String name = subsName.length > i ? subsName[i] : null;
                    final String language = subsLanguage.length > i ? subsLanguage[i] : null;
                    final boolean selected = toEnable.contains(original)
                            || toEnable.contains(sub)
                            || (toEnable.isEmpty() && wanted.size() == 1);
                    // keep our own copy: the sender's read grant ends with this screen
                    final String scheme = sub.getScheme() == null ? "" : sub.getScheme();
                    if (!scheme.startsWith("http")) {
                        final Uri copy = SubtitleFiles.copy(this, sub, null);
                        if (copy != null) {
                            sub = copy;
                        }
                    }
                    apiSubs.add(SubtitleUtils.buildSubtitle(this, sub, name, language, selected));
                }
            }

            if (apiSubs.isEmpty()) {
                searchSubtitles();
            }

            if (apiTitle != null || !apiHeaders.isEmpty() || !apiSubs.isEmpty()) {
                saveLaunchMemory();
            }

            if (bundle != null) {
                intentReturnResult = bundle.getBoolean(API_RETURN_RESULT);

                if (bundle.containsKey(API_POSITION)) {
                    mPrefs.updatePosition((long) bundle.getInt(API_POSITION));
                }
            }
        }
        focusPlay = true;
    }

    private void forgetPreviousFilm() {
        stopPosterWatch();
        stopNoPictureWatch();
        noPictureHits = 0;
        skipLoadedFor = null;
        capabilityAsked = false;
        mpvFallbackActive = false;
        subtitleFailureReported = false;
        sidecarSubtitleUris.clear();
        pictureSeen = false;
        lastVideoSize = null;
        if (skipController != null) {
            skipController.release();
            skipController = null;
        }
        if (overlayCard != null) {
            overlayCard.hide();
        }
    }

    private void setMedia(final Uri uri, final String type) {
        skipLoadedFor = null;
        capabilityAsked = false;
        mpvFallbackActive = false;
        if (skipController != null) {
            skipController.release();
            skipController = null;
        }
        if (overlayCard != null) {
            overlayCard.hide();
        }
        if (onlineController != null) {
            onlineController.forgetResults();
        }
        resetApiAccess();
        restorePlayState = false;
        mPrefs.setPersistent(true);
        mPrefs.updateMedia(this, uri, type);
        // no neighbours until the new film's folder is known
        folderOfCurrent = null;
        setNeighbours(com.brouken.player.home.Neighbours.none());
        searchSubtitles();
    }

    void openFile(Uri pickerInitialUri) {
        if (useHomeBrowser()) {
            // returns a content URI with no persisted grant, like the media store chooser
            final Intent intent = new Intent(this, HomeActivity.class);
            intent.setAction(Intent.ACTION_PICK);
            startActivityForResult(intent, REQUEST_CHOOSER_VIDEO_MEDIASTORE);
        } else if (useMediaStore()) {
            Intent intent = new Intent(this, MediaStoreChooserActivity.class);
            startActivityForResult(intent, REQUEST_CHOOSER_VIDEO_MEDIASTORE);
        } else if ((isTvBox && mPrefs.fileAccess.equals("auto")) || mPrefs.fileAccess.equals("legacy")) {
            Utils.alternativeChooser(this, pickerInitialUri, true);
        } else {
            enableRotation();

            if (pickerInitialUri == null || Utils.isSupportedNetworkUri(pickerInitialUri)) {
                pickerInitialUri = Utils.getMoviesFolderUri();
            }

            final Intent intent = createBaseFileIntent(Intent.ACTION_OPEN_DOCUMENT, pickerInitialUri);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, Utils.supportedMimeTypesVideo);

            if (Build.VERSION.SDK_INT < 30) {
                final ComponentName systemComponentName = Utils.getSystemComponent(this, intent);
                if (systemComponentName != null) {
                    intent.setComponent(systemComponentName);
                }
            }

            safelyStartActivityForResult(intent, REQUEST_CHOOSER_VIDEO);
        }
    }

    private void loadSubtitleFile(Uri pickerInitialUri) {
        Toast.makeText(PlayerActivity.this, R.string.open_subtitles, Toast.LENGTH_SHORT).show();
        final int targetSdkVersion = getApplicationContext().getApplicationInfo().targetSdkVersion;
        if ((isTvBox && Build.VERSION.SDK_INT >= 30 && targetSdkVersion >= 30 && mPrefs.fileAccess.equals("auto")) || mPrefs.fileAccess.equals("mediastore")) {
            Intent intent = new Intent(this, MediaStoreChooserActivity.class);
            intent.putExtra(MediaStoreChooserActivity.SUBTITLES, true);
            keepPlayerForSubtitlePicker();
            startActivityForResult(intent, REQUEST_CHOOSER_SUBTITLE_MEDIASTORE);
        } else if ((isTvBox && mPrefs.fileAccess.equals("auto")) || mPrefs.fileAccess.equals("legacy")) {
            // A dialog over this screen, which never stops: nothing to keep.
            Utils.alternativeChooser(this, pickerInitialUri, false);
        } else {
            enableRotation();

            final Intent intent = createBaseFileIntent(Intent.ACTION_OPEN_DOCUMENT, pickerInitialUri);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");

            final String[] supportedMimeTypes = {
                    MimeTypes.APPLICATION_SUBRIP,
                    MimeTypes.TEXT_SSA,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.APPLICATION_TTML,
                    "text/*",
                    "application/octet-stream"
            };
            intent.putExtra(Intent.EXTRA_MIME_TYPES, supportedMimeTypes);

            if (Build.VERSION.SDK_INT < 30) {
                final ComponentName systemComponentName = Utils.getSystemComponent(this, intent);
                if (systemComponentName != null) {
                    intent.setComponent(systemComponentName);
                }
            }

            if (intent.resolveActivity(getPackageManager()) != null) {
                keepPlayerForSubtitlePicker();
            }
            safelyStartActivityForResult(intent, REQUEST_CHOOSER_SUBTITLE);
        }
    }

    // keep the player paused across the picker, as for the settings trip
    private void keepPlayerForSubtitlePicker() {
        keptPlayerForSettings = player != null && haveMedia;
        settingsWantARebuild = false;
    }

    void attachPickedSubtitle(final Uri uri) {
        attachSubtitle(uri, null);
    }

    private void requestDirectoryAccess() {
        enableRotation();
        final Intent intent = createBaseFileIntent(Intent.ACTION_OPEN_DOCUMENT_TREE, Utils.getMoviesFolderUri());
        safelyStartActivityForResult(intent, REQUEST_CHOOSER_SCOPE_DIR);
    }

    private Intent createBaseFileIntent(final String action, final Uri initialUri) {
        final Intent intent = new Intent(action);

        // http://stackoverflow.com/a/31334967/1615876
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);

        if (Build.VERSION.SDK_INT >= 26 && initialUri != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }

        return intent;
    }

    public void safelyStartActivityForResult(final Intent intent, final int code) {
        if (intent.resolveActivity(getPackageManager()) == null)
            showSnack(getText(R.string.error_files_missing).toString(), intent.toString());
        else
            startActivityForResult(intent, code);
    }

    private TrackGroup getTrackGroupFromFormatId(int trackType, String id) {
        if ((id == null && trackType == C.TRACK_TYPE_AUDIO) || player == null) {
            return null;
        }
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() == trackType) {
                final TrackGroup trackGroup = group.getMediaTrackGroup();
                final Format format = trackGroup.getFormat(0);
                if (Objects.equals(id, format.id)) {
                    return trackGroup;
                }
            }
        }
        return null;
    }

    public void setSelectedTracks(final String subtitleId, final String audioId) {
        if ("#none".equals(subtitleId)) {
            if (trackSelector == null) {
                return;
            }
            trackSelector.setParameters(trackSelector.buildUponParameters().setDisabledTextTrackSelectionFlags(C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED));
        } else if (trackSelector != null) {
            // clear the flags that "None" set, or later subtitles stay refused
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setDisabledTextTrackSelectionFlags(0));
        }

        TrackGroup subtitleGroup = getTrackGroupFromFormatId(C.TRACK_TYPE_TEXT, subtitleId);
        TrackGroup audioGroup = getTrackGroupFromFormatId(C.TRACK_TYPE_AUDIO, audioId);

        TrackSelectionParameters.Builder overridesBuilder = new TrackSelectionParameters.Builder(this);
        TrackSelectionOverride trackSelectionOverride = null;
        final List<Integer> tracks = new ArrayList<>();
        tracks.add(0);
        if (subtitleGroup != null) {
            trackSelectionOverride = new TrackSelectionOverride(subtitleGroup, tracks);
            overridesBuilder.addOverride(trackSelectionOverride);
        }
        if (audioGroup != null) {
            trackSelectionOverride = new TrackSelectionOverride(audioGroup, tracks);
            overridesBuilder.addOverride(trackSelectionOverride);
        }

        if (player != null) {
            TrackSelectionParameters.Builder trackSelectionParametersBuilder = player.getTrackSelectionParameters().buildUpon();
            if (trackSelectionOverride != null) {
                trackSelectionParametersBuilder.setOverrideForType(trackSelectionOverride);
            }
            player.setTrackSelectionParameters(trackSelectionParametersBuilder.build());
        }
    }

    private boolean hasOverrideType(final int trackType) {
        TrackSelectionParameters trackSelectionParameters = player.getTrackSelectionParameters();
        for (TrackSelectionOverride override : trackSelectionParameters.overrides.values()) {
            if (override.getType() == trackType)
                return true;
        }
        return false;
    }

    public String getSelectedTrack(final int trackType) {
        if (player == null) {
            return null;
        }
        Tracks tracks = player.getCurrentTracks();

        // Disabled (e.g. selected subtitle "None" - different than default)
        if (!tracks.isTypeSelected(trackType)) {
            return "#none";
        }

        // Audio track set to "Auto"
        if (trackType == C.TRACK_TYPE_AUDIO) {
            if (!hasOverrideType(C.TRACK_TYPE_AUDIO)) {
                return null;
            }
        }

        for (Tracks.Group group : tracks.getGroups()) {
            if (group.isSelected() && group.getType() == trackType) {
                Format format = group.getMediaTrackGroup().getFormat(0);
                return format.id;
            }
        }

        return null;
    }

    void setSubtitleTextSize() {
        final SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView != null) {
            final CaptioningManager captioningManager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
            SubtitleUtils.updateFractionalTextSize(subtitleView, captioningManager, mPrefs);
        }
    }

    void updateSubtitleViewMargin() {
        if (player == null) {
            return;
        }

        updateSubtitleViewMargin(videoFormat());
    }

    // Set margins to fix PGS aspect as subtitle view is outside of content frame
    void updateSubtitleViewMargin(Format format) {
        if (format == null) {
            return;
        }

        final Rational aspectVideo = Utils.getRational(format);
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        final Rational aspectDisplay = new Rational(metrics.widthPixels, metrics.heightPixels);

        int marginHorizontal = 0;
        int marginVertical = 0;

        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (aspectDisplay.floatValue() > aspectVideo.floatValue()) {
                // Left & right bars
                int videoWidth = metrics.heightPixels / aspectVideo.getDenominator() * aspectVideo.getNumerator();
                marginHorizontal = (metrics.widthPixels - videoWidth) / 2;
            }
        } else {
            // SubtitleView’s height must be the same in both landscape
            // and portrait modes to maintain the same subtitle size.
            DisplayMetrics realMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(realMetrics);
            int minMarginVertical = (realMetrics.heightPixels - realMetrics.widthPixels) / 2;
            if (marginVertical < minMarginVertical) marginVertical = minMarginVertical;
        }

        Utils.setViewParams(playerView.getSubtitleView(), 0, 0, 0, 0,
                marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }

    @TargetApi(26)
    boolean updatePictureInPictureActions(final int iconId, final int resTitle, final int controlType, final int requestCode) {
        try {
            final ArrayList<RemoteAction> actions = new ArrayList<>();
            final PendingIntent intent = PendingIntent.getBroadcast(PlayerActivity.this, requestCode,
                    new Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, controlType), PendingIntent.FLAG_IMMUTABLE);
            final Icon icon = Icon.createWithResource(PlayerActivity.this, iconId);
            final String title = getString(resTitle);
            actions.add(new RemoteAction(icon, title, title, intent));
            ((PictureInPictureParams.Builder) mPictureInPictureParamsBuilder).setActions(actions);
            setPictureInPictureParams(((PictureInPictureParams.Builder) mPictureInPictureParamsBuilder).build());
            return true;
        } catch (IllegalStateException e) {
            // On Samsung devices with Talkback active:
            // Caused by: java.lang.IllegalStateException: setPictureInPictureParams: Device doesn't support picture-in-picture mode.
            e.printStackTrace();
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private boolean isInPip() {
        if (!Utils.isPiPSupported(this))
            return false;
        return isInPictureInPictureMode();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (!isInPip()) {
            setSubtitleTextSize(/*newConfig.orientation*/);
        }
        updateSubtitleViewMargin();

        updateButtonRotation();
    }

    void showError(ExoPlaybackException error) {
        final String errorGeneral = error.getLocalizedMessage();
        String errorDetailed;

        switch (error.type) {
            case ExoPlaybackException.TYPE_SOURCE:
                errorDetailed = error.getSourceException().getLocalizedMessage();
                break;
            case ExoPlaybackException.TYPE_RENDERER:
                errorDetailed = error.getRendererException().getLocalizedMessage();
                break;
            case ExoPlaybackException.TYPE_UNEXPECTED:
                errorDetailed = error.getUnexpectedException().getLocalizedMessage();
                break;
            case ExoPlaybackException.TYPE_REMOTE:
            default:
                errorDetailed = errorGeneral;
                break;
        }

        showSnack(errorGeneral, errorDetailed);
    }

    void showSnack(final String textPrimary, final String textSecondary) {
        int duration = textSecondary != null ? 15000 : Snackbar.LENGTH_SHORT;
        snackbar = Snackbar.make(coordinatorLayout, textPrimary, duration);
        if (textSecondary != null) {
            snackbar.setAction(R.string.error_details, v -> {
                final AlertDialog.Builder builder = new AlertDialog.Builder(PlayerActivity.this);
                builder.setMessage(textSecondary);
                builder.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> dialogInterface.dismiss());
                final AlertDialog dialog = builder.create();
                dialog.show();
            });
        }
        snackbar.setAnchorView(R.id.exo_bottom_bar);
        snackbar.show();
    }

    // opened per drag: it costs a second decoder on the same file
    private void startThumbnails() {
        if (thumbnails != null || !Thumbnails.available(this, mPrefs.mediaUri)) {
            return;
        }
        thumbnails = new Thumbnails(this, mPrefs.mediaUri);
    }

    private void showThumbnail(final long positionMs) {
        if (thumbnails == null || thumbnailView == null) {
            return;
        }
        thumbnails.request(positionMs, (at, bitmap) -> {
            // the drag may have ended before the frame arrived
            if (!isScrubbing || bitmap == null || thumbnailView == null) {
                return;
            }
            thumbnailView.setImageBitmap(bitmap);
            thumbnailView.setVisibility(View.VISIBLE);
        });
    }

    private void hideThumbnail() {
        if (thumbnailView != null) {
            thumbnailView.setVisibility(View.GONE);
            thumbnailView.setImageDrawable(null);
        }
        if (thumbnails != null) {
            thumbnails.release();
            thumbnails = null;
        }
    }

    void reportScrubbing(long position) {
        showThumbnail(position);
        final long diff = position - scrubbingStart;
        if (Math.abs(diff) > 1000) {
            scrubbingNoticeable = true;
        }
        if (scrubbingNoticeable) {
            playerView.clearIcon();
            playerView.setCustomErrorMessage(Utils.formatMilisSign(diff));
        }
        if (frameRendered) {
            frameRendered = false;
            if (player != null) {
                player.seekTo(position);
            }
        }
    }

    private void remeasureOverPicture() {
        playerView.post(() -> {
            if (overlayCard != null) {
                overlayCard.refresh();
            }
            updateSubtitlePictureArea();
        });
    }

    @Nullable
    private androidx.media3.common.VideoSize knownVideoSize() {
        final androidx.media3.common.VideoSize now =
                player == null ? null : player.getVideoSize();
        if (now != null && now.width > 0 && now.height > 0) {
            return now;
        }
        return lastVideoSize;
    }

    private void applyVideoShape() {
        remeasureOverPicture();

        final Format format = videoFormat();
        if (format == null) {
            return;
        }

        // the player resets the frame from the file; mpv's surface must always
        // cover the player so it can draw subtitles on the bars
        if (aspectStep >= 3 || player instanceof com.brouken.player.mpv.MpvPlayer) {
            applyAspectStep(false);
        }

        updateSubtitleViewMargin(format);
        updateSubtitlePictureArea();

        if (mPrefs.refreshSubtitleVerticalPositionForVideoHeight(format.height)) {
            updateSubtitleStyle(this);
            osdSettingsController.updateSubtitlePosition();
        }
    }

    public void updateSubtitleStyle(final Context context) {
        if (engineUi() != null) {
            engineUi().applySubtitleStyle();
        }
    }

    // cues may use the black bars, matching mpv's sub-use-margins
    void updateSubtitlePictureArea() {
        final SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView == null) {
            return;
        }
        subtitleView.post(() -> {
            playerView.cueModifier.setPictureArea(0f, 1f);
            if (player != null && player.isCommandAvailable(Player.COMMAND_GET_TEXT)) {
                subtitleView.setCues(
                        playerView.cueModifier.modifyCues(player.getCurrentCues().cues));
            }
        });
    }

    public void updateSubtitleDelay(int delayMs) {
        mPrefs.updateSubtitleDelay(delayMs);
        applySubtitleDelay();
    }

    private void applySubtitleDelay() {
        int newDelayMs = mPrefs.getSubtitleDelayForUri(mPrefs.mediaUri);
        subtitleDelayMs.set(newDelayMs);

        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            ((com.brouken.player.mpv.MpvPlayer) player).setSubtitleDelayMs(newDelayMs);
        }
    }

    // saved at once, applied 500 ms after the last press
    public void updateAudioDelay(int delayMs) {
        mPrefs.updateAudioDelay(delayMs);
        playerView.removeCallbacks(audioDelayApplyRunnable);
        playerView.postDelayed(audioDelayApplyRunnable, 500);
    }

    private void applyAudioDelay() {
        final int newDelayMs = mPrefs.getAudioDelayForUri(mPrefs.mediaUri);
        final int oldDelayMs = audioDelayMs.get();
        if (player == null || newDelayMs == oldDelayMs) {
            audioDelayMs.set(newDelayMs);
            return;
        }

        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            audioDelayMs.set(newDelayMs);
            ((com.brouken.player.mpv.MpvPlayer) player).setAudioDelayMs(newDelayMs);
            return;
        }

        // Media3's clock never runs back, so only a lower delay needs a seek
        final long soundPositionMs = Math.max(0, player.getCurrentPosition() - oldDelayMs);
        audioDelayMs.set(newDelayMs);
        if (newDelayMs < oldDelayMs) {
            com.brouken.player.engine.SeekPrecision.exact(player);
            player.seekTo(soundPositionMs);
        }
    }

    // mpv's delay properties exist only once the file is open
    private void restoreDelays() {
        final int subtitleDelay = mPrefs.getSubtitleDelayForUri(mPrefs.mediaUri);
        final int audioDelay = mPrefs.getAudioDelayForUri(mPrefs.mediaUri);
        subtitleDelayMs.set(subtitleDelay);
        audioDelayMs.set(audioDelay);
        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            final com.brouken.player.mpv.MpvPlayer mpvPlayer =
                    (com.brouken.player.mpv.MpvPlayer) player;
            mpvPlayer.setSubtitleDelayMs(subtitleDelay);
            mpvPlayer.setAudioDelayMs(audioDelay);
        }
    }

    private void restartPlayback() {
        if (BuildConfig.DEBUG) Utils.log("Restarting playback");
        if (player != null) {
            MediaItem currentItem = player.getCurrentMediaItem();
            if (currentItem != null) {
                long currentPos = player.getCurrentPosition();
                boolean playWhenReady = player.getPlayWhenReady();
                player.setMediaItem(currentItem, currentPos);
                player.setPlayWhenReady(playWhenReady);
                player.prepare();
            }
        }
    }

    void searchSubtitles() {
        if (mPrefs.mediaUri == null)
            return;

        if (Utils.isSupportedNetworkUri(mPrefs.mediaUri) && Utils.isProgressiveContainerUri(mPrefs.mediaUri)) {
            SubtitleUtils.clearCache(this, subtitleFilesInUse());
            if (SubtitleFinder.isUriCompatible(mPrefs.mediaUri)) {
                subtitleFinder = new SubtitleFinder(PlayerActivity.this, mPrefs.mediaUri);
                subtitleFinder.start();
            }
            return;
        }

        if (mPrefs.scopeUri != null || isTvBox) {
            DocumentFile video = null;
            File videoRaw = null;
            final String scheme = mPrefs.mediaUri.getScheme();

            if (mPrefs.scopeUri != null) {
                video = findInScopes(mPrefs.mediaUri);
            } else if (ContentResolver.SCHEME_FILE.equals(scheme)) {
                videoRaw = new File(mPrefs.mediaUri.getSchemeSpecificPart());
                video = DocumentFile.fromFile(videoRaw);
            }

            if (video != null) {
                DocumentFile subtitle = null;
                if (mPrefs.scopeUri != null) {
                    subtitle = SubtitleUtils.findSubtitle(video);
                } else if (ContentResolver.SCHEME_FILE.equals(scheme)) {
                    File parentRaw = videoRaw.getParentFile();
                    DocumentFile dir = DocumentFile.fromFile(parentRaw);
                    subtitle = SubtitleUtils.findSubtitle(video, dir);
                }

                if (subtitle != null) {
                    handleSubtitles(subtitle.getUri());
                }
            }
        }
    }

    // tries each granted folder in turn, newest first
    @Nullable
    private DocumentFile findInScopes(final Uri media) {
        if (media == null) {
            return null;
        }
        final boolean pathInUri =
                "com.android.externalstorage.documents".equals(media.getHost())
                        || "org.courville.nova.provider".equals(media.getHost());

        for (final Uri scope : mPrefs.scopeUris) {
            DocumentFile found = null;
            try {
                if (pathInUri) {
                    found = SubtitleUtils.findUriInScope(this, scope, media);
                }
                // slow match whenever the path match finds nothing
                if (found == null) {
                    final DocumentFile fileScope = DocumentFile.fromTreeUri(this, scope);
                    final DocumentFile fileMedia = DocumentFile.fromSingleUri(this, media);
                    found = SubtitleUtils.findDocInScope(fileScope, fileMedia);
                }
            } catch (SecurityException | IllegalArgumentException e) {
                // revoked grant or removed card; keep looking in the others
                Utils.log("A granted folder could not be read: " + e);
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    Uri findNext() {
        // TODO: Unify with searchSubtitles()
        if (mPrefs.scopeUri != null || isTvBox) {
            DocumentFile video = null;
            File videoRaw = null;

            if (!isTvBox && mPrefs.scopeUri != null) {
                video = findInScopes(mPrefs.mediaUri);
            } else if (isTvBox) {
                videoRaw = new File(mPrefs.mediaUri.getSchemeSpecificPart());
                video = DocumentFile.fromFile(videoRaw);
            }

            if (video != null) {
                DocumentFile next;
                if (!isTvBox) {
                    next = SubtitleUtils.findNext(video);
                } else {
                    File parentRaw = videoRaw.getParentFile();
                    DocumentFile dir = DocumentFile.fromFile(parentRaw);
                    next = SubtitleUtils.findNext(video, dir);
                }
                if (next != null) {
                    return next.getUri();
                }
            }
        }
        return null;
    }

    void askForScope(boolean loadSubtitlesOnCancel, boolean skipToNextOnCancel) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(PlayerActivity.this);
        builder.setMessage(String.format(getString(R.string.request_scope), getString(R.string.app_name)));
        builder.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> requestDirectoryAccess()
        );
        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
            mPrefs.markScopeAsked();
            if (loadSubtitlesOnCancel) {
                loadSubtitleFile(mPrefs.mediaUri);
            }
            if (skipToNextOnCancel) {
                nextUri = findNext();
                if (nextUri != null) {
                    skipToNext();
                }
            }
        });
        final AlertDialog dialog = builder.create();
        Utils.showFocused(dialog, AlertDialog.BUTTON_POSITIVE);
    }

    void resetHideCallbacks() {
        if (haveMedia && player != null && player.isPlaying()) {
            // Keep controller UI visible - alternative to resetHideCallbacks()
            playerView.setControllerShowTimeoutMs(PlayerActivity.CONTROLLER_TIMEOUT);
        }
    }

    private final Runnable showLoading = () -> updateLoading(true);

    private void updateLoading(final boolean enableLoading) {
        if (enableLoading) {
            exoPlayPause.setVisibility(View.GONE);
            loadingProgressBar.setVisibility(View.VISIBLE);
            hideOverlayCard();
        } else {
            loadingProgressBar.setVisibility(View.GONE);
            exoPlayPause.setVisibility(View.VISIBLE);
            if (player != null && !player.isPlaying()) {
                updateOverlayCard(false);
            }
            if (focusPlay) {
                focusPlay = false;
                exoPlayPause.requestFocus();
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onUserLeaveHint() {
        if (mPrefs != null && mPrefs.autoPiP && player != null && player.isPlaying() && Utils.isPiPSupported(this))
            enterPiP();
        else
            super.onUserLeaveHint();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void enterPiP() {
        hideOverlayCard();
        final AppOpsManager appOpsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (AppOpsManager.MODE_ALLOWED != appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), getPackageName())) {
            final Intent intent = new Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", Uri.fromParts("package", getPackageName(), null));
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            }
            return;
        }

        if (player == null) {
            return;
        }

        playerView.setControllerAutoShow(false);
        playerView.hideController();

        final Format format = videoFormat();

        // Media3: https://github.com/google/ExoPlayer/issues/8611; mpv's surface follows the window
        if (engineUi() != null) {
            engineUi().onEnterPip(format);
            pipPrepared = true;
        }

        if (format != null) {
            Rational rational = Utils.getRational(format);
            if (Build.VERSION.SDK_INT >= 33 &&
                    getPackageManager().hasSystemFeature(FEATURE_EXPANDED_PICTURE_IN_PICTURE) &&
                    (rational.floatValue() > rationalLimitWide.floatValue() || rational.floatValue() < rationalLimitTall.floatValue())) {
                ((PictureInPictureParams.Builder) mPictureInPictureParamsBuilder).setExpandedAspectRatio(rational);
            }
            if (rational.floatValue() > rationalLimitWide.floatValue())
                rational = rationalLimitWide;
            else if (rational.floatValue() < rationalLimitTall.floatValue())
                rational = rationalLimitTall;

            ((PictureInPictureParams.Builder) mPictureInPictureParamsBuilder).setAspectRatio(rational);
        }
        boolean entered;
        try {
            entered = enterPictureInPictureMode(
                    ((PictureInPictureParams.Builder) mPictureInPictureParamsBuilder).build());
        } catch (IllegalStateException | IllegalArgumentException e) {
            entered = false;
        }
        if (!entered && pipPrepared) {
            // refused: undo the surface preparation
            pipPrepared = false;
            if (engineUi() != null) {
                engineUi().onExitPip();
            }
        }
    }

    void setEndControlsVisible(boolean visible) {
        final int deleteVisible = (visible && haveMedia && Utils.isDeletable(this, mPrefs.mediaUri)) ? View.VISIBLE : View.GONE;
        // a known neighbour keeps Next on screen for the whole film
        final boolean endOffer = visible && haveMedia
                && (nextUri != null || (mPrefs.askScope && !isTvBox));
        final int nextVisible = (endOffer || neighbours.next != null)
                ? View.VISIBLE : View.GONE;
        findViewById(R.id.delete).setVisibility(deleteVisible);
        findViewById(R.id.next).setVisibility(nextVisible);
    }

    void askDeleteMedia() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(PlayerActivity.this);
        builder.setMessage(getString(R.string.delete_query));
        builder.setPositiveButton(R.string.delete_confirmation, (dialogInterface, i) -> {
            releasePlayer();
            deleteMedia();
            if (nextUri == null) {
                haveMedia = false;
                setEndControlsVisible(false);
                playerView.setControllerShowTimeoutMs(-1);
            } else {
                skipToNext();
            }
        });
        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
        });
        final AlertDialog dialog = builder.create();
        // focus Cancel so a stray OK on a remote cannot delete the file
        Utils.showFocused(dialog, AlertDialog.BUTTON_NEGATIVE);
    }

    void deleteMedia() {
        try {
            if (ContentResolver.SCHEME_CONTENT.equals(mPrefs.mediaUri.getScheme())) {
                DocumentsContract.deleteDocument(getContentResolver(), mPrefs.mediaUri);
            } else if (ContentResolver.SCHEME_FILE.equals(mPrefs.mediaUri.getScheme())) {
                final File file = new File(mPrefs.mediaUri.getSchemeSpecificPart());
                if (file.canWrite()) {
                    file.delete();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void dispatchPlayPause() {
        if (player == null)
            return;

        @Player.State int state = player.getPlaybackState();
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED || !player.getPlayWhenReady()) {
            shortControllerTimeout = true;
            androidx.media3.common.util.Util.handlePlayButtonAction(player);
        } else {
            androidx.media3.common.util.Util.handlePauseButtonAction(player);
        }
    }

    // playMedia forgets the folder, so it is handed back afterwards
    private void playInFolder(final Uri uri) {
        final String folder = folderOfCurrent;
        playMedia(uri, null);
        useFolder(folder);
    }

    // neighbours come from a media store query, so off the main thread
    private void useFolder(@Nullable final String folder) {
        folderOfCurrent = folder;
        setNeighbours(com.brouken.player.home.Neighbours.none());
        if (folder == null || mPrefs.mediaUri == null) {
            return;
        }
        if (neighboursThread != null) {
            neighboursThread.interrupt();
        }
        final Uri asked = mPrefs.mediaUri;
        neighboursThread = new Thread(Background.safely(() -> {
            final com.brouken.player.home.Neighbours.Either found =
                    com.brouken.player.home.Neighbours.of(this, folder, asked);
            runOnUiThread(() -> {
                // another film may have been opened meanwhile
                if (asked.equals(mPrefs.mediaUri)) {
                    setNeighbours(found);
                }
            });
        }), "neighbours");
        neighboursThread.start();
    }

    private void setNeighbours(final com.brouken.player.home.Neighbours.Either found) {
        neighbours = found;
        updateSkipButtons();
    }

    private void updateSkipButtons() {
        final View previous = findViewById(R.id.prev);
        if (previous != null) {
            previous.setVisibility(neighbours.previous != null ? View.VISIBLE : View.GONE);
        }
        final View next = findViewById(R.id.next);
        if (next != null && neighbours.next != null) {
            next.setVisibility(View.VISIBLE);
        }
    }

    void skipToNext() {
        if (nextUri != null) {
            playOnLoad = true;
            releasePlayer();
            resetApiAccess();
            mPrefs.updateMedia(this, nextUri, null);
            searchSubtitles();
            initializePlayer();
        }
    }

    void notifyAudioSessionUpdate(final boolean active) {
        final Intent intent = new Intent(active ? AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                : AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId());
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
        if (active) {
            intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE);
        }
        try {
            sendBroadcast(intent);
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    void updateButtons(final boolean enable) {
        if (buttonPiP != null) {
            Utils.setButtonEnabled(this, buttonPiP, enable);
        }
        Utils.setButtonEnabled(this, buttonAspectRatio, enable);
        // always enabled: the panel is useful before a file is open
        Utils.setButtonEnabled(this, exoSettings, true);
    }

    // Media3 scales the view; mpv zooms inside its surface, so subtitles stay put
    private void scaleStart() {
        isScaling = true;
        final com.brouken.player.engine.EngineUi ui = engineUi();
        if (ui != null && ui.zoomsInEngine()) {
            scaleFactor = ui.zoom();
        } else {
            if (playerView.getResizeMode() != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            }
            scaleFactor = playerView.getVideoSurfaceView().getScaleX();
        }
        playerView.removeCallbacks(playerView.textClearRunnable);
        playerView.clearIcon();
        playerView.setCustomErrorMessage((int) (scaleFactor * 100) + "%");
        playerView.hideController();
        isScaleStarting = true;
    }

    private float zoomFit() {
        final com.brouken.player.engine.EngineUi ui = engineUi();
        return ui != null ? ui.zoomFit() : playerView.getScaleFit();
    }

    private void scale(boolean up) {
        if (up) {
            scaleFactor += 0.01;
        } else {
            scaleFactor -= 0.01;
        }
        scaleFactor = Utils.normalizeScaleFactor(scaleFactor, zoomFit());
        final com.brouken.player.engine.EngineUi ui = engineUi();
        if (ui != null) {
            ui.setZoom(scaleFactor);
        } else {
            playerView.setScale(scaleFactor);
        }
        playerView.setCustomErrorMessage((int) (scaleFactor * 100) + "%");
    }

    private void scaleEnd() {
        isScaling = false;
        playerView.postDelayed(playerView.textClearRunnable, 200);
        if (player != null && !player.isPlaying()) {
            playerView.showController();
        }
        if (Math.abs(zoomFit() - scaleFactor) < 0.01 / 2) {
            final com.brouken.player.engine.EngineUi ui = engineUi();
            if (ui != null && ui.zoomsInEngine()) {
                ui.setZoom(1f);
            } else {
                playerView.setScale(1.f);
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            }
        }
        updatebuttonAspectRatioIcon();
    }

    private boolean zoomedByHand() {
        final com.brouken.player.engine.EngineUi ui = engineUi();
        if (ui != null && ui.zoomsInEngine()) {
            return ui.zoom() > 1.001f;
        }
        return playerView.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
    }

    private void updatebuttonAspectRatioIcon() {
        // A pinch has put the picture somewhere none of the steps describes.
        if (aspectStep == 0 && zoomedByHand()) {
            buttonAspectRatio.setImageResource(R.drawable.ic_fit_screen_24dp);
            return;
        }
        final int step = aspectStep >= 0 && aspectStep < ASPECT_ICONS.length ? aspectStep : 0;
        buttonAspectRatio.setImageResource(ASPECT_ICONS[step]);
    }


    private void applyImmediatePreferences() {
        if (youTubeOverlay != null) {
            youTubeOverlay.seekSeconds(mPrefs.doubleTapSeekSeconds);
        }
        // the running player is not rebuilt after settings, so tell it directly
        if (trackSelector != null) {
            final String[] audioLanguages = Languages.audio(this);
            final String[] textLanguages = Languages.subtitle(this);
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setPreferredAudioLanguages(audioLanguages)
                    .setPreferredTextLanguages(textLanguages));
        }
        updateSubtitleStyle(this);
        applyVolumeBoost();
        applyKeepScreenOn(player != null && player.isPlaying());
        updateClock();
        // set both ways here; Media3 only, mpv has no equivalent
        if (exo() != null) {
            exo().setSkipSilenceEnabled(mPrefs.skipSilence);
        }
        if (onlineController != null && !onlineController.skipEnabled()) {
            updateSkipEnabled(false);
        }
    }


    private static final int VOLUME_BOOST_GAIN_MB = Utils.BOOST_STEPS * Utils.BOOST_STEP_MB;

    // mpv exposes no audio session for the effect, so its boost is the volume property
    static boolean canBoostVolume() {
        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            return true;
        }
        try {
            return loudnessEnhancer != null && loudnessEnhancer.hasControl();
        } catch (Exception e) {
            return false;
        }
    }

    // mpv has one volume property, so fine volume and boost are multiplied here;
    // on Media3 the boost is a LoudnessEnhancer left to the caller
    static void applyEngineVolume(final boolean boostEnabled) {
        if (player == null) {
            return;
        }
        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            final int boost = boostEnabled ? Utils.boostedPercent() : 100;
            ((com.brouken.player.mpv.MpvPlayer) player)
                    .setVolumePercent(Math.round(fineVolume * boost));
            return;
        }
        player.setVolume(fineVolume);
    }

    static void applyEngineVolume() {
        applyEngineVolume(boostLevel > 0);
    }

    static void applyBoostLevel(final boolean enabled) {
        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            applyEngineVolume(enabled);
            return;
        }
        applyEngineVolume(false);
        if (loudnessEnhancer == null) {
            return;
        }
        try {
            loudnessEnhancer.setTargetGain(boostLevel * Utils.BOOST_STEP_MB);
            loudnessEnhancer.setEnabled(enabled);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void applyVolumeBoost() {
        // a rebuilt player starts at full volume
        applyEngineVolume();
        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            boostLevel = mPrefs.volumeBoost ? Utils.BOOST_STEPS : 0;
            applyBoostLevel(mPrefs.volumeBoost);
            return;
        }
        if (loudnessEnhancer == null) {
            return;
        }
        try {
            if (mPrefs.volumeBoost) {
                boostLevel = Utils.BOOST_STEPS;
                loudnessEnhancer.setTargetGain(VOLUME_BOOST_GAIN_MB);
                loudnessEnhancer.setEnabled(true);
                if (BuildConfig.DEBUG) {
                    Utils.log("Volume boost: gain=" + loudnessEnhancer.getTargetGain()
                            + "mB enabled=" + loudnessEnhancer.getEnabled()
                            + " control=" + loudnessEnhancer.hasControl());
                }
            } else if (boostLevel == Utils.BOOST_STEPS) {
                // only undo our boost; a level set by gesture stays
                boostLevel = 0;
                loudnessEnhancer.setEnabled(false);
            }
        } catch (Exception e) {
            // a device that refuses the effect plays at normal volume
            Utils.log("Volume boost unavailable: " + e);
        }
    }

    // e.g. 3840×2160 · HEVC · HDR · E-AC-3 5.1, from the selected tracks
    void updateMetaLine() {
        if (metaView == null) {
            return;
        }
        if (player == null) {
            metaView.setVisibility(View.GONE);
            return;
        }

        final StringBuilder line = new StringBuilder();

        Format video = null;
        Format audio = null;
        for (final Tracks.Group group : player.getCurrentTracks().getGroups()) {
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSelected(i)) {
                    continue;
                }
                if (group.getType() == C.TRACK_TYPE_VIDEO && video == null) {
                    video = group.getTrackFormat(i);
                } else if (group.getType() == C.TRACK_TYPE_AUDIO && audio == null) {
                    audio = group.getTrackFormat(i);
                }
            }
        }

        // the engine reports a size even when the track carried none
        final androidx.media3.common.VideoSize size = player.getVideoSize();
        if (size.width > 0 && size.height > 0) {
            appendMeta(line, size.width + "\u00d7" + size.height);
        } else if (video != null && video.width > 0 && video.height > 0) {
            appendMeta(line, video.width + "\u00d7" + video.height);
        }

        if (video != null) {
            appendMeta(line, TrackNames.codec(video));
            if (video.frameRate > 0) {
                appendMeta(line, Math.round(video.frameRate * 100) / 100f + " fps");
            }
            if (video.colorInfo != null && video.colorInfo.colorTransfer != Format.NO_VALUE
                    && (video.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084
                    || video.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG)) {
                appendMeta(line, "HDR");
            }
        }

        if (audio != null) {
            final StringBuilder sound = new StringBuilder();
            final String codec = TrackNames.codec(audio);
            if (codec != null) {
                sound.append(codec);
            }
            if (audio.channelCount == 6) {
                sound.append(sound.length() > 0 ? " " : "").append("5.1");
            } else if (audio.channelCount == 8) {
                sound.append(sound.length() > 0 ? " " : "").append("7.1");
            } else if (audio.channelCount > 0) {
                sound.append(sound.length() > 0 ? " " : "").append(audio.channelCount).append("ch");
            }
            appendMeta(line, sound.toString());
        }

        appendMeta(line, player instanceof com.brouken.player.mpv.MpvPlayer ? "mpv" : "Media3");

        appendMeta(line, streamSpeed());

        metaView.setText(line.toString());
        metaView.setVisibility(line.length() == 0 ? View.GONE : View.VISIBLE);
    }

    // a decoder that cannot keep up stops producing frames without any error,
    // so warn first; the film is paused while the dialog is up
    private void maybeWarnAboutCapability() {
        if (capabilityAsked || !haveMedia || player == null) {
            return;
        }
        capabilityAsked = true;

        if (!androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("warnCapability", true)) {
            return;
        }

        final Format video = selectedVideoFormat();
        if (video == null || video.width <= 0 || video.height <= 0) {
            return;
        }
        final int bitrate = video.bitrate > 0 ? video.bitrate : video.peakBitrate;
        final Capability.Verdict verdict = Capability.check(video.sampleMimeType,
                video.width, video.height, bitrate);
        if (verdict == Capability.Verdict.FINE) {
            return;
        }

        final boolean onMpv = player instanceof com.brouken.player.mpv.MpvPlayer;
        final boolean wasPlaying = player.isPlaying();
        if (wasPlaying) {
            player.pause();
        }

        final StringBuilder detail = new StringBuilder();
        detail.append(video.width).append('×').append(video.height);
        if (video.frameRate > 0) {
            detail.append(" · ").append(Math.round(video.frameRate)).append(" fps");
        }
        final String codec = TrackNames.codec(video);
        if (codec != null && !codec.isEmpty()) {
            detail.append(" · ").append(codec);
        }
        detail.append("\n\n").append(getString(verdict == Capability.Verdict.IMPOSSIBLE
                ? R.string.capability_impossible : R.string.capability_hard));

        @SuppressLint("InflateParams")
        final View body = getLayoutInflater().inflate(R.layout.dialog_capability, null);
        ((TextView) body.findViewById(R.id.capability_detail)).setText(detail.toString());
        final android.widget.CheckBox dontWarn = body.findViewById(R.id.capability_dont_warn);

        final Runnable rememberChoice = () -> {
            if (dontWarn.isChecked()) {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().putBoolean("warnCapability", false).apply();
            }
        };

        final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.capability_title)
                .setView(body)
                .setCancelable(false)
                .setPositiveButton(R.string.capability_continue, (dialog, which) -> {
                    rememberChoice.run();
                    if (wasPlaying && player != null) {
                        player.play();
                    }
                })
                .setNegativeButton(R.string.capability_quit, (dialog, which) -> {
                    rememberChoice.run();
                    finish();
                });

        if (Capability.otherEngineMightDoBetter(verdict, onMpv)) {
            final String other = getString(R.string.pref_engine_mpv);
            builder.setNeutralButton(getString(R.string.capability_try_other, other),
                    (dialog, which) -> {
                        rememberChoice.run();
                        androidx.preference.PreferenceManager
                                .getDefaultSharedPreferences(PlayerActivity.this)
                                .edit().putString("playbackEngine", "mpv").apply();
                        mPrefs.loadUserPreferences();
                        // ask again on the other engine
                        capabilityAsked = false;
                        rebuildPlayer();
                    });
        }

        Utils.showFocused(builder.create(), AlertDialog.BUTTON_POSITIVE);
    }

    @Nullable
    private Format selectedVideoFormat() {
        if (player != null) {
            for (final Tracks.Group group : player.getCurrentTracks().getGroups()) {
                if (group.getType() != C.TRACK_TYPE_VIDEO) {
                    continue;
                }
                for (int i = 0; i < group.length; i++) {
                    if (group.isTrackSelected(i)) {
                        return group.getTrackFormat(i);
                    }
                }
            }
        }
        return videoFormat();
    }

    // Media3 counts data source bytes; mpv reports its cache fill rate
    @Nullable
    private String streamSpeed() {
        if (player == null || !Utils.isSupportedNetworkUri(mPrefs.mediaUri)) {
            return null;
        }
        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            return com.brouken.player.net.NetworkSpeed.format(
                    ((com.brouken.player.mpv.MpvPlayer) player).cacheSpeedBytesPerSecond());
        }
        return networkSpeed == null ? null
                : com.brouken.player.net.NetworkSpeed.format(networkSpeed.bytesPerSecond());
    }

    private final Runnable metaTick = new Runnable() {
        @Override
        public void run() {
            if (titleBar == null || titleBar.getVisibility() != View.VISIBLE) {
                return;
            }
            updateMetaLine();
            playerView.postDelayed(this, 1000);
        }
    };

    private void startMetaTicking() {
        if (playerView == null) {
            return;
        }
        playerView.removeCallbacks(metaTick);
        if (Utils.isSupportedNetworkUri(mPrefs.mediaUri)) {
            playerView.postDelayed(metaTick, 1000);
        }
    }

    private void stopMetaTicking() {
        if (playerView != null) {
            playerView.removeCallbacks(metaTick);
        }
    }

    private static void appendMeta(final StringBuilder line, final String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (line.length() > 0) {
            line.append("  \u00b7  ");
        }
        line.append(part);
    }

    // an exact seek decodes from the last keyframe, which lags behind a drag
    private static void seekToKeyframes(final SeekParameters parameters) {
        com.brouken.player.engine.SeekPrecision.apply(player, parameters);
    }

    private void applyKeepScreenOn(final boolean isPlaying) {
        playerView.setKeepScreenOn(isPlaying || mPrefs.keepScreenOn);
    }

    // asks for a folder first so subtitles can be found automatically; never on a TV
    void openSubtitleFilePicker() {
        if (!isTvBox && mPrefs.askScope) {
            askForScope(true, false);
        } else {
            loadSubtitleFile(mPrefs.mediaUri);
        }
    }

    private void showSubtitleMenu() {
        hideOverlayCardForNow();
        final List<SubtitleChoice> choices = new ArrayList<>();

        final Tracks tracks = player == null ? Tracks.EMPTY : player.getCurrentTracks();
        boolean anySelected = false;
        for (final Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                final Format format = group.getTrackFormat(i);
                final boolean selected = group.isTrackSelected(i);
                anySelected |= selected;
                choices.add(SubtitleChoice.track(this, group, i, format, selected));
            }
        }

        choices.add(SubtitleChoice.off(this, !anySelected));
        choices.add(SubtitleChoice.action(getString(R.string.subtitle_source_file),
                getString(R.string.subtitle_menu_file_detail), this::openSubtitleFilePicker));
        choices.add(SubtitleChoice.action(getString(R.string.online_search_subtitles),
                getString(R.string.subtitle_menu_search_detail), this::searchOnlineSubtitles));
        choices.add(SubtitleChoice.action(getString(R.string.osd_subtitle_title),
                getString(R.string.subtitle_menu_settings_detail),
                () -> osdSettingsController.showSubtitleSettings()));

        cardReturnsWhenClosed(com.brouken.player.online.ListPicker.show(
                this, getString(R.string.subtitle_menu_title), choices,
                index -> choices.get(index).run()));
    }

    private static final class SubtitleChoice implements com.brouken.player.online.ListPicker.Row {
        private boolean current;

        @Override
        public boolean current() {
            return current;
        }

        private final String title;
        private final String detail;
        private final Runnable action;

        private SubtitleChoice(String title, String detail, Runnable action) {
            this.title = title;
            this.detail = detail;
            this.action = action;
        }

        static SubtitleChoice action(String title, String detail, Runnable action) {
            return new SubtitleChoice(title, detail, action);
        }

        static SubtitleChoice off(final PlayerActivity activity, final boolean current) {
            return marked(current, new SubtitleChoice(activity.getString(R.string.subtitle_menu_off),
                    current ? activity.getString(R.string.subtitle_menu_current) : null,
                    () -> activity.selectTextTrack(null, 0)));
        }

        static SubtitleChoice track(final PlayerActivity activity, final Tracks.Group group,
                                    final int index, final Format format, final boolean selected) {
            return marked(selected, new SubtitleChoice(
                    TrackNames.title(activity, format, index, C.TRACK_TYPE_TEXT),
                    TrackNames.detail(activity, format, selected),
                    () -> activity.selectTextTrack(group, index)));
        }

        private static SubtitleChoice marked(final boolean current, final SubtitleChoice choice) {
            choice.current = current;
            return choice;
        }

        @NonNull
        @Override
        public String title() {
            return title;
        }

        @Nullable
        @Override
        public String detail() {
            return detail;
        }

        void run() {
            action.run();
        }
    }


    public void showVideoMenu() {
        hideOverlayCardForNow();
        final List<VideoChoice> choices = new ArrayList<>();
        final Tracks tracks = player == null ? Tracks.EMPTY : player.getCurrentTracks();

        choices.add(VideoChoice.auto(this, !hasOverrideType(C.TRACK_TYPE_VIDEO)));
        for (final Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                choices.add(VideoChoice.track(this, group, i,
                        group.getTrackFormat(i), group.isTrackSelected(i)));
            }
        }

        if (choices.size() < 2) {
            Utils.showText(playerView, getString(R.string.video_menu_none));
            return;
        }

        cardReturnsWhenClosed(com.brouken.player.online.ListPicker.show(
                this, getString(R.string.video_menu_title),
                choices, index -> choices.get(index).run()));
    }

    public int videoTrackCount() {
        if (player == null) {
            return 0;
        }
        int count = 0;
        for (final Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() == C.TRACK_TYPE_VIDEO) {
                count += group.length;
            }
        }
        return count;
    }

    private static final class VideoChoice implements com.brouken.player.online.ListPicker.Row {

        private final String title;
        private final String detail;
        private final Runnable action;
        private boolean current;

        @Override
        public boolean current() {
            return current;
        }

        private VideoChoice(String title, String detail, Runnable action) {
            this.title = title;
            this.detail = detail;
            this.action = action;
        }

        static VideoChoice auto(final PlayerActivity activity, final boolean current) {
            return marked(current, new VideoChoice(activity.getString(R.string.video_menu_auto),
                    current ? activity.getString(R.string.subtitle_menu_current) : null,
                    () -> {
                        if (activity.player == null) {
                            return;
                        }
                        activity.player.setTrackSelectionParameters(
                                activity.player.getTrackSelectionParameters().buildUpon()
                                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                        .build());
                    }));
        }

        private static VideoChoice marked(final boolean current, final VideoChoice choice) {
            choice.current = current;
            return choice;
        }

        static VideoChoice track(final PlayerActivity activity, final Tracks.Group group,
                                 final int index, final Format format, final boolean selected) {
            // a bitrate ladder rung has no name, only a height
            final String name = format.height > 0
                    ? format.height + "p"
                    : TrackNames.title(activity, format, index, C.TRACK_TYPE_VIDEO);
            return marked(selected, new VideoChoice(name, TrackNames.detail(activity, format, selected),
                    () -> {
                        if (activity.player == null) {
                            return;
                        }
                        final List<Integer> selection = new ArrayList<>();
                        selection.add(index);
                        activity.player.setTrackSelectionParameters(
                                activity.player.getTrackSelectionParameters().buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                                        .setOverrideForType(new TrackSelectionOverride(
                                                group.getMediaTrackGroup(), selection))
                                        .build());
                    }));
        }

        @NonNull
        @Override
        public String title() {
            return title;
        }

        @Nullable
        @Override
        public String detail() {
            return detail;
        }

        void run() {
            action.run();
        }
    }

    public void showAudioMenu() {
        hideOverlayCardForNow();
        final List<AudioChoice> choices = new ArrayList<>();
        final Tracks tracks = player == null ? Tracks.EMPTY : player.getCurrentTracks();

        for (final Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                choices.add(new AudioChoice(this, group, i,
                        group.getTrackFormat(i), group.isTrackSelected(i)));
            }
        }

        // the delay as a row that opens its control: a list picker has no arrows
        final List<Runnable> actions = new ArrayList<>();
        for (final AudioChoice choice : choices) {
            actions.add(choice::select);
        }
        final List<com.brouken.player.online.ListPicker.Row> rows = new ArrayList<>(choices);
        if (!rows.isEmpty()) {
            rows.add(new AudioAction(getString(R.string.osd_audio_delay_title),
                    getString(R.string.audio_menu_delay_detail)));
            actions.add(() -> osdSettingsController.showAudioSettings());
        }

        if (choices.isEmpty()) {
            Utils.showText(playerView, getString(R.string.audio_menu_none));
            return;
        }

        cardReturnsWhenClosed(com.brouken.player.online.ListPicker.show(
                this, getString(R.string.audio_menu_title),
                rows, index -> actions.get(index).run()));
    }

    private static final class AudioAction implements com.brouken.player.online.ListPicker.Row {

        private final String title;
        private final String detail;

        AudioAction(String title, String detail) {
            this.title = title;
            this.detail = detail;
        }

        @NonNull
        @Override
        public String title() {
            return title;
        }

        @Nullable
        @Override
        public String detail() {
            return detail;
        }

        @Override
        public boolean current() {
            return false;
        }
    }

    int audioTrackCount() {
        if (player == null) {
            return 0;
        }
        int count = 0;
        for (final Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() == C.TRACK_TYPE_AUDIO) {
                count += group.length;
            }
        }
        return count;
    }

    private static final class AudioChoice implements com.brouken.player.online.ListPicker.Row {

        private final PlayerActivity activity;
        private final Tracks.Group group;
        private final int index;
        private final Format format;
        private final boolean selected;

        AudioChoice(PlayerActivity activity, Tracks.Group group, int index,
                    Format format, boolean selected) {
            this.activity = activity;
            this.group = group;
            this.index = index;
            this.format = format;
            this.selected = selected;
        }

        @NonNull
        @Override
        public String title() {
            return TrackNames.title(activity, format, index, C.TRACK_TYPE_AUDIO);
        }

        @Nullable
        @Override
        public String detail() {
            return TrackNames.detail(activity, format, selected);
        }

        @Override
        public boolean current() {
            return selected;
        }

        void select() {
            if (activity.player == null) {
                return;
            }
            final List<Integer> selection = new ArrayList<>();
            selection.add(index);
            activity.player.setTrackSelectionParameters(
                    activity.player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(new TrackSelectionOverride(
                                    group.getMediaTrackGroup(), selection))
                            .build());
        }
    }

    private void logEngineState(final String when) {
        if (!BuildConfig.DEBUG || player == null) {
            return;
        }
        int audio = 0;
        int text = 0;
        int video = 0;
        for (final Tracks.Group group : player.getCurrentTracks().getGroups()) {
            switch (group.getType()) {
                case C.TRACK_TYPE_AUDIO: audio += group.length; break;
                case C.TRACK_TYPE_TEXT: text += group.length; break;
                case C.TRACK_TYPE_VIDEO: video += group.length; break;
                default: break;
            }
        }
        Utils.log("PARITY " + when
                + " engine=" + (player instanceof com.brouken.player.mpv.MpvPlayer ? "mpv" : "media3")
                + " state=" + player.getPlaybackState()
                + " items=" + player.getMediaItemCount()
                + " seekable=" + player.isCurrentMediaItemSeekable()
                + " live=" + player.isCurrentMediaItemLive()
                + " duration=" + player.getDuration()
                + " position=" + player.getCurrentPosition()
                + " buffered=" + player.getBufferedPosition()
                + " speed=" + player.getPlaybackParameters().speed
                + " volume=" + player.getVolume()
                + " videoSize=" + player.getVideoSize().width + "x" + player.getVideoSize().height
                + " tracks(v/a/t)=" + video + "/" + audio + "/" + text
                + " cmdSeek=" + player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                + " cmdSpeed=" + player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)
                + " cmdTracks=" + player.isCommandAvailable(Player.COMMAND_GET_TRACKS));
    }
    void selectTextTrack(@Nullable final Tracks.Group group, final int index) {
        if (player == null) {
            return;
        }
        // report a failure of the new choice too
        subtitleFailureReported = false;
        final TrackSelectionParameters.Builder builder =
                player.getTrackSelectionParameters().buildUpon();

        if (group == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT);
        } else {
            final List<Integer> selection = new ArrayList<>();
            selection.add(index);
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(
                            group.getMediaTrackGroup(), selection));
        }
        player.setTrackSelectionParameters(builder.build());
    }

    private void keepSubtitleButtonEnabled() {
        if (subtitleButton != null && !subtitleButton.isEnabled()) {
            subtitleButton.setEnabled(true);
            subtitleButton.setAlpha(1f);
        }
    }
    void updateButtonPlayPause() {
        if (buttonPlayPause != null) {
            final boolean playing = player != null && player.isPlaying();
            buttonPlayPause.setImageResource(playing
                    ? R.drawable.ic_pause_24dp
                    : R.drawable.ic_play_arrow_24dp);
            buttonPlayPause.setContentDescription(getString(playing
                    ? R.string.exo_controls_pause_description
                    : R.string.exo_controls_play_description));
        }
    }


    // mPrefs.subtitleUris minus files that have gone, in engine order
    private List<Uri> sideloadedSubtitleUris() {
        final List<Uri> uris = new ArrayList<>();
        for (final Uri uri : mPrefs.subtitleUris) {
            if (Utils.fileExists(this, uri)) {
                uris.add(uri);
            }
        }
        return uris;
    }

    private List<MediaItem.SubtitleConfiguration> subtitleConfigurations() {
        final List<MediaItem.SubtitleConfiguration> subtitles = new ArrayList<>();
        for (final Uri uri : sideloadedSubtitleUris()) {
            subtitles.add(SubtitleUtils.buildSubtitle(this, uri,
                    subtitleLabelFor(uri), uri.equals(mPrefs.subtitleUri)));
        }
        return subtitles;
    }

    // launcher subtitles, then ones attached here; the latter go last because
    // selectPendingSubtitle counts them from the end
    private List<MediaItem.SubtitleConfiguration> allSubtitleConfigurations() {
        final List<MediaItem.SubtitleConfiguration> attachedHere = subtitleConfigurations();
        final java.util.Set<Uri> here = new java.util.HashSet<>();
        for (final MediaItem.SubtitleConfiguration subtitle : attachedHere) {
            here.add(subtitle.uri);
        }
        final List<MediaItem.SubtitleConfiguration> all = new ArrayList<>();
        for (final MediaItem.SubtitleConfiguration subtitle : apiSubs) {
            if (!here.contains(subtitle.uri)) {
                all.add(subtitle);
            }
        }
        all.addAll(attachedHere);
        return all;
    }

    private void attachSubtitle(final Uri uri) {
        attachSubtitle(uri, null);
    }

    void attachFoundSubtitle(final Uri uri) {
        attachSubtitle(uri, null);
    }

    private void attachSubtitle(final Uri uri, @Nullable final String label) {
        final String named = label == null ? null : label.trim();
        if (uri != null && named != null && !named.isEmpty()) {
            mPrefs.putSubtitleLabel(uri, named);
        }
        final Uri attached = handleSubtitles(uri);
        if (attached == null) {
            // still downloading; SubtitleFetcher attaches it when it arrives
            return;
        }
        // the UTF-8 copy has its own address, so the label is filed under it too
        if (named != null && !named.isEmpty()) {
            mPrefs.putSubtitleLabel(attached, named);
        }
        pendingSubtitleLabel = subtitleLabelFor(attached);
        rememberAttachedSubtitle(attached, pendingSubtitleLabel);

        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            ((com.brouken.player.mpv.MpvPlayer) player).addSubtitle(attached,
                    pendingSubtitleLabel, SubtitleUtils.getSubtitleLanguage(attached));
            return;
        }

        final ExoPlayer exo = exo();
        final MediaItem current = player == null ? null : player.getCurrentMediaItem();
        if (exo == null || current == null) {
            // no player to patch; the rebuild picks the list up
            releasePlayer();
            initializePlayer();
            return;
        }

        final long position = exo.getCurrentPosition();
        final boolean wasPlaying = exo.getPlayWhenReady();

        // saved first: if re-reading the source fails, the restart begins here
        mPrefs.updatePosition(position,
                exo.getDuration() == C.TIME_UNSET ? 0L : exo.getDuration());

        // the override names the old item's track groups, and "None" flags
        // would refuse the new subtitle as a default track
        exo.setTrackSelectionParameters(exo.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build());
        if (trackSelector != null) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setDisabledTextTrackSelectionFlags(0));
        }

        exo.setMediaItem(current.buildUpon()
                .setSubtitleConfigurations(allSubtitleConfigurations())
                .build(), position);
        exo.setPlayWhenReady(wasPlaying);
        exo.prepare();
    }

    private void selectPendingSubtitle(final Tracks tracks) {
        if (pendingSubtitleLabel == null || player == null) {
            return;
        }

        final List<Tracks.Group> text = new ArrayList<>();
        for (final Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_TEXT) {
                text.add(group);
            }
        }
        if (text.isEmpty()) {
            // mid-rebuild; chosen when the new tracks arrive
            return;
        }

        // the last match is the newest; a report with fewer matches than are
        // attached still describes the old item
        int expected = 0;
        for (final MediaItem.SubtitleConfiguration subtitle : allSubtitleConfigurations()) {
            if (subtitle.label != null && subtitle.label.equalsIgnoreCase(pendingSubtitleLabel)) {
                expected++;
            }
        }
        Tracks.Group newest = null;
        int newestIndex = 0;
        int matches = 0;
        for (final Tracks.Group group : text) {
            for (int i = 0; i < group.length; i++) {
                final Format format = group.getTrackFormat(i);
                if (format.label != null
                        && format.label.equalsIgnoreCase(pendingSubtitleLabel)) {
                    newest = group;
                    newestIndex = i;
                    matches++;
                }
            }
        }
        if (newest != null) {
            if (matches >= expected) {
                chooseTextTrack(newest, newestIndex);
            }
            return;
        }

        // no label matched: Media3 appends sideloaded subtitles last, in order
        final List<Uri> attached = sideloadedSubtitleUris();
        final int index = attached.indexOf(mPrefs.subtitleUri);
        if (index < 0 || text.size() < attached.size()) {
            return;
        }

        // if any attached label is present, labels are kept and this report is stale
        for (final Tracks.Group group : text) {
            for (int i = 0; i < group.length; i++) {
                final String label = group.getTrackFormat(i).label;
                if (label == null) {
                    continue;
                }
                for (final Uri uri : attached) {
                    if (label.equalsIgnoreCase(subtitleLabelFor(uri))) {
                        return;
                    }
                }
            }
        }
        chooseTextTrack(text.get(text.size() - attached.size() + index), 0);
    }

    private void chooseTextTrack(final Tracks.Group group, final int index) {
        final List<Integer> tracksToSelect = new ArrayList<>();
        tracksToSelect.add(index);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .setOverrideForType(new TrackSelectionOverride(
                        group.getMediaTrackGroup(), tracksToSelect))
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build());
        pendingSubtitleLabel = null;
    }
    private void resolveTitleFromServer(final Uri uri) {
        if (onlineController == null || uri == null) {
            return;
        }
        final String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return;
        }
        onlineController.resolveNameAsync(name -> {
            // ignore an answer that arrived after the file changed
            if (name == null || name.isEmpty() || !uri.equals(mPrefs.mediaUri)) {
                return;
            }
            titleView.setText(name);
            // a server that says nothing returns the identifier, which is no name
            if (History.isFileName(name)) {
                History.rename(androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(this), uri, name, History.NAME_FILE);
            }

            ensureSkipSegments();
            updateOverlayCard(player != null && player.isPlaying());
            autoIdentify(uri);
        });
    }

    // ------------------------------------------------------------ no picture

    // sound with no picture (e.g. Dolby Vision 5 with no DV decoder) raises no error;
    // counted in played time, so buffering and pauses never count
    private static final long NO_PICTURE_AFTER_MS = 5_000;
    private static final long NO_PICTURE_CHECK_MS = 1_000;
    private long noPicturePlayedMs;
    private long noPictureLastPositionMs = -1;
    private boolean noPictureWatching;
    // for this film, across engines
    private int noPictureHits;

    private void watchForNoPicture() {
        if (pictureSeen || noPictureWatching || player == null || !haveMedia
                || coordinatorLayout == null) {
            return;
        }
        noPictureWatching = true;
        noPictureLastPositionMs = -1;
        coordinatorLayout.postDelayed(noPictureCheck, NO_PICTURE_CHECK_MS);
    }

    private void stopNoPictureWatch() {
        if (coordinatorLayout != null) {
            coordinatorLayout.removeCallbacks(noPictureCheck);
        }
        noPictureWatching = false;
        noPicturePlayedMs = 0;
        noPictureLastPositionMs = -1;
    }

    private boolean hasVideoTrack() {
        if (player == null || !player.isCommandAvailable(Player.COMMAND_GET_TRACKS)) {
            return false;
        }
        // video turned off on purpose is not a missing picture
        if (player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)) {
            return false;
        }
        for (final Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() == C.TRACK_TYPE_VIDEO) {
                return true;
            }
        }
        return false;
    }

    private final Runnable noPictureCheck = new Runnable() {
        @Override
        public void run() {
            if (!noPictureWatching || pictureSeen || player == null || !haveMedia) {
                stopNoPictureWatch();
                return;
            }
            // in the background there is nothing to draw on
            if (player.isPlaying() && !keptPlayingInBackground) {
                final long position = player.getCurrentPosition();
                if (noPictureLastPositionMs >= 0 && position > noPictureLastPositionMs) {
                    noPicturePlayedMs += Math.min(position - noPictureLastPositionMs,
                            2 * NO_PICTURE_CHECK_MS);
                }
                noPictureLastPositionMs = position;
            } else {
                noPictureLastPositionMs = -1;
            }
            if (noPicturePlayedMs >= NO_PICTURE_AFTER_MS) {
                final boolean video = hasVideoTrack();
                stopNoPictureWatch();
                if (video) {
                    onNoPicture();
                }
                return;
            }
            coordinatorLayout.postDelayed(this, NO_PICTURE_CHECK_MS);
        }
    };

    private void onNoPicture() {
        if (player == null || !haveMedia) {
            return;
        }
        noPictureHits++;
        final boolean onMpv = player instanceof com.brouken.player.mpv.MpvPlayer;
        Utils.log("No picture after " + NO_PICTURE_AFTER_MS + "ms of playback on "
                + (onMpv ? "mpv" : "media3") + " (time " + noPictureHits + ")");
        if (noPictureHits > 1 || !com.brouken.player.mpv.MpvPlayer.isSupported()) {
            Utils.showText(playerView, getString(R.string.engine_no_picture_either), 5000);
            return;
        }
        if ("auto".equals(mPrefs.playbackEngine)) {
            if (onMpv) {
                Utils.showText(playerView, getString(R.string.engine_no_picture_either), 5000);
                return;
            }
            // as Auto does for a file Media3 cannot open
            mpvFallbackActive = true;
            Utils.showText(playerView, getString(R.string.engine_no_picture_switched), 3000);
            captureTrackSelection();
            releasePlayer();
            initializePlayer();
            return;
        }
        offerOtherEngine(R.string.engine_no_picture_title, R.string.engine_no_picture_message);
    }

    // --------------------------------------------------------- recent posters

    // saved after 30s played under an uncorrected card, or when picked by hand
    private static final long POSTER_WATCH_MS = 30_000;
    private static final long POSTER_CHECK_MS = 10_000;
    @Nullable
    private Uri posterWatchUri;
    private int posterWatchTmdbId;
    @Nullable
    private String posterWatchPath;
    private long posterWatchedMs;
    private long posterLastPositionMs = -1;

    private void watchForPoster(@NonNull final com.brouken.player.online.Identity identity) {
        if (identity.posterPath == null || mPrefs.mediaUri == null || coordinatorLayout == null) {
            return;
        }
        if (mPrefs.mediaUri.equals(posterWatchUri) && posterWatchTmdbId == identity.tmdbId) {
            return;
        }
        stopPosterWatch();
        posterWatchUri = mPrefs.mediaUri;
        posterWatchTmdbId = identity.tmdbId;
        posterWatchPath = identity.posterPath;
        posterWatchedMs = 0;
        posterLastPositionMs = -1;
        coordinatorLayout.postDelayed(posterCheck, POSTER_CHECK_MS);
    }

    private final Runnable posterCheck = new Runnable() {
        @Override
        public void run() {
            if (posterWatchUri == null || !posterWatchUri.equals(mPrefs.mediaUri)
                    || onlineController == null) {
                stopPosterWatch();
                return;
            }
            // corrected since; the new film is watched once its card shows
            final com.brouken.player.online.Identity shown =
                    onlineController.rememberedForCard(posterWatchUri);
            if (shown == null || shown.tmdbId != posterWatchTmdbId) {
                stopPosterWatch();
                return;
            }
            // played time, not wall time
            if (player != null && player.isPlaying()) {
                final long position = player.getCurrentPosition();
                if (posterLastPositionMs >= 0 && position > posterLastPositionMs) {
                    posterWatchedMs += Math.min(position - posterLastPositionMs, 2 * POSTER_CHECK_MS);
                }
                posterLastPositionMs = position;
            } else {
                posterLastPositionMs = -1;
            }
            if (posterWatchedMs >= POSTER_WATCH_MS) {
                History.setPoster(mPrefs.mSharedPreferences, posterWatchUri, posterWatchPath);
                stopPosterWatch();
                return;
            }
            coordinatorLayout.postDelayed(this, POSTER_CHECK_MS);
        }
    };

    private void stopPosterWatch() {
        if (coordinatorLayout != null) {
            coordinatorLayout.removeCallbacks(posterCheck);
        }
        posterWatchUri = null;
        posterWatchPath = null;
    }

    // the history entry gets the server's file name; the title bar keeps the launcher's
    private void rememberFileName(final Uri uri) {
        final String scheme = uri == null ? null : uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return;
        }
        final android.content.SharedPreferences preferences =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        if (History.nameKindFor(preferences, uri) >= History.NAME_FILE) {
            return;
        }
        new Thread(Background.safely(() -> {
            final String name = com.brouken.player.online.Http.serverFileName(uri.toString());
            if (History.isFileName(name)) {
                runOnUiThread(() -> History.rename(preferences, uri, name, History.NAME_FILE));
            }
        })).start();
    }

    private void autoIdentify(final Uri uri) {
        if (onlineController == null || uri == null
                || !onlineController.identifiesAutomatically()
                || onlineController.remembered(uri) != null) {
            return;
        }
        onlineController.identifySilently(uri, identity -> {
            if (!uri.equals(mPrefs.mediaUri)) {
                return;
            }
            skipLoadedFor = null;
            ensureSkipSegments();
            updateOverlayCard(player != null && player.isPlaying());
            if (onlineController.autoSearchSubtitles()) {
                onlineController.searchSubtitles(this, false);
            }
        });
    }

    public void setSpeed(final float speed) {
        mPrefs.speed = speed;
        mPrefs.updateSpeedForUri(speed);
        if (player != null) {
            player.setPlaybackSpeed(speed);
        }
        Utils.showText(playerView, getString(R.string.osd_player_speed_title)
                + ": " + (speed == 1f
                ? getString(R.string.osd_player_speed_normal)
                : String.format(java.util.Locale.getDefault(), "%.2f×", speed)
                        .replace(".00", "").replace("0×", "×")));
    }

    public void rebuildPlayer() {
        if (!haveMedia) {
            return;
        }
        mpvFallbackActive = false;
        captureTrackSelection();
        releasePlayer();
        initializePlayer();
    }

    // minutes, -1 for end of file, 0 to cancel; it pauses the film
    private SleepTimer sleepTimer;

    public void setSleepTimer(final int minutes) {
        if (sleepTimer == null) {
            sleepTimer = new SleepTimer(() -> {
                if (player != null) {
                    player.pause();
                }
                Utils.showText(playerView, getString(R.string.sleep_done), 4000);
            });
        }
        if (minutes < 0) {
            sleepTimer.setEndOfFile(player);
            Utils.showText(playerView, getString(R.string.sleep_end_set));
        } else if (minutes == 0) {
            sleepTimer.cancel(player);
            Utils.showText(playerView, getString(R.string.sleep_off));
        } else {
            sleepTimer.setMinutes(minutes, player);
            Utils.showText(playerView, getString(R.string.sleep_set, minutes));
        }
    }

    public int sleepMinutesLeft() {
        return sleepTimer == null ? 0 : sleepTimer.minutesLeft();
    }

    public boolean sleepAtEndOfFile() {
        return sleepTimer != null && sleepTimer.isAtEndOfFile();
    }

    // steps 0-2 are the library's fit, crop and stretch; these follow
    private static final float[] FORCED_ASPECTS =
            {16f / 9f, 4f / 3f, 16f / 10f, 2f, 2.35f, 2.39f, 5f / 4f};
    private static final int[] FORCED_ASPECT_NAMES = {
            R.string.video_resize_16_9, R.string.video_resize_4_3,
            R.string.video_resize_16_10, R.string.video_resize_2_1,
            R.string.video_resize_235, R.string.video_resize_239,
            R.string.video_resize_5_4};

    private static final int[] ASPECT_ICONS = {
            R.drawable.ic_aspect_ratio_24dp,    // Default: the film's own shape
            R.drawable.ic_fit_screen_24dp,      // Crop
            R.drawable.ic_stretch_24dp,         // Stretch
            R.drawable.ic_aspect_16_9_24dp,
            R.drawable.ic_aspect_4_3_24dp,
            R.drawable.ic_aspect_16_10_24dp,
            R.drawable.ic_aspect_2_1_24dp,
            R.drawable.ic_aspect_235_24dp,
            R.drawable.ic_aspect_239_24dp,
            R.drawable.ic_aspect_5_4_24dp,
    };

    private int aspectStep;

    // saved per film only when chosen, since this also runs whenever a film opens
    private void applyAspectStep(final boolean announce) {
        final int forced = aspectStep - 3;
        final boolean isForced = forced >= 0 && forced < FORCED_ASPECTS.length;
        final int mode = aspectStep == 1 ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : aspectStep == 2 ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                : AspectRatioFrameLayout.RESIZE_MODE_FIT;

        if (engineUi() != null) {
            engineUi().applyShape(mode, isForced ? FORCED_ASPECTS[forced] : 0f, knownVideoSize());
        }

        if (isForced) {
            if (announce) {
                Utils.showText(playerView, getString(FORCED_ASPECT_NAMES[forced]));
            }
        } else {
            mPrefs.resizeMode = mode;
            if (announce) {
                Utils.showText(playerView, getString(aspectStep == 1
                        ? R.string.video_resize_crop
                        : aspectStep == 2 ? R.string.video_resize_stretch
                        : R.string.video_resize_default));
            }
        }
        if (announce) {
            mPrefs.updateAspectStep(aspectStep);
        }
        if (BuildConfig.DEBUG) {
            Utils.log("Aspect step " + aspectStep + (announce ? " chosen" : " applied"));
        }
        remeasureOverPicture();
        refreshPictureAfterShapeChange();
    }

    // posted: the frame has not laid out its new size yet
    private void refreshPictureAfterShapeChange() {
        playerView.post(() -> {
            if (player instanceof com.brouken.player.mpv.MpvPlayer) {
                ((com.brouken.player.mpv.MpvPlayer) player).refreshPicture();
            }
            updateSubtitlePictureArea();
        });
    }

    // progress shown behind the lock; not draggable, so a pocket cannot scrub it
    @Nullable
    private LockedTimeline lockedTimelineView;

    private final Runnable lockedTimelineTick = new Runnable() {
        @Override
        public void run() {
            if (lockedTimelineView == null || !lockedTimelineView.isShowing()) {
                return;
            }
            updateLockedTimeline();
            coordinatorLayout.postDelayed(this, 500);
        }
    };

    private final Runnable lockedTimelineHide = this::hideLockedTimeline;

    private boolean wantsLockedTimeline() {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("lockedTimeline", false);
    }

    // called from the tap that shows the padlock; both go together
    boolean showLockedTimeline(final long ignored) {
        if (!locked || !haveMedia || player == null || !wantsLockedTimeline() || inPip) {
            hideLockedTimeline();
            return false;
        }
        if (lockedTimelineView == null) {
            lockedTimelineView = new LockedTimeline(this, coordinatorLayout);
        }
        final boolean wasShowing = lockedTimelineView.isShowing();
        lockedTimelineView.show(Accent.color(this));
        updateLockedTimeline();
        if (!wasShowing) {
            // posted: its height is known only after layout
            coordinatorLayout.post(() -> {
                if (lockedTimelineView != null && lockedTimelineView.isShowing()
                        && engineUi() != null) {
                    engineUi().setSubtitleLift(lockedTimelineView.heightFraction());
                }
            });
        }
        coordinatorLayout.removeCallbacks(lockedTimelineTick);
        coordinatorLayout.removeCallbacks(lockedTimelineHide);
        coordinatorLayout.postDelayed(lockedTimelineTick, 500);
        coordinatorLayout.postDelayed(lockedTimelineHide, CONTROLLER_TIMEOUT);
        return true;
    }

    void hideLockedTimeline() {
        if (coordinatorLayout != null) {
            coordinatorLayout.removeCallbacks(lockedTimelineTick);
            coordinatorLayout.removeCallbacks(lockedTimelineHide);
        }
        if (lockedTimelineView != null && lockedTimelineView.isShowing()) {
            lockedTimelineView.hide();
            if (engineUi() != null) {
                engineUi().setSubtitleLift(0f);
            }
        }
    }

    private void updateLockedTimeline() {
        if (player == null || lockedTimelineView == null) {
            return;
        }
        final long duration = player.getDuration() == C.TIME_UNSET ? 0 : player.getDuration();
        lockedTimelineView.update(player.getCurrentPosition(), duration);
    }

    private TextView clockView;
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (clockView == null) {
                return;
            }
            clockView.setText(android.text.format.DateFormat.getTimeFormat(PlayerActivity.this)
                    .format(new java.util.Date()));
            clockView.postDelayed(this, 20_000);
        }
    };

    private void updateClock() {
        final boolean wanted = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this).getBoolean("alwaysOnClock", false);
        if (!wanted) {
            if (clockView != null) {
                clockView.removeCallbacks(clockTick);
                clockView.setVisibility(View.GONE);
                reserveRoomForClock();
            }
            return;
        }
        if (clockView == null) {
            clockView = new TextView(this);
            clockView.setTextColor(Color.WHITE);
            clockView.setShadowLayer(4, 0, 0, Color.BLACK);
            clockView.setTextSize(14);
            final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.TOP | Gravity.END;
            final int margin = Utils.dpToPx(12);
            params.setMargins(margin, margin, margin, margin);
            clockView.setLayoutParams(params);
            coordinatorLayout.addView(clockView);
        }
        clockView.setVisibility(View.VISIBLE);
        clockView.removeCallbacks(clockTick);
        clockTick.run();
        reserveRoomForClock();
    }

    // measured, since "18:42" and "6:42 PM" differ in width
    private void reserveRoomForClock() {
        if (clockView == null || titleView == null || metaView == null) {
            return;
        }
        clockView.post(() -> {
            final int reserve = clockView.getVisibility() == View.VISIBLE
                    ? clockView.getWidth() + Utils.dpToPx(24)
                    : 0;
            titleView.setPadding(0, 0, reserve, 0);
            metaView.setPadding(0, 0, reserve, 0);
        });
    }

    // optional: volume keys move the player's volume instead of the device's
    private boolean adjustPlayerVolume(final boolean up) {
        if (player == null || !androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
                .getBoolean("volumeKeysPlayerOnly", false)) {
            return false;
        }
        final float step = 0.07f;
        final float volume = Math.max(0f, Math.min(1f,
                player.getVolume() + (up ? step : -step)));
        player.setVolume(volume);
        Utils.showText(playerView, " " + Math.round(volume * 100) + "%");
        return true;
    }

    private void readApiHeaders(final Bundle bundle) {
        apiHeaders.clear();
        apiImdbId = bundle.getString(API_IMDB);
        apiTmdbId = bundle.getString(API_TMDB);
        // a name/value array or a Bundle; see LaunchHeaders
        apiHeaders.putAll(LaunchHeaders.read(bundle, API_HEADERS));
    }

    // -------------------------------------------------------- launch memory

    private boolean launchMemoryChecked;

    // title, headers and subtitles from the launcher, for when the film is reopened here
    private void saveLaunchMemory() {
        if (mPrefs.mediaUri == null) {
            return;
        }
        final LaunchMemory.Record record = new LaunchMemory.Record();
        record.title = apiTitle;
        record.headers.putAll(apiHeaders);
        for (final MediaItem.SubtitleConfiguration subtitle : apiSubs) {
            record.subtitles.add(new LaunchMemory.Subtitle(subtitle.uri, subtitle.label,
                    subtitle.language, (subtitle.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0));
        }
        LaunchMemory.save(mPrefs.mSharedPreferences, mPrefs.mediaUri, record);
    }

    // only when this launch brought no extras of its own
    private void restoreLaunchMemory() {
        if (launchMemoryChecked) {
            return;
        }
        launchMemoryChecked = true;
        if (apiAccess || apiAccessPartial || apiTitle != null || !apiHeaders.isEmpty()
                || !apiSubs.isEmpty()) {
            return;
        }
        final LaunchMemory.Record record =
                LaunchMemory.load(mPrefs.mSharedPreferences, mPrefs.mediaUri);
        if (record == null) {
            return;
        }
        if (record.title != null && !record.title.trim().isEmpty()) {
            apiTitle = record.title;
            mPrefs.setMediaTitle(record.title);
        }
        apiHeaders.putAll(record.headers);
        for (final LaunchMemory.Subtitle subtitle : record.subtitles) {
            final String scheme = subtitle.uri.getScheme() == null ? "" : subtitle.uri.getScheme();
            if (scheme.startsWith("http") || Utils.fileExists(this, subtitle.uri)) {
                apiSubs.add(SubtitleUtils.buildSubtitle(this, subtitle.uri, subtitle.name,
                        subtitle.language, subtitle.selected));
            }
        }
    }

    private void rememberAttachedSubtitle(@Nullable final Uri subtitle, @Nullable final String label) {
        if (subtitle == null || mPrefs.mediaUri == null) {
            return;
        }
        LaunchMemory.Record record = LaunchMemory.load(mPrefs.mSharedPreferences, mPrefs.mediaUri);
        if (record == null) {
            record = new LaunchMemory.Record();
            record.title = apiTitle;
            record.headers.putAll(apiHeaders);
        }
        final List<LaunchMemory.Subtitle> kept = new ArrayList<>();
        for (final LaunchMemory.Subtitle existing : record.subtitles) {
            if (!existing.uri.equals(subtitle)) {
                kept.add(new LaunchMemory.Subtitle(existing.uri, existing.name,
                        existing.language, false));
            }
        }
        record.subtitles.clear();
        record.subtitles.addAll(kept);
        record.subtitles.add(new LaunchMemory.Subtitle(subtitle, label,
                SubtitleUtils.getSubtitleLanguage(subtitle), true));
        LaunchMemory.save(mPrefs.mSharedPreferences, mPrefs.mediaUri, record);
    }

    @Nullable
    private String apiImdbId;
    @Nullable
    private String apiTmdbId;

    public void updateSkipEnabled(final boolean enabled) {
        if (enabled) {
            ensureSkipSegments();
        } else if (skipController != null) {
            skipController.release();
            skipController = null;
            skipLoadedFor = null;
        }
    }

    // the player is kept across settings; only these need the file reopened
    private static final String[] REBUILD_ON_RETURN = {
            "playbackEngine", "adaptiveBuffering", "tunneling",
            "decoderPriority", "mapDV7ToHevc",
    };

    private final java.util.Map<String, String> settingsOnTheWayIn = new java.util.HashMap<>();
    private boolean keptPlayerForSettings;
    private boolean resumeAfterSettings;
    private boolean settingsWantARebuild;

    public void openSettingsScreen() {
        rememberSettingsForComparison();
        startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
    }

    private void rememberSettingsForComparison() {
        final SharedPreferences preferences =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        settingsOnTheWayIn.clear();
        for (final String key : REBUILD_ON_RETURN) {
            final Object value = preferences.getAll().get(key);
            settingsOnTheWayIn.put(key, value == null ? "" : String.valueOf(value));
        }
        keptPlayerForSettings = player != null && haveMedia;
    }

    private boolean settingsNeedTheFileReopened() {
        final SharedPreferences preferences =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        for (final String key : REBUILD_ON_RETURN) {
            final Object value = preferences.getAll().get(key);
            final String now = value == null ? "" : String.valueOf(value);
            if (!now.equals(settingsOnTheWayIn.get(key))) {
                return true;
            }
        }
        return false;
    }


    // opens only the two halves beside play; the library's seek buttons stay whole
    private java.util.List<View> centerControlChildren() {
        final java.util.List<View> found = new java.util.ArrayList<>();
        if (centerControls == null) {
            return found;
        }
        for (int i = 0; i < centerControls.getChildCount(); i++) {
            final View child = centerControls.getChildAt(i);
            final int id = child.getId();
            if (id == R.id.center_controls_before || id == R.id.center_controls_after) {
                if (child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                final ViewGroup half = (ViewGroup) child;
                for (int j = 0; j < half.getChildCount(); j++) {
                    found.add(half.getChildAt(j));
                }
                continue;
            }
            found.add(child);
        }
        return found;
    }

    private void setCardControlsVisible(final boolean cardUp) {
        if (centerControls == null || cardControls == null) {
            return;
        }

        centerControls.setVisibility(cardUp ? View.INVISIBLE : View.VISIBLE);
        cardControls.setVisibility(cardUp ? View.VISIBLE : View.GONE);
        cardControls.removeAllViews();
        if (!cardUp) {
            return;
        }

        for (final View child : centerControlChildren()) {
            if (child == exoPlayPause) {
                // already in the time row
                continue;
            }
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            final View target = firstClickable(child);
            if (target == null || !target.isEnabled()) {
                continue;
            }
            final ImageView icon = firstImage(child);
            if (icon == null || icon.getDrawable() == null) {
                continue;
            }

            final ImageButton mirror =
                    new ImageButton(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
            mirror.setImageDrawable(icon.getDrawable().getConstantState() != null
                    ? icon.getDrawable().getConstantState().newDrawable()
                    : icon.getDrawable());
            mirror.setContentDescription(target.getContentDescription() != null
                    ? target.getContentDescription()
                    : child.getContentDescription());
            mirror.setOnClickListener(view -> {
                target.performClick();
                resetHideCallbacks();
            });
            mirror.setOnLongClickListener(view -> target.performLongClick());
            cardControls.addView(mirror);
        }
    }

    @Nullable
    private static View firstClickable(final View view) {
        if (view.isClickable()) {
            return view;
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View found = firstClickable(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Nullable
    private static ImageView firstImage(final View view) {
        if (view instanceof ImageView) {
            return (ImageView) view;
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                final ImageView found = firstImage(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }


    private final Runnable durationTicker = new Runnable() {
        @Override
        public void run() {
            updateDurationText();
            if (showRemainingTime && controllerVisible) {
                playerView.postDelayed(this, 500);
            }
        }
    };

    private void startDurationTicker() {
        playerView.removeCallbacks(durationTicker);
        if (showRemainingTime) {
            playerView.post(durationTicker);
        }
    }

    private void updateDurationText() {
        if (exoDuration == null || player == null) {
            return;
        }
        final long duration = player.getDuration();
        if (duration == C.TIME_UNSET || duration <= 0) {
            return;
        }
        if (showRemainingTime) {
            final long left = Math.max(0, duration - player.getCurrentPosition());
            exoDuration.setText("-" + formatTime(left));
        } else {
            exoDuration.setText(formatTime(duration));
        }
    }

    private static String formatTime(final long milliseconds) {
        final long total = (milliseconds + 500) / 1000;
        final long seconds = total % 60;
        final long minutes = (total / 60) % 60;
        final long hours = total / 3600;
        return hours > 0
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }
    void updateButtonLock() {
        if (buttonLock != null) {
            buttonLock.setImageResource(locked
                    ? R.drawable.ic_lock_24dp
                    : R.drawable.ic_lock_open_24dp);
        }
    }

    // first-run pointers; a pressed circle acts only after the last pointer goes,
    // since the file picker would cover the second one
    private boolean openFileAfterHints;
    private boolean openSettingsAfterHints;

    // kept so a remote can answer the pointer; see hintTakesKey
    @Nullable
    private TapTargetView currentHint;
    private boolean currentHintIsTheKeyOne;


    private void showOpeningHint() {
        currentHintIsTheKeyOne = false;
        watchBackForHint(true);
        currentHint = TapTargetView.showFor(PlayerActivity.this,
                hintAt(buttonOpen, R.string.onboarding_open_title,
                        R.string.onboarding_open_description),
                new TapTargetView.Listener() {
                    @Override
                    public void onTargetClick(TapTargetView view) {
                        // before dismissing, which moves on to the next pointer
                        openFileAfterHints = true;
                        super.onTargetClick(view);
                    }

                    @Override
                    public void onTargetDismissed(TapTargetView view, boolean userInitiated) {
                        super.onTargetDismissed(view, userInitiated);
                        currentHint = null;
                        if (!showKeyHint()) {
                            finishHints();
                        }
                    }
                });
    }

    private boolean showKeyHint() {
        if (exoSettings == null || exoSettings.getVisibility() != View.VISIBLE
                || ApiKeys.hasTmdb(this)) {
            return false;
        }
        currentHintIsTheKeyOne = true;
        watchBackForHint(true);
        currentHint = TapTargetView.showFor(PlayerActivity.this,
                hintAt(exoSettings, R.string.onboarding_key_title,
                        R.string.onboarding_key_description),
                new TapTargetView.Listener() {
                    @Override
                    public void onTargetClick(TapTargetView view) {
                        openSettingsAfterHints = true;
                        super.onTargetClick(view);
                    }

                    @Override
                    public void onTargetDismissed(TapTargetView view, boolean userInitiated) {
                        super.onTargetDismissed(view, userInitiated);
                        currentHint = null;
                        finishHints();
                    }
                });
        return true;
    }

    private void finishHints() {
        watchBackForHint(false);
        // settings wins if both were pressed, being the later
        final boolean settings = openSettingsAfterHints;
        final boolean open = openFileAfterHints;
        openSettingsAfterHints = false;
        openFileAfterHints = false;
        if (settings && exoSettings != null) {
            exoSettings.performClick();
        } else if (open && buttonOpen != null) {
            buttonOpen.performClick();
        }
    }

    private TapTarget hintAt(final View view, final int title, final int description) {
        return TapTarget.forView(view, getString(title), getString(description))
                .outerCircleColorInt(Accent.color(PlayerActivity.this))
                .targetCircleColor(R.color.white)
                .titleTextSize(22)
                .titleTextColor(R.color.white)
                .descriptionTextSize(14)
                .cancelable(true);
    }

    private void updateButtonRotation() {
        switch (mPrefs.orientation) {
            case PORTRAIT:
                buttonRotation.setImageResource(R.drawable.ic_screen_lock_portrait_24dp);
                break;
            case SENSOR:
                buttonRotation.setImageResource(R.drawable.ic_auto_rotate_24dp);
                break;
            case LANDSCAPE:
            default:
                buttonRotation.setImageResource(R.drawable.ic_screen_lock_landscape_24dp);
                break;
        }
    }

    // Android 13+: Back is not a key event, so the lock needs this callback
    private Object createOnBackInvokedCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return (OnBackInvokedCallback) () -> {
                if (locked) {
                    ((CustomPlayerView) playerView).setIconLock(true);
                    Utils.showText(playerView, getString(R.string.locked_hint));
                    return;
                }
                if (controllerVisible) {
                    playerView.hideController();
                    return;
                }
                finish();
            };
        } else {
            return null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void registerBackHandling(final boolean wanted) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || onBackInvokedCallback == null) {
            return;
        }
        if (wanted == backHandlingRegistered) {
            return;
        }
        backHandlingRegistered = wanted;
        if (wanted) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    (OnBackInvokedCallback) onBackInvokedCallback);
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (OnBackInvokedCallback) onBackInvokedCallback);
        }
    }

    private boolean backHandlingRegistered;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (sharedPreferences, key) -> {
        if (key == null) return;
        if ("controlsTimeoutSeconds".equals(key)) {
            loadControllerTimeout(PlayerActivity.this);
            if (playerView != null) {
                playerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT);
            }
            return;
        }
        switch (key) {
            case Prefs.PREF_KEY_SUBTITLE_SIZE:
            case Prefs.PREF_KEY_SUBTITLE_EDGE_TYPE:
            case Prefs.PREF_KEY_SUBTITLE_TYPEFACE:
            case Prefs.PREF_KEY_SUBTITLE_STYLE_EMBEDDED:
            case Prefs.PREF_KEY_SUBTITLE_CUSTOM_FONT_ENABLED:
            case Prefs.PREF_KEY_SUBTITLE_CUSTOM_FONT_NAME:
                updateSubtitleStyle(PlayerActivity.this);
                break;
            default:
                if (key.startsWith(Prefs.PREF_KEY_SUBTITLE_VERTICAL_POSITION)) {
                    updateSubtitleStyle(PlayerActivity.this);
                    break;
                }
        }
    };
}
