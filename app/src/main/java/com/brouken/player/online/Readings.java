package com.brouken.player.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// links are split by hand, not with Android's Uri, so this runs on a plain JVM
final class Readings {

    private static final Pattern ESCAPED = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final Pattern IMDB = Pattern.compile("^tt\\d{6,9}$");
    private static final Pattern LINK = Pattern.compile(
            "^([a-z][a-z0-9+.-]*)://([^/?#]*)([^?#]*)(\\?[^#]*)?", Pattern.CASE_INSENSITIVE);

    private Readings() {
    }

    static final class Link {
        String scheme = "";
        String host = "";
        String path = "";
        final Map<String, String> query = new LinkedHashMap<>();
        final List<String> segments = new ArrayList<>();
    }

    @NonNull
    static Link split(@Nullable final String link) {
        final Link out = new Link();
        final String s = link == null ? "" : link;
        final Matcher m = LINK.matcher(s);
        if (m.find()) {
            out.scheme = m.group(1).toLowerCase(Locale.ROOT);
            final String auth = m.group(2).replaceFirst("^[^@]*@", "");
            final Matcher hp = Pattern.compile("^(\\[[^\\]]*\\]|[^:]*)(?::(\\d+))?$").matcher(auth);
            out.host = (hp.find() ? hp.group(1) : auth).toLowerCase(Locale.ROOT);
            out.path = m.group(3) == null ? "" : m.group(3);
            final String query = m.group(4) == null ? "" : m.group(4).substring(1);
            for (final String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                final int eq = pair.indexOf('=');
                final String key = decode(eq < 0 ? pair : pair.substring(0, eq), true).toLowerCase(Locale.ROOT);
                if (!out.query.containsKey(key)) {
                    out.query.put(key, eq < 0 ? "" : decode(pair.substring(eq + 1), true));
                }
            }
        } else if (s.startsWith("/")) {
            out.scheme = "file";
            out.path = s;
        }
        for (final String part : out.path.split("/")) {
            if (!part.isEmpty()) {
                out.segments.add(decode(part, false));
            }
        }
        return out;
    }

    // some links are encoded twice
    static String decode(@Nullable final String value, final boolean plusIsSpace) {
        String out = value == null ? "" : value;
        if (plusIsSpace) {
            out = out.replace('+', ' ');
        }
        for (int i = 0; i < 2 && ESCAPED.matcher(out).find(); i++) {
            try {
                out = URLDecoder.decode(out.replace("+", "%2B"), "UTF-8");
            } catch (Exception e) {
                break;
            }
        }
        return out;
    }

    private static String query(final Link link, final String key) {
        final String value = link.query.get(key);
        return value == null ? "" : value;
    }

    // fileName and folders stand in for content links, which name nothing
    @NonNull
    static List<NameReading.Reading> of(@Nullable final String uri, @Nullable final String fileName,
                                        @Nullable final List<String> folders,
                                        @Nullable final String launchTitle) {
        final List<NameReading.Reading> out = new ArrayList<>();
        final Link link = split(uri);
        final boolean web = "http".equals(link.scheme) || "https".equals(link.scheme);
        if (web) {
            for (final String key : new String[]{"filename", "file", "fn", "name", "title", "torrent_name"}) {
                final String value = query(link, key);
                if (!value.isEmpty()) {
                    out.add(NameReading.reading(value, null, "link", false));
                }
            }
            for (final String key : new String[]{"response-content-disposition", "rscd"}) {
                final String value = dispositionName(query(link, key));
                if (!value.isEmpty()) {
                    out.add(NameReading.reading(value, null, "link", false));
                }
            }
        }
        String lastSegment = "";
        if ((web || "file".equals(link.scheme)) && !link.segments.isEmpty()) {
            lastSegment = link.segments.get(link.segments.size() - 1);
            // a link's last part counts only if it looks like a file name
            out.add(NameReading.reading(lastSegment,
                    new ArrayList<>(link.segments.subList(0, link.segments.size() - 1)),
                    "file".equals(link.scheme) ? "file name" : "link",
                    web && !NameReading.VIDEO_EXT.matcher(lastSegment).find()));
        } else if (fileName != null && !fileName.isEmpty()) {
            lastSegment = fileName;
            out.add(NameReading.reading(fileName,
                    folders == null ? Collections.<String>emptyList() : folders, "file name", false));
        }
        if (launchTitle != null && !launchTitle.trim().isEmpty()
                && !launchTitle.equals(lastSegment) && !launchTitle.equals(fileName)) {
            out.add(NameReading.reading(launchTitle.trim(), null, "title", true));
        }
        return out;
    }

    // launch title first, as shown; the file name only if it is the same title
    @Nullable
    static String searchName(@Nullable final String fileName, @Nullable final String launchTitle) {
        final String shown = launchTitle == null ? "" : launchTitle.trim();
        final NameReading.Parsed title = NameReading.parse(shown);
        if (shown.isEmpty() || !NameReading.titleOk(title)) {
            return fileName;
        }
        if (fileName != null && !fileName.trim().equals(shown)) {
            final NameReading.Parsed file = NameReading.parse(fileName);
            if (NameReading.titleOk(file)
                    && NameReading.titleMatches(file.title, Collections.singletonList(title.title))) {
                return fileName;
            }
        }
        return shown;
    }

    // ids in the link (Comet's media_id and the like), or the launching app's
    @NonNull
    static AutoMatch.LinkIds ids(@Nullable final String uri, @Nullable final String launchImdb) {
        final AutoMatch.LinkIds ids = new AutoMatch.LinkIds();
        final Link link = split(uri);
        for (final String key : new String[]{"media_id", "imdb", "imdb_id", "imdbid"}) {
            final String value = query(link, key);
            if (ids.imdb == null && IMDB.matcher(value).matches()) {
                ids.imdb = value;
            }
        }
        final int at = link.segments.indexOf("playback");
        if (at >= 0) {
            final List<String> rest = link.segments.subList(at + 1, link.segments.size());
            if (rest.size() > 4 && rest.get(0).matches("(?i)[0-9a-f]{40}")
                    && rest.get(3).matches("\\d+") && rest.get(4).matches("\\d+")) {
                ids.season = Integer.parseInt(rest.get(3));
                ids.episode = Integer.parseInt(rest.get(4));
            }
        }
        if (ids.imdb == null && launchImdb != null && IMDB.matcher(launchImdb.trim()).matches()) {
            ids.imdb = launchImdb.trim();
        }
        return ids;
    }

    static boolean isWeb(@Nullable final String uri) {
        final String scheme = split(uri).scheme;
        return "http".equals(scheme) || "https".equals(scheme);
    }

    @NonNull
    static String dispositionName(@Nullable final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        Matcher m = Pattern.compile("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE).matcher(value);
        if (!m.find()) {
            m = Pattern.compile("filename\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(value);
            if (!m.find()) {
                m = Pattern.compile("filename\\s*=\\s*([^;]+)", Pattern.CASE_INSENSITIVE).matcher(value);
                if (!m.find()) {
                    return "";
                }
            }
        }
        return decode(m.group(1).trim(), false);
    }
}
