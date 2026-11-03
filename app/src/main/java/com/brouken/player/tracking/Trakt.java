package com.brouken.player.tracking;

import android.content.Context;

import androidx.annotation.Nullable;

import com.brouken.player.BuildConfig;
import com.brouken.player.online.Identity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Request;

// Trakt's API: a device code typed on trakt.tv/activate; tokens last three months
final class Trakt implements Tracking.Service {

    static final Trakt INSTANCE = new Trakt();

    private static final String API = "https://api.trakt.tv";

    @Override
    public String name() {
        return Tracking.TRAKT;
    }

    @Override
    public boolean available() {
        return !BuildConfig.TRAKT_CLIENT_ID.isEmpty() && !BuildConfig.TRAKT_CLIENT_SECRET.isEmpty();
    }

    @Override
    public boolean connected(final Context context) {
        return available() && Tracking.prefs(context).getString(Tracking.TRAKT + ".token", null) != null;
    }

    // a code to type on trakt.tv/activate from any device
    static JSONObject deviceCode() throws Exception {
        return new JSONObject(Tracking.send(new Request.Builder().url(API + "/oauth/device/code")
                .header("Content-Type", "application/json")
                .post(Tracking.json(new JSONObject().put("client_id", BuildConfig.TRAKT_CLIENT_ID))).build()));
    }

    // true once the code has been entered; 400 means not yet
    static boolean deviceDone(final Context context, final String deviceCode) throws Exception {
        try (okhttp3.Response response = com.brouken.player.online.Http.client().newCall(new Request.Builder()
                .url(API + "/oauth/device/token")
                .post(Tracking.json(new JSONObject()
                        .put("code", deviceCode)
                        .put("client_id", BuildConfig.TRAKT_CLIENT_ID)
                        .put("client_secret", BuildConfig.TRAKT_CLIENT_SECRET))).build()).execute()) {
            if (response.code() != 200 || response.body() == null) {
                return false;
            }
            saveTokens(context, new JSONObject(response.body().string()));
        }
        exchangeName(context);
        return true;
    }

    private static void exchangeName(final Context context) {
        try {
            final JSONObject settings = new JSONObject(Tracking.send(request("/users/settings", context).build()));
            final String name = Tracking.text(settings.optJSONObject("user"), "username");
            if (name != null) {
                Tracking.prefs(context).edit().putString(Tracking.TRAKT + ".user", name).apply();
            }
        } catch (Exception ignored) {
            // the name is only shown in Settings
        }
    }

    private static void tokens(final Context context, final JSONObject body) throws Exception {
        saveTokens(context, new JSONObject(Tracking.send(new Request.Builder().url(API + "/oauth/token")
                .post(Tracking.json(body)).build())));
    }

    private static void saveTokens(final Context context, final JSONObject answer) throws Exception {
        final long expires = (answer.optLong("created_at") + answer.optLong("expires_in")) * 1000;
        Tracking.prefs(context).edit()
                .putString(Tracking.TRAKT + ".token", answer.getString("access_token"))
                .putString(Tracking.TRAKT + ".refresh", Tracking.text(answer, "refresh_token"))
                .putLong(Tracking.TRAKT + ".expires", expires).apply();
    }

    static void signOut(final Context context) {
        final String token = Tracking.prefs(context).getString(Tracking.TRAKT + ".token", null);
        Tracking.forget(context, INSTANCE);
        if (token == null) {
            return;
        }
        new Thread(() -> {
            try {
                Tracking.send(new Request.Builder().url(API + "/oauth/revoke").post(Tracking.json(new JSONObject()
                        .put("token", token)
                        .put("client_id", BuildConfig.TRAKT_CLIENT_ID)
                        .put("client_secret", BuildConfig.TRAKT_CLIENT_SECRET))).build());
            } catch (Exception ignored) {
                // gone from the device either way
            }
        }, "trakt revoke").start();
    }

    // a minute before it runs out; a refresh Trakt refuses (400) means signing in again
    private static synchronized String token(final Context context) throws Exception {
        final android.content.SharedPreferences prefs = Tracking.prefs(context);
        if (System.currentTimeMillis() > prefs.getLong(Tracking.TRAKT + ".expires", 0) - 60_000) {
            try {
                tokens(context, new JSONObject()
                        .put("refresh_token", prefs.getString(Tracking.TRAKT + ".refresh", ""))
                        .put("client_id", BuildConfig.TRAKT_CLIENT_ID)
                        .put("client_secret", BuildConfig.TRAKT_CLIENT_SECRET)
                        .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                        .put("grant_type", "refresh_token"));
            } catch (java.io.IOException e) {
                if ("HTTP 400".equals(e.getMessage()) || "HTTP 401".equals(e.getMessage())) {
                    Tracking.forget(context, INSTANCE);
                }
                throw e;
            }
        }
        return prefs.getString(Tracking.TRAKT + ".token", "");
    }

    private static Request.Builder request(final String path, final Context context) throws Exception {
        return new Request.Builder().url(API + path)
                .header("Content-Type", "application/json")
                .header("User-Agent", Tracking.USER_AGENT)
                .header("trakt-api-version", "2")
                .header("trakt-api-key", BuildConfig.TRAKT_CLIENT_ID)
                .header("Authorization", "Bearer " + token(context));
    }

    @Override
    public void scrobble(final Context context, final String action, final Identity identity,
                         final float percent) throws Exception {
        final JSONObject body = new JSONObject().put("progress", percent);
        if (identity.isSeries) {
            body.put("show", Tracking.media(identity));
            body.put("episode", new JSONObject().put("season", identity.season).put("number", identity.episode));
        } else {
            body.put("movie", Tracking.media(identity));
        }
        Tracking.send(request("/scrobble/" + action, context).post(Tracking.json(body)).build());
    }

    @Override
    public void history(final Context context, final List<String> keys, final boolean add) throws Exception {
        Tracking.send(request(add ? "/sync/history" : "/sync/history/remove", context)
                .post(Tracking.json(Tracking.historyBody(keys, add))).build());
    }

    @Nullable
    @Override
    public Set<String> watched(final Context context) throws Exception {
        // nothing watched since the last list: not fetched again
        final JSONObject activities = new JSONObject(Tracking.send(request("/sync/last_activities", context).build()));
        final String all = Tracking.text(activities, "all");
        final String key = Tracking.TRAKT + ".activity";
        if (all != null && all.equals(Tracking.prefs(context).getString(key, null))) {
            return null;
        }
        final Set<String> keys = new HashSet<>();
        final JSONArray movies = new JSONArray(Tracking.send(request("/sync/watched/movies", context).build()));
        for (int i = 0; i < movies.length(); i++) {
            final JSONObject entry = movies.optJSONObject(i);
            final String tmdb = entry == null ? null : Tracking.tmdbOf(entry.optJSONObject("movie"));
            if (tmdb != null) {
                keys.add("movie:" + tmdb);
            }
        }
        final JSONArray shows = new JSONArray(Tracking.send(request("/sync/watched/shows", context).build()));
        for (int i = 0; i < shows.length(); i++) {
            final JSONObject entry = shows.optJSONObject(i);
            final String tmdb = entry == null ? null : Tracking.tmdbOf(entry.optJSONObject("show"));
            final JSONArray seasons = entry == null ? null : entry.optJSONArray("seasons");
            for (int s = 0; tmdb != null && seasons != null && s < seasons.length(); s++) {
                final JSONObject season = seasons.optJSONObject(s);
                final JSONArray episodes = season == null ? null : season.optJSONArray("episodes");
                for (int e = 0; episodes != null && e < episodes.length(); e++) {
                    final JSONObject episode = episodes.optJSONObject(e);
                    if (episode != null) {
                        keys.add("tv:" + tmdb + ":" + season.optInt("number") + ":" + episode.optInt("number"));
                    }
                }
            }
        }
        Tracking.prefs(context).edit().putString(key, all).apply();
        return keys;
    }
}
