package com.brouken.player.render;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Every line of each added subtitle file, read whole with the player's own parser. Media3 keeps
// none from before where playback started, which a longer subtitle delay needs.
@UnstableApi
public final class SideloadedCues {

    private final Context context;
    private final SubtitleParser.Factory parsers;
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final Map<Uri, List<CuesWithTiming>> parsed = new ConcurrentHashMap<>();
    private final Map<Uri, Boolean> started = new ConcurrentHashMap<>();
    // the item's subtitles in its order, which is how Media3 numbers their tracks
    private volatile List<MediaItem.SubtitleConfiguration> current = Collections.emptyList();

    public SideloadedCues(final Context context, final SubtitleParser.Factory parsers) {
        this.context = context.getApplicationContext();
        this.parsers = parsers;
    }

    public void use(final List<MediaItem.SubtitleConfiguration> subtitles) {
        current = new ArrayList<>(subtitles);
        for (final MediaItem.SubtitleConfiguration subtitle : subtitles) {
            read(subtitle);
        }
    }

    public void release() {
        reader.shutdownNow();
    }

    // a track of the i-th added file has the id "i+1:", or "0:i+1:" inside a site's paired audio
    @Nullable
    List<CuesWithTiming> cuesFor(@Nullable final String formatId) {
        if (formatId == null || !formatId.endsWith(":")) {
            return null;
        }
        final String[] parts = formatId.split(":");
        final int number;
        try {
            number = Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return null;
        }
        final List<MediaItem.SubtitleConfiguration> subtitles = current;
        if (number < 1 || number > subtitles.size()) {
            return null;
        }
        return parsed.get(subtitles.get(number - 1).uri);
    }

    private void read(final MediaItem.SubtitleConfiguration subtitle) {
        if (subtitle.mimeType == null || started.put(subtitle.uri, true) != null) {
            return;
        }
        // a subtitle link is fetched by Media3 alone: many are single-use or rate-limited, and a
        // second download here made Media3's own fail
        final String scheme = subtitle.uri.getScheme() == null ? "" : subtitle.uri.getScheme();
        if (scheme.startsWith("http")) {
            return;
        }
        final Format format = new Format.Builder()
                .setSampleMimeType(subtitle.mimeType)
                .setLanguage(subtitle.language)
                .setLabel(subtitle.label)
                .build();
        if (!parsers.supportsFormat(format)) {
            return;
        }
        reader.execute(() -> {
            final DataSource source = new DefaultDataSource.Factory(context,
                    new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
                    .createDataSource();
            try {
                final long length = source.open(new DataSpec(subtitle.uri));
                // no subtitle file is this big; it is something else under a subtitle's name
                if (length > MAX_BYTES) {
                    return;
                }
                final byte[] bytes = DataSourceUtil.readToEnd(source);
                final List<CuesWithTiming> cues = new ArrayList<>();
                parsers.create(format).parse(bytes, SubtitleParser.OutputOptions.allCues(), cues::add);
                parsed.put(subtitle.uri, cues);
            } catch (Exception | OutOfMemoryError e) {
                // the delay then works only on the lines Media3 itself has read
                android.util.Log.i("JustPlayer", "Subtitle not read whole: " + e);
            } finally {
                DataSourceUtil.closeQuietly(source);
            }
        });
    }
}
