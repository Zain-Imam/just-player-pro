package com.brouken.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.widget.Button;

import androidx.preference.PreferenceManager;

// one-time intro on the folder list, covering only what the screen cannot show
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

    // the slide with the Open settings button
    private static final int KEY_SLIDE = 1;

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
        final int[] slide = {0};

        final android.view.View content = activity.getLayoutInflater()
                .inflate(R.layout.dialog_intro, null);
        final android.widget.TextView body = content.findViewById(R.id.intro_body);
        final Button action = content.findViewById(R.id.intro_action);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(TITLES[0])
                .setView(content)
                .setPositiveButton(R.string.intro_next, null)
                .setNegativeButton(R.string.intro_back, null)
                .setNeutralButton(R.string.intro_skip, null)
                .create();

        // wired after show: a builder listener dismisses the dialog, and Next must not
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

        // Back cancels without a button press; it still counts as seen
        dialog.setOnCancelListener(cancelled -> markSeen(activity, true));

        // A stray tap beside it should not count as an answer; Back still does.
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }
}
