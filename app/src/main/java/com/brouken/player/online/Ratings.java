package com.brouken.player.online;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import com.brouken.player.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// scores from MDBList; any failure just means none, as some networks block it
public final class Ratings {

    public static final String PREF_ENABLED = "mdblistEnabled";
    public static final String PREF_ON_CARD = "mdblistOnCard";
    private static final String PREF_SITE = "mdblistShow_";
    public static final String PREF_ORDER = "mdblistOrder";
    // "show" or "episode": what an episode's line rates
    public static final String PREF_EPISODES = "ratingsEpisodes";

    public static final String IMDB = "imdb";
    public static final String TMDB = "tmdb";
    public static final String TOMATOES = "tomatoes";
    public static final String AUDIENCE = "audience";
    public static final String METACRITIC = "metacritic";
    public static final String TRAKT = "trakt";
    public static final String LETTERBOXD = "letterboxd";
    public static final String MAL = "mal";

    // display order; anything else MDBList sends is dropped, its scale unknown
    public static final String[] SITES = {IMDB, TMDB, TOMATOES, AUDIENCE, METACRITIC, TRAKT, LETTERBOXD, MAL};

    // kept beside the scores as 1.0, for the card's icon
    public static final String CERTIFIED_FRESH = "certified-fresh";
    public static final String CERTIFIED_HOT = "certified-hot";
    private static final String[] FLAGS = {CERTIFIED_FRESH, CERTIFIED_HOT};

    private static final String API = "https://api.mdblist.com/";
    private static final int TIMEOUT_SECONDS = 8;
    private static final long KEEP_MS = 24 * 3_600_000L;
    // after a timeout or refused key, wait a while rather than retry on every pause
    private static final long RETRY_MS = 15 * 60_000L;
    private static final Pattern IMDB_ID = Pattern.compile("tt\\d{6,}");
    // The Shawshank Redemption: it will exist as long as MDBList does
    private static final String PROBE = "tt0111161";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Map<String, Map<String, Double>> MEMORY = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final String ANY = "*";

    public interface Callback {
        void onRatings(@NonNull Map<String, Double> ratings);
    }

    public enum KeyCheck {WORKS, WRONG, UNREACHABLE}

    public interface KeyCallback {
        void onChecked(@NonNull KeyCheck result);
    }

    private Ratings() {
    }

    private static SharedPreferences preferences(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean enabled(final Context context) {
        return preferences(context).getBoolean(PREF_ENABLED, false)
                && ApiKeys.has(context, ApiKeys.PREF_MDBLIST);
    }

    public static boolean onCard(final Context context) {
        return enabled(context) && preferences(context).getBoolean(PREF_ON_CARD, true);
    }

    // the audience switch keeps its old "popcorn" key
    public static String siteKey(final String site) {
        return PREF_SITE + (AUDIENCE.equals(site) ? "popcorn" : site);
    }

    public static boolean isOn(final Context context, final String site) {
        return preferences(context).getBoolean(siteKey(site), true);
    }

    public static void setOn(final Context context, final String site, final boolean on) {
        preferences(context).edit().putBoolean(siteKey(site), on).apply();
    }

    @NonNull
    public static List<String> order(final Context context) {
        return parseOrder(preferences(context).getString(PREF_ORDER, null));
    }

    @VisibleForTesting
    @NonNull
    static List<String> parseOrder(@Nullable final String stored) {
        final List<String> known = Arrays.asList(SITES);
        final List<String> out = new ArrayList<>();
        if (stored != null) {
            for (final String key : stored.split(",")) {
                final String site = key.trim();
                if (known.contains(site) && !out.contains(site)) {
                    out.add(site);
                }
            }
        }
        for (final String site : SITES) {
            if (!out.contains(site)) {
                out.add(site);
            }
        }
        return out;
    }

    public static void saveOrder(final Context context, final List<String> order) {
        preferences(context).edit().putString(PREF_ORDER, String.join(",", order)).apply();
    }

    public static void resetOrder(final Context context) {
        final SharedPreferences.Editor editor = preferences(context).edit().remove(PREF_ORDER);
        for (final String site : SITES) {
            editor.remove(siteKey(site));
        }
        editor.apply();
    }

    // MDBList rates no single episodes, so an episode's own score is TMDB's
    public static boolean episodeOwn(final Context context) {
        return "episode".equals(preferences(context).getString(PREF_EPISODES, "show"));
    }

    private static boolean ratesEpisode(final Context context, @Nullable final Identity identity) {
        return identity != null && identity.isSeries && identity.episode != null && episodeOwn(context);
    }

    @NonNull
    public static Map<String, Double> forTitle(final Context context, @Nullable final Identity identity,
                                               @Nullable final Map<String, Double> found) {
        if (ratesEpisode(context, identity)) {
            final Map<String, Double> line = new LinkedHashMap<>();
            if (identity.episodeRating > 0 && isOn(context, TMDB)) {
                line.put(TMDB, Math.round(identity.episodeRating * 100) / 10.0);
            }
            return line;
        }
        return forTitle(context, found, identity == null ? 0 : identity.rating);
    }

    // nothing until MDBList answers, so a failure leaves the screens as they were
    @NonNull
    public static Map<String, Double> forTitle(final Context context, @Nullable final Map<String, Double> found,
                                               final double ownTmdb) {
        if (found == null) {
            return new LinkedHashMap<>();
        }
        final java.util.Set<String> off = new java.util.HashSet<>();
        for (final String site : SITES) {
            if (!isOn(context, site)) {
                off.add(site);
            }
        }
        return arrange(order(context), off, found, ownTmdb);
    }

    @VisibleForTesting
    @NonNull
    static Map<String, Double> arrange(final List<String> order, final java.util.Set<String> off,
                                       final Map<String, Double> found, final double ownTmdb) {
        final Map<String, Double> line = new LinkedHashMap<>();
        for (final String site : order) {
            Double value = found.get(site);
            if (value == null && TMDB.equals(site) && ownTmdb > 0) {
                value = Math.round(ownTmdb * 100) / 10.0;
            }
            if (value != null && !off.contains(site)) {
                line.put(site, value);
            }
        }
        return line;
    }

    private static boolean recentlyFailed(final String key) {
        final Long failedAt = FAILED.get(key);
        return failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MS;
    }

    // a new key gets its chance at once
    public static void retrySoon() {
        FAILED.clear();
    }

    @Nullable
    @VisibleForTesting
    static String imdbIdOf(final Identity identity) {
        final String id = identity.isSeries ? identity.parentImdbId : identity.imdbId;
        if (id == null) {
            return null;
        }
        final Matcher match = IMDB_ID.matcher(id);
        return match.find() ? match.group() : null;
    }

    @Nullable
    private static String path(final Identity identity) {
        final String type = identity.isSeries ? "show/" : "movie/";
        final String imdb = imdbIdOf(identity);
        if (imdb != null) {
            return "imdb/" + type + imdb;
        }
        return identity.tmdbId > 0 ? "tmdb/" + type + identity.tmdbId : null;
    }

    @Nullable
    public static Map<String, Double> cached(final Identity identity) {
        final String path = path(identity);
        return path == null ? null : MEMORY.get(path);
    }

    // the callback runs on the main thread, and only when there are ratings
    public static void fetch(final Context context, final Identity identity, final Callback callback) {
        final String path = path(identity);
        // the show's scores would not be shown, so not asked for
        if (!enabled(context) || path == null || ratesEpisode(context, identity)) {
            return;
        }
        final Map<String, Double> known = MEMORY.get(path);
        if (known != null) {
            callback.onRatings(known);
            return;
        }
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            final Map<String, Double> ratings = load(app, path);
            if (ratings != null) {
                main.post(() -> callback.onRatings(ratings));
            }
        });
    }

    @Nullable
    private static Map<String, Double> load(final Context context, final String path) {
        final Map<String, Double> known = MEMORY.get(path);
        if (known != null) {
            return known;
        }
        final File file = new File(new File(context.getCacheDir(), "mdblist-ratings"),
                path.replace('/', '-') + ".json");
        final Map<String, Double> kept = readDisk(file);
        if (kept != null) {
            MEMORY.put(path, kept);
            return kept;
        }
        if (recentlyFailed(path) || recentlyFailed(ANY)) {
            return null;
        }
        final String apiKey = ApiKeys.get(context, ApiKeys.PREF_MDBLIST);
        if (apiKey == null) {
            return null;
        }
        final Http.Result result = request(path, apiKey);
        final String trouble = trouble(result.code);
        if (trouble != null) {
            // a refused key, a spent allowance or no way through stops them all
            FAILED.put(ANY, System.currentTimeMillis());
            Log.i(com.brouken.player.Utils.TAG, "MDBList ratings: none, " + trouble);
            return null;
        }
        // a 404 or 5xx counts as no ratings and is cached for the day too
        final JSONObject json = result.json();
        final Map<String, Double> ratings = json == null ? new LinkedHashMap<>() : parse(json);
        MEMORY.put(path, ratings);
        writeDisk(file, ratings);
        Log.i(com.brouken.player.Utils.TAG, "MDBList ratings: " + ratings.size() + " for " + path);
        return ratings;
    }

    private static Http.Result request(final String path, final String apiKey) {
        final Map<String, String> params = Http.params();
        params.put("apikey", apiKey);
        // the keywords say whether it is Certified Fresh or Verified Hot
        params.put("append_to_response", "keyword");
        return Http.get(API + path + Http.query(params), null, TIMEOUT_SECONDS);
    }

    @Nullable
    private static String trouble(final int code) {
        if (code == 401 || code == 403) {
            return "the key was refused";
        }
        if (code == 429) {
            return "rate limited";
        }
        return code < 0 ? "MDBList unreachable" : null;
    }

    // a spent allowance is still a real key
    public static void checkKey(final Context context, final String apiKey, final KeyCallback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            final int code = request("imdb/movie/" + PROBE, apiKey.trim()).code;
            final KeyCheck result = code == 401 || code == 403 ? KeyCheck.WRONG
                    : code < 0 ? KeyCheck.UNREACHABLE : KeyCheck.WORKS;
            Log.i(com.brouken.player.Utils.TAG, "MDBList key check: " + result);
            main.post(() -> callback.onChecked(result));
        });
    }

    @VisibleForTesting
    @Nullable
    static String siteOf(final String source) {
        final String name = source.trim().toLowerCase(Locale.ROOT);
        switch (name) {
            case "themoviedb":
            case "moviedb":
                return TMDB;
            case "rottentomatoes":
                return TOMATOES;
            // MDBList's name for the Rotten Tomatoes audience score
            case "popcorn":
            case "tomatoesaudience":
                return AUDIENCE;
            case "myanimelist":
                return MAL;
            default:
                for (final String site : SITES) {
                    if (site.equals(name)) {
                        return site;
                    }
                }
                return null;
        }
    }

    @VisibleForTesting
    @NonNull
    public static Map<String, Double> parse(@NonNull final JSONObject json) {
        final Map<String, Double> found = new LinkedHashMap<>();
        final JSONArray rows = json.optJSONArray("ratings");
        if (rows == null) {
            return found;
        }
        for (int i = 0; i < rows.length(); i++) {
            final JSONObject row = rows.optJSONObject(i);
            if (row == null || row.isNull("value")) {
                continue;
            }
            final String site = siteOf(row.optString("source", ""));
            final double value = row.optDouble("value", Double.NaN);
            if (site == null || found.containsKey(site) || Double.isNaN(value)
                    || Double.isInfinite(value) || value <= 0) {
                continue;
            }
            found.put(site, Math.round(value * 10) / 10.0);
        }
        final Map<String, Double> ordered = new LinkedHashMap<>();
        for (final String site : SITES) {
            if (found.containsKey(site)) {
                ordered.put(site, found.get(site));
            }
        }
        readFlags(json.optJSONArray("keywords"), ordered);
        return ordered;
    }

    private static void readFlags(@Nullable final JSONArray keywords, final Map<String, Double> into) {
        if (keywords == null) {
            return;
        }
        for (int i = 0; i < keywords.length(); i++) {
            final JSONObject object = keywords.optJSONObject(i);
            String name = object != null ? object.optString("name", "") : keywords.optString(i, "");
            name = name.substring(name.lastIndexOf('.') + 1).trim().toLowerCase(Locale.ROOT);
            for (final String flag : FLAGS) {
                if (flag.equals(name)) {
                    into.put(flag, 1.0);
                }
            }
        }
    }

    private static boolean has(@Nullable final Map<String, Double> found, final String flag) {
        return found != null && found.containsKey(flag);
    }

    @Nullable
    private static Map<String, Double> readDisk(final File file) {
        try {
            if (!file.isFile() || System.currentTimeMillis() - file.lastModified() >= KEEP_MS) {
                return null;
            }
            final byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            final JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            final Map<String, Double> ratings = new LinkedHashMap<>();
            for (final String site : SITES) {
                final double value = json.optDouble(site, Double.NaN);
                if (!Double.isNaN(value)) {
                    ratings.put(site, value);
                }
            }
            for (final String flag : FLAGS) {
                if (json.has(flag)) {
                    ratings.put(flag, 1.0);
                }
            }
            return ratings;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeDisk(final File file, final Map<String, Double> ratings) {
        try {
            final File folder = file.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
                return;
            }
            final JSONObject json = new JSONObject();
            for (final Map.Entry<String, Double> rating : ratings.entrySet()) {
                json.put(rating.getKey(), rating.getValue().doubleValue());
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // only a cache; it is asked for again next time
        }
    }

    private static boolean percentage(final String site) {
        return TMDB.equals(site) || TOMATOES.equals(site) || AUDIENCE.equals(site)
                || METACRITIC.equals(site) || TRAKT.equals(site);
    }

    @NonNull
    public static String format(final String site, final double value) {
        if (percentage(site)) {
            return String.format(Locale.getDefault(), "%d%%", Math.round(value));
        }
        final double rounded = Math.round(value * 10) / 10.0;
        final String mark = rounded == Math.rint(rounded)
                ? String.format(Locale.getDefault(), "%d", (long) rounded)
                : String.format(Locale.getDefault(), "%.1f", rounded);
        return LETTERBOXD.equals(site) ? mark + "/5" : mark;
    }

    @NonNull
    public static String shortLabel(final String site) {
        switch (site) {
            case IMDB:
                return "IMDb";
            case AUDIENCE:
                return "RT Users";
            case METACRITIC:
                return "Metacritic";
            case TRAKT:
                return "Trakt";
            case LETTERBOXD:
                return "Letterboxd";
            case MAL:
                return "MyAnimeList";
            default:
                return label(site);
        }
    }

    @NonNull
    public static String label(final String site) {
        switch (site) {
            case IMDB:
                return "IMDB";
            case TMDB:
                return "TMDB";
            case TOMATOES:
                return "RT";
            case AUDIENCE:
                return "RT USERS";
            case METACRITIC:
                return "METACRITIC";
            case TRAKT:
                return "TRAKT";
            case LETTERBOXD:
                return "LETTERBOXD";
            default:
                return "MYANIMELIST";
        }
    }

    public static int colour(final String site) {
        switch (site) {
            case IMDB:
                return 0xFFF5C518;
            case TMDB:
                return 0xFF01B4E4;
            case TOMATOES:
                return 0xFFFA320A;
            case AUDIENCE:
                return 0xFFFAA71A;
            case METACRITIC:
                return 0xFF66CC33;
            case TRAKT:
                return 0xFFED1C24;
            case LETTERBOXD:
                return 0xFF00E054;
            default:
                return 0xFF2E51A2;
        }
    }

    public static final class CardScore {
        @NonNull
        public final String site;
        public final int icon;
        @NonNull
        public final String value;

        CardScore(@NonNull final String site, final int icon, @NonNull final String value) {
            this.site = site;
            this.icon = icon;
            this.value = value;
        }
    }

    @NonNull
    public static java.util.List<CardScore> cardScores(final Map<String, Double> line,
                                                       @Nullable final Map<String, Double> found) {
        final java.util.List<CardScore> scores = new java.util.ArrayList<>();
        for (final Map.Entry<String, Double> rating : line.entrySet()) {
            final String site = rating.getKey();
            scores.add(new CardScore(site, icon(site, rating.getValue(), found),
                    format(site, rating.getValue())));
        }
        return scores;
    }

    public static int icon(final String site, final double value, @Nullable final Map<String, Double> found) {
        switch (site) {
            case IMDB:
                return R.drawable.rating_imdb;
            case TMDB:
                return R.drawable.rating_tmdb;
            case TOMATOES:
                return has(found, CERTIFIED_FRESH) && value >= 70 ? R.drawable.rating_tomatoes_certified
                        : value >= 60 ? R.drawable.rating_tomatoes : R.drawable.rating_tomatoes_rotten;
            case AUDIENCE:
                return has(found, CERTIFIED_HOT) && value >= 80 ? R.drawable.rating_audience_hot
                        : value >= 60 ? R.drawable.rating_audience : R.drawable.rating_audience_stale;
            case METACRITIC:
                return R.drawable.rating_metacritic;
            case TRAKT:
                return R.drawable.rating_trakt;
            case LETTERBOXD:
                return R.drawable.rating_letterboxd;
            default:
                return R.drawable.rating_mal;
        }
    }

    @NonNull
    public static String spokenName(final Context context, final String site) {
        switch (site) {
            case IMDB:
                return "IMDb";
            case TMDB:
                return "TMDB";
            case TOMATOES:
                return "Rotten Tomatoes";
            case AUDIENCE:
                return context.getString(R.string.pref_mdblist_audience);
            case METACRITIC:
                return "Metacritic";
            case TRAKT:
                return "Trakt";
            case LETTERBOXD:
                return "Letterboxd";
            default:
                return "MyAnimeList";
        }
    }
}
