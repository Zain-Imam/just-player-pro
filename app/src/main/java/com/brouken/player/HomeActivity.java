package com.brouken.player;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
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

public class HomeActivity extends AppCompatActivity {

    public static final String START_ON_HOME = "home";

    private boolean picking;

    private static final int REQUEST_PERMISSION = 1;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_FOLDER = 1;
    private static final int TYPE_VIDEO = 2;
    private static final int TYPE_FOLDER_TILE = 3;
    private static final int TYPE_VIDEO_TILE = 4;
    private static final int TYPE_POSTER_TILE = 5;

    static final String LAYOUT_WALL = "wall";
    static final String LAYOUT_CARDS = "cards";
    // a grid, as Gallery and Cards are; Cards puts a folder's videos on Recent's tall cards
    private boolean wall;
    private boolean cards;
    private String appliedLayout;
    private final java.util.Map<Uri, Library.Video> videoAt = new java.util.HashMap<>();
    private final java.util.Map<String, List<Uri>> folderFrames = new java.util.HashMap<>();
    private final java.util.Map<String, String> folderLengths = new java.util.HashMap<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Background.single("library");
    private final com.brouken.player.home.Frames frames = new com.brouken.player.home.Frames();

    private SharedPreferences preferences;

    private ImageButton upButton;
    private TextView titleView;
    private EditText searchField;
    private ImageButton searchButton;
    private ImageButton sortButton;
    private ImageButton viewButton;
    private RecyclerView list;
    private TextView emptyView;
    private Button grantButton;
    private ProgressBar loadingView;

    private final List<Library.Video> everything = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private Adapter adapter;

    @Nullable
    private String folderId;
    @Nullable
    private String folderName;
    @Nullable
    private String query;

    private boolean scanned;
    private boolean askedForPermission;
    private boolean permissionPending;
    private boolean offeredLastVideo;
    @Nullable
    private String focusPinFor;
    private String appliedAccent;
    private boolean appliedNight;

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
        appliedNight = AppTheme.isNight(this);

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
        viewButton = findViewById(R.id.home_view_button);
        viewButton.setOnClickListener(view -> showViewMenu());
        list = findViewById(R.id.home_list);
        emptyView = findViewById(R.id.home_empty);
        grantButton = findViewById(R.id.home_grant);
        loadingView = findViewById(R.id.home_loading);

        appliedLayout = preferences.getString("homeLayout", LAYOUT_CARDS);
        applyLayout();
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
        searchField.setOnEditorActionListener((view, actionId, event) -> {
            // a TV remote's OK (IME_NULL) opens the keyboard; a hardware keyboard's Enter goes to
            // the list, once, as the key comes up
            if (actionId == android.view.inputmethod.EditorInfo.IME_NULL) {
                if (Utils.isTvBox(this)) {
                    Utils.showKeyboard(searchField);
                    return true;
                }
                if (event != null && event.getAction() != android.view.KeyEvent.ACTION_UP) {
                    return true;
                }
            }
            hideKeyboard();
            list.requestFocus();
            return true;
        });

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
        if (AppTheme.isNight(this) != appliedNight) {
            recreate();
            return;
        }
        if (appliedLayout != null
                && !appliedLayout.equals(preferences.getString("homeLayout", LAYOUT_CARDS))) {
            recreate();
            return;
        }
        if (refreshRecentOnResume) {
            refreshRecentOnResume = false;
            render();
        }
        com.brouken.player.tracking.Tracking.refresh(this, false, this::ticksByFilm);
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
        startPlayer(uri, type, false);
    }

    void startPlayer(@Nullable final Uri uri, @Nullable final String type, final boolean fromStart) {
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
        if (fromStart) {
            intent.putExtra(PlayerActivity.EXTRA_FROM_START, true);
        }
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
        // held back while the system dialog was up
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
        grantButton.post(grantButton::requestFocus);
    }

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

    // only its stored film matches are read; nothing here needs a player
    @Nullable
    private com.brouken.player.online.OnlineController films;

    // the cards whose film is watched, marked here under another copy or on Simkl's latest list;
    // kept only here, so unwatching there or signing out takes the tick away
    private java.util.Set<String> filmTicks = java.util.Collections.emptySet();
    // a newer screen of cards makes an older count moot
    private int filmTicksRun;

    private void ticksByFilm() {
        if (isFinishing()) {
            return;
        }
        final int run = ++filmTicksRun;
        final java.util.Set<String> marked = Watched.films(preferences);
        final boolean connected = com.brouken.player.tracking.Tracking.anyConnected(this);
        if (marked.isEmpty() && !connected) {
            if (!filmTicks.isEmpty()) {
                filmTicks = java.util.Collections.emptySet();
                adapter.notifyDataSetChanged();
            }
            return;
        }
        filmOf(null);
        final List<Uri> shown = new ArrayList<>();
        for (final Row row : rows) {
            if (row.type == TYPE_VIDEO && row.uri != null) {
                shown.add(row.uri);
            }
        }
        new Thread(() -> {
            final java.util.Set<String> keys = connected
                    ? com.brouken.player.tracking.Tracking.watchedKeys(this) : java.util.Collections.emptySet();
            final java.util.Set<String> ticked = new java.util.HashSet<>();
            for (final Uri uri : shown) {
                if (run != filmTicksRun) {
                    return;
                }
                final String film = Watched.filmKey(films.rememberedForCard(uri));
                if (film != null && (marked.contains(film) || com.brouken.player.tracking.Tracking.covers(keys, film))) {
                    ticked.add(Watched.placeKey(uri));
                }
            }
            runOnUiThread(() -> {
                if (!isFinishing() && run == filmTicksRun && !ticked.equals(filmTicks)) {
                    filmTicks = ticked;
                    adapter.notifyDataSetChanged();
                }
            });
        }, "film ticks").start();
    }

    private com.brouken.player.online.Identity filmOf(final Uri uri) {
        if (films == null) {
            films = new com.brouken.player.online.OnlineController(this, new com.brouken.player.online.OnlineController.Host() {
                @Nullable
                @Override
                public Uri mediaUri() {
                    return null;
                }

                @Nullable
                @Override
                public String mediaName() {
                    return null;
                }

                @Override
                public void loadSubtitle(final Uri uri, @Nullable final String label) {
                }
            });
        }
        return films.rememberedForCard(uri);
    }

    private void render() {
        com.brouken.player.home.Frames.better = preferences.getBoolean("homeBetterFrames", false);
        rows.clear();
        watchedPlaces = Watched.places(preferences);
        // a list row shows folder pictures too, so they are found again on every visit
        folderFrames.clear();
        folderLengths.clear();
        if (wall) {
            videoAt.clear();
            for (final Library.Video video : everything) {
                videoAt.put(video.uri, video);
            }
        }

        final boolean searching = query != null && !query.trim().isEmpty();

        if (searching) {
            renderSearch();
        } else if (folderId != null) {
            renderFolder();
        } else {
            renderRoot();
        }

        adapter.notifyDataSetChanged();
        ticksByFilm();
        if (wall && folderId == null && !rows.isEmpty()) {
            hintWallOnce();
        }
        setTitleText();
        upButton.setVisibility(folderId != null ? View.VISIBLE : View.GONE);
        viewButton.setVisibility(folderId != null && !picking ? View.VISIBLE : View.GONE);

        final boolean empty = rows.isEmpty();
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty && scanned ? View.VISIBLE : View.GONE);
        if (empty && scanned) {
            emptyView.setText(searching
                    ? R.string.home_empty_results
                    : (folderId != null ? R.string.home_empty_folder : R.string.home_empty_none));
        }
    }

    private Row recentRow(final History.Entry entry, final boolean posters) {
        final Row row = Row.recent(entry, posters);
        row.listPlace = History.listPlace(preferences, entry.uri);
        // only a named entry has a film; one left plain in Film info has none
        final com.brouken.player.online.Identity film = entry.title == null ? null : filmOf(entry.uri);
        row.episode = film != null && film.isSeries ? film.episodeCode() : null;
        return row;
    }

    // a playlist says so under its name; a list row has no picture to mark
    @Nullable
    private String recentMeta(final Row row, final boolean withPlace) {
        if (row.listPlace == null) {
            return row.meta;
        }
        String playlist = getString(R.string.home_recent_playlist);
        if (withPlace && !row.listPlace.isEmpty()) {
            playlist += " " + row.listPlace;
        }
        return TextUtils.isEmpty(row.meta) ? playlist : playlist + " · " + row.meta;
    }

    private void renderRoot() {
        // no history while picking: entries may be network addresses
        final List<History.Entry> recent = picking
                ? java.util.Collections.<History.Entry>emptyList()
                : History.load(preferences);
        final boolean posters = preferences.getBoolean("recentPosters", false);
        final int limit = Math.max(1, Math.min(30, preferences.getInt("recentCount", 10)));
        final List<History.Entry> pinnedRecent = new ArrayList<>();
        final List<History.Entry> newest = new ArrayList<>();
        for (final History.Entry entry : recent) {
            if (!entry.played) {
                continue;
            }
            if (entry.pinned) {
                pinnedRecent.add(entry);
            } else if (newest.size() < limit) {
                newest.add(entry);
            }
        }
        final List<Row> here = picking || !preferences.getBoolean("recentHere", true)
                ? java.util.Collections.<Row>emptyList() : playedHere();
        // two lists, so each says which it is
        if (!pinnedRecent.isEmpty() || !newest.isEmpty()) {
            rows.add(Row.header(getString(here.isEmpty()
                    ? R.string.home_section_recent : R.string.home_section_recent_online)));
        }
        for (final History.Entry entry : pinnedRecent) {
            rows.add(recentRow(entry, posters));
        }
        for (final History.Entry entry : newest) {
            rows.add(recentRow(entry, posters));
        }
        if (!here.isEmpty()) {
            rows.add(Row.header(getString(R.string.home_section_recent_here)));
            rows.addAll(here);
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

    private static final int RECENT_HERE = 10;

    // films played on this device, newest first; only those still in the library, so a deleted file never shows
    private List<Row> playedHere() {
        final java.util.Map<String, Library.Video> byLink = new java.util.HashMap<>();
        for (final Library.Video video : everything) {
            byLink.put(video.uri.toString(), video);
        }
        final Set<String> hidden = Prefs.hiddenHere(preferences);
        final boolean posters = preferences.getBoolean("recentPosters", false);
        // pinned first, in the order pinned, then the newest
        final List<String> pins = Prefs.pinnedHere(preferences);
        final List<Prefs.Played> newest = Prefs.playedNewestFirst(this);
        final List<Prefs.Played> ordered = new ArrayList<>();
        for (final String pin : pins) {
            for (final Prefs.Played played : newest) {
                if (played.link.equals(pin)) {
                    ordered.add(played);
                }
            }
        }
        for (final Prefs.Played played : newest) {
            if (!pins.contains(played.link)) {
                ordered.add(played);
            }
        }
        final java.util.Set<String> known = new java.util.HashSet<>();
        for (final Prefs.Played played : ordered) {
            if (byLink.containsKey(played.link) && !hidden.contains(played.link)) {
                known.add(played.link);
            }
        }
        Prefs.keepPinsHere(preferences, known);
        final List<Row> here = new ArrayList<>();
        for (final Prefs.Played played : ordered) {
            final Library.Video video = byLink.get(played.link);
            if (video == null || hidden.contains(played.link)) {
                continue;
            }
            final long duration = played.duration > 0 ? played.duration : video.duration;
            int progress = -1;
            String meta = video.folderName;
            if (duration > 0 && played.position > 0 && played.position < duration) {
                progress = (int) (played.position * 1000 / duration);
                meta += "  ·  " + getString(R.string.home_time_left,
                        Utils.formatMilis(duration - played.position));
            }
            // named and pictured like an online film once Film info knows which film it is
            final com.brouken.player.online.Identity film = filmOf(video.uri);
            final String poster = posters && film != null && film.posterPath != null
                    && !Prefs.frameHere(preferences, played.link)
                    ? com.brouken.player.online.Posters.url(film.posterPath) : null;
            final Row row = Row.playedHere(video, film != null ? film.heading() : video.name, meta, progress,
                    pins.contains(played.link), poster);
            row.episode = film != null && film.isSeries ? film.episodeCode() : null;
            here.add(row);
            if (here.size() >= RECENT_HERE) {
                break;
            }
        }
        return here;
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
            meta.append(com.brouken.player.home.Readable.size(this, video.size));
        }
        return meta.toString();
    }

    private String folderMeta(final Library.Folder folder) {
        final String count = folder.count == 1
                ? getString(R.string.home_videos_one)
                : getString(R.string.home_videos_many, folder.count);
        return folder.size > 0 ? count + "  ·  " + com.brouken.player.home.Readable.size(this, folder.size) : count;
    }

    @Nullable
    private android.os.Parcelable topListState;
    @Nullable
    private String openedFolderId;
    private int openedFolderRow = -1;

    private void openFolder(final Library.Folder folder, final int row) {
        if (folderId == null && query == null && list.getLayoutManager() != null) {
            topListState = list.getLayoutManager().onSaveInstanceState();
            openedFolderId = folder.id;
            openedFolderRow = row;
        }
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
            if (topListState != null && list.getLayoutManager() != null) {
                list.getLayoutManager().onRestoreInstanceState(topListState);
            }
            // a list that changed meanwhile finds the folder by id
            int at = openedFolderRow;
            if (at < 0 || at >= rows.size() || rows.get(at).folder == null
                    || !rows.get(at).folder.id.equals(openedFolderId)) {
                at = -1;
                for (int i = 0; i < rows.size() && openedFolderId != null; i++) {
                    if (rows.get(i).folder != null && rows.get(i).folder.id.equals(openedFolderId)) {
                        at = i;
                        break;
                    }
                }
            }
            topListState = null;
            openedFolderId = null;
            openedFolderRow = -1;
            Panels.focusRow(list, Math.max(0, at));
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
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                });
    }

    private android.graphics.drawable.Drawable pinMark() {
        final android.graphics.drawable.Drawable mark = androidx.core.content.ContextCompat
                .getDrawable(this, R.drawable.ic_pin_filled_24dp).mutate();
        mark.setTint(androidx.core.content.ContextCompat.getColor(this, R.color.rating_amber));
        mark.setBounds(0, 0, dp(16), dp(16));
        return mark;
    }

    private boolean refreshRecentOnResume;

    private void showRecentMenu(final Row row) {
        if (row.uri == null || picking) {
            return;
        }
        final Uri uri = row.uri;
        final com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this,
                        R.style.Theme_Player_RecentSheet);
        final View content = getLayoutInflater().inflate(R.layout.recent_menu, null);
        final History.Entry entry = History.find(preferences, uri);
        final android.widget.ImageView poster = content.findViewById(R.id.recent_menu_poster);
        com.brouken.player.home.Frames.placeholder(this, poster, R.drawable.ic_movie_24dp);
        if (entry != null && entry.poster != null) {
            com.brouken.player.online.Posters.load(poster,
                    com.brouken.player.online.Posters.url(entry.poster), R.drawable.ic_movie_24dp);
        }
        ((TextView) content.findViewById(R.id.recent_menu_title)).setText(row.text);
        final TextView file = content.findViewById(R.id.recent_menu_file);
        file.setText(entry != null ? entry.name : "");
        file.setVisibility(entry != null && !entry.name.equals(row.text) ? View.VISIBLE : View.GONE);

        final TextView pinItem = content.findViewById(R.id.recent_menu_pin);
        if (row.local) {
            file.setVisibility(View.GONE);
            if (row.poster == null) {
                frames.into(this, poster, uri, R.drawable.ic_movie_24dp);
            } else {
                com.brouken.player.online.Posters.load(poster, row.poster, R.drawable.ic_movie_24dp);
            }
        }
        pinItem.setText(row.pinned ? R.string.recent_unpin : R.string.recent_pin);
        pinItem.setOnClickListener(view -> {
            sheet.dismiss();
            final boolean done = row.local
                    ? Prefs.setPinnedHere(preferences, uri.toString(), !row.pinned)
                    : History.setPinned(preferences, uri, !row.pinned);
            if (!done) {
                Toast.makeText(this, getString(R.string.recent_pins_full,
                        row.local ? Prefs.MAX_PINS_HERE : History.maxPins(preferences)), Toast.LENGTH_LONG).show();
                return;
            }
            render();
        });
        content.findViewById(R.id.recent_menu_start).setOnClickListener(view -> {
            sheet.dismiss();
            startPlayer(uri, row.mediaType, true);
        });
        final TextView info = content.findViewById(R.id.recent_menu_info);
        // a film on this device is known through its saved match, not Recent
        final boolean known = row.local ? filmOf(uri) != null : entry != null && entry.title != null;
        info.setText(known ? R.string.recent_info : R.string.film_find);
        info.setOnClickListener(view -> {
            sheet.dismiss();
            final Intent intent = new Intent(this, FilmInfoActivity.class);
            if (row.mediaType == null || row.mediaType.isEmpty()) {
                intent.setData(uri);
            } else {
                intent.setDataAndType(uri, row.mediaType);
            }
            refreshRecentOnResume = true;
            startActivity(intent);
        });
        content.findViewById(R.id.recent_menu_remove).setOnClickListener(view -> {
            sheet.dismiss();
            // a film on this device is only hidden: it keeps its place to resume
            if (row.local) {
                Prefs.setHiddenHere(preferences, uri.toString(), true);
                render();
                offerUndo(() -> Prefs.setHiddenHere(preferences, uri.toString(), false));
                return;
            }
            final History.Entry removed = History.find(preferences, uri);
            final int at = History.remove(preferences, uri);
            render();
            if (removed != null && at >= 0) {
                offerUndo(() -> {
                    if (BuildConfig.DEBUG) {
                        Utils.log("Recent undo: " + removed.name + " back at " + at);
                    }
                    History.restore(preferences, removed, at);
                });
            }
        });
        sheet.setContentView(content);
        sheet.setOnShowListener(dialog -> {
            Showcase.hideBars(sheet.getWindow());
            final View bottomSheet = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                        .setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
            (row.local ? content.findViewById(R.id.recent_menu_start) : pinItem).requestFocus();
        });
        sheet.show();
    }

    private void offerUndo(final Runnable undo) {
        final com.google.android.material.snackbar.Snackbar bar = com.google.android.material.snackbar.Snackbar
                // long enough to reach for Undo; the standard long one is under 3 s
                .make(list, R.string.recent_removed, 5000)
                .setAction(R.string.recent_undo, view -> {
                    undo.run();
                    render();
                });
        bar.show();
        // a remote can't get down to the bar past the list, so Undo takes the focus
        final View action = bar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        if (action != null) {
            action.setFocusable(true);
            action.post(action::requestFocus);
            bar.addCallback(new com.google.android.material.snackbar.BaseTransientBottomBar
                    .BaseCallback<com.google.android.material.snackbar.Snackbar>() {
                @Override
                public void onDismissed(final com.google.android.material.snackbar.Snackbar gone,
                                        final int event) {
                    // the focus went with the bar
                    if (getCurrentFocus() == null || getCurrentFocus() == action) {
                        list.requestFocus();
                    }
                }
            });
        }
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

    // built by hand: setSingleChoiceItems allows only one list of choices
    private void showSortMenu() {
        // an empty search field still shows the folders
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
        final boolean recent;
        @Nullable
        final String poster;
        final boolean postersShown;
        // Recent on this device: a local film with how far it got, 0 to 1000, or -1
        boolean local;
        int progress = -1;
        // a YouTube playlist: "5 / 24", empty before its place is known, null for anything else
        @Nullable
        String listPlace;
        // an episode: "S01E10", on the card since every episode has the show's poster
        @Nullable
        String episode;

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

        static Row playedHere(final Library.Video video, final String name, final String meta,
                              final int progress, final boolean pinned, @Nullable final String poster) {
            final Row row = new Row(TYPE_VIDEO, name, meta, null, pinned, video.uri, null, false,
                    poster, poster != null);
            row.local = true;
            row.progress = progress;
            return row;
        }

        static Row recent(final History.Entry entry, final boolean postersShown) {
            final boolean identified = entry.title != null;
            return new Row(TYPE_VIDEO, identified ? entry.title : entry.name,
                    identified ? entry.name : null, null, entry.pinned, entry.uri, entry.type,
                    true, postersShown
                            ? com.brouken.player.online.Posters.url(entry.poster) : null,
                    postersShown);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(final int position) {
            final int type = rows.get(position).type;
            if (!wall || type == TYPE_HEADER) {
                return type;
            }
            if (rows.get(position).recent || rows.get(position).local
                    || cards && type == TYPE_VIDEO) {
                return TYPE_POSTER_TILE;
            }
            return type == TYPE_FOLDER ? TYPE_FOLDER_TILE : TYPE_VIDEO_TILE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                                          final int viewType) {
            final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            switch (viewType) {
                case TYPE_HEADER:
                    final View header = inflater.inflate(R.layout.home_item_header, parent, false);
                    if (wall) {
                        final int gap = getResources().getDimensionPixelSize(R.dimen.home_tile_gap);
                        header.setPaddingRelative(gap, header.getPaddingTop(), gap,
                                header.getPaddingBottom());
                    }
                    return new HeaderHolder(header);
                case TYPE_FOLDER:
                    return new FolderHolder(
                            inflater.inflate(R.layout.home_item_folder, parent, false));
                case TYPE_FOLDER_TILE:
                    return new FolderTileHolder(
                            inflater.inflate(R.layout.home_tile_folder, parent, false));
                case TYPE_VIDEO_TILE:
                    return new VideoTileHolder(
                            inflater.inflate(R.layout.home_tile_video, parent, false));
                case TYPE_POSTER_TILE:
                    return new PosterTileHolder(
                            inflater.inflate(R.layout.home_tile_poster, parent, false));
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
            } else if (holder instanceof FolderTileHolder) {
                ((FolderTileHolder) holder).bind(row);
            } else if (holder instanceof VideoTileHolder) {
                ((VideoTileHolder) holder).bind(row);
            } else if (holder instanceof PosterTileHolder) {
                ((PosterTileHolder) holder).bind(row);
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
        final View body;
        final TextView name;
        final TextView meta;
        final ImageButton pin;
        final View icon;
        final com.brouken.player.home.FolderPicture picture;

        FolderHolder(final View view) {
            super(view);
            body = view.findViewById(R.id.folder_body);
            name = view.findViewById(R.id.folder_name);
            meta = view.findViewById(R.id.folder_meta);
            pin = view.findViewById(R.id.folder_pin);
            icon = view.findViewById(R.id.folder_icon);
            picture = view.findViewById(R.id.folder_picture);
        }

        void bind(final Row row) {
            final Library.Folder folder = row.folder;
            if (folder == null) {
                return;
            }
            // the plain folder keeps the icon in the list, as a folder
            final boolean pictured = !com.brouken.player.home.FolderPicture.FOLDER.equals(folderLook());
            icon.setVisibility(pictured ? View.GONE : View.VISIBLE);
            picture.setVisibility(pictured ? View.VISIBLE : View.GONE);
            if (pictured) {
                fillFolderPicture(picture, folder, false);
            }
            name.setText(folder.name);
            meta.setText(folderMeta(folder));
            pin.setImageResource(row.pinned
                    ? R.drawable.ic_pin_filled_24dp : R.drawable.ic_pin_24dp);
            pin.setImageTintList(android.content.res.ColorStateList.valueOf(
                    row.pinned
                            ? androidx.core.content.ContextCompat.getColor(
                                    HomeActivity.this, R.color.rating_amber)
                            : textSecondary()));
            pin.setContentDescription(getString(row.pinned
                    ? R.string.home_unpin : R.string.home_pin));
            pin.setOnClickListener(view -> togglePin(folder));
            body.setOnClickListener(view -> openFolder(folder, rows.indexOf(row)));
            if (folder.id.equals(focusPinFor)) {
                focusPinFor = null;
                // after layout: a view not yet placed cannot take focus
                pin.post(pin::requestFocus);
            }
            body.setContentDescription(folder.name + ", " + folderMeta(folder));
        }
    }

    private java.util.Set<String> watchedPlaces = java.util.Collections.emptySet();

    // marked or finished here, or watched by Simkl's latest list
    private boolean watched(final Row row) {
        if (row.uri == null) {
            return false;
        }
        final String place = Watched.placeKey(row.uri);
        return watchedPlaces.contains(place) || filmTicks.contains(place);
    }

    private final class VideoHolder extends RecyclerView.ViewHolder {
        @Nullable
        final android.widget.ImageView watched;
        final TextView name;
        final TextView meta;
        final android.widget.ImageView icon;
        @Nullable
        final android.widget.ProgressBar progress;

        VideoHolder(final View view) {
            super(view);
            name = view.findViewById(R.id.video_name);
            meta = view.findViewById(R.id.video_meta);
            icon = view.findViewById(R.id.video_icon);
            progress = view.findViewById(R.id.video_progress);
            watched = view.findViewById(R.id.video_watched);
            if (icon != null) {
                icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(final View view, final android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(4));
                    }
                });
            }
        }

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
                // the 24dp icon 2dp in from the left, as in a folder's 28dp square
                shapeIcon(40, 60, 2, 18, 14, 18);
                com.brouken.player.home.Frames.placeholder(
                        HomeActivity.this, icon, R.drawable.ic_movie_24dp);
                return;
            }
            shapeIcon(40, 60, 0, 0, 0, 0);
            icon.setClipToOutline(true);
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
            // middle ellipsis over several lines renders blank on some devices
            final boolean identified = row.recent && !TextUtils.isEmpty(row.meta);
            name.setSingleLine(!identified);
            name.setMaxLines(identified ? 2 : 1);
            name.setEllipsize(identified ? TextUtils.TruncateAt.END : TextUtils.TruncateAt.MIDDLE);
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
            final String metaText = row.recent ? recentMeta(row, true) : row.meta;
            if (TextUtils.isEmpty(metaText)) {
                meta.setVisibility(View.GONE);
            } else {
                meta.setVisibility(View.VISIBLE);
                meta.setText(metaText);
            }
            meta.setEllipsize(row.recent ? TextUtils.TruncateAt.MIDDLE : TextUtils.TruncateAt.END);
            name.setCompoundDrawablesRelative(row.recent && row.pinned ? pinMark() : null, null, null, null);
            name.setCompoundDrawablePadding(dp(6));
            name.setContentDescription(row.recent && row.pinned
                    ? getString(R.string.recent_pinned) + ", " + row.text : null);
            if (progress != null) {
                progress.setVisibility(row.progress >= 0 ? View.VISIBLE : View.GONE);
                progress.setProgress(Math.max(0, row.progress));
            }
            if (watched != null) {
                watched.setVisibility(watched(row) ? View.VISIBLE : View.GONE);
            }
            itemView.setOnClickListener(view -> startPlayer(row.uri, row.mediaType));
            if (row.recent || row.local) {
                itemView.setOnLongClickListener(view -> {
                    showRecentMenu(row);
                    return true;
                });
            } else {
                itemView.setOnLongClickListener(null);
                itemView.setLongClickable(false);
            }
            itemView.setPadding(itemView.getPaddingLeft(),
                    dp(row.recent && row.postersShown ? 6 : 0), itemView.getPaddingRight(),
                    dp(row.recent && row.postersShown ? 6 : 0));
            itemView.setContentDescription((TextUtils.isEmpty(row.meta)
                    ? row.text : row.text + ", " + row.meta)
                    + (row.recent && row.pinned ? ", " + getString(R.string.recent_pinned) : ""));
        }
    }

    // a new install starts on Cards; one updated from an older version keeps the List it had
    static void settleLayout(final Context context) {
        final SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.contains("homeLayout")) {
            return;
        }
        boolean fresh = true;
        try {
            final android.content.pm.PackageInfo info =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            fresh = info.firstInstallTime == info.lastUpdateTime;
        } catch (Exception ignored) {
            // taken as new
        }
        prefs.edit().putString("homeLayout", fresh ? LAYOUT_CARDS : "list").apply();
    }

    private int listPaddingStart = -1;
    private int listPaddingEnd;

    // also on a change from the view button, in place, so the open folder stays open
    private void applyLayout() {
        appliedLayout = preferences.getString("homeLayout", LAYOUT_CARDS);
        cards = LAYOUT_CARDS.equals(appliedLayout);
        wall = cards || LAYOUT_WALL.equals(appliedLayout);
        if (listPaddingStart < 0) {
            listPaddingStart = list.getPaddingStart();
            listPaddingEnd = list.getPaddingEnd();
        }
        if (wall) {
            setUpWall();
        } else {
            list.setLayoutManager(new LinearLayoutManager(this));
            list.setPaddingRelative(listPaddingStart, list.getPaddingTop(), listPaddingEnd, list.getPaddingBottom());
        }
    }

    private void showViewMenu() {
        final String[] values = getResources().getStringArray(R.array.home_layout_values);
        final String[] names = getResources().getStringArray(R.array.home_layout_entries);
        final String now = preferences.getString("homeLayout", LAYOUT_CARDS);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(now)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.home_view)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (values[which].equals(now)) {
                        return;
                    }
                    preferences.edit().putString("homeLayout", values[which]).apply();
                    applyLayout();
                    list.setAdapter(adapter);
                    render();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // columns x posters spans: a tile takes "posters" of them, a poster "columns"
    private void setUpWall() {
        final androidx.recyclerview.widget.GridLayoutManager grid =
                new androidx.recyclerview.widget.GridLayoutManager(this, wallSpans());
        grid.setSpanSizeLookup(new androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(final int position) {
                if (position >= rows.size() || rows.get(position).type == TYPE_HEADER) {
                    return grid.getSpanCount();
                }
                // folders, films on this device and, in Cards, a folder's videos line up with Recent's cards
                final Row row = rows.get(position);
                return row.recent || row.local || row.type == TYPE_FOLDER || cards
                        ? wallColumns() : postersPerRow();
            }
        });
        list.setLayoutManager(grid);
        final int side = dp(Utils.isTvBox(this) ? 36 : 10);
        list.setPaddingRelative(side, list.getPaddingTop(), side, list.getPaddingBottom());
    }

    private void hintWallOnce() {
        if (picking || preferences.getBoolean("wallHintShown", false)) {
            return;
        }
        preferences.edit().putBoolean("wallHintShown", true).apply();
        Toast.makeText(this, R.string.home_wall_hint, Toast.LENGTH_LONG).show();
    }

    private int wallColumns() {
        final int width = getResources().getConfiguration().screenWidthDp;
        return Math.max(2, Math.min(6, width / 200));
    }

    // a poster about 0.6 of a tile wide: 3 beside 2 tiles, 6 beside 4
    private int postersPerRow() {
        return Math.round(wallColumns() * 1.6f);
    }

    private int wallSpans() {
        return wallColumns() * postersPerRow();
    }

    @Override
    public void onConfigurationChanged(@NonNull final android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        // uiMode is handled here, so light or dark is redone by hand; behind Settings a
        // relaunch left its starting window stuck over Settings, so onResume does it then
        if (AppTheme.isNight(this) != appliedNight) {
            if (getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                recreate();
            }
            return;
        }
        // turning the phone keeps this screen, so the columns follow by hand
        if (wall && list != null
                && list.getLayoutManager() instanceof androidx.recyclerview.widget.GridLayoutManager) {
            ((androidx.recyclerview.widget.GridLayoutManager) list.getLayoutManager())
                    .setSpanCount(wallSpans());
        }
    }

    private void growOnFocus(final View tile) {
        tile.setOnFocusChangeListener((view, focused) -> {
            view.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                    .translationZ(focused ? dp(4) : 0).setDuration(150).start();
        });
    }

    private List<Uri> framesOf(final Library.Folder folder) {
        List<Uri> known = folderFrames.get(folder.id);
        if (known == null) {
            final List<Library.Video> inside = Library.inFolder(everything, folder.id);
            Sort.apply(inside, Sort.videos(preferences), Sort.videosReversed(preferences));
            known = new ArrayList<>();
            for (int i = 0; i < Math.min(4, inside.size()); i++) {
                known.add(inside.get(i).uri);
            }
            folderFrames.put(folder.id, known);
        }
        return known;
    }

    // all its videos end to end; null when none says how long it is
    @Nullable
    private String folderLength(final Library.Folder folder) {
        if (!folderLengths.containsKey(folder.id)) {
            long total = 0;
            for (final Library.Video video : Library.inFolder(everything, folder.id)) {
                total += Math.max(0, video.duration);
            }
            // a big folder runs past what formatMilis's int can hold
            final long seconds = total / 1000;
            folderLengths.put(folder.id, total <= 0 ? null : seconds >= 3600
                    ? String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                    : String.format(java.util.Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60));
        }
        return folderLengths.get(folder.id);
    }

    // looks from before 5.1 that are gone become the default
    private String folderLook() {
        final String look = preferences.getString("folderLook", com.brouken.player.home.FolderPicture.CENTRED);
        return com.brouken.player.home.FolderPicture.SQUARE.equals(look) || com.brouken.player.home.FolderPicture.MOSAIC.equals(look) || com.brouken.player.home.FolderPicture.FOLDER.equals(look)
                ? look : com.brouken.player.home.FolderPicture.CENTRED;
    }

    // a card is tall like Recent's, the list's small picture is wide
    private void fillFolderPicture(final com.brouken.player.home.FolderPicture picture,
                                   final Library.Folder folder, final boolean card) {
        final List<Uri> uris = framesOf(folder);
        final String look = folderLook();
        final int shown = picture.show(look, uris.size(), Accent.color(this));
        picture.setChip(com.brouken.player.home.FolderPicture.FOLDER.equals(look) ? folderLength(folder) : String.valueOf(folder.count));
        final android.widget.ImageView[] images = picture.images();
        for (int i = 0; i < shown; i++) {
            if (uris.isEmpty()) {
                images[i].setTag(null);
                images[i].setImageDrawable(null);
            } else {
                frames.into(HomeActivity.this, images[i], uris.get(i), R.drawable.ic_movie_24dp,
                        card && com.brouken.player.home.FolderPicture.CENTRED.equals(look));
            }
        }
    }

    private final class FolderTileHolder extends RecyclerView.ViewHolder {
        final com.brouken.player.home.FolderPicture picture;
        final View star;
        final TextView name;
        final TextView meta;

        FolderTileHolder(final View view) {
            super(view);
            view.findViewById(R.id.tile_frame).setClipToOutline(true);
            picture = view.findViewById(R.id.tile_picture);
            star = view.findViewById(R.id.tile_star);
            name = view.findViewById(R.id.tile_name);
            meta = view.findViewById(R.id.tile_meta);
            growOnFocus(view);
        }

        void bind(final Row row) {
            final Library.Folder folder = row.folder;
            if (folder == null) {
                return;
            }
            name.setText(folder.name);
            meta.setText(folderMeta(folder));
            star.setVisibility(row.pinned ? View.VISIBLE : View.GONE);
            final View frame = itemView.findViewById(R.id.tile_frame);
            final androidx.constraintlayout.widget.ConstraintLayout.LayoutParams shape =
                    (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) frame.getLayoutParams();
            final String ratio = com.brouken.player.home.FolderPicture.SQUARE.equals(folderLook()) ? "1:1" : "2:3";
            if (!ratio.equals(shape.dimensionRatio)) {
                shape.dimensionRatio = ratio;
                frame.setLayoutParams(shape);
            }
            fillFolderPicture(picture, folder, true);
            itemView.setOnClickListener(view -> openFolder(folder, rows.indexOf(row)));
            itemView.setOnLongClickListener(view -> {
                togglePin(folder);
                return true;
            });
            if (folder.id.equals(focusPinFor)) {
                focusPinFor = null;
                itemView.post(itemView::requestFocus);
            }
            itemView.setContentDescription(folder.name + ", " + folderMeta(folder)
                    + (row.pinned ? ", " + getString(R.string.home_section_pinned) : ""));
        }
    }

    private final class PosterTileHolder extends RecyclerView.ViewHolder {
        final android.widget.ImageView image;
        final android.widget.ImageView pin;
        final android.widget.ImageView watched;
        final TextView list;
        final TextView episode;
        final TextView name;
        final TextView meta;
        final android.widget.ProgressBar progress;

        PosterTileHolder(final View view) {
            super(view);
            view.findViewById(R.id.tile_frame).setClipToOutline(true);
            image = view.findViewById(R.id.tile_image);
            pin = view.findViewById(R.id.tile_pin);
            watched = view.findViewById(R.id.tile_watched);
            list = view.findViewById(R.id.tile_list);
            episode = view.findViewById(R.id.tile_episode);
            progress = view.findViewById(R.id.tile_progress);
            final android.graphics.drawable.Drawable mark = androidx.core.content.ContextCompat
                    .getDrawable(HomeActivity.this, R.drawable.ic_queue_24dp);
            if (mark != null) {
                mark.setBounds(0, 0, dp(14), dp(14));
                list.setCompoundDrawablesRelative(mark, null, null, null);
            }
            name = view.findViewById(R.id.tile_name);
            meta = view.findViewById(R.id.tile_meta);
            growOnFocus(view);
        }

        void bind(final Row row) {
            if (!row.recent && !row.local) {
                bindFolderVideo(row);
                return;
            }
            final boolean identified = !TextUtils.isEmpty(row.meta);
            name.setSingleLine(!identified);
            // always two lines, so a long site title doesn't make its tile taller than the rest
            if (identified) {
                name.setLines(2);
            }
            name.setEllipsize(identified ? TextUtils.TruncateAt.END : TextUtils.TruncateAt.MIDDLE);
            name.setText(row.text);
            final String metaText = recentMeta(row, false);
            meta.setVisibility(TextUtils.isEmpty(metaText) ? View.GONE : View.VISIBLE);
            meta.setText(metaText);
            list.setVisibility(row.listPlace == null ? View.GONE : View.VISIBLE);
            list.setText(row.listPlace);
            episode.setVisibility(row.listPlace == null && row.episode != null ? View.VISIBLE : View.GONE);
            episode.setText(row.episode);
            if (row.local && row.poster == null) {
                // a poster still loading for a recycled card must not land on this one
                com.brouken.player.online.Posters.forget(image);
                frames.into(HomeActivity.this, image, row.uri, R.drawable.ic_movie_24dp, true);
            } else {
                // nor a frame
                image.setTag(null);
                com.brouken.player.home.Frames.placeholder(
                        HomeActivity.this, image, R.drawable.ic_movie_24dp);
                com.brouken.player.online.Posters.load(image, row.poster, R.drawable.ic_movie_24dp);
            }
            progress.setVisibility(row.progress >= 0 ? View.VISIBLE : View.GONE);
            progress.setProgress(Math.max(0, row.progress));
            pin.setVisibility(row.pinned ? View.VISIBLE : View.GONE);
            watched.setVisibility(watched(row) ? View.VISIBLE : View.GONE);
            itemView.setOnClickListener(view -> startPlayer(row.uri, row.mediaType));
            itemView.setOnLongClickListener(view -> {
                showRecentMenu(row);
                return true;
            });
            itemView.setContentDescription((TextUtils.isEmpty(row.meta)
                    ? row.text : row.text + ", " + row.meta)
                    + (row.pinned ? ", " + getString(R.string.recent_pinned) : ""));
        }

        // a video inside a folder, on Cards
        private void bindFolderVideo(final Row row) {
            final Library.Video video = row.uri == null ? null : videoAt.get(row.uri);
            name.setSingleLine(false);
            name.setLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(row.text);
            final String length = video != null && video.duration > 0 ? Utils.formatMilis(video.duration) : null;
            final String size = video != null && video.size > 0
                    ? com.brouken.player.home.Readable.size(HomeActivity.this, video.size) : null;
            final String where = video != null && query != null && !query.trim().isEmpty()
                    ? video.folderName : null;
            final StringBuilder line = new StringBuilder();
            for (final String part : new String[]{where, length, size}) {
                if (!TextUtils.isEmpty(part)) {
                    if (line.length() > 0) {
                        line.append("  ·  ");
                    }
                    line.append(part);
                }
            }
            meta.setVisibility(line.length() == 0 ? View.GONE : View.VISIBLE);
            meta.setText(line);
            list.setVisibility(View.GONE);
            episode.setVisibility(View.GONE);
            pin.setVisibility(View.GONE);
            watched.setVisibility(watched(row) ? View.VISIBLE : View.GONE);
            com.brouken.player.online.Posters.forget(image);
            if (row.uri != null && !History.isNetworkUri(row.uri)) {
                frames.into(HomeActivity.this, image, row.uri, R.drawable.ic_movie_24dp, true);
            } else {
                image.setTag(null);
                com.brouken.player.home.Frames.placeholder(HomeActivity.this, image, R.drawable.ic_movie_24dp);
            }
            progress.setVisibility(row.progress >= 0 ? View.VISIBLE : View.GONE);
            progress.setProgress(Math.max(0, row.progress));
            itemView.setOnClickListener(view -> startPlayer(row.uri, row.mediaType));
            itemView.setOnLongClickListener(null);
            itemView.setLongClickable(false);
            itemView.setContentDescription(line.length() == 0 ? row.text : row.text + ", " + line);
        }
    }

    private final class VideoTileHolder extends RecyclerView.ViewHolder {
        final android.widget.ImageView image;
        final TextView length;
        final TextView name;
        final TextView meta;
        final android.widget.ProgressBar progress;

        VideoTileHolder(final View view) {
            super(view);
            view.findViewById(R.id.tile_frame).setClipToOutline(true);
            image = view.findViewById(R.id.tile_image);
            progress = view.findViewById(R.id.tile_progress);
            length = view.findViewById(R.id.tile_length);
            name = view.findViewById(R.id.tile_name);
            meta = view.findViewById(R.id.tile_meta);
            growOnFocus(view);
        }

        void bind(final Row row) {
            final Library.Video video = row.uri == null ? null : videoAt.get(row.uri);
            image.setBackground(null);
            image.setTag(null);
            name.setText(row.text);

            if (row.uri != null && !History.isNetworkUri(row.uri)) {
                frames.into(HomeActivity.this, image, row.uri, R.drawable.ic_movie_24dp);
            } else {
                final int accent = Accent.color(HomeActivity.this);
                image.setBackground(new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        new int[]{androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x59),
                                androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x1F)}));
                image.setScaleType(android.widget.ImageView.ScaleType.CENTER);
                image.setImageResource(R.drawable.ic_movie_24dp);
                image.setImageTintList(android.content.res.ColorStateList.valueOf(
                        AppTheme.isNight(HomeActivity.this) ? 0xB3FFFFFF : 0xB3000000));
                image.setAlpha(1f);
            }

            if (video != null && video.duration > 0) {
                length.setVisibility(View.VISIBLE);
                length.setText(Utils.formatMilis(video.duration));
            } else {
                length.setVisibility(View.GONE);
            }
            final String size = video != null && video.size > 0
                    ? com.brouken.player.home.Readable.size(HomeActivity.this, video.size) : null;
            final String where = video != null && query != null && !query.trim().isEmpty()
                    ? video.folderName : null;
            // a film played on this device says where it is and how much is left
            final String line = row.local ? row.meta
                    : where != null && size != null ? where + "  ·  " + size
                    : where != null ? where : size;
            meta.setVisibility(TextUtils.isEmpty(line) ? View.GONE : View.VISIBLE);
            meta.setText(line);
            progress.setVisibility(row.progress >= 0 ? View.VISIBLE : View.GONE);
            progress.setProgress(Math.max(0, row.progress));

            itemView.setOnClickListener(view -> startPlayer(row.uri, row.mediaType));
            if (row.local) {
                itemView.setOnLongClickListener(view -> {
                    showRecentMenu(row);
                    return true;
                });
            } else {
                itemView.setOnLongClickListener(null);
                itemView.setLongClickable(false);
            }
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
