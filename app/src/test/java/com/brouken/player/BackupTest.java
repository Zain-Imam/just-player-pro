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

    /**
     * Everything the home screen remembers travels with a backup.
     *
     * Named one by one rather than trusted to the rule that says "anything
     * unrecognised is a setting". That rule is what carries them today, and it
     * is one edit away from not doing — a new prefix in KEY_PREFIXES, a name
     * added to NEVER — and a favourite that silently stops being exported is
     * the kind of loss nobody notices until they have already wiped the phone.
     */
    @Test
    public void theHomeScreenIsPartOfTheSettings() throws Exception {
        final Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("homePinnedFolders", "[\"/storage/emulated/0/Films\"]");
        stored.put("homeFolderSort", "SIZE");
        stored.put("homeVideoSort", "RECENT");
        stored.put("startOn", "home");

        final String written = Backup.write(stored, EnumSet.of(Backup.Part.SETTINGS));
        final Map<String, Object> read = Backup.read(written);

        assertNotNull(read);
        assertEquals("[\"/storage/emulated/0/Films\"]", read.get("homePinnedFolders"));
        assertEquals("SIZE", read.get("homeFolderSort"));
        assertEquals("RECENT", read.get("homeVideoSort"));
        assertEquals("home", read.get("startOn"));
    }

    /** And they are not quietly counted as keys or as per-file memory. */
    @Test
    public void theHomeScreenIsNotExportedWithTheKeys() throws Exception {
        final Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("homePinnedFolders", "[\"/storage/emulated/0/Films\"]");
        stored.put("apiKeyTmdb", "secret");

        final String keysOnly = Backup.write(stored, EnumSet.of(Backup.Part.KEYS));
        final Map<String, Object> read = Backup.read(keysOnly);

        assertNotNull(read);
        assertEquals("secret", read.get("apiKeyTmdb"));
        assertNull("a favourite is not a key", read.get("homePinnedFolders"));
    }

    /**
     * A file written by 3.0 still imports, and leaves 4.0's own settings alone.
     *
     * The restore puts back what the file holds and touches nothing else, so a
     * backup made before the home screen existed cannot reset it: the keys are
     * simply not in the document, and what is not in the document is not
     * written.
     */
    @Test
    public void aBackupFromBeforeTheHomeScreenStillReadsBack() {
        final String fromThree = "{\n"
                + "  \"format\": \"just-player-pro-backup\",\n"
                + "  \"version\": 1,\n"
                + "  \"app\": \"3.0.0\",\n"
                + "  \"parts\": [\"settings\"],\n"
                + "  \"values\": {\n"
                + "    \"playbackEngine\": {\"t\": \"s\", \"v\": \"mpv\"},\n"
                + "    \"askResume\": {\"t\": \"b\", \"v\": true}\n"
                + "  }\n"
                + "}";

        final Map<String, Object> read = Backup.read(fromThree);

        assertNotNull("a 3.0 file is still one of ours", read);
        assertEquals("mpv", read.get("playbackEngine"));
        assertEquals(Boolean.TRUE, read.get("askResume"));
        // Nothing about the home screen in it, so nothing about the home screen
        // is written back, and what 4.0 already has survives the import.
        assertFalse(read.containsKey("homePinnedFolders"));
        assertFalse(read.containsKey("startOn"));
    }
}
