package com.brouken.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.preference.PreferenceManager;

final class Intro {

    private static final String PREF_SEEN = "introSeen";

    private static final int STYLE_CARD = 0;
    // the cards with an Open settings button
    private static final int KEY_CARD = 2;
    private static final int SETTINGS_CARD = 5;

    private static final int[] TITLES = {
            R.string.intro_style_title,
            R.string.intro_folders_title,
            R.string.intro_key_title,
            R.string.intro_gestures_title,
            R.string.intro_holds_title,
            R.string.intro_settings_title,
    };

    private static final int[] BODIES = {
            0,
            R.string.intro_folders_body,
            R.string.intro_key_body,
            R.string.intro_gestures_body,
            R.string.intro_holds_body,
            R.string.intro_settings_body,
    };

    private static final int[] PICTURES = {
            0,
            R.drawable.intro_recent,
            R.drawable.intro_key,
            R.drawable.intro_gestures,
            R.drawable.intro_holds,
            R.drawable.intro_settings,
    };

    private static final String[] STYLES = {"classic", "glass", "material"};
    private static final int[] STYLE_NAMES = {
            R.string.player_style_classic, R.string.player_style_glass, R.string.player_style_material};
    private static final int[] STYLE_NOTES = {
            R.string.player_style_classic_desc, R.string.player_style_glass_desc, R.string.player_style_material_desc};
    private static final int[] STYLE_BIG = {
            R.drawable.intro_style_classic, R.drawable.intro_style_glass, R.drawable.intro_style_material};
    private static final int[] STYLE_SMALL = {
            R.drawable.sketch_style_classic, R.drawable.sketch_style_glass, R.drawable.sketch_style_material};

    private Intro() {
    }

    static boolean pending(final Context context) {
        return !PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_SEEN, false);
    }

    static void markSeen(final Context context, final boolean seen) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(PREF_SEEN, seen)
                .apply();
    }

    static void show(final Activity activity) {
        new Intro.Cards(activity).show();
    }

    private static final class Cards {
        private final Activity activity;
        private final SharedPreferences prefs;
        private final float density;
        private final View card;
        private final ImageView art;
        private final TextView title;
        private final TextView body;
        private final ScrollView scroll;
        private final LinearLayout styles;
        private final LinearLayout colours;
        private final TextView hint;
        private final Button action;
        private AlertDialog dialog;
        private int at = 0;
        private int styleNoteHeight;
        private String style;
        // applied to the whole app only when the intro closes
        private String colour;

        Cards(final Activity activity) {
            this.activity = activity;
            prefs = PreferenceManager.getDefaultSharedPreferences(activity);
            density = activity.getResources().getDisplayMetrics().density;
            card = activity.getLayoutInflater().inflate(R.layout.dialog_intro, null);
            art = card.findViewById(R.id.intro_art);
            title = card.findViewById(R.id.intro_title);
            body = card.findViewById(R.id.intro_body);
            scroll = card.findViewById(R.id.intro_scroll);
            styles = card.findViewById(R.id.intro_styles);
            colours = card.findViewById(R.id.intro_colours);
            hint = card.findViewById(R.id.intro_hint);
            action = card.findViewById(R.id.intro_action);
            style = prefs.getString(PlayerStyle.PREF_KEY, STYLES[0]);
            if (indexOf(STYLES, style) < 0) {
                style = STYLES[0];
            }
            colour = Accent.stored(activity);
            // a short screen (a TV, a phone on its side) keeps the words in view
            art.setMaxHeight(Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.28f));
        }

        void show() {
            dialog = new AlertDialog.Builder(activity)
                    .setView(card)
                    .setPositiveButton(R.string.intro_next, null)
                    .setNegativeButton(R.string.intro_back, null)
                    .setNeutralButton(R.string.intro_skip, null)
                    .create();

            // wired after show: a builder listener dismisses the dialog, and Next must not
            dialog.setOnShowListener(shown -> {
                final Button next = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                final Button back = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                final Button skip = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                // fixed width: past a width the dialog lays its button row out taller
                int widest = 0;
                for (final int text : new int[]{R.string.intro_next, R.string.intro_done}) {
                    next.setText(text);
                    next.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    widest = Math.max(widest, next.getMeasuredWidth());
                }
                next.setMinWidth(widest);
                next.setMinimumWidth(widest);
                next.setOnClickListener(v -> {
                    if (at == TITLES.length - 1) {
                        close(false);
                        return;
                    }
                    at++;
                    draw();
                    next.requestFocus();
                });
                back.setOnClickListener(v -> {
                    if (at > 0) {
                        at--;
                    }
                    draw();
                    next.requestFocus();
                });
                skip.setOnClickListener(v -> close(false));
                action.setOnClickListener(v -> close(true));
                buildChoices();
                draw();
                next.requestFocus();
                // every card as tall as the tallest, so the window keeps its size
                card.setAlpha(0f);
                card.post(this::evenOut);
            });

            // Back cancels without a button press; it still counts as seen
            dialog.setOnCancelListener(cancelled -> close(false));
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }

        private void evenOut() {
            final int width = card.getWidth();
            if (width <= 0) {
                card.setAlpha(1f);
                return;
            }
            final int showing = at;
            // the style's note as tall as the longest, so the choices under it stay put
            final int textWidth = width - card.getPaddingLeft() - card.getPaddingRight();
            for (final int note : STYLE_NOTES) {
                body.setText(note);
                body.measure(View.MeasureSpec.makeMeasureSpec(textWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                styleNoteHeight = Math.max(styleNoteHeight, body.getMeasuredHeight());
            }
            // real layout passes, hidden: a bare measure came out short on long pages
            windowAlpha(0f);
            at = 0;
            draw();
            layOutNext(showing, 0);
        }

        private void windowAlpha(final float alpha) {
            if (dialog != null && dialog.getWindow() != null) {
                dialog.getWindow().getDecorView().setAlpha(alpha);
            }
        }

        private void layOutNext(final int showing, final int tallestSoFar) {
            card.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    card.getViewTreeObserver().removeOnPreDrawListener(this);
                    final int tallest = Math.max(tallestSoFar, card.getHeight());
                    if (at < TITLES.length - 1) {
                        at++;
                        draw();
                        layOutNext(showing, tallest);
                    } else {
                        at = showing;
                        draw();
                        fit(tallest);
                    }
                    return false;
                }
            });
            card.requestLayout();
        }

        private void fit(final int tallest) {
            // the room the window has under the system's own margins and the buttons
            final int room = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.9f
                    - 96 * density);
            if (tallest <= room) {
                // an exact height: the dialog did not always honour a minimum one
                final ViewGroup.LayoutParams params = card.getLayoutParams();
                params.height = tallest;
                card.setLayoutParams(params);
            } else {
                final ViewGroup.LayoutParams params = card.getLayoutParams();
                params.height = room;
                card.setLayoutParams(params);
                final LinearLayout.LayoutParams scrollParams = (LinearLayout.LayoutParams) scroll.getLayoutParams();
                scrollParams.height = 0;
                scrollParams.weight = 1;
                scroll.setLayoutParams(scrollParams);
            }
            card.setAlpha(1f);
            windowAlpha(1f);
        }

        private static void unseen(final Button button, final boolean unseen) {
            button.setVisibility(View.VISIBLE);
            button.setAlpha(unseen ? 0f : 1f);
            button.setEnabled(!unseen);
            button.setFocusable(!unseen);
            button.setImportantForAccessibility(unseen
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }

        private void draw() {
            final boolean last = at == TITLES.length - 1;
            title.setText(TITLES[at]);
            final boolean choosing = at == STYLE_CARD;
            art.setImageDrawable(picture(choosing ? STYLE_BIG[indexOf(STYLES, style)] : PICTURES[at]));
            body.setMinHeight(choosing ? styleNoteHeight : 0);
            if (choosing) {
                body.setText(STYLE_NOTES[indexOf(STYLES, style)]);
            } else if (at == SETTINGS_CARD && Utils.isTvBox(activity)) {
                // a TV has no search in settings
                body.setText(R.string.intro_settings_body_tv);
            } else {
                body.setText(BODIES[at]);
            }
            styles.setVisibility(choosing ? View.VISIBLE : View.GONE);
            colours.setVisibility(choosing ? View.VISIBLE : View.GONE);
            hint.setVisibility(choosing ? View.VISIBLE : View.GONE);
            action.setVisibility(at == KEY_CARD || at == SETTINGS_CARD ? View.VISIBLE : View.GONE);
            scroll.scrollTo(0, 0);
            if (dialog != null) {
                final Button next = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (next != null) {
                    next.setText(last ? R.string.intro_done : R.string.intro_next);
                    // see-through, never gone: a row of three long buttons lays out taller
                    unseen(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), at == 0);
                    unseen(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), last);
                }
            }
        }

        private Drawable picture(final int res) {
            return AppCompatResources.getDrawable(Accent.themed(activity, colour), res);
        }

        private void buildChoices() {
            styles.removeAllViews();
            final int gap = Math.round(4 * density);
            for (int i = 0; i < STYLES.length; i++) {
                final LinearLayout option = new LinearLayout(activity);
                option.setOrientation(LinearLayout.VERTICAL);
                option.setPadding(gap, gap, gap, gap);
                option.setFocusable(true);
                option.setClickable(true);
                option.setContentDescription(activity.getString(STYLE_NAMES[i]));
                final ImageView picture = new ImageView(activity);
                picture.setAdjustViewBounds(true);
                picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                option.addView(picture, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                final TextView name = new TextView(activity);
                name.setText(STYLE_NAMES[i]);
                name.setTextAppearance(android.R.style.TextAppearance_Material_Small);
                name.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                name.setSingleLine(true);
                name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                option.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                final String key = STYLES[i];
                option.setOnClickListener(v -> {
                    style = key;
                    // saved at once, so the first film already plays in it
                    prefs.edit().putString(PlayerStyle.PREF_KEY, key).apply();
                    paintChoices();
                    draw();
                });
                final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                params.setMargins(i == 0 ? 0 : gap, 0, i == STYLES.length - 1 ? 0 : gap, 0);
                styles.addView(option, params);
            }

            colours.removeAllViews();
            final String[] keys = Accent.keys();
            final String[] names = activity.getResources().getStringArray(R.array.accent_entries);
            final int perRow = 6;
            final int dot = Math.round(32 * density);
            LinearLayout row = null;
            for (int i = 0; i < keys.length; i++) {
                if (i % perRow == 0) {
                    row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    colours.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                final View swatch = new View(activity);
                swatch.setFocusable(true);
                swatch.setClickable(true);
                swatch.setContentDescription(i < names.length ? names[i] : keys[i]);
                final String key = keys[i];
                swatch.setOnClickListener(v -> {
                    colour = key;
                    paintChoices();
                    draw();
                });
                final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dot, dot);
                params.setMargins(0, gap, Math.round(10 * density), gap);
                row.addView(swatch, params);
            }
            paintChoices();
        }

        private void paintChoices() {
            final int accent = Accent.colorOf(activity, colour);
            for (int i = 0; i < styles.getChildCount(); i++) {
                final LinearLayout option = (LinearLayout) styles.getChildAt(i);
                final boolean picked = STYLES[i].equals(style);
                option.setBackground(SettingsActivity.SettingsFragment.pictureCardBackground(picked, accent, density));
                ((ImageView) option.getChildAt(0)).setImageDrawable(picture(STYLE_SMALL[i]));
                option.setSelected(picked);
            }
            final String[] keys = Accent.keys();
            int n = 0;
            for (int r = 0; r < colours.getChildCount(); r++) {
                final LinearLayout row = (LinearLayout) colours.getChildAt(r);
                for (int c = 0; c < row.getChildCount(); c++, n++) {
                    row.getChildAt(c).setBackground(swatch(Accent.colorOf(activity, keys[n]),
                            keys[n].equals(colour)));
                }
            }
        }

        private Drawable swatch(final int fill, final boolean picked) {
            final StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_focused}, dot(fill, true, 3));
            states.addState(new int[]{}, dot(fill, picked, 2));
            return states;
        }

        private Drawable dot(final int fill, final boolean ringed, final int ringDp) {
            final GradientDrawable inner = new GradientDrawable();
            inner.setShape(GradientDrawable.OVAL);
            inner.setColor(fill);
            if (!ringed) {
                return inner;
            }
            final GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(0);
            ring.setStroke(Math.round(ringDp * density),
                    AppTheme.isNight(activity) ? 0xFFFFFFFF : 0xFF202020);
            final LayerDrawable both = new LayerDrawable(new Drawable[]{ring, inner});
            final int inset = Math.round((ringDp + 2) * density);
            both.setLayerInset(1, inset, inset, inset, inset);
            return both;
        }

        private void close(final boolean openSettings) {
            markSeen(activity, true);
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
            final boolean recoloured = !colour.equals(Accent.stored(activity));
            if (recoloured) {
                prefs.edit().putString(Accent.PREF_KEY, colour).apply();
            }
            if (openSettings) {
                // home redraws itself in the new colour on the way back
                activity.startActivity(new Intent(activity, SettingsActivity.class));
            } else if (recoloured) {
                activity.recreate();
            }
        }

        private static int indexOf(final String[] all, final String key) {
            for (int i = 0; i < all.length; i++) {
                if (all[i].equals(key)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
