package com.brouken.player;

import android.content.res.Resources;

import androidx.media3.common.Format;
import androidx.media3.ui.DefaultTrackNameProvider;

class CustomDefaultTrackNameProvider extends DefaultTrackNameProvider {
    public CustomDefaultTrackNameProvider(Resources resources) {
        super(resources);
    }

    @Override
    public String getTrackName(Format format) {
        String trackName = super.getTrackName(format);
        if (format.sampleMimeType != null) {
            // same table as the track pickers, so codecs read the same everywhere
            String sampleFormat = TrackNames.fromMime(format.sampleMimeType);
            if (sampleFormat == null) {
                sampleFormat = TrackNames.fromMime(format.codecs);
            }
            if (sampleFormat == null) {
                sampleFormat = format.sampleMimeType;
            }
            trackName += " (" + sampleFormat + ")";
        }
        if (format.label != null) {
            if (!trackName.startsWith(format.label)) { // HACK
                trackName += " - " + format.label;
            }
        }
        return trackName;
    }
}
