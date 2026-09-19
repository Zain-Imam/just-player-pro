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
    private static final int MAX_REMEMBERED = 80;

    private final Context context;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Named, and unable to take the app down with it: see Background.
    private final ExecutorService worker = com.brouken.player.Background.single("online");

    /*
     * The list the last search returned, kept for the file it was fetched for.
     *
     * Downloading the wrong subtitle is normal -- several releases of the same
     * film sit in the list and only the name tells them apart, and the name is
     * often wrong. Getting back to the list meant identifying the film again
     * and searching every source again: two dialogs and a network round trip to
     * undo one tap. It is held for as long as the file is open, so the second
     * choice costs what the first one did.
     *
     * Only for this file, and only until the film is identified differently --
     * see forget(), where a new identity throws the list away, because results
     * for the wrong film are worse than no results.
     */
    private Uri lastResultsUri;
    private List<Subtitles.Result> lastResults;

    public interface Host {
        @Nullable
        Uri mediaUri();

        @Nullable
        String mediaName();

        /**
         * @param label what the subtitle should be called in the picker, which
         *              the download already knows and the file may not. See the
         *              note where this is called.
         */
        void loadSubtitle(Uri uri, @Nullable String label);
    }

    public OnlineController(final Context context, final Host host) {
        this.context = context;
        this.host = host;
    }

    // ------------------------------------------------------------- settings

    public boolean isConfigured() {
        return ApiKeys.hasTmdb(context);
    }

    public String language() {
        final String value = preferences().getString("subtitleLanguage", "en");
        return value == null || value.isEmpty() ? "en" : value;
    }

    public boolean overlayEnabled() {
        return preferences().getBoolean("overlayOnPause", false);
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

    // Whether a file is looked up as it starts, or only when asked. What the
    // info card, the skip markers and the titles in history all hang off.
    public boolean identifiesAutomatically() {
        return !"manual".equals(preferences().getString("identifyMode", "auto"));
    }

    // Identifying a file and searching it for subtitles used to be one action,
    // so the card could not appear without a subtitle search and a search began
    // without being asked for. They are separate now.
    public boolean autoSearchSubtitles() {
        return preferences().getBoolean("subtitleAutoSearch", false);
    }

    /**
     * Whether the info card and the subtitle search share one title.
     *
     * On, which is the default, they are the same thing: correcting the title
     * to find subtitles also corrects what the card shows, which is what you
     * want nearly always — they are both answers to "what is this?".
     *
     * Off, they are independent. That is for the case where the two questions
     * genuinely differ: subtitles for the film that is playing, while the card
     * shows something else entirely.
     */
    public boolean titlesAreLinked() {
        return preferences().getBoolean("linkSubtitleAndInfo", true);
    }

    /** Where a card-only title is kept, when the two are not linked. */
    private static String cardKey(final String key) {
        return "card:" + key;
    }

    /**
     * The title the info card should show.
     *
     * Linked, that is simply the one title there is. Unlinked, it is the card's
     * own if one has been chosen, and otherwise the shared one — so turning the
     * setting off does not blank a card that was already right.
     */
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
            final Identity own = Identity.fromJson(all.optJSONObject(cardKey(uri.toString())));
            if (own != null) {
                return own;
            }
        } catch (Exception ignored) {
            // Fall through to the shared one.
        }
        return remembered(uri);
    }

    /**
     * Remember a title the person chose for the card.
     *
     * Kept whichever way the setting is pointing, so that turning linking off
     * and on again does not lose a choice. Linked, it is written as the shared
     * title too, which is what makes correcting the card correct the subtitle
     * search with it.
     */
    public void rememberForCard(@Nullable final Uri uri, final Identity identity) {
        if (uri == null || identity == null) {
            return;
        }
        try {
            final JSONObject all =
                    new JSONObject(preferences().getString(PREF_KEY_IDENTITIES, "{}"));
            all.put(cardKey(uri.toString()), identity.toJson());
            preferences().edit().putString(PREF_KEY_IDENTITIES, all.toString()).apply();
        } catch (Exception ignored) {
            // One extra dialog next time, nothing worse.
        }
        if (titlesAreLinked()) {
            remember(uri, identity);
        }
    }

    /** Forget a card-only title, so the shared one applies again. */
    public void forgetCardTitle(@Nullable final Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            final JSONObject all =
                    new JSONObject(preferences().getString(PREF_KEY_IDENTITIES, "{}"));
            all.remove(cardKey(uri.toString()));
            preferences().edit().putString(PREF_KEY_IDENTITIES, all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private SharedPreferences preferences() {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }
    // ------------------------------------------------------------- identity

    @Nullable
    public Identity remembered(@Nullable final Uri uri) {
        if (uri == null) {
            return null;
        }
        try {
            final JSONObject all = new JSONObject(preferences().getString(PREF_KEY_IDENTITIES, "{}"));

            Identity found = Identity.fromJson(all.optJSONObject(uri.toString()));
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
                // Learned under one key, remembered under all of them.
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
            all.put(uri.toString(), identity.toJson());
            all.put(pathKey(uri), identity.toJson());

            final String name = cachedName(uri);
            if (name != null) {
                all.put(nameKey(name), identity.toJson());
            }

            // Bounded, like every other list the app keeps. A miss costs one
            // dialog, so dropping an arbitrary entry over the cap is fine.
            while (all.length() > MAX_REMEMBERED) {
                final Iterator<String> keys = all.keys();
                if (!keys.hasNext()) break;
                all.remove(keys.next());
            }

            preferences().edit().putString(PREF_KEY_IDENTITIES, all.toString()).apply();
        } catch (Exception e) {
            // Losing the memory costs one extra dialog next time, nothing more.
        }
    }

    // ------------------------------------------------- the last result list

    /** Whether the results held are the results for this file. */
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
        // A copy: what is handed back must not change underneath the list.
        lastResults = new ArrayList<>(results);
    }

    /**
     * Throw the held list away.
     *
     * Called wherever the film stops being the film those results were for --
     * a different identity, or a different file.
     */
    public void forgetResults() {
        lastResultsUri = null;
        lastResults = null;
    }

    public void forget(@Nullable final Uri uri) {
        if (uri == null) {
            return;
        }
        // The identity is about to change, so the results no longer belong to
        // what is playing.
        if (uri.equals(lastResultsUri)) {
            forgetResults();
        }
        try {
            final JSONObject all = new JSONObject(preferences().getString(PREF_KEY_IDENTITIES, "{}"));
            all.remove(uri.toString());
            all.remove(pathKey(uri));
            final String name = cachedName(uri);
            if (name != null) {
                all.remove(nameKey(name));
            }
            preferences().edit().putString(PREF_KEY_IDENTITIES, all.toString()).apply();
        } catch (Exception e) {
            // Nothing to undo.
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

    // ---------------------------------------------------------------- entry

    public void searchSubtitles(final Activity activity, final boolean reIdentify) {
        if (!ApiKeys.hasTmdb(context)) {
            toast(R.string.online_needs_tmdb);
            return;
        }
        if (!ApiKeys.hasAnySubtitleSource(context)) {
            toast(R.string.online_needs_source);
            return;
        }

        /*
         * Asking first, when the setting says not to search on its own.
         *
         * Turning "Search subtitles automatically" off says: do not go and find
         * subtitles without me. Pressing the button then went straight to a
         * search anyway, because the film had already been identified as it
         * opened and the answer was sitting there — so there was no way to say
         * which film you wanted subtitles for. With the setting off, the box
         * comes up every time.
         */
        final Uri uri = host.mediaUri();

        /*
         * The list from last time, if it is still the list for this file.
         *
         * Straight to the results, because that is what the button was pressed
         * for -- and the row at the top of them still reopens the question, so
         * nothing is lost by not asking it first. "Change selection" is the
         * other way in, and it comes through here with reIdentify set.
         */
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

    // ------------------------------------------------------------- identify

    public interface OnIdentified {
        void onIdentified(Identity identity);
    }

    public void identify(final Activity activity, final OnIdentified callback) {
        identify(activity, false, callback);
    }

    public void identify(final Activity activity, final boolean manual,
                         final OnIdentified callback) {
        worker.execute(() -> {
            final String name = resolvedName();
            main.post(() -> showIdentifyDialog(activity, name, manual, callback));
        });
    }

    private void showIdentifyDialog(final Activity activity, @Nullable final String name,
                                    final boolean manual, final OnIdentified callback) {
        final ReleaseName.Info parsed = ReleaseName.parse(name);

        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setHint(R.string.online_search_hint);

        final String prefilled = prefillSearch() && parsed.looksLikeTitle()
                ? parsed.searchQuery()
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

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            input.setText("");
            input.requestFocus();
        });
    }

    /*
     * Work out what a file is without asking anybody anything.
     *
     * No dialog, no progress, no toast: this runs as a file starts, and the
     * answer is what fills the info card, the skip markers and the title in the
     * history list. Anything uncertain is dropped rather than guessed at — a
     * name that does not parse as a title, a search that returns nothing, or a
     * series whose episode is not in the file name. Getting it wrong silently
     * is worse than leaving the card empty, and "Change title…" is there for
     * the ones it declines to answer.
     */
    public void identifySilently(@Nullable final Uri uri, final OnIdentified callback) {
        identifySilently(uri, callback, null);
    }

    /*
     * The same, but it always answers.
     *
     * Every way this can fail — no key, a name that is not a title, a search
     * that found nothing, a series whose episode is not in the file name — used
     * to be a quiet return. That is right for something running on its own as a
     * file opens, and wrong for somebody who pressed a button and is watching
     * the word "Identifying" not change. Anything that asked out loud passes a
     * second answer for the case where there is nothing to show, and gets the
     * search box instead of silence.
     */
    public void identifySilently(@Nullable final Uri uri, final OnIdentified callback,
                                 @Nullable final Runnable ifNotFound) {
        if (!ApiKeys.hasTmdb(context)) {
            giveUp(ifNotFound);
            return;
        }
        worker.execute(() -> {
            final ReleaseName.Info parsed = ReleaseName.parse(resolvedName());
            if (!parsed.looksLikeTitle()) {
                giveUp(ifNotFound);
                return;
            }
            final List<Tmdb.Candidate> candidates =
                    Tmdb.searchHard(context, parsed.searchQuery(), parsed.year);
            if (candidates.isEmpty()) {
                giveUp(ifNotFound);
                return;
            }
            final Tmdb.Candidate candidate = candidates.get(0);
            if (candidate.isSeries && (parsed.season == null || parsed.episode == null)) {
                giveUp(ifNotFound);
                return;
            }
            final Identity identity = Tmdb.identify(context, candidate,
                    candidate.isSeries ? parsed.season : null,
                    candidate.isSeries ? parsed.episode : null);
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
                    // Straight back to the box rather than back to the film:
                    // the whole reason for typing was that the guess was wrong,
                    // and one wrong guess is not a reason to stop.
                    showIdentifyDialog(activity, query, true, callback);
                    return;
                }
                chooseCandidate(activity, candidates, parsed, callback);
            });
        });
    }

    /*
     * Which programme, which season, which episode.
     *
     * Each step carries the list behind it so the step can be undone: the
     * seasons are fetched once and handed to the episode list, which hands them
     * back if you go back. Nothing below is fetched twice, and no wrong answer
     * costs more than the one press that made it.
     */
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
                    // No season list to show; fall back to asking for numbers.
                    askSeasonEpisode(activity, candidate, parsed, callback);
                    return;
                }
                showSeasons(activity, candidates, candidate, seasons, parsed, callback);
            });
        });
    }

    /** The season list, shown again on the way back without fetching it twice. */
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
                    // A season with no episode list is still searchable as a whole.
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

        new AlertDialog.Builder(activity)
                .setTitle(candidate.title)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.online_search, (dialog, which) ->
                        resolve(activity, candidate,
                                parseInt(season.getText().toString()),
                                parseInt(episode.getText().toString()),
                                callback))
                .show();
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

    // -------------------------------------------------------------- results

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

    /**
     * The results, with a way back to the question above them.
     *
     * The button at the bottom of the dialog did this already, but a dialog
     * button is not where anybody looks when the list is plainly for the wrong
     * film. It is the first row now, and what you choose there overrides
     * whatever was guessed.
     */
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

    /** The row that reopens the question, at the top of the results. */
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
    }

    private void downloadAndLoad(final Activity activity, final Subtitles.Result result) {
        final ProgressDialog progress = progress(activity, R.string.online_downloading);

        worker.execute(() -> {
            final byte[] bytes = Subtitles.download(context, result);
            final String stem = result.release != null && !result.release.trim().isEmpty()
                    ? result.release.trim()
                    : resolvedName();

            main.post(() -> {
                dismiss(progress);
                if (bytes == null || bytes.length == 0) {
                    toast(R.string.online_download_failed);
                    return;
                }

                final String name =
                        SubtitleStorage.fileName(stem, result.language, result.extension);
                final SubtitleStorage.Result saved = SubtitleStorage.save(context, name, bytes);

                if (!saved.saved()) {
                    toast(R.string.online_download_failed);
                    return;
                }

                /*
                 * The name goes with it, rather than being read back off the
                 * file.
                 *
                 * Reading it back looked equivalent and was not. A subtitle
                 * saved through MediaStore comes back as
                 * content://media/external/downloads/1321321, and when the
                 * display-name column cannot be read the only thing left to
                 * fall back on is the last part of the address -- so the track
                 * you had just chosen by name appeared in the picker as a row
                 * of digits. The release name was in hand all along.
                 */
                host.loadSubtitle(saved.uri, stem);
                Toast.makeText(context,
                        saved.location == null
                                ? context.getString(R.string.online_loaded)
                                : context.getString(R.string.online_saved_to, saved.location),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    // ----------------------------------------------------------- file name

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

        final String fromServer = Http.serverFileName(key);
        synchronized (resolvedNames) {
            // An empty string records "asked, got nothing", so a link that
            // cannot answer is not asked again on every search.
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

    private static boolean looksLikeAName(@Nullable final String name) {
        if (name == null || name.length() < 5) {
            return false;
        }
        final int dot = name.lastIndexOf('.');
        final String stem = dot > 0 ? name.substring(0, dot) : name;
        if (dot <= 0 || name.length() - dot > 5) {
            return false;
        }
        // A UUID is all hex and dashes; so is a hash. Neither is a title.
        return !stem.matches("(?i)[0-9a-f-]{8,}") && stem.matches(".*[A-Za-z].*");
    }

    // --------------------------------------------------------------- pieces

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
        return dialog;
    }

    private void dismiss(@Nullable final ProgressDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (IllegalArgumentException e) {
                // The activity went away while the request was in flight.
            }
        }
    }

    private void toast(final int message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
