package com.brouken.player.subtitle.parser;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.brouken.player.Languages;
import com.brouken.player.SubtitleFiles;
import com.brouken.player.SubtitleNames;
import com.brouken.player.SubtitleUtils;
import com.brouken.player.Utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// TubeMate saves a YouTube video's captions as one SAMI file with every language in it. Each
// language becomes its own SRT file, so both engines list them as separate subtitles
public final class SamiSplit {

    private static final int MAX_BYTES = 20 * 1024 * 1024;

    public static final class Part {
        @NonNull
        public final Uri uri;
        @NonNull
        public final String language;
        @NonNull
        public final String name;

        Part(@NonNull final Uri uri, @NonNull final String language, @NonNull final String name) {
            this.uri = uri;
            this.language = language;
            this.name = name;
        }
    }

    private static final Map<String, List<Part>> known = new HashMap<>();

    private SamiSplit() {
    }

    // empty when the file is not SAMI or holds one language, which plays as it is
    @NonNull
    public static synchronized List<Part> parts(@NonNull final Context context, @NonNull final Uri source) {
        final List<Part> kept = known.get(source.toString());
        if (kept != null && allThere(kept)) {
            return kept;
        }
        List<Part> parts = Collections.emptyList();
        if (SamiParser.MIME.equals(SubtitleUtils.getSubtitleMime(context, source))) {
            try {
                parts = split(context, source);
            } catch (Exception | OutOfMemoryError e) {
                Utils.log("SAMI not split: " + e);
            }
        }
        known.put(source.toString(), parts);
        return parts;
    }

    @Nullable
    public static synchronized Part partOf(@Nullable final Uri uri) {
        if (uri == null) {
            return null;
        }
        for (final List<Part> parts : known.values()) {
            for (final Part part : parts) {
                if (part.uri.equals(uri)) {
                    return part;
                }
            }
        }
        return null;
    }

    // the first of the Settings subtitle languages the file has, else its first language
    @Nullable
    public static Part preferred(@NonNull final List<Part> parts, @Nullable final String[] wanted) {
        if (parts.isEmpty()) {
            return null;
        }
        if (wanted != null) {
            for (final String language : wanted) {
                if (language == null || language.isEmpty()) {
                    continue;
                }
                final String plain = Languages.plain(language);
                for (final Part part : parts) {
                    if (plain.equals(Languages.plain(part.language))) {
                        return part;
                    }
                }
            }
        }
        return parts.get(0);
    }

    private static boolean allThere(final List<Part> parts) {
        for (final Part part : parts) {
            final String path = part.uri.getPath();
            if (path == null || !new File(path).exists()) {
                return false;
            }
        }
        return true;
    }

    private static List<Part> split(final Context context, final Uri source) throws Exception {
        final String text;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) {
                return Collections.emptyList();
            }
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > MAX_BYTES) {
                    return Collections.emptyList();
                }
            }
            text = TextCues.decode(bytes.toByteArray(), 0, bytes.size());
        }
        final Map<String, String> languages = SamiParser.languages(text);
        if (languages.size() < 2) {
            return Collections.emptyList();
        }
        String base = SubtitleNames.withoutExtension(Utils.getFileName(context, source, true));
        if (base == null || base.isEmpty()) {
            base = "subtitle";
        }
        final List<Part> parts = new ArrayList<>();
        for (final Map.Entry<String, String> entry : languages.entrySet()) {
            final List<TextCues.Line> lines = oneAtATime(SamiParser.parse(text, entry.getKey()));
            if (lines.isEmpty()) {
                continue;
            }
            final String language = entry.getValue();
            final File file = SubtitleFiles.fileFor(context, source,
                    base + "." + language.toLowerCase(Locale.ROOT) + ".srt");
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(srt(lines).getBytes(StandardCharsets.UTF_8));
            }
            final String name = SubtitleNames.languageName(language);
            parts.add(new Part(Uri.fromFile(file), language, name != null ? name : language));
        }
        return parts;
    }

    // YouTube's lines overlap, the next starting while the last is still up; each now leaves
    // when the next comes, so one line shows at a time
    static List<TextCues.Line> oneAtATime(final List<TextCues.Line> lines) {
        final List<TextCues.Line> sorted = new ArrayList<>(lines);
        Collections.sort(sorted, (a, b) -> Long.compare(a.startUs, b.startUs));
        final List<TextCues.Line> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            final TextCues.Line line = sorted.get(i);
            long endUs = line.endUs;
            if (i + 1 < sorted.size() && sorted.get(i + 1).startUs > line.startUs) {
                endUs = Math.min(endUs, sorted.get(i + 1).startUs);
            }
            if (endUs > line.startUs) {
                out.add(new TextCues.Line(line.startUs, endUs, line.text));
            }
        }
        return out;
    }

    static String srt(final List<TextCues.Line> lines) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            final TextCues.Line line = lines.get(i);
            out.append(i + 1).append('\n')
                    .append(time(line.startUs)).append(" --> ").append(time(line.endUs)).append('\n')
                    .append(line.text).append("\n\n");
        }
        return out.toString();
    }

    private static String time(final long us) {
        final long ms = us / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
                ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }
}
