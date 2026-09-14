package com.brouken.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

/**
 * The folders the player is allowed to look in.
 *
 * There used to be one, granted from inside the player and silently replaced
 * the next time one was granted. That is fine for a library in a single place
 * and wrong for everything else: films on the card and films in internal
 * storage, or a downloads folder and an archive. Whichever you granted second
 * was the only one the player could search for the next episode, or for a
 * subtitle sitting beside the film.
 *
 * Nothing here grants anything by itself. Android's own folder picker does
 * that, and the grant it returns is what gets kept -- so what the player can
 * read is exactly what was handed to it, and removing a row hands it back.
 */
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

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (Build.VERSION.SDK_INT >= 29) {
            SettingsActivity.recyclerView = getListView();
        }
    }

    private void populate() {
        screen.removeAll();

        // The settings screen has no action bar, so a category header is what
        // tells you which screen you are on.
        final PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setIconSpaceReserved(false);
        category.setTitle(R.string.pref_folders);
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
            /*
             * Opened at the films folder, which is where most people keep them,
             * and the picker goes anywhere from there. That is the whole of the
             * difference from before: the starting point is a suggestion rather
             * than the only answer.
             */
            try {
                pickFolder.launch(Utils.getMoviesFolderUri());
            } catch (android.content.ActivityNotFoundException e) {
                // A television often has no document picker at all.
                Toast.makeText(requireContext(), R.string.pref_folders_no_picker,
                        Toast.LENGTH_LONG).show();
            }
            return true;
        });
        screen.addPreference(add);
    }

    /**
     * Hold on to the grant for good, then remember the folder.
     *
     * Without the persisted permission the grant dies with the process and the
     * folder in the list would be one the player can no longer open.
     */
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
                        // Hand the permission back, rather than keeping an
                        // access nothing is going to use.
                        requireContext().getContentResolver().releasePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (SecurityException e) {
                        // Already gone. Nothing to do.
                    }
                    populate();
                })
                .show();
    }

    /** A folder's name as a person would recognise it, not as a tree address. */
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
            // Fall through to the address.
        }
        final String path = uri.getLastPathSegment();
        return path == null ? uri.toString() : path;
    }
}
