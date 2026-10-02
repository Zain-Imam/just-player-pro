package com.brouken.player;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

// checks the decoders against a stream's size, hardware support and bitrate
// an overloaded decoder stops producing frames without reporting an error
public final class Capability {

    public enum Verdict {
        FINE,
        // software only, or past the hardware decoder's bitrate
        HARD_WORK,
        IMPOSSIBLE
    }

    private Capability() {
    }

    // width and height in pixels, bitrate in bits per second; 0 means unknown
    public static Verdict check(@Nullable final String mimeType, final int width, final int height,
                                final int bitrate) {
        if (mimeType == null || width <= 0 || height <= 0) {
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
                // software decodes almost anything, slowly
                continue;
            }
            if (bitrate > 0 && !video.getBitrateRange().contains(bitrate)) {
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

    // both engines share the hardware decoders; only mpv's FFmpeg software path
    // can do better, so the offer only goes from Media3 to mpv
    public static boolean otherEngineMightDoBetter(final Verdict verdict, final boolean onMpv) {
        if (verdict == Verdict.FINE) {
            return false;
        }
        if (onMpv) {
            return false;
        }
        return com.brouken.player.mpv.MpvPlayer.isSupported();
    }

    // size only: performance points and areSizeAndRateSupported reject 1080p60
    // on devices that play it fine
    private static boolean supportsSize(final MediaCodecInfo.VideoCapabilities video,
                                        final int width, final int height) {
        try {
            return video.isSizeSupported(width, height);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isHardware(final MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) {
            return info.isHardwareAccelerated();
        }
        // before API 29, software decoders are known by name
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
            Utils.log("The decoder list could not be read: " + e);
        }
        return found;
    }
}
