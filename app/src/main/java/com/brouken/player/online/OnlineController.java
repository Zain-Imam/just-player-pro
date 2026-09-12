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

    public interface Host {
        @Nullable
        Uri mediaUri();

        @Nullable
        String mediaName();

        void loadSubtitle(Uri uri);
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

    public void forget(@Nullable final Uri uri) {
        if (uri == null) {
            return;
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

        final Uri uri = host.mediaUri();
        final Identity known = reIdentify ? null : remembered(uri);
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
        if (!ApiKeys.hasTmdb(context)) {
            return;
        }
        worker.execute(() -> {
            final ReleaseName.Info parsed = ReleaseName.parse(resolvedName());
            if (!parsed.looksLikeTitle()) {
                return;
            }
            final List<Tmdb.Candidate> candidates = Tmdb.search(context, parsed.searchQuery());
            if (candidates.isEmpty()) {
                return;
            }
            final Tmdb.Candidate candidate = candidates.get(0);
            if (candidate.isSeries && (parsed.season == null || parsed.episode == null)) {
                return;
            }
            final Identity identity = Tmdb.identify(context, candidate,
                    candidate.isSeries ? parsed.season : null,
                    candidate.isSeries ? parsed.episode : null);
            if (identity == null) {
                return;
            }
            if (uri != null) {
                remember(uri, identity);
            }
            main.post(() -> callback.onIdentified(identity));
        });
    }

    private void searchTmdb(final Activity activity, final String query,
                            final ReleaseName.Info parsed, final OnIdentified callback) {
        final ProgressDialog progress = progress(activity, R.string.online_identifying);

        worker.execute(() -> {
            final List<Tmdb.Candidate> candidates = Tmdb.search(context, query);
            main.post(() -> {
                dismiss(progress);
                if (candidates.isEmpty()) {
                    toast(R.string.online_no_matches);
                    return;
                }
                chooseCandidate(activity, candidates, parsed, callback);
            });
        });
    }

    private void chooseCandidate(final Activity activity, final List<Tmdb.Candidate> candidates,
                                 final ReleaseName.Info parsed, final OnIdentified callback) {
        PosterPicker.show(activity, context.getString(R.string.online_identify_title), candidates,
                index -> {
                    final Tmdb.Candidate candidate = candidates.get(index);
                    if (candidate.isSeries) {
                        chooseSeason(activity, candidate, parsed, callback);
                    } else {
                        resolve(activity, candidate, null, null, callback);
                    }
                });
    }

    private void chooseSeason(final Activity activity, final Tmdb.Candidate candidate,
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
                PosterPicker.show(activity, candidate.title, seasons, index ->
                        chooseEpisode(activity, candidate, seasons.get(index).number, callback));
            });
        });
    }

    private void chooseEpisode(final Activity activity, final Tmdb.Candidate candidate,
                               final int season, final OnIdentified callback) {
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
                                resolve(activity, candidate, season, episodes.get(index).number, callback));
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
                showResults(activity, results);
            });
        });
    }

    private void showResults(final Activity activity, final List<Subtitles.Result> results) {
        ListPicker.show(activity, results.size() + " subtitles", results,
                index -> downloadAndLoad(activity, results.get(index)),
                R.string.online_change_title,
                () -> searchSubtitles(activity, true));
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

                host.loadSubtitle(saved.uri);
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
