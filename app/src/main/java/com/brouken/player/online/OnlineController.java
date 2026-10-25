package com.brouken.player.online;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.brouken.player.R;
import com.brouken.player.ReleaseName;
import com.brouken.player.SubtitleStorage;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OnlineController {

    private static final String PREF_KEY_IDENTITIES = "onlineIdentities";
    // keys, three or four a film; the least used films go first
    private static final int MAX_REMEMBERED = 240;

    private final Context context;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = com.brouken.player.Background.single("online");

    // kept so picking another subtitle for this file does not search again
    private Uri lastResultsUri;
    private List<Subtitles.Result> lastResults;

    public interface Host {
        @Nullable
        Uri mediaUri();

        @Nullable
        String mediaName();

        void loadSubtitle(Uri uri, @Nullable String label);

        // the automatic match reads these on the main thread, where the player lives

        @Nullable
        default String launchTitle() {
            return null;
        }

        @Nullable
        default String launchImdbId() {
            return null;
        }

        @Nullable
        default String fileName() {
            return null;
        }

        // outermost first; asked on the worker thread, as it may query the media store
        @NonNull
        default List<String> folders() {
            return new ArrayList<>();
        }

        // 0 while unknown or live
        default double durationSeconds() {
            return 0;
        }
    }

    public OnlineController(final Context context, final Host host) {
        this.context = context;
        this.host = host;
    }

    public boolean isConfigured() {
        return ApiKeys.hasTmdb(context);
    }

    public String language() {
        final String value = preferences().getString("subtitleLanguage", "en");
        return value == null || value.isEmpty() ? "en" : value;
    }

    public boolean overlayEnabled() {
        return preferences().getBoolean("overlayOnPause", true);
    }

    public int overlayDelaySeconds() {
        return preferences().getInt("overlayDelaySeconds", 3);
    }

    public boolean prefillSearch() {
        return preferences().getBoolean("prefillSearch", true);
    }

    public boolean skipEnabled() {
        return preferences().getBoolean("skipSegments", true);
    }

    public boolean identifiesAutomatically() {
        return !"manual".equals(preferences().getString("identifyMode", "auto"));
    }

    public boolean autoSearchSubtitles() {
        return preferences().getBoolean("subtitleAutoSearch", false);
    }

    public boolean titlesAreLinked() {
        return preferences().getBoolean("linkSubtitleAndInfo", true);
    }

    private static String cardKey(final String key) {
        return "card:" + key;
    }

    // unlinked, the card falls back to the shared title until it has its own
    @Nullable
    public Identity rememberedForCard(@Nullable final Uri uri) {
        if (uri == null) {
            return null;
        }
        if (titlesAreLinked()) {
            return remembered(uri);
        }
        try {
            final JSONObject all =
                    new JSONObject(preferences().getString(PREF_KEY_IDENTITIES, "{}"));
            Identity own = Identity.fromJson(all.optJSONObject(cardKey(uri.toString())));
            if (own == null) {
                // the same link with a new token
                own = Identity.fromJson(all.optJSONObject(cardKey(pathKey(uri))));
            }
            if (own != null) {
                return own;
            }
        } catch (Exception ignored) {
            // fall through to the shared one
        }
        return remembered(uri);
    }

    // saved even when linked, so toggling the setting keeps the choice
    public void rememberForCard(@Nullable final Uri uri, final Identity identity) {
        if (uri == null || identity == null) {
            return;
        }
        try {
            final JSONObject all =
                    new JSONObject(preferences().getString(PREF_KEY_IDENTITIES, "{}"));
            final JSONObject json = identity.toJson();
            putFresh(all, cardKey(uri.toString()), json);
            putFresh(all, cardKey(pathKey(uri)), json);
            trim(all);
            preferences().edit().putString(PREF_KEY_IDENTITIES, all.toString()).apply();
        } catch (Exception ignored) {
        }
        if (titlesAreLinked()) {
            remember(uri, identity);
        }
    }

    public void forgetCardTitle(@Nullable final Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            final JSONObject all =
                    new JSONObject(preferences().getString(PREF_KEY_IDENTITIES, "{}"));
            all.remove(cardKey(uri.toString()));
            all.remove(cardKey(pathKey(uri)));
            preferences().edit().putString(PREF_KEY_IDENTITIES, all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private SharedPreferences preferences() {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    @Nullable
    public Identity remembered(@Nullable final Uri uri) {
        if (uri == null) {
            return null;
        }
        try {
            final JSONObject all = new JSONObject(preferences().getString(PREF_KEY_IDENTITIES, "{}"));

            Identity found = Identity.fromJson(all.optJSONObject(uri.toString()));
            if (found == null) {
                // the same link with a new token
                found = Identity.fromJson(all.optJSONObject(pathKey(uri)));
            }
            if (found == null) {
                found = byMatchingPath(all, uri);
            }
            if (found == null) {
                final String name = cachedName(uri);
                if (name != null) {
                    found = Identity.fromJson(all.optJSONObject(nameKey(name)));
                }
            }

            if (found != null && !all.has(uri.toString())) {
                remember(uri, found);
            }
            return found;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private Identity byMatchingPath(final JSONObject all, final Uri uri) {
        final String wanted = pathKey(uri);
        final Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (key.startsWith("path:") || key.startsWith("name:")) {
                continue;
            }
            if (!key.startsWith("http")) {
                continue;
            }
            if (wanted.equals(pathKey(Uri.parse(key)))) {
                return Identity.fromJson(all.optJSONObject(key));
            }
        }
        return null;
    }

    private void remember(final Uri uri, final Identity identity) {
        try {
            final JSONObject all = new JSONObject(preferences().getString(PREF_KEY_IDENTITIES, "{}"));
            final JSONObject json = identity.toJson();
            dropOtherTokens(all, uri);
            putFresh(all, uri.toString(), json);
            putFresh(all, pathKey(uri), json);

            final String name = cachedName(uri);
            if (name != null) {
                putFresh(all, nameKey(name), json);
            }

            trim(all);
            preferences().edit().putString(PREF_KEY_IDENTITIES, all.toString()).apply();
        } catch (Exception e) {
        }
    }

    // to the end, so the cap drops the films used least lately
    private static void putFresh(final JSONObject all, final String key, final JSONObject value)
            throws org.json.JSONException {
        all.remove(key);
        all.put(key, value);
    }

    private static void trim(final JSONObject all) {
        while (all.length() > MAX_REMEMBERED) {
            final Iterator<String> keys = all.keys();
            if (!keys.hasNext()) break;
            all.remove(keys.next());
        }
    }

    // older tokens of the same link waste room and can bring back a replaced title
    private static void dropOtherTokens(final JSONObject all, final Uri uri) {
        final String self = uri.toString();
        if (!self.startsWith("http")) {
            return;
        }
        final String path = pathKey(uri);
        final List<String> stale = new ArrayList<>();
        final Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (key.startsWith("http") && !key.equals(self) && path.equals(pathKey(Uri.parse(key)))) {
                stale.add(key);
            }
        }
        for (final String key : stale) {
            all.remove(key);
        }
    }

    private boolean hasResultsFor(@Nullable final Uri uri) {
        return uri != null && lastResults != null && !lastResults.isEmpty()
                && uri.equals(lastResultsUri);
    }

    private void rememberResults(@Nullable final Uri uri,
                                 @Nullable final List<Subtitles.Result> results) {
        if (uri == null || results == null || results.isEmpty()) {
            return;
        }
        lastResultsUri = uri;
        lastResults = new ArrayList<>(results);
    }

    // call when the identity or the file changes
    public void forgetResults() {
        lastResultsUri = null;
        lastResults = null;
    }

    public void forget(@Nullable final Uri uri) {
        if (uri == null) {
            return;
        }
        if (uri.equals(lastResultsUri)) {
            forgetResults();
        }
        try {
            final JSONObject all = new JSONObject(preferences().getString(PREF_KEY_IDENTITIES, "{}"));
            all.remove(uri.toString());
            all.remove(pathKey(uri));
            dropOtherTokens(all, uri);
            final String name = cachedName(uri);
            if (name != null) {
                all.remove(nameKey(name));
            }
            preferences().edit().putString(PREF_KEY_IDENTITIES, all.toString()).apply();
        } catch (Exception e) {
        }
    }

    private static String pathKey(final Uri uri) {
        final String scheme = uri.getScheme();
        final String authority = uri.getAuthority();
        if (scheme == null || authority == null) {
            return "path:" + uri;
        }
        final String path = uri.getPath() == null ? "" : uri.getPath();
        return "path:" + scheme + "://" + authority + path;
    }

    @Nullable
    private String cachedName(final Uri uri) {
        final String local = host.mediaName();
        if (looksLikeAName(local)) {
            return local;
        }
        synchronized (resolvedNames) {
            final String cached = resolvedNames.get(uri.toString());
            return cached == null || cached.isEmpty() ? null : cached;
        }
    }

    private static String nameKey(final String name) {
        return "name:" + name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\.(mkv|mp4|avi|mov|m4v|ts|webm)$", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    public void searchSubtitles(final Activity activity, final boolean reIdentify) {
        if (!ApiKeys.hasTmdb(context)) {
            toast(R.string.online_needs_tmdb);
            return;
        }
        if (!ApiKeys.hasAnySubtitleSource(context)) {
            toast(R.string.online_needs_source);
            return;
        }

        final Uri uri = host.mediaUri();

        if (!reIdentify && hasResultsFor(uri)) {
            showResults(activity, lastResults);
            return;
        }

        final boolean ask = reIdentify || !autoSearchSubtitles();
        final Identity known = ask ? null : remembered(uri);
        if (known != null) {
            runSearch(activity, known);
            return;
        }

        identify(activity, reIdentify, identity -> {
            if (uri != null) {
                remember(uri, identity);
            }
            runSearch(activity, identity);
        });
    }

    public interface OnIdentified {
        void onIdentified(Identity identity);
    }

    public void identify(final Activity activity, final OnIdentified callback) {
        identify(activity, false, callback);
    }

    public void identify(final Activity activity, final boolean manual,
                         final OnIdentified callback) {
        // picked by hand, so the automatic match never checks it again
        final OnIdentified picked = identity -> {
            if (identity != null) {
                identity.chosenBy = Identity.PICKED;
            }
            callback.onIdentified(identity);
        };
        worker.execute(() -> {
            final String name = Readings.searchName(resolvedName(), host.launchTitle());
            main.post(() -> showIdentifyDialog(activity, name, manual, picked));
        });
    }

    // one at a time, or a slow lookup stacks an identical box under this one
    @Nullable
    private AlertDialog identifyDialog;

    private void showIdentifyDialog(final Activity activity, @Nullable final String name,
                                    final boolean manual, final OnIdentified callback) {
        if (activity.isFinishing() || activity.isDestroyed()
                || (identifyDialog != null && identifyDialog.isShowing())) {
            return;
        }
        final ReleaseName.Info parsed = ReleaseName.parse(name);

        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        input.setHint(R.string.online_search_hint);

        // not ReleaseName, which cuts site-like words: Edge.of.Tomorrow to "morrow"
        final NameReading.Parsed read = NameReading.parse(name);
        final String prefilled = !prefillSearch() ? ""
                : NameReading.titleOk(read) ? read.title
                : parsed.looksLikeTitle() ? parsed.searchQuery()
                : "";
        if (!prefilled.isEmpty()) {
            input.setText(prefilled);
            input.setSelection(input.getText().length());
        }

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.online_identify_title)
                .setView(inset(activity, input))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.online_clear, null)
                .setPositiveButton(R.string.online_search, (d, which) -> {
                    final String query = input.getText().toString().trim();
                    final boolean edited = !query.equalsIgnoreCase(prefilled.trim());
                    searchTmdb(activity, query,
                            manual || edited ? parsed.withoutEpisode() : parsed, callback);
                })
                .create();

        identifyDialog = dialog;
        // focus Search: a focused field makes a TV keyboard swallow every key
        com.brouken.player.Utils.showFocused(dialog, AlertDialog.BUTTON_POSITIVE);

        // only the Search key searches; a remote's Enter (IME_NULL) opens the keyboard
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                return false;
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            input.setText("");
            input.requestFocus();
        });
    }

    // no UI; gives up rather than guess
    public void identifySilently(@Nullable final Uri uri, final OnIdentified callback) {
        identifySilently(uri, callback, null);
    }

    // ifNotFound is posted to the main thread when no confident match is found
    public void identifySilently(@Nullable final Uri uri, final OnIdentified callback,
                                 @Nullable final Runnable ifNotFound) {
        if (!ApiKeys.hasTmdb(context)) {
            giveUp(ifNotFound);
            return;
        }
        final Clues clues = clues();
        worker.execute(() -> {
            final Identity identity = matchNow(uri, clues);
            if (identity == null) {
                giveUp(ifNotFound);
                return;
            }
            if (uri != null) {
                remember(uri, identity);
            }
            main.post(() -> callback.onIdentified(identity));
        });
    }

    // tryAgain when TMDB could not be asked or its details did not load
    public interface OnNotSure {
        void notSure(boolean tryAgain);
    }

    public void identifyAutomatically(@Nullable final Uri uri, final OnIdentified changed) {
        identifyAutomatically(uri, changed, null);
    }

    public void identifyAutomatically(@Nullable final Uri uri, final OnIdentified changed,
                                      @Nullable final OnNotSure notSure) {
        if (uri == null) {
            return;
        }
        if (!ApiKeys.hasTmdb(context)) {
            android.util.Log.i(com.brouken.player.Utils.TAG, "Automatic match: off, there is no TMDB key");
            return;
        }
        final Clues clues = clues();
        worker.execute(() -> {
            final Identity known = remembered(uri);
            if (known != null) {
                if (Identity.PICKED.equals(known.chosenBy) || Identity.AUTO.equals(known.chosenBy)) {
                    return;
                }
                if (!oldGuessGives(known)) {
                    known.chosenBy = Identity.PICKED;
                    remember(uri, known);
                    return;
                }
            }
            final AutoMatch.Result result = match(uri, clues);
            final Identity identity = result.match == null ? null : identityOf(result.match);
            if (result.match != null && identity == null) {
                android.util.Log.i(com.brouken.player.Utils.TAG,
                        "Automatic match: the details of " + result.match.title + " didn't load");
            }
            if (identity == null && known == null && notSure != null) {
                final boolean tryAgain = result.match != null
                        || result.reason.startsWith("couldn't ask TMDB");
                main.post(() -> notSure.notSure(tryAgain));
            }
            if (identity != null) {
                remember(uri, identity);
                if (known == null || known.tmdbId != identity.tmdbId
                        || !java.util.Objects.equals(known.season, identity.season)
                        || !java.util.Objects.equals(known.episode, identity.episode)) {
                    main.post(() -> changed.onIdentified(identity));
                }
            } else if (known != null && !result.reason.startsWith("couldn't ask TMDB")) {
                // not sure any more: nothing rather than a title that may be wrong
                forget(uri);
                main.post(() -> changed.onIdentified(null));
            }
        });
    }

    // what the player knows, read on the main thread
    private static final class Clues {
        @Nullable
        String launchTitle;
        @Nullable
        String launchImdb;
        @Nullable
        String fileName;
        List<String> folders;
        double seconds;
    }

    private Clues clues() {
        final Clues clues = new Clues();
        clues.launchTitle = host.launchTitle();
        clues.launchImdb = host.launchImdbId();
        clues.fileName = host.fileName();
        clues.seconds = host.durationSeconds();
        return clues;
    }

    @Nullable
    private AutoMatch autoMatch;
    private final AniList.Service aniList = new AniList.Service();

    private AutoMatch matcher() {
        if (autoMatch == null) {
            autoMatch = new AutoMatch(new MatchTmdb.Service(context), new MatchTmdb.DiskCache(context));
        }
        // the setting can change between films
        autoMatch.useAnime(AniList.wanted(context) ? aniList : null);
        return autoMatch;
    }

    @Nullable
    private Identity matchNow(@Nullable final Uri uri, final Clues clues) {
        final AutoMatch.Result result = match(uri, clues);
        return result.match == null ? null : identityOf(result.match);
    }

    private AutoMatch.Result match(@Nullable final Uri uri, final Clues clues) {
        if (clues.folders == null) {
            clues.folders = host.folders();
        }
        final String link = uri == null ? null : uri.toString();
        final List<NameReading.Reading> readings =
                Readings.of(link, clues.fileName, clues.folders, clues.launchTitle);
        final AutoMatch.LinkIds ids = Readings.ids(link, clues.launchImdb);
        final AutoMatch.Result result = matcher().lookup(readings, ids, clues.seconds,
                () -> uri == null ? null : serverName(uri), Readings.isWeb(link));
        android.util.Log.i(com.brouken.player.Utils.TAG, "Automatic match: " + (result.match != null
                ? result.match.title + " " + result.match.code() + " (tmdb " + result.match.tmdbId
                        + (result.viaAnime ? ", with AniList" : "") + ")"
                : "not sure, " + result.reason));
        return result;
    }

    @Nullable
    private Identity identityOf(final AutoMatch.Match match) {
        final Identity identity = Tmdb.identify(context,
                new Tmdb.Candidate(match.tmdbId, match.isSeries, match.title, null, null, null),
                match.season, match.episode);
        if (identity != null) {
            identity.chosenBy = Identity.AUTO;
        }
        return identity;
    }

    // an unmarked title counts as automatic only if this older guess still gives it
    private boolean oldGuessGives(final Identity known) {
        final ReleaseName.Info parsed = ReleaseName.parse(resolvedName());
        if (!parsed.looksLikeTitle()) {
            return false;
        }
        final List<Tmdb.Candidate> candidates =
                Tmdb.searchHard(context, parsed.searchQuery(), parsed.year);
        if (candidates.isEmpty()) {
            return false;
        }
        final Tmdb.Candidate top = candidates.get(0);
        if (top.id != known.tmdbId || top.isSeries != known.isSeries) {
            return false;
        }
        return !top.isSeries || (java.util.Objects.equals(parsed.season, known.season)
                && java.util.Objects.equals(parsed.episode, known.episode));
    }

    private void giveUp(@Nullable final Runnable ifNotFound) {
        if (ifNotFound != null) {
            main.post(ifNotFound);
        }
    }

    private void searchTmdb(final Activity activity, final String query,
                            final ReleaseName.Info parsed, final OnIdentified callback) {
        final ProgressDialog progress = progress(activity, R.string.online_identifying);

        worker.execute(() -> {
            final List<Tmdb.Candidate> candidates =
                    Tmdb.searchHard(context, query, parsed.year);
            main.post(() -> {
                dismiss(progress);
                if (candidates.isEmpty()) {
                    toast(R.string.online_no_matches);
                    showIdentifyDialog(activity, query, true, callback);
                    return;
                }
                chooseCandidate(activity, candidates, parsed, callback);
            });
        });
    }

    // each step keeps the list before it, so going back does not fetch again
    private void chooseCandidate(final Activity activity, final List<Tmdb.Candidate> candidates,
                                 final ReleaseName.Info parsed, final OnIdentified callback) {
        PosterPicker.show(activity, context.getString(R.string.online_identify_title), candidates,
                index -> {
                    final Tmdb.Candidate candidate = candidates.get(index);
                    if (candidate.isSeries) {
                        chooseSeason(activity, candidates, candidate, parsed, callback);
                    } else {
                        resolve(activity, candidate, null, null, callback);
                    }
                });
    }

    private void chooseSeason(final Activity activity, final List<Tmdb.Candidate> candidates,
                              final Tmdb.Candidate candidate,
                              final ReleaseName.Info parsed, final OnIdentified callback) {
        if (parsed.season != null && parsed.episode != null) {
            resolve(activity, candidate, parsed.season, parsed.episode, callback);
            return;
        }

        final ProgressDialog progress = progress(activity, R.string.online_identifying);
        worker.execute(() -> {
            final List<Tmdb.Season> seasons = Tmdb.seasons(context, candidate.id);
            main.post(() -> {
                dismiss(progress);
                if (seasons.isEmpty()) {
                    askSeasonEpisode(activity, candidate, parsed, callback);
                    return;
                }
                showSeasons(activity, candidates, candidate, seasons, parsed, callback);
            });
        });
    }

    private void showSeasons(final Activity activity, final List<Tmdb.Candidate> candidates,
                             final Tmdb.Candidate candidate, final List<Tmdb.Season> seasons,
                             final ReleaseName.Info parsed, final OnIdentified callback) {
        PosterPicker.show(activity, candidate.title, seasons,
                index -> chooseEpisode(activity, candidates, candidate, seasons,
                        seasons.get(index).number, parsed, callback),
                candidates.size() > 1
                        ? () -> chooseCandidate(activity, candidates, parsed, callback)
                        : null);
    }

    private void chooseEpisode(final Activity activity, final List<Tmdb.Candidate> candidates,
                               final Tmdb.Candidate candidate, final List<Tmdb.Season> seasons,
                               final int season, final ReleaseName.Info parsed,
                               final OnIdentified callback) {
        final ProgressDialog progress = progress(activity, R.string.online_identifying);
        worker.execute(() -> {
            final List<Tmdb.Episode> episodes = Tmdb.episodes(context, candidate.id, season);
            main.post(() -> {
                dismiss(progress);
                if (episodes.isEmpty()) {
                    // a season with no episode list is still searchable as a whole
                    resolve(activity, candidate, season, null, callback);
                    return;
                }
                PosterPicker.show(activity, candidate.title + "  ·  S" + (season < 10 ? "0" : "") + season,
                        episodes, index ->
                                resolve(activity, candidate, season, episodes.get(index).number, callback),
                        () -> showSeasons(activity, candidates, candidate, seasons, parsed, callback));
            });
        });
    }

    private void askSeasonEpisode(final Activity activity, final Tmdb.Candidate candidate,
                                  final ReleaseName.Info parsed, final OnIdentified callback) {
        final EditText season = numberField(activity, R.string.online_season, parsed.season);
        final EditText episode = numberField(activity, R.string.online_episode, parsed.episode);

        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        final int margin = dp(activity, 20);
        layout.setPadding(margin, margin / 2, margin, 0);
        layout.addView(season);
        layout.addView(episode);

        com.brouken.player.PlayerStyle.dress(new AlertDialog.Builder(activity)
                .setTitle(candidate.title)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.online_search, (dialog, which) ->
                        resolve(activity, candidate,
                                parseInt(season.getText().toString()),
                                parseInt(episode.getText().toString()),
                                callback))
                .show());
    }

    private void resolve(final Activity activity, final Tmdb.Candidate candidate,
                         @Nullable final Integer season, @Nullable final Integer episode,
                         final OnIdentified callback) {
        final ProgressDialog progress = progress(activity, R.string.online_identifying);

        worker.execute(() -> {
            final Identity identity = Tmdb.identify(context, candidate, season, episode);
            main.post(() -> {
                dismiss(progress);
                if (identity == null) {
                    toast(R.string.online_no_matches);
                    return;
                }
                callback.onIdentified(identity);
            });
        });
    }

    private void runSearch(final Activity activity, final Identity identity) {
        final ProgressDialog progress = progress(activity, R.string.online_searching);

        worker.execute(() -> {
            final String fileName = resolvedName();
            final List<Subtitles.Result> results = Subtitles.search(context,
                    new Subtitles.Target(identity, language(), fileName, null));

            main.post(() -> {
                dismiss(progress);
                if (results.isEmpty()) {
                    toast(R.string.online_no_subtitles);
                    return;
                }
                rememberResults(host.mediaUri(), results);
                showResults(activity, results);
            });
        });
    }

    // the first row reopens the title search
    private void showResults(final Activity activity, final List<Subtitles.Result> results) {
        final List<ListPicker.Row> rows = new ArrayList<>();
        rows.add(new SearchAgainRow(context.getString(R.string.online_search_again),
                context.getString(R.string.online_search_again_detail)));
        rows.addAll(results);

        ListPicker.show(activity, results.size() + " subtitles", rows,
                index -> {
                    if (index == 0) {
                        searchSubtitles(activity, true);
                        return;
                    }
                    downloadAndLoad(activity, results.get(index - 1));
                });
    }

    private static final class SearchAgainRow implements ListPicker.Row {
        private final String title;
        private final String detail;

        SearchAgainRow(final String title, final String detail) {
            this.title = title;
            this.detail = detail;
        }

        @NonNull
        @Override
        public String title() {
            return title;
        }

        @Nullable
        @Override
        public String detail() {
            return detail;
        }

        @Override
        public int icon() {
            return R.drawable.ic_manage_search_24dp;
        }
    }

    private void downloadAndLoad(final Activity activity, final Subtitles.Result result) {
        final ProgressDialog progress = progress(activity, R.string.online_downloading);

        worker.execute(() -> {
            final byte[] bytes = Subtitles.download(context, result);
            final String stem = result.release != null && !result.release.trim().isEmpty()
                    ? result.release.trim()
                    : resolvedName();

            final String refusal = Subtitles.lastDownloadRefusal;

            main.post(() -> {
                dismiss(progress);
                if (bytes == null || bytes.length == 0) {
                    if (refusal != null) {
                        Toast.makeText(context, refusal.isEmpty()
                                        ? context.getString(R.string.online_download_limit,
                                                result.source.label)
                                        : context.getString(R.string.online_download_limit_until,
                                                result.source.label, refusal),
                                Toast.LENGTH_LONG).show();
                    } else {
                        toast(R.string.online_download_failed);
                    }
                    return;
                }

                final String name =
                        SubtitleStorage.fileName(stem, result.language, result.extension);
                final SubtitleStorage.Result saved = SubtitleStorage.save(context, name, bytes);

                if (!saved.saved()) {
                    toast(R.string.online_download_failed);
                    return;
                }

                // a MediaStore uri may not give its display name back, so pass it
                host.loadSubtitle(saved.uri, stem);
                Toast.makeText(context,
                        saved.location == null
                                ? context.getString(R.string.online_loaded)
                                : context.getString(R.string.online_saved_to, saved.location),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private final java.util.Map<String, String> resolvedNames = new java.util.HashMap<>();

    public interface OnName {
        void onName(@Nullable String name);
    }

    public void resolveNameAsync(final OnName callback) {
        worker.execute(() -> {
            final String name = resolvedName();
            main.post(() -> callback.onName(name));
        });
    }

    @Nullable
    String resolvedName() {
        final String local = host.mediaName();
        final Uri uri = host.mediaUri();
        if (uri == null || looksLikeAName(local)) {
            return local;
        }

        final String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return local;
        }

        final String key = uri.toString();
        synchronized (resolvedNames) {
            final String cached = resolvedNames.get(key);
            if (cached != null) {
                return cached.isEmpty() ? local : cached;
            }
        }

        final String fromServer = askServer(key);
        synchronized (resolvedNames) {
            // empty means already asked with no answer
            resolvedNames.put(key, fromServer == null ? "" : fromServer);
        }

        if (fromServer != null) {
            final Identity known = remembered(uri);
            if (known != null) {
                remember(uri, known);
            }
        }
        return fromServer == null ? local : fromServer;
    }

    @Nullable
    private String serverName(final Uri uri) {
        final String key = uri.toString();
        synchronized (resolvedNames) {
            final String cached = resolvedNames.get(key);
            if (cached != null) {
                return cached.isEmpty() ? null : cached;
            }
        }
        final String fromServer = askServer(key);
        synchronized (resolvedNames) {
            resolvedNames.put(key, fromServer == null ? "" : fromServer);
        }
        return fromServer;
    }

    // some servers call every file "download"; such a name counts as no answer
    @Nullable
    private static String askServer(final String link) {
        final String answer = Http.serverFileName(link);
        return answer != null && NameReading.titleOk(NameReading.parse(answer)) ? answer : null;
    }

    private static boolean looksLikeAName(@Nullable final String name) {
        if (name == null || name.length() < 5) {
            return false;
        }
        final int dot = name.lastIndexOf('.');
        final String stem = dot > 0 ? name.substring(0, dot) : name;
        if (dot <= 0 || name.length() - dot > 5) {
            return false;
        }
        // UUIDs and hashes are hex and dashes, not titles
        return !stem.matches("(?i)[0-9a-f-]{8,}") && stem.matches(".*[A-Za-z].*");
    }

    private EditText numberField(final Activity activity, final int hint,
                                 @Nullable final Integer value) {
        final EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setHint(hint);
        field.setSingleLine(true);
        if (value != null) {
            field.setText(String.valueOf(value));
        }
        return field;
    }

    private FrameLayout inset(final Activity activity, final android.view.View view) {
        final FrameLayout container = new FrameLayout(activity);
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(activity, 20);
        params.rightMargin = params.leftMargin;
        container.addView(view, params);
        return container;
    }

    private static int dp(final Context context, final int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    @Nullable
    private static Integer parseInt(@Nullable final String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ProgressDialog progress(final Activity activity, final int message) {
        final ProgressDialog dialog = new ProgressDialog(activity);
        dialog.setMessage(context.getString(message));
        dialog.setIndeterminate(true);
        dialog.setCancelable(true);
        dialog.show();
        com.brouken.player.PlayerStyle.dress(dialog);
        return dialog;
    }

    private void dismiss(@Nullable final ProgressDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (IllegalArgumentException e) {
                // the activity went away while the request was in flight
            }
        }
    }

    private void toast(final int message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
