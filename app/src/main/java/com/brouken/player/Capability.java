package com.brouken.player;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether this device can actually play what it has just been handed.
 *
 * Asked because of a 4K film on a television: the sound played, the picture
 * never appeared, and the only way out was to kill the app from the recents
 * list. Nothing had crashed -- the decoder simply could not keep up, and a
 * decoder that cannot keep up does not say so, it just stops producing frames.
 *
 * The device is asked rather than guessed at. Every decoder Android will admit
 * to is read for what it claims: the sizes it will decode, whether
 * it is the chip or a library pretending to be one, and the bitrate it will
 * take. A file past those numbers is a file this device is going to struggle
 * with, whatever its marketing said about 4K.
 *
 * Nothing here blocks anything. It answers a question, and the player asks the
 * viewer what to do about the answer.
 */
public final class Capability {

    public enum Verdict {
        /** A hardware decoder covers it. Say nothing. */
        FINE,
        /** It will play, but on software, or past what the chip will take. */
        HARD_WORK,
        /** Nothing on this device claims to decode it at all. */
        IMPOSSIBLE
    }

    private Capability() {
    }

    /**
     * What this device makes of a stream.
     *
     * @param mimeType   the video mime type, e.g. video/hevc
     * @param width      in pixels, 0 if unknown
     * @param height     in pixels, 0 if unknown
     * @param bitrate    bits a second, 0 if unknown
     */
    public static Verdict check(@Nullable final String mimeType, final int width, final int height,
                                final int bitrate) {
        if (mimeType == null || width <= 0 || height <= 0) {
            // Nothing to judge. Silence is the only honest answer.
            return Verdict.FINE;
        }

        boolean anyDecoder = false;
        boolean anyComfortable = false;

        for (final MediaCodecInfo info : decoders()) {
            final MediaCodecInfo.CodecCapabilities capabilities = capabilitiesFor(info, mimeType);
            if (capabilities == null) {
                continue;
            }
            final MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
            if (video == null) {
                continue;
            }
            if (!supportsSize(video, width, height)) {
                continue;
            }
            anyDecoder = true;

            if (!isHardware(info)) {
                // Software will decode almost anything, slowly. It is not a
                // reason to say the device can manage.
                continue;
            }
            if (bitrate > 0 && !video.getBitrateRange().contains(bitrate)) {
                // The picture is more data a second than this decoder takes.
                // This is the 50GB film: the size is fine, the torrent of bytes
                // is not.
                continue;
            }
            anyComfortable = true;
            break;
        }

        if (anyComfortable) {
            return Verdict.FINE;
        }
        return anyDecoder ? Verdict.HARD_WORK : Verdict.IMPOSSIBLE;
    }

    /**
     * Whether the engine not currently playing has a real chance of doing better.
     *
     * Both engines decode through the same chip, so when the hardware is the
     * problem, switching is a way of watching the same thing fail twice. The
     * one honest difference is in software: mpv's decoder is FFmpeg's, threaded
     * across every core and willing to drop frames to keep time, which is a
     * better bet for a file the chip has refused than Media3's software path.
     *
     * So the offer is made in one direction only, and only when software is
     * what is left.
     */
    public static boolean otherEngineMightDoBetter(final Verdict verdict, final boolean onMpv) {
        if (verdict == Verdict.FINE) {
            return false;
        }
        if (onMpv) {
            return false;
        }
        return com.brouken.player.mpv.MpvPlayer.isSupported();
    }

    /*
     * The size, and not the frame rate.
     *
     * Two stricter measures were tried against a real device and both cried
     * wolf. The performance points a decoder advertises -- the list where a
     * chip says "3840x2160 at 60 and no faster" -- do not cover 1080p60 on this
     * phone, which plays 1080p60 all day. areSizeAndRateSupported, asked the
     * same question, says no as well: the declared maximum rate at that size is
     * simply lower than what the chip actually manages.
     *
     * A warning that fires on files which play perfectly teaches people to
     * dismiss it, and then it is not there for the file that matters. So what
     * is asked is what the device is reliably honest about: the size it will
     * decode, whether the decoder is the chip or a library pretending to be
     * one, and the bitrate it will take. Those three catch the cases this
     * exists for -- 4K on a decoder that tops out at 1080p, a codec with no
     * hardware behind it at all, and the 50GB film whose torrent of bytes is
     * past what the decoder will accept.
     */
    private static boolean supportsSize(final MediaCodecInfo.VideoCapabilities video,
                                        final int width, final int height) {
        try {
            return video.isSizeSupported(width, height);
        } catch (IllegalArgumentException e) {
            // A codec that will not even discuss the size does not support it.
            return false;
        }
    }

    private static boolean isHardware(final MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) {
            return info.isHardwareAccelerated();
        }
        // Before that it is read off the name, which is how the platform's own
        // code did it: OMX.google.* and c2.android.* are the software ones.
        final String name = info.getName().toLowerCase(java.util.Locale.ROOT);
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
                && !name.startsWith("android.");
    }

    @Nullable
    private static MediaCodecInfo.CodecCapabilities capabilitiesFor(final MediaCodecInfo info,
                                                                    final String mimeType) {
        try {
            return info.getCapabilitiesForType(mimeType);
        } catch (IllegalArgumentException e) {
            // This decoder does not handle this type at all.
            return null;
        }
    }

    private static List<MediaCodecInfo> decoders() {
        final List<MediaCodecInfo> found = new ArrayList<>();
        try {
            for (final MediaCodecInfo info
                    : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) {
                    found.add(info);
                }
            }
        } catch (Exception e) {
            // A device that will not list its decoders is a device nothing can
            // be said about.
            Utils.log("The decoder list could not be read: " + e);
        }
        return found;
    }
}
