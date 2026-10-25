package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import org.junit.Test;

public class TrackNamesTest {

    private static Format format(final String mime, final String codecs) {
        return new Format.Builder().setSampleMimeType(mime).setCodecs(codecs).build();
    }

    @Test
    public void soundCodecsByTypeAndProfile() {
        assertEquals("E-AC-3 Atmos", TrackNames.codec(format(MimeTypes.AUDIO_E_AC3_JOC, null)));
        assertEquals("E-AC-3 Atmos", TrackNames.codec(format(MimeTypes.AUDIO_E_AC3, "Dolby Digital Plus + Dolby Atmos")));
        assertEquals("HE-AAC", TrackNames.codec(format(MimeTypes.AUDIO_AAC, "mp4a.40.5")));
        assertEquals("HE-AACv2", TrackNames.codec(format(MimeTypes.AUDIO_AAC, "mp4a.40.29")));
        assertEquals("AAC", TrackNames.codec(format(MimeTypes.AUDIO_AAC, "mp4a.40.2")));
        assertEquals("DTS-HD MA", TrackNames.codec(format(MimeTypes.AUDIO_DTS_HD, "DTS-HD MA")));
        assertEquals("DTS:X", TrackNames.codec(format(MimeTypes.AUDIO_DTS_X, null)));
        assertEquals("TrueHD Atmos", TrackNames.codec(format(MimeTypes.AUDIO_TRUEHD, "TrueHD + Atmos")));
        assertEquals("PCM", TrackNames.codec(format(MimeTypes.AUDIO_RAW, null)));
    }

    @Test
    public void codecsMedia3HasNoTypeFor() {
        assertEquals("PCM", TrackNames.codec(format(MimeTypes.AUDIO_UNKNOWN, "pcm_s24le")));
        assertEquals("WMA", TrackNames.codec(format(MimeTypes.AUDIO_UNKNOWN, "wmapro")));
        assertEquals("ProRes", TrackNames.codec(format(MimeTypes.VIDEO_UNKNOWN, "prores")));
        assertNull(TrackNames.codec(format(MimeTypes.AUDIO_UNKNOWN, null)));
    }

    @Test
    public void dolbyVisionNamesThePictureUnderIt() {
        final Format dv = format(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06");
        assertEquals("HEVC", TrackNames.codec(dv));
        assertEquals("Dolby Vision", TrackNames.dynamicRange(dv));
        assertEquals("AV1", TrackNames.codec(format(MimeTypes.VIDEO_DOLBY_VISION, "dav1.10.06")));
        // mpv: HEVC with a Dolby Vision codec string
        assertEquals("Dolby Vision", TrackNames.dynamicRange(format(MimeTypes.VIDEO_H265, "dvhe.07")));
    }

    @Test
    public void hdrFromThePicturesTransfer() {
        final Format hdr10 = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
                .setColorInfo(new ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()).build();
        assertEquals("HDR10", TrackNames.dynamicRange(hdr10));
        final Format hlg = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
                .setColorInfo(new ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_HLG).build()).build();
        assertEquals("HLG", TrackNames.dynamicRange(hlg));
        assertNull(TrackNames.dynamicRange(format(MimeTypes.VIDEO_H264, "avc1.640028")));
    }

    @Test
    public void hdrFromTheDecoder() {
        assertEquals("HDR10", TrackNames.dynamicRange(6));
        assertEquals("HLG", TrackNames.dynamicRange(7));
        assertNull(TrackNames.dynamicRange(3));
        assertNull(TrackNames.dynamicRange(0));
    }

    @Test
    public void hardOfHearingByFlagOrName() {
        assertEquals(true, TrackNames.hardOfHearing(new Format.Builder().setLabel("English SDH").build()));
        assertEquals(true, TrackNames.hardOfHearing(new Format.Builder().setLabel("English [CC]").build()));
        assertEquals(true, TrackNames.hardOfHearing(new Format.Builder().setLabel("English")
                .setRoleFlags(C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND).build()));
        assertEquals(false, TrackNames.hardOfHearing(new Format.Builder().setLabel("English").build()));
        assertEquals(false, TrackNames.hardOfHearing(new Format.Builder().setLabel("Chinese").build()));
    }

    @Test
    public void channelsAndFrameRatesAsPlayersWriteThem() {
        assertEquals("Mono", TrackNames.channelLayout(1));
        assertEquals("2.0", TrackNames.channelLayout(2));
        assertEquals("5.1", TrackNames.channelLayout(6));
        assertEquals("7.1", TrackNames.channelLayout(8));
        assertEquals("4ch", TrackNames.channelLayout(4));
        assertNull(TrackNames.channelLayout(0));
        assertEquals("23.976 fps", TrackNames.frameRate(23.976f));
        assertEquals("24 fps", TrackNames.frameRate(24f));
        assertEquals("29.97 fps", TrackNames.frameRate(29.97f));
    }
}
