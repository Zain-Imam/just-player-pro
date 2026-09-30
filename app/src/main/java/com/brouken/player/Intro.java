package com.brouken.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.widget.Button;

import androidx.preference.PreferenceManager;

/**
 * The short introduction shown once, on the folder list.
 *
 * <p>What was here before were two TapTargetView spotlights in the player,
 * written when the player was the only screen there was. With a home screen in
 * front of it they fired over a film that was already playing, drew themselves
 * cut off, and the first of them pointed at the player's own Open button to
 * tell a new arrival how to choose a video -- which is no longer how anybody
 * starts. They are replaced rather than repaired.
 *
 * <p>A plain dialog, on the screen the application actually opens on, and never
 * over a film. One dialog whose contents change on Next rather than a pager:
 * there are four slides and they are text, so a pager would be a dependency and
 * a lifecycle for no gain.
 *
 * <p>What it covers is deliberately only the things that cannot be discovered
 * by reading the screen -- gestures, long presses, and the key that turns the
 * online features on. Anything visible in settings is left for settings.
 */
final class Intro {

    private static final String PREF_SEEN = "introSeen";

    private static final int[] TITLES = {
            R.string.intro_folders_title,
            R.string.intro_key_title,
            R.string.intro_gestures_title,
            R.string.intro_holds_title,
    };

    private static final int[] BODIES = {
            R.string.intro_folders_body,
            R.string.intro_key_body,
            R.string.intro_gestures_body,
            R.string.intro_holds_body,
    };

    /** The slide carrying the Open settings button. */
    private static final int KEY_SLIDE = 1;

    private Intro() {
    }

    /** True until it has been shown, or until somebody asks to see it again. */
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
        final int[] slide = {0};

        final android.view.View content = activity.getLayoutInflater()
                .inflate(R.layout.dialog_intro, null);
        final android.widget.TextView body = content.findViewById(R.id.intro_body);
        final Button action = content.findViewById(R.id.intro_action);

        /*
         * Back, Skip and Next, in that order.
         *
         * Going forward with no way back meant a slide read too quickly was
         * gone for good, short of dismissing the whole thing and finding it
         * again in settings. A dialog has exactly three buttons, so "Open
         * settings" moved into the slide itself to make room -- which suits it
         * better in any case.
         */
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(TITLES[0])
                .setView(content)
                .setPositiveButton(R.string.intro_next, null)
                .setNegativeButton(R.string.intro_back, null)
                .setNeutralButton(R.string.intro_skip, null)
                .create();

        /*
         * The buttons are wired after the dialog is showing, because a listener
         * given to the builder dismisses the dialog when it fires and Next has
         * to leave it up.
         */
        dialog.setOnShowListener(shown -> {
            final Button next = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final Button back = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            final Button skip = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);

            final Runnable draw = () -> {
                final boolean last = slide[0] == TITLES.length - 1;
                dialog.setTitle(TITLES[slide[0]]);
                body.setText(BODIES[slide[0]]);
                next.setText(last ? R.string.intro_done : R.string.intro_next);
                // Nothing to go back to on the first, nothing to skip on the last.
                back.setVisibility(slide[0] == 0 ? Button.GONE : Button.VISIBLE);
                skip.setVisibility(last ? Button.GONE : Button.VISIBLE);
                action.setVisibility(slide[0] == KEY_SLIDE
                        ? Button.VISIBLE : Button.GONE);
            };

            next.setOnClickListener(v -> {
                if (slide[0] == TITLES.length - 1) {
                    markSeen(activity, true);
                    dialog.dismiss();
                    return;
                }
                slide[0]++;
                draw.run();
                next.requestFocus();
            });

            back.setOnClickListener(v -> {
                if (slide[0] > 0) {
                    slide[0]--;
                }
                draw.run();
                next.requestFocus();
            });

            skip.setOnClickListener(v -> {
                markSeen(activity, true);
                dialog.dismiss();
            });

            action.setOnClickListener(v -> {
                markSeen(activity, true);
                dialog.dismiss();
                activity.startActivity(new Intent(activity, SettingsActivity.class));
            });

            draw.run();
            next.requestFocus();
        });

        /*
         * However it is closed, it has been shown.
         *
         * Back dismisses a dialog without any button being pressed, and marking
         * it seen only from the buttons meant backing out left it to open again
         * on every visit to this screen for the rest of time. Anyone who wants
         * it back has Show the introduction again in settings.
         */
        dialog.setOnCancelListener(cancelled -> markSeen(activity, true));

        // A stray tap beside it should not count as an answer; Back still does.
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }
}
