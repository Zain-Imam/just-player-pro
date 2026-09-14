package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a backup must survive: a round trip.
 *
 * The failure that matters in a file like this is not a crash, it is a value
 * that comes back subtly different from the one that went in -- a float landing
 * as a double, a boolean as the string "true" -- because the player then reads
 * it back and throws at some unrelated moment weeks later.
 */
public class BackupTest {

    private static Map<String, Object> everything() {
        final Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("playbackEngine", "mpv");
        stored.put("speed", 1.25f);
        stored.put("doubleTapSeekSeconds", 10);
        stored.put("autoPiP", true);
        stored.put("apiKeyTmdb", "a-key");
        stored.put("subtitleAddons", "[{\"name\":\"one\"}]");
        stored.put("urlHistory", "[{\"uri\":\"http://x/y\"}]");
        stored.put("onlineIdentities", "{\"film\":1}");
        stored.put("subtitleDelayMap", "[{\"name\":\"film.mkv\",\"delay\":300}]");
        stored.put("audioDelayMap", "[{\"name\":\"film.mkv\",\"delay\":-200}]");
        stored.put("speedMap", "[{\"name\":\"film.mkv\",\"delay\":150}]");
        final Set<String> languages = new LinkedHashSet<>();
        languages.add("en");
        languages.add("ur");
        stored.put("languagesSubtitle", languages);
        // Never exported, whatever is asked for.
        stored.put("scopeUri", "content://tree/primary%3AMovies");
        stored.put("mediaUri", "content://media/external/video/media/1");
        return stored;
    }

    @Test
    public void everythingComesBackAsItWentIn() throws Exception {
        final Map<String, Object> stored = everything();
        final String json = Backup.write(stored, EnumSet.allOf(Backup.Part.class));

        final Map<String, Object> back = Backup.read(json);
        assertNotNull("a file we wrote must be one we recognise", back);

        for (final Map.Entry<String, Object> entry : stored.entrySet()) {
            if ("scopeUri".equals(entry.getKey()) || "mediaUri".equals(entry.getKey())) {
                continue;
            }
            final Object restored = back.get(entry.getKey());
            assertNotNull("missing after the round trip: " + entry.getKey(), restored);
            assertEquals("changed type: " + entry.getKey(),
                    entry.getValue().getClass(), restored.getClass());
            assertEquals("changed value: " + entry.getKey(), entry.getValue(), restored);
        }
    }

    @Test
    public void whatCannotTravelIsNotWritten() throws Exception {
        final String json = Backup.write(everything(), EnumSet.allOf(Backup.Part.class));
        assertFalse("a folder grant cannot be given to another device",
                json.contains("scopeUri"));
        assertFalse("where this device was up to is not a setting",
                json.contains("mediaUri"));
    }

    @Test
    public void partsAreKeptApart() throws Exception {
        final String keysOnly = Backup.write(everything(), EnumSet.of(Backup.Part.KEYS));
        assertTrue(keysOnly.contains("apiKeyTmdb"));
        assertTrue(keysOnly.contains("subtitleAddons"));
        assertFalse("a keys-only export must not carry the settings",
                keysOnly.contains("playbackEngine"));
        assertFalse("nor the history", keysOnly.contains("urlHistory"));
        assertFalse("nor one person's delays", keysOnly.contains("audioDelayMap"));

        final String settingsOnly = Backup.write(everything(), EnumSet.of(Backup.Part.SETTINGS));
        assertTrue(settingsOnly.contains("playbackEngine"));
        assertFalse("a settings export must not carry somebody's keys",
                settingsOnly.contains("apiKeyTmdb"));

        final String perFile = Backup.write(everything(), EnumSet.of(Backup.Part.PER_FILE));
        assertTrue(perFile.contains("subtitleDelayMap"));
        assertTrue(perFile.contains("speedMap"));
        assertFalse(perFile.contains("playbackEngine"));
    }

    @Test
    public void somebodyElsesFileIsRefused() {
        assertNull(Backup.read("{\"format\":\"something-else\",\"values\":{}}"));
        assertNull(Backup.read("not json at all"));
        assertNull(Backup.read(""));
    }

    @Test
    public void anEmptyBackupIsRecognisedRatherThanRefused() throws Exception {
        final String json = Backup.write(new LinkedHashMap<>(), EnumSet.allOf(Backup.Part.class));
        final Map<String, Object> back = Backup.read(json);
        assertNotNull("ours, and empty, is not the same as not ours", back);
        assertTrue(back.isEmpty());
    }
}
