package com.brouken.player.mpv;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.brouken.player.BufferProfile;

import dev.jdtech.mpv.MPVLib;

public final class MpvOptions {

    @Nullable
    private final Uri uri;

    public MpvOptions(@Nullable final Uri uri) {
        this.uri = uri;
    }

    public void applyTo(final MPVLib mpv, final Context context) {
        // -- core performance and battery -----------------------------------

        mpv.setOptionString("osd-level", "0");
        mpv.setOptionString("osd-bar", "no");
        mpv.setOptionString("osd-on-seek", "no");
        mpv.setOptionString("input-default-bindings", "no");
        mpv.setOptionString("osc", "no");

        // mpv's lightweight profile: less work on weak devices, better battery
        // on strong ones.
        mpv.setOptionString("profile", "fast");

        // Hardware decoding where the device supports it, falling back on its
        // own when it does not.
        final int priority = Integer.parseInt(PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString("decoderPriority", "1"));
        final String hwdec;
        switch (priority) {
            case 2:  // prefer app decoders
                hwdec = "no";
                break;
            case 0:  // device decoders only
                hwdec = "mediacodec";
                break;
            case 1:
            default:
                hwdec = "auto-safe";
                break;
        }
        mpv.setOptionString("hwdec", hwdec);
        mpv.setOptionString("vd-lavc-threads", "0");

        mpv.setOptionString("framedrop", "decoder");

        // Both are GPU-expensive and neither is worth it on the hardware this
        // engine exists to rescue.
        mpv.setOptionString("interpolation", "no");
        mpv.setOptionString("deband", "no");

        mpv.setOptionString("vo", "gpu");
        // Headroom for the volume boost setting; the player only goes above 100
        // when that is switched on.
        mpv.setOptionString("volume-max", "200");
        mpv.setOptionString("gpu-context", "android");
        mpv.setOptionString("ao", "audiotrack");

        // -- subtitles ------------------------------------------------------

        // The whole reason someone switches to this engine: real libass
        // rendering, with the file's own positioning and effects kept.
        mpv.setOptionString("sub-auto", "fuzzy");
        mpv.setOptionString("sub-file-paths", "Subs:subs:Subtitles:subtitles");
        mpv.setOptionString("sub-fix-timing", "yes");
        mpv.setOptionString("sub-ass-override", "scale");
        mpv.setOptionString("embeddedfonts", "yes");

        final String subtitleLanguage = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString("subtitleLanguage", "en");
        if (subtitleLanguage != null && !subtitleLanguage.isEmpty()) {
            mpv.setOptionString("slang", subtitleLanguage);
        }

        // -- network --------------------------------------------------------

        mpv.setOptionString("network-timeout", "30");
        mpv.setOptionString("stream-lavf-o-append", "reconnect=1");
        mpv.setOptionString("stream-lavf-o-append", "reconnect_streamed=1");
        mpv.setOptionString("stream-lavf-o-append", "reconnect_delay_max=30");

        // -- cache ----------------------------------------------------------

        applyBuffering(mpv, context);
    }

    private void applyBuffering(final MPVLib mpv, final Context context) {
        final boolean adaptive = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("adaptiveBuffering", true);

        mpv.setOptionString("cache", "auto");
        mpv.setOptionString("cache-on-disk", "no");
        mpv.setOptionString("demuxer-donate-buffer", "yes");
        mpv.setOptionString("cache-pause", "yes");
        mpv.setOptionString("cache-pause-initial", "no");

        if (!adaptive) {
            // The conf's own safe fallback, which is what the file uses before
            // any profile is chosen.
            mpv.setOptionString("demuxer-max-bytes", "256MiB");
            mpv.setOptionString("demuxer-max-back-bytes", "64MiB");
            mpv.setOptionString("cache-pause-wait", "3");
            return;
        }

        final BufferProfile.Tier tier = BufferProfile.tierFor(context, uri);

        final String forward;
        final String backward;
        final String pauseWait;

        switch (tier) {
            case HIGH:
                forward = "768MiB";
                backward = "192MiB";
                pauseWait = "4";
                break;
            case BALANCED:
                forward = "512MiB";
                backward = "128MiB";
                pauseWait = "3";
                break;
            case LIVE:
                // Small on purpose: buffering ahead of a live stream is just
                // latency behind the broadcast.
                forward = "64MiB";
                backward = "16MiB";
                pauseWait = "1";
                mpv.setOptionString("cache-secs", "10");
                break;
            case BATTERY_SAVER:
            case LOW:
            default:
                forward = "256MiB";
                backward = "64MiB";
                pauseWait = "2";
                break;
        }

        mpv.setOptionString("demuxer-max-bytes", forward);
        mpv.setOptionString("demuxer-max-back-bytes", backward);
        mpv.setOptionString("cache-pause-wait", pauseWait);
    }
}
