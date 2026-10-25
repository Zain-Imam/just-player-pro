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

// SAMI (.smi, .sami): <SYNC Start=ms> blocks, each holding <P Class=..> text per language
public final class SamiParser implements SubtitleParser {

    public static final String MIME = "text/x-sami";

    // a cue with nothing after it; files normally close with an empty SYNC
    private static final long LAST_CUE_MS = 4_000;

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern SYNC = Pattern.compile(
            "<sync\\b[^>]*?\\bstart\\s*=\\s*[\"']?(-?\\d+)[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern END = Pattern.compile("</body\\s*>|</sami\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_TAG = Pattern.compile("<p\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASS = Pattern.compile(
            "\\bclass\\s*=\\s*[\"']?([^\"'\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    // the semicolon is often missing (&nbsp); unknown names are left as written
    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+);?");

    @Override
    public void parse(@NonNull byte[] data, @NonNull OutputOptions outputOptions, @NonNull Consumer<CuesWithTiming> output) {
        parse(data, 0, data.length, outputOptions, output);
    }

    @Override
    public void parse(@NonNull byte[] data, int offset, int length, @NonNull OutputOptions outputOptions, @NonNull Consumer<CuesWithTiming> output) {
        TextCues.output(parse(TextCues.decode(data, offset, length)), outputOptions, output);
    }

    @Override
    public void reset() {
    }

    @Override
    public int getCueReplacementBehavior() {
        return Format.CUE_REPLACEMENT_BEHAVIOR_MERGE;
    }

    private static final class Block {
        final long startMs;
        // false when the block only holds other languages, which leave our cue alone
        boolean ours;
        final StringBuilder text = new StringBuilder();

        Block(long startMs) {
            this.startMs = Math.max(startMs, 0);
        }
    }

    @NonNull
    static List<TextCues.Line> parse(@NonNull String source) {
        final String text = COMMENT.matcher(source).replaceAll("");

        final List<Long> starts = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final Matcher sync = SYNC.matcher(text);
        int bodyStart = -1;
        while (sync.find()) {
            if (bodyStart >= 0) {
                bodies.add(text.substring(bodyStart, sync.start()));
            }
            starts.add(parseLong(sync.group(1)));
            bodyStart = sync.end();
        }
        if (bodyStart >= 0) {
            String tail = text.substring(bodyStart);
            final Matcher end = END.matcher(tail);
            if (end.find()) {
                tail = tail.substring(0, end.start());
            }
            bodies.add(tail);
        }

        // several languages: the first class used in the body wins
        String chosen = null;
        for (String body : bodies) {
            final Matcher p = P_TAG.matcher(body);
            while (chosen == null && p.find()) {
                chosen = classOf(p.group(1));
            }
            if (chosen != null) break;
        }

        final List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            blocks.add(readBlock(starts.get(i), bodies.get(i), chosen));
        }

        final List<TextCues.Line> lines = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            final Block block = blocks.get(i);
            if (!block.ours || block.text.length() == 0) continue;
            long endMs = block.startMs + LAST_CUE_MS;
            for (int j = i + 1; j < blocks.size(); j++) {
                final Block next = blocks.get(j);
                if (next.ours && next.startMs > block.startMs) {
                    endMs = next.startMs;
                    break;
                }
            }
            lines.add(new TextCues.Line(block.startMs * 1000, endMs * 1000, block.text.toString()));
        }
        return lines;
    }

    private static Block readBlock(long startMs, String body, @Nullable String chosen) {
        final Block block = new Block(startMs);
        final List<int[]> spans = new ArrayList<>();
        final List<String> classes = new ArrayList<>();
        final Matcher p = P_TAG.matcher(body);
        while (p.find()) {
            spans.add(new int[]{p.start(), p.end()});
            classes.add(classOf(p.group(1)));
        }
        if (spans.isEmpty()) {
            block.ours = true;
            block.text.append(clean(body));
            return block;
        }
        for (int i = 0; i < spans.size(); i++) {
            final String paragraphClass = classes.get(i);
            if (paragraphClass != null && chosen != null && !paragraphClass.equalsIgnoreCase(chosen)) {
                continue;
            }
            block.ours = true;
            final int to = i + 1 < spans.size() ? spans.get(i + 1)[0] : body.length();
            final String cleaned = clean(body.substring(spans.get(i)[1], to));
            if (!cleaned.isEmpty()) {
                if (block.text.length() > 0) block.text.append('\n');
                block.text.append(cleaned);
            }
        }
        return block;
    }

    @Nullable
    private static String classOf(String attributes) {
        final Matcher matcher = CLASS.matcher(attributes);
        return matcher.find() ? matcher.group(1) : null;
    }

    // raw line breaks are only spacing in SAMI; <br> is the real one
    static String clean(String raw) {
        String text = raw.replaceAll("\\s+", " ");
        text = BR.matcher(text).replaceAll("\n");
        text = TAG.matcher(text).replaceAll("");
        text = decodeEntities(text);
        final StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            final String tidy = line.replace('\u00A0', ' ').replaceAll(" {2,}", " ").trim();
            if (tidy.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(tidy);
        }
        return out.toString();
    }

    static String decodeEntities(String text) {
        if (text.indexOf('&') < 0) return text;
        final Matcher matcher = ENTITY.matcher(text);
        final StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            final String replacement = entity(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @Nullable
    private static String entity(String name) {
        if (name.startsWith("#")) {
            try {
                final int code = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                        ? Integer.parseInt(name.substring(2), 16)
                        : Integer.parseInt(name.substring(1));
                return Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "nbsp":
                return "\u00A0";
            case "amp":
                return "&";
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "quot":
                return "\"";
            case "apos":
                return "'";
            default:
                return null;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
