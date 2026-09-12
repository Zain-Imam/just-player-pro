package com.brouken.player.mpv;

import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;

import java.util.Locale;

/*
 * FFmpeg's name for a codec, translated into the one Media3 uses.
 *
 * The track picker prints a friendly name for a codec by looking up its mime
 * type, which is fine for the engine that reports one. mpv reports "eac3" and
 * "hdmv_pgs_subtitle" instead, so the same track showed its codec on one engine
 * and nothing on the other. Mapping the names across means one picker, one set
 * of labels, either engine.
 *
 * Anything not listed falls back to the raw name, which is still more than the
 * nothing that was shown before.
 */
final class MpvCodecs {

    private MpvCodecs() {
    }

    @Nullable
    static String mimeFor(final String type, @Nullable final String codec) {
        if (codec == null || codec.isEmpty()) {
            return null;
        }
        switch (codec.toLowerCase(Locale.US)) {
            // -- audio ------------------------------------------------------
            case "aac":
            case "aac_latm":
                return MimeTypes.AUDIO_AAC;
            case "ac3":
                return MimeTypes.AUDIO_AC3;
            case "eac3":
                return MimeTypes.AUDIO_E_AC3;
            case "ac4":
                return MimeTypes.AUDIO_AC4;
            case "dts":
                return MimeTypes.AUDIO_DTS;
            case "dtshd":
            case "dts_hd":
                return MimeTypes.AUDIO_DTS_HD;
            case "truehd":
                return MimeTypes.AUDIO_TRUEHD;
            case "mp3":
                return MimeTypes.AUDIO_MPEG;
            case "mp2":
                return MimeTypes.AUDIO_MPEG_L2;
            case "vorbis":
                return MimeTypes.AUDIO_VORBIS;
            case "opus":
                return MimeTypes.AUDIO_OPUS;
            case "flac":
                return MimeTypes.AUDIO_FLAC;
            case "alac":
                return MimeTypes.AUDIO_ALAC;
            case "amrnb":
                return MimeTypes.AUDIO_AMR_NB;
            case "amrwb":
                return MimeTypes.AUDIO_AMR_WB;

            // -- video ------------------------------------------------------
            case "h264":
                return MimeTypes.VIDEO_H264;
            case "hevc":
                return MimeTypes.VIDEO_H265;
            case "av1":
                return MimeTypes.VIDEO_AV1;
            case "vp8":
                return MimeTypes.VIDEO_VP8;
            case "vp9":
                return MimeTypes.VIDEO_VP9;
            case "mpeg2video":
                return MimeTypes.VIDEO_MPEG2;
            case "mpeg4":
                return MimeTypes.VIDEO_MP4V;
            case "vc1":
                return MimeTypes.VIDEO_VC1;

            // -- subtitles --------------------------------------------------
            case "subrip":
            case "srt":
                return MimeTypes.APPLICATION_SUBRIP;
            case "ass":
            case "ssa":
                return MimeTypes.TEXT_SSA;
            case "webvtt":
                return MimeTypes.TEXT_VTT;
            case "mov_text":
                return MimeTypes.APPLICATION_TX3G;
            case "hdmv_pgs_subtitle":
            case "pgs":
                return MimeTypes.APPLICATION_PGS;
            case "dvb_subtitle":
                return MimeTypes.APPLICATION_DVBSUBS;
            case "ttml":
                return MimeTypes.APPLICATION_TTML;
            default:
                return null;
        }
    }
}
