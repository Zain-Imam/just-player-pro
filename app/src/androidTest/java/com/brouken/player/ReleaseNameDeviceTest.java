package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/*
 * The same corpus as the plain test, run where it actually matters.
 *
 * Everything downstream of the parser — the title in the history list, the info
 * card, the skip markers, the search box — is only as good as what comes out of
 * it, and it runs on a background thread as a file opens. This proves it does
 * not throw, and gets the right answers, on the device's own regular-expression
 * engine rather than on a laptop's.
 */
@RunWith(AndroidJUnit4.class)
public class ReleaseNameDeviceTest {

    @Test
    public void theCorpusParsesTheSameOnTheDevice() {
        for (final String[] each : ReleaseNameCorpus.CASES) {
            final ReleaseName.Info info = ReleaseName.parse(each[0]);
            assertEquals(each[0], each[1], info.cleanTitle);
        }
    }

    @Test
    public void nothingInTheCorpusThrows() {
        for (final String[] each : ReleaseNameCorpus.CASES) {
            assertNotNull(each[0], ReleaseName.parse(each[0]));
        }
        for (final String nasty : ReleaseNameCorpus.NASTY) {
            assertNotNull(nasty, ReleaseName.parse(nasty));
        }
    }

    @Test
    public void aHashIsNotATitle() {
        assertFalse(ReleaseName.parse("a3f5c9d18e2b4c6f8a0d1e2f3a4b5c6d.mp4").looksLikeTitle());
        assertTrue(ReleaseName.parse("The.Dark.Knight.2008.1080p.mkv").looksLikeTitle());
    }
}
