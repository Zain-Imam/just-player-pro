package com.brouken.player.online;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.io.IOException;

// free and keyless; only an anime's name is ever sent
final class AniList {

    static final String PREF = "aniListHelp";
    private static final String URL = "https://graphql.anilist.co";

    private AniList() {
    }

    static boolean wanted(@NonNull final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF, true);
    }

    static final class Service implements AutoMatch.AnimeSource {

        @Nullable
        @Override
        public JSONObject ask(@NonNull final String query, @NonNull final JSONObject variables)
                throws IOException {
            final String body;
            try {
                body = new JSONObject().put("query", query).put("variables", variables).toString();
            } catch (Exception e) {
                throw new IOException(e);
            }
            final Http.Result result = Http.post(URL, null, body);
            if (result.code == 404) {
                return null;
            }
            if (!result.ok()) {
                throw new IOException(result.code > 0 ? "AniList answered " + result.code
                        : String.valueOf(result.error));
            }
            final JSONObject json = result.json();
            if (json == null) {
                throw new IOException("AniList's answer could not be read");
            }
            return json.optJSONObject("data");
        }
    }
}
