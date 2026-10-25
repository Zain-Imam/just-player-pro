package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.appcompat.app.AppCompatDelegate;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

// a film's link can change between plays: debrid links get a new token
public class SavedPositionsTest {

    private static final String FILM = "https://cdn.example.com/dl/abc/The.Long.Film.2019.1080p.mkv";

    private static Map<String, Long> saved(final Object... pairs) {
        final Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }

    @Test
    public void theExactLinkWins() {
        final Map<String, Long> map = saved(FILM + "?token=old", 1000L, FILM + "?token=new", 2000L);
        assertEquals(1000L, Prefs.lookup(map, FILM + "?token=old"));
    }

    @Test
    public void aNewTokenFindsTheSameFile() {
        final Map<String, Long> map = saved(FILM + "?token=old", 5000L);
        assertEquals(5000L, Prefs.lookup(map, FILM + "?token=new"));
    }

    @Test
    public void theNewestOfSeveralTokensWins() {
        final Map<String, Long> map = saved(FILM + "?token=a", 1000L, FILM + "?token=b", 9000L);
        assertEquals(9000L, Prefs.lookup(map, FILM + "?token=c"));
    }

    @Test
    public void aRegeneratedLinkFindsTheFileByName() {
        final Map<String, Long> map = saved(FILM, 7000L);
        assertEquals(7000L, Prefs.lookup(map,
                "https://other.example.net/x/9f8e7d/The.Long.Film.2019.1080p.mkv?t=1"));
    }

    @Test
    public void theSameFolderBeatsTheSameName() {
        final Map<String, Long> map = saved(FILM + "?token=a", 1000L,
                "https://elsewhere.example.org/The.Long.Film.2019.1080p.mkv", 3000L);
        assertEquals(1000L, Prefs.lookup(map, FILM + "?token=b"));
    }

    @Test
    public void aGenericNameIsNotMatched() {
        // every HLS stream is called index.m3u8
        final Map<String, Long> map = saved("https://a.example.com/one/index.m3u8", 1000L);
        assertEquals(0L, Prefs.lookup(map, "https://b.example.com/two/index.m3u8"));
    }

    @Test
    public void anIdIsNotMatched() {
        final Map<String, Long> map = saved("https://a.example.com/v/123456.mp4", 1000L);
        assertEquals(0L, Prefs.lookup(map, "https://b.example.com/w/123456.mp4"));
    }

    @Test
    public void nothingSavedIsZero() {
        assertEquals(0L, Prefs.lookup(saved(), FILM));
        assertNull(Prefs.matchingKey(new ArrayList<>(), FILM));
    }

    @Test
    public void aDocumentLinkIsNamedAfterItsFile() {
        assertEquals("The Film (2019).mkv", Prefs.fileName(
                "content://com.android.externalstorage.documents/document/primary%3AMovies%2FThe%20Film%20(2019).mkv"));
        assertEquals("A+B.mkv", Prefs.fileName("https://x.example.com/A+B.mkv"));
    }

    @Test
    public void theQueryAndFragmentAreDropped() {
        assertEquals("https://x.example.com/a.mkv", Prefs.withoutQuery("https://x.example.com/a.mkv?t=1#s"));
        assertEquals("https://x.example.com/a.mkv", Prefs.withoutQuery("https://x.example.com/a.mkv#s?t"));
    }

    @Test
    public void savingAgainMovesAFilmToTheEnd() {
        final LinkedHashMap<String, Long> map = new LinkedHashMap<>();
        Prefs.remember(map, "a", 1L, 3);
        Prefs.remember(map, "b", 2L, 3);
        Prefs.remember(map, "c", 3L, 3);
        // a is still being watched, so b is the one left longest
        Prefs.remember(map, "a", 4L, 3);
        Prefs.remember(map, "d", 5L, 3);
        assertEquals(Arrays.asList("c", "a", "d"), new ArrayList<>(map.keySet()));
        assertEquals(Long.valueOf(4L), map.get("a"));
    }

    @Test
    public void theCapHolds() {
        final LinkedHashMap<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < 150; i++) {
            Prefs.remember(map, "film" + i, (long) i, 100);
        }
        assertEquals(100, map.size());
        assertEquals("film50", map.keySet().iterator().next());
    }

    @Test
    public void themeChoices() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppTheme.mode("dark", false));
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppTheme.mode(null, false));
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppTheme.mode("light", false));
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppTheme.mode("phone", false));
        // a television stays dark whatever was picked on it
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppTheme.mode("light", true));
    }
}
