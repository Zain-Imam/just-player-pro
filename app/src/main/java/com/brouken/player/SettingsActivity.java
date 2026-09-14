package com.brouken.player;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.obsez.android.lib.filechooser.ChooserDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;

public class SettingsActivity extends AppCompatActivity {

    static RecyclerView recyclerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
            getWindow().setNavigationBarColor(Color.TRANSPARENT);

            if (Build.VERSION.SDK_INT >= 35) {
                int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

                if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                    getWindow().getDecorView().setSystemUiVisibility(0);
                } else if (nightModeFlags == Configuration.UI_MODE_NIGHT_NO) {
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                }
            }
        }

        // Also here, not only in the player: someone can reach settings first.
        com.brouken.player.online.SubtitleAddons.seedDefault(this);

        super.onCreate(savedInstanceState);

        // After super: AppCompat re-applies the manifest theme in its own
        // onCreate, which discards an overlay set before it.
        Accent.apply(this);

        setContentView(R.layout.settings_activity);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        if (Build.VERSION.SDK_INT >= 29) {
            LinearLayout layout = findViewById(R.id.settings_layout);
            layout.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                view.setPadding(windowInsets.getSystemWindowInsetLeft(),
                        windowInsets.getSystemWindowInsetTop(),
                        windowInsets.getSystemWindowInsetRight(),
                        0);
                if (recyclerView != null) {
                    recyclerView.setPadding(0, 0, 0, windowInsets.getSystemWindowInsetBottom());
                }
                windowInsets.consumeSystemWindowInsets();
                return windowInsets;
            });
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        private static final String PREF_KEY_CUSTOM_SUBTITLE_FONT_CHOOSE = "subtitleCustomFontChoose";
        private static final String[] CUSTOM_FONT_MIME_TYPES = new String[]{
                "font/*",
                "application/x-font-ttf",
                "application/x-font-otf",
                "application/x-font-ttc",
                "application/vnd.ms-opentype",
                "application/octet-stream"
        };
        private static final String[] CUSTOM_FONT_EXTENSIONS = new String[]{
                "ttf",
                "otf",
                "ttc",
                "otc"
        };

        private SwitchPreferenceCompat customSubtitleFontSwitch;
        private Preference customSubtitleFontChoose;
        private ActivityResultLauncher<String[]> customSubtitleFontPicker;
        private ActivityResultLauncher<Uri> subtitleFolderPicker;
        private Preference subtitleFolderChoose;
        private boolean pendingCustomFontFallbackPermission;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            customSubtitleFontPicker = registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    this::handleCustomSubtitleFontPick
            );
            subtitleFolderPicker = registerForActivityResult(
                    new ActivityResultContracts.OpenDocumentTree(),
                    this::handleSubtitleFolderPick
            );
        }

        /*
         * A key is asked about before it is kept.
         *
         * A mistyped key used to sit there looking fine until the first search
         * came back with nothing, which reads as the service being down rather
         * than as a typo. The service is asked first, and the key is only
         * written if it answers. Clearing is always allowed.
         */
        /*
         * Nothing here has an icon, so nothing should be indented for one.
         *
         * The preference list leaves a gap at the start of every row for an
         * icon whether or not there is one, which pushed all the text inwards
         * and wasted the width — most visible on a wide screen, where the
         * settings ended up as a narrow column with an empty margin beside it.
         */
        private void useFullWidth(final androidx.preference.PreferenceGroup group) {
            group.setIconSpaceReserved(false);
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                final Preference preference = group.getPreference(i);
                preference.setIconSpaceReserved(false);
                if (preference instanceof androidx.preference.PreferenceGroup) {
                    useFullWidth((androidx.preference.PreferenceGroup) preference);
                }
            }
        }

        /*
         * Hand the typing to a phone.
         *
         * A key is thirty-odd characters and an addon URL is longer, which on a
         * television means a D-pad and an on-screen keyboard. The page only
         * exists while this screen does, and it asks for a PIN shown here
         * before it accepts anything. Everything can still be typed in by hand,
         * which is what happens anyway if the page cannot be opened.
         */
        private com.brouken.player.online.SetupServer setupServer;

        /*
         * Show the colours, not only their names.
         *
         * A list reading Red, Orange, Yellow tells you nothing about what any
         * of them actually looks like, and two of the eleven are close enough
         * that the name is no help at all. Each row carries a disc of its own
         * colour, and the list is our own dialog because a ListPreference will
         * not put a drawable beside an entry.
         */
        /*
         * A preference that opens its own dialog opens it whether or not
         * anybody asked.
         *
         * A click listener on a ListPreference is not a replacement for its
         * dialog, it is an addition to one: the library calls onClick before it
         * consults the listener, so the plain list of names was already on its
         * way when the list of colours was built. The plain one arrived second,
         * because it goes through a fragment transaction, and landed on top —
         * which looked like the colours only appearing after a press of back.
         *
         * This is the hook meant for the job: answer for the preference, and
         * the library does not open anything of its own.
         */
        @Override
        public void onDisplayPreferenceDialog(@NonNull Preference preference) {
            if (preference instanceof ListPreference && "accentColor".equals(preference.getKey())) {
                showAccentSwatches((ListPreference) preference);
                return;
            }
            super.onDisplayPreferenceDialog(preference);
        }

        private void showAccentSwatches(final ListPreference preference) {
            {
                final String[] names = getResources().getStringArray(R.array.accent_entries);
                final String[] values = getResources().getStringArray(R.array.accent_values);
                final int current = Math.max(0, java.util.Arrays.asList(values)
                        .indexOf(preference.getValue()));

                final android.widget.ArrayAdapter<String> adapter =
                        new android.widget.ArrayAdapter<String>(requireContext(),
                                android.R.layout.simple_list_item_single_choice, names) {
                            @NonNull
                            @Override
                            public View getView(int position, View convertView,
                                                @NonNull android.view.ViewGroup parent) {
                                final View view = super.getView(position, convertView, parent);
                                final android.widget.TextView text =
                                        (android.widget.TextView) view;
                                final android.graphics.drawable.Drawable disc =
                                        androidx.core.content.ContextCompat.getDrawable(
                                                requireContext(), R.drawable.accent_swatch);
                                if (disc != null) {
                                    disc.mutate().setColorFilter(
                                            new android.graphics.PorterDuffColorFilter(
                                                    Accent.colorOf(requireContext(), values[position]),
                                                    android.graphics.PorterDuff.Mode.SRC_IN));
                                    text.setCompoundDrawablesWithIntrinsicBounds(
                                            disc, null, null, null);
                                    text.setCompoundDrawablePadding(Utils.dpToPx(16));
                                }
                                return view;
                            }
                        };

                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle(R.string.pref_accent)
                        .setSingleChoiceItems(adapter, current, (dialog, which) -> {
                            preference.setValue(values[which]);
                            dialog.dismiss();
                            requireActivity().recreate();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        }

        /*
         * Ask everything whether it still works, without changing anything.
         *
         * A key and an addon were only ever checked as they were typed in, so a
         * service that stopped answering last week still looked fine here and
         * the first sign of it was an empty subtitle search in the evening.
         * This asks all of them and says which answered.
         */
        private void attachCheckEverything() {
            final Preference preference = findPreference("checkEverything");
            if (preference == null) {
                return;
            }
            preference.setOnPreferenceClickListener(clicked -> {
                final android.content.Context context = requireContext().getApplicationContext();
                android.widget.Toast.makeText(context, R.string.pref_checking_all,
                        android.widget.Toast.LENGTH_SHORT).show();

                new Thread(() -> {
                    final StringBuilder report = new StringBuilder();

                    for (final String key : new String[]{
                            com.brouken.player.online.ApiKeys.PREF_TMDB,
                            com.brouken.player.online.ApiKeys.PREF_OPENSUBTITLES,
                            com.brouken.player.online.ApiKeys.PREF_SUBDL,
                            com.brouken.player.online.ApiKeys.PREF_WYZIE}) {
                        final String value = com.brouken.player.online.ApiKeys.get(context, key);
                        if (value == null || value.isEmpty()) {
                            continue;
                        }
                        final com.brouken.player.online.KeyCheck.Result result =
                                com.brouken.player.online.KeyCheck.check(key, value);
                        report.append(result.ok ? "\u2713 " : "\u2717 ")
                                .append(result.message).append('\n');
                    }

                    for (int slot = 1; slot <= com.brouken.player.online.SubtitleAddons.MAX; slot++) {
                        final String url = PreferenceManager.getDefaultSharedPreferences(context)
                                .getString(com.brouken.player.online.SubtitleAddons.key(slot), "");
                        if (url == null || url.isEmpty()) {
                            continue;
                        }
                        final com.brouken.player.online.SubtitleAddons.Probe probe =
                                com.brouken.player.online.SubtitleAddons.probe(url);
                        report.append(probe.accepted() ? "\u2713 " : "\u2717 ")
                                .append("Addon ").append(slot).append(": ")
                                .append(probe.accepted()
                                        ? (probe.name == null ? "answered" : probe.name)
                                        : probe.verdict.name().toLowerCase(Locale.US)
                                                .replace('_', ' '))
                                .append('\n');
                    }

                    final String text = report.length() == 0
                            ? getString(R.string.pref_check_all_none)
                            : report.toString().trim();

                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) {
                            return;
                        }
                        Utils.showFocused(new android.app.AlertDialog.Builder(requireContext())
                                .setTitle(R.string.pref_check_all_title)
                                .setMessage(text)
                                .setPositiveButton(android.R.string.ok, null)
                                .create(), android.app.AlertDialog.BUTTON_POSITIVE);
                    });
                }).start();
                return true;
            });
        }

        private void attachAbout() {
            final Preference version = findPreference("aboutVersion");
            if (version != null) {
                version.setSummary(getString(R.string.pref_about_version,
                        BuildConfig.VERSION_NAME));
            }
            final Preference preference = findPreference("aboutProject");
            if (preference == null) {
                return;
            }
            preference.setOnPreferenceClickListener(clicked -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/Zain-Imam")));
                } catch (Exception ignored) {
                    // A television with no browser to open it in.
                }
                return true;
            });
        }

        private void attachSetupServer() {
            final Preference preference = findPreference("setupFromPhone");
            if (preference == null) {
                return;
            }
            preference.setOnPreferenceClickListener(clicked -> {
                if (setupServer != null) {
                    setupServer.stop();
                }
                setupServer = new com.brouken.player.online.SetupServer(
                        requireContext(), saved -> {
                });
                final String address = setupServer.start();
                if (address == null) {
                    setupServer = null;
                    new android.app.AlertDialog.Builder(requireContext())
                            .setMessage(R.string.pref_setup_phone_failed)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return true;
                }
                final android.app.AlertDialog dialog =
                        new android.app.AlertDialog.Builder(requireContext())
                                .setTitle(R.string.pref_setup_phone_title)
                                .setMessage(getString(R.string.pref_setup_phone_body,
                                        address, setupServer.pin()))
                                .setCancelable(false)
                                .setPositiveButton(R.string.pref_setup_phone_stop, (d, which) -> {
                                    stopSetupServer();
                                    // Whatever the page wrote is read back in.
                                    setPreferenceScreen(null);
                                    onCreatePreferences(null, null);
                                })
                                .create();
                dialog.show();
                // Red, because it is the button that takes the page away: the
                // phone loses it the moment this is pressed.
                final android.widget.Button stop =
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
                if (stop != null) {
                    stop.setTextColor(0xFFFF5252);
                    stop.requestFocus();
                }
                return true;
            });
        }

        private void stopSetupServer() {
            if (setupServer != null) {
                setupServer.stop();
                setupServer = null;
            }
        }

        @Override
        public void onDestroyView() {
            stopSetupServer();
            super.onDestroyView();
        }

        private void attachKeyChecks(final String... keys) {
            for (final String key : keys) {
                final EditTextPreference preference = findPreference(key);
                if (preference == null) {
                    continue;
                }
                preference.setOnPreferenceChangeListener((changed, newValue) -> {
                    final String value = newValue == null ? "" : newValue.toString().trim();
                    if (value.isEmpty()) {
                        return true;
                    }
                    checkThenSave((EditTextPreference) changed, key, value);
                    // Not yet: written by checkThenSave once the service agrees.
                    return false;
                });
            }
        }

        private void checkThenSave(final EditTextPreference preference,
                                   final String key, final String value) {
            final android.content.Context context = requireContext().getApplicationContext();
            android.widget.Toast.makeText(context, R.string.pref_key_checking,
                    android.widget.Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                final com.brouken.player.online.KeyCheck.Result result =
                        com.brouken.player.online.KeyCheck.check(key, value);
                final android.os.Handler handler =
                        new android.os.Handler(android.os.Looper.getMainLooper());
                handler.post(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    if (result.ok) {
                        preference.setText(value);
                    }
                    android.widget.Toast.makeText(context, result.message,
                            android.widget.Toast.LENGTH_LONG).show();
                });
            }).start();
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            useFullWidth(getPreferenceScreen());

            final Preference update = findPreference("checkUpdate");
            if (update != null) {
                update.setOnPreferenceClickListener(clicked -> {
                    new Updater(requireActivity()).check(false);
                    return true;
                });
            }

            attachSetupServer();
            attachCheckEverything();
            attachAbout();

            attachKeyChecks(com.brouken.player.online.ApiKeys.PREF_TMDB,
                    com.brouken.player.online.ApiKeys.PREF_OPENSUBTITLES,
                    com.brouken.player.online.ApiKeys.PREF_SUBDL,
                    com.brouken.player.online.ApiKeys.PREF_WYZIE);

            subtitleFolderChoose = findPreference("subtitleFolderChoose");
            if (subtitleFolderChoose != null) {
                updateSubtitleFolderSummary();
                subtitleFolderChoose.setOnPreferenceClickListener(preference -> {
                    if (SubtitleStorage.getFolder(requireContext()) != null) {
                        // Already set: offer to go back to the default rather than
                        // making "undo" mean finding the old folder again.
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle(R.string.pref_subtitle_folder)
                                .setItems(new CharSequence[]{
                                        getString(R.string.pref_subtitle_folder_choose),
                                        getString(R.string.pref_subtitle_folder_clear),
                                }, (dialog, which) -> {
                                    if (which == 0) {
                                        launchSubtitleFolderPicker();
                                    } else {
                                        SubtitleStorage.setFolder(requireContext(), null);
                                        updateSubtitleFolderSummary();
                                    }
                                })
                                .show();
                    } else {
                        launchSubtitleFolderPicker();
                    }
                    return true;
                });
            }

            final ListPreference enginePreference = findPreference("playbackEngine");
            if (enginePreference != null) {
                if (!com.brouken.player.mpv.MpvPlayer.isSupported()) {
                    enginePreference.setEnabled(false);
                    enginePreference.setSummary(R.string.pref_engine_mpv_unsupported);
                    enginePreference.setValue("media3");
                }
                applyEngineDependentState(enginePreference.getValue());
                enginePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    applyEngineDependentState(String.valueOf(newValue));
                    return true;
                });
            }

            Preference preferenceFolders = findPreference("foldersOpen");
            if (preferenceFolders != null) {
                final int folders = new Prefs(requireContext()).scopeUris.size();
                preferenceFolders.setSummary(folders == 0
                        ? getString(R.string.pref_folders_summary_empty)
                        : getResources().getQuantityString(
                                R.plurals.pref_folders_summary, folders, folders));
                preferenceFolders.setOnPreferenceClickListener(preference -> {
                    requireActivity().getSupportFragmentManager()
                            .beginTransaction()
                            .replace(R.id.settings, new FoldersFragment())
                            .addToBackStack(null)
                            .commit();
                    return true;
                });
            }

            Preference preferenceHistory = findPreference("historyOpen");
            if (preferenceHistory != null) {
                int count = History.load(PreferenceManager.getDefaultSharedPreferences(requireContext())).size();
                preferenceHistory.setSummary(count == 0
                        ? getString(R.string.pref_history_summary_empty)
                        : getResources().getQuantityString(R.plurals.pref_history_summary, count, count));
                preferenceHistory.setOnPreferenceClickListener(preference -> {
                    requireActivity().getSupportFragmentManager()
                            .beginTransaction()
                            .replace(R.id.settings, new HistoryFragment())
                            .addToBackStack(null)
                            .commit();
                    return true;
                });
            }

            ListPreference preferenceAccent = findPreference(Accent.PREF_KEY);
            if (preferenceAccent != null) {
                preferenceAccent.setOnPreferenceChangeListener((preference, newValue) -> {
                    // Rebuild the screen so the new colour is visible on the
                    // switch you just touched, rather than next time.
                    preference.getSharedPreferences().edit()
                            .putString(Accent.PREF_KEY, String.valueOf(newValue)).apply();
                    requireActivity().recreate();
                    return true;
                });
            }

            Preference preferenceAddons = findPreference("subtitleAddons");
            if (preferenceAddons != null) {
                final int configured =
                        com.brouken.player.online.SubtitleAddons.saved(requireContext()).size();
                if (configured > 0) {
                    preferenceAddons.setSummary(getResources().getQuantityString(
                            R.plurals.pref_addons_summary_count, configured, configured));
                }
                preferenceAddons.setOnPreferenceClickListener(preference -> {
                    requireActivity().getSupportFragmentManager()
                            .beginTransaction()
                            .replace(R.id.settings, new SubtitleAddonsFragment())
                            .addToBackStack(null)
                            .commit();
                    return true;
                });
            }

            Preference preferenceAutoPiP = findPreference("autoPiP");
            if (preferenceAutoPiP != null) {
                preferenceAutoPiP.setEnabled(Utils.isPiPSupported(this.getContext()));
            }
            Preference preferenceFrameRateMatching = findPreference("frameRateMatching");
            if (preferenceFrameRateMatching != null) {
                preferenceFrameRateMatching.setEnabled(Build.VERSION.SDK_INT >= 23);
            }
            ListPreference listPreferenceFileAccess = findPreference("fileAccess");
            if (listPreferenceFileAccess != null) {
                List<String> entries = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.file_access_entries)));
                List<String> values = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.file_access_values)));
                if (Build.VERSION.SDK_INT < 30) {
                    int index = values.indexOf("mediastore");
                    entries.remove(index);
                    values.remove(index);
                }
                if (!Utils.hasSAFChooser(getContext().getPackageManager())) {
                    int index = values.indexOf("saf");
                    entries.remove(index);
                    values.remove(index);
                }
                listPreferenceFileAccess.setEntries(entries.toArray(new String[0]));
                listPreferenceFileAccess.setEntryValues(values.toArray(new String[0]));
            }

            ListPreference listPreferenceLanguageAudio = findPreference("languageAudio");
            if (listPreferenceLanguageAudio != null) {
                LinkedHashMap<String, String> entries = new LinkedHashMap<>();
                entries.put(Prefs.TRACK_DEFAULT, getString(R.string.pref_language_track_default));
                entries.put(Prefs.TRACK_DEVICE, getString(R.string.pref_language_track_device));
                entries.putAll(getLanguages());
                listPreferenceLanguageAudio.setEntries(entries.values().toArray(new String[0]));
                listPreferenceLanguageAudio.setEntryValues(entries.keySet().toArray(new String[0]));
            }

            customSubtitleFontSwitch = findPreference(Prefs.PREF_KEY_SUBTITLE_CUSTOM_FONT_ENABLED);
            customSubtitleFontChoose = findPreference(PREF_KEY_CUSTOM_SUBTITLE_FONT_CHOOSE);

            if (customSubtitleFontSwitch != null && customSubtitleFontChoose != null) {
                customSubtitleFontChoose.setEnabled(customSubtitleFontSwitch.isChecked());
                updateCustomSubtitleFontSummary();

                customSubtitleFontSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enabled = (Boolean) newValue;
                    customSubtitleFontChoose.setEnabled(enabled);
                    return true;
                });

                customSubtitleFontChoose.setOnPreferenceClickListener(preference -> {
                    if (getContext() == null) return true;
                    if (customSubtitleFontPicker != null) {
                        customSubtitleFontPicker.launch(CUSTOM_FONT_MIME_TYPES);
                    }
                    return true;
                });
            }
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            if (Build.VERSION.SDK_INT >= 29) {
                recyclerView = getListView();
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            if (pendingCustomFontFallbackPermission) {
                pendingCustomFontFallbackPermission = false;
                if (!needsManageExternalStoragePermission()) {
                    openCustomFontChooserFallback();
                }
            }
        }

        LinkedHashMap<String, String> getLanguages() {
            LinkedHashMap<String, String> languages = new LinkedHashMap<>();
            for (Locale locale : Locale.getAvailableLocales()) {
                try {
                    // MissingResourceException: Couldn't find 3-letter language code for zz
                    String key = locale.getISO3Language();
                    String language = locale.getDisplayLanguage();
                    int length = language.offsetByCodePoints(0, 1);
                    if (!language.isEmpty()) {
                        language = language.substring(0, length).toUpperCase(locale) + language.substring(length);
                    }
                    String value = language + " [" + key + "]";
                    languages.put(key, value);
                } catch (MissingResourceException e) {
                    e.printStackTrace();
                }
            }
            Collator collator = Collator.getInstance();
            collator.setStrength(Collator.PRIMARY);
            Utils.orderByValue(languages, collator::compare);
            return languages;
        }

        private void applyEngineDependentState(final String engine) {
            final boolean media3 = !"mpv".equals(engine);

            final String[] media3OnlyKeys = {
                    "tunneling",
                    "mapDV7ToHevc",
                    "skipSilence",
                    "frameRateMatching",
                    "subtitleCustomFontEnabled",
                    "subtitleCustomFontChoose",
            };

            for (final String key : media3OnlyKeys) {
                final Preference preference = findPreference(key);
                if (preference == null) {
                    continue;
                }

                if (!originalSummaries.containsKey(key)) {
                    originalSummaries.put(key, preference.getSummary());
                    originalSummaryProviders.put(key, preference.getSummaryProvider());
                }

                preference.setEnabled(media3 && isNormallyEnabled(key));

                if (!media3) {
                    preference.setSummaryProvider(null);
                    preference.setSummary(R.string.pref_media3_only);
                } else {
                    final Preference.SummaryProvider<?> provider =
                            originalSummaryProviders.get(key);
                    if (provider != null) {
                        setSummaryProviderUnchecked(preference, provider);
                    } else {
                        preference.setSummary(originalSummaries.get(key));
                    }
                }
            }
        }

        private final java.util.Map<String, CharSequence> originalSummaries =
                new java.util.HashMap<>();
        private final java.util.Map<String, Preference.SummaryProvider<?>> originalSummaryProviders =
                new java.util.HashMap<>();

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void setSummaryProviderUnchecked(final Preference preference,
                                                 final Preference.SummaryProvider<?> provider) {
            preference.setSummaryProvider((Preference.SummaryProvider) provider);
        }


        private boolean isNormallyEnabled(final String key) {
            if ("subtitleCustomFontChoose".equals(key)) {
                return customSubtitleFontSwitch != null && customSubtitleFontSwitch.isChecked();
            }
            return true;
        }

        private void launchSubtitleFolderPicker() {
            try {
                subtitleFolderPicker.launch(null);
            } catch (android.content.ActivityNotFoundException e) {
                // Some television builds ship no document picker at all.
                Toast.makeText(requireContext(), R.string.pref_subtitle_folder_failed,
                        Toast.LENGTH_LONG).show();
            }
        }

        private void handleSubtitleFolderPick(@Nullable android.net.Uri uri) {
            if (uri == null) {
                return;
            }
            try {
                requireContext().getContentResolver().takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException e) {
                Log.w(Utils.TAG, e);
                Toast.makeText(requireContext(), R.string.pref_subtitle_folder_failed,
                        Toast.LENGTH_LONG).show();
                return;
            }
            SubtitleStorage.setFolder(requireContext(), uri);
            updateSubtitleFolderSummary();
        }

        private void updateSubtitleFolderSummary() {
            if (subtitleFolderChoose == null) {
                return;
            }
            final String folder = SubtitleStorage.describeFolder(requireContext());
            subtitleFolderChoose.setSummary(folder == null
                    ? getString(R.string.pref_subtitle_folder_default)
                    : folder);
        }

        private void handleCustomSubtitleFontPick(@Nullable android.net.Uri uri) {
            if (uri == null) {
                if (Utils.isTvBox(requireContext())) {
                    openCustomFontChooserFallback();
                }
                return;
            }

            File fontFile = prepareCustomFontDestination();
            if (fontFile == null) {
                showCustomFontError();
                return;
            }

            if (!copyFontToFile(uri, fontFile)) {
                showCustomFontError();
                return;
            }

            String displayName = Utils.getFileName(requireContext(), uri, true);
            persistCustomFont(fontFile, displayName);
        }

        private boolean copyFontToFile(android.net.Uri uri, File destination) {
            try (InputStream inputStream = requireContext().getContentResolver().openInputStream(uri)) {
                if (inputStream == null) {
                    return false;
                }
                return copyFontToFile(inputStream, destination);
            } catch (IOException | SecurityException e) {
                Log.w(Utils.TAG, e);
                return false;
            }
        }

        private boolean copyFontToFile(File source, File destination) {
            try (InputStream inputStream = new FileInputStream(source)) {
                return copyFontToFile(inputStream, destination);
            } catch (IOException e) {
                Log.w(Utils.TAG, e);
                return false;
            }
        }

        private boolean copyFontToFile(InputStream inputStream, File destination) throws IOException {
            try (OutputStream outputStream = new FileOutputStream(destination, false)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
                return true;
            }
        }

        private boolean isTypefaceValid(File fontFile) {
            try {
                Typeface typeface = Typeface.createFromFile(fontFile);
                return typeface != null;
            } catch (RuntimeException e) {
                Log.w(Utils.TAG, e);
                return false;
            }
        }

        @Nullable
        private File prepareCustomFontDestination() {
            File fontsDir = new File(requireContext().getFilesDir(), Prefs.SUBTITLE_CUSTOM_FONT_DIR);
            if (!fontsDir.exists() && !fontsDir.mkdirs()) {
                return null;
            }
            return new File(fontsDir, Prefs.SUBTITLE_CUSTOM_FONT_FILE_NAME);
        }

        private void persistCustomFont(File fontFile, @Nullable String displayName) {
            if (!isTypefaceValid(fontFile)) {
                fontFile.delete();
                showCustomFontError();
                return;
            }
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = fontFile.getName();
            }

            getPreferenceManager().getSharedPreferences()
                    .edit()
                    .putString(Prefs.PREF_KEY_SUBTITLE_CUSTOM_FONT_NAME, displayName)
                    .apply();

            updateCustomSubtitleFontSummary();
        }

        private void updateCustomSubtitleFontSummary() {
            if (customSubtitleFontChoose == null) {
                return;
            }
            String fontName = getPreferenceManager().getSharedPreferences()
                    .getString(Prefs.PREF_KEY_SUBTITLE_CUSTOM_FONT_NAME, null);
            if (fontName == null || fontName.trim().isEmpty()) {
                customSubtitleFontChoose.setSummary(R.string.pref_custom_subtitle_font_not_set);
            } else {
                customSubtitleFontChoose.setSummary(fontName);
            }
        }

        private void showCustomFontError() {
            if (getContext() == null) {
                return;
            }
            Toast.makeText(requireContext(), R.string.pref_custom_subtitle_font_failed, Toast.LENGTH_SHORT).show();
        }

        private boolean needsManageExternalStoragePermission() {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager();
        }

        private void requestManageExternalStoragePermission() {
            if (getContext() == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return;
            }
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            if (intent.resolveActivity(requireContext().getPackageManager()) == null) {
                intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            }
            startActivity(intent);
        }

        private void openCustomFontChooserFallback() {
            if (getActivity() == null) {
                return;
            }
            if (needsManageExternalStoragePermission()) {
                pendingCustomFontFallbackPermission = true;
                requestManageExternalStoragePermission();
                return;
            }
            String startPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
            ChooserDialog chooserDialog = new ChooserDialog(requireActivity(), R.style.FileChooserStyle_Dark)
                    .withStartFile(startPath)
                    .withFilter(false, false, CUSTOM_FONT_EXTENSIONS)
                    .withChosenListener((path, pathFile) -> {
                        if (pathFile == null || !pathFile.isFile()) {
                            showCustomFontError();
                            return;
                        }
                        File fontFile = prepareCustomFontDestination();
                        if (fontFile == null) {
                            showCustomFontError();
                            return;
                        }
                        if (!copyFontToFile(pathFile, fontFile)) {
                            showCustomFontError();
                            return;
                        }
                        persistCustomFont(fontFile, pathFile.getName());
                    })
                    .withOnCancelListener(dialog -> dialog.cancel());

            chooserDialog
                    .withOnBackPressedListener(dialog -> chooserDialog.goBack())
                    .withOnLastBackPressedListener(dialog -> dialog.cancel());

            chooserDialog.build().show();
        }
    }
}
