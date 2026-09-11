package com.brouken.player;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import java.util.List;

public class HistoryFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        populate(screen);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (Build.VERSION.SDK_INT >= 29) {
            SettingsActivity.recyclerView = getListView();
        }
    }

    private void populate(final PreferenceScreen screen) {
        screen.removeAll();

        // The settings screen has no action bar, so a category header is what
        // tells you which screen you are on.
        final PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setTitle(R.string.pref_history);
        screen.addPreference(category);

        final List<History.Entry> entries =
                History.load(PreferenceManager.getDefaultSharedPreferences(requireContext()));

        if (entries.isEmpty()) {
            final Preference empty = new Preference(requireContext());
            empty.setSummary(R.string.pref_history_empty);
            empty.setSelectable(false);
            category.addPreference(empty);
            return;
        }

        for (final History.Entry entry : entries) {
            final Preference preference = new Preference(requireContext());
            preference.setTitle(entry.name);
            preference.setSummary(describe(entry));
            // Titles are file names, which are routinely longer than one line.
            preference.setSingleLineTitle(false);
            preference.setOnPreferenceClickListener(clicked -> {
                play(entry);
                return true;
            });
            category.addPreference(preference);
        }

        final Preference clear = new Preference(requireContext());
        clear.setTitle(R.string.pref_history_clear);
        clear.setOnPreferenceClickListener(clicked -> {
            confirmClear(screen);
            return true;
        });
        screen.addPreference(clear);
    }

    private CharSequence describe(final History.Entry entry) {
        final String host = entry.uri.getHost();
        final CharSequence when = entry.time > 0
                ? DateUtils.getRelativeTimeSpanString(entry.time)
                : null;

        if (host == null || host.isEmpty()) {
            return when != null ? when : entry.uri.toString();
        }
        return when != null ? host + "  ·  " + when : host;
    }

    private void play(final History.Entry entry) {
        final Activity activity = requireActivity();
        // The media type travels with it, exactly as the file chooser supplies
        // one, so PlayerActivity need not care where the URL came from.
        activity.setResult(Activity.RESULT_OK, new Intent().setDataAndType(entry.uri, entry.type));
        activity.finish();
    }

    private void confirmClear(final PreferenceScreen screen) {
        new AlertDialog.Builder(requireContext())
                .setMessage(R.string.pref_history_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.pref_history_clear, (dialog, which) -> {
                    History.clear(PreferenceManager.getDefaultSharedPreferences(requireContext()));
                    populate(screen);
                    Toast.makeText(requireContext(), R.string.pref_history_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
