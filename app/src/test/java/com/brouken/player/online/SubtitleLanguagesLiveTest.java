package com.brouken.player.online;

import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

// opt-in with JPP_LIVE_SUBS=1; needs the network and the keys in .env
public class SubtitleLanguagesLiveTest {

    private static final String[] LANGUAGES = {"en", "pt", "pt-br", "pt-pt", "zh-cn", "zh-tw", "fa", "uk",
            "ar", "es", "hi", "ja", "ko", "vi", "id", "tr", "pl", "sq", "is", "sr", "ea", "sp"};

    private static String env(final String name) {
        try {
            for (final String line : Files.readAllLines(Paths.get("..", ".env"))) {
                final String trimmed = line.trim();
                if (trimmed.startsWith(name)) {
                    return trimmed.replaceFirst("^" + name + "\\s*[=:]\\s*", "").replaceAll("^['\"]|['\"]$", "").trim();
                }
            }
        } catch (IOException ignored) {
            // no .env
        }
        return null;
    }

    private static int count(final Http.Result response, final String arrayField) {
        if (!response.ok()) {
            return -response.code;
        }
        final JSONObject object = response.json();
        if (object != null) {
            final JSONArray array = object.optJSONArray(arrayField);
            return array == null ? 0 : array.length();
        }
        final JSONArray array = response.jsonArray();
        return array == null ? 0 : array.length();
    }

    @Test
    public void everySourceAnswersInItsOwnCodes() {
        assumeTrue("1".equals(System.getenv("JPP_LIVE_SUBS")));
        final String os = env("OPENSUBTITLES_API_KEY");
        final String subdl = env("SUBDL_API_KEY");
        final String wyzie = env("WYZIE_API_KEY");
        System.out.println("language | OpenSubtitles | SubDL | Wyzie   (count; - skipped; negative = HTTP error)");
        for (final String language : LANGUAGES) {
            final StringBuilder line = new StringBuilder(language).append(" | ");

            final String osCode = SubtitleLanguages.forOpenSubtitles(language);
            if (os == null || osCode == null) {
                line.append("-");
            } else {
                final Map<String, String> headers = Http.params();
                headers.put("Api-Key", os);
                final Map<String, String> p = Http.params();
                p.put("imdb_id", "1375666");
                p.put("languages", osCode);
                line.append(osCode).append(" ").append(count(Http.get(
                        "https://api.opensubtitles.com/api/v1/subtitles" + Http.query(p), headers), "data"));
            }
            line.append(" | ");

            final String subdlCode = SubtitleLanguages.forSubdl(language);
            if (subdl == null || subdlCode == null) {
                line.append("-");
            } else {
                final Map<String, String> p = Http.params();
                p.put("api_key", subdl);
                p.put("imdb_id", "tt1375666");
                p.put("languages", subdlCode);
                p.put("subs_per_page", "30");
                line.append(subdlCode).append(" ").append(count(Http.get(
                        "https://api.subdl.com/api/v1/subtitles" + Http.query(p), null), "subtitles"));
            }
            line.append(" | ");

            final String wyzieCode = SubtitleLanguages.forWyzie(language);
            if (wyzie == null || wyzieCode == null) {
                line.append("-");
            } else {
                final Map<String, String> p = Http.params();
                p.put("id", "tt1375666");
                p.put("language", wyzieCode);
                p.put("format", "srt");
                p.put("key", wyzie);
                line.append(wyzieCode).append(" ").append(count(Http.get(
                        "https://sub.wyzie.io/search" + Http.query(p), null), "data"));
            }
            System.out.println(line);
        }
    }
}
