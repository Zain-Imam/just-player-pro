package com.brouken.player;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.brouken.player.online.Identity;
import com.brouken.player.online.OnlineController;
import com.brouken.player.online.Posters;
import com.brouken.player.online.Ratings;
import com.brouken.player.online.Tmdb;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// a Recent film's page; changing the title renames it in Recent too
public class FilmInfoActivity extends AppCompatActivity {

    private Uri uri;
    @Nullable
    private String type;
    private SharedPreferences preferences;
    private OnlineController online;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ImageView backdrop;
    private ImageView poster;
    private TextView title;
    private TextView meta;
    private TextView genres;
    private TextView overview;
    private TextView file;
    private TextView resume;
    private TextView start;
    private TextView change;
    private ProgressBar progress;
    private ViewGroup ratings;
    private View confirm;
    private TextView confirmText;
    private View castLabel;
    private View castRow;
    private LinearLayout castList;

    private int runtimeMinutes;
    private boolean tmdbInRatings;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        Accent.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_film_info);

        uri = getIntent().getData();
        type = getIntent().getType();
        if (uri == null) {
            finish();
            return;
        }
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        online = new OnlineController(this, new OnlineController.Host() {
            @Nullable
            @Override
            public Uri mediaUri() {
                return uri;
            }

            @Nullable
            @Override
            public String mediaName() {
                final History.Entry entry = History.find(preferences, uri);
                return entry == null ? null : entry.name;
            }

            @Override
            public void loadSubtitle(final Uri subtitle, @Nullable final String label) {
                // no player here
            }

            @Nullable
            @Override
            public String launchTitle() {
                final LaunchMemory.Record record = LaunchMemory.load(preferences, uri);
                return record == null ? null : record.title;
            }

            @Nullable
            @Override
            public String fileName() {
                final History.Entry entry = History.find(preferences, uri);
                return entry != null && History.isFileName(entry.name) ? entry.name : null;
            }
        });

        backdrop = findViewById(R.id.film_backdrop);
        poster = findViewById(R.id.film_poster);
        title = findViewById(R.id.film_title);
        meta = findViewById(R.id.film_meta);
        genres = findViewById(R.id.film_genres);
        overview = findViewById(R.id.film_overview);
        file = findViewById(R.id.film_file);
        resume = findViewById(R.id.film_resume);
        start = findViewById(R.id.film_start);
        change = findViewById(R.id.film_change);
        progress = findViewById(R.id.film_progress);
        ratings = findViewById(R.id.film_ratings);
        confirm = findViewById(R.id.film_confirm);
        confirmText = findViewById(R.id.film_confirm_text);
        castLabel = findViewById(R.id.film_cast_label);
        castRow = findViewById(R.id.film_cast);
        castList = findViewById(R.id.film_cast_list);

        final int accent = Accent.color(this);
        final int onAccent = androidx.core.graphics.ColorUtils.calculateLuminance(accent) > 0.4
                ? 0xFF1E1E1E : 0xFFFFFFFF;
        resume.setBackground(button(accent, 0, accent));
        resume.setTextColor(onAccent);
        start.setBackground(button(0, 0xFF8A8A8A, accent));
        change.setBackground(button(0, accent, accent));
        change.setTextColor(accent);
        progress.setProgressTintList(ColorStateList.valueOf(accent));

        findViewById(R.id.film_back).setOnClickListener(v -> finish());
        keepClearOfSystemBars();
        resume.setOnClickListener(v -> play(false));
        start.setOnClickListener(v -> play(true));
        change.setOnClickListener(v -> online.identify(this, true, identity -> {
            if (identity == null) {
                return;
            }
            // saved so the next automatic guess does not undo it
            online.rememberForCard(uri, identity);
            History.setIdentified(preferences, uri, identity.posterPath, identity.heading());
            setResult(RESULT_OK);
            bind();
        }));
        setUpAdditions(accent, onAccent);

        bind();
        resume.requestFocus();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void bind() {
        final History.Entry entry = History.find(preferences, uri);
        final Identity identity = online.rememberedForCard(uri);
        final String fileName = entry != null ? entry.name : Utils.getFileName(this, uri, true);

        title.setText(identity != null ? identity.heading()
                : entry != null && entry.title != null ? entry.title : fileName);
        file.setText(fileName);
        overview.setText(identity == null || identity.overview == null ? "" : identity.overview);
        overview.setVisibility(identity == null || identity.overview == null ? View.GONE : View.VISIBLE);
        change.setText(identity != null ? R.string.film_change : R.string.film_find);

        runtimeMinutes = 0;
        genres.setVisibility(View.GONE);
        showMeta(identity);

        final String posterPath = identity != null ? identity.posterPath
                : entry != null ? entry.poster : null;
        Posters.load(poster, Posters.url(posterPath), R.drawable.ic_movie_24dp);
        backdrop.setImageDrawable(null);

        showResume();
        showConfirm(entry, identity);
        showCast(null);
        showRatings(identity, null);

        if (identity != null) {
            Ratings.fetch(this, identity, found -> {
                final Identity now = online.rememberedForCard(uri);
                if (!isFinishing() && !isDestroyed() && now != null
                        && now.tmdbId == identity.tmdbId && now.isSeries == identity.isSeries) {
                    showRatings(identity, found);
                }
            });
            final boolean series = identity.isSeries;
            final int id = identity.tmdbId;
            worker.execute(() -> {
                final Tmdb.Extras extras = Tmdb.extras(this, series, id);
                main.post(() -> showExtras(identity, extras));
            });
        }
    }

    private void showMeta(@Nullable final Identity identity) {
        final StringBuilder line = new StringBuilder();
        if (identity != null && identity.year != null) {
            line.append(identity.year);
        }
        if (runtimeMinutes > 0) {
            appendDot(line).append(runtimeMinutes >= 60
                    ? getString(R.string.film_runtime, runtimeMinutes / 60, runtimeMinutes % 60)
                    : getString(R.string.film_runtime_minutes, runtimeMinutes));
        }
        if (identity != null && identity.rating > 0 && !tmdbInRatings) {
            appendDot(line).append("★ ").append(String.format(java.util.Locale.getDefault(),
                    "%.1f", identity.rating));
        }
        meta.setText(line);
        meta.setVisibility(line.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private static StringBuilder appendDot(final StringBuilder line) {
        return line.length() > 0 ? line.append(" · ") : line;
    }

    private void showExtras(final Identity identity, @Nullable final Tmdb.Extras extras) {
        if (isFinishing() || isDestroyed() || extras == null) {
            return;
        }
        runtimeMinutes = extras.runtimeMinutes;
        showMeta(identity);
        if (extras.genres != null) {
            genres.setText(extras.genres);
            genres.setVisibility(View.VISIBLE);
        }
        if (extras.backdropPath != null) {
            Posters.load(backdrop, Tmdb.BACKDROP_BASE + extras.backdropPath, 0);
        }
        showResume();
        showCast(extras.cast);
    }

    private void showResume() {
        final long position = Prefs.savedPosition(this, uri);
        if (position > 0) {
            resume.setText(getString(R.string.film_resume, Utils.formatMilis(position)));
            start.setVisibility(View.VISIBLE);
            // the player's own length first: TMDB has no runtime for most shows
            final long saved = Prefs.savedDuration(this, uri);
            final long length = saved > 0 ? saved : runtimeMinutes * 60_000L;
            progress.setVisibility(length > 0 ? View.VISIBLE : View.GONE);
            if (length > 0) {
                progress.setProgress((int) Math.min(1000, position * 1000 / length));
            }
        } else {
            resume.setText(R.string.film_play);
            start.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
        }
    }

    private void setUpAdditions(final int accent, final int onAccent) {
        final TextView copy = findViewById(R.id.film_copy_link);
        if (Utils.isSupportedNetworkUri(uri) || History.isNetworkUri(uri)) {
            copy.setBackground(button(0, themeColour(android.R.attr.textColorSecondary), accent));
            copy.setVisibility(View.VISIBLE);
            copy.setOnClickListener(v -> Clipboard.copy(this, uri, title.getText(), true));
        }

        final TextView yes = findViewById(R.id.film_confirm_yes);
        final TextView other = findViewById(R.id.film_confirm_change);
        yes.setBackground(button(accent, 0, accent));
        yes.setTextColor(onAccent);
        other.setBackground(button(0, accent, accent));
        other.setTextColor(accent);
        final float density = getResources().getDisplayMetrics().density;
        final GradientDrawable box = new GradientDrawable();
        box.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x22));
        box.setStroke(Math.round(density), androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x88));
        box.setCornerRadius(14 * density);
        confirm.setBackground(box);
        // what the player's posterCheck does after a while of play
        yes.setOnClickListener(v -> {
            final Identity identity = online.rememberedForCard(uri);
            if (identity != null) {
                History.setIdentified(preferences, uri, identity.posterPath, identity.heading());
                setResult(RESULT_OK);
            }
            hideConfirm();
        });
        other.setOnClickListener(v -> change.performClick());

        if (ratings instanceof com.google.android.material.chip.ChipGroup) {
            final com.google.android.material.chip.ChipGroup group =
                    (com.google.android.material.chip.ChipGroup) ratings;
            group.setChipSpacingHorizontal(Math.round(6 * density));
            group.setChipSpacingVertical(Math.round(6 * density));
        }
    }

    private int themeColour(final int attr) {
        final android.util.TypedValue value = new android.util.TypedValue();
        if (!getTheme().resolveAttribute(attr, value, true)) {
            return 0xFF8A8A8A;
        }
        if (value.resourceId != 0) {
            final ColorStateList list = androidx.core.content.ContextCompat.getColorStateList(this, value.resourceId);
            if (list != null) {
                return list.getDefaultColor();
            }
        }
        return value.data;
    }

    private void showConfirm(@Nullable final History.Entry entry, @Nullable final Identity identity) {
        if (entry == null || entry.title != null || identity == null
                || Identity.PICKED.equals(identity.chosenBy)) {
            hideConfirm();
            return;
        }
        confirmText.setText(getString(R.string.film_confirm_question, identity.heading()));
        confirm.setVisibility(View.VISIBLE);
    }

    private void hideConfirm() {
        final boolean focused = confirm.hasFocus();
        confirm.setVisibility(View.GONE);
        if (focused) {
            resume.requestFocus();
        }
    }

    private void showRatings(@Nullable final Identity identity, @Nullable final Map<String, Double> found) {
        ratings.removeAllViews();
        final Map<String, Double> shown = Ratings.forTitle(this, identity, found);
        // one TMDB number on the page: the chip's, else the star in the line above
        tmdbInRatings = shown.containsKey(Ratings.TMDB);
        showMeta(identity);
        final float density = getResources().getDisplayMetrics().density;
        final int text = themeColour(android.R.attr.textColorPrimary);
        final int valueColour = androidx.core.graphics.ColorUtils.setAlphaComponent(text,
                Math.round(android.graphics.Color.alpha(text) * 0.8f));
        final android.graphics.Typeface semibold =
                android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL);
        for (final Map.Entry<String, Double> rating : shown.entrySet()) {
            final String site = rating.getKey();
            final int colour = Ratings.colour(site);
            final LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            final GradientDrawable outline = new GradientDrawable();
            // see-through, as the landscape and TV page lays the chips over the backdrop
            outline.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                    themeColour(android.R.attr.colorBackground), 0xB3));
            outline.setStroke(Math.max(1, Math.round(density)),
                    androidx.core.graphics.ColorUtils.setAlphaComponent(colour, 0x8C));
            outline.setCornerRadius(6 * density);
            chip.setBackground(outline);
            chip.setPadding(Math.round(8 * density), Math.round(3 * density),
                    Math.round(8 * density), Math.round(3 * density));

            final TextView label = new TextView(this);
            label.setText(Ratings.label(site));
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label.setTextColor(colour);
            label.setSingleLine(true);
            chip.addView(label);

            final TextView value = new TextView(this);
            value.setText(Ratings.format(site, rating.getValue()));
            value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            value.setTypeface(semibold);
            value.setTextColor(valueColour);
            value.setSingleLine(true);
            final LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gap.setMarginStart(Math.round(6 * density));
            chip.addView(value, gap);

            ratings.addView(chip, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        ratings.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showCast(@Nullable final List<Tmdb.CastMember> cast) {
        castList.removeAllViews();
        final boolean any = cast != null && !cast.isEmpty();
        castLabel.setVisibility(any ? View.VISIBLE : View.GONE);
        castRow.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) {
            return;
        }
        castRow.scrollTo(0, 0);
        final float density = getResources().getDisplayMetrics().density;
        final boolean tv = Utils.isTvBox(this);
        final int accent = Accent.color(this);
        final int photoSize = Math.round(64 * density);
        for (final Tmdb.CastMember member : cast) {
            final LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            final int pad = Math.round(4 * density);
            item.setPadding(pad, pad, pad, pad);
            if (tv) {
                // left and right walk the row, up and down leave it
                item.setFocusable(true);
                final GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(10 * density);
                ring.setStroke(Math.round(2 * density), accent);
                final StateListDrawable states = new StateListDrawable();
                states.addState(new int[]{android.R.attr.state_focused}, ring);
                item.setBackground(states);
            }

            final android.widget.FrameLayout photo = new android.widget.FrameLayout(this);
            final GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x55));
            photo.setBackground(circle);
            photo.setClipToOutline(true);
            photo.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

            final TextView initials = new TextView(this);
            initials.setText(initials(member.name));
            initials.setGravity(android.view.Gravity.CENTER);
            initials.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
            initials.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            initials.setTextColor(themeColour(android.R.attr.textColorPrimary));
            photo.addView(initials, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            if (member.profilePath != null) {
                final ImageView picture = new ImageView(this);
                picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
                photo.addView(picture, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                Posters.load(picture, Tmdb.PROFILE_BASE + member.profilePath, 0);
            }
            item.addView(photo, new LinearLayout.LayoutParams(photoSize, photoSize));

            final TextView name = castText(member.name, 13, android.R.attr.textColorPrimary);
            final LinearLayout.LayoutParams nameAt = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameAt.topMargin = Math.round(6 * density);
            item.addView(name, nameAt);
            if (member.character != null) {
                item.addView(castText(member.character, 12, android.R.attr.textColorSecondary),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            castList.addView(item, new LinearLayout.LayoutParams(Math.round(92 * density),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private TextView castText(final String text, final int sizeSp, final int colourAttr) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(themeColour(colourAttr));
        view.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        view.setMaxLines(2);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private static String initials(final String name) {
        final StringBuilder letters = new StringBuilder();
        for (final String word : name.trim().split("\\s+")) {
            if (!word.isEmpty() && letters.length() < 2) {
                letters.appendCodePoint(word.codePointAt(0));
            }
        }
        return letters.toString().toUpperCase(java.util.Locale.getDefault());
    }

    // Android 15 draws under the system bars; only the picture may run behind them
    private void keepClearOfSystemBars() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            return;
        }
        final ScrollView scroll = findViewById(R.id.film_scroll);
        final View back = findViewById(R.id.film_back);
        final ViewGroup.MarginLayoutParams backParams = (ViewGroup.MarginLayoutParams) back.getLayoutParams();
        final int backTop = backParams.topMargin;
        final int backStart = backParams.getMarginStart();
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), 0,
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        back.setOnApplyWindowInsetsListener((view, insets) -> {
            final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            params.topMargin = backTop + insets.getSystemWindowInsetTop();
            params.setMarginStart(backStart + (view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? insets.getSystemWindowInsetRight() : insets.getSystemWindowInsetLeft()));
            view.setLayoutParams(params);
            return insets;
        });
    }

    private void play(final boolean fromStart) {
        final Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(HomeActivity.EXTRA_FROM_HOME, true);
        intent.putExtra(PlayerActivity.EXTRA_FROM_START, fromStart);
        intent.setAction(Intent.ACTION_VIEW);
        if (type == null || type.isEmpty()) {
            intent.setData(uri);
        } else {
            intent.setDataAndType(uri, type);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private Drawable button(final int fill, final int stroke, final int focus) {
        final float density = getResources().getDisplayMetrics().density;
        final StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, pill(fill, focus, 3, density));
        states.addState(new int[]{}, pill(fill, stroke, 1.5f, density));
        return new RippleDrawable(ColorStateList.valueOf(
                AppTheme.isNight(this) ? 0x33FFFFFF : 0x1F000000), states, null);
    }

    private static GradientDrawable pill(final int fill, final int stroke, final float strokeDp,
                                         final float density) {
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(1000 * density);
        if (stroke != 0) {
            shape.setStroke(Math.round(strokeDp * density), stroke);
        }
        return shape;
    }
}
