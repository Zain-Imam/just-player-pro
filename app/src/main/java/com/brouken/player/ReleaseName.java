package com.brouken.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Getting a title out of whatever the file happened to be called.
 *
 * What arrives is rarely a title. It is a scene release, an anime group's
 * bracketed naming, or — when the player was started by another app — a URL
 * with the name buried in its last path segment, percent-encoded, behind a
 * query string full of tokens. Everything downstream, the search box included,
 * is only as good as what comes out of here.
 *
 * The approach is the usual one for this problem, and the one the mpv forks
 * take: find the season and episode markers first, because everything before
 * the earliest of them is the title, then scrub the known noise — resolutions,
 * codecs, sources, release groups, checksums — out of what is left.
 */
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
        public final String episodeTitle;
        @Nullable
        final String group;

        Info(String cleanTitle, @Nullable String year, @Nullable String resolution,
             @Nullable String source, @Nullable String codec, @Nullable String hdr,
             @Nullable String audio, @Nullable Integer season, @Nullable Integer episode,
             @Nullable String episodeTitle, @Nullable String group) {
            this.cleanTitle = cleanTitle;
            this.year = year;
            this.resolution = resolution;
            this.source = source;
            this.codec = codec;
            this.hdr = hdr;
            this.audio = audio;
            this.season = season;
            this.episode = episode;
            this.episodeTitle = episodeTitle;
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
                    null, null, null, group);
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
            Pattern.compile("\\bs(\\d{1,2})[\\s._-]?e(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEASON_ONLY =
            Pattern.compile("\\bs(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT_EPISODE = Pattern.compile("\\b(\\d{1,2})x(\\d{2,3})\\b");
    // Four digits standing alone. The middle of a date stamp — VID-20230515 —
    // is not a year, and a phone fills a gallery with those.
    private static final Pattern YEAR =
            Pattern.compile("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)");

    // "Season 3", "Episode 12", "EP05" — how a release names them when it is not
    // using the scene form.
    private static final Pattern SEASON_WORD =
            Pattern.compile("\\bseason\\s*(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODE_WORD =
            Pattern.compile("\\bepisode\\s*(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODE_MARKER =
            Pattern.compile("\\bep\\s*\\.?\\s*(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE);

    /*
     * The anime form: "Frieren - 08" or "Frieren - 08 - Title".
     *
     * Deliberately narrow. A dash followed by a number is also how half the
     * films with a subtitle in the name are written, so the number has to look
     * like an episode: not a year, not a resolution, and not four digits.
     */
    private static final Pattern DASH_EPISODE =
            Pattern.compile("\\s-\\s*(\\d{1,3})(?:\\s|$|\\s*-\\s*|\\s*\\()");

    private static final Pattern GROUP = Pattern.compile("-([A-Za-z0-9]{2,15})$");

    private static final Pattern SITE_PREFIX = Pattern.compile(
            "^\\s*(\\[[^]]{1,40}]|www\\.\\S{1,40}|[a-z0-9.-]{1,30}\\.(com|net|org|to|software|me|cc|tv))\\s*[-–—]?\\s*",
            Pattern.CASE_INSENSITIVE);

    /*
     * Anything in brackets, wherever it is.
     *
     * An anime release is "[SubsPlease] Frieren - 01 (1080p) [ABCD1234].mkv":
     * the group in front, the quality in the middle and a checksum at the end,
     * and none of the three is part of the title. Films use the same habit for
     * "(2008)", so a year is pulled out before this runs.
     */
    private static final Pattern BRACKETS =
            Pattern.compile("\\[[^\\[\\]]{0,60}]|\\{[^{}]{0,60}}|【[^【】]{0,60}】|（[^（）]{0,60}）");

    // A CRC32 left in the name by the muxer, which is eight hex digits and looks
    // like a word to everything downstream.
    // At least one of the letters in it, so that a date stamp — which is also
    // eight characters that happen to be valid hex — keeps its digits.
    private static final Pattern CHECKSUM =
            Pattern.compile("\\b(?=[0-9a-fA-F]{8}\\b)[0-9]*[a-fA-F][0-9a-fA-F]*\\b");

    // Things that are plainly not part of a name.
    private static final Pattern NOISE = Pattern.compile(
            "\\b(10\\s?bit|8\\s?bit|hdr10\\+?|hdr|dolby\\s?vision|dovi|sdr|hlg|bt2020|bt709"
                    + "|multi|dual[\\s.-]?audio|dubbed|subbed|engsub|vostfr|hardsub|softsub"
                    + "|proper|repack|extended|unrated|uncut|remastered|imax|theatrical|limited"
                    + "|internal|complete|batch|uncensored|censored|retail|readnfo"
                    + "|amzn|nf|dsnp|atvp|hmax|pcok|stan|hulu|hbo|pmtp|itunes|ma"
                    + "|ddp?\\d\\.\\d|dd\\+?\\d\\.\\d|aac\\d\\.\\d|\\d\\.\\d\\s?ch"
                    + "|\\d{3,4}[pi]|\\d+\\s?kbps|\\d+(\\.\\d+)?\\s?[mg]b"
                    + "|v\\d)\\b",
            Pattern.CASE_INSENSITIVE);

    /*
     * Groups that sign their releases with a bare word rather than after a dash.
     *
     * The dash form is caught by GROUP; these are the ones that are not, and
     * that turn up often enough in a personal collection to be worth naming.
     */
    private static final Pattern KNOWN_GROUP = Pattern.compile(
            "\\b(yts(\\.[a-z]{2,3})?|yify|rarbg|galaxyrg|qxr|tigole|joy|shaanig|pahe|psa"
                    + "|evo|fgt|ntb|ntg|cakes|flux|mzabi|edith|ghosts|sparks|amiable|geckos"
                    + "|ion10|ember|successfulcrab|nogrp|mkvcage|trollhd|bonsaihd|rusted"
                    + "|horriblesubs|subsplease|erai-?raws|judas|asw|anime\\s?time|ohys-?raws)\\b",
            Pattern.CASE_INSENSITIVE);

    /*
     * The tags that only exist once the dots have become spaces.
     *
     * "DD5.1" and "H.264" are written with the same dot that separates every
     * other word in a scene release, so by the time the name is readable they
     * read as "DD5 1" and "H 264" and every pattern written against the dotted
     * form misses them. These are matched after the normalisation instead.
     */
    private static final Pattern CHANNELS = Pattern.compile(
            "\\b(?:ddp?\\+?|dd\\+|aac|eac3|ac3|dts(?:\\s?hd)?(?:\\s?ma)?|truehd|atmos)?"
                    + "\\s?[1257]\\s[01]\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPACED_CODEC =
            Pattern.compile("\\bh\\s?26[45]\\b", Pattern.CASE_INSENSITIVE);
    // What is left after a group name has been taken off the end.
    private static final Pattern TRAILING_GROUP =
            Pattern.compile("[-–—]\\s*[A-Za-z0-9]{2,15}\\s*$");

    private static final Pattern SEPARATORS = Pattern.compile("[._]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern EXTENSION = Pattern.compile(
            "\\.(mkv|mp4|avi|mov|m4v|ts|m2ts|webm|flv|wmv|mpg|mpeg|ogv|3gp|divx|m3u8)$",
            Pattern.CASE_INSENSITIVE);

    @NonNull
    public static Info parse(@Nullable final String rawName) {
        final String raw = fromLink(rawName);
        if (raw.isEmpty()) {
            return new Info("", null, null, null, null, null, null, null, null, null, null);
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
        int markerEnd = -1;

        final Matcher se = SEASON_EPISODE.matcher(normalised);
        if (se.find()) {
            season = toInt(se.group(1));
            episode = toInt(se.group(2));
            markerEnd = se.end();
        }
        if (season == null) {
            final Matcher alt = ALT_EPISODE.matcher(normalised);
            if (alt.find()) {
                season = toInt(alt.group(1));
                episode = toInt(alt.group(2));
                markerEnd = alt.end();
            }
        }
        if (episode == null) {
            final Matcher word = EPISODE_WORD.matcher(normalised);
            if (word.find()) {
                episode = toInt(word.group(1));
                markerEnd = word.end();
            }
        }
        if (episode == null) {
            final Matcher marker = EPISODE_MARKER.matcher(normalised);
            if (marker.find()) {
                episode = toInt(marker.group(1));
                markerEnd = marker.end();
            }
        }
        if (season == null) {
            final Matcher word = SEASON_WORD.matcher(normalised);
            if (word.find()) {
                season = toInt(word.group(1));
                if (markerEnd < 0) {
                    markerEnd = word.end();
                }
            }
        }
        if (season == null && episode == null) {
            final Matcher only = SEASON_ONLY.matcher(normalised);
            if (only.find()) {
                season = toInt(only.group(1));
            }
        }

        String year = null;
        final Matcher yearMatcher = YEAR.matcher(normalised);
        while (yearMatcher.find()) {
            // A four-digit episode number is not a year, and neither is a year
            // that turned out to be part of the season marker.
            if (markerEnd < 0 || yearMatcher.start() >= markerEnd
                    || yearMatcher.end() <= markerEnd - 6) {
                year = yearMatcher.group(1);
                break;
            }
        }

        // The anime dash form, tried last: it is the loosest of the patterns and
        // should never win over one that actually said "S01E02".
        if (episode == null && year == null) {
            final Matcher dash = DASH_EPISODE.matcher(normalised);
            if (dash.find()) {
                final Integer candidate = toInt(dash.group(1));
                if (candidate != null && candidate > 0 && candidate < 999) {
                    episode = candidate;
                    if (season == null) {
                        season = 1;
                    }
                    markerEnd = dash.end(1);
                }
            }
        }

        String group = null;
        final Matcher groupMatcher = GROUP.matcher(withoutExtension);
        if (groupMatcher.find()) {
            group = groupMatcher.group(1);
        }

        final String title = cleanTitle(normalised, year, season, episode);
        return new Info(title, year, resolution, source, codec, hdr, audio, season, episode,
                episodeTitle(normalised, markerEnd, title), group);
    }

    /*
     * A link, reduced to the name inside it.
     *
     * Players are handed URLs more often than filenames these days, and the
     * name in one is percent-encoded, behind a query string, and sometimes
     * encoded twice by whatever proxied it. Anything that is already a plain
     * name comes through this untouched.
     */
    @NonNull
    static String fromLink(@Nullable final String rawName) {
        String value = rawName == null ? "" : rawName.trim();
        if (value.isEmpty()) {
            return "";
        }

        final String lower = value.toLowerCase(Locale.US);

        /*
         * A content URI carries no name at all.
         *
         * Its last segment is a row number and the one above it is the word
         * "media", which would be handed to the search box as the title of the
         * film. The display name is asked for elsewhere; nothing is better than
         * a wrong answer here.
         */
        if (lower.startsWith("content://")) {
            return "";
        }

        final boolean isLink = lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("file://")
                || value.indexOf('/') >= 0;

        if (isLink) {
            final int query = value.indexOf('?');
            if (query > 0) {
                value = value.substring(0, query);
            }
            final int fragment = value.indexOf('#');
            if (fragment > 0) {
                value = value.substring(0, fragment);
            }
            // The last segment that has anything in it: a URL often ends in a
            // slash, or in an id with the name one level up.
            final String[] segments = value.split("/");
            String best = "";
            for (int i = segments.length - 1; i >= 0; i--) {
                final String candidate = decode(segments[i]).trim();
                if (candidate.isEmpty()) {
                    continue;
                }
                best = candidate;
                // An id, a hash, or a bare number is not a name; keep looking up
                // the path for something that is.
                if (looksNamed(candidate)) {
                    break;
                }
            }
            value = best;
        }

        return decode(value).trim();
    }

    private static boolean looksNamed(final String candidate) {
        int letters = 0;
        for (int i = 0; i < candidate.length(); i++) {
            if (Character.isLetter(candidate.charAt(i))) {
                letters++;
            }
        }
        if (letters < 3) {
            return false;
        }
        final String bare = candidate.replaceAll("[^A-Za-z0-9]", "");
        // A hash reads as letters too, and there is no name in one.
        return !(bare.length() >= 16 && bare.matches("(?i)[0-9a-f]+"));
    }

    private static String decode(final String value) {
        String decoded = value;
        // Twice at most: a name that went through two services is encoded twice,
        // and a third pass starts eating real percent signs.
        for (int i = 0; i < 2; i++) {
            if (decoded.indexOf('%') < 0 && decoded.indexOf('+') < 0) {
                break;
            }
            try {
                final String next = java.net.URLDecoder.decode(decoded, "UTF-8");
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            } catch (Exception e) {
                break;
            }
        }
        return decoded;
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

    /*
     * What sits between the episode marker and the first piece of noise.
     *
     * "Breaking Bad S05E16 Felina 720p" has "Felina" in it, which is worth
     * having on the card. Most releases have nothing there at all, so anything
     * that scrubs down to a couple of characters is dropped rather than shown.
     */
    @Nullable
    private static String episodeTitle(final String normalised, final int markerEnd,
                                       final String title) {
        if (markerEnd < 0 || markerEnd >= normalised.length()) {
            return null;
        }
        final String after = scrub(normalised.substring(markerEnd));
        if (after.length() < 3 || after.equalsIgnoreCase(title)) {
            return null;
        }
        int letters = 0;
        for (int i = 0; i < after.length(); i++) {
            if (Character.isLetter(after.charAt(i))) {
                letters++;
            }
        }
        return letters < 3 ? null : after;
    }

    @NonNull
    static String cleanTitle(final String name, @Nullable final String year,
                             @Nullable final Integer season, @Nullable final Integer episode) {
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
        if (season != null || episode != null) {
            addStart(cuts, SEASON_EPISODE.matcher(work));
            // The "3x07" form counts too, or a title keeps the marker it was
            // matched on.
            addStart(cuts, ALT_EPISODE.matcher(work));
            addStart(cuts, SEASON_WORD.matcher(work));
            addStart(cuts, EPISODE_WORD.matcher(work));
            addStart(cuts, EPISODE_MARKER.matcher(work));
            addStart(cuts, SEASON_ONLY.matcher(work));
            addStart(cuts, DASH_EPISODE.matcher(work));
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
        title = scrub(title);
        if (title.isEmpty()) {
            title = scrub(work);
        }
        if (title.isEmpty()) {
            title = work.isEmpty() ? name : work;
        }
        return title;
    }

    /*
     * Everything that is definitely not part of a name, taken out.
     *
     * Order matters here: the brackets go first because a checksum and a group
     * are usually inside one, and the trailing dash is cleaned last because
     * removing a group is what leaves it behind.
     */
    private static String scrub(final String raw) {
        String text = BRACKETS.matcher(raw).replaceAll(" ");
        text = NOISE.matcher(text).replaceAll(" ");
        text = SEPARATORS.matcher(text).replaceAll(" ");
        text = CHECKSUM.matcher(text).replaceAll(" ");
        text = NOISE.matcher(text).replaceAll(" ");
        text = CHANNELS.matcher(text).replaceAll(" ");
        text = SPACED_CODEC.matcher(text).replaceAll(" ");
        text = KNOWN_GROUP.matcher(text).replaceAll(" ");
        for (final Rule rule : RESOLUTION) {
            text = rule.pattern.matcher(text).replaceAll(" ");
        }
        for (final Rule rule : SOURCE) {
            text = rule.pattern.matcher(text).replaceAll(" ");
        }
        for (final Rule rule : CODEC) {
            text = rule.pattern.matcher(text).replaceAll(" ");
        }
        for (final Rule rule : AUDIO) {
            text = rule.pattern.matcher(text).replaceAll(" ");
        }
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = TRAILING_GROUP.matcher(text).replaceAll(" ");
        return trimEdges(text).trim();
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
                || c == '(' || c == ')' || c == '[' || c == ']' || c == '.' || c == ',';
    }
}
