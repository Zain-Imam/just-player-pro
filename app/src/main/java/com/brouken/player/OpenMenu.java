package com.brouken.player;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

final class OpenMenu {

    private OpenMenu() {
    }

    interface OnPlay {
        void play(Uri uri, @Nullable String type);
    }

    private static boolean isPlayable(final Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            return false;
        }
        // A network URL needs a host; file:// and content:// do not.
        return History.isNetworkUri(uri) ? uri.getHost() != null && !uri.getHost().isEmpty() : true;
    }

    static void showSubtitleSources(final PlayerActivity activity, final Runnable loadFile,
                                    final Runnable searchOnline, final Runnable reIdentify,
                                    final boolean identified) {
        final java.util.List<CharSequence> labels = new java.util.ArrayList<>();
        final java.util.List<Runnable> actions = new java.util.ArrayList<>();

        labels.add(activity.getString(R.string.subtitle_source_file));
        actions.add(loadFile);

        labels.add(activity.getString(R.string.online_search_subtitles));
        actions.add(searchOnline);

        if (identified) {
            labels.add(activity.getString(R.string.online_change_selection));
            actions.add(reIdentify);
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.subtitle_source_title)
                .setItems(labels.toArray(new CharSequence[0]),
                        (dialog, which) -> actions.get(which).run())
                .show();
    }

    static void show(final PlayerActivity activity) {
        final List<History.Entry> recent =
                History.load(androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity));

        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add(activity.getString(R.string.open_source_local));
        actions.add(() -> activity.openFile(activity.mPrefs.mediaUri));

        labels.add(activity.getString(R.string.open_source_url));
        actions.add(() -> showUrlInput(activity, activity::playMedia));

        if (!recent.isEmpty()) {
            labels.add(activity.getString(R.string.open_source_recent));
            actions.add(() -> showRecent(activity, recent));
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.open_source_title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    static void showUrl(final android.app.Activity activity, final OnPlay onPlay) {
        showUrlInput(activity, onPlay);
    }

    private static void showUrlInput(final android.app.Activity activity, final OnPlay onPlay) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setHint(R.string.open_url_hint);
        input.setSingleLine(true);

        // AlertDialog gives a custom view no margins of its own.
        final int margin = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 20, activity.getResources().getDisplayMetrics());
        final FrameLayout container = new FrameLayout(activity);
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = margin;
        params.rightMargin = margin;
        container.addView(input, params);

        // Paste must not dismiss, so its listener is set after show()
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.open_url_title)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.open_url_paste, null)
                .setPositiveButton(R.string.open_url_play, (d, which) -> play(activity, onPlay, input.getText().toString()))
                .create();

        input.setOnEditorActionListener((view, actionId, event) -> {
            dialog.dismiss();
            play(activity, onPlay, input.getText().toString());
            return true;
        });

        dialog.show();

        final android.widget.Button paste = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (paste != null) {
            paste.setOnClickListener(view -> {
                final String text = clipboardText(activity);
                if (text == null || text.isEmpty()) {
                    Toast.makeText(activity, R.string.open_url_clipboard_empty,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                input.setText(text);
                input.setSelection(text.length());
            });
        }
    }

    private static void showRecent(final PlayerActivity activity, final List<History.Entry> recent) {
        final String[] labels = new String[recent.size()];
        for (int i = 0; i < recent.size(); i++) {
            labels[i] = recent.get(i).name;
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.open_source_recent)
                .setItems(labels, (dialog, which) -> {
                    final History.Entry entry = recent.get(which);
                    activity.playMedia(entry.uri, entry.type);
                })
                .show();
    }

    private static void play(final android.app.Activity activity, final OnPlay onPlay, final String text) {
        final String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        final Uri uri = Uri.parse(trimmed);
        if (!isPlayable(uri)) {
            Toast.makeText(activity, R.string.open_url_invalid, Toast.LENGTH_LONG).show();
            return;
        }

        // type is worked out from the response
        onPlay.play(uri, null);
    }

    private static String clipboardText(final Context context) {
        try {
            final ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return null;
            }
            final ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return null;
            }
            final CharSequence text = clip.getItemAt(0).coerceToText(context);
            return text == null ? null : text.toString().trim();
        } catch (Exception e) {
            Utils.log("Could not read clipboard: " + e);
            return null;
        }
    }

    private static String clipboardUrl(final Context context) {
        try {
            final ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return null;
            }
            final ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return null;
            }
            final CharSequence text = clip.getItemAt(0).coerceToText(context);
            if (text == null) {
                return null;
            }
            final String candidate = text.toString().trim();
            return History.isNetworkUri(Uri.parse(candidate)) ? candidate : null;
        } catch (Exception e) {
            Utils.log("Could not read clipboard: " + e);
            return null;
        }
    }
}
