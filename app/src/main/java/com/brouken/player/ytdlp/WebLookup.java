package com.brouken.player.ytdlp;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.brouken.player.online.Http;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Request;
import okhttp3.Response;

// before a website link plays: a page goes to yt-dlp, anything else plays as it always did
public final class WebLookup {

    public interface Done {
        // both null: not a page, play the link itself
        void onDone(@Nullable WebVideo video, @Nullable WebVideo.Failure failure);
    }

    private static final List<String> MEDIA_ENDINGS = Arrays.asList(
            "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mts", "flv", "wmv", "mpg",
            "mpeg", "3gp", "ogv", "m3u8", "m3u", "mpd", "mp3", "m4a", "aac", "flac", "ogg", "opus",
            "wav", "wma", "mka", "srt", "ass", "vtt", "iso");

    public final Uri uri;
    private final AtomicReference<Process> process = new AtomicReference<>();
    private final Thread thread;
    private volatile boolean cancelled;

    // links from other apps' launches carry their own headers and are direct streams
    public static boolean applies(final Context context, @Nullable final Uri uri,
                                  final Map<String, String> launchHeaders, @Nullable final String mediaType,
                                  final boolean fromApp) {
        if (uri == null || !launchHeaders.isEmpty()) {
            return false;
        }
        // a stream another app hands over, or one said to be a video, plays as it did before
        // websites; a YouTube link is always the site's
        if (!WebVideo.isYouTube(uri) && (fromApp || isMedia(mediaType))) {
            return false;
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return false;
        }
        final String path = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        final int dot = path.lastIndexOf('.');
        return dot < 0 || !MEDIA_ENDINGS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public WebLookup(final Context context, final Uri uri, final Done done) {
        this.uri = uri;
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        thread = new Thread(() -> {
            WebVideo video = null;
            WebVideo.Failure failure = null;
            if (WebVideo.isYouTube(uri) || isVideoSite(uri) || isPage(uri)) {
                try {
                    video = WebVideo.resolve(app, uri, process);
                } catch (WebVideo.Failure e) {
                    failure = e;
                }
            }
            final WebVideo found = video;
            final WebVideo.Failure failed = failure;
            main.post(() -> {
                if (!cancelled) {
                    done.onDone(found, failed);
                }
            });
            // after the lookup, so it never slows one down; the next link gets the update
            if (found != null || failed != null) {
                YtDlp.checkNow(app);
            }
        }, "web lookup");
        thread.start();
    }

    public void cancel() {
        cancelled = true;
        thread.interrupt();
        final Process running = process.get();
        if (running != null) {
            running.destroy();
        }
    }

    private static boolean isMedia(@Nullable final String type) {
        if (type == null) {
            return false;
        }
        final String lower = type.toLowerCase(Locale.ROOT);
        return lower.startsWith("video/") || lower.startsWith("audio/") || lower.contains("mpegurl")
                || lower.contains("dash+xml");
    }

    // big video sites go straight to yt-dlp: some answer the page check with an error or a
    // login page, and the link was then tried as a video file
    private static final List<String> VIDEO_SITES = Arrays.asList(
            "facebook.com", "fb.watch", "fb.com", "instagram.com", "threads.net", "tiktok.com",
            "x.com", "twitter.com", "vimeo.com", "dailymotion.com", "dai.ly", "twitch.tv",
            "reddit.com", "redd.it", "soundcloud.com", "bandcamp.com", "bilibili.com", "ted.com",
            "streamable.com", "rumble.com", "odysee.com", "ok.ru", "vk.com", "vkvideo.ru");

    static boolean isVideoSite(final Uri uri) {
        final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        for (final String site : VIDEO_SITES) {
            if (host.equals(site) || host.endsWith("." + site)) {
                return true;
            }
        }
        return false;
    }

    // HEAD, so a link that works only once is not used up; when the server cannot say, the link
    // plays as before. A link OkHttp refuses (an odd port, a Kodi-style |header) plays as before too
    private static boolean isPage(final Uri uri) {
        final Request request;
        try {
            request = new Request.Builder().url(uri.toString()).head().build();
        } catch (IllegalArgumentException e) {
            return false;
        }
        try (Response response = Http.client().newBuilder()
                .callTimeout(10, TimeUnit.SECONDS).build().newCall(request).execute()) {
            // an error page (a server refusing HEAD) is not the video's page
            final String type = response.header("Content-Type", "");
            return response.isSuccessful() && type != null
                    && (type.startsWith("text/html") || type.startsWith("application/xhtml"));
        } catch (Exception e) {
            return false;
        }
    }
}
