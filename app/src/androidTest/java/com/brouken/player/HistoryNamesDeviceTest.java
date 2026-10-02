package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

// Proves the recent list keeps a file name once known. Uses its own prefs file.
@RunWith(AndroidJUnit4.class)
public class HistoryNamesDeviceTest {

    private static final Uri NUMBERS = Uri.parse("https://debrid.example/dl/8787887");
    private static final Uri NUMBERS_NEW_TOKEN = Uri.parse("https://debrid.example/dl/8787887?token=2");
    private static final Uri NAMED = Uri.parse("https://cdn.example/files/Batman.2005.1080p.BluRay.mkv");
    private static final String FILE = "Batman.2005.1080p.BluRay.x264.mkv";

    private SharedPreferences preferences;

    @Before
    public void fresh() {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences("historyNamesTest", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @After
    public void tidy() {
        preferences.edit().clear().commit();
    }

    private History.Entry only() {
        final List<History.Entry> entries = History.load(preferences);
        assertEquals(1, entries.size());
        return entries.get(0);
    }

    @Test
    public void aLinkOfNumbersIsOnlyARawName() {
        History.record(preferences, NUMBERS, null);
        assertEquals("8787887", only().name);
        assertEquals(History.NAME_RAW, only().nameKind);
    }

    @Test
    public void aLinkEndingInARealFileNameIsTheFile() {
        History.record(preferences, NAMED, null);
        assertEquals(History.NAME_FILE, only().nameKind);
        // A launcher's title does not replace it.
        History.rename(preferences, NAMED, "Batman Begins", History.NAME_LAUNCHER);
        assertEquals("Batman.2005.1080p.BluRay.mkv", only().name);
    }

    @Test
    public void theFileNameWinsOverTheLaunchersTitle() {
        History.record(preferences, NUMBERS, null);
        History.rename(preferences, NUMBERS, "Batman Begins", History.NAME_LAUNCHER);
        assertEquals("Batman Begins", only().name);
        History.rename(preferences, NUMBERS, FILE, History.NAME_FILE);
        assertEquals(FILE, only().name);
    }

    @Test
    public void onceTheFileIsKnownNothingReplacesIt() {
        History.record(preferences, NUMBERS, null);
        History.rename(preferences, NUMBERS, FILE, History.NAME_FILE);
        History.rename(preferences, NUMBERS, "Batman Begins", History.NAME_LAUNCHER);
        History.rename(preferences, NUMBERS, "Other.2010.720p.mkv", History.NAME_FILE);
        assertEquals(FILE, only().name);
    }

    @Test
    public void openingItAgainKeepsTheNameThePosterAndThatItPlayed() {
        History.record(preferences, NUMBERS, null);
        History.markPlayed(preferences, NUMBERS);
        History.rename(preferences, NUMBERS, FILE, History.NAME_FILE);
        History.setPoster(preferences, NUMBERS, "/poster.jpg");

        // the expired link, opened from the list again
        History.record(preferences, NUMBERS, null);
        assertEquals(FILE, only().name);
        assertEquals("/poster.jpg", only().poster);
        assertTrue("still listed as played", only().played);

        // A fresh token on the same file is the same entry.
        History.record(preferences, NUMBERS_NEW_TOKEN, null);
        assertEquals(FILE, only().name);
    }

    @Test
    public void aNeverConfirmedEntryHasNoPoster() {
        History.record(preferences, NUMBERS, null);
        assertNull(only().poster);
    }

    @Test
    public void anEntryFromBeforeKindsCanStillBeGivenTheFileName() {
        // an old entry: a launcher's title and no kind recorded
        preferences.edit().putString("urlHistory",
                "[{\"uri\":\"https://debrid.example/dl/8787887\",\"name\":\"Batman Begins\","
                        + "\"time\":1,\"played\":true}]").commit();
        assertEquals(History.NAME_LAUNCHER, only().nameKind);
        History.rename(preferences, NUMBERS, FILE, History.NAME_FILE);
        assertEquals(FILE, only().name);
    }
}
