package com.brouken.player.online;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

// the name table again, on the device's own ICU regex engine
@RunWith(AndroidJUnit4.class)
public class NameReadingDeviceTest {

    @Test
    public void everyNameReadsTheSameOnTheDevice() {
        final List<String> failures = new ArrayList<>();
        for (final String[] each : NameReadingCorpus.CASES) {
            final List<String> problems = NameReadingCheck.problems(each[0], each[1]);
            if (!problems.isEmpty()) {
                failures.add(each[0] + " -> " + problems);
            }
        }
        assertEquals(String.join("\n", failures), 0, failures.size());
    }

    @Test
    public void nothingThrowsOnTheDevice() {
        for (final String nasty : NameReadingCorpus.NASTY) {
            NameReading.usable(NameReading.reading(nasty, null, "file name", false));
        }
    }
}
