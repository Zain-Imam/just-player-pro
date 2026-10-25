package com.brouken.player.online;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class MatchTmdb {

    private static final String BASE = "https://api.themoviedb.org/3";
    private static final int MAX_ENTRIES = 300;

    private MatchTmdb() {
    }

    static final class Service implements AutoMatch.Source {
        private final Context context;

        Service(@NonNull final Context context) {
            this.context = context.getApplicationContext();
        }

        @Nullable
        @Override
        public JSONObject get(@NonNull final String path, @NonNull final Map<String, String> params)
                throws IOException {
            final String key = ApiKeys.get(context, ApiKeys.PREF_TMDB);
            if (key == null) {
                throw new IOException("no TMDB key");
            }
            final Map<String, String> query = Http.params();
            query.putAll(params);
            query.put("api_key", key);
            final Http.Result result = Http.get(BASE + path + Http.query(query), null);
            if (result.code == 404) {
                return null;
            }
            if (!result.ok()) {
                throw new IOException(result.code > 0 ? "TMDB answered " + result.code
                        : String.valueOf(result.error));
            }
            final JSONObject json = result.json();
            if (json == null) {
                throw new IOException("TMDB's answer could not be read");
            }
            return json;
        }
    }

    // the oldest go first past 300; a lost file costs a few requests, nothing more
    static final class DiskCache implements AutoMatch.Cache {
        private final File file;
        @Nullable
        private JSONObject all;

        DiskCache(@NonNull final Context context) {
            file = new File(context.getApplicationContext().getFilesDir(), "tmdb-cache.json");
        }

        private JSONObject all() {
            if (all == null) {
                try {
                    final byte[] bytes = com.brouken.player.Utils.readFile(file);
                    all = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    all = new JSONObject();
                }
            }
            return all;
        }

        @Nullable
        @Override
        public synchronized JSONObject get(@NonNull final String key, final int hours) {
            final JSONObject entry = all().optJSONObject(key);
            if (entry == null) {
                return null;
            }
            // a 404 is kept for a day whatever the kind
            final int keep = entry.isNull("v") ? 24 : hours;
            if (System.currentTimeMillis() - entry.optLong("t") >= keep * 3_600_000L) {
                return null;
            }
            final JSONObject hit = new JSONObject();
            try {
                hit.put("v", entry.opt("v"));
            } catch (Exception ignored) {
                // only string keys, cannot throw
            }
            return hit;
        }

        @Override
        public synchronized void put(@NonNull final String key, @Nullable final JSONObject value) {
            final JSONObject cache = all();
            try {
                final JSONObject entry = new JSONObject();
                entry.put("t", System.currentTimeMillis());
                entry.put("v", value == null ? JSONObject.NULL : value);
                cache.put(key, entry);
                if (cache.length() > MAX_ENTRIES) {
                    final List<String> keys = new ArrayList<>();
                    final Iterator<String> each = cache.keys();
                    while (each.hasNext()) {
                        keys.add(each.next());
                    }
                    Collections.sort(keys, (a, b) -> Long.compare(
                            cache.optJSONObject(a).optLong("t"), cache.optJSONObject(b).optLong("t")));
                    for (final String old : keys.subList(0, keys.size() - MAX_ENTRIES)) {
                        cache.remove(old);
                    }
                }
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(cache.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                // kept in memory for this run at least
            }
        }
    }
}
