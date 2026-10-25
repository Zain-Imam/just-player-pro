package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TrackMemoryTest {

    private static Format subtitle(final String language, final String label, final boolean forced) {
        return new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage(language)
                .setLabel(label).setSelectionFlags(forced ? C.SELECTION_FLAG_FORCED : 0).build();
    }

    private static Tracks tracks(final Format... formats) {
        final List<Tracks.Group> groups = new ArrayList<>();
        for (final Format f : formats) {
            groups.add(new Tracks.Group(new TrackGroup(f), false, new int[]{C.FORMAT_HANDLED}, new boolean[]{false}));
        }
        return new Tracks(groups);
    }

    private static String labelOf(final TrackMemory.Found found) {
        return found.group.getTrackFormat(found.track).label;
    }

    private static TrackMemory.Pick pick(final Tracks tracks, final int index) {
        final Tracks.Group group = tracks.getGroups().get(index);
        return TrackMemory.of(tracks, C.TRACK_TYPE_TEXT, group, 0);
    }

    @Test
    public void theSameFilmFindsItsTrackByLabel() {
        final Tracks film = tracks(subtitle("en", "English", false), subtitle("en", "English SDH", false),
                subtitle("fr", "Français", false));
        final TrackMemory.Pick sdh = pick(film, 1);
        assertNotNull(sdh);
        assertTrue(sdh.sdh);
        assertEquals("English SDH", labelOf(TrackMemory.find(film, C.TRACK_TYPE_TEXT, sdh, true)));
    }

    // mpv adds an app's subtitles past the third after the film is ready
    @Test
    public void whileSubtitlesAreStillArrivingOnlyThePickItselfCounts() {
        final Format[] all = new Format[12];
        for (int i = 0; i < all.length; i++) {
            all[i] = subtitle(null, "Addon" + (i + 1), false);
        }
        final TrackMemory.Pick addon7 = pick(tracks(all), 6);
        final Tracks firstThree = tracks(all[0], all[1], all[2]);
        assertNull(TrackMemory.findExact(firstThree, C.TRACK_TYPE_TEXT, addon7));
        assertNotNull(TrackMemory.find(firstThree, C.TRACK_TYPE_TEXT, addon7, true));
        assertEquals("Addon7", labelOf(TrackMemory.findExact(tracks(all), C.TRACK_TYPE_TEXT, addon7)));
    }

    @Test
    public void theNextEpisodeFindsTheLanguageInAnotherOrder() {
        final TrackMemory.Pick french = pick(tracks(subtitle("en", "English", false),
                subtitle("fr", "Français", false)), 1);
        final Tracks next = tracks(subtitle("fr", "French", false), subtitle("en", "English", false));
        assertEquals("French", labelOf(TrackMemory.find(next, C.TRACK_TYPE_TEXT, french, false)));
    }

    @Test
    public void forcedAndHearingImpairedAreKeptApart() {
        final TrackMemory.Pick forced = pick(tracks(subtitle("en", "English", false),
                subtitle("en", "English", true)), 1);
        assertTrue(forced.forced);
        final Tracks next = tracks(subtitle("en", "English (Forced)", true), subtitle("en", "English", false),
                subtitle("en", "English [CC]", false));
        assertEquals("English (Forced)", labelOf(TrackMemory.find(next, C.TRACK_TYPE_TEXT, forced, false)));

        final TrackMemory.Pick cc = pick(next, 2);
        assertTrue(cc.sdh);
        final Tracks later = tracks(subtitle("en", "English SDH", false), subtitle("en", "English", false));
        assertEquals("English SDH", labelOf(TrackMemory.find(later, C.TRACK_TYPE_TEXT, cc, false)));
    }

    @Test
    public void offFindsNothing() {
        assertNull(TrackMemory.find(tracks(subtitle("en", "English", false)), C.TRACK_TYPE_TEXT,
                TrackMemory.off(), true));
    }

    @Test
    public void aLanguageTheEpisodeLacksFindsNothing() {
        final TrackMemory.Pick german = pick(tracks(subtitle("de", "Deutsch", false)), 0);
        assertNull(TrackMemory.find(tracks(subtitle("en", "English", false)), C.TRACK_TYPE_TEXT, german, false));
    }

    @Test
    public void theShowIsReadFromTheName() {
        assertEquals("s:the bear", TrackMemory.showKey("The.Bear.S03E05.1080p.WEB.h264-ETHEL.mkv"));
        assertEquals(TrackMemory.showKey("The.Bear.S03E05.mkv"), TrackMemory.showKey("The Bear S03E06.mkv"));
        assertNull(TrackMemory.showKey("Inception.2010.1080p.mkv"));
        assertNull(TrackMemory.showKey(null));
    }

    @Test
    public void picksAreKeptPerFilmAndPerShow() {
        final MemoryPrefs prefs = new MemoryPrefs();
        final TrackMemory.Pick french = pick(tracks(subtitle("en", "English", false),
                subtitle("fr", "Français", false)), 1);
        TrackMemory.save(prefs, C.TRACK_TYPE_TEXT, "film-a", "s:the bear", french);
        TrackMemory.save(prefs, C.TRACK_TYPE_AUDIO, "film-a", null, TrackMemory.off());
        assertEquals("fr", TrackMemory.forFilm(prefs, C.TRACK_TYPE_TEXT, "film-a").language);
        assertEquals("Français", TrackMemory.forShow(prefs, C.TRACK_TYPE_TEXT, "s:the bear").label);
        assertTrue(TrackMemory.forFilm(prefs, C.TRACK_TYPE_AUDIO, "film-a").off);
        assertNull(TrackMemory.forFilm(prefs, C.TRACK_TYPE_TEXT, "film-b"));
        assertNull(TrackMemory.forShow(prefs, C.TRACK_TYPE_AUDIO, "s:the bear"));
    }

    // which go depends on order, which only Android's JSON keeps; here only the count
    @Test
    public void onlyThreeHundredAreKept() throws Exception {
        final MemoryPrefs prefs = new MemoryPrefs();
        for (int i = 0; i < 305; i++) {
            TrackMemory.save(prefs, C.TRACK_TYPE_TEXT, "film-" + i, null, TrackMemory.off());
        }
        assertEquals(300, new org.json.JSONObject(prefs.getString("trackMemory", "{}"))
                .getJSONObject("films").length());
        assertNotNull(TrackMemory.forFilm(prefs, C.TRACK_TYPE_TEXT, "film-304"));
    }

    private static final class MemoryPrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return values;
        }

        @Override
        public String getString(final String key, final String defValue) {
            return values.containsKey(key) ? (String) values.get(key) : defValue;
        }

        @Override
        public Set<String> getStringSet(final String key, final Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(final String key, final int defValue) {
            return defValue;
        }

        @Override
        public long getLong(final String key, final long defValue) {
            return defValue;
        }

        @Override
        public float getFloat(final String key, final float defValue) {
            return defValue;
        }

        @Override
        public boolean getBoolean(final String key, final boolean defValue) {
            return defValue;
        }

        @Override
        public boolean contains(final String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new Editor() {
                @Override
                public Editor putString(final String key, final String value) {
                    values.put(key, value);
                    return this;
                }

                @Override
                public Editor putStringSet(final String key, final Set<String> set) {
                    return this;
                }

                @Override
                public Editor putInt(final String key, final int value) {
                    return this;
                }

                @Override
                public Editor putLong(final String key, final long value) {
                    return this;
                }

                @Override
                public Editor putFloat(final String key, final float value) {
                    return this;
                }

                @Override
                public Editor putBoolean(final String key, final boolean value) {
                    return this;
                }

                @Override
                public Editor remove(final String key) {
                    values.remove(key);
                    return this;
                }

                @Override
                public Editor clear() {
                    values.clear();
                    return this;
                }

                @Override
                public boolean commit() {
                    return true;
                }

                @Override
                public void apply() {
                }
            };
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {
        }
    }
}
