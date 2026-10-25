package com.brouken.player;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.brouken.player.online.SubtitleAddons;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubtitleAddonsFragment extends PreferenceFragmentCompat {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());

        final PreferenceCategory about = new PreferenceCategory(requireContext());
        about.setIconSpaceReserved(false);
        screen.addPreference(about);

        final Preference note = new Preference(requireContext());
        note.setIconSpaceReserved(false);
        note.setSelectable(false);
        note.setSummary(R.string.pref_addons_note);
        about.addPreference(note);

        for (int slot = 1; slot <= SubtitleAddons.MAX; slot++) {
            about.addPreference(slotPreference(slot));
        }

        setPreferenceScreen(screen);
    }

    private EditTextPreference slotPreference(final int slot) {
        final EditTextPreference preference = new EditTextPreference(requireContext());
        preference.setIconSpaceReserved(false);
        preference.setKey(SubtitleAddons.key(slot));
        preference.setTitle(getString(R.string.pref_addon_slot, slot));
        preference.setDialogTitle(getString(R.string.pref_addon_slot, slot));
        preference.setDialogMessage(getString(R.string.pref_addon_dialog_message));
        preference.setIconSpaceReserved(false);

        preference.setSummaryProvider(pref -> {
            final String text = ((EditTextPreference) pref).getText();
            if (TextUtils.isEmpty(text)) {
                return getString(R.string.pref_addon_empty);
            }
            final String base = SubtitleAddons.normalizeUrl(text);
            final String host = base == null ? null : android.net.Uri.parse(base).getHost();
            return host == null ? text : host;
        });

        preference.setOnPreferenceChangeListener((changed, value) -> {
            final String raw = value == null ? "" : value.toString().trim();
            if (raw.isEmpty()) {
                main.post(() -> ((EditTextPreference) changed).setText(""));
                return true;
            }
            check(raw, (EditTextPreference) changed);
            return false;
        });

        return preference;
    }

    private void check(final String raw, final EditTextPreference preference) {
        final String normalized = SubtitleAddons.normalizeUrl(raw);
        if (normalized == null) {
            Toast.makeText(requireContext(), R.string.pref_addon_invalid, Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(requireContext(), R.string.pref_addon_checking, Toast.LENGTH_SHORT).show();

        worker.execute(() -> {
            final SubtitleAddons.Probe probe = SubtitleAddons.probe(normalized);
            main.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (probe.accepted()) {
                    preference.setText(normalized);
                    Toast.makeText(requireContext(), accepted(probe), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(requireContext(), probe.reason(requireContext()), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private String accepted(final SubtitleAddons.Probe probe) {
        final String message = getString(R.string.pref_addon_ok, probe.name(requireContext()),
                probe.subtitles, probe.languages);
        return probe.downloadVerified
                ? message
                : message + " " + getString(R.string.pref_addon_ok_unverified);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
