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

/**
 * Where the application opens: what is on the device, as folders and files.
 *
 * <p>This is a separate activity from the player rather than a screen inside
 * it, and that is the whole of the back behaviour. A file opened from here
 * puts the player on top of this screen, so Back comes back here; a file sent
 * by another application starts the player in that application's task, so Back
 * returns there. Neither case needs to be detected or remembered — the task
 * stack already knows, and anything this screen added on top of it would be a
 * second answer that could disagree with the first.
 *
 * <p>One list for three things: the folders, the files in one of them, and
 * what a search matched. A screen each would mean three sets of focus rules to
 * get wrong with a remote.
 */
public class HomeActivity extends AppCompatActivity {

    /** What {@code startOn} is set to when this screen is wanted. See settings. */
    public static final String START_ON_HOME = "home";

    /**
     * Started to choose a file for somebody else rather than to play one.
     *
     * <p>This is what the player's Open button reaches: the same folder list,
     * handing an address back instead of starting a film. One browser rather
     * than two that drift apart — and, unlike the system picker, one that a
     * remote can already drive.
     */
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
    /** True between asking for storage and being answered. See maybeOfferLastVideo. */
    private boolean permissionPending;
    private boolean offeredLastVideo;
    /** The folder whose star should take focus once the list is rebuilt. */
    @Nullable
    private String focusPinFor;
    /** The accent this screen was themed with, so a change to it can be noticed. */
    private String appliedAccent;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        /*
         * A television is dark, whatever the box says it prefers.
         *
         * The player does this too. It matters more here: this screen follows
         * the system between light and dark like the settings screen, and a
         * white folder list is wrong on a television in a way it is not on a
         * phone. Before super, which is where the theme is resolved.
         */
        if (Utils.isTvBox(this)) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        }

        super.onCreate(savedInstanceState);

        // After super, for the reason SettingsActivity gives: AppCompat
        // re-applies the manifest theme in its own onCreate.
        Accent.apply(this);
        appliedAccent = Accent.stored(this);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        picking = Intent.ACTION_PICK.equals(getIntent().getAction());

        /*
         * Straight past this screen when that is what was asked for.
         *
         * Before setContentView, so nothing of the home screen is ever drawn on
         * the way to the player -- the alternative is a flash of a folder list
         * in front of somebody who has said they do not want one.
         *
         * Never while picking: that is somebody asking for this screen by name,
         * whatever they have set as the place to start.
         */
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
            /*
             * Choosing a file on this device is the whole of the question being
             * asked, so an address box and a settings screen are two ways to
             * lose the thread. The list, a search and a sort are all that is
             * left, and Back answers "none of these".
             */
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
        // The remote's centre key on the field means "done typing", not "type a
        // newline": put the keyboard away and leave the results up.
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
        // Rescanned on every visit rather than once: coming back from the
        // player is exactly when a file may have been deleted, and a list that
        // offers something that is no longer there is worse than a short wait.
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
        /*
         * A new accent needs this screen built again.
         *
         * The colour is a theme overlay applied as the screen is created, so
         * views already inflated keep the old one -- the folder icons, the
         * section headings and the search underline all stayed orange after
         * the colour was changed, until the application was closed and opened
         * again. The player and the settings screen already did this; the home
         * screen was new and had not been told.
         */
        if (appliedAccent != null && !appliedAccent.equals(Accent.stored(this))) {
            recreate();
            return;
        }
        maybeOfferLastVideo();
    }

    @Override
    protected void onDestroy() {
        // The scanning thread has nothing left to report to, and the frames
        // are pictures for a list that is going away.
        worker.shutdownNow();
        frames.close();
        super.onDestroy();
    }

    // -------------------------------------------------------------- opening

    /**
     * Where the application opens, as the setting has it.
     *
     * <p>The home screen unless someone has said otherwise, including on an
     * upgrade from an earlier version — the release notes say so, and one
     * setting puts it back.
     */
    private boolean wantsHome() {
        return START_ON_HOME.equals(preferences.getString("startOn", START_ON_HOME));
    }

    /**
     * The offer to carry on with the last file, over this screen.
     *
     * <p>It survived the home screen arriving because it answers a different
     * question: the folder list says what there is, and this says what you were
     * in the middle of. Once per visit to this screen, never twice, and never
     * at all with the setting off.
     */
    private void maybeOfferLastVideo() {
        // Never over the permission request: two dialogs deep is a poor way to
        // meet an application, and the offer keeps until the system one has
        // been answered.
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
        /*
         * Nothing is offered that cannot be named.
         *
         * A media store address whose file has been deleted resolves to nothing,
         * and what is left to show is the last part of the address -- a row of
         * digits. "Play last video? 1000161621" is not a question anybody can
         * answer, and the answer would fail anyway.
         */
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

    /**
     * Hand a file to the player.
     *
     * <p>A null address opens the player with nothing, which is what "start on
     * the last video" means: the player has always worked out for itself what
     * that was.
     *
     * <p>CLEAR_TOP with SINGLE_TOP so that a player already in this task — one
     * left in a corner by picture-in-picture, say — is handed the new file
     * rather than stacked under a second copy of itself.
     */
    /**
     * The folder a film was opened from, so the player can offer the next one.
     *
     * <p>The folder rather than the list of files in it: a folder of five
     * hundred holiday clips would be several hundred kilobytes of addresses,
     * and an intent that large is refused by the system outright. The player
     * asks the media store the same question this screen asked and sorts the
     * answer the same way, which costs one query and cannot go stale.
     */
    static final String EXTRA_FOLDER = "com.brouken.player.FOLDER";

    void startPlayer(@Nullable final Uri uri, @Nullable final String type) {
        /*
         * Handed back rather than played, when somebody else asked the question.
         *
         * The player's Open button starts this screen for an answer, and the
         * answer is an address. Playing it here as well would open the film
         * twice -- once on top of the player that asked, and once when the
         * player got the result.
         */
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
        if (uri != null) {
            intent.setAction(Intent.ACTION_VIEW);
            if (type == null || type.isEmpty()) {
                intent.setData(uri);
            } else {
                intent.setDataAndType(uri, type);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            /*
             * Which list this came out of, so next and previous mean the list
             * you were looking at rather than whatever alphabetical order a
             * scan of the folder happens to produce.
             *
             * Only where the film belongs to a folder. A row in the Recent
             * section may be an address on the internet and a search result
             * belongs to no one folder, so neither carries one and the player
             * hides the buttons.
             */
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
        // The only thing on screen that does anything, so a remote must land on
        // it: with the list gone there is nothing else for focus to be in.
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
                /*
                 * A remote needs something focused or every arrow press goes
                 * nowhere — see Panels, where the same thing had to be learned
                 * about the track pickers. Only on the first list, and never
                 * while the search field is up, because taking focus off a
                 * field somebody is typing into is worse than not having it.
                 */
                if (first && query == null) {
                    Panels.focusFirstRow(list);
                }
            });
        }));
    }

    // ------------------------------------------------------------- drawing

    private void render() {
        rows.clear();

        /*
         * An open but empty search field is not a search for nothing.
         *
         * Pressing the search button before typing would otherwise blank the
         * screen and say "nothing matched that", which is both alarming and
         * untrue. The list underneath stays until there is something to match.
         */
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
        // No history while picking: those are films, and several of them are
        // addresses on the internet. The question was which file on this device.
        final List<History.Entry> recent = picking
                ? java.util.Collections.<History.Entry>emptyList()
                : History.load(preferences);
        int shown = 0;
        for (final History.Entry entry : recent) {
            if (!entry.played) {
                continue;
            }
            if (shown == 0) {
                rows.add(Row.header(getString(R.string.home_section_recent)));
            }
            rows.add(Row.video(entry.uri, entry.name, null, entry.type));
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
            // The folder as well as the length: two files of the same name in
            // two folders are otherwise one row repeated.
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

    /**
     * Back goes up a level before it leaves.
     *
     * <p>Registered rather than overridden. This application asks for
     * {@code enableOnBackInvokedCallback}, and under that the platform stops
     * calling {@code onBackPressed} at all from Android 13 on: the override
     * compiled, read correctly, and was simply never run, so Back inside a
     * folder closed the whole application instead of returning to the folder
     * list. The player hit the same thing and answers it with a callback of its
     * own — see createOnBackInvokedCallback there. This is the AndroidX form of
     * the same fix, which works on every version this app supports.
     */
    private void registerBackHandling() {
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (query != null || folderId != null) {
                            goUp();
                            return;
                        }
                        // Nothing left to go up to: leave, the way Back does.
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                });
    }

    private void togglePin(final Library.Folder folder) {
        final boolean nowPinned = Pinned.toggle(preferences, folder.id);
        Toast.makeText(this, nowPinned ? R.string.home_pinned : R.string.home_unpinned,
                Toast.LENGTH_SHORT).show();
        /*
         * Follow the folder to wherever it has just moved.
         *
         * Pinning rebuilds the list, which destroys the view the remote was on;
         * the framework then hands focus to whatever is nearest, which turned
         * out to be the search button at the top of the screen. Pinning three
         * folders meant travelling back down the list three times.
         */
        focusPinFor = folder.id;
        render();
        /*
         * And bring it into view, because it has just moved.
         *
         * Favouriting sends a folder to the top of the list and un-favouriting
         * sends it back down among the rest, which is frequently off the screen
         * -- and a row that is not on screen has no view, so the focus above
         * has nothing to land on. The remote was left in the toolbar, and the
         * next press opened the search field instead of starring the next
         * folder. Scrolling to it also happens to be the only way to see what
         * the star just did.
         */
        for (int index = 0; index < rows.size(); index++) {
            final Row row = rows.get(index);
            if (row.folder != null && folder.id.equals(row.folder.id)) {
                bringIntoView(index);
                break;
            }
        }
    }

    /**
     * Put a folder row on screen, under its heading where both fit.
     *
     * <p>The heading is what says the star worked. Without it a folder that has
     * just moved to the top of the list looks like a list that has merely
     * scrolled, and there is nothing on screen to say which section it landed
     * in. So the heading above it is scrolled to instead of the row itself,
     * while there is room on the screen for everything between them -- and
     * where there is not, the row wins: it is the one that has to be visible,
     * because a row with no view on screen has nothing to take the focus and
     * the remote would be left stranded in the toolbar.
     */
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
        // A row is about as tall as the tallest one on screen: headings are
        // shorter than folders, and taking the smaller of the two would claim
        // more rows fit than do.
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
        // Only on a device with a keyboard worth showing: on a television the
        // remote drives the field and a keyboard over the list is in the way.
        if (!Utils.isTvBox(this)) {
            final InputMethodManager manager =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
            }
        }
        render();
    }

    /**
     * Put the search away.
     *
     * <p>The order of the last two lines is the whole of this method.
     * Emptying the field runs the text watcher, and the watcher sets
     * {@code query} to the empty string — so clearing it first and setting
     * {@code query} to null afterwards is the only order that leaves it null.
     *
     * <p>The other way round, {@code query} was never null again after the
     * first search, and since opening a folder closes the search, Back inside
     * a folder took the "close the search" branch for ever and did nothing at
     * all. Nothing about the screen looked wrong; Back simply stopped working.
     */
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

    /**
     * The orders that make sense for what is on screen.
     *
     * <p>A list of folders cannot be sorted by length, and a list of files
     * cannot be sorted by how many files it holds, so the menu is built from
     * whichever list is up rather than showing both and greying half of them.
     */
    /**
     * One dialog, two questions: what to sort by, and which way round.
     *
     * <p>Built by hand rather than with {@code setSingleChoiceItems}, which
     * offers exactly one list and no room for a second. Two radio groups under
     * their own headings is what a person expects, and a remote walks straight
     * down through both of them.
     *
     * <p>The orders offered are the ones that make sense for what is on screen:
     * a list of folders cannot be sorted by length, and a list of files cannot
     * be sorted by how many files it holds, so the menu is built from whichever
     * list is up rather than showing both and greying half of them out.
     */
    private void showSortMenu() {
        // What is on screen, by the same rule render() uses: an open but empty
        // search field is still the folder list.
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

        /*
         * The two directions are named after the column, so they have to be
         * renamed the moment the column changes -- "A to Z" is nonsense under
         * Size, and leaving it there would be worse than not offering the
         * choice at all.
         */
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

        private Row(final int type, final String text, @Nullable final String meta,
                    @Nullable final Library.Folder folder, final boolean pinned,
                    @Nullable final Uri uri, @Nullable final String mediaType) {
            this.type = type;
            this.text = text;
            this.meta = meta;
            this.folder = folder;
            this.pinned = pinned;
            this.uri = uri;
            this.mediaType = mediaType;
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
        /** The part that opens the folder, beside the star rather than around it. */
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
                    /*
                     * Amber, not the accent.
                     *
                     * A star means the same thing as the star on a rating, and
                     * it reads as one because it is that colour -- the info
                     * card's rating is fixed amber for exactly this reason. On
                     * the slate or violet accent an accent-coloured star stops
                     * looking like a favourite and starts looking like a
                     * selection.
                     */
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
                // After layout: a view that is not yet placed cannot take focus,
                // and asking here would quietly do nothing.
                pin.post(pin::requestFocus);
            }
            // Said out loud, because a folder row is a name and two numbers and
            // a screen reader would otherwise read the numbers as a sentence.
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
        }

        void bind(final Row row) {
            name.setText(row.text);
            /*
             * A frame for a file on this device, the plain icon for anything
             * else. A row in the Recent section can be an address on the
             * internet, and fetching a film over a connection to look at one
             * picture of it is not a trade worth making.
             */
            if (icon != null) {
                if (row.uri != null && !History.isNetworkUri(row.uri)) {
                    frames.into(HomeActivity.this, icon, row.uri, R.drawable.ic_movie_24dp);
                } else {
                    icon.setTag(null);
                    com.brouken.player.home.Frames.placeholder(
                            HomeActivity.this, icon, R.drawable.ic_movie_24dp);
                }
            }
            if (TextUtils.isEmpty(row.meta)) {
                meta.setVisibility(View.GONE);
            } else {
                meta.setVisibility(View.VISIBLE);
                meta.setText(row.meta);
            }
            itemView.setOnClickListener(view -> startPlayer(row.uri, row.mediaType));
            // The name and the numbers as one sentence, so a screen reader does
            // not read "one minute three, one hundred and sixty six megabytes"
            // as though it were part of the title.
            itemView.setContentDescription(TextUtils.isEmpty(row.meta)
                    ? row.text : row.text + ", " + row.meta);
        }
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
