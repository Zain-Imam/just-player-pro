package com.brouken.player;

import android.annotation.SuppressLint;
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

    private int navigationInset;

    // the list finishes rather than go to the manifest parent, the player
    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    static final String EXTRA_PAGE = "com.brouken.player.SETTINGS_PAGE";
    static final String EXTRA_HIGHLIGHT = "com.brouken.player.SETTINGS_HIGHLIGHT";
    // anyone can open settings, and any other page name would crash it
    private static final List<String> PAGES = Arrays.asList("screen_appearance", "screen_home",
            "screen_playback", "screen_audio", "screen_gestures", "screen_subtitles", "screen_online",
            "screen_storage", "screen_advanced", "screen_about");

    @Nullable
    private String page() {
        final String page = getIntent().getStringExtra(EXTRA_PAGE);
        return PAGES.contains(page) ? page : null;
    }

    @Override
    public boolean onCreateOptionsMenu(final android.view.Menu menu) {
        // typing is slow with a remote, so a television goes by the categories
        if (!Utils.isTvBox(this)) {
            menu.add(0, 1, 0, R.string.set_search)
                    .setIcon(R.drawable.ic_search_24dp)
                    .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
            final android.graphics.drawable.Drawable icon = menu.findItem(1).getIcon();
            if (icon != null) {
                final boolean dark = (getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                icon.mutate().setTint(dark ? Color.WHITE : Color.BLACK);
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final android.view.MenuItem item) {
        if (item.getItemId() == 1) {
            SettingsSearch.show(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            // the bar icons follow light or dark in AppTheme, on every version
        }

        // settings can be opened before the player
        com.brouken.player.online.SubtitleAddons.seedDefault(this);

        super.onCreate(savedInstanceState);

        // after super: AppCompat re-applies the manifest theme in its onCreate
        Accent.apply(this);

        setContentView(R.layout.settings_activity);
        if (savedInstanceState == null) {
            final String page = page();
            final androidx.fragment.app.Fragment first;
            if (page != null) {
                final Bundle args = new Bundle();
                args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, page);
                args.putString(EXTRA_HIGHLIGHT, getIntent().getStringExtra(EXTRA_HIGHLIGHT));
                first = new SettingsFragment();
                first.setArguments(args);
            } else {
                first = new SettingsHeaders();
            }
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, first)
                    .commit();
        }
        setSupportActionBar(findViewById(R.id.settings_toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(@NonNull final androidx.fragment.app.FragmentManager fm,
                                                      @NonNull final androidx.fragment.app.Fragment f,
                                                      @NonNull final View v, @Nullable final Bundle state) {
                        if (f instanceof PreferenceFragmentCompat) {
                            pad((PreferenceFragmentCompat) f);
                        }
                    }

                    @Override
                    public void onFragmentStarted(@NonNull final androidx.fragment.app.FragmentManager fm,
                                                  @NonNull final androidx.fragment.app.Fragment f) {
                        updateTitle();
                    }
                }, true);

        if (Build.VERSION.SDK_INT >= 29) {
            LinearLayout layout = findViewById(R.id.settings_layout);
            layout.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                view.setPadding(windowInsets.getSystemWindowInsetLeft(),
                        windowInsets.getSystemWindowInsetTop(),
                        windowInsets.getSystemWindowInsetRight(),
                        0);
                navigationInset = windowInsets.getSystemWindowInsetBottom();
                padAll(getSupportFragmentManager());
                windowInsets.consumeSystemWindowInsets();
                return windowInsets;
            });
        }
    }

    private void pad(final PreferenceFragmentCompat page) {
        final RecyclerView list = page.getListView();
        if (list != null) {
            list.setPadding(0, 0, 0, navigationInset);
            list.setClipToPadding(false);
        }
    }

    private void padAll(final androidx.fragment.app.FragmentManager fm) {
        for (final androidx.fragment.app.Fragment f : fm.getFragments()) {
            if (f instanceof PreferenceFragmentCompat && f.getView() != null) {
                pad((PreferenceFragmentCompat) f);
            }
            padAll(f.getChildFragmentManager());
        }
    }

    // a phone readies the first page behind the list, so only an open pane counts
    void updateTitle() {
        final androidx.fragment.app.Fragment shown = getSupportFragmentManager().findFragmentById(R.id.settings);
        androidx.fragment.app.Fragment page = shown;
        if (shown instanceof SettingsHeaders) {
            page = null;
            if (shown.getView() != null) {
                final androidx.slidingpanelayout.widget.SlidingPaneLayout pane =
                        ((SettingsHeaders) shown).getSlidingPaneLayout();
                final androidx.fragment.app.Fragment detail = shown.getChildFragmentManager()
                        .findFragmentById(androidx.preference.R.id.preferences_detail);
                if (!pane.isSlideable() || pane.isOpen()) {
                    page = detail;
                }
                ((SettingsHeaders) shown).markOpen(pane.isSlideable() ? null : detail);
            }
        }
        setTitle(titleOf(page));
    }

    private CharSequence titleOf(@Nullable final androidx.fragment.app.Fragment page) {
        if (page != null && page.getArguments() != null && page.getArguments().getCharSequence(EXTRA_TITLE) != null) {
            return page.getArguments().getCharSequence(EXTRA_TITLE);
        }
        if (page instanceof PreferenceFragmentCompat) {
            final androidx.preference.PreferenceScreen screen = ((PreferenceFragmentCompat) page).getPreferenceScreen();
            if (screen != null && screen.getTitle() != null && screen.getTitle().length() > 0) {
                return screen.getTitle();
            }
        }
        return getString(R.string.pref_title);
    }

    // title of a page within a page, from the row that opened it
    static final String EXTRA_TITLE = "com.brouken.player.SETTINGS_TITLE";

    public static class SettingsHeaders extends androidx.preference.PreferenceHeaderFragmentCompat {

        @NonNull
        @Override
        public PreferenceFragmentCompat onCreatePreferenceHeader() {
            return new HeadersList();
        }

        @Override
        public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            getSlidingPaneLayout().addPanelSlideListener(
                    new androidx.slidingpanelayout.widget.SlidingPaneLayout.SimplePanelSlideListener() {
                        @Override
                        public void onPanelOpened(@NonNull final View panel) {
                            ((SettingsActivity) requireActivity()).updateTitle();
                        }

                        @Override
                        public void onPanelClosed(@NonNull final View panel) {
                            ((SettingsActivity) requireActivity()).updateTitle();
                        }
                    });
            // one pane or two is only known once laid out
            view.post(() -> {
                if (isAdded()) {
                    ((SettingsActivity) requireActivity()).updateTitle();
                }
            });
        }

        // none with one pane; a page within a page keeps its category marked
        void markOpen(@Nullable final androidx.fragment.app.Fragment detail) {
            final androidx.fragment.app.Fragment list = getChildFragmentManager()
                    .findFragmentById(androidx.preference.R.id.preferences_header);
            if (!(list instanceof PreferenceFragmentCompat)
                    || ((PreferenceFragmentCompat) list).getPreferenceScreen() == null) {
                return;
            }
            final String root = detail == null || detail.getArguments() == null ? null
                    : detail.getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT);
            if (detail != null && root == null) {
                return;
            }
            final androidx.preference.PreferenceScreen screen = ((PreferenceFragmentCompat) list).getPreferenceScreen();
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                final androidx.preference.Preference header = screen.getPreference(i);
                if (header instanceof SettingsHeaderPreference) {
                    ((SettingsHeaderPreference) header).setOpen(root != null
                            && root.equals(header.getExtras().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT)));
                }
            }
        }
    }

    public static class HeadersList extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(@Nullable final Bundle savedInstanceState, @Nullable final String rootKey) {
            setPreferencesFromResource(R.xml.settings_headers, rootKey);
        }

        @Override
        public void onResume() {
            super.onResume();
            summarize();
        }

        private void summarize() {
            final android.content.SharedPreferences prefs =
                    PreferenceManager.getDefaultSharedPreferences(requireContext());
            set("header_appearance", getString(R.string.set_hub_appearance,
                    entry(R.array.accent_entries, R.array.accent_values, prefs.getString("accentColor", "orange")),
                    entry(R.array.player_style_entries, R.array.player_style_values, prefs.getString("playerStyle", "classic"))));
            set("header_home", getString(R.string.set_hub_home,
                    entry(R.array.home_layout_entries, R.array.home_layout_values, prefs.getString("homeLayout", "list")),
                    prefs.getInt("recentCount", 10)));
            final String seek = SeekPreview.mode(requireContext());
            set("header_playback", getString(R.string.set_hub_playback,
                    entry(R.array.playback_engine_entries, R.array.playback_engine_values, prefs.getString("playbackEngine", "auto")),
                    getString(SeekPreview.OFF.equals(seek) ? R.string.set_previews_off
                            : SeekPreview.LOCAL.equals(seek) ? R.string.set_previews_local : R.string.set_previews_all)));
            final String audio = prefs.getString("languageAudio", Prefs.TRACK_DEVICE);
            set("header_audio", getString(R.string.set_hub_audio,
                    getString(Prefs.TRACK_DEFAULT.equals(audio) ? R.string.pref_language_track_default
                            : Prefs.TRACK_DEVICE.equals(audio) ? R.string.pref_language_track_device
                            : R.string.pref_language_audio),
                    getString(prefs.getBoolean("volumeBoost", false) ? R.string.set_on : R.string.set_off)));
            set("header_gestures", getString(R.string.set_hub_gestures,
                    prefs.getInt("doubleTapSeekSeconds", 10), prefs.getInt("controlsTimeoutSeconds", 6)));
            set("header_subtitles", getString(R.string.set_hub_subtitles,
                    com.brouken.player.online.SubtitleLanguages.nameInSentence(prefs.getString("subtitleLanguage", "en"))));
            set("header_online", com.brouken.player.online.ApiKeys.hasTmdb(requireContext())
                    ? getString(R.string.set_hub_online_set,
                            entry(R.array.identify_mode_entries, R.array.identify_mode_values, prefs.getString("identifyMode", "auto")))
                    : getString(R.string.set_hub_online_unset));
            set("header_storage", getString(R.string.set_hub_storage));
            set("header_advanced", getString(R.string.set_hub_advanced));
            set("header_about", getString(R.string.set_hub_about, BuildConfig.VERSION_NAME));
        }

        private void set(final String key, final CharSequence summary) {
            final Preference preference = findPreference(key);
            if (preference != null) {
                preference.setSummary(summary);
            }
        }

        private String entry(final int entriesId, final int valuesId, @Nullable final String value) {
            final String[] entries = getResources().getStringArray(entriesId);
            final String[] values = getResources().getStringArray(valuesId);
            for (int i = 0; i < values.length && i < entries.length; i++) {
                if (values[i].equals(value)) {
                    return entries[i];
                }
            }
            return value == null ? "" : value;
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
        private ActivityResultLauncher<String> backupExportPicker;
        private ActivityResultLauncher<String[]> backupImportPicker;
        private java.util.Set<Backup.Part> pendingExportParts;
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
            backupExportPicker = registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("application/json"),
                    this::writeBackupTo
            );
            backupImportPicker = registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    this::readBackupFrom
            );
        }

        @SuppressLint("InflateParams")
        private void askWhatToExport() {
            final View body = getLayoutInflater().inflate(R.layout.dialog_backup, null);
            final android.widget.CheckBox settings = body.findViewById(R.id.backup_settings);
            final android.widget.CheckBox keys = body.findViewById(R.id.backup_keys);
            final android.widget.CheckBox history = body.findViewById(R.id.backup_history);
            final android.widget.CheckBox perFile = body.findViewById(R.id.backup_per_file);

            new android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pref_backup_choose)
                    .setView(body)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.pref_backup_export_go, (dialog, which) -> {
                        pendingExportParts = java.util.EnumSet.noneOf(Backup.Part.class);
                        if (settings.isChecked()) {
                            pendingExportParts.add(Backup.Part.SETTINGS);
                        }
                        if (keys.isChecked()) {
                            pendingExportParts.add(Backup.Part.KEYS);
                        }
                        if (history.isChecked()) {
                            pendingExportParts.add(Backup.Part.HISTORY);
                        }
                        if (perFile.isChecked()) {
                            pendingExportParts.add(Backup.Part.PER_FILE);
                        }
                        if (pendingExportParts.isEmpty()) {
                            Toast.makeText(requireContext(), R.string.pref_backup_nothing,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            backupExportPicker.launch(Backup.suggestedFileName());
                        } catch (android.content.ActivityNotFoundException e) {
                            Toast.makeText(requireContext(), R.string.pref_backup_failed,
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .show();
        }

        private void writeBackupTo(@Nullable final Uri uri) {
            if (uri == null || pendingExportParts == null) {
                return;
            }
            final java.util.Set<Backup.Part> parts = pendingExportParts;
            pendingExportParts = null;
            try {
                final String json = Backup.export(requireContext(), parts);
                try (java.io.OutputStream out =
                             requireContext().getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) {
                        throw new java.io.IOException("nothing to write to");
                    }
                    out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                final int count = new org.json.JSONObject(json).getJSONObject("values").length();
                Toast.makeText(requireContext(),
                        getString(R.string.pref_backup_exported, count),
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Utils.log("The backup could not be written: " + e);
                Toast.makeText(requireContext(), R.string.pref_backup_failed,
                        Toast.LENGTH_LONG).show();
            }
        }

        private void readBackupFrom(@Nullable final Uri uri) {
            if (uri == null) {
                return;
            }
            final String json;
            try (java.io.InputStream in =
                         requireContext().getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new java.io.IOException("nothing to read from");
                }
                final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                final byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                    if (buffer.size() > 4 * 1024 * 1024) {
                        // a backup is a few kilobytes
                        throw new java.io.IOException("far too big to be a backup");
                    }
                }
                json = buffer.toString("UTF-8");
            } catch (Exception e) {
                Utils.log("The backup could not be read: " + e);
                Toast.makeText(requireContext(), R.string.pref_backup_failed,
                        Toast.LENGTH_LONG).show();
                return;
            }

            final Backup.Result result = Backup.restore(requireContext(), json);
            if (!result.recognised) {
                Toast.makeText(requireContext(), R.string.pref_backup_not_ours,
                        Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(requireContext(),
                    getString(R.string.pref_backup_imported, result.applied),
                    Toast.LENGTH_LONG).show();
            // a restored light or dark recreates the open screens itself
            AppTheme.apply(requireContext());
            requireActivity().recreate();
        }

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

        private com.brouken.player.online.SetupServer setupServer;

        // a ListPreference opens its own dialog before any click listener runs
        @Override
        public void onDisplayPreferenceDialog(@NonNull Preference preference) {
            if (preference instanceof PictureListPreference) {
                showPictureChoice((PictureListPreference) preference);
                return;
            }
            if (preference instanceof ListPreference && "accentColor".equals(preference.getKey())) {
                showAccentSwatches((ListPreference) preference);
                return;
            }
            if (preference instanceof ListPreference && "subtitleColor".equals(preference.getKey())) {
                showSubtitleColorSwatches((ListPreference) preference);
                return;
            }
            super.onDisplayPreferenceDialog(preference);
        }

        private void showPictureChoice(final PictureListPreference preference) {
            final android.content.Context context = requireContext();
            final float density = getResources().getDisplayMetrics().density;
            final int accent = Accent.color(context);
            final CharSequence[] entries = preference.getEntries();
            final CharSequence[] values = preference.getEntryValues();
            final int picked = preference.findIndexOfValue(preference.getValue());

            final LinearLayout cards = new LinearLayout(context);
            cards.setOrientation(LinearLayout.VERTICAL);
            final int pad = Math.round(16 * density);
            cards.setPadding(pad, Math.round(8 * density), pad, Math.round(4 * density));
            final androidx.appcompat.app.AlertDialog[] dialog = new androidx.appcompat.app.AlertDialog[1];
            View pickedCard = null;
            for (int i = 0; i < entries.length; i++) {
                final View card = getLayoutInflater().inflate(R.layout.pref_picture_card, cards, false);
                ((android.widget.ImageView) card.findViewById(R.id.picture_image))
                        .setImageResource(preference.pictureAt(i));
                ((android.widget.TextView) card.findViewById(R.id.picture_title)).setText(entries[i]);
                final android.widget.TextView description = card.findViewById(R.id.picture_description);
                final CharSequence line = preference.descriptionAt(i);
                description.setText(line);
                description.setVisibility(line == null ? View.GONE : View.VISIBLE);
                card.setBackground(pictureCardBackground(i == picked, accent, density));
                final String value = values[i].toString();
                card.setOnClickListener(clicked -> {
                    if (preference.callChangeListener(value)) {
                        preference.setValue(value);
                    }
                    if (dialog[0] != null) {
                        dialog[0].dismiss();
                    }
                });
                if (i == picked) {
                    pickedCard = card;
                }
                cards.addView(card);
            }
            final android.widget.ScrollView scroll = new android.widget.ScrollView(context);
            scroll.addView(cards);
            dialog[0] = new androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(preference.getTitle())
                    .setView(scroll)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            final View focus = pickedCard;
            dialog[0].setOnShowListener(shown -> {
                if (focus != null) {
                    focus.requestFocus();
                }
            });
            dialog[0].show();
        }

        static android.graphics.drawable.Drawable pictureCardBackground(final boolean picked,
                                                                               final int accent, final float density) {
            final android.graphics.drawable.StateListDrawable states =
                    new android.graphics.drawable.StateListDrawable();
            states.addState(new int[]{android.R.attr.state_focused},
                    pictureCard(picked, accent, 3f, accent, density));
            states.addState(new int[]{},
                    pictureCard(picked, picked ? accent : 0x40808080, picked ? 2f : 1.5f, accent, density));
            return new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33808080), states, null);
        }

        private static android.graphics.drawable.GradientDrawable pictureCard(final boolean picked,
                                                                              final int stroke, final float strokeDp,
                                                                              final int accent, final float density) {
            final android.graphics.drawable.GradientDrawable shape =
                    new android.graphics.drawable.GradientDrawable();
            shape.setCornerRadius(16 * density);
            shape.setColor(picked ? androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x1F) : 0);
            shape.setStroke(Math.round(strokeDp * density), stroke);
            return shape;
        }

        private void showAccentSwatches(final ListPreference preference) {
            final String[] names = getResources().getStringArray(R.array.accent_entries);
            final String[] values = getResources().getStringArray(R.array.accent_values);
            final int[] colors = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                colors[i] = Accent.colorOf(requireContext(), values[i]);
            }
            showSwatches(preference, R.string.pref_accent, names, values, colors, false,
                    () -> requireActivity().recreate());
        }

        private void showSubtitleColorSwatches(final ListPreference preference) {
            final String[] names = getResources().getStringArray(R.array.subtitle_color_entries);
            final String[] values = getResources().getStringArray(R.array.subtitle_color_values);
            final int[] colors = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                colors[i] = SubtitleColors.argb(values[i]);
            }
            // white and cream would vanish on a light dialog without an edge
            showSwatches(preference, R.string.subtitle_color_title, names, values, colors, true, null);
        }

        private void showSwatches(final ListPreference preference, final int title,
                                  final String[] names, final String[] values, final int[] colors,
                                  final boolean edged, @Nullable final Runnable afterPick) {
            {
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
                                final android.graphics.drawable.Drawable disc;
                                if (edged) {
                                    final android.graphics.drawable.GradientDrawable shape =
                                            new android.graphics.drawable.GradientDrawable();
                                    shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                                    shape.setSize(Utils.dpToPx(24), Utils.dpToPx(24));
                                    shape.setColor(colors[position]);
                                    shape.setStroke(Math.max(1, Utils.dpToPx(1)), 0x66808080);
                                    disc = shape;
                                } else {
                                    disc = androidx.core.content.ContextCompat.getDrawable(
                                            requireContext(), R.drawable.accent_swatch);
                                    if (disc != null) {
                                        disc.mutate().setColorFilter(
                                                new android.graphics.PorterDuffColorFilter(
                                                        colors[position],
                                                        android.graphics.PorterDuff.Mode.SRC_IN));
                                    }
                                }
                                if (disc != null) {
                                    text.setCompoundDrawablesWithIntrinsicBounds(
                                            disc, null, null, null);
                                    text.setCompoundDrawablePadding(Utils.dpToPx(16));
                                }
                                return view;
                            }
                        };

                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle(title)
                        .setSingleChoiceItems(adapter, current, (dialog, which) -> {
                            preference.setValue(values[which]);
                            dialog.dismiss();
                            if (afterPick != null) {
                                afterPick.run();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        }

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
                                .append(result.message(context)).append('\n');
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
                                .append(context.getString(R.string.pref_addon_slot, slot)).append(": ")
                                .append(probe.accepted() ? probe.name(context) : probe.reason(context))
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
                    // no browser, as on some TVs
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
                                    // reload whatever the page wrote
                                    setPreferenceScreen(null);
                                    onCreatePreferences(null, null);
                                })
                                .create();
                dialog.show();
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

        private static final int TICK_GREEN = 0xFF4CAF50;

        private void markWhatIsSet() {
            for (final String key : new String[]{
                    com.brouken.player.online.ApiKeys.PREF_TMDB,
                    com.brouken.player.online.ApiKeys.PREF_OPENSUBTITLES,
                    com.brouken.player.online.ApiKeys.PREF_OPENSUBTITLES_USER,
                    com.brouken.player.online.ApiKeys.PREF_OPENSUBTITLES_PASSWORD,
                    com.brouken.player.online.ApiKeys.PREF_SUBDL,
                    com.brouken.player.online.ApiKeys.PREF_WYZIE,
                    com.brouken.player.online.ApiKeys.PREF_MDBLIST}) {
                final Preference preference = findPreference(key);
                if (preference == null) {
                    continue;
                }
                final String value = PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .getString(key, null);
                mark(preference, value != null && !value.trim().isEmpty(),
                        getString(R.string.pref_key_set), getString(R.string.pref_key_not_set));
            }

            final Preference addons = findPreference("subtitleAddons");
            if (addons != null) {
                final int count = com.brouken.player.online.SubtitleAddons
                        .saved(requireContext()).size();
                mark(addons, count > 0,
                        getResources().getQuantityString(
                                R.plurals.pref_addons_summary_count, count, count),
                        getString(R.string.pref_addons_summary));
            }
        }

        private void mark(final Preference preference, final boolean set,
                          final String whenSet, final String whenNot) {
            if (!set) {
                preference.setSummary(whenNot);
                return;
            }
            final String text = "✓  " + whenSet;
            final android.text.SpannableString ticked = new android.text.SpannableString(text);
            ticked.setSpan(new android.text.style.ForegroundColorSpan(TICK_GREEN), 0, 1,
                    android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            preference.setSummary(ticked);
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
                        // the write happens after this returns, so refresh a turn later
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(this::markWhatIsSet);
                        return true;
                    }
                    checkThenSave((EditTextPreference) changed, key, value);
                    // checkThenSave writes it once the service accepts the key
                    return false;
                });
            }
        }

        private void showRatingsOrder() {
            final Preference row = findPreference("ratingsOrder");
            if (row == null) {
                return;
            }
            final java.util.List<String> names = new java.util.ArrayList<>();
            for (final String site : com.brouken.player.online.Ratings.order(requireContext())) {
                if (com.brouken.player.online.Ratings.isOn(requireContext(), site)) {
                    names.add(com.brouken.player.online.Ratings.shortLabel(site));
                }
            }
            row.setSummary(names.isEmpty() ? getString(R.string.pref_ratings_order_none)
                    : android.text.TextUtils.join(", ", names));
        }

        private void checkMdbListKey(final String value) {
            if (value.isEmpty()) {
                return;
            }
            final android.content.Context context = requireContext().getApplicationContext();
            com.brouken.player.online.Ratings.checkKey(context, value, result -> {
                final int message = result == com.brouken.player.online.Ratings.KeyCheck.WORKS
                        ? R.string.pref_mdblist_key_works
                        : result == com.brouken.player.online.Ratings.KeyCheck.WRONG
                        ? R.string.pref_mdblist_key_wrong
                        : R.string.pref_mdblist_key_unreachable;
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show();
            });
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
                        markWhatIsSet();
                    }
                    android.widget.Toast.makeText(context, result.message(context),
                            android.widget.Toast.LENGTH_LONG).show();
                });
            }).start();
        }

        // counted off the main thread: the folder can hold thousands of files
        private void showImageCacheSize(final Preference preference) {
            final android.content.Context context = requireContext().getApplicationContext();
            new Thread(() -> {
                final long bytes = ImageCache.size();
                final String summary = bytes > 0
                        ? context.getString(R.string.pref_image_cache_size,
                                android.text.format.Formatter.formatShortFileSize(context, bytes))
                        : context.getString(R.string.pref_image_cache_empty);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (isAdded()) {
                            preference.setSummary(summary);
                        }
                    });
                }
            }, "jpp-image-cache").start();
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            // before the list reads it, so a stored "off" shows as Off
            SeekPreview.migrate(PreferenceManager.getDefaultSharedPreferences(requireContext()));
            PlayerCorner.migrate(PreferenceManager.getDefaultSharedPreferences(requireContext()));
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            final Preference battery = findPreference(PlayerCorner.BATTERY);
            if (battery != null && !PlayerCorner.hasBattery(requireContext())) {
                battery.setVisible(false);
            }
            final Preference theme = findPreference(AppTheme.PREF_KEY);
            if (theme != null) {
                // a television is always dark
                theme.setVisible(!Utils.isTvBox(requireContext()));
                theme.setOnPreferenceChangeListener((preference, newValue) -> {
                    preference.getSharedPreferences().edit()
                            .putString(AppTheme.PREF_KEY, String.valueOf(newValue)).apply();
                    // AppCompat recreates the open screens, this one included
                    AppTheme.apply(requireContext());
                    return true;
                });
            }
            useFullWidth(getPreferenceScreen());
            // the engine row may be on another page; its value decides what greys out here
            applyEngineDependentState(PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString("playbackEngine", "auto"));

            final Preference controlsOrder = findPreference("controlsOrder");
            if (controlsOrder != null) {
                controlsOrder.setOnPreferenceClickListener(clicked -> {
                    startActivity(new Intent(requireContext(), ControlOrderActivity.class));
                    return true;
                });
            }

            final Preference ratingsOrder = findPreference("ratingsOrder");
            if (ratingsOrder != null) {
                ratingsOrder.setOnPreferenceClickListener(clicked -> {
                    startActivity(new Intent(requireContext(), RatingsOrderActivity.class));
                    return true;
                });
            }

            final Preference licenses = findPreference("licenses");
            if (licenses != null) {
                licenses.setOnPreferenceClickListener(clicked -> {
                    startActivity(new Intent(requireContext(), LicensesActivity.class));
                    return true;
                });
            }

            final Preference intro = findPreference("showIntroAgain");
            if (intro != null) {
                intro.setOnPreferenceClickListener(clicked -> {
                    // shown next time on the folder list, not over settings
                    Intro.markSeen(requireContext(), false);
                    Toast.makeText(requireContext(),
                            R.string.pref_intro_again_done, Toast.LENGTH_LONG).show();
                    return true;
                });
            }

            final Preference images = findPreference("clearImageCache");
            if (images != null) {
                showImageCacheSize(images);
                images.setOnPreferenceClickListener(clicked -> {
                    final long bytes = ImageCache.size();
                    if (bytes > 0) {
                        new android.app.AlertDialog.Builder(requireContext())
                                .setTitle(R.string.image_cache_clear_title)
                                .setMessage(getString(R.string.image_cache_clear_message,
                                        android.text.format.Formatter.formatShortFileSize(requireContext(), bytes)))
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.image_cache_clear, (dialog, which) -> {
                                    ImageCache.clear();
                                    showImageCacheSize(images);
                                })
                                .show();
                    }
                    return true;
                });
            }

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

            // unchecked: a login try uses up a sign-in, and some networks block MDBList
            for (final String key : new String[]{
                    com.brouken.player.online.ApiKeys.PREF_OPENSUBTITLES_USER,
                    com.brouken.player.online.ApiKeys.PREF_OPENSUBTITLES_PASSWORD,
                    com.brouken.player.online.ApiKeys.PREF_MDBLIST}) {
                final Preference preference = findPreference(key);
                if (preference != null) {
                    preference.setOnPreferenceChangeListener((changed, newValue) -> {
                        if (com.brouken.player.online.ApiKeys.PREF_MDBLIST.equals(key)) {
                            com.brouken.player.online.Ratings.retrySoon();
                            checkMdbListKey(newValue == null ? "" : newValue.toString().trim());
                        }
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(this::markWhatIsSet);
                        return true;
                    });
                }
            }

            markWhatIsSet();

            subtitleFolderChoose = findPreference("subtitleFolderChoose");
            if (subtitleFolderChoose != null) {
                updateSubtitleFolderSummary();
                subtitleFolderChoose.setOnPreferenceClickListener(preference -> {
                    if (SubtitleStorage.getFolder(requireContext()) != null) {
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

            // these only take effect when the player is rebuilt
            for (final String key : new String[]{"playbackEngine", "decoderPriority"}) {
                final ListPreference list = findPreference(key);
                if (list == null || !list.isEnabled()) {
                    continue;
                }
                list.setSummaryProvider(preference -> {
                    final CharSequence entry = ((ListPreference) preference).getEntry();
                    return (entry == null ? "" : entry + "\n") + getString(R.string.pref_reopens_file);
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
                    openSubPage(new FoldersFragment(), preference.getTitle());
                    return true;
                });
            }

            final Preference preferenceExport = findPreference("backupExport");
            if (preferenceExport != null) {
                preferenceExport.setOnPreferenceClickListener(preference -> {
                    askWhatToExport();
                    return true;
                });
            }

            final Preference preferenceImport = findPreference("backupImport");
            if (preferenceImport != null) {
                preferenceImport.setOnPreferenceClickListener(preference -> {
                    // any type: shared files often arrive mistyped; the content is checked
                    try {
                        backupImportPicker.launch(new String[]{"*/*"});
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(requireContext(), R.string.pref_backup_failed,
                                Toast.LENGTH_LONG).show();
                    }
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
                    openSubPage(new HistoryFragment(), preference.getTitle());
                    return true;
                });
            }

            ListPreference preferenceAccent = findPreference(Accent.PREF_KEY);
            if (preferenceAccent != null) {
                preferenceAccent.setOnPreferenceChangeListener((preference, newValue) -> {
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
                    openSubPage(new SubtitleAddonsFragment(), preference.getTitle());
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

            final ListPreference appLanguage = findPreference(AppLanguage.PREF_KEY);
            if (appLanguage != null) {
                final String chosen = AppLanguage.chosen(requireContext());
                final String listed = AppLanguage.listed(chosen, appLanguage.getEntryValues());
                if (listed != null) {
                    appLanguage.setValue(listed);
                }
                // a language Android holds that the list has no form of: its own name
                appLanguage.setSummaryProvider(preference -> {
                    if (listed != null) {
                        return appLanguage.getEntry();
                    }
                    final Locale locale = Locale.forLanguageTag(chosen);
                    final String name = locale.getDisplayName(locale);
                    return name.isEmpty() ? chosen
                            : name.substring(0, 1).toUpperCase(locale) + name.substring(1);
                });
                appLanguage.setOnPreferenceChangeListener((preference, value) -> {
                    AppLanguage.choose(requireActivity(), (String) value);
                    return true;
                });
            }

            final ListPreference subtitleLanguage = findPreference("subtitleLanguage");
            if (subtitleLanguage != null) {
                final java.util.List<String> codes = com.brouken.player.online.SubtitleLanguages.codes();
                final java.util.Map<String, String> names = new java.util.HashMap<>();
                for (final String code : codes) {
                    names.put(code, com.brouken.player.online.SubtitleLanguages.name(code));
                }
                final java.text.Collator collator = java.text.Collator.getInstance();
                java.util.Collections.sort(codes, (a, b) -> collator.compare(names.get(a), names.get(b)));
                final String[] entries = new String[codes.size()];
                for (int i = 0; i < entries.length; i++) {
                    entries[i] = names.get(codes.get(i));
                }
                subtitleLanguage.setEntries(entries);
                subtitleLanguage.setEntryValues(codes.toArray(new String[0]));
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

        private void openSubPage(final androidx.fragment.app.Fragment page, final CharSequence title) {
            final Bundle args = new Bundle();
            args.putCharSequence(EXTRA_TITLE, title);
            page.setArguments(args);
            final boolean inPane = getParentFragment() instanceof androidx.preference.PreferenceHeaderFragmentCompat;
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(inPane ? androidx.preference.R.id.preferences_detail : R.id.settings, page)
                    .addToBackStack(null)
                    .commit();
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            final String highlight = getArguments() == null ? null : getArguments().getString(EXTRA_HIGHLIGHT);
            if (highlight != null && savedInstanceState == null) {
                pickOut(highlight);
            }
        }

        private void pickOut(final String key) {
            scrollToPreference(key);
            getListView().postDelayed(() -> {
                if (!isAdded() || !(getListView().getAdapter() instanceof androidx.preference.PreferenceGroup.PreferencePositionCallback)) {
                    return;
                }
                final int position = ((androidx.preference.PreferenceGroup.PreferencePositionCallback)
                        getListView().getAdapter()).getPreferenceAdapterPosition(key);
                final RecyclerView.ViewHolder holder = getListView().findViewHolderForAdapterPosition(position);
                if (holder == null) {
                    return;
                }
                final View row = holder.itemView;
                final android.graphics.drawable.Drawable before = row.getBackground();
                row.setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                        Accent.color(requireContext()), 0x40));
                row.requestFocus();
                row.postDelayed(() -> row.setBackground(before), 1500);
            }, 300);
        }

        @Override
        public void onResume() {
            super.onResume();
            markWhatIsSet();
            showRatingsOrder();
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
                // some television builds ship no document picker at all
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
