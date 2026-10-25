package com.brouken.player.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// follows Episode Info's alLookup: show nothing rather than the wrong thing
public final class AutoMatch {

    // null for a 404, throws when TMDB could not be asked
    public interface Source {
        @Nullable
        JSONObject get(@NonNull String path, @NonNull Map<String, String> params) throws IOException;
    }

    public interface Cache {
        // {"v": value or null}, or null when nothing fresh enough is cached
        @Nullable
        JSONObject get(@NonNull String key, int hours);

        void put(@NonNull String key, @Nullable JSONObject value);
    }

    // GraphQL's data, null when it has nothing, throws when it could not be asked
    public interface AnimeSource {
        @Nullable
        JSONObject ask(@NonNull String query, @NonNull JSONObject variables) throws IOException;
    }

    public static final class Match {
        public final boolean isSeries;
        public final int tmdbId;
        @Nullable
        public final Integer season;
        @Nullable
        public final Integer episode;
        @NonNull
        public final String title;
        @NonNull
        public final String episodeTitle;

        Match(final boolean isSeries, final int tmdbId, @Nullable final Integer season,
              @Nullable final Integer episode, @NonNull final String title,
              @NonNull final String episodeTitle) {
            this.isSeries = isSeries;
            this.tmdbId = tmdbId;
            this.season = season;
            this.episode = episode;
            this.title = title;
            this.episodeTitle = episodeTitle;
        }

        @NonNull
        public String code() {
            return season == null || episode == null ? ""
                    : String.format(Locale.ROOT, "S%02dE%02d", season, episode);
        }
    }

    public static final class Result {
        @Nullable
        public final Match match;
        @NonNull
        public final String reason;
        @NonNull
        public final String query;
        @Nullable
        public Integer season;
        @Nullable
        public Integer episode;
        @NonNull
        public String from = "";
        public boolean viaAnime;

        Result(@Nullable final Match match, @NonNull final String reason, @NonNull final String query) {
            this.match = match;
            this.reason = reason;
            this.query = query;
        }
    }

    public static final class LinkIds {
        @Nullable
        public String imdb;
        @Nullable
        public Integer season;
        @Nullable
        public Integer episode;
    }

    public interface ServerName {
        @Nullable
        String fetch();
    }

    private static final Pattern GENERIC_TITLE = Pattern.compile("^(?:(?:part|pt|episode|ep|chapter|ch|day"
            + "|night|week|vol|volume|disc|disk|cd|take|scene|clip|act|round|game|session|lesson|lecture|track"
            + "|book|issue|no)\\.?\\s*)?(?:\\d+|[ivxl]+|one|two|three|four|five|six|seven|eight|nine|ten)$",
            Pattern.CASE_INSENSITIVE);

    private final Source source;
    private final Cache cache;
    // null while the setting is off
    @Nullable
    private volatile AnimeSource anime;

    public AutoMatch(@NonNull final Source source, @NonNull final Cache cache) {
        this.source = source;
        this.cache = cache;
    }

    public void useAnime(@Nullable final AnimeSource anime) {
        this.anime = anime;
    }

    private static Result notSure(final String reason, @Nullable final String query) {
        return new Result(null, reason, query == null ? "" : query);
    }

    private static Result sure(final Match match, final String query) {
        return new Result(match, "", query);
    }

    // TMDB, cached; a 404 is kept for a day, errors are not kept
    @Nullable
    private JSONObject tmdb(final String path, final Map<String, String> params, final int hours,
                            final Trim trim) throws IOException {
        final StringBuilder key = new StringBuilder(path).append('?');
        boolean first = true;
        for (final Map.Entry<String, String> each : new TreeMap<>(params).entrySet()) {
            if (!first) {
                key.append('&');
            }
            key.append(each.getKey()).append('=').append(each.getValue());
            first = false;
        }
        final JSONObject hit = cache.get(key.toString(), hours);
        if (hit != null) {
            return hit.optJSONObject("v");
        }
        final JSONObject answer = source.get(path, params);
        final JSONObject value = answer == null ? null : trim.apply(answer);
        cache.put(key.toString(), value);
        return value;
    }

    private interface Trim {
        JSONObject apply(JSONObject raw);
    }

    private static Map<String, String> params(final String... pairs) {
        final Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static List<String> strings(@Nullable final JSONArray array) {
        final List<String> out = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                out.add(array.optString(i, ""));
            }
        }
        return out;
    }

    private static String text(final JSONObject o, final String key) {
        final String value = o.optString(key, "");
        return "null".equals(value) ? "" : value;
    }

    private List<JSONObject> search(final String kind, final String title,
                                     @Nullable final Map<String, String> extra) throws IOException {
        final Map<String, String> params = params("query", title, "include_adult", "false");
        if (extra != null) {
            params.putAll(extra);
        }
        final JSONObject v = tmdb("/search/" + kind, params, 168, d -> {
            final JSONArray results = d.optJSONArray("results");
            final JSONArray trimmed = new JSONArray();
            for (int i = 0; results != null && i < Math.min(20, results.length()); i++) {
                final JSONObject x = results.optJSONObject(i);
                if (x == null) {
                    continue;
                }
                final JSONObject t = new JSONObject();
                try {
                    t.put("id", x.optInt("id"));
                    t.put("name", !text(x, "name").isEmpty() ? text(x, "name") : text(x, "title"));
                    t.put("original", !text(x, "original_name").isEmpty()
                            ? text(x, "original_name") : text(x, "original_title"));
                    t.put("date", !text(x, "first_air_date").isEmpty()
                            ? text(x, "first_air_date") : text(x, "release_date"));
                    t.put("country", x.optJSONArray("origin_country") != null
                            ? x.optJSONArray("origin_country") : new JSONArray());
                    t.put("votes", x.optInt("vote_count", 0));
                } catch (Exception ignored) {
                    // only string keys, cannot throw
                }
                trimmed.put(t);
            }
            return wrap("results", trimmed);
        });
        final List<JSONObject> out = new ArrayList<>();
        final JSONArray results = v == null ? null : v.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            out.add(results.optJSONObject(i));
        }
        return out;
    }

    private static JSONObject wrap(final String key, final Object value) {
        final JSONObject o = new JSONObject();
        try {
            o.put(key, value);
        } catch (Exception ignored) {
            // only string keys, cannot throw
        }
        return o;
    }

    @Nullable
    private JSONObject tvDetails(final int id) throws IOException {
        return tmdb("/tv/" + id, params("append_to_response", "alternative_titles,external_ids"), 24, d -> {
            final JSONObject t = new JSONObject();
            try {
                t.put("id", d.optInt("id"));
                t.put("name", text(d, "name"));
                t.put("original", text(d, "original_name"));
                t.put("date", text(d, "first_air_date"));
                t.put("poster", text(d, "poster_path"));
                t.put("votes", d.optInt("vote_count", 0));
                final JSONArray seasons = new JSONArray();
                final JSONArray all = d.optJSONArray("seasons");
                for (int i = 0; all != null && i < all.length(); i++) {
                    final JSONObject s = all.optJSONObject(i);
                    if (s != null) {
                        seasons.put(s.optInt("season_number"));
                    }
                }
                t.put("seasons", seasons);
                final JSONArray alt = new JSONArray();
                final JSONObject titles = d.optJSONObject("alternative_titles");
                final JSONArray results = titles == null ? null : titles.optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    final JSONObject a = results.optJSONObject(i);
                    if (a != null) {
                        alt.put(text(a, "title"));
                    }
                }
                t.put("alt", alt);
                final JSONObject external = d.optJSONObject("external_ids");
                t.put("imdb", external == null ? "" : text(external, "imdb_id"));
            } catch (Exception ignored) {
                // only string keys, cannot throw
            }
            return t;
        });
    }

    @Nullable
    private JSONObject season(final int id, final int n) throws IOException {
        return tmdb("/tv/" + id + "/season/" + n, params(), 24, d -> {
            final JSONObject t = new JSONObject();
            try {
                t.put("poster", text(d, "poster_path"));
                final JSONArray episodes = new JSONArray();
                final JSONArray all = d.optJSONArray("episodes");
                for (int i = 0; all != null && i < all.length(); i++) {
                    final JSONObject e = all.optJSONObject(i);
                    if (e == null) {
                        continue;
                    }
                    final JSONObject x = new JSONObject();
                    x.put("n", e.optInt("episode_number"));
                    x.put("name", text(e, "name"));
                    x.put("air", text(e, "air_date"));
                    x.put("runtime", e.optInt("runtime", 0));
                    episodes.put(x);
                }
                t.put("episodes", episodes);
            } catch (Exception ignored) {
                // only string keys, cannot throw
            }
            return t;
        });
    }

    @Nullable
    private JSONObject movieDetails(final int id) throws IOException {
        return tmdb("/movie/" + id, params("append_to_response", "alternative_titles,external_ids"), 168, d -> {
            final JSONObject t = new JSONObject();
            try {
                t.put("id", d.optInt("id"));
                t.put("title", text(d, "title"));
                t.put("original", text(d, "original_title"));
                t.put("date", text(d, "release_date"));
                t.put("runtime", d.optInt("runtime", 0));
                t.put("votes", d.optInt("vote_count", 0));
                final JSONArray alt = new JSONArray();
                final JSONObject titles = d.optJSONObject("alternative_titles");
                final JSONArray results = titles == null ? null : titles.optJSONArray("titles");
                for (int i = 0; results != null && i < results.length(); i++) {
                    final JSONObject a = results.optJSONObject(i);
                    if (a != null) {
                        alt.put(text(a, "title"));
                    }
                }
                t.put("alt", alt);
            } catch (Exception ignored) {
                // only string keys, cannot throw
            }
            return t;
        });
    }

    @Nullable
    private JSONObject find(final String imdb) throws IOException {
        return tmdb("/find/" + imdb, params("external_source", "imdb_id"), 168, d -> {
            final JSONObject t = new JSONObject();
            try {
                t.put("movie", ids(d.optJSONArray("movie_results")));
                t.put("tv", ids(d.optJSONArray("tv_results")));
                final JSONArray episodes = new JSONArray();
                final JSONArray all = d.optJSONArray("tv_episode_results");
                for (int i = 0; all != null && i < all.length(); i++) {
                    final JSONObject x = all.optJSONObject(i);
                    if (x != null) {
                        final JSONObject e = new JSONObject();
                        e.put("show", x.optInt("show_id"));
                        e.put("season", x.optInt("season_number"));
                        e.put("episode", x.optInt("episode_number"));
                        episodes.put(e);
                    }
                }
                t.put("episode", episodes);
            } catch (Exception ignored) {
                // only string keys, cannot throw
            }
            return t;
        });
    }

    private static JSONArray ids(@Nullable final JSONArray results) {
        final JSONArray out = new JSONArray();
        for (int i = 0; results != null && i < results.length(); i++) {
            final JSONObject x = results.optJSONObject(i);
            if (x != null) {
                out.put(x.optInt("id"));
            }
        }
        return out;
    }

    private static int yearOf(final String date) {
        if (date == null || date.length() < 4) {
            return 0;
        }
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean lengthFits(final double seconds, final int minutes, final int count) {
        if (minutes <= 0) {
            return true;
        }
        final double ratio = seconds / (minutes * 60.0 * Math.max(1, count));
        return ratio >= 0.5 && ratio <= 2;
    }

    private List<JSONObject> titleCandidates(final String kind, final List<JSONObject> list,
                                             final String title) throws IOException {
        final List<JSONObject> out = new ArrayList<>();
        for (final JSONObject x : list.subList(0, Math.min(10, list.size()))) {
            if (NameReading.titleMatches(title, java.util.Arrays.asList(text(x, "name"), text(x, "original")))) {
                out.add(x);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        for (int i = 0; i < Math.min(3, list.size()); i++) {
            final JSONObject d = "tv".equals(kind)
                    ? tvDetails(list.get(i).optInt("id"))
                    : movieDetails(list.get(i).optInt("id"));
            if (d != null && NameReading.titleMatches(title, strings(d.optJSONArray("alt")))) {
                out.add(list.get(i));
            }
        }
        return out;
    }

    private static final class Fit {
        final int votes;
        @Nullable
        final Match match;

        Fit(final int votes, @Nullable final Match match) {
            this.votes = votes;
            this.match = match;
        }
    }

    // several fit: the top one only if it is far more popular
    @Nullable
    private static Fit dominant(final List<Fit> fits) {
        Collections.sort(fits, (a, b) -> b.votes - a.votes);
        final int second = fits.size() > 1 ? fits.get(1).votes : 0;
        return fits.get(0).votes >= 50 && fits.get(0).votes >= 10 * second ? fits.get(0) : null;
    }

    private static Match tvMatch(final JSONObject d, final JSONObject ep, final int season) {
        final String name = text(ep, "name");
        return new Match(true, d.optInt("id"), season, ep.optInt("n"), text(d, "name"),
                name.isEmpty() ? "Episode " + ep.optInt("n") : name);
    }

    private static Match movieMatch(final JSONObject d) {
        return new Match(false, d.optInt("id"), null, null, text(d, "title"), "");
    }

    @Nullable
    private static JSONObject episode(@Nullable final JSONObject sd, final int n) {
        final JSONArray episodes = sd == null ? null : sd.optJSONArray("episodes");
        for (int i = 0; episodes != null && i < episodes.length(); i++) {
            final JSONObject e = episodes.optJSONObject(i);
            if (e != null && e.optInt("n") == n) {
                return e;
            }
        }
        return null;
    }

    private static List<Integer> regularSeasons(final JSONObject d) {
        final List<Integer> regular = new ArrayList<>();
        final JSONArray seasons = d.optJSONArray("seasons");
        for (int i = 0; seasons != null && i < seasons.length(); i++) {
            if (seasons.optInt(i) > 0) {
                regular.add(seasons.optInt(i));
            }
        }
        return regular;
    }

    private static String code(final int season, final int episode) {
        return String.format(Locale.ROOT, "S%02dE%02d", season, episode);
    }

    // absolute: anime numbered from 1 across all its seasons
    private Result matchTv(final NameReading.Parsed p, final double seconds, final boolean absolute)
            throws IOException {
        if (p.seasonWords) {
            return notSure("its season numbering may not match TMDB's", p.title);
        }
        if (p.season != null && p.season == 0) {
            return notSure("specials are numbered differently everywhere", p.title);
        }
        List<JSONObject> list = search("tv", p.title,
                p.year != null ? params("first_air_date_year", String.valueOf(p.year)) : null);
        boolean loose = false;
        if (p.year != null && titleCandidates("tv", list, p.title).isEmpty()) {
            // the year can be when the episode aired, not when the show began
            list = search("tv", p.title, null);
            loose = true;
        }
        List<JSONObject> cands = titleCandidates("tv", list, p.title);
        if (p.year != null) {
            final List<JSONObject> kept = new ArrayList<>();
            for (final JSONObject c : cands) {
                final int y = yearOf(text(c, "date"));
                if (y != 0 && (loose ? y <= p.year : Math.abs(y - p.year) <= 1)) {
                    kept.add(c);
                }
            }
            cands = kept;
        }
        if (p.country != null) {
            final List<JSONObject> kept = new ArrayList<>();
            for (final JSONObject c : cands) {
                if (strings(c.optJSONArray("country")).contains(p.country)) {
                    kept.add(c);
                }
            }
            cands = kept;
        }
        if (cands.isEmpty()) {
            return notSure("TMDB has no show called “" + p.title + "”", p.title);
        }

        // a show that cannot be checked still blocks one that fits
        final List<Fit> fits = new ArrayList<>();
        String why = "";
        String unresolved = "";
        for (int i = 0; i < Math.min(3, cands.size()); i++) {
            final JSONObject d = tvDetails(cands.get(i).optInt("id"));
            if (d == null) {
                continue;
            }
            final List<Integer> regular = regularSeasons(d);
            Integer season = p.season;
            JSONObject sd = null;
            JSONObject ep = null;
            if (season == null) {
                if (regular.size() == 1) {
                    season = regular.get(0);
                } else if (absolute) {
                    final Absolute abs = absolute(d, p.episode);
                    if (abs.none) {
                        why = "“" + text(d, "name") + "” has no episode " + p.episode;
                        continue;
                    }
                    if (abs.season == null) {
                        why = unresolved = "no season number, and “" + text(d, "name") + "” has several";
                        continue;
                    }
                    season = abs.season;
                    sd = abs.sd;
                    ep = abs.ep;
                } else {
                    why = unresolved = "no season number, and “" + text(d, "name") + "” has several";
                    continue;
                }
            }
            if (sd == null) {
                sd = season(d.optInt("id"), season);
                ep = episode(sd, p.episode);
            }
            if (ep == null) {
                why = "“" + text(d, "name") + "” has no " + code(season, p.episode);
                continue;
            }
            if (!lengthFits(seconds, ep.optInt("runtime"), p.episodes)) {
                why = "the video's length doesn't match the episode";
                continue;
            }
            fits.add(new Fit(d.optInt("votes"), tvMatch(d, ep, season)));
        }
        if (!fits.isEmpty() && !unresolved.isEmpty()) {
            return notSure(unresolved, p.title);
        }
        if (fits.size() == 1) {
            return sure(fits.get(0).match, p.title);
        }
        if (fits.size() > 1) {
            final Fit top = dominant(fits);
            return top != null ? sure(top.match, p.title)
                    : notSure("several shows called “" + p.title + "” fit", p.title);
        }
        return notSure(!unresolved.isEmpty() ? unresolved : !why.isEmpty() ? why : "no matching episode", p.title);
    }

    private Result matchMovie(final NameReading.Parsed p, final double seconds) throws IOException {
        List<JSONObject> list = search("movie", p.title,
                p.year != null ? params("primary_release_year", String.valueOf(p.year)) : null);
        List<JSONObject> cands = titleCandidates("movie", list, p.title);
        if (p.year != null && cands.isEmpty()) {
            // a year off, or released elsewhere first
            list = search("movie", p.title, null);
            cands = titleCandidates("movie", list, p.title);
        }
        if (p.year != null) {
            final List<JSONObject> kept = new ArrayList<>();
            for (final JSONObject c : cands) {
                final int y = yearOf(text(c, "date"));
                if (y != 0 && Math.abs(y - p.year) <= 1) {
                    kept.add(c);
                }
            }
            cands = kept;
        }
        if (cands.isEmpty()) {
            return notSure("TMDB has no film called “" + p.title + "”"
                    + (p.year != null ? " from " + p.year : ""), p.title);
        }

        final List<Fit> fits = new ArrayList<>();
        String why = "";
        for (int i = 0; i < Math.min(3, cands.size()); i++) {
            final JSONObject d = movieDetails(cands.get(i).optInt("id"));
            if (d == null) {
                continue;
            }
            if (!lengthFits(seconds, d.optInt("runtime"), 1)) {
                why = "the video's length doesn't match the film";
                continue;
            }
            // on a plain name an obscure film is more likely a home video
            if (d.optInt("votes") < 10 && !p.tagged) {
                why = "“" + text(d, "title") + "” is too little-known to match on a plain name";
                continue;
            }
            fits.add(new Fit(d.optInt("votes"), movieMatch(d)));
        }
        if (fits.size() == 1) {
            return sure(fits.get(0).match, p.title);
        }
        if (fits.size() > 1) {
            final Fit top = dominant(fits);
            return top != null ? sure(top.match, p.title)
                    : notSure("several films called “" + p.title + "” fit", p.title);
        }
        return notSure(!why.isEmpty() ? why : "no matching film", p.title);
    }

    private static final class Absolute {
        boolean none;
        @Nullable
        Integer season;
        @Nullable
        JSONObject sd;
        @Nullable
        JSONObject ep;
    }

    @Nullable
    private JSONObject tvShape(final int id) throws IOException {
        return tmdb("/tv/" + id, params(), 24, x -> {
            final JSONObject t = new JSONObject();
            try {
                boolean animation = false;
                final JSONArray genres = x.optJSONArray("genres");
                for (int i = 0; genres != null && i < genres.length(); i++) {
                    final JSONObject g = genres.optJSONObject(i);
                    if (g != null && g.optInt("id") == 16) {
                        animation = true;
                    }
                }
                final boolean japanese = "ja".equals(text(x, "original_language"))
                        || strings(x.optJSONArray("origin_country")).contains("JP");
                t.put("anime", animation && japanese);
                t.put("animation", animation);
                final List<JSONObject> regular = new ArrayList<>();
                final JSONArray seasons = x.optJSONArray("seasons");
                for (int i = 0; seasons != null && i < seasons.length(); i++) {
                    final JSONObject s = seasons.optJSONObject(i);
                    if (s != null && s.optInt("season_number") > 0) {
                        regular.add(s);
                    }
                }
                Collections.sort(regular, (a, b) -> a.optInt("season_number") - b.optInt("season_number"));
                final JSONArray array = new JSONArray();
                final JSONArray air = new JSONArray();
                for (final JSONObject s : regular) {
                    array.put(new JSONArray().put(s.optInt("season_number")).put(s.optInt("episode_count", 0)));
                    air.put(text(s, "air_date"));
                }
                t.put("counts", array);
                t.put("air", air);
            } catch (Exception ignored) {
                // only string keys, cannot throw
            }
            return t;
        });
    }

    // an absolute number to TMDB's season and episode (One Piece - 1089)
    private Absolute absolute(final JSONObject d, final int n) throws IOException {
        final Absolute out = new Absolute();
        final JSONObject shape = tvShape(d.optInt("id"));
        if (shape == null) {
            return out;
        }
        final JSONArray counts = shape.optJSONArray("counts");
        int total = 0;
        int longest = 0;
        for (int i = 0; counts != null && i < counts.length(); i++) {
            final int c = counts.optJSONArray(i).optInt(1);
            total += c;
            longest = Math.max(longest, c);
        }
        // far past the show's end, even if TMDB is behind: some other show
        if (n > total * 1.5 + 10) {
            out.none = true;
            return out;
        }
        if (!shape.optBoolean("anime")) {
            return out;
        }
        // only past the longest season; "Title - 12" could be any season
        if (n <= longest || n > total) {
            return out;
        }
        int before = 0;
        for (int i = 0; counts != null && i < counts.length(); i++) {
            final int s = counts.optJSONArray(i).optInt(0);
            final int c = counts.optJSONArray(i).optInt(1);
            if (n <= before + c) {
                final JSONObject sd = season(d.optInt("id"), s);
                final JSONArray episodes = sd == null ? null : sd.optJSONArray("episodes");
                if (episodes == null || episodes.length() == 0) {
                    return out;
                }
                // some seasons carry the numbering on, others start again at 1
                final JSONObject ep = episodes.optJSONObject(0).optInt("n") > 1
                        ? episode(sd, n)
                        : episodes.optJSONObject(n - before - 1);
                if (ep != null) {
                    out.season = s;
                    out.sd = sd;
                    out.ep = ep;
                }
                return out;
            }
            before += c;
        }
        return out;
    }

    // "Breaking Bad - Felina": one episode with that exact title in a well-known show
    private Result matchEpisodeTitle(final String show, final String title, final double seconds,
                                     @Nullable final Integer year) throws IOException {
        final String want = NameReading.norm(title);
        if (want.length() < 3 || GENERIC_TITLE.matcher(title).matches()) {
            return notSure("no matching episode", show);
        }
        final List<JSONObject> cands = titleCandidates("tv", search("tv", show, null), show);
        if (cands.isEmpty()) {
            return notSure("TMDB has no show called “" + show + "”", show);
        }
        final List<Object[]> found = new ArrayList<>();
        String unchecked = "";
        for (int i = 0; i < Math.min(3, cands.size()); i++) {
            final JSONObject d = tvDetails(cands.get(i).optInt("id"));
            if (d == null) {
                continue;
            }
            final List<Integer> regular = regularSeasons(d);
            if (regular.size() > 30) {
                unchecked = "“" + text(d, "name") + "” has too many seasons to search";
                continue;
            }
            for (final int s : regular) {
                final JSONObject sd = season(d.optInt("id"), s);
                final JSONArray episodes = sd == null ? null : sd.optJSONArray("episodes");
                for (int e = 0; episodes != null && e < episodes.length(); e++) {
                    final JSONObject ep = episodes.optJSONObject(e);
                    if (ep != null && NameReading.norm(text(ep, "name")).equals(want)) {
                        found.add(new Object[]{d, ep, s});
                    }
                }
            }
        }
        if (!unchecked.isEmpty()) {
            return notSure(unchecked, show);
        }
        if (found.size() != 1) {
            return notSure(found.isEmpty() ? "no episode called “" + title + "”"
                    : "several episodes called “" + title + "”", show);
        }
        final JSONObject d = (JSONObject) found.get(0)[0];
        final JSONObject ep = (JSONObject) found.get(0)[1];
        final int season = (Integer) found.get(0)[2];
        if (d.optInt("votes") < 100) {
            return notSure("“" + text(d, "name") + "” is too little-known to match on an episode's title", show);
        }
        if (year != null && Math.abs(yearOf(text(ep, "air")) - year) > 1) {
            return notSure("the year doesn't match the episode", show);
        }
        // tighter than usual: a film can run twice an episode's length
        final double ratio = ep.optInt("runtime") > 0 ? seconds / (ep.optInt("runtime") * 60.0) : 1;
        if (ratio < 0.7 || ratio > 1.5) {
            return notSure("the video's length doesn't match the episode", show);
        }
        return sure(tvMatch(d, ep, season), show);
    }

    private Result secondTry(final NameReading.Parsed p, final double seconds, final Result out,
                             final NameReading.Reading reading) throws IOException {
        if (p.episode != null) {
            if (p.season != null || !out.reason.startsWith("no season number, and ")) {
                return out;
            }
            final Result again = matchTv(p, seconds, true);
            return again.match != null ? again : out;
        }
        if (!reading.weak && out.reason.startsWith("TMDB has no film called ")) {
            final Matcher parts = Pattern.compile("^(.+?)\\s+[-–—]\\s+(.+)$").matcher(p.title);
            if (parts.find()) {
                final Result byTitle = matchEpisodeTitle(parts.group(1).trim(), parts.group(2).trim(),
                        seconds, p.year);
                if (byTitle.match != null) {
                    return byTitle;
                }
            }
        }
        return out;
    }

    // title only: a popular film of the right length and nothing else so named
    private Result matchBareTitle(final NameReading.Parsed p, final double seconds) throws IOException {
        final List<JSONObject> films = titleCandidates("movie", search("movie", p.title, null), p.title);
        final List<JSONObject> shows = titleCandidates("tv", search("tv", p.title, null), p.title);
        final List<Fit> fits = new ArrayList<>();
        for (final JSONObject s : shows) {
            fits.add(new Fit(s.optInt("votes"), null));
        }
        for (int i = 0; i < Math.min(3, films.size()); i++) {
            final JSONObject d = movieDetails(films.get(i).optInt("id"));
            final double ratio = d != null && d.optInt("runtime") > 0
                    ? seconds / (d.optInt("runtime") * 60.0) : 0;
            if (ratio >= 0.9 && ratio <= 1.1) {
                fits.add(new Fit(d.optInt("votes"), movieMatch(d)));
            }
        }
        final Fit top = fits.size() == 1 ? fits.get(0) : fits.isEmpty() ? null : dominant(fits);
        if (top == null || top.match == null || top.votes < 100) {
            return notSure("only the title “" + p.title + "” to go on, and that isn't enough", p.title);
        }
        return sure(top.match, p.title);
    }

    @Nullable
    private Result fromImdb(final LinkIds ids, final List<NameReading.Reading> readings) throws IOException {
        final JSONObject f = find(ids.imdb);
        if (f == null) {
            return null;
        }
        final JSONArray episodes = f.optJSONArray("episode");
        if (episodes != null && episodes.length() > 0) {
            final JSONObject x = episodes.optJSONObject(0);
            return exactTv(x.optInt("show"), x.optInt("season"), x.optInt("episode"));
        }
        final JSONArray tv = f.optJSONArray("tv");
        if (tv != null && tv.length() > 0) {
            Integer s = ids.season;
            Integer e = ids.episode;
            if (s == null || e == null) {
                for (final NameReading.Reading rd : readings) {
                    if (rd.p.season != null && rd.p.episode != null) {
                        s = rd.p.season;
                        e = rd.p.episode;
                        break;
                    }
                }
            }
            if (s == null || e == null) {
                return notSure("the link names the show but not the episode", "");
            }
            return exactTv(tv.optInt(0), s, e);
        }
        final JSONArray movie = f.optJSONArray("movie");
        if (movie != null && movie.length() > 0) {
            final JSONObject d = movieDetails(movie.optInt(0));
            return d != null ? sure(movieMatch(d), text(d, "title")) : null;
        }
        return null;
    }

    private Result exactTv(final int showId, final int season, final int episode) throws IOException {
        final JSONObject d = tvDetails(showId);
        final JSONObject sd = d != null ? season(showId, season) : null;
        final JSONObject ep = episode(sd, episode);
        if (ep == null) {
            return notSure("TMDB has no " + code(season, episode)
                    + (d != null ? " of “" + text(d, "name") + "”" : ""), d != null ? text(d, "name") : "");
        }
        return sure(tvMatch(d, ep, season), text(d, "name"));
    }

    // AniList places anime TMDB can't by name; TMDB still gives show, episode, card

    private static final String ANIME_FIELDS = "id format episodes duration popularity"
            + " startDate { year month day } title { romaji english native } synonyms"
            + " relations { edges { relationType node { id type format } } }";
    private static final String ANIME_SEARCH = "query ($search: String) { Page(perPage: 10) {"
            + " media(search: $search, type: ANIME, isAdult: false) { " + ANIME_FIELDS + " } } }";
    private static final String ANIME_BY_ID = "query ($id: Int) { Media(id: $id, type: ANIME) { "
            + ANIME_FIELDS + " } }";
    // names tried on TMDB for each anime, and parts walked back to find the show
    private static final int ANIME_NAMES = 4;
    private static final int ANIME_HOPS = 6;
    // slack between Japan's air date and the one TMDB lists
    private static final int ANIME_DAYS = 2;
    private static final long NO_DAY = Long.MIN_VALUE;

    @Nullable
    private JSONObject anilist(final AnimeSource from, final String key, final String query,
                               final JSONObject variables, final Trim trim) throws IOException {
        final JSONObject hit = cache.get(key, 168);
        if (hit != null) {
            return hit.optJSONObject("v");
        }
        final JSONObject answer = from.ask(query, variables);
        final JSONObject value = answer == null ? null : trim.apply(answer);
        cache.put(key, value);
        return value;
    }

    // trimmed AniList entry; prefers a TV prequel when there are several
    private static JSONObject animeEntry(final JSONObject m) {
        final JSONObject t = new JSONObject();
        try {
            t.put("id", m.optInt("id"));
            t.put("format", text(m, "format"));
            t.put("episodes", m.optInt("episodes", 0));
            t.put("duration", m.optInt("duration", 0));
            t.put("popularity", m.optInt("popularity", 0));
            final JSONObject start = m.optJSONObject("startDate");
            final int year = start == null ? 0 : start.optInt("year", 0);
            final int month = start == null ? 0 : start.optInt("month", 0);
            final int day = start == null ? 0 : start.optInt("day", 0);
            t.put("year", year);
            t.put("start", year > 0 && month > 0 && day > 0
                    ? String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day) : "");
            final List<String> all = new ArrayList<>();
            final JSONObject title = m.optJSONObject("title");
            if (title != null) {
                all.add(text(title, "english"));
                all.add(text(title, "romaji"));
                all.add(text(title, "native"));
            }
            all.addAll(strings(m.optJSONArray("synonyms")));
            final List<String> seen = new ArrayList<>();
            final JSONArray names = new JSONArray();
            for (final String name : all) {
                final String n = NameReading.norm(name);
                if (!n.isEmpty() && !seen.contains(n)) {
                    seen.add(n);
                    names.put(name);
                }
            }
            t.put("names", names);
            int prequel = 0;
            final JSONObject relations = m.optJSONObject("relations");
            final JSONArray edges = relations == null ? null : relations.optJSONArray("edges");
            for (int i = 0; edges != null && i < edges.length(); i++) {
                final JSONObject edge = edges.optJSONObject(i);
                final JSONObject node = edge == null ? null : edge.optJSONObject("node");
                if (node == null || !"PREQUEL".equals(text(edge, "relationType"))
                        || !"ANIME".equals(text(node, "type"))) {
                    continue;
                }
                final boolean tv = text(node, "format").startsWith("TV");
                if (prequel == 0 || tv) {
                    prequel = node.optInt("id");
                }
                if (tv) {
                    break;
                }
            }
            t.put("prequel", prequel);
        } catch (Exception ignored) {
            // only string keys, cannot throw
        }
        return t;
    }

    private List<JSONObject> animeSearch(final AnimeSource from, final String title) throws IOException {
        final JSONObject v = anilist(from, "anilist:search:" + title.toLowerCase(Locale.ROOT), ANIME_SEARCH,
                wrap("search", title), d -> {
                    final JSONObject page = d.optJSONObject("Page");
                    final JSONArray media = page == null ? null : page.optJSONArray("media");
                    final JSONArray trimmed = new JSONArray();
                    for (int i = 0; media != null && i < media.length(); i++) {
                        final JSONObject m = media.optJSONObject(i);
                        if (m != null) {
                            trimmed.put(animeEntry(m));
                        }
                    }
                    return wrap("media", trimmed);
                });
        final List<JSONObject> out = new ArrayList<>();
        final JSONArray media = v == null ? null : v.optJSONArray("media");
        for (int i = 0; media != null && i < media.length(); i++) {
            out.add(media.optJSONObject(i));
        }
        return out;
    }

    @Nullable
    private JSONObject animeById(final AnimeSource from, final int id) throws IOException {
        return anilist(from, "anilist:" + id, ANIME_BY_ID, wrap("id", id), d -> {
            final JSONObject m = d.optJSONObject("Media");
            return m == null ? null : animeEntry(m);
        });
    }

    @Nullable
    private JSONObject animeCalled(final AnimeSource from, final String title, final NameReading.Parsed p,
                                   final double seconds) throws IOException {
        final List<JSONObject> fits = new ArrayList<>();
        for (final JSONObject e : animeSearch(from, title)) {
            if (!NameReading.titleMatches(title, strings(e.optJSONArray("names")))) {
                continue;
            }
            final boolean film = "MOVIE".equals(text(e, "format")) || e.optInt("episodes") == 1;
            if (film != (p.episode == null)) {
                continue;
            }
            if (p.year != null && e.optInt("year") != 0 && Math.abs(e.optInt("year") - p.year) > 1) {
                continue;
            }
            if (!lengthFits(seconds, e.optInt("duration"), p.episode == null ? 1 : p.episodes)) {
                continue;
            }
            fits.add(e);
        }
        if (fits.isEmpty()) {
            return null;
        }
        Collections.sort(fits, (a, b) -> b.optInt("popularity") - a.optInt("popularity"));
        final int second = fits.size() > 1 ? fits.get(1).optInt("popularity") : 0;
        // several of that name: the top one only if it is far better known
        return fits.size() == 1 || fits.get(0).optInt("popularity") >= 10 * second ? fits.get(0) : null;
    }

    private static NameReading.Parsed named(final NameReading.Parsed p, final String title) {
        final NameReading.Parsed q = new NameReading.Parsed();
        q.title = title;
        q.series = title;
        q.year = p.year;
        q.season = p.season;
        q.episode = p.episode;
        q.episodes = p.episodes;
        q.episodeTitle = p.episodeTitle;
        q.country = p.country;
        q.dash = p.dash;
        q.tagged = p.tagged;
        q.bracketed = p.bracketed;
        return q;
    }

    // fallback: TMDB had no answer for the part's own name
    private Result matchAnime(final AnimeSource from, final NameReading.Parsed p, final double seconds,
                              final boolean fallback) throws IOException {
        JSONObject entry = animeCalled(from, p.series, p, seconds);
        // the episode counts from this part's start unless the name gave TMDB's season
        final boolean fromStart = entry != null && p.episode != null && (p.dash || p.season == null);
        if (entry == null && fallback && !NameReading.norm(p.series).equals(NameReading.norm(p.title))) {
            // a part AniList doesn't know: the show's names only, never its dates
            entry = animeCalled(from, p.title, p, seconds);
        }
        if (entry == null) {
            return notSure("AniList has no anime called “" + p.series + "”", "");
        }
        if (fromStart) {
            return fromStart(from, entry, p, seconds);
        }
        if (!fallback) {
            return notSure("AniList's name is only for the show", "");
        }
        final List<String> names = strings(entry.optJSONArray("names"));
        String known = "";
        for (final String name : names.subList(0, Math.min(ANIME_NAMES, names.size()))) {
            if (NameReading.norm(name).equals(NameReading.norm(p.title))) {
                continue;
            }
            final NameReading.Parsed q = named(p, name);
            Result r;
            if (p.episode == null) {
                if (q.year == null && entry.optInt("year") > 0) {
                    q.year = entry.optInt("year");
                }
                r = matchMovie(q, seconds);
            } else {
                r = matchTv(q, seconds, false);
                if (r.match == null && p.season == null && r.reason.startsWith("no season number, and ")) {
                    r = matchTv(q, seconds, true);
                }
            }
            if (r.match != null) {
                return r;
            }
            if (known.isEmpty() && !r.reason.startsWith("TMDB has no ")) {
                known = name;
            }
        }
        return notSure("TMDB has nothing by AniList's names for “" + p.series + "”", known);
    }

    // the episode counted from the day this part began, among TMDB's episodes
    private Result fromStart(final AnimeSource from, final JSONObject entry, final NameReading.Parsed p,
                             final double seconds) throws IOException {
        final String start = text(entry, "start");
        final List<String> own = strings(entry.optJSONArray("names"));
        final String called = own.isEmpty() ? p.series : own.get(0);
        if (start.isEmpty()) {
            return notSure("AniList doesn't know when “" + called + "” began", "");
        }
        // the show on TMDB: by this part's names, else by those of the parts before it
        List<JSONObject> shows = new ArrayList<>();
        String found = "";
        JSONObject part = entry;
        for (int hop = 0; part != null && hop <= ANIME_HOPS; hop++) {
            final List<String> names = strings(part.optJSONArray("names"));
            for (final String name : names.subList(0, Math.min(ANIME_NAMES, names.size()))) {
                shows = titleCandidates("tv", search("tv", name, null), name);
                if (!shows.isEmpty()) {
                    found = name;
                    break;
                }
            }
            if (!shows.isEmpty() || part.optInt("prequel") <= 0) {
                break;
            }
            part = animeById(from, part.optInt("prequel"));
        }
        if (shows.isEmpty()) {
            return notSure("TMDB has no show by AniList's names for “" + called + "”", "");
        }
        final List<Fit> fits = new ArrayList<>();
        String why = "TMDB has no episode of “" + found + "” from " + start;
        for (int i = 0; i < Math.min(3, shows.size()); i++) {
            final JSONObject d = tvDetails(shows.get(i).optInt("id"));
            final JSONObject shape = d == null ? null : tvShape(d.optInt("id"));
            // never a live-action show of the same name
            if (shape == null || !shape.optBoolean("animation", shape.optBoolean("anime"))) {
                continue;
            }
            final Anchor at = episodeFrom(d.optInt("id"), shape, start, p.episode, entry.optInt("episodes"));
            if (at == null) {
                continue;
            }
            final List<String> tmdbNames = strings(d.optJSONArray("alt"));
            tmdbNames.add(text(d, "name"));
            tmdbNames.add(text(d, "original"));
            if (at.oneSeason && !p.bracketed && entry.optInt("prequel") == 0
                    && p.episode <= entry.optInt("episodes") && NameReading.titleMatches(p.title, tmdbNames)) {
                // a plain "Title - 12" by the show's own name could be any of its seasons
                why = "every season of “" + text(d, "name") + "” may have an episode " + p.episode;
                continue;
            }
            if (!lengthFits(seconds, at.ep.optInt("runtime"), p.episodes)) {
                why = "the video's length doesn't match the episode";
                continue;
            }
            fits.add(new Fit(d.optInt("votes"), tvMatch(d, at.ep, at.season)));
        }
        if (fits.size() == 1) {
            return sure(fits.get(0).match, found);
        }
        if (fits.size() > 1) {
            final Fit top = dominant(fits);
            return top != null ? sure(top.match, found) : notSure("several shows called “" + found + "” fit", found);
        }
        return notSure(why, found);
    }

    private static final class Anchor {
        final int season;
        final JSONObject ep;
        // the part is one TMDB season of several
        final boolean oneSeason;

        Anchor(final int season, final JSONObject ep, final boolean oneSeason) {
            this.season = season;
            this.ep = ep;
            this.oneSeason = oneSeason;
        }
    }

    // the n-th episode from the one that aired on start, carried on into later seasons
    @Nullable
    private Anchor episodeFrom(final int id, final JSONObject shape, final String start, final int n,
                               final int partEpisodes) throws IOException {
        final JSONArray counts = shape.optJSONArray("counts");
        final JSONArray air = shape.optJSONArray("air");
        final long day = daysOf(start);
        if (counts == null || air == null || air.length() != counts.length() || day == NO_DAY) {
            return null;
        }
        // the season running then: the last to have begun by that day
        int at = -1;
        for (int i = 0; i < air.length(); i++) {
            final long began = daysOf(air.optString(i));
            if (began != NO_DAY && began <= day + ANIME_DAYS) {
                at = i;
            }
        }
        if (at < 0) {
            return null;
        }
        final JSONObject sd = season(id, counts.optJSONArray(at).optInt(0));
        final JSONArray episodes = sd == null ? null : sd.optJSONArray("episodes");
        int first = -1;
        for (int k = 0; episodes != null && k < episodes.length(); k++) {
            final long aired = daysOf(text(episodes.optJSONObject(k), "air"));
            if (aired != NO_DAY && Math.abs(aired - day) <= ANIME_DAYS) {
                first = k;
                break;
            }
        }
        if (first < 0) {
            return null;
        }
        // later seasons by their counts, so only the one it lands in is asked for
        int index = first + n - 1;
        int i = at;
        int length = episodes.length();
        while (index >= length) {
            index -= length;
            if (++i >= counts.length()) {
                return null;
            }
            length = counts.optJSONArray(i).optInt(1);
            if (length <= 0) {
                return null;
            }
        }
        final int s = counts.optJSONArray(i).optInt(0);
        final JSONObject landed = i == at ? sd : season(id, s);
        final JSONArray list = landed == null ? null : landed.optJSONArray("episodes");
        final JSONObject ep = list == null || index >= list.length() ? null : list.optJSONObject(index);
        final boolean oneSeason = counts.length() > 1 && partEpisodes > 0
                && first + partEpisodes <= episodes.length();
        return ep == null ? null : new Anchor(s, ep, oneSeason);
    }

    // days since 1970 for 2019-04-29 (Howard Hinnant's days_from_civil), or NO_DAY
    static long daysOf(@Nullable final String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return NO_DAY;
        }
        final int month = Integer.parseInt(date.substring(5, 7));
        final int day = Integer.parseInt(date.substring(8, 10));
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return NO_DAY;
        }
        final long y = Integer.parseInt(date.substring(0, 4)) - (month <= 2 ? 1 : 0);
        final long era = (y >= 0 ? y : y - 399) / 400;
        final long yoe = y - era * 400;
        final long doy = (153L * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        final long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    // a fansub "Title - 12", a 2nd Season, or a name TMDB doesn't know
    private static boolean wantsAnime(final NameReading.Parsed p, final Result out) {
        if (out.match != null) {
            // sure, but of a part TMDB may number differently (Title Season 3 Part 2 - 03)
            return p.dash && !NameReading.norm(p.series).equals(NameReading.norm(p.title));
        }
        return p.dash || p.seasonWords || out.reason.startsWith("TMDB has no ")
                || out.reason.startsWith("its season numbering");
    }

    // every name for the file, best first; seconds 0 when the length is unknown
    @NonNull
    public Result lookup(@NonNull final List<NameReading.Reading> readingsIn, @NonNull final LinkIds ids,
                         final double seconds, @Nullable final ServerName server, final boolean httpLink) {
        final List<NameReading.Reading> readings = new ArrayList<>(readingsIn);
        boolean anyUsable = false;
        for (final NameReading.Reading rd : readings) {
            anyUsable |= NameReading.usable(rd);
        }
        if (ids.imdb == null && !anyUsable && httpLink && server != null) {
            final String served = server.fetch();
            if (served != null && !served.isEmpty()) {
                readings.add(NameReading.reading(served, null, "server", false));
            }
        }
        if (ids.imdb != null) {
            try {
                final Result exact = fromImdb(ids, readings);
                if (exact != null) {
                    return exact;
                }
            } catch (IOException ignored) {
                // falls back to the names
            }
        }

        for (final NameReading.Reading rd : readings) {
            if (rd.p.extra) {
                return notSure("it looks like a trailer, sample or extra", "");
            }
        }
        NameReading.Reading primary = null;
        final List<NameReading.Reading> usable = new ArrayList<>();
        for (final NameReading.Reading rd : readings) {
            if (NameReading.usable(rd)) {
                usable.add(rd);
            }
        }
        if (!usable.isEmpty()) {
            primary = usable.get(0);
        }
        if (primary == null) {
            for (final NameReading.Reading rd : readings) {
                final NameReading.Parsed q = rd.p;
                if (rd.weak && rd.source.contains("title") && NameReading.titleOk(q) && !q.dated && !q.lead
                        && q.year == null && q.season == null && q.episode == null && seconds > 0) {
                    try {
                        final Result b = matchBareTitle(q, seconds);
                        if (b.match != null) {
                            b.from = rd.from;
                        }
                        return b;
                    } catch (IOException e) {
                        return notSure("couldn't ask TMDB (" + e.getMessage() + ")", q.title);
                    }
                }
            }
            for (final NameReading.Reading rd : readings) {
                if (NameReading.titleOk(rd.p)) {
                    return notSure("there is no usable name", rd.p.title);
                }
            }
            return notSure("there is no usable name", "");
        }
        final NameReading.Parsed p = primary.p;
        for (int i = 1; i < usable.size(); i++) {
            final NameReading.Parsed o = usable.get(i).p;
            final boolean sameShape = (o.episode != null) == (p.episode != null);
            if (!sameShape) {
                continue;
            }
            if (!NameReading.norm(o.title).equals(NameReading.norm(p.title))
                    || (o.episode != null && (!o.episode.equals(p.episode)
                    || (o.season != null && p.season != null && !o.season.equals(p.season))))) {
                return notSure("its names disagree (“" + p.title + "”, “" + o.title + "”)", p.title);
            }
        }
        Result out;
        if (!(seconds > 0)) {
            out = notSure("its length is unknown (a live stream?)", p.title);
        } else {
            try {
                out = p.episode != null ? matchTv(p, seconds, false) : matchMovie(p, seconds);
            } catch (IOException e) {
                out = notSure("couldn't ask TMDB (" + e.getMessage() + ")", p.title);
            }
            if (out.match == null && !out.reason.startsWith("couldn't ask TMDB")) {
                try {
                    final Result again = secondTry(p, seconds, out, primary);
                    if (again.match != null) {
                        out = again;
                    }
                } catch (IOException ignored) {
                    // the first answer stands
                }
            }
            final AnimeSource from = anime;
            if (from != null && !out.reason.startsWith("couldn't ask TMDB") && wantsAnime(p, out)) {
                try {
                    final Result named = matchAnime(from, p, seconds, out.match == null);
                    if (named.match != null) {
                        named.viaAnime = true;
                        out = named;
                    } else if (out.match == null && !named.query.isEmpty()) {
                        // TMDB's own name for it, for the picker's search box
                        out = notSure(out.reason, named.query);
                    }
                } catch (IOException ignored) {
                    // the answer so far stands
                }
            }
        }
        if (out.match != null) {
            out.from = primary.from;
        } else if (p.season != null && p.episode != null) {
            // so the picker can open this episode once the show is picked
            out.season = p.season;
            out.episode = p.episode;
        }
        return out;
    }
}
