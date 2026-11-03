package com.brouken.player.ytdlp;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

// A YouTube playlist: titles only, read once; each video is looked up when its turn comes
public final class WebQueue {

    public static final class Entry {
        public final String id;
        // null for a private or deleted video, which YouTube lists by id alone
        @Nullable
        public final String title;
        // 0 when the site doesn't say
        public final long seconds;
        public final boolean available;

        Entry(final String id, @Nullable final String title, final long seconds, final boolean available) {
            this.id = id;
            this.title = title;
            this.seconds = seconds;
            this.available = available;
        }
    }

    public final String listId;
    // opened from YouTube Music: its own list is used when it answers, as it swaps a blocked video for
    // another copy of the song; links stay on music.youtube.com
    public final boolean music;
    public final List<Entry> entries;

    private WebQueue(final String listId, final boolean music, final List<Entry> entries) {
        this.listId = listId;
        this.music = music;
        this.entries = Collections.unmodifiableList(entries);
    }

    // the list a YouTube link belongs to; a watch link with &list= plays its own video first
    @Nullable
    public static String listOf(@Nullable final Uri uri) {
        if (!WebVideo.isYouTube(uri)) {
            return null;
        }
        try {
            final String list = uri.getQueryParameter("list");
            return list == null || list.trim().isEmpty() ? null : list.trim();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    public static boolean isMusic(@Nullable final Uri uri) {
        return uri != null && "music.youtube.com".equalsIgnoreCase(uri.getHost());
    }

    public Uri uriOf(final Entry entry) {
        return Uri.parse((music ? "https://music.youtube.com" : "https://www.youtube.com")
                + "/watch?v=" + entry.id + "&list=" + listId);
    }

    // the playlist's own page starts at its first video that plays
    public int indexOf(@Nullable final Uri uri) {
        if (uri == null || !listId.equals(listOf(uri))) {
            return -1;
        }
        final String place = WebVideo.youTubePlace(uri);
        if (place == null) {
            return step(-1, 1);
        }
        final String id = Uri.parse(place).getQueryParameter("v");
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    public Uri previous(@Nullable final Uri current) {
        final int at = indexOf(current);
        final int to = at < 0 ? -1 : step(at, -1);
        return to < 0 ? null : uriOf(entries.get(to));
    }

    @Nullable
    public Uri next(@Nullable final Uri current) {
        final int at = indexOf(current);
        final int to = at < 0 ? -1 : step(at, 1);
        return to < 0 ? null : uriOf(entries.get(to));
    }

    // the nearest one that plays, as YouTube passes over private and deleted videos
    private int step(final int from, final int direction) {
        for (int i = from + direction; i >= 0 && i < entries.size(); i += direction) {
            if (entries.get(i).available) {
                return i;
            }
        }
        return -1;
    }

    // one request for every hundred titles, nothing per video; not on the main thread
    @Nullable
    public static WebQueue load(final Context context, final String listId, final boolean music,
                                final AtomicReference<Process> running) {
        if (music) {
            final WebQueue queue = parse(listId, true, musicList(context, listId));
            if (queue != null) {
                android.util.Log.i(com.brouken.player.Utils.TAG, "Playlist from YouTube Music: "
                        + queue.entries.size() + " songs");
                return queue;
            }
        }
        final String page = "https://www.youtube.com/playlist?list=" + listId;
        // its own key: a video lookup of the same playlist link keeps a different answer
        final String key = "queue " + page;
        String json = Lookups.read(context, key);
        if (json == null) {
            try {
                final YtDlp.Result result = YtDlp.run(context, new ArrayList<>(Arrays.asList(
                        "--ignore-config", "--flat-playlist", "--socket-timeout", "20", "-J", "--", page)),
                        120_000, running);
                if (result.exitCode != 0) {
                    android.util.Log.i(com.brouken.player.Utils.TAG, "Playlist not read: "
                            + YtDlp.lastLine(result.err));
                    return null;
                }
                json = result.out;
            } catch (IOException e) {
                return null;
            }
            Lookups.write(context, key, json);
        }
        final WebQueue queue = parse(listId, music, json);
        if (queue != null) {
            int missing = 0;
            for (final Entry entry : queue.entries) {
                missing += entry.available ? 0 : 1;
            }
            android.util.Log.i(com.brouken.player.Utils.TAG, "Playlist from youtube.com: "
                    + queue.entries.size() + " videos, " + missing + " unavailable");
        }
        return queue;
    }

    @Nullable
    private static WebQueue parse(final String listId, final boolean music, @Nullable final String json) {
        if (json == null) {
            return null;
        }
        try {
            final JSONArray items = new JSONObject(json).optJSONArray("entries");
            final List<Entry> entries = new ArrayList<>();
            for (int i = 0; items != null && i < items.length(); i++) {
                final JSONObject item = items.optJSONObject(i);
                final String id = item == null ? "" : item.optString("id", "");
                if (id.isEmpty()) {
                    continue;
                }
                // optString turns a JSON null into the word "null"
                final String title = item.isNull("title") ? null : item.optString("title", null);
                final boolean available = item.optBoolean("available",
                        title != null && !title.matches("\\[(Private|Deleted) video]"));
                entries.add(new Entry(id, available ? title : null, item.optLong("duration", 0), available));
            }
            return entries.size() < 2 ? null : new WebQueue(listId, music, entries);
        } catch (Exception e) {
            return null;
        }
    }

    // YouTube Music's own list, as its site shows it to someone signed out. yt-dlp reads
    // music playlists from youtube.com, which lists the blocked originals instead
    @Nullable
    private static String musicList(final Context context, final String listId) {
        final String key = "queue https://music.youtube.com/playlist?list=" + listId;
        final String kept = Lookups.read(context, key);
        if (kept != null) {
            return kept;
        }
        final okhttp3.OkHttpClient client = com.brouken.player.online.Http.client().newBuilder()
                .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build();
        final JSONArray entries = new JSONArray();
        try {
            JSONObject answer = browse(client, new JSONObject().put("browseId", "VL" + listId));
            JSONArray rows = shelfOf(answer);
            // a hundred a page; ten pages is more than any playlist anyone plays through
            for (int page = 0; rows != null && page < 10; page++) {
                String more = null;
                for (int i = 0; i < rows.length(); i++) {
                    final JSONObject row = rows.optJSONObject(i);
                    if (row == null) {
                        continue;
                    }
                    final JSONObject song = row.optJSONObject("musicResponsiveListItemRenderer");
                    if (song != null) {
                        add(entries, song);
                    }
                    final String token = continuationOf(row);
                    if (token != null) {
                        more = token;
                    }
                }
                if (more == null) {
                    break;
                }
                answer = browse(client, new JSONObject().put("continuation", more));
                rows = continuedRows(answer);
            }
        } catch (Exception e) {
            android.util.Log.i(com.brouken.player.Utils.TAG, "YouTube Music list not read: " + e);
            return null;
        }
        if (entries.length() < 2) {
            return null;
        }
        final String json;
        try {
            json = new JSONObject().put("entries", entries).toString();
        } catch (org.json.JSONException e) {
            return null;
        }
        Lookups.write(context, key, json);
        return json;
    }

    private static JSONObject browse(final okhttp3.OkHttpClient client, final JSONObject body) throws Exception {
        body.put("context", new JSONObject().put("client", new JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20260707.12.00")
                .put("hl", "en")));
        final okhttp3.Request request = new okhttp3.Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                .header("Origin", "https://music.youtube.com")
                .post(okhttp3.RequestBody.create(body.toString(),
                        okhttp3.MediaType.parse("application/json")))
                .build();
        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            return new JSONObject(response.body().string());
        }
    }

    private static void add(final JSONArray entries, final JSONObject song) throws org.json.JSONException {
        final JSONObject data = song.optJSONObject("playlistItemData");
        final String id = data == null ? "" : data.optString("videoId", "");
        if (id.isEmpty()) {
            return;
        }
        final JSONArray columns = song.optJSONArray("flexColumns");
        final String title = text(columns, 0, "musicResponsiveListItemFlexColumnRenderer");
        final String artist = text(columns, 1, "musicResponsiveListItemFlexColumnRenderer");
        final String length = text(song.optJSONArray("fixedColumns"), 0, "musicResponsiveListItemFixedColumnRenderer");
        final boolean greyed = song.optString("musicItemRendererDisplayPolicy", "").endsWith("GREY_OUT");
        entries.put(new JSONObject()
                .put("id", id)
                .put("title", title.isEmpty() ? JSONObject.NULL
                        : artist.isEmpty() ? title : title + " · " + artist)
                .put("duration", seconds(length))
                .put("available", !greyed && !title.isEmpty()));
    }

    private static String text(@Nullable final JSONArray columns, final int index, final String kind) {
        final JSONObject column = columns == null ? null : columns.optJSONObject(index);
        final JSONObject renderer = column == null ? null : column.optJSONObject(kind);
        final JSONObject text = renderer == null ? null : renderer.optJSONObject("text");
        if (text == null) {
            return "";
        }
        if (text.has("simpleText")) {
            return text.optString("simpleText", "");
        }
        final JSONArray runs = text.optJSONArray("runs");
        final StringBuilder out = new StringBuilder();
        for (int i = 0; runs != null && i < runs.length(); i++) {
            final JSONObject run = runs.optJSONObject(i);
            out.append(run == null ? "" : run.optString("text", ""));
        }
        return out.toString().trim();
    }

    // "4:05" or "1:02:10"
    private static long seconds(final String length) {
        long total = 0;
        for (final String part : length.split(":")) {
            try {
                total = total * 60 + Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return total;
    }

    // the playlist's own shelf, not the suggestions under it
    @Nullable
    private static JSONArray shelfOf(final JSONObject answer) {
        final JSONObject shelf = find(answer, "musicPlaylistShelfRenderer");
        return shelf == null ? null : shelf.optJSONArray("contents");
    }

    @Nullable
    private static JSONArray continuedRows(final JSONObject answer) {
        final JSONArray actions = answer.optJSONArray("onResponseReceivedActions");
        for (int i = 0; actions != null && i < actions.length(); i++) {
            final JSONObject action = actions.optJSONObject(i);
            final JSONObject append = action == null ? null : action.optJSONObject("appendContinuationItemsAction");
            if (append != null) {
                return append.optJSONArray("continuationItems");
            }
        }
        final JSONObject old = find(answer, "musicPlaylistShelfContinuation");
        return old == null ? null : old.optJSONArray("contents");
    }

    @Nullable
    private static String continuationOf(final JSONObject row) {
        final JSONObject more = row.optJSONObject("continuationItemRenderer");
        final JSONObject endpoint = more == null ? null : more.optJSONObject("continuationEndpoint");
        final JSONObject command = endpoint == null ? null : endpoint.optJSONObject("continuationCommand");
        final String token = command == null ? null : command.optString("token", null);
        return token == null || token.isEmpty() ? null : token;
    }

    @Nullable
    private static JSONObject find(final Object node, final String name) {
        if (node instanceof JSONObject) {
            final JSONObject object = (JSONObject) node;
            final JSONObject direct = object.optJSONObject(name);
            if (direct != null) {
                return direct;
            }
            final java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                final JSONObject found = find(object.opt(keys.next()), name);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof JSONArray) {
            final JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                final JSONObject found = find(array.opt(i), name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
