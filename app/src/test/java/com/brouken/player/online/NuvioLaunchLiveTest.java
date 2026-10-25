package com.brouken.player.online;

import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

// live TMDB, only with JPP_LIVE_TMDB=1; Nuvio hands over a title and nothing else
public class NuvioLaunchLiveTest {

    private static final String OPAQUE = "https://store-1.example-cdn.st/dld/8f3a2c91e0b7?token=x";

    private static String key() {
        try {
            for (final String line : Files.readAllLines(Paths.get("..", ".env"))) {
                final String trimmed = line.trim();
                if (trimmed.startsWith("TMDB_API_KEY")) {
                    return trimmed.replaceFirst("^TMDB_API_KEY\\s*[=:]\\s*", "").replaceAll("^['\"]|['\"]$", "").trim();
                }
            }
        } catch (IOException ignored) {
            // no .env
        }
        return null;
    }

    private static final Object[][] CASES = {
            // link, launch title, minutes, the server's file name
            {OPAQUE, "Inception", 148, null},
            {OPAQUE, "Inception", 148, "download"},
            {OPAQUE, "Inception", 148, "Inception.2010.1080p.BluRay.x264.mkv"},
            {"https://n1.download.example-debrid.com/d/ABC123/Inception.2010.1080p.BluRay.x264-SPARKS.mkv", "Inception", 148, null},
            {OPAQUE, "Dune", 155, null},
            {OPAQUE, "Dune: Part Two", 166, null},
            {OPAQUE, "Oppenheimer", 180, null},
            {OPAQUE, "Spider-Man: No Way Home", 148, null},
            {OPAQUE, "The Batman", 176, null},
            {OPAQUE, "Avatar: The Way of Water", 192, null},
            {OPAQUE, "Gladiator II", 148, null},
            {OPAQUE, "Breaking Bad - S01E02", 48, null},
            {OPAQUE, "Breaking Bad - S01E02 - Cat's in the Bag...", 48, null},
            {OPAQUE, "The Office - S02E03", 22, null},
            {OPAQUE, "Shōgun - S01E01", 70, null},
            {OPAQUE, "Stranger Things - S04E01", 78, null},
            {OPAQUE, "Wednesday - S01E01", 59, null},
            {OPAQUE, "The Boys - S04E01", 60, null},
            {OPAQUE, "House of the Dragon - S02E01", 59, null},
    };

    @Test
    public void whatNuvioLaunchesBecome() throws Exception {
        assumeTrue("1".equals(System.getenv("JPP_LIVE_TMDB")));
        final String key = key();
        assumeTrue(key != null && !key.isEmpty());
        final AutoMatch matcher = new AutoMatch(new AutoMatchLiveTest.Live(key), new FakeTmdb.MemoryCache());
        for (final Object[] c : CASES) {
            final String link = (String) c[0];
            final String title = (String) c[1];
            final double seconds = ((Integer) c[2]) * 60.0;
            final String served = (String) c[3];
            final List<NameReading.Reading> readings =
                    Readings.of(link, null, Collections.<String>emptyList(), title);
            final AutoMatch.Result out = matcher.lookup(readings, Readings.ids(link, null), seconds,
                    () -> served, Readings.isWeb(link));
            final AutoMatch.Match m = out.match;
            System.out.println("NUVIO " + title + (served != null ? " [server: " + served + "]" : "")
                    + (link.equals(OPAQUE) ? "" : " [named link]") + " -> "
                    + (m != null ? m.title + " " + m.code() + " (tmdb " + m.tmdbId + ")" : "not sure: " + out.reason));
        }
    }
}
