package com.brouken.player.subtitle.parser;

import androidx.annotation.NonNull;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class EnhancedSubtitleParserFactory implements SubtitleParser.Factory {

    private volatile double fallbackFrameRate;
    private final DefaultSubtitleParserFactory defaultFactory = new DefaultSubtitleParserFactory();
    private final List<WeakReference<MicroDvdParser>> microDvdParsers = new CopyOnWriteArrayList<>();

    public EnhancedSubtitleParserFactory(double fallbackFrameRate) {
        this.fallbackFrameRate = fallbackFrameRate;
    }

    @Override
    public boolean supportsFormat(@NonNull Format format) {
        return SamiParser.MIME.equals(format.sampleMimeType) || defaultFactory.supportsFormat(format);
    }

    @Override
    public int getCueReplacementBehavior(@NonNull Format format) {
        if (SamiParser.MIME.equals(format.sampleMimeType)) {
            return Format.CUE_REPLACEMENT_BEHAVIOR_MERGE;
        }
        return defaultFactory.getCueReplacementBehavior(format);
    }

    @NonNull
    @Override
    public SubtitleParser create(@NonNull Format format) {
        SubtitleParser fallbackParser;
        String mimeType = format.sampleMimeType != null ? format.sampleMimeType : "";
        switch (mimeType) {
            case SamiParser.MIME:
                return new SamiParser();
            case MimeTypes.APPLICATION_SUBRIP:
                // .sub and .txt come here as well, and may be SubViewer
                fallbackParser = new SubViewerParser(new EnhancedSubripParser());
                break;
            default:
                fallbackParser = defaultFactory.create(format);
                break;
        }
        final MicroDvdParser microDvdParser = new MicroDvdParser(format, fallbackParser, fallbackFrameRate);
        pruneStaleParsers();
        microDvdParsers.add(new WeakReference<>(microDvdParser));
        return microDvdParser;
    }

    public boolean setFallbackFrameRate(double newFallbackFrameRate) {
        if (newFallbackFrameRate == fallbackFrameRate) return false;
        fallbackFrameRate = newFallbackFrameRate;
        pruneStaleParsers();

        for (WeakReference<MicroDvdParser> microDvdParser : microDvdParsers) {
            MicroDvdParser parser = microDvdParser.get();
            if (parser != null && parser.isSubtitleReloadNeeded(newFallbackFrameRate)) {
                return true;
            }
        }

        return false;
    }

    private void pruneStaleParsers() {
        microDvdParsers.removeIf(microDvdParserWeakReference -> microDvdParserWeakReference.get() == null);
    }

}
