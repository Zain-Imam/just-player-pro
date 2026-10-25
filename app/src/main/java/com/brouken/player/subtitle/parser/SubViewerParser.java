package com.brouken.player.subtitle.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.util.Consumer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// shares .sub with MicroDVD and VobSub, so it is told apart by its content
public final class SubViewerParser implements SubtitleParser {

    private static final Pattern TIME_LINE = Pattern.compile(
            "^(\\d{1,2}):(\\d{2}):(\\d{2})[.,:](\\d{1,3})\\s*,\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,:](\\d{1,3})$");
    private static final Pattern BR = Pattern.compile("\\[br\\]", Pattern.CASE_INSENSITIVE);
    // enough to get past the [INFORMATION] header to the first cue
    private static final int SNIFF_BYTES = 8192;

    @Nullable
    private final SubtitleParser fallbackParser;

    public SubViewerParser(@Nullable SubtitleParser fallbackParser) {
        this.fallbackParser = fallbackParser;
    }

    @Override
    public void parse(@NonNull byte[] data, @NonNull OutputOptions outputOptions, @NonNull Consumer<CuesWithTiming> output) {
        parse(data, 0, data.length, outputOptions, output);
    }

    @Override
    public void parse(@NonNull byte[] data, int offset, int length, @NonNull OutputOptions outputOptions, @NonNull Consumer<CuesWithTiming> output) {
        if (fallbackParser != null
                && !looksLikeSubViewer(TextCues.decode(data, offset, Math.min(length, SNIFF_BYTES)))) {
            fallbackParser.parse(data, offset, length, outputOptions, output);
            return;
        }
        TextCues.output(parse(TextCues.decode(data, offset, length)), outputOptions, output);
    }

    @Override
    public void reset() {
        if (fallbackParser != null) {
            fallbackParser.reset();
        }
    }

    @Override
    public int getCueReplacementBehavior() {
        return fallbackParser != null
                ? fallbackParser.getCueReplacementBehavior()
                : Format.CUE_REPLACEMENT_BEHAVIOR_MERGE;
    }

    // the first line that is not blank or a [TAG] has to be a time line
    static boolean looksLikeSubViewer(@NonNull String head) {
        for (String raw : head.split("\r\n|\r|\n")) {
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("[")) continue;
            return TIME_LINE.matcher(line).matches();
        }
        return false;
    }

    @NonNull
    static List<TextCues.Line> parse(@NonNull String source) {
        final List<TextCues.Line> lines = new ArrayList<>();
        boolean inInformation = false;
        long startUs = 0;
        long endUs = 0;
        StringBuilder text = null;
        for (String raw : source.split("\r\n|\r|\n")) {
            final String line = raw.trim();
            final String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("[INFORMATION]")) {
                inInformation = true;
                continue;
            }
            if (inInformation) {
                if (upper.startsWith("[END INFORMATION]")) {
                    inInformation = false;
                }
                continue;
            }
            final Matcher time = TIME_LINE.matcher(line);
            if (time.matches()) {
                add(lines, startUs, endUs, text);
                startUs = timeUs(time, 1);
                endUs = timeUs(time, 5);
                text = new StringBuilder();
                continue;
            }
            // [SUBTITLE], [COLF] and the like sit before the first cue
            if (text == null) continue;
            if (line.isEmpty()) {
                add(lines, startUs, endUs, text);
                text = null;
                continue;
            }
            if (text.length() > 0) text.append('\n');
            text.append(BR.matcher(line).replaceAll("\n"));
        }
        add(lines, startUs, endUs, text);
        return lines;
    }

    private static void add(List<TextCues.Line> lines, long startUs, long endUs, @Nullable StringBuilder text) {
        if (text == null) return;
        final StringBuilder tidy = new StringBuilder();
        for (String line : text.toString().split("\n")) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (tidy.length() > 0) tidy.append('\n');
            tidy.append(trimmed);
        }
        if (tidy.length() > 0) {
            lines.add(new TextCues.Line(startUs, endUs, tidy.toString()));
        }
    }

    // the fraction is usually hundredths, sometimes tenths or thousandths
    private static long timeUs(Matcher matcher, int group) {
        final long hours = Long.parseLong(matcher.group(group));
        final long minutes = Long.parseLong(matcher.group(group + 1));
        final long seconds = Long.parseLong(matcher.group(group + 2));
        final String fraction = matcher.group(group + 3);
        long ms = Long.parseLong(fraction);
        if (fraction.length() == 1) {
            ms *= 100;
        } else if (fraction.length() == 2) {
            ms *= 10;
        }
        return ((hours * 60 + minutes) * 60 + seconds) * 1_000_000L + ms * 1000L;
    }
}
