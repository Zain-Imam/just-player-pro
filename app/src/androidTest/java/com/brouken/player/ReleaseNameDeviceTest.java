package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

// The release name corpus again, on the device's own ICU regex engine.
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
