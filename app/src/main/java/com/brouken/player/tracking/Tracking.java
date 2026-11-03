package com.brouken.player.tracking;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.brouken.player.Background;
import com.brouken.player.BuildConfig;
import com.brouken.player.Utils;
import com.brouken.player.Watched;
import com.brouken.player.online.Identity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

// Simkl, as Nuvio does it: playback is reported as it goes and the watched list is fetched
// now and then for the ticks. Accounts live in their own file, so a backup never carries a login.
public final class Tracking {

    public static final String SIMKL = "Simkl";
    public static final String TRAKT = "Trakt";

    public static final String START = "start";
    public static final String PAUSE = "pause";
    public static final String STOP = "stop";

    static final String PREFS = "tracking";
    static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    static final String USER_AGENT = "JustPlayerPro/" + BuildConfig.VERSION_NAME;

    private static final long REFRESH_MS = 15 * 60 * 1000L;
    private static long refreshedAt = -REFRESH_MS;

    // one at a time, so calls keep their order and a service is never flooded
    private static final ExecutorService worker = Background.pool("tracking", 1);

    private Tracking() {
    }

    interface Service {
        String name();

        boolean available();

        boolean connected(Context context);

        void scrobble(Context context, String action, Identity identity, float percent) throws Exception;

        // keys as Watched.filmKey makes them: "movie:603", "tv:1396:1:5"
        void history(Context context, List<String> keys, boolean add) throws Exception;

        // Film info's Unwatch; list is where the film sat before this app marked it
        default void unwatch(Context context, String key, @Nullable String list) throws Exception {
            history(context, java.util.Collections.singletonList(key), false);
        }

        // the list a film not yet watched sits on (plantowatch, dropped), or null
        @Nullable
        default String listOf(Context context, String key) {
            return null;
        }

        // "movie:603", "tv:1396:1:5"; null when nothing changed since last time
        @Nullable
        Set<String> watched(Context context) throws Exception;
    }

    // Simkl alone: since October 2026 Trakt gives apps to everyone's accounts only on a paid
    // developer plan, so Trakt.java is left out and nothing reaches it
    static List<Service> services() {
        final List<Service> all = new ArrayList<>();
        all.add(Simkl.INSTANCE);
        return all;
    }

    static SharedPreferences prefs(final Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean anyConnected(@NonNull final Context context) {
        for (final Service service : services()) {
            if (service.connected(context)) {
                return true;
            }
        }
        return false;
    }

    public static void scrobble(@NonNull final Context context, @NonNull final String action,
                                @Nullable final Identity identity, final float percent) {
        if (identity == null || Watched.filmKey(identity) == null || !anyConnected(context)) {
            return;
        }
        final Context app = context.getApplicationContext();
        worker.execute(Background.safely(() -> {
            for (final Service service : services()) {
                if (service.connected(app)) {
                    try {
                        service.scrobble(app, action, identity, Math.max(0, Math.min(100, percent)));
                    } catch (Exception e) {
                        Utils.log(service.name() + " scrobble " + action + " failed: " + e);
                    }
                }
            }
        }));
    }

    // Film info's button: added to, or taken from, every connected service's history
    public static void history(@NonNull final Context context, @NonNull final List<String> films,
                               final boolean add) {
        if (films.isEmpty() || !anyConnected(context)) {
            return;
        }
        final Context app = context.getApplicationContext();
        for (final String film : films) {
            showAtOnce(app, film, add);
        }
        worker.execute(Background.safely(() -> {
            for (final Service service : services()) {
                if (service.connected(app)) {
                    try {
                        service.history(app, films, add);
                    } catch (Exception e) {
                        Utils.log(service.name() + " history failed: " + e);
                    }
                }
            }
        }));
        refresh(app, true, null);
    }

    // the kept list follows this app's own change straight away, so Film info and Recent never
    // show the old state; the read that follows confirms it, or puts back what Simkl refused
    private static void showAtOnce(final Context context, final String film, final boolean watched) {
        for (final Service service : services()) {
            if (service.connected(context)) {
                final Set<String> keys = keys(context, service);
                if (followChange(keys, film, watched)) {
                    prefs(context).edit().putString(service.name() + ".watched", new JSONArray(keys).toString())
                            .remove(service.name() + ".activity").apply();
                }
            }
        }
    }

    // an unwatched episode of a show kept as completed leaves the whole show; true when changed
    static boolean followChange(@NonNull final Set<String> keys, @NonNull final String film,
                                final boolean watched) {
        if (watched) {
            return keys.add(film);
        }
        boolean changed = keys.remove(film);
        final String[] parts = film.split(":");
        if (parts.length == 4 && "tv".equals(parts[0])) {
            changed |= keys.remove("tv:" + parts[1]);
        }
        return changed;
    }

    // Film info's Unwatch: taken from the history, and back on the list it came from
    public static void unwatch(@NonNull final Context context, @Nullable final String film,
                               @Nullable final String list) {
        if (film == null || !anyConnected(context)) {
            return;
        }
        final Context app = context.getApplicationContext();
        showAtOnce(app, film, false);
        worker.execute(Background.safely(() -> {
            for (final Service service : services()) {
                if (service.connected(app)) {
                    try {
                        service.unwatch(app, film, list);
                    } catch (Exception e) {
                        Utils.log(service.name() + " unwatch failed: " + e);
                    }
                }
            }
        }));
        refresh(app, true, null);
    }

    // for a mark: the list the film sits on now, so Unwatch can put it back
    @Nullable
    public static String listOf(@NonNull final Context context, @Nullable final String film) {
        if (film == null) {
            return null;
        }
        for (final Service service : services()) {
            if (service.connected(context)) {
                final String list = service.listOf(context, film);
                if (list != null) {
                    return list;
                }
            }
        }
        return null;
    }

    // Settings' "Counts as watched at", 95% unless changed
    public static int watchedPercent(@NonNull final Context context) {
        return Math.max(50, Math.min(100, androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context).getInt("watchedPercent", 95)));
    }

    // the service that says it is watched, or null
    @Nullable
    public static String watchedOn(@NonNull final Context context, @Nullable final String filmKey) {
        if (filmKey == null) {
            return null;
        }
        for (final Service service : services()) {
            if (service.connected(context) && covers(keys(context, service), filmKey)) {
                return service.name();
            }
        }
        return null;
    }

    // what every connected service says is watched, for a screen of ticks; empty when none is
    @NonNull
    public static Set<String> watchedKeys(@NonNull final Context context) {
        final Set<String> all = new HashSet<>();
        for (final Service service : services()) {
            if (service.connected(context)) {
                all.addAll(keys(context, service));
            }
        }
        return all;
    }

    // an episode, or the whole show when the service has it as completed ("tv:1396")
    public static boolean covers(@NonNull final Set<String> keys, @Nullable final String filmKey) {
        if (filmKey == null) {
            return false;
        }
        if (keys.contains(filmKey)) {
            return true;
        }
        final String[] parts = filmKey.split(":");
        return parts.length == 4 && "tv".equals(parts[0]) && keys.contains("tv:" + parts[1]);
    }

    // a field's text, or null when it is missing, empty or JSON null: Android's optString gives
    // the word "null" for that, which once made every unwatched episode count as watched
    @Nullable
    static String text(@Nullable final JSONObject object, @NonNull final String name) {
        if (object == null || object.isNull(name)) {
            return null;
        }
        final String value = object.optString(name, "").trim();
        return value.isEmpty() ? null : value;
    }

    // a real time, as the services write it: 2026-10-31T21:17:01Z
    static boolean isTime(@Nullable final String value) {
        return value != null && value.matches("\\d{4}-\\d{2}-\\d{2}T.*");
    }

    public interface OnRefreshed {
        void refreshed();
    }

    // each connected service's watched list, kept on the device; at most every 15 minutes
    public static void refresh(@NonNull final Context context, final boolean now,
                               @Nullable final OnRefreshed done) {
        if (!anyConnected(context)) {
            return;
        }
        checkLists(context);
        if (!now && SystemClock.elapsedRealtime() - refreshedAt < REFRESH_MS) {
            return;
        }
        refreshedAt = SystemClock.elapsedRealtime();
        final Context app = context.getApplicationContext();
        worker.execute(Background.safely(() -> {
            for (final Service service : services()) {
                if (!service.connected(app)) {
                    continue;
                }
                try {
                    final Set<String> keys = service.watched(app);
                    if (keys != null) {
                        prefs(app).edit().putString(service.name() + ".watched",
                                new JSONArray(keys).toString()).apply();
                    }
                } catch (Exception e) {
                    Utils.log(service.name() + " watched list failed: " + e);
                }
            }
            if (done != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(done::refreshed);
            }
        }));
    }

    // the first 5.1.0 test builds read unwatched episodes as watched; their lists are fetched again
    private static final int LIST_FORMAT = 2;
    private static volatile boolean listsChecked;

    private static void checkLists(final Context context) {
        if (listsChecked) {
            return;
        }
        listsChecked = true;
        final SharedPreferences prefs = prefs(context);
        if (prefs.getInt("listFormat", 1) != LIST_FORMAT) {
            final SharedPreferences.Editor editor = prefs.edit();
            for (final Service service : services()) {
                editor.remove(service.name() + ".watched").remove(service.name() + ".activity")
                        .remove(service.name() + ".listed");
            }
            editor.putInt("listFormat", LIST_FORMAT).apply();
        }
    }

    static Set<String> keys(final Context context, final Service service) {
        checkLists(context);
        final Set<String> keys = new HashSet<>();
        try {
            final JSONArray array = new JSONArray(prefs(context).getString(service.name() + ".watched", "[]"));
            for (int i = 0; i < array.length(); i++) {
                keys.add(array.getString(i));
            }
        } catch (Exception ignored) {
            // nothing known yet
        }
        return keys;
    }

    static void forget(final Context context, final Service service) {
        prefs(context).edit().remove(service.name() + ".watched").remove(service.name() + ".activity")
                .remove(service.name() + ".listed")
                .remove(service.name() + ".token").remove(service.name() + ".refresh")
                .remove(service.name() + ".expires").remove(service.name() + ".user").apply();
    }

    @Nullable
    public static String user(@NonNull final Context context, @NonNull final String name) {
        return prefs(context).getString(name + ".user", null);
    }

    static String now() {
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    // { "title", "year", "ids": { "tmdb": 603 } }
    static JSONObject media(final Identity identity) throws Exception {
        final JSONObject media = new JSONObject();
        media.put("title", identity.title);
        if (identity.year != null && identity.year.matches("\\d{4}")) {
            media.put("year", Integer.parseInt(identity.year));
        }
        final JSONObject ids = new JSONObject();
        ids.put("tmdb", identity.tmdbId);
        if (!identity.isSeries && identity.imdbId != null) {
            ids.put("imdb", identity.imdbId);
        }
        if (identity.isSeries && identity.parentImdbId != null) {
            ids.put("imdb", identity.parentImdbId);
        }
        media.put("ids", ids);
        return media;
    }

    // the /sync/history body both services take, by tmdb id alone
    static JSONObject historyBody(final List<String> keys, final boolean add) throws Exception {
        final JSONArray movies = new JSONArray();
        final JSONArray shows = new JSONArray();
        final String at = now();
        for (final String key : keys) {
            final String[] parts = key.split(":");
            if (parts.length == 2 && "movie".equals(parts[0])) {
                final JSONObject movie = new JSONObject().put("ids",
                        new JSONObject().put("tmdb", Integer.parseInt(parts[1])));
                if (add) {
                    movie.put("watched_at", at);
                }
                movies.put(movie);
            } else if (parts.length == 4 && "tv".equals(parts[0])) {
                final JSONObject episode = new JSONObject().put("number", Integer.parseInt(parts[3]));
                if (add) {
                    episode.put("watched_at", at);
                }
                final JSONObject season = new JSONObject().put("number", Integer.parseInt(parts[2]))
                        .put("episodes", new JSONArray().put(episode));
                shows.put(new JSONObject()
                        .put("ids", new JSONObject().put("tmdb", Integer.parseInt(parts[1])))
                        .put("seasons", new JSONArray().put(season)));
            }
        }
        final JSONObject body = new JSONObject();
        if (movies.length() > 0) {
            body.put("movies", movies);
        }
        if (shows.length() > 0) {
            body.put("shows", shows);
        }
        return body;
    }

    static String send(final Request request) throws Exception {
        try (Response response = com.brouken.player.online.Http.client().newCall(request).execute()) {
            final String text = response.body() == null ? "" : response.body().string();
            // 409: already counted, as a repeated stop is
            if (!response.isSuccessful() && response.code() != 409) {
                throw new HttpError(response.code(), text);
            }
            return text;
        }
    }

    // "HTTP 401" and the like, with what the service said
    static final class HttpError extends java.io.IOException {
        final int code;
        final String body;

        HttpError(final int code, final String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body == null ? "" : body;
        }

        // Simkl allows one write a second and locks a user's writes for 20 s after some
        boolean isWriteLimit() {
            return code == 429 || (code == 400 && body.contains("RATE_LIMIT"));
        }
    }

    static RequestBody json(final JSONObject body) {
        return RequestBody.create(body.toString(), JSON);
    }

    // tmdb ids come as numbers or strings
    @Nullable
    static String tmdbOf(@Nullable final JSONObject media) {
        final String tmdb = text(media == null ? null : media.optJSONObject("ids"), "tmdb");
        return tmdb == null || "0".equals(tmdb) ? null : tmdb;
    }
}
