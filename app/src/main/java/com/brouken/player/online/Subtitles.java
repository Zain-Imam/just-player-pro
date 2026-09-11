package com.brouken.player.online;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Subtitles {

    private Subtitles() {
    }

    public enum Source {
        OPENSUBTITLES("OpenSubtitles"),
        SUBDL("SubDL"),
        WYZIE("Wyzie"),
        ADDON("Addon");

        public final String label;

        Source(String label) {
            this.label = label;
        }
    }

    static Result addonResult(final String addonName, @NonNull final String release,
                              @NonNull final String language, @NonNull final String url,
                              @NonNull final String extension, final boolean hearingImpaired,
                              final boolean aiTranslated, final boolean fromTrusted) {
        final Result result = new Result(Source.ADDON, release, language, url, null, extension,
                hearingImpaired, aiTranslated, fromTrusted, 0, "addon");
        result.sourceLabel = addonName;
        return result;

    }
    public static final class Result implements ListPicker.Row {
        public final Source source;
        @NonNull
        public final String release;
        @NonNull
        public final String language;
        @Nullable
        public final String url;
        @Nullable
        public final String fileId;
        @NonNull
        public final String extension;
        public final boolean hearingImpaired;
        public final boolean aiTranslated;
        public final boolean fromTrusted;
        public final int downloads;
        @NonNull
        final String attemptLabel;

        int score;
        boolean wantedLanguage;
        
        String sourceLabel;

        Result(Source source, @NonNull String release, @NonNull String language,
               @Nullable String url, @Nullable String fileId, @NonNull String extension,
               boolean hearingImpaired, boolean aiTranslated, boolean fromTrusted,
               int downloads, @NonNull String attemptLabel) {
            this.source = source;
            this.release = release;
            this.language = language;
            this.url = url;
            this.fileId = fileId;
            this.extension = extension;
            this.hearingImpaired = hearingImpaired;
            this.aiTranslated = aiTranslated;
            this.fromTrusted = fromTrusted;
            this.downloads = downloads;
            this.attemptLabel = attemptLabel;
        }

        @NonNull
        @Override
        public String title() {
            return release.isEmpty() ? "(no release name)" : release;
        }

        @NonNull
        @Override
        public String detail() {
            final StringBuilder sb = new StringBuilder(sourceLabel != null ? sourceLabel : source.label);
            sb.append("  ·  ").append(language.toUpperCase(Locale.ROOT));
            if (hearingImpaired) sb.append("  ·  HI");
            if (aiTranslated) sb.append("  ·  AI");
            if (downloads > 0) sb.append("  ·  ").append(downloads).append(" downloads");
            return sb.toString();
        }
    }

    public static final class Target {
        final Identity identity;
        final String language;
        @Nullable
        final String fileName;
        @Nullable
        final String manualQuery;

        public Target(Identity identity, String language, @Nullable String fileName,
                      @Nullable String manualQuery) {
            this.identity = identity;
            this.language = language == null || language.isEmpty() ? "en" : language;
            this.fileName = fileName;
            this.manualQuery = manualQuery;
        }
    }

    // ---------------------------------------------------------------- search

    @NonNull
    public static List<Result> search(final Context context, final Target target) {
        final List<Result> all = new ArrayList<>();

        if (ApiKeys.has(context, ApiKeys.PREF_OPENSUBTITLES)) {
            all.addAll(openSubtitles(context, target));
        }
        if (ApiKeys.has(context, ApiKeys.PREF_SUBDL)) {
            all.addAll(subdl(context, target));
        }
        if (ApiKeys.has(context, ApiKeys.PREF_WYZIE)) {
            all.addAll(wyzie(context, target));
        }
        // The user's own addons need no key at all, which is what makes them the
        // only coverage available to someone who has signed up for nothing.
        all.addAll(SubtitleAddons.search(context, target));

        for (final Result result : all) {
            result.wantedLanguage = result.language.equalsIgnoreCase(target.language);
            result.score = score(result, target);
        }

        Collections.sort(all, new Comparator<Result>() {
            @Override
            public int compare(Result a, Result b) {
                final boolean aWanted = a.wantedLanguage;
                final boolean bWanted = b.wantedLanguage;
                if (aWanted != bWanted) return aWanted ? -1 : 1;
                if (a.score != b.score) return b.score - a.score;
                if (!a.language.equals(b.language)) return a.language.compareTo(b.language);
                if (a.downloads != b.downloads) return b.downloads - a.downloads;
                return a.source.label.compareTo(b.source.label);
            }
        });

        return all;
    }

    // ------------------------------------------------------- OpenSubtitles

    private static List<Result> openSubtitles(final Context context, final Target target) {
        final String key = ApiKeys.get(context, ApiKeys.PREF_OPENSUBTITLES);
        final Map<String, String> headers = Http.params();
        headers.put("Api-Key", key);

        final Identity id = target.identity;
        final String language = target.language.toLowerCase(Locale.ROOT);

        if (target.manualQuery != null) {
            final Map<String, String> params = Http.params();
            params.put("query", target.manualQuery);
            params.put("languages", language);
            return osCall(params, headers, "Text search");
        }

        List<Result> results;

        if (!id.isSeries) {
            if (id.imdbId != null) {
                final Map<String, String> p = Http.params();
                p.put("imdb_id", stripTt(id.imdbId));
                p.put("languages", language);
                results = osCall(p, headers, "IMDB lookup");
                if (!results.isEmpty()) return results;
            }
            if (id.tmdbId > 0) {
                final Map<String, String> p = Http.params();
                p.put("tmdb_id", String.valueOf(id.tmdbId));
                p.put("languages", language);
                if (id.year != null) p.put("year", id.year);
                results = osCall(p, headers, "TMDB lookup");
                if (!results.isEmpty()) return results;
            }
            final Map<String, String> p = Http.params();
            p.put("query", id.title);
            p.put("languages", language);
            p.put("type", "movie");
            if (id.year != null) p.put("year", id.year);
            return osCall(p, headers, "Text search");
        }

        // An episode's own id, with NO season or episode numbers. Sometimes the
        // only thing that finds very fresh content.
        if (id.imdbId != null) {
            final Map<String, String> p = Http.params();
            p.put("imdb_id", stripTt(id.imdbId));
            p.put("languages", language);
            results = osCall(p, headers, "Episode IMDB lookup");
            if (!results.isEmpty()) return results;
        }
        if (id.parentImdbId != null && id.season != null && id.episode != null) {
            final Map<String, String> p = Http.params();
            p.put("parent_imdb_id", stripTt(id.parentImdbId));
            p.put("season_number", String.valueOf(id.season));
            p.put("episode_number", String.valueOf(id.episode));
            p.put("languages", language);
            results = osCall(p, headers, "Show IMDB lookup");
            if (!results.isEmpty()) return results;
        }
        if (id.tmdbId > 0 && id.season != null && id.episode != null) {
            final Map<String, String> p = Http.params();
            p.put("parent_tmdb_id", String.valueOf(id.tmdbId));
            p.put("season_number", String.valueOf(id.season));
            p.put("episode_number", String.valueOf(id.episode));
            p.put("languages", language);
            results = osCall(p, headers, "TMDB lookup");
            if (!results.isEmpty()) return results;
        }

        final Map<String, String> p = Http.params();
        p.put("query", id.title);
        p.put("languages", language);
        p.put("type", "episode");
        if (id.season != null) p.put("season_number", String.valueOf(id.season));
        if (id.episode != null) p.put("episode_number", String.valueOf(id.episode));
        return osCall(p, headers, "Text search");
    }

    private static List<Result> osCall(final Map<String, String> params,
                                       final Map<String, String> headers, final String attempt) {
        final List<Result> results = new ArrayList<>();
        final Http.Result response =
                Http.get("https://api.opensubtitles.com/api/v1/subtitles" + Http.query(params), headers);

        final JSONObject json = response.json();
        if (json == null) {
            if (!response.ok()) {
                Log.note("OpenSubtitles " + attempt + " -> HTTP " + response.code);
            }
            return results;
        }

        final JSONArray data = json.optJSONArray("data");
        if (data == null) {
            return results;
        }

        for (int i = 0; i < data.length(); i++) {
            final JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            final JSONObject attrs = row.optJSONObject("attributes");
            if (attrs == null) continue;

            final JSONArray files = attrs.optJSONArray("files");
            String fileId = null;
            if (files != null && files.length() > 0) {
                final JSONObject file = files.optJSONObject(0);
                if (file != null) {
                    fileId = String.valueOf(file.optInt("file_id"));
                }
            }
            if (fileId == null) continue;

            final JSONObject uploader = attrs.optJSONObject("uploader");

            results.add(new Result(
                    Source.OPENSUBTITLES,
                    orEmpty(attrs.optString("release", "")),
                    orEmpty(attrs.optString("language", "")),
                    null,
                    fileId,
                    "srt",
                    attrs.optBoolean("hearing_impaired", false),
                    attrs.optBoolean("ai_translated", false)
                            || attrs.optBoolean("machine_translated", false),
                    uploader != null && "administrator".equalsIgnoreCase(uploader.optString("rank", ""))
                            || attrs.optBoolean("from_trusted", false),
                    attrs.optInt("download_count", 0),
                    attempt));
        }
        return results;
    }

    // ---------------------------------------------------------------- SubDL

    private static List<Result> subdl(final Context context, final Target target) {
        final String key = ApiKeys.get(context, ApiKeys.PREF_SUBDL);
        final Identity id = target.identity;
        final String language = target.language.toUpperCase(Locale.ROOT);

        if (target.manualQuery != null) {
            final Map<String, String> p = Http.params();
            p.put("api_key", key);
            p.put("film_name", target.manualQuery);
            p.put("languages", language);
            p.put("subs_per_page", "30");
            return subdlCall(p, "Text search");
        }

        List<Result> results;

        if (id.tmdbId > 0) {
            final Map<String, String> p = Http.params();
            p.put("api_key", key);
            p.put("tmdb_id", String.valueOf(id.tmdbId));
            p.put("type", id.isSeries ? "tv" : "movie");
            p.put("languages", language);
            p.put("subs_per_page", "30");
            if (id.isSeries && id.season != null) p.put("season_number", String.valueOf(id.season));
            if (id.isSeries && id.episode != null) p.put("episode_number", String.valueOf(id.episode));
            if (!id.isSeries && id.year != null) p.put("year", id.year);
            results = subdlCall(p, "TMDB lookup");
            if (!results.isEmpty()) return results;
        }

        final String imdb = id.isSeries ? id.parentImdbId : id.imdbId;
        if (imdb != null) {
            final Map<String, String> p = Http.params();
            p.put("api_key", key);
            p.put("imdb_id", withTt(imdb));
            p.put("type", id.isSeries ? "tv" : "movie");
            p.put("languages", language);
            p.put("subs_per_page", "30");
            if (id.isSeries && id.season != null) p.put("season_number", String.valueOf(id.season));
            if (id.isSeries && id.episode != null) p.put("episode_number", String.valueOf(id.episode));
            results = subdlCall(p, "IMDB lookup");
            if (!results.isEmpty()) return results;
        }

        final Map<String, String> p = Http.params();
        p.put("api_key", key);
        p.put("film_name", id.title);
        p.put("languages", language);
        p.put("subs_per_page", "30");
        return subdlCall(p, "Text search");
    }

    private static List<Result> subdlCall(final Map<String, String> params, final String attempt) {
        final List<Result> results = new ArrayList<>();
        final Http.Result response =
                Http.get("https://api.subdl.com/api/v1/subtitles" + Http.query(params), null);

        final JSONObject json = response.json();
        if (json == null || !json.optBoolean("status", false)) {
            if (!response.ok()) {
                Log.note("SubDL " + attempt + " -> HTTP " + response.code);
            }
            return results;
        }

        final JSONArray data = json.optJSONArray("subtitles");
        if (data == null) {
            return results;
        }

        for (int i = 0; i < data.length(); i++) {
            final JSONObject row = data.optJSONObject(i);
            if (row == null) continue;

            String url = row.optString("url", "");
            if (url.isEmpty()) continue;
            if (url.startsWith("/")) {
                url = "https://dl.subdl.com" + url;
            }

            results.add(new Result(
                    Source.SUBDL,
                    orEmpty(row.optString("release_name", row.optString("name", ""))),
                    orEmpty(row.optString("lang", "")),
                    url,
                    null,
                    url.toLowerCase(Locale.ROOT).endsWith(".zip") ? "zip" : "srt",
                    row.optBoolean("hi", false),
                    false,
                    false,
                    0,
                    attempt));
        }
        return results;
    }

    // ---------------------------------------------------------------- Wyzie

    private static List<Result> wyzie(final Context context, final Target target) {
        final String key = ApiKeys.get(context, ApiKeys.PREF_WYZIE);
        final Identity id = target.identity;
        // A series is asked by the SHOW's id with season and episode alongside.
        final String imdb = id.isSeries ? id.parentImdbId : id.imdbId;

        if (imdb == null) {
            // No id, nothing to ask with — and saying so beats an empty list that
            // looks like "no subtitles exist".
            Log.note("Wyzie skipped: no IMDb id");
            return new ArrayList<>();
        }

        final Map<String, String> p = Http.params();
        p.put("id", withTt(imdb));
        p.put("language", target.language.toLowerCase(Locale.ROOT));
        p.put("format", "srt");
        p.put("key", key);
        if (id.isSeries && id.season != null) p.put("season", String.valueOf(id.season));
        if (id.isSeries && id.episode != null) p.put("episode", String.valueOf(id.episode));

        final List<Result> results = new ArrayList<>();
        final Http.Result response = Http.get("https://sub.wyzie.io/search" + Http.query(p), null);

        final JSONArray data = response.jsonArray();
        if (data == null) {
            if (!response.ok()) {
                Log.note("Wyzie -> HTTP " + response.code);
            }
            return results;
        }

        for (int i = 0; i < data.length(); i++) {
            final JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            final String url = row.optString("url", "");
            if (url.isEmpty()) continue;

            results.add(new Result(
                    Source.WYZIE,
                    orEmpty(row.optString("release", row.optString("media", ""))),
                    orEmpty(row.optString("language", "")),
                    url,
                    null,
                    orEmpty(row.optString("format", "srt")),
                    row.optBoolean("isHearingImpaired", false),
                    false,
                    false,
                    0,
                    "IMDB lookup"));
        }
        return results;
    }

    // -------------------------------------------------------------- ranking

    static int score(final Result row, final Target target) {
        final Identity id = target.identity;
        int score = 0;

        // No language term here: it is a sort gate, not a score. See the
        // comparator in search().

        if (row.attemptLabel.toLowerCase(Locale.ROOT).contains("imdb")
                || row.attemptLabel.toLowerCase(Locale.ROOT).contains("tmdb")) {
            score += 25;
        } else if (row.attemptLabel.toLowerCase(Locale.ROOT).contains("text")) {
            score -= 10;
        }

        final double overlap = titleOverlap(row.release, id.title);
        if (overlap >= 0.8) {
            score += 40;
        } else if (overlap >= 0.5) {
            score += 15;
        }

        if (!id.isSeries) {
            if (id.year != null && Pattern.compile("\\b" + Pattern.quote(id.year) + "\\b")
                    .matcher(normalise(row.release)).find()) {
                score += 20;
            }
        } else if (id.season != null && id.episode != null) {
            if (hasEpisode(row.release, id.season, id.episode)) {
                score += 60;
            } else if (hasAnyEpisode(row.release)) {
                score -= 90;
            } else if (isSeasonPack(row.release, id.season)) {
                score += 20;
            } else if (namesOtherSeason(row.release, id.season)) {
                score -= 70;
            }
        } else if (id.season != null) {
            if (isSeasonPack(row.release, id.season)) {
                score += 60;
            } else if (hasAnyEpisode(row.release)) {
                score -= 20;
            }
        }

        if (row.aiTranslated) score -= 8;
        if (row.fromTrusted) score += 5;

        score += qualityNudge(row.release);
        score += fileMatch(row.release, target.fileName);

        return score;
    }

    static int fileMatch(final String release, @Nullable final String fileName) {
        if (fileName == null || fileName.isEmpty() || release.isEmpty()) {
            return 0;
        }
        final String a = normalise(release);
        final String b = normalise(fileName);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }

        // The whole release name appearing in the file name (or the reverse) is
        // as close to "same rip" as this can get.
        if (a.equals(b) || b.contains(a) || a.contains(b)) {
            return 14;
        }

        int points = 0;
        final String group = releaseGroup(fileName);
        if (group != null && a.contains(normalise(group))) {
            points += 8;
        }
        for (final String token : new String[]{
                "2160p", "1080p", "720p", "480p",
                "bluray", "web dl", "webrip", "hdtv", "remux", "dvdrip"}) {
            if (a.contains(token) && b.contains(token)) {
                points += 3;
                if (points >= 12) break;
            }
        }
        return Math.min(points, 12);
    }

    @Nullable
    private static String releaseGroup(final String name) {
        final Matcher m = Pattern.compile("-([A-Za-z0-9]{2,15})(\\.[A-Za-z0-9]{2,4})?$").matcher(name.trim());
        return m.find() ? m.group(1) : null;
    }

    static int qualityNudge(final String release) {
        final String t = normalise(release);
        if (Pattern.compile("\\b(cam|camrip|hdcam|ts|telesync|tc|telecine|workprint|scr|screener)\\b")
                .matcher(t).find()) {
            return -10;
        }
        if (Pattern.compile("\\b(remux|bluray|blu ray|bdrip|brrip|web dl|webdl|webrip|web|hdtv|dvdrip)\\b")
                .matcher(t).find()) {
            return 8;
        }
        return 0;
    }

    static String normalise(@Nullable final String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[._]+", " ")
                .replaceAll("[^a-z0-9\\s]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static boolean hasEpisode(final String release, final int season, final int episode) {
        final String t = normalise(release);
        return Pattern.compile("\\bs0*" + season + "\\s*e0*" + episode + "\\b").matcher(t).find()
                || Pattern.compile("\\b0*" + season + "x0*" + episode + "\\b").matcher(t).find()
                || Pattern.compile("\\bseason\\s*0*" + season + "\\s*episode\\s*0*" + episode + "\\b")
                        .matcher(t).find();
    }

    static boolean hasAnyEpisode(final String release) {
        final String t = normalise(release);
        return Pattern.compile("\\bs\\d{1,2}\\s*e\\d{1,3}\\b").matcher(t).find()
                || Pattern.compile("\\b\\d{1,2}x\\d{2,3}\\b").matcher(t).find();
    }

    static boolean namesOtherSeason(final String release, final int season) {
        final String t = normalise(release);
        final Matcher m = Pattern.compile("\\bs(?:eason)?\\s*0*(\\d{1,2})\\b").matcher(t);
        while (m.find()) {
            try {
                if (Integer.parseInt(m.group(1)) != season) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // Not a season number after all.
            }
        }
        return false;
    }

    static boolean isSeasonPack(final String release, final int season) {
        final String t = normalise(release);
        final boolean mentions =
                Pattern.compile("\\bs0*" + season + "\\b").matcher(t).find()
                        || Pattern.compile("\\bseason\\s*0*" + season + "\\b").matcher(t).find();
        return mentions && !hasAnyEpisode(t);
    }

    static double titleOverlap(final String release, @Nullable final String title) {
        final String t = normalise(title);
        if (t.isEmpty()) return 0;
        final String[] words = t.split(" ");
        int total = 0;
        int found = 0;
        final String r = normalise(release);
        for (final String word : words) {
            if (word.length() <= 1) continue;
            total++;
            if (r.contains(word)) found++;
        }
        return total == 0 ? 0 : (double) found / total;
    }

    // ------------------------------------------------------------- download

    @Nullable
    public static byte[] download(final Context context, final Result result) {
        if (result.source == Source.OPENSUBTITLES) {
            final String key = ApiKeys.get(context, ApiKeys.PREF_OPENSUBTITLES);
            if (key == null || result.fileId == null) {
                return null;
            }

            final Map<String, String> headers = Http.params();
            headers.put("Api-Key", key);
            headers.put("Content-Type", "application/json");

            final JSONObject payload = new JSONObject();
            try {
                payload.put("file_id", Integer.parseInt(result.fileId));
            } catch (Exception e) {
                return null;
            }

            final Http.Result response = Http.post(
                    "https://api.opensubtitles.com/api/v1/download", headers, payload.toString());
            final JSONObject json = response.json();
            if (json == null) {
                Log.note("OpenSubtitles download -> HTTP " + response.code);
                return null;
            }
            final String link = json.optString("link", "");
            return link.isEmpty() ? null : unwrap(Http.getBytes(link, null));
        }

        if (result.url == null) {
            return null;
        }
        return unwrap(Http.getBytes(result.url, null));
    }

    @Nullable
    static byte[] unwrap(@Nullable final byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return bytes;
        }
        if (bytes[0] != 0x50 || bytes[1] != 0x4B) {
            return bytes;
        }

        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                final String name = entry.getName().toLowerCase(Locale.ROOT);
                if (entry.isDirectory() || !name.matches(".*\\.(srt|ass|ssa|vtt|sub)$")) {
                    continue;
                }
                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                if (out.size() > 0) {
                    return out.toByteArray();
                }
            }
        } catch (java.io.IOException e) {
            Log.note("Subtitle archive could not be opened: " + e);
            return null;
        }
        Log.note("Subtitle archive held no subtitle");
        return null;
    }

    // --------------------------------------------------------------- helpers

    static String stripTt(final String imdbId) {
        String s = imdbId.trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("tt")) {
            s = s.substring(2);
        }
        s = s.replaceFirst("^0+", "");
        return s.isEmpty() ? "0" : s;
    }

    static String withTt(final String imdbId) {
        final String s = imdbId.trim();
        return s.toLowerCase(Locale.ROOT).startsWith("tt") ? s : "tt" + s;
    }

    private static String orEmpty(@Nullable final String s) {
        return s == null || "null".equals(s) ? "" : s;
    }

    static final class Log {
        static void note(final String message) {
            android.util.Log.w("JustPlayer", "[online] " + message);
        }
    }
}
