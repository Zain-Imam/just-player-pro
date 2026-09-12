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
import com.brouken.player.subtitle.SubtitleDelayRenderersFactory;
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

    private PlayerListener playerListener;
    private BroadcastReceiver mReceiver;
    private AudioManager mAudioManager;
    private MediaSession mediaSession;
    private DefaultTrackSelector trackSelector;
    public static LoudnessEnhancer loudnessEnhancer;

    public CustomPlayerView playerView;
    public static Player player;
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
    public static final int CONTROLLER_TIMEOUT = 3500;
    private static final String ACTION_MEDIA_CONTROL = "media_control";
    private static final String EXTRA_CONTROL_TYPE = "control_type";
    private static final int REQUEST_PLAY = 1;
    private static final int REQUEST_PAUSE = 2;
    private static final int CONTROL_TYPE_PLAY = 1;
    private static final int CONTROL_TYPE_PAUSE = 2;

    private CoordinatorLayout coordinatorLayout;
    private TextView titleView;
    private ImageButton buttonOpen;
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
    private String appliedAccent;
    private ProgressBar loadingProgressBar;
    private PlayerControlView controlView;
    private CustomDefaultTimeBar timeBar;

    private boolean restoreOrientationLock;
    private Uri deferredResumeUri;
    private String deferredResumeType;
    private boolean pendingResumeAsk;
    private boolean restorePlayState;
    private boolean restorePlayStateAllowed;
    private boolean play;
    // private float subtitlesScale;
    private boolean isScrubbing;
    private boolean scrubbingNoticeable;
    private long scrubbingStart;
    public boolean frameRendered;
    private boolean alive;
    private final AtomicInteger subtitleDelayMs = new AtomicInteger();
    private final Runnable subtitleDelayApplyRunnable = this::applySubtitleDelay;
    public static boolean focusPlay = false;
    private Uri nextUri;
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
    /* Extras a launching app can add, beyond the ones above. */
    static final String API_HEADERS = "headers";
    static final String API_IMDB = "imdb_id";
    static final String API_TMDB = "tmdb_id";

    /** Request headers the launching app asked to be sent with the stream. */
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
        Utils.setOrientation(this, mPrefs.orientation);
        // One addon ships configured, so subtitles work with no key at all.
        com.brouken.player.online.SubtitleAddons.seedDefault(this);

        super.onCreate(savedInstanceState);

        Accent.apply(this);
        appliedAccent = Accent.stored(this);
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
            resetApiAccess();
            final Uri uri = launchIntent.getData();
            if (SubtitleUtils.isSubtitle(uri, type)) {
                handleSubtitles(uri);
            } else {
                Bundle bundle = launchIntent.getExtras();
                if (bundle != null) {
                    apiAccess = bundle.containsKey(API_POSITION) || bundle.containsKey(API_RETURN_RESULT)
                            || bundle.containsKey(API_SUBS) || bundle.containsKey(API_SUBS_ENABLE);
                    if (apiAccess) {
                        mPrefs.setPersistent(false);
                    } else if (bundle.containsKey(API_TITLE)) {
                        apiAccessPartial = true;
                    }
                    apiTitle = bundle.getString(API_TITLE);
                    readApiHeaders(bundle);
                }

                mPrefs.updateMedia(this, uri, type);

                if (bundle != null) {
                    Uri defaultSub = null;
                    Parcelable[] subsEnable = bundle.getParcelableArray(API_SUBS_ENABLE);
                    if (subsEnable != null && subsEnable.length > 0) {
                        defaultSub = (Uri) subsEnable[0];
                    }

                    Parcelable[] subParcelableArray = bundle.getParcelableArray(API_SUBS);
                    if (subParcelableArray == null) subParcelableArray = new Parcelable[0];

                    List<Uri> subUriList = new ArrayList<>(subParcelableArray.length);
                    for (Parcelable parcelable : subParcelableArray) {
                        Uri element = (Uri) parcelable;
                        subUriList.add(element);
                    }

                    List<Uri> subs = new SubtitleConverter().convertSubtitles(this, subUriList);
                    String[] subsName = bundle.getStringArray(API_SUBS_NAME);

                    for (int i = 0; i < subs.size(); i++) {
                        Uri sub = subs.get(i);
                        String name = null;
                        if (subsName != null && subsName.length > i) {
                            name = subsName[i];
                        }
                        boolean selected = sub.equals(defaultSub) || subs.size() == 1;
                        apiSubs.add(SubtitleUtils.buildSubtitle(this, sub, name, selected));
                    }
                }

                if (apiSubs.isEmpty()) {
                    searchSubtitles();
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
                // Keep the buffered band on screen for the length of the drag.
                PlayerActivity.this.timeBar.holdBufferedPosition(player.getBufferedPosition());
                if (exo() != null) {

                    exo().setSeekParameters(SeekParameters.CLOSEST_SYNC);

                }
                reportScrubbing(position);
            }

            @Override
            public void onScrubMove(TimeBar timeBar, long position) {
                reportScrubbing(position);
            }

            @Override
            public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
                playerView.setCustomErrorMessage(null);
                isScrubbing = false;
                PlayerActivity.this.timeBar.releaseBufferedPosition();
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
                player.play();
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
            final Runnable loadFile = () -> {
                if (!isTvBox && mPrefs.askScope) {
                    askForScope(true, false);
                } else {
                    loadSubtitleFile(mPrefs.mediaUri);
                }
            };
            // Unconfigured, this behaves exactly as it always did.
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
            playerView.setScale(1.f);
            aspectStep = (aspectStep + 1) % (3 + FORCED_ASPECTS.length);
            applyAspectStep(true);
            updatebuttonAspectRatioIcon();
            resetHideCallbacks();
        });
        if (isTvBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
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
        titleView = new TextView(this);
        titleView.setBackgroundResource(R.color.ui_controls_background);
        titleView.setTextColor(Color.WHITE);
        titleView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleView.setPadding(titleViewPaddingHorizontal, titleViewPaddingVertical, titleViewPaddingHorizontal, titleViewPaddingVertical);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setVisibility(View.GONE);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        centerView.addView(titleView);

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

                int insetLeft = windowInsets.getSystemWindowInsetLeft();
                int insetRight = windowInsets.getSystemWindowInsetRight();

                int paddingLeft = 0;
                int marginLeft = insetLeft;

                int paddingRight = 0;
                int marginRight = insetRight;

                if (Build.VERSION.SDK_INT >= 28 && windowInsets.getDisplayCutout() != null) {
                    if (windowInsets.getDisplayCutout().getSafeInsetLeft() == insetLeft) {
                        paddingLeft = insetLeft;
                        marginLeft = 0;
                    }
                    if (windowInsets.getDisplayCutout().getSafeInsetRight() == insetRight) {
                        paddingRight = insetRight;
                        marginRight = 0;
                    }
                }

                int bottomBarPaddingBottom = 0;
                int progressBarMarginBottom = 0;

                if (Build.VERSION.SDK_INT >= 35) {
                    final int left = windowInsets.getInsets(WindowInsets.Type.navigationBars()).left;
                    final int right = windowInsets.getInsets(WindowInsets.Type.navigationBars()).right;

                    final View exoTop = findViewById(R.id.exo_top);
                    exoTop.getLayoutParams().height = windowInsets.getSystemWindowInsetTop();
                    Utils.setViewMargins(exoTop, left, 0, right, 0);

                    final FrameLayout exoBottomBar = findViewById(R.id.exo_bottom_bar);
                    ViewGroup.LayoutParams params = exoBottomBar.getLayoutParams();
                    params.height = getResources().getDimensionPixelSize(R.dimen.exo_styled_bottom_bar_height) + windowInsets.getSystemWindowInsetBottom();
                    exoBottomBar.setLayoutParams(params);

                    /*
                     * The controls use the whole width the screen has.
                     *
                     * These two spacers reserved the navigation bar's width at
                     * the sides, which in landscape left a band of nothing at
                     * one end of the bottom bar while the seek bar above it ran
                     * edge to edge -- so the row looked misaligned and short of
                     * the screen. The seek bar was never inset, so matching it
                     * is what makes the two agree. The bar's height still
                     * accounts for the navigation bar underneath it.
                     */
                    findViewById(R.id.exo_left).getLayoutParams().width = 0;
                    findViewById(R.id.exo_right).getLayoutParams().width = 0;

                    bottomBarPaddingBottom = windowInsets.getSystemWindowInsetBottom();
                    progressBarMarginBottom = windowInsets.getSystemWindowInsetBottom();
                } else {
                    view.setPadding(0, windowInsets.getSystemWindowInsetTop(), 0, windowInsets.getSystemWindowInsetBottom());
                }

                Utils.setViewParams(titleView, paddingLeft + titleViewPaddingHorizontal, titleViewPaddingVertical, paddingRight + titleViewPaddingHorizontal, titleViewPaddingVertical,
                        marginLeft, windowInsets.getSystemWindowInsetTop(), marginRight, 0);

                Utils.setViewParams(findViewById(R.id.exo_bottom_bar), paddingLeft, 0, paddingRight, bottomBarPaddingBottom,
                        marginLeft, 0, marginRight, 0);

                Utils.setViewParams(findViewById(R.id.exo_progress), windowInsets.getSystemWindowInsetLeft(), 0, windowInsets.getSystemWindowInsetRight(), 0,
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
                        return mPrefs.mediaUri == null
                                ? null
                                : Utils.getFileName(PlayerActivity.this, mPrefs.mediaUri, true);
                    }

                    @Override
                    public void loadSubtitle(Uri uri) {
                        attachSubtitle(uri);
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
            if (!isTvBox && mPrefs.askScope) {
                askForScope(false, true);
            } else {
                skipToNext();
            }
        });

        exoPlayPause.setOnClickListener(view -> dispatchPlayPause());

        // Prevent double tap actions in controller
        findViewById(R.id.exo_bottom_bar).setOnTouchListener((v, event) -> true);
        //titleView.setOnTouchListener((v, event) -> true);

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

        // Audio tracks sit beside subtitles in the controls rather than being
        // buried in the settings panel: on a dual-language file it is reached
        // as often as the subtitle button next to it.
        buttonAudioTrack = exoBasicControls.findViewById(R.id.audio_track);
        exoBasicControls.removeView(buttonAudioTrack);
        buttonAudioTrack.setOnClickListener(view -> showAudioMenu());

        exoSettings = exoBasicControls.findViewById(R.id.exo_settings);
        exoBasicControls.removeView(exoSettings);
        final ImageButton exoRepeat = exoBasicControls.findViewById(R.id.exo_repeat_toggle);
        exoBasicControls.removeView(exoRepeat);
        //exoBasicControls.setVisibility(View.GONE);

        exoSettings.setOnClickListener(view -> osdSettingsController.showPlayerSettings());

        exoSettings.setOnLongClickListener(view -> {
            //askForScope(false, false);
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivityForResult(intent, REQUEST_SETTINGS);
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
        controls.addView(buttonOpen);
        controls.addView(exoSubtitle);
        controls.addView(buttonAudioTrack);
        controls.addView(buttonAspectRatio);
        controls.addView(exoSettings);
        controls.addView(buttonLock);
        if (!isTvBox) {
            controls.addView(buttonRotation);
        }
        if (Utils.isPiPSupported(this) && buttonPiP != null) {
            controls.addView(buttonPiP);
        }
        if (mPrefs.repeatToggle) {
            controls.addView(exoRepeat);
        }

        // A fading edge is the only hint that there is more of the strip; with
        // a hard edge it looks like the buttons simply end at the screen.
        horizontalScrollView.setHorizontalFadingEdgeEnabled(true);
        horizontalScrollView.setFadingEdgeLength(Utils.dpToPx(24));
        // So the row stretches to the bar when the buttons fit, and scrolls only
        // when they do not.
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

                controllerVisibleFully = playerView.isControllerFullyVisible();

                if (overlayCard != null && overlayCard.isShowing()) {
                    setCardControlsVisible(true);
                }
                keepSubtitleButtonEnabled();
                if (controllerVisible) {
                    startDurationTicker();
                } else {
                    playerView.removeCallbacks(durationTicker);
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
                    // The one in the time row first: the centre button is hidden
                    // unless the info card is up, and focusing something nobody
                    // can see is what sent the remote to the middle of the row.
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
                    if (mPrefs.firstRun) {
                        TapTargetView.showFor(PlayerActivity.this,
                                TapTarget.forView(buttonOpen, getString(R.string.onboarding_open_title), getString(R.string.onboarding_open_description))
                                        .outerCircleColorInt(Accent.color(PlayerActivity.this))
                                        .targetCircleColor(R.color.white)
                                        .titleTextSize(22)
                                        .titleTextColor(R.color.white)
                                        .descriptionTextSize(14)
                                        .cancelable(true),
                                new TapTargetView.Listener() {
                                    @Override
                                    public void onTargetClick(TapTargetView view) {
                                        super.onTargetClick(view);
                                        buttonOpen.performClick();
                                    }
                                });
                        // TODO: Explain gestures?
                        //  "Use vertical and horizontal gestures to change brightness, volume and seek in video"
                        mPrefs.markFirstRun();
                    }
                    if (errorToShow != null) {
                        showError(errorToShow);
                        errorToShow = null;
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isTvBox) {
                    if (visibility == View.VISIBLE) {
                        if (player != null /*&& player.isPlaying()*/) {
                            //noinspection DataFlowIssue
                            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                                    (OnBackInvokedCallback) onBackInvokedCallback
                            );
                        }
                    } else {
                        //noinspection DataFlowIssue
                        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) onBackInvokedCallback);
                    }
                }
            }
        });

        youTubeOverlay = findViewById(R.id.youtube_overlay);
        // How far a double tap jumps, from settings.
        youTubeOverlay.seekSeconds(mPrefs.doubleTapSeekSeconds);

        youTubeOverlay.performListener(new YouTubeOverlay.PerformListener() {
            @Override
            public void onAnimationStart() {
                youTubeOverlay.setAlpha(1.0f);
                youTubeOverlay.setVisibility(View.VISIBLE);
                // The info card is for settling in, not for seeking through.
                hideOverlayCard();
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
        if (!(isTvBox && Build.VERSION.SDK_INT >= 31)) {
            updateSubtitleStyle(this);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            playerView.removeCallbacks(barsHider);
            Utils.toggleSystemUi(this, playerView, true);
        }
        initializePlayer();
        updateButtonRotation();

        // After the player exists, so the dialog sits over the idle player
        // rather than a blank window. Once only, per launch.
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
        if (isTvBox && Build.VERSION.SDK_INT >= 31) {
            updateSubtitleStyle(this);
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
        if (Build.VERSION.SDK_INT >= 31) {
            playerView.removeCallbacks(barsHider);
        }
        playerView.setCustomErrorMessage(null);
        releasePlayer(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPrefs.mSharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        restorePlayStateAllowed = false;
        super.onBackPressed();
    }

    @Override
    public void finish() {
        if (intentReturnResult) {
            Intent intent = new Intent("com.mxtech.intent.result.VIEW");
            intent.putExtra(API_END_BY, playbackFinished ? "playback_completion" : "user");
            if (!playbackFinished) {
                if (player != null) {
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
                if (SubtitleUtils.isSubtitle(uri, type)) {
                    handleSubtitles(uri);
                } else {
                    mPrefs.updateMedia(this, uri, type);
                    searchSubtitles();
                }
                focusPlay = true;
                initializePlayer();
            } else if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
                String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (text != null) {
                    final Uri parsedUri = Uri.parse(text);
                    if (parsedUri.isAbsolute()) {
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
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                    player.pause();
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                    player.play();
                } else if (player.isPlaying()) {
                    player.pause();
                } else {
                    player.play();
                }
                return true;
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
                        player.play();
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
                    // The step the user set, not a fixed ten seconds: the same
                    // setting the double tap uses, so both mean the same thing.
                    long seekTo = pos - mPrefs.doubleTapSeekSeconds * 1000L;
                    if (seekTo < 0)
                        seekTo = 0;
                    if (exo() != null) {

                        exo().setSeekParameters(SeekParameters.PREVIOUS_SYNC);

                    }
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
                    if (PlayerActivity.exo() != null) {

                        PlayerActivity.exo().setSeekParameters(SeekParameters.NEXT_SYNC);

                    }
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
                    if (isTvBox) {
                        if (controllerVisible && player != null /*&& player.isPlaying()*/) {
                            playerView.hideController();
                            return true;
                        } else {
                            onBackPressed();
                        }
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
        /*
         * A way out of the lock on a television.
         *
         * Unlocking was wired to a tap on the on-screen message, and that was
         * only ever attached on a device with a touchscreen — so a remote could
         * lock the player and then had no way back at all, which read as the
         * lock button doing nothing. Back or OK lifts it; anything else shows
         * the padlock, so it is clear why the film is ignoring the remote.
         */
        if (locked && isTvBox) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return true;
            }
            final int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
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

        if (isTvBox && !controllerVisibleFully) {
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
            setSubtitleTextSizePiP();
            playerView.setScale(1.f);
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !ACTION_MEDIA_CONTROL.equals(intent.getAction()) || player == null) {
                        return;
                    }

                    switch (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                        case CONTROL_TYPE_PLAY:
                            player.play();
                            break;
                        case CONTROL_TYPE_PAUSE:
                            player.pause();
                            break;
                    }
                }
            };
            ContextCompat.registerReceiver(this, mReceiver, new IntentFilter(ACTION_MEDIA_CONTROL), ContextCompat.RECEIVER_EXPORTED);
        } else {
            setSubtitleTextSize();
            if (mPrefs.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                playerView.setScale(mPrefs.scale);
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

    void resetApiAccess() {
        apiAccess = false;
        apiAccessPartial = false;
        apiTitle = null;
        apiSubs.clear();
        mPrefs.setPersistent(true);
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

        if (resultCode == RESULT_OK && alive) {
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

                handleSubtitles(uri);
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

            if (!Accent.stored(this).equals(appliedAccent)) {
                recreate();
                return;
            }

            // A URL picked from the history screen comes back as the result data.
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                setMedia(data.getData(), data.getType());
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }

        // Init here because onStart won't follow when app was only paused when file chooser was shown
        // (for example pop-up file chooser on tablets)
        if (resultCode == RESULT_OK && alive) {
            initializePlayer();
        }
    }

    void handleSubtitles(Uri uri) {
        // Convert subtitles to UTF-8 if necessary
        SubtitleUtils.clearCache(this);
        uri = Utils.convertToUTF(this, uri);
        mPrefs.updateSubtitle(uri);
    }

    private boolean tryMpvFallback() {
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

        // Rebuilt rather than patched: the engine is chosen when the player is
        // constructed, so the whole player has to come back with the new one.
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

    /*
     * Carry the chosen tracks across a change of engine.
     *
     * The two engines number their tracks differently -- Media3 uses the id out
     * of the container, mpv its own -- so the remembered id means something
     * else on the other side and the film came back in another language. The
     * language is carried instead, with the position among tracks of that kind
     * as a fallback for files that label nothing.
     */
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

        if (player != null) {
            player.removeListener(playerListener);
            player.clearMediaItems();
            player.release();
            player = null;
        }

        trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true));
        if (mPrefs.tunneling) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setTunnelingEnabled(true)
            );
        }
        switch (mPrefs.languageAudio) {
            case Prefs.TRACK_DEFAULT:
                break;
            case Prefs.TRACK_DEVICE:
                trackSelector.setParameters(trackSelector.buildUponParameters()
                        .setPreferredAudioLanguages(Utils.getDeviceLanguages())
                );
                break;
            default:
                trackSelector.setParameters(trackSelector.buildUponParameters()
                        .setPreferredAudioLanguages(mPrefs.languageAudio)
                );
        }
        final CaptioningManager captioningManager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
        if (!captioningManager.isEnabled()) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            );
        }
        Locale locale = captioningManager.getLocale();
        if (locale != null) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setPreferredTextLanguage(locale.getISO3Language())
            );
        }

        int subtitleDelay = mPrefs.getSubtitleDelayForUri(mPrefs.mediaUri);
        subtitleDelayMs.set(subtitleDelay);

        EnhancedSubtitleParserFactory enhancedSubtitleParserFactory = new EnhancedSubtitleParserFactory(0);
        SubtitleParser.Factory subtitleParserFactory = enhancedSubtitleParserFactory;

        // https://github.com/google/ExoPlayer/issues/8571
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
                .setSubtitleParserFactory(subtitleParserFactory);

        @SuppressLint("WrongConstant") RenderersFactory renderersFactory = new SubtitleDelayRenderersFactory(this, subtitleDelayMs)
                .setExtensionRendererMode(mPrefs.decoderPriority)
                .setMapDV7ToHevc(mPrefs.mapDV7ToHevc);

        DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory(this, extractorsFactory)
                        .setSubtitleParserFactory(subtitleParserFactory);

        ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(this, renderersFactory)
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(mediaSourceFactory);

        if (haveMedia && isNetworkUri) {
            if (mPrefs.mediaUri.getScheme().toLowerCase().startsWith("http")) {
                HashMap<String, String> headers = new HashMap<>();
                String userInfo = mPrefs.mediaUri.getUserInfo();
                if (userInfo != null && userInfo.length() > 0 && userInfo.contains(":")) {
                    headers.put("Authorization", "Basic " + Base64.encodeToString(userInfo.getBytes(), Base64.NO_WRAP));
                }
                // Whatever the app that launched us asked to be sent. A stream
                // behind a token or a referer check cannot be played without
                // them, which is how most front-ends hand over a link.
                headers.putAll(apiHeaders);
                if (!headers.isEmpty()) {
                    DefaultHttpDataSource.Factory defaultHttpDataSourceFactory = new DefaultHttpDataSource.Factory();
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
        // Subtitle size and position are remembered per engine, so the settings
        // have to follow whichever one is about to play.
        mPrefs.setSubtitleEngine(mpv ? "mpv" : "media3");
        if (mpv) {
            player = new com.brouken.player.mpv.MpvPlayer(this,
                    new com.brouken.player.mpv.MpvOptions(mPrefs.mediaUri)
                            .withHeaders(apiHeaders));
        } else {
            player = playerBuilder.build();
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
        }

        if (player.canAdvertiseSession()) {
            try {
                mediaSession = new MediaSession.Builder(this, player).build();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }

        playerView.setControllerShowTimeoutMs(-1);

        locked = false;

        if (haveMedia) {

            aspectStep = androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(this).getInt(PREF_ASPECT_STEP, 0);
            playerView.setResizeMode(mPrefs.resizeMode);

            if (mPrefs.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
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
            if (apiAccess && apiSubs.size() > 0) {
                mediaItemBuilder.setSubtitleConfigurations(apiSubs);
            } else {
                final List<MediaItem.SubtitleConfiguration> subtitles = subtitleConfigurations();
                if (!subtitles.isEmpty()) {
                    mediaItemBuilder.setSubtitleConfigurations(subtitles);
                }
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

            updateLoading(true);

            if (mPrefs.getPosition() == 0L || apiAccess || apiAccessPartial) {
                play = true;
            }

            if (apiTitle != null) {
                titleView.setText(apiTitle);
            } else {
                titleView.setText(Utils.getFileName(this, mPrefs.mediaUri, false));
                // For a link this also resolves the real name first, and only
                // then identifies; a local file already has its name.
                resolveTitleFromServer(mPrefs.mediaUri);
            }
            autoIdentify(mPrefs.mediaUri);
            titleView.setVisibility(View.VISIBLE);

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
//            mediaSession.setActive(true);
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
        if (player != null) {
            mPrefs.updateBrightness(mBrightnessControl.currentBrightnessLevel);
            mPrefs.updateOrientation();

            if (haveMedia) {
                // Prevent overwriting temporarily inaccessible media position
                if (player.isCurrentMediaItemSeekable()) {
                    mPrefs.updatePosition(player.getCurrentPosition());
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
        // The skip poller runs on a Handler; without this it keeps ticking
        // against a player that no longer exists.
        if (skipController != null) {
            skipController.stop();
        }

        if (save) {
            savePlayer();
        }

        if (player != null) {
            notifyAudioSessionUpdate(false);

//            mediaSession.setActive(false);
            if (mediaSession != null) {
                mediaSession.release();
            }

            if (player.isPlaying() && restorePlayStateAllowed) {
                restorePlayState = true;
            }
            player.removeListener(playerListener);
            player.clearMediaItems();
            player.release();
            player = null;
        }
        titleView.setVisibility(View.GONE);
        updateButtons(false);
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

            if (!isPlaying) {
                PlayerActivity.locked = false;
            }
        }

        @SuppressLint("SourceLockedOrientationActivity")
        @Override
        public void onPlaybackStateChanged(int state) {
            /*
             * The spinner is for every wait, not only the first one.
             *
             * It was shown when a file was opened and hidden when playback
             * became ready, and nothing brought it back -- so a stall halfway
             * through a film was a still picture and no explanation. Shown
             * after a short delay so that the momentary rebuffer after a seek
             * does not flash it.
             */
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

                if (videoLoading) {
                    videoLoading = false;

                    if (mPrefs.orientation == Utils.Orientation.UNSPECIFIED) {
                        mPrefs.orientation = Utils.getNextOrientation(mPrefs.orientation);
                        Utils.setOrientation(PlayerActivity.this, mPrefs.orientation);
                    }

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

                    if (mPrefs.speed <= 0.99f || mPrefs.speed >= 1.01f) {
                        player.setPlaybackSpeed(mPrefs.speed);
                    }
                    if (!apiAccess) {
                        setSelectedTracks(mPrefs.subtitleTrackId, mPrefs.audioTrackId);
                    }
                }
            } else if (state == Player.STATE_ENDED) {
                playbackFinished = true;
                if (sleepTimer != null) {
                    sleepTimer.onPlaybackEnded(player);
                }
                if (apiAccess) {
                    finish();
                }
            }
        }


        @Override
        public void onVideoSizeChanged(@NonNull androidx.media3.common.VideoSize videoSize) {
            applyVideoShape();
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            selectPendingSubtitle(tracks);
            applyCarriedTracks(tracks);
            keepSubtitleButtonEnabled();
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
                    releasePlayer(false);
                    return;
                }
                if (controllerVisible && controllerVisibleFully) {
                    showError(exoPlaybackException);
                } else {
                    errorToShow = exoPlaybackException;
                }
                return;
            }
            // Anything that is not an ExoPlayer failure -- which is everything
            // mpv raises -- used to fall through here and say nothing at all.
            PlaybackError.show(PlayerActivity.this, error,
                    useMpvEngine() ? "mpv" : "media3",
                    mPrefs.mediaUri == null ? null : mPrefs.mediaUri.toString());
        }
    }

    /*
     * When one engine cannot play a file, offer the other one.
     *
     * On Auto this never comes up: the fallback has already tried the other
     * engine by the time an error reaches the user. On a fixed engine it used
     * to be a bare error message, with nothing to say that the other one would
     * very likely play the file — which is the whole reason there are two.
     */
    private boolean offerOtherEngine() {
        if (!haveMedia || "auto".equals(mPrefs.playbackEngine)
                || !com.brouken.player.mpv.MpvPlayer.isSupported()) {
            return false;
        }
        final boolean onMpv = "mpv".equals(mPrefs.playbackEngine);
        final String other = onMpv ? "media3" : "mpv";

        Utils.showFocused(new AlertDialog.Builder(this)
                .setTitle(R.string.engine_failed_title)
                .setMessage(getString(R.string.engine_failed_message,
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

    boolean useMediaStore() {
        final int targetSdkVersion = getApplicationContext().getApplicationInfo().targetSdkVersion;
        return (isTvBox && Build.VERSION.SDK_INT >= 30 && targetSdkVersion >= 30 && mPrefs.fileAccess.equals("auto")) || mPrefs.fileAccess.equals("mediastore");
    }

    void playMedia(final Uri uri, final String type) {
        if (uri == null) {
            return;
        }
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
            return;
        }

        final com.brouken.player.online.Identity identity =
                onlineController.remembered(mPrefs.mediaUri);
        if (identity == null) {
            return;
        }

        skipLoadedFor = mPrefs.mediaUri;

        if (skipController == null) {
            skipController = new com.brouken.player.online.SkipController(this, coordinatorLayout,
                    new com.brouken.player.online.SkipController.Host() {
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
                                    // A chapter without a usable time is no chapter.
                                }
                            }
                            return marks;
                        }
                    });
        }
        // The button dodges the info card when both are up; the card is built
        // lazily, so this asks for it rather than holding a reference.
        skipController.avoid(() -> overlayCard == null ? null : overlayCard.box());
        skipController.load(identity);
    }


    private final Runnable overlayShower = () -> {
        if (onlineController == null || mPrefs.mediaUri == null) {
            return;
        }
        if (player == null || player.isPlaying()) {
            return;
        }
        final com.brouken.player.online.Identity identity =
                onlineController.remembered(mPrefs.mediaUri);
        if (identity == null) {
            return;
        }
        if (overlayCard == null) {
            overlayCard = new com.brouken.player.online.OverlayCard(
                    this, coordinatorLayout, overlayBounds());
        }
        overlayCard.show(identity);
        if (skipController != null) {
            skipController.reposition();
        }

        // The centre controls step aside; the card owns the middle.
        setCardControlsVisible(true);

        playerView.hideController();
    };

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

    private android.view.View overlayBounds() {
        final android.view.View frame =
                playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        return frame != null ? frame : coordinatorLayout;
    }

    public void hideOverlayCard() {
        coordinatorLayout.removeCallbacks(overlayShower);
        if (overlayCard != null) {
            overlayCard.hide();
        }
        if (skipController != null) {
            // The button can come back up to where it normally sits.
            skipController.reposition();
        }
        setCardControlsVisible(false);
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
        hideOverlayCard();
        if (onlineController != null) {
            onlineController.searchSubtitles(this, false);
        }
    }

    private void setMedia(final Uri uri, final String type) {
        // A different file needs its own segments, its own card, and its own
        // chance at Media3 before Auto gives up on it.
        skipLoadedFor = null;
        mpvFallbackActive = false;
        if (skipController != null) {
            skipController.release();
            skipController = null;
        }
        if (overlayCard != null) {
            overlayCard.hide();
        }
        resetApiAccess();
        restorePlayState = false;
        mPrefs.setPersistent(true);
        mPrefs.updateMedia(this, uri, type);
        searchSubtitles();
    }

    void openFile(Uri pickerInitialUri) {
        if (useMediaStore()) {
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
            startActivityForResult(intent, REQUEST_CHOOSER_SUBTITLE_MEDIASTORE);
        } else if ((isTvBox && mPrefs.fileAccess.equals("auto")) || mPrefs.fileAccess.equals("legacy")) {
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

            safelyStartActivityForResult(intent, REQUEST_CHOOSER_SUBTITLE);
        }
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
        // setSubtitleTextSize(getResources().getConfiguration().orientation);

        final SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView != null) {
            final CaptioningManager captioningManager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
            SubtitleUtils.updateFractionalTextSize(subtitleView, captioningManager, mPrefs);
        }
    }

    /*void setSubtitleTextSize(final int orientation) {
        // Tweak text size as fraction size doesn't work well in portrait
        final SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView != null) {
            final float size;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                size = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitlesScale;
            } else {
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                float ratio = ((float)metrics.heightPixels / (float)metrics.widthPixels);
                if (ratio < 1)
                    ratio = 1 / ratio;
                size = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitlesScale / ratio;
            }

            subtitleView.setFractionalTextSize(size);
        }
    }*/

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

    void setSubtitleTextSizePiP() {
        final SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView != null)
            subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 2);
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

    void reportScrubbing(long position) {
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

    private void applyVideoShape() {
        final Format format = videoFormat();
        if (format == null) {
            return;
        }
        if (!isTvBox && mPrefs.orientation == Utils.Orientation.VIDEO) {
            if (Utils.isPortrait(format)) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
            updateButtonRotation();
        }

        // A forced shape is re-applied here: the player sets the frame from
        // the file whenever a size arrives, which would undo it.
        if (aspectStep >= 3) {
            applyAspectStep(false);
        }

        updateSubtitleViewMargin(format);

        if (mPrefs.refreshSubtitleVerticalPositionForVideoHeight(format.height)) {
            updateSubtitleStyle(this);
            osdSettingsController.updateSubtitlePosition();
        }
    }

    public void updateSubtitleStyle(final Context context) {
        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            ((com.brouken.player.mpv.MpvPlayer) player).setSubtitleStyle(
                    mPrefs.subtitleVerticalPosition,
                    mPrefs.subtitleSize,
                    mPrefs.subtitleEdgeType == null ? null : mPrefs.subtitleEdgeType.name(),
                    mPrefs.subtitleTypeface == null ? null : mPrefs.subtitleTypeface.name(),
                    mPrefs.subtitleStyleEmbedded);
            return;
        }

        final CaptioningManager captioningManager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
        final SubtitleView subtitleView = playerView.getSubtitleView();
        // final boolean isTablet = Utils.isTablet(context);
        // subtitlesScale = SubtitleUtils.normalizeFontScale(captioningManager.getFontScale(), isTvBox || isTablet);
        if (subtitleView != null) {
            final CaptioningManager.CaptionStyle userStyle = captioningManager.getUserStyle();
            final CaptionStyleCompat userStyleCompat = CaptionStyleCompat.createFromCaptionStyle(userStyle);
            final int edgeColor = userStyle.hasEdgeColor() ? userStyleCompat.edgeColor : Color.BLACK;
            final Typeface customTypeface = SubtitleUtils.loadCustomSubtitleTypeface(context, mPrefs);
            final Typeface typeface = SubtitleUtils.getSubtitleTypeface(mPrefs.subtitleTypeface, userStyleCompat, customTypeface);
            final CaptionStyleCompat captionStyle = new CaptionStyleCompat(
                    userStyle.hasForegroundColor() ? userStyleCompat.foregroundColor : Color.WHITE,
                    userStyle.hasBackgroundColor() ? userStyleCompat.backgroundColor : Color.TRANSPARENT,
                    userStyle.hasWindowColor() ? userStyleCompat.windowColor : Color.TRANSPARENT,
                    SubtitleUtils.getSubtitleEdgeType(mPrefs.subtitleEdgeType, userStyle),
                    edgeColor,
                    typeface);

            subtitleView.setStyle(captionStyle);
            subtitleView.setApplyEmbeddedStyles(mPrefs.subtitleStyleEmbedded);
            updateSubtitleBottomPaddingFraction(mPrefs.subtitleVerticalPosition);
            SubtitleUtils.updateFractionalTextSize(subtitleView, captioningManager, mPrefs);

            CueModifier cueModifier = playerView.cueModifier;
            cueModifier.setSubtitleTypeface(mPrefs.subtitleTypeface, typeface);
            cueModifier.setSubtitleEdgeType(mPrefs.subtitleEdgeType);
            cueModifier.setShadowColor(edgeColor);
            final Player player = PlayerActivity.player;
            if (player != null && player.isCommandAvailable(Player.COMMAND_GET_TEXT)) {
                subtitleView.setCues(cueModifier.modifyCues(player.getCurrentCues().cues));
            }
        }
        // setSubtitleTextSize();
    }

    private void updateSubtitleBottomPaddingFraction(int subtitleVerticalPosition) {
        float bottomPaddingFraction = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION + (subtitleVerticalPosition * 0.01f);
        playerView.getSubtitleView().setBottomPaddingFraction(bottomPaddingFraction);
    }

    public void updateSubtitleDelay(int delayMs) {
        mPrefs.updateSubtitleDelay(delayMs);
        playerView.removeCallbacks(subtitleDelayApplyRunnable);
        playerView.postDelayed(subtitleDelayApplyRunnable, 500);
    }

    private void applySubtitleDelay() {
        int newDelayMs = mPrefs.getSubtitleDelayForUri(mPrefs.mediaUri);
        subtitleDelayMs.set(newDelayMs);

        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            ((com.brouken.player.mpv.MpvPlayer) player).setSubtitleDelayMs(newDelayMs);
            return;
        }
        restartPlayback();
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
            SubtitleUtils.clearCache(this);
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
                if ("com.android.externalstorage.documents".equals(mPrefs.mediaUri.getHost()) ||
                        "org.courville.nova.provider".equals(mPrefs.mediaUri.getHost())) {
                    // Fast search based on path in uri
                    video = SubtitleUtils.findUriInScope(this, mPrefs.scopeUri, mPrefs.mediaUri);
                } else {
                    // Slow search based on matching metadata, no path in uri
                    // Provider "com.android.providers.media.documents" when using "Videos" tab in file picker
                    DocumentFile fileScope = DocumentFile.fromTreeUri(this, mPrefs.scopeUri);
                    DocumentFile fileMedia = DocumentFile.fromSingleUri(this, mPrefs.mediaUri);
                    video = SubtitleUtils.findDocInScope(fileScope, fileMedia);
                }
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

    Uri findNext() {
        // TODO: Unify with searchSubtitles()
        if (mPrefs.scopeUri != null || isTvBox) {
            DocumentFile video = null;
            File videoRaw = null;

            if (!isTvBox && mPrefs.scopeUri != null) {
                if ("com.android.externalstorage.documents".equals(mPrefs.mediaUri.getHost())) {
                    // Fast search based on path in uri
                    video = SubtitleUtils.findUriInScope(this, mPrefs.scopeUri, mPrefs.mediaUri);
                } else {
                    // Slow search based on matching metadata, no path in uri
                    // Provider "com.android.providers.media.documents" when using "Videos" tab in file picker
                    DocumentFile fileScope = DocumentFile.fromTreeUri(this, mPrefs.scopeUri);
                    DocumentFile fileMedia = DocumentFile.fromSingleUri(this, mPrefs.mediaUri);
                    video = SubtitleUtils.findDocInScope(fileScope, fileMedia);
                }
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
        } else {
            loadingProgressBar.setVisibility(View.GONE);
            exoPlayPause.setVisibility(View.VISIBLE);
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
        // The card is for a paused film on a full screen, not for a thumbnail.
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

        if (format != null) {
            // https://github.com/google/ExoPlayer/issues/8611
            // TODO: Test/disable on Android 11+
            final View videoSurfaceView = playerView.getVideoSurfaceView();
            if (videoSurfaceView instanceof SurfaceView) {
                ((SurfaceView) videoSurfaceView).getHolder().setFixedSize(format.width, format.height);
            }

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
        enterPictureInPictureMode(((PictureInPictureParams.Builder) mPictureInPictureParamsBuilder).build());
    }

    void setEndControlsVisible(boolean visible) {
        final int deleteVisible = (visible && haveMedia && Utils.isDeletable(this, mPrefs.mediaUri)) ? View.VISIBLE : View.INVISIBLE;
        final int nextVisible = (visible && haveMedia && (nextUri != null || (mPrefs.askScope && !isTvBox))) ? View.VISIBLE : View.INVISIBLE;
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
        // Cancel under the remote, not Delete: on a television an accidental
        // press of OK on an unfocused dialog would remove the file.
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

    void skipToNext() {
        if (nextUri != null) {
            releasePlayer();
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
        if (isTvBox) {
            Utils.setButtonEnabled(this, exoSettings, true);
        } else {
            Utils.setButtonEnabled(this, exoSettings, enable);
        }
    }

    private void scaleStart() {
        isScaling = true;
        if (playerView.getResizeMode() != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        }
        scaleFactor = playerView.getVideoSurfaceView().getScaleX();
        playerView.removeCallbacks(playerView.textClearRunnable);
        playerView.clearIcon();
        playerView.setCustomErrorMessage((int) (scaleFactor * 100) + "%");
        playerView.hideController();
        isScaleStarting = true;
    }

    private void scale(boolean up) {
        if (up) {
            scaleFactor += 0.01;
        } else {
            scaleFactor -= 0.01;
        }
        scaleFactor = Utils.normalizeScaleFactor(scaleFactor, playerView.getScaleFit());
        playerView.setScale(scaleFactor);
        playerView.setCustomErrorMessage((int) (scaleFactor * 100) + "%");
    }

    private void scaleEnd() {
        isScaling = false;
        playerView.postDelayed(playerView.textClearRunnable, 200);
        if (player != null && !player.isPlaying()) {
            playerView.showController();
        }
        if (Math.abs(playerView.getScaleFit() - scaleFactor) < 0.01 / 2) {
            playerView.setScale(1.f);
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        updatebuttonAspectRatioIcon();
    }

    private void updatebuttonAspectRatioIcon() {
        switch (playerView.getResizeMode()) {
            case AspectRatioFrameLayout.RESIZE_MODE_ZOOM:
                buttonAspectRatio.setImageResource(R.drawable.ic_fit_screen_24dp);
                break;
            case AspectRatioFrameLayout.RESIZE_MODE_FILL:
                buttonAspectRatio.setImageResource(R.drawable.ic_stretch_24dp);
                break;
            default:
                buttonAspectRatio.setImageResource(R.drawable.ic_aspect_ratio_24dp);
                break;
        }
    }


    private void applyImmediatePreferences() {
        if (youTubeOverlay != null) {
            youTubeOverlay.seekSeconds(mPrefs.doubleTapSeekSeconds);
        }
        updateSubtitleStyle(this);
        applyVolumeBoost();
        applyKeepScreenOn(player != null && player.isPlaying());
        updateClock();
        if (onlineController != null && !onlineController.skipEnabled()) {
            updateSkipEnabled(false);
        }
    }


    private static final int VOLUME_BOOST_GAIN_MB = Utils.BOOST_STEPS * Utils.BOOST_STEP_MB;

    // mpv mixes its own audio and exposes no session for the platform effect, so
    // its boost is the volume property instead — same scale, same on-screen number
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

    static void applyBoostLevel(final boolean enabled) {
        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            ((com.brouken.player.mpv.MpvPlayer) player)
                    .setVolumePercent(enabled ? Utils.boostedPercent() : 100);
            return;
        }
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
                // Only undo OUR boost; a level the user dialled in by gesture is
                // theirs and stays where they put it.
                boostLevel = 0;
                loudnessEnhancer.setEnabled(false);
            }
        } catch (Exception e) {
            // A device that refuses the effect simply plays at normal volume.
            Utils.log("Volume boost unavailable: " + e);
        }
    }

    private void applyKeepScreenOn(final boolean isPlaying) {
        playerView.setKeepScreenOn(isPlaying || mPrefs.keepScreenOn);
    }

    private void showSubtitleMenu() {
        hideOverlayCard();
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
        choices.add(SubtitleChoice.action(getString(R.string.online_search_subtitles),
                getString(R.string.subtitle_menu_search_detail), this::searchOnlineSubtitles));
        choices.add(SubtitleChoice.action(getString(R.string.osd_subtitle_title),
                getString(R.string.subtitle_menu_settings_detail),
                () -> osdSettingsController.showSubtitleSettings()));

        com.brouken.player.online.ListPicker.show(this, getString(R.string.subtitle_menu_title), choices,
                index -> choices.get(index).run());
    }

    private static final class SubtitleChoice implements com.brouken.player.online.ListPicker.Row {

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
            return new SubtitleChoice(activity.getString(R.string.subtitle_menu_off),
                    current ? activity.getString(R.string.subtitle_menu_current) : null,
                    () -> activity.selectTextTrack(null, 0));
        }

        static SubtitleChoice track(final PlayerActivity activity, final Tracks.Group group,
                                    final int index, final Format format, final boolean selected) {
            String name = format.label;
            if (name == null || name.isEmpty()) {
                name = format.language == null
                        ? activity.getString(R.string.subtitle_menu_track, index + 1)
                        : new java.util.Locale(format.language).getDisplayLanguage();
            }
            final String detail = selected
                    ? activity.getString(R.string.subtitle_menu_current)
                    : format.language;
            return new SubtitleChoice(name, detail,
                    () -> activity.selectTextTrack(group, index));
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
        hideOverlayCard();
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

        if (choices.isEmpty()) {
            Utils.showText(playerView, getString(R.string.audio_menu_none));
            return;
        }

        com.brouken.player.online.ListPicker.show(this, getString(R.string.audio_menu_title),
                choices, index -> choices.get(index).select());
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
            if (format.label != null && !format.label.isEmpty()) {
                return format.label;
            }
            if (format.language != null && !format.language.isEmpty()) {
                return new java.util.Locale(format.language).getDisplayLanguage();
            }
            return activity.getString(R.string.audio_menu_track, index + 1);
        }

        @Nullable
        @Override
        public String detail() {
            final StringBuilder sb = new StringBuilder();
            if (selected) {
                sb.append(activity.getString(R.string.subtitle_menu_current));
            }
            if (format.channelCount > 0) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(format.channelCount).append("ch");
            }
            if (format.codecs != null) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(format.codecs);
            }
            return sb.length() == 0 ? null : sb.toString();
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


    private List<MediaItem.SubtitleConfiguration> subtitleConfigurations() {
        final List<MediaItem.SubtitleConfiguration> subtitles = new ArrayList<>();
        for (final Uri uri : mPrefs.subtitleUris) {
            if (!Utils.fileExists(this, uri)) {
                continue;
            }
            subtitles.add(SubtitleUtils.buildSubtitle(this, uri,
                    Utils.getFileName(this, uri, false), uri.equals(mPrefs.subtitleUri)));
        }
        return subtitles;
    }

    private void attachSubtitle(final Uri uri) {
        handleSubtitles(uri);
        pendingSubtitleLabel = Utils.getFileName(this, mPrefs.subtitleUri, false);

        if (player instanceof com.brouken.player.mpv.MpvPlayer) {
            ((com.brouken.player.mpv.MpvPlayer) player).addSubtitle(mPrefs.subtitleUri);
            return;
        }

        final ExoPlayer exo = exo();
        final MediaItem current = player == null ? null : player.getCurrentMediaItem();
        if (exo == null || current == null) {
            // No player to patch — the normal path will pick the list up.
            releasePlayer();
            initializePlayer();
            return;
        }

        final long position = exo.getCurrentPosition();
        final boolean wasPlaying = exo.getPlayWhenReady();

        exo.setMediaItem(current.buildUpon()
                .setSubtitleConfigurations(subtitleConfigurations())
                .build(), position);
        exo.setPlayWhenReady(wasPlaying);
        exo.prepare();
    }

    private void selectPendingSubtitle(final Tracks tracks) {
        if (pendingSubtitleLabel == null || player == null) {
            return;
        }
        for (final Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                final Format format = group.getTrackFormat(i);
                if (format.label == null || !format.label.equals(pendingSubtitleLabel)) {
                    continue;
                }
                final List<Integer> tracksToSelect = new ArrayList<>();
                tracksToSelect.add(i);
                player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                        .buildUpon()
                        .setOverrideForType(new TrackSelectionOverride(
                                group.getMediaTrackGroup(), tracksToSelect))
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build());
                pendingSubtitleLabel = null;
                return;
            }
        }
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
            // Ignore an answer that arrived after the file changed.
            if (name == null || name.isEmpty() || !uri.equals(mPrefs.mediaUri)) {
                return;
            }
            titleView.setText(name);
            // The history list shows UUIDs otherwise, one per episode.
            History.rename(androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(this), uri, name);

            ensureSkipSegments();
            updateOverlayCard(player != null && player.isPlaying());
            autoIdentify(uri);
        });
    }

    /*
     * Look the file up as it starts, unless asked not to.
     *
     * The card, the skip markers and the titles in history all need to know
     * what the film is. That used to happen only inside the subtitle search,
     * which meant the card could not appear without searching for subtitles
     * first. This asks the same question quietly and on its own.
     */
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
            if (identity.title != null && !identity.title.isEmpty()) {
                History.rename(androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(this), uri, identity.title);
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

    /*
     * The sleep timer, driven from the quick panel.
     *
     * Minutes counts down and fades the sound over the last half minute;
     * minus one waits for the file to finish instead. Either way it pauses
     * rather than closing, so the film is still there in the morning.
     */
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

    /*
     * Ten ways to fit the picture to the screen.
     *
     * The first three are the library's own -- fit inside, crop to fill, and
     * stretch. The rest force a shape regardless of what the file claims, which
     * is what rescues a film encoded with the wrong ratio, or one with the black
     * bars baked into the picture. A forced shape has to be re-applied whenever
     * the video size arrives, because the player sets the frame from the file
     * and would otherwise overwrite it.
     */
    private static final float[] FORCED_ASPECTS =
            {16f / 9f, 4f / 3f, 16f / 10f, 2f, 2.35f, 2.39f, 5f / 4f};
    private static final int[] FORCED_ASPECT_NAMES = {
            R.string.video_resize_16_9, R.string.video_resize_4_3,
            R.string.video_resize_16_10, R.string.video_resize_2_1,
            R.string.video_resize_235, R.string.video_resize_239,
            R.string.video_resize_5_4};
    private static final String PREF_ASPECT_STEP = "aspectStep";

    private int aspectStep;

    private void applyAspectStep(final boolean announce) {
        final AspectRatioFrameLayout frame =
                playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        final int forced = aspectStep - 3;

        if (forced >= 0 && forced < FORCED_ASPECTS.length) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            if (frame != null) {
                frame.setAspectRatio(FORCED_ASPECTS[forced]);
            }
            if (announce) {
                Utils.showText(playerView, getString(FORCED_ASPECT_NAMES[forced]));
            }
        } else {
            final int mode = aspectStep == 1 ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    : aspectStep == 2 ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                    : AspectRatioFrameLayout.RESIZE_MODE_FIT;
            playerView.setResizeMode(mode);
            mPrefs.resizeMode = mode;
            if (announce) {
                Utils.showText(playerView, getString(aspectStep == 1
                        ? R.string.video_resize_crop
                        : aspectStep == 2 ? R.string.video_resize_stretch
                        : R.string.video_resize_fit));
            }
        }
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt(PREF_ASPECT_STEP, aspectStep).apply();
    }

    /*
     * A clock in the corner, for watching in bed.
     *
     * Sits above the title so it does not collide with it, hides itself with
     * the rest of the furniture in picture-in-picture, and ticks on the minute
     * rather than every second.
     */
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
    }

    /*
     * Volume keys that quieten the film rather than the device.
     *
     * Off by default, because the keys belonging to the device is what everyone
     * expects. On, it is the player's own volume that moves, which is the one
     * thing that does not also turn down an alarm.
     */
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

    /*
     * Extras from the app that launched us.
     *
     * Headers arrive the way every player that takes them accepts: a flat array
     * of name, value, name, value. A stream behind a token or a referer check
     * cannot be played without them, and that is how a front-end usually hands
     * a link over. A Bundle of strings is accepted too, since some send that.
     *
     * An IMDb or TMDB id saves asking a database what the file is, and is far
     * more reliable than reading it off the file name.
     */
    private void readApiHeaders(final Bundle bundle) {
        apiHeaders.clear();
        apiImdbId = bundle.getString(API_IMDB);
        apiTmdbId = bundle.getString(API_TMDB);

        final Object raw = bundle.get(API_HEADERS);
        if (raw instanceof String[]) {
            final String[] pairs = (String[]) raw;
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                if (pairs[i] != null && pairs[i + 1] != null) {
                    apiHeaders.put(pairs[i], pairs[i + 1]);
                }
            }
        } else if (raw instanceof Bundle) {
            final Bundle headers = (Bundle) raw;
            for (final String key : headers.keySet()) {
                final String value = headers.getString(key);
                if (value != null) {
                    apiHeaders.put(key, value);
                }
            }
        }
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

    public void openSettingsScreen() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
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

        for (int i = 0; i < centerControls.getChildCount(); i++) {
            final View child = centerControls.getChildAt(i);
            if (child == exoPlayPause) {
                // Already permanently in the time row; two would be silly.
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

    private void updateButtonRotation() {
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        boolean auto = false;
        try {
            auto = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION) == 1;
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }

        if (mPrefs.orientation == Utils.Orientation.VIDEO) {
            if (auto) {
                buttonRotation.setImageResource(R.drawable.ic_screen_lock_rotation_24dp);
            } else if (portrait) {
                buttonRotation.setImageResource(R.drawable.ic_screen_lock_portrait_24dp);
            } else {
                buttonRotation.setImageResource(R.drawable.ic_screen_lock_landscape_24dp);
            }
        } else {
            if (auto) {
                buttonRotation.setImageResource(R.drawable.ic_screen_rotation_24dp);
            } else if (portrait) {
                buttonRotation.setImageResource(R.drawable.ic_screen_portrait_24dp);
            } else {
                buttonRotation.setImageResource(R.drawable.ic_screen_landscape_24dp);
            }
        }
    }

    private Object createOnBackInvokedCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return (OnBackInvokedCallback) () -> playerView.hideController();
        } else {
            return null;
        }
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (sharedPreferences, key) -> {
        if (key == null) return;
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
