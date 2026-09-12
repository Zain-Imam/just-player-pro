package com.brouken.player;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import java.util.Locale;

/*
 * One way of naming a track, wherever it is shown.
 *
 * The track pickers had been printing whatever was easy to reach: a language on
 * its own for subtitles, a language and a channel count for audio. On a file
 * with two English audio tracks — one stereo, one 5.1 — that is two identical
 * rows and a guess. Everything the container knows goes in the second line
 * instead, in a fixed order, so two tracks that differ always look different.
 *
 * It is shared so that the two engines cannot drift apart: whatever either of
 * them reports about a track is described here by the same code.
 */
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

        // The language, when the title was a name rather than the language.
        if (format.label != null && !format.label.isEmpty()
                && format.language != null && !format.language.isEmpty()
                && !C.LANGUAGE_UNDETERMINED.equals(format.language)) {
            append(line, new Locale(format.language).getDisplayLanguage());
        }

        if (format.width > 0 && format.height > 0) {
            append(line, format.width + "×" + format.height);
        }
        if (format.channelCount > 0) {
            append(line, channels(context, format.channelCount));
        }
        append(line, codec(format));
        if (format.sampleRate > 0) {
            append(line, (Math.round(format.sampleRate / 100f) / 10f) + " kHz");
        }
        if (format.bitrate != Format.NO_VALUE && format.bitrate > 0) {
            append(line, (format.bitrate / 1000) + " kb/s");
        }

        if ((format.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) {
            append(line, context.getString(R.string.track_forced));
        }
        if ((format.roleFlags & C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) {
            append(line, context.getString(R.string.track_hearing_impaired));
        }
        if ((format.roleFlags & C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) {
            append(line, context.getString(R.string.track_visual_impaired));
        }

        return line.length() == 0 ? null : line.toString();
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

    /*
     * A channel count people recognise. Nobody calls a soundtrack six channel,
     * and 5.1 and 7.1 are what is written on the box.
     */
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
        String name = fromMime(format.sampleMimeType);
        if (name == null) {
            name = fromMime(format.codecs);
        }
        if (name != null) {
            return name;
        }
        // Neither spelling was one this knows. The raw codec name is still more
        // use than an audio/unknown placeholder, which is no use at all.
        if (format.codecs != null && !format.codecs.isEmpty()) {
            return format.codecs.toUpperCase(Locale.US);
        }
        if (format.sampleMimeType != null && !format.sampleMimeType.endsWith("/unknown")) {
            return format.sampleMimeType;
        }
        return null;
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
                return "E-AC-3-JOC";
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
        }
        return null;
    }
}
