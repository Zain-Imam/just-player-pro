package com.brouken.player.tracking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

// the /sync/history body Simkl and Trakt both take, as Nuvio sends it
public class TrackingTest {

    @Test
    public void moviesAndEpisodesByTmdbId() throws Exception {
        final JSONObject body = Tracking.historyBody(Arrays.asList("movie:603", "tv:1396:1:5", "bad"), true);
        final JSONObject movie = body.getJSONArray("movies").getJSONObject(0);
        assertEquals(603, movie.getJSONObject("ids").getInt("tmdb"));
        assertTrue(movie.has("watched_at"));
        final JSONObject show = body.getJSONArray("shows").getJSONObject(0);
        assertEquals(1396, show.getJSONObject("ids").getInt("tmdb"));
        final JSONObject season = show.getJSONArray("seasons").getJSONObject(0);
        assertEquals(1, season.getInt("number"));
        assertEquals(5, season.getJSONArray("episodes").getJSONObject(0).getInt("number"));
    }

    @Test
    public void removingCarriesNoTime() throws Exception {
        final JSONObject body = Tracking.historyBody(Arrays.asList("movie:603"), false);
        assertFalse(body.getJSONArray("movies").getJSONObject(0).has("watched_at"));
        assertFalse(body.has("shows"));
    }
}
