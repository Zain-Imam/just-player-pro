package com.brouken.player;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import java.util.Locale;

// shared by both engines so they name tracks the same way
public final class TrackNames {

    private TrackNames() {
    }

    public static String title(final Context context, final Format format,
                               final int index, final int trackType) {
        if (format.label != null && !format.label.isEmpty()) {
            return format.label;
        }
        if (format.language != null && !format.language.isEmpty()
                && !C.LANGUAGE_UNDETERMINED.equals(format.language)) {
            final String name = new Locale(format.language).getDisplayLanguage();
            if (!name.isEmpty() && !name.equalsIgnoreCase(format.language)) {
                return name;
            }
        }
        return context.getString(trackType == C.TRACK_TYPE_AUDIO
                ? R.string.audio_menu_track : R.string.subtitle_menu_track, index + 1);
    }

    @Nullable
    public static String detail(final Context context, final Format format,
                                final boolean selected) {
        final StringBuilder line = new StringBuilder();
        if (selected) {
            append(line, context.getString(R.string.subtitle_menu_current));
        }

        if (format.label != null && !format.label.isEmpty()
                && format.language != null && !format.language.isEmpty()
                && !C.LANGUAGE_UNDETERMINED.equals(format.language)) {
            final String languageName = new Locale(format.language).getDisplayLanguage();
            if (!languageName.equalsIgnoreCase(format.label)) {
                append(line, languageName);
            }
        }

        if (format.width > 0 && format.height > 0) {
            append(line, format.width + "×" + format.height);
        }
        if (format.channelCount > 0) {
            append(line, channels(context, format.channelCount));
        }
        append(line, codec(format));
        if (format.sampleRate > 0) {
            // the decimal mark of the app's language: 44,1 kHz in Turkish
            append(line, String.format(Locale.getDefault(), "%.1f %s", Math.round(format.sampleRate / 100f) / 10f,
                    context.getString(R.string.unit_kilohertz)));
        }
        if (format.bitrate != Format.NO_VALUE && format.bitrate > 0) {
            append(line, (format.bitrate / 1000) + " " + context.getString(R.string.unit_kilobits_per_second));
        }

        if ((format.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) {
            append(line, context.getString(R.string.track_forced));
        }
        if (hardOfHearing(format)) {
            append(line, context.getString(R.string.track_hearing_impaired));
        }
        if ((format.roleFlags & C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) {
            append(line, context.getString(R.string.track_visual_impaired));
        }

        return line.length() == 0 ? null : line.toString();
    }

    private static final java.util.regex.Pattern SDH =
            java.util.regex.Pattern.compile("(?i)\\b(sdh|cc|hearing[ -]impaired|hi)\\b");

    // the name too: Media3 reads no such flag from Matroska, where mpv does
    static boolean hardOfHearing(final Format format) {
        return (format.roleFlags & C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0
                || (format.label != null && SDH.matcher(format.label).find());
    }

    private static void append(final StringBuilder line, @Nullable final String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (line.length() > 0) {
            line.append("  ·  ");
        }
        line.append(part);
    }

    private static String channels(final Context context, final int count) {
        switch (count) {
            case 1:
                return context.getString(R.string.track_mono);
            case 2:
                return context.getString(R.string.track_stereo);
            case 6:
                return "5.1";
            case 8:
                return "7.1";
            default:
                return count + "ch";
        }
    }

    @Nullable
    public static String codec(final Format format) {
        final String codecs = format.codecs == null ? "" : format.codecs;
        // the picture under Dolby Vision; dynamicRange names the Dolby Vision itself
        if (MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) {
            final String c = codecs.toLowerCase(Locale.US);
            return c.startsWith("dvav") || c.startsWith("dva1") ? "H.264"
                    : c.startsWith("dav1") ? "AV1" : "HEVC";
        }
        String name = fromMime(format.sampleMimeType);
        if (name == null) {
            name = fromMime(format.codecs);
        }
        if (name != null) {
            return refined(name, codecs);
        }
        // a raw codec string, but never an audio/unknown placeholder
        if (!codecs.isEmpty()) {
            return rawName(codecs);
        }
        if (format.sampleMimeType != null && !format.sampleMimeType.contains("unknown")) {
            return format.sampleMimeType;
        }
        return null;
    }

    // on mpv the codec string is the profile FFmpeg reports
    private static String refined(final String name, final String codecs) {
        final String c = codecs.toLowerCase(Locale.US);
        switch (name) {
            case "AAC":
                if (c.contains("mp4a.40.29") || c.contains("he-aacv2")) {
                    return "HE-AACv2";
                }
                if (c.contains("mp4a.40.5") || c.contains("he-aac")) {
                    return "HE-AAC";
                }
                return name;
            case "E-AC-3":
            case "TrueHD":
                return c.contains("atmos") ? name + " Atmos" : name;
            case "DTS":
            case "DTS-HD":
                if (c.contains("dts:x")) {
                    return "DTS:X";
                }
                if (c.contains("dts-hd ma")) {
                    return "DTS-HD MA";
                }
                if (c.contains("dts-hd hra")) {
                    return "DTS-HD HRA";
                }
                if (c.contains("express")) {
                    return "DTS Express";
                }
                return name;
            default:
                return name;
        }
    }

    // FFmpeg's names for what Media3 has no type for
    private static String rawName(final String codecs) {
        final String c = codecs.toLowerCase(Locale.US);
        if (c.startsWith("pcm_")) {
            return "PCM";
        }
        if (c.startsWith("adpcm")) {
            return "ADPCM";
        }
        if (c.startsWith("wmav") || c.equals("wmapro") || c.equals("wmalossless")) {
            return "WMA";
        }
        if (c.startsWith("wmv")) {
            return "WMV";
        }
        if (c.startsWith("msmpeg4")) {
            return "MPEG-4";
        }
        switch (c) {
            case "prores":
                return "ProRes";
            case "h263":
                return "H.263";
            case "vvc":
                return "VVC";
            case "mjpeg":
                return "MJPEG";
            case "mpeg1video":
                return "MPEG-1";
            case "theora":
                return "Theora";
            case "dnxhd":
                return "DNxHD";
            case "dvvideo":
                return "DV";
            case "cook":
                return "RealAudio";
            case "speex":
                return "Speex";
            default:
                return codecs.toUpperCase(Locale.US);
        }
    }

    // on mpv a Dolby Vision track shows only in its codec string
    @Nullable
    public static String dynamicRange(final Format video) {
        final String c = video.codecs == null ? "" : video.codecs.toLowerCase(Locale.US);
        if (MimeTypes.VIDEO_DOLBY_VISION.equals(video.sampleMimeType)
                || c.startsWith("dvhe") || c.startsWith("dvh1") || c.startsWith("dvav")
                || c.startsWith("dva1") || c.startsWith("dav1")) {
            return "Dolby Vision";
        }
        if (video.colorInfo != null) {
            if (video.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084) {
                return "HDR10";
            }
            if (video.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG) {
                return "HLG";
            }
        }
        return null;
    }

    // MediaFormat.COLOR_TRANSFER_* from the decoder; Media3 misses it in many MKVs
    @Nullable
    public static String dynamicRange(final int decoderTransfer) {
        switch (decoderTransfer) {
            case 6: // MediaFormat.COLOR_TRANSFER_ST2084
                return "HDR10";
            case 7: // MediaFormat.COLOR_TRANSFER_HLG
                return "HLG";
            default:
                return null;
        }
    }

    @Nullable
    public static String channelLayout(final int count) {
        switch (count) {
            case 1:
                return "Mono";
            case 2:
                return "2.0";
            case 3:
                return "2.1";
            case 6:
                return "5.1";
            case 7:
                return "6.1";
            case 8:
                return "7.1";
            default:
                return count > 0 ? count + "ch" : null;
        }
    }

    public static String frameRate(final float fps) {
        return new java.text.DecimalFormat("0.###",
                java.text.DecimalFormatSymbols.getInstance(Locale.US)).format(fps) + " fps";
    }

    @Nullable
    public static String fromMime(@Nullable final String mimeType) {
        if (mimeType == null) {
            return null;
        }
        switch (mimeType) {
            case MimeTypes.AUDIO_DTS:
                return "DTS";
            case MimeTypes.AUDIO_DTS_HD:
                return "DTS-HD";
            case MimeTypes.AUDIO_DTS_EXPRESS:
                return "DTS Express";
            case MimeTypes.AUDIO_TRUEHD:
                return "TrueHD";
            case MimeTypes.AUDIO_AC3:
                return "AC-3";
            case MimeTypes.AUDIO_E_AC3:
                return "E-AC-3";
            case MimeTypes.AUDIO_E_AC3_JOC:
                return "E-AC-3 Atmos";
            case MimeTypes.AUDIO_DTS_X:
                return "DTS:X";
            case MimeTypes.AUDIO_RAW:
                return "PCM";
            case MimeTypes.AUDIO_AC4:
                return "AC-4";
            case MimeTypes.AUDIO_AAC:
                return "AAC";
            case MimeTypes.AUDIO_MPEG:
                return "MP3";
            case MimeTypes.AUDIO_MPEG_L2:
                return "MP2";
            case MimeTypes.AUDIO_VORBIS:
                return "Vorbis";
            case MimeTypes.AUDIO_OPUS:
                return "Opus";
            case MimeTypes.AUDIO_FLAC:
                return "FLAC";
            case MimeTypes.AUDIO_ALAC:
                return "ALAC";
            case MimeTypes.AUDIO_WAV:
                return "WAV";
            case MimeTypes.AUDIO_AMR:
                return "AMR";
            case MimeTypes.AUDIO_AMR_NB:
                return "AMR-NB";
            case MimeTypes.AUDIO_AMR_WB:
                return "AMR-WB";
            case MimeTypes.AUDIO_IAMF:
                return "IAMF";
            case MimeTypes.AUDIO_MPEGH_MHA1:
            case MimeTypes.AUDIO_MPEGH_MHM1:
                return "MPEG-H";

            case MimeTypes.VIDEO_H264:
                return "H.264";
            case MimeTypes.VIDEO_H265:
                return "HEVC";
            case MimeTypes.VIDEO_AV1:
                return "AV1";
            case MimeTypes.VIDEO_VP8:
                return "VP8";
            case MimeTypes.VIDEO_VP9:
                return "VP9";
            case MimeTypes.VIDEO_MPEG2:
                return "MPEG-2";
            case MimeTypes.VIDEO_MP4V:
                return "MPEG-4";
            case MimeTypes.VIDEO_VC1:
                return "VC-1";
            case MimeTypes.VIDEO_H263:
                return "H.263";
            case MimeTypes.VIDEO_H266:
                return "VVC";
            case MimeTypes.VIDEO_DIVX:
                return "DivX";
            case MimeTypes.VIDEO_MJPEG:
                return "MJPEG";

            case MimeTypes.APPLICATION_PGS:
                return "PGS";
            case MimeTypes.APPLICATION_SUBRIP:
                return "SRT";
            case MimeTypes.TEXT_SSA:
                return "SSA";
            case MimeTypes.TEXT_VTT:
                return "VTT";
            case MimeTypes.APPLICATION_TTML:
                return "TTML";
            case MimeTypes.APPLICATION_TX3G:
                return "TX3G";
            case MimeTypes.APPLICATION_DVBSUBS:
                return "DVB";
            case com.brouken.player.subtitle.parser.SamiParser.MIME:
                return "SAMI";
        }
        return null;
    }
}
