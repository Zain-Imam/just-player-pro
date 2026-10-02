package com.brouken.player;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.brouken.player.home.Library;
import com.brouken.player.home.Pinned;
import com.brouken.player.home.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

// Home screen: folders, the files in one, and search results share one list.
// A separate activity, so Back follows the task stack.
public class HomeActivity extends AppCompatActivity {

    public static final String START_ON_HOME = "home";

    // started with ACTION_PICK to hand a file back to the player's Open button
    private boolean picking;

    private static final int REQUEST_PERMISSION = 1;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_FOLDER = 1;
    private static final int TYPE_VIDEO = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Background.single("library");
    private final com.brouken.player.home.Frames frames = new com.brouken.player.home.Frames();

    private SharedPreferences preferences;

    private ImageButton upButton;
    private TextView titleView;
    private EditText searchField;
    private ImageButton searchButton;
    private ImageButton sortButton;
    private RecyclerView list;
    private TextView emptyView;
    private Button grantButton;
    private ProgressBar loadingView;

    /** Everything the media store knows about, read once per visit. */
    private final List<Library.Video> everything = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private Adapter adapter;

    /** The folder being looked at, or null at the top. */
    @Nullable
    private String folderId;
    @Nullable
    private String folderName;
    /** What is being searched for, or null when not searching. */
    @Nullable
    private String query;

    private boolean scanned;
    private boolean askedForPermission;
    /** True while the storage permission dialog is up. */
    private boolean permissionPending;
    private boolean offeredLastVideo;
    /** The folder whose star should take focus once the list is rebuilt. */
    @Nullable
    private String focusPinFor;
    /** The accent this screen was themed with. */
    private String appliedAccent;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        // always dark on TV; before super, where the theme is resolved
        if (Utils.isTvBox(this)) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        }

        super.onCreate(savedInstanceState);

        // after super: AppCompat re-applies the manifest theme in its onCreate
        Accent.apply(this);
        appliedAccent = Accent.stored(this);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        picking = Intent.ACTION_PICK.equals(getIntent().getAction());

        // before setContentView so the folder list never flashes on the way past
        if (!picking && !wantsHome() && savedInstanceState == null
                && getIntent().getData() == null) {
            startPlayer(null, null);
            finish();
            return;
        }

        setContentView(R.layout.activity_home);

        upButton = findViewById(R.id.home_up);
        titleView = findViewById(R.id.home_title);
        searchField = findViewById(R.id.home_search);
        searchButton = findViewById(R.id.home_search_button);
        sortButton = findViewById(R.id.home_sort_button);
        list = findViewById(R.id.home_list);
        emptyView = findViewById(R.id.home_empty);
        grantButton = findViewById(R.id.home_grant);
        loadingView = findViewById(R.id.home_loading);

        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);

        upButton.setOnClickListener(view -> goUp());
        searchButton.setOnClickListener(view -> toggleSearch());
        sortButton.setOnClickListener(view -> showSortMenu());
        grantButton.setOnClickListener(view -> requestStorage());

        final View network = findViewById(R.id.home_network_button);
        final View settings = findViewById(R.id.home_settings_button);
        network.setOnClickListener(view -> OpenMenu.showUrl(this, this::startPlayer));
        settings.setOnClickListener(view ->
                startActivity(new Intent(this, SettingsActivity.class)));
        if (picking) {
            network.setVisibility(View.GONE);
            settings.setVisibility(View.GONE);
        }

        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(final Editable editable) {
                query = editable.toString();
                render();
            }
        });
        // the remote's centre key means done typing: hide the keyboard, keep results
        searchField.setOnEditorActionListener((view, actionId, event) -> {
            hideKeyboard();
            list.requestFocus();
            return true;
        });

        // Keeps the bar clear of the status bar and of a television's overscan.
        if (Build.VERSION.SDK_INT >= 29) {
            findViewById(R.id.home_root).setOnApplyWindowInsetsListener((view, insets) -> {
                view.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
                return insets;
            });
        }

        registerBackHandling();
        setTitleText();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (isFinishing()) {
            return;
        }
        // rescan on every visit: a file may have been deleted while playing
        if (hasStoragePermission()) {
            scan();
        } else if (!askedForPermission) {
            askedForPermission = true;
            requestStorage();
        } else {
            showPermissionWanted();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the accent is a theme overlay set at creation; a change needs recreate()
        if (appliedAccent != null && !appliedAccent.equals(Accent.stored(this))) {
            recreate();
            return;
        }
        // intro before the resume offer: never two dialogs at once
        if (!picking && !permissionPending && hasStoragePermission() && Intro.pending(this)) {
            Intro.show(this);
            return;
        }
        maybeOfferLastVideo();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        frames.close();
        super.onDestroy();
    }

    // -------------------------------------------------------------- opening

    private boolean wantsHome() {
        return START_ON_HOME.equals(preferences.getString("startOn", START_ON_HOME));
    }

    private void maybeOfferLastVideo() {
        // not over the permission dialog; offered again once it is answered
        if (offeredLastVideo || picking || permissionPending || !wantsHome()
                || !preferences.getBoolean("askResume", true)) {
            return;
        }
        offeredLastVideo = true;

        final String stored = preferences.getString("mediaUri", null);
        if (stored == null || stored.isEmpty()) {
            return;
        }
        final Uri uri = Uri.parse(stored);
        final String type = preferences.getString("mediaType", null);

        String name = History.nameFor(preferences, uri);
        if (name == null || name.isEmpty()) {
            name = Utils.getFileName(this, uri, true);
        }
        // a deleted media store entry leaves only its numeric id as the name
        if (name == null || name.isEmpty() || name.matches("\\d+")) {
            return;
        }

        Utils.showFocused(new AlertDialog.Builder(this)
                .setTitle(R.string.resume_title)
                .setMessage(name)
                .setNegativeButton(R.string.resume_decline, null)
                .setPositiveButton(R.string.resume_accept, (dialog, which) -> startPlayer(uri, type))
                .create(), AlertDialog.BUTTON_POSITIVE);
    }

    // a folder id: a full file list could exceed the intent size limit
    static final String EXTRA_FOLDER = "com.brouken.player.FOLDER";

    // keeps the player from sending a launch made here straight back to home
    static final String EXTRA_FROM_HOME = "com.brouken.player.FROM_HOME";

    // a null uri opens the player on the last video
    void startPlayer(@Nullable final Uri uri, @Nullable final String type) {
        if (picking) {
            if (uri == null) {
                return;
            }
            final Intent result = new Intent();
            if (type == null || type.isEmpty()) {
                result.setData(uri);
            } else {
                result.setDataAndType(uri, type);
            }
            setResult(RESULT_OK, result);
            finish();
            return;
        }

        final Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(EXTRA_FROM_HOME, true);
        if (uri != null) {
            intent.setAction(Intent.ACTION_VIEW);
            if (type == null || type.isEmpty()) {
                intent.setData(uri);
            } else {
                intent.setDataAndType(uri, type);
            }
            // reuses a player already in this task, e.g. one in picture-in-picture
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            // gives the player next/previous; network rows have no folder
            final String folder = folderOf(uri);
            if (folder != null) {
                intent.putExtra(EXTRA_FOLDER, folder);
            }
        }
        startActivity(intent);
    }

    /** The folder the scan filed this address under, if it knows of it. */
    @Nullable
    private String folderOf(final Uri uri) {
        if (folderId != null) {
            return folderId;
        }
        for (final Library.Video video : everything) {
            if (video.uri.equals(uri)) {
                return video.folderId;
            }
        }
        return null;
    }

    // ---------------------------------------------------------- permission

    private static String storagePermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return Manifest.permission.READ_MEDIA_VIDEO;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(storagePermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT < 23) {
            scan();
            return;
        }
        permissionPending = true;
        requestPermissions(new String[]{storagePermission()}, REQUEST_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions,
                                           @NonNull final int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSION) {
            return;
        }
        permissionPending = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            scan();
        } else {
            showPermissionWanted();
        }
        // Held back while the system dialog was up.
        maybeOfferLastVideo();
    }

    private void showPermissionWanted() {
        scanned = true;
        rows.clear();
        adapter.notifyDataSetChanged();
        loadingView.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
        emptyView.setText(R.string.home_empty_permission);
        grantButton.setVisibility(View.VISIBLE);
        // the only focusable view left, so a remote must land on it
        grantButton.post(grantButton::requestFocus);
    }

    // ------------------------------------------------------------ scanning

    private void scan() {
        grantButton.setVisibility(View.GONE);
        if (!scanned) {
            loadingView.setVisibility(View.VISIBLE);
        }
        worker.execute(Background.safely(() -> {
            final List<Library.Video> found = Library.videos(this);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                everything.clear();
                everything.addAll(found);
                final boolean first = !scanned;
                scanned = true;
                loadingView.setVisibility(View.GONE);
                render();
                // a remote needs a focused row; never steal it from the search field
                if (first && query == null) {
                    Panels.focusFirstRow(list);
                }
            });
        }));
    }

    // ------------------------------------------------------------- drawing

    private void render() {
        rows.clear();

        // an empty search field keeps the list underneath
        final boolean searching = query != null && !query.trim().isEmpty();

        if (searching) {
            renderSearch();
        } else if (folderId != null) {
            renderFolder();
        } else {
            renderRoot();
        }

        adapter.notifyDataSetChanged();
        setTitleText();
        upButton.setVisibility(folderId != null ? View.VISIBLE : View.GONE);

        final boolean empty = rows.isEmpty();
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty && scanned ? View.VISIBLE : View.GONE);
        if (empty && scanned) {
            emptyView.setText(searching
                    ? R.string.home_empty_results
                    : (folderId != null ? R.string.home_empty_folder : R.string.home_empty_none));
        }
    }

    private void renderRoot() {
        // no history while picking: entries may be network addresses
        final List<History.Entry> recent = picking
                ? java.util.Collections.<History.Entry>emptyList()
                : History.load(preferences);
        int shown = 0;
        // off by default: the identified film may not match the file name
        final boolean posters = preferences.getBoolean("recentPosters", false);
        for (final History.Entry entry : recent) {
            if (!entry.played) {
                continue;
            }
            if (shown == 0) {
                rows.add(Row.header(getString(R.string.home_section_recent)));
            }
            rows.add(Row.recent(entry, posters));
            if (++shown == 5) {
                break;
            }
        }

        final List<Library.Folder> folders = Library.folders(everything);
        Sort.apply(folders, Sort.folders(preferences), Sort.foldersReversed(preferences));

        final Set<String> pinned = Pinned.load(preferences);
        final List<Library.Folder> top = new ArrayList<>();
        final List<Library.Folder> rest = new ArrayList<>();
        for (final Library.Folder folder : folders) {
            (pinned.contains(folder.id) ? top : rest).add(folder);
        }

        if (!top.isEmpty()) {
            rows.add(Row.header(getString(R.string.home_section_pinned)));
            for (final Library.Folder folder : top) {
                rows.add(Row.folder(folder, true));
            }
        }
        if (!rest.isEmpty()) {
            rows.add(Row.header(getString(R.string.home_section_folders)));
            for (final Library.Folder folder : rest) {
                rows.add(Row.folder(folder, false));
            }
        }
    }

    private void renderFolder() {
        final List<Library.Video> videos = Library.inFolder(everything, folderId);
        Sort.apply(videos, Sort.videos(preferences), Sort.videosReversed(preferences));
        for (final Library.Video video : videos) {
            rows.add(Row.video(video.uri, video.name, metaOf(video), null));
        }
    }

    private void renderSearch() {
        final List<Library.Video> matches = Library.matching(everything, query);
        if (matches.isEmpty()) {
            return;
        }
        Sort.apply(matches, Sort.videos(preferences), Sort.videosReversed(preferences));
        rows.add(Row.header(getString(R.string.home_section_results)));
        for (final Library.Video video : matches) {
            // with the folder, so same-named files in different folders differ
            rows.add(Row.video(video.uri, video.name,
                    video.folderName + "  ·  " + metaOf(video), null));
        }
    }

    private void setTitleText() {
        if (searchField.getVisibility() == View.VISIBLE) {
            return;
        }
        titleView.setText(folderName != null
                ? folderName
                : getString(picking ? R.string.home_choose : R.string.home_title));
    }

    private String metaOf(final Library.Video video) {
        final StringBuilder meta = new StringBuilder();
        if (video.duration > 0) {
            meta.append(Utils.formatMilis(video.duration));
        }
        if (video.size > 0) {
            if (meta.length() > 0) {
                meta.append("  ·  ");
            }
            meta.append(com.brouken.player.home.Readable.size(video.size));
        }
        return meta.toString();
    }

    private String folderMeta(final Library.Folder folder) {
        final String count = folder.count == 1
                ? getString(R.string.home_videos_one)
                : getString(R.string.home_videos_many, folder.count);
        return folder.size > 0 ? count + "  ·  " + com.brouken.player.home.Readable.size(folder.size) : count;
    }

    // ------------------------------------------------------------ actions

    private void openFolder(final Library.Folder folder) {
        folderId = folder.id;
        folderName = folder.name;
        closeSearch();
        render();
        list.scrollToPosition(0);
        Panels.focusFirstRow(list);
    }

    private void goUp() {
        if (query != null) {
            closeSearch();
            render();
            return;
        }
        if (folderId != null) {
            folderId = null;
            folderName = null;
            render();
            Panels.focusFirstRow(list);
            return;
        }
        finish();
    }

    // onBackPressed is never called on 13+ with enableOnBackInvokedCallback
    private void registerBackHandling() {
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (query != null || folderId != null) {
                            goUp();
                            return;
                        }
                        // nothing left to go up to: leave
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                });
    }

    private void togglePin(final Library.Folder folder) {
        final boolean nowPinned = Pinned.toggle(preferences, folder.id);
        Toast.makeText(this, nowPinned ? R.string.home_pinned : R.string.home_unpinned,
                Toast.LENGTH_SHORT).show();
        // rebuilding the list drops focus; put it back on this folder's star
        focusPinFor = folder.id;
        render();
        // the folder has moved; an off-screen row has no view to take focus
        for (int index = 0; index < rows.size(); index++) {
            final Row row = rows.get(index);
            if (row.folder != null && folder.id.equals(row.folder.id)) {
                bringIntoView(index);
                break;
            }
        }
    }

    // scroll to the section heading if it fits with the row, else to the row
    private void bringIntoView(final int index) {
        final androidx.recyclerview.widget.LinearLayoutManager layout =
                (androidx.recyclerview.widget.LinearLayoutManager) list.getLayoutManager();
        if (layout == null) {
            list.scrollToPosition(index);
            return;
        }
        int heading = index;
        for (int above = index; above >= 0; above--) {
            if (rows.get(above).type == TYPE_HEADER) {
                heading = above;
                break;
            }
        }
        // tallest row on screen, so the fit is never overestimated
        int tallest = 0;
        for (int child = 0; child < list.getChildCount(); child++) {
            tallest = Math.max(tallest, list.getChildAt(child).getHeight());
        }
        final int fits = tallest <= 0 ? 0 : list.getHeight() / tallest;
        layout.scrollToPositionWithOffset(index - heading < fits ? heading : index, 0);
    }

    // ------------------------------------------------------------- search

    private void toggleSearch() {
        if (searchField.getVisibility() == View.VISIBLE) {
            closeSearch();
            render();
            return;
        }
        titleView.setVisibility(View.GONE);
        searchField.setVisibility(View.VISIBLE);
        searchField.setText("");
        searchField.requestFocus();
        query = "";
        // no soft keyboard on TV: the remote drives the field
        if (!Utils.isTvBox(this)) {
            final InputMethodManager manager =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
            }
        }
        render();
    }

    // setText("") fires the watcher, which sets query to ""; so null it last
    private void closeSearch() {
        searchField.setText("");
        searchField.setVisibility(View.GONE);
        titleView.setVisibility(View.VISIBLE);
        hideKeyboard();
        query = null;
    }

    private void hideKeyboard() {
        final InputMethodManager manager =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        }
    }

    // --------------------------------------------------------------- sort

    // built by hand: setSingleChoiceItems allows only one list of choices
    private void showSortMenu() {
        // same rule as render(): an empty search field still shows the folders
        final boolean foldersShowing = folderId == null
                && (query == null || query.trim().isEmpty());

        final int pad = Utils.dpToPx(8);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Utils.dpToPx(12), pad, Utils.dpToPx(12), 0);

        final RadioGroup byGroup = new RadioGroup(this);
        final RadioGroup orderGroup = new RadioGroup(this);

        final int[] byLabels;
        final int[] firstLabels;
        final int[] reversedLabels;
        final int checkedBy;
        final boolean reversedNow;

        if (foldersShowing) {
            final Sort.Folders[] orders = Sort.Folders.values();
            byLabels = new int[orders.length];
            firstLabels = new int[orders.length];
            reversedLabels = new int[orders.length];
            for (int i = 0; i < orders.length; i++) {
                byLabels[i] = orders[i].label;
                firstLabels[i] = orders[i].first;
                reversedLabels[i] = orders[i].reversedFirst;
            }
            checkedBy = Sort.folders(preferences).ordinal();
            reversedNow = Sort.foldersReversed(preferences);
        } else {
            final Sort.Videos[] orders = Sort.Videos.values();
            byLabels = new int[orders.length];
            firstLabels = new int[orders.length];
            reversedLabels = new int[orders.length];
            for (int i = 0; i < orders.length; i++) {
                byLabels[i] = orders[i].label;
                firstLabels[i] = orders[i].first;
                reversedLabels[i] = orders[i].reversedFirst;
            }
            checkedBy = Sort.videos(preferences).ordinal();
            reversedNow = Sort.videosReversed(preferences);
        }

        content.addView(sortHeading(R.string.home_sort_title));
        for (int i = 0; i < byLabels.length; i++) {
            byGroup.addView(sortChoice(getString(byLabels[i]), i, i == checkedBy));
        }
        content.addView(byGroup);

        content.addView(sortHeading(R.string.home_sort_order));
        orderGroup.addView(sortChoice(getString(firstLabels[checkedBy]), 0, !reversedNow));
        orderGroup.addView(sortChoice(getString(reversedLabels[checkedBy]), 1, reversedNow));
        content.addView(orderGroup);

        // direction labels depend on the column, so rename them when it changes
        byGroup.setOnCheckedChangeListener((group, id) -> {
            final int which = id;
            if (which < 0 || which >= firstLabels.length) {
                return;
            }
            ((RadioButton) orderGroup.getChildAt(0)).setText(getString(firstLabels[which]));
            ((RadioButton) orderGroup.getChildAt(1)).setText(getString(reversedLabels[which]));
        });

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        new AlertDialog.Builder(this)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, button) -> {
                    final int by = Math.max(0, byGroup.getCheckedRadioButtonId());
                    final boolean reversed = orderGroup.getCheckedRadioButtonId() == 1;
                    if (foldersShowing) {
                        Sort.setFolders(preferences, Sort.Folders.values()[by]);
                        Sort.setFoldersReversed(preferences, reversed);
                    } else {
                        Sort.setVideos(preferences, Sort.Videos.values()[by]);
                        Sort.setVideosReversed(preferences, reversed);
                    }
                    render();
                })
                .show();
    }

    private TextView sortHeading(final int text) {
        final TextView heading = new TextView(this);
        heading.setText(text);
        heading.setAllCaps(true);
        heading.setTextSize(13);
        heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
        heading.setTextColor(Accent.color(this));
        heading.setPadding(Utils.dpToPx(12), Utils.dpToPx(12), 0, Utils.dpToPx(4));
        return heading;
    }

    private RadioButton sortChoice(final String text, final int id, final boolean checked) {
        final RadioButton button = new RadioButton(this);
        button.setId(id);
        button.setText(text);
        button.setChecked(checked);
        button.setPadding(Utils.dpToPx(12), Utils.dpToPx(10), Utils.dpToPx(12), Utils.dpToPx(10));
        return button;
    }

    // --------------------------------------------------------------- rows

    private static final class Row {
        final int type;
        final String text;
        @Nullable
        final String meta;
        @Nullable
        final Library.Folder folder;
        final boolean pinned;
        @Nullable
        final Uri uri;
        @Nullable
        final String mediaType;
        /** A row of the Recent section, which is laid out on its own. */
        final boolean recent;
        /** Poster URL for a Recent row, when posters are on. */
        @Nullable
        final String poster;
        /** Whether Recent shows posters at all, so its rows all share a shape. */
        final boolean postersShown;

        private Row(final int type, final String text, @Nullable final String meta,
                    @Nullable final Library.Folder folder, final boolean pinned,
                    @Nullable final Uri uri, @Nullable final String mediaType) {
            this(type, text, meta, folder, pinned, uri, mediaType, false, null, false);
        }

        private Row(final int type, final String text, @Nullable final String meta,
                    @Nullable final Library.Folder folder, final boolean pinned,
                    @Nullable final Uri uri, @Nullable final String mediaType,
                    final boolean recent, @Nullable final String poster,
                    final boolean postersShown) {
            this.type = type;
            this.text = text;
            this.meta = meta;
            this.folder = folder;
            this.pinned = pinned;
            this.uri = uri;
            this.mediaType = mediaType;
            this.recent = recent;
            this.poster = poster;
            this.postersShown = postersShown;
        }

        static Row header(final String text) {
            return new Row(TYPE_HEADER, text, null, null, false, null, null);
        }

        static Row folder(final Library.Folder folder, final boolean pinned) {
            return new Row(TYPE_FOLDER, folder.name, null, folder, pinned, null, null);
        }

        static Row video(final Uri uri, final String name, @Nullable final String meta,
                         @Nullable final String mediaType) {
            return new Row(TYPE_VIDEO, name, meta, null, false, uri, mediaType);
        }

        static Row recent(final History.Entry entry, final boolean postersShown) {
            return new Row(TYPE_VIDEO, entry.name, null, null, false, entry.uri, entry.type,
                    true, postersShown
                            ? com.brouken.player.online.Posters.url(entry.poster) : null,
                    postersShown);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(final int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                                          final int viewType) {
            final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            switch (viewType) {
                case TYPE_HEADER:
                    return new HeaderHolder(
                            inflater.inflate(R.layout.home_item_header, parent, false));
                case TYPE_FOLDER:
                    return new FolderHolder(
                            inflater.inflate(R.layout.home_item_folder, parent, false));
                default:
                    return new VideoHolder(
                            inflater.inflate(R.layout.home_item_video, parent, false));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder,
                                     final int position) {
            final Row row = rows.get(position);
            if (holder instanceof HeaderHolder) {
                ((HeaderHolder) holder).text.setText(row.text);
            } else if (holder instanceof FolderHolder) {
                ((FolderHolder) holder).bind(row);
            } else {
                ((VideoHolder) holder).bind(row);
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView text;

        HeaderHolder(final View view) {
            super(view);
            text = view.findViewById(R.id.header_text);
        }
    }

    private final class FolderHolder extends RecyclerView.ViewHolder {
        /** Opens the folder; sits beside the star. */
        final View body;
        final TextView name;
        final TextView meta;
        final ImageButton pin;

        FolderHolder(final View view) {
            super(view);
            body = view.findViewById(R.id.folder_body);
            name = view.findViewById(R.id.folder_name);
            meta = view.findViewById(R.id.folder_meta);
            pin = view.findViewById(R.id.folder_pin);
        }

        void bind(final Row row) {
            final Library.Folder folder = row.folder;
            if (folder == null) {
                return;
            }
            name.setText(folder.name);
            meta.setText(folderMeta(folder));
            pin.setImageResource(row.pinned
                    ? R.drawable.ic_pin_filled_24dp : R.drawable.ic_pin_24dp);
            pin.setImageTintList(android.content.res.ColorStateList.valueOf(
                    // amber like the rating star, whatever the accent
                    row.pinned
                            ? androidx.core.content.ContextCompat.getColor(
                                    HomeActivity.this, R.color.rating_amber)
                            : textSecondary()));
            pin.setContentDescription(getString(row.pinned
                    ? R.string.home_unpin : R.string.home_pin));
            pin.setOnClickListener(view -> togglePin(folder));
            body.setOnClickListener(view -> openFolder(folder));
            if (folder.id.equals(focusPinFor)) {
                focusPinFor = null;
                // after layout: a view not yet placed cannot take focus
                pin.post(pin::requestFocus);
            }
            body.setContentDescription(folder.name + ", " + folderMeta(folder));
        }
    }

    private final class VideoHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final android.widget.ImageView icon;

        VideoHolder(final View view) {
            super(view);
            name = view.findViewById(R.id.video_name);
            meta = view.findViewById(R.id.video_meta);
            icon = view.findViewById(R.id.video_icon);
            if (icon != null) {
                // rounded corners, clipped only while a poster shows
                icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(final View view, final android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(4));
                    }
                });
            }
        }

        // 28dp square like a folder's; 2:3 for every row when posters are on
        private void bindRecentIcon(final Row row) {
            icon.setTag(null);
            icon.setClipToOutline(false);
            if (!row.postersShown) {
                shapeIcon(28, 28, 0, 0, 0, 0);
                com.brouken.player.home.Frames.placeholder(
                        HomeActivity.this, icon, R.drawable.ic_movie_24dp);
                return;
            }
            if (row.poster == null) {
                // The 24dp icon 2dp in from the left, as in a folder's 28dp square.
                shapeIcon(40, 60, 2, 18, 14, 18);
                com.brouken.player.home.Frames.placeholder(
                        HomeActivity.this, icon, R.drawable.ic_movie_24dp);
                return;
            }
            shapeIcon(40, 60, 0, 0, 0, 0);
            icon.setClipToOutline(true);
            // placeholder while loading, and if the poster fails
            com.brouken.player.home.Frames.placeholder(
                    HomeActivity.this, icon, R.drawable.ic_movie_24dp);
            com.brouken.player.online.Posters.load(icon, row.poster, R.drawable.ic_movie_24dp);
        }

        private void shapeIcon(final int widthDp, final int heightDp, final int left,
                               final int top, final int right, final int bottom) {
            com.brouken.player.online.Posters.forget(icon);
            icon.setClipToOutline(false);
            final ViewGroup.LayoutParams params = icon.getLayoutParams();
            final int width = dp(widthDp);
            final int height = dp(heightDp);
            if (params.width != width || params.height != height) {
                params.width = width;
                params.height = height;
                icon.setLayoutParams(params);
            }
            icon.setPadding(dp(left), dp(top), dp(right), dp(bottom));
        }

        void bind(final Row row) {
            name.setText(row.text);
            // frames for local files only; a network one means fetching the video
            if (icon != null) {
                if (row.recent) {
                    bindRecentIcon(row);
                } else {
                    shapeIcon(64, 36, 0, 0, 0, 0);
                    if (row.uri != null && !History.isNetworkUri(row.uri)) {
                        frames.into(HomeActivity.this, icon, row.uri, R.drawable.ic_movie_24dp);
                    } else {
                        icon.setTag(null);
                        com.brouken.player.home.Frames.placeholder(
                                HomeActivity.this, icon, R.drawable.ic_movie_24dp);
                    }
                }
            }
            if (TextUtils.isEmpty(row.meta)) {
                meta.setVisibility(View.GONE);
            } else {
                meta.setVisibility(View.VISIBLE);
                meta.setText(row.meta);
            }
            itemView.setOnClickListener(view -> startPlayer(row.uri, row.mediaType));
            itemView.setPadding(itemView.getPaddingLeft(),
                    dp(row.recent && row.postersShown ? 6 : 0), itemView.getPaddingRight(),
                    dp(row.recent && row.postersShown ? 6 : 0));
            itemView.setContentDescription(TextUtils.isEmpty(row.meta)
                    ? row.text : row.text + ", " + row.meta);
        }
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int textSecondary() {
        final android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorSecondary, value, true);
        if (value.resourceId != 0) {
            return androidx.core.content.ContextCompat.getColor(this, value.resourceId);
        }
        return value.data;
    }
}
