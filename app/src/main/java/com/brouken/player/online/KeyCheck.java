package com.brouken.player.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

// Asks each service whether a key works before it is saved, rather than letting
// a typo sit there until the first search comes back empty and looks like the
// service being down.
public final class KeyCheck {

    public static final class Result {
        public final boolean ok;
        public final String message;

        Result(final boolean ok, final String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private KeyCheck() {
    }

    // Runs on the caller's thread, which must not be the main one.
    @NonNull
    public static Result check(final String prefKey, @Nullable final String value) {
        final String key = value == null ? "" : value.trim();
        if (key.isEmpty()) {
            return new Result(true, "Cleared");
        }
        switch (prefKey) {
            case ApiKeys.PREF_TMDB:
                return tmdb(key);
            case ApiKeys.PREF_OPENSUBTITLES:
                return openSubtitles(key);
            case ApiKeys.PREF_SUBDL:
                return subdl(key);
            case ApiKeys.PREF_WYZIE:
                return wyzie(key);
            default:
                // Nothing to ask: the user name and password are only meaningful
                // alongside an OpenSubtitles key, which is checked on its own.
                return new Result(true, "Saved");
        }
    }

    private static Result tmdb(final String key) {
        final Map<String, String> params = Http.params();
        params.put("api_key", key);
        return judge(Http.get("https://api.themoviedb.org/3/configuration"
                + Http.query(params), null), "TMDB");
    }

    private static Result openSubtitles(final String key) {
        final Map<String, String> headers = Http.params();
        headers.put("Api-Key", key);
        return judge(Http.get("https://api.opensubtitles.com/api/v1/infos/formats",
                headers), "OpenSubtitles");
    }

    private static Result subdl(final String key) {
        final Map<String, String> params = Http.params();
        params.put("api_key", key);
        params.put("film_name", "Inception");
        params.put("languages", "en");
        return judge(Http.get("https://api.subdl.com/api/v1/subtitles"
                + Http.query(params), null), "SubDL");
    }

    private static Result wyzie(final String key) {
        final Map<String, String> params = Http.params();
        params.put("id", "tt1375666");
        params.put("key", key);
        return judge(Http.get("https://sub.wyzie.io/search" + Http.query(params), null), "Wyzie");
    }

    private static Result judge(final Http.Result response, final String service) {
        if (response.ok()) {
            return new Result(true, service + " accepted the key");
        }
        switch (response.code) {
            case 401:
            case 403:
                return new Result(false, service + " rejected the key");
            case 429:
                return new Result(false, service + " is rate limiting; try again shortly");
            case 0:
                return new Result(false, "No answer from " + service + " — check the connection");
            default:
                return new Result(false, service + " answered HTTP " + response.code);
        }
    }
}
