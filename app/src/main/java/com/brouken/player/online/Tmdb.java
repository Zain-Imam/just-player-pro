package com.brouken.player.online;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Tmdb {

    private static final String BASE = "https://api.themoviedb.org/3";
    public static final String IMAGE_BASE = "https://image.tmdb.org/t/p/w342";

    private Tmdb() {
    }

    public static final class Candidate implements PosterPicker.Item {
        public final int id;
        public final boolean isSeries;
        @NonNull
        public final String title;
        @Nullable
        public final String year;
        @Nullable
        public final String overview;
        @Nullable
        public final String posterPath;

        Candidate(int id, boolean isSeries, @NonNull String title, @Nullable String year,
                  @Nullable String overview, @Nullable String posterPath) {
            this.id = id;
            this.isSeries = isSeries;
            this.title = title;
            this.year = year;
            this.overview = overview;
            this.posterPath = posterPath;
        }

        @NonNull
        public String label() {
            final String kind = isSeries ? "TV" : "Film";
            return year == null ? title + "  ·  " + kind : title + " (" + year + ")  ·  " + kind;
        }

        @Nullable
        @Override
        public String posterPath() {
            return posterPath;
        }

        @Override
        public float aspect() {
            return 2f / 3f;
        }

        @NonNull
        @Override
        public String title() {
            return title;
        }

        @Nullable
        @Override
        public String subtitle() {
            final String kind = isSeries ? "TV" : "Film";
            return year == null ? kind : year + "  ·  " + kind;
        }
    }

    @NonNull
    public static List<Candidate> search(final Context context, final String query) {
        return search(context, query, null);
    }

    /*
     * Ask more than once, in decreasing order of confidence.
     *
     * A name out of a file is a guess, and one guess is not enough: the year
     * may be wrong, the title may carry a subtitle the database does not use,
     * an anime may be listed under its other name. Asking once and giving up is
     * how "The Runner 2026" came back with nothing while "The Runner" was there
     * all along.
     *
     * Each rung asks for less than the one above it. The first that answers
     * wins, and if none do the caller is told so rather than left waiting.
     */
    @NonNull
    public static List<Candidate> searchHard(final Context context,
                                             final String title,
                                             @Nullable final String year) {
        final java.util.LinkedHashSet<String> tried = new java.util.LinkedHashSet<>();
        final String clean = title == null ? "" : title.trim();
        if (clean.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. The title, with the year beside it.
        if (year != null) {
            final List<Candidate> withYear = search(context, clean, year);
            if (!withYear.isEmpty()) {
                return withYear;
            }
        }

        // 2. The title on its own.
        tried.add(clean);

        // 3. Without whatever follows a colon or a dash, which is usually a
        //    subtitle the database files under the main name.
        final String[] cuts = {":", " - ", " – "};
        for (final String cut : cuts) {
            final int at = clean.indexOf(cut);
            if (at > 2) {
                tried.add(clean.substring(0, at).trim());
            }
        }

        // 4. Without the last word, for a name that kept one too many.
        final String[] words = clean.split("[ ]+");
        if (words.length > 2) {
            final StringBuilder shorter = new StringBuilder();
            for (int i = 0; i < words.length - 1; i++) {
                if (shorter.length() > 0) {
                    shorter.append(' ');
                }
                shorter.append(words[i]);
            }
            tried.add(shorter.toString());
        }

        // 5. Letters and digits only, for a name still carrying punctuation.
        final String bare = clean.replaceAll("[^A-Za-z0-9 ]+", " ")
                .replaceAll("[ ]+", " ").trim();
        if (!bare.isEmpty()) {
            tried.add(bare);
        }

        for (final String attempt : tried) {
            final List<Candidate> found = search(context, attempt, null);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return new ArrayList<>();
    }

    /*
     * The year goes beside the query, never inside it.
     *
     * Searching for "The Runner 2026" asks TMDB for a title containing those
     * words and finds nothing; searching for "The Runner" with 2026 as the year
     * finds the film. The year was being glued on to the end of the query,
     * which is why taking it off by hand was what made the search work.
     */
    @NonNull
    public static List<Candidate> search(final Context context, final String query,
                                         @Nullable final String year) {
        final List<Candidate> results = new ArrayList<>();
        final String key = ApiKeys.get(context, ApiKeys.PREF_TMDB);
        if (key == null || query == null || query.trim().isEmpty()) {
            return results;
        }

        final Map<String, String> params = Http.params();
        params.put("api_key", key);
        params.put("query", query.trim());
        params.put("include_adult", "false");
        if (year != null && year.matches("[0-9]{4}")) {
            // Multi search takes one year for both kinds.
            params.put("year", year);
        }

        final Http.Result response = Http.get(BASE + "/search/multi" + Http.query(params), null);
        final JSONObject json = response.json();
        if (json == null) {
            return results;
        }

        final JSONArray array = json.optJSONArray("results");
        if (array == null) {
            return results;
        }

        for (int i = 0; i < array.length(); i++) {
            final JSONObject row = array.optJSONObject(i);
            if (row == null) {
                continue;
            }
            final String mediaType = row.optString("media_type", "");
            final boolean isSeries = "tv".equals(mediaType);
            if (!isSeries && !"movie".equals(mediaType)) {
                // People show up in multi search too.
                continue;
            }

            final String title = isSeries
                    ? row.optString("name", "")
                    : row.optString("title", "");
            if (title.isEmpty()) {
                continue;
            }

            final String date = isSeries
                    ? row.optString("first_air_date", "")
                    : row.optString("release_date", "");

            results.add(new Candidate(
                    row.optInt("id"),
                    isSeries,
                    title,
                    date.length() >= 4 ? date.substring(0, 4) : null,
                    nullIfEmpty(row.optString("overview", "")),
                    nullIfEmpty(row.optString("poster_path", ""))));
        }
        return results;
    }

    @Nullable
    public static Identity identify(final Context context, final Candidate candidate,
                                    @Nullable final Integer season, @Nullable final Integer episode) {
        final String key = ApiKeys.get(context, ApiKeys.PREF_TMDB);
        if (key == null) {
            return null;
        }

        if (!candidate.isSeries) {
            final Map<String, String> params = Http.params();
            params.put("api_key", key);
            params.put("append_to_response", "external_ids");

            final JSONObject movie =
                    Http.get(BASE + "/movie/" + candidate.id + Http.query(params), null).json();
            if (movie == null) {
                return null;
            }
            String imdb = movie.optString("imdb_id", null);
            if (imdb == null || imdb.isEmpty()) {
                final JSONObject external = movie.optJSONObject("external_ids");
                imdb = external == null ? null : nullIfEmpty(external.optString("imdb_id", ""));
            }
            final String date = movie.optString("release_date", "");

            return new Identity(false, candidate.id, nullIfEmpty(imdb), null,
                    movie.optString("title", candidate.title), null, null, null,
                    date.length() >= 4 ? date.substring(0, 4) : candidate.year,
                    nullIfEmpty(movie.optString("overview", "")),
                    nullIfEmpty(movie.optString("poster_path", "")),
                    nullIfEmpty(date),
                    movie.optDouble("vote_average", 0));
        }

        // -- a series -------------------------------------------------------
        final Map<String, String> showParams = Http.params();
        showParams.put("api_key", key);
        showParams.put("append_to_response", "external_ids");

        final JSONObject show =
                Http.get(BASE + "/tv/" + candidate.id + Http.query(showParams), null).json();
        if (show == null) {
            return null;
        }

        final JSONObject showExternal = show.optJSONObject("external_ids");
        final String parentImdb =
                showExternal == null ? null : nullIfEmpty(showExternal.optString("imdb_id", ""));
        final String firstAir = show.optString("first_air_date", "");
        final String showTitle = show.optString("name", candidate.title);
        final String showYear = firstAir.length() >= 4 ? firstAir.substring(0, 4) : candidate.year;

        if (season == null || episode == null) {
            // A whole show, with no episode picked out yet.
            return new Identity(true, candidate.id, null, parentImdb, showTitle, null,
                    season, null, showYear,
                    nullIfEmpty(show.optString("overview", "")),
                    nullIfEmpty(show.optString("poster_path", "")),
                    nullIfEmpty(firstAir),
                    show.optDouble("vote_average", 0));
        }

        final Map<String, String> epParams = Http.params();
        epParams.put("api_key", key);
        epParams.put("append_to_response", "external_ids");

        final JSONObject ep = Http.get(
                BASE + "/tv/" + candidate.id + "/season/" + season + "/episode/" + episode
                        + Http.query(epParams), null).json();

        String episodeImdb = null;
        String episodeTitle = null;
        String episodeOverview = null;
        String airDate = null;
        double rating = show.optDouble("vote_average", 0);

        if (ep != null) {
            final JSONObject epExternal = ep.optJSONObject("external_ids");
            episodeImdb = epExternal == null ? null : nullIfEmpty(epExternal.optString("imdb_id", ""));
            episodeTitle = nullIfEmpty(ep.optString("name", ""));
            episodeOverview = nullIfEmpty(ep.optString("overview", ""));
            airDate = nullIfEmpty(ep.optString("air_date", ""));
            final double epRating = ep.optDouble("vote_average", 0);
            if (epRating > 0) {
                rating = epRating;
            }
        }

        return new Identity(true, candidate.id, episodeImdb, parentImdb, showTitle, episodeTitle,
                season, episode, showYear,
                episodeOverview != null ? episodeOverview : nullIfEmpty(show.optString("overview", "")),
                // The show poster, not the still from this episode.
                //
                // A still is a frame out of the middle of the episode: on a
                // card that sits over the paused film it reads as a second
                // screenshot rather than as the thing being watched, and for
                // half the episodes ever made it is a dark corridor. The
                // poster is the picture a series is recognised by, and it is
                // the same picture a film gets, so the card looks the same
                // whichever is playing.
                nullIfEmpty(show.optString("poster_path", "")),
                airDate != null ? airDate : nullIfEmpty(firstAir),
                rating);
    }

    public static final class Season implements PosterPicker.Item {
        public final int number;
        @NonNull
        public final String name;
        @Nullable
        public final String posterPath;
        public final int episodeCount;

        Season(int number, @NonNull String name, @Nullable String posterPath, int episodeCount) {
            this.number = number;
            this.name = name;
            this.posterPath = posterPath;
            this.episodeCount = episodeCount;
        }

        @Nullable
        @Override
        public String posterPath() {
            return posterPath;
        }

        @Override
        public float aspect() {
            return 2f / 3f;
        }

        @NonNull
        @Override
        public String title() {
            return name;
        }

        @Nullable
        @Override
        public String subtitle() {
            if (episodeCount <= 0) {
                return null;
            }
            return episodeCount + (episodeCount == 1 ? " episode" : " episodes");
        }
    }

    public static final class Episode implements PosterPicker.Item {
        public final int number;
        @NonNull
        public final String name;
        @Nullable
        public final String stillPath;
        @Nullable
        public final String airDate;

        Episode(int number, @NonNull String name, @Nullable String stillPath,
                @Nullable String airDate) {
            this.number = number;
            this.name = name;
            this.stillPath = stillPath;
            this.airDate = airDate;
        }

        @Nullable
        @Override
        public String posterPath() {
            return stillPath;
        }

        @Override
        public float aspect() {
            // A frame from the episode, not a poster.
            return 16f / 9f;
        }

        @NonNull
        @Override
        public String title() {
            final String code = number < 10 ? "E0" + number : "E" + number;
            return code + "  " + name;
        }

        @Nullable
        @Override
        public String subtitle() {
            return airDate;
        }
    }

    @NonNull
    public static List<Season> seasons(final Context context, final int showId) {
        final List<Season> seasons = new ArrayList<>();
        final String key = ApiKeys.get(context, ApiKeys.PREF_TMDB);
        if (key == null) {
            return seasons;
        }

        final Map<String, String> params = Http.params();
        params.put("api_key", key);

        final JSONObject show = Http.get(BASE + "/tv/" + showId + Http.query(params), null).json();
        if (show == null) {
            return seasons;
        }

        final JSONArray array = show.optJSONArray("seasons");
        if (array == null) {
            return seasons;
        }

        for (int i = 0; i < array.length(); i++) {
            final JSONObject row = array.optJSONObject(i);
            if (row == null) {
                continue;
            }
            seasons.add(new Season(
                    row.optInt("season_number"),
                    row.optString("name", "Season " + row.optInt("season_number")),
                    nullIfEmpty(row.optString("poster_path", "")),
                    row.optInt("episode_count", 0)));
        }
        return seasons;
    }

    @NonNull
    public static List<Episode> episodes(final Context context, final int showId,
                                         final int seasonNumber) {
        final List<Episode> episodes = new ArrayList<>();
        final String key = ApiKeys.get(context, ApiKeys.PREF_TMDB);
        if (key == null) {
            return episodes;
        }

        final Map<String, String> params = Http.params();
        params.put("api_key", key);

        final JSONObject season = Http.get(
                BASE + "/tv/" + showId + "/season/" + seasonNumber + Http.query(params), null).json();
        if (season == null) {
            return episodes;
        }

        final JSONArray array = season.optJSONArray("episodes");
        if (array == null) {
            return episodes;
        }

        for (int i = 0; i < array.length(); i++) {
            final JSONObject row = array.optJSONObject(i);
            if (row == null) {
                continue;
            }
            episodes.add(new Episode(
                    row.optInt("episode_number"),
                    row.optString("name", ""),
                    nullIfEmpty(row.optString("still_path", "")),
                    nullIfEmpty(row.optString("air_date", ""))));
        }
        return episodes;
    }

    @Nullable
    static String nullIfEmpty(@Nullable final String value) {
        return value == null || value.isEmpty() || "null".equals(value) ? null : value;
    }
}
