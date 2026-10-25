package com.brouken.player.online;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RatingsTest {

    private static JSONObject json(final String text) throws Exception {
        return new JSONObject(text);
    }

    @Test
    public void readsTheValuesInTorboxOrder() throws Exception {
        final Map<String, Double> found = Ratings.parse(json("{\"ratings\":["
                + "{\"source\":\"letterboxd\",\"value\":4.2,\"score\":84},"
                + "{\"source\":\"popcorn\",\"value\":91,\"score\":91},"
                + "{\"source\":\"imdb\",\"value\":8.84,\"score\":88},"
                + "{\"source\":\"themoviedb\",\"value\":83,\"score\":83},"
                + "{\"source\":\"rogerebert\",\"value\":3.5,\"score\":88},"
                + "{\"source\":\"metacriticuser\",\"value\":8.1,\"score\":81},"
                + "{\"source\":\"tomatoes\",\"value\":86,\"score\":86},"
                + "{\"source\":\"metacritic\",\"value\":74,\"score\":74},"
                + "{\"source\":\"trakt\",\"value\":87,\"score\":87},"
                + "{\"source\":\"myanimelist\",\"value\":8.1}]}"));
        assertArrayEquals(new String[]{Ratings.IMDB, Ratings.TMDB, Ratings.TOMATOES, Ratings.AUDIENCE,
                        Ratings.METACRITIC, Ratings.TRAKT, Ratings.LETTERBOXD, Ratings.MAL},
                found.keySet().toArray(new String[0]));
        assertEquals(8.8, found.get(Ratings.IMDB), 0.0001);
        assertEquals(83, found.get(Ratings.TMDB), 0.0001);
        assertEquals(91, found.get(Ratings.AUDIENCE), 0.0001);
        // the value, not the 0-100 score
        assertEquals(4.2, found.get(Ratings.LETTERBOXD), 0.0001);
    }

    @Test
    public void acceptsEveryAlias() {
        assertEquals(Ratings.TMDB, Ratings.siteOf("moviedb"));
        assertEquals(Ratings.TOMATOES, Ratings.siteOf("RottenTomatoes"));
        assertEquals(Ratings.AUDIENCE, Ratings.siteOf("audience"));
        assertEquals(Ratings.AUDIENCE, Ratings.siteOf("tomatoesaudience"));
        assertEquals(Ratings.MAL, Ratings.siteOf("mal"));
        assertNull(Ratings.siteOf("rogerebert"));
    }

    @Test
    public void dropsNoScore() throws Exception {
        final Map<String, Double> found = Ratings.parse(json("{\"ratings\":["
                + "{\"source\":\"imdb\",\"value\":null,\"score\":70},"
                + "{\"source\":\"tomatoes\",\"value\":-1},"
                + "{\"source\":\"metacritic\",\"value\":0},"
                + "{\"source\":\"trakt\"},"
                + "{\"source\":\"audience\",\"value\":64}]}"));
        assertEquals(1, found.size());
        assertEquals(64, found.get(Ratings.AUDIENCE), 0.0001);
        assertTrue(Ratings.parse(json("{}")).isEmpty());
    }

    @Test
    public void writesValuesLikeTorbox() {
        final Locale before = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            assertEquals("86%", Ratings.format(Ratings.TOMATOES, 85.6));
            assertEquals("83%", Ratings.format(Ratings.TMDB, 83));
            assertEquals("61%", Ratings.format(Ratings.METACRITIC, 61));
            assertEquals("7", Ratings.format(Ratings.IMDB, 7.0));
            assertEquals("6.9", Ratings.format(Ratings.IMDB, 6.85));
            assertEquals("8.1", Ratings.format(Ratings.MAL, 8.1));
            assertEquals("4.2/5", Ratings.format(Ratings.LETTERBOXD, 4.2));
            assertEquals("4/5", Ratings.format(Ratings.LETTERBOXD, 4.0));
        } finally {
            Locale.setDefault(before);
        }
    }

    private static List<String> line(final List<Ratings.CardScore> scores) {
        final List<String> line = new ArrayList<>();
        for (final Ratings.CardScore score : scores) {
            line.add(score.site + " " + score.value);
        }
        return line;
    }

    @Test
    public void bothScreensShowTheSameLine() {
        final Locale before = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            final Map<String, Double> found = new LinkedHashMap<>();
            found.put(Ratings.IMDB, 7.7);
            found.put(Ratings.TMDB, 83.0);
            found.put(Ratings.TOMATOES, 76.0);
            found.put(Ratings.LETTERBOXD, 4.2);
            final List<String> order = Ratings.parseOrder("letterboxd,tmdb,imdb");
            final Map<String, Double> shown = Ratings.arrange(order,
                    new java.util.HashSet<>(java.util.Collections.singletonList(Ratings.TOMATOES)), found, 7.3);
            final List<Ratings.CardScore> scores = Ratings.cardScores(shown, found);
            assertEquals("[letterboxd 4.2/5, tmdb 83%, imdb 7.7]", line(scores).toString());
            assertEquals(com.brouken.player.R.drawable.rating_tmdb, scores.get(1).icon);
            assertEquals(com.brouken.player.R.drawable.rating_imdb, scores.get(2).icon);
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    public void ownTmdbVoteOnlyWhenMdbListHasNone() {
        final Locale before = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            final Map<String, Double> found = new LinkedHashMap<>();
            found.put(Ratings.IMDB, 7.7);
            final List<String> order = Ratings.parseOrder(null);
            final Map<String, Double> shown = Ratings.arrange(order, new java.util.HashSet<>(), found, 7.3);
            assertEquals("[imdb 7.7, tmdb 73%]", line(Ratings.cardScores(shown, found)).toString());
            final Map<String, Double> none = Ratings.arrange(order, new java.util.HashSet<>(),
                    new LinkedHashMap<>(), 0);
            assertTrue(none.isEmpty());
            final Map<String, Double> off = Ratings.arrange(order,
                    new java.util.HashSet<>(java.util.Collections.singletonList(Ratings.TMDB)), found, 7.3);
            assertFalse(off.containsKey(Ratings.TMDB));
        } finally {
            Locale.setDefault(before);
        }
    }

    // unknown names dropped, repeats ignored, missing sites added in torbox order at the end
    @Test
    public void readsTheStoredOrder() {
        assertEquals(java.util.Arrays.asList(Ratings.SITES), Ratings.parseOrder(null));
        assertEquals(java.util.Arrays.asList(Ratings.MAL, Ratings.IMDB, Ratings.TMDB, Ratings.TOMATOES,
                        Ratings.AUDIENCE, Ratings.METACRITIC, Ratings.TRAKT, Ratings.LETTERBOXD),
                Ratings.parseOrder("mal, rogerebert,mal,imdb"));
    }

    // keywords come as objects or bare strings; the name is after the last '.'
    @Test
    public void readsTheCertificates() throws Exception {
        final Map<String, Double> objects = Ratings.parse(json("{\"ratings\":["
                + "{\"source\":\"tomatoes\",\"value\":93}],"
                + "\"keywords\":[{\"id\":1,\"name\":\"rt.certified-fresh\"},{\"name\":\"heist\"}]}"));
        assertTrue(objects.containsKey(Ratings.CERTIFIED_FRESH));
        assertFalse(objects.containsKey(Ratings.CERTIFIED_HOT));
        final Map<String, Double> strings = Ratings.parse(json("{\"ratings\":["
                + "{\"source\":\"popcorn\",\"value\":91}],\"keywords\":[\"certified-hot\"]}"));
        assertTrue(strings.containsKey(Ratings.CERTIFIED_HOT));
        assertEquals(91, strings.get(Ratings.AUDIENCE), 0.0001);
        // the flags are not scores, so never a chip or a switch
        assertFalse(java.util.Arrays.asList(Ratings.SITES).contains(Ratings.CERTIFIED_HOT));
    }

    @Test
    public void picksNuviosTomatoAndPopcorn() {
        final Map<String, Double> certified = new LinkedHashMap<>();
        certified.put(Ratings.CERTIFIED_FRESH, 1.0);
        certified.put(Ratings.CERTIFIED_HOT, 1.0);
        final Map<String, Double> plain = new LinkedHashMap<>();
        assertEquals(com.brouken.player.R.drawable.rating_tomatoes_certified,
                Ratings.icon(Ratings.TOMATOES, 70, certified));
        assertEquals(com.brouken.player.R.drawable.rating_tomatoes,
                Ratings.icon(Ratings.TOMATOES, 69, certified));
        assertEquals(com.brouken.player.R.drawable.rating_tomatoes,
                Ratings.icon(Ratings.TOMATOES, 95, plain));
        assertEquals(com.brouken.player.R.drawable.rating_tomatoes_rotten,
                Ratings.icon(Ratings.TOMATOES, 59, certified));
        assertEquals(com.brouken.player.R.drawable.rating_audience_hot,
                Ratings.icon(Ratings.AUDIENCE, 80, certified));
        assertEquals(com.brouken.player.R.drawable.rating_audience,
                Ratings.icon(Ratings.AUDIENCE, 79, certified));
        assertEquals(com.brouken.player.R.drawable.rating_audience,
                Ratings.icon(Ratings.AUDIENCE, 90, plain));
        assertEquals(com.brouken.player.R.drawable.rating_audience_stale,
                Ratings.icon(Ratings.AUDIENCE, 59, plain));
    }

    // a show's own IMDb id, never the episode's
    @Test
    public void asksForTheFilmOrTheShow() {
        final Identity episode = new Identity(true, 1396, "tt0959621", "tt0903747", "Breaking Bad",
                "Pilot", 1, 1, "2008", null, null, null, 8.9);
        assertEquals("tt0903747", Ratings.imdbIdOf(episode));
        final Identity film = new Identity(false, 27205, "tt1375666", null, "Inception",
                null, null, null, "2010", null, null, null, 8.4);
        assertEquals("tt1375666", Ratings.imdbIdOf(film));
        final Identity unknown = new Identity(false, 27205, "nonsense", null, "Inception",
                null, null, null, "2010", null, null, null, 8.4);
        assertNull(Ratings.imdbIdOf(unknown));
    }

    @Test
    public void keepsTheOldAudienceSwitch() {
        assertEquals("mdblistShow_popcorn", Ratings.siteKey(Ratings.AUDIENCE));
        assertEquals("mdblistShow_tmdb", Ratings.siteKey(Ratings.TMDB));
    }

    @Test
    public void readsAShowsCastFromItsRoles() throws Exception {
        final List<Tmdb.CastMember> cast = new ArrayList<>();
        Tmdb.readCast(json("{\"cast\":["
                + "{\"name\":\"Bryan Cranston\",\"profile_path\":\"/a.jpg\","
                + "\"roles\":[{\"character\":\"Walter White\"}]},"
                + "{\"name\":\"\",\"roles\":[]},"
                + "{\"name\":\"Aaron Paul\",\"profile_path\":null,\"roles\":[]}]}"), cast);
        assertEquals(2, cast.size());
        assertEquals("Walter White", cast.get(0).character);
        assertEquals("/a.jpg", cast.get(0).profilePath);
        assertNull(cast.get(1).character);
        assertNull(cast.get(1).profilePath);
    }
}
