package com.brouken.player.online;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

// Episode Info's whole-lookup cases, run against its fixed TMDB answers
public class AutoMatchTest {

    private static final String HASH = "8f1c2e5a9b0d4c3e7f6a1b2c3d4e5f6a7b8c9d0e";
    private static final String STREMIO = "http://127.0.0.1:11470/" + "ab".repeat(20) + "/0";

    private static String torrentio(final String name) {
        return "https://torrentio.strem.fun/resolve/realdebrid/SECRETKEY/" + HASH + "/null/1/" + name;
    }

    private static String local(final String name) {
        return "file:///Users/me/Movies/" + name;
    }

    private static final class Case {
        final String name;
        final String url;
        final double seconds;
        String title;
        String served;
        boolean serverMustNotBeAsked;
        Boolean serverAsked;
        String tmdbId;
        String code;
        Boolean isMovie;
        String epTitle;
        Pattern notSure;
        String query;
        Integer[] hint;
        boolean hintNone;
        Integer requests;
        Consumer<FakeTmdb> tmdb = t -> { };

        Case(final String name, final String url, final double seconds) {
            this.name = name;
            this.url = url;
            this.seconds = seconds;
        }

        Case found(final String tmdbId) {
            this.tmdbId = tmdbId;
            return this;
        }

        Case code(final String code) {
            this.code = code;
            return this;
        }

        Case movie() {
            this.isMovie = true;
            return this;
        }

        Case episode(final String title) {
            this.epTitle = title;
            return this;
        }

        Case notSure(final String regex) {
            this.notSure = Pattern.compile(regex);
            return this;
        }

        Case title(final String title) {
            this.title = title;
            return this;
        }

        Case query(final String query) {
            this.query = query;
            return this;
        }

        Case hint(final Integer season, final Integer episode) {
            this.hint = new Integer[]{season, episode};
            return this;
        }

        Case noHint() {
            this.hintNone = true;
            return this;
        }

        Case served(final String name) {
            this.served = name;
            return this;
        }

        Case serverAsked(final boolean asked) {
            this.serverAsked = asked;
            return this;
        }

        Case requests(final int requests) {
            this.requests = requests;
            return this;
        }

        Case tmdb(final Consumer<FakeTmdb> setup) {
            this.tmdb = setup;
            return this;
        }
    }

    private static List<Case> cases() {
        final List<Case> c = new ArrayList<>();
        c.add(new Case("Torrentio link, The Bear S03E05", torrentio("The.Bear.S03E05.1080p.WEB.h264-ETHEL.mkv"), 1920)
                .found("136315").code("S03E05").episode("Children"));
        c.add(new Case("The Office S05E10: only the US show has it", local("The.Office.S05E10.720p.mkv"), 1320)
                .found("2316").code("S05E10"));
        c.add(new Case("The Office UK S01E01: the country tag decides", local("The.Office.UK.S01E01.mkv"), 1800)
                .found("2996").code("S01E01"));
        c.add(new Case("Dune 2021: the year decides", local("Dune.2021.2160p.mkv"), 9300).found("438631").movie());
        c.add(new Case("Dune 1984", local("Dune.1984.1080p.mkv"), 8220).found("841"));
        c.add(new Case("Inception without a year: the short film is far too short", local("Inception.mkv"), 8880)
                .found("27205"));
        c.add(new Case("Sousou no Frieren - 01: alternative title, single season",
                local("[SubsPlease] Sousou no Frieren - 01 (1080p).mkv"), 1440).found("209867").code("S01E01"));
        c.add(new Case("Doctor Who 2005 S01E01", local("Doctor.Who.2005.S01E01.Rose.1080p.mkv"), 2700)
                .found("57243").episode("Rose"));
        c.add(new Case("Parasite, a year off", local("Parasite.2018.1080p.mkv"), 7980).found("496243"));
        c.add(new Case("1917 (2019)", local("1917.2019.1080p.BluRay.mkv"), 7140).found("530915"));
        c.add(new Case("an app's title agrees with the file name", torrentio("The.Bear.S03E05.1080p.WEB.h264-ETHEL.mkv"), 1920)
                .title("The Bear - S03E05 - Children").found("136315").code("S03E05"));
        c.add(new Case("S3 link: the opaque object key does not outvote the download name",
                "https://bucket.s3.amazonaws.com/0f9a8b7c?X-Amz-Signature=abc&response-content-disposition=attachment%3B%20filename%3D%22Inception.2010.1080p.mkv%22",
                8880).found("27205"));
        c.add(new Case("a link ending in a route, not a file name", "https://media.example/watch/inception", 8880)
                .notSure("no usable name"));
        c.add(new Case("Invincible S04E03: the namesake without season 4 does not stop it",
                local("Invincible - S04E03 - I GOTTA GET SOME AIR - 2160p HDR Ai Upscale -Mesc.mkv"), 2940)
                .found("95557").code("S04E03").episode("I GOTTA GET SOME AIR"));
        c.add(new Case("Invincible S04E03 named only by the app's title", STREMIO, 2940)
                .title("Invincible - S04E03 - I GOTTA GET SOME AIR - 2160p HDR Ai Upscale -Mesc")
                .found("95557").code("S04E03"));
        c.add(new Case("Backrooms, only the app's title: a well-known film of the right length", STREMIO, 6660)
                .title("Backrooms").found("1083381").movie());
        c.add(new Case("Backrooms, only the title, on a 9-minute video", STREMIO, 540).title("Backrooms")
                .notSure("isn't enough").query("Backrooms"));
        c.add(new Case("Backrooms, only the title, 25% longer than the film", STREMIO, 8340).title("Backrooms")
                .notSure("isn't enough"));
        c.add(new Case("Westworld, only the title: a popular show shares the name", STREMIO, 5400).title("Westworld")
                .notSure("isn't enough").query("Westworld"));
        c.add(new Case("Invincible, only the title, film length: the show shares the name", STREMIO, 6300)
                .title("Invincible").notSure("isn't enough"));
        c.add(new Case("Dune, only the title: the length tells the two films apart", STREMIO, 9300).title("Dune")
                .found("438631"));
        c.add(new Case("Wedding, only the title: a little-known film is never enough", STREMIO, 5700)
                .title("Wedding").notSure("isn't enough"));
        c.add(new Case("a link's last word alone is not a title", "https://media.example/watch/backrooms", 6660)
                .notSure("no usable name"));
        c.add(new Case("not sure of the show: the season and episode go to the picker", local("Doctor.Who.S01E01.mkv"), 2700)
                .notSure("several shows").hint(1, 1).query("Doctor Who"));
        c.add(new Case("a film it is not sure of passes no episode", local("Dune.mkv"), 9300)
                .notSure("several films").noHint());

        c.add(new Case("TorBox-style link: the server's Content-Disposition names it",
                "https://nexus-1.enam.tb-cdn.io/dld/3edea20d-aa77-4a42-acca-50d54635bc4a?token=aaa.bbb.ccc", 1920)
                .served("The Bear (2022) - S03E05 - Children (1080p WEB h264 ETHEL).mkv")
                .found("136315").code("S03E05").serverAsked(true));
        c.add(new Case("only a plain filename= in the header", "https://cdn.example/d/9f8e7d6c5b4a", 8880)
                .served("Inception.2010.1080p.BluRay.mkv").found("27205"));
        c.add(new Case("the server names nothing either", "https://cdn.example/d/9f8e7d6c5b4a", 3000)
                .served("").notSure("no usable name").serverAsked(true));
        c.add(new Case("the server names a trailer: still not shown", "https://cdn.example/d/9f8e7d6c5b4a", 150)
                .served("Dune.Part.Two.2024.Official.Trailer.1080p.mkv").notSure("trailer"));
        c.add(new Case("no request when the link already names the file", torrentio("The.Bear.S03E05.1080p.WEB.h264-ETHEL.mkv"), 1920)
                .code("S03E05").serverAsked(false));
        c.add(new Case("no request for local files", local("video_final2.mp4"), 600)
                .notSure("no film called").serverAsked(false));
        c.add(new Case("a web page: its server names no file, and the page title alone isn't enough",
                "https://www.youtube.com/watch?v=abc123", 600).title("My holiday in Spain").served("")
                .notSure("isn't enough").serverAsked(true));

        c.add(new Case("Comet: IMDb id plus season and episode from the link (no length needed)",
                "https://comet.example/cfg/playback/" + HASH + "/0/1/3/5?torrent_name=The.Bear.S03.1080p&name=The%20Bear&media_id=tt14452776&media_type=series",
                0).found("136315").code("S03E05"));
        c.add(new Case("Comet film", "https://comet.example/cfg/playback/" + HASH + "/0/n/n/n?name=Inception&media_id=tt1375666&media_type=movie",
                8880).found("27205").movie());
        c.add(new Case("an episode's own IMDb id", "https://addon.example/play?imdb_id=tt99000001", 1920)
                .found("136315").code("S03E05"));

        c.add(new Case("too short for the episode (a sample or clip)", torrentio("The.Bear.S03E05.1080p.WEB.h264-ETHEL.mkv"), 90)
                .notSure("length"));
        c.add(new Case("unknown length (live stream)", torrentio("The.Bear.S03E05.1080p.WEB.h264-ETHEL.mkv"), 0)
                .notSure("length is unknown"));
        c.add(new Case("The Office S01E01: both shows have it", local("The.Office.S01E01.mkv"), 1500).notSure("several shows"));
        c.add(new Case("Dune without a year: two well-known films", local("Dune.mkv"), 9300).notSure("several films"));
        c.add(new Case("Doctor Who S01E01 without a year", local("Doctor.Who.S01E01.mkv"), 2700).notSure("several shows"));
        c.add(new Case("One Piece - 05: the single-season namesake must not win", local("One Piece - 05 [1080p].mkv"), 1440)
                .notSure("no season number"));
        c.add(new Case("a home video named like a little-known film", local("Wedding 2019.mp4"), 5700).notSure("little-known"));
        c.add(new Case("the same little-known film from a release name", local("Wedding.2019.1080p.WEB-DL.mkv"), 5700)
                .found("700001"));
        c.add(new Case("the names disagree", torrentio("The.Bear.S03E05.1080p.WEB.h264-ETHEL.mkv"), 1920)
                .title("Severance - S01E02 - Half Loop").notSure("disagree"));
        c.add(new Case("a show TMDB does not have", local("Some.Unknown.Show.S01E01.1080p.mkv"), 1800).notSure("no show called"));
        c.add(new Case("an episode the show does not have", local("The.Bear.S09E01.mkv"), 1920).notSure("has no S09E01"));
        c.add(new Case("a trailer", local("Dune.Part.Two.2024.Official.Trailer.1080p.mkv"), 150).notSure("trailer"));
        c.add(new Case("a whole-season pack", local("Breaking.Bad.S05.1080p.BluRay.mkv"), 3000).notSure("no usable name"));
        c.add(new Case("a daily show by date", local("The.Daily.Show.2024.05.13.1080p.mkv"), 1800).notSure("no usable name"));
        c.add(new Case("specials", local("The.Office.S00E01.mkv"), 300).notSure("specials"));
        c.add(new Case("Final Season numbering", local("Shingeki no Kyojin - The Final Season - 05 (1080p).mkv"), 1440)
                .notSure("numbering"));
        c.add(new Case("Plex link: no name at all",
                "http://192.168.1.10:32400/library/parts/12345/1700000000/file.mkv?X-Plex-Token=abc", 3000)
                .served("").notSure("no usable name"));
        c.add(new Case("no TMDB key", local("The.Bear.S03E05.mkv"), 1920).tmdb(t -> t.noKey = true)
                .notSure("no TMDB key"));

        c.add(new Case("One Piece - 1089: seasons numbered on, so E1089 is in S22", local("One Piece - 1089 [1080p].mkv"), 1440)
                .found("37854").code("S22E1089").episode("The Gear 5 Battle").query("One Piece"));
        c.add(new Case("Shingeki no Kyojin - 87: seasons that start again, so 87 is S04E28",
                local("[SubsPlease] Shingeki no Kyojin - 87 (1080p).mkv"), 1440)
                .found("1429").code("S04E28").episode("The Dawn of Humanity"));
        c.add(new Case("Attack on Titan - 12: every season has a 12th", local("Attack on Titan - 12.mkv"), 1440)
                .notSure("no season number"));
        c.add(new Case("Attack on Titan - 30: the longest season has a 30th", local("Attack on Titan - 30.mkv"), 1440)
                .notSure("no season number"));
        c.add(new Case("Attack on Titan - 200: more than the show has", local("Attack on Titan - 200.mkv"), 1440)
                .notSure("no season number"));
        c.add(new Case("Attack on Titan - 87 on a 5-minute video", local("Attack on Titan - 87.mkv"), 300)
                .notSure("no season number"));
        c.add(new Case("The Simpsons - 100: not anime, so never counted through", local("The Simpsons - 100.mkv"), 1320)
                .notSure("no season number"));
        c.add(new Case("Doctor Who - 12: not anime either", local("Doctor Who - 12.mkv"), 2700).notSure("no season number"));
        c.add(new Case("Westworld - 38: the show still blocks its one-season namesake", local("Westworld - 38.mkv"), 3600)
                .notSure("no season number"));
        c.add(new Case("a season in the name is never second-guessed", local("Attack.on.Titan.S04E87.1080p.mkv"), 1440)
                .notSure("has no S04E87"));
        c.add(new Case("TMDB failing on the second try leaves the first answer", local("Attack on Titan - 87.mkv"), 1440)
                .tmdb(t -> t.failing = path -> path.startsWith("/tv/1429?") && !path.contains("append_to_response"))
                .notSure("no season number").query("Attack on Titan"));
        c.add(new Case("Breaking Bad - Felina: one episode by that name", local("Breaking Bad - Felina.mkv"), 3300)
                .found("1396").code("S05E16").episode("Felina").query("Breaking Bad"));
        c.add(new Case("Breaking Bad - Ozymandias", local("Breaking Bad - Ozymandias.mp4"), 2820).found("1396").code("S05E14"));
        c.add(new Case("The Office - Pilot: only the US show has one", local("The Office - Pilot.mkv"), 1380)
                .found("2316").code("S01E01"));
        c.add(new Case("The Office - Dinner Party", local("The Office - Dinner Party.mkv"), 1320).found("2316").code("S04E09"));
        c.add(new Case("Westworld - The Original", local("Westworld - The Original.mkv"), 4080).found("63247").code("S01E01"));
        c.add(new Case("Doctor Who - Rose: only the 2005 show has one", local("Doctor Who - Rose.mkv"), 2700)
                .found("57243").code("S01E01"));
        c.add(new Case("Dune - Part Two: a film by that name is still the film", local("Dune - Part Two.mkv"), 9960)
                .found("693134").movie());
        c.add(new Case("Lost - Pilot: no episode is called just that", local("Lost - Pilot.mkv"), 2580)
                .notSure("no film called").query("Lost - Pilot"));
        c.add(new Case("Invincible - It's About Time: both shows have one", local("Invincible - It's About Time.mkv"), 2700)
                .notSure("no film called"));
        c.add(new Case("The Simpsons - Homer's Odyssey: too many seasons", local("The Simpsons - Homer's Odyssey.mkv"), 1320)
                .notSure("no film called"));
        c.add(new Case("Holiday - Christmas Eve: a little-known show is never enough", local("Holiday - Christmas Eve.mp4"), 1800)
                .notSure("no film called"));
        c.add(new Case("Holiday - Day 2: a name any home video could have, not even looked up", local("Holiday - Day 2.mp4"), 1800)
                .notSure("no film called").requests(1));
        c.add(new Case("Breaking Bad - Felina on a 5-minute video", local("Breaking Bad - Felina.mkv"), 300).notSure("no film called"));
        c.add(new Case("Breaking Bad - Felina on a 90-minute video", local("Breaking Bad - Felina.mkv"), 5400).notSure("no film called"));
        c.add(new Case("Breaking Bad - Felina 2020: the year agrees", local("Breaking Bad - Felina 2020.mkv"), 3300)
                .found("1396").code("S05E16"));
        c.add(new Case("Breaking Bad - Felina 2008: the year doesn't", local("Breaking Bad - Felina 2008.mkv"), 3300)
                .notSure("no film called"));
        c.add(new Case("TMDB failing while reading the seasons leaves the first answer", local("Breaking Bad - Felina.mkv"), 3300)
                .tmdb(t -> t.failing = path -> path.startsWith("/tv/1396/season/")).notSure("no film called"));
        return c;
    }

    @Test
    public void everyLookupAnswersAsEpisodeInfoDoes() {
        final List<String> failures = new ArrayList<>();
        for (final Case c : cases()) {
            final String problem = run(c);
            if (problem != null) {
                failures.add(c.name + ": " + problem);
            }
        }
        assertEquals(String.join("\n", failures), 0, failures.size());
    }

    private static String run(final Case c) {
        final FakeTmdb tmdb = new FakeTmdb();
        c.tmdb.accept(tmdb);
        final boolean[] asked = {false};
        final List<NameReading.Reading> readings =
                Readings.of(c.url, null, Collections.<String>emptyList(), c.title);
        final AutoMatch.Result r = new AutoMatch(tmdb, new FakeTmdb.MemoryCache()).lookup(readings,
                Readings.ids(c.url, null), c.seconds, () -> {
                    asked[0] = true;
                    return c.served == null || c.served.isEmpty() ? null : c.served;
                }, Readings.isWeb(c.url));
        final AutoMatch.Match m = r.match;
        if (c.tmdbId != null || c.code != null || c.isMovie != null || c.epTitle != null) {
            if (m == null) {
                return "wanted a match, got not sure: " + r.reason;
            }
            if (c.tmdbId != null && !c.tmdbId.equals(String.valueOf(m.tmdbId))) {
                return "tmdb id: wanted " + c.tmdbId + ", got " + m.tmdbId;
            }
            if (c.code != null && !c.code.equals(m.code())) {
                return "code: wanted " + c.code + ", got " + m.code();
            }
            if (c.isMovie != null && c.isMovie == m.isSeries) {
                return "film: wanted " + c.isMovie;
            }
            if (c.epTitle != null && !c.epTitle.equals(m.episodeTitle)) {
                return "episode: wanted " + c.epTitle + ", got " + m.episodeTitle;
            }
        }
        if (c.notSure != null) {
            if (m != null) {
                return "wanted not sure (" + c.notSure + "), got " + m.title + " " + m.code() + " (tmdb " + m.tmdbId + ")";
            }
            if (!c.notSure.matcher(r.reason).find()) {
                return "reason \"" + r.reason + "\" does not match " + c.notSure;
            }
        }
        if (c.query != null && !c.query.equals(r.query)) {
            return "search box: wanted " + c.query + ", got " + r.query;
        }
        if (c.hint != null && !(c.hint[0].equals(r.season) && c.hint[1].equals(r.episode))) {
            return "season and episode passed on: wanted S" + c.hint[0] + "E" + c.hint[1]
                    + ", got " + r.season + " " + r.episode;
        }
        if (c.hintNone && r.season != null) {
            return "wanted no season passed on, got " + r.season;
        }
        if (c.requests != null && c.requests != tmdb.requests) {
            return "wanted " + c.requests + " TMDB requests, made " + tmdb.requests;
        }
        if (c.serverAsked != null && c.serverAsked != asked[0]) {
            return "server asked: wanted " + c.serverAsked + ", got " + asked[0];
        }
        return null;
    }

    @Test
    public void theNextEpisodeCostsNoRequests() {
        final FakeTmdb tmdb = new FakeTmdb();
        final AutoMatch matcher = new AutoMatch(tmdb, new FakeTmdb.MemoryCache());
        final String first = local("The.Bear.S03E05.mkv");
        assertEquals("S03E05", matcher.lookup(Readings.of(first, null, null, null), Readings.ids(first, null),
                1920, null, false).match.code());
        final int before = tmdb.requests;
        final String next = local("The.Bear.S03E06.mkv");
        assertEquals("S03E06", matcher.lookup(Readings.of(next, null, null, null), Readings.ids(next, null),
                1920, null, false).match.code());
        assertEquals(before, tmdb.requests);
    }
}
