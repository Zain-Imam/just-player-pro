package com.brouken.player.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// a small made-up TMDB and AniList: Attack on Titan, its parts and a film
public class AnimeMatchTest {

    private static final double EPISODE = 1440;

    // TMDB: the anime, a live-action show of the same name, and a film
    private static final class Tmdb implements AutoMatch.Source {
        final Map<String, JSONObject> shows = new HashMap<>();
        final Map<String, JSONArray> seasons = new HashMap<>();
        final Map<String, int[]> tvSearch = new HashMap<>();
        final Map<String, int[]> movieSearch = new HashMap<>();
        final Map<String, JSONObject> movies = new HashMap<>();

        Tmdb() throws Exception {
            final JSONArray aot = new JSONArray();
            aot.put(season(1, weekly("2013-04-07", 25, 1)));
            aot.put(season(2, weekly("2017-04-01", 12, 1)));
            final JSONArray third = weekly("2018-07-23", 12, 1);
            final JSONArray part2 = weekly("2019-04-29", 10, 13);
            for (int i = 0; i < part2.length(); i++) {
                third.put(part2.get(i));
            }
            aot.put(season(3, third));
            aot.put(season(4, weekly("2020-12-07", 16, 1)));
            shows.put("1429", show(1429, "Attack on Titan", "進撃の巨人", "2013-04-07", 16, "JP", 6000, aot));
            final JSONArray live = new JSONArray().put(season(1, weekly("2022-01-01", 8, 1)));
            shows.put("9999", show(9999, "Attack on Titan", "Attack on Titan", "2022-01-01", 18, "US", 40, live));
            tvSearch.put("attack on titan", new int[]{1429, 9999});
            tvSearch.put("進撃の巨人", new int[]{1429});
            movies.put("372058", new JSONObject().put("id", 372058).put("title", "Your Name.")
                    .put("original_title", "君の名は。").put("release_date", "2016-08-26").put("runtime", 106)
                    .put("vote_count", 10000));
            movieSearch.put("your name.", new int[]{372058});
        }

        private JSONObject season(final int n, final JSONArray episodes) throws Exception {
            return new JSONObject().put("n", n).put("episodes", episodes);
        }

        private static JSONArray weekly(final String first, final int count, final int from) throws Exception {
            final JSONArray out = new JSONArray();
            LocalDate day = LocalDate.parse(first);
            for (int i = 0; i < count; i++) {
                out.put(new JSONObject().put("episode_number", from + i).put("name", "Episode " + (from + i))
                        .put("air_date", day.toString()).put("runtime", 24));
                day = day.plusWeeks(1);
            }
            return out;
        }

        private JSONObject show(final int id, final String name, final String original, final String first,
                                final int genre, final String country, final int votes,
                                final JSONArray list) throws Exception {
            final JSONArray summary = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                final JSONObject s = list.getJSONObject(i);
                final JSONArray episodes = s.getJSONArray("episodes");
                seasons.put(id + "/" + s.getInt("n"), episodes);
                summary.put(new JSONObject().put("season_number", s.getInt("n"))
                        .put("episode_count", episodes.length())
                        .put("air_date", episodes.getJSONObject(0).getString("air_date")));
            }
            return new JSONObject().put("id", id).put("name", name).put("original_name", original)
                    .put("first_air_date", first).put("origin_country", new JSONArray().put(country))
                    .put("original_language", "JP".equals(country) ? "ja" : "en").put("vote_count", votes)
                    .put("genres", new JSONArray().put(new JSONObject().put("id", genre)))
                    .put("seasons", summary);
        }

        @Override
        public JSONObject get(final String path, final Map<String, String> params) throws IOException {
            try {
                return answer(path, params);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        private JSONObject answer(final String path, final Map<String, String> params) throws Exception {
            final String q = params.containsKey("query") ? params.get("query").toLowerCase(Locale.ROOT) : "";
            Matcher m;
            if ("/search/tv".equals(path)) {
                final JSONArray results = new JSONArray();
                for (final int id : tvSearch.getOrDefault(q, new int[0])) {
                    final JSONObject s = shows.get(String.valueOf(id));
                    results.put(new JSONObject().put("id", id).put("name", s.get("name"))
                            .put("original_name", s.get("original_name")).put("first_air_date", s.get("first_air_date"))
                            .put("origin_country", s.get("origin_country")).put("vote_count", s.get("vote_count")));
                }
                return new JSONObject().put("results", results);
            }
            if ("/search/movie".equals(path)) {
                final JSONArray results = new JSONArray();
                for (final int id : movieSearch.getOrDefault(q, new int[0])) {
                    final JSONObject f = movies.get(String.valueOf(id));
                    results.put(new JSONObject().put("id", id).put("title", f.get("title"))
                            .put("original_title", f.get("original_title")).put("release_date", f.get("release_date"))
                            .put("vote_count", f.get("vote_count")));
                }
                return new JSONObject().put("results", results);
            }
            if ((m = Pattern.compile("^/tv/(\\d+)$").matcher(path)).find()) {
                return shows.get(m.group(1));
            }
            if ((m = Pattern.compile("^/tv/(\\d+)/season/(\\d+)$").matcher(path)).find()) {
                final JSONArray episodes = seasons.get(m.group(1) + "/" + m.group(2));
                return episodes == null ? null : new JSONObject().put("episodes", episodes);
            }
            if ((m = Pattern.compile("^/movie/(\\d+)$").matcher(path)).find()) {
                return movies.get(m.group(1));
            }
            return null;
        }
    }

    // AniList: the anime's parts and the film; counts what it is asked
    private static final class Anilist implements AutoMatch.AnimeSource {
        final Map<Integer, JSONObject> media = new HashMap<>();
        final Map<String, int[]> search = new HashMap<>();
        int asked;
        boolean down;

        Anilist() throws Exception {
            add(16498, "Shingeki no Kyojin", "Attack on Titan", "進撃の巨人", "TV", 25, 24, 900000, "2013-04-07", 0);
            add(20958, "Shingeki no Kyojin Season 2", "Attack on Titan Season 2", "進撃の巨人 Season2", "TV", 12, 24,
                    600000, "2017-04-01", 16498);
            add(99147, "Shingeki no Kyojin Season 3", "Attack on Titan Season 3", "進撃の巨人 Season3", "TV", 12, 24,
                    550000, "2018-07-23", 20958);
            add(104578, "Shingeki no Kyojin Season 3 Part 2", "Attack on Titan Season 3 Part 2",
                    "進撃の巨人 Season3 Part.2", "TV", 10, 24, 500000, "2019-04-29", 99147);
            add(21519, "Kimi no Na wa.", "Your Name.", "君の名は。", "MOVIE", 1, 106, 700000, "2016-08-26", 0);
            add(5555, "Hoshi no Uta", null, "星の歌", "TV", 12, 24, 3000, "2015-01-01", 0);
            add(7777, "Kyojin Gaiden", "Attack on Titan", null, "TV", 12, 24, 100, "2099-01-01", 0);
            search.put("shingeki no kyojin", new int[]{16498, 20958, 99147, 104578});
            search.put("shingeki no kyojin season 3 part 2", new int[]{104578, 99147});
            search.put("attack on titan season 3 part 2", new int[]{104578});
            search.put("attack on titan", new int[]{16498, 7777});
            search.put("kimi no na wa", new int[]{21519});
            search.put("hoshi no uta", new int[]{5555});
            search.put("kyojin gaiden", new int[]{7777});
        }

        private void add(final int id, final String romaji, final String english, final String nativeName,
                         final String format, final int episodes, final int duration, final int popularity,
                         final String start, final int prequel) throws Exception {
            final LocalDate day = LocalDate.parse(start);
            final JSONArray edges = new JSONArray();
            if (prequel > 0) {
                edges.put(new JSONObject().put("relationType", "PREQUEL").put("node",
                        new JSONObject().put("id", prequel).put("type", "ANIME").put("format", "TV")));
            }
            media.put(id, new JSONObject().put("id", id).put("format", format).put("episodes", episodes)
                    .put("duration", duration).put("popularity", popularity)
                    .put("startDate", new JSONObject().put("year", day.getYear()).put("month", day.getMonthValue())
                            .put("day", day.getDayOfMonth()))
                    .put("title", new JSONObject().put("romaji", romaji)
                            .put("english", english == null ? JSONObject.NULL : english)
                            .put("native", nativeName == null ? JSONObject.NULL : nativeName))
                    .put("synonyms", new JSONArray())
                    .put("relations", new JSONObject().put("edges", edges)));
        }

        @Override
        public JSONObject ask(final String query, final JSONObject variables) throws IOException {
            asked++;
            if (down) {
                throw new IOException("AniList answered 429");
            }
            try {
                if (variables.has("search")) {
                    final JSONArray found = new JSONArray();
                    for (final int id : search.getOrDefault(
                            variables.getString("search").toLowerCase(Locale.ROOT), new int[0])) {
                        found.put(media.get(id));
                    }
                    return new JSONObject().put("Page", new JSONObject().put("media", found));
                }
                final JSONObject one = media.get(variables.getInt("id"));
                return one == null ? null : new JSONObject().put("Media", one);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    private static AutoMatch.Result lookup(final AutoMatch matcher, final String file, final double seconds) {
        final String url = "file:///storage/emulated/0/Anime/" + file;
        final List<NameReading.Reading> readings = Readings.of(url, null, Collections.<String>emptyList(), null);
        return matcher.lookup(readings, Readings.ids(url, null), seconds, null, false);
    }

    private static AutoMatch matcher(final Anilist anilist) throws Exception {
        final AutoMatch matcher = new AutoMatch(new Tmdb(), new FakeTmdb.MemoryCache());
        matcher.useAnime(anilist);
        return matcher;
    }

    @Test
    public void aRomajiNameFindsTheShowByItsEnglishName() throws Exception {
        final AutoMatch.Result r = lookup(matcher(new Anilist()),
                "[SubsPlease] Shingeki no Kyojin - 05 (1080p) [ABCD1234].mkv", EPISODE);
        assertNotNull(r.reason, r.match);
        assertEquals(1429, r.match.tmdbId);
        assertEquals("S01E05", r.match.code());
        assertTrue(r.viaAnime);
    }

    @Test
    public void aPartCountsFromTheDayItBegan() throws Exception {
        final AutoMatch.Result r = lookup(matcher(new Anilist()),
                "[Erai-raws] Shingeki no Kyojin Season 3 Part 2 - 03 [1080p].mkv", EPISODE);
        assertNotNull(r.reason, r.match);
        assertEquals("S03E15", r.match.code());
    }

    @Test
    public void numbersPastTheFirstSeasonCarryOn() throws Exception {
        final AutoMatch.Result r = lookup(matcher(new Anilist()), "Shingeki no Kyojin - 30.mkv", EPISODE);
        assertNotNull(r.reason, r.match);
        assertEquals("S02E05", r.match.code());
    }

    @Test
    public void aFilmFindsItsEnglishName() throws Exception {
        final AutoMatch.Result r = lookup(matcher(new Anilist()),
                "[Group] Kimi no Na wa (2016) [BD 1080p].mkv", 106 * 60);
        assertNotNull(r.reason, r.match);
        assertEquals(372058, r.match.tmdbId);
        assertFalse(r.match.isSeries);
    }

    // TMDB read it as S03E03; the part's own start puts it at S03E15
    @Test
    public void aPartBeatsTheLiteralSeason() throws Exception {
        final AutoMatch.Result r = lookup(matcher(new Anilist()),
                "[Erai-raws] Attack on Titan Season 3 Part 2 - 03 [1080p].mkv", EPISODE);
        assertNotNull(r.reason, r.match);
        assertEquals("S03E15", r.match.code());
    }

    @Test
    public void aPartAniListDoesNotKnowLeavesTmdbsAnswer() throws Exception {
        final AutoMatch.Result r = lookup(matcher(new Anilist()),
                "[Erai-raws] Attack on Titan Season 3 Extra - 03 [1080p].mkv", EPISODE);
        assertNotNull(r.reason, r.match);
        assertEquals("S03E03", r.match.code());
        assertFalse(r.viaAnime);
    }

    // by the show's own name, without a group's tag, "- 12" could be any season's
    @Test
    public void aPlainTwelfthStaysUnsure() throws Exception {
        final AutoMatch.Result r = lookup(matcher(new Anilist()), "Attack on Titan - 12.mkv", EPISODE);
        assertNull(r.match == null ? null : r.match.code(), r.match);
    }

    @Test
    public void aFansubTwelfthIsTheFirstPart() throws Exception {
        final AutoMatch.Result r = lookup(matcher(new Anilist()), "[SubsPlease] Attack on Titan - 12 (1080p).mkv",
                EPISODE);
        assertNotNull(r.reason, r.match);
        assertEquals("S01E12", r.match.code());
    }

    @Test
    public void offMeansTmdbAlone() throws Exception {
        final AutoMatch matcher = new AutoMatch(new Tmdb(), new FakeTmdb.MemoryCache());
        final AutoMatch.Result r = lookup(matcher, "[SubsPlease] Shingeki no Kyojin - 05 (1080p).mkv", EPISODE);
        assertNull(r.match);
        assertTrue(r.reason, r.reason.startsWith("TMDB has no show called"));
    }

    @Test
    public void aniListDownLeavesTheAnswerAsItWas() throws Exception {
        final Anilist anilist = new Anilist();
        anilist.down = true;
        final AutoMatch.Result r = lookup(matcher(anilist),
                "[SubsPlease] Shingeki no Kyojin - 05 (1080p).mkv", EPISODE);
        assertNull(r.match);
        assertTrue(r.reason, r.reason.startsWith("TMDB has no show called"));
        assertEquals("Shingeki no Kyojin", r.query);
    }

    @Test
    public void theWrongLengthIsNoMatch() throws Exception {
        assertNull(lookup(matcher(new Anilist()),
                "[SubsPlease] Shingeki no Kyojin - 05 (1080p).mkv", 3000).match);
    }

    @Test
    public void anAnimeTmdbLacksStaysUnmatched() throws Exception {
        final AutoMatch.Result r = lookup(matcher(new Anilist()), "[Group] Hoshi no Uta - 03 [720p].mkv", EPISODE);
        assertNull(r.match);
        assertEquals("Hoshi no Uta", r.query);
    }

    // the show was found but not the day: the picker opens on TMDB's name for it
    @Test
    public void thePickerGetsTmdbsName() throws Exception {
        final AutoMatch.Result r = lookup(matcher(new Anilist()), "[Group] Kyojin Gaiden - 03 [720p].mkv", EPISODE);
        assertNull(r.match);
        assertEquals("Attack on Titan", r.query);
    }

    @Test
    public void aNameTmdbKnowsIsNeverSent() throws Exception {
        final Anilist anilist = new Anilist();
        final AutoMatch.Result r = lookup(matcher(anilist), "Attack.on.Titan.S01E05.1080p.mkv", EPISODE);
        assertNotNull(r.reason, r.match);
        assertEquals("S01E05", r.match.code());
        assertEquals(0, anilist.asked);
    }

    @Test
    public void theNextEpisodeAsksAniListNothing() throws Exception {
        final Anilist anilist = new Anilist();
        final AutoMatch matcher = matcher(anilist);
        assertEquals("S01E05", lookup(matcher, "[SubsPlease] Shingeki no Kyojin - 05 (1080p).mkv", EPISODE)
                .match.code());
        final int before = anilist.asked;
        assertEquals("S01E06", lookup(matcher, "[SubsPlease] Shingeki no Kyojin - 06 (1080p).mkv", EPISODE)
                .match.code());
        assertEquals(before, anilist.asked);
    }

    @Test
    public void theWholeNameBeforeTheDashIsKept() {
        final NameReading.Parsed part = NameReading.parse("[Erai-raws] Shingeki no Kyojin Season 3 Part 2 - 03 [1080p].mkv");
        assertEquals("Shingeki no Kyojin", part.title);
        assertEquals("Shingeki no Kyojin Season 3 Part 2", part.series);
        final NameReading.Parsed bracketed =
                NameReading.parse("[SubsPlease] Shingeki no Kyojin (The Final Season) - 28 (1080p) [ABCD1234].mkv");
        assertEquals("Shingeki no Kyojin The Final Season", bracketed.series);
        assertEquals("Breaking Bad", NameReading.parse("Breaking.Bad.S01E01.mkv").series);
    }

    @Test
    public void daysCountFrom1970() {
        assertEquals(0, AutoMatch.daysOf("1970-01-01"));
        assertEquals(18015, AutoMatch.daysOf("2019-04-29"));
        assertEquals(1, AutoMatch.daysOf("2000-03-01") - AutoMatch.daysOf("2000-02-29"));
        assertEquals(Long.MIN_VALUE, AutoMatch.daysOf("2019-4-29"));
        assertEquals(Long.MIN_VALUE, AutoMatch.daysOf(""));
    }
}
