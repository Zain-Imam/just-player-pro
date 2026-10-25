package com.brouken.player.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// mirrors Episode Info's JavaScript name reader, so the two agree name for name
public final class NameReading {

    private static final String WORD = "A-Za-z0-9_";
    // JS \b, which only sees ASCII word characters; Java's and Android's differ
    private static final String B = "(?<![" + WORD + "])";
    private static final String E = "(?![" + WORD + "])";

    private static final int I = Pattern.CASE_INSENSITIVE;

    static final Pattern VIDEO_EXT = Pattern.compile(
            "\\.(mkv|mp4|m4v|avi|mov|wmv|flv|webm|ts|m2ts|mts|mpg|mpeg|ogv|3gp|divx|m3u8|iso|rmvb|vob)$", I);

    private static final Pattern GENERIC = Pattern.compile("^(?:video|videos|stream|streams|index|master|playlist"
            + "|manifest|media|file|files|download|downloads|play|watch|movie|movies|film|films|show|shows|series|tv"
            + "|episode|episodes|output|chunklist|default|main|untitled|clip|sample|source|content|data|null|undefined"
            + "|resolve|playback|dl|get|view|preview|original|encoded|new|test|temp|tmp|seg|segment|part|audio)$", I);
    static final Pattern FOLDER_DENY = Pattern.compile("^(?:users|home|downloads?|movies|films?|tv|tv shows|shows"
            + "|series|videos?|media|volumes|library|plex|jellyfin|emby|torrents?|complete|completed|incoming|new"
            + "|private|public|desktop|documents|mnt|data|share|shared|nas|disk ?\\d*|drive|storage|anime)$", I);
    private static final Pattern EXTRA_FOLDER = Pattern.compile("^(?:extras?|featurettes?|trailers?|samples?"
            + "|behind the scenes|deleted scenes|interviews|shorts|scenes|other|specials?)$", I);
    static final Pattern EXTRA = Pattern.compile(B + "(?:trailers?|teasers?|sample|featurettes?|behind the scenes"
            + "|deleted scenes?|making of|bloopers?|gag reel|extras|bonus|promo|ncop|nced|creditless|music video"
            + "|full movie|reaction|explained|recap|interviews?|soundtrack|ost|opening|ending)" + E, I);
    // numbering TMDB doesn't use (Final Season, 2nd Season, Part 2)
    private static final Pattern SEASON_WORDS = Pattern.compile(
            B + "(?:final season|\\d+(?:st|nd|rd|th) season|part \\d+|cour \\d+)" + E, I);
    // tags that end the title; not "web" or "complete", real titles use them
    private static final Pattern[] CUT = {
            Pattern.compile(B + "(?:2160p|1440p|1080p|1080i|720p|576p|480p|4k|uhd)" + E, I),
            Pattern.compile(B + "(?:blu-?ray|bdrip|brrip|bdremux|remux|web-?dl|webrip|hdtv|pdtv|dvdrip|hdrip"
                    + "|dvdscr|hdcam|camrip|telesync|hdts)" + E, I),
            Pattern.compile(B + "(?:x26[45]|h ?26[45]|hevc|xvid|divx|av1)" + E, I),
            // "dd+" ends in a sign, so there JS wants a word character next
            Pattern.compile(B + "(?:dts(?:-?hd)?|truehd|atmos|e?ac3|ddp?\\d|aac\\d?)" + E
                    + "|" + B + "dd\\+(?=[" + WORD + "])", I),
            Pattern.compile(B + "(?:hdr10\\+?|hdr|dolby ?vision|dovi|10 ?bit|8 ?bit)" + E, I),
            Pattern.compile(B + "(?:proper|repack|rerip|internal|extended|unrated|uncut|remastered|imax|multi"
                    + "|dual[ -]audio|vostfr|subbed|dubbed)" + E, I),
            Pattern.compile(B + "(?:amzn|dsnp|hmax|atvp|pcok)" + E, I),
    };

    private static final Pattern LEADING_TAGS = Pattern.compile("^\\s*(?:\\[[^\\]]{0,60}\\]\\s*|【[^】]{0,60}】\\s*)+");
    private static final Pattern WWW_PREFIX = Pattern.compile("^\\s*www\\.\\S+?\\s*[-–—:]?\\s+", I);
    private static final Pattern SITE_PREFIX = Pattern.compile("^\\s*[a-z0-9-]{2,40}\\.(?:com|net|org|to|me|cc|tv"
            + "|io|se|ru|xyz|info|site|club|lol|vip|co|in)\\s+[-–—]\\s+", I);
    private static final Pattern DATED = Pattern.compile(
            B + "(?:19|20)\\d{2} (?:0[1-9]|1[0-2]) (?:0[1-9]|[12]\\d|3[01])" + E);
    private static final Pattern SXXEXX = Pattern.compile(
            B + "[Ss](\\d{1,2}) ?[Ee](\\d{1,4})((?:[ -]?[Ee]\\d{1,4}|-\\d{1,4})*)" + E);
    private static final Pattern NXNN = Pattern.compile(B + "(\\d{1,2})[xX](\\d{2,3})" + E);
    private static final Pattern SEASON_EPISODE_WORDS = Pattern.compile(
            B + "season ?(\\d{1,2})(?: ?[-,] ?| )(?:episode|ep) ?(\\d{1,4})" + E, I);
    private static final Pattern SEASON_ONLY = Pattern.compile(
            B + "(?:[Ss](\\d{1,2})|season ?(\\d{1,2}))" + E, I);
    private static final Pattern EPISODE_ONLY = Pattern.compile(
            B + "(?:episode|ep) ?(\\d{1,4})" + E + "|" + B + "E(\\d{2,4})" + E, I);
    private static final Pattern YEAR = Pattern.compile(B + "(19\\d{2}|20\\d{2})" + E);
    private static final Pattern DASH_EPISODE = Pattern.compile("(?:^| )[-–] ?(\\d{1,4})(?:v\\d)?(?= |$|[\\[(])");
    private static final Pattern LEAD_NUMBER = Pattern.compile("^(\\d{1,3})(?:(?: ?[-.] ?| )(?=\\S)|$)");
    private static final Pattern COUNTRY = Pattern.compile("\\s\\(?(US|UK|AU|NZ|CA|IE)\\)?\\s*$");
    private static final Pattern SAMPLE = Pattern.compile(B + "sample" + E, I);
    private static final Pattern SEASON_FOLDER = Pattern.compile(
            "^(?:season|series|staffel|saison|temporada|stagione) ?(\\d{1,2})$|^s(\\d{1,2})$", I);

    private NameReading() {
    }

    public static final class Parsed {
        @NonNull
        public String title = "";
        @Nullable
        public Integer year;
        @Nullable
        public Integer season;
        @Nullable
        public Integer episode;
        // more than one in the file, as in S01E01E02
        public int episodes = 1;
        @NonNull
        public String episodeTitle = "";
        @Nullable
        public String country;
        // "01 - Pilot": a number first, the title must come from the folder
        public boolean lead;
        // fansub "Title - 12"
        public boolean dash;
        // a daily show by date
        public boolean dated;
        public boolean extra;
        public boolean seasonWords;
        // a quality or source tag: a release rather than a home video
        public boolean tagged;
        // AniList only, not in Episode Info: all the name before " - 12"
        @NonNull
        public String series = "";
        public boolean bracketed;
    }

    public static final class Reading {
        @NonNull
        public final Parsed p;
        @NonNull
        public final String source;
        public final boolean weak;
        @NonNull
        public final String from;

        Reading(@NonNull final Parsed p, @NonNull final String source, final boolean weak,
                @NonNull final String from) {
            this.p = p;
            this.source = source;
            this.weak = weak;
            this.from = from;
        }
    }

    // dots and underscores to spaces; hyphens stay (Spider-Man)
    static String separators(final String s) {
        return s.replaceAll("[._]+", " ").replaceAll("\\s+", " ").trim();
    }

    // first quality tag at or after from, or -1
    static int firstCut(final String s, final int from) {
        int best = -1;
        final String rest = s.substring(Math.min(from, s.length()));
        for (final Pattern cut : CUT) {
            final Matcher m = cut.matcher(rest);
            if (m.find() && (best < 0 || m.start() + from < best)) {
                best = m.start() + from;
            }
        }
        return best;
    }

    @NonNull
    public static Parsed parse(@Nullable final String raw) {
        final Parsed r = new Parsed();
        String s = raw == null ? "" : raw;
        s = VIDEO_EXT.matcher(s).replaceFirst("");
        r.bracketed = LEADING_TAGS.matcher(s).find();
        s = LEADING_TAGS.matcher(s).replaceFirst("");
        s = WWW_PREFIX.matcher(s).replaceFirst("");
        s = SITE_PREFIX.matcher(s).replaceFirst("");
        s = separators(s);
        if (s.isEmpty()) {
            return r;
        }

        r.dated = DATED.matcher(s).find();

        // season and episode, the most explicit form first
        int mark = -1;
        int markEnd = -1;
        Matcher m = SXXEXX.matcher(s);
        boolean found = m.find();
        if (found) {
            r.season = Integer.parseInt(m.group(1));
            r.episode = Integer.parseInt(m.group(2));
            final Matcher more = Pattern.compile("\\d+").matcher(m.group(3));
            int last = r.episode;
            while (more.find()) {
                last = Integer.parseInt(more.group());
            }
            if (last > r.episode && last - r.episode < 10) {
                r.episodes = last - r.episode + 1;
            }
        } else {
            m = NXNN.matcher(s);
            found = m.find();
            if (found) {
                r.season = Integer.parseInt(m.group(1));
                r.episode = Integer.parseInt(m.group(2));
            } else {
                m = SEASON_EPISODE_WORDS.matcher(s);
                found = m.find();
                if (found) {
                    r.season = Integer.parseInt(m.group(1));
                    r.episode = Integer.parseInt(m.group(2));
                }
            }
        }
        if (found) {
            mark = m.start();
            markEnd = m.end();
        } else {
            final Matcher so = SEASON_ONLY.matcher(s);
            final Matcher eo = EPISODE_ONLY.matcher(s);
            if (so.find()) {
                r.season = Integer.parseInt(so.group(1) != null ? so.group(1) : so.group(2));
                mark = so.start();
                markEnd = so.end();
            }
            if (eo.find()) {
                r.episode = Integer.parseInt(eo.group(1) != null ? eo.group(1) : eo.group(2));
                if (mark < 0 || eo.start() < mark) {
                    mark = eo.start();
                }
                markEnd = Math.max(markEnd, eo.end());
            }
        }

        // the last year before the episode or the tags (1917 alone is a title)
        final int qc = firstCut(s, 0);
        r.tagged = qc >= 0;
        final int hard = Math.min(mark >= 0 ? mark : s.length(), qc >= 0 ? qc : s.length());
        final int maxYear = Calendar.getInstance().get(Calendar.YEAR) + 1;
        int yearAt = -1;
        if (!r.dated) {
            final Matcher ym = YEAR.matcher(s);
            while (ym.find()) {
                final int y = Integer.parseInt(ym.group(1));
                if (ym.start() > 0 && ym.start() < hard && y <= maxYear
                        && (mark < 0 || ym.start() < mark || ym.start() >= markEnd)) {
                    r.year = y;
                    yearAt = ym.start();
                }
            }
        }

        // fansub "Title - 12", only without a year
        int dashAt = -1;
        if (r.episode == null && r.year == null) {
            final Matcher dm = DASH_EPISODE.matcher(s);
            if (dm.find() && Integer.parseInt(dm.group(1)) > 0) {
                r.episode = Integer.parseInt(dm.group(1));
                r.dash = true;
                dashAt = dm.start();
                if (mark < 0 || dm.start() < mark) {
                    mark = dm.start();
                }
                markEnd = Math.max(markEnd, dm.end());
            }
        }
        if (r.episode == null && r.season == null && r.year == null) {
            final Matcher lm = LEAD_NUMBER.matcher(s);
            if (lm.find()) {
                r.episode = Integer.parseInt(lm.group(1));
                r.lead = true;
                mark = 0;
                markEnd = lm.end();
            }
        }

        int cut = s.length();
        if (mark >= 0) {
            cut = Math.min(cut, mark);
        }
        if (qc >= 0) {
            cut = Math.min(cut, qc);
        }
        if (yearAt >= 0) {
            cut = Math.min(cut, yearAt);
        }
        String title = s.substring(0, cut).replaceAll("[\\[({][^\\])}]*$", "");
        // only in the show's name; "Pilot Part 1" is an episode title
        r.seasonWords = SEASON_WORDS.matcher(title).find();
        final Matcher c = COUNTRY.matcher(title);
        if (c.find()) {
            r.country = "UK".equals(c.group(1)) ? "GB" : c.group(1);
            title = title.substring(0, c.start());
        }
        r.title = title.replaceAll("[\\[({][^\\])}]*[\\])}]", " ").replaceAll("\\s+", " ")
                .replaceAll("^[\\s\\-–—:,]+|[\\s\\-–—:,(\\[{]+$", "");
        r.series = r.title;
        if (dashAt >= 0) {
            // brackets kept as words: (The Final Season) names the part
            final String whole = s.substring(0, qc >= 0 ? Math.min(dashAt, qc) : dashAt)
                    .replaceAll("[\\[\\](){}]", " ").replaceAll("\\s+", " ")
                    .replaceAll("^[\\s\\-–—:,]+|[\\s\\-–—:,]+$", "");
            if (!whole.isEmpty()) {
                r.series = whole;
            }
        }

        final int tailAt = markEnd >= 0 ? firstCut(s, markEnd) : qc;
        if (markEnd >= 0) {
            r.episodeTitle = s.substring(markEnd, tailAt >= 0 ? tailAt : s.length())
                    .replaceAll("^[\\s\\-–—:]+|[\\s\\-–—:]+$", "");
        }
        // any word is fine in an episode title (The Opening)
        final String checked = r.episode != null
                ? r.title + " " + (tailAt >= 0 ? s.substring(tailAt) : "")
                : s;
        r.extra = EXTRA.matcher(checked).find() || SAMPLE.matcher(s).find();
        return r;
    }

    // worth searching TMDB for?
    public static boolean titleOk(@Nullable final Parsed r) {
        final String t = r == null ? "" : r.title;
        if (t.isEmpty()) {
            return false;
        }
        int letters = 0;
        for (int i = 0; i < t.length(); ) {
            final int cp = t.codePointAt(i);
            if (Character.isLetter(cp)) {
                letters++;
            }
            i += Character.charCount(cp);
        }
        final boolean anchored = r.year != null || (r.season != null && r.episode != null && !r.lead);
        // numbers (24, 1917) and short titles (It) need a year or an episode
        if (t.matches("\\d{2,4}")) {
            return anchored;
        }
        if (letters < 2 || (letters < 3 && !anchored)) {
            return false;
        }
        if (GENERIC.matcher(t).matches()) {
            return false;
        }
        final String bare = t.replaceAll("[^0-9a-zA-Z]", "");
        if (bare.length() >= 12 && bare.matches("[0-9a-fA-F]+")) {
            return false; // hashes, UUIDs
        }
        if (t.matches("[A-Za-z0-9_-]{20,}")) {
            return false; // keys, tokens
        }
        if (t.indexOf('=') >= 0 || t.indexOf('&') >= 0
                || Pattern.compile("[0-9a-f]{16,}", I).matcher(t).find()) {
            return false; // query strings
        }
        if (Pattern.compile("^(?=.*\\d)[0-9a-f]{6,}$", I).matcher(t).find()) {
            return false; // short hex ids
        }
        if (Pattern.compile("^(?=(?:.*\\d){3})(?=(?:.*[a-z]){3})[a-z0-9]{10,}$", I).matcher(t).find()) {
            return false; // random ids
        }
        return !Pattern.compile("^(?:part|vol|volume|disc|cd) ?\\d+$", I).matcher(t).find();
    }

    // the title from the folder (Show/Season 1/01.mkv) if it looks like a show
    @Nullable
    static Context folderContext(@NonNull final List<String> folders) {
        final int n = folders.size();
        if (n == 0) {
            return null;
        }
        final String parent = folders.get(n - 1).trim();
        final Matcher sm = SEASON_FOLDER.matcher(parent);
        if (sm.find()) {
            if (n < 2) {
                return null;
            }
            final Parsed g = parse(folders.get(n - 2));
            if (!titleOk(g) || FOLDER_DENY.matcher(g.title).matches()) {
                return null;
            }
            return new Context(g.title, g.year, g.country,
                    Integer.parseInt(sm.group(1) != null ? sm.group(1) : sm.group(2)),
                    folders.get(n - 2) + "/" + parent);
        }
        final Parsed q = parse(parent);
        final boolean strong = q.season != null || q.year != null || firstCut(separators(parent), 0) >= 0;
        if (!strong || !titleOk(q) || FOLDER_DENY.matcher(q.title).matches()) {
            return null;
        }
        return new Context(q.title, q.year, q.country, q.season, parent);
    }

    static final class Context {
        final String title;
        @Nullable
        final Integer year;
        @Nullable
        final String country;
        @Nullable
        final Integer season;
        final String name;

        Context(final String title, @Nullable final Integer year, @Nullable final String country,
                @Nullable final Integer season, final String name) {
            this.title = title;
            this.year = year;
            this.country = country;
            this.season = season;
            this.name = name;
        }
    }

    // weak readings (a title some app showed) need an episode or a year
    @NonNull
    public static Reading reading(@NonNull final String name, @Nullable List<String> folders,
                                  @NonNull final String source, final boolean weak) {
        Parsed p = parse(name);
        String from = name;
        if (folders == null) {
            folders = new ArrayList<>();
        }
        if (titleOk(p) && p.episode != null && !folders.isEmpty()) {
            // scene files often start with the group's name
            final Context ctx = folderContext(folders);
            if (ctx != null && !ctx.title.isEmpty() && !norm(p.title).equals(norm(ctx.title))
                    && norm(p.title).endsWith(norm(ctx.title))) {
                p.title = ctx.title;
                p.series = ctx.title;
                from = ctx.name + "/" + name;
            }
        } else if (!titleOk(p) && p.episode != null && !folders.isEmpty()) {
            final Context f = folderContext(folders);
            if (f != null) {
                p.title = f.title;
                p.series = f.title;
                p.year = p.year != null ? p.year : f.year;
                p.country = p.country != null ? p.country : f.country;
                if (p.season == null) {
                    p.season = f.season;
                }
                p.lead = false;
                from = f.name + "/" + name;
            }
        } else if (!titleOk(p) && p.episode == null && !folders.isEmpty()) {
            // a generic or numbered file inside a folder that names it
            final String parent = folders.get(folders.size() - 1);
            final Parsed q = parse(parent);
            final boolean named = q.year != null || (q.season != null && q.episode != null);
            if (named && usable(new Reading(q, source, false, parent))
                    && !FOLDER_DENY.matcher(q.title).matches()) {
                p = q;
                from = parent + "/" + name;
            }
        }
        for (final String folder : folders.subList(Math.max(0, folders.size() - 2), folders.size())) {
            if (EXTRA_FOLDER.matcher(folder.trim()).matches()) {
                p.extra = true;
            }
        }
        // web page titles like "... Ending Scene" are clips
        if (weak && EXTRA.matcher(separators(name)).find()) {
            p.extra = true;
        }
        return new Reading(p, source, weak, from);
    }

    public static boolean usable(@NonNull final Reading rd) {
        final Parsed p = rd.p;
        if (!titleOk(p) || p.dated || p.lead) {
            return false;
        }
        if (p.season != null && p.episode == null) {
            return false; // season pack
        }
        return !(rd.weak && p.episode == null && p.year == null);
    }

    // accents dropped, & as "and", punctuation and spaces gone
    @NonNull
    public static String norm(@Nullable final String s) {
        final String decomposed = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replace("&", " and ").replaceAll("['’`ʼ]", "").replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    // also without a leading The, A or An on both sides
    public static boolean titleMatches(@Nullable final String title, @NonNull final List<String> names) {
        final String a = norm(title);
        if (a.isEmpty()) {
            return false;
        }
        final String a2 = norm(withoutArticle(title));
        for (final String n : names) {
            final String b = norm(n);
            if (!b.isEmpty() && (b.equals(a) || norm(withoutArticle(n)).equals(a2))) {
                return true;
            }
        }
        return false;
    }

    private static String withoutArticle(@Nullable final String s) {
        return (s == null ? "" : s).replaceFirst("(?i)^\\s*(?:the|a|an)\\s+", "");
    }
}
