package com.brouken.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

// the watched list holds only this app's own marks; Simkl's word is read from Simkl each time
public class WatchedStoreTest {

    @Test
    public void marksCopiedFromAServiceAreDropped() throws Exception {
        final JSONObject kept = Watched.ownMarks(new JSONObject("{"
                + "\"movie:603\":{\"by\":\"app\",\"at\":1},"
                + "\"place:https://example.com/a.mkv\":{\"by\":\"app\",\"at\":1},"
                + "\"movie:604\":{\"by\":\"Simkl\",\"at\":1},"
                + "\"place:https://example.com/b.mkv\":{\"by\":\"Simkl\",\"at\":1},"
                + "\"tv:1:1:1\":{\"by\":null,\"at\":1},"
                + "\"broken\":7}"));
        assertTrue(kept.has("movie:603"));
        assertTrue(kept.has("place:https://example.com/a.mkv"));
        assertFalse(kept.has("movie:604"));
        assertFalse(kept.has("place:https://example.com/b.mkv"));
        assertTrue(kept.has("tv:1:1:1"));
        assertFalse(kept.has("broken"));
    }

    // every copy of a marked film gets the tick, whichever copy was marked
    @Test
    public void theFilmsMarkedLeaveThePlacesOut() throws Exception {
        final java.util.Set<String> films = Watched.filmsIn(new JSONObject("{"
                + "\"movie:45745\":{\"by\":\"app\"},\"place:content://media/1\":{\"by\":\"app\"},"
                + "\"tv:1396:1:2\":{\"by\":\"app\"}}"));
        assertTrue(films.contains("movie:45745"));
        assertTrue(films.contains("tv:1396:1:2"));
        assertFalse(films.contains("place:content://media/1"));
    }

    @Test
    public void anOldMarkWithoutAnOwnerIsThisApps() throws Exception {
        assertTrue(Watched.ownMarks(new JSONObject("{\"movie:603\":{\"at\":1}}")).has("movie:603"));
    }
}
