package com.brouken.player.online;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// only the fields a case lists are compared; a case with a title must be usable
public final class NameReadingCheck {

    private NameReadingCheck() {
    }

    public static List<String> problems(final String name, final String want) {
        final NameReading.Reading rd = NameReading.reading(name, Collections.<String>emptyList(),
                "file name", false);
        final NameReading.Parsed got = rd.p;
        final boolean usable = NameReading.usable(rd);
        final List<String> problems = new ArrayList<>();
        boolean saysUsable = false;
        boolean special = false;
        String title = null;
        for (final String pair : want.split(";")) {
            final int eq = pair.indexOf('=');
            final String key = pair.substring(0, eq);
            final String value = pair.substring(eq + 1);
            final String actual;
            switch (key) {
                case "title": actual = got.title; title = value; break;
                case "year": actual = String.valueOf(got.year); break;
                case "season": actual = String.valueOf(got.season); break;
                case "episode": actual = String.valueOf(got.episode); break;
                case "episodes": actual = String.valueOf(got.episodes); break;
                case "country": actual = String.valueOf(got.country); break;
                case "extra": actual = String.valueOf(got.extra); special |= got.extra; break;
                case "dated": actual = String.valueOf(got.dated); special |= "true".equals(value); break;
                case "seasonWords": actual = String.valueOf(got.seasonWords); special |= "true".equals(value); break;
                case "usable": actual = String.valueOf(usable); saysUsable = true; break;
                default: actual = "?"; break;
            }
            if ("extra".equals(key)) {
                special = "true".equals(value);
            }
            if (!value.equals(actual)) {
                problems.add(key + ": wanted " + value + ", got " + actual);
            }
        }
        if (!saysUsable && !special && title != null && !usable) {
            problems.add("wanted usable, got not usable");
        }
        return problems;
    }
}
