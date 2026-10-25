package com.brouken.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.media3.ui.PlayerControlView;
import androidx.media3.ui.PlayerView;
import androidx.preference.PreferenceManager;

import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.SchemeTonalSpot;

// the Glass and Material You looks; Classic has no PlayerStyle at all
public final class PlayerStyle {

    public static final String PREF_KEY = "playerStyle";
    static final String CLASSIC = "classic";
    static final String GLASS = "glass";
    static final String MATERIAL = "material";

    // how far Glass's bar rises above Classic's, so the seek line sits inside it
    private static final int GLASS_ABOVE_LINE_DP = 18;
    private static final int TEXT_SHADOW = 0x8C000000;

    private final Activity activity;
    private final boolean glass;
    private final Palette palette;
    @Nullable
    private final Frost frost;

    private PlayerStyle(final Activity activity, final boolean glass, final Palette palette) {
        this.activity = activity;
        this.glass = glass;
        this.palette = palette;
        this.frost = glass ? new Frost() : null;
    }

    public static String stored(@NonNull final Context context) {
        final String value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_KEY, CLASSIC);
        return GLASS.equals(value) || MATERIAL.equals(value) ? value : CLASSIC;
    }

    @Nullable
    static PlayerStyle create(@NonNull final Activity activity) {
        switch (stored(activity)) {
            case GLASS:
                return new PlayerStyle(activity, true, Palette.glass(activity));
            case MATERIAL:
                return new PlayerStyle(activity, false, Palette.material(activity));
            default:
                return null;
        }
    }

    // the style of the player a panel or card belongs to; null means Classic
    @Nullable
    public static PlayerStyle of(@Nullable Context context) {
        while (context instanceof ContextWrapper && !(context instanceof PlayerActivity)) {
            final Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) {
                break;
            }
            context = base;
        }
        return context instanceof PlayerActivity ? ((PlayerActivity) context).playerStyle() : null;
    }

    boolean isGlass() {
        return glass;
    }

    @Nullable
    Frost frost() {
        return frost;
    }

    private int dp(final float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }

    public int highlight(final int accent) {
        return glass ? accent : palette.primary;
    }

    void dressController(@NonNull final PlayerView playerView, @NonNull final LinearLayout titleBar,
                         @NonNull final TextView titleView, @NonNull final TextView metaView,
                         @NonNull final ImageButton back, @NonNull final ImageButton playPause) {
        // the system bars carry on the gradient under them
        final int topEdge = glass ? 0x80000000 : ColorUtils.setAlphaComponent(palette.background, 0xDB);
        final int bottomEdge = glass ? 0x9E000000 : ColorUtils.setAlphaComponent(palette.background, 0xF0);
        final Window window = activity.getWindow();
        if (window != null) {
            window.setStatusBarColor(topEdge);
            window.setNavigationBarColor(bottomEdge);
        }
        final View topFiller = playerView.findViewById(R.id.exo_top);
        if (topFiller != null) {
            // Android 15 draws the app under the status bar, so the glass can reach the top
            if (glass) {
                topFiller.setBackground(frost.drawable(Frost.BAND_FILL));
            } else {
                topFiller.setBackgroundColor(topEdge);
            }
        }

        final View under = playerView.findViewById(R.id.exo_controls_background);
        if (under instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) under;
            group.addView(gradient(Gravity.TOP, glass ? 150 : 140, glass
                    ? new int[]{0x80000000, 0x00000000}
                    : new int[]{ColorUtils.setAlphaComponent(palette.background, 0xDB),
                    ColorUtils.setAlphaComponent(palette.background, 0)}), 0);
            group.addView(gradient(Gravity.BOTTOM, glass ? 230 : 240, glass
                    ? new int[]{0x00000000, 0x9E000000}
                    : new int[]{ColorUtils.setAlphaComponent(palette.background, 0),
                    ColorUtils.setAlphaComponent(palette.background, 0xC7),
                    ColorUtils.setAlphaComponent(palette.background, 0xF0)}), 1);
        }

        final View bottomBar = playerView.findViewById(R.id.exo_bottom_bar);
        if (glass) {
            titleBar.setBackground(frost.drawable(Frost.BAND_TOP));
            if (bottomBar != null) {
                bottomBar.setBackground(frost.drawable(Frost.BAND_BOTTOM));
            }
        } else {
            titleBar.setBackground(null);
            if (bottomBar != null) {
                bottomBar.setBackground(null);
            }
            titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        text(titleView, palette.onSurface);
        text(metaView, palette.onSurfaceVariant);
        if (bottomBar != null) {
            dressButtons(bottomBar);
        }
        dressButton(back);
        dressButton(playPause);
        dressTime(playerView.findViewById(R.id.time_text));

        final View timeBar = playerView.findViewById(R.id.exo_progress);
        if (timeBar instanceof CustomDefaultTimeBar) {
            ((CustomDefaultTimeBar) timeBar).setTrack(glass
                    ? new RoundTrack(palette.primary, palette.buffered, palette.unplayed)
                    : new SliderTrack(palette.primary, palette.buffered, palette.unplayed));
        }

        dressCentre(playerView);
    }

    private View gradient(final int gravity, final int heightDp, final int[] colours) {
        final View view = new View(activity);
        view.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colours));
        view.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(heightDp), gravity));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    // after Classic's own placing; bottom is the system bar this controller avoids
    void placeBars(@NonNull final View titleBar, @NonNull final View bottomBar,
                   @NonNull final View progress, final int side, final int top, final int bottom) {
        if (glass) {
            final ViewGroup.LayoutParams params = bottomBar.getLayoutParams();
            params.height = activity.getResources().getDimensionPixelSize(R.dimen.exo_styled_bottom_bar_height)
                    + dp(GLASS_ABOVE_LINE_DP) + bottom;
            bottomBar.setLayoutParams(params);
            bottomBar.setPadding(bottomBar.getPaddingLeft(), dp(GLASS_ABOVE_LINE_DP),
                    bottomBar.getPaddingRight(), bottomBar.getPaddingBottom());
            return;
        }
        // line 70dp up; the stock bar draws its line 9dp above its bottom padding
        final int padBottom = dp(13);
        Utils.setViewParams(progress, side + dp(17), 0, side + dp(17), padBottom,
                0, 0, 0, dp(70) - dp(9) - padBottom + bottom);
    }

    void dressButton(@NonNull final ImageButton button) {
        // an inset background would replace the padding and blow the icon up
        final int left = button.getPaddingLeft();
        final int top = button.getPaddingTop();
        final int right = button.getPaddingRight();
        final int bottom = button.getPaddingBottom();
        button.setBackground(buttonBackground());
        button.setPadding(left, top, right, bottom);
        button.setImageTintList(iconTint());
    }

    private void dressButtons(@NonNull final View view) {
        if (view instanceof ImageButton) {
            dressButton((ImageButton) view);
        } else if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dressButtons(group.getChildAt(i));
            }
        }
    }

    private void dressTime(@Nullable final View timeText) {
        if (!(timeText instanceof ViewGroup)) {
            return;
        }
        final StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, rounded(glass ? 0x33FFFFFF : palette.tonal, 16, focusColour()));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(glass ? 0x33FFFFFF : palette.tonal, 16, 0));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        timeText.setBackground(states);

        final ViewGroup row = (ViewGroup) timeText;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (!(row.getChildAt(i) instanceof TextView)) {
                continue;
            }
            final TextView text = (TextView) row.getChildAt(i);
            if (glass) {
                text(text, palette.onSurface);
            } else if (text.getId() == R.id.exo_position) {
                text.setTextColor(palette.onSurface);
                text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            } else {
                text.setTextColor(palette.onSurfaceVariant);
                if (text.getId() == View.NO_ID) {
                    // the separator between the two times
                    text.setText(" / ");
                }
            }
        }
    }

    private void dressCentre(@NonNull final PlayerView playerView) {
        final View view = playerView.findViewById(R.id.exo_play_pause);
        if (view instanceof ImageButton) {
            final ImageButton play = (ImageButton) view;
            final int size = dp(glass ? 64 : 72);
            final ViewGroup.LayoutParams params = play.getLayoutParams();
            params.width = size;
            params.height = size;
            play.setLayoutParams(params);
            final int padding = dp(glass ? 17 : 19);
            play.setPadding(padding, padding, padding, padding);
            play.setScaleType(ImageView.ScaleType.FIT_CENTER);
            play.setBackground(withFocusRing(() -> glass
                    ? frost.drawable(Frost.ROUND)
                    : oval(palette.primaryContainer)));
            play.setImageTintList(ColorStateList.valueOf(glass ? Color.WHITE : palette.onPrimaryContainer));
            plainPlayIcons(playerView);
        }
        // the stock pictures carry a dark halo, which a tint would turn into a disc
        final int[] ids = {R.id.delete, R.id.prev, R.id.next};
        final int[] plain = {R.drawable.ic_delete_24dp_, androidx.media3.ui.R.drawable.exo_icon_previous,
                androidx.media3.ui.R.drawable.exo_icon_next};
        for (int i = 0; i < ids.length; i++) {
            final View secondary = playerView.findViewById(ids[i]);
            if (!(secondary instanceof ImageButton)) {
                continue;
            }
            final ImageButton button = (ImageButton) secondary;
            button.setImageResource(plain[i]);
            final ViewGroup.LayoutParams params = button.getLayoutParams();
            params.width = dp(52);
            params.height = dp(52);
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) params).setMarginStart(dp(8));
                ((ViewGroup.MarginLayoutParams) params).setMarginEnd(dp(8));
            }
            button.setLayoutParams(params);
            button.setPadding(dp(12), dp(12), dp(12), dp(12));
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (glass) {
                button.setBackground(withFocusRing(() -> frost.drawable(Frost.ROUND)));
                button.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            } else {
                // plain icons in Material: a lasting disc reads as a button held down
                button.setBackground(buttonBackground());
                button.setImageTintList(iconTint());
            }
        }
    }

    // the stock play and pause pictures carry their own white disc
    private void plainPlayIcons(@NonNull final PlayerView playerView) {
        final View controller = playerView.findViewById(R.id.exo_controller);
        if (!(controller instanceof PlayerControlView)) {
            return;
        }
        try {
            final java.lang.reflect.Field play = PlayerControlView.class.getDeclaredField("playButtonDrawable");
            final java.lang.reflect.Field pause = PlayerControlView.class.getDeclaredField("pauseButtonDrawable");
            play.setAccessible(true);
            pause.setAccessible(true);
            play.set(controller, activity.getDrawable(R.drawable.ic_play_arrow_24dp));
            pause.set(controller, activity.getDrawable(R.drawable.ic_pause_24dp));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // keeps the stock pictures, which still work on the style's disc
            e.printStackTrace();
        }
    }

    public Drawable panelBackground() {
        if (glass) {
            return frost.drawable(Frost.PANEL);
        }
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(palette.panel);
        final float r = dp(28);
        final boolean rtl = activity.getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;
        shape.setCornerRadii(rtl
                ? new float[]{0, 0, r, r, r, r, 0, 0}
                : new float[]{r, r, 0, 0, 0, 0, r, r});
        return shape;
    }

    public void dressPanel(@NonNull final View root) {
        root.setBackground(panelBackground());
        final TextView title = root.findViewById(android.R.id.text1);
        if (title == null) {
            return;
        }
        text(title, palette.onSurface);
        if (!glass) {
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
    }

    public void dressOsdRow(@NonNull final View row) {
        final TextView title = row.findViewById(android.R.id.title);
        final TextView summary = row.findViewById(android.R.id.summary);
        if (title != null) {
            text(title, palette.onSurface);
        }
        if (summary != null) {
            text(summary, glass ? palette.onSurfaceVariant : palette.primary);
        }
        dressRow(row);
        // the halves flash pressed under a remote's left and right too
        for (final int id : new int[]{android.R.id.button1, android.R.id.button2, android.R.id.icon2}) {
            final View half = row.findViewById(id);
            if (half != null) {
                keepPadding(half, new RippleDrawable(ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(palette.onSurface, 0x29)), null,
                        new InsetDrawable(rounded(Color.WHITE, 14, 0), dp(4), dp(2), dp(4), dp(2))));
            }
        }
        final View trailing = row.findViewById(R.id.osd_trailing_action);
        if (trailing != null) {
            keepPadding(trailing, buttonBackground());
        }
        if (glass) {
            return;
        }
        final int arrows = palette.onSurfaceVariant;
        for (final int id : new int[]{android.R.id.icon1, android.R.id.icon2, android.R.id.icon,
                R.id.osd_trailing_action}) {
            final View icon = row.findViewById(id);
            if (icon instanceof ImageView) {
                ((ImageView) icon).setImageTintList(ColorStateList.valueOf(arrows));
            }
        }
    }

    // the inset must not become the row's padding, so the padding is put back
    public void dressRow(@NonNull final View row) {
        final StateListDrawable focus = new StateListDrawable();
        focus.addState(new int[]{android.R.attr.state_focused}, new InsetDrawable(
                rounded(glass ? 0x24FFFFFF : palette.tonal, 14, focusColour()), dp(6), dp(2), dp(6), dp(2)));
        focus.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        final Drawable mask = new InsetDrawable(rounded(Color.WHITE, 14, 0), dp(6), dp(2), dp(6), dp(2));
        final int left = row.getPaddingLeft();
        final int top = row.getPaddingTop();
        final int right = row.getPaddingRight();
        final int bottom = row.getPaddingBottom();
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(palette.onSurface, 0x29)), focus, mask));
        row.setPadding(left, top, right, bottom);
    }

    public void dressSkipButton(@NonNull final Button button) {
        final StateListDrawable states = new StateListDrawable();
        final Drawable active = rounded(palette.primary, -1, 0);
        states.addState(new int[]{android.R.attr.state_focused}, active);
        states.addState(new int[]{android.R.attr.state_pressed}, active);
        states.addState(new int[]{}, glass
                ? frost.drawable(Frost.ROUND)
                : rounded(palette.tonal, -1, 0));
        button.setBackground(states);

        final ColorStateList colours = new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{palette.onPrimary, palette.onPrimary,
                        glass ? Color.WHITE : palette.onSecondaryContainer});
        button.setTextColor(colours);
        button.setCompoundDrawableTintList(colours);
        if (glass) {
            button.setShadowLayer(dp(3), 0, dp(1), TEXT_SHADOW);
        }
    }

    // clear of Material's slider handle, which stands above Classic's bar
    public int skipBottomMargin(final int resting) {
        return glass ? resting : Math.max(resting, dp(120));
    }

    public Drawable infoCardBackground(final int percent) {
        final int alpha = Math.round(percent * 255f / 100f);
        if (glass) {
            final Drawable frosted = frost.drawable(Frost.CARD);
            frosted.setAlpha(alpha);
            return frosted;
        }
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(ColorUtils.setAlphaComponent(palette.panel, alpha));
        shape.setCornerRadius(dp(24));
        return shape;
    }

    private GradientDrawable rounded(final int fill, final int radiusDp, final int stroke, final int ringPx) {
        final GradientDrawable shape = rounded(fill, radiusDp, stroke);
        shape.setStroke(ringPx, focusColour());
        return shape;
    }

    // radiusDp -1 is a pill; a stroke colour of 0 draws none
    private GradientDrawable rounded(final int fill, final int radiusDp, final int stroke) {
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radiusDp < 0 ? dp(1000) : dp(radiusDp));
        if (stroke != 0) {
            shape.setStroke(dp(2), stroke);
        }
        return shape;
    }

    private GradientDrawable oval(final int fill) {
        final GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(fill);
        return shape;
    }

    private int focusColour() {
        return glass ? Accent.color(activity) : palette.primary;
    }

    // a fill per state: a drawable repaints only through its last holder
    private Drawable withFocusRing(final java.util.function.Supplier<Drawable> base) {
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(3), focusColour());
        final StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, new LayerDrawable(new Drawable[]{base.get(), ring}));
        states.addState(new int[]{}, base.get());
        return states;
    }

    // no activated state: subtitles being on shows in Media3's filled CC icon
    private Drawable buttonBackground() {
        final StateListDrawable states = new StateListDrawable();
        final GradientDrawable focused = oval(glass ? 0xF0FFFFFF : palette.secondaryContainer);
        focused.setStroke(dp(glass ? 3 : 2), focusColour());
        states.addState(new int[]{android.R.attr.state_focused}, new InsetDrawable(focused, dp(2)));
        states.addState(new int[]{android.R.attr.state_pressed},
                new InsetDrawable(oval(glass ? 0x33FFFFFF : palette.tonal), dp(2)));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    private ColorStateList iconTint() {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                glass ? new int[]{0xFF111418, Color.WHITE}
                        : new int[]{palette.onSecondaryContainer, palette.onSurface});
    }

    // over a picture of any brightness Glass's words need their shadow
    private void text(@NonNull final TextView view, final int colour) {
        view.setTextColor(colour);
        if (glass) {
            view.setShadowLayer(dp(3), 0, dp(1), TEXT_SHADOW);
        }
    }

    void dressMessage(@NonNull final TextView message) {
        keepPadding(message, glass ? frost.drawable(Frost.ROUND)
                : rounded(ColorUtils.setAlphaComponent(palette.panel, 0xEB), 28, 0));
        text(message, palette.onSurface);
        message.setCompoundDrawableTintList(ColorStateList.valueOf(
                glass ? Color.WHITE : palette.onSurfaceVariant));
    }

    void dressLoading(@NonNull final ViewGroup loading) {
        if (loading.getChildCount() < 1 || !(loading.getChildAt(0) instanceof android.widget.ProgressBar)) {
            return;
        }
        final android.widget.ProgressBar spinner = (android.widget.ProgressBar) loading.getChildAt(0);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(focusColour()));
    }

    void dressSeekBubble(@NonNull final View bubble) {
        final View frame = bubble.findViewById(R.id.seek_bubble_frame);
        if (frame != null) {
            final float radius = dp(glass ? 10 : 16);
            final GradientDrawable fill = new GradientDrawable();
            fill.setColor(Color.BLACK);
            fill.setCornerRadius(radius);
            // the frame clips to this outline
            frame.setBackground(fill);
            final GradientDrawable edge = new GradientDrawable();
            edge.setColor(Color.TRANSPARENT);
            edge.setCornerRadius(radius);
            edge.setStroke(dp(glass ? 1 : 2), glass ? 0x4DFFFFFF : palette.primary);
            frame.setForeground(edge);
        }
        final android.widget.ProgressBar loading = bubble.findViewById(R.id.seek_bubble_loading);
        if (loading != null) {
            loading.setIndeterminateTintList(ColorStateList.valueOf(focusColour()));
        }
        final View pill = bubble.findViewById(R.id.seek_bubble_pill);
        if (pill != null) {
            keepPadding(pill, glass ? frost.drawable(Frost.ROUND) : rounded(palette.surfaceHigh, 14, 0));
        }
        final TextView time = bubble.findViewById(R.id.seek_bubble_time);
        if (time != null) {
            text(time, palette.onSurface);
        }
        final TextView delta = bubble.findViewById(R.id.seek_bubble_delta);
        if (delta != null) {
            text(delta, glass ? 0xBFFFFFFF : palette.onSurfaceVariant);
        }
    }

    void dressDoubleTap(@NonNull final com.brouken.player.dtpv.youtube.YouTubeOverlay overlay) {
        overlay.circleBackgroundColorInt(glass ? 0x80101216
                : ColorUtils.setAlphaComponent(palette.background, 0xB3));
        overlay.tapCircleColorInt(glass ? 0x2EFFFFFF : ColorUtils.setAlphaComponent(palette.primary, 0x33));
    }

    // Glass keeps Classic's white with its shadow
    void dressClock(@NonNull final TextView clock) {
        if (!glass) {
            clock.setTextColor(palette.onSurface);
            clock.setShadowLayer(0, 0, 0, 0);
        }
    }

    Drawable lockedBackground() {
        if (glass) {
            return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0x8C000000, 0xC8000000});
        }
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                ColorUtils.setAlphaComponent(palette.background, 0),
                ColorUtils.setAlphaComponent(palette.background, 0xC7),
                ColorUtils.setAlphaComponent(palette.background, 0xF0)});
    }

    int textColour() {
        return palette.onSurface;
    }

    int textVariantColour() {
        return glass ? 0xBDFFFFFF : palette.onSurfaceVariant;
    }

    int bufferedColour() {
        return palette.buffered;
    }

    int unplayedColour() {
        return palette.unplayed;
    }

    public void dressMatchPrompt(@NonNull final View root) {
        keepPadding(root, glass ? frost.drawable(Frost.ROUND)
                : rounded(palette.surfaceHigh, -1, 0, dp(1.5f)));
        final TextView open = root.findViewById(R.id.match_prompt_open);
        if (open != null) {
            open.setTextColor(glass ? Color.WHITE : palette.onSurface);
            open.setCompoundDrawableTintList(ColorStateList.valueOf(focusColour()));
        }
        final android.widget.ImageView close = root.findViewById(R.id.match_prompt_close);
        if (close != null) {
            close.setImageTintList(ColorStateList.valueOf(glass ? 0xCCFFFFFF : palette.onSurfaceVariant));
        }
    }

    public void dressCard(@NonNull final View root) {
        final View poster = root.findViewById(R.id.overlay_poster);
        if (poster != null) {
            final int radius = dp(glass ? 8 : 12);
            poster.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(final View view, final android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            poster.setClipToOutline(true);
        }
        if (glass) {
            return;
        }
        // the solid card needs no shadow under its words
        plain(root.findViewById(R.id.overlay_heading), palette.onSurface);
        plain(root.findViewById(R.id.overlay_meta), palette.primary);
        plain(root.findViewById(R.id.overlay_ratings), palette.onSurface);
        plain(root.findViewById(R.id.overlay_overview), palette.onSurfaceVariant);
    }

    private static void plain(@Nullable final View view, final int colour) {
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(colour);
            ((TextView) view).setShadowLayer(0, 0, 0, 0);
        }
    }

    void dressSnackbar(@NonNull final View bar) {
        bar.setBackground(glass ? frost.drawable(Frost.CARD) : rounded(palette.inverseSurface, 8, 0));
        final TextView message = bar.findViewById(com.google.android.material.R.id.snackbar_text);
        if (message != null) {
            message.setTextColor(glass ? Color.WHITE : palette.inverseOnSurface);
        }
        final TextView action = bar.findViewById(com.google.android.material.R.id.snackbar_action);
        if (action != null) {
            action.setTextColor(glass ? Accent.color(activity) : palette.inversePrimary);
        }
    }

    public static void dress(@Nullable final android.app.Dialog dialog) {
        final PlayerStyle style = dialog == null ? null : of(dialog.getContext());
        if (style != null) {
            style.dressDialog(dialog);
        }
    }

    void dressDialog(@NonNull final android.app.Dialog dialog) {
        final Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new InsetDrawable(glass ? frost.drawable(Frost.CARD)
                    : rounded(palette.surfaceHigh, 28, 0), dp(16)));
        }
        dressDialogText(dialog);
    }

    // text only, for side panels that keep their own window
    public void dressDialogText(@NonNull final android.app.Dialog dialog) {
        final int title = android.content.res.Resources.getSystem().getIdentifier("alertTitle", "id", "android");
        for (final int id : new int[]{title, androidx.appcompat.R.id.alertTitle}) {
            final View view = id == 0 ? null : dialog.findViewById(id);
            if (view instanceof TextView) {
                text((TextView) view, palette.onSurface);
            }
        }
        final View message = dialog.findViewById(android.R.id.message);
        if (message instanceof TextView) {
            text((TextView) message, glass ? 0xDBFFFFFF : palette.onSurfaceVariant);
        }
        for (final int id : new int[]{android.R.id.button1, android.R.id.button2, android.R.id.button3}) {
            final View button = dialog.findViewById(id);
            if (button instanceof Button) {
                ((Button) button).setTextColor(highlight(Accent.color(activity)));
                dressRow(button);
            }
        }
        final View progress = dialog.findViewById(android.R.id.progress);
        if (progress instanceof android.widget.ProgressBar) {
            ((android.widget.ProgressBar) progress).setIndeterminateTintList(ColorStateList.valueOf(focusColour()));
        }
        final Window window = dialog.getWindow();
        if (window != null) {
            dressFields(window.getDecorView());
        }
    }

    private void dressFields(@NonNull final View view) {
        if (view instanceof android.widget.EditText) {
            final android.widget.EditText field = (android.widget.EditText) view;
            final int colour = focusColour();
            field.setBackgroundTintList(ColorStateList.valueOf(colour));
            field.setHighlightColor(ColorUtils.setAlphaComponent(colour, 0x66));
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                final GradientDrawable cursor = new GradientDrawable();
                cursor.setColor(colour);
                cursor.setSize(dp(2), dp(20));
                field.setTextCursorDrawable(cursor);
                for (final Drawable handle : new Drawable[]{field.getTextSelectHandle(),
                        field.getTextSelectHandleLeft(), field.getTextSelectHandleRight()}) {
                    if (handle != null) {
                        handle.mutate().setTint(colour);
                    }
                }
            }
        } else if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dressFields(group.getChildAt(i));
            }
        }
    }

    public void dressListRow(@NonNull final View row) {
        dressRow(row);
        if (glass) {
            return;
        }
        final View title = row.findViewById(R.id.row_title);
        if (title instanceof TextView) {
            ((TextView) title).setTextColor(palette.onSurface);
        }
        final View detail = row.findViewById(R.id.row_detail);
        if (detail instanceof TextView) {
            ((TextView) detail).setTextColor(palette.onSurfaceVariant);
        }
    }

    public int iconColour() {
        return glass ? 0xD9FFFFFF : palette.onSurfaceVariant;
    }

    public int dividerColour() {
        return palette.divider;
    }

    public Drawable posterCellBackground() {
        final StateListDrawable states = new StateListDrawable();
        final int fill = glass ? 0x33FFFFFF : palette.tonal;
        states.addState(new int[]{android.R.attr.state_focused}, rounded(fill, 8, 0, dp(3)));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(fill, 8, 0, dp(3)));
        states.addState(new int[]{android.R.attr.state_selected},
                rounded(glass ? 0x22FFFFFF : palette.tonal, 8, 0, dp(2)));
        states.addState(new int[]{}, rounded(Color.TRANSPARENT, 8, 0));
        return states;
    }

    // a background with insets would replace the view's own padding
    private static void keepPadding(@NonNull final View view, @NonNull final Drawable background) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        view.setBackground(background);
        view.setPadding(left, top, right, bottom);
    }

    static final class Palette {
        int primary;
        int onPrimary;
        int primaryContainer;
        int onPrimaryContainer;
        int secondaryContainer;
        int onSecondaryContainer;
        int tonal;
        int background;
        int panel;
        int onSurface;
        int onSurfaceVariant;
        int buffered;
        int unplayed;
        int surfaceHigh;
        int divider;
        int inverseSurface;
        int inverseOnSurface;
        int inversePrimary;

        static Palette glass(@NonNull final Context context) {
            final Palette p = new Palette();
            final int accent = Accent.color(context);
            p.primary = accent;
            p.onPrimary = Color.WHITE;
            p.primaryContainer = accent;
            p.onPrimaryContainer = Color.WHITE;
            p.secondaryContainer = 0x33FFFFFF;
            p.onSecondaryContainer = Color.WHITE;
            p.tonal = 0x33FFFFFF;
            p.background = Color.BLACK;
            p.panel = 0xD60E0F12;
            p.onSurface = 0xFFF7F7F7;
            p.onSurfaceVariant = 0xDBFFFFFF;
            p.buffered = 0x9EFFFFFF;
            p.unplayed = 0x3DFFFFFF;
            p.surfaceHigh = 0xE6202228;
            p.divider = 0x22FFFFFF;
            p.inverseSurface = 0xF2F2F2F2;
            p.inverseOnSurface = 0xFF1A1C1F;
            p.inversePrimary = accent;
            return p;
        }

        // built from the theme colour on every device, not from the wallpaper
        @SuppressLint("RestrictedApi")
        static Palette material(@NonNull final Context context) {
            final DynamicScheme scheme = new SchemeTonalSpot(Hct.fromInt(Accent.color(context)), true, 0.0);
            final Palette p = new Palette();
            p.primary = scheme.primaryPalette.tone(80);
            p.onPrimary = scheme.primaryPalette.tone(20);
            p.primaryContainer = scheme.primaryPalette.tone(30);
            p.onPrimaryContainer = scheme.primaryPalette.tone(90);
            p.secondaryContainer = scheme.secondaryPalette.tone(30);
            p.onSecondaryContainer = scheme.secondaryPalette.tone(90);
            p.tonal = ColorUtils.setAlphaComponent(p.secondaryContainer, 0xCC);
            p.background = scheme.neutralPalette.tone(6);
            p.panel = scheme.neutralPalette.tone(12);
            p.onSurface = scheme.neutralPalette.tone(90);
            p.onSurfaceVariant = scheme.neutralVariantPalette.tone(80);
            p.buffered = ColorUtils.setAlphaComponent(p.primary, 0x61);
            p.unplayed = p.secondaryContainer;
            p.surfaceHigh = scheme.neutralPalette.tone(17);
            p.divider = scheme.neutralVariantPalette.tone(30);
            p.inverseSurface = scheme.neutralPalette.tone(90);
            p.inverseOnSurface = scheme.neutralPalette.tone(20);
            p.inversePrimary = scheme.primaryPalette.tone(40);
            return p;
        }
    }

    private final class RoundTrack implements CustomDefaultTimeBar.Track {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF line = new RectF();
        private final int played;
        private final int buffered;
        private final int unplayed;

        RoundTrack(final int played, final int buffered, final int unplayed) {
            this.played = played;
            this.buffered = buffered;
            this.unplayed = unplayed;
        }

        @Override
        public int reachAbove() {
            return dp(12);
        }

        @Override
        public void draw(@NonNull final Canvas canvas, @NonNull final Rect bar,
                         final int bufferedRight, final int playedRight, final boolean grown) {
            final float half = dp(2);
            final float y = bar.exactCenterY();
            segment(canvas, bar.left, bar.right, y, half, unplayed);
            segment(canvas, bar.left, bufferedRight, y, half, buffered);
            segment(canvas, bar.left, playedRight, y, half, played);

            final float ring = dp(grown ? 12 : 10);
            final float edge = Math.max(1f, dp(1.5f));
            paint.setColor(0x38FFFFFF);
            canvas.drawCircle(playedRight, y, ring, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(edge);
            paint.setColor(played);
            canvas.drawCircle(playedRight, y, ring - edge / 2f, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(playedRight, y, dp(grown ? 5.5f : 4.5f), paint);
        }

        private void segment(final Canvas canvas, final float from, final float to,
                             final float y, final float half, final int colour) {
            if (to <= from) {
                return;
            }
            paint.setColor(colour);
            line.set(from, y - half, to, y + half);
            canvas.drawRoundRect(line, half, half, paint);
        }
    }

    private final class SliderTrack implements CustomDefaultTimeBar.Track {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF box = new RectF();
        private final int active;
        private final int buffered;
        private final int inactive;

        SliderTrack(final int active, final int buffered, final int inactive) {
            this.active = active;
            this.buffered = buffered;
            this.inactive = inactive;
        }

        @Override
        public int reachAbove() {
            return dp(12);
        }

        @Override
        public void draw(@NonNull final Canvas canvas, @NonNull final Rect bar,
                         final int bufferedRight, final int playedRight, final boolean grown) {
            final float y = bar.exactCenterY();
            final float half = dp(3);
            final float gap = dp(4);
            final float outer = dp(3);
            final float inner = dp(1);

            segment(canvas, bar.left, playedRight - gap, y, half, outer, inner, active);
            segment(canvas, playedRight + gap, bar.right, y, half, inner, outer, inactive);
            segment(canvas, playedRight + gap, Math.min(bar.right, bufferedRight), y, half, inner,
                    bufferedRight >= bar.right ? outer : inner, buffered);
            if (playedRight + gap < bar.right - dp(6)) {
                paint.setColor(active);
                canvas.drawCircle(bar.right - dp(3), y, dp(1.5f), paint);
            }

            final float width = dp(grown ? 5 : 4);
            final float height = dp(grown ? 24 : 20);
            paint.setColor(active);
            box.set(playedRight - width / 2f, y - height / 2f, playedRight + width / 2f, y + height / 2f);
            canvas.drawRoundRect(box, width / 2f, width / 2f, paint);
        }

        private void segment(final Canvas canvas, final float from, final float to, final float y,
                             final float half, final float startRadius, final float endRadius,
                             final int colour) {
            if (to - from < 1f) {
                return;
            }
            paint.setColor(colour);
            box.set(from, y - half, to, y + half);
            final float s = Math.min(startRadius, (to - from) / 2f);
            final float e = Math.min(endRadius, (to - from) / 2f);
            path.reset();
            path.addRoundRect(box, new float[]{s, s, e, e, e, e, s, s}, Path.Direction.CW);
            canvas.drawPath(path, paint);
        }
    }
}
