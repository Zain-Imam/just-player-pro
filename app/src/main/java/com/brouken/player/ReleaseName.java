package com.brouken.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReleaseName {

    private ReleaseName() {
    }

    public static final class Info {
        public final String cleanTitle;
        @Nullable
        public final String year;
        @Nullable
        final String resolution;
        @Nullable
        final String source;
        @Nullable
        final String codec;
        @Nullable
        final String hdr;
        @Nullable
        final String audio;
        @Nullable
        public final Integer season;
        @Nullable
        public final Integer episode;
        @Nullable
        final String group;

        Info(String cleanTitle, @Nullable String year, @Nullable String resolution,
             @Nullable String source, @Nullable String codec, @Nullable String hdr,
             @Nullable String audio, @Nullable Integer season, @Nullable Integer episode,
             @Nullable String group) {
            this.cleanTitle = cleanTitle;
            this.year = year;
            this.resolution = resolution;
            this.source = source;
            this.codec = codec;
            this.hdr = hdr;
            this.audio = audio;
            this.season = season;
            this.episode = episode;
            this.group = group;
        }

        public boolean isSeries() {
            return season != null;
        }

        @Nullable
        String episodeLabel() {
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

        public Info withoutEpisode() {
            return new Info(cleanTitle, year, resolution, source, codec, hdr, audio,
                    null, null, group);
        }


        public boolean looksLikeTitle() {
            final String title = cleanTitle == null ? "" : cleanTitle.trim();
            if (title.length() < 2) {
                return false;
            }
            int letters = 0;
            for (int i = 0; i < title.length(); i++) {
                if (Character.isLetter(title.charAt(i))) {
                    letters++;
                }
            }
            if (letters < 2) {
                return false;
            }
            if (!title.matches("(?s).*[A-Za-z]{2,}.*")) {
                return false;
            }
            // A hash or a UUID is all hex and separators, and reads as a word.
            final String bare = title.replaceAll("[^A-Za-z0-9]", "");
            if (bare.length() >= 8 && bare.matches("(?i)[0-9a-f]+")) {
                return false;
            }
            // "1080", "2160p", "Part 2" with nothing else.
            return !title.matches("(?i)[0-9\\s._-]*(p|part\\s*\\d+)?");
        }

        public String searchQuery() {
            if (!isSeries() && year != null) {
                return cleanTitle + " " + year;
            }
            return cleanTitle;
        }
    }

    private static final class Rule {
        final Pattern pattern;
        final String label;

        Rule(String regex, String label) {
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.label = label;
        }
    }

    // Ordered longest-first where prefixes overlap, so "2160p" is not caught by
    // a looser rule first.
    private static final Rule[] RESOLUTION = {
            new Rule("\\b(2160p|4k|uhd)\\b", "4K"),
            new Rule("\\b1440p\\b", "1440p"),
            new Rule("\\b1080p\\b", "1080p"),
            new Rule("\\b720p\\b", "720p"),
            new Rule("\\b(576p|480p)\\b", "SD"),
    };

    private static final Rule[] SOURCE = {
            new Rule("\\b(blu-?ray|bdrip|brrip|bdremux|remux)\\b", "BluRay"),
            new Rule("\\b(web-?dl|webdl)\\b", "WEB-DL"),
            new Rule("\\b(web-?rip|webrip)\\b", "WEBRip"),
            new Rule("\\b(hdtv)\\b", "HDTV"),
            new Rule("\\b(dvdrip|dvd)\\b", "DVD"),
            new Rule("\\b(cam|camrip|hdcam|ts|telesync|hdts)\\b", "CAM"),
    };

    private static final Rule[] CODEC = {
            new Rule("\\b(x265|h\\.?265|hevc)\\b", "x265"),
            new Rule("\\b(x264|h\\.?264|avc)\\b", "x264"),
            new Rule("\\b(av1)\\b", "AV1"),
            new Rule("\\b(xvid|divx)\\b", "XviD"),
    };

    private static final Rule[] HDR = {
            new Rule("\\b(dolby.?vision|dovi|\\bdv\\b)\\b", "DV"),
            new Rule("\\b(hdr10\\+|hdr10plus)\\b", "HDR10+"),
            new Rule("\\b(hdr10|hdr)\\b", "HDR"),
    };

    private static final Rule[] AUDIO = {
            new Rule("\\b(atmos)\\b", "Atmos"),
            new Rule("\\b(truehd)\\b", "TrueHD"),
            new Rule("\\b(dts-?hd|dtshd)\\b", "DTS-HD"),
            new Rule("\\b(dts)\\b", "DTS"),
            new Rule("\\b(eac3|ddp|dd\\+)\\b", "DD+"),
            new Rule("\\b(aac)\\b", "AAC"),
    };

    private static final Pattern SEASON_EPISODE =
            Pattern.compile("\\bs(\\d{1,2})[\\s._-]?e(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEASON_ONLY =
            Pattern.compile("\\bs(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT_EPISODE = Pattern.compile("\\b(\\d{1,2})x(\\d{2,3})\\b");
    private static final Pattern YEAR = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    private static final Pattern GROUP = Pattern.compile("-([A-Za-z0-9]{2,15})$");

    private static final Pattern SITE_PREFIX = Pattern.compile(
            "^\\s*(\\[[^]]{1,40}]|www\\.\\S{1,40}|[a-z0-9.-]{1,30}\\.(com|net|org|to|software|me|cc|tv))\\s*[-–—]?\\s*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SEPARATORS = Pattern.compile("[._]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern EXTENSION = Pattern.compile(
            "\\.(mkv|mp4|avi|mov|m4v|ts|m2ts|webm|flv|wmv|mpg|mpeg|ogv|3gp|divx)$",
            Pattern.CASE_INSENSITIVE);

    @NonNull
    public static Info parse(@Nullable final String rawName) {
        final String raw = rawName == null ? "" : rawName.trim();
        if (raw.isEmpty()) {
            return new Info("", null, null, null, null, null, null, null, null, null);
        }

        final String withoutExtension = EXTENSION.matcher(raw).replaceAll("");

        final String withoutSite = SITE_PREFIX.matcher(withoutExtension).replaceAll("");

        // Separators are normalised for MATCHING; the original is kept for display.
        String normalised = SEPARATORS.matcher(withoutSite).replaceAll(" ");
        normalised = WHITESPACE.matcher(normalised).replaceAll(" ").trim();

        final String resolution = firstMatch(RESOLUTION, normalised);
        final String source = firstMatch(SOURCE, normalised);
        final String codec = firstMatch(CODEC, normalised);
        final String hdr = firstMatch(HDR, normalised);
        final String audio = firstMatch(AUDIO, normalised);

        Integer season = null;
        Integer episode = null;

        final Matcher se = SEASON_EPISODE.matcher(normalised);
        if (se.find()) {
            season = toInt(se.group(1));
            episode = toInt(se.group(2));
        }
        if (season == null) {
            final Matcher alt = ALT_EPISODE.matcher(normalised);
            if (alt.find()) {
                season = toInt(alt.group(1));
                episode = toInt(alt.group(2));
            }
        }
        if (season == null) {
            final Matcher only = SEASON_ONLY.matcher(normalised);
            if (only.find()) {
                season = toInt(only.group(1));
            }
        }

        String year = null;
        final Matcher yearMatcher = YEAR.matcher(normalised);
        if (yearMatcher.find()) {
            year = yearMatcher.group(1);
        }

        String group = null;
        final Matcher groupMatcher = GROUP.matcher(withoutExtension);
        if (groupMatcher.find()) {
            group = groupMatcher.group(1);
        }

        return new Info(cleanTitle(normalised, year, season), year, resolution, source, codec,
                hdr, audio, season, episode, group);
    }

    @Nullable
    private static String firstMatch(final Rule[] rules, final String text) {
        for (final Rule rule : rules) {
            if (rule.pattern.matcher(text).find()) {
                return rule.label;
            }
        }
        return null;
    }

    @Nullable
    private static Integer toInt(@Nullable final String text) {
        try {
            return text == null ? null : Integer.valueOf(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @NonNull
    static String cleanTitle(final String name, @Nullable final String year,
                             @Nullable final Integer season) {
        String work = SEPARATORS.matcher(name).replaceAll(" ");
        work = WHITESPACE.matcher(work).replaceAll(" ").trim();
        work = SITE_PREFIX.matcher(work).replaceAll("");

        final List<Integer> cuts = new ArrayList<>();
        if (year != null) {
            final int at = work.indexOf(year);
            if (at > 0) {
                cuts.add(at);
            }
        }
        if (season != null) {
            addStart(cuts, SEASON_EPISODE.matcher(work));
            // The "3x07" form counts too, or a title keeps the marker it was
            // matched on.
            addStart(cuts, ALT_EPISODE.matcher(work));
            addStart(cuts, SEASON_ONLY.matcher(work));
        }
        for (final Rule rule : RESOLUTION) {
            addStart(cuts, rule.pattern.matcher(work));
        }
        for (final Rule rule : SOURCE) {
            addStart(cuts, rule.pattern.matcher(work));
        }

        int cut = -1;
        for (final int candidate : cuts) {
            if (cut == -1 || candidate < cut) {
                cut = candidate;
            }
        }

        String title = cut > 0 ? work.substring(0, cut) : work;
        title = trimEdges(title);
        if (title.isEmpty()) {
            title = work.isEmpty() ? name : work;
        }
        return title;
    }

    private static void addStart(final List<Integer> cuts, final Matcher matcher) {
        if (matcher.find() && matcher.start() > 0) {
            cuts.add(matcher.start());
        }
    }

    private static String trimEdges(final String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isEdge(text.charAt(start))) {
            start++;
        }
        while (end > start && isEdge(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isEdge(final char c) {
        return Character.isWhitespace(c) || c == '-' || c == '–' || c == '—'
                || c == '(' || c == '[' || c == '.' || c == ',';
    }
}
