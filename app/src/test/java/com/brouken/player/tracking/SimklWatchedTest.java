package com.brouken.player.tracking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

// what counts as watched on Simkl's lists, as Nuvio reads them: only a real time, or completed.
// Simkl sends null for what is not watched, which Android's optString turns into "null"
public class SimklWatchedTest {

    private static Set<String> watched(final String json) throws Exception {
        return Simkl.watchedKeys(new JSONObject(json));
    }

    @Test
    public void plannedFilmsAreNotWatched() throws Exception {
        final Set<String> keys = watched("{\"movies\":["
                + "{\"status\":\"plantowatch\",\"last_watched_at\":null,\"movie\":{\"ids\":{\"tmdb\":603}}},"
                + "{\"status\":\"completed\",\"last_watched_at\":null,\"movie\":{\"ids\":{\"tmdb\":604}}},"
                + "{\"status\":\"dropped\",\"last_watched_at\":\"2026-10-01T20:00:00Z\",\"movie\":{\"ids\":{\"tmdb\":605}}},"
                + "{\"status\":\"plantowatch\",\"last_watched_at\":\"null\",\"movie\":{\"ids\":{\"tmdb\":606}}}]}");
        assertEquals(new HashSet<>(Arrays.asList("movie:604", "movie:605")), keys);
    }

    @Test
    public void onlyEpisodesWithATimeAreWatched() throws Exception {
        final Set<String> keys = watched("{\"shows\":[{\"status\":\"watching\",\"show\":{\"ids\":{\"tmdb\":1396}},"
                + "\"seasons\":[{\"number\":1,\"episodes\":["
                + "{\"number\":1,\"watched_at\":\"2026-10-01T20:00:00Z\"},"
                + "{\"number\":2,\"watched_at\":null},"
                + "{\"number\":3}]}]}]}");
        assertEquals(new HashSet<>(Arrays.asList("tv:1396:1:1")), keys);
    }

    @Test
    public void aCompletedShowIsWatchedWhole() throws Exception {
        final Set<String> keys = watched("{\"shows\":[{\"status\":\"completed\",\"show\":{\"ids\":{\"tmdb\":1399}},"
                + "\"seasons\":[]}]}");
        assertTrue(keys.contains("tv:1399"));
        assertTrue(Tracking.covers(keys, "tv:1399:3:9"));
        assertFalse(Tracking.covers(keys, "tv:1400:1:1"));
    }

    @Test
    public void animeUsesTvdbNumbersOnlyWhenGiven() throws Exception {
        final Set<String> keys = watched("{\"anime\":[{\"status\":\"watching\",\"show\":{\"ids\":{\"tmdb\":37854}},"
                + "\"seasons\":[{\"number\":1,\"episodes\":["
                + "{\"number\":62,\"watched_at\":\"2026-10-01T20:00:00Z\",\"tvdb\":{\"season\":3,\"episode\":1}},"
                + "{\"number\":63,\"watched_at\":\"2026-10-01T20:30:00Z\",\"tvdb\":{\"season\":null,\"episode\":null}}]}]}]}");
        assertEquals(new HashSet<>(Arrays.asList("tv:37854:3:1", "tv:37854:1:63")), keys);
    }

    @Test
    public void anAnimeFilmIsAFilm() throws Exception {
        final Set<String> keys = watched("{\"anime\":[{\"status\":\"completed\",\"anime_type\":\"movie\","
                + "\"show\":{\"ids\":{\"tmdb\":129}}}]}");
        assertEquals(new HashSet<>(Arrays.asList("movie:129")), keys);
    }

    @Test
    public void nothingWithoutATmdbId() throws Exception {
        assertTrue(watched("{\"movies\":[{\"status\":\"completed\",\"movie\":{\"ids\":{\"tmdb\":null}}}]}").isEmpty());
    }

    // what Unwatch puts a film back on, Simkl's remove having taken it off every list
    @Test
    public void filmsOnAListButNotWatchedAreKeptWithTheirList() throws Exception {
        final JSONObject listed = Simkl.listedFilms(new JSONObject("{\"movies\":["
                + "{\"status\":\"plantowatch\",\"last_watched_at\":null,\"movie\":{\"ids\":{\"tmdb\":603}}},"
                + "{\"status\":\"dropped\",\"last_watched_at\":null,\"movie\":{\"ids\":{\"tmdb\":604}}},"
                + "{\"status\":\"notinteresting\",\"movie\":{\"ids\":{\"tmdb\":607}}},"
                + "{\"status\":\"dropped\",\"last_watched_at\":\"2026-10-01T20:00:00Z\",\"movie\":{\"ids\":{\"tmdb\":605}}},"
                + "{\"status\":\"completed\",\"movie\":{\"ids\":{\"tmdb\":606}}}],"
                + "\"anime\":[{\"status\":\"plantowatch\",\"anime_type\":\"movie\",\"show\":{\"ids\":{\"tmdb\":129}}},"
                + "{\"status\":\"plantowatch\",\"anime_type\":\"tv\",\"show\":{\"ids\":{\"tmdb\":37854}}}]}"));
        assertEquals("plantowatch", listed.getString("movie:603"));
        assertEquals("dropped", listed.getString("movie:604"));
        assertEquals("dropped", listed.getString("movie:607"));
        assertEquals("plantowatch", listed.getString("movie:129"));
        assertFalse(listed.has("movie:605"));
        assertFalse(listed.has("movie:606"));
        assertEquals(4, listed.length());
    }

    @Test
    public void backOnTheListAsSimklsDocsWriteIt() throws Exception {
        final JSONObject movie = Simkl.listBody("603", "plantowatch").getJSONArray("movies").getJSONObject(0);
        assertEquals("plantowatch", movie.getString("to"));
        assertEquals("603", movie.getJSONObject("ids").getString("tmdb"));
        assertEquals(2, movie.length());
    }

    // Film info shows a Mark or an Unwatch at once, before the list is read again
    @Test
    public void theKeptListFollowsThisAppsChange() {
        final Set<String> keys = new HashSet<>(Arrays.asList("movie:310", "tv:1399", "tv:1399:1:1"));
        assertTrue(Tracking.followChange(keys, "movie:310", false));
        assertFalse(keys.contains("movie:310"));
        assertTrue(Tracking.followChange(keys, "tv:1399:1:1", false));
        assertFalse(Tracking.covers(keys, "tv:1399:1:1"));
        assertTrue(Tracking.followChange(keys, "movie:603", true));
        assertTrue(keys.contains("movie:603"));
        assertFalse(Tracking.followChange(keys, "movie:603", true));
    }

    @Test
    public void onlyTheWriteLockIsSentAgain() {
        assertTrue(new Tracking.HttpError(400, "{\"error\":\"RATE_LIMIT\"}").isWriteLimit());
        assertTrue(new Tracking.HttpError(429, "{\"error\":\"rate_limit\"}").isWriteLimit());
        assertFalse(new Tracking.HttpError(400, "{\"error\":\"empty_field\"}").isWriteLimit());
        assertFalse(new Tracking.HttpError(401, "").isWriteLimit());
    }

    @Test
    public void textTreatsJsonNullAsMissing() throws Exception {
        final JSONObject object = new JSONObject("{\"a\":null,\"b\":\"\",\"c\":\"x\"}");
        assertNull(Tracking.text(object, "a"));
        assertNull(Tracking.text(object, "b"));
        assertNull(Tracking.text(object, "missing"));
        assertEquals("x", Tracking.text(object, "c"));
    }
}
