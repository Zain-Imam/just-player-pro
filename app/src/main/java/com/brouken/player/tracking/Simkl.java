package com.brouken.player.tracking;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.brouken.player.BuildConfig;
import com.brouken.player.online.Identity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

// Simkl's OAuth 2.0 sign-in: PKCE in the browser on phones, a code typed on simkl.com/pin on TVs.
// Tokens last a week; the refresh token lasts half a year from its last use.
final class Simkl implements Tracking.Service {

    static final Simkl INSTANCE = new Simkl();

    private static final String API = "https://api.simkl.com";
    static final String REDIRECT = "justplayerpro://auth/simkl";
    private static final String SCOPE = "media:read media:write";
    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    @Override
    public String name() {
        return Tracking.SIMKL;
    }

    @Override
    public boolean available() {
        return !BuildConfig.SIMKL_CLIENT_ID.isEmpty();
    }

    @Override
    public boolean connected(final Context context) {
        return available() && Tracking.prefs(context).getString(Tracking.SIMKL + ".token", null) != null;
    }

    // renewed an hour before it runs out; a renewal Simkl refuses means signing in again
    @Nullable
    private static synchronized String token(final Context context) throws Exception {
        final android.content.SharedPreferences prefs = Tracking.prefs(context);
        final String token = prefs.getString(Tracking.SIMKL + ".token", null);
        if (token != null && System.currentTimeMillis() > prefs.getLong(Tracking.SIMKL + ".expires", 0) - 3_600_000) {
            renew(context);
            return prefs.getString(Tracking.SIMKL + ".token", null);
        }
        return token;
    }

    private static void renew(final Context context) throws Exception {
        try {
            saveTokens(context, oauth(new FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", BuildConfig.SIMKL_CLIENT_ID)
                    .add("refresh_token", Tracking.prefs(context).getString(Tracking.SIMKL + ".refresh", ""))));
        } catch (java.io.IOException e) {
            if ("HTTP 400".equals(e.getMessage()) || "HTTP 401".equals(e.getMessage())) {
                Tracking.forget(context, INSTANCE);
            }
            throw e;
        }
    }

    static Uri authorizeUrl(final String challenge, final String state) {
        return Uri.parse("https://simkl.com/oauth2/authorize").buildUpon()
                .appendQueryParameter("client_id", BuildConfig.SIMKL_CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build();
    }

    static void exchange(final Context context, final String code, final String verifier) throws Exception {
        saveTokens(context, oauth(new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", BuildConfig.SIMKL_CLIENT_ID)
                .add("code", code)
                .add("redirect_uri", REDIRECT)
                .add("code_verifier", verifier)));
        signedIn(context);
    }

    // a TV: a code to type on simkl.com/pin from any device
    static JSONObject deviceCode() throws Exception {
        return oauthAt("/oauth2/device", new FormBody.Builder()
                .add("client_id", BuildConfig.SIMKL_CLIENT_ID)
                .add("scope", SCOPE));
    }

    // true once the code has been entered; Simkl answers 400 until then
    static boolean deviceDone(final Context context, final String deviceCode) throws Exception {
        final JSONObject answer;
        try (Response response = com.brouken.player.online.Http.client().newCall(new Request.Builder()
                .url(API + "/oauth2/token")
                .header("User-Agent", Tracking.USER_AGENT)
                .post(new FormBody.Builder()
                        .add("grant_type", DEVICE_GRANT)
                        .add("client_id", BuildConfig.SIMKL_CLIENT_ID)
                        .add("device_code", deviceCode).build()).build()).execute()) {
            final String text = response.body() == null ? "" : response.body().string();
            if (response.code() != 200) {
                // asked too often: Simkl wants five more seconds between tries
                if (text.contains("slow_down")) {
                    Thread.sleep(5000);
                }
                return false;
            }
            answer = new JSONObject(text);
        }
        saveTokens(context, answer);
        signedIn(context);
        return true;
    }

    private static JSONObject oauth(final FormBody.Builder body) throws Exception {
        return oauthAt("/oauth2/token", body);
    }

    private static JSONObject oauthAt(final String path, final FormBody.Builder body) throws Exception {
        return new JSONObject(Tracking.send(new Request.Builder().url(API + path)
                .header("User-Agent", Tracking.USER_AGENT)
                .post(body.build()).build()));
    }

    private static void saveTokens(final Context context, final JSONObject answer) throws Exception {
        final android.content.SharedPreferences.Editor editor = Tracking.prefs(context).edit()
                .putString(Tracking.SIMKL + ".token", answer.getString("access_token"))
                .putLong(Tracking.SIMKL + ".expires",
                        System.currentTimeMillis() + answer.optLong("expires_in", 604800) * 1000);
        // the same refresh token comes back each time; kept if a renewal leaves it out
        final String refresh = Tracking.text(answer, "refresh_token");
        if (refresh != null) {
            editor.putString(Tracking.SIMKL + ".refresh", refresh);
        }
        editor.apply();
    }

    private static void signedIn(final Context context) {
        try {
            final JSONObject settings = new JSONObject(Tracking.send(request("/users/settings", token(context))
                    .post(Tracking.json(new JSONObject())).build()));
            final String name = Tracking.text(settings.optJSONObject("user"), "name");
            if (name != null) {
                Tracking.prefs(context).edit().putString(Tracking.SIMKL + ".user", name).apply();
            }
        } catch (Exception ignored) {
            // the name is only shown in Settings
        }
    }

    static void signOut(final Context context) {
        final String token = Tracking.prefs(context).getString(Tracking.SIMKL + ".token", null);
        Tracking.forget(context, INSTANCE);
        if (token == null) {
            return;
        }
        new Thread(() -> {
            try {
                oauthAt("/oauth2/revoke", new FormBody.Builder()
                        .add("client_id", BuildConfig.SIMKL_CLIENT_ID)
                        .add("token", token));
            } catch (Exception ignored) {
                // gone from the device either way
            }
        }, "simkl revoke").start();
    }

    // every call carries the app's id and name, as Simkl asks
    private static HttpUrl.Builder url(final String path) {
        return HttpUrl.get(API + path).newBuilder()
                .addQueryParameter("client_id", BuildConfig.SIMKL_CLIENT_ID)
                .addQueryParameter("app-name", "justplayerpro")
                .addQueryParameter("app-version", BuildConfig.VERSION_NAME);
    }

    private static Request.Builder request(final String path, @Nullable final String token) {
        return request(url(path).build(), token);
    }

    private static Request.Builder request(final HttpUrl url, @Nullable final String token) {
        final Request.Builder builder = new Request.Builder().url(url)
                .header("User-Agent", Tracking.USER_AGENT)
                .header("simkl-api-key", BuildConfig.SIMKL_CLIENT_ID);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    // a token Simkl no longer takes is renewed once; refused again, the account is signed out
    private String call(final Context context, final Request request) throws Exception {
        try {
            return Tracking.send(request);
        } catch (java.io.IOException e) {
            if (!"HTTP 401".equals(e.getMessage())) {
                throw e;
            }
            renew(context);
            final String token = Tracking.prefs(context).getString(Tracking.SIMKL + ".token", "");
            return Tracking.send(request.newBuilder().header("Authorization", "Bearer " + token).build());
        }
    }

    // Simkl takes one write a second and locks a user's writes for 20 s after some; a history
    // change refused for that is sent once more when the lock is over, so it is never lost
    private String write(final Context context, final Request request) throws Exception {
        try {
            return call(context, request);
        } catch (Tracking.HttpError e) {
            if (!e.isWriteLimit()) {
                throw e;
            }
            Thread.sleep(21_000);
            return call(context, request);
        }
    }

    // a lost start or pause only costs Simkl's "watching now", so these are never sent twice
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
        final HttpUrl.Builder url = url("/scrobble/" + action);
        if (Tracking.STOP.equals(action)) {
            // watched at the Settings point rather than Simkl's own 80%
            url.addQueryParameter("min_progress", String.valueOf(Tracking.watchedPercent(context)));
        }
        call(context, request(url.build(), token(context)).post(Tracking.json(body)).build());
    }

    @Override
    public void history(final Context context, final List<String> keys, final boolean add) throws Exception {
        write(context, request(add ? "/sync/history" : "/sync/history/remove", token(context))
                .post(Tracking.json(Tracking.historyBody(keys, add))).build());
    }

    // Simkl's remove takes a film off every list too, Plan to watch included, so it goes back on
    // the one it was on before this app marked it
    @Override
    public void unwatch(final Context context, final String key, @Nullable final String list) throws Exception {
        history(context, java.util.Collections.singletonList(key), false);
        if (list != null && key.startsWith("movie:")) {
            Thread.sleep(1_500);
            write(context, request("/sync/add-to-list", token(context))
                    .post(Tracking.json(listBody(key.substring("movie:".length()), list))).build());
        }
    }

    // {"movies":[{"to":"plantowatch","ids":{"tmdb":"603"}}]}, as Simkl's docs write it
    static JSONObject listBody(final String tmdb, final String list) throws Exception {
        return new JSONObject().put("movies", new JSONArray().put(new JSONObject()
                .put("to", list).put("ids", new JSONObject().put("tmdb", tmdb))));
    }

    @Nullable
    @Override
    public String listOf(final Context context, final String key) {
        try {
            return Tracking.text(new JSONObject(Tracking.prefs(context)
                    .getString(Tracking.SIMKL + ".listed", "{}")), key);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    @Override
    public Set<String> watched(final Context context) throws Exception {
        final String token = token(context);
        // nothing changed since the last list: not fetched again
        final JSONObject activities = new JSONObject(call(context, request("/sync/activities", token).build()));
        final String all = Tracking.text(activities, "all");
        final String key = Tracking.SIMKL + ".activity";
        if (all != null && all.equals(Tracking.prefs(context).getString(key, null))) {
            return null;
        }
        final HttpUrl url = url("/sync/all-items")
                .addQueryParameter("extended", "full_anime_seasons")
                .addQueryParameter("episode_watched_at", "yes")
                .addQueryParameter("episode_tvdb_id", "yes")
                .addQueryParameter("include_all_episodes", "yes").build();
        final JSONObject items = new JSONObject(call(context, request(url, token).build()));
        final Set<String> keys = watchedKeys(items);
        // the snapshot only once everything is in, as Simkl asks
        Tracking.prefs(context).edit().putString(Tracking.SIMKL + ".listed", listedFilms(items).toString())
                .putString(key, all).apply();
        return keys;
    }

    // films on a list but not watched: "movie:603" -> "plantowatch" (or "dropped")
    static JSONObject listedFilms(final JSONObject items) throws Exception {
        final JSONObject listed = new JSONObject();
        for (final String type : new String[]{"movies", "anime"}) {
            final JSONArray entries = items.optJSONArray(type);
            for (int i = 0; entries != null && i < entries.length(); i++) {
                final JSONObject entry = entries.optJSONObject(i);
                if (entry == null || ("anime".equals(type) && !"movie".equals(Tracking.text(entry, "anime_type")))) {
                    continue;
                }
                final String status = Tracking.text(entry, "status");
                final JSONObject media = entry.optJSONObject("movie") != null
                        ? entry.optJSONObject("movie") : entry.optJSONObject("show");
                final String tmdb = Tracking.tmdbOf(media);
                // Simkl's older apps see notinteresting where newer ones see dropped
                if (tmdb != null && ("plantowatch".equals(status) || "dropped".equals(status)
                        || "notinteresting".equals(status))
                        && !Tracking.isTime(Tracking.text(entry, "last_watched_at"))) {
                    listed.put("movie:" + tmdb, "plantowatch".equals(status) ? status : "dropped");
                }
            }
        }
        return listed;
    }

    // as Nuvio reads it: a film with a watched time or completed, an episode with a watched time, a
    // completed show whole ("tv:1396"); plan to watch and episodes not seen yet are only listed
    static Set<String> watchedKeys(final JSONObject items) {
        final Set<String> keys = new HashSet<>();
        for (final String type : new String[]{"movies", "shows", "anime"}) {
            final JSONArray entries = items.optJSONArray(type);
            for (int i = 0; entries != null && i < entries.length(); i++) {
                final JSONObject entry = entries.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                final boolean completed = "completed".equals(Tracking.text(entry, "status"));
                // an anime film is listed with the anime, under "show"
                final boolean film = "movies".equals(type)
                        || ("anime".equals(type) && "movie".equals(Tracking.text(entry, "anime_type")));
                final JSONObject media = entry.optJSONObject("movie") != null
                        ? entry.optJSONObject("movie") : entry.optJSONObject("show");
                final String tmdb = Tracking.tmdbOf(media);
                if (tmdb == null) {
                    continue;
                }
                if (film) {
                    if (completed || Tracking.isTime(Tracking.text(entry, "last_watched_at"))) {
                        keys.add("movie:" + tmdb);
                    }
                    continue;
                }
                if (completed) {
                    keys.add("tv:" + tmdb);
                }
                final JSONArray seasons = entry.optJSONArray("seasons");
                for (int s = 0; seasons != null && s < seasons.length(); s++) {
                    final JSONObject season = seasons.optJSONObject(s);
                    final JSONArray episodes = season == null ? null : season.optJSONArray("episodes");
                    for (int e = 0; episodes != null && e < episodes.length(); e++) {
                        final JSONObject episode = episodes.optJSONObject(e);
                        if (episode == null || !Tracking.isTime(Tracking.text(episode, "watched_at"))) {
                            continue;
                        }
                        // anime counts its own way; the TVDB numbers are the ones TMDB uses too
                        final JSONObject tvdb = episode.optJSONObject("tvdb");
                        final String seasonNumber = Tracking.text(tvdb, "season") != null
                                ? Tracking.text(tvdb, "season") : Tracking.text(season, "number");
                        final String episodeNumber = Tracking.text(tvdb, "episode") != null
                                ? Tracking.text(tvdb, "episode") : Tracking.text(episode, "number");
                        if (seasonNumber != null && episodeNumber != null) {
                            keys.add("tv:" + tmdb + ":" + seasonNumber + ":" + episodeNumber);
                        }
                    }
                }
            }
        }
        return keys;
    }
}
