package com.brouken.player.subtitle;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.mkv.WebmVttMatroskaExtractor;
import androidx.media3.extractor.text.SubtitleParser;

import java.util.List;
import java.util.Map;

// Media3's Matroska extractor drops WebM-style WebVTT, as FFmpeg writes it in mkv
public final class WebmVttExtractorsFactory implements ExtractorsFactory {

    private final DefaultExtractorsFactory delegate;
    private SubtitleParser.Factory subtitleParserFactory;
    // DefaultExtractorsFactory's default
    private boolean textTrackTranscodingEnabled = true;

    public WebmVttExtractorsFactory(@NonNull DefaultExtractorsFactory delegate,
                                    @NonNull SubtitleParser.Factory subtitleParserFactory) {
        this.delegate = delegate;
        this.subtitleParserFactory = subtitleParserFactory;
        delegate.setSubtitleParserFactory(subtitleParserFactory);
    }

    @NonNull
    @Override
    @SuppressWarnings("deprecation")
    public synchronized ExtractorsFactory experimentalSetTextTrackTranscodingEnabled(boolean enabled) {
        textTrackTranscodingEnabled = enabled;
        delegate.experimentalSetTextTrackTranscodingEnabled(enabled);
        return this;
    }

    @NonNull
    @Override
    public synchronized ExtractorsFactory setSubtitleParserFactory(@NonNull SubtitleParser.Factory factory) {
        subtitleParserFactory = factory;
        delegate.setSubtitleParserFactory(factory);
        return this;
    }

    @NonNull
    @Override
    public synchronized ExtractorsFactory experimentalSetCodecsToParseWithinGopSampleDependencies(int codecs) {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs);
        return this;
    }

    @NonNull
    @Override
    public synchronized Extractor[] createExtractors() {
        return swap(delegate.createExtractors());
    }

    @NonNull
    @Override
    public synchronized Extractor[] createExtractors(@NonNull Uri uri,
                                                    @NonNull Map<String, List<String>> responseHeaders) {
        return swap(delegate.createExtractors(uri, responseHeaders));
    }

    private Extractor[] swap(Extractor[] extractors) {
        for (int i = 0; i < extractors.length; i++) {
            if (extractors[i] instanceof MatroskaExtractor) {
                extractors[i] = new WebmVttMatroskaExtractor(subtitleParserFactory,
                        textTrackTranscodingEnabled ? 0 : WebmVttMatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA);
            }
        }
        return extractors;
    }
}
