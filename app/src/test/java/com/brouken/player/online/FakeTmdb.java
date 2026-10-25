package com.brouken.player.online;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// a fixed TMDB answering from lookup-tmdb.json; counts the requests
final class FakeTmdb implements AutoMatch.Source {

    private static JSONObject data;

    int requests;
    // paths that answer as a server error would
    Predicate<String> failing = path -> false;
    boolean noKey;

    FakeTmdb() {
        if (data == null) {
            try (InputStream in = FakeTmdb.class.getResourceAsStream("/lookup-tmdb.json")) {
                data = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException("lookup-tmdb.json missing", e);
            }
        }
    }

    private static int yearOf(final String date) {
        return date == null || date.length() < 4 ? 0 : Integer.parseInt(date.substring(0, 4));
    }

    private static List<String> ids(final JSONObject index, final String query) {
        final List<String> out = new ArrayList<>();
        final JSONArray array = index.optJSONArray(query);
        for (int i = 0; array != null && i < array.length(); i++) {
            out.add(String.valueOf(array.optInt(i)));
        }
        return out;
    }

    private static JSONArray titles(final JSONArray alt, final String key) throws Exception {
        final JSONArray out = new JSONArray();
        for (int i = 0; alt != null && i < alt.length(); i++) {
            out.put(new JSONObject().put(key, alt.optString(i)));
        }
        return out;
    }

    @Override
    public JSONObject get(final String path, final Map<String, String> params) throws IOException {
        requests++;
        if (noKey) {
            throw new IOException("no TMDB key");
        }
        final String full = path + "?" + new java.util.TreeMap<>(params);
        if (failing.test(full)) {
            throw new IOException("TMDB answered 500");
        }
        try {
            return answer(path, params);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private JSONObject answer(final String path, final Map<String, String> params) throws Exception {
        final JSONObject tv = data.getJSONObject("TV");
        final JSONObject movies = data.getJSONObject("MOVIES");
        final String q = params.containsKey("query") ? params.get("query").toLowerCase(java.util.Locale.ROOT) : "";
        Matcher m;

        if ("/search/tv".equals(path)) {
            final int y = params.containsKey("first_air_date_year")
                    ? Integer.parseInt(params.get("first_air_date_year")) : 0;
            final JSONArray results = new JSONArray();
            for (final String id : ids(data.getJSONObject("TV_SEARCH"), q)) {
                final JSONObject s = tv.getJSONObject(id);
                if (y != 0 && yearOf(s.optString("first_air_date")) != y) {
                    continue;
                }
                results.put(new JSONObject().put("id", Integer.parseInt(id)).put("name", s.optString("name"))
                        .put("original_name", s.optString("original_name"))
                        .put("first_air_date", s.optString("first_air_date"))
                        .put("origin_country", s.optJSONArray("origin_country"))
                        .put("vote_count", s.optInt("vote_count")));
            }
            return new JSONObject().put("results", results);
        }
        if ("/search/movie".equals(path)) {
            final String year = params.containsKey("primary_release_year")
                    ? params.get("primary_release_year") : params.get("year");
            final int y = year == null ? 0 : Integer.parseInt(year);
            final JSONArray results = new JSONArray();
            for (final String id : ids(data.getJSONObject("MOVIE_SEARCH"), q)) {
                final JSONObject mv = movies.getJSONObject(id);
                if (y != 0 && yearOf(mv.optString("release_date")) != y) {
                    continue;
                }
                results.put(new JSONObject().put("id", Integer.parseInt(id)).put("title", mv.optString("title"))
                        .put("original_title", mv.optString("original_title"))
                        .put("release_date", mv.optString("release_date"))
                        .put("vote_count", mv.optInt("vote_count")));
            }
            return new JSONObject().put("results", results);
        }
        if ((m = Pattern.compile("^/tv/(\\d+)$").matcher(path)).find()) {
            final JSONObject s = tv.optJSONObject(m.group(1));
            if (s == null) {
                return null;
            }
            final JSONArray seasons = new JSONArray();
            final JSONObject all = s.getJSONObject("seasons");
            final List<Integer> numbers = new ArrayList<>();
            for (final Iterator<String> it = all.keys(); it.hasNext(); ) {
                numbers.add(Integer.parseInt(it.next()));
            }
            Collections.sort(numbers);
            for (final int n : numbers) {
                seasons.put(new JSONObject().put("season_number", n)
                        .put("episode_count", all.getJSONArray(String.valueOf(n)).length()));
            }
            final JSONArray genres = new JSONArray();
            final JSONArray genreIds = s.optJSONArray("genres");
            if (genreIds == null) {
                genres.put(new JSONObject().put("id", 18));
            } else {
                for (int i = 0; i < genreIds.length(); i++) {
                    genres.put(new JSONObject().put("id", genreIds.optInt(i)));
                }
            }
            return new JSONObject().put("id", Integer.parseInt(m.group(1))).put("name", s.optString("name"))
                    .put("original_name", s.optString("original_name"))
                    .put("first_air_date", s.optString("first_air_date"))
                    .put("origin_country", s.optJSONArray("origin_country"))
                    .put("vote_count", s.optInt("vote_count"))
                    .put("poster_path", s.isNull("poster_path") ? JSONObject.NULL : s.optString("poster_path"))
                    .put("genres", genres).put("original_language", s.optString("lang", "en"))
                    .put("seasons", seasons)
                    .put("alternative_titles", new JSONObject().put("results", titles(s.optJSONArray("alt"), "title")))
                    .put("external_ids", new JSONObject().put("imdb_id", s.optString("imdb")));
        }
        if ((m = Pattern.compile("^/tv/(\\d+)/season/(\\d+)$").matcher(path)).find()) {
            final JSONObject s = tv.optJSONObject(m.group(1));
            final JSONArray list = s == null ? null : s.getJSONObject("seasons").optJSONArray(m.group(2));
            if (list == null) {
                return null;
            }
            return new JSONObject().put("season_number", Integer.parseInt(m.group(2)))
                    .put("poster_path", "/s" + m.group(2) + s.optString("poster_path"))
                    .put("episodes", list);
        }
        if ((m = Pattern.compile("^/movie/(\\d+)$").matcher(path)).find()) {
            final JSONObject mv = movies.optJSONObject(m.group(1));
            if (mv == null) {
                return null;
            }
            return new JSONObject().put("id", Integer.parseInt(m.group(1))).put("title", mv.optString("title"))
                    .put("original_title", mv.optString("original_title"))
                    .put("release_date", mv.optString("release_date")).put("runtime", mv.optInt("runtime"))
                    .put("vote_count", mv.optInt("vote_count")).put("imdb_id", mv.optString("imdb"))
                    .put("alternative_titles", new JSONObject().put("titles", titles(mv.optJSONArray("alt"), "title")));
        }
        if ((m = Pattern.compile("^/find/(tt\\d+)$").matcher(path)).find()) {
            final JSONObject found = new JSONObject().put("movie_results", new JSONArray())
                    .put("tv_results", new JSONArray()).put("tv_episode_results", new JSONArray());
            final JSONObject known = data.getJSONObject("FIND").optJSONObject(m.group(1));
            if (known != null) {
                for (final Iterator<String> it = known.keys(); it.hasNext(); ) {
                    final String key = it.next();
                    found.put(key, known.get(key));
                }
            }
            return found;
        }
        return null;
    }

    static final class MemoryCache implements AutoMatch.Cache {
        private final Map<String, JSONObject> entries = new HashMap<>();

        @Override
        public JSONObject get(final String key, final int hours) {
            return entries.get(key);
        }

        @Override
        public void put(final String key, final JSONObject value) {
            try {
                entries.put(key, new JSONObject().put("v", value == null ? JSONObject.NULL : value));
            } catch (Exception ignored) {
                // only string keys
            }
        }
    }
}
