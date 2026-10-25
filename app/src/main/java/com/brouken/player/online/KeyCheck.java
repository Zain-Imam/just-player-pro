package com.brouken.player.online;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.brouken.player.R;

import java.util.Map;

public final class KeyCheck {

    public enum Kind {
        CLEARED, SAVED, ACCEPTED, REJECTED, RATE_LIMITED, NO_ANSWER, HTTP
    }

    public static final class Result {
        public final boolean ok;
        public final Kind kind;
        @Nullable
        final String service;
        final int code;

        Result(final Kind kind, @Nullable final String service, final int code) {
            this.ok = kind == Kind.CLEARED || kind == Kind.SAVED || kind == Kind.ACCEPTED;
            this.kind = kind;
            this.service = service;
            this.code = code;
        }

        // worded where it is shown, in the app's language
        public String message(final Context context) {
            switch (kind) {
                case CLEARED:
                    return context.getString(R.string.key_check_cleared);
                case SAVED:
                    return context.getString(R.string.key_check_saved);
                case ACCEPTED:
                    return context.getString(R.string.key_check_accepted, service);
                case REJECTED:
                    return context.getString(R.string.key_check_rejected, service);
                case RATE_LIMITED:
                    return context.getString(R.string.key_check_rate_limited, service);
                case NO_ANSWER:
                    return context.getString(R.string.key_check_no_answer, service);
                default:
                    return context.getString(R.string.key_check_http, service, code);
            }
        }
    }

    private KeyCheck() {
    }

    // on the caller's thread, which must not be the main one
    @NonNull
    public static Result check(final String prefKey, @Nullable final String value) {
        final String key = value == null ? "" : value.trim();
        if (key.isEmpty()) {
            return new Result(Kind.CLEARED, null, 0);
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
                // user name and password have no check of their own
                return new Result(Kind.SAVED, null, 0);
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
            return new Result(Kind.ACCEPTED, service, response.code);
        }
        switch (response.code) {
            case 401:
            case 403:
                return new Result(Kind.REJECTED, service, response.code);
            case 429:
                return new Result(Kind.RATE_LIMITED, service, response.code);
            case 0:
                return new Result(Kind.NO_ANSWER, service, 0);
            default:
                return new Result(Kind.HTTP, service, response.code);
        }
    }
}
