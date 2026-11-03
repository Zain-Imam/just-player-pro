package com.brouken.player.ytdlp;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.SubtitleParser;

import com.brouken.player.R;
import com.brouken.player.engine.RangedDataSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// a website's video as yt-dlp found it: one choice per quality, best first, audio only last
public final class WebVideo {

    public static final String PREF_QUALITY = "ytdlpQuality";
    private static final int[] HEIGHTS = {144, 240, 360, 480, 720, 1080, 1440, 2160, 4320};
    private static final Pattern NOTE_HEIGHT = Pattern.compile("^(\\d{3,4})p");
    private static final List<String> COOKIE_ATTRIBUTES = Arrays.asList(
            "domain", "path", "expires", "max-age", "secure", "httponly", "samesite");

    public static final class Choice {
        public final String label;
        public final String detail;
        public final boolean audioOnly;
        final int height;
        String url;
        @Nullable
        String audioUrl;
        @Nullable
        String mimeType;
        final long pieceBytes;
        @Nullable
        String master;
        long bits;

        Choice(final String label, final String detail, final boolean audioOnly, final int height,
               final String url, @Nullable final String audioUrl, @Nullable final String mimeType,
               final long pieceBytes) {
            this.label = label;
            this.detail = detail;
            this.audioOnly = audioOnly;
            this.height = height;
            this.url = url;
            this.audioUrl = audioUrl;
            this.mimeType = mimeType;
            this.pieceBytes = pieceBytes;
        }

        public String mpvUrl() {
            return master != null ? master : url;
        }

        // per-file options for mpv's loadfile
        public List<String> mpvOptions() {
            final List<String> options = new ArrayList<>();
            if (master != null) {
                // the variant at or under this bitrate, as the quality picked
                options.add("hls-bitrate=" + (bits > 0 ? bits : "max"));
            } else if (audioUrl != null) {
                // the list form splits at every ":", so one address goes in whole this way
                options.add("audio-file=" + quoted(audioUrl));
            }
            if (pieceBytes > 0) {
                options.add("stream-lavf-o=" + quoted("request_size=" + pieceBytes + ","
                        + com.brouken.player.mpv.MpvOptions.RECONNECT));
            }
            return options;
        }

        // mpv's %length% quoting, since URLs can hold commas
        private static String quoted(final String value) {
            return "%" + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + "%" + value;
        }
    }

    public static final class Failure extends Exception {
        public static final int LOGIN = 1;
        public static final int UNSUPPORTED = 2;
        public static final int NETWORK = 3;
        public static final int OTHER = 4;
        public static final int DRM = 5;

        public final int kind;

        Failure(final int kind, final String message) {
            super(message);
            this.kind = kind;
        }
    }

    public final Uri page;
    public final String title;
    public final boolean live;
    @Nullable
    public final String thumbnail;
    public final List<Choice> choices;
    public final List<Audio> audios = new ArrayList<>();
    public int audioPicked;
    // fetched captions, as the player gets them
    public final List<MediaItem.SubtitleConfiguration> subtitles = new java.util.concurrent.CopyOnWriteArrayList<>();
    // all the site offers; only the one for Settings is fetched up front
    public final List<Caption> captions = new ArrayList<>();
    @Nullable
    public Storyboard storyboard;
    public final Map<String, String> headers;
    // YouTube serves pieces of this size fast and the rest of a file slowly; 0 when it does not matter
    public final long pieceBytes;
    public int picked;
    // YouTube's addresses stop working after about six hours; set from the links themselves
    private long usableUntil = android.os.SystemClock.elapsedRealtime() + Lookups.KEEP_MS;

    private static final long BOT_PAUSE_MS = 5 * 60 * 1000L;
    private static volatile long botCheckAt;
    @Nullable
    private static volatile Failure botCheck;

    public boolean fresh() {
        return android.os.SystemClock.elapsedRealtime() < usableUntil;
    }

    private void usableFor(final long ms) {
        usableUntil = android.os.SystemClock.elapsedRealtime() + ms;
    }

    private WebVideo(final Uri page, final String title, final boolean live, @Nullable final String thumbnail,
                     final List<Choice> choices, final Map<String, String> headers, final long pieceBytes) {
        this.page = page;
        this.title = title;
        this.live = live;
        this.thumbnail = thumbnail;
        this.choices = choices;
        this.headers = headers;
        this.pieceBytes = pieceBytes;
    }

    public Choice current() {
        return choices.get(picked);
    }

    // one file to read seek frames from; none for a stream in pieces or a live one
    @Nullable
    public Uri plainFile() {
        final Choice choice = current();
        return live || choice.audioOnly || choice.mimeType != null ? null : Uri.parse(choice.url);
    }

    public static boolean isYouTube(@Nullable final Uri uri) {
        final String host = uri == null || uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com")
                || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com");
    }

    // one key per YouTube video however it was linked (watch?v=, youtu.be, shorts, a share's ?si=),
    // since Recent and resume points otherwise drop the query that holds the video's id
    @Nullable
    public static String youTubePlace(@Nullable final Uri uri) {
        if (!isYouTube(uri)) {
            return null;
        }
        String id = null;
        try {
            id = uri.getQueryParameter("v");
        } catch (UnsupportedOperationException ignored) {
        }
        final List<String> segments = uri.getPathSegments();
        if (id == null && "youtu.be".equalsIgnoreCase(uri.getHost()) && !segments.isEmpty()) {
            id = segments.get(0);
        } else if (id == null && segments.size() >= 2 && Arrays.asList("shorts", "live", "embed", "v")
                .contains(segments.get(0))) {
            id = segments.get(1);
        }
        return id == null || id.isEmpty() ? null : "https://www.youtube.com/watch?v=" + id;
    }

    public static WebVideo resolve(final Context context, final Uri page,
                                   final AtomicReference<Process> running) throws Failure {
        // a kept answer whose links still work: the site isn't asked again
        final String kept = Lookups.read(context, page.toString());
        if (kept != null) {
            final WebVideo video = parse(context, page, kept);
            if (video != null && !video.choices.isEmpty() && !video.live) {
                video.usableFor(Lookups.leftMs(context, page.toString(), kept));
                return ready(context, video);
            }
        }
        // after YouTube's "not a bot" check, asking again at once only keeps the block going
        final Failure paused = botCheck;
        if (paused != null && isYouTube(page)
                && android.os.SystemClock.elapsedRealtime() - botCheckAt < BOT_PAUSE_MS) {
            throw new Failure(paused.kind, paused.getMessage());
        }
        final List<String> args = new ArrayList<>(Arrays.asList("--ignore-config", "--no-progress",
                "--playlist-items", "1", "--socket-timeout", "20", "-J"));
        // a watch link with &list= is its own video; the list is read apart, for the queue
        if (isYouTube(page)) {
            args.add("--no-playlist");
        }
        YtDlp.Result result = run(context, args, page, running);
        WebVideo video = result.exitCode == 0 ? parse(context, page, result.out) : null;
        // YouTube may need its JavaScript solved, which is slower, so only when plain fails; not after a
        // bot check, which JavaScript doesn't answer and asking again only prolongs
        if ((video == null || video.choices.isEmpty()) && isYouTube(page) && YtDlp.hasQuickJs(context)
                && !result.err.toLowerCase(Locale.ROOT).contains("not a bot")
                && !Thread.currentThread().isInterrupted()) {
            args.add(0, "quickjs:" + YtDlp.quickJs(context));
            args.add(0, "--js-runtimes");
            final YtDlp.Result again = run(context, args, page, running);
            final WebVideo second = again.exitCode == 0 ? parse(context, page, again.out) : null;
            if (second != null && !second.choices.isEmpty()) {
                keep(context, page, again.out, second);
                return ready(context, second);
            }
        }
        if (video != null && !video.choices.isEmpty()) {
            keep(context, page, result.out, video);
            return ready(context, video);
        }
        final Failure failure = failure(result);
        if (isYouTube(page) && result.err.toLowerCase(Locale.ROOT).contains("not a bot")) {
            botCheckAt = android.os.SystemClock.elapsedRealtime();
            botCheck = failure;
        }
        // Vimeo's pages want a login now, its player links do not
        final Uri player = vimeoPlayer(page);
        if (failure.kind == Failure.LOGIN && player != null && !Thread.currentThread().isInterrupted()) {
            final YtDlp.Result again = run(context, args, player, running);
            final WebVideo second = again.exitCode == 0 ? parse(context, page, again.out) : null;
            if (second != null && !second.choices.isEmpty()) {
                return ready(context, second);
            }
        }
        throw failure;
    }

    // a live stream's addresses move on, so it is always asked afresh
    private static void keep(final Context context, final Uri page, final String json, final WebVideo video) {
        video.usableFor(Lookups.keepFor(json, System.currentTimeMillis()));
        if (!video.live) {
            Lookups.write(context, page.toString(), json);
        }
    }

    private static WebVideo ready(final Context context, final WebVideo video) throws Failure {
        if (isProtected(video)) {
            throw new Failure(Failure.DRM, "DRM");
        }
        video.keepSubtitles(context);
        return video;
    }

    // only the caption for Settings now: YouTube refuses captions (429) after a few dozen, so the
    // rest wait until picked
    private void keepSubtitles(final Context context) {
        final java.io.File[] old = new java.io.File(context.getCacheDir(), "web-subtitles").listFiles();
        for (int i = 0; old != null && i < old.length; i++) {
            if (System.currentTimeMillis() - old[i].lastModified() > 24 * 60 * 60 * 1000L) {
                //noinspection ResultOfMethodCallIgnored
                old[i].delete();
            }
        }
        final Caption wanted = wantedCaption(context, captions);
        // quiet if it doesn't come: it stays in the menu to try again
        if (wanted != null) {
            fetch(context, wanted);
        }
    }

    // yt-dlp reads only the master playlist; Vimeo's FairPlay key shows in the one below it
    private static boolean isProtected(final WebVideo video) {
        final Choice choice = video.current();
        if (!MimeTypes.APPLICATION_M3U8.equals(choice.mimeType)) {
            return false;
        }
        final okhttp3.Request.Builder request = new okhttp3.Request.Builder().url(choice.url);
        for (final Map.Entry<String, String> header : video.headers.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        try (okhttp3.Response response = com.brouken.player.online.Http.client()
                .newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return false;
            }
            for (final String line : response.body().string().split("\n")) {
                if ((line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY"))
                        && line.contains("METHOD=SAMPLE-AES")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // let the player find out
        }
        return false;
    }

    // vimeo.com/123 and vimeo.com/123/abcdef (unlisted) as player.vimeo.com links
    @Nullable
    private static Uri vimeoPlayer(final Uri page) {
        final String host = page.getHost() == null ? "" : page.getHost().toLowerCase(Locale.ROOT);
        final List<String> segments = page.getPathSegments();
        if (!(host.equals("vimeo.com") || host.equals("www.vimeo.com")) || segments.isEmpty()
                || !segments.get(0).matches("\\d+")) {
            return null;
        }
        final Uri.Builder player = Uri.parse("https://player.vimeo.com/video/" + segments.get(0)).buildUpon();
        if (segments.size() > 1 && segments.get(1).matches("[0-9a-f]+")) {
            player.appendQueryParameter("h", segments.get(1));
        }
        return player.build();
    }

    private static YtDlp.Result run(final Context context, final List<String> args, final Uri page,
                                    final AtomicReference<Process> running) throws Failure {
        final List<String> all = new ArrayList<>(args);
        all.add("--");
        all.add(page.toString());
        try {
            return YtDlp.run(context, all, 120_000, running);
        } catch (IOException e) {
            throw new Failure(Failure.OTHER, String.valueOf(e.getMessage()));
        }
    }

    private static Failure failure(final YtDlp.Result result) {
        String error = "";
        for (final String line : result.err.split("\n")) {
            if (line.startsWith("ERROR:")) {
                error = line.substring(6).trim();
            }
        }
        if (error.isEmpty()) {
            error = YtDlp.lastLine(result.err);
        }
        final String lower = error.toLowerCase(Locale.ROOT);
        final int kind;
        if (error.isEmpty()) {
            // yt-dlp answered, but with nothing a player can open
            kind = Failure.UNSUPPORTED;
        } else if (lower.contains("sign in") || lower.contains("log in") || lower.contains("login")
                || lower.contains("--cookies") || lower.contains("private") || lower.contains("members")
                || lower.contains("registered users") || lower.contains("account")) {
            kind = Failure.LOGIN;
        } else if (lower.contains("unsupported url") || lower.contains("no video formats")
                || lower.contains("no video could be found")) {
            kind = Failure.UNSUPPORTED;
        } else if (lower.contains("unable to download") || lower.contains("timed out")
                || lower.contains("name resolution") || lower.contains("failed to resolve")
                || lower.contains("connection")) {
            kind = Failure.NETWORK;
        } else {
            kind = Failure.OTHER;
        }
        // drop yt-dlp's "[site] id: " prefix
        return new Failure(kind, error.replaceFirst("^\\[[^]]+] [^:]{1,40}: ", ""));
    }

    @Nullable
    static WebVideo parse(final Context context, final Uri page, final String json) {
        try {
            JSONObject info = new JSONObject(json);
            if ("playlist".equals(info.optString("_type"))) {
                final JSONArray entries = info.optJSONArray("entries");
                if (entries == null || entries.optJSONObject(0) == null) {
                    return null;
                }
                info = entries.getJSONObject(0);
            }
            JSONArray formats = info.optJSONArray("formats");
            if (formats == null || formats.length() == 0) {
                formats = new JSONArray().put(info);
            }
            final List<JSONObject> videos = new ArrayList<>();
            final List<JSONObject> audios = new ArrayList<>();
            final List<JSONObject> both = new ArrayList<>();
            long pieceBytes = 0;
            Map<String, String> headers = null;
            for (int i = 0; i < formats.length(); i++) {
                final JSONObject format = formats.optJSONObject(i);
                if (format == null || playable(format) == null) {
                    continue;
                }
                final boolean video = !"none".equals(format.optString("vcodec"));
                final boolean audio = !"none".equals(format.optString("acodec"));
                if (video && audio) {
                    both.add(format);
                } else if (video) {
                    videos.add(format);
                } else if (audio) {
                    audios.add(format);
                } else {
                    continue;
                }
                if (headers == null) {
                    headers = headersOf(format);
                }
                final JSONObject options = format.optJSONObject("downloader_options");
                if (options != null && options.optLong("http_chunk_size") > 0) {
                    pieceBytes = options.optLong("http_chunk_size");
                }
            }
            // one sound per language; the picked one goes with every picture
            final List<Audio> languages = languages(context, audios);
            final int audioPicked = defaultAudio(context, languages);
            final JSONObject audio = languages.isEmpty() ? bestAudio(audios) : languages.get(audioPicked).format;
            // nameless, sizeless copies would list as "0", "1" beside the named ones
            boolean sized = false;
            for (final JSONObject format : both) {
                sized |= classHeight(format) > 0;
            }
            for (final JSONObject format : videos) {
                sized |= classHeight(format) > 0;
            }
            final boolean skipNameless = sized;
            final Map<String, Choice> byLabel = new LinkedHashMap<>();
            final Map<String, Integer> rankOf = new HashMap<>();
            for (final JSONObject format : both) {
                offer(byLabel, rankOf, format, null, 0, pieceBytes, skipNameless);
            }
            // a silent video (some reels) has no sound to pair with, and plays as it is
            for (final JSONObject format : videos) {
                offer(byLabel, rankOf, format, audio, 1, pieceBytes, skipNameless);
            }
            final List<Choice> choices = new ArrayList<>(byLabel.values());
            Collections.sort(choices, (a, b) -> b.height != a.height ? b.height - a.height
                    : b.label.compareTo(a.label));
            if (audio != null) {
                choices.add(new Choice("", codecName(audio.optString("acodec")) + bitrate(audio),
                        true, 0, playable(audio), null, mimeOf(audio), pieceBytes));
            }
            if (headers == null) {
                headers = new HashMap<>();
            }
            final WebVideo result = new WebVideo(page, info.optString("title", page.toString()),
                    info.optBoolean("is_live"), info.optString("thumbnail", null), choices, headers, pieceBytes);
            result.picked = defaultPick(context, choices);
            result.audios.addAll(languages);
            result.audioPicked = audioPicked;
            result.captions.addAll(captions(context, info));
            if (!result.live) {
                result.storyboard = storyboard(formats);
            }
            return result;
        } catch (JSONException e) {
            return null;
        }
    }

    // the address a player can open, or null for kinds it cannot (fragment lists, storyboards)
    @Nullable
    private static String playable(final JSONObject format) {
        if (format.optBoolean("has_drm") || "mhtml".equals(format.optString("ext"))
                || format.optString("format_id").contains("-drc")) {
            return null;
        }
        final String protocol = format.optString("protocol", "https");
        final String url = format.optString("url", "");
        if (url.isEmpty()) {
            return null;
        }
        switch (protocol) {
            case "http":
            case "https":
            case "m3u8":
            case "m3u8_native":
                return url;
            case "http_dash_segments":
                return format.optString("manifest_url", null);
            default:
                return null;
        }
    }

    @Nullable
    private static String mimeOf(final JSONObject format) {
        final String protocol = format.optString("protocol", "");
        if (protocol.startsWith("m3u8")) {
            return MimeTypes.APPLICATION_M3U8;
        }
        if (protocol.equals("http_dash_segments")) {
            return MimeTypes.APPLICATION_MPD;
        }
        return null;
    }

    private static void offer(final Map<String, Choice> byLabel, final Map<String, Integer> rankOf,
                              final JSONObject format, @Nullable final JSONObject audio, final int separate,
                              final long pieceBytes, final boolean skipNameless) {
        final int height = classHeight(format);
        if (skipNameless && height <= 0 && format.optString("format_note", "").trim().isEmpty()) {
            return;
        }
        final double fps = format.optDouble("fps", 0);
        // some sites give no size, only their own name for it ("sd", "hd")
        final String note = format.optString("format_note", "").trim();
        final String label = height > 0 ? height + "p" + (fps >= 49 ? " · " + Math.round(fps) + " fps" : "")
                : !note.isEmpty() ? note.toUpperCase(Locale.ROOT) : format.optString("format_id", "?");
        final String vcodec = format.optString("vcodec", "");
        // easiest to decode first; one stream before two; direct before HLS (YouTube's is slow)
        final int rank = codecRank(vcodec) * 100 + separate * 10
                + (format.optString("protocol").startsWith("m3u8") ? 1 : 0);
        final Integer had = rankOf.get(label);
        if (had != null && had <= rank) {
            return;
        }
        rankOf.put(label, rank);
        String detail = codecName(vcodec);
        if (format.optString("dynamic_range", "SDR").startsWith("HDR")) {
            detail += " · HDR";
        }
        final Choice choice = new Choice(label, detail, false, height, playable(format),
                audio == null ? null : playable(audio), mimeOf(format), pieceBytes);
        // HLS picture and sound from one main playlist: mpv gets that playlist, so both share one
        // clock, where a separate sound file is lined up by its own start and drifts on live streams
        final String master = format.optString("manifest_url", "");
        if (audio != null && MimeTypes.APPLICATION_M3U8.equals(choice.mimeType)
                && MimeTypes.APPLICATION_M3U8.equals(mimeOf(audio))
                && !master.isEmpty() && master.equals(audio.optString("manifest_url"))) {
            choice.master = master;
            choice.bits = (long) (format.optDouble("tbr", 0) * 1000);
        }
        byLabel.put(label, choice);
    }

    private static int codecRank(final String codec) {
        if (codec.startsWith("avc1") || codec.equals("h264")) {
            return 0;
        }
        if (codec.startsWith("vp09") || codec.startsWith("vp9")) {
            return 1;
        }
        if (codec.startsWith("hev1") || codec.startsWith("hvc1")) {
            return 2;
        }
        if (codec.startsWith("av01")) {
            return 3;
        }
        return codec.isEmpty() || codec.equals("null") ? 1 : 4;
    }

    static String codecName(final String codec) {
        if (codec.startsWith("avc1") || codec.equals("h264")) return "H.264";
        if (codec.startsWith("vp09") || codec.startsWith("vp9")) return "VP9";
        if (codec.startsWith("hev1") || codec.startsWith("hvc1")) return "HEVC";
        if (codec.startsWith("av01")) return "AV1";
        if (codec.startsWith("mp4a")) return "AAC";
        if (codec.equals("opus")) return "Opus";
        if (codec.isEmpty() || codec.equals("null")) return "";
        return codec.toUpperCase(Locale.ROOT);
    }

    private static String bitrate(final JSONObject audio) {
        final double abr = audio.optDouble("abr", 0);
        return abr > 0 ? " · " + Math.round(abr) + " kbps" : "";
    }

    // 1920x800 films are 1080p to people; YouTube names its own in format_note
    private static int classHeight(final JSONObject format) {
        final Matcher note = NOTE_HEIGHT.matcher(format.optString("format_note", ""));
        if (note.find()) {
            return Integer.parseInt(note.group(1));
        }
        final int height = format.optInt("height", 0);
        final int width = format.optInt("width", 0);
        if (height <= 0) {
            return 0;
        }
        // an upright phone video is named by its width, as 720x1280 is 720p
        final int fromWidth = height > width && width > 0 ? width
                : width > height * 16 / 9 ? width * 9 / 16 : height;
        int best = HEIGHTS[0];
        for (final int standard : HEIGHTS) {
            if (Math.abs(standard - fromWidth) < Math.abs(best - fromWidth)) {
                best = standard;
            }
        }
        return best;
    }

    public static final class Audio {
        public final String label;
        final JSONObject format;

        Audio(final String label, final JSONObject format) {
            this.label = label;
            this.format = format;
        }
    }

    // the sound in another language, for every quality alike
    public void pickAudio(final int index) {
        final JSONObject format = audios.get(index).format;
        audioPicked = index;
        for (final Choice choice : choices) {
            if (choice.audioOnly) {
                choice.url = playable(format);
                choice.mimeType = mimeOf(format);
            } else if (choice.audioUrl != null) {
                choice.audioUrl = playable(format);
                choice.master = null;
            }
        }
    }

    // YouTube's dubs: the best sound of each language, the original first; none when only one
    private static List<Audio> languages(final Context context, final List<JSONObject> audios) {
        final Map<String, List<JSONObject>> byLanguage = new LinkedHashMap<>();
        for (final JSONObject audio : audios) {
            final String language = audio.optString("language", "");
            if (!language.isEmpty() && !language.equals("null")) {
                List<JSONObject> list = byLanguage.get(language);
                if (list == null) {
                    list = new ArrayList<>();
                    byLanguage.put(language, list);
                }
                list.add(audio);
            }
        }
        final List<Audio> languages = new ArrayList<>();
        if (byLanguage.size() < 2) {
            return languages;
        }
        for (final Map.Entry<String, List<JSONObject>> entry : byLanguage.entrySet()) {
            final JSONObject best = bestAudio(entry.getValue());
            // only some of a language's streams say "dubbed" or "original" in their note
            final StringBuilder notes = new StringBuilder();
            for (final JSONObject audio : entry.getValue()) {
                notes.append(audio.optString("format_note", "").toLowerCase(Locale.ROOT)).append(' ');
            }
            final String note = notes.toString();
            final String name = languageName(entry.getKey());
            final String label = best.optInt("language_preference", -1) >= 10 || note.contains("original")
                    ? context.getString(R.string.web_audio_original, name)
                    : note.contains("dubbed") ? context.getString(R.string.web_audio_dubbed, name) : name;
            languages.add(new Audio(label, best));
        }
        Collections.sort(languages, (a, b) -> Integer.compare(
                b.format.optInt("language_preference", -1), a.format.optInt("language_preference", -1)));
        return languages;
    }

    // a language set for sound in Settings, else the original
    private static int defaultAudio(final Context context, final List<Audio> languages) {
        for (final String language : com.brouken.player.Languages.audio(context)) {
            final String wanted = com.brouken.player.Languages.plain(language);
            for (int i = 0; i < languages.size(); i++) {
                if (com.brouken.player.Languages.plain(languages.get(i).format.optString("language"))
                        .equals(wanted)) {
                    return i;
                }
            }
        }
        return 0;
    }

    public static final class Caption {
        public static final int UPLOADED = 0;
        public static final int AUTOMATIC = 1;

        public final int kind;
        public final String language;
        // the language alone
        public final String name;
        // as the subtitle menu shows it once fetched
        public final String label;
        final String url;
        final String mimeType;
        // a playlist of short WebVTT pieces rather than one file (TED)
        final boolean pieces;
        // the same language as pieces from the video servers, for when the caption address
        // refuses (YouTube limits it apart from the video); null when there is none
        @Nullable
        String fallback;
        public boolean loaded;

        Caption(final int kind, final String language, final String name, final String label,
                final String url, final String mimeType, final boolean pieces) {
            this.kind = kind;
            this.language = language;
            this.name = name;
            this.label = label;
            this.url = url;
            this.mimeType = mimeType;
            this.pieces = pieces;
        }
    }

    // the uploader's, then the automatic one of the spoken language. YouTube's machine
    // translations are left out: its caption server refuses them after a handful
    private static List<Caption> captions(final Context context, final JSONObject info) {
        final List<Caption> uploaded = new ArrayList<>();
        final List<Caption> automatic = new ArrayList<>();
        final JSONObject own = info.optJSONObject("subtitles");
        if (own != null) {
            final java.util.Iterator<String> keys = own.keys();
            while (keys.hasNext()) {
                final String language = keys.next();
                final String name = languageName(language);
                addCaption(uploaded, Caption.UPLOADED, own.optJSONArray(language), language, name, name,
                        piecesOf(info, language));
            }
        }
        final JSONObject auto = info.optJSONObject("automatic_captions");
        if (auto != null) {
            // "en-orig" is the speech as heard; the other codes are translations of it
            boolean heard = false;
            final java.util.Iterator<String> keys = auto.keys();
            while (keys.hasNext()) {
                heard |= keys.next().endsWith("-orig");
            }
            final String spoken = base(info.optString("language", ""));
            final String plainSpoken = heard || spoken.isEmpty() ? null : captionKey(auto, spoken);
            final java.util.Iterator<String> all = auto.keys();
            while (all.hasNext()) {
                final String key = all.next();
                if (key.endsWith("-orig") || key.equals(plainSpoken)) {
                    final String language = key.replace("-orig", "");
                    final String name = languageName(language);
                    addCaption(automatic, Caption.AUTOMATIC, auto.optJSONArray(key), base(language), name,
                            context.getString(R.string.web_subtitle_auto, name),
                            piecesOf(info, base(language)));
                }
            }
        }
        final java.text.Collator collator = java.text.Collator.getInstance();
        Collections.sort(uploaded, (a, b) -> collator.compare(a.name, b.name));
        final List<Caption> captions = new ArrayList<>(uploaded);
        captions.addAll(automatic);
        return captions;
    }

    // YouTube also lists each caption language as a piece playlist on its video servers
    @Nullable
    private static String piecesOf(final JSONObject info, final String key) {
        final JSONObject auto = info.optJSONObject("automatic_captions");
        final JSONArray formats = auto == null ? null : auto.optJSONArray(key);
        for (int i = 0; formats != null && i < formats.length(); i++) {
            final JSONObject format = formats.optJSONObject(i);
            if (format != null && "vtt".equals(format.optString("ext"))
                    && format.optString("protocol", "").startsWith("m3u8")
                    && !format.optString("url", "").isEmpty()) {
                return format.optString("url");
            }
        }
        return null;
    }

    // a site without "-orig" may still name the spoken language's automatic captions
    @Nullable
    private static String captionKey(final JSONObject automatic, final String language) {
        if (automatic.has(language)) {
            return language;
        }
        final java.util.Iterator<String> keys = automatic.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (key.startsWith(language + "-")) {
                return key;
            }
        }
        return null;
    }

    private static void addCaption(final List<Caption> found, final int kind, @Nullable final JSONArray formats,
                                   final String language, final String name, final String label,
                                   @Nullable final String fallback) {
        if (formats == null) {
            return;
        }
        // the menu row and the track are named by this, so never blank or a web address
        final String shown = label == null || label.trim().isEmpty() || label.contains("://") ? name : label;
        // one file first; YouTube lists a piece playlist beside it, TED only that
        for (final boolean pieces : new boolean[]{false, true}) {
            for (final String[] type : new String[][]{{"vtt", MimeTypes.TEXT_VTT},
                    {"srt", MimeTypes.APPLICATION_SUBRIP}, {"ttml", MimeTypes.APPLICATION_TTML}}) {
                if (pieces && !type[0].equals("vtt")) {
                    continue;
                }
                for (int i = 0; i < formats.length(); i++) {
                    final JSONObject format = formats.optJSONObject(i);
                    if (format != null && type[0].equals(format.optString("ext"))
                            && format.optString("protocol", "").startsWith("m3u8") == pieces
                            && !format.optString("url", "").isEmpty()) {
                        final Caption caption = new Caption(kind, language, name, shown,
                                format.optString("url"), type[1], pieces);
                        caption.fallback = pieces ? null : fallback;
                        found.add(caption);
                        return;
                    }
                }
            }
        }
    }

    // the first Settings language with a caption: the uploader's, else the automatic one
    @Nullable
    private static Caption wantedCaption(final Context context, final List<Caption> captions) {
        for (final String language : com.brouken.player.Languages.subtitle(context)) {
            final String wanted = com.brouken.player.Languages.plain(language);
            Caption automatic = null;
            for (final Caption caption : captions) {
                if (!com.brouken.player.Languages.plain(caption.language).equals(wanted)) {
                    continue;
                }
                if (caption.kind == Caption.UPLOADED) {
                    return caption;
                }
                if (automatic == null) {
                    automatic = caption;
                }
            }
            if (automatic != null) {
                return automatic;
            }
        }
        return null;
    }

    // a local copy: mpv waits on a site's subtitle with everything else, and the links expire
    // before a long film ends. Not on the main thread
    @Nullable
    public MediaItem.SubtitleConfiguration fetch(final Context context, final Caption caption) {
        final java.io.File dir = new java.io.File(context.getCacheDir(), "web-subtitles");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        final java.io.File file = new java.io.File(dir, Integer.toHexString(caption.url.hashCode())
                + "-" + caption.language + ending(caption.mimeType));
        final okhttp3.OkHttpClient client = com.brouken.player.online.Http.client().newBuilder()
                .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build();
        // opened again within the lookup's life: the same link, so last time's copy, as YouTube's
        // caption server is quick to refuse
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis());
        } else try {
            byte[] text;
            try {
                text = caption.pieces ? joinPieces(client, caption.url) : get(client, caption.url);
            } catch (java.io.IOException e) {
                // YouTube's caption server often stalls rather than refusing
                android.util.Log.i(com.brouken.player.Utils.TAG, "Caption " + caption.language + " stalled: " + e);
                text = null;
            }
            if (text == null && caption.fallback != null) {
                android.util.Log.i(com.brouken.player.Utils.TAG, "Caption " + caption.language
                        + " from the video servers instead");
                text = joinPieces(client, caption.fallback);
            }
            if (text == null) {
                return null;
            }
            if (MimeTypes.TEXT_VTT.equals(caption.mimeType)) {
                text = withoutPlacement(text);
            }
            try (java.io.OutputStream out = new java.io.FileOutputStream(file)) {
                out.write(text);
            }
        } catch (Exception e) {
            android.util.Log.i(com.brouken.player.Utils.TAG, "Caption " + caption.language + " failed: " + e);
            return null;
        }
        final MediaItem.SubtitleConfiguration subtitle = new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                .setMimeType(caption.mimeType)
                .setLanguage(caption.language)
                .setLabel(caption.label)
                .build();
        subtitles.add(subtitle);
        caption.loaded = true;
        return subtitle;
    }

    @Nullable
    private static byte[] get(final okhttp3.OkHttpClient client, final String url) throws java.io.IOException {
        try (okhttp3.Response response = client.newCall(new okhttp3.Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                android.util.Log.i(com.brouken.player.Utils.TAG, "Caption refused: HTTP " + response.code());
                return null;
            }
            return response.body().bytes();
        }
    }

    // each piece counts from the start of its part of the playlist, so a part after a break
    // (TED's intro) moves on by all that played before it. A line crossing two pieces is in both
    @Nullable
    private static byte[] joinPieces(final okhttp3.OkHttpClient client, final String url) throws Exception {
        final byte[] list = get(client, url);
        if (list == null) {
            return null;
        }
        final java.net.URI base = java.net.URI.create(url);
        final List<String> pieces = new ArrayList<>();
        final List<Long> shifts = new ArrayList<>();
        long playedMs = 0;
        long shiftMs = 0;
        double seconds = 0;
        for (final String raw : new String(list, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            final String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                final int comma = line.indexOf(',');
                seconds = Double.parseDouble(line.substring(8, comma < 0 ? line.length() : comma));
            } else if (line.equals("#EXT-X-DISCONTINUITY")) {
                shiftMs = playedMs;
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                pieces.add(base.resolve(line).toString());
                shifts.add(shiftMs);
                playedMs += Math.round(seconds * 1000);
                seconds = 0;
            }
        }
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            final List<java.util.concurrent.Future<byte[]>> got = new ArrayList<>();
            for (final String piece : pieces) {
                got.add(pool.submit(() -> get(client, piece)));
            }
            final StringBuilder out = new StringBuilder("WEBVTT\n\n");
            final java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < got.size(); i++) {
                final byte[] piece = got.get(i).get();
                if (piece == null) {
                    return null;
                }
                final String text = new String(piece, java.nio.charset.StandardCharsets.UTF_8).replace("\r", "");
                for (final String block : text.split("\n\n")) {
                    final String[] lines = block.trim().split("\n");
                    int timing = 0;
                    while (timing < lines.length && !lines[timing].contains("-->")) {
                        timing++;
                    }
                    // the header, notes and styles have no timing
                    if (timing == lines.length) {
                        continue;
                    }
                    final StringBuilder cue = new StringBuilder(shifted(lines[timing], shifts.get(i)));
                    for (int l = timing + 1; l < lines.length; l++) {
                        cue.append('\n').append(lines[l]);
                    }
                    if (seen.add(cue.toString())) {
                        out.append(cue).append("\n\n");
                    }
                }
            }
            return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            pool.shutdownNow();
        }
    }

    private static final java.util.regex.Pattern CUE_TIME =
            java.util.regex.Pattern.compile("(?:(\\d+):)?(\\d{2}):(\\d{2})\\.(\\d{3})");

    // the settings after a cue's times, as in "align:start position:0%"
    private static final java.util.regex.Pattern CUE_SETTINGS =
            java.util.regex.Pattern.compile("(?m)^(\\S+[ \\t]+-->[ \\t]+\\S+)[ \\t].*$");

    // YouTube starts its automatic lines at the left edge and uploaders put theirs anywhere,
    // so they'd jump about; without the settings they sit where every other subtitle does
    private static byte[] withoutPlacement(final byte[] vtt) {
        final String text = new String(vtt, java.nio.charset.StandardCharsets.UTF_8);
        return CUE_SETTINGS.matcher(text).replaceAll("$1").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String shifted(final String timing, final long shiftMs) {
        if (shiftMs == 0) {
            return timing;
        }
        final java.util.regex.Matcher matcher = CUE_TIME.matcher(timing);
        final StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            final long ms = (matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1))) * 3_600_000
                    + Long.parseLong(matcher.group(2)) * 60_000 + Long.parseLong(matcher.group(3)) * 1000
                    + Long.parseLong(matcher.group(4)) + shiftMs;
            matcher.appendReplacement(out, String.format(Locale.US, "%02d:%02d:%02d.%03d",
                    ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // YouTube's seek pictures: sheets of rows by columns tiles, fps tiles a second of film
    public static final class Storyboard {
        public final List<String> sheets;
        public final int columns;
        public final int rows;
        public final int width;
        public final int height;
        public final double fps;

        Storyboard(final List<String> sheets, final int columns, final int rows, final int width,
                   final int height, final double fps) {
            this.sheets = sheets;
            this.columns = columns;
            this.rows = rows;
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
    }

    // the sharpest of the sizes on offer
    @Nullable
    private static Storyboard storyboard(final JSONArray formats) {
        JSONObject best = null;
        for (int i = 0; i < formats.length(); i++) {
            final JSONObject format = formats.optJSONObject(i);
            if (format == null || !"storyboard".equals(format.optString("format_note"))
                    || format.optJSONArray("fragments") == null || format.optInt("columns") <= 0
                    || format.optInt("rows") <= 0 || format.optInt("width") <= 0
                    || format.optInt("height") <= 0 || format.optDouble("fps", 0) <= 0) {
                continue;
            }
            if (best == null || format.optInt("width") > best.optInt("width")) {
                best = format;
            }
        }
        if (best == null) {
            return null;
        }
        final JSONArray fragments = best.optJSONArray("fragments");
        final List<String> sheets = new ArrayList<>();
        for (int i = 0; i < fragments.length(); i++) {
            final JSONObject fragment = fragments.optJSONObject(i);
            final String url = fragment == null ? "" : fragment.optString("url", "");
            if (url.isEmpty()) {
                return null;
            }
            sheets.add(url);
        }
        return sheets.isEmpty() ? null : new Storyboard(sheets, best.optInt("columns"), best.optInt("rows"),
                best.optInt("width"), best.optInt("height"), best.optDouble("fps"));
    }

    // not ".sub": that names picture subtitles, which Media3 leaves out
    private static String ending(@Nullable final String mimeType) {
        return MimeTypes.APPLICATION_SUBRIP.equals(mimeType) ? ".srt"
                : MimeTypes.APPLICATION_TTML.equals(mimeType) ? ".ttml" : ".vtt";
    }

    private static String base(final String language) {
        final String plain = language.replace("-orig", "");
        final int dash = plain.indexOf('-');
        return dash > 0 ? plain.substring(0, dash) : plain;
    }

    private static String languageName(final String language) {
        final String name = Locale.forLanguageTag(language.replace("-orig", "")).getDisplayName();
        return name.isEmpty() ? language : name;
    }

    // the site's preferred language first (the original, not a dub), then quality
    @Nullable
    private static JSONObject bestAudio(final List<JSONObject> audios) {
        JSONObject best = null;
        for (final JSONObject audio : audios) {
            if (best == null || compareAudio(audio, best) > 0) {
                best = audio;
            }
        }
        return best;
    }

    private static int compareAudio(final JSONObject a, final JSONObject b) {
        final int language = Integer.compare(a.optInt("language_preference", -1),
                b.optInt("language_preference", -1));
        if (language != 0) {
            return language;
        }
        final boolean aDirect = !a.optString("protocol").startsWith("m3u8");
        final boolean bDirect = !b.optString("protocol").startsWith("m3u8");
        if (aDirect != bDirect) {
            return aDirect ? 1 : -1;
        }
        return Double.compare(a.optDouble("abr", 0), b.optDouble("abr", 0));
    }

    private static Map<String, String> headersOf(final JSONObject format) {
        final Map<String, String> headers = new HashMap<>();
        final JSONObject http = format.optJSONObject("http_headers");
        if (http != null) {
            final java.util.Iterator<String> keys = http.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                headers.put(key, http.optString(key));
            }
        }
        // "a=1; Domain=.site.com; Path=/; b=2; ..." from the site's own pages
        final String cookies = format.optString("cookies", "");
        if (!cookies.isEmpty()) {
            final StringBuilder header = new StringBuilder();
            for (final String part : cookies.split(";")) {
                final int equals = part.indexOf('=');
                final String name = (equals < 0 ? part : part.substring(0, equals)).trim();
                if (equals < 0 || name.isEmpty()
                        || COOKIE_ATTRIBUTES.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (header.length() > 0) {
                    header.append("; ");
                }
                header.append(part.trim());
            }
            if (header.length() > 0) {
                headers.put("Cookie", header.toString());
            }
        }
        return headers;
    }

    private static int defaultPick(final Context context, final List<Choice> choices) {
        if (choices.isEmpty()) {
            return 0;
        }
        final String wanted = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_QUALITY, "screen");
        if ("audio".equals(wanted) && choices.get(choices.size() - 1).audioOnly) {
            return choices.size() - 1;
        }
        int limit;
        switch (wanted) {
            case "best":
                limit = Integer.MAX_VALUE;
                break;
            case "screen":
                limit = screenHeight(context);
                break;
            default:
                try {
                    limit = Integer.parseInt(wanted);
                } catch (NumberFormatException e) {
                    limit = screenHeight(context);
                }
                break;
        }
        int lowest = -1;
        for (int i = 0; i < choices.size(); i++) {
            final Choice choice = choices.get(i);
            if (choice.audioOnly) {
                continue;
            }
            if (choice.height <= limit) {
                return i;
            }
            lowest = i;
        }
        return lowest >= 0 ? lowest : 0;
    }

    // the short side of the screen's largest mode: 2160 on a 4K TV, 1080 on most phones
    private static int screenHeight(final Context context) {
        final android.view.Display display = ((android.hardware.display.DisplayManager)
                context.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(android.view.Display.DEFAULT_DISPLAY);
        int best = 0;
        if (display != null) {
            for (final android.view.Display.Mode mode : display.getSupportedModes()) {
                best = Math.max(best, Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight()));
            }
        }
        return best > 0 ? best : 1080;
    }

    public MediaItem.Builder item() {
        final Choice choice = current();
        return new MediaItem.Builder().setUri(choice.url).setMimeType(choice.mimeType).setTag(choice);
    }

    public MediaSource.Factory sources(final Context context, final ExtractorsFactory extractors,
                                       final SubtitleParser.Factory subtitles) {
        final DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true);
        for (final Map.Entry<String, String> header : headers.entrySet()) {
            if ("User-Agent".equalsIgnoreCase(header.getKey())) {
                http.setUserAgent(header.getValue());
            }
        }
        http.setDefaultRequestProperties(headers);
        final DataSource.Factory network = pieceBytes > 0 ? new RangedDataSource.Factory(http, pieceBytes) : http;
        final DefaultMediaSourceFactory inner = new DefaultMediaSourceFactory(
                new DefaultDataSource.Factory(context, network), extractors)
                .setSubtitleParserFactory(subtitles);
        return new Paired(inner);
    }

    // a separate audio stream played beside the picture, kept through item rebuilds by the tag
    private static final class Paired implements MediaSource.Factory {
        private final DefaultMediaSourceFactory inner;

        Paired(final DefaultMediaSourceFactory inner) {
            this.inner = inner;
        }

        @NonNull
        @Override
        public MediaSource.Factory setDrmSessionManagerProvider(@NonNull final DrmSessionManagerProvider provider) {
            inner.setDrmSessionManagerProvider(provider);
            return this;
        }

        @NonNull
        @Override
        public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull final LoadErrorHandlingPolicy policy) {
            inner.setLoadErrorHandlingPolicy(policy);
            return this;
        }

        @NonNull
        @Override
        public @C.ContentType int[] getSupportedTypes() {
            return inner.getSupportedTypes();
        }

        @NonNull
        @Override
        public MediaSource createMediaSource(@NonNull final MediaItem item) {
            final MediaSource main = inner.createMediaSource(item);
            final Object tag = item.localConfiguration == null ? null : item.localConfiguration.tag;
            if (tag instanceof Choice && ((Choice) tag).audioUrl != null) {
                final MediaSource audio = inner.createMediaSource(
                        new MediaItem.Builder().setUri(((Choice) tag).audioUrl).build());
                return new MergingMediaSource(main, audio);
            }
            return main;
        }
    }
}
