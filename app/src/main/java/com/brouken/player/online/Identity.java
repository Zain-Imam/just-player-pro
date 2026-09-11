package com.brouken.player.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

public final class Identity {

    public final boolean isSeries;
    public final int tmdbId;
    @Nullable
    public final String imdbId;
    @Nullable
    public final String parentImdbId;
    @NonNull
    public final String title;
    @Nullable
    public final String episodeTitle;
    @Nullable
    public final Integer season;
    @Nullable
    public final Integer episode;
    @Nullable
    public final String year;
    @Nullable
    public final String overview;
    @Nullable
    public final String posterPath;
    @Nullable
    public final String airDate;
    public final double rating;

    public Identity(boolean isSeries, int tmdbId, @Nullable String imdbId,
                    @Nullable String parentImdbId, @NonNull String title,
                    @Nullable String episodeTitle, @Nullable Integer season,
                    @Nullable Integer episode, @Nullable String year,
                    @Nullable String overview, @Nullable String posterPath,
                    @Nullable String airDate, double rating) {
        this.isSeries = isSeries;
        this.tmdbId = tmdbId;
        this.imdbId = imdbId;
        this.parentImdbId = parentImdbId;
        this.title = title;
        this.episodeTitle = episodeTitle;
        this.season = season;
        this.episode = episode;
        this.year = year;
        this.overview = overview;
        this.posterPath = posterPath;
        this.airDate = airDate;
        this.rating = rating;
    }

    @Nullable
    public String episodeCode() {
        if (season == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder("S");
        if (season < 10) sb.append('0');
        sb.append(season);
        if (episode != null) {
            sb.append('E');
            if (episode < 10) sb.append('0');
            sb.append(episode);
        }
        return sb.toString();
    }

    @NonNull
    public String heading() {
        final String code = episodeCode();
        if (code == null) {
            return year == null ? title : title + " (" + year + ")";
        }
        return episodeTitle == null || episodeTitle.isEmpty()
                ? title + " · " + code
                : title + " · " + code + " · " + episodeTitle;
    }

    @Nullable
    public String bestImdbId() {
        return imdbId != null ? imdbId : parentImdbId;
    }

    // -- persistence, so a file identified once stays identified -------------

    @NonNull
    public JSONObject toJson() {
        final JSONObject o = new JSONObject();
        try {
            o.put("isSeries", isSeries);
            o.put("tmdbId", tmdbId);
            o.putOpt("imdbId", imdbId);
            o.putOpt("parentImdbId", parentImdbId);
            o.put("title", title);
            o.putOpt("episodeTitle", episodeTitle);
            if (season != null) o.put("season", season.intValue());
            if (episode != null) o.put("episode", episode.intValue());
            o.putOpt("year", year);
            o.putOpt("overview", overview);
            o.putOpt("posterPath", posterPath);
            o.putOpt("airDate", airDate);
            o.put("rating", rating);
        } catch (Exception e) {
            // A JSONObject with String keys cannot actually throw here.
        }
        return o;
    }

    @Nullable
    public static Identity fromJson(@Nullable final JSONObject o) {
        if (o == null) {
            return null;
        }
        final String title = o.optString("title", null);
        if (title == null || title.isEmpty()) {
            return null;
        }
        return new Identity(
                o.optBoolean("isSeries", false),
                o.optInt("tmdbId", 0),
                emptyToNull(o.optString("imdbId", null)),
                emptyToNull(o.optString("parentImdbId", null)),
                title,
                emptyToNull(o.optString("episodeTitle", null)),
                o.has("season") ? Integer.valueOf(o.optInt("season")) : null,
                o.has("episode") ? Integer.valueOf(o.optInt("episode")) : null,
                emptyToNull(o.optString("year", null)),
                emptyToNull(o.optString("overview", null)),
                emptyToNull(o.optString("posterPath", null)),
                emptyToNull(o.optString("airDate", null)),
                o.optDouble("rating", 0));
    }

    @Nullable
    private static String emptyToNull(@Nullable final String value) {
        return value == null || value.isEmpty() || "null".equals(value) ? null : value;
    }
}
