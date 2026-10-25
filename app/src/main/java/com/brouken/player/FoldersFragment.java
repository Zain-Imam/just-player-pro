package com.brouken.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

public class FoldersFragment extends PreferenceFragmentCompat {

    private Prefs prefs;
    private PreferenceScreen screen;

    private final ActivityResultLauncher<Uri> pickFolder = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) {
                    return;
                }
                keep(uri);
            });

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        prefs = new Prefs(requireContext());
        screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        populate();
    }


    private void populate() {
        screen.removeAll();

        final PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setIconSpaceReserved(false);
        screen.addPreference(category);

        final Preference note = new Preference(requireContext());
        note.setIconSpaceReserved(false);
        note.setSelectable(false);
        note.setSummary(R.string.pref_folders_note);
        category.addPreference(note);

        if (prefs.scopeUris.isEmpty()) {
            final Preference empty = new Preference(requireContext());
            empty.setIconSpaceReserved(false);
            empty.setSelectable(false);
            empty.setSummary(R.string.pref_folders_empty);
            category.addPreference(empty);
        } else {
            for (final Uri uri : new java.util.ArrayList<>(prefs.scopeUris)) {
                final Preference preference = new Preference(requireContext());
                preference.setIconSpaceReserved(false);
                preference.setTitle(describe(uri));
                preference.setSummary(R.string.pref_folders_remove);
                preference.setSingleLineTitle(false);
                preference.setOnPreferenceClickListener(clicked -> {
                    confirmRemove(uri);
                    return true;
                });
                category.addPreference(preference);
            }
        }

        final Preference add = new Preference(requireContext());
        add.setIconSpaceReserved(false);
        add.setTitle(R.string.pref_folders_add);
        add.setOnPreferenceClickListener(clicked -> {
            try {
                pickFolder.launch(Utils.getMoviesFolderUri());
            } catch (android.content.ActivityNotFoundException e) {
                // many TVs have no document picker
                Toast.makeText(requireContext(), R.string.pref_folders_no_picker,
                        Toast.LENGTH_LONG).show();
            }
            return true;
        });
        screen.addPreference(add);
    }

    // without a persisted permission the grant dies with the process
    private void keep(final Uri uri) {
        try {
            requireContext().getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            Utils.log("The folder grant could not be kept: " + e);
            Toast.makeText(requireContext(), R.string.pref_folders_failed, Toast.LENGTH_LONG).show();
            return;
        }
        prefs.updateScope(uri);
        populate();
    }

    private void confirmRemove(final Uri uri) {
        new AlertDialog.Builder(requireContext())
                .setTitle(describe(uri))
                .setMessage(R.string.pref_folders_remove_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.pref_folders_remove_confirm_yes, (dialog, which) -> {
                    prefs.removeScope(uri);
                    try {
                        requireContext().getContentResolver().releasePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (SecurityException e) {
                        // already released
                    }
                    populate();
                })
                .show();
    }

    private CharSequence describe(final Uri uri) {
        try {
            final DocumentFile folder = DocumentFile.fromTreeUri(requireContext(), uri);
            if (folder != null) {
                final String name = folder.getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (IllegalArgumentException | SecurityException e) {
            // fall through to the uri
        }
        final String path = uri.getLastPathSegment();
        return path == null ? uri.toString() : path;
    }
}
