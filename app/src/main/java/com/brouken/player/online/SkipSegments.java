package com.brouken.player.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SkipSegments {

    private static final double SAME_SEGMENT_OVERLAP = 0.5;

    private static final double CLOSE_ENOUGH = 30;

    private static final String[] SOURCE_ORDER =
            {"chapters", "introdb", "theintrodb", "skipdb", "skipme", "introhater", "aniskip"};

    private SkipSegments() {
    }

    public enum Kind {
        INTRO("intro"),
        RECAP("recap"),
        CREDITS("outro"),
        PREVIEW("preview");

        final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    public static final class Segment {
        public final Kind kind;
        public final double start;
        public final double end;
        public final int sources;

        Segment(Kind kind, double start, double end, int sources) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.sources = sources;
        }

        public boolean contains(final double seconds) {
            return seconds >= start && seconds < end;
        }
    }

    private static final class Raw {
        final Kind kind;
        final double start;
        final double end;
        final String source;
        final boolean preciseStart;
        final boolean preciseEnd;

        Raw(Kind kind, double start, double end, String source,
            boolean preciseStart, boolean preciseEnd) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.source = source;
            this.preciseStart = preciseStart;
            this.preciseEnd = preciseEnd;
        }
    }

    @NonNull
    public static List<Segment> lookup(@Nullable final String imdbId,
                                       @Nullable final Integer season,
                                       @Nullable final Integer episode,
                                       final double durationSeconds) {
        final List<Raw> raw = new ArrayList<>();
        if (imdbId == null || imdbId.isEmpty()) {
            return new ArrayList<>();
        }

        final Map<String, String> params = Http.params();
        params.put("imdb_id", Subtitles.withTt(imdbId));
        if (season != null) params.put("season", String.valueOf(season));
        if (episode != null) params.put("episode", String.valueOf(episode));
        final String qs = Http.query(params);

        introDb(raw, qs);
        theIntroDb(raw, qs, durationSeconds);
        skipDb(raw, qs);
        aniSkip(raw, Subtitles.withTt(imdbId), season, episode);
        skipMe(raw, imdbId, season, episode);
        introHater(raw, imdbId, season, episode);

        return merge(raw);
    }

    public static final class ChapterMark {
        public final String title;
        public final double start;

        public ChapterMark(final String title, final double start) {
            this.title = title == null ? "" : title;
            this.start = start;
        }
    }

    @NonNull
    public static List<Segment> fromChapters(@Nullable final List<ChapterMark> chapters,
                                             final double duration) {
        final List<Raw> raw = new ArrayList<>();
        if (chapters == null || chapters.size() < 2) {
            return new ArrayList<>();
        }

        for (int i = 0; i < chapters.size(); i++) {
            final ChapterMark chapter = chapters.get(i);
            final Kind kind = kindOfChapter(chapter.title);
            if (kind == null) {
                continue;
            }
            // A chapter runs until the next one starts, or to the end of the file.
            final double end = i + 1 < chapters.size()
                    ? chapters.get(i + 1).start
                    : duration;
            add(raw, kind, chapter.start, end, "chapters", true, true);
        }
        return merge(raw);
    }

    @Nullable
    private static Kind kindOfChapter(final String title) {
        final String t = title.trim().toLowerCase(Locale.ROOT);
        if (t.matches("op|opening|intro|avant|titles?|opening credits|main titles?")) {
            return Kind.INTRO;
        }
        if (t.matches("recap|prev|previously|previously on.*")) {
            return Kind.RECAP;
        }
        if (t.matches("ed|ending|outro|credits|end credits|closing credits|endcard")) {
            return Kind.CREDITS;
        }
        if (t.matches("preview|next episode|next time|trailer")) {
            return Kind.PREVIEW;
        }
        return null;
    }

    /*
     * Two more databases, asked the same way as the rest.
     *
     * Every source is merged rather than trusted in turn, so another one is
     * another vote: where they agree the bounds get sharper, and a source that
     * is alone in claiming something loses to the ones that agree.
     */
    private static void skipMe(final List<Raw> out, final String imdbId,
                               final Integer season, final Integer episode) {
        if (imdbId == null || imdbId.isEmpty()) {
            return;
        }
        final StringBuilder url = new StringBuilder("https://db.skipme.workers.dev/v1/movies/")
                .append(Subtitles.withTt(imdbId));
        if (season != null && episode != null) {
            url.append("/").append(season).append("/").append(episode);
        }
        final java.util.Map<String, String> headers = Http.params();
        headers.put("User-Agent", "SkipMe.db/0.0");

        final JSONObject body = Http.get(url.toString(), headers).json();
        if (body == null) {
            return;
        }
        final JSONArray segments = body.optJSONArray("segments");
        for (int i = 0; segments != null && i < segments.length(); i++) {
            final JSONObject seg = segments.optJSONObject(i);
            if (seg == null) {
                continue;
            }
            final Kind kind = kindOfChapter(seg.optString("category", ""));
            if (kind == null) {
                continue;
            }
            add(out, kind, seg.optDouble("start", -1), seg.optDouble("end", -1),
                    "skipme", true, true);
        }
    }

    private static void introHater(final List<Raw> out, final String imdbId,
                                   final Integer season, final Integer episode) {
        if (imdbId == null || imdbId.isEmpty()) {
            return;
        }
        final StringBuilder url = new StringBuilder("https://introhater.com/api/v1/segments/")
                .append(Subtitles.withTt(imdbId));
        if (season != null && episode != null) {
            url.append("/").append(season).append("/").append(episode);
        }
        final java.util.Map<String, String> params = Http.params();
        params.put("key", "introhater_mpv_client");

        final JSONObject body = Http.get(url + Http.query(params), null).json();
        if (body == null) {
            return;
        }
        final JSONArray segments = body.optJSONArray("segments");
        for (int i = 0; segments != null && i < segments.length(); i++) {
            final JSONObject seg = segments.optJSONObject(i);
            if (seg == null) {
                continue;
            }
            final Kind kind = kindOfChapter(seg.optString("type", ""));
            if (kind == null) {
                continue;
            }
            add(out, kind, seg.optDouble("start", -1), seg.optDouble("end", -1),
                    "introhater", true, true);
        }
    }

    private static void introDb(final List<Raw> out, final String qs) {
        final JSONObject body = Http.get("https://api.introdb.app/segments" + qs, null).json();
        if (body == null) {
            return;
        }
        for (final Kind kind : Kind.values()) {
            final JSONObject seg = body.optJSONObject(kind.key);
            if (seg == null) {
                continue;
            }
            add(out, kind, seg.optDouble("start_sec", -1), seg.optDouble("end_sec", -1),
                    "introdb", true, true);
        }
    }

    private static void theIntroDb(final List<Raw> out, final String qs, final double duration) {
        final JSONObject body = Http.get("https://api.theintrodb.org/v2/media" + qs, null).json();
        if (body == null) {
            return;
        }

        final String[][] pairs = {{"intro", "intro"}, {"credits", "outro"}};
        for (final String[] pair : pairs) {
            final JSONArray array = body.optJSONArray(pair[0]);
            if (array == null) {
                continue;
            }
            final Kind kind = "intro".equals(pair[1]) ? Kind.INTRO : Kind.CREDITS;

            for (int i = 0; i < array.length(); i++) {
                final JSONObject row = array.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                final boolean vagueStart = row.isNull("start_ms");
                final boolean vagueEnd = row.isNull("end_ms");
                final double start = vagueStart ? 0 : row.optDouble("start_ms", -1) / 1000.0;
                final double end = vagueEnd ? duration : row.optDouble("end_ms", -1) / 1000.0;
                add(out, kind, start, end, "theintrodb", !vagueStart, !vagueEnd);
            }
        }
    }

    private static void skipDb(final List<Raw> out, final String qs) {
        final JSONObject body = Http.get("https://api.skipdb.tv/api/segments" + qs, null).json();
        if (body == null) {
            return;
        }
        final JSONObject segments = body.optJSONObject("segments");
        if (segments == null) {
            return;
        }
        for (final Kind kind : Kind.values()) {
            final JSONObject seg = segments.optJSONObject(kind.key);
            if (seg == null) {
                continue;
            }
            // Its own confidence, where it gives one. Below half is a guess.
            if (seg.has("confidence") && seg.optDouble("confidence", 1) < 0.5) {
                continue;
            }
            add(out, kind, seg.optDouble("start_ms", -1) / 1000.0,
                    seg.optDouble("end_ms", -1) / 1000.0, "skipdb", true, true);
        }
    }

    private static void aniSkip(final List<Raw> out, final String imdbId,
                                @Nullable final Integer season,
                                @Nullable final Integer episode) {
        final String malId = malIdFor(imdbId, season);
        if (malId == null) {
            return;
        }

        final String url = "https://api.aniskip.com/v2/skip-times/" + malId + "/"
                + (episode == null ? 1 : episode)
                + "?types[]=op&types[]=ed&types[]=recap&episodeLength=0";
        final JSONObject body = Http.get(url, null).json();
        if (body == null || !body.optBoolean("found", false)) {
            return;
        }
        final JSONArray results = body.optJSONArray("results");
        if (results == null) {
            return;
        }

        for (int i = 0; i < results.length(); i++) {
            final JSONObject row = results.optJSONObject(i);
            if (row == null) {
                continue;
            }
            final Kind kind = aniSkipKind(row.optString("skipType", ""));
            final JSONObject interval = row.optJSONObject("interval");
            if (kind == null || interval == null) {
                continue;
            }
            add(out, kind, interval.optDouble("startTime", -1),
                    interval.optDouble("endTime", -1), "aniskip", true, true);
        }
    }

    @Nullable
    private static Kind aniSkipKind(final String type) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "op":
            case "mixed-op":
                return Kind.INTRO;
            case "ed":
            case "mixed-ed":
                return Kind.CREDITS;
            case "recap":
                return Kind.RECAP;
            default:
                return null;
        }
    }

    @Nullable
    private static String malIdFor(final String imdbId, @Nullable final Integer season) {
        final Map<String, String> params = Http.params();
        params.put("id", imdbId);
        params.put("include", "myanimelist");
        final Http.Result result = Http.get(
                "https://arm.haglund.dev/api/v2/imdb" + Http.query(params), null);

        final JSONArray array = result.jsonArray();
        if (array == null || array.length() == 0) {
            return null; // not anime
        }
        final int wanted = Math.max(0, (season == null ? 1 : season) - 1);
        final JSONObject entry = array.optJSONObject(wanted < array.length() ? wanted : 0);
        if (entry == null || entry.isNull("myanimelist")) {
            return null;
        }
        final String mal = entry.optString("myanimelist", "");
        return mal.isEmpty() ? null : mal;
    }

    private static void add(final List<Raw> out, final Kind kind, final double start,
                            final double end, final String source,
                            final boolean preciseStart, final boolean preciseEnd) {
        if (Double.isNaN(start) || Double.isNaN(end)) {
            return;
        }
        if (start < 0 || end <= start) {
            return;
        }
        if (end - start < 3 || end - start > 20 * 60) {
            return;
        }
        out.add(new Raw(kind, start, end, source, preciseStart, preciseEnd));
    }

    private static List<Segment> merge(final List<Raw> raw) {
        final List<Segment> merged = new ArrayList<>();

        for (final Kind kind : Kind.values()) {
            final List<Raw> ofKind = new ArrayList<>();
            for (final Raw r : raw) {
                if (r.kind == kind) {
                    ofKind.add(r);
                }
            }
            if (ofKind.isEmpty()) {
                continue;
            }

            final List<List<Raw>> groups = cluster(ofKind);
            Collections.sort(groups, new Comparator<List<Raw>>() {
                @Override
                public int compare(List<Raw> a, List<Raw> b) {
                    final int byCount = distinctSources(b).size() - distinctSources(a).size();
                    if (byCount != 0) {
                        return byCount;
                    }
                    return bestRank(a) - bestRank(b);
                }
            });

            final Segment winner = collapse(kind, groups.get(0));
            if (winner != null) {
                merged.add(winner);
            }
        }

        Collections.sort(merged, new Comparator<Segment>() {
            @Override
            public int compare(Segment a, Segment b) {
                return Double.compare(a.start, b.start);
            }
        });
        return merged;
    }

    private static List<List<Raw>> cluster(final List<Raw> ofKind) {
        final List<List<Raw>> groups = new ArrayList<>();

        for (final Raw candidate : ofKind) {
            boolean placed = false;
            for (final List<Raw> group : groups) {
                for (final Raw member : group) {
                    if (overlapRatio(member, candidate) > SAME_SEGMENT_OVERLAP) {
                        group.add(candidate);
                        placed = true;
                        break;
                    }
                }
                if (placed) {
                    break;
                }
            }
            if (!placed) {
                final List<Raw> group = new ArrayList<>();
                group.add(candidate);
                groups.add(group);
            }
        }
        return groups;
    }

    private static double overlapRatio(final Raw a, final Raw b) {
        final double shared = Math.min(a.end, b.end) - Math.max(a.start, b.start);
        if (shared <= 0) {
            return 0;
        }
        final double shortest = Math.min(a.end - a.start, b.end - b.start);
        return shortest > 0 ? shared / shortest : 0;
    }

    private static Set<String> distinctSources(final List<Raw> group) {
        final Set<String> sources = new LinkedHashSet<>();
        for (final Raw r : group) {
            sources.add(r.source);
        }
        return sources;
    }

    private static int bestRank(final List<Raw> group) {
        int best = 99;
        for (final Raw r : group) {
            for (int i = 0; i < SOURCE_ORDER.length; i++) {
                if (SOURCE_ORDER[i].equals(r.source)) {
                    best = Math.min(best, i);
                }
            }
        }
        return best;
    }

    @Nullable
    private static Segment collapse(final Kind kind, final List<Raw> group) {
        final List<Double> starts = new ArrayList<>();
        final List<Double> ends = new ArrayList<>();

        for (final Raw r : group) {
            if (r.preciseStart) starts.add(r.start);
            if (r.preciseEnd) ends.add(r.end);
        }
        if (starts.isEmpty()) {
            for (final Raw r : group) starts.add(r.start);
        }
        if (ends.isEmpty()) {
            for (final Raw r : group) ends.add(r.end);
        }

        final double start = min(starts);
        final double endMedian = median(ends);
        final double endMax = max(ends);
        final double end = endMax - endMedian <= CLOSE_ENOUGH ? endMax : endMedian;

        if (end - start < 3) {
            return null;
        }
        return new Segment(kind, start, end, distinctSources(group).size());
    }

    private static double median(final List<Double> values) {
        final List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        final int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private static double min(final List<Double> values) {
        double lowest = values.get(0);
        for (final double value : values) {
            lowest = Math.min(lowest, value);
        }
        return lowest;
    }

    private static double max(final List<Double> values) {
        double highest = values.get(0);
        for (final double value : values) {
            highest = Math.max(highest, value);
        }
        return highest;
    }
}
