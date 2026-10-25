package com.brouken.player.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// live TMDB, only with JPP_LIVE_TMDB=1 and TMDB_API_KEY in .env; wrong must be 0
public class AutoMatchLiveTest {

    static String key() {
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

    static final class Live implements AutoMatch.Source {
        final String key;
        int requests;

        Live(final String key) {
            this.key = key;
        }

        @Override
        public JSONObject get(final String path, final Map<String, String> params) throws IOException {
            requests++;
            final StringBuilder url = new StringBuilder("https://api.themoviedb.org/3").append(path)
                    .append("?api_key=").append(key);
            for (final Map.Entry<String, String> each : params.entrySet()) {
                url.append('&').append(each.getKey()).append('=')
                        .append(URLEncoder.encode(each.getValue(), "UTF-8"));
            }
            final HttpURLConnection connection = (HttpURLConnection) new URL(url.toString()).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            final int code = connection.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code != 200) {
                // the address holds the key, so only the code is told
                throw new IOException("TMDB answered " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IOException("unreadable answer");
            }
        }
    }

    private static String norm(final String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace("&", "and").replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    @Test
    public void wrongStaysAtZero() throws Exception {
        run(false);
    }

    // with AniList on; a case's "anilist" is what it may find instead
    @Test
    public void wrongStaysAtZeroWithAniList() throws Exception {
        run(true);
    }

    private static void run(final boolean anime) throws Exception {
        assumeTrue("1".equals(System.getenv("JPP_LIVE_TMDB")));
        final String key = key();
        assumeTrue(key != null && !key.isEmpty());

        final JSONArray cases;
        try (InputStream in = AutoMatchLiveTest.class.getResourceAsStream("/live-names.json")) {
            cases = new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        final Live live = new Live(key);
        final AutoMatch matcher = new AutoMatch(live, new FakeTmdb.MemoryCache());
        if (anime) {
            matcher.useAnime(new AniList.Service());
        }
        int right = 0, wrong = 0, unsure = 0, unsureOk = 0, findable = 0;
        final List<String> lines = new ArrayList<>();
        for (int i = 0; i < cases.length(); i++) {
            final JSONObject c = cases.getJSONObject(i);
            final String name = c.getString("name");
            final String title = c.optString("title", null);
            final double seconds = c.getDouble("seconds");
            final JSONObject want = anime && c.has("anilist") ? c.getJSONObject("anilist") : c.getJSONObject("want");
            final String link = name.matches("^https?:.*") ? name : "file:///Users/me/Videos/" + name;
            final List<NameReading.Reading> readings =
                    Readings.of(link, null, Collections.<String>emptyList(), title);
            final AutoMatch.Result out = matcher.lookup(readings, Readings.ids(link, null), seconds,
                    () -> null, Readings.isWeb(link));
            final AutoMatch.Match m = out.match;
            if (!want.has("skip")) {
                findable++;
            }
            final String verdict;
            if (m == null) {
                verdict = want.has("skip") || want.optBoolean("either") ? "unsure-ok" : "unsure";
            } else if (want.has("skip")) {
                verdict = "WRONG";
            } else if (want.has("tv")) {
                verdict = m.isSeries && norm(m.title).equals(norm(want.getString("tv")))
                        && m.code().equals(want.getString("code")) ? "right" : "WRONG";
            } else {
                final int year = want.getInt("year");
                final JSONObject d = live.get("/movie/" + m.tmdbId, Collections.<String, String>emptyMap());
                final String date = d == null ? "" : d.optString("release_date", "");
                final int got = date.length() >= 4 ? Integer.parseInt(date.substring(0, 4)) : 0;
                verdict = !m.isSeries && norm(m.title).equals(norm(want.getString("movie")))
                        && Math.abs(got - year) <= 1 ? "right" : "WRONG";
            }
            switch (verdict) {
                case "right": right++; break;
                case "WRONG": wrong++; break;
                case "unsure": unsure++; break;
                default: unsureOk++; break;
            }
            if (out.viaAnime || (!"right".equals(verdict) && !"unsure-ok".equals(verdict))) {
                lines.add(verdict + (out.viaAnime ? " (AniList)" : "") + "  " + (title != null ? "title: " + title : name) + "\n      wanted " + want
                        + "\n      got    " + (m != null ? m.title + " " + m.code() + " [tmdb " + m.tmdbId + "]"
                        : "not sure: " + out.reason));
            }
        }
        for (final String line : lines) {
            System.out.println(line);
        }
        final String summary = (anime ? "live TMDB with AniList: " : "live TMDB: ") + cases.length() + " names - " + right + " right, " + wrong
                + " WRONG, " + unsure + " unsure that should be found, " + unsureOk + " unsure as allowed; found "
                + right + " of " + findable + " findable; " + live.requests + " requests";
        System.out.println(summary);
        Files.write(Paths.get("build", anime ? "live-tmdb-anilist.txt" : "live-tmdb.txt"),
                (String.join("\n", lines) + "\n" + summary + "\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(summary, 0, wrong);
    }
}
